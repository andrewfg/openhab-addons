/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.homekit.internal.camera.go2rtc;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Function;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homekit.internal.camera.codec.AudioCodec;
import org.openhab.binding.homekit.internal.camera.codec.VideoCodec;
import org.openhab.binding.homekit.internal.camera.ffmpeg.FfmpegRtpTranscoder;
import org.openhab.binding.homekit.internal.camera.session.RtspConnection;
import org.openhab.binding.homekit.internal.camera.session.SrtpParameters;
import org.openhab.binding.homekit.internal.camera.session.SrtpSession;
import org.openhab.binding.homekit.internal.enums.AudioCodecType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relays media between the camera and go2rtc over RTSP.
 * <p>
 * Camera forward path audio and video arrive as SRTP on UDP ports advertised in the SetupEndpoints request.
 * They are decrypted and published as interleaved RTP to go2rtc's listen stream. AAC-ELD audio is first transcoded to
 * Opus by ffmpeg, because go2rtc only accepts Opus.
 * <p>
 * The talk path is read as interleaved RTP from go2rtc's talk stream, re-labelled with the talk payload type and
 * sync source, encrypted and sent to the camera's talk port advertised in the SetupEndpoints response. When the
 * camera's audio codec is AAC-ELD, the talk audio is first transcoded from Opus to AAC-ELD by ffmpeg.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Go2RtcDataPump implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Go2RtcDataPump.class);

    private static final String RTSP_HOST = "127.0.0.1";
    private static final int MAX_DATAGRAM_SIZE = 2048;
    private static final int RTP_HEADER_LENGTH = 12;
    private static final int SRTP_AUTH_TAG_LENGTH = 10;
    private static final int TALK_RETRY_DELAY_MS = 2000;
    private static final int PUBLISH_TIMEOUT_SECONDS = 60;

    public static final int AUDIO_PAYLOAD_TYPE = 110;
    public static final int VIDEO_PAYLOAD_TYPE = 99;

    // sync sources are uint which Java doesn't have so we use 4 byte long (8 hex digits)
    public static final long AUDIO_SYNC_SOURCE = 0x12345678L;
    public static final long VIDEO_SYNC_SOURCE = 0x23456789L;

    // interleaved RTP channels; the RTCP channel is always the RTP channel plus one.
    private static final int AUDIO_CHANNEL = 0;
    private static final int VIDEO_CHANNEL = 2;
    private static final int TALK_CHANNEL = 0;

    /**
     * Opus RTP timestamps always use a 48 kHz clock regardless of the output sample rate (RFC 7587).
     */
    private static final String ANNOUNCE_SDP = """
            v=0
            o=- 0 0 IN IP4 127.0.0.1
            s=HomeKit
            c=IN IP4 127.0.0.1
            t=0 0
            m=audio 0 RTP/AVP %d
            a=rtpmap:%d opus/48000/2
            a=control:trackID=0
            m=video 0 RTP/AVP %d
            a=rtpmap:%d H264/90000
            a=fmtp:%d packetization-mode=1
            a=control:trackID=1
            """.formatted(AUDIO_PAYLOAD_TYPE, AUDIO_PAYLOAD_TYPE, VIDEO_PAYLOAD_TYPE, VIDEO_PAYLOAD_TYPE,
            VIDEO_PAYLOAD_TYPE);

    /**
     * The SRTP contexts of one camera session. Each context is used only by its own relay thread.
     */
    public record Session(SrtpSession audioSession, SrtpSession videoSession, SrtpSession talkSession,
            InetSocketAddress talkAddress) {
    }

    private final AudioCodec audioCodec;
    private final VideoCodec videoCodec;
    private final @Nullable String ffmpegBinary;
    private final DatagramSocket audioSocket;
    private final DatagramSocket videoSocket;
    private final DatagramSocket talkSocket;

    private volatile @Nullable Session session = null;
    private volatile @Nullable RtspConnection publisher = null;
    private volatile @Nullable String publishUrl = null;
    private volatile @Nullable RtspConnection talkReader = null;
    private volatile @Nullable FfmpegRtpTranscoder audioTranscoder = null;
    private volatile boolean closed;
    private final Object talkMonitor = new Object();
    private boolean talkActive; // guarded by talkMonitor

    /**
     * Creates a data pump for the camera's audio and video. The camera must support Opus or AAC-ELD audio and H.264
     * video.
     *
     * @param audioCodecs the audio codecs the camera supports
     * @param videoCodecs the video configurations the camera supports
     * @param ffmpegBinary the ffmpeg executable, required only when the camera's audio is AAC-ELD; otherwise may be
     *            null
     * @throws IllegalArgumentException if the camera does not support Opus or AAC-ELD audio or H.264 video, or if
     *             AAC-ELD is used without an ffmpeg executable
     * @throws IOException if the sockets cannot be created
     */
    public Go2RtcDataPump(List<AudioCodec> audioCodecs, List<VideoCodec> videoCodecs, @Nullable String ffmpegBinary)
            throws IOException {
        audioCodec = AudioCodec.preferred(audioCodecs);
        videoCodec = VideoCodec.preferred(videoCodecs);
        if (audioCodec.codecType() == AudioCodecType.AAC_ELD && ffmpegBinary == null) {
            throw new IllegalArgumentException("ffmpeg is required to transcode AAC-ELD audio");
        }
        this.ffmpegBinary = ffmpegBinary;

        DatagramSocket audio = new DatagramSocket(0);
        DatagramSocket video = null;
        DatagramSocket talk = null;
        try {
            video = new DatagramSocket(0);
            talk = new DatagramSocket(0);
        } catch (IOException | RuntimeException e) {
            audio.close();
            closeQuietly(video, talk);
            throw e;
        }

        audioSocket = audio;
        videoSocket = video;
        talkSocket = talk;

        startThread("audio-rtp", () -> relayListen(audioSocket, AUDIO_CHANNEL, Session::audioSession));
        startThread("video-rtp", () -> relayListen(videoSocket, VIDEO_CHANNEL, Session::videoSession));
    }

    /**
     * @return the camera's audio codec
     */
    public AudioCodec audioCodec() {
        return audioCodec;
    }

    /**
     * @return the camera's video codec
     */
    public VideoCodec videoCodec() {
        return videoCodec;
    }

    /**
     * @return the UDP port that receives the camera's audio SRTP
     */
    public int audioPort() {
        return audioSocket.getLocalPort();
    }

    /**
     * @return the UDP port that receives the camera's video SRTP
     */
    public int videoPort() {
        return videoSocket.getLocalPort();
    }

    /**
     * Publishes the camera media to go2rtc and starts relaying the talk stream to the camera. The talk stream is
     * retried until go2rtc has it available.
     *
     * @param rtspPort the go2rtc RTSP port on the loopback interface
     * @param listenStream the name of the go2rtc stream that receives the camera media
     * @param talkStream the name of the go2rtc stream that carries the browser talk audio
     * @throws IOException if go2rtc refuses the publish
     * @throws IllegalStateException if already connected or closed
     */
    public synchronized void connect(int rtspPort, String listenStream, String talkStream) throws IOException {
        if (closed || publisher != null) {
            throw new IllegalStateException("data pump is already connected or closed");
        }
        RtspConnection connection = new RtspConnection(RTSP_HOST, rtspPort);
        String url = url(rtspPort, listenStream);
        try {
            publish(connection, url);
        } catch (IOException | RuntimeException e) {
            connection.close();
            throw e;
        }
        publishUrl = url;
        publisher = connection;
        startThread("talk-rtp", () -> relayTalk(rtspPort, talkStream));
    }

    /**
     * Creates the AAC-ELD to Opus transcoder when the camera's audio needs one, forwarding its output to the publisher.
     */
    private @Nullable FfmpegRtpTranscoder createAudioTranscoder(RtspConnection connection) throws IOException {
        String ffmpeg = ffmpegBinary;
        if (audioCodec.codecType() != AudioCodecType.AAC_ELD || ffmpeg == null) {
            return null;
        }
        return FfmpegRtpTranscoder.aacEldToOpus(ffmpeg, audioCodec, AUDIO_PAYLOAD_TYPE,
                (data, length) -> connection.sendFrame(AUDIO_CHANNEL, data, length));
    }

    /**
     * Starts relaying media for a camera session, replacing any session already active.
     *
     * @param audioSrtpParameters the SRTP parameters the camera uses to encrypt its audio
     * @param videoSrtpParameters the SRTP parameters the camera uses to encrypt its video
     * @param talkParameters the SRTP parameters the binding uses to encrypt talk audio towards the camera
     * @param remoteHost the camera's IP address
     * @param remoteTalkPort the camera's audio port from the SetupEndpoints response
     * @return the new session
     * @throws IllegalArgumentException if a crypto suite is NONE
     */
    public synchronized Session startSession(SrtpParameters audioParameters, SrtpParameters videoParameters,
            SrtpParameters talkParameters, String remoteHost, int remoteTalkPort) {
        Session newSession = new Session(SrtpSession.create(audioParameters), SrtpSession.create(videoParameters),
                SrtpSession.create(talkParameters), new InetSocketAddress(remoteHost, remoteTalkPort));
        session = newSession;
        startAudioTranscoder();
        return newSession;
    }

    /**
     * Stops relaying media. Packets received afterwards are dropped, and the browser talk relay is stopped.
     */
    public synchronized void stopSession() {
        LOGGER.debug("Stopping go2rtc relay session: publisherActive={}", publisher != null);
        session = null;
        stopAudioTranscoder();
        stopTalk();
    }

    /**
     * Starts a fresh AAC-ELD transcoder for the session, since ffmpeg exits when its input is idle.
     */
    private void startAudioTranscoder() {
        stopAudioTranscoder();
        RtspConnection connection = publisher;
        if (connection == null) {
            return;
        }
        try {
            audioTranscoder = createAudioTranscoder(connection);
        } catch (IOException e) {
            LOGGER.warn("Failed to start audio transcoder: {}", e.getMessage());
        }
    }

    private void stopAudioTranscoder() {
        FfmpegRtpTranscoder transcoder = audioTranscoder;
        audioTranscoder = null;
        if (transcoder != null) {
            transcoder.close();
        }
    }

    /**
     * Sends an RTSP OPTIONS request on the publisher connection. go2rtc drops an idle publisher after its timeout, and
     * each request it receives resets that timeout. The response is read here, so it does not accumulate.
     *
     * @throws IOException if the publisher connection fails
     */
    public void keepAlive() throws IOException {
        RtspConnection connection = publisher;
        String url = publishUrl;
        if (connection != null && url != null) {
            connection.request("OPTIONS", url, "", null);
        }
    }

    /**
     * Starts reading the browser talk stream from go2rtc and relaying it to the camera. Called when a browser talk
     * session begins; until then the talk relay stays idle and does not poll go2rtc.
     */
    public void startTalk() {
        synchronized (talkMonitor) {
            if (!talkActive) {
                LOGGER.debug("Talk-back session started");
            }
            talkActive = true;
            talkMonitor.notifyAll();
        }
    }

    /**
     * Stops relaying the browser talk stream, closing the go2rtc talk reader.
     */
    public void stopTalk() {
        synchronized (talkMonitor) {
            if (talkActive) {
                LOGGER.debug("Talk-back session stopped");
            }
            talkActive = false;
            talkMonitor.notifyAll();
        }
        RtspConnection reader = talkReader;
        if (reader != null) {
            reader.close();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        session = null;
        audioSocket.close();
        videoSocket.close();
        talkSocket.close();
        FfmpegRtpTranscoder transcoder = audioTranscoder;
        if (transcoder != null) {
            transcoder.close();
        }
        RtspConnection connection = publisher;
        if (connection != null) {
            connection.close();
        }
        stopTalk();
    }

    /**
     * Decrypts camera audio or video SRTP and publishes it as interleaved RTP to go2rtc. AAC-ELD audio is passed to the
     * transcoder instead, which publishes Opus.
     */
    private void relayListen(DatagramSocket socket, int channel, Function<Session, SrtpSession> stream) {
        byte[] srtpBuffer = new byte[MAX_DATAGRAM_SIZE];
        byte[] rtpBuffer = new byte[MAX_DATAGRAM_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(srtpBuffer, srtpBuffer.length);
        while (!closed) {
            try {
                receivePacket.setLength(srtpBuffer.length);
                socket.receive(receivePacket);
                Session current = session;
                RtspConnection connection = publisher;
                if (current != null && connection != null) {
                    int rtpLength = stream.apply(current).decrypt(srtpBuffer, receivePacket.getLength(), rtpBuffer);
                    if (rtpLength >= 0) {
                        FfmpegRtpTranscoder transcoder = audioTranscoder;
                        if (channel == AUDIO_CHANNEL && transcoder != null) {
                            transcoder.send(rtpBuffer, rtpLength);
                        } else {
                            connection.sendFrame(channel, rtpBuffer, rtpLength);
                        }
                    }
                }
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                LOGGER.debug(
                        "go2rtc RTP relay failed on channel {} ({}: {}) socketClosed={} sessionActive={} publisherActive={}",
                        channel, e.getClass().getSimpleName(), e.getMessage(), socket.isClosed(), session != null,
                        publisher != null);
            }
        }
    }

    /**
     * Reads the browser talk stream from go2rtc and sends it as SRTP to the camera. Idles until a talk session starts,
     * and reconnects after failures while the talk session remains active.
     */
    private void relayTalk(int rtspPort, String talkStream) {
        byte[] rtpBuffer = new byte[MAX_DATAGRAM_SIZE];
        byte[] srtpBuffer = new byte[MAX_DATAGRAM_SIZE + SRTP_AUTH_TAG_LENGTH];
        while (awaitTalkActive()) {
            try {
                relayTalkSession(rtspPort, talkStream, rtpBuffer, srtpBuffer);
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                LOGGER.debug("Talk-back unavailable: {}", e.getMessage());
                if (!pauseBeforeTalkRetry()) {
                    return;
                }
            }
        }
    }

    /**
     * Relays one talk session, reconnecting to go2rtc while the session stays active. The talk transcoder, if any,
     * lives
     * for the whole session.
     */
    private void relayTalkSession(int rtspPort, String talkStream, byte[] rtpBuffer, byte[] srtpBuffer)
            throws IOException {
        FfmpegRtpTranscoder encoder = openTalkEncoder(srtpBuffer);
        try {
            while (!closed && isTalkActive()) {
                try (RtspConnection reader = openTalkReader(rtspPort, talkStream)) {
                    LOGGER.debug("Talk-back reading go2rtc stream {}", talkStream);
                    talkReader = reader;
                    while (!closed && isTalkActive()) {
                        RtspConnection.Frame frame = reader.readFrame(rtpBuffer);
                        if (frame.channel() != TALK_CHANNEL || frame.length() < RTP_HEADER_LENGTH) {
                            continue;
                        }
                        if (encoder != null) {
                            setPayloadType(rtpBuffer);
                            encoder.send(rtpBuffer, frame.length());
                        } else {
                            Session current = session;
                            if (current != null) {
                                sendTalk(current, rtpBuffer, frame.length(), srtpBuffer);
                            }
                        }
                    }
                } catch (IOException e) {
                    if (closed) {
                        return;
                    }
                    // a reader closed by stopTalk() is expected and not a failure
                    if (isTalkActive()) {
                        LOGGER.debug("go2rtc talk stream unavailable: {}", e.getMessage());
                        if (!pauseBeforeTalkRetry()) {
                            return;
                        }
                    }
                } finally {
                    talkReader = null;
                }
            }
        } finally {
            if (encoder != null) {
                encoder.close();
            }
        }
    }

    /**
     * Creates the Opus to AAC-ELD transcoder for a talk session. Returns null when the camera takes Opus directly.
     * Encoded packets are encrypted and sent to the camera while the session is active.
     */
    private @Nullable FfmpegRtpTranscoder openTalkEncoder(byte[] srtpBuffer) throws IOException {
        if (audioCodec.codecType() != AudioCodecType.AAC_ELD) {
            return null;
        }
        String ffmpeg = ffmpegBinary;
        if (ffmpeg == null) {
            throw new IOException("No ffmpeg binary available for AAC-ELD talk-back");
        }
        return FfmpegRtpTranscoder.opusToAacEld(ffmpeg, audioCodec, AUDIO_PAYLOAD_TYPE, (data, length) -> {
            Session current = session;
            if (current != null) {
                sendTalk(current, data, length, srtpBuffer);
            }
        });
    }

    /**
     * Blocks until a talk session is active or the data pump is closed.
     *
     * @return true if a talk session is active, false if the data pump is closed or the thread was interrupted
     */
    private boolean awaitTalkActive() {
        synchronized (talkMonitor) {
            while (!talkActive && !closed) {
                try {
                    talkMonitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return talkActive && !closed;
        }
    }

    private boolean isTalkActive() {
        synchronized (talkMonitor) {
            return talkActive;
        }
    }

    /**
     * @return false if the thread was interrupted
     */
    private static boolean pauseBeforeTalkRetry() {
        try {
            Thread.sleep(TALK_RETRY_DELAY_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Re-labels a talk RTP packet with the talk payload type and sync source, then encrypts and sends it to the camera.
     */
    private void sendTalk(Session current, byte[] rtpBuffer, int length, byte[] srtpBuffer) throws IOException {
        setPayloadType(rtpBuffer);
        // header bytes 8-11 contain SSRC in network byte order
        rtpBuffer[8] = (byte) (AUDIO_SYNC_SOURCE >>> 24);
        rtpBuffer[9] = (byte) (AUDIO_SYNC_SOURCE >>> 16);
        rtpBuffer[10] = (byte) (AUDIO_SYNC_SOURCE >>> 8);
        rtpBuffer[11] = (byte) AUDIO_SYNC_SOURCE;
        int srtpLength = current.talkSession().encrypt(rtpBuffer, length, srtpBuffer);
        talkSocket.send(new DatagramPacket(srtpBuffer, srtpLength, current.talkAddress()));
    }

    private static void setPayloadType(byte[] rtpBuffer) {
        // keep marker bit (bit 7) and replace payload type
        rtpBuffer[1] = (byte) ((rtpBuffer[1] & 0x80) | AUDIO_PAYLOAD_TYPE);
    }

    /**
     * Connects a reader to the go2rtc talk stream, and starts playback of its audio on interleaved channel 0.
     */
    private static RtspConnection openTalkReader(int rtspPort, String talkStream) throws IOException {
        String url = url(rtspPort, talkStream);
        RtspConnection reader = new RtspConnection(RTSP_HOST, rtspPort);
        try {
            String sdp = reader.request("DESCRIBE", url, "Accept: application/sdp\r\n", null).body();
            reader.request("SETUP", trackUrl(url, sdp), "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n", null);
            reader.request("PLAY", url, "", null);
            reader.disableReadTimeout();
            return reader;
        } catch (IOException | RuntimeException e) {
            reader.close();
            throw e;
        }
    }

    /**
     * Sends the publish sequence (ANNOUNCE, SETUP per track, RECORD) that makes go2rtc accept the camera media.
     */
    private static void publish(RtspConnection connection, String url) throws IOException {
        connection.request("ANNOUNCE", url + "?timeout=" + PUBLISH_TIMEOUT_SECONDS, "Content-Type: application/sdp\r\n",
                ANNOUNCE_SDP);
        connection.request("SETUP", url + "/trackID=0",
                "Transport: RTP/AVP/TCP;unicast;mode=record;interleaved=0-1\r\n", null);
        connection.request("SETUP", url + "/trackID=1",
                "Transport: RTP/AVP/TCP;unicast;mode=record;interleaved=2-3\r\n", null);
        connection.request("RECORD", url, "", null);
    }

    /**
     * Returns the URL of the first media track in the SDP, falling back to the stream URL.
     */
    private static String trackUrl(String url, String sdp) {
        boolean inMedia = false;
        for (String line : sdp.split("\r?\n")) {
            if (line.startsWith("m=")) {
                inMedia = true;
            } else if (inMedia && line.startsWith("a=control:")) {
                String control = line.substring("a=control:".length()).trim();
                return "*".equals(control) ? url : url + "/" + control;
            }
        }
        return url;
    }

    private static String url(int rtspPort, String stream) {
        return "rtsp://%s:%d/%s".formatted(RTSP_HOST, rtspPort, stream);
    }

    /**
     * Starts a daemon thread to run the given task.
     */
    private void startThread(String name, Runnable task) {
        Thread thread = new Thread(task, "OH-binding-homekit-go2rtc-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Closes the given sockets, ignoring any exception.
     */
    private static void closeQuietly(@Nullable DatagramSocket... sockets) {
        for (DatagramSocket socket : sockets) {
            if (socket != null) {
                socket.close();
            }
        }
    }

    @Override
    public String toString() {
        return "Go2RtcDataPump [session=" + session + ", closed=" + closed + ", audioCodec=" + audioCodec
                + ", videoCodec=" + videoCodec + ", audioPort=" + audioPort() + ", videoPort=" + videoPort() + "]";
    }
}
