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

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.clip2.enums.ActionType;

/**
 * DTO for 'alert' of a light.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Alerts {
    private @Nullable List<String> action_values;
    private @Nullable String action;

    public List<ActionType> getActionValues() {
        List<String> action_values = this.action_values;
        if (action_values != null) {
            return action_values.stream().map(ActionType::of).collect(Collectors.toList());
        }
        return List.of();
    }

    public @Nullable ActionType getActionType() {
        String action = this.action;
        return action != null ? ActionType.of(action) : null;
    }

    public void setActionType(ActionType action) {
        action_values = null;
        this.action = action.name().toLowerCase();
    }
}
