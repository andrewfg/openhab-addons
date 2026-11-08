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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.homekit.internal.camera.AudioCodec;
import org.openhab.binding.homekit.internal.camera.VideoCodec;
import org.openhab.binding.homekit.internal.camera.srtp.SrtpCryptoSuite;
import org.openhab.binding.homekit.internal.camera.srtp.SrtpParameters;
import org.openhab.binding.homekit.internal.camera.srtp.SrtpSession;
import org.openhab.binding.homekit.internal.enums.AudioCodecType;

/**
 * Tests for {@link Go2RtcDataPump} codec selection, SDP generation and the SRTP relay.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class Go2RtcDataPumpTest {

    private static final int TIMEOUT_MS = 2000;
    private static final SrtpParameters LISTEN_AUDIO = new SrtpParameters(SrtpCryptoSuite.AES_CM_128_HMAC_SHA1_80,
            new byte[16], new byte[14]);
    private static final SrtpParameters LISTEN_VIDEO = new SrtpParameters(SrtpCryptoSuite.AES_CM_128_HMAC_SHA1_80,
            new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }, new byte[14]);
    private static final SrtpParameters TALK = new SrtpParameters(SrtpCryptoSuite.AES_CM_128_HMAC_SHA1_80,
            new byte[] { 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1 }, new byte[14]);
    private static final AudioCodec OPUS = new AudioCodec(AudioCodecType.OPUS, 1, 16);
    private static final AudioCodec PCMA = new AudioCodec(AudioCodecType.PCMA, 1, 8);
    private static final VideoCodec VIDEO = new VideoCodec(0, 0, 640, 480, 30);

    private @Nullable Go2RtcDataPump pump;
    private @Nullable DatagramSocket camera;

    @BeforeEach
    void setUp() throws Exception {
        DatagramSocket c = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        c.setSoTimeout(TIMEOUT_MS);
        camera = c;
    }

    @AfterEach
    void tearDown() {
        Go2RtcDataPump p = pump;
        if (p != null) {
            p.close();
        }
        DatagramSocket c = camera;
        if (c != null) {
            c.close();
        }
    }

    private Go2RtcDataPump newPump(List<AudioCodec> audio) throws IOException {
        Go2RtcDataPump p = new Go2RtcDataPump(audio, List.of(VIDEO));
        pump = p;
        return p;
    }

    private Go2RtcDataPump newPump(int audioFfmpegPort, int videoFfmpegPort) throws IOException {
        Go2RtcDataPump p = new Go2RtcDataPump(List.of(PCMA), List.of(VIDEO), audioFfmpegPort, videoFfmpegPort);
        pump = p;
        return p;
    }

    @Test
    void testPrefersPcmaOverOpus() throws IOException {
        assertEquals(AudioCodecType.PCMA, newPump(List.of(OPUS, PCMA)).audioCodec().codecType());
    }

    @Test
    void testFallsBackToOpusWhenNoPcma() throws IOException {
        assertEquals(AudioCodecType.OPUS, newPump(List.of(OPUS)).audioCodec().codecType());
    }

    @Test
    void testRejectsWhenNoUsableAudioCodec() {
        assertThrows(IllegalArgumentException.class,
                () -> new Go2RtcDataPump(List.of(new AudioCodec(AudioCodecType.AAC_ELD, 1, 16)), List.of(VIDEO)));
    }

    @Test
    void testSdpFilesContainFfmpegPorts() throws IOException {
        Go2RtcDataPump p = newPump(List.of(PCMA));

        String audioSdp = Files.readString(Path.of(p.audioSdpFile()));
        String videoSdp = Files.readString(Path.of(p.videoSdpFile()));

        Matcher audioPort = Pattern.compile("m=audio (\\d+) ").matcher(audioSdp);
        Matcher videoPort = Pattern.compile("m=video (\\d+) ").matcher(videoSdp);
        assertTrue(audioPort.find());
        assertTrue(videoPort.find());
        assertTrue(audioSdp.contains("a=rtpmap:" + Go2RtcDataPump.AUDIO_PAYLOAD_TYPE + " PCMA/8000"));
        assertTrue(videoSdp.contains("a=rtpmap:" + Go2RtcDataPump.VIDEO_PAYLOAD_TYPE + " H264/90000"));
    }

    @Test
    void testListenRelayDecryptsAndForwardsPlainRtp() throws Exception {
        try (DatagramSocket ffmpeg = new DatagramSocket(0, InetAddress.getLoopbackAddress());
                DatagramSocket ffmpegVideo = new DatagramSocket(0, InetAddress.getLoopbackAddress());
                DatagramSocket sender = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            ffmpeg.setSoTimeout(TIMEOUT_MS);
            Go2RtcDataPump p = newPump(ffmpeg.getLocalPort(), ffmpegVideo.getLocalPort());
            p.startSession(LISTEN_AUDIO, LISTEN_VIDEO, TALK, "127.0.0.1", camera().getLocalPort());

            byte[] plain = rtp(Go2RtcDataPump.AUDIO_PAYLOAD_TYPE, 1, 160, 0x11223344, new byte[] { 1, 2, 3, 4 });
            byte[] srtp = new byte[plain.length + 10];
            SrtpSession session = SrtpSession.create(LISTEN_AUDIO);
            int srtpLength = session.encrypt(plain, plain.length, srtp);
            sender.send(new DatagramPacket(srtp, srtpLength, InetAddress.getLoopbackAddress(), p.audioPort()));

            DatagramPacket received = new DatagramPacket(new byte[2048], 2048);
            ffmpeg.receive(received);
            assertArrayEquals(plain, java.util.Arrays.copyOf(received.getData(), received.getLength()));
        }
    }

    @Test
    void testTalkRelayEncryptsAndSendsSrtpToCamera() throws Exception {
        Go2RtcDataPump p = newPump(List.of(PCMA));
        DatagramSocket cam = camera();
        p.startSession(LISTEN_AUDIO, LISTEN_VIDEO, TALK, "127.0.0.1", cam.getLocalPort());

        byte[] plain = rtp(Go2RtcDataPump.AUDIO_PAYLOAD_TYPE, 7, 320, 0x55667788, new byte[] { 9, 8, 7, 6 });

        try (DatagramSocket ffmpeg = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            ffmpeg.send(new DatagramPacket(plain, plain.length, InetAddress.getLoopbackAddress(), p.talkPort()));
        }

        DatagramPacket received = new DatagramPacket(new byte[2048], 2048);
        cam.receive(received);
        byte[] srtp = Arrays.copyOf(received.getData(), received.getLength());
        assertNotEquals(plain.length, srtp.length, "SRTP must add an authentication tag");

        SrtpSession session = SrtpSession.create(TALK);
        byte[] decrypted = new byte[srtp.length];
        int length = session.decrypt(srtp, srtp.length, decrypted);

        assertTrue(length >= 0);
        assertArrayEquals(plain, Arrays.copyOf(decrypted, length));
    }

    @Test
    void testPacketsDroppedAfterStopSession() throws Exception {
        Go2RtcDataPump p = newPump(List.of(PCMA));
        DatagramSocket cam = camera();
        p.startSession(LISTEN_AUDIO, LISTEN_VIDEO, TALK, "127.0.0.1", cam.getLocalPort());
        p.stopSession();

        // sent after stopSession() returned, so the pump must see no active session when it reads this packet
        byte[] plain = rtp(Go2RtcDataPump.AUDIO_PAYLOAD_TYPE, 1, 0, 1, new byte[] { 1 });
        try (DatagramSocket ffmpeg = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            ffmpeg.send(new DatagramPacket(plain, plain.length, InetAddress.getLoopbackAddress(), p.talkPort()));
        }

        assertThrows(java.net.SocketTimeoutException.class,
                () -> cam.receive(new DatagramPacket(new byte[2048], 2048)));
    }

    private DatagramSocket camera() {
        DatagramSocket c = camera;
        assertNotNull(c);
        return c;
    }

    private static byte[] rtp(int payloadType, int seq, int timestamp, int ssrc, byte[] payload) {
        byte[] p = new byte[12 + payload.length];
        p[0] = (byte) 0x80;
        p[1] = (byte) payloadType;
        p[2] = (byte) (seq >> 8);
        p[3] = (byte) seq;
        p[4] = (byte) (timestamp >> 24);
        p[5] = (byte) (timestamp >> 16);
        p[6] = (byte) (timestamp >> 8);
        p[7] = (byte) timestamp;
        p[8] = (byte) (ssrc >> 24);
        p[9] = (byte) (ssrc >> 16);
        p[10] = (byte) (ssrc >> 8);
        p[11] = (byte) ssrc;
        System.arraycopy(payload, 0, p, 12, payload.length);
        return p;
    }
}
