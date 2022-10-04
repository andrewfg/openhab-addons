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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.hue.internal.dto.ApiVersion;
import org.openhab.binding.hue.internal.dto.ApiVersionUtils;
import org.openhab.binding.hue.internal.dto.Config;
import org.openhab.binding.hue.internal.dto.ConfigUpdate;
import org.openhab.binding.hue.internal.dto.CreateUserRequest;
import org.openhab.binding.hue.internal.dto.ErrorResponse;
import org.openhab.binding.hue.internal.dto.FullConfig;
import org.openhab.binding.hue.internal.dto.FullGroup;
import org.openhab.binding.hue.internal.dto.FullLight;
import org.openhab.binding.hue.internal.dto.FullSensor;
import org.openhab.binding.hue.internal.dto.Group;
import org.openhab.binding.hue.internal.dto.HueObject;
import org.openhab.binding.hue.internal.dto.Scene;
import org.openhab.binding.hue.internal.dto.SearchForLightsRequest;
import org.openhab.binding.hue.internal.dto.StateUpdate;
import org.openhab.binding.hue.internal.dto.SuccessResponse;
import org.openhab.binding.hue.internal.dto.tag.Light;
import org.openhab.binding.hue.internal.dto.tag.Sensor;
import org.openhab.binding.hue.internal.dto.tag.Update;
import org.openhab.binding.hue.internal.exceptions.ApiException;
import org.openhab.binding.hue.internal.exceptions.DeviceOffException;
import org.openhab.binding.hue.internal.exceptions.EntityNotAvailableException;
import org.openhab.binding.hue.internal.exceptions.GroupTableFullException;
import org.openhab.binding.hue.internal.exceptions.InvalidCommandException;
import org.openhab.binding.hue.internal.exceptions.LinkButtonException;
import org.openhab.binding.hue.internal.exceptions.UnauthorizedException;
import org.openhab.core.i18n.CommunicationException;
import org.openhab.core.i18n.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonParseException;

/**
 * Representation of a connection with a Hue Bridge running API v1.
 *
 * @author Q42 - Initial contribution
 * @author Andre Fuechsel - search for lights with given serial number added
 * @author Denis Dudnik - moved Jue library source code inside the smarthome Hue binding, minor code cleanup
 * @author Samuel Leisering - added cached config and API-Version
 * @author Laurent Garnier - change the return type of getGroups
 * @author Andrew Fiddian-Green - refactored HueBrige into HueBridge base class and HueBridgeV1 implementation
 */
@NonNullByDefault
public class HueBridgeV1 extends HueBridge {

    private final Logger logger = LoggerFactory.getLogger(HueBridgeV1.class);

    private @Nullable Config cachedConfig;

    /**
     * ------------------------------------------------------------------------
     * Constructors
     * ------------------------------------------------------------------------
     */

    public HueBridgeV1(HttpClient httpClient, String ip, int port, String protocol,
            ScheduledExecutorService scheduler) {
        super(httpClient, ip, port, protocol, scheduler);
    }

    public HueBridgeV1(HttpClient httpClient, String ip, int port, String protocol, String username,
            ScheduledExecutorService scheduler)
            throws ConfigurationException, UnauthorizedException, IOException, ApiException {
        super(httpClient, ip, port, protocol, username, scheduler);
    }

    /**
     * ------------------------------------------------------------------------
     * Implementation of abstract methods
     * ------------------------------------------------------------------------
     */

    @Override
    protected String getRootPath() {
        return "/api";
    }

    @Override
    public ApiVersion getVersion() throws IOException, ApiException {
        Config c = getCachedConfig();
        return ApiVersion.of(c.getApiVersion());
    }

    /**
     * Returns a list of lights known to the bridge.
     *
     * @return list of known lights as {@link FullLight}s
     * @throws UnauthorizedException thrown if the user no longer exists
     */
    @Override
    public List<Light> getFullLights() throws IOException, ApiException {
        if (ApiVersionUtils.supportsFullLights(getVersion())) {
            Type gsonType = FullLight.GSON_TYPE;
            // TODO
            // return getTypedLights(gsonType);
            return List.of();
        } else {
            return getFullConfig().getLights();
        }
    }

