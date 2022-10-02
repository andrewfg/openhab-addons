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
package org.openhab.binding.hue.internal.dto.v2;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Basic object information for API V2.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class BaseV2 {
    private @NonNullByDefault({}) String type;
    private @NonNullByDefault({}) String id;
    private @Nullable String id_v1;

    public void setType(String type) {
        this.type = type;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setId_v1(String id_v1) {
        this.id_v1 = id_v1;
    }

    public String getType() {
        return type;
    }

    public String getId() {
        return id;
    }

    public String getId_v1() {
        return id_v1 != null ? id_v1 : "";
    }
}
