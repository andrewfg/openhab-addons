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
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
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
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.hue.internal.dto.CreateUserRequest;
import org.openhab.binding.hue.internal.dto.SuccessResponse;
import org.openhab.binding.hue.internal.dto.clip2.BridgeConfig;
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

    private static final String APPLICATION_ID = "org-openhab-binding-hue-clip2";

    private static final String FORMAT_URL_CONFIG = "http://%s/api/0/config";
    private static final String FORMAT_URL_RESOURCE = "https://%s/clip/v2/resource/";
    private static final String FORMAT_URL_REGISTER = "http://%s/api";
    private static final String FORMAT_URL_EVENTS = "https://%s/eventstream/clip/v2";

    private static final String HEADER_APPLICATION_KEY = "hue-application-key";
    private static final String HEADER_ACCEPT = HttpHeader.ACCEPT.toString();

    private static final String CONTENT_APPLICATION_JSON = "application/json";

    private static final int CLIP2_MINIMUM_VERSION = 1948086000;

    private static final ResourceReference BRIDGE = new ResourceReference().setType(ResourceType.BRIDGE);

    private static final Duration TIMEOUT = Duration.of(5, ChronoUnit.SECONDS);

    /**
     * Static method to attempt to connect to a Hue Bridge, get its software version, and check if it is high enough to
     * support the CLIP 2 API.
     *
     * @param hostName the bridge IP address.
     * @return returns without any exception if the bridge is online and it does support CLIP 2.
     * @throws ApiException if was not possible to connect or another error was encountered.
     * @throws IllegalArgumentException if the hostName is bad.
     * @throws IllegalStateException if it was possible to connect but CLIP 2 is not supported.
     */
    public static void testSupportsClip2(String hostName)
            throws ApiException, IllegalStateException, IllegalArgumentException {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder().uri(new URI(String.format(FORMAT_URL_CONFIG, hostName)))
                    .header(HEADER_ACCEPT, CONTENT_APPLICATION_JSON).timeout(TIMEOUT).build();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Bad host name");
        }

        HttpResponse<String> response;
        try {
            response = java.net.http.HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new ApiException("Communication error", e);
        }

        if (response.statusCode() == 200) {
            BridgeConfig config = new Gson().fromJson(response.body(), BridgeConfig.class);
            if (config != null) {
                String swVersion = config.swversion;
                if (swVersion != null) {
                    if (Integer.parseInt(swVersion) < CLIP2_MINIMUM_VERSION) {
                        throw new IllegalStateException("Hue Bridge does not support CLIP 2");
                    }
                    return;
                }
            }
        }
        throw new ApiException("Unexpected response");
    }

    private final Logger logger = LoggerFactory.getLogger(Clip2Bridge.class);

    private final HttpClient httpClient;
    private final ClientBuilder clientBuilder;
    private final SseEventSourceFactory eventSourceFactory;
    private final String hostName;
    private final String baseUrl;
    private final String eventUrl;
    private final String registrationUrl;

    private final Clip2BridgeHandler bridgeHandler;
    private final Gson gson = new Gson();
    private String applicationKey;

    private boolean closing;
    private boolean online;

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
        this.registrationUrl = String.format(FORMAT_URL_REGISTER, hostName);
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
    private String buildFullPath(ResourceReference reference) {
        String url = baseUrl + reference.getType().name().toLowerCase();
        String id = reference.getId();
        return id == null || id.isEmpty() ? url : url + "/" + id;
    }

    /**
     * Close the connection.
     */
    @Override
    public void close() {
        closing = true;
        online = false;
        disposeAssets();
    }

    /**
     * Close the assets.
     */
    private void disposeAssets() {
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
        // TODO ?? flush client HTTP 1.1 connections e.g. send a request with connection close header
    }

    /**
     * ClientRequestFilter method implementation that adds the application key header to the HTTP request when opening
     * SSE connections.
     */
    @Override
    public void filter(@Nullable ClientRequestContext requestContext) {
        if (requestContext != null) {
            requestContext.getHeaders().add(HEADER_APPLICATION_KEY, applicationKey);
        }
    }

    /**
     * HTTP GET a Resources object, for a given resource Reference, from the Hue Bridge. The reference is a class
     * comprising a resource type and an id. If the id is a specific resource id then only the one specific resource is
     * returned, whereas if it is null then all resources of the given resource type are returned.
     *
     * @param reference a Reference class instance.
     * @return the resources object.
     * @throws ApiException if something fails.
     */
    public Resources getResources(ResourceReference reference) throws ApiException {
        return sendHttpRequestAuthorized(HttpMethod.GET, buildFullPath(reference), null);
    }

    /**
     * Initialize the assets needed by the class.
     */
    private void initializeAssets() {
        Client sseClient = clientBuilder //
                .sslContext(httpClient.getSslContextFactory().getSslContext()) //
                .register(this) //
                .hostnameVerifier(this) //
                .readTimeout(0, TimeUnit.SECONDS) //
                .build();
        SseEventSource eventSource = eventSourceFactory //
                .newBuilder(sseClient.target(eventUrl)) //
                .build();
        eventSource.register(this::notifySseEvent, this::notifySseError);
        eventSource.open();
        this.sseClient = sseClient;
        this.eventSource = eventSource;
    }

    /**
     * Getter for the 'online' field.
     *
     * @return the online state.
     */
    public boolean isOnline() {
        return online;
    }

    /**
     * Respond to SSE errors.
     *
     * @param e the exception that caused the error.
     */
    private void notifySseError(Throwable e) {
        if (online && !closing) {
            bridgeHandler.notifySseError(e);
            SseEventSource eventSource = this.eventSource;
            if (eventSource != null && !eventSource.isOpen()) {
                eventSource.open();
            }
        }
    }

    /**
     * SSE calls this method when an event comes in.
     * It forwards the event data as a list of Resources to the bridge handler.
     *
     * @param inboundSseEvent the incoming SSE event.
     */
    private void notifySseEvent(InboundSseEvent inboundSseEvent) {
        if (!online || closing) {
            return;
        }
        if (inboundSseEvent.isEmpty()) {
            return;
        }
        String json = inboundSseEvent.readData();
        if (json == null) {
            return;
        }
        logger.trace("notifySseEvent() data:{}", json);
        List<Event> eventList;
        try {
            eventList = gson.fromJson(json, Event.EVENT_LIST_TYPE);
        } catch (JsonParseException e) {
            logger.debug("notifySseEvent() {}", e.getMessage(), e);
            return;
        }
        if (eventList == null) {
            logger.debug("notifySseEvent() event list is null");
            return;
        }
        List<Resource> resourceList = new ArrayList<>();
        for (Event event : eventList) {
            resourceList.addAll(event.getData());
        }
        if (resourceList.isEmpty()) {
            logger.debug("notifySseEvent() resource list is empty");
            return;
        }
        bridgeHandler.notifySseEvent(resourceList);
    }

    /**
     * HTTP PUT a Resource object to the server.
     *
     * @param resource the resource to put.
     * @throws ApiException if something fails.
     */
    public void putResource(Resource resource) throws ApiException {
        ResourceReference reference = new ResourceReference().setId(resource.getId()).setType(resource.getType());
        sendHttpRequestAuthorized(HttpMethod.PUT, buildFullPath(reference), resource);
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
                .timeout(TIMEOUT.getSeconds(), TimeUnit.SECONDS)
                .content(new StringContentProvider(json), CONTENT_APPLICATION_JSON);

        ContentResponse contentResponse;
        try {
            logger.trace("registerApplicationKey() POST {}, request:{}", registrationUrl, json);
            contentResponse = httpRequest.send();
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            throw new ApiException("HTTP procesiing error", e);
        }

        int httpStatus = contentResponse.getStatus();
        json = contentResponse.getContentAsString().trim();
        logger.trace("registerApplicationKey() HTTP status:{}, content:{}", httpStatus, json);

        if (httpStatus != HttpStatus.OK_200) {
            throw new ApiException("HTTP bad response");
        }

        try {
            List<SuccessResponse> entries = gson.fromJson(json, SuccessResponse.GSON_TYPE);
            if (entries != null && !entries.isEmpty()) {
                SuccessResponse response = entries.get(0);
                Map<String, Object> responseSuccess = response.success;
                if (responseSuccess != null) {
                    String newApplicationKey = (String) responseSuccess.get("username");
                    if (newApplicationKey != null) {
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
     * Send an HTTP request to the Hue Bridge and process its response.
     *
     * @param method HTTP method (GET / PUT).
     * @param url the end-point to connect to.
     * @param resource a Resource (command) to send to the bridge.
     * @return a Resource object containing either a list of Resources or a list of Errors.
     * @throws ApiException if the communication failed, or an unexpected result occured.
     * @throws IllegalAccessException if the request was refused as not authorized / forbidden.
     */
    private Resources sendHttpRequest(HttpMethod method, String url, @Nullable Resource resource)
            throws ApiException, IllegalAccessException {
        //
        Request request = httpClient //
                .newRequest(url) //
                .method(method) //
                .timeout(TIMEOUT.getSeconds(), TimeUnit.SECONDS) //
                .header(HEADER_APPLICATION_KEY, applicationKey).accept(CONTENT_APPLICATION_JSON);

        if (resource == null) {
            logger.trace("{} {} {}", method, url, request.getVersion());
        } else {
            String json = gson.toJson(resource);
            request.content(new StringContentProvider(json), CONTENT_APPLICATION_JSON);
            logger.trace("{} {} {}, body:{}", method, url, request.getVersion(), json);
        }

        ContentResponse response;
        try {
            response = request.send();
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            throw new ApiException("HTTP processing error", e);
        }

        String json = response.getContentAsString().trim();
        logger.trace("{} {} {}, body:{}", response.getVersion(), response.getStatus(), response.getReason(), json);

        switch (response.getStatus()) {
            case HttpStatus.UNAUTHORIZED_401:
            case HttpStatus.FORBIDDEN_403:
                throw new IllegalAccessException("HTTP request not authorized");
            case HttpStatus.OK_200:
            default:
        }

        try {
            Resources resources = gson.fromJson(json, Resources.class);
            if (resources == null) {
                throw new ApiException("Missing Resources object");
            }
            if (logger.isDebugEnabled()) {
                for (String errorResponse : resources.getErrors()) {
                    logger.debug("doHTTP() error response:{}", errorResponse);
                }
            }
            return resources;
        } catch (JsonParseException e) {
            throw new ApiException("Parsing error", e);
        }
    }

    /**
     * Send an HTTP request to the Hue Bridge and process its response. It wraps the sendHttpRequest() method in a
     * try/catch block, and transposes any IllegalAccessException into an ApiException. Such transposition should never
     * be required in reality since by the time this method is called, the connection will surely already have been
     * authorised. It also sets the 'online' field according to whether the call succeeded or failed.
     *
     * @param method HTTP method (GET / PUT).
     * @param url the end-point to connect to.
     * @param resource a Resource (command) to send to the bridge.
     * @return a Resource object containing either a list of Resources or a list of Errors.
     * @throws ApiException if the communication failed, or an unexpected result occured.
     */
    private Resources sendHttpRequestAuthorized(HttpMethod method, String url, @Nullable Resource resource)
            throws ApiException {
        ApiException exception;
        try {
            Resources result = sendHttpRequest(method, url, resource);
            setOnline(true);
            return result;
        } catch (ApiException e) {
            exception = e;
        } catch (IllegalAccessException e) {
            exception = new ApiException("Unexpected access error", e);
        }
        setOnline(false);
        throw exception;
    }

    /**
     * Sets the 'online' field. If the state is changing, it disposes the class assets. And if it is changing to online,
     * it (re-)creates the class assets.
     *
     * @param online the new online state.
     */
    private void setOnline(boolean online) {
        if (online != this.online) {
            disposeAssets();
            if (online) {
                initializeAssets();
            }
        }
        this.online = online;
    }

    /**
     * Test the Hue Bridge connection state by attempting to connect and trying to execute a basic command that requires
     * authentication.
     *
     * @throws ApiException if it was not possible to connect.
     * @throws IllegalAccessException if it was possible to connect but not to authenticate.
     */
    public void testConnectionState() throws IllegalAccessException, ApiException {
        sendHttpRequest(HttpMethod.GET, buildFullPath(BRIDGE), null);
    }

    /**
     * HostnameVerifier method implementation that validates the host name when opening SSE connections.
     *
     * @param hostName the host name to be verified.
     * @param sslSession not used.
     * @return true if the host name matches our own.
     */
    @Override
    public boolean verify(@Nullable String hostName, @Nullable SSLSession sslSession) {
        return this.hostName.equals(hostName);
    }
}
