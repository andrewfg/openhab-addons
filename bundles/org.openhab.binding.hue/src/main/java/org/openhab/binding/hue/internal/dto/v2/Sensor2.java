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
package org.openhab.binding.hue.internal.dto.v2;

import java.lang.reflect.Type;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.dto.tag.ApiEnum;
import org.openhab.binding.hue.internal.dto.tag.IBase;
import org.openhab.binding.hue.internal.dto.tag.ISensor;

import com.google.gson.reflect.TypeToken;

/**
 * DTO for an API v2 sensor.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Sensor2 extends BaseObject implements ISensor {
    public static final Type GSON_TYPE = new TypeToken<Resources<Sensor2>>() {
    }.getType();

    private @Nullable Boolean enabled;
    private @Nullable LightLevel light;
    private @Nullable Button button;
    private @Nullable Temperature temperature;
    private @Nullable Motion motion;
    private @Nullable Power power_state;

    public Sensor2(SensorType sensorType) {
        setType(sensorType.name().toLowerCase());
        switch (sensorType) {
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
        }
    }

    public SensorType getSensorType() {
        return SensorType.valueOf(getType().toUpperCase());
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

    @Override
    public ApiEnum apiVersion() {
        return ApiEnum.V2;
    }

    @Override
    public boolean isSame(IBase other) {
        // TODO
        return ISensor.super.isSame(other);
    }
}
