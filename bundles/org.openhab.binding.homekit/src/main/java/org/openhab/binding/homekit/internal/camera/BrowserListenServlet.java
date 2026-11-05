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

import java.io.IOException;
import java.io.Serial;
import java.nio.charset.StandardCharsets;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;

/**
 * {@link BrowserListenServlet} serves the listen page at {@value #SERVLET_PATH}/{token}. The page opens the
 * WebSocket at {@link BrowserAudioWebSocketServlet#SERVLET_PATH} with the same token and plays the 16 kHz mono PCM
 * stream that it receives.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
@WebServlet(urlPatterns = { BrowserListenServlet.SERVLET_PATH + "/*" })
@Component(service = HttpServlet.class, configurationPolicy = ConfigurationPolicy.OPTIONAL)
public class BrowserListenServlet extends HttpServlet {

    public static final String SERVLET_PATH = "/homekit/audio/listen";

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String PAGE = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"><title>HomeKit listen</title></head>
            <body>
            <button id="start">Start listening</button>
            <span id="status">idle</span>
            <script>
            (function () {
              const token = decodeURIComponent(location.pathname.split('/').pop());
              const RATE = 16000;
              const status = document.getElementById('status');
              document.getElementById('start').onclick = function () {
                const ctx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: RATE });
                let nextTime = 0;
                const scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
                const ws = new WebSocket(scheme + location.host + '%s/' + encodeURIComponent(token));
                ws.binaryType = 'arraybuffer';
                ws.onopen = function () { status.textContent = 'listening'; ctx.resume(); };
                ws.onclose = function () { status.textContent = 'closed'; };
                ws.onerror = function () { status.textContent = 'error'; };
                ws.onmessage = function (event) {
                  const pcm = new Int16Array(event.data);
                  const buffer = ctx.createBuffer(1, pcm.length, RATE);
                  const samples = buffer.getChannelData(0);
                  for (let i = 0; i < pcm.length; i++) { samples[i] = pcm[i] / 32768; }
                  const source = ctx.createBufferSource();
                  source.buffer = buffer;
                  source.connect(ctx.destination);
                  nextTime = Math.max(nextTime, ctx.currentTime + 0.1);
                  source.start(nextTime);
                  nextTime += buffer.duration;
                };
              };
            })();
            </script>
            </body>
            </html>
            """.formatted(BrowserAudioWebSocketServlet.SERVLET_PATH);

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/html");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(PAGE);
    }
}
