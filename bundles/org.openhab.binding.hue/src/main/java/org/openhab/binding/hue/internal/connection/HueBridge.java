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

import static org.openhab.binding.hue.internal.HueBindingConstants.*;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLHandshakeException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.openhab.binding.hue.internal.dto.ApiVersion;
import org.openhab.binding.hue.internal.dto.ConfigUpdate;
import org.openhab.binding.hue.internal.dto.FullConfig;
import org.openhab.binding.hue.internal.dto.FullGroup;
import org.openhab.binding.hue.internal.dto.FullLight;
import org.openhab.binding.hue.internal.dto.Group;
import org.openhab.binding.hue.internal.dto.HueObject;
import org.openhab.binding.hue.internal.dto.Scene;
import org.openhab.binding.hue.internal.dto.tag.ILight;
import org.openhab.binding.hue.internal.dto.tag.ISensor;
import org.openhab.binding.hue.internal.dto.tag.IUpdate;
import org.openhab.binding.hue.internal.exceptions.ApiException;
import org.openhab.binding.hue.internal.exceptions.UnauthorizedException;
import org.openhab.core.i18n.CommunicationException;
import org.openhab.core.i18n.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * Representation of a connection with a Hue Bridge.
 *
 * @author Q42 - Initial contribution
 * @author Andre Fuechsel - search for lights with given serial number added
 * @author Denis Dudnik - moved Jue library source code inside the smarthome Hue binding, minor code cleanup
 * @author Samuel Leisering - added cached config and API-Version
 * @author Laurent Garnier - change the return type of getGroups
 * @author Andrew Fiddian-Green - refactored HueBrige into HueBridge base class and HueBridgeV1 implementation
 */
@NonNullByDefault
public abstract class HueBridge {

    private final Logger logger = LoggerFactory.getLogger(HueBridge.class);

    private static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";

    protected final HttpClient httpClient;
    protected final String ip;
    protected final String baseUrl;
    protected @Nullable String username;

    private long timeout = TimeUnit.MILLISECONDS.convert(5, TimeUnit.SECONDS);

    protected final Gson gson = new GsonBuilder().setDateFormat(DATE_FORMAT).create();

    private final LinkedList<AsyncPutParameters> commandsQueue = new LinkedList<>();
    private @Nullable Future<?> job;
    private final ScheduledExecutorService scheduler;

    /**
     * ------------------------------------------------------------------------
     * Constructors
     * ------------------------------------------------------------------------
     */

    /**
     * Connect with a bridge as a new user.
     *
     * @param httpClient instance of the Jetty shared client
     * @param ip ip address of bridge
     * @param port port of bridge
     * @param protocol protocol to connect to the bridge
     * @param scheduler the ExecutorService to schedule commands
     */
    public HueBridge(HttpClient httpClient, String ip, int port, String protocol, ScheduledExecutorService scheduler) {
        this.httpClient = httpClient;
        this.ip = ip;
        String baseUrl;
        try {
            URI uri = new URI(protocol, null, ip, port, getRootPath(), null, null);
            baseUrl = uri.toString();
        } catch (URISyntaxException e) {
            logger.error("exception during constructing URI protocol={}, host={}, port={}", protocol, ip, port, e);
            baseUrl = protocol + "://" + ip + ":" + port + getRootPath();
        }
        this.baseUrl = baseUrl;
        this.scheduler = scheduler;
    }

    /**
     * Connect with a bridge as an existing user.
     *
     * The username is verified by requesting the list of lights.
     * Use the ip only constructor and authenticate() function if
     * you don't want to connect right now.
     *
     * @param httpClient instance of the Jetty shared client
     * @param ip ip address of bridge
     * @param port port of bridge
     * @param protocol protocol to connect to the bridge
     * @param username username to authenticate with
     * @param scheduler the ExecutorService to schedule commands
     */
    public HueBridge(HttpClient httpClient, String ip, int port, String protocol, String username,
            ScheduledExecutorService scheduler)
            throws IOException, ApiException, ConfigurationException, UnauthorizedException {
        this(httpClient, ip, port, protocol, scheduler);
        authenticate(username);
    }