    /**
     * Returns a list of lights known to the bridge.
     *
     * @return list of known lights
     * @throws UnauthorizedException thrown if the user no longer exists
     */
    @Override
    public List<HueObject> getLights() throws IOException, ApiException {
        Type gsonType = HueObject.GSON_TYPE;
        return getTypedLights(gsonType);
    }

    private <T extends HueObject> List<T> getTypedLights(Type gsonType)
            throws IOException, ApiException, ConfigurationException, CommunicationException {
        requireAuthentication();

        HueResult result = get(getRelativeURL("lights"));

        handleErrors(result);

        Map<String, T> lightMap = safeFromJson(result.body, gsonType);
        List<T> lights = new ArrayList<>();
        lightMap.forEach((id, light) -> {
            light.setId(id);
            lights.add(light);
        });
        return lights;
    }

    /**
     * Returns a list of sensors known to the bridge
     *
     * @return list of sensors
     * @throws UnauthorizedException thrown if the user no longer exists
     */
    @Override
    public List<Sensor> getSensors() throws IOException, ApiException, ConfigurationException, CommunicationException {
        requireAuthentication();

        HueResult result = get(getRelativeURL("sensors"));

        handleErrors(result);

        Map<String, FullSensor> sensorMap = safeFromJson(result.body, FullSensor.GSON_TYPE);
        List<Sensor> sensors = new ArrayList<>();
        sensorMap.forEach((id, sensor) -> {
            sensor.setId(id);
            sensors.add(sensor);
        });
        return sensors;
    }

    /**
     * Start searching for new lights for 1 minute.
     * A maximum amount of 15 new lights will be added.
     *
     * @throws UnauthorizedException thrown if the user no longer exists
     */
    @Override
    public void startSearch() throws IOException, ApiException, ConfigurationException, CommunicationException {
        requireAuthentication();

        HueResult result = post(getRelativeURL("lights"), "");

        handleErrors(result);
    }

    /**
     * Start searching for new lights with given serial numbers for 1 minute.
     * A maximum amount of 15 new lights will be added.
     *
     * @param serialNumbers list of serial numbers
     * @throws UnauthorizedException thrown if the user no longer exists
     */
    @Override
    public void startSearch(List<String> serialNumbers)
            throws IOException, ApiException, ConfigurationException, CommunicationException {
        requireAuthentication();

        HueResult result = post(getRelativeURL("lights"), gson.toJson(new SearchForLightsRequest(serialNumbers)));

        handleErrors(result);
    }

    /**
     * Changes the state of a light.
     *
     * @param light light
     * @param update changes to the state
     * @throws UnauthorizedException thrown if the user no longer exists
     * @throws EntityNotAvailableException thrown if the specified light no longer exists
     * @throws DeviceOffException thrown if the specified light is turned off
     * @throws IOException if the bridge cannot be reached
     */
    @Override
    public CompletableFuture<HueResult> setLightState(Light light, Update update) {
        FullLight fullLight = light.toFullLight();
        StateUpdate stateUpdate = update.toStateUpdate();
        requireAuthentication();

        return putAsync(getRelativeURL("lights/" + enc(fullLight.getId()) + "/state"), stateUpdate.toJson(),
                stateUpdate.getMessageDelay());
    }

    /**
     * Changes the state of a clip sensor.
     *
     * @param sensor sensor
     * @param update changes to the state
     * @throws UnauthorizedException thrown if the user no longer exists
     * @throws EntityNotAvailableException thrown if the specified sensor no longer exists
     * @throws DeviceOffException thrown if the specified sensor is turned off
     * @throws IOException if the bridge cannot be reached
     */
    @Override
    public CompletableFuture<HueResult> setSensorState(Sensor sensor, Update update) {
        FullSensor fullSensor = sensor.toFullSensor();
        StateUpdate stateUpdate = update.toStateUpdate();
        requireAuthentication();

        return putAsync(getRelativeURL("sensors/" + enc(fullSensor.getId()) + "/state"), stateUpdate.toJson(),
                stateUpdate.getMessageDelay());
    }

