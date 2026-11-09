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
package org.openhab.binding.homekit.internal.enums;

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Enumeration of the video codecs defined by the HomeKit Accessory Protocol for camera video streams.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public enum VideoCodecType {
    H_264(0x00),
    H_265(0x01);

    public final int value;

    // codecs that can be natively decoded by ffmpeg
    public static final Set<VideoCodecType> FFMPEG_NATIVE = Set.of(H_264);

    VideoCodecType(int value) {
        this.value = value;
    }
}
