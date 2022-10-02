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
package org.openhab.binding.hue.internal.dto.interfaces;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.dto.FullLight;
import org.openhab.binding.hue.internal.dto.v2.LightV2;

/**
 * Interface used as an instance reference for consuming Light DTO instances from both API v1 and v2.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public interface LightInstance {

    /**
     * Compare whether two LightInstances have the same state
     *
     * @param lightInstance the other LightInstance
     * @return true is both have the same state.
     */
    public boolean sameState(LightInstance lightInstance);

    public default @Nullable FullLight toFullLight() {
        return null;
    };

    public default @Nullable LightV2 toLightV2() {
        return null;
    };
}
