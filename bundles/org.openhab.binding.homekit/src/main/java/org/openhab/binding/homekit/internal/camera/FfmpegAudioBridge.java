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

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.homekit.internal.enums.SrtpCryptoSuite;

/**
 * Builds ffmpeg command lines that bridge camera audio over SRTP to and from stdin/stdout pipes. ffmpeg never decodes
 * or encodes audio: it only decrypts or encrypts SRTP and remuxes between RTP and Ogg, copying the native Opus packets.
 * <p>
 * Listen: ffmpeg reads the camera's SRTP Opus (described by an SDP file) and writes it as Ogg Opus to stdout.
 * Talk: ffmpeg reads Ogg Opus from stdin and writes it as SRTP Opus to the camera endpoint.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public final class FfmpegAudioBridge {

    private static final String FFMPEG = "ffmpeg";

    private FfmpegAudioBridge() {
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
        byte[] combined = ByteBuffer.allocate(srtp.masterKey().length + srtp.masterSalt().length)
                .put(srtp.masterKey()).put(srtp.masterSalt()).array();
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
     * Builds the listen command: SRTP camera Opus described by {@code sdpFile} in, Ogg Opus out on stdout with the
     * packets copied unchanged.
     *
     * @param sdpFile path to an SDP file describing the camera's RTP audio stream and its UDP port
     * @param srtp the camera's SRTP parameters for this audio stream
     */
    public static List<String> listenCommand(String sdpFile, SrtpParameters srtp) {
        return List.of(FFMPEG, "-hide_banner", "-loglevel", "warning", "-protocol_whitelist", "file,rtp,udp,srtp",
                "-srtp_in_suite", toFfmpegSuite(srtp.cryptoSuite()), "-srtp_in_params", toFfmpegParams(srtp), "-i",
                sdpFile, "-vn", "-c:a", "copy", "-f", "ogg", "pipe:1");
    }

    /**
     * Builds the talk command: Ogg Opus on stdin in, SRTP Opus to the camera endpoint with the packets copied
     * unchanged.
     *
     * @param remoteHost the camera's IP address
     * @param remotePort the camera's audio RTP port
     * @param localPort the local UDP port used as the RTP source
     * @param srtp the SRTP parameters negotiated for the talk-back session
     * @param payloadType the RTP payload type agreed with the camera
     * @param ssrc the RTP SSRC
     */
    public static List<String> talkCommand(String remoteHost, int remotePort, int localPort, SrtpParameters srtp,
            int payloadType, long ssrc) {
        return List.of(FFMPEG, "-hide_banner", "-loglevel", "warning", "-f", "ogg", "-i", "pipe:0", "-vn", "-c:a",
                "copy", "-payload_type", String.valueOf(payloadType), "-ssrc", String.valueOf(ssrc),
                "-srtp_out_suite", toFfmpegSuite(srtp.cryptoSuite()), "-srtp_out_params", toFfmpegParams(srtp), "-f",
                "rtp", "rtp://" + remoteHost + ":" + remotePort + "?localport=" + localPort);
    }
}
