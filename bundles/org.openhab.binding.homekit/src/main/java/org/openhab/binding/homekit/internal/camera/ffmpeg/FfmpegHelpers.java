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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.homekit.internal.camera.AudioCodec;
import org.openhab.binding.homekit.internal.enums.AudioCodecType;

/**
 * Helpers for building the forward and back ffmpeg instances used by go2rtc.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public final class FfmpegHelpers {

    private FfmpegHelpers() {
    }

    /**
     * Returns true if the codec is the 8 kHz mono A-law stream that go2rtc uses (avoid transcoding).
     */
    static boolean isGo2rtcAlaw(AudioCodec codec) {
        return codec.codecType() == AudioCodecType.PCMA && codec.rtpClockRate() == 8000 && codec.channels() == 1;
    }

    /**
     * Returns the ffmpeg encoder setting to convert go2rtc's A-law stream to the camera's required codec format.
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
     * Builds the command line for the listener ffmpeg instance that reads the camera's RTP streams and outputs
     * an RTSP stream for go2rtc. The audio is transcoded to A-law unless it is already A-law, and the video is
     * copied. The RTSP {@code {output}} is a placeholder that go2rtc replaces with its own RTSP URL.
     *
     * @param ffmpeg the ffmpeg executable, see {@link FfmpegInstaller}
     * @param audioSdpFile path to the SDP file describing the camera audio RTP stream
     * @param videoSdpFile path to the SDP file describing the camera video RTP stream
     * @param audioCodec the camera audio codec carried by the RTP stream
     * @return the command, with {@code {output}} as the RTSP output placeholder that go2rtc replaces
     */
    public static List<String> go2rtcListenCommandLine(String ffmpeg, String audioSdpFile, String videoSdpFile,
            AudioCodec audioCodec) {
        // @formatter:off
        List<String> command = new ArrayList<>(List.of(ffmpeg,
                "-hide_banner", 
                "-loglevel", "warning",
                "-protocol_whitelist", "file,rtp,udp", 
                "-i", audioSdpFile, 
                "-protocol_whitelist", "file,rtp,udp", 
                "-i", videoSdpFile, 
                "-map", "1:v", 
                "-map", "0:a", 
                "-c:v", "copy"
        ));
        if (isGo2rtcAlaw(audioCodec)) {
            command.addAll(List.of(
                "-c:a", "copy"
            ));
        } else {
            command.addAll(List.of(
                "-ar", "8000", 
                "-ac", "1", 
                "-c:a", "pcm_alaw"
                ));
        }
        command.addAll(List.of(
                "-rtsp_transport", "tcp",
                "-f", "rtsp", 
                "{output}"
            ));
        // @formatter:on
        return List.copyOf(command);
    }

    /**
     * Builds the command line for the talk ffmpeg instance that reads go2rtc's A-law audio from stdin and outputs
     * RTP to the camera. The audio is copied if it is already A-law, otherwise it is encoded to the camera's codec.
     *
     * @param ffmpeg the ffmpeg executable, see {@link FfmpegInstaller}
     * @param audioCodec the camera audio codec to send
     * @param payloadType the RTP payload type agreed with the camera
     * @param syncSource the RTP SSRC
     * @param localRtpPort the loopback UDP port the binding listens on
     * @return the command
     */
    public static List<String> go2rtcTalkCommandLine(String ffmpeg, AudioCodec audioCodec, int payloadType, long ssrc,
            int localRtpPort) {
        // @formatter:off
        List<String> command = new ArrayList<>(List.of(ffmpeg,
                "-hide_banner", 
                "-loglevel", "warning", 
                "-f", "alaw",
                "-ar", "8000", 
                "-ac", "1",
                "-i", "pipe:0", 
                "-vn"
        ));
        if (isGo2rtcAlaw(audioCodec)) {
            command.addAll(List.of(
                "-c:a", "copy"
            ));
        } else {
            command.addAll(List.of(
                "-ar", String.valueOf(audioCodec.rtpClockRate()),
                "-ac", String.valueOf(audioCodec.channels()),
                "-c:a", encoder(audioCodec.codecType())
            ));
            if (audioCodec.codecType() == AudioCodecType.OPUS) {
                command.addAll(List.of(
                "-b:a", audioCodec.maxBitrateKbps() + "k"
                ));
            }
        }
        command.addAll(List.of(
                "-payload_type", String.valueOf(payloadType), 
                "-ssrc", String.valueOf(ssrc),
                "-f", "rtp", "rtp://127.0.0.1:" + localRtpPort,
                "#backchannel=1#audio=alaw/8000"
        ));
        // @formatter:on
        return List.copyOf(command);
    }
}
