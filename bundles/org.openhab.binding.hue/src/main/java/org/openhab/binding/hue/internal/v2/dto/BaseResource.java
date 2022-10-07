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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.v2.enums.ApiType;
import org.openhab.binding.hue.internal.v2.enums.ResourceType;
import org.openhab.binding.hue.internal.v2.interfaces.IBaseResource;

/**
 * Base object information DTO for API V2.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class BaseResource implements IBaseResource {
    /**
     * The following fields are @Nullable because some cases do not (must not) use them.
     */
    private @Nullable String type;
    private @Nullable String id;
    private @Nullable String id_v1;
    private @Nullable Reference owner;
    private @Nullable MetaData metadata;

    @Override
    public ApiType apiVersion() {
        return ApiType.V2;
    }

    public void setType(ResourceType type) {
        this.type = type.name().toLowerCase();
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setId_v1(String id_v1) {
        this.id_v1 = id_v1;
    }

    public ResourceType getType() {
        return ResourceType.of(type);
    }

    public String getId() {
        String id = this.id;
        return id != null ? id : "";
    }

    public String getIdV1() {
        String id_v1 = this.id_v1;
        return id_v1 != null ? id_v1 : "";
    }

    public @Nullable Reference getOwner() {
        return owner;
    }

    public @Nullable MetaData getMetaData() {
        return metadata;
    }
}
