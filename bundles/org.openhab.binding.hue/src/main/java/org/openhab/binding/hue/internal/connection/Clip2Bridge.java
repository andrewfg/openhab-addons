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
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.ws.rs.core.MediaType;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MetaData.Response;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise.Completable;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory.Client;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * This class handles HTTP and SSE connections to/from a Hue Bridge running CLIP 2.
 *
 * It uses the following connection mechanisms..
 *
 * <li>The primary communication uses HTTP 2.0 streams over a single shared permanent HTTP 2.0 session.</li>
 * <li>The 'registerApplicationKey()' method is a legacy HTTP 1.1 call so it uses the OH common HTTP 1.1 client.</li>
 * <li>The 'testSupportsClip2()' method is static so it uses a one time locally instantiated HTTP 1.1 client.</li>
 *
 * @author Andrew Fiddian-Green - Initial Contribution
 */
@NonNullByDefault
public class Clip2Bridge implements Closeable {

    /**
     * Interface for processing adapter errors. It handles fatal errors by implementing the fatalError() method.
     *
     * @author Andrew Fiddian-Green - Initial Contribution
     */
    private static interface AdapterErrorHandler {

        /**
         * Potential fatal HTTP 2.0 session/stream errors.
         *
         * @author Andrew Fiddian-Green - Initial Contribution
         */
        public static enum Error {
            CLOSED,
            ERROR,
            FAILURE,
            TIMEOUT,
            RESET,
            IDLE,
            GOAWAY,
            UNAUTHORISED;
        }

        public void fatalError(Error error);
    }

    /**
     * Base (abstract) adapter for HTTP 2.0 stream events.
     *
     * It implements a CompletableFuture by means of which the caller can wait for the response data to come in. And
     * which, in the case of fatal errors, gets completed exceptionally.
     *
     * It handles the following fatal error events by notifying the owner..
     *
     * <li>onHeaders() HTTP unauthorised codes</li>
     *
     * @author Andrew Fiddian-Green - Initial Contribution
     */
    private static abstract class BaseAdapter extends Stream.Listener.Adapter implements AdapterErrorHandler {

        protected final Clip2Bridge owner;
        protected final List<String> strings = new ArrayList<>();
        protected final CompletableFuture<String> completable = new CompletableFuture<>();

        protected BaseAdapter(Clip2Bridge owner) {
            this.owner = owner;
        }

        @Override
        public void fatalError(Error error) {
            Exception e;
            if (Error.UNAUTHORISED.equals(error)) {
                e = new IllegalAccessException("HTTP 2.0 request not authorized");
            } else {
                e = new ApiException("HTTP 2.0 stream " + error.toString().toLowerCase());
            }
            completable.completeExceptionally(e);
            owner.fatalError(this, error);
        }

        /**
         * Check the reply headers to see whether the request was authorised.
         */
        @Override
        public void onHeaders(@Nullable Stream stream, @Nullable HeadersFrame frame) {
            Objects.requireNonNull(frame);
            MetaData metaData = frame.getMetaData();
            if (metaData.isResponse()) {
                int httpStatus = ((Response) metaData).getStatus();
                switch (httpStatus) {
                    case HttpStatus.UNAUTHORIZED_401:
                    case HttpStatus.FORBIDDEN_403:
                        fatalError(Error.UNAUTHORISED);
                    default:
                }
            }
        }
    }

    /**
     * Adapter for regular HTTP GET/PUT request stream events.
     *
     * It assembles the incoming text data into an HTTP 'content' entity. And when the last data frame arrives, it
     * returns the full content by completing the CompletableFuture with that data.
     *
     * In addition to those handled by the parent, it handles the following fatal error events by notifying the owner..
     *
     * <li>onIdleTimeout()</li>
     * <li>onTimeout()</li>
     *
     * @author Andrew Fiddian-Green - Initial Contribution
     */
    private static class ContentAdapter extends BaseAdapter {

        protected ContentAdapter(Clip2Bridge owner) {
            super(owner);
        }

        @Override
        public void onData(@Nullable Stream stream, @Nullable DataFrame frame, @Nullable Callback callback) {
            Objects.requireNonNull(frame);
            Objects.requireNonNull(callback);
            strings.add(StandardCharsets.UTF_8.decode(frame.getData()).toString());
            if (frame.isEndStream() && !completable.isDone()) {
                completable.complete(String.join("", strings));
            }
            callback.succeeded();
        }

