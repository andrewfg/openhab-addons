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
package org.openhab.binding.homekit.internal.audio;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.openhab.binding.homekit.internal.enums.AudioCodecType;

/**
 * Tests for {@link MicrophoneSession}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class MicrophoneSessionTest {

    @Test
    void testSdpDescribesLoopbackOpusStreamOnPort() {
        AudioCodec opus = new AudioCodec(AudioCodecType.OPUS, 1, 24);
        String sdp = MicrophoneSession.sdpContent(40000, 110, opus);

        assertTrue(sdp.contains("\nm=audio 40000 RTP/AVP 110\n"), sdp);
        assertTrue(sdp.contains("\na=rtpmap:110 opus/24000/1\n"), sdp);
        assertTrue(sdp.contains("\nc=IN IP4 127.0.0.1\n"), sdp);
        assertFalse(sdp.contains("fmtp"), sdp);
    }

    @Test
    void testSdpNamesG711Encoding() {
        String sdp = MicrophoneSession.sdpContent(40000, 0, new AudioCodec(AudioCodecType.PCMU, 1, 8));

        assertTrue(sdp.contains("\na=rtpmap:0 PCMU/8000/1\n"), sdp);
    }

    @Test
    void testSdpDescribesAmrWbWithOctetAlign() {
        String sdp = MicrophoneSession.sdpContent(40000, 99, new AudioCodec(AudioCodecType.AMR_WB, 1, 16));

        assertTrue(sdp.contains("\na=rtpmap:99 AMR-WB/16000/1\n"), sdp);
        assertTrue(sdp.contains("\na=fmtp:99 octet-align=1\n"), sdp);
    }

    @Test
    void testVideoSdpDescribesH264StreamOnPort() {
        String sdp = MicrophoneSession.videoSdpContent(40002, 99);

        assertTrue(sdp.contains("\nm=video 40002 RTP/AVP 99\n"), sdp);
        assertTrue(sdp.contains("\na=rtpmap:99 H264/90000\n"), sdp);
        assertTrue(sdp.contains("\na=fmtp:99 packetization-mode=1\n"), sdp);
        assertFalse(sdp.contains("m=audio"), sdp);
    }
}
