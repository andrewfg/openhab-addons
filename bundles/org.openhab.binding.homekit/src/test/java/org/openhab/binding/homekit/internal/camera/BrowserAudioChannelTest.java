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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BrowserAudioChannel} and {@link BrowserAudioRegistry}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class BrowserAudioChannelTest {

    private static final class RecordingBrowser implements BrowserAudioChannel.Sender {
        final List<byte[]> frames = new ArrayList<>();
        int closeCount;

        @Override
        public void send(byte[] frame) {
            frames.add(frame);
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    @Test
    void secondBrowserIsRejectedWhileFirstIsAttached() {
        BrowserAudioChannel channel = new BrowserAudioChannel("t", frame -> {
        });
        RecordingBrowser first = new RecordingBrowser();
        RecordingBrowser second = new RecordingBrowser();

        assertTrue(channel.attach(first));
        assertFalse(channel.attach(second));
    }

    @Test
    void rejectedBrowserCannotDetachAttachedBrowser() {
        BrowserAudioChannel channel = new BrowserAudioChannel("t", frame -> {
        });
        RecordingBrowser first = new RecordingBrowser();
        RecordingBrowser second = new RecordingBrowser();
        channel.attach(first);

        channel.detach(second);
        channel.sendToBrowser(new byte[] { 1 });

        assertEquals(1, first.frames.size());
    }

    @Test
    void browserFramesReachInboundConsumer() {
        List<byte[]> inbound = new ArrayList<>();
        BrowserAudioChannel channel = new BrowserAudioChannel("t", inbound::add);

        channel.onBrowserAudio(new byte[] { 7, 8 });

        assertEquals(1, inbound.size());
        assertArrayEquals(new byte[] { 7, 8 }, inbound.get(0));
    }

    @Test
    void framesToBrowserAreDroppedWhenDetached() {
        BrowserAudioChannel channel = new BrowserAudioChannel("t", frame -> {
        });
        RecordingBrowser browser = new RecordingBrowser();
        channel.attach(browser);
        channel.detach(browser);

        channel.sendToBrowser(new byte[] { 1 });

        assertTrue(browser.frames.isEmpty());
    }

    @Test
    void framesToBrowserAreDeliveredWhenAttached() {
        BrowserAudioChannel channel = new BrowserAudioChannel("t", frame -> {
        });
        RecordingBrowser browser = new RecordingBrowser();
        channel.attach(browser);

        channel.sendToBrowser(new byte[] { 1 });
        channel.sendToBrowser(new byte[] { 2 });

        assertEquals(2, browser.frames.size());
    }

    @Test
    void closeClosesAttachedBrowserAndAllowsReattach() {
        BrowserAudioChannel channel = new BrowserAudioChannel("t", frame -> {
        });
        RecordingBrowser first = new RecordingBrowser();
        channel.attach(first);

        channel.close();
        channel.close();

        assertEquals(1, first.closeCount);
        assertTrue(channel.attach(new RecordingBrowser()));
    }

    @Test
    void registryIssuesUniqueUnguessableTokens() {
        BrowserAudioRegistry registry = new BrowserAudioRegistry();

        BrowserAudioChannel a = registry.create(frame -> {
        });
        BrowserAudioChannel b = registry.create(frame -> {
        });

        assertNotEquals(a.getToken(), b.getToken());
        assertTrue(a.getToken().length() >= 22);
        assertSame(a, registry.find(a.getToken()));
    }

    @Test
    void registryReleaseRemovesAndClosesChannel() {
        BrowserAudioRegistry registry = new BrowserAudioRegistry();
        BrowserAudioChannel channel = registry.create(frame -> {
        });
        RecordingBrowser browser = new RecordingBrowser();
        channel.attach(browser);

        registry.release(channel);

        assertNull(registry.find(channel.getToken()));
        assertEquals(1, browser.closeCount);
    }

    @Test
    void registryFindUnknownTokenReturnsNull() {
        assertNull(new BrowserAudioRegistry().find("unknown"));
    }
}
