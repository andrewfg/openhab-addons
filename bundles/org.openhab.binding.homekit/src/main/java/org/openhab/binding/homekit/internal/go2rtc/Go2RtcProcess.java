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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one go2rtc process for a camera, using a generated YAML configuration file.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Go2RtcProcess {

    private static final Logger LOGGER = LoggerFactory.getLogger(Go2RtcProcess.class);
    private static final long STOP_TIMEOUT_SECONDS = 5;

    private final Path binary;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private @Nullable Process process;
    private @Nullable Path configFile;

    public Go2RtcProcess(Path binary) {
        this.binary = binary;
    }

    /**
     * Returns a free TCP port on the loopback interface. There is a small window between closing the probe socket
     * and go2rtc binding the port, which is acceptable for a per-camera local service.
     */
    public static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Writes the configuration and starts go2rtc. Any previously started process is stopped first.
     *
     * @param yaml the go2rtc configuration
     * @throws IOException if the configuration cannot be written or the process cannot be started
     */
    public synchronized void start(String yaml) throws IOException {
        stop();
        Path config = Files.createTempFile("go2rtc-", ".yaml");
        Files.writeString(config, yaml, StandardCharsets.UTF_8);
        configFile = config;

        Process p = new ProcessBuilder(binary.toString(), "-config", config.toString()).redirectErrorStream(true)
                .start();
        process = p;
        Thread reader = new Thread(() -> logOutput(p), "OH-binding-homekit-go2rtc-log");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Returns true if go2rtc answers on its API port.
     *
     * @param host the API address from the configuration
     * @param apiPort the API port from the configuration
     */
    public boolean isReady(String host, int apiPort) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + apiPort + "/api/config"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Returns true if the process has been started and is still running.
     */
    public synchronized boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /**
     * Stops the process, forcing it to exit if it does not stop within a few seconds, and removes the configuration.
     */
    public synchronized void stop() {
        Process p = process;
        process = null;
        if (p != null) {
            p.destroy();
            try {
                if (!p.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        Path config = configFile;
        configFile = null;
        if (config != null) {
            try {
                Files.deleteIfExists(config);
            } catch (IOException e) {
                LOGGER.debug("Failed to delete go2rtc configuration {}", config, e);
            }
        }
    }

    private static void logOutput(Process p) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOGGER.debug("go2rtc: {}", line);
            }
        } catch (IOException e) {
            LOGGER.debug("go2rtc output closed", e);
        }
    }
}
