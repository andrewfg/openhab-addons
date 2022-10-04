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
import org.openhab.binding.hue.internal.dto.FullSensor;
import org.openhab.binding.hue.internal.dto.v2.Sensor2;

/**
 * /**
 * Interface used as a 'tag' for passing references to instances of different Sensor DTO classes in an API agnostic
 * manner.
 * <p>
 * See {@link Light} for description and example how to use this.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public interface Sensor {

    /**
     * Compares whether this {@link Sensor} instance has the same state as another.
     *
     * @param other the other instance.
     * @return true is both instances have the same state.
     */
    public boolean sameState(Sensor other);

    /**
     * Get the sensor API version
     *
     * @return sensor API version
     */
    public ApiType apiVersion();

    /**
     * Cast the implementing class to a FullSensor.
     *
     * @return the FullSensor that implements this interface.
     * @throws ClassCastException if cast is not supported.
     */
    public default FullSensor toFullSensor() throws ClassCastException {
        throw new ClassCastException("Cannot cast 'Sensor' to 'FullSensor'");
    }

    /**
     * Cast the implementing class to a Sensor2.
     *
     * @return the Sensor2 that implements this interface.
     * @throws ClassCastException if cast is not supported.
     */
    public default Sensor2 toSensor2() throws ClassCastException {
        throw new ClassCastException("Cannot cast 'Sensor' to 'Sensor2'");
    }
}
