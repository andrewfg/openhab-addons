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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homekit.internal.camera.tlv.CameraAudioCodec;
import org.openhab.core.audio.AudioException;
import org.openhab.core.audio.AudioFormat;
import org.openhab.core.audio.AudioSource;
import org.openhab.core.audio.AudioStream;
import org.openhab.core.audio.PipedAudioStream;

/**
 * {@link HomekitAudioSource} exposes the audio of a HomeKit camera as an openHAB {@link AudioSource}. The
 * camera's audio is published as its native packets, in the codec the camera declares, to every open
 * {@link AudioStream}. Only one format can be open at a time. Opening the first stream and closing the last one
 * notifies the owner, so the camera's
 * audio is only received while something is listening.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class HomekitAudioSource implements AudioSource {

    private static final int PIPE_SIZE = 64 * 1024;

    /**
     * Receives stream open and close notifications. {@link #streamOpening} is called from the thread that opens the
     * stream, possibly concurrently with other opens.
     */
    public interface Microphone {
        void streamOpening(CameraAudioCodec codec) throws AudioException;

        void streamClosed();
    }

    private final Microphone microphone;
    private final Object lock = new Object();
    private final Map<AudioFormat, PipedAudioStream.Group> groups = new ConcurrentHashMap<>();

    private volatile List<CameraAudioCodec> codecs = List.of();
    private volatile @Nullable AudioFormat activeFormat;
    private int openStreams;

    public HomekitAudioSource(Microphone microphone) {
        this.microphone = microphone;
    }

    /**
     * Sets the codecs that the camera declares, which determine the formats this source offers.
     */
    public void setCodecs(List<CameraAudioCodec> codecs) {
        this.codecs = List.copyOf(codecs);
    }

    @Override
    public String getId() {
        return "homekit-camera-microphone";
    }

    @Override
    public String getLabel(@Nullable Locale locale) {
        return "HomeKit camera microphone";
    }

    @Override
    public Set<AudioFormat> getSupportedFormats() {
        return codecs.stream().map(HomekitAudioSource::formatOf).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public AudioStream getInputStream(AudioFormat format) throws AudioException {
        CameraAudioCodec codec = codecFor(codecs, format)
                .orElseThrow(() -> new AudioException("Unsupported audio format: " + format));
        synchronized (lock) {
            AudioFormat active = activeFormat;
            if (openStreams > 0 && !format.equals(active)) {
                throw new AudioException("Camera audio is already open in format " + active);
            }
            openStreams++;
            activeFormat = format;
        }
        PipedAudioStream stream;
        try {
            microphone.streamOpening(codec);
            PipedAudioStream.Group group = groups.computeIfAbsent(format, f -> PipedAudioStream.newGroup(f, PIPE_SIZE));
            stream = Objects.requireNonNull(group).getAudioStreamInGroup();
        } catch (AudioException | IOException | RuntimeException e) {
            streamReleased();
            throw new AudioException("Failed to open camera audio stream", e);
        }
        stream.onClose(() -> {
            streamReleased();
            microphone.streamClosed();
        });
        return stream;
    }

    /**
     * Delivers the camera's packets to all open streams. Does nothing while no stream is open. Blocks while a stream's
     * buffer is full.
     */
    public void publish(byte[] data) {
        AudioFormat format = activeFormat;
        if (format == null) {
            return;
        }
        PipedAudioStream.Group group = groups.get(format);
        if (group == null || group.isEmpty()) {
            return;
        }
        group.write(data, 0, data.length);
    }

    /**
     * Closes all open streams. Used when the camera session ends.
     */
    public void close() {
        groups.values().forEach(PipedAudioStream.Group::close);
    }

    /**
     * Returns the openHAB audio format that carries the packets of a declared codec.
     */
    static AudioFormat formatOf(CameraAudioCodec codec) {
        long frequency = codec.rtpClockRate();
        int channels = codec.channels();
        return switch (codec.codec()) {
            case OPUS -> new AudioFormat(AudioFormat.CONTAINER_OGG, "OPUS", null, null, null, frequency, channels);
            case PCMU -> new AudioFormat(null, "PCM_ULAW", null, null, null, frequency, channels);
            case PCMA -> new AudioFormat(null, "PCM_ALAW", null, null, null, frequency, channels);
            case AMR -> new AudioFormat("AMR", "AMR", null, null, null, frequency, channels);
            case AMR_WB -> new AudioFormat("AMR", "AMR-WB", null, null, null, frequency, channels);
            case AAC_ELD, MSBC -> throw new IllegalArgumentException("Unsupported codec: " + codec.codec());
        };
    }

    /**
     * Returns the declared codec whose format equals {@code format}, if any.
     */
    static Optional<CameraAudioCodec> codecFor(List<CameraAudioCodec> codecs, AudioFormat format) {
        return codecs.stream().filter(codec -> formatOf(codec).equals(format)).findFirst();
    }

    private void streamReleased() {
        synchronized (lock) {
            openStreams--;
            if (openStreams == 0) {
                activeFormat = null;
            }
        }
    }
}
