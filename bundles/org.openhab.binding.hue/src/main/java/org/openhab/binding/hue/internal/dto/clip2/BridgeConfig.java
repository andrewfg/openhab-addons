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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * A 'special' DTO for bridge discovery, which includes a static method that does an HTTP GET to read the software
 * version
 * from a real bridge, and which checks if the version number is high enough for it to support CLIP 2.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class BridgeConfig {

    private static final int CLIP2_MINIMUM_VERSION = 1948086000;

    public @Nullable String swversion;

    /**
     * Get the configuration information from a Hue bridge and return true if its software version is high enough to
     * support CLIP 2.
     *
     * @param bridgeIpAddress the IP address of the bridge.
     * @return true if it supports CLIP 2.
     */
    public static boolean supportClip2(String bridgeIpAddress) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(String.format("http://%s/api/0/config", bridgeIpAddress)))
                    .header("accept", "application/json").build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                BridgeConfig config = new Gson().fromJson(response.body(), BridgeConfig.class);
                if (config != null) {
                    String swVersion = config.swversion;
                    if (swVersion != null) {
                        return Integer.parseInt(swVersion) >= CLIP2_MINIMUM_VERSION;
                    }
                }
            }
        } catch (URISyntaxException | IOException | InterruptedException | JsonSyntaxException
                | NumberFormatException e) {
            // fall through
        }
        return false;
    }
}
