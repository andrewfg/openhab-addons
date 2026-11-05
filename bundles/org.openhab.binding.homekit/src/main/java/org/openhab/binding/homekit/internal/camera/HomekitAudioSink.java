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
package org.openhab.binding.homekit.internal.camera;

import static org.openhab.binding.homekit.internal.camera.HomekitAudioSource.FORMAT;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.audio.AudioException;
import org.openhab.core.audio.AudioFormat;
import org.openhab.core.audio.AudioSink;
import org.openhab.core.audio.AudioStream;
import org.openhab.core.audio.UnsupportedAudioFormatException;
import org.openhab.core.audio.UnsupportedAudioStreamException;
import org.openhab.core.library.types.PercentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link HomekitAudioSink} sends openHAB audio to a HomeKit camera's speaker. The audio must be native Opus in an Ogg
 * container ({@link HomekitAudioSource#FORMAT}), which is repackaged as SRTP without transcoding. Each
 * {@link #process(AudioStream)} call holds the talk-back session for its duration.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class HomekitAudioSink implements AudioSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(HomekitAudioSink.class);

    private final TalkbackRoute.Host host;
    private PercentType volume = new PercentType(100);

    public HomekitAudioSink(TalkbackRoute.Host host) {
        this.host = host;
    }

    @Override
    public String getId() {
        return "homekit-camera-speaker";
    }

    @Override
    public String getLabel(@Nullable Locale locale) {
        return "HomeKit camera speaker";
    }

    @Override
    public Set<AudioFormat> getSupportedFormats() {
        return Set.of(FORMAT);
    }

    @Override
    public Set<Class<? extends AudioStream>> getSupportedStreams() {
        return Set.of(AudioStream.class);
    }

    /**
     * Sends the stream to the camera. Blocks until the stream ends, so the caller must not run it on a shared thread.
     */
    @Override
    public void process(@Nullable AudioStream audioStream)
            throws UnsupportedAudioFormatException, UnsupportedAudioStreamException {
        if (audioStream == null) {
            return;
        }
        if (!FORMAT.equals(audioStream.getFormat())) {
            throw new UnsupportedAudioFormatException("Unsupported audio format", audioStream.getFormat());
        }
        TalkbackRoute route;
        try {
            route = host.acquire();
        } catch (AudioException e) {
            throw new UnsupportedAudioStreamException("Camera talk-back unavailable", audioStream.getClass(), e);
        }
        try {
            Process process = new ProcessBuilder(FfmpegAudioBridge.talkCommand(route.cameraHost(),
                    route.cameraAudioPort(), route.localPort(), route.srtp(), route.payloadType(), route.ssrc()))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (OutputStream ogg = process.getOutputStream()) {
                audioStream.transferTo(ogg);
            } catch (IOException e) {
                LOGGER.debug("Camera talk-back stream ended: {}", e.getMessage());
            } finally {
                process.destroy();
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to start camera talk-back encoder: {}", e.getMessage());
        } finally {
            host.release();
        }
    }

    @Override
    public PercentType getVolume() {
        return volume;
    }

    /**
     * Stores the volume for the openHAB UI. The camera's speaker level is not changed.
     */
    @Override
    public void setVolume(PercentType volume) {
        this.volume = volume;
    }
}
