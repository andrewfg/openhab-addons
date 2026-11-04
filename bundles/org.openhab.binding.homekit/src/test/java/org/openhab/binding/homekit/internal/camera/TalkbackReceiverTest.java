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

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TalkbackReceiver}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class TalkbackReceiverTest {

    private static final long TIMEOUT_SECONDS = 5;

    @Test
    void testDiscardsDatagramsAndStopsOnClose() throws Exception {
        TalkbackReceiver receiver = new TalkbackReceiver();
        assertTrue(receiver.getPort() > 0);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = executor.submit(receiver);

            try (DatagramSocket sender = new DatagramSocket()) {
                byte[] payload = new byte[] { 1, 2, 3 };
                sender.send(new DatagramPacket(payload, payload.length, InetAddress.getLoopbackAddress(),
                        receiver.getPort()));
            }

            assertFalse(running.isDone(), "receiver must keep running until closed");

            receiver.close();
            assertDoesNotThrow(() -> running.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            receiver.close();
            executor.shutdownNow();
        }
    }
}
