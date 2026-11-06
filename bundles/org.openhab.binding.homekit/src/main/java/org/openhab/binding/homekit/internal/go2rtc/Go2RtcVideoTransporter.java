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
package org.openhab.binding.homekit.internal.go2rtc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loopback TCP endpoint that streams the camera's H.264 video to the ffmpeg process started by go2rtc. Video is queued
 * and written by a separate thread, so a stalled client drops frames instead of blocking the HomeKit video thread.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Go2RtcVideoTransporter implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Go2RtcVideoTransporter.class);
    private static final int QUEUE_CAPACITY = 256;

    private final ServerSocket server;
    private final Object lock = new Object();

    private @Nullable Socket client;
    private BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private volatile boolean closed;

    /**
     * Opens a loopback server on an ephemeral port and starts accepting connections.
     *
     * @throws IOException if the server cannot be opened
     */
    public Go2RtcVideoTransporter() throws IOException {
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(this::acceptLoop, "OH-binding-homekit-go2rtc-video-accept");
        thread.setDaemon(true);
        thread.start();
    }

    /** @return the loopback port that serves the video */
    public int port() {
        return server.getLocalPort();
    }

    /**
     * Queues a copy of the video for the connected client. Data is dropped when no client is connected or the client
     * cannot keep up.
     *
     * @param buffer the video buffer, which the caller may reuse after this call returns
     * @param offset the offset of the first byte to publish
     * @param length the number of bytes to publish
     */
    public void publish(byte[] buffer, int offset, int length) {
        synchronized (lock) {
            if (client == null) {
                return;
            }
            queue.offer(Arrays.copyOfRange(buffer, offset, offset + length));
        }
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(server);
        synchronized (lock) {
            Socket current = client;
            client = null;
            queue.clear();
            closeQuietly(current);
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                onClient(server.accept());
            } catch (IOException e) {
                if (!closed) {
                    LOGGER.debug("go2rtc video feed accept failed", e);
                }
                return;
            }
        }
    }

    private void onClient(Socket socket) {
        BlockingQueue<byte[]> clientQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        synchronized (lock) {
            closeQuietly(client);
            client = socket;
            queue = clientQueue;
        }
        Thread writer = new Thread(() -> writeVideo(socket, clientQueue), "OH-binding-homekit-go2rtc-video-writer");
        writer.setDaemon(true);
        writer.start();
    }

    private void writeVideo(Socket socket, BlockingQueue<byte[]> clientQueue) {
        try (OutputStream out = socket.getOutputStream()) {
            while (!closed && !socket.isClosed()) {
                byte[] chunk = clientQueue.poll(1, TimeUnit.SECONDS);
                if (chunk != null) {
                    out.write(chunk);
                    out.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.debug("go2rtc video client disconnected", e);
        } catch (IOException e) {
            LOGGER.debug("go2rtc video client disconnected", e);
        } finally {
            synchronized (lock) {
                if (Objects.equals(client, socket)) {
                    client = null;
                }
            }
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(@Nullable AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOGGER.trace("Ignoring close failure", e);
            }
        }
    }
}
