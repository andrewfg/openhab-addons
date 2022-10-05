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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.hue.internal.dto.v2.Light2;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.HSBType;
import org.openhab.core.library.types.IncreaseDecreaseType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.types.State;

import com.google.gson.Gson;

/**
 * JUnit test for LightV2 DTO for an API v2 light.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class LightV2Test {

    private static final Gson gson = new Gson();

    private static final String json =
    //@formatter:off
            "{\n"
            + "    \"type\": \"light\",\n"
            + "    \"id\": \"c6b028c8-076e-4817-92b1-bcb0cbb78783\",\n"
            + "    \"id_v1\": \"/lights/21\",\n"
            + "    \"metadata\": {\n"
            + "        \"name\": \"Hue downlight right\"\n"
            + "    },\n"
            + "    \"on\": {\n"
            + "        \"on\": true\n"
            + "    },\n"
            + "    \"dimming\": {\n"
            + "        \"brightness\": 100.0\n"
            + "    },\n"
            + "    \"color_temperature\": {\n"
            + "        \"mirek\": 366\n"
            + "    },\n"
            + "    \"color\": {\n"
            + "        \"gamut\": {\n"
            + "            \"blue\": {\n"
            + "                \"x\": 0.1532,\n"
            + "                \"y\": 0.0475\n"
            + "            },\n"
            + "            \"green\": {\n"
            + "                \"x\": 0.17,\n"
            + "                \"y\": 0.7\n"
            + "            },\n"
            + "            \"red\": {\n"
            + "                \"x\": 0.6915,\n"
            + "                \"y\": 0.3083\n"
            + "            }\n"
            + "        },\n"
            + "        \"gamut_type\": \"C\",\n"
            + "        \"xy\": {\n"
            + "            \"x\": 0.4575,\n"
            + "            \"y\": 0.4099\n"
            + "        }\n"
            + "    }\n"
            + "}";
    //@formatter:on

    @Test
    void testCreateFromJson() {
        Light2 light = gson.fromJson(json, Light2.class);
        assertNotNull(light);
        assertEquals("light", light.getType());
        assertEquals("c6b028c8-076e-4817-92b1-bcb0cbb78783", light.getId());
        assertEquals("/lights/21", light.getIdV1());
        assertEquals(OnOffType.ON, light.getSwitch());
        assertEquals(PercentType.HUNDRED, light.getBrightnessState());
        assertEquals(new PercentType(61), light.getColorTemperatureState());
        assertEquals(new DecimalType(2732), light.getColorTemperatureKelvin());
        State state = light.getColor();
        assertTrue(state instanceof HSBType);
        float[] xy = Light2.xyFromHsb((HSBType) state);
        assertEquals(0.4575, xy[0], 0.015); // note rounding !!
        assertEquals(0.4099, xy[1], 0.015); // note rounding !!
    }

    @Test
    void testCreateRaw() {
        Light2 light = new Light2();
        assertNotNull(light);
        light.setType("light");
        light.setId("c6b028c8-076e-4817-92b1-bcb0cbb78783");
        light.setSwitch(OnOffType.ON);
        assertEquals(OnOffType.ON, light.getSwitch());
        light.setSwitch(OnOffType.OFF);
        assertEquals(OnOffType.OFF, light.getSwitch());
        light.setBrightness(PercentType.HUNDRED);
        assertEquals(PercentType.HUNDRED, light.getBrightnessState());
        light.setColorTemperature(new PercentType(61));
        assertEquals(new PercentType(61), light.getColorTemperatureState());
        light.setColorTemperature(new DecimalType(2732));
        assertEquals(new DecimalType(2732), light.getColorTemperatureKelvin());
        light.setColor(Light2.hsbFromXY(new float[] { 0.4575f, 0.4099f }));
        State state = light.getColor();
        assertTrue(state instanceof HSBType);
        float[] xy = Light2.xyFromHsb((HSBType) state);
        assertEquals(0.4575, xy[0], 0.015); // note rounding !!
        assertEquals(0.4099, xy[1], 0.015); // note rounding !!
    }

    @Test
    void testSameState() {
        Light2 lightA = gson.fromJson(json, Light2.class);
        assertNotNull(lightA);
        Light2 lightB = gson.fromJson(json, Light2.class);
        assertNotNull(lightB);
        assertTrue(lightA.isSame(lightB));
        lightA.setBrightness(IncreaseDecreaseType.INCREASE);
        assertTrue(lightA.isSame(lightB));
        lightA.setBrightness(IncreaseDecreaseType.DECREASE);
        assertFalse(lightA.isSame(lightB));
        lightA.setBrightness(OnOffType.ON);
        assertTrue(lightA.isSame(lightB));
    }
}
