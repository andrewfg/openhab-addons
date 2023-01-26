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
package org.openhab.binding.hue.internal.dto.clip2;

import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * DTO for colour X/Y of a light.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class ColorXy {
    private @NonNullByDefault({}) PairXy xy;

    public float[] getXY() {
        return xy.getXY();
    }

    public ColorXy setXY(float[] xyValues) {
        xy = Objects.nonNull(xy) ? xy : new PairXy();
        xy.setXY(xyValues);
        return this;
    }
}
