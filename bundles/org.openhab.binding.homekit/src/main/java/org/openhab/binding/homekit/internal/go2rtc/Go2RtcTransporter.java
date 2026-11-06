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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homekit.internal.ffmpeg.FfmpegConverter;
import org.openhab.binding.homekit.internal.media.AudioCodec;
import org.openhab.binding.homekit.internal.media.tlv.SrtpParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loopback endpoints that connect the binding's media to go2rtc.
 *
 * Each of the audio and video channels receives the camera's SRTP RTP on an ephemeral UDP port. While a go2rtc client
 * is connected and media is started, the channel relays the RTP to an ffmpeg decoder, and streams the decoder's output
 * to that client over TCP. Data is queued and written by a separate thread, so a stalled client drops data instead of
 * blocking the HomeKit thread. The talk server hands each accepted connection, carrying browser audio from go2rtc, to a
 * consumer.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Go2RtcTransporter implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Go2RtcTransporter.class);
    private static final int AUDIO_QUEUE_CAPACITY = 128;
    private static final int VIDEO_QUEUE_CAPACITY = 256;
    private static final int MAX_DATAGRAM_SIZE = 2048;
    private static final int BUFFER_SIZE = 65536;

    public static final int AUDIO_PAYLOAD_TYPE = 110;
    public static final int VIDEO_PAYLOAD_TYPE = 99;

    /**
     * Receives notifications when the audio listen client connects or disconnects. Close of the transporter does not
     * notify.
     */
    public interface ListenObserver {
        void listenConnected();

        void listenDisconnected();
    }

    private final Channel audio;
    private final Channel video;
    private final ServerSocket talkServer;
    private final Consumer<InputStream> talkConsumer;
    private volatile boolean closed;

    /**
     * Opens loopback servers on ephemeral ports and starts accepting connections.
     *
     * @param audioObserver is notified when the audio listen client connects or disconnects
     * @param talkConsumer receives the input stream of each talk connection
     * @throws IOException if the servers cannot be opened
     */
    public Go2RtcTransporter(ListenObserver audioObserver, Consumer<InputStream> talkConsumer) throws IOException {
        this.talkConsumer = talkConsumer;
        audio = new Channel("audio", AUDIO_QUEUE_CAPACITY, audioObserver);
        try {
            video = new Channel("video", VIDEO_QUEUE_CAPACITY, null);
            talkServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            audio.close();
            throw e;
        }
        startAcceptThread("talk", talkServer, this::onTalkClient);
    }

    /** @return the loopback port that serves the audio listen stream */
    public int audioListenPort() {
        return audio.port();
    }

    /** @return the loopback port that serves the video listen stream */
    public int videoListenPort() {
        return video.port();
    }

    /** @return the loopback port that receives the talk audio */
    public int talkPort() {
        return talkServer.getLocalPort();
    }

    /** @return the loopback UDP port that receives the camera's audio RTP */
    public int audioRtpPort() {
        return audio.rtpPort();
    }

    /** @return the loopback UDP port that receives the camera's video RTP */
    public int videoRtpPort() {
        return video.rtpPort();
    }

    /**
     * Starts decoding the camera's audio, and its video if the session negotiated it. Decoders run only while a go2rtc
     * client is connected to the matching listen channel.
     *
     * @param ffmpeg the ffmpeg executable
     * @param codec the camera's audio codec
     * @param audioSrtp the camera's audio SRTP parameters from the SetupEndpoints response
     * @param videoSrtp the camera's video SRTP parameters, or null if video is not negotiated
     */
    public void startMedia(String ffmpeg, AudioCodec codec, SrtpParameters audioSrtp,
            @Nullable SrtpParameters videoSrtp) {
        Decoder audioSpec = new Decoder(port -> audioSdp(port, AUDIO_PAYLOAD_TYPE, codec),
                sdpFile -> FfmpegConverter.listenCommand(ffmpeg, sdpFile, audioSrtp, codec));
        if (videoSrtp == null) {
            startDecoders(audioSpec, null);
        } else {
            SrtpParameters videoParams = videoSrtp;
            startDecoders(audioSpec, new Decoder(port -> videoSdp(port, VIDEO_PAYLOAD_TYPE),
                    sdpFile -> FfmpegConverter.watchCommand(ffmpeg, sdpFile, videoParams)));
        }
    }

    /**
     * Stops all decoders. The RTP receivers and listen servers stay open for the next session.
     */
    public void stopMedia() {
        startDecoders(null, null);
    }

    void startDecoders(@Nullable Decoder audioSpec, @Nullable Decoder videoSpec) {
        audio.setDecoder(audioSpec);
        video.setDecoder(videoSpec);
    }

    static String audioSdp(int ffmpegPort, int payloadType, AudioCodec codec) {
        String encoding = switch (codec.codec()) {
            case OPUS -> "opus";
            case PCMU -> "PCMU";
            case PCMA -> "PCMA";
            case AMR -> "AMR";
            case AMR_WB -> "AMR-WB";
            case AAC_ELD, MSBC -> throw new IllegalArgumentException("Unsupported codec: " + codec.codec());
        };
        String fmtp = switch (codec.codec()) {
            case AMR, AMR_WB -> "a=fmtp:%d octet-align=1\n".formatted(payloadType);
            default -> "";
        };
        return """
                v=0
                o=- 0 0 IN IP4 127.0.0.1
                s=HomeKit listen
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio %d RTP/AVP %d
                a=rtpmap:%d %s/%d/%d
                %s""".formatted(ffmpegPort, payloadType, payloadType, encoding, codec.rtpClockRate(), codec.channels(),
                fmtp);
    }

    static String videoSdp(int ffmpegPort, int payloadType) {
        return """
                v=0
                o=- 0 0 IN IP4 127.0.0.1
                s=HomeKit listen video
                c=IN IP4 127.0.0.1
                t=0 0
                m=video %d RTP/AVP %d
                a=rtpmap:%d H264/90000
                a=fmtp:%d packetization-mode=1
                """.formatted(ffmpegPort, payloadType, payloadType, payloadType);
    }

    @Override
    public void close() {
        closed = true;
        audio.close();
        video.close();
        closeQuietly(talkServer);
    }

    private void startAcceptThread(String name, ServerSocket server, SocketHandler handler) {
        Thread thread = new Thread(() -> acceptLoop(server, handler), "OH-binding-homekit-go2rtc-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptLoop(ServerSocket server, SocketHandler handler) {
        while (!closed) {
            try {
                handler.handle(server.accept());
            } catch (IOException e) {
                if (!closed) {
                    LOGGER.debug("go2rtc transporter accept failed", e);
                }
                return;
            }
        }
    }

    private void onTalkClient(Socket socket) {
        Thread reader = new Thread(() -> {
            try (InputStream in = socket.getInputStream()) {
                talkConsumer.accept(in);
            } catch (IOException e) {
                LOGGER.debug("go2rtc talk client disconnected", e);
            } finally {
                closeQuietly(socket);
            }
        }, "OH-binding-homekit-go2rtc-talk-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private static void closeQuietly(@Nullable AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOGGER.trace("Ignoring close failure", e);
            }
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.debug("Failed to delete go2rtc temp file {}: {}", file, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface SocketHandler {
        void handle(Socket socket) throws IOException;
    }

    /**
     * An ffmpeg decoder specification. {@code sdp} builds the SDP that describes the RTP on the given ffmpeg port, and
     * {@code command} builds the ffmpeg command line that reads that SDP file.
     */
    record Decoder(IntFunction<String> sdp, Function<String, List<String>> command) {
    }

    /**
     * A running decoder: its ffmpeg process and the SDP file it reads.
     */
    private record Running(Process process, Path sdpFile) {
    }

    /**
     * A loopback server that streams decoded data to a single connected go2rtc client. It also receives the camera's
     * SRTP RTP, relays it to ffmpeg while a decoder runs, and discards it otherwise.
     */
    private class Channel implements AutoCloseable {

        private final String name;
        private final int capacity;
        private final @Nullable ListenObserver observer;
        private final ServerSocket server;
        private final DatagramSocket rtpSocket;
        private final Object lock = new Object();

        private @Nullable Socket client;
        private BlockingQueue<byte[]> queue;
        private @Nullable Decoder decoder;
        private @Nullable Running running;
        private volatile @Nullable Integer forwardPort;

        Channel(String name, int capacity, @Nullable ListenObserver observer) throws IOException {
            this.name = name;
            this.capacity = capacity;
            this.observer = observer;
            this.queue = new ArrayBlockingQueue<>(capacity);
            this.rtpSocket = new DatagramSocket(0);
            try {
                this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                rtpSocket.close();
                throw e;
            }
            startAcceptThread(name, server, this::onClient);
            Thread relay = new Thread(this::relayRtp, "OH-binding-homekit-go2rtc-" + name + "-rtp");
            relay.setDaemon(true);
            relay.start();
        }

        int port() {
            return server.getLocalPort();
        }

        int rtpPort() {
            return rtpSocket.getLocalPort();
        }

        void setDecoder(@Nullable Decoder spec) {
            synchronized (lock) {
                decoder = spec;
                restartDecoderLocked();
            }
        }

        private void relayRtp() {
            byte[] buffer = new byte[MAX_DATAGRAM_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while (!rtpSocket.isClosed()) {
                try {
                    packet.setLength(buffer.length);
                    rtpSocket.receive(packet);
                    Integer target = forwardPort;
                    if (target != null) {
                        rtpSocket.send(new DatagramPacket(buffer, packet.getLength(), InetAddress.getLoopbackAddress(),
                                target));
                    }
                } catch (IOException e) {
                    // a closed socket ends the loop; any other failure is transient and the datagram is dropped
                    if (rtpSocket.isClosed()) {
                        return;
                    }
                }
            }
        }

        /**
         * Queues decoded data for the connected client. Data from a decoder that has since been stopped or replaced is
         * dropped, as is data when no client is connected or the client cannot keep up.
         */
        private void publish(Running owner, byte[] buffer, int offset, int length) {
            synchronized (lock) {
                if (running != owner || client == null) {
                    return;
                }
                queue.offer(Arrays.copyOfRange(buffer, offset, offset + length));
            }
        }

        /** Must be called while holding {@code lock}. */
        private void restartDecoderLocked() {
            stopDecoderLocked();
            Decoder spec = decoder;
            if (spec != null && client != null) {
                try {
                    startDecoderLocked(spec);
                } catch (IOException e) {
                    LOGGER.warn("Failed to start go2rtc {} decoder: {}", name, e.getMessage());
                }
            }
        }

        /** Must be called while holding {@code lock}. */
        private void startDecoderLocked(Decoder spec) throws IOException {
            int ffmpegPort;
            try (DatagramSocket probe = new DatagramSocket(0)) {
                ffmpegPort = probe.getLocalPort();
            }
            Path sdp = Files.createTempFile("homekit-" + name + "-", ".sdp");
            try {
                Files.writeString(sdp, spec.sdp().apply(ffmpegPort), StandardCharsets.US_ASCII);
                Process process = new ProcessBuilder(spec.command().apply(sdp.toString()))
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                Running owner = new Running(process, sdp);
                running = owner;
                forwardPort = ffmpegPort;
                Thread copier = new Thread(() -> copy(owner), "OH-binding-homekit-go2rtc-" + name + "-decoder");
                copier.setDaemon(true);
                copier.start();
            } catch (IOException | RuntimeException e) {
                deleteQuietly(sdp);
                throw e;
            }
        }

        /** Must be called while holding {@code lock}. */
        private void stopDecoderLocked() {
            Running current = running;
            running = null;
            forwardPort = null;
            if (current != null) {
                current.process().destroy();
                deleteQuietly(current.sdpFile());
            }
        }

        private void copy(Running owner) {
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream input = owner.process().getInputStream()) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    publish(owner, buffer, 0, read);
                }
            } catch (IOException e) {
                LOGGER.debug("go2rtc {} decoder ended: {}", name, e.getMessage());
            }
        }

        @Override
        public void close() {
            closeQuietly(server);
            closeQuietly(rtpSocket);
            synchronized (lock) {
                Socket current = client;
                client = null;
                queue.clear();
                closeQuietly(current);
                decoder = null;
                stopDecoderLocked();
            }
        }

        private void onClient(Socket socket) {
            BlockingQueue<byte[]> clientQueue = new ArrayBlockingQueue<>(capacity);
            boolean replaced;
            synchronized (lock) {
                replaced = client != null;
                closeQuietly(client);
                client = socket;
                queue = clientQueue;
                restartDecoderLocked();
            }
            ListenObserver listener = observer;
            if (listener != null) {
                if (replaced) {
                    listener.listenDisconnected();
                }
                listener.listenConnected();
            }
            Thread writer = new Thread(() -> write(socket, clientQueue),
                    "OH-binding-homekit-go2rtc-" + name + "-writer");
            writer.setDaemon(true);
            writer.start();
        }

        private void write(Socket socket, BlockingQueue<byte[]> clientQueue) {
            try (OutputStream out = socket.getOutputStream()) {
                while (!closed && !socket.isClosed()) {
                    byte[] chunk = clientQueue.poll(1, TimeUnit.SECONDS);
                    if (chunk != null) {
                        out.write(chunk);
                        out.flush();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.debug("go2rtc {} client disconnected", name, e);
            } catch (IOException e) {
                LOGGER.debug("go2rtc {} client disconnected", name, e);
            } finally {
                boolean current;
                synchronized (lock) {
                    current = Objects.equals(client, socket);
                    if (current) {
                        client = null;
                        stopDecoderLocked();
                    }
                }
                closeQuietly(socket);
                ListenObserver listener = observer;
                if (current && !closed && listener != null) {
                    listener.listenDisconnected();
                }
            }
        }
    }
}
