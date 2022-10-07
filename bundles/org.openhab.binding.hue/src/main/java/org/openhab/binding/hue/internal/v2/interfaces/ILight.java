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

/**
 * Interface used as a 'tag' for passing references to instances of different Light DTO classes in an API agnostic
 * manner.
 * <p>
 * So for example, both API v1 'FullLight' and Api V2 'Light' classes implement 'ILight', and so both classes can be
 * passed as arguments into, or return values from, methods in a manner independent of the actual class being used. Then
 * within those methods the actual class can be accessed via the 'ILight.as()' method as shown in the example below..
 *
 * <pre>
 * public void doSomething(ILight light) {
 *     try {
 *         FullLight fullLight = light.as(FullLight.class);
 *         fullLight.doSomethingVersion1Specific();
 *     } catch (ClassCastException e) {
 *     }
 *     try {
 *         Light2 light2 = light.as(Light2.class);
 *         light2.doSomethingVersion2Specific();
 *     } catch (ClassCastException e) {
 *     }
 * }
 * </pre>
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public interface ILight extends IBaseResource {
}
