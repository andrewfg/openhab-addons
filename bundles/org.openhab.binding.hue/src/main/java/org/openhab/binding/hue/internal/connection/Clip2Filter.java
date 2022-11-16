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
package org.openhab.binding.hue.internal.connection;

import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * ClientRequestFilter class implementation that adds a hue application key header to the HTTP request when opening SSE
 * connections.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Clip2Filter implements ClientRequestFilter {

    private static final String HEADER_APPLICATION_KEY = "hue-application-key";

    private @Nullable String applicationKey;

    @Override
    public void filter(@Nullable ClientRequestContext requestContext) {
        if (requestContext != null) {
            requestContext.getHeaders().add(HEADER_APPLICATION_KEY, applicationKey);
        }
    }

    public Clip2Filter setApplicationKey(String applicationKey) {
        this.applicationKey = applicationKey;
        return this;
    }
}
