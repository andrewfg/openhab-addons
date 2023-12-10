/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openhab.binding.hue.internal.api.dto.clip2.Resource;
import org.openhab.binding.hue.internal.api.dto.clip2.enums.ResourceType;
import org.openhab.binding.hue.internal.api.dto.clip2.helper.Setters;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * JUnit test for sorting the sequence of scene events in a list.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
class SortSceneResourcesTest {

    private static final JsonElement STATIC = JsonParser.parseString("{\"active\":\"static\"}");
    private static final JsonElement INACTIVE = JsonParser.parseString("{\"active\":\"inactive\"}");

    @Test
    void testSceneSorting() {
        List<Resource> list = new ArrayList<>();
        list.add(new Resource(ResourceType.LIGHT).setId("0"));
        list.add(new Resource(ResourceType.SCENE).setId("1").setStatus(STATIC));
        list.add(new Resource(ResourceType.LIGHT).setId("2"));
        list.add(new Resource(ResourceType.SCENE).setId("3").setStatus(INACTIVE));
        list.add(new Resource(ResourceType.LIGHT).setId("4"));
        list.forEach(r -> r.markAsSparse());

        assertEquals(5, list.size());
        assertEquals("0", list.get(0).getId());
        assertEquals("1", list.get(1).getId());
        assertEquals("2", list.get(2).getId());
        assertEquals("3", list.get(3).getId());
        assertEquals("4", list.get(4).getId());

        assertTrue(list.get(1).getSceneActive().get());
        assertFalse(list.get(3).getSceneActive().get());

        Setters.sortSceneResources(list);

        assertEquals(5, list.size());
        assertEquals("0", list.get(0).getId());
        assertEquals("2", list.get(1).getId());
        assertEquals("3", list.get(2).getId());
        assertEquals("4", list.get(3).getId());
        assertEquals("1", list.get(4).getId());

        assertFalse(list.get(2).getSceneActive().get());
        assertTrue(list.get(4).getSceneActive().get());
    }

    private static final Map<String, Resource> sceneContributorsCache = new HashMap<>();

    @BeforeAll
    static void initializeCache() {
        Resource one = new Resource(ResourceType.SCENE).setId("1").setStatus(STATIC);
        Resource three = new Resource(ResourceType.SCENE).setId("3").setStatus(STATIC);
        sceneContributorsCache.put(one.getId(), one);
        sceneContributorsCache.put(three.getId(), three);
    }

    @Test
    void testTransitionA() {
        List<Resource> list = new ArrayList<>();
        list.add(new Resource(ResourceType.LIGHT).setId("0"));
        list.add(new Resource(ResourceType.SCENE).setId("1").setStatus(STATIC));
        list.add(new Resource(ResourceType.LIGHT).setId("2"));
        list.add(new Resource(ResourceType.SCENE).setId("3").setStatus(INACTIVE));
        list.add(new Resource(ResourceType.LIGHT).setId("4"));
        System.out.println("testTransitionA");
        onResources(list);
        System.out.println();
    }

    @Test
    void testTransitionB() {
        List<Resource> list = new ArrayList<>();
        list.add(new Resource(ResourceType.LIGHT).setId("0"));
        list.add(new Resource(ResourceType.SCENE).setId("1").setStatus(INACTIVE));
        list.add(new Resource(ResourceType.LIGHT).setId("2"));
        list.add(new Resource(ResourceType.SCENE).setId("3").setStatus(STATIC));
        list.add(new Resource(ResourceType.LIGHT).setId("4"));
        System.out.println("testTransitionB");
        onResources(list);
        System.out.println();
    }

    @Test
    void testTransitionC() {
        List<Resource> list = new ArrayList<>();
        list.add(new Resource(ResourceType.LIGHT).setId("0"));
        list.add(new Resource(ResourceType.SCENE).setId("1").setStatus(STATIC));
        list.add(new Resource(ResourceType.LIGHT).setId("2"));
        list.add(new Resource(ResourceType.SMART_SCENE).setId("3").setState("inactive"));
        list.add(new Resource(ResourceType.LIGHT).setId("4"));
        System.out.println("testTransitionC");
        onResources(list);
        System.out.println();
    }

    @Test
    void testTransitionD() {
        List<Resource> list = new ArrayList<>();
        list.add(new Resource(ResourceType.LIGHT).setId("0"));
        list.add(new Resource(ResourceType.SMART_SCENE).setId("1").setState("active"));
        list.add(new Resource(ResourceType.LIGHT).setId("2"));
        list.add(new Resource(ResourceType.SCENE).setId("3").setStatus(INACTIVE));
        list.add(new Resource(ResourceType.LIGHT).setId("4"));
        System.out.println("testTransitionD");
        onResources(list);
        System.out.println();
    }

