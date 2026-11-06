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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.openhab.binding.homekit.internal.enums.AudioCodecType;
import org.openhab.binding.homekit.internal.media.AudioCodec;

/**
 * Tests for {@link Go2RtcTransporter}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class Go2RtcTransporterTest {

    private static final int TIMEOUT_SECONDS = 10;
    private static final List<AudioCodec> CAMERA_CODECS = List.of(new AudioCodec(AudioCodecType.PCMA, 1, 8));
    private static final Go2RtcTransporter.ClientObserver NO_OP_OBSERVER = new Go2RtcTransporter.ClientObserver() {
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

    @Test
    void alawCodecIsSelectedFromCameraCodecs() throws IOException {
        AudioCodec alaw = new AudioCodec(AudioCodecType.PCMA, 1, 8);
        List<AudioCodec> cameraCodecs = List.of(new AudioCodec(AudioCodecType.OPUS, 1, 24), alaw);

        assertEquals(alaw, Go2RtcTransporter.alawCodec(cameraCodecs).orElseThrow());
        try (Go2RtcTransporter transporter = new Go2RtcTransporter(cameraCodecs, NO_OP_OBSERVER)) {
            assertEquals(alaw, transporter.codec());
        }
    }

    @Test
    void constructorRejectsCameraWithoutAlaw() {
        List<AudioCodec> cameraCodecs = List.of(new AudioCodec(AudioCodecType.OPUS, 1, 24));

        assertTrue(Go2RtcTransporter.alawCodec(cameraCodecs).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Go2RtcTransporter(cameraCodecs, NO_OP_OBSERVER));
    }

    @Test
    void audioSdpDescribesLoopbackOpusStreamOnPort() {
        String sdp = Go2RtcTransporter.audioSdp(40000, 110, new AudioCodec(AudioCodecType.OPUS, 1, 24));

        assertTrue(sdp.contains("\nm=audio 40000 RTP/AVP 110\n"), sdp);
        assertTrue(sdp.contains("\na=rtpmap:110 opus/24000/1\n"), sdp);
        assertTrue(sdp.contains("\nc=IN IP4 127.0.0.1\n"), sdp);
        assertFalse(sdp.contains("fmtp"), sdp);
    }

    @Test
    void audioSdpNamesG711Encoding() {
        String sdp = Go2RtcTransporter.audioSdp(40000, 0, new AudioCodec(AudioCodecType.PCMU, 1, 8));

        assertTrue(sdp.contains("\na=rtpmap:0 PCMU/8000/1\n"), sdp);
    }

    @Test
    void audioSdpDescribesAmrWbWithOctetAlign() {
        String sdp = Go2RtcTransporter.audioSdp(40000, 99, new AudioCodec(AudioCodecType.AMR_WB, 1, 16));

        assertTrue(sdp.contains("\na=rtpmap:99 AMR-WB/16000/1\n"), sdp);
        assertTrue(sdp.contains("\na=fmtp:99 octet-align=1\n"), sdp);
    }

    @Test
    void videoSdpDescribesH264StreamOnPort() {
        String sdp = Go2RtcTransporter.videoSdp(40002, 99);

        assertTrue(sdp.contains("\nm=video 40002 RTP/AVP 99\n"), sdp);
        assertTrue(sdp.contains("\na=rtpmap:99 H264/90000\n"), sdp);
        assertTrue(sdp.contains("\na=fmtp:99 packetization-mode=1\n"), sdp);
        assertFalse(sdp.contains("m=audio"), sdp);
    }

    @Test
    void decoderOutputReachesListenClient() throws Exception {
        AudioCodec pcmu = new AudioCodec(AudioCodecType.PCMU, 1, 8);
        CompletableFuture<Integer> decoderPort = new CompletableFuture<>();
        try (Go2RtcTransporter transporter = new Go2RtcTransporter(CAMERA_CODECS, NO_OP_OBSERVER)) {
            // the decoder command is "cat <sdp file>", so its output is exactly the SDP that was written
            transporter.startDecoders(new Go2RtcTransporter.Decoder(port -> {
                decoderPort.complete(port);
                return Go2RtcTransporter.audioSdp(port, 0, pcmu);
            }, file -> List.of("cat", file)), null);
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), transporter.audioListenPort())) {
                client.setSoTimeout(TIMEOUT_SECONDS * 1000);
                String expected = Go2RtcTransporter.audioSdp(decoderPort.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), 0,
                        pcmu);
                byte[] actual = client.getInputStream().readNBytes(expected.getBytes(StandardCharsets.US_ASCII).length);
                assertEquals(expected, new String(actual, StandardCharsets.US_ASCII));
            }
        }
    }

    @Test
    void rtpReceivedWhileListenConnectedIsForwardedToDecoder() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        CompletableFuture<Integer> decoderPort = new CompletableFuture<>();
        try (Go2RtcTransporter transporter = new Go2RtcTransporter(CAMERA_CODECS, NO_OP_OBSERVER)) {
            // "cat" with no argument stays alive reading stdin, so the decoder keeps running while the test sends RTP
            transporter.startDecoders(new Go2RtcTransporter.Decoder(port -> {
                decoderPort.complete(port);
                return "";
            }, file -> List.of("cat")), null);
            try (Socket client = new Socket(loopback, transporter.audioListenPort());
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
                    camera.send(new DatagramPacket(payload, payload.length, loopback, transporter.audioRtpPort()));
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
        Go2RtcTransporter.ClientObserver observer = new Go2RtcTransporter.ClientObserver() {
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
        try (Go2RtcTransporter transporter = new Go2RtcTransporter(CAMERA_CODECS, observer)) {
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), transporter.talkPort())) {
                connected.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                client.shutdownOutput();
            }
            disconnected.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
