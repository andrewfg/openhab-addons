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
package org.openhab.binding.hue.internal.clip2.dto;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.types.State;

/**
 * DTO for CLIP 2 light level sensor.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class LightLevel {
    private int light_level;
    private boolean light_level_valid;

    public int getLightlevel() {
        return light_level;
    }

    public boolean isLightLevelValid() {
        return light_level_valid;
    }

    public State getLightlevelState() {
        return new DecimalType(light_level);
    }

    public State isLightLevelValidState() {
        return OnOffType.from(light_level_valid);
    }
}