        @Override
        public boolean onIdleTimeout(@Nullable Stream stream, @Nullable Throwable x) {
            fatalError(Error.IDLE);
            return true;
        }

        @Override
        public void onTimeout(@Nullable Stream stream, @Nullable Throwable x) {
            fatalError(Error.TIMEOUT);
        }
    }

    /**
     * Adapter for SSE stream events.
     *
     * It receives the incoming text lines. Receipt of the first data line causes the CompletableFuture to complete. It
     * then parses subsequent data according to the SSE specification. If the line starts with a 'data:' message, it
     * adds the data to the list of strings. And if the line is empty (i.e. the last line of an event), it passes the
     * full set of strings to the owner via a call-back method.
     *
     * The stream must be permanently connected, so it ignores onIdleTimeout() events.
     *
     * The parent class handles most fatal errors, but since the event stream is supposed to be permanently connected,
     * the following events are also considered as fatal..
     *
     * <li>OnClosed()</li>
     * <li>onReset()</li>
     *
     * @author Andrew Fiddian-Green - Initial Contribution
     */
    private static class EventAdapter extends BaseAdapter {

        protected EventAdapter(Clip2Bridge owner) {
            super(owner);
        }

        @Override
        public void onClosed(@Nullable Stream stream) {
            fatalError(Error.CLOSED);
        }

        @Override
        public void onData(@Nullable Stream stream, @Nullable DataFrame frame, @Nullable Callback callback) {
            Objects.requireNonNull(frame);
            Objects.requireNonNull(callback);
            if (!completable.isDone()) {
                completable.complete(Boolean.toString(true));
            }
            String lines = StandardCharsets.UTF_8.decode(frame.getData()).toString();
            for (String line : lines.split("\n")) {
                if (line.startsWith("data: ")) {
                    strings.add(line.substring(6));
                } else if (!strings.isEmpty()) {
                    owner.onEventData(String.join("", strings).trim());
                    strings.clear();
                }
            }
            callback.succeeded();
        }

        @Override
        public boolean onIdleTimeout(@Nullable Stream stream, @Nullable Throwable x) {
            return false;
        }

        @Override
        public void onReset(@Nullable Stream stream, @Nullable ResetFrame frame) {
            fatalError(Error.RESET);
        }
    }

    /**
     * Adapter for HTTP 2.0 session status events.
     *
     * The session must be permanently connected, so it ignores onIdleTimeout() events.
     * It also handles the following fatal events by notifying the owner.
     *
     * <li>onClose()</li>
     * <li>onFailure()</li>
     * <li>onGoAway()</li>
     * <li>onReset()</li>
     *
     * @author Andrew Fiddian-Green - Initial Contribution
     */
    private static class SessionAdapter extends Session.Listener.Adapter implements AdapterErrorHandler {

        private final Clip2Bridge owner;

        protected SessionAdapter(Clip2Bridge owner) {
            this.owner = owner;
        }

        @Override
        public void fatalError(Error error) {
            owner.fatalError(this, error);
        }

        @Override
        public void onClose(@Nullable Session session, @Nullable GoAwayFrame frame) {
            fatalError(Error.CLOSED);
        }

        @Override
        public void onFailure(@Nullable Session session, @Nullable Throwable failure) {
            fatalError(Error.FAILURE);
        }

        @Override
        public void onGoAway(@Nullable Session session, @Nullable GoAwayFrame frame) {
            fatalError(Error.GOAWAY);
        }

        @Override
        public boolean onIdleTimeout(@Nullable Session session) {
            return false;
        }

        @Override
        public void onReset(@Nullable Session session, @Nullable ResetFrame frame) {
            fatalError(Error.RESET);
        }
    }

    private static final String APPLICATION_ID = "org-openhab-binding-hue-clip2";
    private static final String FORMAT_URL_CONFIG = "http://%s/api/0/config";
    private static final String FORMAT_URL_RESOURCE = "https://%s/clip/v2/resource/";
    private static final String FORMAT_URL_REGISTER = "http://%s/api";
    private static final String FORMAT_URL_EVENTS = "https://%s/eventstream/clip/v2";
    private static final String HEADER_APPLICATION_KEY = "hue-application-key";
    private static final String MEDIA_EVENT_STREAM = "text/event-stream";

