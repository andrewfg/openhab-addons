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
package org.openhab.binding.hue.internal.dto.clip2;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.dto.ApiVersion;
import org.openhab.binding.hue.internal.dto.clip2.enums.Archetype;

import com.google.gson.annotations.SerializedName;

/**
 * DTO for CLIP 2 product data.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class ProductData {
    @SerializedName(value = "model_id")
    private @NonNullByDefault({}) String modelId;

    @SerializedName(value = "manufacturer_name")
    private @NonNullByDefault({}) String manufacturerName;

    @SerializedName(value = "product_name")
    private @NonNullByDefault({}) String productName;

    @SerializedName(value = "product_archetype")
    private @NonNullByDefault({}) String productArchetype;

    private @NonNullByDefault({}) Boolean certified;

    @SerializedName(value = "software_version")
    private @NonNullByDefault({}) String softwareVersion;

    @SerializedName(value = "hardware_platform_type")
    private @Nullable String hardwarePlatformType;

    public String getModelId() {
        return modelId;
    }

    public String getManufacturerName() {
        return manufacturerName;
    }

    public String getProductName() {
        return productName;
    }

    public Archetype getProductArchetype() {
        return Archetype.of(productArchetype);
    }

    public Boolean getCertified() {
        return certified != null ? certified : false;
    }

    public ApiVersion getSoftwareVersion() {
        return ApiVersion.of(softwareVersion);
    }

    public @Nullable String getHardwarePlatformType() {
        return hardwarePlatformType;
    }
}
