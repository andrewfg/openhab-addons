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
package org.openhab.binding.hue.internal.v2.interfaces;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.hue.internal.v2.enums.ApiType;

/**
 * Base interface from which 'tag' interfaces are extended. It requires the implementing class to return its API
 * version. It provides a default method for casting from a 'tag' reference to the actual implementing class, and a
 * method for comparing if two instance contain the same data values.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public interface IBaseResource {

    /**
     * Return the Api version that the implementer consumes or produces.
     *
     * @return the Api version (default is V1)
     */
    public default ApiType apiVersion() {
        return ApiType.V1;
    }

    /**
     * Compares whether this implementing instance has the same data values as another.
     *
     * @param other the other instance.
     * @return true if both instances have the same data values (default is false).
     */
    public default boolean isSame(IBaseResource other) {
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
    public default <T extends IBaseResource> T as(Class<T> targetClass) throws ClassCastException {
        return targetClass.cast(this);
    }
}
