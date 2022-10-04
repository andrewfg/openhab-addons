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

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.dto.tag.ApiType;
import org.openhab.binding.hue.internal.dto.tag.Sensor;

/**
 * DTO for an API v2 sensor.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Sensor2 extends BaseObject implements Sensor {
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

    public Boolean getEnabled() {
        return enabled != null ? enabled : false;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public LightLevel getLight() {
        return light != null ? light : new LightLevel();
    }

    public Button getButton() {
        return button != null ? button : new Button();
    }

    public Temperature getTemperature() {
        return temperature != null ? temperature : new Temperature();
    }

    public Motion getMotion() {
        return motion != null ? motion : new Motion();
    }

    public Power getPower_state() {
        return power_state != null ? power_state : new Power();
    }

    @Override
    public @NonNull ApiType apiVersion() {
        return ApiType.V2;
    }

    @Override
    public Sensor2 toSensor2() {
        return this;
    }

    @Override
    public boolean sameState(Sensor other) {
        // TODO
        return false;
    }
}
