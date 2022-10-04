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
package org.openhab.binding.hue.internal.dto.tag;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.hue.internal.dto.StateUpdate;
import org.openhab.binding.hue.internal.dto.v2.Light2;

/**
 * Interface used as a 'tag' for passing references to instances of different light state update DTO classes in an API
 * agnostic manner.
 * <p>
 * See {@link Light} for description and example how to use this.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public interface Update {

    /**
     * Cast the implementing class to a StateUpdate.
     *
     * @return the StateUpdate that implements this interface.
     * @throws ClassCastException if cast is not supported.
     */
    public default StateUpdate toStateUpdate() throws ClassCastException {
        throw new ClassCastException("Cannot cast 'Update' to 'StateUpdate'");
    }

    /**
     * Cast the implementing class to a Light2 (update DTO).
     *
     * @return the Light2 that implements this interface.
     * @throws ClassCastException if cast is not supported.
     */
    public default Light2 toLight2Update() throws ClassCastException {
        throw new ClassCastException("Cannot cast 'Update' to 'Light2'");
    }
}
