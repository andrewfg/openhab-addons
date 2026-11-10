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
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Function;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homekit.internal.camera.AudioCodec;
import org.openhab.binding.homekit.internal.camera.VideoCodec;
import org.openhab.binding.homekit.internal.camera.srtp.SrtpParameters;
import org.openhab.binding.homekit.internal.camera.srtp.SrtpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relays media between the camera and go2rtc over RTSP.
 * <p>
 * Camera forward path audio and video arrive as SRTP on UDP ports advertised in the SetupEndpoints request.
 * They are decrypted and published as interleaved RTP to go2rtc's listen stream.
 * <p>
 * The talk path is read as interleaved RTP from go2rtc's talk stream, re-labelled with the talk payload type and
 * sync source, encrypted and sent to the camera's talk port advertised in the SetupEndpoints response.
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

    public static final int AUDIO_PAYLOAD_TYPE = 110;
    public static final int VIDEO_PAYLOAD_TYPE = 99;
    public static final long AUDIO_SYNC_SOURCE = 0x12345678L;

    /** Interleaved RTP channels; the RTCP channel is always the RTP channel plus one. */
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
    private final DatagramSocket audioSocket;
    private final DatagramSocket videoSocket;
    private final DatagramSocket talkSocket;

    private volatile @Nullable Session session = null;
    private volatile @Nullable RtspConnection publisher = null;
    private volatile @Nullable RtspConnection talkReader = null;
    private volatile boolean closed;

    /**
     * Creates a data pump for the camera's audio and video. The camera must support Opus audio and H.264 video.
     *
     * @param audioCodecs the audio codecs the camera supports
     * @param videoCodecs the video configurations the camera supports
     * @throws IllegalArgumentException if the camera does not support Opus audio or H.264 video
     * @throws IOException if the sockets cannot be created
     */
    public Go2RtcDataPump(List<AudioCodec> audioCodecs, List<VideoCodec> videoCodecs) throws IOException {
        audioCodec = AudioCodec.preferred(audioCodecs);
        videoCodec = VideoCodec.preferred(videoCodecs);

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
        try {
            publish(connection, url(rtspPort, listenStream));
        } catch (IOException | RuntimeException e) {
            connection.close();
            throw e;
        }
        publisher = connection;
        startThread("talk-rtp", () -> relayTalk(rtspPort, talkStream));
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
    public Session startSession(SrtpParameters audioParameters, SrtpParameters videoParameters,
            SrtpParameters talkParameters, String remoteHost, int remoteTalkPort) {
        Session newSession = new Session(SrtpSession.create(audioParameters), SrtpSession.create(videoParameters),
                SrtpSession.create(talkParameters), new InetSocketAddress(remoteHost, remoteTalkPort));
        session = newSession;
        return newSession;
    }

    /**
     * Stops relaying media. Packets received afterwards are dropped.
     */
    public void stopSession() {
        session = null;
    }

    @Override
    public synchronized void close() {
        closed = true;
        session = null;
        audioSocket.close();
        videoSocket.close();
        talkSocket.close();
        RtspConnection connection = publisher;
        if (connection != null) {
            connection.close();
        }
        RtspConnection reader = talkReader;
        if (reader != null) {
            reader.close();
        }
    }

    /**
     * Decrypts camera SRTP and publishes it as interleaved RTP to go2rtc.
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
                        connection.sendFrame(channel, rtpBuffer, rtpLength);
                    }
                }
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                LOGGER.debug("go2rtc RTP relay failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Reads the browser talk stream from go2rtc and sends it as SRTP to the camera. Reconnects after failures
     * until the data pump is closed.
     */
    private void relayTalk(int rtspPort, String talkStream) {
        byte[] rtpBuffer = new byte[MAX_DATAGRAM_SIZE];
        byte[] srtpBuffer = new byte[MAX_DATAGRAM_SIZE + SRTP_AUTH_TAG_LENGTH];
        while (!closed) {
            try (RtspConnection reader = openTalkReader(rtspPort, talkStream)) {
                talkReader = reader;
                if (closed) {
                    return;
                }
                while (!closed) {
                    RtspConnection.Frame frame = reader.readFrame(rtpBuffer);
                    Session current = session;
                    if (current != null && frame.channel() == TALK_CHANNEL && frame.length() >= RTP_HEADER_LENGTH) {
                        sendTalk(current, rtpBuffer, frame.length(), srtpBuffer);
                    }
                }
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                LOGGER.debug("go2rtc talk stream unavailable: {}", e.getMessage());
            } finally {
                talkReader = null;
            }
            try {
                Thread.sleep(TALK_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Re-labels a talk RTP packet with the talk payload type and sync source, then encrypts and sends it to the camera.
     */
    private void sendTalk(Session current, byte[] rtpBuffer, int length, byte[] srtpBuffer) throws IOException {
        rtpBuffer[1] = (byte) ((rtpBuffer[1] & 0x80) | AUDIO_PAYLOAD_TYPE);
        ByteBuffer.wrap(rtpBuffer).putInt(8, (int) AUDIO_SYNC_SOURCE);
        int srtpLength = current.talkSession().encrypt(rtpBuffer, length, srtpBuffer);
        talkSocket.send(new DatagramPacket(srtpBuffer, srtpLength, current.talkAddress()));
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
        connection.request("ANNOUNCE", url, "Content-Type: application/sdp\r\n", ANNOUNCE_SDP);
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
