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
import org.openhab.binding.homekit.internal.camera.codec.AudioCodec;
import org.openhab.binding.homekit.internal.enums.AudioCodecType;

/**
 * Tests for {@link FfmpegHelpers}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class FfmpegHelpersTest {

    @Test
    void testAudioSpecificConfigForAacEld() {
        assertEquals("F8F03000", FfmpegHelpers.audioSpecificConfig(16, 1));
        assertEquals("F8EC3000", FfmpegHelpers.audioSpecificConfig(24, 1));
        assertEquals("F8F05000", FfmpegHelpers.audioSpecificConfig(16, 2));
    }

    @Test
    void testAudioSpecificConfigRejectsUnsupportedInput() {
        assertThrows(IllegalArgumentException.class, () -> FfmpegHelpers.audioSpecificConfig(44, 1));
        assertThrows(IllegalArgumentException.class, () -> FfmpegHelpers.audioSpecificConfig(16, 0));
    }

    @Test
    void testInputSdp() {
        AudioCodec codec = new AudioCodec(AudioCodecType.AAC_ELD, 1, 16);
        String sdp = FfmpegHelpers.aacEldInputSdp(5004, codec, 110);
        assertTrue(sdp.contains("m=audio 5004 RTP/AVP 110\n"));
        assertTrue(sdp.contains("a=rtpmap:110 mpeg4-generic/16000/1\n"));
        assertTrue(sdp.contains("config=F8F03000;"));
        assertTrue(sdp.contains("constantDuration=480"));
    }

    @Test
    void testOpusCommandLine() {
        List<String> cmd = FfmpegHelpers.transcodeCommandLine("/usr/bin/ffmpeg", "/tmp/in.sdp", 6000, List.of(),
                FfmpegHelpers.opusEncoderArgs(), 110);
        assertEquals("/usr/bin/ffmpeg", cmd.get(0));
        assertEquals("/tmp/in.sdp", cmd.get(cmd.indexOf("-i") + 1));
        assertEquals("libopus", cmd.get(cmd.indexOf("-c:a") + 1));
        assertEquals("110", cmd.get(cmd.indexOf("-payload_type") + 1));
        assertEquals("rtp://127.0.0.1:6000", cmd.get(cmd.size() - 1));
    }

    @Test
    void testAacEldEncoderArgs() {
        List<String> args = FfmpegHelpers.aacEldEncoderArgs(new AudioCodec(AudioCodecType.AAC_ELD, 1, 16));
        assertEquals("libfdk_aac", args.get(args.indexOf("-c:a") + 1));
        assertEquals("aac_eld", args.get(args.indexOf("-profile:a") + 1));
        assertEquals("16000", args.get(args.indexOf("-ar") + 1));
        assertEquals("1", args.get(args.indexOf("-ac") + 1));
    }
}
