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

import static org.openhab.binding.shelly.internal.api1.Shelly1ApiJsonDTO.*;
import static org.openhab.core.util.LightModel.LedOperatingMode.*;
import static org.openhab.core.util.LightModel.LightCapabilities.*;
import static org.openhab.core.util.LightModel.RgbDataType.DEFAULT;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.shelly.internal.api.ShellyDeviceProfile;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.HSBType;
import org.openhab.core.library.types.IncreaseDecreaseType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.types.Command;
import org.openhab.core.util.LightModel;

/**
 * The {@link ShellyLightModel} overrides the OH Core {@link LightModel} with Shelly specific functions.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class ShellyLightModel extends LightModel {
    private int gain = 0;
    private int effect = 0;

    private boolean modeDirty;
    private boolean colorDirty;
    private boolean brightnessDirty;
    private boolean gainDirty;
    private boolean effectDirty;
    private boolean colorTempDirty;
    private boolean onOffDirty;

    public ShellyLightModel(ShellyDeviceProfile profile) {
        super(profile.isDuo ? BRIGHTNESS_WITH_COLOR_TEMPERATURE : COLOR_WITH_COLOR_TEMPERATURE, DEFAULT, 0.4,
                1000000.0 / profile.maxTemp, 1000000.0 / profile.minTemp, null, null, null);
    }

    /**
     * Set the brightness. Do not set the dirty flag.
     */
    public void hydrateBrightness(int value) {
        setBrightness((double) value);
    }

    /**
     * Set the brightness. Set the dirty flag.
     */
    public void setBrightness(int value) {
        setBrightness((double) value);
        brightnessDirty = true;
    }

    /**
     * Check if the brightness has been changed since the last reset of the dirty flags.
     */
    public boolean isBrightnessDirty() {
        return brightnessDirty;
    }

    /**
     * Get the color component at the given RGBW index as a PercentType.
     */
    public PercentType getColor(RGBW index) {
        double[] rgbw = getRGBx();
        return new PercentType((int) Math.round(rgbw[index.ordinal()] * 100.0 / 255.0));
    }

    /**
     * Set the color component at the given RGBW index. Do not set the dirty flag.
     */
    public void hydrateColor(RGBW index, int value) {
        double[] rgbw = getRGBx();
        rgbw[index.ordinal()] = value;
        setRGBx(rgbw);
    }

    /**
     * Set the color component at the given RGBW index. Set the dirty flag.
     */
    public void setColor(RGBW index, int value) {
        hydrateColor(index, value);
        colorDirty = true;
    }

    /**
     * Check if the color has been changed since the last reset of the dirty flags.
     */
    public boolean isColorDirty() {
        return colorDirty;
    }

    /**
     * Get the effect as a DecimalType.
     */
    public DecimalType getEffect() {
        return new DecimalType(effect);
    }

    /**
     * Set the effect. Set the dirty flag.
     */
    public void hydrateEffect(int value) {
        effect = value;
    }

    /**
     * Set the effect. Mark it as dirty.
     */
    public void setEffect(int value) {
        effect = value;
        effectDirty = true;
    }

    /**
     * Check if the effect has been changed since the last reset of the dirty flags.
     */
    public boolean isEffectDirty() {
        return effectDirty;
    }

    /**
     * Get the gain as a DecimalType.
     */
    public DecimalType getGain() {
        return new DecimalType(gain);
    }

    /**
     * Set the gain. Set the dirty flag.
     */
    public void hydrateGain(int value) {
        gain = value;
    }

    /**
     * Set the gain. Mark it as dirty.
     */
    public void setGain(int value) {
        gain = value;
        gainDirty = true;
    }

    /**
     * Check if the gain has been changed since the last reset of the dirty flags.
     */
    public boolean isGainDirty() {
        return gainDirty;
    }

    /**
     * Get the led operating mode as a string.
     */
    public String getMode() {
        return RGB_ONLY == getLedOperatingMode() ? SHELLY_MODE_COLOR : SHELLY_MODE_WHITE;
    }

    /**
     * Set the led operating mode. Do not set the dirty flag.
     */
    public void hydrateMode(String mode) {
        setLedOperatingMode(SHELLY_MODE_COLOR.equals(mode) ? RGB_ONLY : WHITE_ONLY);
    }

    /**
     * Set the mode. Set the dirty flag.
     */
    public void setMode(String modeStr) {
        hydrateMode(modeStr);
        modeDirty = true;
    }

    /**
     * Check if the mode has been changed since the last reset of the dirty flags.
     */
    public boolean isModeDirty() {
        return modeDirty;
    }

    /**
     * Set the on/off state. Do not set the dirty flag.
     */
    public void hydrateOnOff(boolean on) {
        super.setOnOff(on);
    }

    /**
     * Set the on/off state. Set the dirty flag.
     */
    @Override
    public void setOnOff(boolean on) {
        hydrateOnOff(on);
        onOffDirty = true;
    }

    /**
     * Check if the on/off state has been changed since the last reset of the dirty flags.
     */
    public boolean isOnOffDirty() {
        return onOffDirty;
    }

    /**
     * Set the RGBW values. Do not set the dirty flag.
     */
    public void hydrateRGBW(int red, int green, int blue, int white) {
        setRGBx(new double[] { red, green, blue, white });
    }

    /**
     * Set the RGBW values. Set the dirty flag.
     */
    public void setRGBW(int red, int green, int blue, int white) {
        hydrateRGBW(red, green, blue, white);
        colorDirty = true;
    }

    /**
     * Set the RGBW values from a comma-separated string. Set the dirty flag.
     */
    public void setRGBW(String rgbwString) {
        Integer[] values = new Integer[4];
        values[0] = values[1] = values[2] = values[3] = -1;
        try {
            String[] rgbw = rgbwString.split(",");
            for (int i = 0; i < rgbw.length; i++) {
                values[i] = Integer.parseInt(rgbw[i]);
            }
        } catch (NumberFormatException e) { // might be a format problem
            throw new IllegalArgumentException(
                    "Unable to convert fullColor value: " + rgbwString + ", " + e.getMessage(), e);
        }
        setRGBW(values[0], values[1], values[2], values[3]);
    }

    /**
     * Set the color temperature. Do not set the dirty flag.
     */
    public void hydrateTemp(int value) {
        setMirek(1000000.0 / value);
    }

    /**
     * Set the color temperature. Set the dirty flag.
     */
    public void setTemp(int value) {
        hydrateTemp(value);
        colorTempDirty = true;
    }

    /**
     * Check if the color temperature has been changed since the last reset of the dirty flags.
     */
    public boolean isColorTempDirty() {
        return colorTempDirty;
    }

    /**
     * TODO
     */
    public boolean isRgbValid() {
        return true;
    }

    @Override
    public String toString() {
        double[] rgbw = getRGBx();
        return "mode=%s, power=%s, rgbw=(%f,%f,%f,%f), bri=%s, color-temp=%f K, min=%f K, max=%f K, gain=%d, effect=%d" //
                .formatted(getMode(), getOnOff(true), rgbw[0], rgbw[1], rgbw[2], rgbw[3], getBrightness(),
                        1000000.0 / getMirek(), 1000000.0 / configGetMirekControlWarmest(),
                        1000000.0 / configGetMirekControlCoolest(), gain, effect);
    }

    /**
     * Override handleCommand and set the dirty flags accordingly.
     */
    @Override
    public synchronized void handleCommand(Command command) throws IllegalArgumentException {
        super.handleCommand(command);
        colorDirty = command instanceof HSBType;
        brightnessDirty = colorDirty || command instanceof PercentType || command instanceof IncreaseDecreaseType;
        onOffDirty = brightnessDirty || command instanceof OnOffType;
    }

    /**
     * Reset all dirty flags.
     */
    public void resetDirtyFlags() {
        modeDirty = false;
        colorDirty = false;
        brightnessDirty = false;
        gainDirty = false;
        effectDirty = false;
        colorTempDirty = false;
        onOffDirty = false;
    }
}
