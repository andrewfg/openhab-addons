/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
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
package org.openhab.binding.shelly.internal.handler;

import static org.openhab.binding.shelly.internal.ShellyBindingConstants.*;
import static org.openhab.binding.shelly.internal.api1.Shelly1ApiJsonDTO.*;
import static org.openhab.binding.shelly.internal.handler.RGBW.*;
import static org.openhab.binding.shelly.internal.util.ShellyUtils.*;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.openhab.binding.shelly.internal.api.ShellyApiException;
import org.openhab.binding.shelly.internal.api.ShellyDeviceProfile;
import org.openhab.binding.shelly.internal.api1.Shelly1ApiJsonDTO.ShellySettingsRgbwLight;
import org.openhab.binding.shelly.internal.api1.Shelly1ApiJsonDTO.ShellySettingsStatus;
import org.openhab.binding.shelly.internal.api1.Shelly1ApiJsonDTO.ShellyStatusLight;
import org.openhab.binding.shelly.internal.api1.Shelly1ApiJsonDTO.ShellyStatusLightChannel;
import org.openhab.binding.shelly.internal.api1.Shelly1CoapServer;
import org.openhab.binding.shelly.internal.config.ShellyBindingRuntimeConfig;
import org.openhab.binding.shelly.internal.provider.ShellyChannelDefinitions;
import org.openhab.binding.shelly.internal.provider.ShellyTranslationProvider;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ShellyLightHandler} handles light (Bulb, Duo and RGBW2) specific commands and status. All other commands
 * will be routet of the ShellyBaseHandler.
 *
 * @author Markus Michels - Initial contribution
 */
@NonNullByDefault
public class ShellyLightHandler extends ShellyBaseHandler {
    private final Logger logger = LoggerFactory.getLogger(ShellyLightHandler.class);
    private final Map<Integer, ShellyLightModel> lightModels;

    public ShellyLightHandler(final Thing thing, final ShellyTranslationProvider translationProvider,
            final ShellyBindingRuntimeConfig bindingConfig, final ShellyThingTable thingTable,
            final Shelly1CoapServer coapServer, final HttpClient httpClient, WebSocketClient webSocketClient) {
        super(thing, translationProvider, bindingConfig, thingTable, coapServer, httpClient, webSocketClient);
        lightModels = new TreeMap<>();
    }

    @Override
    public void initialize() {
        logger.debug("Thing is using  {}", this.getClass());
        super.initialize();
    }

