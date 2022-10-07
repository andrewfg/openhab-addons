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
package org.openhab.binding.hue.internal.v2.database;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.v2.dto.BaseResource;
import org.openhab.binding.hue.internal.v2.dto.Resources;
import org.openhab.binding.hue.internal.v2.interfaces.IBaseResource;

/**
 * Database class that contains all resources. It comprises a map between the resource id in the bridge and its
 * respective BaseResource DTO.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class ResourceDatabase {

    private final Map<String, BaseResource> database = new HashMap<>();

    public @Nullable <T extends IBaseResource> T getAs(String resourceId, Class<T> targetClass) {
        BaseResource resource = database.get(resourceId);
        return resource != null ? resource.as(targetClass) : null;
    }

    public @Nullable BaseResource getAs(String resourceId) {
        return getAs(resourceId, BaseResource.class);
    }

    public void put(BaseResource resource) {
        database.put(resource.getId(), resource);
    }

    public void putAll(Resources<?> resource) {
        database.putAll(
                resource.getResources().stream().collect(Collectors.toMap(BaseResource::getId, Function.identity())));
    }
}
