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
import org.eclipse.jdt.annotation.Nullable;

/**
 * DTO for colour temperature of a light in API v2.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class ColorTemperature2 {
    private @Nullable Integer mirek;
    private @Nullable MirekSchema mirek_schema;

    public static int MIN = 153;
    public static int MAX = 500;

    private int getReciprocal(int value) {
        return Math.round(1000000f / value);
    }

    public @Nullable Integer getMirek() {
        return mirek;
    }

    public void setMirek(int mirek) {
        this.mirek = mirek;
    }

    public @Nullable Integer getPercent() {
        Integer mirek = this.mirek;
        if (mirek != null) {
            MirekSchema mirekSchema = mirek_schema;
            int min = mirekSchema != null ? mirekSchema.getMirekMinimum() : MIN;
            int max = mirekSchema != null ? mirekSchema.getMirekMaximum() : MAX;
            int percent = Math.round(100f * (mirek - min) / (max - min));
            return Math.round(Math.max(0, Math.min(100, percent)));
        }
        return null;
    }

    public void setPercent(int percent) {
        float offset = (percent / 100f) * (MAX - MIN);
        setMirek(MIN + Math.round(offset));
    }

    public @Nullable Integer getKelvin() {
        Integer mirek = this.mirek;
        if (mirek != null) {
            return getReciprocal(mirek);
        }
        return null;
    }

    public void setKelvin(int kelvin) {
        setMirek(getReciprocal(kelvin));
    }
}
