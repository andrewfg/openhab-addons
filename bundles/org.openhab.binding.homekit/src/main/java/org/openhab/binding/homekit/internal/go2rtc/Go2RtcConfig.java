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
package org.openhab.binding.homekit.internal.go2rtc;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homekit.internal.ffmpeg.FfmpegHelpers;

/**
 * Builds the go2rtc YAML configuration and the ffmpeg exec commands that connect go2rtc to the binding.
 *
 * The binding owns all HomeKit/SRTP work. go2rtc only serves the browser: the listen stream is an RTSP source
 * fed from the binding's audio, and the talk stream is a WebRTC backchannel whose output is delivered back to the
 * binding over TCP.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public final class Go2RtcConfig {

    /**
     * The ports that go2rtc listens on. The API and WebRTC ports are reachable from the browser, while RTSP is
     * bound to loopback so that only the local ffmpeg exec can publish to it.
     */
    public record Ports(int api, int webrtc, int rtsp) {
    }

    private Go2RtcConfig() {
    }

    /**
     * Returns the go2rtc YAML configuration.
     *
     * @param ports the ports to listen on
     * @param listenStream the name of the stream the browser listens to
     * @param listenCommand the exec command that produces the listen stream, see
     *            {@link FfmpegHelpers#go2rtcListenCommand(String, int)}
     * @param talkStream the name of the stream the browser talks to, or null when talk is not available
     * @param talkCommand the exec command that consumes talk audio, see
     *            {@link FfmpegHelpers#go2rtcTalkCommand(String, int)}, or null
     */
    public static String toYaml(Ports ports, String listenStream, List<String> listenCommand,
            @Nullable String talkStream, @Nullable List<String> talkCommand) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("api:\n");
        yaml.append("  listen: \":").append(ports.api()).append("\"\n");
        yaml.append("webrtc:\n");
        yaml.append("  listen: \":").append(ports.webrtc()).append("\"\n");
        yaml.append("rtsp:\n");
        yaml.append("  listen: \"127.0.0.1:").append(ports.rtsp()).append("\"\n");
        yaml.append("streams:\n");
        yaml.append("  ").append(listenStream).append(":\n");
        yaml.append("    - ").append(quote("exec:" + String.join(" ", listenCommand))).append("\n");
        if (talkStream != null && talkCommand != null) {
            yaml.append("  ").append(talkStream).append(":\n");
            yaml.append("    - ").append(quote("exec:" + String.join(" ", talkCommand))).append("\n");
        }
        return yaml.toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
