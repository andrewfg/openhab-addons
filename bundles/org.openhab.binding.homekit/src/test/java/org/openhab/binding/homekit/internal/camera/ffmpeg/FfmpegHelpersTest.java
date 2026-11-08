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
package org.openhab.binding.homekit.internal.camera.ffmpeg;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openhab.binding.homekit.internal.camera.AudioCodec;
import org.openhab.binding.homekit.internal.enums.AudioCodecType;

/**
 * Tests for {@link FfmpegHelpers} command construction.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class FfmpegHelpersTest {

    private static final AudioCodec OPUS = new AudioCodec(AudioCodecType.OPUS, 1, 24);
    private static final AudioCodec PCMU = new AudioCodec(AudioCodecType.PCMU, 1, 8);
    private static final AudioCodec PCMA = new AudioCodec(AudioCodecType.PCMA, 1, 8);
    private static final AudioCodec AAC_ELD = new AudioCodec(AudioCodecType.AAC_ELD, 1, 24);

    @Test
    void listenCopiesAlawAndMapsBothSdpInputs() {
        List<String> cmd = listen(PCMA);
        assertEquals("ffmpeg", cmd.get(0));
        assertEquals("{output}", cmd.get(cmd.size() - 1));
        assertEquals("tcp", valueAfter(cmd, "-rtsp_transport"));
        assertEquals("rtsp", valueAfter(cmd, "-f"));
        assertEquals("copy", valueAfter(cmd, "-c:v"));
        assertEquals("copy", valueAfter(cmd, "-c:a"));
        assertEquals("/tmp/a.sdp", valueAfter(cmd, "-i"));
        assertEquals(2, cmd.stream().filter("file,rtp,udp"::equals).count());
        assertEquals(2, cmd.stream().filter("-i"::equals).count());
        assertTrue(String.join(" ", cmd).contains("-map 1:v -map 0:a"));
        assertFalse(cmd.contains("-ar"));
    }

    @Test
    void listenTranscodesNonAlawAudioToAlaw() {
        for (AudioCodec codec : List.of(OPUS, PCMU)) {
            List<String> cmd = listen(codec);
            assertEquals("pcm_alaw", valueAfter(cmd, "-c:a"));
            assertEquals("8000", valueAfter(cmd, "-ar"));
            assertEquals("1", valueAfter(cmd, "-ac"));
        }
    }

    @Test
    void talkCopiesAlawAndSetsRtpPayloadAndSsrc() {
        List<String> cmd = talk(PCMA);
        assertEquals("pipe:0", valueAfter(cmd, "-i"));
        assertEquals("alaw", valueAfter(cmd, "-f"));
        assertEquals("copy", valueAfter(cmd, "-c:a"));
        assertFalse(cmd.contains("-b:a"));
        assertEquals("110", valueAfter(cmd, "-payload_type"));
        assertEquals("305419896", valueAfter(cmd, "-ssrc"));
        assertTrue(String.join(" ", cmd).endsWith("-payload_type 110 -ssrc 305419896 -f rtp rtp://127.0.0.1:6000"));
    }

    @Test
    void talkEncodesOpusWithBitrateAtCodecRate() {
        List<String> cmd = talk(OPUS);
        assertEquals("libopus", valueAfter(cmd, "-c:a"));
        assertEquals("24k", valueAfter(cmd, "-b:a"));
        assertTrue(String.join(" ", cmd).contains("-ar " + OPUS.rtpClockRate() + " -ac 1 -c:a libopus"));
    }

    @Test
    void talkEncodesPcmuWithoutBitrate() {
        List<String> cmd = talk(PCMU);
        assertEquals("pcm_mulaw", valueAfter(cmd, "-c:a"));
        assertFalse(cmd.contains("-b:a"));
    }

    @Test
    void encoderRejectsUnsupportedCodecs() {
        assertThrows(IllegalArgumentException.class, () -> FfmpegHelpers.encoder(AudioCodecType.AAC_ELD));
        assertThrows(IllegalArgumentException.class, () -> FfmpegHelpers.encoder(AudioCodecType.MSBC));
        assertThrows(IllegalArgumentException.class, () -> talk(AAC_ELD));
    }

    @Test
    void alawDetectionRequiresEightKilohertzMono() {
        assertTrue(FfmpegHelpers.isGo2rtcAlaw(PCMA));
        assertFalse(FfmpegHelpers.isGo2rtcAlaw(PCMU));
        assertFalse(FfmpegHelpers.isGo2rtcAlaw(OPUS));
    }

    private static List<String> listen(AudioCodec codec) {
        return FfmpegHelpers.go2rtcListenCommand("ffmpeg", "/tmp/a.sdp", "/tmp/v.sdp", codec);
    }

    private static List<String> talk(AudioCodec codec) {
        return FfmpegHelpers.go2rtcTalkCommand("ffmpeg", codec, 110, 0x12345678L, 6000);
    }

    private static String valueAfter(List<String> cmd, String option) {
        int index = cmd.indexOf(option);
        assertTrue(index >= 0 && index + 1 < cmd.size(), "missing option " + option);
        return cmd.get(index + 1);
    }
}
