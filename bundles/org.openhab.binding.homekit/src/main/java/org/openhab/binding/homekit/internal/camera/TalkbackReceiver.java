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
import java.net.DatagramPacket;
import java.net.DatagramSocket;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Receives talk-back RTP datagrams on an ephemeral UDP port and discards them. The payload is not decrypted or
 * played, so this only keeps the camera's sender from targeting an unbound port.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class TalkbackReceiver implements Runnable {

    private static final int MAX_DATAGRAM_SIZE = 2048;

    private final DatagramSocket socket;
    private final byte[] buffer = new byte[MAX_DATAGRAM_SIZE];

    public TalkbackReceiver() throws IOException {
        socket = new DatagramSocket(0);
    }

    public int getPort() {
        return socket.getLocalPort();
    }

    /**
     * Blocks, discarding received datagrams, until {@link #close()} is called.
     */
    @Override
    public void run() {
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (!socket.isClosed()) {
            try {
                socket.receive(packet);
            } catch (IOException e) {
                // a closed socket ends the loop; anything else is a transient read failure and is ignored
                if (socket.isClosed()) {
                    return;
                }
            }
        }
    }

    public void close() {
        socket.close();
    }
}
