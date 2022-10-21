/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
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
package org.openhab.binding.hue.internal.clip2.connection;

import java.io.Closeable;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.sse.InboundSseEvent;
import javax.ws.rs.sse.SseEventSource;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.openhab.binding.hue.internal.clip2.dto.Event;
import org.openhab.binding.hue.internal.clip2.dto.Reference;
import org.openhab.binding.hue.internal.clip2.dto.Resource;
import org.openhab.binding.hue.internal.clip2.dto.Resources;
import org.openhab.binding.hue.internal.clip2.enums.ResourceType;
import org.openhab.binding.hue.internal.clip2.handler.Clip2BridgeHandler;
import org.openhab.binding.hue.internal.exceptions.ApiException;
import org.osgi.service.jaxrs.client.SseEventSourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/**
 * Class that handles HTTP and SSE connections to/from a Hue Bridge running CLIP 2.
 *
 * @author Andrew Fiddian-Green - Initial Contribution
 */
@NonNullByDefault
public class Clip2Bridge implements Closeable, HostnameVerifier, ClientRequestFilter {

    private final Logger logger = LoggerFactory.getLogger(Clip2Bridge.class);

    private static final String FORMAT_URL_RESOURCE = "https://%s/clip/v2/resource/";
    private static final String FORMAT_URL_EVENTS = "https://%s/eventstream/clip/v2";
    private static final String HUE_APPLICATION_KEY = "hue-application-key";
    private static final String APPLICATION_JSON = "application/json";

    private final HttpClient httpClient;
    private final ClientBuilder clientBuilder;
    private final SseEventSourceFactory eventSourceFactory;
    private final String applicationKey;
    private final String hostName;
    private final String baseUrl;
    private final String eventUrl;
    private final Clip2BridgeHandler bridgeHandler;
    private final Duration timeout = Duration.of(5, ChronoUnit.SECONDS);
    private final Gson gson = new Gson();

    private @Nullable Client sseClient;
    private @Nullable SseEventSource eventSource;

    public Clip2Bridge(HttpClient httpClient, ClientBuilder clientBuilder, SseEventSourceFactory eventSourceFactory,
            Clip2BridgeHandler bridgeHandler, String hostName, String applicationKey) {
        this.httpClient = httpClient;
        this.clientBuilder = clientBuilder;
        this.eventSourceFactory = eventSourceFactory;
        this.applicationKey = applicationKey;
        this.bridgeHandler = bridgeHandler;
        this.hostName = hostName;
        this.baseUrl = String.format(FORMAT_URL_RESOURCE, hostName);
        this.eventUrl = String.format(FORMAT_URL_EVENTS, hostName);
    }

    /*
     * ++++++++++++++++++++++++++++++++++
     * + Public Methods
     * ++++++++++++++++++++++++++++++++++
     */

    /**
     * Close the SSE connection.
     */
    @Override
    public void close() {
        SseEventSource eventSource = this.eventSource;
        if (eventSource != null) {
            eventSource.close();
            this.eventSource = null;
        }
        Client sseClient = this.sseClient;
        if (sseClient != null) {
            sseClient.close();
            this.sseClient = null;
        }
    }

    /**
     * Filter to add the application key header to SSE requests.
     */
    @Override
    public void filter(@Nullable ClientRequestContext requestContext) {
        if (requestContext != null) {
            requestContext.getHeaders().add(HUE_APPLICATION_KEY, applicationKey);
        }
    }

    /**
     * Host name verifier for SSE requests.
     */
    @Override
    public boolean verify(@Nullable String hostName, @Nullable SSLSession sslSession) {
        return this.hostName.equals(hostName);
    }

    /**
     * Open the SSE connection.
     */
    public void openSse() {
        Client sseClient = this.sseClient;
        SseEventSource eventSource = this.eventSource;
        if (sseClient != null && eventSource != null && eventSource.isOpen()) {
            return;
        }
        close();
        sseClient = clientBuilder //
                .sslContext(httpClient.getSslContextFactory().getSslContext()) //
                .register(this) //
                .hostnameVerifier(this) //
                .readTimeout(0, TimeUnit.SECONDS) //
                .build();
        eventSource = eventSourceFactory //
                .newBuilder(sseClient.target(eventUrl)) //
                .reconnectingEvery(3, TimeUnit.MINUTES) //
                .build();
        eventSource.register(this::onSseEvent, this::onSseError, this::onSseComplete);
        eventSource.open();
        this.sseClient = sseClient;
        this.eventSource = eventSource;
    }

    /**
     * HTTP GET a Resources object containing multiple Resource instances from the server
     *
     * @param resourceType the type of resource to get
     * @return a list of resources, may be an empty list
     * @throws ApiException if something fails
     */
    public Resources getResources(ResourceType resourceType) throws ApiException {
        return doHTTP(HttpMethod.GET, getFullPath(resourceType, null), null);
    }

