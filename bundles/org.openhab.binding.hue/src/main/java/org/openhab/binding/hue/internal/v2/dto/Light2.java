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
import org.openhab.binding.hue.internal.v2.enums.ResourceType;
import org.openhab.binding.hue.internal.v2.interfaces.IBaseResource;
import org.openhab.binding.hue.internal.v2.interfaces.ILight;
import org.openhab.binding.hue.internal.v2.interfaces.IUpdate;

import com.google.gson.reflect.TypeToken;

/**
 * DTO for an API v2 light.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Light2 extends LightState implements ILight, IUpdate {
    public static final Type GSON_TYPE = new TypeToken<Resources<Light2>>() {
    }.getType();

    public Light2() {
        setType(ResourceType.LIGHT);
    }

    @Override
    public boolean isSame(IBaseResource other) {
        try {
            Light2 two = other.as(Light2.class);
            return getSwitch().equals(two.getSwitch()) && getBrightnessState().equals(two.getBrightnessState())
                    && getColorTemperatureState().equals(two.getColorTemperatureState())
                    && getColor().equals(two.getColor());
        } catch (ClassCastException e) {
        }
        return false;
    }
}
