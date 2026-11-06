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
 * Loopback TCP endpoints that connect the binding's audio to the ffmpeg processes started by go2rtc.
 *
 * The listen side is a server that streams the camera's microphone audio to whichever ffmpeg client is connected.
 * Audio is queued and written by a separate thread, so a stalled client drops audio instead of blocking the
 * HomeKit microphone thread. The talk side is a server that hands each accepted connection, carrying browser audio
 * from go2rtc, to a consumer.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Go2RtcAudioTransporter implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Go2RtcAudioTransporter.class);
    private static final int QUEUE_CAPACITY = 128;

    /**
     * Receives notifications when the listen client connects or disconnects. Close of the bridge does not notify.
     */
    public interface ListenObserver {
        void listenConnected();

        void listenDisconnected();
    }

    private final ServerSocket listenServer;
    private final ServerSocket talkServer;
    private final ListenObserver listenObserver;
    private final Consumer<InputStream> talkConsumer;
    private final Object lock = new Object();

    private @Nullable Socket listenClient;
    private BlockingQueue<byte[]> listenQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private volatile boolean closed;

    /**
     * Opens two loopback servers on ephemeral ports and starts accepting connections.
     *
     * @param listenObserver is notified when the listen client connects or disconnects
     * @param talkConsumer receives the input stream of each talk connection
     * @throws IOException if the servers cannot be opened
     */
    public Go2RtcAudioTransporter(ListenObserver listenObserver, Consumer<InputStream> talkConsumer) throws IOException {
        this.listenObserver = listenObserver;
        this.talkConsumer = talkConsumer;
        this.listenServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.talkServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        startAcceptThread("listen", listenServer, this::onListenClient);
        startAcceptThread("talk", talkServer, this::onTalkClient);
    }

    /** @return the loopback port that serves the listen audio */
    public int listenPort() {
        return listenServer.getLocalPort();
    }

    /** @return the loopback port that receives the talk audio */
    public int talkPort() {
        return talkServer.getLocalPort();
    }

    /**
     * Queues a copy of the audio for the connected listen client. Audio is dropped when no client is connected or the
     * client cannot keep up.
     *
     * @param buffer the audio buffer, which the caller may reuse after this call returns
     * @param offset the offset of the first byte to publish
     * @param length the number of bytes to publish
     */
    public void publish(byte[] buffer, int offset, int length) {
        synchronized (lock) {
            if (listenClient == null) {
                return;
            }
            listenQueue.offer(Arrays.copyOfRange(buffer, offset, offset + length));
        }
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(listenServer);
        closeQuietly(talkServer);
        synchronized (lock) {
            Socket client = listenClient;
            listenClient = null;
            listenQueue.clear();
            closeQuietly(client);
        }
    }

    private void startAcceptThread(String name, ServerSocket server, SocketHandler handler) {
        Thread thread = new Thread(() -> acceptLoop(server, handler), "OH-binding-homekit-go2rtc-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptLoop(ServerSocket server, SocketHandler handler) {
        while (!closed) {
            try {
                Socket socket = server.accept();
                handler.handle(socket);
            } catch (IOException e) {
                if (!closed) {
                    LOGGER.debug("go2rtc audio bridge accept failed", e);
                }
                return;
            }
        }
    }

    private void onListenClient(Socket socket) throws IOException {
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        boolean replaced;
        synchronized (lock) {
            replaced = listenClient != null;
            closeQuietly(listenClient);
            listenClient = socket;
            listenQueue = queue;
        }
        if (replaced) {
            listenObserver.listenDisconnected();
        }
        listenObserver.listenConnected();
        Thread writer = new Thread(() -> writeListenAudio(socket, queue), "OH-binding-homekit-go2rtc-listen-writer");
        writer.setDaemon(true);
        writer.start();
    }

    private void writeListenAudio(Socket socket, BlockingQueue<byte[]> queue) {
        try (OutputStream out = socket.getOutputStream()) {
            while (!closed && !socket.isClosed()) {
                byte[] chunk = queue.poll(1, TimeUnit.SECONDS);
                if (chunk != null) {
                    out.write(chunk);
                    out.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.debug("go2rtc listen client disconnected", e);
        } catch (IOException e) {
            LOGGER.debug("go2rtc listen client disconnected", e);
        } finally {
            boolean current;
            synchronized (lock) {
                current = Objects.equals(listenClient, socket);
                if (current) {
                    listenClient = null;
                }
            }
            closeQuietly(socket);
            if (current && !closed) {
                listenObserver.listenDisconnected();
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
}
