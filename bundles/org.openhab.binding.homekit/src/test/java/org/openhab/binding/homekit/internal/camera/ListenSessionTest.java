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

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ListenSession}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class ListenSessionTest {

    @Test
    void testSdpDescribesLoopbackOpusStreamOnPort() {
        String sdp = ListenSession.sdpContent(40000, 110);

        assertTrue(sdp.contains("\nm=audio 40000 RTP/AVP 110\n"), sdp);
        assertTrue(sdp.contains("\na=rtpmap:110 opus/16000/1\n"), sdp);
        assertTrue(sdp.contains("\nc=IN IP4 127.0.0.1\n"), sdp);
    }
}
