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
package org.openhab.binding.hue.internal.clip2;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.hue.internal.clip2.dto.ActionEntry;
import org.openhab.binding.hue.internal.clip2.dto.Alerts;
import org.openhab.binding.hue.internal.clip2.dto.Button;
import org.openhab.binding.hue.internal.clip2.dto.Event;
import org.openhab.binding.hue.internal.clip2.dto.LightLevel;
import org.openhab.binding.hue.internal.clip2.dto.MetaData;
import org.openhab.binding.hue.internal.clip2.dto.Motion;
import org.openhab.binding.hue.internal.clip2.dto.Power;
import org.openhab.binding.hue.internal.clip2.dto.ProductData;
import org.openhab.binding.hue.internal.clip2.dto.Reference;
import org.openhab.binding.hue.internal.clip2.dto.Resource;
import org.openhab.binding.hue.internal.clip2.dto.Resources;
import org.openhab.binding.hue.internal.clip2.dto.Temperature;
import org.openhab.binding.hue.internal.clip2.enums.ActionType;
import org.openhab.binding.hue.internal.clip2.enums.Archetype;
import org.openhab.binding.hue.internal.clip2.enums.BatteryStateType;
import org.openhab.binding.hue.internal.clip2.enums.ButtonEventType;
import org.openhab.binding.hue.internal.clip2.enums.ResourceType;
import org.openhab.binding.hue.internal.clip2.enums.ZigBeeState;
import org.openhab.binding.hue.internal.dto.ApiVersion;
import org.openhab.core.library.types.HSBType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * JUnit test for CLIP 2 DTOs.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
class Clip2DtoTests {

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

    @Test
    void testValidJson() {
        for (ResourceType res : ResourceType.values()) {
            if (!ResourceType.SSE_TYPES.contains(res)) {
                try {
                    String file = res.name().toLowerCase();
                    String json = load(file);
                    JsonParser.parseString(json);
                } catch (JsonSyntaxException e) {
                    fail(res.name());
                }
            }
        }
    }

