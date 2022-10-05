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

import java.lang.reflect.Type;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.dto.tag.ApiEnum;
import org.openhab.binding.hue.internal.dto.tag.IBase;
import org.openhab.binding.hue.internal.dto.tag.ILight;
import org.openhab.binding.hue.internal.dto.tag.IUpdate;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.HSBType;
import org.openhab.core.library.types.IncreaseDecreaseType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import com.google.gson.reflect.TypeToken;

/**
 * DTO for an API v2 light.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Light2 extends BaseObject implements ILight, IUpdate {
    public static final Type GSON_TYPE = new TypeToken<Resources<Light2>>() {
    }.getType();

    private @Nullable OnState on;
    private @Nullable Dimming dimming;
    private @Nullable ColorTemperature2 color_temperature;
    private @Nullable ColorXy color;

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
    public State getSwitch() {
        return on != null ? OnOffType.from(on.isOn()) : UnDefType.UNDEF;
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
    public State getBrightnessState() {
        return dimming != null ? new PercentType(dimming.getBrightness()) : UnDefType.UNDEF;
    }

    /**
     * Evaluate a new percent value depending on the type of command and if relevant the current value.
     *
     * @param command either a PercentType with the new value, an OnOffType to set it at 0 / 100 percent, or an
     *            IncreaseDecreaseType to increment the percentage value by a fixed amount.
     * @param old the current percent value
     * @return the new PercenType value, or null if the command was not recognised
     */
    private static State evaluateState(Command command, State current) {
        if (command instanceof PercentType) {
            return (PercentType) command;
        } else if (command instanceof OnOffType) {
            return OnOffType.ON.equals(command) ? PercentType.HUNDRED : PercentType.ZERO;
        } else if (command instanceof IncreaseDecreaseType && current instanceof PercentType) {
            int sign = IncreaseDecreaseType.INCREASE.equals(command) ? 1 : -1;
            int percent = ((PercentType) current).intValue() + (sign * DELTA);
            return new PercentType(Math.min(100, Math.max(0, percent)));
        }
        return UnDefType.UNDEF;
    }

    /**
     * Set the brightness percent.
     *
     * @param command either a PercentType with the new value, an OnOffType to set it at 0 / 100 percent, or an
     *            IncreaseDecreaseType to increment the percentage value by a fixed amount
     */
    public void setBrightness(Command command) {
        State state = evaluateState(command, getBrightnessState());
        if (state instanceof PercentType) {
            dimming = dimming != null ? dimming : new Dimming();
            dimming.setBrightness(((PercentType) state).intValue());
            if (PercentType.ZERO.equals(state)) {
                setSwitch(OnOffType.OFF);
            }
        }
    }

    /**
     * Get the colour temperature as a raw mirek value.
     *
     * @return the mirek value
     */
    public int getColorTemperature() {
        return color_temperature != null ? color_temperature.getMirek() : -1;
    }

    /**
     * Get the colour temperature in percent.
     *
     * @return a PercentType with the colour temperature percentage
     */
    public State getColorTemperatureState() {
        ColorTemperature2 colorTemp = color_temperature;
        if (colorTemp != null) {
            Integer percent = colorTemp.getPercent();
            if (percent != null) {
                return new PercentType(percent);
            }
        }
        return UnDefType.UNDEF;
    }

    /**
     * Get the colour temperature in Kelvin.
     *
     * @return a DecimalType with the colour temperature in Kelvin
     */
    public State getColorTemperatureKelvin() {
        ColorTemperature2 colorTemp = color_temperature;
        if (colorTemp != null) {
            Integer kelvin = colorTemp.getKelvin();
            if (kelvin != null) {
                return new DecimalType(kelvin);
            }
        }
        return UnDefType.UNDEF;
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
        State state = evaluateState(command, getColorTemperatureState());
        if (state instanceof PercentType) {
            colorTemperature.setPercent(((PercentType) state).intValue());
        } else if (command instanceof DecimalType) {
            colorTemperature.setKelvin(((DecimalType) command).intValue());
        }
    }

    /**
     * Get the colour
     *
     * @return an HSBType containing the current color.
     */
    public State getColor() {
        return color != null ? hsbFromXY(color.getXY()) : UnDefType.UNDEF;
    }

    /**
     * Set the colour or the brightness depending on the command type.
     *
     * @param command either an HSBType with the new color value, a PercentType in which case the brightness is set in
     *            percent, an OnOff type in which case the brightness is set to 0/100, an IncreaseDecreaseType in which
     *            case the brightness is increased/decreased by a certain amount.
     */
    public void setColor(Command command) {
        if (command instanceof HSBType) {
            color = color != null ? color : new ColorXy();
            color.setXY(xyFromHsb((HSBType) command));
        } else {
            State state = evaluateState(command, getBrightnessState());
            if (state instanceof PercentType) {
                setBrightness(command);
            }
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
    public ApiEnum apiVersion() {
        return ApiEnum.V2;
    }

    @Override
    public boolean isSame(IBase other) {
        try {
            Light2 two = other.as(Light2.class);
            return getSwitch().equals(two.getSwitch()) && getBrightnessState().equals(two.getBrightnessState())
                    && getColorTemperatureState().equals(two.getColorTemperatureState())
                    && getColor().equals(two.getColor());
        } catch (ClassCastException e) {
        }
        return false;
    }
}
