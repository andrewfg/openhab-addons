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
package org.openhab.binding.shelly.internal.api1;

import static org.openhab.binding.shelly.internal.ShellyBindingConstants.*;
import static org.openhab.binding.shelly.internal.api1.Shelly1ApiJsonDTO.*;
import static org.openhab.binding.shelly.internal.util.ShellyUtils.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.shelly.internal.api1.Shelly1CoapJSonDTO.CoIotDescrBlk;
import org.openhab.binding.shelly.internal.api1.Shelly1CoapJSonDTO.CoIotDescrSen;
import org.openhab.binding.shelly.internal.api1.Shelly1CoapJSonDTO.CoIotSensor;
import org.openhab.binding.shelly.internal.handler.ShellyColorUtils;
import org.openhab.binding.shelly.internal.handler.ShellyThingInterface;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link Shelly1CoIoTVersion1} implements the parsing for CoIoT version 1
 *
 * @author Markus Michels - Initial contribution
 */
@NonNullByDefault
public class Shelly1CoIoTVersion1 extends Shelly1CoIoTProtocol implements Shelly1CoIoTInterface {
    private static final String DESC_TEMPERATURE = "temperature";
    private static final String DESC_TEMPERATURE_F = "temperature f";
    private static final String DESC_TEMPERATURE_C = "temperature c";
    private static final String DESC_EXTERNAL_TEMPERATURE_F = "external temperature f";
    private static final String DESC_EXTERNAL_TEMPERATURE_C = "external temperature c";
    private static final String DESC_EXTERNAL_TEMPERATURE = "external_temperature";
    private static final String DESC_OVERTEMP = "overtemp";
    private static final String DESC_ENERGY_COUNTER_0 = "energy counter 0 [w-min]";
    private static final String DESC_ENERGY_COUNTER_1 = "energy counter 1 [w-min]";
    private static final String DESC_ENERGY_COUNTER_2 = "energy counter 2 [w-min]";
    private static final String DESC_ENERGY_COUNTER_TOTAL_WH = "energy counter total [w-h]";
    private static final String DESC_ENERGY_COUNTER_TOTAL_WMIN = "energy counter total [w-min]";
    private static final String DESC_VOLTAGE = "voltage";
    private static final String DESC_CURRENT = "current";
    private static final String DESC_POSITION = "position";
    private static final String DESC_INPUT_EVENT = "input event";
    private static final String DESC_INPUT_EVENT_COUNTER = "input event counter";
    private static final String DESC_FLOOD = "flood";
    private static final String DESC_TILT = "tilt";
    private static final String DESC_COLOR_TEMPERATURE = "colortemperature";
    private static final String DESC_SENSOR_STATE = "sensor state";
    private static final String DESC_ALARM_STATE = "alarm state";
    private static final String DESC_SELF_TEST_STATE = "self-test state";
    private static final String DESC_CONCENTRATION = "concentration";
    private static final String DESC_SENSOR_ERROR = "sensorerror";
    private static final String DESC_POWER = "power";
    private static final String DESC_VSWITCH = "vswitch";
    private static final String TYPE_OVER_TEMP = "Overtemp";
    private static final String TYPE_W = "w";
    private static final String TYPE_RELAY0 = "relay0";
    private static final String TYPE_SWITCH = "switch";
    private static final String TYPE_STATE = "State";
    private static final String TYPE_MOTION = "motion";
    private static final String TYPE_BATTERY = "battery";
    private static final String TYPE_E_CNT_0 = "e cnt 0 [w-min]";
    private static final String TYPE_E_CNT_1 = "e cnt 1 [w-min]";
    private static final String TYPE_E_CNT_2 = "e cnt 2 [w-min]";
    private static final String TYPE_E_CNT_TOTAL = "e cnt total [w-min]";
    private static final String TYPE_E_CNT = "e cnt";
    private static final String TYPE_ENERGY_COUNTER = "energy counter";
    private static final String TYPE_INPUT = "input";
    private static final String TYPE_OUTPUT = "output";
    private static final String TYPE_TOSTATE = "tostate";
    private static final String TEXT_POWER = "Power";
    private static final String TEXT_TEMPERATURE_C = "Temperature C";
    private static final String TEXT_TEMPERATURE_F = "Temperature F";
    private static final String TEXT_MOTION = "Motion";
    private static final String TEXT_BATTERY = "Battery";
    private static final String TEXT_TEMPERATURE = "Temperature";
    private static final String TEXT_INPUT = "Input";
    private static final String TEXT_OUTPUT = "Output";
    private static final String TEXT_BRIGHTNESS = "Brightness";
    private static final String LOG_EXT_SENSOR_ID = "{}: Unable to get extSensorId {} from {}/{}";
    private static final String LOG_UNKNOWN_TEMPERATURE = "{}: Unknown temperature type: {}";

