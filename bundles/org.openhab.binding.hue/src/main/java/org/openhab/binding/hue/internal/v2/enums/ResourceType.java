/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
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
package org.openhab.binding.hue.internal.v2.enums;

import java.util.EnumSet;
import java.util.NoSuchElementException;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Enum for resource types.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public enum ResourceType {
    DEVICE,
    BRIDGE_HOME,
    ROOM,
    ZONE,
    LIGHT,
    BUTTON,
    TEMPERATURE,
    LIGHT_LEVEL,
    MOTION,
    ENTERTAINMENT,
    GROUPED_LIGHT,
    DEVICE_POWER,
    ZIGBEE_BRIDGE_CONNECTIVITY,
    ZIGBEE_CONNECTIVITY,
    ZGP_CONNECTIVITY,
    BRIDGE,
    HOMEKIT,
    SCENE,
    ENTERTAINMENT_CONFIGURATION,
    PUBLIC_IMAGE,
    AUTH_V1,
    BEHAVIOR_SCRIPT,
    BEHAVIOR_INSTANCE,
    GEOFENCE,
    GEOFENCE_CLIENT,
    GEOLOCATION,
    UNKNOWN;

    public static final Set<ResourceType> SENSOR_TYPES = EnumSet.of(LIGHT, BUTTON, TEMPERATURE, LIGHT_LEVEL, MOTION,
            DEVICE_POWER);

    public static ResourceType of(@Nullable String value) {
        if (value != null) {
            try {
                return valueOf(value.toUpperCase());
            } catch (NoSuchElementException e) {
                // fall through
            }
        }
        return UNKNOWN;
    }
}
