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
package org.openhab.binding.hue.internal.clip2.dto;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.clip2.enums.Archetype;
import org.openhab.binding.hue.internal.dto.ApiVersion;

/**
 * DTO for CLIP 2 product data.
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

    public String getModelId() {
        return model_id;
    }

    public String getManufacturerName() {
        return manufacturer_name;
    }

    public String getProductName() {
        return product_name;
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

    public @Nullable String getHardwarePlatformType() {
        return hardware_platform_type;
    }
}