    @Test
    void testTransitionE() {
        List<Resource> list = new ArrayList<>();
        list.add(new Resource(ResourceType.LIGHT).setId("0"));
        list.add(new Resource(ResourceType.SMART_SCENE).setId("1").setState("active"));
        list.add(new Resource(ResourceType.LIGHT).setId("2"));
        list.add(new Resource(ResourceType.SMART_SCENE).setId("3").setState("inactive"));
        list.add(new Resource(ResourceType.LIGHT).setId("4"));
        System.out.println("testTransitionE");
        onResources(list);
        System.out.println();
    }

    @Test
    void testTransitionF() {
        List<Resource> list = new ArrayList<>();
        list.add(new Resource(ResourceType.LIGHT).setId("0"));
        list.add(new Resource(ResourceType.SCENE).setId("1").setStatus(STATIC));
        list.add(new Resource(ResourceType.LIGHT).setId("2"));
        list.add(new Resource(ResourceType.SCENE).setId("3").setStatus(STATIC));
        list.add(new Resource(ResourceType.LIGHT).setId("4"));
        System.out.println("testTransitionF");
        onResources(list);
        System.out.println();
    }

    @Test
    void testTransitionG() {
        List<Resource> list = new ArrayList<>();
        list.add(new Resource(ResourceType.LIGHT).setId("0"));
        list.add(new Resource(ResourceType.SMART_SCENE).setId("1").setState("active"));
        list.add(new Resource(ResourceType.LIGHT).setId("2"));
        list.add(new Resource(ResourceType.SMART_SCENE).setId("3").setState("active"));
        list.add(new Resource(ResourceType.LIGHT).setId("4"));
        System.out.println("testTransitionG");
        onResources(list);
        System.out.println();
    }

    @Test
    void testTransitionH() {
        List<Resource> list = new ArrayList<>();
        list.add(new Resource(ResourceType.LIGHT).setId("0"));
        list.add(new Resource(ResourceType.SCENE).setId("1x").setStatus(STATIC));
        list.add(new Resource(ResourceType.LIGHT).setId("2"));
        list.add(new Resource(ResourceType.SCENE).setId("3").setStatus(INACTIVE));
        list.add(new Resource(ResourceType.LIGHT).setId("4"));
        System.out.println("testTransitionH");
        onResources(list);
        System.out.println();
    }

    @Test
    void testTransitionI() {
        List<Resource> list = new ArrayList<>();
        list.add(new Resource(ResourceType.LIGHT).setId("0"));
        list.add(new Resource(ResourceType.SCENE).setId("1").setStatus(STATIC));
        list.add(new Resource(ResourceType.LIGHT).setId("2"));
        list.add(new Resource(ResourceType.SCENE).setId("3x").setStatus(INACTIVE));
        list.add(new Resource(ResourceType.LIGHT).setId("4"));
        System.out.println("testTransitionI");
        onResources(list);
        System.out.println();
    }

    boolean testJlaurCode = false;// true;

    public void onResources(Collection<Resource> resources) {
        if (testJlaurCode) {
            onResourcesJlaur(resources);
        } else {
            onResourcesAndreFG(resources);
        }
    }

    public void onResourcesAndreFG(Collection<Resource> resources) {
        boolean sceneActivated = resources.stream().anyMatch(r -> sceneContributorsCache.containsKey(r.getId())
                && (r.getSceneActive().orElse(false) || r.getSmartSceneActive().orElse(false)));
        resources.forEach(r -> {
            boolean skipUpdate = sceneActivated && sceneContributorsCache.containsKey(r.getId())
                    && (!r.getSceneActive().orElse(true) || !r.getSmartSceneActive().orElse(true));
            System.out.println(String.format("Resource %s => %s", r.toString(), !skipUpdate ? "processed" : "skipped"));
        });
    }

    public void onResourcesJlaur(Collection<Resource> resources) {
        boolean sceneActivated = resources.stream()
                .anyMatch(r -> sceneContributorsCache.containsKey(r.getId()) && r.getSceneActive().orElse(false)
                        || r.getSmartSceneActive().orElse(false));
        for (Resource resource : resources) {
            boolean skipUpdate = sceneActivated && sceneContributorsCache.containsKey(resource.getId())
                    && (resource.getSceneActive().isPresent() || resource.getSmartSceneActive().isPresent());
            System.out.println(
                    String.format("Resource %s => %s", resource.toString(), !skipUpdate ? "processed" : "skipped"));
        }
    }

}
