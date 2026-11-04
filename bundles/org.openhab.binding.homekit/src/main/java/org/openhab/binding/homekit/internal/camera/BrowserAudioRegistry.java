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

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.osgi.service.component.annotations.Component;

/**
 * {@link BrowserAudioRegistry} creates {@link BrowserAudioChannel}s and looks them up by their unguessable token. The
 * token is the only credential for the browser WebSocket, so it carries 128 bits of randomness.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
@Component(service = BrowserAudioRegistry.class)
public class BrowserAudioRegistry {

    private static final int TOKEN_BYTES = 16;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, BrowserAudioChannel> channels = new ConcurrentHashMap<>();

    public BrowserAudioChannel create(Consumer<byte[]> inbound) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        BrowserAudioChannel channel = new BrowserAudioChannel(token, inbound);
        channels.put(token, channel);
        return channel;
    }

    public @Nullable BrowserAudioChannel find(String token) {
        return channels.get(token);
    }

    /**
     * Removes the channel and closes any browser still attached to it.
     */
    public void release(BrowserAudioChannel channel) {
        channels.remove(channel.getToken());
        channel.close();
    }
}
