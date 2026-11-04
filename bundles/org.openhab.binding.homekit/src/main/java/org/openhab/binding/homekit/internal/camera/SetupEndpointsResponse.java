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
 * Parsed SetupEndpoints response returned by the accessory. The audio SSRC is only present on success.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public record SetupEndpointsResponse(byte[] sessionId, SetupEndpointsStatus status, @Nullable Long audioSsrc) {

    private static final int TYPE_SESSION_ID = 0x01;
    private static final int TYPE_STATUS = 0x02;
    private static final int TYPE_AUDIO_SSRC = 0x07;

    public SetupEndpointsResponse {
        requireLength(sessionId, 16);
        if (status == SetupEndpointsStatus.SUCCESS && audioSsrc == null) {
            throw new IllegalArgumentException("Audio SSRC missing from successful SetupEndpoints response");
        }
    }

    public static SetupEndpointsResponse decode(byte[] data) {
        Map<Integer, byte[]> tlv = Tlv8Codec.decode(data);
        byte[] sessionId = required(tlv, TYPE_SESSION_ID);
        SetupEndpointsStatus status = SetupEndpointsStatus.fromValue(readU8(required(tlv, TYPE_STATUS)));
        byte[] ssrc = tlv.get(TYPE_AUDIO_SSRC);
        return new SetupEndpointsResponse(sessionId, status, ssrc == null ? null : readU32Le(ssrc));
    }
}
