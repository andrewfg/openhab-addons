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
import org.openhab.binding.homekit.internal.audio.AudioCodec;
import org.openhab.binding.homekit.internal.audio.tlv.SrtpParameters;
import org.openhab.binding.homekit.internal.enums.AudioCodecType;
import org.openhab.binding.homekit.internal.enums.SrtpCryptoSuite;

/**
 * Builds ffmpeg command lines that bridge camera audio over SRTP to and from stdin/stdout pipes.
 * ffmpeg never decodes or encodes audio: it only decrypts or encrypts SRTP and remuxes between RTP
 * and a container, copying the native packets of the declared codec.
 * <p>
 * Listen: ffmpeg reads the camera's SRTP audio (described by an SDP file) and writes it to stdout in
 * the codec's container.
 * Talk: ffmpeg reads the codec's container from stdin and writes it as SRTP audio to the camera end-point.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public final class FfmpegConverter {

    private FfmpegConverter() {
    }

    /**
     * Returns the SRTP master key and salt concatenated and base64 encoded, as expected by ffmpeg's
     * {@code srtp_in_params} and {@code srtp_out_params} options.
     *
     * @param srtp the negotiated SRTP parameters; must not use {@link SrtpCryptoSuite#NONE}
     * @throws IllegalArgumentException if the crypto suite is {@link SrtpCryptoSuite#NONE}
     */
    public static String toFfmpegParams(SrtpParameters srtp) {
        if (srtp.cryptoSuite() == SrtpCryptoSuite.NONE) {
            throw new IllegalArgumentException("SRTP crypto suite NONE cannot be bridged");
        }
        byte[] combined = ByteBuffer.allocate(srtp.masterKey().length + srtp.masterSalt().length).put(srtp.masterKey())
                .put(srtp.masterSalt()).array();
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
     * Returns the ffmpeg container format that carries the packets of a declared codec on the pipes.
     */
    static String containerFormat(AudioCodecType codec) {
        return switch (codec) {
            case OPUS -> "ogg";
            case PCMU -> "mulaw";
            case PCMA -> "alaw";
            case AMR, AMR_WB -> "amr";
            case AAC_ELD, MSBC -> throw new IllegalArgumentException("Unsupported codec: " + codec);
        };
    }

    /**
     * Builds the listen command: SRTP camera audio described by {@code sdpFile} in, the codec's container out on stdout
     * with the packets copied unchanged.
     *
     * @param ffmpeg the ffmpeg executable, see {@link FfmpegInstaller}
     * @param sdpFile path to an SDP file describing the camera's RTP audio stream and its UDP port
     * @param srtp the camera's SRTP parameters for this audio stream
     * @param codec the declared codec of the stream
     */
    public static List<String> listenCommand(String ffmpeg, String sdpFile, SrtpParameters srtp, AudioCodec codec) {
        return List.of(ffmpeg, "-hide_banner", "-loglevel", "warning", "-protocol_whitelist", "file,rtp,udp,srtp",
                "-srtp_in_suite", toFfmpegSuite(srtp.cryptoSuite()), "-srtp_in_params", toFfmpegParams(srtp), "-i",
                sdpFile, "-vn", "-c:a", "copy", "-f", containerFormat(codec.codec()), "pipe:1");
    }

    /**
     * Builds the talk command: the codec's container on stdin in, SRTP audio to the camera endpoint with the packets
     * copied unchanged. Raw G.711 input has no header, so its sample rate and channel count are given explicitly.
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
            SrtpParameters srtp, int payloadType, long ssrc, AudioCodec codec) {
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "warning"));
        if (codec.codec() == AudioCodecType.PCMU || codec.codec() == AudioCodecType.PCMA) {
            command.addAll(
                    List.of("-ar", String.valueOf(codec.rtpClockRate()), "-ac", String.valueOf(codec.channels())));
        }
        command.addAll(List.of("-f", containerFormat(codec.codec()), "-i", "pipe:0", "-vn", "-c:a", "copy",
                "-payload_type", String.valueOf(payloadType), "-ssrc", String.valueOf(ssrc), "-srtp_out_suite",
                toFfmpegSuite(srtp.cryptoSuite()), "-srtp_out_params", toFfmpegParams(srtp), "-f", "rtp",
                "rtp://" + remoteHost + ":" + remotePort + "?localport=" + localPort));
        return List.copyOf(command);
    }
}
