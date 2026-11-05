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
import org.openhab.core.audio.AudioException;

/**
 * Where talk-back audio is sent: the camera's audio RTP endpoint, the local UDP port used as the RTP source, and the
 * SRTP parameters and SSRC the controller uses for its audio.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public record TalkbackRoute(String cameraHost, int cameraAudioPort, int localPort, SrtpParameters srtp, long ssrc,
        int payloadType) {

    /**
     * Supplies the route for an active talk-back session, starting the session if needed.
     */
    @NonNullByDefault
    public interface Host {
        /**
         * Takes a hold on the talk-back session.
         *
         * @throws AudioException if the session cannot be started
         */
        TalkbackRoute acquire() throws AudioException;

        /**
         * Releases a hold taken by {@link #acquire()}. The session ends when the last hold is released.
         */
        void release();
    }
}
