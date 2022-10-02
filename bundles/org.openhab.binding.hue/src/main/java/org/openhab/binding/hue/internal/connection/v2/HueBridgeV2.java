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
package org.openhab.binding.hue.internal.connection.v2;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.hue.internal.connection.HueBridge;
import org.openhab.binding.hue.internal.dto.ApiVersion;
import org.openhab.binding.hue.internal.dto.ConfigUpdate;
import org.openhab.binding.hue.internal.dto.FullConfig;
import org.openhab.binding.hue.internal.dto.FullGroup;
import org.openhab.binding.hue.internal.dto.FullSensor;
import org.openhab.binding.hue.internal.dto.Group;
import org.openhab.binding.hue.internal.dto.Scene;
import org.openhab.binding.hue.internal.dto.StateUpdate;
import org.openhab.binding.hue.internal.dto.interfaces.LightInstance;
import org.openhab.binding.hue.internal.dto.interfaces.LightUpdateInstance;
import org.openhab.binding.hue.internal.exceptions.ApiException;
import org.openhab.core.i18n.CommunicationException;
import org.openhab.core.i18n.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Representation of a connection with a Hue Bridge running API v2.
 *
 * @author Andrew Fiddian-Green - Initial Contribution
 */
@NonNullByDefault
public class HueBridgeV2 extends HueBridge {

    private final Logger logger = LoggerFactory.getLogger(HueBridgeV2.class);

    /**
     * ------------------------------------------------------------------------
     * Constructors
     * ------------------------------------------------------------------------
     */

    public HueBridgeV2(HttpClient httpClient, String ip, int port, String protocol,
            ScheduledExecutorService scheduler) {
        super(httpClient, ip, port, protocol, scheduler);
    }

    /**
     * ------------------------------------------------------------------------
     * Implementation of abstract methods
     * ------------------------------------------------------------------------
     */

    @Override
    protected String getRootPath() {
        return "/clip/v2";
    }

    @Override
    public ApiVersion getApiVersion() throws IOException, ApiException {
        // TODO get the API version dynamically from the hub
        return new ApiVersion(2, 0, 0);
    }

    @Override
    public List<LightInstance> getFullLights() throws IOException, ApiException {
        HueResult result = get(getUrl("resource/light/"));
        handleErrors(result);
        // TODO convert HueResult to List<FullLight>
        return List.of();
    }

    @Override
    public List<LightInstance> getLights() throws IOException, ApiException {
        return getFullLights().stream().collect(Collectors.toList());
    }

    @Override
    public List<FullSensor> getSensors()
            throws IOException, ApiException, ConfigurationException, CommunicationException {
        // TODO Auto-generated method stub
        return List.of();
    }

    @Override
    public void startSearch() throws IOException, ApiException, ConfigurationException, CommunicationException {
        // TODO Auto-generated method stub
    }

    @Override
    public void startSearch(List<String> serialNumbers)
            throws IOException, ApiException, ConfigurationException, CommunicationException {
        // TODO Auto-generated method stub
    }

    @Override
    public CompletableFuture<HueResult> setLightState(LightInstance lightInstance,
            LightUpdateInstance lightUpdateInstance) {
        requireAuthentication();
        // TODO convert V1 StateUpdate json to V2 equivalent

        String json = lightUpdateInstance.toJson();
        return putAsync(getUrl("resource/light/" + enc(lightInstance.getId())), json,
                lightUpdateInstance.getMessageDelay());
    }

    @Override
    public CompletableFuture<HueResult> setSensorState(FullSensor sensor, StateUpdate update) {
        // TODO Auto-generated method stub
        return new CompletableFuture<>();
    }

    @Override
    public CompletableFuture<HueResult> updateSensorConfig(FullSensor sensor, ConfigUpdate update) {
        // TODO Auto-generated method stub
        return new CompletableFuture<>();
    }

    @Override
    public List<FullGroup> getGroups()
            throws IOException, ApiException, ConfigurationException, CommunicationException {
        HueResult result = get(getUrl("resource/grouped_light/"));
        handleErrors(result);
        // TODO convert result contents to List<FullGroup>
        return List.of();
    }

    @Override
    public CompletableFuture<HueResult> setGroupState(Group group, StateUpdate update) {
        // TODO Auto-generated method stub
        return new CompletableFuture<>();
    }

    @Override
    public List<Scene> getScenes() throws IOException, ApiException, ConfigurationException, CommunicationException {
        HueResult result = get(getUrl("resource/scene/"));
        handleErrors(result);
        // TODO convert result contents to List<Scene>
        return List.of();
    }

    @Override
    public CompletableFuture<HueResult> recallScene(String id) {
        // TODO Auto-generated method stub
        return new CompletableFuture<>();
    }

    @Override
    public String link(String devicetype)
            throws IOException, ApiException, ConfigurationException, CommunicationException {
        // TODO Auto-generated method stub
        return "";
    }

    @Override
    public FullConfig getFullConfig() throws IOException, ApiException, ConfigurationException, CommunicationException {
        throw new ApiException("getFullConfig() method is not supported in API V2");
    }

    @Override
    public void handleErrors(HueResult result) throws IOException, ApiException {
        // TODO Auto-generated method stub
    }

    /**
     * ------------------------------------------------------------------------
     * Internal private methods
     * ------------------------------------------------------------------------
     */

    private String getUrl(String path) {
        return path.isEmpty() ? baseUrl : baseUrl + "/" + path;
    }
}
