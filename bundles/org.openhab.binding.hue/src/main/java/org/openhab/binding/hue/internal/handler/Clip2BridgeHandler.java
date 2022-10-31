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
package org.openhab.binding.hue.internal.handler;

import static org.openhab.binding.hue.internal.HueBindingConstants.CHANNEL_SCENE;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.ws.rs.client.ClientBuilder;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.hue.internal.HueBindingConstants;
import org.openhab.binding.hue.internal.config.Clip2BridgeConfig;
import org.openhab.binding.hue.internal.connection.Clip2Bridge;
import org.openhab.binding.hue.internal.connection.HueTlsTrustManagerProvider;
import org.openhab.binding.hue.internal.dto.clip2.MetaData;
import org.openhab.binding.hue.internal.dto.clip2.ProductData;
import org.openhab.binding.hue.internal.dto.clip2.Resource;
import org.openhab.binding.hue.internal.dto.clip2.ResourceReference;
import org.openhab.binding.hue.internal.dto.clip2.Resources;
import org.openhab.binding.hue.internal.dto.clip2.enums.Archetype;
import org.openhab.binding.hue.internal.dto.clip2.enums.ResourceType;
import org.openhab.binding.hue.internal.exceptions.ApiException;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.net.http.TlsTrustManagerProvider;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
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

    private static final String FORMAT_HOST_PORT = "%s:443";
    private static final int FAST_SCHEDULE_MILLI_SECONDS = 500; //
    private static final int FAST_SCHEDULE_MAX_ATTEMPTS = 600; // i.e. 5 minutes

    private final Logger logger = LoggerFactory.getLogger(Clip2BridgeHandler.class);

    private final HttpClient httpClient;
    private final ClientBuilder clientBuilder;
    private final SseEventSourceFactory eventSourceFactory;

    private @Nullable Clip2Bridge clip2Bridge;
    private @Nullable ScheduledFuture<?> checkConnectionTask;
    private @Nullable ServiceRegistration<?> serviceRegistration;

    private boolean disposing;
    private Clip2BridgeConfig config = new Clip2BridgeConfig();
    private int retryAttemptsRemaining = FAST_SCHEDULE_MAX_ATTEMPTS;

    public Clip2BridgeHandler(Bridge bridge, HttpClient httpClient, ClientBuilder clientBuilder,
            SseEventSourceFactory eventSourceFactory) {
        super(bridge);
        this.httpClient = httpClient;
        this.clientBuilder = clientBuilder;
        this.eventSourceFactory = eventSourceFactory;
    }

    /**
     * Try to connect and set the online status accordingly. If the connection attempt throws an IllegalAccessException
     * then try to register the existing application key, or create a new one, with the hub. If the connection attempt
     * throws an ApiException then set the thing status to offline. This method is called on a scheduler thread, and it
     * reschedules itself repeatedly until the thing is shutdown.
     */
    private void checkConnection() {
        if (!disposing) {

            // check connection to the hub
            ThingStatusDetail errorStatus = ThingStatusDetail.NONE;
            try {
                getClip2Bridge().checkConnection();
            } catch (IllegalAccessException e) {
                logger.debug("doCheckConnection() {}", Clip2Bridge.exceptionMessageFrom(e));
                errorStatus = ThingStatusDetail.CONFIGURATION_ERROR;
            } catch (ApiException e) {
                logger.debug("doCheckConnection() {}", Clip2Bridge.exceptionMessageFrom(e));
                errorStatus = ThingStatusDetail.COMMUNICATION_ERROR;
            }

            // update the thing status
            boolean retryAgainFast = false;
            switch (errorStatus) {
                case CONFIGURATION_ERROR:
                    updateStatus(ThingStatus.OFFLINE, errorStatus,
                            "@text/offline.clip2.conf-error-press-pairing-button");
                    if (retryAttemptsRemaining > 0) {
                        try {
                            registerApplicationKey();
                            retryAgainFast = true;
                        } catch (IllegalAccessException e) {
                            retryAgainFast = true;
                        } catch (ApiException e) {
                        }
                    } else {
                        updateStatus(ThingStatus.OFFLINE, errorStatus,
                                "@text/offline.clip2.conf-error-creation-applicationkey");
                    }
                    break;

                case COMMUNICATION_ERROR:
                    updateStatus(ThingStatus.OFFLINE, errorStatus, "@text/offline.communication-error");
                    break;

                default:
                    updateStatus(ThingStatus.ONLINE);
                    refreshThingState();
                    break;
            }
            retryAttemptsRemaining = retryAgainFast ? retryAttemptsRemaining-- : FAST_SCHEDULE_MAX_ATTEMPTS;

            /*
             * This method continuously schedules itself to be called again. Note: there is no need to cancel or null
             * the prior future that called this method here, since the method is self evidently terminating itself
             * right now!
             */
            checkConnectionTask = scheduler.schedule(() -> checkConnection(),
                    retryAgainFast ? FAST_SCHEDULE_MILLI_SECONDS : 1000 * config.refreshSeconds, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Return the application key for the console app.
     *
     * @return the application key.
     */
    public String consoleGetApplicationKey() {
        Clip2BridgeConfig config = getConfigAs(Clip2BridgeConfig.class);
        String applicationKey = config.applicationKey;
        return "  - Application key: " + (applicationKey != null ? applicationKey : "undefined");
    }

    /**
     * Return the list of resource texts for the console app.
     *
     * @param type the resource type to get.
     * @return list of user display texts.
     */
    public List<String> consoleGetResources(ResourceType type) {
        try {
            return getClip2Bridge().getResources(new ResourceReference().setType(type)).getResources().stream()
                    .map(r -> String.format("  - %s id: %s - %s: '%s'", type.toString(), r.getId(), r.getProductName(),
                            r.getName()))
                    .collect(Collectors.toList());
        } catch (ApiException e) {
        }
        return List.of(String.format("no '%ss' found", type.toString()));
    }

    @Override
    public void dispose() {
        disposing = true;
        ScheduledFuture<?> task = checkConnectionTask;
        if (task != null) {
            task.cancel(false);
            checkConnectionTask = null;
        }
        Clip2Bridge bridge = clip2Bridge;
        if (bridge != null) {
            bridge.close();
            clip2Bridge = null;
        }
        ServiceRegistration<?> registration = serviceRegistration;
        if (registration != null) {
            registration.unregister();
            serviceRegistration = null;
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
     * Execute an HTTP GET for a resources reference object from the server.
     *
     * @param reference containing the resourceType and (optionally) the resourceId of the resource to get. If the
     *            resourceId is null then all resources of the given type are returned.
     * @return the resource, or null if something fails.
     */
    public @Nullable Resources getResources(ResourceReference reference) {
        if (!disposing) {
            try {
                return getClip2Bridge().getResources(reference);
            } catch (ApiException e) {
                logger.debug("getResources() {}", Clip2Bridge.exceptionMessageFrom(e));
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "@text/offline.communication-error");
            }
        }
        return null;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (RefreshType.REFRESH.equals(command)) {
            return;
        }
        if (CHANNEL_SCENE.equals(channelUID.getId()) && command instanceof StringType) {
            putResource(new Resource(ResourceType.SCENE).setId(command.toString()));
        }
    }

    @Override
    public void initialize() {
        disposing = false;

        Clip2BridgeConfig config = getConfigAs(Clip2BridgeConfig.class);
        this.config = config;

        String ipAddress = config.ipAddress;
        if (ipAddress == null || ipAddress.isEmpty()) {
            logger.debug("initialize() invalid ip address '{}'", config.ipAddress);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error-no-ip-address");
            return;
        }

        HueTlsTrustManagerProvider tlsTrustManagerProvider = new HueTlsTrustManagerProvider(
                String.format(FORMAT_HOST_PORT, ipAddress), config.useSelfSignedCertificate);
        serviceRegistration = FrameworkUtil.getBundle(getClass()).getBundleContext()
                .registerService(TlsTrustManagerProvider.class.getName(), tlsTrustManagerProvider, null);

        String applicationKey = config.applicationKey;
        clip2Bridge = new Clip2Bridge(httpClient, clientBuilder, eventSourceFactory, this, ipAddress,
                applicationKey != null ? applicationKey : "");

        updateStatus(ThingStatus.UNKNOWN);

        retryAttemptsRemaining = FAST_SCHEDULE_MAX_ATTEMPTS;
        scheduler.submit(() -> checkConnection());
    }

    /**
     * Notify all child thing handlers about the contents of the given resource.
     *
     * @param resource the given resource.
     * @throws ApiException if something failed.
     */
    private void notifyResource(Resource resource) throws ApiException {
        if (!disposing) {
            for (Thing thing : getThing().getThings()) {
                ThingHandler handler = thing.getHandler();
                if (handler instanceof DeviceThingHandler) {
                    ((DeviceThingHandler) handler).notifyResource(resource);
                }
            }
        }
    }

    /**
     * Called when the bridge SSE connection has been closed.
     */
    public void onSseComplete() {
        logger.warn("notifySseComplete() SSE session completed");
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
    }

    /**
     * Called when the bridge SSE connection has returned an error.
     */
    public void onSseError(Throwable e) {
        logger.warn("notifySseError() {}", Clip2Bridge.exceptionMessageFrom(e));
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
    }

    /**
     * Method that is called when an SSE event message comes in.
     *
     * @param resources a list of incoming resource objects.
     */
    public void onSseEvent(List<Resource> resources) {
        if (!disposing) {
            try {
                for (Resource resource : resources) {
                    notifyResource(resource.markAsSparse());
                }
            } catch (ApiException e) {
                logger.debug("onSseEvent() {}", Clip2Bridge.exceptionMessageFrom(e));
            }
        }
    }

    /**
     * Get the data for all (necessary) resources in the bridge, and notify all child thing handlers.
     *
     * @throws ApiException if something failed.
     */
    private void pollResources() throws ApiException {
        if (!disposing) {
            ResourceReference reference = new ResourceReference();
            for (ResourceType resourceType : ResourceType.NOTIFY_TYPES) {
                for (Resource resource : getClip2Bridge().getResources(reference.setType(resourceType))
                        .getResources()) {
                    notifyResource(resource);
                }
            }
        }
    }

    /**
     * Execute an HTTP PUT to send a Resource object to the server.
     *
     * @param resource the resource to put.
     * @throws ApiException if something fails.
     */
    public void putResource(Resource resource) {
        if (!disposing) {
            try {
                getClip2Bridge().putResource(resource);
            } catch (ApiException e) {
                logger.warn("putResource() {}", Clip2Bridge.exceptionMessageFrom(e));
            }
        }
    }

    /**
     * Refresh the Sse connection and poll for the thing's state. Called sporadically in case any Sse events may have
     * been lost.
     *
     * @throws ApiException if something failed.
     */
    private void refreshThingState() {
        if (!disposing) {
            try {
                updateProperties();
                pollResources();
                getClip2Bridge().openSse();
            } catch (ApiException e) {
                logger.debug("doRefresh() {}", Clip2Bridge.exceptionMessageFrom(e));
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "@text/offline.communication-error");
            }
        }
    }

    /**
     * Register the application key with the hub. If the current application key is empty it will create a new one.
     *
     * @throws ApiException if there was a communication error.
     * @throws IllegalAccessException if the communication was OK but the registration failed anyway.
     *
     */
    private void registerApplicationKey() throws IllegalAccessException, ApiException {
        String newApplicationKey = getClip2Bridge().registerApplicationKey(config.applicationKey);
        Configuration config = editConfiguration();
        config.put(Clip2BridgeConfig.APPLICATION_KEY, newApplicationKey);
        updateConfiguration(config);
    }

    /**
     * Update the bridge thing properties.
     *
     */
    private void updateProperties() {
        Resources resources = null;
        try {
            resources = getClip2Bridge().getResources(new ResourceReference().setType(ResourceType.DEVICE));
        } catch (ApiException e) {
            logger.warn("updateProperties() {}", Clip2Bridge.exceptionMessageFrom(e));
            return;
        }

        List<Resource> devices = resources.getResources();
        if (devices.isEmpty()) {
            logger.warn("updateProperties() bridge contains no devices");
        }

        Map<String, @Nullable String> properties = new HashMap<>(thing.getProperties());
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
}
