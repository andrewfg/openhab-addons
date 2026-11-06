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
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homekit.internal.camera.FfmpegAudioConverter;
import org.openhab.binding.homekit.internal.camera.tlv.CameraAudioCodec;
import org.openhab.core.audio.AudioFormat;
import org.openhab.core.audio.AudioManager;
import org.openhab.core.audio.AudioSink;
import org.openhab.core.audio.AudioSource;
import org.openhab.core.audio.AudioStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges one camera's microphone and speaker to a browser client connected through core's {@code audio-pcm}
 * WebSocket adapter.
 * <p>
 * Core registers the browser endpoints as {@code pcm::<id>::source} and {@code pcm::<id>::sink} only while a browser
 * has sent INITIALIZE with that {@code id}. This bridge polls for them and starts or stops the audio pipes when they
 * appear or disappear. Each pipe runs ffmpeg to convert between raw browser PCM and the camera's declared codec.
 * <p>
 * An ID may be claimed by only one bridge at a time, so two Things cannot share a browser endpoint.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public final class BrowserAudioBridge {

    /** Raw PCM format for the browser: 16 kHz, mono, signed 16-bit little-endian, with no container header. */
    static final AudioFormat BROWSER_FORMAT = new AudioFormat(AudioFormat.CONTAINER_NONE, AudioFormat.CODEC_PCM_SIGNED,
            false, 16, null, (long) FfmpegAudioConverter.BROWSER_SAMPLE_RATE, FfmpegAudioConverter.BROWSER_CHANNELS);

    static final String WEBSOCKET_PATH = "/ws/audio-pcm";

    private static final long POLL_INTERVAL_SECONDS = 5;
    private static final Logger LOGGER = LoggerFactory.getLogger(BrowserAudioBridge.class);
    private static final Map<String, String> CLAIMS = new ConcurrentHashMap<>();

    private final String id;
    private final String ownerUid;
    private final AudioManager audioManager;
    private final HomekitAudioSource cameraSource;
    private final HomekitAudioSink cameraSink;
    private final List<CameraAudioCodec> codecs;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workers;

    private @Nullable ScheduledFuture<?> pollFuture;
    private @Nullable Session session;
    private boolean started;

    /**
     * Creates a bridge. Call {@link #start()} to begin polling.
     *
     * @param id the browser endpoint ID that the browser sends in INITIALIZE
     * @param ownerUid the Thing UID that owns the ID, used to reject duplicates
     * @param audioManager core audio manager, used to look up the browser endpoints
     * @param cameraSource the camera microphone source
     * @param cameraSink the camera speaker sink
     * @param codecs the camera's declared audio codecs, in preference order; must not be empty
     * @param scheduler scheduler for the endpoint poll
     * @param workers pool for the blocking pipe tasks
     */
    public BrowserAudioBridge(String id, String ownerUid, AudioManager audioManager, HomekitAudioSource cameraSource,
            HomekitAudioSink cameraSink, List<CameraAudioCodec> codecs, ScheduledExecutorService scheduler,
            ExecutorService workers) {
        this.id = id;
        this.ownerUid = ownerUid;
        this.audioManager = audioManager;
        this.cameraSource = cameraSource;
        this.cameraSink = cameraSink;
        this.codecs = List.copyOf(codecs);
        this.scheduler = scheduler;
        this.workers = workers;
    }

    /**
     * Returns the WebSocket URL that a browser must connect to. The access token is not included.
     */
    public static String webSocketUrl(String host, int port) {
        return "ws://" + host + ":" + port + WEBSOCKET_PATH;
    }

    /**
     * Claims the ID and starts polling for the browser endpoints.
     *
     * @return true if started; false if the ID is blank, has no codecs, or is already claimed by another Thing
     */
    public synchronized boolean start() {
        if (started) {
            return true;
        }
        if (id.isBlank() || codecs.isEmpty()) {
            return false;
        }
        String previous = CLAIMS.putIfAbsent(id, ownerUid);
        if (previous != null) {
            LOGGER.warn("Browser audio ID '{}' is already used by {}; choose a different browserAudioId", id, previous);
            return false;
        }
        started = true;
        pollFuture = scheduler.scheduleWithFixedDelay(this::poll, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        return true;
    }

    /**
     * Stops polling, ends any active pipes and releases the ID claim.
     */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        ScheduledFuture<?> future = pollFuture;
        if (future != null) {
            future.cancel(true);
            pollFuture = null;
        }
        endSession();
        CLAIMS.remove(id, ownerUid);
    }

    private synchronized void poll() {
        try {
            AudioSource browserSource = audioManager.getSource(sourceId());
            AudioSink browserSink = audioManager.getSink(sinkId());
            Session current = session;
            if (browserSource == null || browserSink == null) {
                endSession();
                return;
            }
            if (current != null && current.browserSource == browserSource && current.browserSink == browserSink) {
                return;
            }
            endSession();
            startSession(browserSource, browserSink);
        } catch (RuntimeException e) {
            LOGGER.debug("Browser audio endpoint poll failed for '{}': {}", id, e.getMessage());
        }
    }

    private String sourceId() {
        return "pcm::" + id + "::source";
    }

    private String sinkId() {
        return "pcm::" + id + "::sink";
    }

    private void startSession(AudioSource browserSource, AudioSink browserSink) {
        CameraAudioCodec uplinkCodec = codecs.get(0);
        CameraAudioCodec downlinkCodec = codecs.get(0);
        Session s = new Session(browserSource, browserSink);
        session = s;
        LOGGER.info("Browser audio connected for ID '{}'", id);
        workers.execute(() -> runUplink(s, uplinkCodec));
        workers.execute(() -> runDownlink(s, downlinkCodec));
    }

    private void endSession() {
        Session s = session;
        if (s != null) {
            session = null;
            s.close();
            LOGGER.info("Browser audio disconnected for ID '{}'", id);
        }
    }

    /** Camera microphone to browser: camera codec to raw browser PCM. */
    private void runUplink(Session s, CameraAudioCodec codec) {
        Process process = null;
        AudioStream cameraStream = null;
        try {
            cameraStream = cameraSource.getInputStream(HomekitAudioSource.formatOf(codec));
            process = start(FfmpegAudioConverter.browserUplinkCommand(codec));
            s.register(process);
            Process p = process;
            AudioStream feedStream = cameraStream;
            workers.execute(() -> pump(feedStream, p.getOutputStream()));
            AudioSink sink = s.browserSink;
            sink.process(new ProcessAudioStream(p.getInputStream(), BROWSER_FORMAT));
        } catch (Exception e) {
            logPipeEnd("uplink", s, e);
        } finally {
            if (process != null) {
                process.destroy();
            }
            closeQuietly(cameraStream);
        }
    }

    /** Browser to camera speaker: raw browser PCM to camera codec. */
    private void runDownlink(Session s, CameraAudioCodec codec) {
        Process process = null;
        AudioStream browserStream = null;
        try {
            browserStream = s.browserSource.getInputStream(s.browserSource.getSupportedFormats().iterator().next());
            process = start(FfmpegAudioConverter.browserDownlinkCommand(codec));
            s.register(process);
            Process p = process;
            AudioStream feedStream = browserStream;
            workers.execute(() -> pump(feedStream, p.getOutputStream()));
            cameraSink.process(new ProcessAudioStream(p.getInputStream(), HomekitAudioSource.formatOf(codec)));
        } catch (Exception e) {
            logPipeEnd("downlink", s, e);
        } finally {
            if (process != null) {
                process.destroy();
            }
            closeQuietly(browserStream);
        }
    }

    private void logPipeEnd(String direction, Session s, Exception e) {
        if (s.isClosed()) {
            LOGGER.debug("Browser audio {} ended for ID '{}': {}", direction, id, e.getMessage());
        } else {
            LOGGER.warn("Browser audio {} failed for ID '{}': {}", direction, id, e.getMessage());
        }
    }

    private static Process start(List<String> command) throws IOException {
        return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static void pump(AudioStream from, OutputStream to) {
        try (from; to) {
            from.transferTo(to);
        } catch (IOException e) {
            LOGGER.debug("Browser audio pump ended: {}", e.getMessage());
        }
    }

    private static void closeQuietly(@Nullable AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOGGER.debug("Failed to close browser audio stream: {}", e.getMessage());
            }
        }
    }

    /** One connected browser endpoint pair and the ffmpeg processes serving it. */
    private static final class Session {
        private final AudioSource browserSource;
        private final AudioSink browserSink;
        private final Set<Process> processes = ConcurrentHashMap.newKeySet();
        private volatile boolean closed;

        Session(AudioSource browserSource, AudioSink browserSink) {
            this.browserSource = browserSource;
            this.browserSink = browserSink;
        }

        void register(Process process) {
            processes.add(process);
            if (closed) {
                process.destroy();
            }
        }

        boolean isClosed() {
            return closed;
        }

        void close() {
            closed = true;
            processes.forEach(Process::destroy);
            processes.clear();
        }
    }

    /** Presents a raw process output stream as a core {@link AudioStream} with a known format. */
    private static final class ProcessAudioStream extends AudioStream {
        private final InputStream in;
        private final AudioFormat format;

        ProcessAudioStream(InputStream in, AudioFormat format) {
            this.in = in;
            this.format = format;
        }

        @Override
        public AudioFormat getFormat() {
            return format;
        }

        @Override
        public int read() throws IOException {
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return in.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
