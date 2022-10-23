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

/**
 * DTO for CLIP 2 mirek schema.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class MirekSchema {
    public static final int MIN = 153;
    public static final int MAX = 500;

    private int mirek_minimum = MIN;
    private int mirek_maximum = MAX;

    public int getMirekMinimum() {
        return mirek_minimum;
    }

    public int getMirekMaximum() {
        return mirek_maximum;
    }
}
