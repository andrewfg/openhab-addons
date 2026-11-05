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

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decrypts the camera's Opus audio with ffmpeg and passes it to a consumer as Ogg Opus, without transcoding. The
 * camera's RTP is relayed by a {@link TalkbackReceiver} to a loopback port that ffmpeg reads through a generated SDP
 * file.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class ListenSession implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ListenSession.class);

    private static final int BUFFER_SIZE = 4096;

    private final Consumer<byte[]> audioConsumer;
    private final TalkbackReceiver receiver;
    private final SrtpParameters srtp;
    private final int payloadType;

    private @Nullable Process process;
    private @Nullable Path sdpFile;
    private volatile boolean closed = false;

    /**
     * @param audioConsumer receives each chunk of Ogg Opus copied from ffmpeg
     * @param receiver the receiver that already accepts the camera's RTP for this session
     * @param srtp the camera's audio SRTP parameters from the SetupEndpoints response
     * @param payloadType the RTP payload type that the camera uses for its Opus audio
     */
    public ListenSession(Consumer<byte[]> audioConsumer, TalkbackReceiver receiver, SrtpParameters srtp,
            int payloadType) {
        this.audioConsumer = audioConsumer;
        this.receiver = receiver;
        this.srtp = srtp;
        this.payloadType = payloadType;
    }

    /**
     * Starts ffmpeg and points the receiver at it. Must be called before {@link #run()}.
     *
     * @throws IOException if the SDP file cannot be written or ffmpeg cannot be started
     */
    public synchronized void start() throws IOException {
        int ffmpegPort;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            ffmpegPort = probe.getLocalPort();
        }
        Path sdp = Files.createTempFile("homekit-listen-", ".sdp");
        sdpFile = sdp;
        Files.writeString(sdp, sdpContent(ffmpegPort, payloadType), StandardCharsets.US_ASCII);
        Process started = new ProcessBuilder(FfmpegAudioBridge.listenCommand(sdp.toString(), srtp))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        process = started;
        receiver.setForwardPort(ffmpegPort);
    }

    /**
     * Copies Ogg Opus from ffmpeg to the consumer until ffmpeg ends or {@link #close()} is called.
     */
    @Override
    public void run() {
        Process current = process;
        if (current == null) {
            return;
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream ogg = current.getInputStream()) {
            int read;
            while ((read = ogg.read(buffer)) != -1) {
                byte[] chunk = new byte[read];
                System.arraycopy(buffer, 0, chunk, 0, read);
                audioConsumer.accept(chunk);
            }
        } catch (IOException e) {
            if (!closed) {
                LOGGER.debug("Listen session ended unexpectedly: {}", e.getMessage());
            }
        }
    }

    /**
     * Stops ffmpeg, stops relaying camera audio and removes the SDP file. Safe to call more than once.
     */
    public synchronized void close() {
        closed = true;
        receiver.setForwardPort(null);
        Process current = process;
        process = null;
        if (current != null) {
            current.destroy();
        }
        Path sdp = sdpFile;
        sdpFile = null;
        if (sdp != null) {
            try {
                Files.deleteIfExists(sdp);
            } catch (IOException e) {
                LOGGER.debug("Failed to delete listen SDP file {}: {}", sdp, e.getMessage());
            }
        }
    }

    static String sdpContent(int ffmpegPort, int payloadType) {
        return """
                v=0
                o=- 0 0 IN IP4 127.0.0.1
                s=HomeKit listen
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio %d RTP/AVP %d
                a=rtpmap:%d opus/24000/1
                """.formatted(ffmpegPort, payloadType, payloadType);
    }
}
