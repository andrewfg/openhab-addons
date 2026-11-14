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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Splits an RFC 3640 AAC-hbr RTP packet, which may carry several access units, into one RTP packet per access unit.
 * Each output packet carries a single AU header with a 13-bit size and 3-bit index, and its own sequence number and
 * timestamp. Input packets that do not match the expected layout are returned unchanged.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
final class AacEldRtpSplitter {

    private static final int RTP_HEADER_LENGTH = 12;
    private static final int AU_HEADERS_LENGTH_FIELD = 2;
    private static final int AU_HEADER_LENGTH = 2;
    private static final int SAMPLES_PER_ACCESS_UNIT = 480;

    private int sequence;

    /**
     * @param data the RTP packet bytes
     * @param length the number of valid bytes in {@code data}
     * @return one RTP packet per access unit, or a copy of the input when it cannot be split
     */
    List<byte[]> split(byte[] data, int length) {
        List<byte[]> packets = new ArrayList<>();
        // version 2, no padding, no extension, no CSRC
        if (length < RTP_HEADER_LENGTH + AU_HEADERS_LENGTH_FIELD || data[0] != (byte) 0x80) {
            packets.add(Arrays.copyOf(data, length));
            return packets;
        }
        int headersBits = ((data[RTP_HEADER_LENGTH] & 0xFF) << 8) | (data[RTP_HEADER_LENGTH + 1] & 0xFF);
        int count = headersBits / 16;
        int offset = RTP_HEADER_LENGTH + AU_HEADERS_LENGTH_FIELD + count * AU_HEADER_LENGTH;
        if (count == 0 || offset > length) {
            packets.add(Arrays.copyOf(data, length));
            return packets;
        }
        long timestamp = ((data[4] & 0xFFL) << 24) | ((data[5] & 0xFF) << 16) | ((data[6] & 0xFF) << 8)
                | (data[7] & 0xFF);
        for (int i = 0; i < count; i++) {
            int headerIndex = RTP_HEADER_LENGTH + AU_HEADERS_LENGTH_FIELD + i * AU_HEADER_LENGTH;
            int auHeader = ((data[headerIndex] & 0xFF) << 8) | (data[headerIndex + 1] & 0xFF);
            int size = auHeader >>> 3;
            if (offset + size > length) {
                packets.clear();
                packets.add(Arrays.copyOf(data, length));
                return packets;
            }
            boolean last = i == count - 1;
            byte[] packet = new byte[RTP_HEADER_LENGTH + AU_HEADERS_LENGTH_FIELD + AU_HEADER_LENGTH + size];
            System.arraycopy(data, 0, packet, 0, RTP_HEADER_LENGTH);
            // only the last packet of a frame carries the marker bit
            packet[1] = (byte) (data[1] & (last ? 0xFF : 0x7F));
            packet[2] = (byte) (sequence >>> 8);
            packet[3] = (byte) sequence;
            sequence = (sequence + 1) & 0xFFFF;
            long auTimestamp = (timestamp + (long) i * SAMPLES_PER_ACCESS_UNIT) & 0xFFFFFFFFL;
            packet[4] = (byte) (auTimestamp >>> 24);
            packet[5] = (byte) (auTimestamp >>> 16);
            packet[6] = (byte) (auTimestamp >>> 8);
            packet[7] = (byte) auTimestamp;
            packet[RTP_HEADER_LENGTH] = 0;
            packet[RTP_HEADER_LENGTH + 1] = (byte) (AU_HEADER_LENGTH * 8);
            packet[RTP_HEADER_LENGTH + 2] = (byte) (size >>> 5);
            packet[RTP_HEADER_LENGTH + 3] = (byte) (size << 3);
            System.arraycopy(data, offset, packet, RTP_HEADER_LENGTH + AU_HEADERS_LENGTH_FIELD + AU_HEADER_LENGTH,
                    size);
            offset += size;
            packets.add(packet);
        }
        return packets;
    }
}
