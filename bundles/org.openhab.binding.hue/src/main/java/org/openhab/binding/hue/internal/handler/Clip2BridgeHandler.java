/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
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

import static org.openhab.binding.hue.internal.HueBindingConstants.THING_TYPE_BRIDGE_API2;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.HueBindingConstants;
import org.openhab.binding.hue.internal.config.Clip2BridgeConfig;
import org.openhab.binding.hue.internal.connection.Clip2Bridge;
import org.openhab.binding.hue.internal.connection.HueTlsTrustManagerProvider;
import org.openhab.binding.hue.internal.discovery.Clip2ThingDiscoveryService;
import org.openhab.binding.hue.internal.dto.clip2.MetaData;
import org.openhab.binding.hue.internal.dto.clip2.ProductData;
import org.openhab.binding.hue.internal.dto.clip2.Resource;
import org.openhab.binding.hue.internal.dto.clip2.ResourceReference;
import org.openhab.binding.hue.internal.dto.clip2.Resources;
import org.openhab.binding.hue.internal.dto.clip2.enums.Archetype;
import org.openhab.binding.hue.internal.dto.clip2.enums.ResourceType;
import org.openhab.binding.hue.internal.exceptions.ApiException;
import org.openhab.binding.hue.internal.exceptions.AssetNotLoadedException;
import org.openhab.binding.hue.internal.exceptions.HttpUnauthorizedException;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.i18n.LocaleProvider;
import org.openhab.core.i18n.TranslationProvider;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.io.net.http.TlsTrustManagerProvider;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
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

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_BRIDGE_API2);

    private static final int FAST_SCHEDULE_MILLI_SECONDS = 500;
    private static final int APPLICATION_KEY_MAX_TRIES = 600; // i.e. 300 seconds, 5 minutes
    private static final int RECONNECT_DELAY_SECONDS = 10;
    private static final int RECONNECT_MAX_TRIES = 5;

    private static final ResourceReference DEVICE = new ResourceReference().setType(ResourceType.DEVICE);
    private static final ResourceReference ROOM = new ResourceReference().setType(ResourceType.ROOM);
    private static final ResourceReference ZONE = new ResourceReference().setType(ResourceType.ZONE);
    private static final ResourceReference BRIDGE = new ResourceReference().setType(ResourceType.BRIDGE);
    private static final ResourceReference BRIDGE_HOME = new ResourceReference().setType(ResourceType.BRIDGE_HOME);
    private static final ResourceReference SCENE = new ResourceReference().setType(ResourceType.SCENE);

    private static final Set<ResourceReference> POLL_RESOURCE_SET = Set.of(DEVICE, ROOM, ZONE, BRIDGE_HOME);

    private final Logger logger = LoggerFactory.getLogger(Clip2BridgeHandler.class);

    private final HttpClientFactory httpClientFactory;
    private final ThingRegistry thingRegistry;
    private final Bundle bundle;
    private final LocaleProvider localeProvider;
    private final TranslationProvider translationProvider;

    private @Nullable Clip2Bridge clip2Bridge;
    private @Nullable ScheduledFuture<?> checkConnectionTask;
    private @Nullable ServiceRegistration<?> trustManagerRegistration;
    private @Nullable Clip2ThingDiscoveryService discoveryService;

    private boolean assetsLoaded;
    private int applKeyRetriesRemaining;
    private int connectRetriesRemaining;

    public Clip2BridgeHandler(Bridge bridge, HttpClientFactory httpClientFactory, ThingRegistry thingRegistry,
            Bundle bundle, LocaleProvider localeProvider, TranslationProvider translationProvider) {
        super(bridge);
        this.httpClientFactory = httpClientFactory;
        this.thingRegistry = thingRegistry;
        this.bundle = bundle;
        this.localeProvider = localeProvider;
        this.translationProvider = translationProvider;
    }

    /**
     * Check if assets are loaded.
     *
     * @throws AssetNotLoadedException if assets not loaded.
     */
    private void checkAssetsLoaded() throws AssetNotLoadedException {
        if (!assetsLoaded) {
            throw new AssetNotLoadedException("Assets not loaded");
        }
    }

    /**
     * Try to connect and set the online status accordingly. If the connection attempt throws an
     * HttpUnAuthorizedException then try to register the existing application key, or create a new one, with the hub.
     * If the connection attempt throws an ApiException then set the thing status to offline. This method is called on a
     * scheduler thread, which reschedules itself repeatedly until the thing is shutdown.
     */
    private synchronized void checkConnection() {
        logger.debug("checkConnection()");

        // check connection to the hub
        ThingStatusDetail thingStatus;
        try {
            checkAssetsLoaded();
            getClip2Bridge().testConnectionState();
            thingStatus = ThingStatusDetail.NONE;
        } catch (HttpUnauthorizedException e) {
            logger.debug("checkConnection() {}", e.getMessage(), e);
            thingStatus = ThingStatusDetail.CONFIGURATION_ERROR;
        } catch (ApiException e) {
            logger.debug("checkConnection() {}", e.getMessage(), e);
            thingStatus = ThingStatusDetail.COMMUNICATION_ERROR;
        } catch (AssetNotLoadedException e) {
            logger.debug("checkConnection() {}", e.getMessage(), e);
            thingStatus = ThingStatusDetail.BRIDGE_UNINITIALIZED;
        }

        // update the thing status
        boolean retryApplicationKey = false;
        boolean retryConnection = false;
        switch (thingStatus) {
            case CONFIGURATION_ERROR:
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "@text/offline.api2.conf-error-press-pairing-button");
                if (applKeyRetriesRemaining > 0) {
                    try {
                        registerApplicationKey();
                        retryApplicationKey = true;
                    } catch (HttpUnauthorizedException e) {
                        retryApplicationKey = true;
                    } catch (ApiException e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "@text/offline.communication-error");
                    } catch (IllegalStateException e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                "@text/offline.api2.conf-error-read-only");
                    } catch (AssetNotLoadedException e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED,
                                "@text/offline.api2.conf-error-assets-not-loaded");
                    }
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            "@text/offline.api2.conf-error-creation-applicationkey");
                }
                break;

            case COMMUNICATION_ERROR:
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "@text/offline.communication-error");
                retryConnection = connectRetriesRemaining > 0;
                break;

            case BRIDGE_UNINITIALIZED:
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED,
                        "@text/offline.api2.conf-error-assets-not-loaded");
                break;

            case NONE:
            default:
                updateSelf(); // go online
                break;
        }

        // this method schedules itself to be called again in a loop..
        ScheduledFuture<?> task = checkConnectionTask;
        if (Objects.nonNull(task)) {
            task.cancel(false);
        }
        int milliSeconds;
        if (retryApplicationKey) {
            // short delay used during attempts to create or validate an application key
            milliSeconds = FAST_SCHEDULE_MILLI_SECONDS;
            applKeyRetriesRemaining--;
        } else {
            // default delay, set via configuration parameter, used as heart-beat 'just-in-case'
            Clip2BridgeConfig config = getConfigAs(Clip2BridgeConfig.class);
            milliSeconds = config.checkMinutes * 60000;
            if (retryConnection) {
                // exponential back off delay used during attempts to reconnect
                int backOffDelay = 60000 * (int) Math.pow(2, RECONNECT_MAX_TRIES - connectRetriesRemaining);
                milliSeconds = Math.min(milliSeconds, backOffDelay);
                connectRetriesRemaining--;
            }
        }
        checkConnectionTask = scheduler.schedule(() -> checkConnection(), milliSeconds, TimeUnit.MILLISECONDS);
    }

    /**
     * If a child thing has been added, and the bridge is online, update the child's data.
     */
    @Override
    public void childHandlerInitialized(ThingHandler childHandler, Thing childThing) {
        if (thing.getStatus() == ThingStatus.ONLINE && (childHandler instanceof Clip2ThingHandler)) {
            logger.debug("childHandlerInitialized() {}", childThing.getUID());
            try {
                updateScenes();
                updateThings();
            } catch (ApiException | AssetNotLoadedException e) {
                // exceptions should not occur here; but log anyway (just in case)
                logger.warn("childHandlerInitialized() {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public void dispose() {
        logger.debug("dispose() {}", this);
        if (assetsLoaded) {
            assetsLoaded = false;
            scheduler.submit(() -> disposeAssets());
        }
    }

    /**
     * Dispose the bridge handler's assets.
     */
    private void disposeAssets() {
        logger.debug("disposeAssets() {}", this);
        synchronized (this) {
            assetsLoaded = false;
            ScheduledFuture<?> task = checkConnectionTask;
            if (Objects.nonNull(task)) {
                task.cancel(false);
                checkConnectionTask = null;
            }
            ServiceRegistration<?> registration = trustManagerRegistration;
            if (Objects.nonNull(registration)) {
                registration.unregister();
                trustManagerRegistration = null;
            }
            Clip2Bridge bridge = clip2Bridge;
            if (Objects.nonNull(bridge)) {
                bridge.close(); // NB this may take ~15 seconds
                clip2Bridge = null;
            }
        }
    }

    /**
     * Return the application key for the console app.
     *
     * @return the application key.
     */
    public @Nullable String getApplicationKey() {
        Clip2BridgeConfig config = getConfigAs(Clip2BridgeConfig.class);
        return config.applicationKey;
    }

    /**
     * Get the Clip2Bridge connection and throw an exception if it is null.
     *
     * @return the Clip2Bridge.
     * @throws AssetNotLoadedException if the Clip2Bridge is null.
     */
    private Clip2Bridge getClip2Bridge() throws AssetNotLoadedException {
        Clip2Bridge clip2Bridge = this.clip2Bridge;
        if (Objects.nonNull(clip2Bridge)) {
            return clip2Bridge;
        }
        throw new AssetNotLoadedException("Clip2Bridge is null");
    }

    /**
     * Return the ip address for the console app.
     *
     * @return the ip address.
     */
    public @Nullable String getIpAddress() {
        Clip2BridgeConfig config = getConfigAs(Clip2BridgeConfig.class);
        return config.ipAddress;
    }

    /**
     * Get the v1 legacy Hue thing (if any) which has an Id that matches the idV1 attribute of a v2 thing.
     *
     * @param targetIdV1 the idV1 attribute value of a v2 thing.
     * @return Optional result containing the legacy thing (if found).
     */
    public Optional<Thing> getLegacyThing(String targetIdV1) {
        String config;
        if (targetIdV1.startsWith("/lights/")) {
            config = HueBindingConstants.LIGHT_ID;
        } else if (targetIdV1.startsWith("/sensors/")) {
            config = HueBindingConstants.SENSOR_ID;
        } else if (targetIdV1.startsWith("/groups/")) {
            config = HueBindingConstants.GROUP_ID;
        } else {
            config = null;
        }
        if (Objects.nonNull(config)) {
            return thingRegistry.getAll().stream() //
                    .filter(thing -> HueBindingConstants.V1_THING_TYPE_UIDS.contains(thing.getThingTypeUID())) //
                    .filter(thing -> {
                        Object id = thing.getConfiguration().get(config);
                        return id instanceof String && targetIdV1.endsWith("/" + (String) id);
                    }).findFirst();
        }
        return Optional.empty();
    }

    /**
     * Return a localized text.
     *
     * @param key the i18n text key.
     * @param arguments for parameterized translation.
     * @return the localized text.
     */
    public String getLocalizedText(String key, @Nullable Object @Nullable... arguments) {
        String result = translationProvider.getText(bundle, key, key, localeProvider.getLocale(), arguments);
        return Objects.nonNull(result) ? result : key;
    }

    /**
     * Execute an HTTP GET for a resources reference object from the server.
     *
     * @param reference containing the resourceType and (optionally) the resourceId of the resource to get. If the
     *            resourceId is null then all resources of the given type are returned.
     * @return the resource, or null if something fails.
     * @throws ApiException if a communication error occurred.
     * @throws AssetNotLoadedException if one of the assets is not loaded.
     */
    public Resources getResources(ResourceReference reference) throws ApiException, AssetNotLoadedException {
        logger.debug("getResources() {}", reference);
        checkAssetsLoaded();
        return getClip2Bridge().getResources(reference);
    }

    /**
     * Getter for the scheduler.
     *
     * @return the scheduler.
     */
    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(Clip2ThingDiscoveryService.class);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (RefreshType.REFRESH.equals(command)) {
            return;
        }
        logger.warn("handleCommand() bridge thing has no channels.");
    }

    @Override
    public void initialize() {
        logger.debug("initialize() {}", this);
        updateThingFromLegacy();
        updateStatus(ThingStatus.UNKNOWN);
        applKeyRetriesRemaining = APPLICATION_KEY_MAX_TRIES;
        connectRetriesRemaining = RECONNECT_MAX_TRIES;
        scheduler.submit(() -> initializeAssets());
    }

    /**
     * Initialize the bridge handler's assets.
     */
    private void initializeAssets() {
        logger.debug("initializeAssets() {}", this);
        synchronized (this) {
            Clip2BridgeConfig config = getConfigAs(Clip2BridgeConfig.class);

            String ipAddress = config.ipAddress;
            if (Objects.isNull(ipAddress) || ipAddress.isEmpty()) {
                logger.warn("initializeAssets() invalid ip address '{}'", config.ipAddress);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "@text/offline.conf-error-no-ip-address");
                return;
            }

            try {
                if (!Clip2Bridge.isClip2Supported(ipAddress)) {
                    logger.warn("initializeAssets() hub does not support clip 2");
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            "@text/offline.api2.conf-error-clip2-not-supported");
                    return;
                }
            } catch (IOException e) {
                logger.warn("initializeAssets() communication error on '{}'", ipAddress, e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "@text/offline.communication-error");
                return;
            }

            HueTlsTrustManagerProvider trustManagerProvider = new HueTlsTrustManagerProvider(ipAddress + ":443",
                    config.useSelfSignedCertificate);

            if (Objects.isNull(trustManagerProvider.getPEMTrustManager())) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "@text/offline.api2.conf-error-certificate-load");
                return;
            }

            trustManagerRegistration = FrameworkUtil.getBundle(getClass()).getBundleContext()
                    .registerService(TlsTrustManagerProvider.class.getName(), trustManagerProvider, null);

            String applicationKey = config.applicationKey;
            applicationKey = Objects.nonNull(applicationKey) ? applicationKey : "";
            clip2Bridge = new Clip2Bridge(httpClientFactory, this, ipAddress, applicationKey);

            assetsLoaded = true;
        }

        scheduler.submit(() -> checkConnection());
    }

    /**
     * Called when the connection goes offline. Schedule a reconnection.
     */
    public void onConnectionOffline() {
        if (assetsLoaded) {
            scheduler.schedule(() -> checkConnection(), RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Called when the connection goes online. Schedule a general state update.
     */
    public void onConnectionOnline() {
        scheduler.submit(() -> updateOnlineState());
    }

    /**
     * Inform all child thing handlers about the contents of the given resource.
     *
     * @param resource the given resource.
     */
    private void onResource(Resource resource) {
        getThing().getThings().forEach(thing -> {
            ThingHandler handler = thing.getHandler();
            if (handler instanceof Clip2ThingHandler) {
                ((Clip2ThingHandler) handler).onResource(resource);
            }
        });
    }

    /**
     * Called when an SSE event message comes in with a valid list of resources.
     *
     * @param resources a list of incoming resource objects.
     */
    public void onResourcesEvent(List<Resource> resources) {
        if (assetsLoaded) {
            scheduler.submit(() -> {
                logger.debug("onResourcesEvent() resource count {}", resources.size());
                resources.forEach(resource -> onResource(resource));
            });
        }
    }

    /**
     * Execute an HTTP PUT to send a Resource object to the server.
     *
     * @param resource the resource to put.
     * @throws ApiException if a communication error occurred.
     * @throws AssetNotLoadedException if one of the assets is not loaded.
     */
    public void putResource(Resource resource) throws ApiException, AssetNotLoadedException {
        logger.debug("putResource() {}", resource);
        checkAssetsLoaded();
        getClip2Bridge().putResource(resource);
    }

    /**
     * Register the application key with the hub. If the current application key is empty it will create a new one.
     *
     * @throws ApiException if a communication error occurred.
     * @throws AssetNotLoadedException if one of the assets is not loaded.
     * @throws HttpUnauthorizedException if the communication was OK but the registration failed anyway.
     * @throws IllegalStateException if the configuration cannot be changed e.g. read only.
     */
    private void registerApplicationKey()
            throws HttpUnauthorizedException, ApiException, AssetNotLoadedException, IllegalStateException {
        logger.debug("registerApplicationKey()");
        Clip2BridgeConfig config = getConfigAs(Clip2BridgeConfig.class);
        String newApplicationKey = getClip2Bridge().registerApplicationKey(config.applicationKey);
        Configuration configuration = editConfiguration();
        configuration.put(Clip2BridgeConfig.APPLICATION_KEY, newApplicationKey);
        updateConfiguration(configuration);
    }

    /**
     * Register or unregister the discovery service.
     *
     * @param discoveryService new discoveryService, or null if un-registering.
     */
    public void registerDiscoveryService(@Nullable Clip2ThingDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    /**
     * Update the bridge's online state and update its dependent things. Called when the connection goes online.
     */
    private void updateOnlineState() {
        if (assetsLoaded && (thing.getStatus() != ThingStatus.ONLINE)) {
            logger.debug("updateOnlineState()");
            connectRetriesRemaining = RECONNECT_MAX_TRIES;
            updateStatus(ThingStatus.ONLINE);
            try {
                updateScenes();
                updateThings();
                Clip2ThingDiscoveryService discoveryService = this.discoveryService;
                if (Objects.nonNull(discoveryService)) {
                    discoveryService.startScan(null);
                }
            } catch (ApiException | AssetNotLoadedException e) {
                // should never happen as we are already online
            }
        }
    }

    /**
     * Update the bridge thing properties.
     *
     * @throws ApiException if a communication error occurred.
     * @throws AssetNotLoadedException if one of the assets is not loaded.
     */
    private void updateProperties() throws ApiException, AssetNotLoadedException {
        logger.debug("updateProperties()");
        Map<String, String> properties = new HashMap<>(thing.getProperties());

        for (Resource device : getClip2Bridge().getResources(BRIDGE).getResources()) {
            // set the serial number
            String bridgeId = device.getBridgeId();
            if (Objects.nonNull(bridgeId)) {
                properties.put(Thing.PROPERTY_SERIAL_NUMBER, bridgeId);
            }
            break;
        }

        for (Resource device : getClip2Bridge().getResources(DEVICE).getResources()) {
            MetaData metaData = device.getMetaData();
            if (Objects.nonNull(metaData) && metaData.getArchetype() == Archetype.BRIDGE_V2) {
                // set resource properties
                properties.put(HueBindingConstants.PROPERTY_RESOURCE_ID, device.getId());
                properties.put(HueBindingConstants.PROPERTY_RESOURCE_TYPE, device.getType().toString());

                // set metadata properties
                String metaDataName = metaData.getName();
                if (Objects.nonNull(metaDataName)) {
                    properties.put(HueBindingConstants.PROPERTY_RESOURCE_NAME, metaDataName);
                }
                properties.put(HueBindingConstants.PROPERTY_RESOURCE_ARCHETYPE, metaData.getArchetype().toString());

                // set product data properties
                ProductData productData = device.getProductData();
                if (Objects.nonNull(productData)) {
                    // set generic thing properties
                    properties.put(Thing.PROPERTY_MODEL_ID, productData.getModelId());
                    properties.put(Thing.PROPERTY_VENDOR, productData.getManufacturerName());
                    properties.put(Thing.PROPERTY_FIRMWARE_VERSION, productData.getSoftwareVersion());
                    String hardwarePlatformType = productData.getHardwarePlatformType();
                    if (Objects.nonNull(hardwarePlatformType)) {
                        properties.put(Thing.PROPERTY_HARDWARE_VERSION, hardwarePlatformType);
                    }

                    // set hue specific properties
                    properties.put(HueBindingConstants.PROPERTY_PRODUCT_NAME, productData.getProductName());
                    properties.put(HueBindingConstants.PROPERTY_PRODUCT_ARCHETYPE,
                            productData.getProductArchetype().toString());
                    properties.put(HueBindingConstants.PROPERTY_PRODUCT_CERTIFIED,
                            productData.getCertified().toString());
                }
                break; // we only needed the BRIDGE_V2 resource
            }
        }
        thing.setProperties(properties);
    }

    /**
     * Get the full list of scene resources from the bridge, and notify all child things.
     *
     * @throws ApiException if a communication error occurred.
     * @throws AssetNotLoadedException if one of the assets is not loaded.
     */
    private void updateScenes() throws ApiException, AssetNotLoadedException {
        logger.debug("updateScenes()");
        List<Resource> scenes = getClip2Bridge().getResources(SCENE).getResources();
        getThing().getThings().forEach(thing -> {
            ThingHandler handler = thing.getHandler();
            if (handler instanceof Clip2ThingHandler) {
                ((Clip2ThingHandler) handler).updateScenes(scenes);
            }
        });
    }

    /**
     * Update the thing's own state. Called sporadically in case any SSE events may have been lost.
     */
    private void updateSelf() {
        logger.debug("updateSelf()");
        try {
            checkAssetsLoaded();
            updateProperties();
            updateStatus(ThingStatus.UNKNOWN);
            getClip2Bridge().open();
        } catch (ApiException e) {
            logger.debug("updateSelf() {}", e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/offline.communication-error");
        } catch (AssetNotLoadedException e) {
            logger.debug("updateSelf() {}", e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.api2.conf-error-assets-not-loaded");
        } catch (HttpUnauthorizedException e) {
            logger.debug("updateSelf() {}", e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.api2.conf-error-access_denied");
        }
    }

    /**
     * Check if a PROPERTY_LEGACY_THING_UID value was set by the discovery process, and if so, clone the legacy thing's
     * settings into this thing.
     */
    private void updateThingFromLegacy() {
        if (isInitialized()) {
            logger.warn("updateThingFromLegacy() was called after handler was initialized.");
            return;
        }
        Map<String, String> properties = thing.getProperties();
        String legacyThingUID = properties.get(HueBindingConstants.PROPERTY_LEGACY_THING_UID);
        if (Objects.nonNull(legacyThingUID)) {
            Thing legacyThing = thingRegistry.get(new ThingUID(legacyThingUID));
            if (Objects.nonNull(legacyThing)) {
                BridgeBuilder editBuilder = editThing();

                String location = legacyThing.getLocation();
                if (Objects.nonNull(location) && !location.isBlank()) {
                    editBuilder = editBuilder.withLocation(location);
                }

                Object userName = legacyThing.getConfiguration().get(HueBindingConstants.USER_NAME);
                if (userName instanceof String) {
                    Configuration configuration = thing.getConfiguration();
                    configuration.put(Clip2BridgeConfig.APPLICATION_KEY, userName);
                    editBuilder = editBuilder.withConfiguration(configuration);
                }

                Map<String, String> newProperties = new HashMap<>(properties);
                newProperties.remove(HueBindingConstants.PROPERTY_LEGACY_THING_UID);

                updateThing(editBuilder.withProperties(newProperties).build());
            }
        }
    }

    /**
     * Get the data for all things in the bridge, and inform all child thing handlers.
     *
     * @throws ApiException if a communication error occurred.
     * @throws AssetNotLoadedException if one of the assets is not loaded.
     */
    private void updateThings() throws ApiException, AssetNotLoadedException {
        logger.debug("updateThings()");
        Clip2Bridge bridge = getClip2Bridge();
        for (ResourceReference reference : POLL_RESOURCE_SET) {
            bridge.getResources(reference).getResources().forEach(resource -> onResource(resource));
        }
    }
}