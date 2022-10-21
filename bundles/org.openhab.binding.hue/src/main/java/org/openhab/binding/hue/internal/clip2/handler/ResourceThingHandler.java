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
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.HueBindingConstants;
import org.openhab.binding.hue.internal.clip2.config.ResourceConfig;
import org.openhab.binding.hue.internal.clip2.dto.MetaData;
import org.openhab.binding.hue.internal.clip2.dto.ProductData;
import org.openhab.binding.hue.internal.clip2.dto.Reference;
import org.openhab.binding.hue.internal.clip2.dto.Resource;
import org.openhab.binding.hue.internal.clip2.dto.Resources;
import org.openhab.binding.hue.internal.clip2.enums.ResourceType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for things based on CLIP 2 resources.
 *
 * @author Andrew Fiddian-Green - Initial contribution.
 */
@NonNullByDefault
public class ResourceThingHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(ResourceThingHandler.class);

    private Resource thisResource = new Resource(null);
    private final Map<String, Resource> contributorResources = new ConcurrentHashMap<>();
    private final Map<ResourceType, String> commandResourceIds = new ConcurrentHashMap<>();
    private final Map<String, Integer> controlIds = new ConcurrentHashMap<>();
    private final Set<String> supportedChannelIds = ConcurrentHashMap.newKeySet(16); // 16 = thing max channel count

    private boolean disposing;

    public ResourceThingHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        ResourceConfig config = getConfigAs(ResourceConfig.class);

        String resourceId = config.resourceId;
        if (resourceId == null || resourceId.isEmpty()) {
            logger.debug("initialize() configuration resourceId is bad");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "@text/TODO");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN);

        thisResource.setId(resourceId);
        supportedChannelIds.clear();
        commandResourceIds.clear();
        contributorResources.clear();
        controlIds.clear();
        disposing = false;

        scheduler.submit(this::getAllResources);
    }

    @Override
    public void dispose() {
        disposing = true;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (RefreshType.REFRESH.equals(command)) {
            scheduler.submit(this::getContributorResources);
            return;
        }

        Clip2BridgeHandler handler = getBridgeHandler();
        if (handler == null) {
            return;
        }

        Resource newResource;
        switch (channelUID.getId()) {
            case HueBindingConstants.CHANNEL_COLORTEMPERATURE:
                newResource = new Resource(ResourceType.LIGHT).setColorTemperature(command);
                break;

            case HueBindingConstants.CHANNEL_COLOR:
                newResource = new Resource(ResourceType.LIGHT).setColor(command);
                break;

            case HueBindingConstants.CHANNEL_BRIGHTNESS:
                newResource = new Resource(ResourceType.LIGHT).setBrightness(command);
                break;

            case HueBindingConstants.CHANNEL_SWITCH:
                newResource = new Resource(ResourceType.LIGHT).setSwitch(command);
                break;

            case HueBindingConstants.CHANNEL_TEMPERATURE_ENABLED:
                newResource = new Resource(ResourceType.TEMPERATURE).setEnabled(command);
                break;

            case HueBindingConstants.CHANNEL_MOTION_ENABLED:
                newResource = new Resource(ResourceType.MOTION).setEnabled(command);
                break;

            case HueBindingConstants.CHANNEL_LIGHT_LEVEL_ENABLED:
                newResource = new Resource(ResourceType.LIGHT_LEVEL).setEnabled(command);
                break;

            case HueBindingConstants.CHANNEL_SCENE:
                if (command == OnOffType.ON) {
                    newResource = new Resource(ResourceType.SCENE).setRecall(command);
                    scheduler.schedule(() -> updateState(HueBindingConstants.CHANNEL_SCENE, OnOffType.OFF), 3,
                            TimeUnit.SECONDS);
                    break;
                }
                return; // <= nota bene !!

            default:
                return; // <= nota bene !!
        }

        String resourceId = commandResourceIds.get(newResource.getType());
        if (resourceId != null) {
            handler.putResource(newResource.setId(resourceId));
        }
    }

    /**
     * Update the channel state depending on a new resource received from the bridge.
     *
     * @param newResource the resource containing the new state.
     */
    public synchronized void notify(Resource newResource) {
        if (disposing) {
            return;
        }

        if ((thing.getStatus() != ThingStatus.ONLINE) && thisResource.getId().equals(newResource.getId())
                && (newResource.getType() != thisResource.getType())) {
            //
            logger.debug("notify() going online");

            // update this resource current state
            thisResource = newResource;

            if (thisResource.getType() == ResourceType.SCENE) {
                updateState(HueBindingConstants.CHANNEL_SCENE, OnOffType.OFF, true);
            }

            // actualise the properties
            Map<String, String> properties = new HashMap<>();

            // resource data
            properties.put(HueBindingConstants.PROPERTY_RESOURCE_ID, thisResource.getId());
            properties.put(HueBindingConstants.PROPERTY_RESOURCE_TYPE, thisResource.getType().toString());

            // metadata
            MetaData metaData = thisResource.getMetaData();
            if (metaData != null) {
                properties.put(HueBindingConstants.PROPERTY_RESOURCE_NAME, metaData.getName());
                properties.put(HueBindingConstants.PROPERTY_RESOURCE_ARCHETYPE, metaData.getArchetype().toString());
            }

            // product data
            ProductData productData = thisResource.getProductData();
            if (productData != null) {
                // standard properties
                properties.put(Thing.PROPERTY_MODEL_ID, productData.getModelId());
                properties.put(Thing.PROPERTY_VENDOR, productData.getManufacturerName());
                properties.put(Thing.PROPERTY_FIRMWARE_VERSION, productData.getSoftwareVersion().toString());
                String hardwarePlatformType = productData.getHardwarePlatformType();
                if (hardwarePlatformType != null) {
                    properties.put(Thing.PROPERTY_HARDWARE_VERSION, hardwarePlatformType);
                }

                // hue specific properties
                properties.put(HueBindingConstants.PROPERTY_PRODUCT_NAME, productData.getProductName());
                properties.put(HueBindingConstants.PROPERTY_PRODUCT_ARCHETYPE,
                        productData.getProductArchetype().toString());
                properties.put(HueBindingConstants.PROPERTY_PRODUCT_CERTIFIED, productData.getCertified().toString());
            }
            thing.setProperties(properties);

            updateStatus(ThingStatus.ONLINE);
            return;
        }

        Resource contributorResource = contributorResources.get(newResource.getId());
        if (contributorResource != null) {
            //
            logger.trace("notify() updating channels");

            boolean fullUpdate = newResource.hasFullState();
            switch (newResource.getType()) {
                case BUTTON:
                    supportedChannelIds.add(HueBindingConstants.CHANNEL_BUTTON_LAST_EVENT);
                    newResource.putControlId(controlIds);
                    updateState(HueBindingConstants.CHANNEL_BUTTON_LAST_EVENT,
                            newResource.getButtonEventState(controlIds), fullUpdate);
                    break;

                case DEVICE_POWER:
                    updateState(HueBindingConstants.CHANNEL_BATTERY_LEVEL, newResource.getBatteryLevelState(),
                            fullUpdate);
                    updateState(HueBindingConstants.CHANNEL_BATTERY_LOW, newResource.getBatteryLowState(), fullUpdate);
                    break;

                case LIGHT:
                    updateState(HueBindingConstants.CHANNEL_COLORTEMPERATURE, newResource.getColorTemperatureState(),
                            fullUpdate);
                    updateState(HueBindingConstants.CHANNEL_COLORTEMPERATURE_ABS,
                            newResource.getColorTemperatureKelvinState(), fullUpdate);
                    updateState(HueBindingConstants.CHANNEL_COLOR, newResource.getColorState(), fullUpdate);
                    updateState(HueBindingConstants.CHANNEL_BRIGHTNESS, newResource.getBrightnessState(), fullUpdate);
                    updateState(HueBindingConstants.CHANNEL_SWITCH, newResource.getSwitch(), fullUpdate);
                    break;

                case LIGHT_LEVEL:
                    updateState(HueBindingConstants.CHANNEL_LIGHT_LEVEL, newResource.getLightLevelState(), fullUpdate);
                    updateState(HueBindingConstants.CHANNEL_LIGHT_LEVEL_ENABLED, newResource.getEnabledState(),
                            fullUpdate);
                    break;

                case MOTION:
                    updateState(HueBindingConstants.CHANNEL_MOTION, newResource.getMotionState(), fullUpdate);
                    updateState(HueBindingConstants.CHANNEL_MOTION_ENABLED, newResource.getEnabledState(), fullUpdate);
                    break;

                case TEMPERATURE:
                    updateState(HueBindingConstants.CHANNEL_TEMPERATURE, newResource.getTemperatureState(), fullUpdate);
                    updateState(HueBindingConstants.CHANNEL_TEMPERATURE_ENABLED, newResource.getEnabledState(),
                            fullUpdate);
                    break;

                case ZIGBEE_CONNECTIVITY:
                    updateState(HueBindingConstants.CHANNEL_ZIGBEE_STATUS, newResource.getZigBeeState(), fullUpdate);
                    break;

                default:
                    return; // <= nota bene !!
            }

            // update contributor resource current state
            contributorResources.put(contributorResource.getId(), newResource);
        }
    }

    /**
     * Get the bridge handler.
     *
     * @return the bridge handler or null if the bridge is bad.
     */
    private @Nullable Clip2BridgeHandler getBridgeHandler() {
        Bridge bridge = getBridge();
        if (bridge != null) {
            BridgeHandler handler = bridge.getHandler();
            if (handler instanceof Clip2BridgeHandler) {
                return (Clip2BridgeHandler) handler;
            }
        }
        logger.debug("getBridgeHandler() bridge handler missing");
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED);
        return null;
    }

    /**
     * Update the channel state, and if appropriate add the channel id to the set of supportedChannelIds.
     *
     * @param channelID the id of the channel.
     * @param state the new state of the channel.
     * @param fullUpdate if true always update the channel, otherwise only update if state is not 'UNDEF'.
     */
    private void updateState(String channelID, State state, boolean fullUpdate) {
        logger.trace("updateState() channelID:{}, state:{}, fullUpdate:{}", channelID, state, fullUpdate);
        boolean isDefined = state != UnDefType.UNDEF;
        if (fullUpdate || isDefined) {
            updateState(channelID, state);
        }
        if (fullUpdate && isDefined) {
            supportedChannelIds.add(channelID);
        }
    }

    /**
     * Get all resources needed for building the thing state. Build the forward / reverse contributor lookup maps. Set
     * up the final list of channels in the thing.
     */
    private void getAllResources() {
        if (!disposing) {
            getPrimaryResources();
            setLookups();
            getContributorResources();
            setChannels();
        }
    }

    /**
     * Execute a series of HTTP GET commands for device / scene resource types to fetch the primary resource data for
     * the thing state.
     */
    private void getPrimaryResources() {
        if (!disposing) {
            logger.debug("getPrimaryResources() called");
            Reference reference = new Reference().setId(thisResource.getId());
            for (ResourceType resourceType : Set.of(ResourceType.DEVICE, ResourceType.SCENE)) {
                getResources(reference.setType(resourceType));
            }
        }
    }

    /**
     * Execute a series of HTTP GET commands to fetch the resource data for all resources that contribute to the thing
     * state.
     */
    private void getContributorResources() {
        if (!disposing) {
            logger.debug("getContributorResources() called for {} contributors", contributorResources.size());
            Reference reference = new Reference();
            for (Entry<String, Resource> entry : contributorResources.entrySet()) {
                getResources(reference.setId(entry.getKey()).setType(entry.getValue().getType()));
            }
        }
    }

    /**
     * Execute an HTTP GET command to fetch the resources data for referenced resource.
     *
     * @param reference to the required resource.
     */
    private synchronized void getResources(Reference reference) {
        if (!disposing) {
            Clip2BridgeHandler handler = getBridgeHandler();
            if (handler == null) {
                return;
            }
            logger.trace("getResources() called");
            Resources resources = handler.getResources(reference);
            if (resources != null) {
                List<Resource> resourceList = resources.getResources();
                for (Resource resource : resourceList) {
                    notify(resource);
                }
            }
        }
    }

    /**
     * Initialize the lookup maps of resources that contribute to the thing state.
     */
    private void setLookups() {
        if (!disposing) {
            List<Reference> references = thisResource.getServiceReferences();
            contributorResources.clear();
            commandResourceIds.clear();
            contributorResources.putAll(
                    references.stream().collect(Collectors.toMap(Reference::getId, r -> new Resource(r.getType()))));
            commandResourceIds.putAll( // use a 'mergeFunction' to prevent duplicates
                    references.stream()
                            .collect(Collectors.toMap(Reference::getType, Reference::getId, (r1, r2) -> r1)));
        }
    }

    /**
     * Set the active list of channels by removing any that had initially been created by the thing XML declaration, but
     * which in fact did not have data returned from the bridge (i.e. channels which are not in the supportedChannelIds
     * set).
     */
    private void setChannels() {
        if (!disposing) {
            for (Channel channel : thing.getChannels()) {
                String channelId = channel.getUID().getId();
                if (!supportedChannelIds.contains(channelId)) {
                    logger.debug("setChannels() unused channel '{}' removed from {}", channelId, thing.getUID());
                    updateThing(editThing().withoutChannels(channel).build());
                }
            }
        }
    }
}
