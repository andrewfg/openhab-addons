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
import org.openhab.binding.hue.internal.dto.ApiVersion;
import org.openhab.binding.hue.internal.v2.enums.Archetype;

/**
 * DTO for API v2 product data.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class ProductData {
    private @NonNullByDefault({}) String model_id;
    private @NonNullByDefault({}) String manufacturer_name;
    private @NonNullByDefault({}) String product_name;
    private @NonNullByDefault({}) String product_archetype;
    private @NonNullByDefault({}) Boolean certified;
    private @NonNullByDefault({}) String software_version;
    private @Nullable String hardware_platform_type;

    private String nonNull(@Nullable String value) {
        return value != null ? value : "unknown";
    }

    public String getModelId() {
        return nonNull(model_id);
    }

    public String getManufacturerName() {
        return nonNull(manufacturer_name);
    }

    public String getProductName() {
        return nonNull(product_name);
    }

    public Archetype getProductArchetype() {
        return Archetype.of(product_archetype);
    }

    public Boolean getCertified() {
        return certified != null ? certified : false;
    }

    public ApiVersion getSoftwareVersion() {
        return ApiVersion.of(software_version);
    }

    public String getHardwarePlatformType() {
        return nonNull(hardware_platform_type);
    }
}
