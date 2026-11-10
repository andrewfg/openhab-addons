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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openhab.binding.homekit.internal.camera.codec.AudioCodec;
import org.openhab.binding.homekit.internal.camera.codec.VideoCodec;
import org.openhab.binding.homekit.internal.enums.AudioCodecType;
import org.openhab.binding.homekit.internal.enums.VideoCodecType;

/**
 * Tests for {@link Go2RtcDataPump} codec selection and port allocation.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class Go2RtcDataPumpTest {

    private static final AudioCodec OPUS = new AudioCodec(AudioCodecType.OPUS, 1, 16);
    private static final AudioCodec PCMA = new AudioCodec(AudioCodecType.PCMA, 1, 8);
    private static final VideoCodec H264 = new VideoCodec(VideoCodecType.H_264, 0, 0, 640, 480, 30);

    @Test
    void testSelectsOpusAudioAndH264Video() throws IOException {
        try (Go2RtcDataPump pump = new Go2RtcDataPump(List.of(PCMA, OPUS), List.of(H264))) {
            assertEquals(AudioCodecType.OPUS, pump.audioCodec().codecType());
            assertEquals(VideoCodecType.H_264, pump.videoCodec().codecType());
        }
    }

    @Test
    void testAllocatesDistinctPorts() throws IOException {
        try (Go2RtcDataPump pump = new Go2RtcDataPump(List.of(OPUS), List.of(H264))) {
            assertTrue(pump.audioPort() > 0);
            assertTrue(pump.videoPort() > 0);
            assertNotEquals(pump.audioPort(), pump.videoPort());
        }
    }

    @Test
    void testRejectsCameraWithoutOpus() {
        assertThrows(IllegalArgumentException.class, () -> new Go2RtcDataPump(List.of(PCMA), List.of(H264)));
    }

    @Test
    void testRejectsCameraWithoutH264() {
        assertThrows(IllegalArgumentException.class, () -> new Go2RtcDataPump(List.of(OPUS), List.of()));
    }
}
