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

/**
 * DTO for API v2 button state.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
public class Button {
    private String last_event;

    public ButtonEvent getLast_event() {
        return ButtonEvent.valueOf(last_event.toUpperCase());
    }
}
