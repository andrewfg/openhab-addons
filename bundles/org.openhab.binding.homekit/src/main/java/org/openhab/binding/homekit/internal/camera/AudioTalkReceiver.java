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
import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Receives talk-back RTP datagrams on an ephemeral UDP port. Datagrams are discarded unless a local
 * forward target is set, in which case they are relayed unchanged so that an ffmpeg process can
 * decrypt them. The payload is not decrypted here.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class AudioTalkReceiver implements Runnable {

    private static final int MAX_DATAGRAM_SIZE = 2048;

    private final DatagramSocket socket;
    private final byte[] buffer = new byte[MAX_DATAGRAM_SIZE];
    private volatile @Nullable InetSocketAddress forwardTo;

    public AudioTalkReceiver() throws IOException {
        socket = new DatagramSocket(0);
    }

    public int getPort() {
        return socket.getLocalPort();
    }

    /**
     * Relays subsequent datagrams to the given loopback UDP port. Pass {@code null} to resume discarding them.
     */
    public void setForwardPort(@Nullable Integer port) {
        forwardTo = port == null ? null : new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    /**
     * Blocks, discarding or relaying received datagrams, until {@link #close()} is called.
     */
    @Override
    public void run() {
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (!socket.isClosed()) {
            try {
                packet.setLength(buffer.length);
                socket.receive(packet);
                InetSocketAddress target = forwardTo;
                if (target != null) {
                    socket.send(new DatagramPacket(buffer, packet.getLength(), target));
                }
            } catch (IOException e) {
                // a closed socket ends the loop; anything else is a transient read or relay failure and is ignored
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