    private final Logger logger = LoggerFactory.getLogger(Shelly1CoIoTVersion1.class);

    public Shelly1CoIoTVersion1(String thingName, ShellyThingInterface thingHandler, Map<String, CoIotDescrBlk> blkMap,
            Map<String, CoIotDescrSen> sensorMap) {
        super(thingName, thingHandler, blkMap, sensorMap);
    }

    @Override
    public int getVersion() {
        return Shelly1CoapJSonDTO.COIOT_VERSION_1;
    }

    /**
     * Process CoIoT status update message. If a status update is received, but the device description has not been
     * received yet a GET is send to query device description.
     *
     * @param sensorUpdates
     * @param sen
     * @param serial Serial for this request. If this the the same as last serial
     *            the update was already sent and processed so this one gets
     *            ignored.
     * @param serial
     * @param s
     * @param updates
     * @param col
     */
    @Override
    public boolean handleStatusUpdate(List<CoIotSensor> sensorUpdates, CoIotDescrSen sen, int serial, CoIotSensor s,
            Map<String, State> updates, ShellyColorUtils col) {
        // first check the base implementation
        if (super.handleStatusUpdate(sensorUpdates, sen, s, updates, col)) {
            // process by the base class
            return true;
        }

        // Process status information and convert into channel updates
        Integer rIndex = Integer.parseInt(sen.links) + 1;
        String rGroup = getProfile().numRelays <= 1 ? CHANNEL_GROUP_RELAY_CONTROL
                : CHANNEL_GROUP_RELAY_CONTROL + rIndex;
        switch (sen.type.toLowerCase(Locale.ROOT)) {
            case "t": // Temperature +
                Double value = getDouble(s.value);
                switch (sen.desc.toLowerCase(Locale.ROOT)) {
                    case DESC_TEMPERATURE: // Sensor Temp
                        if (getString(getProfile().settings.temperatureUnits)
                                .equalsIgnoreCase(SHELLY_TEMP_FAHRENHEIT)) {
                            value = ImperialUnits.FAHRENHEIT.getConverterTo(SIUnits.CELSIUS).convert(getDouble(s.value))
                                    .doubleValue();
                        }
                        updateChannel(updates, CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_TEMP,
                                toQuantityType(value, DIGITS_TEMP, SIUnits.CELSIUS));
                        break;
                    case DESC_TEMPERATURE_F: // Device Temp -> ignore (we use C only)
                        break;
                    case DESC_TEMPERATURE_C: // Device Temp in C
                        // Device temperature
                        updateChannel(updates, CHANNEL_GROUP_DEV_STATUS, CHANNEL_DEVST_ITEMP,
                                toQuantityType(value, DIGITS_NONE, SIUnits.CELSIUS));
                        break;
                    case DESC_EXTERNAL_TEMPERATURE_F: // Shelly 1/1PM external temp sensors
                        // ignore F, we use C only
                        break;
                    case DESC_EXTERNAL_TEMPERATURE_C: // Shelly 1/1PM external temp sensors
                    case DESC_EXTERNAL_TEMPERATURE:
                        int idx = getExtTempId(sen.id);
                        if (idx > 0) {
                            updateChannel(updates, CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_TEMP + idx,
                                    toQuantityType(value, DIGITS_TEMP, SIUnits.CELSIUS));
                        } else {
                            logger.debug(LOG_EXT_SENSOR_ID, thingName, sen.id, sen.type, sen.desc);
                        }
                        break;
                    default:
                        logger.debug(LOG_UNKNOWN_TEMPERATURE, thingName, sen.desc);
                }
                break;
            case "p": // Power/Watt
                // 3EM uses 1-based meter IDs, other 0-based
                String mGroup = profile.numMeters == 1 ? CHANNEL_GROUP_METER
                        : CHANNEL_GROUP_METER + (profile.isEMeter ? sen.links : rIndex);
                updateChannel(updates, mGroup, CHANNEL_METER_CURRENTWATTS,
                        toQuantityType(s.value, DIGITS_WATT, Units.WATT));
                updateChannel(updates, mGroup, CHANNEL_LAST_UPDATE, getTimestamp());
                break;
            case "s" /* CatchAll */:
                switch (sen.desc.toLowerCase(Locale.ROOT)) {
                    case DESC_OVERTEMP:
                        if (s.value == 1) {
                            thingHandler.postEvent(ALARM_TYPE_OVERTEMP, true);
                        }
                        break;
                    case DESC_ENERGY_COUNTER_0:
                        // lastPower1 (W, backward compat) has no dual-write mapping — the W state is
                        // incompatible with the Wh channel — so both channels are written explicitly.
                        updateChannel(updates, rGroup, CHANNEL_METER_LASTMIN1,
                                toQuantityType(s.value, DIGITS_WATT, Units.WATT));
                        updateChannel(updates, rGroup, CHANNEL_METER_ENERGYHISTMIN1,
                                toQuantityType(s.value / 60.0, DIGITS_KWH, Units.WATT_HOUR));
                        break;
                    case DESC_ENERGY_COUNTER_1:
                        updateChannel(updates, rGroup, CHANNEL_METER_ENERGYHISTMIN2,
                                toQuantityType(s.value / 60.0, DIGITS_KWH, Units.WATT_HOUR));
                        break;
                    case DESC_ENERGY_COUNTER_2:
                        // energyAvgLast3Min is not computed here: each counter arrives as an independent
                        // CoIoT event, so the poll path remains the only source for that average
                        updateChannel(updates, rGroup, CHANNEL_METER_ENERGYHISTMIN3,
                                toQuantityType(s.value / 60.0, DIGITS_KWH, Units.WATT_HOUR));
                        break;
                    case DESC_ENERGY_COUNTER_TOTAL_WH: // 3EM reports W/h
                    case DESC_ENERGY_COUNTER_TOTAL_WMIN:
                        Double total = profile.isEMeter ? s.value / 1000 : s.value / 60 / 1000;
                        updateChannel(updates, rGroup, CHANNEL_METER_TOTALKWH,
                                toQuantityType(total, DIGITS_KWH, Units.KILOWATT_HOUR));
                        break;
                    case DESC_VOLTAGE:
                        updateChannel(updates, rGroup, CHANNEL_EMETER_VOLTAGE,
                                toQuantityType(getDouble(s.value), DIGITS_VOLT, Units.VOLT));
                        break;
                    case DESC_CURRENT:
                        updateChannel(updates, rGroup, CHANNEL_EMETER_CURRENT,
                                toQuantityType(getDouble(s.value), DIGITS_AMPERE, Units.AMPERE));
                        break;
                    case "pf":
                        updateChannel(updates, rGroup, CHANNEL_EMETER_PFACTOR, getDecimal(s.value));
                        break;
                    case DESC_POSITION:
                        // work around: Roller reports 101% instead max 100
                        double pos = Math.max(SHELLY_MIN_ROLLER_POS, Math.min(s.value, SHELLY_MAX_ROLLER_POS));
                        updateChannel(updates, CHANNEL_GROUP_ROL_CONTROL, CHANNEL_ROL_CONTROL_CONTROL,
                                toQuantityType(SHELLY_MAX_ROLLER_POS - pos, Units.PERCENT));
                        updateChannel(updates, CHANNEL_GROUP_ROL_CONTROL, CHANNEL_ROL_CONTROL_POS,
                                toQuantityType(pos, Units.PERCENT));
                        break;
                    case DESC_INPUT_EVENT: // Shelly Button 1
                        handleInputEvent(sen, getString(s.valueStr), -1, serial, updates);
                        break;
                    case DESC_INPUT_EVENT_COUNTER: // Shelly Button 1/ix3
                        handleInputEvent(sen, "", getInteger((int) s.value), serial, updates);
                        break;
                    case DESC_FLOOD:
                        updateChannel(updates, CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_FLOOD,
                                OnOffType.from(s.value == 1));
                        break;
                    case DESC_TILT: // DW with FW1.6.5+ //+
                        updateChannel(updates, CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_TILT,
                                toQuantityType(s.value, DIGITS_NONE, Units.DEGREE_ANGLE));
                        break;
                    case SHELLY_EVENT_VIBRATION: // DW with FW1.6.5+
                        if (profile.isMotion) {
                            // handle as status
                            updateChannel(updates, CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_VIBRATION,
                                    OnOffType.from(s.value == 1));
                        } else if (s.value == 1) {
                            // handle as event
                            thingHandler.triggerChannel(CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_ALARM_STATE,
                                    EVENT_TYPE_VIBRATION);
                        }
                        break;
                    case SHELLY_COLOR_TEMP: // Shelly Bulb
                    case DESC_COLOR_TEMPERATURE: // Shelly Duo
                        updateChannel(updates,
                                profile.inColor ? CHANNEL_GROUP_COLOR_CONTROL : CHANNEL_GROUP_WHITE_CONTROL,
                                CHANNEL_COLOR_TEMP,
                                ShellyColorUtils.toPercent((int) s.value, profile.minTemp, profile.maxTemp));
                        break;
                    case DESC_SENSOR_STATE: // Shelly Gas
                        updateChannel(updates, CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_SSTATE, getStringType(s.valueStr));
                        break;
                    case DESC_ALARM_STATE: // Shelly Gas
                        updateChannel(updates, CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_ALARM_STATE,
                                getStringType(s.valueStr));
                        break;
                    case DESC_SELF_TEST_STATE:// Shelly Gas
                        updateChannel(updates, CHANNEL_GROUP_DEV_STATUS, CHANNEL_DEVST_SELFTTEST,
                                getStringType(s.valueStr));
                        break;
                    case DESC_CONCENTRATION:// Shelly Gas
                        updateChannel(updates, CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_PPM,
                                toQuantityType(getDouble(s.value), DIGITS_NONE, Units.PARTS_PER_MILLION));
                        break;
                    case DESC_SENSOR_ERROR:
                        updateChannel(updates, CHANNEL_GROUP_SENSOR, CHANNEL_SENSOR_ERROR, getStringType(s.valueStr));
                        break;
                    default:
                        // Unknown
                        return false;
                }
                break;

            default:
                // Unknown type
                return false;
        }
        return true;
    }