    @Override
    public boolean handleDeviceCommand(ChannelUID channelUID, Command command) throws IllegalArgumentException {
        String groupName = getString(channelUID.getGroupId());
        if (groupName.isEmpty()) {
            throw new IllegalArgumentException("Empty groupName");
        }

        int lightId = getLightIdFromGroup(groupName);
        logger.trace("{}: Execute command {} on channel {}, lightId={}", thingName, command, channelUID.getAsString(),
                lightId);

        ShellyLightModel model = getCurrentLightModel(lightId);
        model.resetDirtyFlags();
        try {
            switch (channelUID.getIdWithoutGroup()) {
                default: // non-bulb commands will be handled by the generic handler
                    return false;

                case CHANNEL_LIGHT_POWER:
                    logger.debug("{}: Switch light {}", thingName, command);
                    api.setLightParm(lightId, SHELLY_LIGHT_TURN,
                            command == OnOffType.ON ? SHELLY_API_ON : SHELLY_API_OFF);
                    model.handleCommand(command);
                    model.resetDirtyFlags();
                    requestUpdates(1, false);
                    break;
                case CHANNEL_LIGHT_COLOR_MODE:
                    logger.debug("{}: Select color mode {}", thingName, command);
                    model.setMode((OnOffType) command == OnOffType.ON ? SHELLY_MODE_COLOR : SHELLY_MODE_WHITE);
                    break;
                case CHANNEL_COLOR_PICKER:
                    logger.debug("{}: Update colors from color picker", thingName);
                    model.handleCommand(command);
                    break;
                case CHANNEL_COLOR_FULL:
                    logger.debug("{}: Set colors to {}", thingName, command);
                    model.setFullColor(command);
                    break;
                case CHANNEL_COLOR_RED:
                    model.setColor(R, setColor(lightId, SHELLY_COLOR_RED, command, SHELLY_MAX_COLOR));
                    break;
                case CHANNEL_COLOR_GREEN:
                    model.setColor(G, setColor(lightId, SHELLY_COLOR_GREEN, command, SHELLY_MAX_COLOR));
                    break;
                case CHANNEL_COLOR_BLUE:
                    model.setColor(B, setColor(lightId, SHELLY_COLOR_BLUE, command, SHELLY_MAX_COLOR));
                    break;
                case CHANNEL_COLOR_WHITE:
                    model.setColor(W, setColor(lightId, SHELLY_COLOR_WHITE, command, SHELLY_MAX_COLOR));
                    break;
                case CHANNEL_COLOR_GAIN:
                    model.setGain(setColor(lightId, SHELLY_COLOR_GAIN, command, SHELLY_MIN_GAIN, SHELLY_MAX_GAIN));
                    break;
                case CHANNEL_BRIGHTNESS: // only in white mode
                    model.handleCommand(command);
                    updateChannel(CHANNEL_COLOR_WHITE, CHANNEL_BRIGHTNESS + "$Switch", model.getOnOff());
                    updateChannel(CHANNEL_COLOR_WHITE, CHANNEL_BRIGHTNESS + "$Value", model.getBrightness(true));
                    updateChannel(CHANNEL_GROUP_LIGHT_CONTROL, CHANNEL_LIGHT_POWER, model.getOnOff());
                    break;
                case CHANNEL_COLOR_TEMP:
                    model.handleColorTemperatureCommand(command);
                    break;
                case CHANNEL_COLOR_EFFECT:
                    Integer effect = ((DecimalType) command).intValue();
                    logger.debug("{}: Set color effect to {}", thingName, effect);
                    validateRange("effect", effect, SHELLY_MIN_EFFECT, SHELLY_MAX_EFFECT);
                    model.setEffect(effect.intValue());
            }
            logger.debug("{}: command={} light-mode={}", thingName, command, model);

            if (profile.isBulb && !model.isModeDirty()) {
                logger.debug("{}: Color mode changed to {}", thingName, model.getMode());
                api.setLightMode(model.getMode());
            }

            // send changed light state parameters to the device
            sendColors(profile, lightId, model);
            return true;
        } catch (ShellyApiException e) {
            logger.debug("{}: Unable to handle command: {}", thingName, e.toString());
            return false;
        } catch (IllegalArgumentException e) {
            logger.debug("{}: Unable to handle command", thingName, e);
            return false;
        } finally {
            model.resetDirtyFlags();
        }
    }

    private ShellyLightModel getCurrentLightModel(int lightId) {
        ShellyLightModel col = lightModels.get(lightId);
        if (col == null) {
            col = new ShellyLightModel(profile); // create a new entry
            lightModels.put(lightId, col);
            logger.trace("{}: Colors entry created for lightId {}", thingName, lightId);
        } else {
            logger.trace("{}: Colors loaded for lightId {} '{}'", thingName, lightId, col);
        }
        return col;
    }

