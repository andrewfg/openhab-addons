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

import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * {@link BrowserAudioChannel} is the audio endpoint shared between a HomeKit camera session and one browser client.
 * Audio captured in the browser is delivered to the inbound consumer, and audio for the browser is delivered to the
 * outbound consumer of the currently attached socket. Only one browser may be attached at a time.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public final class BrowserAudioChannel {

    /**
     * The browser side of the channel, implemented by the WebSocket endpoint.
     */
    public interface Sender {
        void send(byte[] frame);

        void close();
    }

    private final String token;
    private final Consumer<byte[]> inbound;

    // All access must be guarded by "this"
    private @Nullable Sender sender;

    public BrowserAudioChannel(String token, Consumer<byte[]> inbound) {
        this.token = token;
        this.inbound = inbound;
    }

    public String getToken() {
        return token;
    }

    /**
     * Attaches a browser.
     *
     * @return {@code true} if attached, {@code false} if another browser is already attached
     */
    public synchronized boolean attach(Sender browser) {
        if (sender != null) {
            return false;
        }
        sender = browser;
        return true;
    }

    /**
     * Detaches the given browser. A browser that is not currently attached is ignored, so a rejected connection cannot
     * detach the browser that is already connected.
     */
    public synchronized void detach(Sender browser) {
        if (sender == browser) {
            sender = null;
        }
    }

    /**
     * Detaches and closes the attached browser, if any. Used when the HomeKit session ends.
     */
    public void close() {
        Sender browser;
        synchronized (this) {
            browser = sender;
            sender = null;
        }
        if (browser != null) {
            browser.close();
        }
    }

    /**
     * Delivers a frame captured in the browser. Called from the WebSocket thread, so the consumer must not block.
     */
    public void onBrowserAudio(byte[] frame) {
        inbound.accept(frame);
    }

    /**
     * Sends a frame to the attached browser. Frames are dropped while no browser is attached.
     */
    public void sendToBrowser(byte[] frame) {
        Sender browser;
        synchronized (this) {
            browser = sender;
        }
        if (browser != null) {
            browser.send(frame);
        }
    }
}
