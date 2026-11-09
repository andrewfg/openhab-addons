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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

/**
 * Tests for the OS-selected port helpers in {@link Go2RtcProcess}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class Go2RtcProcessTest {

    @Test
    void freePortIsBindableAfterSelection() throws IOException {
        int port = Go2RtcProcess.freePort();
        assertTrue(port > 0 && port <= 65535);
        try (ServerSocket socket = new ServerSocket(port)) {
            assertEquals(port, socket.getLocalPort());
        }
    }

    @Test
    void freeTcpAndUdpPortIsBindableForBoth() throws IOException {
        int port = Go2RtcProcess.freeTcpAndUdpPort();
        try (ServerSocket tcp = new ServerSocket(port); DatagramSocket udp = new DatagramSocket(port)) {
            assertEquals(port, tcp.getLocalPort());
            assertEquals(port, udp.getLocalPort());
        }
    }
}
