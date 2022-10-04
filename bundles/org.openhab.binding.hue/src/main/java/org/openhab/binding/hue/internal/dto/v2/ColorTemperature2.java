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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * DTO for colour temperature of a light in API v2.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class ColorTemperature2 {
    private int mirek;

    public static int MIN = 153;
    public static int MAX = 500;

    private void check(int value) {
        if (value < MIN || value > MAX) {
            throw new NumberFormatException(String.format("mirek value '%d' not in range %d .. %d", value, MIN, MAX));
        }
    }

    private int getReciprocal(int value) {
        return Math.round(1000000f / value);
    }

    public int getMirek() {
        check(mirek);
        return mirek;
    }

    public void setMirek(int mirek) {
        check(mirek);
        this.mirek = mirek;
    }

    public int getPercent() {
        int percent = Math.round(100f * (getMirek() - MIN) / (MAX - MIN));
        return Math.round(Math.max(0, Math.min(100, percent)));
    }

    public void setPercent(int percent) {
        float offset = (percent / 100f) * (MAX - MIN);
        setMirek(MIN + Math.round(offset));
    }

    public int getKelvin() {
        return getReciprocal(getMirek());
    }

    public void setKelvin(int kelvin) {
        setMirek(getReciprocal(kelvin));
    }
}
