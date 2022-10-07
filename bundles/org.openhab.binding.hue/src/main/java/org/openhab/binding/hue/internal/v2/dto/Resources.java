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

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * DTO for API v2 to retrieve a list of generic resources from the bridge.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Resources<T extends BaseResource> {
    private @NonNullByDefault({}) List<Error> errors;
    private @NonNullByDefault({}) List<T> data;

    public List<String> getErrors() {
        return errors.stream().map(Error::getDescription).collect(Collectors.toList());
    }

    public List<T> getResources() {
        return data;
    }
}