    /**
     *
     * Depending on the device type and firmware release there are significant bugs or incosistencies in the CoIoT
     * Device Description returned by the discovery request. Shelly is even not following it's own speicifcation. All of
     * that has been reported to Shelly and acknowledged. Firmware 1.6 brought significant improvements. However, the
     * old mapping stays in to support older firmware releases.
     *
     * @param sen Sensor description received from device
     * @return fixed Sensor description (sen)
     */
    @Override
    public CoIotDescrSen fixDescription(CoIotDescrSen sen, Map<String, CoIotDescrBlk> blkMap) {
        // Shelly1: reports null descr+type "Switch" -> map to S
        // Shelly1PM: reports null descr+type "Overtemp" -> map to O
        // Shelly1PM: reports null descr+type "W" -> add description
        // Shelly1PM: reports temp senmsors without desc -> add description
        // Shelly Dimmer: sensors are reported without descriptions -> map to S
        // SHelly Sense: multiple issues: Description should not be lower case, invalid type for Motion and Battery
        // Shelly Sense: Battery is reported with Desc "battery", but type "H" instead of "B"
        // Shelly Sense: Motion is reported with Desc "battery", but type "H" instead of "B"
        // Shelly Bulb: Colors are coded with Type="Red" etc. rather than Type="S" and color as Descr
        // Shelly RGBW2 is reporting Brightness, Power, VSwitch for each channel, but all with L=0
        if (sen.desc == null) {
            sen.desc = "";
        }
        if (sen.type == null) {
            sen.type = "";
        }
        String desc = sen.desc.toLowerCase(Locale.ROOT);

        // RGBW2 reports Power_0, Power_1, Power_2, Power_3; same for VSwitch and Brightness, all of them linkted to L:0
        // we break it up to Power with L:0, Power with L:1...
        if (desc.contains("_")
                && (desc.contains(DESC_POWER) || desc.contains(DESC_VSWITCH) || desc.contains(SHELLY_COLOR_BRIGHTNESS))) {
            String newDesc = substringBefore(sen.desc, "_");
            String newLink = substringAfter(sen.desc, "_");
            sen.desc = newDesc;
            sen.links = newLink;
            if (!blkMap.containsKey(sen.links)) {
                // auto-insert a matching blk entry
                CoIotDescrBlk blk = new CoIotDescrBlk();
                CoIotDescrBlk blk0 = blkMap.get("0"); // blk 0 is always there
                blk.id = sen.links;
                if (blk0 != null) {
                    blk.desc = blk0.desc + "_" + blk.id;
                    blkMap.put(blk.id, blk);
                }
            }
        }

        switch (sen.type.toLowerCase(Locale.ROOT)) {
            case TYPE_W: // old devices/firmware releases use "W", new ones "P"
                sen.type = "P";
                sen.desc = TEXT_POWER;
                break;
            case "tc":
                sen.type = "T";
                sen.desc = TEXT_TEMPERATURE_C;
                break;
            case "tf":
                sen.type = "T";
                sen.desc = TEXT_TEMPERATURE_F;
                break;
            case DESC_OVERTEMP:
                sen.type = "S";
                sen.desc = TYPE_OVER_TEMP;
                break;
            case TYPE_RELAY0:
            case TYPE_SWITCH:
            case DESC_VSWITCH:
                sen.type = "S";
                sen.desc = TYPE_STATE;
                break;
        }

        switch (sen.desc.toLowerCase(Locale.ROOT)) {
            case TYPE_MOTION: // fix acc to spec it's T=M
                sen.type = "M";
                sen.desc = TEXT_MOTION;
                break;
            case TYPE_BATTERY: // fix: type is B not H
                sen.type = "B";
                sen.desc = TEXT_BATTERY;
                break;
            case DESC_OVERTEMP:
                sen.type = "S";
                sen.desc = TYPE_OVER_TEMP;
                break;
            case TYPE_RELAY0:
            case TYPE_SWITCH:
            case DESC_VSWITCH:
                sen.type = "S";
                sen.desc = TYPE_STATE;
                break;
            case TYPE_E_CNT_0: // 4 Pro
            case TYPE_E_CNT_1:
            case TYPE_E_CNT_2:
            case TYPE_E_CNT_TOTAL: // 4 Pro
                sen.desc = sen.desc.toLowerCase(Locale.ROOT).replace(TYPE_E_CNT, TYPE_ENERGY_COUNTER);
                break;

        }

        if (sen.desc.isEmpty()) {
            switch (sen.type.toLowerCase(Locale.ROOT)) {
                case "p":
                    sen.desc = TEXT_POWER;
                    break;
                case "t":
                    sen.desc = TEXT_TEMPERATURE;
                    break;
                case TYPE_INPUT:
                    sen.type = "S";
                    sen.desc = TEXT_INPUT;
                    break;
                case TYPE_OUTPUT:
                    sen.type = "S";
                    sen.desc = TEXT_OUTPUT;
                    break;
                case SHELLY_COLOR_BRIGHTNESS:
                    sen.type = "S";
                    sen.desc = TEXT_BRIGHTNESS;
                    break;
                case SHELLY_COLOR_RED:
                case SHELLY_COLOR_GREEN:
                case SHELLY_COLOR_BLUE:
                case SHELLY_COLOR_WHITE:
                case SHELLY_COLOR_GAIN:
                case SHELLY_COLOR_TEMP: // Bulb: Color temperature
                    sen.desc = sen.type;
                    sen.type = "S";
                    break;
                case DESC_VSWITCH:
                    // it seems that Shelly tends to break their own spec: T is the description and D is no longer
                    // included -> map D to sen.T and set CatchAll for T
                    sen.desc = sen.type;
                    sen.type = "S";
                    break;
                // Default: set no description
                // (there are no T values defined in the CoIoT spec)
                case TYPE_TOSTATE:
                default:
                    sen.desc = "";
            }
        }
        return sen;
    }
}
