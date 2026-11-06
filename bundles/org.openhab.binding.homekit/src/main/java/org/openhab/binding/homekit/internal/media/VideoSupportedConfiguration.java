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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.homekit.internal.crypto.Tlv8Codec;

/**
 * The H.264 configuration chosen from a camera's SupportedVideoStreamConfiguration characteristic: the highest
 * advertised profile and level, and the first advertised image size and frame rate.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public record VideoSupportedConfiguration(int profile, int level, int width, int height, int frameRate) {

    private static final int TYPE_VIDEO_CODEC_CONFIGURATION = 0x01;
    private static final int TYPE_CODEC_TYPE = 0x01;
    private static final int TYPE_CODEC_PARAMETERS = 0x02;
    private static final int TYPE_VIDEO_ATTRIBUTES = 0x03;
    private static final int TYPE_PROFILE_ID = 0x01;
    private static final int TYPE_LEVEL = 0x02;
    private static final int TYPE_IMAGE_WIDTH = 0x01;
    private static final int TYPE_IMAGE_HEIGHT = 0x02;
    private static final int TYPE_FRAME_RATE = 0x03;
    private static final int CODEC_TYPE_H264 = 0x00;

    /**
     * Decodes the first H.264 entry of a SupportedVideoStreamConfiguration value.
     *
     * @param tlv the decoded TLV8 bytes of the characteristic
     * @return the chosen configuration, or empty when the camera does not advertise H.264
     */
    public static Optional<VideoSupportedConfiguration> decode(byte[] tlv) {
        try {
            for (byte[] entry : entries(tlv, TYPE_VIDEO_CODEC_CONFIGURATION)) {
                Optional<VideoSupportedConfiguration> config = decodeEntry(entry);
                if (config.isPresent()) {
                    return config;
                }
            }
        } catch (SecurityException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<VideoSupportedConfiguration> decodeEntry(byte[] entry) {
        Map<Integer, byte[]> fields = Tlv8Codec.decode(entry);
        byte[] codecType = fields.get(TYPE_CODEC_TYPE);
        byte[] parameters = fields.get(TYPE_CODEC_PARAMETERS);
        if (codecType == null || codecType.length != 1 || (codecType[0] & 0xFF) != CODEC_TYPE_H264
                || parameters == null) {
            return Optional.empty();
        }
        Map<Integer, byte[]> codecParameters = Tlv8Codec.decode(parameters);
        byte[] profiles = codecParameters.get(TYPE_PROFILE_ID);
        byte[] levels = codecParameters.get(TYPE_LEVEL);
        List<byte[]> attributes = entries(entry, TYPE_VIDEO_ATTRIBUTES);
        if (profiles == null || profiles.length == 0 || levels == null || levels.length == 0 || attributes.isEmpty()) {
            return Optional.empty();
        }
        Map<Integer, byte[]> attribute = Tlv8Codec.decode(attributes.get(0));
        byte[] widthBytes = attribute.get(TYPE_IMAGE_WIDTH);
        byte[] heightBytes = attribute.get(TYPE_IMAGE_HEIGHT);
        byte[] frameRateBytes = attribute.get(TYPE_FRAME_RATE);
        if (widthBytes == null || widthBytes.length != 2 || heightBytes == null || heightBytes.length != 2
                || frameRateBytes == null || frameRateBytes.length != 1) {
            return Optional.empty();
        }
        int profile = maxUnsigned(profiles);
        int level = maxUnsigned(levels);
        int width = ByteBuffer.wrap(widthBytes).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
        int height = ByteBuffer.wrap(heightBytes).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
        int frameRate = frameRateBytes[0] & 0xFF;
        return Optional.of(new VideoSupportedConfiguration(profile, level, width, height, frameRate));
    }

    /**
     * Returns the value of each occurrence of a tag. Repeated tags must be read in sequence, because
     * {@link Tlv8Codec#decode(byte[])} would merge them.
     */
    private static List<byte[]> entries(byte[] tlv, int tag) {
        List<byte[]> result = new ArrayList<>();
        int offset = 0;
        while (offset + 2 <= tlv.length) {
            int type = tlv[offset] & 0xFF;
            int start = offset + 2;
            int end = Math.min(start + (tlv[offset + 1] & 0xFF), tlv.length);
            if (type == tag) {
                result.add(Arrays.copyOfRange(tlv, start, end));
            }
            offset = end;
        }
        return result;
    }

    private static int maxUnsigned(byte[] values) {
        int max = 0;
        for (byte value : values) {
            max = Math.max(max, value & 0xFF);
        }
        return max;
    }
}
