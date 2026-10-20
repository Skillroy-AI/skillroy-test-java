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

package ai.skillroy.testjava.agent.core;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.AgentModule;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 8 (phase8-contracts.md 3.2 rule 4, A68): a module's {@code info()} is shown as the {@code info} entry of its
 * record in {@code agent.info.modules[]} when non-empty, only while it is installed; a module that throws is logged
 * once and its entry left out.
 */
class ModuleInfoTest {

    private final RingLog log = new RingLog();

    private static ModuleLoader.Record record(final AtomicReference<Object> info) {
        return new ModuleLoader.Record("m", "1", new AgentModule() {
            @Override
            public String id() {
                return "m";
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public void install(AgentContext ctx) {
            }

            @Override
            @SuppressWarnings("unchecked")
            public Map<String, Object> info() {
                Object v = info.get();
                if (v instanceof RuntimeException) {
                    throw (RuntimeException) v;
                }
                return (Map<String, Object>) v;
            }
        }, "test");
    }

    @Test
    void anInstalledModulesNonEmptyInfoIsItsRecordsInfoEntry() {
        AtomicReference<Object> info = new AtomicReference<Object>(Collections.singletonMap("vetoWithheld", 2L));
        ModuleLoader.Record r = record(info);
        r.log = log;
        assertFalse(r.toJson().containsKey("info"), "not installed yet");
        r.state = "INSTALLED";
        Map<String, Object> json = r.toJson();
        assertEquals(Collections.singletonMap("vetoWithheld", 2L), json.get("info"));
        assertEquals(java.util.Arrays.asList("id", "version", "state", "info"),
                new java.util.ArrayList<String>(json.keySet()), "info is the last key");
        info.set(new LinkedHashMap<String, Object>());
        assertFalse(r.toJson().containsKey("info"), "an empty map is left out");
        info.set(null);
        assertFalse(r.toJson().containsKey("info"), "null is left out");
    }

    @Test
    void aThrowingInfoIsLoggedOnceAndLeftOut() {
        AtomicReference<Object> info = new AtomicReference<Object>(new IllegalStateException("secret detail"));
        ModuleLoader.Record r = record(info);
        r.log = log;
        r.state = "INSTALLED";
        assertFalse(r.toJson().containsKey("info"));
        assertFalse(r.toJson().containsKey("info"));
        List<String> lines = log.tail(100);
        int failures = 0;
        for (String line : lines) {
            if (line.contains("info() failed")) {
                failures++;
                assertTrue(line.contains("IllegalStateException") && !line.contains("secret detail"), line);
            }
        }
        assertEquals(1, failures, String.valueOf(lines));
    }
}
