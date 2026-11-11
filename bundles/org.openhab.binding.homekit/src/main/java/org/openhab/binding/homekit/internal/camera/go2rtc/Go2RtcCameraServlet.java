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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.net.HttpServiceUtil;
import org.openhab.core.net.NetworkAddressService;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;

/**
 * Serves the browser viewer URL of a HomeKit camera. A request opens the camera stream on demand and, once go2rtc
 * has a producer, redirects the browser to the go2rtc viewer page.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
@Component(immediate = true, service = { Servlet.class, Go2RtcCameraServlet.class }, property = {
        HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=" + Go2RtcCameraServlet.PATH + "/*",
        HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME + "=homekit-camera" })
public class Go2RtcCameraServlet extends HttpServlet {

    /**
     * Provides the go2rtc viewer page for one camera, starting the camera stream if necessary.
     */
    public interface BrowserStreamProvider {

        /**
         * Opens the camera stream for a browser and waits until it is available.
         *
         * @return the go2rtc viewer URL, or null if the stream could not be opened in time
         * @throws InterruptedException if the waiting thread is interrupted
         */
        @Nullable
        String openBrowserStream() throws InterruptedException;
    }

    public static final String PATH = "/homekit/camera";

    private static final long serialVersionUID = 1L;

    private final Map<String, BrowserStreamProvider> providers = new ConcurrentHashMap<>();
    private final NetworkAddressService networkAddressService;
    private final BundleContext bundleContext;

    @Activate
    public Go2RtcCameraServlet(@Reference NetworkAddressService networkAddressService, BundleContext bundleContext) {
        this.networkAddressService = networkAddressService;
        this.bundleContext = bundleContext;
    }

    /**
     * Returns the openHAB URL that serves the viewer of the given camera.
     *
     * @param thingId the thing ID of the camera
     * @return the URL, or null if the openHAB address or HTTP port cannot be determined
     */
    public @Nullable String viewerUrl(String thingId) {
        String ipAddress = networkAddressService.getPrimaryIpv4HostAddress();
        if (ipAddress == null) {
            return null;
        }
        int httpsPort = HttpServiceUtil.getHttpsServicePort(bundleContext);
        if (httpsPort != -1) {
            return "https://" + ipAddress + ":" + httpsPort + PATH + "/" + thingId;
        }
        int port = HttpServiceUtil.getHttpServicePort(bundleContext);
        if (port == -1) {
            return null;
        }
        return "http://" + ipAddress + ":" + port + PATH + "/" + thingId;
    }

    public void addProvider(String thingId, BrowserStreamProvider provider) {
        providers.put(thingId, provider);
    }

    public void removeProvider(String thingId, BrowserStreamProvider provider) {
        providers.remove(thingId, provider);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String pathInfo = request.getPathInfo();
        if (pathInfo == null || pathInfo.length() < 2) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        @Nullable
        BrowserStreamProvider provider = providers.get(pathInfo.substring(1));
        if (provider == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String url;
        try {
            url = provider.openBrowserStream();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        if (url == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "camera stream not available");
            return;
        }
        response.sendRedirect(url);
    }
}
