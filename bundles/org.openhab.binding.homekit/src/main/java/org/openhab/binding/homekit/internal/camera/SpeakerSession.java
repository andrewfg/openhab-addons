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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.homekit.internal.camera.tlv.CameraAudioCodec;
import org.openhab.binding.homekit.internal.camera.tlv.SrtpParameters;
import org.openhab.core.audio.AudioException;

/**
 * Where talk-back audio is sent: the camera's audio RTP endpoint, the local UDP port used as the RTP source, and the
 * SRTP parameters and SSRC the controller uses for its audio.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public record SpeakerSession(String cameraHost, int cameraAudioPort, int localPort, SrtpParameters srtp, long ssrc,
        int payloadType, CameraAudioCodec codec) {

    public interface Speaker {
        SpeakerSession acquire(CameraAudioCodec codec) throws AudioException;

        void release();
    }
}
