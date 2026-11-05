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

import static org.openhab.binding.homekit.internal.camera.CameraTlv.*;

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homekit.internal.crypto.Tlv8Codec;
import org.openhab.binding.homekit.internal.enums.SetupEndpointsStatus;

/**
 * Parsed SetupEndpoints response returned by the accessory. The audio SSRC and audio SRTP parameters are only present
 * on success. The audio SRTP parameters are the key the accessory uses to encrypt its audio towards the controller. The
 * audio port is the accessory's RTP port for receiving audio from the controller (talk-back).
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public record SetupEndpointsResponse(byte[] sessionId, SetupEndpointsStatus status, @Nullable Long audioSsrc,
        @Nullable SrtpParameters audioSrtp, @Nullable Integer audioPort) {

    private static final int TYPE_SESSION_ID = 0x01;
    private static final int TYPE_STATUS = 0x02;
    private static final int TYPE_ACCESSORY_ADDRESS = 0x03;
    private static final int TYPE_ADDRESS_AUDIO_PORT = 0x04;
    private static final int TYPE_AUDIO_SRTP = 0x05;
    private static final int TYPE_AUDIO_SSRC = 0x07;

    public SetupEndpointsResponse {
        requireLength(sessionId, 16);
        if (status == SetupEndpointsStatus.SUCCESS && (audioSsrc == null || audioSrtp == null)) {
            throw new IllegalArgumentException("Audio SSRC or SRTP missing from successful SetupEndpoints response");
        }
    }

    public static SetupEndpointsResponse decode(byte[] data) {
        Map<Integer, byte[]> tlv = Tlv8Codec.decode(data);
        byte[] sessionId = required(tlv, TYPE_SESSION_ID);
        SetupEndpointsStatus status = SetupEndpointsStatus.fromValue(readU8(required(tlv, TYPE_STATUS)));
        byte[] ssrc = tlv.get(TYPE_AUDIO_SSRC);
        byte[] srtp = tlv.get(TYPE_AUDIO_SRTP);
        byte[] address = tlv.get(TYPE_ACCESSORY_ADDRESS);
        Integer audioPort = null;
        if (address != null) {
            byte[] port = Tlv8Codec.decode(address).get(TYPE_ADDRESS_AUDIO_PORT);
            if (port != null && port.length == 2) {
                audioPort = (port[0] & 0xFF) | ((port[1] & 0xFF) << 8);
            }
        }
        return new SetupEndpointsResponse(sessionId, status, ssrc == null ? null : readU32Le(ssrc),
                srtp == null ? null : SrtpParameters.decode(srtp), audioPort);
    }
}
