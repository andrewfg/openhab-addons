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
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.type.ChannelTypeUID;

/**
 * The {@link DlmsBindingConstants} class defines common constants for
 * the Things for IEC 62056-21 optical reader heads for DLMS/COSEM meters.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class DlmsBindingConstants {

    private static final String BINDING_ID = "dlms";

    public static final ThingTypeUID THING_TYPE_METER = new ThingTypeUID(BINDING_ID, "dlms-meter");

    public static final ChannelTypeUID GENERIC_CHANNEL_UID = new ChannelTypeUID(BINDING_ID, "generic");
}
