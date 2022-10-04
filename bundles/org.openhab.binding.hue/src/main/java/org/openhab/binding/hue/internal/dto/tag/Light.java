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
import org.openhab.binding.hue.internal.dto.FullLight;
import org.openhab.binding.hue.internal.dto.v2.Light2;

/**
 * Interface used as a 'tag' for passing references to instances of different Light DTO classes in an API agnostic
 * manner.
 * <p>
 * So for example, both API v1 'FullLight' and Api V2 'Light' classes implement 'LightDto', and so both classes can be
 * passed as arguments in method calls independent of the actual class being used. Then the actual class can be
 * accessed via the 'toFullLight() or 'toLight2()' methods which would .
 *
 * <pre>
 * public void doSomething(LightDto light) {
 *     try {
 *         FullLight fullLight = light.asFullLight();
 *         fullLight.doSomething();
 *     } catch (ClassCastException e) {
 *     }
 *     try {
 *         Light2 light2 = light.asLight2();
 *         light2.doSomethingElse();
 *     } catch (ClassCastException e) {
 *     }
 * }
 * </pre>
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public interface Light {

    /**
     * Compares whether this {@link Light} instance has the same state as another.
     *
     * @param other the other instance.
     * @return true is both instances have the same state.
     */
    public boolean sameState(Light other);

    /**
     * Return the API version that the implementing class is based on.
     *
     * @return sensor API version
     */
    public ApiType apiVersion();

    /**
     * Cast the implementing class to a FullLight.
     *
     * @return the FullLight that implements this interface.
     * @throws ClassCastException if cast is not supported.
     */
    public default FullLight toFullLight() throws ClassCastException {
        throw new ClassCastException("Cannot cast 'Light' to 'FullLight'");
    }

    /**
     * Cast the implementing class to a Light2.
     *
     * @return the Light2 that implements this interface.
     * @throws ClassCastException if cast is not supported.
     */
    public default Light2 toLight2() throws ClassCastException {
        throw new ClassCastException("Cannot cast 'Light' to 'Light2'");
    }
}
