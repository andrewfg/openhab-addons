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

import java.util.HexFormat;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.homekit.internal.camera.codec.AudioCodec;

/**
 * Helpers for building the ffmpeg input SDP and command line that transcode a camera's AAC-ELD audio to Opus.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public final class FfmpegHelpers {

    private static final int AUDIO_OBJECT_TYPE_ESCAPE = 0x1F;
    private static final int AUDIO_OBJECT_TYPE_AAC_ELD = 39;
    private static final int ELD_FRAME_LENGTH_480 = 1;
    private static final int ELD_REMAINING_FLAG_BITS = 8;

    private FfmpegHelpers() {
    }

    /**
     * Returns an SDP description of the camera's AAC-ELD RTP stream, received on the given loopback UDP port.
     *
     * @param port the loopback UDP port on which ffmpeg receives the RTP
     * @param codec the AAC-ELD codec negotiated with the camera
     * @param payloadType the RTP payload type the camera uses
     * @return the SDP text
     */
    public static String aacEldInputSdp(int port, AudioCodec codec, int payloadType) {
        return """
                v=0
                o=- 0 0 IN IP4 127.0.0.1
                s=HomeKit AAC-ELD
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio %d RTP/AVP %d
                a=rtpmap:%d mpeg4-generic/%d/%d
                a=fmtp:%d streamtype=5;profile-level-id=39;mode=AAC-hbr;config=%s;sizelength=13;indexlength=3;indexdeltalength=3;constantDuration=480
                """.formatted(port, payloadType, payloadType, codec.sampleRateKhz() * 1000, codec.channels(),
                payloadType, audioSpecificConfig(codec.sampleRateKhz(), codec.channels()));
    }

    /**
     * Returns the hex encoded AudioSpecificConfig for AAC-ELD with a 480 sample frame, as used in the SDP config
     * parameter (ISO/IEC 14496-3, ELDSpecificConfig without extensions).
     *
     * @param sampleRateKhz the sample rate, either 16 or 24
     * @param channels the channel count, 1 to 15
     * @return the upper case hex string
     * @throws IllegalArgumentException if the sample rate or channel count is unsupported
     */
    static String audioSpecificConfig(int sampleRateKhz, int channels) {
        int samplingFrequencyIndex = switch (sampleRateKhz) {
            case 16 -> 0x8;
            case 24 -> 0x6;
            default -> throw new IllegalArgumentException("Unsupported AAC-ELD sample rate: " + sampleRateKhz);
        };
        if (channels < 1 || channels > 15) {
            throw new IllegalArgumentException("Unsupported AAC-ELD channel count: " + channels);
        }
        StringBuilder bits = new StringBuilder();
        appendBits(bits, AUDIO_OBJECT_TYPE_ESCAPE, 5);
        appendBits(bits, AUDIO_OBJECT_TYPE_AAC_ELD - 32, 6);
        appendBits(bits, samplingFrequencyIndex, 4);
        appendBits(bits, channels, 4);
        appendBits(bits, ELD_FRAME_LENGTH_480, 1);
        appendBits(bits, 0, ELD_REMAINING_FLAG_BITS);
        while (bits.length() % 8 != 0) {
            bits.append('0');
        }
        byte[] bytes = new byte[bits.length() / 8];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
        }
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private static void appendBits(StringBuilder bits, int value, int count) {
        for (int shift = count - 1; shift >= 0; shift--) {
            bits.append((value >> shift) & 1);
        }
    }

    /**
     * Builds the command line for an ffmpeg instance that reads the camera's AAC-ELD RTP described by an SDP file and
     * sends it as Opus RTP to a loopback UDP port.
     *
     * @param ffmpeg the ffmpeg executable, see {@link FfmpegInstaller}
     * @param inputSdpFile path to the SDP file from {@link #aacEldInputSdp}
     * @param outputPort the loopback UDP port that receives the Opus RTP
     * @param payloadType the RTP payload type of the Opus output
     * @return the command
     */
    public static List<String> aacEldToOpusCommandLine(String ffmpeg, String inputSdpFile, int outputPort,
            int payloadType) {
        return List.of(ffmpeg, "-hide_banner", "-loglevel", "warning", "-protocol_whitelist", "file,rtp,udp", "-i",
                inputSdpFile, "-vn", "-c:a", "libopus", "-ar", "48000", "-ac", "2", "-b:a", "48k", "-payload_type",
                String.valueOf(payloadType), "-f", "rtp", "rtp://127.0.0.1:" + outputPort);
    }
}
