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
package org.openhab.binding.hue.internal.clip2.console;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.HueBindingConstants;
import org.openhab.binding.hue.internal.clip2.dto.MetaData;
import org.openhab.binding.hue.internal.clip2.dto.ProductData;
import org.openhab.binding.hue.internal.clip2.dto.Resource;
import org.openhab.binding.hue.internal.clip2.dto.ResourceReference;
import org.openhab.binding.hue.internal.clip2.dto.Resources;
import org.openhab.binding.hue.internal.clip2.enums.ResourceType;
import org.openhab.binding.hue.internal.clip2.handler.Clip2BridgeHandler;
import org.openhab.core.io.console.Console;
import org.openhab.core.io.console.ConsoleCommandCompleter;
import org.openhab.core.io.console.StringsCompleter;
import org.openhab.core.io.console.extensions.AbstractConsoleCommandExtension;
import org.openhab.core.io.console.extensions.ConsoleCommandExtension;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.binding.ThingHandler;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The {@link HueCommandExtension} is responsible for handling console commands
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
@Component(service = ConsoleCommandExtension.class)
public class HueCommandExtension extends AbstractConsoleCommandExtension implements ConsoleCommandCompleter {

    private static final String SHOW_IDS = "showIds";
    private static final StringsCompleter SUBCMD_COMPLETER = new StringsCompleter(List.of(SHOW_IDS), false);

    private final ThingRegistry thingRegistry;

    @Activate
    public HueCommandExtension(final @Reference ThingRegistry thingRegistry) {
        super(HueBindingConstants.BINDING_ID, "Interact with the Philips Hue binding.");
        this.thingRegistry = thingRegistry;
    }

    @Override
    public void execute(String[] args, Console console) {
        if (args.length != 1 || !SHOW_IDS.equals(args[0])) {
            printUsage(console);
            return;
        }

        for (Thing thing : thingRegistry.getAll()) {
            ThingHandler thingHandler = thing.getHandler();

            if (thingHandler instanceof Clip2BridgeHandler) {
                console.println(String.format("CLIP 2 API bridge: { %s }", thing.getLabel()));

                for (ResourceType resourceType : Set.of(ResourceType.DEVICE, ResourceType.SCENE)) {
                    ResourceReference resourceReference = new ResourceReference().setType(resourceType);

                    Resources resources = ((Clip2BridgeHandler) thingHandler).getResources(resourceReference);
                    if (resources != null) {
                        List<Resource> resourceList = resources.getResources();

                        if (!resourceList.isEmpty()) {
                            console.println(String.format(" - %ss:", resourceType.toString()));

                            for (Resource resource : resourceList) {
                                MetaData metaData = resource.getMetaData();
                                ProductData productData = resource.getProductData();

                                String resourceName = metaData != null ? metaData.getName()
                                        : productData != null ? productData.getProductName() : "??";

                                console.println(String.format("    - ID: %s { %s }", resource.getId(), resourceName));
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public List<String> getUsages() {
        return Arrays.asList(buildCommandUsage(SHOW_IDS, "list all CLIP 2 devices and scenes"));
    }

    @Override
    public @Nullable ConsoleCommandCompleter getCompleter() {
        return this;
    }

    @Override
    public boolean complete(String[] args, int cursorArgumentIndex, int cursorPosition, List<String> candidates) {
        if (cursorArgumentIndex <= 0) {
            return SUBCMD_COMPLETER.complete(args, cursorArgumentIndex, cursorPosition, candidates);
        }
        return false;
    }
}
