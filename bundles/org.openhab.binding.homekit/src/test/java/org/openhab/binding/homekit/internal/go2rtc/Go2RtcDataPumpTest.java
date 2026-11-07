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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.openhab.binding.homekit.internal.enums.AudioCodecType;
import org.openhab.binding.homekit.internal.media.AudioSupportedCodec;
import org.openhab.binding.homekit.internal.media.VideoSupportedCodec;

/**
 * Tests for {@link Go2RtcDataPump}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class Go2RtcDataPumpTest {

    private static final int TIMEOUT_SECONDS = 10;
    private static final List<AudioSupportedCodec> CAMERA_CODECS = List
            .of(new AudioSupportedCodec(AudioCodecType.PCMA, 1, 8));
    private static final Go2RtcDataPump.ClientObserver NO_OP_OBSERVER = new Go2RtcDataPump.ClientObserver() {
        @Override
        public void listenConnected() {
        }

        @Override
        public void listenDisconnected() {
        }

        @Override
        public void talkConnected() {
        }

        @Override
        public void talkDisconnected() {
        }
    };

    private static volatile Path decoderHelperSource;

    private static Path decoderHelperSource() {
        Path helper = decoderHelperSource;
        if (helper != null) {
            return helper;
        }
        synchronized (Go2RtcDataPumpTest.class) {
            helper = decoderHelperSource;
            if (helper == null) {
                try {
                    helper = Files.createTempFile("go2rtc-decoder-helper-", ".java");
                    Files.writeString(helper, """
                            import java.nio.file.Files;
                            import java.nio.file.Path;

                            class Go2RtcDataPumpTestDecoderMain {
                                public static void main(String[] args) throws Exception {
                                    if (args.length == 0) {
                                        return;
                                    }
                                    switch (args[0]) {
                                        case \"emit-file\" -> {
                                            if (args.length < 2) {
                                                throw new IllegalArgumentException(\"Missing file path\");
                                            }
                                            byte[] content = Files.readAllBytes(Path.of(args[1]));
                                            System.out.write(content);
                                            System.out.flush();
                                        }
                                        case \"block-stdin\" -> {
                                            while (System.in.read() != -1) {
                                                // block until stdin closes
                                            }
                                        }
                                        default -> throw new IllegalArgumentException(\"Unknown mode: \" + args[0]);
                                    }
                                }
                            }
                            """);
                    decoderHelperSource = helper;
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to create decoder helper source", e);
                }
            }
            return helper;
        }
    }

    private static List<String> decoderCommand(String mode, String... args) {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        // Launch source directly to avoid very long test classpaths on Windows.
        command.add(decoderHelperSource().toString());
        command.add(mode);
        command.addAll(Arrays.asList(args));
        return command;
    }

    @Test
    void preferredCodecPrefersAlawOverOtherCodecs() throws IOException {
        AudioSupportedCodec alaw = new AudioSupportedCodec(AudioCodecType.PCMA, 1, 8);
        List<AudioSupportedCodec> codecs = List.of(new AudioSupportedCodec(AudioCodecType.OPUS, 1, 24), alaw);

        assertEquals(alaw, Go2RtcDataPump.preferredCodec(codecs).orElseThrow());
        try (Go2RtcDataPump pump = new Go2RtcDataPump(codecs, NO_OP_OBSERVER)) {
            assertEquals(alaw, pump.codec());
        }
    }

    @Test
    void constructorAcceptsCameraWithoutAlawAndUsesFirstTranscodableCodec() throws IOException {
        AudioSupportedCodec opus = new AudioSupportedCodec(AudioCodecType.OPUS, 1, 24);
        List<AudioSupportedCodec> codecs = List.of(new AudioSupportedCodec(AudioCodecType.AAC_ELD, 1, 16), opus);

        assertEquals(opus, Go2RtcDataPump.preferredCodec(codecs).orElseThrow());
        try (Go2RtcDataPump pump = new Go2RtcDataPump(codecs, NO_OP_OBSERVER)) {
            assertEquals(opus, pump.codec());
        }
    }

    @Test
    void constructorRejectsCameraWithoutTranscodableCodec() {
        List<AudioSupportedCodec> codecs = List.of(new AudioSupportedCodec(AudioCodecType.AAC_ELD, 1, 16));

        assertTrue(Go2RtcDataPump.preferredCodec(codecs).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Go2RtcDataPump(codecs, NO_OP_OBSERVER));
    }

    @Test
    void preferredVideoCodecPrefersHighestLevelBaselineProfile() {
        VideoSupportedCodec baselineLevel31 = new VideoSupportedCodec(0, 0, 1920, 1080, 30);
        VideoSupportedCodec baselineLevel40 = new VideoSupportedCodec(0, 2, 1280, 720, 30);
        List<VideoSupportedCodec> codecs = List.of(new VideoSupportedCodec(2, 2, 1920, 1080, 30), baselineLevel31,
                baselineLevel40);

        assertEquals(baselineLevel40, Go2RtcDataPump.preferredVideoCodec(codecs).orElseThrow());
    }

    @Test
    void preferredVideoCodecFallsBackToFirstWithoutBaseline() {
        VideoSupportedCodec main = new VideoSupportedCodec(1, 0, 640, 480, 15);
        List<VideoSupportedCodec> codecs = List.of(main, new VideoSupportedCodec(2, 2, 1920, 1080, 30));

        assertEquals(main, Go2RtcDataPump.preferredVideoCodec(codecs).orElseThrow());
        assertTrue(Go2RtcDataPump.preferredVideoCodec(List.of()).isEmpty());
    }

    @Test
    void audioSdpDescribesLoopbackOpusStreamOnPort() {
        String sdp = Go2RtcDataPump.audioSdp(40000, 110, new AudioSupportedCodec(AudioCodecType.OPUS, 1, 24));

        assertTrue(sdp.contains("\nm=audio 40000 RTP/AVP 110\n"), sdp);
        assertTrue(sdp.contains("\na=rtpmap:110 opus/24000/1\n"), sdp);
        assertTrue(sdp.contains("\nc=IN IP4 127.0.0.1\n"), sdp);
        assertFalse(sdp.contains("fmtp"), sdp);
    }

    @Test
    void audioSdpNamesG711Encoding() {
        String sdp = Go2RtcDataPump.audioSdp(40000, 0, new AudioSupportedCodec(AudioCodecType.PCMU, 1, 8));

        assertTrue(sdp.contains("\na=rtpmap:0 PCMU/8000/1\n"), sdp);
    }

    @Test
    void audioSdpDescribesAmrWbWithOctetAlign() {
        String sdp = Go2RtcDataPump.audioSdp(40000, 99, new AudioSupportedCodec(AudioCodecType.AMR_WB, 1, 16));

        assertTrue(sdp.contains("\na=rtpmap:99 AMR-WB/16000/1\n"), sdp);
        assertTrue(sdp.contains("\na=fmtp:99 octet-align=1\n"), sdp);
    }

    @Test
    void videoSdpDescribesH264StreamOnPort() {
        String sdp = Go2RtcDataPump.videoSdp(40002, 99);

        assertTrue(sdp.contains("\nm=video 40002 RTP/AVP 99\n"), sdp);
        assertTrue(sdp.contains("\na=rtpmap:99 H264/90000\n"), sdp);
        assertTrue(sdp.contains("\na=fmtp:99 packetization-mode=1\n"), sdp);
        assertFalse(sdp.contains("m=audio"), sdp);
    }

    @Test
    void decoderOutputReachesListenClient() throws Exception {
        AudioSupportedCodec pcmu = new AudioSupportedCodec(AudioCodecType.PCMU, 1, 8);
        CompletableFuture<Integer> decoderPort = new CompletableFuture<>();
        try (Go2RtcDataPump pump = new Go2RtcDataPump(CAMERA_CODECS, NO_OP_OBSERVER)) {
            // the helper process writes the SDP file to stdout, so decoder output matches the written SDP
            pump.startDecoders(new Go2RtcDataPump.DecoderSpecification(port -> {
                decoderPort.complete(port);
                return Go2RtcDataPump.audioSdp(port, 0, pcmu);
            }, file -> decoderCommand("emit-file", file)), null);
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), pump.audioListenPort())) {
                client.setSoTimeout(TIMEOUT_SECONDS * 1000);
                String expected = Go2RtcDataPump.audioSdp(decoderPort.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), 0, pcmu);
                byte[] actual = client.getInputStream().readNBytes(expected.getBytes(StandardCharsets.US_ASCII).length);
                assertEquals(expected, new String(actual, StandardCharsets.US_ASCII));
            }
        }
    }

    @Test
    void rtpReceivedWhileListenConnectedIsForwardedToDecoder() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        CompletableFuture<Integer> decoderPort = new CompletableFuture<>();
        try (Go2RtcDataPump pump = new Go2RtcDataPump(CAMERA_CODECS, NO_OP_OBSERVER)) {
            // the helper process blocks on stdin to keep the decoder process alive while the test sends RTP
            pump.startDecoders(new Go2RtcDataPump.DecoderSpecification(port -> {
                decoderPort.complete(port);
                return "";
            }, file -> decoderCommand("block-stdin")), null);
            try (Socket client = new Socket(loopback, pump.audioListenPort());
                    DatagramSocket decoderSocket = new DatagramSocket(
                            decoderPort.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), loopback);
                    DatagramSocket camera = new DatagramSocket()) {
                byte[] payload = { 1, 2, 3, 4 };
                decoderSocket.setSoTimeout(100);
                DatagramPacket packet = new DatagramPacket(new byte[2048], 2048);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
                boolean received = false;
                // the relay only forwards once the decoder is running, so keep sending until it arrives
                while (!received && System.nanoTime() < deadline) {
                    camera.send(new DatagramPacket(payload, payload.length, loopback, pump.audioRtpPort()));
                    try {
                        decoderSocket.receive(packet);
                        received = true;
                    } catch (SocketTimeoutException e) {
                        // retry until the deadline
                    }
                }
                assertTrue(received, "RTP was not forwarded to the decoder");
                assertArrayEquals(payload, Arrays.copyOf(packet.getData(), packet.getLength()));
            }
        }
    }

    @Test
    void talkConnectionNotifiesObserverOfConnectAndDisconnect() throws Exception {
        CompletableFuture<Void> connected = new CompletableFuture<>();
        CompletableFuture<Void> disconnected = new CompletableFuture<>();
        Go2RtcDataPump.ClientObserver observer = new Go2RtcDataPump.ClientObserver() {
            @Override
            public void listenConnected() {
            }

            @Override
            public void listenDisconnected() {
            }

            @Override
            public void talkConnected() {
                connected.complete(null);
            }

            @Override
            public void talkDisconnected() {
                disconnected.complete(null);
            }
        };
        try (Go2RtcDataPump pump = new Go2RtcDataPump(CAMERA_CODECS, observer)) {
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), pump.talkPort())) {
                connected.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                client.shutdownOutput();
            }
            disconnected.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
