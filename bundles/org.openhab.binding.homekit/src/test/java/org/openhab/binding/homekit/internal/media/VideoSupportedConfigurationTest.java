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
package org.openhab.binding.homekit.internal.media;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link VideoSupportedConfiguration#decode(byte[])}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class VideoSupportedConfigurationTest {

    @Test
    void decodesHighestProfileAndLevelAndFirstAttribute() {
        byte[] params = concat(tlv(0x01, 0x00), tlv(0x01, 0x02), tlv(0x02, 0x00), tlv(0x02, 0x02));
        byte[] attribute = concat(tlv(0x01, 0x80, 0x07), tlv(0x02, 0x38, 0x04), tlv(0x03, 30));
        byte[] entry = concat(tlv(0x01, 0x00), tlv(0x02, params), tlv(0x03, attribute), tlv(0x03, attribute));

        Optional<VideoSupportedConfiguration> config = VideoSupportedConfiguration.decode(tlv(0x01, entry));

        assertTrue(config.isPresent());
        assertEquals(new VideoSupportedConfiguration(2, 2, 1920, 1080, 30), config.get());
    }

    @Test
    void skipsNonH264EntriesAndUsesNextH264Entry() {
        byte[] params = concat(tlv(0x01, 0x01), tlv(0x02, 0x00));
        byte[] attribute = concat(tlv(0x01, 0x40, 0x01), tlv(0x02, 0xF0, 0x00), tlv(0x03, 15));
        byte[] nonH264 = concat(tlv(0x01, 0x01), tlv(0x02, params), tlv(0x03, attribute));
        byte[] h264 = concat(tlv(0x01, 0x00), tlv(0x02, params), tlv(0x03, attribute));

        Optional<VideoSupportedConfiguration> config = VideoSupportedConfiguration
                .decode(concat(tlv(0x01, nonH264), tlv(0x01, h264)));

        assertEquals(new VideoSupportedConfiguration(1, 0, 320, 240, 15), config.orElseThrow());
    }

    @Test
    void returnsEmptyWithoutH264() {
        byte[] params = concat(tlv(0x01, 0x00), tlv(0x02, 0x00));
        byte[] attribute = concat(tlv(0x01, 0x80, 0x07), tlv(0x02, 0x38, 0x04), tlv(0x03, 30));
        byte[] entry = concat(tlv(0x01, 0x01), tlv(0x02, params), tlv(0x03, attribute));

        assertTrue(VideoSupportedConfiguration.decode(tlv(0x01, entry)).isEmpty());
    }

    @Test
    void returnsEmptyForEmptyOrTruncatedInput() {
        assertTrue(VideoSupportedConfiguration.decode(new byte[0]).isEmpty());
        assertTrue(VideoSupportedConfiguration.decode(new byte[] { 0x01, 0x05, 0x00 }).isEmpty());
    }

    private static byte[] tlv(int type, int... value) {
        byte[] bytes = new byte[value.length];
        for (int i = 0; i < value.length; i++) {
            bytes[i] = (byte) value[i];
        }
        return tlv(type, bytes);
    }

    private static byte[] tlv(int type, byte... value) {
        return concat(new byte[] { (byte) type, (byte) value.length }, value);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
