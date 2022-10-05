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

/**
 * Base interface from which 'tag' interfaces are extended. It requires the implementing class to return its API
 * version. And it provides a default method for casting from a 'tag' reference to the actual implementing class.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public interface IBase {

    /**
     * Return the Api version that the implementer consumes or produces.
     *
     * @return the Api version
     */
    public default ApiEnum apiVersion() {
        return ApiEnum.V1;
    }

    /**
     * Compares whether this implementing instance has the same state as another.
     *
     * @param other the other instance.
     * @return true is both instances have the same state.
     */
    public default boolean isSame(IBase other) {
        return false;
    }

    /**
     * Cast the implementing class to the given target class.
     *
     * @param <T> generic type (obviously restricted to classes that implement this interface).
     * @param targetClass the class to which the implementer of this interface shall be cast.
     * @return reference to the casted class.
     * @throws ClassCastException if the cast fails.
     */
    public default <T extends IBase> T as(Class<T> targetClass) throws ClassCastException {
        return targetClass.cast(this);
    }
}
