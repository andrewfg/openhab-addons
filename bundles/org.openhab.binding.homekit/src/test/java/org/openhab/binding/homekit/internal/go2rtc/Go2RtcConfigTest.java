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

import org.junit.jupiter.api.Test;
import org.openhab.binding.homekit.internal.enums.AudioCodecType;
import org.openhab.binding.homekit.internal.ffmpeg.FfmpegHelpers;
import org.openhab.binding.homekit.internal.media.AudioCodec;

/**
 * Tests for {@link Go2RtcConfig} and {@link Go2RtcInstaller#assetName}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class Go2RtcConfigTest {

    private static final AudioCodec ALAW = new AudioCodec(AudioCodecType.PCMA, 1, 8);

    @Test
    void yamlContainsPortsStreamsAndExecPrefix() {
        String yaml = Go2RtcConfig.toYaml(new Go2RtcConfig.Ports(1984, 8555, 18554), "cam",
                FfmpegHelpers.go2rtcListenCommand("ffmpeg", "/tmp/a.sdp", "/tmp/v.sdp", ALAW), "cam_talk",
                FfmpegHelpers.go2rtcTalkCommand("ffmpeg", ALAW, 110, 1L, 6000));
        assertTrue(yaml.contains("api:\n  listen: \":1984\"\n"));
        assertTrue(yaml.contains("webrtc:\n  listen: \":8555\"\n"));
        assertTrue(yaml.contains("rtsp:\n  listen: \"127.0.0.1:18554\"\n"));
        assertTrue(yaml.contains("  cam:\n    - \"exec:ffmpeg "));
        assertTrue(yaml.contains("  cam_talk:\n    - \"exec:ffmpeg "));
    }

    @Test
    void talkExecEndsWithBackchannelFragment() {
        String yaml = Go2RtcConfig.toYaml(new Go2RtcConfig.Ports(1984, 8555, 18554), "cam",
                FfmpegHelpers.go2rtcListenCommand("ffmpeg", "/tmp/a.sdp", "/tmp/v.sdp", ALAW), "cam_talk",
                FfmpegHelpers.go2rtcTalkCommand("ffmpeg", ALAW, 110, 1L, 6000));
        assertTrue(yaml.contains("#backchannel=1#audio=alaw/8000\""));
    }

    @Test
    void yamlOmitsTalkStreamWhenNotAvailable() {
        String yaml = Go2RtcConfig.toYaml(new Go2RtcConfig.Ports(1984, 8555, 18554), "cam",
                FfmpegHelpers.go2rtcListenCommand("ffmpeg", "/tmp/a.sdp", "/tmp/v.sdp", ALAW), null, null);
        assertFalse(yaml.contains("cam_talk"));
        assertFalse(yaml.contains("backchannel"));
        assertTrue(yaml.endsWith("\"\n"));
    }

    @Test
    void assetNameForLinuxIsRawBinary() throws IOException {
        assertEquals("go2rtc_linux_amd64", Go2RtcInstaller.assetName("Linux", "amd64"));
        assertEquals("go2rtc_linux_amd64", Go2RtcInstaller.assetName("Linux", "x86_64"));
        assertEquals("go2rtc_linux_arm64", Go2RtcInstaller.assetName("Linux", "aarch64"));
    }

    @Test
    void assetNameForOtherPlatformsIsZip() throws IOException {
        assertEquals("go2rtc_win64.zip", Go2RtcInstaller.assetName("Windows 11", "amd64"));
        assertEquals("go2rtc_mac_arm64.zip", Go2RtcInstaller.assetName("Mac OS X", "aarch64"));
        assertEquals("go2rtc_mac_amd64.zip", Go2RtcInstaller.assetName("Mac OS X", "x86_64"));
    }

    @Test
    void assetNameRejectsUnsupportedPlatform() {
        assertThrows(IOException.class, () -> Go2RtcInstaller.assetName("Linux", "riscv64"));
    }
}
