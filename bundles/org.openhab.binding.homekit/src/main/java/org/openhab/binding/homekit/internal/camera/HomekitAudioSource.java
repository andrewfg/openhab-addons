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

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.audio.AudioException;
import org.openhab.core.audio.AudioFormat;
import org.openhab.core.audio.AudioSource;
import org.openhab.core.audio.AudioStream;
import org.openhab.core.audio.PipedAudioStream;

/**
 * {@link HomekitAudioSource} exposes the audio of a HomeKit camera as an openHAB {@link AudioSource}. The camera's audio
 * is published as native Opus packets in an Ogg container ({@link #FORMAT}) to every open {@link AudioStream}. Opening
 * the first stream and closing the last one notifies the owner, so the camera's audio is only received while something
 * is listening.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class HomekitAudioSource implements AudioSource {

    /** The Opus codec as used by HomeKit camera talk-back: 24 kHz mono, 20 ms packets, in an Ogg container. */
    private static final String CODEC_OPUS = "OPUS";

    public static final AudioFormat FORMAT = new AudioFormat(AudioFormat.CONTAINER_OGG, CODEC_OPUS, null, null, null,
            24000L, 1);

    private static final int PIPE_SIZE = 64 * 1024;

    /**
     * Receives stream open and close notifications. Called from the thread that opens or closes the stream.
     */
    public interface Listener {
        void streamOpened();

        void streamClosed();
    }

    private final Listener listener;
    private final PipedAudioStream.Group group = PipedAudioStream.newGroup(FORMAT, PIPE_SIZE);
    private final AtomicInteger openStreams = new AtomicInteger();

    public HomekitAudioSource(Listener listener) {
        this.listener = listener;
    }

    @Override
    public Set<AudioFormat> getSupportedFormats() {
        return Set.of(FORMAT);
    }

    @Override
    public AudioStream getInputStream(AudioFormat format) throws AudioException {
        if (!FORMAT.equals(format)) {
            throw new AudioException("Unsupported audio format: " + format);
        }
        PipedAudioStream stream;
        try {
            stream = group.getAudioStreamInGroup();
        } catch (IOException e) {
            throw new AudioException("Failed to open camera audio stream", e);
        }
        openStreams.incrementAndGet();
        listener.streamOpened();
        stream.onClose(() -> {
            openStreams.decrementAndGet();
            listener.streamClosed();
        });
        return stream;
    }

    /**
     * Delivers Ogg Opus data to all open streams. Does nothing while no stream is open. Blocks while a stream's buffer is
     * full.
     */
    public void publish(byte[] data) {
        if (openStreams.get() == 0 || group.isEmpty()) {
            return;
        }
        group.write(data, 0, data.length);
    }

    /**
     * Closes all open streams. Used when the camera session ends.
     */
    public void close() {
        group.close();
    }
}
