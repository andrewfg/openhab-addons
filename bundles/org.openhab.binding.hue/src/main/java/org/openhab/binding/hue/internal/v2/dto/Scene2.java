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
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.hue.internal.v2.enums.ResourceType;
import org.openhab.binding.hue.internal.v2.interfaces.IScene;

import com.google.gson.reflect.TypeToken;

/**
 * DTO for an API v2 sensor.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Scene2 extends BaseResource implements IScene {
    public static final Type GSON_TYPE = new TypeToken<Resources<Scene2>>() {
    }.getType();

    private @NonNullByDefault({}) Reference group;
    private @NonNullByDefault({}) List<ActionEntry> actions;

    public Scene2() {
        setType(ResourceType.SCENE);
    }

    public List<ActionEntry> getActions() {
        return actions;
    }

    public Reference getGroup() {
        return group;
    }
}
