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
package org.openhab.binding.hue.internal.version2;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.hue.internal.dto.ApiVersion;
import org.openhab.binding.hue.internal.dto.FullLight;
import org.openhab.binding.hue.internal.dto.StateUpdate;
import org.openhab.binding.hue.internal.dto.tag.ApiEnum;
import org.openhab.binding.hue.internal.dto.tag.ILight;
import org.openhab.binding.hue.internal.dto.tag.IUpdate;
import org.openhab.binding.hue.internal.dto.v2.Archetype;
import org.openhab.binding.hue.internal.dto.v2.BatteryState;
import org.openhab.binding.hue.internal.dto.v2.Button;
import org.openhab.binding.hue.internal.dto.v2.ButtonEvent;
import org.openhab.binding.hue.internal.dto.v2.Device2;
import org.openhab.binding.hue.internal.dto.v2.Light2;
import org.openhab.binding.hue.internal.dto.v2.LightLevel;
import org.openhab.binding.hue.internal.dto.v2.MetaData;
import org.openhab.binding.hue.internal.dto.v2.Motion;
import org.openhab.binding.hue.internal.dto.v2.Power;
import org.openhab.binding.hue.internal.dto.v2.ProductData;
import org.openhab.binding.hue.internal.dto.v2.Resources;
import org.openhab.binding.hue.internal.dto.v2.Sensor2;
import org.openhab.binding.hue.internal.dto.v2.SensorType;
import org.openhab.binding.hue.internal.dto.v2.Temperature;
import org.openhab.core.library.types.HSBType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * JUnit test for API v2 DTOs.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
class DtoTests {

    private static final Gson GSON = new Gson();

