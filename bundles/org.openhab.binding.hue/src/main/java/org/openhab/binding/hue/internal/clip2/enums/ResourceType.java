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
package org.openhab.binding.hue.internal.clip2.enums;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
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
    AUTH_V1,
    BEHAVIOR_INSTANCE,
    BEHAVIOR_SCRIPT,
    BRIDGE,
    BRIDGE_HOME,
    BUTTON,
    DEVICE,
    DEVICE_POWER,
    ENTERTAINMENT,
    ENTERTAINMENT_CONFIGURATION,
    GEOFENCE,
    GEOFENCE_CLIENT,
    GEOLOCATION,
    GROUPED_LIGHT,
    HOMEKIT,
    LIGHT,
    LIGHT_LEVEL,
    MOTION,
    PUBLIC_IMAGE,
    ROOM,
    SCENE,
    TEMPERATURE,
    ZGP_CONNECTIVITY,
    ZIGBEE_CONNECTIVITY,
    ZONE,
    UPDATE,
    ADD,
    DELETE,
    ERROR;

    public static final Set<ResourceType> SSE_TYPES = EnumSet.of(UPDATE, ADD, DELETE, ERROR);

    /*
     * NB: the order of the entries in this list is essential for proper initialisation of things!
     */
    public static final List<ResourceType> NOTIFY_TYPES = Arrays.asList(DEVICE, LIGHT, BUTTON, LIGHT_LEVEL, MOTION,
            TEMPERATURE, DEVICE_POWER, ZIGBEE_CONNECTIVITY);

    public static ResourceType of(@Nullable String value) {
        if (value != null) {
            try {
                return valueOf(value.toUpperCase());
            } catch (NoSuchElementException e) {
                // fall through
            }
        }
        return ERROR;
    }

    @Override
    public String toString() {
        String s = this.name().replace("_", " ");
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }
}
