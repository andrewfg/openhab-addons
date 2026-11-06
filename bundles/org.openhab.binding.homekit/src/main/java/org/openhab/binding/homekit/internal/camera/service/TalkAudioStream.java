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
import java.io.InputStream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homekit.internal.camera.tlv.CameraAudioCodec;
import org.openhab.core.audio.AudioFormat;
import org.openhab.core.audio.AudioStream;

/**
 * An openHAB {@link AudioStream} that carries browser talk audio received from go2rtc, encoded with a declared codec.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class TalkAudioStream extends AudioStream {

    private final InputStream input;
    private final AudioFormat format;

    public TalkAudioStream(InputStream input, CameraAudioCodec codec) {
        this.input = input;
        this.format = HomekitAudioSource.formatOf(codec);
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public int read() throws IOException {
        return input.read();
    }

    @Override
    public int read(byte @Nullable [] b, int off, int len) throws IOException {
        return input.read(b, off, len);
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
