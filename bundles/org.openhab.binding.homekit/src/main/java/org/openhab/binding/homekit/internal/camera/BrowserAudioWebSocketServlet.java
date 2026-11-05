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
package org.openhab.binding.homekit.internal.camera;

import java.io.Serial;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link BrowserAudioWebSocketServlet} accepts browser WebSocket connections at {@value #SERVLET_PATH}/{token}. The
 * token identifies the {@link HomekitAudioSource} registered for a HomeKit session and is the only credential, so an
 * unknown token is rejected with 404.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
@WebServlet(urlPatterns = { BrowserAudioWebSocketServlet.SERVLET_PATH + "/*" })
@Component(service = HttpServlet.class, configurationPolicy = ConfigurationPolicy.OPTIONAL)
public class BrowserAudioWebSocketServlet extends WebSocketServlet {

    public static final String SERVLET_PATH = "/homekit/audio/ws";

    @Serial
    private static final long serialVersionUID = 1L;

    private final Logger logger = LoggerFactory.getLogger(BrowserAudioWebSocketServlet.class);
    private final BrowserAudioRegistry registry;

    @Activate
    public BrowserAudioWebSocketServlet(@Reference BrowserAudioRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void configure(@Nullable WebSocketServletFactory factory) {
        if (factory == null) {
            logger.warn("Could not configure browser audio WebSocket servlet");
            return;
        }
        factory.setCreator((request, response) -> {
            String path = request.getRequestURI().getPath();
            String prefix = SERVLET_PATH + "/";
            String token = path.startsWith(prefix) ? path.substring(prefix.length()) : "";
            HomekitAudioSource source = token.isEmpty() ? null : registry.find(token);
            if (source == null) {
                logger.debug("Browser audio WebSocket rejected, unknown session");
                response.setStatusCode(HttpStatus.NOT_FOUND_404);
                return null;
            }
            return new BrowserAudioSocket(source);
        });
    }
}
