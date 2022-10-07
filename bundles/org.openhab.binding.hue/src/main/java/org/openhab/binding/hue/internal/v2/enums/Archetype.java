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

import java.util.NoSuchElementException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Enum for product archetypes.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public enum Archetype {
    BRIDGE_V2,
    UNKNOWN_ARCHETYPE,
    CLASSIC_BULB,
    SULTAN_BULB,
    FLOOD_BULB,
    SPOT_BULB,
    CANDLE_BULB,
    LUSTER_BULB,
    PENDANT_ROUND,
    PENDANT_LONG,
    CEILING_ROUND,
    CEILING_SQUARE,
    FLOOR_SHADE,
    FLOOR_LANTERN,
    TABLE_SHADE,
    RECESSED_CEILING,
    RECESSED_FLOOR,
    SINGLE_SPOT,
    DOUBLE_SPOT,
    TABLE_WASH,
    WALL_LANTERN,
    WALL_SHADE,
    FLEXIBLE_LAMP,
    GROUND_SPOT,
    WALL_SPOT,
    PLUG,
    HUE_GO,
    HUE_LIGHTSTRIP,
    HUE_IRIS,
    HUE_BLOOM,
    BOLLARD,
    WALL_WASHER,
    HUE_PLAY,
    VINTAGE_BULB,
    CHRISTMAS_TREE,
    HUE_CENTRIS,
    HUE_LIGHTSTRIP_TV,
    HUE_TUBE,
    HUE_SIGNE;

    public static Archetype of(@Nullable String value) {
        if (value != null) {
            try {
                return valueOf(value.toUpperCase());
            } catch (NoSuchElementException e) {
                // fall through
            }
        }
        return UNKNOWN_ARCHETYPE;
    }
}
