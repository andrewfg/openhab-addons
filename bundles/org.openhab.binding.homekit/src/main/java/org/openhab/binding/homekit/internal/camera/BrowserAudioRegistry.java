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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.osgi.service.component.annotations.Component;

/**
 * {@link BrowserAudioRegistry} maps unguessable tokens to {@link HomekitAudioSource}s for the browser WebSocket. The
 * token is the only credential for the browser WebSocket, so it carries 128 bits of randomness.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
@Component(service = BrowserAudioRegistry.class)
public class BrowserAudioRegistry {

    private static final int TOKEN_BYTES = 16;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, HomekitAudioSource> sources = new ConcurrentHashMap<>();

    /**
     * Registers the source and returns the token that the browser uses to reach it.
     */
    public String register(HomekitAudioSource source) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sources.put(token, source);
        return token;
    }

    public @Nullable HomekitAudioSource find(String token) {
        return sources.get(token);
    }

    public void unregister(String token) {
        sources.remove(token);
    }
}
