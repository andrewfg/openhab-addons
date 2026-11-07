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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homekit.internal.ffmpeg.FfmpegInstaller;

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

    /**
     * The ffmpeg input and output format of the audio that go2rtc publishes and receives: 8 kHz mono A-law.
     */
    public static final String AUDIO_FORMAT = "-f alaw -ar 8000 -ac 1";

    private Go2RtcConfig() {
    }

    /**
     * Returns an ffmpeg command that reads the binding's audio from a loopback TCP connection and publishes it to
     * go2rtc over RTSP. The {output} placeholder is substituted by go2rtc with its RTSP publish URL.
     *
     * @param ffmpeg the ffmpeg executable, see {@link FfmpegInstaller}
     * @param inputArgs the ffmpeg input format arguments, for example "-f ogg" or "-f mulaw -ar 8000 -ac 1"
     * @param inputPort the loopback TCP port the binding serves the listen audio on
     */
    public static String listenCommand(String ffmpeg, String inputArgs, int inputPort) {
        return ffmpeg + " -hide_banner -loglevel error " + inputArgs + " -i tcp://127.0.0.1:" + inputPort
                + " -vn -c:a copy -f rtsp {output}";
    }

    /**
     * Returns an ffmpeg command that muxes the binding's audio and H.264 video into one RTSP stream for go2rtc. Both
     * inputs are loopback TCP connections served by the binding.
     *
     * @param ffmpeg the ffmpeg executable, see {@link FfmpegInstaller}
     * @param inputArgs the ffmpeg input format arguments for the audio, see {@link #listenCommand(String, String, int)}
     * @param inputPort the loopback TCP port the binding serves the listen audio on
     * @param videoPort the loopback TCP port the binding serves the H.264 Annex B video on
     */
    public static String listenCommand(String ffmpeg, String inputArgs, int inputPort, int videoPort) {
        return ffmpeg + " -hide_banner -loglevel error " + inputArgs + " -i tcp://127.0.0.1:" + inputPort
                + " -f h264 -i tcp://127.0.0.1:" + videoPort + " -map 0:a -map 1:v -c copy -f rtsp {output}";
    }

    /**
     * Returns the ffmpeg command for the go2rtc backchannel. go2rtc writes the browser's microphone audio to the
     * command's stdin as 8 kHz mono A-law. ffmpeg sends it unchanged to the binding's loopback TCP port, which
     * transcodes it to the camera's codec.
     *
     * @param ffmpeg the ffmpeg executable, see {@link FfmpegInstaller}
     * @param outputPort the loopback TCP port the binding listens on for talk audio
     */
    public static String talkCommand(String ffmpeg, int outputPort) {
        return ffmpeg + " -hide_banner -loglevel error " + AUDIO_FORMAT + " -i pipe:0 -c:a pcm_alaw " + AUDIO_FORMAT
                + " tcp://127.0.0.1:" + outputPort + "#backchannel=1#audio=alaw/8000";
    }

    /**
     * Returns the go2rtc YAML configuration.
     *
     * @param ports the ports to listen on
     * @param listenStream the name of the stream the browser listens to
     * @param listenCommand the exec command that produces the listen stream, see {@link #listenCommand}
     * @param talkStream the name of the stream the browser talks to, or null when talk is not available
     * @param talkCommand the exec source that consumes talk audio, see {@link #talkCommand}, or null
     */
    public static String toYaml(Ports ports, String listenStream, String listenCommand, @Nullable String talkStream,
            @Nullable String talkCommand) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("api:\n");
        yaml.append("  listen: \":").append(ports.api()).append("\"\n");
        yaml.append("webrtc:\n");
        yaml.append("  listen: \":").append(ports.webrtc()).append("\"\n");
        yaml.append("rtsp:\n");
        yaml.append("  listen: \"127.0.0.1:").append(ports.rtsp()).append("\"\n");
        yaml.append("streams:\n");
        yaml.append("  ").append(listenStream).append(":\n");
        yaml.append("    - ").append(quote("exec:" + listenCommand)).append("\n");
        if (talkStream != null && talkCommand != null) {
            yaml.append("  ").append(talkStream).append(":\n");
            yaml.append("    - ").append(quote("exec:" + talkCommand)).append("\n");
        }
        return yaml.toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
