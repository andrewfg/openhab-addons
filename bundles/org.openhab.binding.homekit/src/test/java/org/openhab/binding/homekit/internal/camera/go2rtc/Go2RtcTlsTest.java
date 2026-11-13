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

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link Go2RtcTls}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class Go2RtcTlsTest {

    @TempDir
    Path dir;

    @Test
    void certificateMatchesHostAddress() throws Exception {
        Go2RtcTls.TlsFiles tls = Go2RtcTls.generateCertificate(dir, "192.168.1.232");
        assertNotNull(tls);
        X509Certificate cert = readCertificate(tls.cert());
        assertTrue(hasIpAddress(cert, "192.168.1.232"));
        assertFalse(hasIpAddress(cert, "192.168.1.233"));
        assertTrue(cert.getNotAfter().after(cert.getNotBefore()));
        assertEquals(cert.getSubjectX500Principal(), cert.getIssuerX500Principal());
        assertTrue(cert.getSubjectX500Principal().getName().contains("CN=192.168.1.232"));
        assertTrue(cert.getIssuerX500Principal().getName().contains("O=openHAB"));
    }

    @Test
    void existingCertificateIsReusedForSameHost() throws Exception {
        Go2RtcTls.TlsFiles first = Go2RtcTls.generateCertificate(dir, "192.168.1.232");
        assertNotNull(first);
        String firstContent = Files.readString(first.cert());

        Go2RtcTls.TlsFiles second = Go2RtcTls.generateCertificate(dir, "192.168.1.232");
        assertNotNull(second);
        assertEquals(firstContent, Files.readString(second.cert()));
    }

    @Test
    void certificateIsRegeneratedForDifferentHost() throws Exception {
        Go2RtcTls.TlsFiles first = Go2RtcTls.generateCertificate(dir, "192.168.1.232");
        assertNotNull(first);
        String firstContent = Files.readString(first.cert());

        Go2RtcTls.TlsFiles second = Go2RtcTls.generateCertificate(dir, "192.168.1.233");
        assertNotNull(second);
        assertNotEquals(firstContent, Files.readString(second.cert()));
        assertTrue(hasIpAddress(readCertificate(second.cert()), "192.168.1.233"));
    }

    private static X509Certificate readCertificate(Path file) throws Exception {
        try (InputStream stream = Files.newInputStream(file)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(stream);
        }
    }

    private static boolean hasIpAddress(X509Certificate cert, String host) throws Exception {
        Collection<List<?>> names = cert.getSubjectAlternativeNames();
        assertNotNull(names);
        for (List<?> name : names) {
            if (Integer.valueOf(7).equals(name.get(0)) && host.equals(name.get(1))) {
                return true;
            }
        }
        return false;
    }
}
