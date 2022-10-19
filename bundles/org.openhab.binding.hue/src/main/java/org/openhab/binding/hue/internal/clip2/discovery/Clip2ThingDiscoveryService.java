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
package org.openhab.binding.hue.internal.clip2.discovery;

import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.HueBindingConstants;
import org.openhab.binding.hue.internal.clip2.dto.MetaData;
import org.openhab.binding.hue.internal.clip2.dto.Reference;
import org.openhab.binding.hue.internal.clip2.dto.Resource;
import org.openhab.binding.hue.internal.clip2.dto.Resources;
import org.openhab.binding.hue.internal.clip2.enums.Archetype;
import org.openhab.binding.hue.internal.clip2.enums.ResourceType;
import org.openhab.binding.hue.internal.clip2.handler.Clip2BridgeHandler;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingUID;

/**
 * Discovery service to find resource things on a Hue Bridge that is running CLIP 2.
 *
 * @author Andrew Fiddian-Green - Initial Contribution
 */
@NonNullByDefault
public class Clip2ThingDiscoveryService extends AbstractDiscoveryService {

    public static final int DISCOVERY_TIMEOUT = 10; // seconds
    public static final int DISCOVERY_START_DELAY = 30;// seconds
    public static final int DISCOVERY_REFRESH_PERIOD = 600; // seconds

    private final Clip2BridgeHandler bridgeHandler;
    private @Nullable ScheduledFuture<?> discoveryTask;

    public Clip2ThingDiscoveryService(Clip2BridgeHandler bridgeHandler) {
        super(Set.of(HueBindingConstants.THING_TYPE_UID_RESOURCE), DISCOVERY_TIMEOUT);
        this.bridgeHandler = bridgeHandler;
    }

    public void activate() {
        super.activate(null);
    }

    @Override
    public void deactivate() {
        super.deactivate();
    }

    @Override
    protected void startScan() {
        discoverDevices();
    }

    @Override
    protected void startBackgroundDiscovery() {
        ScheduledFuture<?> discoveryTask = this.discoveryTask;
        if (discoveryTask == null || discoveryTask.isCancelled()) {
            this.discoveryTask = scheduler.scheduleWithFixedDelay(this::discoverDevices, DISCOVERY_START_DELAY,
                    DISCOVERY_REFRESH_PERIOD, TimeUnit.SECONDS);
        }
    }

    @Override
    protected void stopBackgroundDiscovery() {
        ScheduledFuture<?> discoveryTask = this.discoveryTask;
        if (discoveryTask != null) {
            discoveryTask.cancel(true);
            this.discoveryTask = null;
        }
    }

    /**
     * If the bridge is online, then query it to get all resource types within it, which are allowed to be instantiated
     * as OH things, and announce those respective things by calling the core 'thingDiscovered()' method.
     */
    private synchronized void discoverDevices() {
        if (bridgeHandler.getThing().getStatus() != ThingStatus.ONLINE) {
            return;
        }

        ThingUID bridgeUID = bridgeHandler.getThing().getUID();
        for (ResourceType resourceType : Set.of(ResourceType.DEVICE, ResourceType.SCENE)) {
            Resources resources = bridgeHandler.getResources(new Reference().setType(resourceType));
            if (resources != null) {
                for (Resource resource : resources.getResources()) {
                    MetaData metaData = resource.getMetaData();
                    if (metaData != null) {
                        if (metaData.getArchetype() == Archetype.BRIDGE_V2) {
                            // the bridge device resource itself is already in the bridge thing handler
                            continue;
                        }
                        String resId = resource.getId();
                        String resType = resource.getType().toString();
                        String label = metaData.getName();
                        ThingUID thingUID = new ThingUID(HueBindingConstants.THING_TYPE_UID_RESOURCE, bridgeUID, resId);

                        DiscoveryResult thing = DiscoveryResultBuilder.create(thingUID).withBridge(bridgeUID)
                                .withLabel(label).withProperty(HueBindingConstants.PROPERTY_RESOURCE_ID, resId)
                                .withProperty(HueBindingConstants.PROPERTY_RESOURCE_TYPE, resType)
                                .withProperty(HueBindingConstants.PROPERTY_RESOURCE_NAME, label)
                                .withRepresentationProperty(HueBindingConstants.PROPERTY_RESOURCE_ID).build();

                        thingDiscovered(thing);
                    }
                }
            }
        }
    }
}