    /**
     * Changes the config of a sensor.
     *
     * @param sensor sensor
     * @param update changes to the config
     * @throws UnauthorizedException thrown if the user no longer exists
     * @throws EntityNotAvailableException thrown if the specified sensor no longer exists
     * @throws IOException if the bridge cannot be reached
     */
    @Override
    public CompletableFuture<HueResult> updateSensorConfig(Sensor sensor, ConfigUpdate update) {
        FullSensor fullSensor = sensor.toFullSensor();
        requireAuthentication();

        return putAsync(getRelativeURL("sensors/" + enc(fullSensor.getId()) + "/config"), update.toJson(),
                update.getMessageDelay());
    }

    /**
     * Returns the list of groups, including the unmodifiable all lights group.
     *
     * @return list of groups
     * @throws UnauthorizedException thrown if the user no longer exists
     */
    @Override
    public List<FullGroup> getGroups()
            throws IOException, ApiException, ConfigurationException, CommunicationException {
        requireAuthentication();

        HueResult result = get(getRelativeURL("groups"));

        handleErrors(result);

        Map<String, FullGroup> groupMap = safeFromJson(result.body, FullGroup.GSON_TYPE);
        List<FullGroup> groups = new ArrayList<>();
        if (groupMap.get("0") == null) {
            // Group 0 is not returned, we create it as in fact it exists
            try {
                groups.add(getGroup(getAllGroup()));
            } catch (FileNotFoundException e) {
                // We need a special exception handling here to further support deCONZ REST API. On deCONZ group "0" may
                // not exist and the APIs will return a different HTTP status code if requesting a non existing group
                // (Hue: 200, deCONZ: 404).
                // see https://github.com/openhab/openhab-addons/issues/9175
                logger.debug("Cannot find AllGroup with id \"0\" on Hue Bridge. Skipping it.");
            }
        }
        groupMap.forEach((id, group) -> {
            group.setId(id);
            groups.add(group);
        });
        return groups;
    }

    /**
     * Changes the state of a group.
     *
     * @param group group
     * @param update changes to the state
     * @throws UnauthorizedException thrown if the user no longer exists
     * @throws EntityNotAvailableException thrown if the specified group no longer exists
     */
    @Override
    public CompletableFuture<HueResult> setGroupState(Group group, Update update) {
        StateUpdate stateUpdate = update.toStateUpdate();
        requireAuthentication();

        return putAsync(getRelativeURL("groups/" + enc(group.getId()) + "/action"), stateUpdate.toJson(),
                stateUpdate.getMessageDelay());
    }

    /**
     * Returns the list of scenes that are not recyclable.
     *
     * @return all scenes that can be activated
     */
    @Override
    public List<Scene> getScenes() throws IOException, ApiException, ConfigurationException, CommunicationException {
        requireAuthentication();

        HueResult result = get(getRelativeURL("scenes"));

        handleErrors(result);

        Map<String, Scene> sceneMap = safeFromJson(result.body, Scene.GSON_TYPE);
        return sceneMap.entrySet().stream()//
                .map(e -> {
                    e.getValue().setId(e.getKey());
                    return e.getValue();
                })//
                .filter(scene -> !scene.isRecycle())//
                .sorted(Comparator.comparing(Scene::extractKeyForComparator))//
                .collect(Collectors.toList());
    }

    /**
     * Activate scene to all lights that belong to the scene.
     *
     * @param id the scene to be activated
     * @throws IOException if the bridge cannot be reached
     */
    @Override
    public CompletableFuture<HueResult> recallScene(String id) {
        Group allLightsGroup = new Group();
        return setGroupState(allLightsGroup, new StateUpdate().setScene(id));
    }

    /**
     * Returns the entire bridge configuration.
     * This request is rather resource intensive for the bridge,
     * don't use it more often than necessary. Prefer using requests for
     * specific information your app needs.
     *
     * @return full bridge configuration
     * @throws UnauthorizedException thrown if the user no longer exists
     */
    @Override
    public FullConfig getFullConfig() throws IOException, ApiException, ConfigurationException, CommunicationException {
        requireAuthentication();

        HueResult result = get(getRelativeURL(""));

        handleErrors(result);

        FullConfig fullConfig = gson.fromJson(result.body, FullConfig.class);
        return Objects.requireNonNull(fullConfig);
    }

