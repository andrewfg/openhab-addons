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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.homekit.internal.enums.AudioCodecType;
import org.openhab.binding.homekit.internal.enums.SrtpCryptoSuite;
import org.openhab.binding.homekit.internal.media.AudioSupportedCodec;
import org.openhab.binding.homekit.internal.media.tlv.SrtpParameters;

/**
 * Helpers for building ffmpeg commands to bridge HomeKit camera audio and video streams.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public final class FfmpegHelpers {

    private FfmpegHelpers() {
    }

    /**
     * Returns the SRTP master key and salt concatenated and base64 encoded, as expected by ffmpeg's
     * {@code srtp_in_params} and {@code srtp_out_params} options.
     *
     * @param params the negotiated SRTP parameters; must not use {@link SrtpCryptoSuite#NONE}
     * @throws IllegalArgumentException if the crypto suite is {@link SrtpCryptoSuite#NONE}
     */
    public static String toFfmpegParams(SrtpParameters params) {
        if (params.cryptoSuite() == SrtpCryptoSuite.NONE) {
            throw new IllegalArgumentException("SRTP crypto suite NONE cannot be bridged");
        }
        byte[] combined = ByteBuffer.allocate(params.masterKey().length + params.masterSalt().length)
                .put(params.masterKey()).put(params.masterSalt()).array();
        return Base64.getEncoder().encodeToString(combined);
    }

    /**
     * Returns the ffmpeg crypto suite name for an SRTP suite.
     *
     * @throws IllegalArgumentException if the crypto suite is {@link SrtpCryptoSuite#NONE}
     */
    public static String toFfmpegSuite(SrtpCryptoSuite suite) {
        return switch (suite) {
            case AES_CM_128_HMAC_SHA1_80, AES_CM_256_HMAC_SHA1_80 -> suite.name();
            case NONE -> throw new IllegalArgumentException("SRTP crypto suite NONE cannot be bridged");
        };
    }

    /**
     * Returns true if the codec is the 8 kHz mono A-law stream that go2rtc uses on both pipes, so its packets can be
     * copied without transcoding.
     */
    static boolean isGo2rtcAlaw(AudioSupportedCodec codec) {
        return codec.codecType() == AudioCodecType.PCMA && codec.rtpClockRate() == 8000 && codec.channels() == 1;
    }

    /**
     * Returns the ffmpeg encoder that transcodes between a declared codec and go2rtc's A-law stream.
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
     * Builds the listen command: SRTP camera audio described by {@code sdpFile} in, 8 kHz mono A-law on stdout. A-law
     * packets are copied unchanged, other codecs are decoded and encoded to A-law.
     *
     * @param ffmpeg the ffmpeg executable, see {@link FfmpegInstaller}
     * @param sdpFile path to an SDP file describing the camera's RTP audio stream and its UDP port
     * @param srtp the camera's SRTP parameters for this audio stream
     * @param codec the declared codec of the stream
     */
    public static List<String> listenCommand(String ffmpeg, String sdpFile, SrtpParameters srtp,
            AudioSupportedCodec codec) {
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "warning",
                "-protocol_whitelist", "file,rtp,udp,srtp", "-srtp_in_suite", toFfmpegSuite(srtp.cryptoSuite()),
                "-srtp_in_params", toFfmpegParams(srtp), "-i", sdpFile, "-vn"));
        if (isGo2rtcAlaw(codec)) {
            command.addAll(List.of("-c:a", "copy"));
        } else {
            command.addAll(List.of("-ar", "8000", "-ac", "1", "-c:a", "pcm_alaw"));
        }
        command.addAll(List.of("-f", "alaw", "pipe:1"));
        return List.copyOf(command);
    }

    /**
     * Builds the video watch command: SRTP camera video described by {@code sdpFile} in, an H.264 Annex B elementary
     * stream on stdout with the packets copied unchanged.
     *
     * @param ffmpeg the ffmpeg executable, see {@link FfmpegInstaller}
     * @param sdpFile path to an SDP file describing the camera's RTP video stream and its UDP port
     * @param srtp the camera's SRTP parameters for this video stream
     */
    public static List<String> watchCommand(String ffmpeg, String sdpFile, SrtpParameters srtp) {
        return List.of(ffmpeg, "-hide_banner", "-loglevel", "warning", "-protocol_whitelist", "file,rtp,udp,srtp",
                "-srtp_in_suite", toFfmpegSuite(srtp.cryptoSuite()), "-srtp_in_params", toFfmpegParams(srtp), "-i",
                sdpFile, "-an", "-c:v", "copy", "-f", "h264", "pipe:1");
    }

    /**
     * Builds the talk command: 8 kHz mono A-law on stdin in, SRTP audio to the camera endpoint. A-law packets are
     * copied unchanged, other codecs are encoded from A-law. Raw G.711 input has no header, so its sample rate and
     * channel count are given explicitly.
     *
     * @param ffmpeg the ffmpeg executable, see {@link FfmpegInstaller}
     * @param remoteHost the camera's IP address
     * @param remotePort the camera's audio RTP port
     * @param localPort the local UDP port used as the RTP source
     * @param srtp the SRTP parameters negotiated for the talk-back session
     * @param payloadType the RTP payload type agreed with the camera
     * @param ssrc the RTP SSRC
     * @param codec the declared codec of the stream
     */
    public static List<String> talkCommand(String ffmpeg, String remoteHost, int remotePort, int localPort,
            SrtpParameters srtp, int payloadType, long ssrc, AudioSupportedCodec codec) {
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "warning", "-f", "alaw",
                "-ar", "8000", "-ac", "1", "-i", "pipe:0", "-vn"));
        if (isGo2rtcAlaw(codec)) {
            command.addAll(List.of("-c:a", "copy"));
        } else {
            command.addAll(List.of("-ar", String.valueOf(codec.rtpClockRate()), "-ac", String.valueOf(codec.channels()),
                    "-c:a", encoder(codec.codecType())));
            if (codec.codecType() == AudioCodecType.OPUS) {
                command.addAll(List.of("-b:a", codec.maxBitrateKbps() + "k"));
            }
        }
        command.addAll(List.of("-payload_type", String.valueOf(payloadType), "-ssrc", String.valueOf(ssrc),
                "-srtp_out_suite", toFfmpegSuite(srtp.cryptoSuite()), "-srtp_out_params", toFfmpegParams(srtp), "-f",
                "rtp", "rtp://" + remoteHost + ":" + remotePort + "?localport=" + localPort));
        return List.copyOf(command);
    }
}
