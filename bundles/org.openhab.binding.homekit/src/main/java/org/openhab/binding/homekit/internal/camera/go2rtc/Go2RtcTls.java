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
package org.openhab.binding.homekit.internal.camera.go2rtc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Base64;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exports the openHAB core HTTPS certificate and private key as PEM files, which go2rtc requires for its TLS listener.
 * The core keystore is read from the {@code jetty.keystore.path} system property, as created by the core
 * certificate generator.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public final class Go2RtcTls {

    /**
     * The PEM files for go2rtc: the certificate chain and the private key.
     */
    public record TlsFiles(Path cert, Path key) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Go2RtcTls.class);

    private static final String KEYSTORE_PATH_PROPERTY = "jetty.keystore.path";
    private static final String KEYSTORE_PASSWORD = "openhab";
    private static final String KEYSTORE_ALIAS = "mykey";
    private static final String KEYSTORE_TYPE = "JKS";
    private static final String CERT_FILE_NAME = "go2rtc-cert.pem";
    private static final String KEY_FILE_NAME = "go2rtc-key.pem";

    private Go2RtcTls() {
    }

    /**
     * Writes the core certificate chain and private key into the given directory as PEM files.
     *
     * @param dir the directory to write the PEM files to
     * @return the PEM files, or null if the core keystore is not available or cannot be exported
     */
    public static @Nullable TlsFiles exportCoreCertificate(Path dir) {
        String keystorePath = System.getProperty(KEYSTORE_PATH_PROPERTY);
        if (keystorePath == null || keystorePath.isEmpty() || !Files.isRegularFile(Paths.get(keystorePath))) {
            LOGGER.debug("openHAB keystore not found, go2rtc will not serve HTTPS");
            return null;
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
            try (InputStream stream = Files.newInputStream(Paths.get(keystorePath))) {
                keyStore.load(stream, KEYSTORE_PASSWORD.toCharArray());
            }
            Certificate[] chain = keyStore.getCertificateChain(KEYSTORE_ALIAS);
            if (chain == null || chain.length == 0
                    || !(keyStore.getKey(KEYSTORE_ALIAS, KEYSTORE_PASSWORD.toCharArray()) instanceof PrivateKey key)) {
                LOGGER.debug("openHAB keystore has no '{}' certificate and key", KEYSTORE_ALIAS);
                return null;
            }
            byte[] keyEncoded = key.getEncoded();
            if (keyEncoded == null) {
                LOGGER.debug("openHAB private key is not extractable");
                return null;
            }

            StringBuilder certPem = new StringBuilder();
            for (Certificate certificate : chain) {
                certPem.append(pem("CERTIFICATE", certificate.getEncoded()));
            }

            Files.createDirectories(dir);
            Path certFile = dir.resolve(CERT_FILE_NAME);
            Path keyFile = dir.resolve(KEY_FILE_NAME);
            Files.writeString(certFile, certPem.toString(), StandardCharsets.US_ASCII);
            writeOwnerOnly(keyFile, pem("PRIVATE KEY", keyEncoded));
            return new TlsFiles(certFile, keyFile);
        } catch (IOException | GeneralSecurityException e) {
            LOGGER.warn("Cannot export the openHAB HTTPS certificate for go2rtc: {}", e.getMessage());
            return null;
        }
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    /**
     * Writes the file so that only the owner can read it, where the file system supports POSIX permissions.
     */
    private static void writeOwnerOnly(Path file, String content) throws IOException {
        Files.deleteIfExists(file);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.writeString(file, content, StandardCharsets.US_ASCII);
    }
}