    @Test
    void testDevice2() {
        String json = load(ResourceType.DEVICE.name().toLowerCase());
        Resources resources = GSON.fromJson(json, Resources.class);
        assertNotNull(resources);
        List<Resource> list = resources.getResources();
        assertNotNull(list);
        assertEquals(34, list.size());
        boolean itemFound = false;
        for (Resource item : list) {
            assertEquals(ResourceType.DEVICE, item.getType());
            ProductData productData = item.getProductData();
            assertNotNull(productData);
            if (productData.getProductArchetype() == Archetype.BRIDGE_V2) {
                itemFound = true;
                assertEquals("BSB002", productData.getModelId());
                assertEquals("Signify Netherlands B.V.", productData.getManufacturerName());
                assertEquals("Philips hue", productData.getProductName());
                assertNull(productData.getHardwarePlatformType());
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
        String json = load(ResourceType.LIGHT.name().toLowerCase());
        Resources resources = GSON.fromJson(json, Resources.class);
        assertNotNull(resources);
        List<Resource> list = resources.getResources();
        assertNotNull(list);
        assertEquals(17, list.size());
        int itemFoundCount = 0;
        for (Resource item : list) {
            assertEquals(ResourceType.LIGHT, item.getType());
            MetaData metaData = item.getMetaData();
            assertNotNull(metaData);
            String name = metaData.getName();
            if (name.contains("Bay Window Lamp")) {
                itemFoundCount++;
                assertEquals(ResourceType.LIGHT, item.getType());
                assertEquals(OnOffType.OFF, item.getSwitch());
                assertEquals(PercentType.HUNDRED, item.getBrightnessState());
                assertEquals(UnDefType.UNDEF, item.getColorTemperatureState());
                State state = item.getColorState();
                assertTrue(state instanceof HSBType);
                float[] xy = Resource.xyFromHsb((HSBType) state);
                assertEquals(0.6367, xy[0], 0.015); // note: rounding errors !!
                assertEquals(0.3503, xy[1], 0.015); // note: rounding errors !!
                Alerts alert = item.getAlert();
                assertNotNull(alert);
                for (ActionType actionValue : alert.getActionValues()) {
                    assertEquals(ActionType.BREATHE, actionValue);
                }
            }
            if (name.contains("Table Lamp A")) {
                itemFoundCount++;
                assertEquals(ResourceType.LIGHT, item.getType());
                assertEquals(OnOffType.OFF, item.getSwitch());
                assertEquals(new PercentType(57), item.getBrightnessState());
                assertEquals(new PercentType(96), item.getColorTemperatureState());
                assertEquals(UnDefType.UNDEF, item.getColorState());
                Alerts alert = item.getAlert();
                assertNotNull(alert);
                for (ActionType actionValue : alert.getActionValues()) {
                    assertEquals(ActionType.BREATHE, actionValue);
                }
            }
        }
        assertEquals(2, itemFoundCount);
    }

    @Test
    void testSensor2Button() {
        String json = load(ResourceType.BUTTON.name().toLowerCase());
        Resources resources = GSON.fromJson(json, Resources.class);
        assertNotNull(resources);
        List<Resource> list = resources.getResources();
        assertNotNull(list);
        assertEquals(43, list.size());
        Resource item = list.get(0);
        assertEquals(ResourceType.BUTTON, item.getType());
        Button button = item.getButton();
        assertNotNull(button);
        assertEquals(ButtonEventType.SHORT_RELEASE, button.getLastEvent());
    }

    @Test
    void testSensor2DevicePower() {
        String json = load(ResourceType.DEVICE_POWER.name().toLowerCase());
        Resources resources = GSON.fromJson(json, Resources.class);
        assertNotNull(resources);
        List<Resource> list = resources.getResources();
        assertNotNull(list);
        assertEquals(16, list.size());
        Resource item = list.get(0);
        assertEquals(ResourceType.DEVICE_POWER, item.getType());
        Power power = item.getPowerState();
        assertNotNull(power);
        assertEquals(60, power.getBatteryLevel());
        assertEquals(BatteryStateType.NORMAL, power.getBatteryState());
    }

    @Test
    void testSensor2Motion() {
        String json = load(ResourceType.MOTION.name().toLowerCase());
        Resources resources = GSON.fromJson(json, Resources.class);
        assertNotNull(resources);
        List<Resource> list = resources.getResources();
        assertNotNull(list);
        assertEquals(1, list.size());
        Resource item = list.get(0);
        assertEquals(ResourceType.MOTION, item.getType());
        Boolean enabled = item.getEnabled();
        assertNotNull(enabled);
        assertTrue(enabled);
        Motion motion = item.getMotion();
        assertNotNull(motion);
        assertTrue(motion.isMotion());
        assertTrue(motion.isMotionValid());
    }

    @Test
    void testSensor2Temperature() {
        String json = load(ResourceType.TEMPERATURE.name().toLowerCase());
        Resources resources = GSON.fromJson(json, Resources.class);
        assertNotNull(resources);
        List<Resource> list = resources.getResources();
        assertNotNull(list);
        assertEquals(1, list.size());
        Resource item = list.get(0);
        assertEquals(ResourceType.TEMPERATURE, item.getType());
        Temperature temperature = item.getTemperature();
        assertNotNull(temperature);
        assertEquals(17.2, temperature.getTemperature(), 0.1);
        assertTrue(temperature.isTemperatureValid());
    }

    @Test
    void testSensor2LightLevel() {
        String json = load(ResourceType.LIGHT_LEVEL.name().toLowerCase());
        Resources resources = GSON.fromJson(json, Resources.class);
        assertNotNull(resources);
        List<Resource> list = resources.getResources();
        assertNotNull(list);
        assertEquals(1, list.size());
        Resource item = list.get(0);
        assertEquals(ResourceType.LIGHT_LEVEL, item.getType());
        Boolean enabled = item.getEnabled();
        assertNotNull(enabled);
        assertTrue(enabled);
        LightLevel lightLevel = item.getLightLevel();
        assertNotNull(lightLevel);
        assertEquals(12725, lightLevel.getLightlevel());
        assertTrue(lightLevel.isLightLevelValid());
    }

    @Test
    void testScene2() {
        String json = load(ResourceType.SCENE.name().toLowerCase());
        Resources resources = GSON.fromJson(json, Resources.class);
        assertNotNull(resources);
        List<Resource> list = resources.getResources();
        assertNotNull(list);
        assertEquals(123, list.size());
        Resource item = list.get(0);
        List<ActionEntry> actions = item.getActions();
        assertNotNull(actions);
        assertEquals(3, actions.size());
        ActionEntry actionEntry = actions.get(0);
        assertNotNull(actionEntry);
        Resource action = actionEntry.getAction();
        assertNotNull(action);
        assertEquals(OnOffType.ON, action.getSwitch());
    }

    @Test
    void testRoomGroup2() {
        String json = load(ResourceType.ROOM.name().toLowerCase());
        Resources resources = GSON.fromJson(json, Resources.class);
        assertNotNull(resources);
        List<Resource> list = resources.getResources();
        assertNotNull(list);
        assertEquals(6, list.size());
        Resource item = list.get(0);
        assertEquals(ResourceType.ROOM, item.getType());
        List<Reference> children = item.getChildren();
        assertEquals(2, children.size());
        Reference child = children.get(0);
        assertNotNull(child);
        assertEquals("0d47bd3d-d82b-4a21-893c-299bff18e22a", child.getId());
        assertEquals(ResourceType.DEVICE, child.getType());
        List<Reference> services = item.getServiceReferences();
        assertEquals(1, services.size());
        Reference service = services.get(0);
        assertNotNull(service);
        assertEquals("08947162-67be-4ed5-bfce-f42dade42416", service.getId());
        assertEquals(ResourceType.GROUPED_LIGHT, service.getType());
    }

    @Test
    void testZoneGroup2() {
        String json = load(ResourceType.ZONE.name().toLowerCase());
        Resources resources = GSON.fromJson(json, Resources.class);
        assertNotNull(resources);
        List<Resource> list = resources.getResources();
        assertNotNull(list);
        assertEquals(7, list.size());
        Resource item = list.get(0);
        assertEquals(ResourceType.ZONE, item.getType());
        List<Reference> children = item.getChildren();
        assertEquals(1, children.size());
        Reference child = children.get(0);
        assertNotNull(child);
        assertEquals("bcad47a0-3f1f-498c-a8aa-3cf389965219", child.getId());
        assertEquals(ResourceType.LIGHT, child.getType());
        List<Reference> services = item.getServiceReferences();
        assertEquals(1, services.size());
        Reference service = services.get(0);
        assertNotNull(service);
        assertEquals("db4fd630-3798-40de-b642-c1ef464bf770", service.getId());
        assertEquals(ResourceType.GROUPED_LIGHT, service.getType());
    }

    @Test
    void testGroupedLight() {
        String json = load(ResourceType.GROUPED_LIGHT.name().toLowerCase());
        Resources resources = GSON.fromJson(json, Resources.class);
        assertNotNull(resources);
        List<Resource> list = resources.getResources();
        assertNotNull(list);
        assertEquals(15, list.size());
        int itemsFound = 0;
        for (Resource item : list) {
            assertEquals(ResourceType.GROUPED_LIGHT, item.getType());
            Alerts alert;
            switch (item.getId()) {
                case "db4fd630-3798-40de-b642-c1ef464bf770":
                    itemsFound++;
                    assertEquals(OnOffType.OFF, item.getSwitch());
                    assertEquals(PercentType.ZERO, item.getBrightnessState());
                    alert = item.getAlert();
                    assertNotNull(alert);
                    for (ActionType actionValue : alert.getActionValues()) {
                        assertEquals(ActionType.BREATHE, actionValue);
                    }
                    break;
                case "9228d710-3c54-4ae4-8c88-bfe57d8fd220":
                    itemsFound++;
                    assertEquals(OnOffType.ON, item.getSwitch());
                    assertEquals(PercentType.HUNDRED, item.getBrightnessState());
                    alert = item.getAlert();
                    assertNotNull(alert);
                    for (ActionType actionValue : alert.getActionValues()) {
                        assertEquals(ActionType.BREATHE, actionValue);
                    }
                    break;
                default:
            }
        }
        assertEquals(2, itemsFound);
    }

    @Test
    void testZigbeeStatus() {
        String json = load(ResourceType.ZIGBEE_CONNECTIVITY.name().toLowerCase());
        Resources resources = GSON.fromJson(json, Resources.class);
        assertNotNull(resources);
        List<Resource> list = resources.getResources();
        assertNotNull(list);
        assertEquals(35, list.size());
        Resource item = list.get(0);
        assertEquals(ResourceType.ZIGBEE_CONNECTIVITY, item.getType());
        ZigBeeState zigbeeState = item.getZigBeeStatus();
        assertNotNull(zigbeeState);
        assertEquals("Connected", zigbeeState.toString());
    }

    @Test
    void testSseEvent() {
        String json = load("event");
        List<Event> eventList = GSON.fromJson(json, Event.EVENT_LIST_TYPE);
        assertNotNull(eventList);
        assertEquals(2, eventList.size());
        Event event = eventList.get(0);
        List<Resource> resources = event.getData();
        assertEquals(9, resources.size());
        for (Resource r : resources) {
            assertNotEquals(ResourceType.ERROR, r.getType());
        }
    }
}
