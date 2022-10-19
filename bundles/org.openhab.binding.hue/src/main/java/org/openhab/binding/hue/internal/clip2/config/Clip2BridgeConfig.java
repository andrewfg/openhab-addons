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
package org.openhab.binding.hue.internal.clip2.config;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Configuration for the Clip2BridgeHandler.
 *
 * @author Christoph Weitkamp - Initial contribution
 * @author Andrew Fiddian-Green - Cloned and re-used for CLIP 2
 */
@NonNullByDefault
public class Clip2BridgeConfig {
    public static final String HTTP = "http";
    public static final String HTTPS = "https";

    public @Nullable String ipAddress;
    public @Nullable String applicationKey;
    public boolean useSelfSignedCertificate = true;
    public int refreshSeconds = 3600;
}