    /**
     * HTTP GET a Resources object containing a single Resource from the server
     *
     * @param resourceType the type of resource to get
     * @param resourceId the id of the resource to get
     * @return the resource, or null
     * @throws ApiException if something fails
     */
    public @Nullable Resources getResource(Reference reference) throws ApiException {
        return doHTTP(HttpMethod.GET, getFullPath(reference.getType(), reference.getId()), null);
    }

    /**
     * HTTP PUT a Resource object to the server
     *
     * @param resource the resource to put
     * @throws ApiException if something fails
     */
    public void putResource(Resource resource) throws ApiException {
        doHTTP(HttpMethod.PUT, getFullPath(resource.getType(), resource.getId()), resource);
    }

    /*
     * ++++++++++++++++++++++++++++++++++
     * + Private Methods
     * ++++++++++++++++++++++++++++++++++
     */

    /**
     * Build an exception message around another exception.
     *
     * @param e the exception
     * @return the message
     */
    private String exceptionMessageFrom(Exception e) {
        return String.format("%s, %s", e.getClass().getSimpleName(), e.getMessage()).toLowerCase();
    }

    /**
     * SSE calls this method when an event comes in.
     * It forwards the event data as a list of Resources to the bridge handler.
     *
     * @param inboundSseEvent the incoming SSE event.
     */
    private void onSseEvent(InboundSseEvent inboundSseEvent) {
        if (inboundSseEvent.isEmpty()) {
            return;
        }
        String json = inboundSseEvent.readData();
        if (json == null) {
            return;
        }
        logger.trace("onSseEvent() data:{}", json);
        List<Event> eventList;
        try {
            eventList = gson.fromJson(json, Event.EVENT_LIST_TYPE);
        } catch (JsonParseException e) {
            logger.debug("onSseEvent() {}", exceptionMessageFrom(e));
            return;
        }
        if (eventList == null) {
            logger.debug("onSseEvent() event list is null");
            return;
        }
        List<Resource> resourceList = new ArrayList<>();
        for (Event event : eventList) {
            resourceList.addAll(event.getData());
        }
        if (resourceList.isEmpty()) {
            logger.debug("onSseEvent() resource list is empty");
            return;
        }
        bridgeHandler.onSseEvent(resourceList);
    }

    /**
     * Log SSE errors.
     *
     * @param e the exception that caused the error.
     */
    private void onSseError(Throwable e) {
        logger.debug("onSseError() exception '{}' - {}", e.getClass().getSimpleName(), e.getMessage());
    }

    /**
     * Log when an SSE session has completed.
     *
     */
    private void onSseComplete() {
        logger.debug("onSseComplete() disconnected from event stream");
    }

    /**
     * Build a full path to a server end point
     *
     * @param resourceType the type of resource to get/put
     * @param id the id of the resource to get/put, may be null to get/put all
     * @return the full path
     */
    private String getFullPath(ResourceType resourceType, @Nullable String id) {
        String url = baseUrl + resourceType.name().toLowerCase();
        return id == null || id.isEmpty() ? url : url + "/" + id;
    }

    /**
     * Execute an HTTP GET or PUT command. It sends the pay-load Resource object (if any) and returns the reply
     * Resources object.
     *
     * @param method HTTP GET or PUT
     * @param url the URL of the server end point
     * @param resource the Resource object to send; may be null
     * @return the Resources object containing the response
     * @throws ApiException if anything goes wrong
     */
    private Resources doHTTP(HttpMethod method, String url, @Nullable Resource resource) throws ApiException {
        logger.trace("doHTTP() method:{}, url:{}", method, url);
        Request request = httpClient.newRequest(url).method(method).timeout(timeout.getSeconds(), TimeUnit.SECONDS);
        request.header(HUE_APPLICATION_KEY, applicationKey);
        if (resource != null) {
            String json = gson.toJson(resource);
            logger.trace("doHTTP() request:{}", json);
            request.content(new StringContentProvider(json), APPLICATION_JSON);
        }
        ContentResponse contentResponse;
        try {
            contentResponse = request.send();
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            throw new ApiException(exceptionMessageFrom(e));
        }
        int httpStatus = contentResponse.getStatus();
        String json = contentResponse.getContentAsString().trim();
        logger.trace("doHTTP() status:{}, body:{}", httpStatus, json);
        try {
            Resources resources = gson.fromJson(json, Resources.class);
            if (resources == null) {
                throw new ApiException("resources object is null");
            }
            if (logger.isDebugEnabled()) {
                for (String errorResponse : resources.getErrors()) {
                    logger.debug("doHTTP() error response:{}", errorResponse);
                }
            }
            return resources;
        } catch (JsonParseException e) {
            throw new ApiException(exceptionMessageFrom(e));
        }
    }
}