    private static final int CLIP2_MINIMUM_VERSION = 1948086000;
    private static final int TIMEOUT_SECONDS = 10;
    private static final int CHECK_ALIVE_SECONDS = 300;

    private static final ResourceReference BRIDGE = new ResourceReference().setType(ResourceType.BRIDGE);

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
        //
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder().uri(new URI(String.format(FORMAT_URL_CONFIG, hostName)))
                    .header(HttpHeader.ACCEPT.toString(), MediaType.APPLICATION_JSON)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();
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
    private final HTTP2Client http2Client;
    private final Client sslContextFactoryClient;

    private final String hostName;
    private final String baseUrl;
    private final String eventUrl;
    private final String registrationUrl;
    private final String applicationKey;

    private final Clip2BridgeHandler bridgeHandler;
    private final Gson jsonParser = new Gson();

    private boolean closing = false;
    private boolean online = false;
    private int lastStreamId = 1;

    private @Nullable Session http2Session;
    private @Nullable Stream eventStream;
    private @Nullable ScheduledFuture<?> checkAliveTask;

    /**
     * Constructor.
     *
     * @param httpClient the OH common HTTP client.
     * @param bridgeHandler the bridge handler.
     * @param hostName the host name (ip address) of the Hue bridge
     * @param applicationKey the application key.
     * @throws IllegalStateException if something could not be initialised.
     */
    public Clip2Bridge(HttpClient httpClient, Clip2BridgeHandler bridgeHandler, String hostName, String applicationKey)
            throws IllegalStateException {
        //
        this.httpClient = httpClient;
        this.applicationKey = applicationKey;
        this.bridgeHandler = bridgeHandler;
        this.hostName = hostName;
        this.baseUrl = String.format(FORMAT_URL_RESOURCE, hostName);
        this.eventUrl = String.format(FORMAT_URL_EVENTS, hostName);
        this.registrationUrl = String.format(FORMAT_URL_REGISTER, hostName);

        // TODO perhaps in future we should use the OH core sslContextFactory, and OH core HTTP2Clients ??
        sslContextFactoryClient = new SslContextFactory.Client.Client(true); // true = accepts all certificates
        http2Client = new HTTP2Client();
        http2Client.addBean(sslContextFactoryClient);
    }

    /**
     * Build a full path to a server end point, based on a Reference class instance. If the reference contains only
     * a resource type, the method returns the end point url to get all resources of the given resource type. Whereas if
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
     * Send a request to the Hue bridge to check that the session is still alive.
     */
    private void checkAlive() {
        try {
            sendHttpRequest(HttpMethod.GET, buildFullPath(BRIDGE), null);
        } catch (IllegalAccessException | ApiException e) {
            fatalError(this, AdapterErrorHandler.Error.ERROR);
        }
    }

    /**
     * Close the connection.
     */
    @Override
    public void close() {
        close(true);
    }

    /**
     * Close the connection.
     *
     * @param notifyHandler indicates whether to notify the handler.
     */
    private void close(boolean notifyHandler) {
        synchronized (this) {
            closing = true;
            online = false;
            closeCheckAliveTask();
            closeEventStream();
            closeSession();
            closeClients();
            if (notifyHandler) {
                bridgeHandler.onConnectionOffline();
            }
        }
    }

    /**
     * Close the check alive task if necessary.
     */
    private void closeCheckAliveTask() {
        ScheduledFuture<?> task = checkAliveTask;
        if (task != null && !task.isDone() && !task.isCancelled()) {
            task.cancel(true);
        }
        checkAliveTask = null;
    }

    /**
     * Stop the client entities if necessary.
     */
    private void closeClients() {
        try {
            if (http2Client.isRunning()) {
                http2Client.stop();
            }
            if (sslContextFactoryClient.isRunning()) {
                sslContextFactoryClient.stop();
            }
        } catch (Exception e) {
            // closing anyway so ignore errors
        }
    }

    /**
     * Close the HTTP 2.0 SSE event stream if necessary.
     */
    private void closeEventStream() {
        Stream stream = eventStream;
        if (stream != null && !stream.isClosed() && !stream.isReset()) {
            stream.reset(new ResetFrame(stream.getId(), 0), Callback.NOOP);
        }
        eventStream = null;
    }

