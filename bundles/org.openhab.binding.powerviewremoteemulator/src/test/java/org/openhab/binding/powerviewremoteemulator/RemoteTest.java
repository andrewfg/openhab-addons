/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
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

package org.openhab.binding.powerviewremoteemulator;

import java.util.UUID;

import javax.bluetooth.BluetoothStateException;
import javax.bluetooth.LocalDevice;

import org.junit.jupiter.api.Test;

/**
 * Guff
 *
 * @author AndrewFG - Initial contribution
 */

class RemoteTest {

    public static final String HUNTER_DOUGLAS = "Hunter Douglas";
    public static final String SHADE_LABEL = "PowerView Shade";
    public static final String REMOTE_LABEL = "PowerViewRemote";

    public static final UUID UUID_SERVICE_SHADE = UUID.fromString("0000FDC1-0000-1000-8000-00805F9B34FB");
    public static final UUID UUID_CHARACTERISTIC_POSITION = UUID.fromString("CAFE1001-C0FF-EE01-8000-A110CA7AB1E0");

    @Test
    void test() {
        try {
            LocalDevice localDevice = LocalDevice.getLocalDevice();
        } catch (BluetoothStateException e) {
            e.printStackTrace();
        }
    }
}
