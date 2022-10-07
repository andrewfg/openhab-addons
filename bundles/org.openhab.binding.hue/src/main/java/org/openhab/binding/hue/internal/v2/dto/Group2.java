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

import java.lang.reflect.Type;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.hue.internal.v2.enums.ResourceType;
import org.openhab.binding.hue.internal.v2.interfaces.IGroup;

import com.google.gson.reflect.TypeToken;

/**
 * DTO for an API v2 light group.
 *
 * Grouping is used to give structure to the system, and offer joint control of group members. We distinguish two types
 * of groups: rooms and zones. Rooms group devices based on their physical location, which means each device can only be
 * part of one room, and if a device is in a room then logically all services of that device must be in that same room.
 * Zones group services based on anything that makes sense for the use-case, meaning that services can be part of
 * multiple zones, and any subset of services can be part of a zone.
 *
 * The resources that are grouped by a room or zone to create the structure are referenced in the children array. Much
 * like on a device level, the services used to control group members as a whole, are referenced in the services array.
 * For example, the grouped_light service can be used to turn on all lights in the group with a multicast command.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Group2 extends BaseResource implements IGroup {
    public static final Type GSON_TYPE = new TypeToken<Resources<Group2>>() {
    }.getType();

    private @NonNullByDefault({}) List<Reference> children;
    private @NonNullByDefault({}) List<Reference> services;

    public Group2(ResourceType resourceType) {
        switch (resourceType) {
            case ROOM:
                break;
            case ZONE:
                break;
            default:
                throw new IllegalArgumentException(
                        String.format("ResourceType '%s' is not a valid group type.", resourceType.name()));
        }
        setType(resourceType);
    }

    public List<Reference> getChildren() {
        return children;
    }

    public List<Reference> getServices() {
        return services;
    }
}
