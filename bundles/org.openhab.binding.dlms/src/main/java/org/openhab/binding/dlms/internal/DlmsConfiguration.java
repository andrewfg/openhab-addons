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
package org.openhab.binding.dlms.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link DlmsConfiguration} class contains fields mapping thing configuration parameters.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class DlmsConfiguration {

    public String port = "/dev/ttyUSB0";
    public Integer refresh = 10;
}