    /**
     * ------------------------------------------------------------------------
     * Abstract method declarations
     * ------------------------------------------------------------------------
     */

    protected abstract String getRootPath();

    public abstract ApiVersion getVersion() throws IOException, ApiException;

    /**
     * Returns a list of lights known to the bridge.
     *
     * @return list of known lights as {@link FullLight}s
     * @throws IOException
     * @throws ApiException
     */
    public abstract List<ILight> getFullLights() throws IOException, ApiException;

    /**
     * Returns a list of lights known to the bridge.
     *
     * @return list of known lights
     * @throws IOException
     * @throws ApiException
     */
    public abstract List<HueObject> getLights() throws IOException, ApiException;

    /**
     * Returns a list of sensors known to the bridge
     *
     * @return list of sensors
     * @throws IOException
     * @throws ApiException
     * @throws ConfigurationException
     * @throws CommunicationException
     */
    public abstract List<ISensor> getSensors()
            throws IOException, ApiException, ConfigurationException, CommunicationException;

    /**
     * Start searching for new lights for 1 minute.
     * A maximum amount of 15 new lights will be added.
     *
     * @throws IOException
     * @throws ApiException
     * @throws ConfigurationException
     * @throws CommunicationException
     */
    public abstract void startSearch() throws IOException, ApiException, ConfigurationException, CommunicationException;

    /**
     * Start searching for new lights with given serial numbers for 1 minute.
     * A maximum amount of 15 new lights will be added.
     *
     * @param serialNumbers list of serial numbers
     * @throws UnauthorizedException thrown if the user no longer exists
     */
    public abstract void startSearch(List<String> serialNumbers)
            throws IOException, ApiException, ConfigurationException, CommunicationException;

    /**
     * Changes the state of a light.
     *
     * @param light light
     * @param update changes to the state
     */
    public abstract CompletableFuture<HueResult> setLightState(ILight light, IUpdate update);

    /**
     * Changes the state of a clip sensor.
     *
     * @param sensor sensor
     * @param update changes to the state
     */
    public abstract CompletableFuture<HueResult> setSensorState(ISensor sensor, IUpdate update);

    /**
     * Changes the config of a sensor.
     *
     * @param sensor sensor
     * @param update changes to the config
     */
    public abstract CompletableFuture<HueResult> updateSensorConfig(ISensor sensor, ConfigUpdate update);

    /**
     * Returns the list of groups, including the unmodifiable all lights group.
     *
     * @return list of groups
     * @throws IOException
     * @throws ApiException
     * @throws ConfigurationException
     * @throws CommunicationException
     */
    public abstract List<FullGroup> getGroups()
            throws IOException, ApiException, ConfigurationException, CommunicationException;

    /**
     * Changes the state of a group.
     *
     * @param group group
     * @param update changes to the state
     */
    public abstract CompletableFuture<HueResult> setGroupState(Group group, IUpdate update);

    /**
     * Returns the list of scenes that are not recyclable.
     *
     * @return all scenes that can be activated
     * @throws IOException
     * @throws ApiException
     * @throws ConfigurationException
     * @throws CommunicationException
     */
    public abstract List<Scene> getScenes()
            throws IOException, ApiException, ConfigurationException, CommunicationException;

    /**
     * Activate scene to all lights that belong to the scene.
     *
     * @param id the scene to be activated
     */
    public abstract CompletableFuture<HueResult> recallScene(String id);

    /**
     * Link with bridge using the specified device type. A random valid username will be generated by the bridge and
     * returned.
     *
     * @return new random username generated by bridge
     * @param devicetype identifier of application [0..40]
     * @throws IOException
     * @throws ApiException
     * @throws ConfigurationException
     * @throws CommunicationException
     */
    public abstract String link(String devicetype)
            throws IOException, ApiException, ConfigurationException, CommunicationException;