    @Override
    public boolean updateDeviceStatus(ShellySettingsStatus genericStatus) throws ShellyApiException {
        if (!profile.isInitialized()) {
            logger.debug("{}: Device not yet initialized!", thingName);
            return false;
        }
        if (!profile.isLight) {
            logger.debug("{}: ERROR: Device is not a light. but class ShellyHandlerLight is called!", thingName);
        }

        ShellyStatusLight status = api.getLightStatus();
        logger.trace("{}: Updating light status in {} mode, {} channel(s)", thingName, profile.device.mode,
                status.lights.size());

        // In white mode we have multiple channels
        int lightId = 0;
        boolean updated = false;
        for (ShellyStatusLightChannel light : status.lights) {
            Integer channelId = lightId + 1;
            String controlGroup = buildControlGroupName(profile, channelId);
            createLightChannels(light, lightId);
            // The bulb has a combined channel set for color or white mode
            // The RGBW2 uses 2 different thing types: color=1 channel, white=4 channel
            if (profile.isBulb) {
                updateChannel(CHANNEL_GROUP_LIGHT_CONTROL, CHANNEL_LIGHT_COLOR_MODE, getOnOff(profile.inColor));
            }

            ShellyLightModel model = getCurrentLightModel(lightId);
            model.setOnOff(light.ison);

            List<ShellySettingsRgbwLight> lights = profile.settings.lights;
            if (lights != null) {
                // Channel control/timer
                ShellySettingsRgbwLight ls = lights.get(lightId);
                updated |= updateChannel(controlGroup, CHANNEL_TIMER_AUTOON,
                        toQuantityType(getDouble(ls.autoOn), Units.SECOND));
                updated |= updateChannel(controlGroup, CHANNEL_TIMER_AUTOOFF,
                        toQuantityType(getDouble(ls.autoOff), Units.SECOND));
                updated |= updateChannel(controlGroup, CHANNEL_LIGHT_POWER, model.getOnOff());
                updated |= updateChannel(controlGroup, CHANNEL_TIMER_ACTIVE, getOnOff(light.hasTimer));
            }

            if (getBool(light.overpower)) {
                postEvent(ALARM_TYPE_OVERPOWER, false);
            }

            if (profile.inColor || (profile.isGen2 && profile.isRGBW2)) {
                logger.trace("{}: update color settings", thingName);
                model.setRGBW(getInteger(light.red), getInteger(light.green), getInteger(light.blue),
                        getInteger(light.white));
                model.setGain(getInteger(light.gain));
                model.setEffect(getInteger(light.effect));

                String colorGroup = CHANNEL_GROUP_COLOR_CONTROL;
                logger.trace("{}: Update channels for group {} : '{}'", thingName, colorGroup, model);
                updated |= updateChannel(colorGroup, CHANNEL_COLOR_RED, model.getColor(R));
                updated |= updateChannel(colorGroup, CHANNEL_COLOR_GREEN, model.getColor(G));
                updated |= updateChannel(colorGroup, CHANNEL_COLOR_BLUE, model.getColor(B));
                updated |= updateChannel(colorGroup, CHANNEL_COLOR_WHITE, model.getColor(W));
                updated |= updateChannel(colorGroup, CHANNEL_COLOR_GAIN, model.getGain());
                updated |= updateChannel(colorGroup, CHANNEL_COLOR_EFFECT, model.getEffect());
                setFullColor(colorGroup, model);

                logger.trace("{}: update {}.color picker", thingName, colorGroup);
                updated |= updateChannel(colorGroup, CHANNEL_COLOR_PICKER, model.getColor());
            }

            if ((!profile.inColor && !profile.isGen2) || profile.isBulb) {
                String whiteGroup = buildWhiteGroupName(profile, channelId);
                model.setBrightness(getInteger(light.brightness));
                updated |= updateChannel(whiteGroup, CHANNEL_BRIGHTNESS + "$Switch", model.getOnOff(true));
                updated |= updateChannel(whiteGroup, CHANNEL_BRIGHTNESS + "$Value", model.getBrightness());

                if ((profile.isBulb || profile.isDuo) && (light.temp != null)) {
                    model.setTemp(getInteger(light.temp));
                    updated |= updateChannel(whiteGroup, CHANNEL_COLOR_TEMP, model.getColorTemperaturePercent());
                    updated |= updateChannel(whiteGroup, CHANNEL_COLOR_PICKER, model.getColor());
                }
            }

            // continue with next light
            lightId++;
        }
        return updated;
    }

    private void createLightChannels(ShellyStatusLightChannel status, int idx) {
        if (!areChannelsCreated()) {
            updateChannelDefinitions(ShellyChannelDefinitions.createLightChannels(getThing(), profile, status, idx));
        }
    }

