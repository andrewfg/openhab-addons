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

import java.io.BufferedReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntFunction;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.homekit.internal.camera.codec.AudioCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transcodes RTP audio between a camera's AAC-ELD and Opus using an ffmpeg child process.
 * <p>
 * RTP is sent to ffmpeg on a loopback port described by a temporary SDP file, and ffmpeg sends the transcoded RTP back
 * to a loopback port owned by this class. Each received packet is passed to the {@link PacketSink}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public final class FfmpegRtpTranscoder implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(FfmpegRtpTranscoder.class);

    private static final int MAX_DATAGRAM_SIZE = 2048;
    private static final int SDP_DELETE_ATTEMPTS = 5;
    private static final long SDP_DELETE_RETRY_MS = 200;

    /**
     * Receives RTP packets produced by ffmpeg.
     */
    @FunctionalInterface
    public interface PacketSink {
        void accept(byte[] data, int length) throws IOException;
    }

    private final DatagramSocket inputSocket;
    private final DatagramSocket outputSocket;
    private final InetAddress loopback;
    private final int inputPort;
    private final Path sdpFile;
    private final Process process;
    private volatile boolean closed;
    private boolean firstPacketSent;

    /**
     * Starts ffmpeg and the thread that forwards its output.
     *
     * @param ffmpeg the ffmpeg executable
     * @param inputSdp builds the SDP describing the input RTP stream, given the loopback port ffmpeg reads from
     * @param inputArgs the decoder arguments applied to the input, see {@link #aacEldDecoderArgs}
     * @param encoderArgs the ffmpeg arguments that select the output codec
     * @param payloadType the RTP payload type of the input and output streams
     * @param sink receives each RTP packet produced by ffmpeg
     * @throws IOException if the sockets, SDP file or process cannot be created
     */
    private FfmpegRtpTranscoder(String ffmpeg, IntFunction<String> inputSdp, List<String> inputArgs,
            List<String> encoderArgs, int payloadType, PacketSink sink) throws IOException {
        loopback = InetAddress.getLoopbackAddress();
        inputSocket = new DatagramSocket();
        outputSocket = new DatagramSocket(0, loopback);
        try {
            inputPort = freeRtpPortPair();
        } catch (IOException e) {
            inputSocket.close();
            outputSocket.close();
            throw e;
        }

        sdpFile = Files.createTempFile("homekit-rtp-transcoder-", ".sdp");
        try {
            Files.writeString(sdpFile, inputSdp.apply(inputPort));
            process = new ProcessBuilder(FfmpegHelpers.transcodeCommandLine(ffmpeg, sdpFile.toString(),
                    outputSocket.getLocalPort(), inputArgs, encoderArgs, payloadType)).redirectErrorStream(true)
                    .start();
        } catch (IOException | RuntimeException e) {
            inputSocket.close();
            outputSocket.close();
            Files.deleteIfExists(sdpFile);
            throw e;
        }

        Thread logger = new Thread(() -> logOutput(process), "OH-binding-homekit-ffmpeg-log");
        logger.setDaemon(true);
        logger.start();

        Thread reader = new Thread(() -> forwardOutput(sink), "OH-binding-homekit-ffmpeg-transcoder");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Creates a transcoder that converts the camera's AAC-ELD RTP to Opus RTP.
     *
     * @param ffmpeg the ffmpeg executable
     * @param codec the camera's AAC-ELD audio codec
     * @param payloadType the RTP payload type used for both input and output
     * @param sink receives each Opus RTP packet
     * @throws IOException if the transcoder cannot be started
     */
    public static FfmpegRtpTranscoder aacEldToOpus(String ffmpeg, AudioCodec codec, int payloadType, PacketSink sink)
            throws IOException {
        return new FfmpegRtpTranscoder(ffmpeg, port -> FfmpegHelpers.aacEldInputSdp(port, codec, payloadType),
                FfmpegHelpers.aacEldDecoderArgs(), FfmpegHelpers.opusEncoderArgs(), payloadType, sink);
    }

    /**
     * Creates a transcoder that converts browser Opus RTP to the camera's AAC-ELD RTP.
     *
     * @param ffmpeg the ffmpeg executable
     * @param codec the camera's AAC-ELD audio codec
     * @param payloadType the RTP payload type used for both input and output
     * @param sink receives each AAC-ELD RTP packet
     * @throws IOException if the transcoder cannot be started
     */
    public static FfmpegRtpTranscoder opusToAacEld(String ffmpeg, AudioCodec codec, int payloadType, PacketSink sink)
            throws IOException {
        return new FfmpegRtpTranscoder(ffmpeg, port -> FfmpegHelpers.opusInputSdp(port, payloadType), List.of(),
                FfmpegHelpers.aacEldEncoderArgs(codec), payloadType, sink);
    }

    /**
     * Sends one input RTP packet to ffmpeg.
     *
     * @param data the buffer holding the RTP packet
     * @param length the packet length
     * @throws IOException if the packet cannot be sent
     */
    private long debugPacketsSent;

    public void send(byte[] data, int length) throws IOException {
        if (!firstPacketSent) {
            firstPacketSent = true;
            LOGGER.debug("ffmpeg input: first RTP packet forwarded, {} bytes to port {}", length, inputPort);
        }
        debugPacketsSent++;
        if (debugPacketsSent % 100 == 0) {
            LOGGER.info("DEBUG transcoder sent {} packets to ffmpeg input", debugPacketsSent);
        }
        inputSocket.send(new DatagramPacket(data, length, loopback, inputPort));
    }

    private void logOutput(Process p) {
        try (BufferedReader reader = p.inputReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    LOGGER.debug("ffmpeg: {}", line);
                }
            }
        } catch (IOException e) {
            LOGGER.debug("ffmpeg output closed", e);
        }
        try {
            LOGGER.debug("ffmpeg exited with code {}", p.waitFor());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void forwardOutput(PacketSink sink) {
        byte[] buffer = new byte[MAX_DATAGRAM_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (!closed) {
            try {
                packet.setLength(buffer.length);
                outputSocket.receive(packet);
                sink.accept(buffer, packet.getLength());
            } catch (IOException e) {
                if (!closed) {
                    LOGGER.debug("RTP transcoding output failed: {}", e.getMessage());
                }
                return;
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        inputSocket.close();
        outputSocket.close();
        process.destroy();
        // destroy() is asynchronous and Windows may keep the SDP file locked briefly after ffmpeg exits, so retry
        process.onExit().thenRun(() -> {
            for (int attempt = 1; attempt <= SDP_DELETE_ATTEMPTS; attempt++) {
                try {
                    Files.deleteIfExists(sdpFile);
                    return;
                } catch (IOException e) {
                    if (attempt == SDP_DELETE_ATTEMPTS) {
                        LOGGER.debug("Cannot delete transcoder SDP file {}: {}", sdpFile, e.getMessage());
                    } else {
                        try {
                            Thread.sleep(SDP_DELETE_RETRY_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        });
    }

    /**
     * Returns a UDP port whose successor is also free, so that ffmpeg can bind both its RTP port and its RTCP port
     * (port + 1) on the wildcard address. ffmpeg binds these itself, so another process could take them first; the
     * caller accepts that small risk.
     */
    private static int freeRtpPortPair() throws IOException {
        for (int attempt = 0; attempt < 20; attempt++) {
            int port;
            try (DatagramSocket probe = new DatagramSocket(0)) {
                port = probe.getLocalPort();
            }
            if (port < 65535) {
                try (DatagramSocket rtcpProbe = new DatagramSocket(port + 1)) {
                    return port;
                } catch (IOException e) {
                    // RTCP port taken, try another pair
                }
            }
        }
        throw new IOException("No free RTP/RTCP UDP port pair available");
    }
}
