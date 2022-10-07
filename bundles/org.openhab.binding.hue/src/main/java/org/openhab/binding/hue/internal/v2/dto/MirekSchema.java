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
 * DTO for API v2 mirek schema.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class MirekSchema {
    private int mirek_minimum;
    private int mirek_maximum;

    public int getMirekMinimum() {
        return mirek_minimum;
    }

    public int getMirekMaximum() {
        return mirek_maximum;
    }
}
