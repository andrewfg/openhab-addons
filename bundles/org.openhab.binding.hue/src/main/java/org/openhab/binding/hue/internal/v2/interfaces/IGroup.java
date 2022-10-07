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
 * Interface used as a 'tag' for passing references to instances of different light group DTO classes in an API agnostic
 * manner.
 * <p>
 * See {@link ILight} for description and example how to use this.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public interface IGroup extends IBaseResource {
}
