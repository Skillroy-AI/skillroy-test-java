/*
 * Copyright 2026 Mike Mills.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The golden normalisation (phase6-contracts.md 8.2, 13.1 item 7): ids, states and strategy strict, reason by presence,
 * the allow-list, the diff text; and the committed goldens are well formed (one per load mode, ids sorted, the hooks
 * leg's with {@code hook.engine} and no {@code hook.<id>}).
 */
class CapabilityGoldenTest {

    private static CapabilityMatrix matrix(Capability... capabilities) {
        Map<String, Capability> map = new LinkedHashMap<>();
        for (Capability c : capabilities) {
            map.put(c.id(), c);
        }
        return new CapabilityMatrix(map);
    }

    private final Map<String, Object> golden = CapabilityGolden.normalise(matrix(
            new Capability("swing.tree", CapabilityState.OK, null, "edt"),
            new Capability("jvm.instrumentation", CapabilityState.UNAVAILABLE, "no Instrumentation in EMBEDDED mode",
                    null)));

    @Test
    void reasonsCountByPresenceOnly() {
        Map<?, ?> entries = (Map<?, ?>) golden.get("capabilities");
        assertEquals(List.of("jvm.instrumentation", "swing.tree"), List.copyOf(entries.keySet()), "ids sorted");
        assertEquals(Map.of("state", "UNAVAILABLE", "reason", true), withoutNull((Map<?, ?>) entries.get(
                "jvm.instrumentation")));
        Map<String, Object> otherWording = CapabilityGolden.normalise(matrix(
                new Capability("swing.tree", CapabilityState.OK, null, "edt"),
                new Capability("jvm.instrumentation", CapabilityState.UNAVAILABLE, "another JDK's message", null)));
        assertNull(CapabilityGolden.compare(golden, otherWording, List.of()));
    }

    @Test
    void idsStatesAndStrategiesAreStrictAndTheDiffShowsWhy() {
        Map<String, Object> extra = CapabilityGolden.normalise(matrix(
                new Capability("swing.tree", CapabilityState.DEGRADED, "slow", "edt"),
                new Capability("jvm.instrumentation", CapabilityState.UNAVAILABLE, "x", null),
                new Capability("hook.engine", CapabilityState.OK, null, "advice")));
        String diff = CapabilityGolden.compare(golden, extra, List.of());
        assertTrue(diff.startsWith("--- golden\n+++ live\n"), diff);
        assertTrue(diff.contains("+hook.engine {\"state\":\"OK\",\"strategy\":\"advice\",\"reason\":false}"), diff);
        assertTrue(diff.contains("-swing.tree {\"state\":\"OK\",\"strategy\":\"edt\",\"reason\":false}"), diff);
        assertTrue(diff.contains("+swing.tree {\"state\":\"DEGRADED\",\"strategy\":\"edt\",\"reason\":true}"), diff);
        assertTrue(diff.contains(" jvm.instrumentation"), "an unchanged line is context: " + diff);
        Map<String, Object> strategy = CapabilityGolden.normalise(matrix(
                new Capability("swing.tree", CapabilityState.OK, null, "robot"),
                new Capability("jvm.instrumentation", CapabilityState.UNAVAILABLE, "x", null)));
        assertTrue(CapabilityGolden.compare(golden, strategy, List.of()).contains("robot"));
        Map<String, Object> missing = CapabilityGolden.normalise(matrix(
                new Capability("swing.tree", CapabilityState.OK, null, "edt")));
        assertTrue(CapabilityGolden.compare(golden, missing, List.of()).contains("-jvm.instrumentation"));
    }

    @Test
    void theAllowListExplainsAStateDifference() {
        Map<String, Object> degraded = CapabilityGolden.normalise(matrix(
                new Capability("swing.tree", CapabilityState.DEGRADED, "slow on this JDK", "edt"),
                new Capability("jvm.instrumentation", CapabilityState.UNAVAILABLE, "x", null)));
        List<CapabilityGolden.Allowed> allow = List.of(new CapabilityGolden.Allowed("swing.tree",
                List.of("OK", "DEGRADED"), "slower on one JDK"));
        assertNull(CapabilityGolden.compare(golden, degraded, allow));
        List<CapabilityGolden.Allowed> other = List.of(new CapabilityGolden.Allowed("swing.tree",
                List.of("OK", "UNAVAILABLE"), "not this pair"));
        assertTrue(CapabilityGolden.compare(golden, degraded, other) != null);
    }

    /** The committed files: the allow-list empty at first, each golden sorted and well formed. */
    @Test
    void theCommittedGoldensAreWellFormed() throws Exception {
        Path dir = CapabilityGolden.dir();
        assertEquals(List.of(), CapabilityGolden.allowed(dir.resolve("allow-differences.json")), "empty at first");
        List<String> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("allow-differences.json", "attach.json", "embedded.json", "java-tool-options.json",
                "javaagent-hooks.json", "javaagent.json"), files);
        for (String file : files) {
            if (file.equals("allow-differences.json")) {
                continue;
            }
            Map<?, ?> json = Json.parseObject(Files.readString(dir.resolve(file)));
            Map<?, ?> entries = (Map<?, ?>) json.get("capabilities");
            assertEquals(entries.keySet().stream().map(String::valueOf).sorted().toList(),
                    entries.keySet().stream().map(String::valueOf).toList(), file + ": ids sorted");
            boolean hooksLeg = file.equals("javaagent-hooks.json");
            assertEquals(hooksLeg, entries.containsKey("hook.engine"), file);
            // BW (phase6-contracts.md 13.2 item 7): the hooks leg loads the optional Byteman module too.
            assertEquals(hooksLeg, entries.containsKey("rule.engine"), file);
            assertTrue(entries.keySet().stream().noneMatch(id -> String.valueOf(id).startsWith("hook.")
                    && !"hook.engine".equals(id)), file + ": read before any catalogue is installed");
        }
    }

    private static Map<Object, Object> withoutNull(Map<?, ?> map) {
        Map<Object, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (v != null) {
                out.put(k, v);
            }
        });
        return out;
    }
}
