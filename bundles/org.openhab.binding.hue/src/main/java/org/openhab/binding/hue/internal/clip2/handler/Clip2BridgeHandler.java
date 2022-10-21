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
package org.openhab.binding.hue.internal.clip2.handler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.client.ClientBuilder;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.hue.internal.HueBindingConstants;
import org.openhab.binding.hue.internal.clip2.config.Clip2BridgeConfig;
import org.openhab.binding.hue.internal.clip2.connection.Clip2Bridge;
import org.openhab.binding.hue.internal.clip2.dto.MetaData;
import org.openhab.binding.hue.internal.clip2.dto.ProductData;
import org.openhab.binding.hue.internal.clip2.dto.Reference;
import org.openhab.binding.hue.internal.clip2.dto.Resource;
import org.openhab.binding.hue.internal.clip2.dto.Resources;
import org.openhab.binding.hue.internal.clip2.enums.Archetype;
import org.openhab.binding.hue.internal.clip2.enums.ResourceType;
import org.openhab.binding.hue.internal.connection.HueTlsTrustManagerProvider;
import org.openhab.binding.hue.internal.exceptions.ApiException;
import org.openhab.core.io.net.http.TlsTrustManagerProvider;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.jaxrs.client.SseEventSourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridge handler for a CLIP 2 bridge. It communicates with the bridge via CLIP 2 end points, and reads and writes API
 * V2 resource objects. It also subscribes to the server's SSE event stream, and receives SSE events from it.
 *
 * @author Andrew Fiddian-Green - Initial contribution.
 */
