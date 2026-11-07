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
package org.openhab.binding.homekit.internal.go2rtc;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homekit.internal.enums.AudioCodecType;
import org.openhab.binding.homekit.internal.media.AudioCodec;
import org.openhab.binding.homekit.internal.media.SrtpSession;
import org.openhab.binding.homekit.internal.media.VideoCodec;
import org.openhab.binding.homekit.internal.media.tlv.SrtpParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relays RTP between the camera and go2rtc's ffmpeg instances.
 *
 * Camera audio and video arrive as SRTP on UDP ports advertised in the SetupEndpoints request. While a session is
 * active they are decrypted and forwarded as plain RTP on loopback to the forward ffmpeg instance, which reads them
 * from SDP files written by this class. Plain RTP that the back ffmpeg instance sends on loopback is encrypted and sent
 * to the camera's talk port. Packets are dropped while no session is active.
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
    private record Session(SrtpSession audioIn, SrtpSession videoIn, SrtpSession talkOut,
            InetSocketAddress talkTarget) {
    }

    private final AudioCodec audioCodec;
    private final VideoCodec videoCodec;
    private final DatagramSocket audioSocket;
    private final DatagramSocket videoSocket;
    private final DatagramSocket talkSocket;
    private final DatagramSocket talkSender;
    private final int audioFfmpegPort;
    private final int videoFfmpegPort;
    private final Path audioSdpFile;
    private final Path videoSdpFile;

    private volatile @Nullable Session session = null;
    private volatile boolean closed;

    /**
     * Returns the codec the data pump uses for listen and talk. A-law is preferred because go2rtc uses it natively, so
     * ffmpeg only copies packets. Other codecs are transcoded by ffmpeg.
     *
     * @param audioCodecs the audio codecs the camera supports
     * @return the preferred codec, or empty if the camera supports no codec that ffmpeg can transcode
     */
    private static Optional<AudioCodec> preferredAudioCodec(List<AudioCodec> audioCodecs) {
        Optional<AudioCodec> alaw = audioCodecs.stream().filter(codec -> codec.codecType() == AudioCodecType.PCMA)
                .findFirst();
        return alaw.or(() -> audioCodecs.stream().filter(
                codec -> codec.codecType() != AudioCodecType.AAC_ELD && codec.codecType() != AudioCodecType.MSBC)
                .findFirst());
    }

    /**
     * Returns the video codec configuration the data pump uses. H.264 Baseline is preferred because browsers accept it
     * for WebRTC, so go2rtc can pass the camera's stream through without transcoding. Among Baseline configurations,
     * the highest level is chosen. Other H.264 profiles are used when the camera offers no Baseline configuration.
     *
     * @param videoCodecs the video configurations the camera supports
     * @return the preferred configuration, or empty if the camera supports none
     */
    private static Optional<VideoCodec> preferredVideoCodec(List<VideoCodec> videoCodecs) {
        Optional<VideoCodec> baseline = videoCodecs.stream()
                .filter(codec -> codec.profile() == VideoCodec.PROFILE_BASELINE)
                .max(Comparator.comparingInt(VideoCodec::level));
        return baseline.or(() -> videoCodecs.stream().findFirst());
    }

    /**
     * Opens the camera-facing and loopback sockets, writes the SDP files for the forward ffmpeg instance and starts the
     * relay threads.
     *
     * @param audioCodecs the audio codecs the camera supports
     * @param videoCodecs the video configurations the camera supports
     * @throws IllegalArgumentException if the camera supports no codec that ffmpeg can transcode
     * @throws IOException if the sockets or SDP files cannot be created
     */
    public Go2RtcDataPump(List<AudioCodec> audioCodecs, List<VideoCodec> videoCodecs) throws IOException {
        audioCodec = preferredAudioCodec(audioCodecs)
                .orElseThrow(() -> new IllegalArgumentException("go2rtc requires audio"));
        videoCodec = preferredVideoCodec(videoCodecs)
                .orElseThrow(() -> new IllegalArgumentException("go2rtc requires video"));

        audioFfmpegPort = freeLoopbackPort();
        videoFfmpegPort = freeLoopbackPort();
        audioSocket = new DatagramSocket(0);
        try {
            videoSocket = new DatagramSocket(0);
        } catch (IOException e) {
            audioSocket.close();
            throw e;
        }
        talkSocket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        talkSender = new DatagramSocket(0);

        audioSdpFile = writeSdp("homekit-audio-", audioSdp(audioFfmpegPort, AUDIO_PAYLOAD_TYPE, audioCodec));
        videoSdpFile = writeSdp("homekit-video-", videoSdp(videoFfmpegPort, VIDEO_PAYLOAD_TYPE));

        startThread("audio-rtp", () -> relayListen(audioSocket, audioFfmpegPort, Session::audioIn));
        startThread("video-rtp", () -> relayListen(videoSocket, videoFfmpegPort, Session::videoIn));
        startThread("talk-rtp", this::relayTalk);
    }

    /**
     * @return the camera's audio codec, used for both listen and talk
     */
    public AudioCodec audioCodec() {
        return audioCodec;
    }

    /**
     * @return the camera's video configuration
     */
    public VideoCodec videoCodec() {
        return videoCodec;
    }

    /**
     * @return the UDP port that receives the camera's audio RTP, as advertised in the SetupEndpoints request
     */
    public int audioRtpPort() {
        return audioSocket.getLocalPort();
    }

    /**
     * @return the UDP port that receives the camera's video RTP, as advertised in the SetupEndpoints request
     */
    public int videoRtpPort() {
        return videoSocket.getLocalPort();
    }

    /**
     * @return the loopback UDP port that the back ffmpeg instance sends its talk RTP to
     */
    public int talkRtpPort() {
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
     * @param listenAudio the SRTP parameters the camera uses to encrypt its audio
     * @param listenVideo the SRTP parameters the camera uses to encrypt its video
     * @param talk the SRTP parameters the binding uses to encrypt talk audio towards the camera
     * @param remoteHost the camera's IP address
     * @param remoteTalkPort the camera's audio port from the SetupEndpoints response
     * @throws IllegalArgumentException if a crypto suite is
     *             {@link org.openhab.binding.homekit.internal.enums.SrtpCryptoSuite#NONE}
     */
    public void startSession(SrtpParameters listenAudio, SrtpParameters listenVideo, SrtpParameters talk,
            String remoteHost, int remoteTalkPort) {
        session = new Session(SrtpSession.create(listenAudio), SrtpSession.create(listenVideo),
                SrtpSession.create(talk), new InetSocketAddress(remoteHost, remoteTalkPort));
    }

    /**
     * Stops relaying media. Packets received afterwards are dropped.
     */
    public void stopSession() {
        session = null;
    }

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
                s=HomeKit listen
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio %d RTP/AVP %d
                a=rtpmap:%d %s/%d/%d
                %s""".formatted(ffmpegPort, payloadType, payloadType, encoding, codec.rtpClockRate(), codec.channels(),
                fmtp);
    }

    static String videoSdp(int ffmpegPort, int payloadType) {
        return """
                v=0
                o=- 0 0 IN IP4 127.0.0.1
                s=HomeKit watch
                c=IN IP4 127.0.0.1
                t=0 0
                m=video %d RTP/AVP %d
                a=rtpmap:%d H264/90000
                a=fmtp:%d packetization-mode=1
                """.formatted(ffmpegPort, payloadType, payloadType, payloadType);
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
     * Decrypts camera RTP and forwards it as plain RTP to the forward ffmpeg instance on loopback.
     */
    private void relayListen(DatagramSocket socket, int ffmpegPort, Function<Session, SrtpSession> stream) {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        byte[] buffer = new byte[MAX_DATAGRAM_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (!closed) {
            try {
                packet.setLength(buffer.length);
                socket.receive(packet);
                Session current = session;
                if (current != null) {
                    byte[] rtp = stream.apply(current).unprotect(Arrays.copyOf(packet.getData(), packet.getLength()));
                    if (rtp != null) {
                        socket.send(new DatagramPacket(rtp, rtp.length, loopback, ffmpegPort));
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
     * Encrypts plain RTP from the back ffmpeg instance and sends it to the camera's talk port.
     */
    private void relayTalk() {
        byte[] buffer = new byte[MAX_DATAGRAM_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (!closed) {
            try {
                packet.setLength(buffer.length);
                talkSocket.receive(packet);
                Session current = session;
                if (current != null) {
                    byte[] srtp = current.talkOut().protect(Arrays.copyOf(packet.getData(), packet.getLength()));
                    talkSender.send(new DatagramPacket(srtp, srtp.length, current.talkTarget()));
                }
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                LOGGER.debug("go2rtc talk relay failed: {}", e.getMessage());
            }
        }
    }

    private void startThread(String name, Runnable task) {
        Thread thread = new Thread(task, "OH-binding-homekit-go2rtc-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    private static int freeLoopbackPort() throws IOException {
        try (DatagramSocket probe = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

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

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.debug("Failed to delete go2rtc temp file {}: {}", file, e.getMessage());
        }
    }
}
