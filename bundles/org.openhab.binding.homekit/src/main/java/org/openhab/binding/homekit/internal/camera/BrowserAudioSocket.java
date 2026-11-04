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
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link BrowserAudioSocket} is the browser end of a {@link BrowserAudioChannel}. Binary frames received from the
 * browser are passed to the channel, and frames from the channel are sent back as binary frames.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
@WebSocket
public class BrowserAudioSocket implements BrowserAudioChannel.Sender {

    private final Logger logger = LoggerFactory.getLogger(BrowserAudioSocket.class);

    private final BrowserAudioChannel channel;

    // All access must be guarded by "this"
    private @Nullable Session session;

    public BrowserAudioSocket(BrowserAudioChannel channel) {
        this.channel = channel;
    }

    @OnWebSocketConnect
    public void onConnect(Session session) {
        synchronized (this) {
            this.session = session;
        }
        if (!channel.attach(this)) {
            logger.debug("Rejecting browser audio connection, channel already in use");
            session.close(StatusCode.POLICY_VIOLATION, "Audio session already in use");
            synchronized (this) {
                this.session = null;
            }
        }
    }

    @OnWebSocketMessage
    public void onBinaryMessage(byte[] payload, int offset, int length) {
        channel.onBrowserAudio(Arrays.copyOfRange(payload, offset, offset + length));
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason) {
        synchronized (this) {
            session = null;
        }
        channel.detach(this);
    }

    @OnWebSocketError
    public void onError(Throwable cause) {
        logger.debug("Browser audio WebSocket error: {}", cause.getMessage());
    }

    @Override
    public void send(byte[] frame) {
        Session current;
        synchronized (this) {
            current = session;
        }
        if (current == null || !current.isOpen()) {
            return;
        }
        try {
            current.getRemote().sendBytes(ByteBuffer.wrap(frame));
        } catch (IOException e) {
            logger.debug("Failed to send audio frame to browser: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        Session current;
        synchronized (this) {
            current = session;
            session = null;
        }
        if (current != null && current.isOpen()) {
            current.close(StatusCode.NORMAL, "Audio session ended");
        }
    }
}
