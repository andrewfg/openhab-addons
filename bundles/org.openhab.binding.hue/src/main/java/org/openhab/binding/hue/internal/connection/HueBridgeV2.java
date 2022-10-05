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

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.hue.internal.dto.ApiVersion;
import org.openhab.binding.hue.internal.dto.ConfigUpdate;
import org.openhab.binding.hue.internal.dto.FullConfig;
import org.openhab.binding.hue.internal.dto.FullGroup;
import org.openhab.binding.hue.internal.dto.FullLight;
import org.openhab.binding.hue.internal.dto.Group;
import org.openhab.binding.hue.internal.dto.HueObject;
import org.openhab.binding.hue.internal.dto.Scene;
import org.openhab.binding.hue.internal.dto.StateUpdate;
import org.openhab.binding.hue.internal.dto.tag.ILight;
import org.openhab.binding.hue.internal.dto.tag.ISensor;
import org.openhab.binding.hue.internal.dto.tag.IUpdate;
import org.openhab.binding.hue.internal.dto.v2.Archetype;
import org.openhab.binding.hue.internal.dto.v2.Device2;
import org.openhab.binding.hue.internal.dto.v2.ProductData;
import org.openhab.binding.hue.internal.dto.v2.Resources;
import org.openhab.binding.hue.internal.exceptions.ApiException;
import org.openhab.core.i18n.CommunicationException;
import org.openhab.core.i18n.ConfigurationException;

/**
 * Representation of a connection with a Hue Bridge running API v2.
 *
 * @author Andrew Fiddian-Green - Initial Contribution
 */
@NonNullByDefault
public class HueBridgeV2 extends HueBridge {

    // private final Logger logger = LoggerFactory.getLogger(HueBridgeV2.class);

    private Map<String, Device2> deviceMap = new HashMap<>();

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
    public ApiVersion getVersion() throws IOException, ApiException {
        Map<String, Device2> devices = getCachedDevices();
        for (Entry<String, Device2> device : devices.entrySet()) {
            ProductData productData = device.getValue().getProductData();
            if (productData.getProductArchetype() == Archetype.BRIDGE_V2) {
                return productData.getSoftwareVersion();
            }
        }
        throw new ApiException("Device map does not contain a bridge");
    }

    private Map<String, Device2> getCachedDevices() throws IOException {
        if (deviceMap.isEmpty()) {
            deviceMap = getDevices();
        }
        return deviceMap;
    }

    private Map<String, Device2> getDevices() throws IOException {
        requireAuthentication();
        HueResult result = get(getUrl("device"));
        if (result.responseCode != HttpStatus.OK_200) {
            throw new IOException();
        }
        Resources devices = gson.fromJson(result.body, Resources.class);
        if (devices != null && devices.getErrors().isEmpty()) {
            List<Device2> deviceList = devices.getResources();
            if (!deviceList.isEmpty()) {
                deviceMap.clear();
                deviceMap.putAll(deviceList.stream().collect(Collectors.toMap(Device2::getId, Function.identity())));
                deviceMap.putAll(deviceList.stream().collect(Collectors.toMap(Device2::getIdV1, Function.identity())));
            }
        }
        return deviceMap;
    }

    @Override
    public List<ILight> getFullLights() throws IOException, ApiException {
        HueResult result = get(getUrl("resource/light/"));
        handleErrors(result);
        // TODO convert HueResult to List<FullLight>
        return List.of();
    }

    @Override
    public List<HueObject> getLights() throws IOException, ApiException {
        // TODO
        return List.of();
    }

    @Override
    public List<ISensor> getSensors() throws IOException, ApiException, ConfigurationException, CommunicationException {
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
    public CompletableFuture<HueResult> setLightState(ILight lightDto, IUpdate updateDto) {
        // TODO following two lines may throw ClassCastException, eventually, if my code is wrong
        FullLight light = (FullLight) lightDto;
        StateUpdate update = (StateUpdate) updateDto;

        requireAuthentication();
        // TODO convert V1 StateUpdate json to V2 equivalent
        String json = update.toJson();
        return putAsync(getUrl("resource/light/" + enc(light.getId())), json, update.getMessageDelay());
    }

    @Override
    public CompletableFuture<HueResult> setSensorState(ISensor sensor, IUpdate update) {
        // TODO Auto-generated method stub
        return new CompletableFuture<>();
    }

    @Override
    public CompletableFuture<HueResult> updateSensorConfig(ISensor sensor, ConfigUpdate update) {
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
    public CompletableFuture<HueResult> setGroupState(Group group, IUpdate update) {
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
