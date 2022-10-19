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
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.clip2.enums.Archetype;

/**
 * DTO for CLIP 2 product metadata.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class MetaData {
    private @Nullable String archetype;
    private @Nullable String name;
    private @Nullable Integer control_id;

    public Archetype getArchetype() {
        return Archetype.of(archetype);
    }

    public String getName() {
        String name = this.name;
        return name != null ? name : "";
    }

    public int getControlIdValue() {
        Integer control_id = this.control_id;
        return control_id != null ? control_id.intValue() : 0;
    }
}
