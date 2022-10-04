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
package org.openhab.binding.hue.internal.dto.v2;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.dto.tag.ApiType;
import org.openhab.binding.hue.internal.dto.tag.Light;
import org.openhab.binding.hue.internal.dto.tag.Update;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.HSBType;
import org.openhab.core.library.types.IncreaseDecreaseType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.Command;

/**
 * DTO for an API v2 light.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Light2 extends BaseObject implements Light, Update {
    private @Nullable OnState on;
    private @Nullable Dimming dimming;
    private @Nullable ColorTemperature2 color_temperature;
    private @Nullable ColorXy color;

    private transient HSBType colorObject = HSBType.WHITE;
    private transient boolean colorInitialized = false;

    private static final int DELTA = 30;

    public Light2() {
        setType("light");
    }

    /**
     * Create an HsbType from an array of floats of colour x & y parameters.
     *
     * @param xy colour x & y parameters
     * @return a new HsbType
     */
    public static HSBType hsbFromXY(float[] xy) {
        return HSBType.fromXY(xy[0], xy[1]);
    }

    /**
     * Get the x & y colour parameters of an HsbType instance.
     *
     * @param hsb the HsbType
     * @return colour x & y parameters
     */
    public static float[] xyFromHsb(HSBType hsb) {
        PercentType[] percentTypes = hsb.toXY();
        float[] floats = new float[percentTypes.length];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = percentTypes[i].floatValue() / 100.0f;
        }
        return floats;
    }

    /**
     * Get the light on status.
     *
     * @return OnOffType with the on status.
     */
    public OnOffType getSwitch() {
        getColor();
        return OnOffType.from(on != null ? on.isOn() : false);
    }

    /**
     * Set the light on status.
     *
     * @param command and OnOffType with either on / off.
     */
    public void setSwitch(Command command) {
        if (command instanceof OnOffType) {
            on = on != null ? on : new OnState();
            on.setOn(OnOffType.ON.equals(command));
        }
    }

    /**
     * Get the brightness percentage.
     *
     * @return a PercentType with the brightness 0..100
     */
    public PercentType getBrightnessPercent() {
        return getColor().getBrightness();
    }

    /**
     * Evaluate a new percent value depending on the type of command and if relevant the current value.
     *
     * @param command either a PercentType with the new value, an OnOffType to set it at 0 / 100 percent, or an
     *            IncreaseDecreaseType to increment the percentage value by a fixed amount.
     * @param old the current percent value
     * @return the new PercenType value, or null if the command was not recognised
     */
    private static @Nullable PercentType evaluatePercent(Command command, PercentType current) {
        if (command instanceof PercentType) {
            return (PercentType) command;
        } else if (command instanceof OnOffType) {
            return OnOffType.ON.equals(command) ? PercentType.HUNDRED : PercentType.ZERO;
        } else if (command instanceof IncreaseDecreaseType) {
            int sign = IncreaseDecreaseType.INCREASE.equals(command) ? 1 : -1;
            int percent = current.intValue() + (sign * DELTA);
            return new PercentType(Math.min(100, Math.max(0, percent)));
        }
        return null;
    }

    /**
     * Set the brightness percent.
     *
     * @param command either a PercentType with the new value, an OnOffType to set it at 0 / 100 percent, or an
     *            IncreaseDecreaseType to increment the percentage value by a fixed amount
     */
    public void setBrightness(Command command) {
        PercentType percent = evaluatePercent(command, getBrightnessPercent());
        if (percent != null) {
            if (PercentType.ZERO.equals(percent)) {
                setSwitch(OnOffType.OFF);
            } else {
                HSBType oldColor = getColor();
                setColor(new HSBType(oldColor.getHue(), oldColor.getSaturation(), percent));
            }
        }
    }

    /**
     * Get the colour temperature as a raw mirek value.
     *
     * @return the mirek value
     */
    public int getColorTemperature() {
        return color_temperature != null ? color_temperature.getMirek() : ColorTemperature2.MIN;
    }

    /**
     * Get the colour temperature in percent.
     *
     * @return a PercentType with the colour temperature percentage
     */
    public PercentType getColorTemperaturePercent() {
        return color_temperature != null ? new PercentType(color_temperature.getPercent()) : PercentType.ZERO;
    }

    /**
     * Get the colour temperature in Kelvin.
     *
     * @return a DecimalType with the colour temperature in Kelvin
     */
    public DecimalType getColorTemperatureKelvin() {
        return color_temperature != null ? new DecimalType(color_temperature.getKelvin()) : DecimalType.ZERO;
    }

    /**
     * Set the colour temperature in percent or in Kelvin depending on the command type.
     *
     * @param command either a DecimalType in which case the colour temperature is set in Kelvin, a PercentType in which
     *            case the colour temperature is set in percent, an OnOff type in which case it is set to 0/100, an
     *            IncreaseDecreaseType in which case it is increased/decreased by a certain amount.
     */
    public void setColorTemperature(Command command) {
        ColorTemperature2 colorTemperature = color_temperature != null ? color_temperature : new ColorTemperature2();
        this.color_temperature = colorTemperature;
        PercentType percent = evaluatePercent(command, getColorTemperaturePercent());
        if (percent != null) {
            colorTemperature.setPercent(percent.intValue());
        } else if (command instanceof DecimalType) {
            colorTemperature.setKelvin(((DecimalType) command).intValue());
        }
    }

    /**
     * Get the colour
     *
     * @return an HSBType containing the current color.
     */
    public HSBType getColor() {
        if (!colorInitialized) {
            HSBType oldColor = colorObject;
            PercentType brightness = dimming != null ? new PercentType(dimming.getBrightness()) : PercentType.ZERO;
            if (color != null) {
                oldColor = hsbFromXY(color.getXY());
            }
            colorObject = new HSBType(oldColor.getHue(), oldColor.getSaturation(), brightness);
        }
        return colorObject;
    }

    /**
     * Set the colour or the brightness depending on the command type.
     *
     * @param command either an HSBType with the new color value, a PercentType in which case the brightness is set in
     *            percent, an OnOff type in which case the brightness is set to 0/100, an IncreaseDecreaseType in which
     *            case the brightness is increased/decreased by a certain amount.
     */
    public void setColor(Command command) {
        PercentType percent = evaluatePercent(command, getBrightnessPercent());
        if (percent != null) {
            setBrightness(command);
        } else if (command instanceof HSBType) {
            colorObject = (HSBType) command;
            color = color != null ? color : new ColorXy();
            color.setXY(xyFromHsb(colorObject));
            dimming = dimming != null ? dimming : new Dimming();
            dimming.setBrightness(colorObject.getBrightness().intValue());
            colorInitialized = true;
        }
    }

    public void setAlert(Command command) {
        if (command instanceof StringType) {
            // TODO in API V2 the alerts enum is different than in V1!
        }

    }

    public void setEffect(Command command) {
        if (command instanceof OnOffType) {
            // TODO in API V2 the effects enum is different than in V1!
        }
    }

    @Override
    public boolean sameState(Light other) {
        Light2 two = other.toLight2();
        return getSwitch().equals(two.getSwitch()) && getBrightnessPercent().equals(two.getBrightnessPercent())
                && getColorTemperaturePercent().equals(two.getColorTemperaturePercent())
                && getColor().equals(two.getColor());
    }

    @Override
    public ApiType apiVersion() {
        return ApiType.V2;
    }

    @Override
    public Light2 toLight2() {
        return this;
    }

    @Override
    public Light2 toLight2Update() {
        return this;
    }
}
