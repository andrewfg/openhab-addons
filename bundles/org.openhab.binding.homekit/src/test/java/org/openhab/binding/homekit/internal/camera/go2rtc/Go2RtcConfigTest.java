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

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.openhab.core.storage.Storage;
import org.openhab.core.test.storage.VolatileStorageService;

/**
 * Tests for {@link Go2RtcConfig} and {@link Go2RtcInstaller#assetName}.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class Go2RtcConfigTest {

    @Test
    void yamlContainsPortsAndDeclaredStreams() {
        String yaml = Go2RtcConfig.toYaml(new Go2RtcConfig.Address("192.168.1.10", 1984, 8555, 18554, 8443), "camera",
                "camera_talk", null);
        assertTrue(yaml.contains("api:\n  listen: \"192.168.1.10:1984\"\n"));
        assertTrue(yaml.contains("webrtc:\n  listen: \"192.168.1.10:8555\"\n"));
        assertTrue(yaml.contains("rtsp:\n  listen: \"127.0.0.1:18554\"\n"));
        assertTrue(yaml.contains("streams:\n  camera:\n  camera_talk:\n"));
        assertFalse(yaml.contains("tls_listen"));
    }

    @Test
    void yamlContainsTlsListenerWhenCertificateSupplied() {
        Go2RtcTls.TlsFiles tls = new Go2RtcTls.TlsFiles(Paths.get("/tmp/cert.pem"), Paths.get("/tmp/key.pem"));
        String yaml = Go2RtcConfig.toYaml(new Go2RtcConfig.Address("192.168.1.10", 1984, 8555, 18554, 8443), "camera",
                "camera_talk", tls);
        assertTrue(yaml.contains("api:\n  listen: \"192.168.1.10:1984\"\n  tls_listen: \"192.168.1.10:8443\"\n"
                + "  tls_cert: '\\tmp\\cert.pem'\n  tls_key: '\\tmp\\key.pem'\n"));
    }

    @Test
    void stableTcpPortReusesStoredPortAcrossCalls() throws IOException {
        Storage<String> storage = new VolatileStorageService().getStorage("test");
        int first = Go2RtcConfig.stableTcpPort(storage, "thing");
        assertEquals(first, Go2RtcConfig.stableTcpPort(storage, "thing"));
        assertEquals(Integer.toString(first), storage.get("thing"));
    }

    @Test
    void stableTcpPortReplacesStoredPortWhenInUse() throws IOException {
        Storage<String> storage = new VolatileStorageService().getStorage("test");
        try (ServerSocket busy = new ServerSocket(0)) {
            storage.put("thing", Integer.toString(busy.getLocalPort()));
            int port = Go2RtcConfig.stableTcpPort(storage, "thing");
            assertNotEquals(busy.getLocalPort(), port);
            assertEquals(Integer.toString(port), storage.get("thing"));
        }
    }

    @Test
    void yamlContainsNoExecSources() {
        String yaml = Go2RtcConfig.toYaml(new Go2RtcConfig.Address("192.168.1.10", 1984, 8555, 18554, 8443), "camera",
                "camera_talk", null);
        assertFalse(yaml.contains("exec:"));
        assertFalse(yaml.contains("backchannel"));
    }

    @Test
    void assetNameForLinuxIsRawBinary() throws IOException {
        assertEquals("go2rtc_linux_amd64", Go2RtcInstaller.assetName("Linux", "amd64"));
        assertEquals("go2rtc_linux_amd64", Go2RtcInstaller.assetName("Linux", "x86_64"));
        assertEquals("go2rtc_linux_arm64", Go2RtcInstaller.assetName("Linux", "aarch64"));
    }

    @Test
    void assetNameForOtherPlatformsIsZip() throws IOException {
        assertEquals("go2rtc_win64.zip", Go2RtcInstaller.assetName("Windows 11", "amd64"));
        assertEquals("go2rtc_mac_arm64.zip", Go2RtcInstaller.assetName("Mac OS X", "aarch64"));
        assertEquals("go2rtc_mac_amd64.zip", Go2RtcInstaller.assetName("Mac OS X", "x86_64"));
    }

    @Test
    void assetNameRejectsUnsupportedPlatform() {
        assertThrows(IOException.class, () -> Go2RtcInstaller.assetName("Linux", "riscv64"));
    }
}
