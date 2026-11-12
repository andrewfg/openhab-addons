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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homekit.internal.camera.codec.AudioCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transcodes a camera's AAC-ELD RTP audio to Opus RTP using an ffmpeg child process.
 * <p>
 * RTP is sent to ffmpeg on a loopback port described by a temporary SDP file, and ffmpeg sends the Opus RTP back to a
 * loopback port owned by this class. Each received Opus packet is passed to the {@link PacketSink}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public final class AacEldOpusTranscoder implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AacEldOpusTranscoder.class);

    private static final int MAX_DATAGRAM_SIZE = 2048;

    /**
     * Receives Opus RTP packets produced by ffmpeg.
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

    /**
     * Starts ffmpeg and the thread that forwards its output.
     *
     * @param ffmpeg the ffmpeg executable
     * @param codec the camera's AAC-ELD audio codec
     * @param payloadType the RTP payload type of the AAC-ELD packets sent to {@link #send}
     * @param sink receives each Opus RTP packet produced by ffmpeg
     * @throws IOException if the sockets, SDP file or process cannot be created
     */
    public AacEldOpusTranscoder(String ffmpeg, AudioCodec codec, int payloadType, PacketSink sink)
            throws IOException {
        loopback = InetAddress.getLoopbackAddress();
        inputPort = freeLoopbackPort();
        inputSocket = new DatagramSocket();
        outputSocket = new DatagramSocket(0, loopback);

        sdpFile = Files.createTempFile("homekit-aac-eld-", ".sdp");
        try {
            Files.writeString(sdpFile, FfmpegHelpers.aacEldInputSdp(inputPort, codec, payloadType));
            process = new ProcessBuilder(FfmpegHelpers.aacEldToOpusCommandLine(ffmpeg, sdpFile.toString(),
                    outputSocket.getLocalPort(), payloadType)).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException | RuntimeException e) {
            inputSocket.close();
            outputSocket.close();
            Files.deleteIfExists(sdpFile);
            throw e;
        }

        Thread reader = new Thread(() -> forwardOutput(sink), "OH-binding-homekit-aac-eld-transcoder");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Sends one AAC-ELD RTP packet to ffmpeg.
     *
     * @param data the buffer holding the RTP packet
     * @param length the packet length
     * @throws IOException if the packet cannot be sent
     */
    public void send(byte[] data, int length) throws IOException {
        inputSocket.send(new DatagramPacket(data, length, loopback, inputPort));
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
                    LOGGER.debug("AAC-ELD transcoding output failed: {}", e.getMessage());
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
            LOGGER.debug("Cannot delete AAC-ELD SDP file {}: {}", sdpFile, e.getMessage());
        }
    }

    /**
     * Returns a loopback UDP port that is free at the time of the call. ffmpeg binds this port itself, so another
     * process could take it first; the caller accepts that small risk.
     */
    private static int freeLoopbackPort() throws IOException {
        try (DatagramSocket probe = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }
}
