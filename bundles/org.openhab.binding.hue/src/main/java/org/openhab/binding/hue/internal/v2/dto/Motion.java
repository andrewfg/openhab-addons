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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * DTO for API v2 motion sensor.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Motion {
    private boolean motion;
    private boolean motion_valid;

    public boolean isMotion() {
        return motion;
    }

    public boolean isMotionValid() {
        return motion_valid;
    }
}
