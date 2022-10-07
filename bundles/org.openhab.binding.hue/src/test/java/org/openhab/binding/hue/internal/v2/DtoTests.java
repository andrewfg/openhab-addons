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
package org.openhab.binding.hue.internal.v2;

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
import org.openhab.binding.hue.internal.v2.database.ResourceDatabase;
import org.openhab.binding.hue.internal.v2.dto.ActionEntry;
import org.openhab.binding.hue.internal.v2.dto.Alerts;
import org.openhab.binding.hue.internal.v2.dto.Button;
import org.openhab.binding.hue.internal.v2.dto.Device2;
import org.openhab.binding.hue.internal.v2.dto.Group2;
import org.openhab.binding.hue.internal.v2.dto.GroupedLight;
import org.openhab.binding.hue.internal.v2.dto.Light2;
import org.openhab.binding.hue.internal.v2.dto.LightLevel;
import org.openhab.binding.hue.internal.v2.dto.LightState;
import org.openhab.binding.hue.internal.v2.dto.MetaData;
import org.openhab.binding.hue.internal.v2.dto.Motion;
import org.openhab.binding.hue.internal.v2.dto.Power;
import org.openhab.binding.hue.internal.v2.dto.ProductData;
import org.openhab.binding.hue.internal.v2.dto.Reference;
import org.openhab.binding.hue.internal.v2.dto.Resources;
import org.openhab.binding.hue.internal.v2.dto.Scene2;
import org.openhab.binding.hue.internal.v2.dto.Sensor2;
import org.openhab.binding.hue.internal.v2.dto.Temperature;
import org.openhab.binding.hue.internal.v2.enums.ActionType;
import org.openhab.binding.hue.internal.v2.enums.ApiType;
import org.openhab.binding.hue.internal.v2.enums.Archetype;
import org.openhab.binding.hue.internal.v2.enums.BatteryStateType;
import org.openhab.binding.hue.internal.v2.enums.ButtonEventType;
import org.openhab.binding.hue.internal.v2.enums.ResourceType;
import org.openhab.binding.hue.internal.v2.interfaces.ILight;
import org.openhab.binding.hue.internal.v2.interfaces.IUpdate;
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
        String json = load(ResourceType.DEVICE.name().toLowerCase());
        Resources<Device2> resources = GSON.fromJson(json, Device2.GSON_TYPE);
        assertNotNull(resources);
        List<Device2> list = resources.getResources();
        assertNotNull(list);
        assertEquals(34, list.size());
        boolean itemFound = false;
        for (Device2 item : list) {
            assertEquals(ResourceType.DEVICE, item.getType());
            ProductData productData = item.getProductData();
            assertNotNull(productData);
            if (productData.getProductArchetype() == Archetype.BRIDGE_V2) {
                itemFound = true;
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
        String json = load(ResourceType.LIGHT.name().toLowerCase());
        Resources<Light2> resources = GSON.fromJson(json, Light2.GSON_TYPE);
        assertNotNull(resources);
        List<Light2> list = resources.getResources();
        assertNotNull(list);
        assertEquals(17, list.size());
        int itemFoundCount = 0;
        for (Light2 item : list) {
            assertEquals(ResourceType.LIGHT, item.getType());
            MetaData metaData = item.getMetaData();
            assertNotNull(metaData);
            String name = metaData.getName();
            if (name.contains("Bay Window Lamp")) {
                itemFoundCount++;
                assertEquals(ResourceType.LIGHT, item.getType());
                assertEquals(ApiType.V2, item.apiVersion());
                assertEquals(OnOffType.OFF, item.getSwitch());
                assertEquals(PercentType.HUNDRED, item.getBrightnessState());
                assertEquals(UnDefType.UNDEF, item.getColorTemperatureState());
                State state = item.getColor();
                assertTrue(state instanceof HSBType);
                float[] xy = LightState.xyFromHsb((HSBType) state);
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
                assertEquals(ApiType.V2, item.apiVersion());
                assertEquals(OnOffType.OFF, item.getSwitch());
                assertEquals(new PercentType(57), item.getBrightnessState());
                assertEquals(new PercentType(96), item.getColorTemperatureState());
                assertEquals(UnDefType.UNDEF, item.getColor());
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
        Resources<Sensor2> resources = GSON.fromJson(json, Sensor2.GSON_TYPE);
        assertNotNull(resources);
        List<Sensor2> list = resources.getResources();
        assertNotNull(list);
        assertEquals(43, list.size());
        Sensor2 item = list.get(0);
        assertEquals(ApiType.V2, item.apiVersion());
        assertEquals(ResourceType.BUTTON, item.getType());
        Button button = item.getButton();
        assertNotNull(button);
        assertEquals(ButtonEventType.SHORT_RELEASE, button.getLastEvent());
    }

    @Test
    void testSensor2DevicePower() {
        String json = load(ResourceType.DEVICE_POWER.name().toLowerCase());
        Resources<Sensor2> resources = GSON.fromJson(json, Sensor2.GSON_TYPE);
        assertNotNull(resources);
        List<Sensor2> list = resources.getResources();
        assertNotNull(list);
        assertEquals(16, list.size());
        Sensor2 item = list.get(0);
        assertEquals(ApiType.V2, item.apiVersion());
        assertEquals(ResourceType.DEVICE_POWER, item.getType());
        Power power = item.getPowerState();
        assertNotNull(power);
        assertEquals(60, power.getBatteryLevel());
        assertEquals(BatteryStateType.NORMAL, power.getBatteryState());
    }

    @Test
    void testSensor2Motion() {
        String json = load(ResourceType.MOTION.name().toLowerCase());
        Resources<Sensor2> resources = GSON.fromJson(json, Sensor2.GSON_TYPE);
        assertNotNull(resources);
        List<Sensor2> list = resources.getResources();
        assertNotNull(list);
        assertEquals(1, list.size());
        Sensor2 item = list.get(0);
        assertEquals(ApiType.V2, item.apiVersion());
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
        Resources<Sensor2> resources = GSON.fromJson(json, Sensor2.GSON_TYPE);
        assertNotNull(resources);
        List<Sensor2> list = resources.getResources();
        assertNotNull(list);
        assertEquals(1, list.size());
        Sensor2 item = list.get(0);
        assertEquals(ApiType.V2, item.apiVersion());
        assertEquals(ResourceType.TEMPERATURE, item.getType());
        Temperature temperature = item.getTemperature();
        assertNotNull(temperature);
        assertEquals(17.2, temperature.getTemperature(), 0.1);
        assertTrue(temperature.isTemperatureValid());
    }

    @Test
    void testSensor2LightLevel() {
        String json = load(ResourceType.LIGHT_LEVEL.name().toLowerCase());
        Resources<Sensor2> resources = GSON.fromJson(json, Sensor2.GSON_TYPE);
        assertNotNull(resources);
        List<Sensor2> list = resources.getResources();
        assertNotNull(list);
        assertEquals(1, list.size());
        Sensor2 item = list.get(0);
        assertEquals(ApiType.V2, item.apiVersion());
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
        Resources<Scene2> resources = GSON.fromJson(json, Scene2.GSON_TYPE);
        assertNotNull(resources);
        List<Scene2> list = resources.getResources();
        assertNotNull(list);
        assertEquals(123, list.size());
        Scene2 item = list.get(0);
        List<ActionEntry> actions = item.getActions();
        assertNotNull(actions);
        assertEquals(3, actions.size());
        ActionEntry actionEntry = actions.get(0);
        assertNotNull(actionEntry);
        LightState action = actionEntry.getAction();
        assertNotNull(action);
        assertEquals(OnOffType.ON, action.getSwitch());
    }

    @Test
    void testRoomGroup2() {
        String json = load(ResourceType.ROOM.name().toLowerCase());
        Resources<Group2> resources = GSON.fromJson(json, Group2.GSON_TYPE);
        assertNotNull(resources);
        List<Group2> list = resources.getResources();
        assertNotNull(list);
        assertEquals(6, list.size());
        Group2 item = list.get(0);
        assertEquals(ResourceType.ROOM, item.getType());
        List<Reference> children = item.getChildren();
        assertEquals(2, children.size());
        Reference child = children.get(0);
        assertNotNull(child);
        assertEquals("0d47bd3d-d82b-4a21-893c-299bff18e22a", child.getReferenceId());
        assertEquals(ResourceType.DEVICE, child.getReferenceResourceType());
        List<Reference> services = item.getServices();
        assertEquals(1, services.size());
        Reference service = services.get(0);
        assertNotNull(service);
        assertEquals("08947162-67be-4ed5-bfce-f42dade42416", service.getReferenceId());
        assertEquals(ResourceType.GROUPED_LIGHT, service.getReferenceResourceType());
    }

    @Test
    void testZoneGroup2() {
        String json = load(ResourceType.ZONE.name().toLowerCase());
        Resources<Group2> resources = GSON.fromJson(json, Group2.GSON_TYPE);
        assertNotNull(resources);
        List<Group2> list = resources.getResources();
        assertNotNull(list);
        assertEquals(7, list.size());
        Group2 item = list.get(0);
        assertEquals(ResourceType.ZONE, item.getType());
        List<Reference> children = item.getChildren();
        assertEquals(1, children.size());
        Reference child = children.get(0);
        assertNotNull(child);
        assertEquals("bcad47a0-3f1f-498c-a8aa-3cf389965219", child.getReferenceId());
        assertEquals(ResourceType.LIGHT, child.getReferenceResourceType());
        List<Reference> services = item.getServices();
        assertEquals(1, services.size());
        Reference service = services.get(0);
        assertNotNull(service);
        assertEquals("db4fd630-3798-40de-b642-c1ef464bf770", service.getReferenceId());
        assertEquals(ResourceType.GROUPED_LIGHT, service.getReferenceResourceType());
    }

    @Test
    void testGroupedLight() {
        String json = load(ResourceType.GROUPED_LIGHT.name().toLowerCase());
        Resources<GroupedLight> resources = GSON.fromJson(json, GroupedLight.GSON_TYPE);
        assertNotNull(resources);
        List<GroupedLight> list = resources.getResources();
        assertNotNull(list);
        assertEquals(15, list.size());
        int itemsFound = 0;
        for (GroupedLight item : list) {
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
        assertNotNull(stateUpdate);

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

    @Test
    void testObjectPool() {
        final ResourceDatabase db = new ResourceDatabase();
        String json;
        Resources<?> res;

        json = load(ResourceType.LIGHT_LEVEL.name().toLowerCase());
        res = GSON.fromJson(json, Sensor2.GSON_TYPE);
        if (res != null) {
            db.putAll(res);
        }

        json = load(ResourceType.BUTTON.name().toLowerCase());
        res = GSON.fromJson(json, Sensor2.GSON_TYPE);
        if (res != null) {
            db.putAll(res);
        }

        json = load(ResourceType.TEMPERATURE.name().toLowerCase());
        res = GSON.fromJson(json, Sensor2.GSON_TYPE);
        if (res != null) {
            db.putAll(res);
        }

        json = load(ResourceType.MOTION.name().toLowerCase());
        res = GSON.fromJson(json, Sensor2.GSON_TYPE);
        if (res != null) {
            db.putAll(res);
        }

        json = load(ResourceType.DEVICE_POWER.name().toLowerCase());
        res = GSON.fromJson(json, Sensor2.GSON_TYPE);
        if (res != null) {
            db.putAll(res);
        }

        json = load(ResourceType.DEVICE.name().toLowerCase());
        res = GSON.fromJson(json, Device2.GSON_TYPE);
        if (res != null) {
            db.putAll(res);
        }

        json = load(ResourceType.GROUPED_LIGHT.name().toLowerCase());
        res = GSON.fromJson(json, GroupedLight.GSON_TYPE);
        if (res != null) {
            db.putAll(res);
        }

        json = load(ResourceType.ZONE.name().toLowerCase());
        res = GSON.fromJson(json, Group2.GSON_TYPE);
        if (res != null) {
            db.putAll(res);
        }

        json = load(ResourceType.ROOM.name().toLowerCase());
        res = GSON.fromJson(json, Group2.GSON_TYPE);
        if (res != null) {
            db.putAll(res);
        }

        json = load(ResourceType.SCENE.name().toLowerCase());
        res = GSON.fromJson(json, Scene2.GSON_TYPE);
        if (res != null) {
            db.putAll(res);
        }

        json = load(ResourceType.LIGHT.name().toLowerCase());
        res = GSON.fromJson(json, Light2.GSON_TYPE);
        if (res != null) {
            db.putAll(res);
        }

        // ensure that all lights have a valid owner device
        json = load(ResourceType.LIGHT.name().toLowerCase());
        Resources<Light2> lightResources = GSON.fromJson(json, Light2.GSON_TYPE);
        assertNotNull(lightResources);
        for (Light2 light : lightResources.getResources()) {
            assertNotNull(db.getAs(light.getId()));
            Reference owner = light.getOwner();
            assertNotNull(owner);
            assertNotNull(db.getAs(owner.getReferenceId()));
            assertEquals(ResourceType.DEVICE, owner.getReferenceResourceType());
        }

        // ensure that all (say) button sensors have a valid owner device
        json = load(ResourceType.BUTTON.name().toLowerCase());
        Resources<Sensor2> sensorResources = GSON.fromJson(json, Sensor2.GSON_TYPE);
        assertNotNull(sensorResources);
        for (Sensor2 sensor : sensorResources.getResources()) {
            assertNotNull(db.getAs(sensor.getId()));
            Reference owner = sensor.getOwner();
            assertNotNull(owner);
            assertNotNull(db.getAs(owner.getReferenceId()));
            assertEquals(ResourceType.DEVICE, owner.getReferenceResourceType());
        }

        // devices are not permitted to have an owner
        json = load(ResourceType.DEVICE.name().toLowerCase());
        Resources<Device2> deviceResources = GSON.fromJson(json, Device2.GSON_TYPE);
        assertNotNull(deviceResources);
        for (Device2 device : deviceResources.getResources()) {
            assertNotNull(db.getAs(device.getId()));
            assertNull(device.getOwner());
        }

        // scenes must have no owner, but must have a valid group and actions
        json = load(ResourceType.SCENE.name().toLowerCase());
        Resources<Scene2> sceneResources = GSON.fromJson(json, Scene2.GSON_TYPE);
        assertNotNull(sceneResources);
        for (Scene2 scene : sceneResources.getResources()) {
            assertNotNull(db.getAs(scene.getId()));
            assertNull(scene.getOwner());
            Reference group = scene.getGroup();
            assertNotNull(group);
            assertNotNull(db.getAs(group.getReferenceId()));
            boolean hasActions = false;
            for (ActionEntry action : scene.getActions()) {
                hasActions = true;
                Reference target = action.getTarget();
                assertNotNull(db.getAs(target.getReferenceId()));
            }
            assertTrue(hasActions);
        }

        // zone groups must have no owner, but must have valid services (group lights) and children (lights)
        json = load(ResourceType.ZONE.name().toLowerCase());
        Resources<Group2> groupResources = GSON.fromJson(json, Group2.GSON_TYPE);
        assertNotNull(groupResources);
        for (Group2 group : groupResources.getResources()) {
            assertNotNull(db.getAs(group.getId()));
            assertNull(group.getOwner());
            boolean hasService = false;
            for (Reference service : group.getServices()) {
                assertNotNull(db.getAs(service.getReferenceId()));
                assertEquals(ResourceType.GROUPED_LIGHT, service.getReferenceResourceType());
                hasService = true;
            }
            assertTrue(hasService);
            boolean hasChild = false;
            for (Reference child : group.getChildren()) {
                assertNotNull(db.getAs(child.getReferenceId()));
                assertEquals(ResourceType.LIGHT, child.getReferenceResourceType());
                hasChild = true;
            }
            assertTrue(hasChild);
        }

        // room groups must have no owner, but must have valid services (grouped lights) and children (devices)
        json = load(ResourceType.ROOM.name().toLowerCase());
        Resources<Group2> roomResources = GSON.fromJson(json, Group2.GSON_TYPE);
        assertNotNull(roomResources);
        for (Group2 group : roomResources.getResources()) {
            assertNotNull(db.getAs(group.getId()));
            assertNull(group.getOwner());
            boolean hasService = false;
            for (Reference service : group.getServices()) {
                assertNotNull(db.getAs(service.getReferenceId()));
                assertEquals(ResourceType.GROUPED_LIGHT, service.getReferenceResourceType());
                hasService = true;
            }
            assertTrue(hasService);
            boolean hasChild = false;
            for (Reference child : group.getChildren()) {
                assertNotNull(db.getAs(child.getReferenceId()));
                assertEquals(ResourceType.DEVICE, child.getReferenceResourceType());
                hasChild = true;
            }
            assertTrue(hasChild);
        }
    }
}
