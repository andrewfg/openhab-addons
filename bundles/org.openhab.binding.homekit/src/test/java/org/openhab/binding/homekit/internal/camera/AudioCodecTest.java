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
package org.openhab.binding.homekit.internal.camera;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openhab.binding.homekit.internal.enums.AudioCodecType;

/**
 * Tests for {@link AudioCodec} decoding of SupportedAudioStreamConfiguration.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class AudioCodecTest {

    @Test
    void testDecodesEveryDeclaredCodecAndRate() {
        byte[] tlv = tlv(entry(AudioCodecType.OPUS, 1, 0x00, 0x01, 0x02), entry(AudioCodecType.PCMU, 1, 0x00));

        List<AudioCodec> codecs = AudioCodec.decode(tlv);

        assertEquals(List.of(new AudioCodec(AudioCodecType.OPUS, 1, 8), new AudioCodec(AudioCodecType.OPUS, 1, 16),
                new AudioCodec(AudioCodecType.OPUS, 1, 24), new AudioCodec(AudioCodecType.PCMU, 1, 8)), codecs);
    }

    @Test
    void testSkipsCodecsThatCannotBeBridged() {
        byte[] tlv = tlv(entry(AudioCodecType.AAC_ELD, 1, 0x02), entry(AudioCodecType.MSBC, 1, 0x00),
                entry(AudioCodecType.PCMA, 1, 0x01));

        assertEquals(List.of(), AudioCodec.decode(tlv));
    }

    @Test
    void testRejectsRatesThatCodecDoesNotAllow() {
        byte[] tlv = tlv(entry(AudioCodecType.AMR_WB, 1, 0x00, 0x01), entry(AudioCodecType.PCMU, 1, 0x01));

        assertEquals(List.of(new AudioCodec(AudioCodecType.AMR_WB, 1, 16)), AudioCodec.decode(tlv));
    }

    @Test
    void testReadsRepeatedCodecEntriesInSequence() {
        byte[] tlv = tlv(entry(AudioCodecType.AMR, 1, 0x00), entry(AudioCodecType.AMR, 1, 0x00));

        assertEquals(List.of(new AudioCodec(AudioCodecType.AMR, 1, 8)), AudioCodec.decode(tlv));
    }

    @Test
    void testIgnoresUnknownCodecType() {
        byte[] tlv = tlv(entry(0x7F, 1, 0x00), entry(AudioCodecType.PCMU, 1, 0x00));

        assertEquals(List.of(new AudioCodec(AudioCodecType.PCMU, 1, 8)), AudioCodec.decode(tlv));
    }

    @Test
    void testRtpClockRateAndBitrate() {
        assertEquals(16000, new AudioCodec(AudioCodecType.AMR_WB, 1, 16).rtpClockRate());
        assertEquals(24, new AudioCodec(AudioCodecType.OPUS, 1, 24).maxBitrateKbps());
    }

    /** Builds one AUDIO_CODEC_CONFIGURATION entry with the given codec, channel count and sample-rate codes. */
    private static byte[] entry(AudioCodecType codec, int channels, int... sampleRateCodes) {
        return entry(codec.value, channels, sampleRateCodes);
    }

    private static byte[] entry(int codecType, int channels, int... sampleRateCodes) {
        ByteArrayOutputStream rates = new ByteArrayOutputStream();
        for (int code : sampleRateCodes) {
            rates.write(0x03);
            rates.write(1);
            rates.write(code);
        }
        byte[] parameters = concat(new byte[] { 0x01, 1, (byte) channels }, rates.toByteArray());
        byte[] body = concat(new byte[] { 0x01, 1, (byte) codecType },
                concat(new byte[] { 0x02 }, concat(new byte[] { (byte) parameters.length }, parameters)));
        return concat(new byte[] { 0x01, (byte) body.length }, body);
    }

    private static byte[] tlv(byte[]... entries) {
        byte[] result = new byte[0];
        for (byte[] entry : entries) {
            result = concat(result, entry);
        }
        return result;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