    // Used as assert in all requests to elegantly catch common errors
    @Override
    public void handleErrors(HueResult result) throws IOException, ApiException {
        if (result.responseCode != HttpStatus.OK_200) {
            throw new IOException();
        } else {
            try {
                List<ErrorResponse> errors = gson.fromJson(result.body, ErrorResponse.GSON_TYPE);
                if (errors == null) {
                    return;
                }

                for (ErrorResponse error : errors) {
                    if (error.getType() == null) {
                        continue;
                    }

                    switch (error.getType()) {
                        case 1:
                            username = null;
                            throw new UnauthorizedException(error.getDescription());
                        case 3:
                            throw new EntityNotAvailableException(error.getDescription());
                        case 7:
                            throw new InvalidCommandException(error.getDescription());
                        case 101:
                            throw new LinkButtonException(error.getDescription());
                        case 201:
                            throw new DeviceOffException(error.getDescription());
                        case 301:
                            throw new GroupTableFullException(error.getDescription());
                        default:
                            throw new ApiException(error.getDescription());
                    }
                }
            } catch (JsonParseException e) {
                // Not an error
            }
        }
    }

    @Override
    public String link(String devicetype)
            throws IOException, ApiException, ConfigurationException, CommunicationException {
        return (this.username = link(new CreateUserRequest(devicetype)));
    }

    /**
     * ------------------------------------------------------------------------
     * Internal private methods
     * ------------------------------------------------------------------------
     */

    /**
     * Returns the basic {@link Config} configuration.
     *
     * @return The {@link Config} of the Hue Bridge, loaded and cached lazily on the first call
     * @throws IOException
     * @throws ApiException
     * @throws ConfigurationException
     * @throws CommunicationException
     */
    protected Config getConfig() throws IOException, ApiException, ConfigurationException, CommunicationException {
        requireAuthentication();
        HueResult result = get(getRelativeURL("config"));
        handleErrors(result);
        return safeFromJson(result.body, Config.class);
    }

    /**
     * Returns a cached version of the basic {@link Config} mostly immutable configuration.
     * This can be used to reduce load on the bridge.
     *
     * @return The {@link Config} of the Hue Bridge, loaded and cached lazily on the first call
     * @throws IOException
     * @throws ApiException
     */
    private Config getCachedConfig() throws IOException, ApiException {
        if (cachedConfig == null) {
            cachedConfig = getConfig();
        }

        return Objects.requireNonNull(cachedConfig);
    }

    /**
     * Returns detailed information for the given group.
     *
     * @param group group
     * @return detailed group information
     * @throws UnauthorizedException thrown if the user no longer exists
     * @throws EntityNotAvailableException thrown if a group with the given id doesn't exist
     */
    private FullGroup getGroup(Group group)
            throws IOException, ApiException, ConfigurationException, CommunicationException {
        requireAuthentication();

        HueResult result = get(getRelativeURL("groups/" + enc(group.getId())));

        handleErrors(result);

        FullGroup fullGroup = safeFromJson(result.body, FullGroup.class);
        fullGroup.setId(group.getId());
        return fullGroup;
    }

    /**
     * Returns a group object representing all lights.
     *
     * @return all lights pseudo group
     */
    private Group getAllGroup() {
        return new Group();
    }

    private String getRelativeURL(String path) {
        String relativeUrl = baseUrl;
        if (username != null) {
            relativeUrl += "/" + enc(username);
        }
        return path.isEmpty() ? relativeUrl : relativeUrl + "/" + path;
    }

    private String link(CreateUserRequest request)
            throws IOException, ApiException, ConfigurationException, CommunicationException {
        if (this.username != null) {
            throw new IllegalStateException("already linked");
        }
        HueResult result = post(getRelativeURL(""), gson.toJson(request));
        handleErrors(result);
        List<SuccessResponse> entries = safeFromJson(result.body, SuccessResponse.GSON_TYPE);
        SuccessResponse response = entries.get(0);
        String username = (String) response.success.get("username");
        if (username == null) {
            throw new ApiException("Response didn't contain username");
        }
        return username;
    }
}
