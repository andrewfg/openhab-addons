/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
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
package org.openhab.binding.dlms.internal.helper;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.QuantityType;

/**
 * A helper to convert the read value of a DLMS meter channel into a {@link QuantityType}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class MeterQuantity {

    // Typical meter values for DLMS/COSEM
    // 1-0:1.8.0(12345.678*kWh)
    // 1-0:32.7.0(230.0*V)
    // 1-0:31.7.0(1.5*A)
    // 1-0:16.7.0(0.345*kW)

    public static QuantityType<?> of(String text) throws IllegalArgumentException {
        String[] parts = text.split("(");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid meter value: " + text);
        }
        return QuantityType.valueOf(parts[1].replace(")", "").replace("*", " ").trim());
    }
}
