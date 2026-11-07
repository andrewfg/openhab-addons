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
package org.openhab.binding.homekit.internal.ffmpeg;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.homekit.internal.enums.AudioCodecType;
import org.openhab.binding.homekit.internal.media.AudioCodec;

/**
 * Helpers for building the two ffmpeg commands that go2rtc runs. Both work on plain RTP only. SRTP is handled in Java
 * by the go2rtc data pump, so ffmpeg never sees keys.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public final class FfmpegHelpers {

    private FfmpegHelpers() {
    }

    /**
     * Returns true if the codec is the 8 kHz mono A-law stream that go2rtc uses, so its packets can be copied without
     * transcoding.
     */
    static boolean isGo2rtcAlaw(AudioCodec codec) {
        return codec.codecType() == AudioCodecType.PCMA && codec.rtpClockRate() == 8000 && codec.channels() == 1;
    }

    /**
     * Returns the ffmpeg encoder that produces the camera's codec from go2rtc's A-law stream.
     *
     * @throws IllegalArgumentException if the codec cannot be encoded
     */
    static String encoder(AudioCodecType codec) {
        return switch (codec) {
            case OPUS -> "libopus";
            case PCMU -> "pcm_mulaw";
            case PCMA -> "pcm_alaw";
            case AMR -> "libopencore_amrnb";
            case AMR_WB -> "libvo_amrwb";
            case AAC_ELD, MSBC -> throw new IllegalArgumentException("Unsupported codec: " + codec);
        };
    }

    /**
     * Builds the forward command. It reads the camera's video and audio RTP from two SDP files on loopback and writes
     * them as one RTSP stream to go2rtc. Video is copied. Audio is copied when it is already go2rtc's A-law, otherwise
     * it is decoded and encoded to A-law.
     *
     * @param ffmpeg the ffmpeg executable, see {@link FfmpegInstaller}
     * @param audioSdpFile path to the SDP file describing the camera audio RTP stream
     * @param videoSdpFile path to the SDP file describing the camera video RTP stream
     * @param audioCodec the camera audio codec carried by the RTP stream
     * @return the command, with {@code {output}} as the RTSP output placeholder that go2rtc replaces
     */
    public static List<String> go2rtcListenCommand(String ffmpeg, String audioSdpFile, String videoSdpFile,
            AudioCodec audioCodec) {
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "warning",
                "-protocol_whitelist", "file,rtp,udp", "-i", audioSdpFile, "-protocol_whitelist", "file,rtp,udp", "-i",
                videoSdpFile, "-map", "1:v", "-map", "0:a", "-c:v", "copy"));
        if (isGo2rtcAlaw(audioCodec)) {
            command.addAll(List.of("-c:a", "copy"));
        } else {
            command.addAll(List.of("-ar", "8000", "-ac", "1", "-c:a", "pcm_alaw"));
        }
        command.addAll(List.of("-f", "rtsp", "{output}"));
        return List.copyOf(command);
    }

    /**
     * Builds the back command. It reads go2rtc's A-law audio from stdin and writes plain RTP to a loopback port. The
     * audio is copied when it is already A-law, otherwise it is encoded to the camera's codec. The camera's payload
     * type and SSRC are set so the binding can encrypt the packets without changing them.
     *
     * @param ffmpeg the ffmpeg executable, see {@link FfmpegInstaller}
     * @param audioCodec the camera audio codec to send
     * @param payloadType the RTP payload type agreed with the camera
     * @param ssrc the RTP SSRC
     * @param localRtpPort the loopback UDP port the binding listens on
     * @return the command
     */
    public static List<String> go2rtcTalkCommand(String ffmpeg, AudioCodec audioCodec, int payloadType, long ssrc,
            int localRtpPort) {
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "warning", "-f", "alaw",
                "-ar", "8000", "-ac", "1", "-i", "pipe:0", "-vn"));
        if (isGo2rtcAlaw(audioCodec)) {
            command.addAll(List.of("-c:a", "copy"));
        } else {
            command.addAll(List.of("-ar", String.valueOf(audioCodec.rtpClockRate()), "-ac",
                    String.valueOf(audioCodec.channels()), "-c:a", encoder(audioCodec.codecType())));
            if (audioCodec.codecType() == AudioCodecType.OPUS) {
                command.addAll(List.of("-b:a", audioCodec.maxBitrateKbps() + "k"));
            }
        }
        command.addAll(List.of("-payload_type", String.valueOf(payloadType), "-ssrc", String.valueOf(ssrc), "-f",
                "rtp", "rtp://127.0.0.1:" + localRtpPort));
        return List.copyOf(command);
    }
}
