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
import java.util.Random;
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

    /**
     * Receives RTP packets produced by ffmpeg.
     */
    @FunctionalInterface
    public interface PacketSink {
        void accept(byte[] data, int length) throws IOException;
    }

    private static final int RTP_PORT_ATTEMPTS = 16;
    private static final int RTP_PORT_MIN = 20000;
    private static final int RTP_PORT_MAX = 45000;
    private static final Random RANDOM = new Random();

    private final DatagramSocket inputSocket;
    private final DatagramSocket outputSocket;
    private final InetAddress loopback;
    private final int inputPort;
    private final Path sdpFile;
    private final Process process;
    private volatile boolean closed;

    /**
     * Starts ffmpeg and the thread that forwards its output.
     *
     * @param ffmpeg the ffmpeg executable
     * @param inputSdp builds the SDP describing the input RTP stream, given the loopback port ffmpeg reads from
     * @param encoderArgs the ffmpeg arguments that select the output codec
     * @param payloadType the RTP payload type of the input and output streams
     * @param sink receives each RTP packet produced by ffmpeg
     * @throws IOException if the sockets, SDP file or process cannot be created
     */
    private FfmpegRtpTranscoder(String ffmpeg, IntFunction<String> inputSdp, List<String> encoderArgs, int payloadType,
            PacketSink sink) throws IOException {
        loopback = InetAddress.getLoopbackAddress();
        inputPort = freeLoopbackPort();
        inputSocket = new DatagramSocket();
        outputSocket = new DatagramSocket(0, loopback);

        sdpFile = Files.createTempFile("homekit-rtp-transcoder-", ".sdp");
        try {
            Files.writeString(sdpFile, inputSdp.apply(inputPort));
            process = new ProcessBuilder(FfmpegHelpers.transcodeCommandLine(ffmpeg, sdpFile.toString(),
                    outputSocket.getLocalPort(), encoderArgs, payloadType))
                    .redirectErrorStream(true).start();
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
                FfmpegHelpers.opusEncoderArgs(), payloadType, sink);
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
        return new FfmpegRtpTranscoder(ffmpeg, port -> FfmpegHelpers.opusInputSdp(port, payloadType),
                FfmpegHelpers.aacEldEncoderArgs(codec), payloadType, sink);
    }

    /**
     * Sends one input RTP packet to ffmpeg.
     *
     * @param data the buffer holding the RTP packet
     * @param length the packet length
     * @throws IOException if the packet cannot be sent
     */
    public void send(byte[] data, int length) throws IOException {
        inputSocket.send(new DatagramPacket(data, length, loopback, inputPort));
    }

    private void logOutput(Process p) {
        try (BufferedReader reader = p.inputReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOGGER.debug("ffmpeg: {}", line);
            }
        } catch (IOException e) {
            LOGGER.debug("ffmpeg output closed", e);
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
        try {
            Files.deleteIfExists(sdpFile);
        } catch (IOException e) {
            LOGGER.debug("Cannot delete transcoder SDP file {}: {}", sdpFile, e.getMessage());
        }
    }

    /**
     * Returns a UDP port whose port + 1 is also free at the time of the call. ffmpeg binds the RTP port and, by default,
     * the RTCP port one above it, so both are probed on the wildcard address that ffmpeg binds. Ports are drawn from
     * below the Windows dynamic range (49152+), which the OS hands out for ephemeral sockets and which overlaps Windows
     * reserved UDP ranges, so ffmpeg cannot be denied the bind with WSAEACCES. ffmpeg binds the port itself, so another
     * process could take it first; the caller accepts that small risk.
     */
    private static int freeLoopbackPort() throws IOException {
        for (int attempt = 0; attempt < RTP_PORT_ATTEMPTS; attempt++) {
            int port = RTP_PORT_MIN + RANDOM.nextInt(RTP_PORT_MAX - RTP_PORT_MIN);
            try (DatagramSocket rtp = new DatagramSocket(port)) {
                try (DatagramSocket rtcp = new DatagramSocket(port + 1)) {
                    return port;
                } catch (IOException e) {
                    // RTCP port in use or reserved, try another pair
                }
            } catch (IOException e) {
                // RTP port in use or reserved, try another pair
            }
        }
        throw new IOException("No free UDP port pair for ffmpeg RTP and RTCP");
    }
}
