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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loopback TCP endpoints that connect the binding's media to the ffmpeg processes started by go2rtc.
 *
 * The audio and video listen channels each stream the camera's media to whichever ffmpeg client is connected. Data is
 * queued and written by a separate thread, so a stalled client drops data instead of blocking the HomeKit thread that
 * publishes it. The talk server hands each accepted connection, carrying browser audio from go2rtc, to a consumer.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Go2RtcTransporter implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Go2RtcTransporter.class);
    private static final int AUDIO_QUEUE_CAPACITY = 128;
    private static final int VIDEO_QUEUE_CAPACITY = 256;

    /**
     * Receives notifications when the audio listen client connects or disconnects. Close of the transporter does not
     * notify.
     */
    public interface ListenObserver {
        void listenConnected();

        void listenDisconnected();
    }

    private final Channel audio;
    private final Channel video;
    private final ServerSocket talkServer;
    private final Consumer<InputStream> talkConsumer;
    private volatile boolean closed;

    /**
     * Opens loopback servers on ephemeral ports and starts accepting connections.
     *
     * @param audioObserver is notified when the audio listen client connects or disconnects
     * @param talkConsumer receives the input stream of each talk connection
     * @throws IOException if the servers cannot be opened
     */
    public Go2RtcTransporter(ListenObserver audioObserver, Consumer<InputStream> talkConsumer) throws IOException {
        this.talkConsumer = talkConsumer;
        audio = new Channel("audio", AUDIO_QUEUE_CAPACITY, audioObserver);
        try {
            video = new Channel("video", VIDEO_QUEUE_CAPACITY, null);
            talkServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            audio.close();
            throw e;
        }
        startAcceptThread("talk", talkServer, this::onTalkClient);
    }

    /** @return the loopback port that serves the audio listen stream */
    public int audioListenPort() {
        return audio.port();
    }

    /** @return the loopback port that serves the video listen stream */
    public int videoListenPort() {
        return video.port();
    }

    /** @return the loopback port that receives the talk audio */
    public int talkPort() {
        return talkServer.getLocalPort();
    }

    /**
     * Queues a copy of the audio for the connected audio listen client. Audio is dropped when no client is connected or
     * the client cannot keep up.
     *
     * @param buffer the audio buffer, which the caller may reuse after this call returns
     * @param offset the offset of the first byte to publish
     * @param length the number of bytes to publish
     */
    public void publishAudio(byte[] buffer, int offset, int length) {
        audio.publish(buffer, offset, length);
    }

    /**
     * Queues a copy of the video for the connected video listen client. Video is dropped when no client is connected or
     * the client cannot keep up.
     *
     * @param buffer the video buffer, which the caller may reuse after this call returns
     * @param offset the offset of the first byte to publish
     * @param length the number of bytes to publish
     */
    public void publishVideo(byte[] buffer, int offset, int length) {
        video.publish(buffer, offset, length);
    }

    @Override
    public void close() {
        closed = true;
        audio.close();
        video.close();
        closeQuietly(talkServer);
    }

    private void startAcceptThread(String name, ServerSocket server, SocketHandler handler) {
        Thread thread = new Thread(() -> acceptLoop(server, handler), "OH-binding-homekit-go2rtc-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptLoop(ServerSocket server, SocketHandler handler) {
        while (!closed) {
            try {
                handler.handle(server.accept());
            } catch (IOException e) {
                if (!closed) {
                    LOGGER.debug("go2rtc transporter accept failed", e);
                }
                return;
            }
        }
    }

    private void onTalkClient(Socket socket) {
        Thread reader = new Thread(() -> {
            try (InputStream in = socket.getInputStream()) {
                talkConsumer.accept(in);
            } catch (IOException e) {
                LOGGER.debug("go2rtc talk client disconnected", e);
            } finally {
                closeQuietly(socket);
            }
        }, "OH-binding-homekit-go2rtc-talk-reader");
        reader.setDaemon(true);
        reader.start();
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

    @FunctionalInterface
    private interface SocketHandler {
        void handle(Socket socket) throws IOException;
    }

    /**
     * A loopback server that streams published data to a single connected client.
     */
    private class Channel implements AutoCloseable {

        private final String name;
        private final int capacity;
        private final @Nullable ListenObserver observer;
        private final ServerSocket server;
        private final Object lock = new Object();

        private @Nullable Socket client;
        private BlockingQueue<byte[]> queue;

        Channel(String name, int capacity, @Nullable ListenObserver observer) throws IOException {
            this.name = name;
            this.capacity = capacity;
            this.observer = observer;
            this.queue = new ArrayBlockingQueue<>(capacity);
            this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            startAcceptThread(name, server, this::onClient);
        }

        int port() {
            return server.getLocalPort();
        }

        void publish(byte[] buffer, int offset, int length) {
            synchronized (lock) {
                if (client == null) {
                    return;
                }
                queue.offer(Arrays.copyOfRange(buffer, offset, offset + length));
            }
        }

        @Override
        public void close() {
            closeQuietly(server);
            synchronized (lock) {
                Socket current = client;
                client = null;
                queue.clear();
                closeQuietly(current);
            }
        }

        private void onClient(Socket socket) {
            BlockingQueue<byte[]> clientQueue = new ArrayBlockingQueue<>(capacity);
            boolean replaced;
            synchronized (lock) {
                replaced = client != null;
                closeQuietly(client);
                client = socket;
                queue = clientQueue;
            }
            ListenObserver listener = observer;
            if (listener != null) {
                if (replaced) {
                    listener.listenDisconnected();
                }
                listener.listenConnected();
            }
            Thread writer = new Thread(() -> write(socket, clientQueue),
                    "OH-binding-homekit-go2rtc-" + name + "-writer");
            writer.setDaemon(true);
            writer.start();
        }

        private void write(Socket socket, BlockingQueue<byte[]> clientQueue) {
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
                LOGGER.debug("go2rtc {} client disconnected", name, e);
            } catch (IOException e) {
                LOGGER.debug("go2rtc {} client disconnected", name, e);
            } finally {
                boolean current;
                synchronized (lock) {
                    current = Objects.equals(client, socket);
                    if (current) {
                        client = null;
                    }
                }
                closeQuietly(socket);
                ListenObserver listener = observer;
                if (current && !closed && listener != null) {
                    listener.listenDisconnected();
                }
            }
        }
    }
}
