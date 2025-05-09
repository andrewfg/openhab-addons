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
package org.openhab.binding.bluetooth.bluegiga.internal.command.security;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.bluegiga.internal.BlueGigaResponse;

/**
 * Class to implement the BlueGiga command <b>bondStatusEvent</b>.
 * <p>
 * This event outputs bonding status information.
 * <p>
 * This class provides methods for processing BlueGiga API commands.
 * <p>
 * Note that this code is autogenerated. Manual changes may be overwritten.
 *
 * @author Chris Jackson - Initial contribution of Java code generator
 */
@NonNullByDefault
public class BlueGigaBondStatusEvent extends BlueGigaResponse {
    public static final int COMMAND_CLASS = 0x05;
    public static final int COMMAND_METHOD = 0x04;

    /**
     * Bonding handle
     * <p>
     * BlueGiga API type is <i>uint8</i> - Java type is {@link int}
     */
    private int bond;

    /**
     * Encryption key size used in long-term key
     * <p>
     * BlueGiga API type is <i>uint8</i> - Java type is {@link int}
     */
    private int keysize;

    /**
     * Was Man-in-the-Middle mode was used in pairing. 0: No MITM used. 1: MITM was used
     * <p>
     * BlueGiga API type is <i>uint8</i> - Java type is {@link int}
     */
    private int mitm;

    /**
     * Keys stored for bonding. See: Bonding Keys
     * <p>
     * BlueGiga API type is <i>uint8</i> - Java type is {@link int}
     */
    private int keys;

    /**
     * Event constructor
     */
    public BlueGigaBondStatusEvent(int[] inputBuffer) {
        // Super creates deserializer and reads header fields
        super(inputBuffer);

        event = (inputBuffer[0] & 0x80) != 0;

        // Deserialize the fields
        bond = deserializeUInt8();
        keysize = deserializeUInt8();
        mitm = deserializeUInt8();
        keys = deserializeUInt8();
    }

    /**
     * Bonding handle
     * <p>
     * BlueGiga API type is <i>uint8</i> - Java type is {@link int}
     *
     * @return the current bond as {@link int}
     */
    public int getBond() {
        return bond;
    }

    /**
     * Encryption key size used in long-term key
     * <p>
     * BlueGiga API type is <i>uint8</i> - Java type is {@link int}
     *
     * @return the current keysize as {@link int}
     */
    public int getKeysize() {
        return keysize;
    }

    /**
     * Was Man-in-the-Middle mode was used in pairing. 0: No MITM used. 1: MITM was used
     * <p>
     * BlueGiga API type is <i>uint8</i> - Java type is {@link int}
     *
     * @return the current mitm as {@link int}
     */
    public int getMitm() {
        return mitm;
    }

    /**
     * Keys stored for bonding. See: Bonding Keys
     * <p>
     * BlueGiga API type is <i>uint8</i> - Java type is {@link int}
     *
     * @return the current keys as {@link int}
     */
    public int getKeys() {
        return keys;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("BlueGigaBondStatusEvent [bond=");
        builder.append(bond);
        builder.append(", keysize=");
        builder.append(keysize);
        builder.append(", mitm=");
        builder.append(mitm);
        builder.append(", keys=");
        builder.append(keys);
        builder.append(']');
        return builder.toString();
    }
}