    /**
     * Close the HTTP 2.0 session if necessary.
     */
    private void closeSession() {
        Session session = http2Session;
        if (session != null && !session.isClosed()) {
            session.close(0, null, Callback.NOOP);
        }
        http2Session = null;
    }

    /**
     * Method that is called back in case of fatal stream or session events.
     *
     * @param cause the entity that called this method.
     * @param error the type of error.
     */
    private void fatalError(Object cause, BaseAdapter.Error error) {
        if (logger.isTraceEnabled()) {
            logger.debug("fatalError() {} {}", cause.getClass().getSimpleName(), error);
        }
        if (isOfflineOrClosing() || (cause instanceof ContentAdapter)) {
            // GET/PUT request errors aren't fatal enough to require calling close()
            return;
        }
        close(true);
    }

    /**
     * HTTP GET a Resources object, for a given resource Reference, from the Hue Bridge. The reference is a class
     * comprising a resource type and an id. If the id is a specific resource id then only the one specific resource
     * is
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
     * Get the next stream id in the sequence (must be an odd number).
     *
     * @return stream id.
     */
    private synchronized int getStreamId() {
        lastStreamId = lastStreamId + 2;
        return lastStreamId;
    }

    /**
     * Check if we are offline or closing.
     *
     * @return true if we are online and not closing.
     */
    private boolean isOfflineOrClosing() {
        return closing || !online;
    }

    /**
     * The event stream calls this method when it has received text data. It parses the text as JSON into a list of
     * Event entries, converts the list of events to a list of resources, and forwards that list to the bridge
     * handler.
     *
     * @param data the incoming (presumed to be JSON) text.
     */
    private void onEventData(String data) {
        logger.trace("onEventData() data..\n{}", data);
        JsonElement jsonElement = JsonParser.parseString(data);
        if (!(jsonElement instanceof JsonArray)) {
            logger.debug("onEventData() data is not a JsonArray {}", data);
            return;
        }
        List<Event> events;
        try {
            events = jsonParser.fromJson(jsonElement, Event.EVENT_LIST_TYPE);
        } catch (JsonParseException e) {
            logger.debug("onEventData() {}", e.getMessage(), e);
            return;
        }
        if (events == null || events.isEmpty()) {
            logger.debug("onEventData() event list is null or empty");
            return;
        }
        List<Resource> resources = new ArrayList<>();
        events.forEach(event -> resources.addAll(event.getData()));
        if (resources.isEmpty()) {
            logger.debug("onEventData() resource list is empty");
            return;
        }
        resources.forEach(resource -> resource.markAsSparse());
        bridgeHandler.onSseResources(resources);
    }

    /**
     * Open the HTTP 2.0 session and the event stream.
     *
     * @throws ApiException if there was a communication error.
     * @throws IllegalAccessException if the application key is not authenticated
     */
    public void open() throws ApiException, IllegalAccessException {
        open(true);
    }

    /**
     * Open the HTTP 2.0 session and the event stream.
     *
     * @param notifyHandler indicates whether to notify the handler.
     * @throws ApiException if there was a communication error.
     * @throws IllegalAccessException if the application key is not authenticated
     */
    private void open(boolean notifyHandler) throws ApiException, IllegalAccessException {
        synchronized (this) {
            closing = false;
            online = false;
            openClients();
            openSession();
            openEventStream();
            openCheckAliveTask();
            online = true;
            if (notifyHandler) {
                bridgeHandler.onConnectionOnline();
            }
        }
    }

