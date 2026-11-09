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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Relays RTP between the camera and go2rtc's ffmpeg instances.
 * <p>
 * Camera forward path audio and video arrive as SRTP on UDP ports advertised in the SetupEndpoints request.
 * The forward SRTP streams are decrypted and passed as plain RTP to the forward ffmpeg instance.
 * <p>
 * The back ffmpeg instance sends plain RTP to the data pump.
 * The back path audio is encrypted and sent as SRTP to the camera's talk port advertised in the SetupEndpoints
 * response.
 * 
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Go2RtcDataPump implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Go2RtcDataPump.class);

    private static final int MAX_DATAGRAM_SIZE = 2048;

    public static final int AUDIO_PAYLOAD_TYPE = 110;
    public static final int VIDEO_PAYLOAD_TYPE = 99;

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
    private final DatagramSocket talkSender;
    private final Path audioSdpFile;
    private final Path videoSdpFile;

    private volatile @Nullable Session session = null;
    private volatile boolean closed;

    /**
     * Creates a data pump that forwards between the go2rtc facing forward and backward ffmpeg instances
     * and the camera facing audio and video stream ports.
     * 
     * @param audioCodecs the audio codecs the camera supports
     * @param videoCodecs the video configurations the camera supports
     * @throws IllegalArgumentException if the camera supports no codec that ffmpeg can trans-code
     * @throws IOException if the sockets or SDP files cannot be created
     */
    public Go2RtcDataPump(List<AudioCodec> audioCodecs, List<VideoCodec> videoCodecs) throws IOException {
        this(audioCodecs, videoCodecs, Go2RtcConfig.freeTcpUdpPort(), Go2RtcConfig.freeTcpUdpPort());
    }

    /**
     * For testing..
     */
    Go2RtcDataPump(List<AudioCodec> audioCodecs, List<VideoCodec> videoCodecs, int audioFfmpegPort, int videoFfmpegPort)
            throws IOException {
        audioCodec = AudioCodec.preferred(audioCodecs)
                .orElseThrow(() -> new IllegalArgumentException("go2rtc requires audio"));
        videoCodec = VideoCodec.preferred(videoCodecs)
                .orElseThrow(() -> new IllegalArgumentException("go2rtc requires video"));

        DatagramSocket audio = new DatagramSocket(0);
        DatagramSocket video = null;
        DatagramSocket talk = null;
        DatagramSocket sender = null;
        Path audioSdp = null;
        Path videoSdp = null;

        try {
            video = new DatagramSocket(0);
            talk = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            sender = new DatagramSocket(0);
            audioSdp = writeSdp("homekit-audio-", audioSdp(audioFfmpegPort, AUDIO_PAYLOAD_TYPE, audioCodec));
            videoSdp = writeSdp("homekit-video-", videoSdp(videoFfmpegPort, VIDEO_PAYLOAD_TYPE, videoCodec));
        } catch (IOException | RuntimeException e) {
            audio.close();
            closeQuietly(video, talk, sender);
            deleteQuietly(audioSdp);
            deleteQuietly(videoSdp);
            throw e;
        }

        audioSocket = audio;
        videoSocket = video;
        talkSocket = talk;
        talkSender = sender;
        audioSdpFile = audioSdp;
        videoSdpFile = videoSdp;

        startThread("audio-rtp", () -> relayListen(audioSocket, audioFfmpegPort, Session::audioSession));
        startThread("video-rtp", () -> relayListen(videoSocket, videoFfmpegPort, Session::videoSession));
        startThread("talk-rtp", this::relayTalk);
    }

    /**
     * @return the camera's audio codec (used for both listen and talk).
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
     * @return the UDP port that the back ffmpeg instance sends its talk RTP to
     */
    public int talkPort() {
        return talkSocket.getLocalPort();
    }

    /**
     * @return the SDP file that describes the camera audio for the forward ffmpeg instance
     */
    public String audioSdpFile() {
        return audioSdpFile.toString();
    }

    /**
     * @return the SDP file that describes the camera video for the forward ffmpeg instance
     */
    public String videoSdpFile() {
        return videoSdpFile.toString();
    }

    /**
     * Starts relaying media for a camera session, replacing any session already active.
     *
     * @param audioSrtpParameters the SRTP parameters the camera uses to encrypt its audio
     * @param videoSrtpParameters the SRTP parameters the camera uses to encrypt its video
     * @param talkParameters the SRTP parameters the binding uses to encrypt talk audio towards the camera
     * @param remoteHost the camera's IP address
     * @param remoteTalkPort the camera's audio port from the SetupEndpoints response
     * @return
     * @throws IllegalArgumentException if a crypto suite is NONE
     */
    public @Nullable Session startSession(SrtpParameters audioParameters, SrtpParameters videoParameters,
            SrtpParameters talkParameters, String remoteHost, int remoteTalkPort) {
        session = new Session(SrtpSession.create(audioParameters), SrtpSession.create(videoParameters),
                SrtpSession.create(talkParameters), new InetSocketAddress(remoteHost, remoteTalkPort));
        return session;
    }

    /**
     * Stops relaying media. Packets received afterwards are dropped.
     */
    public void stopSession() {
        session = null;
    }

    /**
     * Returns an SDP that describes the camera's audio for the forward ffmpeg instance.
     */
    static String audioSdp(int ffmpegPort, int payloadType, AudioCodec codec) {
        String encoding = switch (codec.codecType()) {
            case OPUS -> "opus";
            case PCMU -> "PCMU";
            case PCMA -> "PCMA";
            case AMR -> "AMR";
            case AMR_WB -> "AMR-WB";
            case AAC_ELD, MSBC -> throw new IllegalArgumentException("Unsupported codec: " + codec.codecType());
        };
        String fmtp = switch (codec.codecType()) {
            case AMR, AMR_WB -> "a=fmtp:%d octet-align=1\n".formatted(payloadType);
            default -> "";
        };
        return """
                v=0
                o=- 0 0 IN IP4 127.0.0.1
                s=HomeKit Audio
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio %d RTP/AVP %d
                a=rtpmap:%d %s/%d/%d
                %s""".formatted(ffmpegPort, payloadType, payloadType, encoding, codec.rtpClockRate(), codec.channels(),
                fmtp);
    }

    /**
     * Returns an SDP that describes the camera's video for the forward ffmpeg instance.
     */
    static String videoSdp(int ffmpegPort, int payloadType, VideoCodec codec) {
        String encoding = switch (codec.codecType()) {
            case H_264 -> "H264";
            case H_265 -> throw new IllegalArgumentException("Unsupported codec: " + codec.codecType());
        };
        return """
                v=0
                o=- 0 0 IN IP4 127.0.0.1
                s=HomeKit Video
                c=IN IP4 127.0.0.1
                t=0 0
                m=video %d RTP/AVP %d
                a=rtpmap:%d %s/90000
                a=fmtp:%d packetization-mode=1
                """.formatted(ffmpegPort, payloadType, payloadType, encoding, payloadType);
    }

    @Override
    public void close() {
        closed = true;
        session = null;
        audioSocket.close();
        videoSocket.close();
        talkSocket.close();
        talkSender.close();
        deleteQuietly(audioSdpFile);
        deleteQuietly(videoSdpFile);
    }

    /**
     * Decrypts camera SRTP and forwards it as plain RTP to the forward ffmpeg instance.
     */
    private void relayListen(DatagramSocket socket, int ffmpegPort, Function<Session, SrtpSession> stream) {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        byte[] srtpBuffer = new byte[MAX_DATAGRAM_SIZE];
        byte[] rtpBuffer = new byte[MAX_DATAGRAM_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(srtpBuffer, srtpBuffer.length);
        DatagramPacket sendPacket = new DatagramPacket(rtpBuffer, 0, loopback, ffmpegPort);
        while (!closed) {
            try {
                receivePacket.setLength(srtpBuffer.length);
                socket.receive(receivePacket);
                Session current = session;
                if (current != null) {
                    int rtpLength = stream.apply(current).decrypt(srtpBuffer, receivePacket.getLength(), rtpBuffer);
                    if (rtpLength >= 0) {
                        sendPacket.setData(rtpBuffer, 0, rtpLength);
                        socket.send(sendPacket);
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
     * Encrypts plain RTP from the back ffmpeg instance and sends it as SRTP to the camera.
     */
    private void relayTalk() {
        byte[] rtpBuffer = new byte[MAX_DATAGRAM_SIZE];
        byte[] srtpBuffer = new byte[MAX_DATAGRAM_SIZE + 10];
        DatagramPacket receivePacket = new DatagramPacket(rtpBuffer, rtpBuffer.length);
        DatagramPacket sendPacket = new DatagramPacket(srtpBuffer, 0);
        while (!closed) {
            try {
                receivePacket.setLength(rtpBuffer.length);
                talkSocket.receive(receivePacket);
                Session current = session;
                if (current != null) {
                    int srtpLength = current.talkSession().encrypt(rtpBuffer, receivePacket.getLength(), srtpBuffer);
                    sendPacket.setData(srtpBuffer, 0, srtpLength);
                    sendPacket.setSocketAddress(current.talkAddress());
                    talkSender.send(sendPacket);
                }
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                LOGGER.debug("go2rtc talk relay failed: {}", e.getMessage());
            }
        }
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
     * Writes the given SDP to a temporary file and returns the path.
     */
    private static Path writeSdp(String prefix, String sdp) throws IOException {
        Path file = Files.createTempFile(prefix, ".sdp");
        try {
            Files.writeString(file, sdp, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            deleteQuietly(file);
            throw e;
        }
        return file;
    }

    /**
     * Deletes the given file, ignoring any exception.
     */
    private static void deleteQuietly(@Nullable Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.debug("Failed to delete go2rtc temp file {}: {}", file, e.getMessage());
        }
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
}
