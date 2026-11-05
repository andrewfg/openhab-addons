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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MicrophoneListener}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class MicrophoneListenerTest {

    private static final long TIMEOUT_SECONDS = 5;

    @Test
    void testDiscardsDatagramsAndStopsOnClose() throws Exception {
        MicrophoneListener microphoneListener = new MicrophoneListener();
        assertTrue(microphoneListener.getPort() > 0);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = executor.submit(microphoneListener);

            try (DatagramSocket sender = new DatagramSocket()) {
                byte[] payload = new byte[] { 1, 2, 3 };
                sender.send(new DatagramPacket(payload, payload.length, InetAddress.getLoopbackAddress(),
                        microphoneListener.getPort()));
            }

            assertFalse(running.isDone(), "receiver must keep running until closed");

            microphoneListener.close();
            assertDoesNotThrow(() -> running.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            microphoneListener.close();
            executor.shutdownNow();
        }
    }

    @Test
    void testRelaysDatagramsToForwardPortWhenSet() throws Exception {
        MicrophoneListener microphoneListener = new MicrophoneListener();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (DatagramSocket listener = new DatagramSocket(0, InetAddress.getLoopbackAddress());
                DatagramSocket sender = new DatagramSocket()) {
            listener.setSoTimeout((int) TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            microphoneListener.setForwardPort(listener.getLocalPort());
            executor.submit(microphoneListener);

            byte[] small = new byte[] { 9 };
            sender.send(new DatagramPacket(small, small.length, InetAddress.getLoopbackAddress(),
                    microphoneListener.getPort()));
            assertArrayEquals(small, receiveRelayed(listener));

            byte[] large = new byte[] { 1, 2, 3, 4 };
            sender.send(new DatagramPacket(large, large.length, InetAddress.getLoopbackAddress(),
                    microphoneListener.getPort()));
            assertArrayEquals(large, receiveRelayed(listener));
        } finally {
            microphoneListener.close();
            executor.shutdownNow();
        }
    }

    private static byte[] receiveRelayed(DatagramSocket listener) throws IOException {
        DatagramPacket relayed = new DatagramPacket(new byte[16], 16);
        listener.receive(relayed);
        return Arrays.copyOf(relayed.getData(), relayed.getLength());
    }
}
