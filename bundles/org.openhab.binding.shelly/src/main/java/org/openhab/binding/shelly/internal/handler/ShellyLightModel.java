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

import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.shelly.internal.api.ShellyDeviceProfile;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.HSBType;
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
    private Integer gain = 0;
    private Integer effect = 0;

    private String mode = SHELLY_MODE_WHITE; // TODO: add getLedOperatingMode() method in OH Core

    private boolean modeDirty;
    private boolean colorDirty;
    private boolean brightnessDirty;
    private boolean gainDirty;
    private boolean effectDirty;
    private boolean colorTempDirty;
    private boolean powerDirty;

    public ShellyLightModel(ShellyDeviceProfile profile) {
        super(profile.isDuo ? BRIGHTNESS_WITH_COLOR_TEMPERATURE : COLOR_WITH_COLOR_TEMPERATURE, DEFAULT, 0.4,
                1000000.0 / profile.maxTemp, 1000000.0 / profile.minTemp, null, null, null);
    }

    @Override
    public synchronized void handleCommand(Command command) throws IllegalArgumentException {
        super.handleCommand(command);
        colorDirty = command instanceof HSBType;
        brightnessDirty = colorDirty || command instanceof PercentType;
        powerDirty = true;
    }

    public PercentType getColor(RGBW index) {
        double[] rgbw = getRGBx();
        return new PercentType((int) Math.round(rgbw[index.ordinal()] * 100.0 / 255.0));
    }

    public DecimalType getEffect() {
        return new DecimalType(effect);
    }

    public DecimalType getGain() {
        return new DecimalType(gain);
    }

    public String getMode() {
        return mode; // TODO: add getLedOperatingMode() method in OH Core
    }

    public boolean isBrightnessDirty() {
        return brightnessDirty;
    }

    public boolean isColorDirty() {
        return colorDirty;
    }

    public boolean isColorTempDirty() {
        return colorTempDirty;
    }

    public boolean isEffectDirty() {
        return effectDirty;
    }

    public boolean isGainDirty() {
        return gainDirty;
    }

    public boolean isModeDirty() {
        return modeDirty;
    }

    public boolean isPowerDirty() {
        return powerDirty;
    }

    public boolean isRgbValid() {
        return true; // TODO
    }

    public void setBrightness(int value) {
        setBrightness((double) value);
        brightnessDirty = true;
    }

    public void setColor(RGBW index, int value) {
        double[] rgbw = getRGBx();
        rgbw[index.ordinal()] = value;
        setRGBx(rgbw);
        colorDirty = true;
    }

    public void setEffect(int value) {
        effect = value;
        effectDirty = true;
    }

    public void setFullColor(Command command) throws IllegalArgumentException {
        String color = command.toString().toLowerCase(Locale.ROOT);
        if (color.contains(",")) {
            setRGBW(color);
        } else if (color.equals(SHELLY_COLOR_RED)) {
            setRGBW(SHELLY_MAX_COLOR, 0, 0, 0);
        } else if (color.equals(SHELLY_COLOR_GREEN)) {
            setRGBW(0, SHELLY_MAX_COLOR, 0, 0);
        } else if (color.equals(SHELLY_COLOR_BLUE)) {
            setRGBW(0, 0, SHELLY_MAX_COLOR, 0);
        } else if (color.equals(SHELLY_COLOR_YELLOW)) {
            setRGBW(SHELLY_MAX_COLOR, SHELLY_MAX_COLOR, 0, 0);
        } else if (color.equals(SHELLY_COLOR_WHITE)) {
            setRGBW(0, 0, 0, SHELLY_MAX_COLOR);
            setMode(SHELLY_MODE_WHITE);
        } else {
            throw new IllegalArgumentException("Invalid full color selection: " + color);
        }
    }

    public void setGain(int value) {
        gain = value;
        gainDirty = true;
    }

    public void setMode(String modeStr) {
        mode = modeStr; // TODO: add getLedOperatingMode() method in OH Core
        setLedOperatingMode(SHELLY_MODE_COLOR.equals(modeStr) ? RGB_ONLY : WHITE_ONLY);
        modeDirty = true;
    }

    public void setRGBW(int red, int green, int blue, int white) {
        setRGBx(new double[] { red, green, blue, white });
        colorDirty = true;
    }

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
        setRGBx(new double[] { values[0], values[1], values[2], values[3] });
        colorDirty = true;
    }

    public void setTemp(int value) {
        setMirek(1000000.0 / value);
        colorTempDirty = true;
    }

    @Override
    public String toString() {
        double[] rgbw = getRGBx();
        return "mode=%s, power=%s, rgbw=(%f,%f,%f,%f), bri=%s, color-temp=%f K, min=%f K, max=%f K, gain=%d, effect=%d" //
                .formatted(mode, getOnOff(true), rgbw[0], rgbw[1], rgbw[2], rgbw[3], getBrightness(),
                        1000000.0 / getMirek(), 1000000.0 / configGetMirekControlWarmest(),
                        1000000.0 / configGetMirekControlCoolest(), gain, effect);
    }

    public void resetDirtyFlags() {
        modeDirty = false;
        colorDirty = false;
        brightnessDirty = false;
        gainDirty = false;
        effectDirty = false;
        colorTempDirty = false;
        powerDirty = false;
    }
}
