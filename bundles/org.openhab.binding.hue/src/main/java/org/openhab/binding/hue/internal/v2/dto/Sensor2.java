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
package org.openhab.binding.hue.internal.v2.dto;

import java.lang.reflect.Type;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.v2.enums.ResourceType;
import org.openhab.binding.hue.internal.v2.interfaces.ISensor;

import com.google.gson.reflect.TypeToken;

/**
 * DTO for an API v2 sensor.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Sensor2 extends BaseResource implements ISensor {
    public static final Type GSON_TYPE = new TypeToken<Resources<Sensor2>>() {
    }.getType();

    /**
     * These fields are @Nullable since different types of sensors implement different fields.
     */
    private @Nullable Boolean enabled;
    private @Nullable LightLevel light;
    private @Nullable Button button;
    private @Nullable Temperature temperature;
    private @Nullable Motion motion;
    private @Nullable Power power_state;

    public Sensor2(ResourceType resourceType) {
        switch (resourceType) {
            case BUTTON:
                button = new Button();
                break;
            case LIGHT_LEVEL:
                light = new LightLevel();
                enabled = false;
                break;
            case MOTION:
                motion = new Motion();
                enabled = false;
                break;
            case TEMPERATURE:
                temperature = new Temperature();
                enabled = false;
                break;
            case DEVICE_POWER:
                break;
            default:
                throw new IllegalArgumentException(
                        String.format("ResourceType '%s' is not a valid sensor type.", resourceType.name()));
        }
        setType(resourceType);
    }

    public @Nullable Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public @Nullable LightLevel getLightLevel() {
        return light;
    }

    public @Nullable Button getButton() {
        return button;
    }

    public @Nullable Temperature getTemperature() {
        return temperature;
    }

    public @Nullable Motion getMotion() {
        return motion;
    }

    public @Nullable Power getPowerState() {
        return power_state;
    }
}
