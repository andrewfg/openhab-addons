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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.HueBindingConstants;
import org.openhab.binding.hue.internal.config.ResourceConfig;
import org.openhab.binding.hue.internal.dto.clip2.ColorTemperature2;
import org.openhab.binding.hue.internal.dto.clip2.MetaData;
import org.openhab.binding.hue.internal.dto.clip2.MirekSchema;
import org.openhab.binding.hue.internal.dto.clip2.ProductData;
import org.openhab.binding.hue.internal.dto.clip2.Resource;
import org.openhab.binding.hue.internal.dto.clip2.ResourceReference;
import org.openhab.binding.hue.internal.dto.clip2.Resources;
import org.openhab.binding.hue.internal.dto.clip2.enums.ResourceType;
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
 * Handler for things based on CLIP 2 device resources.
 *
 * @author Andrew Fiddian-Green - Initial contribution.
 */
@NonNullByDefault
public class DeviceThingHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(DeviceThingHandler.class);

    private Resource thisResource = new Resource(null);
    private final Map<String, Resource> contributorsCache = new ConcurrentHashMap<>();
    private final Map<ResourceType, String> commandResourceIds = new ConcurrentHashMap<>();
    private final Map<String, Integer> controlIds = new ConcurrentHashMap<>();
    private final Set<String> supportedChannelIds = ConcurrentHashMap.newKeySet(16); // 16 = thing max channel count

    private boolean disposing;

    public DeviceThingHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void dispose() {
        disposing = true;
    }

    /**
     * Get all resources needed for building the thing state. Build the forward / reverse contributor lookup maps. Set
     * up the final list of channels in the thing.
     */
    private void getAllResources() {
        if (!disposing) {
            getPrimaryResource();
            setLookups();
            getContributorsCache();
            setChannels();
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
     * Execute a series of HTTP GET commands to fetch the cached resource data for all resources that contribute to the
     * thing state.
     */
    private void getContributorsCache() {
        if (!disposing) {
            logger.debug("getContributorsCache() called for {} contributors", contributorsCache.size());
            ResourceReference reference = new ResourceReference();
            for (Entry<String, Resource> entry : contributorsCache.entrySet()) {
                getResources(reference.setId(entry.getKey()).setType(entry.getValue().getType()));
            }
        }
    }

    /**
     * Execute a series of HTTP GET commands for device or scene resource types to fetch the primary resource data for
     * the thing state.
     */
    private void getPrimaryResource() {
        if (!disposing) {
            logger.debug("getPrimaryResource() called");
            ResourceReference reference = new ResourceReference().setId(thisResource.getId());
            for (ResourceType resourceType : Set.of(ResourceType.DEVICE, ResourceType.SCENE)) {
                getResources(reference.setType(resourceType));
            }
        }
    }

    /**
     * Execute an HTTP GET command to fetch the resources data for referenced resource.
     *
     * @param reference to the required resource.
     */
    private synchronized void getResources(ResourceReference reference) {
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
                    notifyResource(resource);
                }
            }
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (RefreshType.REFRESH.equals(command)) {
            scheduler.submit(this::getContributorsCache);
            return;
        }

        Clip2BridgeHandler handler = getBridgeHandler();
        if (handler == null) {
            return;
        }

        Resource newResource;
        switch (channelUID.getId()) {
            case HueBindingConstants.CHANNEL_COLORTEMPERATURE:
                newResource = new Resource(ResourceType.LIGHT).setColorTemperaturePercent(command,
                        mirekSchemaFrom(ResourceType.LIGHT));
                break;

            case HueBindingConstants.CHANNEL_COLORTEMPERATURE_ABS:
                newResource = new Resource(ResourceType.LIGHT).setColorTemperatureKelvin(command);
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

            default:
                return; // <= nota bene !!
        }

        String resourceId = commandResourceIds.get(newResource.getType());
        if (resourceId != null) {
            handler.putResource(newResource.setId(resourceId));
        }
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
        contributorsCache.clear();
        controlIds.clear();
        disposing = false;

        scheduler.submit(this::getAllResources);
    }

    /**
     * Check the if the given resource has a MirekSchema, and if not check the contributors cache to see if it contains
     * a resource that matches the passed resource's id. In either case return that respective schema. And if not,
     * return a default schema comprising the default static mirek MIN and MAX constant values.
     *
     * @param resource the reference resource.
     * @return the MirekSchema.
     */
    private MirekSchema mirekSchemaFrom(Resource resource) {
        MirekSchema schema = resource.getMirekSchema();
        if (schema == null) {
            Resource cacheResource = contributorsCache.get(resource.getId());
            if (cacheResource != null) {
                ColorTemperature2 colorTemperature = cacheResource.getColorTemperature();
                if (colorTemperature != null) {
                    schema = colorTemperature.getMirekSchema();
                }
            }
        }
        return schema != null ? schema : new MirekSchema();
    }

    /**
     * Check the commandResourceIds to see if we have a command resource id for the given resource type, and if so
     * return its respective MirekSchema, and if not return a default schema comprising the default static mirek MIN and
     * MAX constant values.
     *
     * @param resourceType the reference resource type.
     * @return the MirekSchema.
     */
    private MirekSchema mirekSchemaFrom(ResourceType resourceType) {
        String resourceId = commandResourceIds.get(resourceType);
        if (resourceId != null) {
            return mirekSchemaFrom(new Resource(resourceType).setId(resourceId));
        }
        return new MirekSchema();
    }

    /**
     * Update the channel state depending on a new resource received from the bridge.
     *
     * @param newResource the resource containing the new state.
     */
    public synchronized void notifyResource(Resource newResource) {
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
            properties.put(HueBindingConstants.PROPERTY_RESOURCE_NAME, thisResource.getName());

            // metadata
            MetaData metaData = thisResource.getMetaData();
            if (metaData != null) {
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

        Resource contributorResource = contributorsCache.get(newResource.getId());
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
                    updateState(HueBindingConstants.CHANNEL_COLORTEMPERATURE,
                            newResource.getColorTemperaturePercentState(mirekSchemaFrom(newResource)), fullUpdate);
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
            contributorsCache.put(contributorResource.getId(), newResource);
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

    /**
     * Initialize the lookup maps of resources that contribute to the thing state.
     */
    private void setLookups() {
        if (!disposing) {
            List<ResourceReference> references = thisResource.getServiceReferences();
            contributorsCache.clear();
            commandResourceIds.clear();
            contributorsCache.putAll(references.stream()
                    .collect(Collectors.toMap(ResourceReference::getId, r -> new Resource(r.getType()))));
            commandResourceIds.putAll( // use a 'mergeFunction' to prevent duplicates
                    references.stream().collect(
                            Collectors.toMap(ResourceReference::getType, ResourceReference::getId, (r1, r2) -> r1)));
        }
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
}