    /**
     * Returns the entire bridge configuration.
     * This request is rather resource intensive for the bridge,
     * don't use it more often than necessary. Prefer using requests for
     * specific information your app needs.
     *
     * @return full bridge configuration
     * @throws IOException
     * @throws ApiException
     * @throws ConfigurationException
     * @throws CommunicationException
     */
    public abstract FullConfig getFullConfig()
            throws IOException, ApiException, ConfigurationException, CommunicationException;

    /**
     * Used as assert in all requests to elegantly catch common errors
     *
     * @param result
     * @throws IOException
     * @throws ApiException
     */
    public abstract void handleErrors(HueResult result) throws IOException, ApiException;

    /**
     * ------------------------------------------------------------------------
     * Common methods used externally
     * ------------------------------------------------------------------------
     */

    /**
     * Returns the IP address of the bridge.
     *
     * @return ip address of bridge
     */
    public String getIPAddress() {
        return ip;
    }

    /**
     * Returns the username currently authenticated with or null if there isn't one.
     *
     * @return username or null
     */
    public @Nullable String getUsername() {
        return username;
    }

    /**
     * Authenticate on the bridge as the specified user.
     * This function verifies that the specified username is valid and will use
     * it for subsequent requests if it is, otherwise an UnauthorizedException
     * is thrown and the internal username is not changed.
     *
     * @param username username to authenticate
     * @throws ConfigurationException thrown on ssl failure
     * @throws UnauthorizedException thrown if authentication failed
     */
    public void authenticate(String username) throws ConfigurationException, UnauthorizedException {
        try {
            this.username = username;
            getLights();
        } catch (ConfigurationException e) {
            throw e;
        } catch (Exception e) {
            this.username = null;
            throw new UnauthorizedException(e.toString());
        }
    }

    // Used as assert in requests that require authentication
    protected void requireAuthentication() {
        if (this.username == null) {
            throw new IllegalStateException("linking is required before interacting with the bridge");
        }
    }

    // Methods that convert gson exceptions into ApiExceptions
    protected <T> T safeFromJson(String json, Type typeOfT) throws ApiException {
        try {
            return gson.fromJson(json, typeOfT);
        } catch (JsonParseException e) {
            throw new ApiException("API returned unexpected result: " + e.getMessage());
        }
    }

    protected <T> T safeFromJson(String json, Class<T> classOfT) throws ApiException {
        try {
            return gson.fromJson(json, classOfT);
        } catch (JsonParseException e) {
            throw new ApiException("API returned unexpected result: " + e.getMessage());
        }
    }

    public HueResult get(String address) throws ConfigurationException, CommunicationException {
        return doNetwork(address, HttpMethod.GET);
    }

    public HueResult post(String address, String body) throws ConfigurationException, CommunicationException {
        return doNetwork(address, HttpMethod.POST, body);
    }

    public HueResult put(String address, String body) throws ConfigurationException, CommunicationException {
        return doNetwork(address, HttpMethod.PUT, body);
    }

    public HueResult delete(String address) throws ConfigurationException, CommunicationException {
        return doNetwork(address, HttpMethod.DELETE);
    }

    protected CompletableFuture<HueResult> putAsync(String address, String body, long delay) {
        AsyncPutParameters asyncPutParameters = new AsyncPutParameters(address, body, delay);
        synchronized (commandsQueue) {
            if (commandsQueue.isEmpty()) {
                commandsQueue.offer(asyncPutParameters);
                Future<?> localJob = job;
                if (localJob == null || localJob.isDone()) {
                    job = scheduler.submit(this::executeCommands);
                }
            } else {
                commandsQueue.offer(asyncPutParameters);
            }
        }
        return asyncPutParameters.future;
    }

