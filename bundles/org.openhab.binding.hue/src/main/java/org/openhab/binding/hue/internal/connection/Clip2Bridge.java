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
package org.openhab.binding.hue.internal.connection;

import java.io.Closeable;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.hue.internal.dto.CreateUserRequest;
import org.openhab.binding.hue.internal.dto.SuccessResponse;
import org.openhab.binding.hue.internal.dto.clip2.Event;
import org.openhab.binding.hue.internal.dto.clip2.Resource;
import org.openhab.binding.hue.internal.dto.clip2.ResourceReference;
import org.openhab.binding.hue.internal.dto.clip2.Resources;
import org.openhab.binding.hue.internal.dto.clip2.enums.ResourceType;
import org.openhab.binding.hue.internal.exceptions.ApiException;
import org.openhab.binding.hue.internal.handler.Clip2BridgeHandler;
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

    private static final String APPLICATION_ID = "org.openhab.binding.hue.clip2";

    private static final String FORMAT_URL_RESOURCE = "https://%s/clip/v2/resource/";
    private static final String FORMAT_URL_REGISTRATION = "http://%s/api";
    private static final String FORMAT_URL_EVENTS = "https://%s/eventstream/clip/v2";

    private static final String HUE_APPLICATION_KEY = "hue-application-key";
    private static final String APPLICATION_JSON = "application/json";

    private final Logger logger = LoggerFactory.getLogger(Clip2Bridge.class);

    private final HttpClient httpClient;
    private final ClientBuilder clientBuilder;
    private final SseEventSourceFactory eventSourceFactory;
    private final String hostName;
    private final String baseUrl;
    private final String eventUrl;
    private final String registrationUrl;
    private final Clip2BridgeHandler bridgeHandler;
    private final Duration timeout = Duration.of(5, ChronoUnit.SECONDS);
    private final Gson gson = new Gson();

    private String applicationKey;

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
        this.registrationUrl = String.format(FORMAT_URL_REGISTRATION, hostName);
    }

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
     * Execute an HTTP GET or PUT command. It sends the pay-load Resource object (if any) and returns the reply
     * Resources object.
     *
     * @param method HTTP GET or PUT.
     * @param url the URL of the server end point.
     * @param resource the Resource object to send; may be null.
     * @return the Resources object containing the response.
     * @throws ApiException if any error occurs.
     */
    private Resources doHTTPAuthorized(HttpMethod method, String url, @Nullable Resource resource) throws ApiException {
        try {
            return doHTTP(method, url, resource);
        } catch (IllegalAccessException e) {
            // should not happen but re-throw just in case
            throw new ApiException(exceptionMessageFrom(e));
        }
    }

    private Resources doHTTP(HttpMethod method, String url, @Nullable Resource resource)
            throws ApiException, IllegalAccessException {
        Request request = httpClient.newRequest(url).method(method).timeout(timeout.getSeconds(), TimeUnit.SECONDS)
                .header(HUE_APPLICATION_KEY, applicationKey);

        if (resource == null) {
            logger.trace("doHTTP() HTTP {} {}", method, url);
        } else {
            String json = gson.toJson(resource);
            request.content(new StringContentProvider(json), APPLICATION_JSON);
            logger.trace("doHTTP() HTTP {} {} request:{}", method, url, json);
        }

        ContentResponse contentResponse;
        try {
            contentResponse = request.send();
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            throw new ApiException(exceptionMessageFrom(e));
        }
        int httpStatus = contentResponse.getStatus();
        String json = contentResponse.getContentAsString().trim();
        logger.trace("doHTTP() HTTP status:{}, content:{}", httpStatus, json);

        switch (httpStatus) {
            case HttpStatus.OK_200:
                break;
            case HttpStatus.UNAUTHORIZED_401:
            case HttpStatus.FORBIDDEN_403:
                throw new IllegalAccessException("HTTP request no authorized");
            default:
                throw new ApiException(
                        String.format("HTTP error:%d, reason:%s", httpStatus, contentResponse.getReason()));
        }

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

    /**
     * Build an exception message around another exception.
     *
     * @param e the exception.
     * @return the message.
     */
    public static String exceptionMessageFrom(Throwable e) {
        return String.format("error:%s, message:%s", e.getClass().getSimpleName(), e.getMessage()).toLowerCase();
    }

    /**
     * ClientRequestFilter filter to add the application key header to SSE requests.
     */
    @Override
    public void filter(@Nullable ClientRequestContext requestContext) {
        if (requestContext != null) {
            requestContext.getHeaders().add(HUE_APPLICATION_KEY, applicationKey);
        }
    }

    /**
     * Build a full path to a server end point, based on a Reference class instance. If the reference contains only a
     * resource type, the method returns the end point url to get all resources of the given resource type. Whereas if
     * it also contains an id, the method returns the end point url to get the specific single resource with that type
     * and id.
     *
     * @param reference a Reference class instance.
     * @return the full path.
     */
    private String getFullPath(ResourceReference reference) {
        String url = baseUrl + reference.getType().name().toLowerCase();
        String id = reference.getId();
        return id == null || id.isEmpty() ? url : url + "/" + id;
    }

    /**
     * HTTP GET a Resources object, for a given resource Reference, from the server. The reference is a class comprising
     * a resource type and an id. If the id is a specific resource id then only one resource is returned, whereas if it
     * is null then all resources of the given resource type are returned.
     *
     * @param reference a Reference class instance.
     * @return the resources object.
     * @throws ApiException if something fails.
     */
    public Resources getResources(ResourceReference reference) throws ApiException {
        return doHTTPAuthorized(HttpMethod.GET, getFullPath(reference), null);
    }

    /**
     * Log when an SSE session has completed.
     *
     */
    private void onSseComplete() {
        bridgeHandler.onSseComplete();
    }

    /**
     * Log SSE errors.
     *
     * @param e the exception that caused the error.
     */
    private void onSseError(Throwable e) {
        bridgeHandler.onSseError(e);
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
     * HTTP PUT a Resource object to the server.
     *
     * @param resource the resource to put.
     * @throws ApiException if something fails.
     */
    public void putResource(Resource resource) throws ApiException {
        ResourceReference reference = new ResourceReference().setId(resource.getId()).setType(resource.getType());
        doHTTPAuthorized(HttpMethod.PUT, getFullPath(reference), resource);
    }

    /**
     * HostnameVerifier to validate the host name for SSE requests.
     */
    @Override
    public boolean verify(@Nullable String hostName, @Nullable SSLSession sslSession) {
        return this.hostName.equals(hostName);
    }

    /**
     * Try to register the application key with the hub. Use the given application key if one is provided; otherwise the
     * hub will create a new one.
     *
     * @param oldApplicationKey existing application key if any i.e. may be empty.
     * @return the existing or a newly created application key.
     * @throws ApiException if there was a communications error.
     * @throws IllegalAccessException if the registration failed.
     */
    public String registerApplicationKey(@Nullable String oldApplicationKey)
            throws ApiException, IllegalAccessException {
        String json = gson.toJson(
                (oldApplicationKey == null || oldApplicationKey.isEmpty()) ? new CreateUserRequest(APPLICATION_ID)
                        : new CreateUserRequest(oldApplicationKey, APPLICATION_ID));

        Request httpRequest = httpClient.newRequest(registrationUrl).method(HttpMethod.POST)
                .timeout(timeout.getSeconds(), TimeUnit.SECONDS)
                .content(new StringContentProvider(json), APPLICATION_JSON);

        ContentResponse contentResponse;
        try {
            logger.trace("registerApplicationKey() POST {}, request:{}", registrationUrl, json);
            contentResponse = httpRequest.send();
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            throw new ApiException(exceptionMessageFrom(e));
        }

        int httpStatus = contentResponse.getStatus();
        json = contentResponse.getContentAsString().trim();
        logger.trace("registerApplicationKey() HTTP status:{}, content:{}", httpStatus, json);

        if (httpStatus != HttpStatus.OK_200) {
            throw new ApiException(String.format("HTTP error:%d, reason:%s", httpStatus, contentResponse.getReason()));
        }

        try {
            List<SuccessResponse> entries = gson.fromJson(json, SuccessResponse.GSON_TYPE);
            if (entries != null && !entries.isEmpty()) {
                SuccessResponse response = entries.get(0);
                Map<String, Object> responseSuccess = response.success;
                if (responseSuccess != null) {
                    String newApplicationKey = (String) responseSuccess.get("username");
                    if (newApplicationKey != null) {
                        applicationKey = newApplicationKey;
                        return newApplicationKey;
                    }
                }
            }
        } catch (JsonParseException e) {
            // fall through
        }
        throw new IllegalAccessException("Application key registration failed");
    }

    /**
     * Attempt connection to server to test authentication.
     *
     * @throws IllegalAccessException if HTTP 401 or 403 error occurs.
     * @throws ApiException if any other error occurs.
     *
     */
    public void checkConnection() throws IllegalAccessException, ApiException {
        doHTTP(HttpMethod.GET, getFullPath(new ResourceReference().setType(ResourceType.BRIDGE)), null);
    }
}
