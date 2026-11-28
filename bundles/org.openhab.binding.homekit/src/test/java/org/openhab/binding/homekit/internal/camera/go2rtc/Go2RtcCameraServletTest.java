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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.List;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openhab.core.auth.AuthenticationException;
import org.openhab.core.io.rest.auth.AnonymousUserSecurityContext;
import org.openhab.core.io.rest.auth.AuthFilter;
import org.openhab.core.net.NetworkAddressService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.SecurityContext;

/**
 * Tests that every registered camera is served, and that a missing camera is not turned into a container error page.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
class Go2RtcCameraServletTest {

    @Test
    void servesEveryRegisteredCamera() throws Exception {
        Go2RtcCameraServlet servlet = servlet();
        servlet.addProvider("55bcd1cd8b1e", provider());
        servlet.addProvider("6cb70c2223a0", provider());

        Captured first = get(servlet, "/55bcd1cd8b1e", "/homekit/camera/55bcd1cd8b1e");
        Captured second = get(servlet, null, "/homekit/camera/6cb70c2223a0");

        assertEquals(HttpServletResponse.SC_OK, first.status);
        assertEquals(HttpServletResponse.SC_OK, second.status);
        assertTrue(first.body.contains("<video"));
        assertTrue(second.body.contains("<video"));
        verify(first.response, never()).sendError(anyInt());
        verify(second.response, never()).sendError(anyInt());
    }

    @Test
    void missingCameraDoesNotDispatchAnErrorPage() throws Exception {
        Captured captured = get(servlet(), "/6cb70c2223a0", "/homekit/camera/6cb70c2223a0");

        assertEquals(HttpServletResponse.SC_NOT_FOUND, captured.status);
        verify(captured.response, never()).sendError(anyInt());
        verify(captured.response, never()).sendError(anyInt(), anyString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aliasFollowsProviderLifecycle() {
        BundleContext bundleContext = mock(BundleContext.class);
        List<ServiceRegistration<Servlet>> registrations = new ArrayList<>();
        when(bundleContext.registerService(eq(Servlet.class), any(Servlet.class), any(Dictionary.class)))
                .thenAnswer(invocation -> {
                    ServiceRegistration<Servlet> registration = mock(ServiceRegistration.class);
                    registrations.add(registration);
                    return registration;
                });
        Go2RtcCameraServlet servlet = new Go2RtcCameraServlet(mock(NetworkAddressService.class), bundleContext);
        Go2RtcCameraServlet.BrowserStreamProvider provider = provider();
        servlet.addProvider("55bcd1cd8b1e", provider);
        servlet.removeProvider("55bcd1cd8b1e", provider);
        servlet.addProvider("55bcd1cd8b1e", provider);
        assertEquals(2, registrations.size());
        verify(registrations.get(0)).unregister();
        verify(registrations.get(1), never()).unregister();
        servlet.deactivate();
        verify(registrations.get(1)).unregister();
    }

    @Test
    void loginRequiredRejectsMissingAndAnonymousCredentials() throws Exception {
        AuthFilter filter = mock(AuthFilter.class);
        Go2RtcCameraServlet servlet = servlet();
        servlet.setAuthFilter(filter);
        servlet.addProvider("55bcd1cd8b1e", loginProvider());

        when(filter.getSecurityContext(any(HttpServletRequest.class), eq(false))).thenReturn(null);
        Captured none = get(servlet, null, "/homekit/camera/55bcd1cd8b1e");
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, none.status);
        verify(none.response).setHeader(eq("WWW-Authenticate"), startsWith("Basic"));

        when(filter.getSecurityContext(any(HttpServletRequest.class), eq(false)))
                .thenReturn(new AnonymousUserSecurityContext());
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get(servlet, null, "/homekit/camera/55bcd1cd8b1e").status);
    }

    @Test
    void loginRequiredAcceptsValidUserAndThrottlesBadCredentials() throws Exception {
        AuthFilter filter = mock(AuthFilter.class);
        Go2RtcCameraServlet servlet = servlet();
        servlet.setAuthFilter(filter);
        servlet.addProvider("55bcd1cd8b1e", loginProvider());

        when(filter.getSecurityContext(any(HttpServletRequest.class), eq(false)))
                .thenReturn(mock(SecurityContext.class));
        assertEquals(HttpServletResponse.SC_OK, get(servlet, null, "/homekit/camera/55bcd1cd8b1e").status);

        when(filter.getSecurityContext(any(HttpServletRequest.class), eq(false)))
                .thenThrow(new AuthenticationException("bad"));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get(servlet, null, "/homekit/camera/55bcd1cd8b1e").status);
        assertEquals(429, get(servlet, null, "/homekit/camera/55bcd1cd8b1e").status);
    }

    @Test
    void loginRequiredFailsClosedWithoutAuthService() throws Exception {
        Go2RtcCameraServlet servlet = servlet();
        servlet.addProvider("55bcd1cd8b1e", loginProvider());
        assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                get(servlet, null, "/homekit/camera/55bcd1cd8b1e").status);
    }

    private static Go2RtcCameraServlet.BrowserStreamProvider loginProvider() {
        Go2RtcCameraServlet.BrowserStreamProvider base = provider();
        return new Go2RtcCameraServlet.BrowserStreamProvider() {
            @Override
            public boolean requiresCredentials() {
                return true;
            }

            @Override
            public boolean openBrowserStream() throws InterruptedException {
                return base.openBrowserStream();
            }

            @Override
            public String answerBrowserOffer(String offer) throws IOException {
                return base.answerBrowserOffer(offer);
            }

            @Override
            public String answerBrowserTalkOffer(String offer) throws IOException {
                return base.answerBrowserTalkOffer(offer);
            }
        };
    }

    private static Go2RtcCameraServlet servlet() {
        return new Go2RtcCameraServlet(mock(NetworkAddressService.class), mock(BundleContext.class));
    }

    private static Go2RtcCameraServlet.BrowserStreamProvider provider() {
        return new Go2RtcCameraServlet.BrowserStreamProvider() {
            @Override
            public boolean openBrowserStream() {
                return true;
            }

            @Override
            public String answerBrowserOffer(String offer) {
                return null;
            }

            @Override
            public String answerBrowserTalkOffer(String offer) {
                return null;
            }
        };
    }

    private static Captured get(Go2RtcCameraServlet servlet, @Nullable String pathInfo, String requestUri)
            throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getPathInfo()).thenReturn(pathInfo);
        when(request.getRequestURI()).thenReturn(requestUri);
        when(request.getContextPath()).thenReturn("");
        when(request.getRemoteAddr()).thenReturn("192.168.0.10");
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        servlet.doGet(request, response);
        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(response).setStatus(status.capture());
        return new Captured(response, status.getValue(), body.toString());
    }

    private record Captured(HttpServletResponse response, int status, String body) {
    }
}