    /**
     * Load the test JSON payload string from a file
     */
    private String load(String fileName) {
        try (FileReader file = new FileReader(String.format("src/test/resources/%s.json", fileName));
                BufferedReader reader = new BufferedReader(file)) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append("\n");
            }
            return builder.toString();
        } catch (IOException e) {
            fail(e.getMessage());
        }
        return "";
    }

    private List<String> files = Arrays.asList(new String[] { "light", "scene", "room", "zone", "bridge_home",
            "grouped_light", "device", "bridge", "device_power", "motion", "temperature", "button", "light_level" });

    @Test
    void testValidJson() {
        for (String file : files) {
            try {
                String json = load(file);
                JsonParser.parseString(json);
            } catch (JsonSyntaxException e) {
                fail(file);
            }
        }
    }

    @Test
    void testDevice2() {
        String json = load("device");
        Resources<Device2> resources = GSON.fromJson(json, Device2.GSON_TYPE);
        assertNotNull(resources);
        List<Device2> list = resources.getResources();
        assertNotNull(list);
        assertEquals(34, list.size());
        boolean itemFound = false;
        for (Device2 item : list) {
            ProductData productData = item.getProductData();
            assertNotNull(productData);
            if (productData.getProductArchetype() == Archetype.BRIDGE_V2) {
                itemFound = true;
                assertEquals("device", item.getType());
                assertEquals("BSB002", productData.getModelId());
                assertEquals("Signify Netherlands B.V.", productData.getManufacturerName());
                assertEquals("Philips hue", productData.getProductName());
                assertEquals("unknown", productData.getHardwarePlatformType());
                assertTrue(productData.getCertified());
                ApiVersion ver = productData.getSoftwareVersion();
                assertEquals(1, ver.getMajor());
                assertEquals(53, ver.getMinor());
                assertEquals(1953188020, ver.getMicro());
                break;
            }
        }
        assertTrue(itemFound);
    }

    @Test
    void testLight2() {
        String json = load("light");
        Resources<Light2> resources = GSON.fromJson(json, Light2.GSON_TYPE);
        assertNotNull(resources);
        List<Light2> list = resources.getResources();
        assertNotNull(list);
        assertEquals(17, list.size());
        int itemFoundCount = 0;
        for (Light2 item : list) {
            MetaData metaData = item.getMetaData();
            if (metaData != null) {
                String name = metaData.getName();
                if (name.contains("Bay Window Lamp")) {
                    itemFoundCount++;
                    assertEquals("light", item.getType());
                    assertEquals(ApiEnum.V2, item.apiVersion());
                    assertEquals(OnOffType.OFF, item.getSwitch());
                    assertEquals(PercentType.HUNDRED, item.getBrightnessState());
                    assertEquals(UnDefType.UNDEF, item.getColorTemperatureState());
                    State state = item.getColor();
                    assertTrue(state instanceof HSBType);
                    float[] xy = Light2.xyFromHsb((HSBType) state);
                    assertEquals(0.6367, xy[0], 0.015); // note: rounding errors !!
                    assertEquals(0.3503, xy[1], 0.015); // note: rounding errors !!
                }
                if (name.contains("Table Lamp A")) {
                    itemFoundCount++;
                    assertEquals("light", item.getType());
                    assertEquals(ApiEnum.V2, item.apiVersion());
                    assertEquals(OnOffType.OFF, item.getSwitch());
                    assertEquals(new PercentType(57), item.getBrightnessState());
                    assertEquals(new PercentType(96), item.getColorTemperatureState());
                    assertEquals(UnDefType.UNDEF, item.getColor());
                }
            }
        }
        assertEquals(2, itemFoundCount);
    }

    @Test
    void testSensor2Button() {
        String json = load("button");
        Resources<Sensor2> resources = GSON.fromJson(json, Sensor2.GSON_TYPE);
        assertNotNull(resources);
        List<Sensor2> list = resources.getResources();
        assertNotNull(list);
        assertEquals(43, list.size());
        Sensor2 item = list.get(0);
        assertEquals("button", item.getType());
        assertEquals(ApiEnum.V2, item.apiVersion());
        assertEquals(SensorType.BUTTON, item.getSensorType());
        Button button = item.getButton();
        assertNotNull(button);
        assertEquals(ButtonEvent.SHORT_RELEASE, button.getLastEvent());
    }

    @Test
    void testSensor2DevicePower() {
        String json = load("device_power");
        Resources<Sensor2> resources = GSON.fromJson(json, Sensor2.GSON_TYPE);
        assertNotNull(resources);
        List<Sensor2> list = resources.getResources();
        assertNotNull(list);
        assertEquals(16, list.size());
        Sensor2 item = list.get(0);
        assertEquals("device_power", item.getType());
        assertEquals(ApiEnum.V2, item.apiVersion());
        assertEquals(SensorType.DEVICE_POWER, item.getSensorType());
        Power power = item.getPowerState();
        assertNotNull(power);
        assertEquals(60, power.getBatteryLevel());
        assertEquals(BatteryState.NORMAL, power.getBatteryState());
    }

    @Test
    void testSensor2Motion() {
        String json = load("motion");
        Resources<Sensor2> resources = GSON.fromJson(json, Sensor2.GSON_TYPE);
        assertNotNull(resources);
        List<Sensor2> list = resources.getResources();
        assertNotNull(list);
        assertEquals(1, list.size());
        Sensor2 item = list.get(0);
        assertEquals("motion", item.getType());
        assertEquals(ApiEnum.V2, item.apiVersion());
        assertEquals(SensorType.MOTION, item.getSensorType());
        Motion motion = item.getMotion();
        assertNotNull(motion);
        assertTrue(motion.isMotion());
        assertTrue(motion.isMotionValid());
    }

    @Test
    void testSensor2Temperature() {
        String json = load("temperature");
        Resources<Sensor2> resources = GSON.fromJson(json, Sensor2.GSON_TYPE);
        assertNotNull(resources);
        List<Sensor2> list = resources.getResources();
        assertNotNull(list);
        assertEquals(1, list.size());
        Sensor2 item = list.get(0);
        assertEquals("temperature", item.getType());
        assertEquals(ApiEnum.V2, item.apiVersion());
        assertEquals(SensorType.TEMPERATURE, item.getSensorType());
        Temperature temperature = item.getTemperature();
        assertNotNull(temperature);
        assertEquals(23.4, temperature.getTemperature(), 0.1);
        assertTrue(temperature.isTemperatureValid());
    }

    @Test
    void testSensor2LightLevel() {
        String json = load("light_level");
        Resources<Sensor2> resources = GSON.fromJson(json, Sensor2.GSON_TYPE);
        assertNotNull(resources);
        List<Sensor2> list = resources.getResources();
        assertNotNull(list);
        assertEquals(1, list.size());
        Sensor2 item = list.get(0);
        assertEquals("light_level", item.getType());
        assertEquals(ApiEnum.V2, item.apiVersion());
        assertEquals(SensorType.LIGHT_LEVEL, item.getSensorType());
        LightLevel lightLevel = item.getLightLevel();
        assertNotNull(lightLevel);
        assertEquals(1234, lightLevel.getLightlevel());
        assertFalse(lightLevel.isLightLevelValid());
    }

    @Test
    void testScene2() {
        String json = load("scene");
    }

    @Test
    void testGroup2() {
        String json = load("grouped_light");
    }

    @Test
    void testClassCasting() {
        Light2 light2;
        IUpdate update;

        // test light casting
        ILight light = new FullLight();
        ((FullLight) light).setId("aardvark");
        FullLight fullLight = light.as(FullLight.class);
        assertEquals("aardvark", fullLight.getId());

        // check type comparison
        assertTrue(fullLight.isSame(light));
        assertTrue(light.isSame(fullLight));

        // check failed light casting
        boolean castFailed;
        try {
            light2 = light.as(Light2.class);
            castFailed = false;
        } catch (ClassCastException e) {
            castFailed = true;
        }
        assertTrue(castFailed);

        // test api v1 update passing
        update = new StateUpdate();
        ((StateUpdate) update).setOn(true);
        StateUpdate stateUpdate = update.as(StateUpdate.class);

        // check failed api 1 update casting
        try {
            light2 = update.as(Light2.class);
            castFailed = false;
        } catch (ClassCastException e) {
            castFailed = true;
        }
        assertTrue(castFailed);

        // test api v2 update passing
        update = new Light2();
        ((Light2) update).setId("aardvark");
        light2 = update.as(Light2.class);
        assertEquals("aardvark", light2.getId());

        // check type comparison
        assertTrue(light2.isSame(update));
        assertTrue(update.isSame(light2));
    }
}
