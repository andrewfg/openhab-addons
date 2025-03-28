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
package org.openhab.binding.bluetooth.bluegiga.internal.command.gap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.bluegiga.internal.BlueGigaResponse;
import org.openhab.binding.bluetooth.bluegiga.internal.enumeration.BgApiResponse;

/**
 * Class to implement the BlueGiga command <b>connectSelective</b>.
 * <p>
 * This command will start the GAP direct connection establishment procedure to a set of
 * dedicated Bluetooth Smart devices. When this command is issued the the module will enter a
 * state where it scans connectable Bluetooth advertisement packets from the remote devices
 * which are registered in the local white list. Upon receiving an advertisement packet from
 * one of the registered devices, the module will send a connection request to this device, and a
 * successful connection will produce a connection status event. The connect selective
 * command can be cancelled with End Procedure command. When in Initiating State there are no
 * scan response events.
 * <p>
 * This class provides methods for processing BlueGiga API commands.
 * <p>
 * Note that this code is autogenerated. Manual changes may be overwritten.
 *
 * @author Chris Jackson - Initial contribution of Java code generator
 */
@NonNullByDefault
public class BlueGigaConnectSelectiveResponse extends BlueGigaResponse {
    public static final int COMMAND_CLASS = 0x06;
    public static final int COMMAND_METHOD = 0x05;

    /**
     * 0 : procedure was successfully started Non-zero: An error occurred
     * <p>
     * BlueGiga API type is <i>BgApiResponse</i> - Java type is {@link BgApiResponse}
     */
    private BgApiResponse result;

    /**
     * Connection handle that is reserved for new connection
     * <p>
     * BlueGiga API type is <i>uint8</i> - Java type is {@link int}
     */
    private int connectionHandle;

    /**
     * Response constructor
     */
    public BlueGigaConnectSelectiveResponse(int[] inputBuffer) {
        // Super creates deserializer and reads header fields
        super(inputBuffer);

        event = (inputBuffer[0] & 0x80) != 0;

        // Deserialize the fields
        result = deserializeBgApiResponse();
        connectionHandle = deserializeUInt8();
    }

    /**
     * 0 : procedure was successfully started Non-zero: An error occurred
     * <p>
     * BlueGiga API type is <i>BgApiResponse</i> - Java type is {@link BgApiResponse}
     *
     * @return the current result as {@link BgApiResponse}
     */
    public BgApiResponse getResult() {
        return result;
    }

    /**
     * Connection handle that is reserved for new connection
     * <p>
     * BlueGiga API type is <i>uint8</i> - Java type is {@link int}
     *
     * @return the current connection_handle as {@link int}
     */
    public int getConnectionHandle() {
        return connectionHandle;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("BlueGigaConnectSelectiveResponse [result=");
        builder.append(result);
        builder.append(", connectionHandle=");
        builder.append(connectionHandle);
        builder.append(']');
        return builder.toString();
    }
}
