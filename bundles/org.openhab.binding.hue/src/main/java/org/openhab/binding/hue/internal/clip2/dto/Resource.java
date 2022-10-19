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
package org.openhab.binding.hue.internal.clip2.dto;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hue.internal.clip2.enums.RecallAction;
import org.openhab.binding.hue.internal.clip2.enums.ResourceType;
import org.openhab.binding.hue.internal.clip2.enums.ZigBeeState;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.HSBType;
import org.openhab.core.library.types.IncreaseDecreaseType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * Complete Resource object information DTO for CLIP 2.
 *
 * Note: all dto fields are @Nullable because some cases do not (must not) use them.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class Resource {

    /*
     * ++++++++++++++++++++++++++++++++++++++++
     * + Common Fields
     * ++++++++++++++++++++++++++++++++++++++++
     */
    private @Nullable String type;
    private @Nullable String id;
    private @Nullable String id_v1;
    private @Nullable Reference owner;
    private @Nullable MetaData metadata;

    /*
     * ++++++++++++++++++++++++++++++++++++++++
     * + Device Fields
     * ++++++++++++++++++++++++++++++++++++++++
     */
    private @Nullable ProductData product_data;
    private @Nullable List<Reference> services;

    /*
     * ++++++++++++++++++++++++++++++++++++++++
     * + Light Fields
     * ++++++++++++++++++++++++++++++++++++++++
     */
    private @Nullable OnState on;
    private @Nullable Dimming dimming;
    private @Nullable ColorTemperature2 color_temperature;
    private @Nullable ColorXy color;
    private @Nullable Alerts alert;
    private @Nullable Effects effects;
    private @Nullable Effects timed_effects;

    private static final int DELTA = 30;

    /*
     * ++++++++++++++++++++++++++++++++++++++++
     * + Scene Fields
     * ++++++++++++++++++++++++++++++++++++++++
     */
    private @Nullable Reference group;
    private @Nullable List<ActionEntry> actions;
    private @Nullable Recall recall;

    /*
     * ++++++++++++++++++++++++++++++++++++++++
     * + Sensor Fields
     * ++++++++++++++++++++++++++++++++++++++++
     */
    private @Nullable Boolean enabled;
    private @Nullable LightLevel light;
    private @Nullable Button button;
    private @Nullable Temperature temperature;
    private @Nullable Motion motion;
    private @Nullable Power power_state;

    /*
     * ++++++++++++++++++++++++++++++++++++++++
     * + Group Fields
     * ++++++++++++++++++++++++++++++++++++++++
     */
    private @Nullable List<Reference> children;

    /*
     * ++++++++++++++++++++++++++++++++++++++++
     * + ZigBee Fields
     * ++++++++++++++++++++++++++++++++++++++++
     */
    private @Nullable String status;

    /*
     * ++++++++++++++++++++++++++++++++++++++++
     * + Common Field Getters & Setters
     * ++++++++++++++++++++++++++++++++++++++++
     */

    public Resource(@Nullable ResourceType resourceType) {
        if (resourceType != null) {
            setType(resourceType);
        }
    }

    public Resource setType(ResourceType resourceType) {
        this.type = resourceType.name().toLowerCase();
        return this;
    }

    public Resource setId(String id) {
        this.id = id;
        return this;
    }

    public ResourceType getType() {
        return ResourceType.of(type);
    }

    public String getId() {
        String id = this.id;
        return id != null ? id : "";
    }

    public String getIdV1() {
        String id_v1 = this.id_v1;
        return id_v1 != null ? id_v1 : "";
    }

    public @Nullable Reference getOwner() {
        return owner;
    }

    public @Nullable MetaData getMetaData() {
        return metadata;
    }

    /*
     * ++++++++++++++++++++++++++++++++++++++++
     * + Device Field Getters & Setters
     * ++++++++++++++++++++++++++++++++++++++++
     */

    public @Nullable ProductData getProductData() {
        return product_data;
    }

    public List<Reference> getServiceReferences() {
        List<Reference> services = this.services;
        return services != null ? services : List.of();
    }

    /*
     * ++++++++++++++++++++++++++++++++++++++++
     * + Light Field Getters & Setters
     * ++++++++++++++++++++++++++++++++++++++++
     */

    /**
     * Create an HsbType from an array of floats of colour x & y parameters.
     *
     * @param xy colour x & y parameters.
     * @return a new HsbType.
     */
    private static HSBType hsbFromXY(float[] xy) {
        return HSBType.fromXY(xy[0], xy[1]);
    }

    /**
     * Get the x & y colour parameters of an HsbType instance.
     *
     * @param hsb the HsbType.
     * @return colour x & y parameters.
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
        OnState on = this.on;
        return on != null ? OnOffType.from(on.isOn()) : UnDefType.UNDEF;
    }

    /**
     * Set the light on status.
     *
     * @param command and OnOffType with either on / off.
     */
    public Resource setSwitch(Command command) {
        if (command instanceof OnOffType) {
            OnState on = this.on;
            on = on != null ? on : new OnState();
            on.setOn(OnOffType.ON.equals(command));
            this.on = on;
        }
        return this;
    }

    /**
     * Get the brightness percentage.
     *
     * @return a PercentType with the brightness 0..100
     */
    public State getBrightnessState() {
        Dimming dimming = this.dimming;
        return dimming != null ? new PercentType(dimming.getBrightness()) : UnDefType.UNDEF;
    }

    /**
     * Get a new percent value depending on the type of command and if relevant the current value.
     *
     * @param command either a PercentType with the new value, an OnOffType to set it at 0 / 100 percent, or an
     *            IncreaseDecreaseType to increment the percentage value by a fixed amount.
     * @param old the current percent value.
     * @return the new PercenType value, or null if the command was not recognised.
     */
    private static State getPercentType(Command command, State current) {
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
     *            IncreaseDecreaseType to increment the percentage value by a fixed amount.
     */
    public Resource setBrightness(Command command) {
        State state = getPercentType(command, getBrightnessState());
        if (state instanceof PercentType) {
            Dimming dimming = this.dimming;
            dimming = dimming != null ? dimming : new Dimming();
            dimming.setBrightness(((PercentType) state).intValue());
            if (PercentType.ZERO.equals(state)) {
                setSwitch(OnOffType.OFF);
            }
            this.dimming = dimming;
        }
        return this;
    }

    /**
     * Get the colour temperature as a raw mirek value.
     *
     * @return the mirek value.
     */
    public int getColorTemperature() {
        ColorTemperature2 colorTemp = color_temperature;
        if (colorTemp != null) {
            Integer mirek = colorTemp.getMirek();
            if (mirek != null) {
                return mirek.intValue();
            }
        }
        return -1;
    }

    /**
     * Get the colour temperature in percent.
     *
     * @return a PercentType with the colour temperature percentage.
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
     * @return a DecimalType with the colour temperature in Kelvin.
     */
    public State getColorTemperatureKelvinState() {
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
    public Resource setColorTemperature(Command command) {
        ColorTemperature2 color_temperature = this.color_temperature;
        color_temperature = color_temperature != null ? color_temperature : new ColorTemperature2();
        State state = getPercentType(command, getColorTemperatureState());
        if (state instanceof PercentType) {
            color_temperature.setPercent(((PercentType) state).intValue());
        } else if (command instanceof DecimalType) {
            color_temperature.setKelvin(((DecimalType) command).intValue());
        }
        this.color_temperature = color_temperature;
        return this;
    }

    /**
     * Get the colour.
     *
     * @return an HSBType containing the current color.
     */
    public State getColorState() {
        ColorXy color = this.color;
        return color != null ? hsbFromXY(color.getXY()) : UnDefType.UNDEF;
    }

    /**
     * Set the colour or the brightness depending on the command type.
     *
     * @param command either an HSBType with the new color value, a PercentType in which case the brightness is set in
     *            percent, an OnOff type in which case the brightness is set to 0/100, an IncreaseDecreaseType in which
     *            case the brightness is increased/decreased by a certain amount.
     */
    public Resource setColor(Command command) {
        if (command instanceof HSBType) {
            ColorXy color = this.color;
            color = color != null ? color : new ColorXy();
            color.setXY(xyFromHsb((HSBType) command));
            this.color = color;
        } else {
            State state = getPercentType(command, getBrightnessState());
            if (state instanceof PercentType) {
                setBrightness(command);
            }
        }
        return this;
    }

    public @Nullable Alerts getAlert() {
        return alert;
    }

    public @Nullable Effects getEffects() {
        return effects;
    }

    public @Nullable Effects getTimedEffects() {
        return timed_effects;
    }

    /*
     * ++++++++++++++++++++++++++++++++++++++++
     * + Scene Field Getters & Setters
     * ++++++++++++++++++++++++++++++++++++++++
     */

    public @Nullable List<ActionEntry> getActions() {
        return actions;
    }

    public @Nullable Reference getGroup() {
        return group;
    }

    /*
     * ++++++++++++++++++++++++++++++++++++++++
     * + Sensor Field Getters & Setters
     * ++++++++++++++++++++++++++++++++++++++++
     */
    public @Nullable Boolean getEnabled() {
        return enabled;
    }

    public State getEnabledState() {
        Boolean enabled = this.enabled;
        return enabled != null ? OnOffType.from(enabled.booleanValue()) : UnDefType.UNDEF;
    }

    public Resource setEnabled(Command command) {
        if (command instanceof OnOffType) {
            this.enabled = ((OnOffType) command) == OnOffType.ON;
        }
        return this;
    }

    public @Nullable LightLevel getLightLevel() {
        return light;
    }

    public State getLightLevelState() {
        LightLevel light = this.light;
        return light != null ? light.getLightlevelState() : UnDefType.UNDEF;
    }

    public @Nullable Button getButton() {
        return button;
    }

    public State getButtonLastEventState() {
        Button button = this.button;
        return button != null ? button.getLastEventState() : UnDefType.UNDEF;
    }

    public @Nullable Temperature getTemperature() {
        return temperature;
    }

    public State getTemperatureState() {
        Temperature temperature = this.temperature;
        return temperature != null ? temperature.getTemperatureState() : UnDefType.UNDEF;
    }

    public State getTemperatureValidState() {
        Temperature temperature = this.temperature;
        return temperature != null ? temperature.getTemperatureValidState() : UnDefType.UNDEF;
    }

    public @Nullable Motion getMotion() {
        return motion;
    }

    public State getMotionState() {
        Motion motion = this.motion;
        return motion != null ? motion.getMotionState() : UnDefType.UNDEF;
    }

    public State getMotionValidState() {
        Motion motion = this.motion;
        return motion != null ? motion.getMotionValidState() : UnDefType.UNDEF;
    }

    public @Nullable Power getPowerState() {
        return power_state;
    }

    public State getBatteryLowState() {
        Power power_state = this.power_state;
        return power_state != null ? power_state.getBatteryLowState() : UnDefType.UNDEF;
    }

    public State getBatteryLevelState() {
        Power power_state = this.power_state;
        return power_state != null ? power_state.getBatteryLevelState() : UnDefType.UNDEF;
    }

    /*
     * ++++++++++++++++++++++++++++++++++++++++
     * + Scene Field Getters & Setters
     * ++++++++++++++++++++++++++++++++++++++++
     */
    public @Nullable Recall getRecall() {
        return recall;
    }

    public Resource setRecall(Command command) {
        if (OnOffType.ON.equals(command)) {
            Recall recall = new Recall();
            recall.setAction(RecallAction.ACTIVE);
            this.recall = recall;
        }
        return this;
    }

    /*
     * ++++++++++++++++++++++++++++++++++++++++
     * + Group Field Getters & Setters
     * ++++++++++++++++++++++++++++++++++++++++
     */
    public List<Reference> getChildren() {
        List<Reference> children = this.children;
        return children != null ? children : List.of();
    }

    /*
     * ++++++++++++++++++++++++++++++++++++++++
     * + Zigbee Field Getters & Setters
     * ++++++++++++++++++++++++++++++++++++++++
     */
    public @Nullable ZigBeeState getZigBeeStatus() {
        String status = this.status;
        return status != null ? ZigBeeState.of(status) : null;
    }

    public State getZigBeeState() {
        ZigBeeState zigBeeState = getZigBeeStatus();
        return zigBeeState != null ? new StringType(zigBeeState.toString()) : UnDefType.UNDEF;
    }

    /*
     * ++++++++++++++++++++++++++++++++++++++++
     * + Button Field Getters & Setters
     * ++++++++++++++++++++++++++++++++++++++++
     */
    public State getButtonValueState() {
        Button button = this.button;
        if (button != null) {
            int lastEventValue = button.getLastEvent().ordinal();
            int controlIdValue = 1000;
            MetaData metadata = this.metadata;
            if (metadata != null) {
                controlIdValue = metadata.getControlIdValue() * 1000;
            }
            return new DecimalType(controlIdValue + lastEventValue);
        }
        return UnDefType.UNDEF;
    }
}