@NonNullByDefault
public class Clip2BridgeHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(Clip2BridgeHandler.class);

    private static final String FORMAT_HOST_PORT = "%s:443";

    private final HttpClient httpClient;
    private final ClientBuilder clientBuilder;
    private final SseEventSourceFactory eventSourceFactory;

    private @Nullable Clip2Bridge clip2Bridge;
    private @Nullable ScheduledFuture<?> refreshTask;
    private @Nullable ServiceRegistration<?> serviceRegistration;

    public Clip2BridgeHandler(Bridge bridge, HttpClient httpClient, ClientBuilder clientBuilder,
            SseEventSourceFactory eventSourceFactory) {
        super(bridge);
        this.httpClient = httpClient;
        this.clientBuilder = clientBuilder;
        this.eventSourceFactory = eventSourceFactory;
    }

    @Override
    public void initialize() {
        Clip2BridgeConfig config = getConfigAs(Clip2BridgeConfig.class);

        String ipAddress = config.ipAddress;
        if (ipAddress == null || ipAddress.isEmpty()) {
            logger.debug("initialize() invalid ip address '{}'", config.ipAddress);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error-no-ip-address");
            return;
        }

        String applicationKey = config.applicationKey;
        if (applicationKey == null || applicationKey.isEmpty()) {
            logger.debug("initialize() invalid application key (userName) '{}'", applicationKey);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/TODO offline.conf-error-no-application-key");
            return;
        }

        HueTlsTrustManagerProvider tlsTrustManagerProvider = new HueTlsTrustManagerProvider(
                String.format(FORMAT_HOST_PORT, ipAddress), config.useSelfSignedCertificate);
        serviceRegistration = FrameworkUtil.getBundle(getClass()).getBundleContext()
                .registerService(TlsTrustManagerProvider.class.getName(), tlsTrustManagerProvider, null);

        clip2Bridge = new Clip2Bridge(httpClient, clientBuilder, eventSourceFactory, this, ipAddress, applicationKey);

        try {
            // test server connection by trying to retrieve bridge device properties
            updateProperties();
        } catch (ApiException e) {
            logger.debug("initialize() exception '{}' - {}", e.getClass().getSimpleName(), e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/offline.communication-error");
            clip2Bridge = null;
            return;
        }

        updateStatus(ThingStatus.ONLINE);

        /*
         * Normally the handler should rely on the bridge sending SSE push updates. But in case SSE events may have been
         * lost due to lost packets, or the SSE connection itself having been dropped, we also run a refresh task at
         * infrequent intervals, as a fall-back in order to update the state via polling requests, and/or to
         * re-establish the SSE connection. Note: we call doRefresh() directly after 10 seconds to fetch the initial
         * state of all resources and to initially open the SSE connection.
         */
        ScheduledFuture<?> refreshTask = this.refreshTask;
        if (refreshTask != null) {
            refreshTask.cancel(false);
        }
        this.refreshTask = scheduler.scheduleWithFixedDelay(this::doRefresh, 10, config.refreshSeconds,
                TimeUnit.SECONDS);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // nothing to do..
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> refreshTask = this.refreshTask;
        if (refreshTask != null) {
            refreshTask.cancel(false);
            this.refreshTask = null;
        }
        Clip2Bridge clip2Bridge = this.clip2Bridge;
        if (clip2Bridge != null) {
            clip2Bridge.close();
            this.clip2Bridge = null;
        }
        ServiceRegistration<?> serviceRegistration = this.serviceRegistration;
        if (serviceRegistration != null) {
            serviceRegistration.unregister();
            this.serviceRegistration = null;
        }
    }

    /**
     * Method that is called when an SSE event message comes in.
     *
     * @param resources a list of incoming resource objects.
     */
    public void onSseEvent(List<Resource> resources) {
        try {
            for (Resource resource : resources) {
                notify(resource.markAsSparse());
            }
        } catch (ApiException e) {
            logger.debug("onSseEvent() {}, {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * Execute an HTTP GET for a resources reference object from the server.
     *
     * @param reference containing the resourceType and (optionally) the resourceId of the resource to get. If the
     *            resourceId is null then all resources of the given type are returned.
     * @return the resource, or null if something fails.
     */
    public @Nullable Resources getResources(Reference reference) {
        try {
            return getClip2Bridge().getResource(reference);
        } catch (ApiException e) {
            logger.debug("getResources() {}, {}", e.getClass().getSimpleName(), e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/offline.communication-error");
        }
        return null;
    }

    /**
     * Execute an HTTP PUT to send a Resource object to the server.
     *
     * @param resource the resource to put.
     * @throws ApiException if something fails.
     */
    public void putResource(Resource resource) {
        try {
            getClip2Bridge().putResource(resource);
        } catch (ApiException e) {
            logger.debug("putResource() {}, {}", e.getClass().getSimpleName(), e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/offline.communication-error");
        }
    }

    /**
     * Get the Clip2Bridge connection and throw an exception if it is null.
     *
     * @return the Clip2Bridge.
     * @throws ApiException if the Clip2Bridge is null.
     */
    private Clip2Bridge getClip2Bridge() throws ApiException {
        Clip2Bridge hueBridge = this.clip2Bridge;
        if (hueBridge != null) {
            return hueBridge;
        }
        throw new ApiException("getClip2Bridge() clip2Bridge is null");
    }

    /**
     * Scheduled task to refresh the Sse connection and poll state (in case any Sse events may have been lost).
     *
     * @throws ApiException if something failed.
     */
    private void doRefresh() {
        try {
            pollResources();
            getClip2Bridge().openSse();
            if (thing.getStatus() != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.ONLINE);
            }
        } catch (ApiException e) {
            logger.debug("doRefresh() exception '{}' - {}", e.getClass().getSimpleName(), e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/offline.communication-error");
        }
    }

    /**
     * Update the bridge thing properties.
     *
     * @throws ApiException if something failed.
     */
    private void updateProperties() throws ApiException {
        Resources resources = getClip2Bridge().getResources(ResourceType.DEVICE);
        List<Resource> devices = resources.getResources();
        if (devices.isEmpty()) {
            throw new ApiException("updateProperties() bridge contains no devices");
        }
        Map<String, String> properties = new HashMap<>(thing.getProperties());
        for (Resource device : devices) {
            // set resource properties
            properties.put(HueBindingConstants.PROPERTY_RESOURCE_ID, device.getId());
            properties.put(HueBindingConstants.PROPERTY_RESOURCE_TYPE, device.getType().toString());

            MetaData metaData = device.getMetaData();
            if (metaData != null && metaData.getArchetype() == Archetype.BRIDGE_V2) {
                // set metadata properties
                properties.put(HueBindingConstants.PROPERTY_RESOURCE_NAME, metaData.getName());
                properties.put(HueBindingConstants.PROPERTY_RESOURCE_ARCHETYPE, metaData.getArchetype().toString());

                // set product data properties
                ProductData productData = device.getProductData();
                if (productData != null) {
                    // set generic thing properties
                    properties.put(Thing.PROPERTY_MODEL_ID, productData.getModelId());
                    properties.put(Thing.PROPERTY_VENDOR, productData.getManufacturerName());
                    properties.put(Thing.PROPERTY_FIRMWARE_VERSION, productData.getSoftwareVersion().toString());
                    String hardwarePlatformType = productData.getHardwarePlatformType();
                    if (hardwarePlatformType != null) {
                        properties.put(Thing.PROPERTY_HARDWARE_VERSION, hardwarePlatformType);
                    }

                    // set hue specific properties
                    properties.put(HueBindingConstants.PROPERTY_PRODUCT_NAME, productData.getProductName());
                    properties.put(HueBindingConstants.PROPERTY_PRODUCT_ARCHETYPE,
                            productData.getProductArchetype().toString());
                    properties.put(HueBindingConstants.PROPERTY_PRODUCT_CERTIFIED,
                            productData.getCertified().toString());
                }

                thing.setProperties(properties);
            }
        }
    }

    /**
     * Get the data for all (necessary) resources in the bridge, and notify all child thing handlers.
     *
     * @throws ApiException if something failed.
     */
    private void pollResources() throws ApiException {
        for (ResourceType resourceType : ResourceType.NOTIFY_TYPES) {
            for (Resource resource : getClip2Bridge().getResources(resourceType).getResources()) {
                notify(resource);
            }
        }
    }

    /**
     * Notify all child thing handlers about the contents of the given resource.
     *
     * @param resource the given resource.
     * @throws ApiException if something failed.
     */
    private void notify(Resource resource) throws ApiException {
        for (Thing thing : getThing().getThings()) {
            ThingHandler handler = thing.getHandler();
            if (handler instanceof ResourceThingHandler) {
                ((ResourceThingHandler) handler).notify(resource);
            }
        }
    }
}
