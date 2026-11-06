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
package org.openhab.binding.homekit.internal.camera.go2rtc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AudioBridge}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class AudioBridgeTest {

    private static final int TIMEOUT_SECONDS = 10;
    private static final AudioBridge.ListenObserver NO_OP_OBSERVER = new AudioBridge.ListenObserver() {
        @Override
        public void listenConnected() {
        }

        @Override
        public void listenDisconnected() {
        }
    };

    @Test
    void publishedAudioReachesListenClient() throws Exception {
        CompletableFuture<InputStream> talk = new CompletableFuture<>();
        try (AudioBridge bridge = new AudioBridge(NO_OP_OBSERVER, talk::complete);
                Socket client = new Socket(InetAddress.getLoopbackAddress(), bridge.listenPort())) {
            client.setSoTimeout(TIMEOUT_SECONDS * 1000);
            InputStream in = client.getInputStream();

            byte[] expected = { 1, 2, 3, 4 };
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            // the bridge only queues audio once the client has been accepted, so keep publishing until it arrives
            while (System.nanoTime() < deadline && in.available() < expected.length) {
                bridge.publish(expected, 0, expected.length);
                Thread.sleep(10);
            }
            assertArrayEquals(expected, in.readNBytes(expected.length));
        }
    }

    @Test
    void talkConnectionIsDeliveredToConsumer() throws Exception {
        CompletableFuture<byte[]> received = new CompletableFuture<>();
        try (AudioBridge bridge = new AudioBridge(NO_OP_OBSERVER, in -> {
            try {
                received.complete(in.readAllBytes());
            } catch (IOException e) {
                received.completeExceptionally(e);
            }
        })) {
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), bridge.talkPort())) {
                OutputStream out = client.getOutputStream();
                out.write(new byte[] { 9, 8, 7 });
                out.flush();
                client.shutdownOutput();
            }
            assertArrayEquals(new byte[] { 9, 8, 7 }, received.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }
}
