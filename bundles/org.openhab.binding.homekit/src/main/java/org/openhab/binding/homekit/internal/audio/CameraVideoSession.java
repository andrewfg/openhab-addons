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
package org.openhab.binding.homekit.internal.audio;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homekit.internal.audio.tlv.SrtpParameters;
import org.openhab.binding.homekit.internal.ffmpeg.FfmpegConverter;
import org.openhab.binding.homekit.internal.ffmpeg.FfmpegInstaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decrypts the camera's H.264 video with ffmpeg and passes the Annex B stream to a consumer without transcoding.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class CameraVideoSession implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CameraVideoSession.class);

    private static final int BUFFER_SIZE = 65536;

    private final Consumer<byte[]> videoConsumer;
    private final MicrophoneListener videoListener;
    private final SrtpParameters srtp;
    private final int payloadType;
    private final String ffmpeg;

    private @Nullable Path sdpFile;
    private @Nullable volatile Process process;
    private volatile boolean closed = false;

    /**
     * @param videoConsumer receives each chunk of the H.264 Annex B stream copied from ffmpeg
     * @param videoListener the receiver that already accepts the camera's RTP for this session
     * @param srtp the camera's video SRTP parameters from the SetupEndpoints response
     * @param payloadType the RTP payload type that the camera uses for its video
     * @param ffmpeg the ffmpeg executable, see {@link FfmpegInstaller}
     */
    public CameraVideoSession(Consumer<byte[]> videoConsumer, MicrophoneListener videoListener, SrtpParameters srtp,
            int payloadType, String ffmpeg) {
        this.videoConsumer = videoConsumer;
        this.videoListener = videoListener;
        this.srtp = srtp;
        this.payloadType = payloadType;
        this.ffmpeg = ffmpeg;
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
        Path sdp = Files.createTempFile("homekit-video-", ".sdp");
        sdpFile = sdp;
        Files.writeString(sdp, MicrophoneSession.videoSdpContent(ffmpegPort, payloadType), StandardCharsets.US_ASCII);
        Process started = new ProcessBuilder(FfmpegConverter.videoListenCommand(ffmpeg, sdp.toString(), srtp))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        process = started;
        videoListener.setForwardPort(ffmpegPort);
    }

    /**
     * Copies the H.264 stream from ffmpeg to the consumer until ffmpeg ends or {@link #close()} is called.
     */
    @Override
    public void run() {
        Process current = process;
        if (current == null) {
            return;
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = current.getInputStream()) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                byte[] chunk = new byte[read];
                System.arraycopy(buffer, 0, chunk, 0, read);
                videoConsumer.accept(chunk);
            }
        } catch (IOException e) {
            if (!closed) {
                LOGGER.debug("Video session ended unexpectedly: {}", e.getMessage());
            }
        }
    }

    /**
     * Stops ffmpeg, stops relaying camera video and removes the SDP file. Safe to call more than once.
     */
    public synchronized void close() {
        closed = true;
        videoListener.setForwardPort(null);
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
                LOGGER.debug("Failed to delete video SDP file {}: {}", sdp, e.getMessage());
            }
        }
    }
}
