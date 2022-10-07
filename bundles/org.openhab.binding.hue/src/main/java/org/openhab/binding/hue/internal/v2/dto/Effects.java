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

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.v2.enums.EffectType;

/**
 * DTO for 'effect' of a light.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Effects {
    /**
     * Following fields are @Nullable since different cases use different subsets of the fields.
     */
    private @Nullable List<String> effect_values;
    private @Nullable String effect;
    private @Nullable List<String> status_values;
    private @Nullable String status;
    private @Nullable Integer duration;

    public List<EffectType> getEffectValues() {
        if (effect_values != null) {
            return effect_values.stream().map(EffectType::of).collect(Collectors.toList());
        }
        return List.of();
    }

    public List<EffectType> getStatusEffectValues() {
        if (status_values != null) {
            return status_values.stream().map(EffectType::of).collect(Collectors.toList());
        }
        return List.of();
    }

    public @Nullable EffectType getEffectType() {
        return effect != null ? EffectType.of(effect) : null;
    }

    public void setEffectType(EffectType effect) {
        effect_values = null;
        this.effect = effect.name().toLowerCase();
    }

    public @Nullable EffectType getStatusEffectType() {
        return status != null ? EffectType.of(status) : null;
    }

    public void setStatusEffectType(EffectType status) {
        status_values = null;
        this.status = status.name().toLowerCase();
    }

    public @Nullable Integer getDuration() {
        return duration;
    }

    public void setDuration(Integer duration) {
        this.duration = duration;
    }

}