    /**
     * UTF-8 URL encode
     *
     * @param str input string
     * @return encoded version thereof
     */
    protected String enc(@Nullable String str) {
        return str == null ? "" : URLEncoder.encode(str, StandardCharsets.UTF_8);
    }

    /**
     * ------------------------------------------------------------------------
     * Internal private methods
     * ------------------------------------------------------------------------
     */

    private HueResult doNetwork(String address, HttpMethod requestMethod)
            throws ConfigurationException, CommunicationException {
        return doNetwork(address, requestMethod, null);
    }

    private HueResult doNetwork(String address, HttpMethod requestMethod, @Nullable String body)
            throws ConfigurationException, CommunicationException {
        logger.trace("Hue request: {} - URL = '{}'", requestMethod, address);
        try {
            final Request request = httpClient.newRequest(address).method(requestMethod).timeout(timeout,
                    TimeUnit.MILLISECONDS);

            if (body != null) {
                logger.trace("Hue request body: '{}'", body);
                request.content(new StringContentProvider(body), "application/json");
            }

            final ContentResponse contentResponse = request.send();

            final int httpStatus = contentResponse.getStatus();
            final String content = contentResponse.getContentAsString();
            logger.trace("Hue response: status = {}, content = '{}'", httpStatus, content);
            return new HueResult(content, httpStatus);
        } catch (ExecutionException e) {
            String message = e.getMessage();
            if (e.getCause() instanceof SSLHandshakeException) {
                logger.debug("SSLHandshakeException occurred during execution: {}", message, e);
                throw new ConfigurationException(TEXT_OFFLINE_CONFIGURATION_ERROR_INVALID_SSL_CERIFICATE, e.getCause());
            } else {
                logger.debug("ExecutionException occurred during execution: {}", message, e);
                throw new CommunicationException(message == null ? TEXT_OFFLINE_COMMUNICATION_ERROR : message,
                        e.getCause());
            }
        } catch (TimeoutException e) {
            String message = e.getMessage();
            logger.debug("TimeoutException occurred during execution: {}", message, e);
            throw new CommunicationException(message == null ? TEXT_OFFLINE_COMMUNICATION_ERROR : message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String message = e.getMessage();
            logger.debug("InterruptedException occurred during execution: {}", message, e);
            throw new CommunicationException(message == null ? TEXT_OFFLINE_COMMUNICATION_ERROR : message);
        }
    }

    private void executeCommands() {
        while (true) {
            try {
                long delayTime = 0;
                synchronized (commandsQueue) {
                    AsyncPutParameters payloadCallbackPair = commandsQueue.poll();
                    if (payloadCallbackPair != null) {
                        logger.debug("Async sending put to address: {} delay: {} body: {}", payloadCallbackPair.address,
                                payloadCallbackPair.delay, payloadCallbackPair.body);
                        try {
                            HueResult result = doNetwork(payloadCallbackPair.address, HttpMethod.PUT,
                                    payloadCallbackPair.body);
                            payloadCallbackPair.future.complete(result);
                        } catch (ConfigurationException | CommunicationException e) {
                            payloadCallbackPair.future.completeExceptionally(e);
                        }
                        delayTime = payloadCallbackPair.delay;
                    } else {
                        return;
                    }
                }
                Thread.sleep(delayTime);
            } catch (InterruptedException e) {
                logger.debug("commandExecutorThread was interrupted", e);
            }
        }
    }

    /**
     * ------------------------------------------------------------------------
     * Internal static classes
     * ------------------------------------------------------------------------
     */

    public static class HueResult {
        public final String body;
        public final int responseCode;

        public HueResult(String body, int responseCode) {
            this.body = body;
            this.responseCode = responseCode;
        }
    }

    public final class AsyncPutParameters {
        public final String address;
        public final String body;
        public final CompletableFuture<HueResult> future;
        public final long delay;

        public AsyncPutParameters(String address, String body, long delay) {
            this.address = address;
            this.body = body;
            this.future = new CompletableFuture<>();
            this.delay = delay;
        }
    }
}
