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

import org.openhab.binding.hue.internal.clip2.enums.ButtonEventType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.State;

/**
 * DTO for CLIP 2 button state.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
public class Button {
    private String last_event;

    public ButtonEventType getLastEvent() {
        return ButtonEventType.valueOf(last_event.toUpperCase());
    }

    public State getLastEventState() {
        return new StringType(getLastEvent().name());
    }
}
