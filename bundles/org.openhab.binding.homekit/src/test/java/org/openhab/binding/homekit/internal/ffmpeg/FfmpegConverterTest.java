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
package org.openhab.binding.homekit.internal.ffmpeg;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openhab.binding.homekit.internal.enums.AudioCodecType;
import org.openhab.binding.homekit.internal.enums.SrtpCryptoSuite;
import org.openhab.binding.homekit.internal.media.AudioCodec;
import org.openhab.binding.homekit.internal.media.tlv.SrtpParameters;

/**
 * Tests for {@link FfmpegConverter} command construction.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class FfmpegConverterTest {

    private static final SrtpParameters SRTP = new SrtpParameters(SrtpCryptoSuite.AES_CM_128_HMAC_SHA1_80, new byte[16],
            new byte[14]);
    private static final AudioCodec OPUS = new AudioCodec(AudioCodecType.OPUS, 1, 24);
    private static final AudioCodec PCMU_8K = new AudioCodec(AudioCodecType.PCMU, 1, 8);
    private static final AudioCodec PCMA_8K = new AudioCodec(AudioCodecType.PCMA, 1, 8);
    private static final AudioCodec AMR_WB_16K = new AudioCodec(AudioCodecType.AMR_WB, 1, 16);

    @Test
    void testParamsAreBase64OfKeyThenSalt() {
        byte[] key = new byte[16];
        byte[] salt = new byte[14];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) (0x80 + i);
        }
        String params = FfmpegConverter
                .toFfmpegParams(new SrtpParameters(SrtpCryptoSuite.AES_CM_128_HMAC_SHA1_80, key, salt));
        byte[] decoded = Base64.getDecoder().decode(params);
        assertEquals(30, decoded.length);
        assertEquals((byte) 0x00, decoded[0]);
        assertEquals((byte) 0x0F, decoded[15]);
        assertEquals((byte) 0x80, decoded[16]);
    }

    @Test
    void testNoneSuiteRejected() {
        SrtpParameters none = new SrtpParameters(SrtpCryptoSuite.NONE, new byte[0], new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> FfmpegConverter.toFfmpegSuite(SrtpCryptoSuite.NONE));
        assertThrows(IllegalArgumentException.class, () -> FfmpegConverter.toFfmpegParams(none));
    }

    @Test
    void testSuiteNames() {
        assertEquals("AES_CM_128_HMAC_SHA1_80", FfmpegConverter.toFfmpegSuite(SrtpCryptoSuite.AES_CM_128_HMAC_SHA1_80));
        assertEquals("AES_CM_256_HMAC_SHA1_80", FfmpegConverter.toFfmpegSuite(SrtpCryptoSuite.AES_CM_256_HMAC_SHA1_80));
    }

    @Test
    void testListenCommandReadsSrtpAndCopiesContainerToStdout() {
        List<String> cmd = FfmpegConverter.listenCommand("ffmpeg", "/tmp/camera.sdp", SRTP, OPUS);
        assertEquals("ffmpeg", cmd.get(0));
        assertEquals("pipe:1", cmd.get(cmd.size() - 1));
        assertEquals("ogg", valueAfter(cmd, "-f"));
        assertEquals("copy", valueAfter(cmd, "-c:a"));
        assertFalse(cmd.contains("-ar"));
        assertFalse(cmd.contains("libopus"));
        assertEquals("AES_CM_128_HMAC_SHA1_80", valueAfter(cmd, "-srtp_in_suite"));
        assertEquals(FfmpegConverter.toFfmpegParams(SRTP), valueAfter(cmd, "-srtp_in_params"));
        assertEquals("/tmp/camera.sdp", valueAfter(cmd, "-i"));
        assertFalse(cmd.contains("-srtp_out_suite"));
    }

    @Test
    void testVideoListenCommandReadsSrtpAndWritesAnnexBToStdout() {
        List<String> cmd = FfmpegConverter.watchCommand("ffmpeg", "/tmp/camera.sdp", SRTP);
        assertEquals("ffmpeg", cmd.get(0));
        assertEquals("pipe:1", cmd.get(cmd.size() - 1));
        assertEquals("h264", valueAfter(cmd, "-f"));
        assertEquals("copy", valueAfter(cmd, "-c:v"));
        assertTrue(cmd.contains("-an"));
        assertFalse(cmd.contains("-c:a"));
        assertEquals("AES_CM_128_HMAC_SHA1_80", valueAfter(cmd, "-srtp_in_suite"));
        assertEquals(FfmpegConverter.toFfmpegParams(SRTP), valueAfter(cmd, "-srtp_in_params"));
        assertEquals("/tmp/camera.sdp", valueAfter(cmd, "-i"));
        assertFalse(cmd.contains("-srtp_out_suite"));
    }

    @Test
    void testListenCommandUsesContainerOfDeclaredCodec() {
        assertEquals("mulaw", valueAfter(listen(PCMU_8K), "-f"));
        assertEquals("alaw", valueAfter(listen(PCMA_8K), "-f"));
        assertEquals("amr", valueAfter(listen(AMR_WB_16K), "-f"));
    }

    @Test
    void testTalkCommandReadsContainerAndWritesSrtpToCamera() {
        List<String> cmd = FfmpegConverter.talkCommand("ffmpeg", "192.168.1.50", 5004, 6000, SRTP, 110, 0x12345678L,
                OPUS);
        assertEquals("pipe:0", valueAfter(cmd, "-i"));
        assertEquals("ogg", valueAfter(cmd, "-f"));
        assertEquals("copy", valueAfter(cmd, "-c:a"));
        assertEquals("110", valueAfter(cmd, "-payload_type"));
        assertEquals("305419896", valueAfter(cmd, "-ssrc"));
        assertEquals("AES_CM_128_HMAC_SHA1_80", valueAfter(cmd, "-srtp_out_suite"));
        assertEquals(FfmpegConverter.toFfmpegParams(SRTP), valueAfter(cmd, "-srtp_out_params"));
        assertEquals("rtp://192.168.1.50:5004?localport=6000", cmd.get(cmd.size() - 1));
        assertFalse(cmd.contains("-srtp_in_suite"));
        assertFalse(cmd.contains("-ar"));
    }

    @Test
    void testTalkCommandGivesRawG711InputItsSampleRateAndChannels() {
        List<String> cmd = talk(PCMU_8K);
        assertEquals("8000", valueAfter(cmd, "-ar"));
        assertEquals("1", valueAfter(cmd, "-ac"));
        assertEquals("mulaw", valueAfter(cmd, "-f"));
        assertEquals("pipe:0", valueAfter(cmd, "-i"));
    }

    @Test
    void testTalkCommandUsesAmrContainerForAmrWb() {
        assertEquals("amr", valueAfter(talk(AMR_WB_16K), "-f"));
        assertFalse(talk(AMR_WB_16K).contains("-ar"));
    }

    private static List<String> listen(AudioCodec codec) {
        return FfmpegConverter.listenCommand("ffmpeg", "/tmp/camera.sdp", SRTP, codec);
    }

    private static List<String> talk(AudioCodec codec) {
        return FfmpegConverter.talkCommand("ffmpeg", "192.168.1.50", 5004, 6000, SRTP, 110, 0x12345678L, codec);
    }

    private static String valueAfter(List<String> cmd, String option) {
        int index = cmd.indexOf(option);
        assertTrue(index >= 0 && index + 1 < cmd.size(), "missing option " + option);
        return cmd.get(index + 1);
    }
}
