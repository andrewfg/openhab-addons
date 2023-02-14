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
package org.openhab.binding.hue.internal.discovery;

import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.HueBindingConstants;
import org.openhab.binding.hue.internal.dto.clip2.MetaData;
import org.openhab.binding.hue.internal.dto.clip2.Resource;
import org.openhab.binding.hue.internal.dto.clip2.ResourceReference;
import org.openhab.binding.hue.internal.dto.clip2.enums.Archetype;
import org.openhab.binding.hue.internal.dto.clip2.enums.ResourceType;
import org.openhab.binding.hue.internal.exceptions.ApiException;
import org.openhab.binding.hue.internal.exceptions.AssetNotLoadedException;
import org.openhab.binding.hue.internal.handler.Clip2BridgeHandler;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;

/**
 * Discovery service to find resource things on a Hue Bridge that is running CLIP 2.
 *
 * @author Andrew Fiddian-Green - Initial Contribution
 */
@NonNullByDefault
public class Clip2ThingDiscoveryService extends AbstractDiscoveryService implements ThingHandlerService {

    public static final int DISCOVERY_TIMEOUT_SECONDS = 20;
    public static final int DISCOVERY_INTERVAL_SECONDS = 600;

    private @Nullable ScheduledFuture<?> discoveryTask;
    private @Nullable Clip2BridgeHandler clip2BridgeHandler;

    public Clip2ThingDiscoveryService() {
        super(Set.of(HueBindingConstants.THING_TYPE_DEVICE), DISCOVERY_TIMEOUT_SECONDS, true);
    }

    @Override
    public void activate() {
        Clip2BridgeHandler clip2BridgeHandler = this.clip2BridgeHandler;
        if (Objects.nonNull(clip2BridgeHandler)) {
            clip2BridgeHandler.registerDiscoveryService(this);
        }
        super.activate(null);
    }

    @Override
    public void deactivate() {
        super.deactivate();
        Clip2BridgeHandler clip2BridgeHandler = this.clip2BridgeHandler;
        if (Objects.nonNull(clip2BridgeHandler)) {
            clip2BridgeHandler.registerDiscoveryService(null);
            removeOlderResults(new Date().getTime(), clip2BridgeHandler.getThing().getBridgeUID());
        }
    }

    /**
     * If the bridge is online, then query it to get all resource types within it, which are allowed to be instantiated
     * as OH things, and announce those respective things by calling the core 'thingDiscovered()' method.
     */
    private synchronized void discoverDevices() {
        Clip2BridgeHandler clip2BridgeHandler = this.clip2BridgeHandler;
        if (Objects.nonNull(clip2BridgeHandler) && clip2BridgeHandler.getThing().getStatus() == ThingStatus.ONLINE) {
            try {
                ThingUID bridgeUID = clip2BridgeHandler.getThing().getUID();
                for (Resource resource : clip2BridgeHandler
                        .getResources(new ResourceReference().setType(ResourceType.DEVICE)).getResources()) {

                    MetaData metaData = resource.getMetaData();
                    if (Objects.nonNull(metaData)) {
                        if (metaData.getArchetype() == Archetype.BRIDGE_V2) {
                            // the bridge device resource itself is already in the bridge thing handler
                            continue;
                        }

                        String resId = resource.getId();
                        String resType = resource.getType().toString();
                        String label = resource.getName();

                        Optional<Thing> legacyThing = getLegacyThing(resource.getIdV1());
                        if (legacyThing.isPresent()) {
                            String label2 = legacyThing.get().getLabel();
                            label = Objects.nonNull(label2) ? label2 : label;
                        }

                        DiscoveryResult thing = DiscoveryResultBuilder
                                .create(new ThingUID(HueBindingConstants.THING_TYPE_DEVICE, bridgeUID, resId))
                                .withBridge(bridgeUID) //
                                .withLabel(label) //
                                .withProperty(HueBindingConstants.PROPERTY_RESOURCE_ID, resId)
                                .withProperty(HueBindingConstants.PROPERTY_RESOURCE_TYPE, resType)
                                .withProperty(HueBindingConstants.PROPERTY_RESOURCE_NAME, label)
                                .withRepresentationProperty(HueBindingConstants.PROPERTY_RESOURCE_ID) //
                                .build();

                        thingDiscovered(thing);
                    }
                }
            } catch (ApiException | AssetNotLoadedException e) {
                // bridge is offline or in a bad state
            }
        }
        stopScan();
    }

    /**
     * Get the v1 legacy Hue thing (if any) which has an Id that matches the idV1 attribute of a v2 thing.
     *
     * @param targetId the idV1 attribute value of a v2 thing.
     * @return Optional result containing the legacy thing (if found).
     */
    private Optional<Thing> getLegacyThing(String targetId) {
        String config;
        if (targetId.startsWith("/lights/")) {
            config = HueBindingConstants.LIGHT_ID;
        } else if (targetId.startsWith("/sensors/")) {
            config = HueBindingConstants.SENSOR_ID;
        } else {
            config = null;
        }
        if (Objects.nonNull(config)) {
            Clip2BridgeHandler clip2BridgeHandler = this.clip2BridgeHandler;
            if (Objects.nonNull(clip2BridgeHandler)) {
                return clip2BridgeHandler.getThingRegistry().getAll().stream() //
                        .filter(thing -> HueBindingConstants.V1_THING_TYPE_UIDS.contains(thing.getThingTypeUID())) //
                        .filter(thing -> {
                            Object id = thing.getConfiguration().get(config);
                            return id instanceof String && targetId.endsWith((String) id);
                        }).findFirst();
            }
        }
        return Optional.empty();
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return clip2BridgeHandler;
    }

    @Override
    public void setThingHandler(ThingHandler handler) {
        if (handler instanceof Clip2BridgeHandler) {
            clip2BridgeHandler = (Clip2BridgeHandler) handler;
        }
    }

    @Override
    protected void startBackgroundDiscovery() {
        ScheduledFuture<?> discoveryTask = this.discoveryTask;
        if (Objects.isNull(discoveryTask) || discoveryTask.isCancelled()) {
            this.discoveryTask = scheduler.scheduleWithFixedDelay(this::discoverDevices, 0, DISCOVERY_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
        }
    }

    @Override
    protected void startScan() {
        scheduler.execute(this::discoverDevices);
    }

    @Override
    protected void stopBackgroundDiscovery() {
        ScheduledFuture<?> discoveryTask = this.discoveryTask;
        if (Objects.nonNull(discoveryTask)) {
            discoveryTask.cancel(true);
            this.discoveryTask = null;
        }
    }
}