    /**
     * Open the check alive task if necessary.
     */
    private void openCheckAliveTask() {
        ScheduledFuture<?> task = checkAliveTask;
        if (task == null || task.isCancelled() || task.isDone()) {
            checkAliveTask = bridgeHandler.getScheduler().scheduleWithFixedDelay(() -> checkAlive(),
                    CHECK_ALIVE_SECONDS, CHECK_ALIVE_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Start the client entities.
     *
     * @throws IllegalStateException if either of the clients failed to start.
     */
    private void openClients() throws IllegalStateException {
        if (!sslContextFactoryClient.isRunning()) {
            try {
                sslContextFactoryClient.start();
            } catch (Exception e) {
                throw new IllegalStateException("SSL contect factory not started");
            }
        }
        if (!http2Client.isRunning()) {
            try {
                http2Client.setConnectTimeout(TIMEOUT_SECONDS * 1000);
                http2Client.setIdleTimeout(-1);
                http2Client.start();
            } catch (Exception e) {
                throw new IllegalStateException("HTTP v2 client not started");
            }
        }
    }

    /**
     * Open an HTTP 2.0 SSE event stream if necessary.
     *
     * @throws ApiException if an error was encountered.
     */
    private void openEventStream() throws ApiException, IllegalAccessException {
        Session session = http2Session;
        if (session == null || session.isClosed()) {
            throw new ApiException("HTTP 2.0 session is null or in an illegal state");
        }

        Stream stream = eventStream;
        if (stream != null && !stream.isClosed() && !stream.isReset()) {
            return;
        }

        closeEventStream();

        HttpFields fields = new HttpFields();
        fields.put(HttpHeader.ACCEPT, MEDIA_EVENT_STREAM);
        fields.put(HEADER_APPLICATION_KEY, applicationKey);

        int streamId = getStreamId();
        String method = HttpMethod.GET.toString();
        MetaData.Request request = new MetaData.Request(method, new HttpURI(eventUrl), HttpVersion.HTTP_2, fields);
        HeadersFrame headers = new HeadersFrame(streamId, request, null, true);
        EventAdapter adapter = new EventAdapter(this);
        Completable<@Nullable Stream> completable = new Completable<>();

        logger.trace("{} {} {}", method, eventUrl, HttpVersion.HTTP_2);

        try {
            session.newStream(headers, completable, adapter);
            eventStream = Objects.requireNonNull(completable.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            eventStream.setIdleTimeout(0);
            adapter.completable.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            throw new ApiException("HTTP 2.0 error", e);
        }
    }

    /**
     * Open the HTTP 2.0 session if necessary.
     *
     * @throws ApiException if it was not possible to create and connect the session.
     */
    private void openSession() throws ApiException {
        Session session = http2Session;

        if (session != null && !session.isClosed()) {
            return;
        }

        closeSession();

        InetSocketAddress address = new InetSocketAddress(hostName, 443);
        SessionAdapter adapter = new SessionAdapter(this);
        Completable<@Nullable Session> completable = new Completable<>();

        try {
            http2Client.connect(sslContextFactoryClient, address, adapter, completable);
            http2Session = Objects.requireNonNull(completable.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            lastStreamId = 1;
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new ApiException("HTTP v2 session not open");
        }
    }

    /**
     * Use HTTP 2.0 to PUT a Resource object to the server.
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
     * hub will create a new one. Note: this requires an HTTP 1.x client call.
     *
     * @param oldApplicationKey existing application key if any i.e. may be empty.
     * @return the existing or a newly created application key.
     * @throws ApiException if there was a communications error.
     * @throws IllegalAccessException if the registration failed.
     */
    public String registerApplicationKey(@Nullable String oldApplicationKey)
            throws ApiException, IllegalAccessException {
        //
        String json = jsonParser.toJson(
                (oldApplicationKey == null || oldApplicationKey.isEmpty()) ? new CreateUserRequest(APPLICATION_ID)
                        : new CreateUserRequest(oldApplicationKey, APPLICATION_ID));

        Request httpRequest = httpClient.newRequest(registrationUrl).method(HttpMethod.POST)
                .timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .content(new StringContentProvider(json), MediaType.APPLICATION_JSON);

        ContentResponse contentResponse;
        try {
            logger.trace("registerApplicationKey() POST {}, request:{}", registrationUrl, json);
            contentResponse = httpRequest.send();
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            throw new ApiException("HTTP processing error", e);
        }

        int httpStatus = contentResponse.getStatus();
        json = contentResponse.getContentAsString().trim();
        logger.trace("registerApplicationKey() HTTP status:{}, content:{}", httpStatus, json);

        if (httpStatus != HttpStatus.OK_200) {
            throw new ApiException("HTTP bad response");
        }

        try {
            List<SuccessResponse> entries = jsonParser.fromJson(json, SuccessResponse.GSON_TYPE);
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
     * Send an HTTP 2.0 request to the Hue Bridge and process its response.
     *
     * @param methodParam HTTP method (GET / PUT).
     * @param url the end-point to connect to.
     * @param resource a Resource (command) to send to the bridge.
     * @return a Resource object containing either a list of Resources or a list of Errors.
     * @throws ApiException if the communication failed, or an unexpected result occurred.
     * @throws IllegalAccessException if the request was refused as not authorised or forbidden.
     */
    private Resources sendHttpRequest(HttpMethod methodParam, String url, @Nullable Resource resource)
            throws ApiException, IllegalAccessException {
        //
        Session session = http2Session;
        if (session == null || session.isClosed() || lastStreamId > Short.MAX_VALUE) {
            throw new ApiException("HTTP 2.0 session is null or in an illegal state");
        }

        HttpFields fields = new HttpFields();
        fields.put(HEADER_APPLICATION_KEY, applicationKey);
        fields.put(HttpHeader.ACCEPT, MediaType.APPLICATION_JSON);

        int streamId = getStreamId();
        String method = methodParam.toString();
        HttpURI uri = new HttpURI(url);
        MetaData.Request request;
        HeadersFrame headers;
        DataFrame content;
        ContentAdapter adapter = new ContentAdapter(this);
        Completable<@Nullable Stream> completable = new Completable<>();

        if (resource == null) {
            request = new MetaData.Request(method, uri, HttpVersion.HTTP_2, fields);
            headers = new HeadersFrame(streamId, request, null, true);
            content = null;
            logger.trace("{} {} {}", method, url, HttpVersion.HTTP_2);
        } else {
            String json = jsonParser.toJson(resource);
            byte[] data = json.getBytes();
            int length = data.length;
            fields.put(HttpHeader.CONTENT_TYPE, MediaType.APPLICATION_JSON);
            fields.put(HttpHeader.CONTENT_LENGTH, String.valueOf(length));

            request = new MetaData.Request(method, uri, HttpVersion.HTTP_2, fields, length);
            headers = new HeadersFrame(streamId, request, null, false);
            content = new DataFrame(streamId, ByteBuffer.wrap(data), true);
            logger.trace("{} {} {} request..\n{}", method, url, HttpVersion.HTTP_2, json);
        }

        String json = "";
        Stream newStream = null;
        try {
            session.newStream(headers, completable, adapter);
            newStream = Objects.requireNonNull(completable.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            if (content != null) {
                newStream.data(content, Callback.NOOP);
            }
            json = adapter.completable.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).trim();
            logger.trace("HTTP/2.0 200 OK response..\n{}", json);
        } catch (ExecutionException e) {
            Throwable e2 = e.getCause();
            if (e2 instanceof IllegalAccessException) {
                throw (IllegalAccessException) e2;
            }
            throw new ApiException("HTTP 2.0 error", e);
        } catch (InterruptedException | TimeoutException e) {
            throw new ApiException("HTTP 2.0 error", e);
        }

        try {
            Resources resources = jsonParser.fromJson(json, Resources.class);
            if (resources == null) {
                throw new ApiException("Missing Resources object");
            }
            if (logger.isDebugEnabled()) {
                resources.getErrors().forEach(error -> logger.debug("Resources error:{}", error));
            }
            return resources;
        } catch (JsonParseException e) {
            throw new ApiException("Parsing error", e);
        }
    }

    /**
     * Send an HTTP 2.0 request to the Hue Bridge and process its response. It wraps the sendHttp2Request() method in a
     * try/catch block, and transposes any IllegalAccessException into an ApiException. Such transposition should never
     * be required in reality since by the time this method is called, the connection will surely already have been
     * authorised.
     *
     * @param method HTTP method (GET / PUT).
     * @param url the end-point to connect to.
     * @param resource a Resource (command) to send to the bridge.
     * @return a Resource object containing either a list of Resources or a list of Errors.
     * @throws ApiException if the communication failed, or an unexpected result occured.
     */
    private Resources sendHttpRequestAuthorized(HttpMethod method, String url, @Nullable Resource resource)
            throws ApiException {
        try {
            return sendHttpRequest(method, url, resource);
        } catch (IllegalAccessException e) {
            throw new ApiException("Unexpected access error", e);
        }
    }

    /**
     * Test the Hue Bridge connection state by attempting to connect and trying to execute a basic command that requires
     * authentication.
     *
     * @throws ApiException if it was not possible to connect.
     * @throws IllegalAccessException if it was possible to connect but not to authenticate.
     */
    public void testConnectionState() throws IllegalAccessException, ApiException {
        try {
            open(false);
            sendHttpRequest(HttpMethod.GET, buildFullPath(BRIDGE), null);
        } catch (IllegalAccessException | ApiException e) {
            close(false);
            throw e;
        }
    }
}
