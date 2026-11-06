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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locates or downloads the go2rtc binary, following the same approach as the unifi binding: a binary on the PATH
 * is used directly, otherwise the release asset is downloaded into the binding's cache directory.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Go2RtcInstaller {

    static final String VERSION = "1.9.14";
    private static final String NAME = "go2rtc";
    private static final String RELEASE_URL = "https://github.com/AlexxIT/go2rtc/releases/download/v" + VERSION + "/";

    private final Logger logger = LoggerFactory.getLogger(Go2RtcInstaller.class);
    private final Path baseDir;
    private final HttpClient httpClient;

    public Go2RtcInstaller(Path baseDir) {
        this.baseDir = baseDir;
        this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
    }

    /**
     * Returns the command to execute go2rtc, downloading the binary first if it is not available.
     *
     * @throws IOException if the binary is missing and cannot be downloaded or extracted
     * @throws InterruptedException if interrupted while downloading
     */
    public Path ensureBinary() throws IOException, InterruptedException {
        String os = Objects.requireNonNull(System.getProperty("os.name", ""));
        String arch = Objects.requireNonNull(System.getProperty("os.arch", ""));
        if (isOnPath(NAME, os)) {
            return Paths.get(NAME);
        }

        String asset = assetName(os, arch);
        Path destDir = baseDir.resolve(os.toLowerCase(Locale.ROOT) + "-" + arch.toLowerCase(Locale.ROOT))
                .resolve(VERSION);
        Path destBinary = destDir.resolve(NAME + (isWindows(os) ? ".exe" : ""));
        if (!Files.exists(destBinary)) {
            logger.debug("Downloading {} to {}", asset, destBinary);
            download(RELEASE_URL + asset, asset, destDir, destBinary, os);
        }
        destBinary.toFile().setExecutable(true);
        return destBinary;
    }

    /**
     * Returns the release asset name for the given platform. Linux amd64 and arm64 are published as raw binaries, all
     * other platforms as zip archives.
     *
     * @throws IOException if the platform is not supported
     */
    static String assetName(String osName, String arch) throws IOException {
        String os = osName.toLowerCase(Locale.ROOT);
        String cpu = arch.toLowerCase(Locale.ROOT);
        boolean x64 = cpu.contains("amd64") || cpu.contains("x86_64") || "x64".equals(cpu);
        boolean arm64 = cpu.contains("aarch64") || cpu.contains("arm64");
        if (os.contains("linux") && x64) {
            return "go2rtc_linux_amd64";
        }
        if (os.contains("linux") && arm64) {
            return "go2rtc_linux_arm64";
        }
        if (os.contains("freebsd") && x64) {
            return "go2rtc_freebsd_amd64.zip";
        }
        if (os.contains("freebsd") && arm64) {
            return "go2rtc_freebsd_arm64.zip";
        }
        if (os.contains("mac")) {
            return arm64 || cpu.contains("arm") ? "go2rtc_mac_arm64.zip" : "go2rtc_mac_amd64.zip";
        }
        if (os.contains("win")) {
            return "go2rtc_win64.zip";
        }
        throw new IOException("Unsupported platform for go2rtc: " + osName + " " + arch);
    }

    private boolean isOnPath(String name, String os) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return false;
        }
        String fileName = name + (isWindows(os) ? ".exe" : "");
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (Files.isExecutable(Paths.get(dir, fileName))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWindows(String os) {
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    private void download(String url, String asset, Path destDir, Path destBinary, String os)
            throws IOException, InterruptedException {
        Files.createDirectories(destDir);
        Path tmp = Files.createTempFile(destDir, "go2rtc-download-", ".tmp");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET().build();
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
            if (response.statusCode() != 200) {
                throw new IOException("Unexpected HTTP status " + response.statusCode() + " for " + url);
            }

            if (asset.endsWith(".zip")) {
                extractFromZip(tmp, destBinary, url);
            } else {
                Files.move(tmp, destBinary, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        if (!isWindows(os)) {
            destBinary.toFile().setExecutable(true);
        }
    }

    private static void extractFromZip(Path zipFile, Path destBinary, String url) throws IOException {
        String expected = destBinary.getFileName().toString();
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            ZipEntry entry = zip.stream().filter(e -> !e.isDirectory())
                    .filter(e -> expected.equals(Paths.get(e.getName()).getFileName().toString())).findFirst()
                    .orElseThrow(() -> new IOException("Could not find " + expected + " inside archive " + url));
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, destBinary, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
