/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
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
package org.openhab.binding.homekit.internal.camera.go2rtc;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Builds the go2rtc YAML configuration.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public final class Go2RtcConfig {

    /**
     * The address and ports that go2rtc listens on.
     */
    public record Address(String host, int apiPort, int webRtcPort, int rtspPort) {
    }

    private Go2RtcConfig() {
    }

    /**
     * Returns the go2rtc YAML configuration.
     *
     * @param address the host address and ports to listen on
     * @param listenStream the name of the stream the browser listens to
     * @param listenCommandLine the exec command that produces the listen stream
     * @param talkStream the name of the stream the browser talks to, or null when talk is not available
     * @param talkCommandLine the exec command that consumes talk audio or null
     */
    public static String toYaml(Address address, String listenStream, List<String> listenCommandLine,
            @Nullable String talkStream, @Nullable List<String> talkCommandLine) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("api:\n");
        yaml.append("  listen: \"").append(address.host()).append(":").append(address.apiPort()).append("\"\n");
        yaml.append("webrtc:\n");
        yaml.append("  listen: \"").append(address.host()).append(":").append(address.webRtcPort()).append("\"\n");
        yaml.append("rtsp:\n");
        yaml.append("  listen: \"127.0.0.1:").append(address.rtspPort()).append("\"\n");
        yaml.append("streams:\n");
        yaml.append("  ").append(listenStream).append(":\n");
        yaml.append("    - ").append(quote("exec:" + String.join(" ", listenCommandLine))).append("\n");
        if (talkStream != null && talkCommandLine != null) {
            yaml.append("  ").append(talkStream).append(":\n");
            yaml.append("    - ").append(quote("exec:" + String.join(" ", talkCommandLine))).append("\n");
        }
        return yaml.toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
