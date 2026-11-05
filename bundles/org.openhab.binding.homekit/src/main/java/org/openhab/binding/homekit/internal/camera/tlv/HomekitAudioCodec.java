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
package org.openhab.binding.homekit.internal.camera.tlv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homekit.internal.crypto.Tlv8Codec;
import org.openhab.binding.homekit.internal.enums.AudioCodecType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An audio codec configuration that a HomeKit camera declares it can send and receive,
 * as found in its SupportedAudioStreamConfiguration characteristic.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public record HomekitAudioCodec(AudioCodecType codec, int channels, int sampleRateKhz) {

    private static final Logger LOGGER = LoggerFactory.getLogger(HomekitAudioCodec.class);

    private static final int TYPE_AUDIO_CODEC_CONFIGURATION = 0x01;
    private static final int TYPE_CODEC_TYPE = 0x01;
    private static final int TYPE_CODEC_PARAMETERS = 0x02;
    private static final int TYPE_CHANNEL = 0x01;
    private static final int TYPE_SAMPLE_RATE = 0x03;

    /**
     * Returns the audio codec configurations declared by a SupportedAudioStreamConfiguration TLV8 value. Codecs that
     * the binding cannot bridge with stream copy, and sample rates a codec does not allow, are skipped.
     */
    public static List<HomekitAudioCodec> decodeSupportedConfiguration(byte[] tlv) {
        List<HomekitAudioCodec> result = new ArrayList<>();
        // The codec entries share one tag, so they are read in sequence rather than through Tlv8Codec.decode(),
        // which would merge them.
        int offset = 0;
        while (offset + 2 <= tlv.length) {
            int type = tlv[offset] & 0xFF;
            int start = offset + 2;
            int end = Math.min(start + (tlv[offset + 1] & 0xFF), tlv.length);
            if (type == TYPE_AUDIO_CODEC_CONFIGURATION) {
                decodeEntry(Arrays.copyOfRange(tlv, start, end), result);
            }
            offset = end;
        }
        return result;
    }

    /**
     * Returns the RTP clock rate in Hz, which equals the sample rate.
     */
    public int rtpClockRate() {
        return sampleRateKhz * 1000;
    }

    /**
     * Returns the maximum bit rate in kbps that the RTP parameters of a selected stream may request.
     */
    public int maxBitrateKbps() {
        return switch (codec) {
            case OPUS -> 24;
            case PCMU, PCMA -> 64;
            case AMR -> 13;
            case AMR_WB -> 24;
            case AAC_ELD, MSBC -> throw new IllegalStateException("Unsupported codec: " + codec);
        };
    }

    private static void decodeEntry(byte[] entry, List<HomekitAudioCodec> result) {
        Map<Integer, byte[]> fields = Tlv8Codec.decode(entry);
        byte[] typeValue = fields.get(TYPE_CODEC_TYPE);
        byte[] parameters = fields.get(TYPE_CODEC_PARAMETERS);
        if (typeValue == null || typeValue.length != 1 || parameters == null) {
            return;
        }
        AudioCodecType codec = codecOf(typeValue[0] & 0xFF);
        if (codec == null) {
            LOGGER.debug("Ignoring camera audio codec with unknown type {}", typeValue[0] & 0xFF);
            return;
        }
        int[] allowedRates = allowedSampleRatesKhz(codec);
        if (allowedRates.length == 0) {
            LOGGER.debug("Ignoring camera audio codec {} that the binding cannot bridge", codec);
            return;
        }
        Map<Integer, byte[]> codecParameters = Tlv8Codec.decode(parameters);
        byte[] channelValue = codecParameters.get(TYPE_CHANNEL);
        int channels = channelValue == null || channelValue.length == 0 ? 1 : channelValue[0] & 0xFF;
        byte[] rateCodes = codecParameters.get(TYPE_SAMPLE_RATE);
        if (rateCodes == null) {
            return;
        }
        for (byte rateCode : rateCodes) {
            int sampleRateKhz = sampleRateKhz(rateCode & 0xFF);
            HomekitAudioCodec candidate = new HomekitAudioCodec(codec, channels, sampleRateKhz);
            if (Arrays.stream(allowedRates).anyMatch(rate -> rate == sampleRateKhz) && !result.contains(candidate)) {
                result.add(candidate);
            }
        }
    }

    private static @Nullable AudioCodecType codecOf(int value) {
        for (AudioCodecType codec : AudioCodecType.values()) {
            if (codec.value == value) {
                return codec;
            }
        }
        return null;
    }

    private static int[] allowedSampleRatesKhz(AudioCodecType codec) {
        return switch (codec) {
            case OPUS -> new int[] { 8, 16, 24 };
            case PCMU, PCMA, AMR -> new int[] { 8 };
            case AMR_WB -> new int[] { 16 };
            case AAC_ELD, MSBC -> new int[0];
        };
    }

    private static int sampleRateKhz(int code) {
        return switch (code) {
            case 0x00 -> 8;
            case 0x01 -> 16;
            case 0x02 -> 24;
            default -> -1;
        };
    }
}