    private Integer setColor(Integer lightId, String colorName, Command command, Integer minValue, Integer maxValue)
            throws ShellyApiException, IllegalArgumentException {
        Integer value = -1;
        logger.debug("{}: Set {} to {} ({})", thingName, colorName, command, command.getClass());
        if (command instanceof PercentType percentCommand) {
            double v = (double) maxValue * percentCommand.doubleValue() / 100.0;
            value = (int) v;
            logger.debug("{}: Value for {} is in percent: {}%={}", thingName, colorName, percentCommand, value);
        } else if (command instanceof DecimalType decimalCommand) {
            value = decimalCommand.intValue();
            logger.debug("Value for {} is a number: {}", colorName, value);
        } else if (command instanceof OnOffType onOffCommand) {
            value = onOffCommand.equals(OnOffType.ON) ? SHELLY_MAX_COLOR : SHELLY_MIN_COLOR;
            logger.debug("{}: Value for {} of type OnOff was converted to {}", thingName, colorName, value);
        } else {
            throw new IllegalArgumentException(
                    "Invalid value type for " + colorName + ": " + value + " / type " + value.getClass());
        }
        validateRange(colorName, value, minValue, maxValue);
        return value.intValue();
    }

    private Integer setColor(Integer lightId, String colorName, Command command, Integer maxValue)
            throws ShellyApiException, IllegalArgumentException {
        return setColor(lightId, colorName, command, 0, maxValue);
    }

    private void setFullColor(String colorGroup, ShellyLightModel model) {
        double[] rgbx = model.getRGBx();
        if ((rgbx[0] == SHELLY_MAX_COLOR) && (rgbx[1] == SHELLY_MAX_COLOR) && (rgbx[2] == 0)) {
            updateChannel(colorGroup, CHANNEL_COLOR_FULL, new StringType(SHELLY_COLOR_YELLOW));
        } else if ((rgbx[0] == SHELLY_MAX_COLOR) && (rgbx[1] == 0) && (rgbx[2] == 0)) {
            updateChannel(colorGroup, CHANNEL_COLOR_FULL, new StringType(SHELLY_COLOR_RED));
        } else if ((rgbx[0] == 0) && (rgbx[1] == SHELLY_MAX_COLOR) && (rgbx[2] == 0)) {
            updateChannel(colorGroup, CHANNEL_COLOR_FULL, new StringType(SHELLY_COLOR_GREEN));
        } else if ((rgbx[0] == 0) && (rgbx[1] == 0) && (rgbx[2] == SHELLY_MAX_COLOR)) {
            updateChannel(colorGroup, CHANNEL_COLOR_FULL, new StringType(SHELLY_COLOR_BLUE));
        } else if ((rgbx[0] == 0) && (rgbx[1] == 0) && (rgbx[2] == 0) && (rgbx[3] == SHELLY_MAX_COLOR)) {
            updateChannel(colorGroup, CHANNEL_COLOR_FULL, new StringType(SHELLY_COLOR_WHITE));
        }
    }

    private void sendColors(ShellyDeviceProfile profile, Integer lightId, ShellyLightModel model)
            throws ShellyApiException {
        Integer channelId = lightId + 1;
        Map<String, String> parms = new TreeMap<>();

        if (model.isPowerDirty()) {
            parms.put(SHELLY_LIGHT_TURN, OnOffType.ON == model.getOnOff(true) ? SHELLY_API_ON : SHELLY_API_OFF);
        }
        if (model.isBrightnessDirty()) {
            parms.put(SHELLY_COLOR_BRIGHTNESS, String.valueOf(model.getBrightness().intValue()));
        }
        if (model.isColorDirty()) {
            double[] rgbw = model.getRGBx();
            parms.put(SHELLY_COLOR_RED, String.valueOf(Math.round(rgbw[0])));
            parms.put(SHELLY_COLOR_GREEN, String.valueOf(Math.round(rgbw[1])));
            parms.put(SHELLY_COLOR_BLUE, String.valueOf(Math.round(rgbw[2])));
            parms.put(SHELLY_COLOR_WHITE, String.valueOf(Math.round(rgbw[3])));
        }
        if (model.isColorTempDirty()) {
            parms.put(SHELLY_COLOR_TEMP, String.valueOf(1000000.0 / model.getMirek()));
        }
        if (model.isGainDirty()) {
            parms.put(SHELLY_COLOR_GAIN, String.valueOf(model.getGain()));
        }
        if (model.isEffectDirty()) {
            parms.put(SHELLY_COLOR_EFFECT, String.valueOf(model.getEffect()));
        }

        if (!parms.isEmpty()) {
            logger.debug("{}: Send light channel: {} parameters: {}", thingName, channelId, parms);
            api.setLightParms(lightId, parms);
        }
    }
}
