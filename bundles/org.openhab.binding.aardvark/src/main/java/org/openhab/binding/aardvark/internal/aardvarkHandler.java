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
package org.openhab.binding.aardvark.internal;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link aardvarkHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class aardvarkHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(aardvarkHandler.class);

    private int value = 5;

    private @Nullable ScheduledFuture<?> task;

    public aardvarkHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            logger.warn("handleCommand() REFRESH");
            return;
        }
        logger.warn("handleCommand() UNEXPECTEDLY CALLED !!");
    }

    @Override
    public void initialize() {
        updateStatus(ThingStatus.ONLINE);
        task = scheduler.scheduleWithFixedDelay(() -> {
            updateState(aardvarkBindingConstants.CHANNEL_1, new PercentType(value));
            value++;
            if (value > 100) {
                value = 5;
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> task = this.task;
        if (task != null) {
            task.cancel(true);
        }
        this.task = null;
        super.dispose();
    }
}
