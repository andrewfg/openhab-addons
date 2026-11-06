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
package org.openhab.binding.homekit.internal.camera.service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homekit.internal.camera.FfmpegAudioConverter;
import org.openhab.binding.homekit.internal.camera.FfmpegInstaller;
import org.openhab.binding.homekit.internal.camera.SpeakerSession;
import org.openhab.binding.homekit.internal.camera.tlv.CameraAudioCodec;
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
 * {@link HomekitAudioSink} sends openHAB audio to a HomeKit camera's speaker. The audio must be in one of the
 * formats that the camera declares, and is repackaged as SRTP without transcoding. Each
 * {@link #process(AudioStream)} call holds the talk-back session for its duration.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class HomekitAudioSink implements AudioSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(HomekitAudioSink.class);

    private final SpeakerSession.Speaker speaker;
    private volatile List<CameraAudioCodec> codecs = List.of();
    private volatile String ffmpeg = "ffmpeg";
    private PercentType volume = new PercentType(100);

    public HomekitAudioSink(SpeakerSession.Speaker speaker) {
        this.speaker = speaker;
    }

    /**
     * Sets the codecs that the camera declares, which determine the formats this sink accepts.
     */
    public void setCodecs(List<CameraAudioCodec> codecs) {
        this.codecs = List.copyOf(codecs);
    }

    /**
     * Sets the ffmpeg executable used for talk-back, see {@link FfmpegInstaller}.
     */
    public void setFfmpeg(String ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    @Override
    public String getId() {
        return "homekit-camera-speaker";
    }

    @Override
    public @Nullable String getLabel(@Nullable Locale locale) {
        return "HomeKit camera speaker";
    }

    @Override
    public Set<AudioFormat> getSupportedFormats() {
        return codecs.stream().map(HomekitAudioSource::formatOf).collect(Collectors.toUnmodifiableSet());
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
        CameraAudioCodec codec = HomekitAudioSource.codecFor(codecs, audioStream.getFormat()).orElseThrow(
                () -> new UnsupportedAudioFormatException("Unsupported audio format", audioStream.getFormat()));
        SpeakerSession route;
        try {
            route = speaker.acquire(codec);
        } catch (AudioException e) {
            throw new UnsupportedAudioStreamException("Camera talk-back unavailable", audioStream.getClass(), e);
        }
        try {
            Process process = new ProcessBuilder(FfmpegAudioConverter.talkCommand(ffmpeg, route.cameraHost(),
                    route.cameraAudioPort(), route.localPort(), route.srtp(), route.payloadType(), route.ssrc(), codec))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (OutputStream output = process.getOutputStream()) {
                audioStream.transferTo(output);
            } catch (IOException e) {
                LOGGER.debug("Camera talk-back stream ended: {}", e.getMessage());
            } finally {
                process.destroy();
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to start camera talk-back encoder: {}", e.getMessage());
        } finally {
            speaker.release();
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
