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

import static org.openhab.binding.homekit.internal.HomekitBindingConstants.BINDING_ID;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.openhab.core.audio.AudioException;
import org.openhab.core.audio.AudioStream;
import org.openhab.core.common.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link BrowserAudioSocket} streams the listen audio of one {@link HomekitAudioSource} to one browser as binary
 * WebSocket frames of raw PCM ({@link HomekitAudioSource#FORMAT}). Each connection opens its own {@link AudioStream}
 * and closes it when the browser disconnects.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
@WebSocket
public class BrowserAudioSocket {

    private static final int CHUNK_SIZE = 4096;

    private final Logger logger = LoggerFactory.getLogger(BrowserAudioSocket.class);
    private final HomekitAudioSource source;

    private @Nullable AudioStream stream;
    private volatile boolean closed = false;

    public BrowserAudioSocket(HomekitAudioSource source) {
        this.source = source;
    }

    @OnWebSocketConnect
    public void onConnect(Session session) {
        try {
            AudioStream audioStream = source.getInputStream(HomekitAudioSource.FORMAT);
            stream = audioStream;
            ThreadPoolManager.getPool(BINDING_ID).submit(() -> pump(session, audioStream));
        } catch (AudioException e) {
            logger.debug("Browser audio stream could not be opened: {}", e.getMessage());
            session.close();
        }
    }

    private void pump(Session session, AudioStream audioStream) {
        byte[] buffer = new byte[CHUNK_SIZE];
        try {
            while (!closed && session.isOpen()) {
                int n = audioStream.read(buffer);
                if (n < 0) {
                    break;
                }
                if (n > 0) {
                    session.getRemote().sendBytes(ByteBuffer.wrap(buffer, 0, n));
                }
            }
        } catch (IOException e) {
            if (!closed) {
                logger.debug("Browser audio stream ended: {}", e.getMessage());
            }
        }
    }

    @OnWebSocketClose
    public void onClose(int statusCode, @Nullable String reason) {
        closed = true;
        AudioStream audioStream = stream;
        if (audioStream != null) {
            try {
                audioStream.close();
            } catch (IOException e) {
                logger.debug("Error closing browser audio stream: {}", e.getMessage());
            }
        }
    }

    @OnWebSocketError
    public void onError(Throwable cause) {
        logger.debug("Browser audio WebSocket error: {}", cause.getMessage());
    }
}
