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
import org.openhab.binding.hue.internal.v2.interfaces.ILight;

import com.google.gson.reflect.TypeToken;

/**
 * DTO for an API v2 grouped light.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class GroupedLight extends LightState implements ILight {
    public static final Type GSON_TYPE = new TypeToken<Resources<GroupedLight>>() {
    }.getType();

    public GroupedLight() {
        setType(ResourceType.GROUPED_LIGHT);
    }
}
