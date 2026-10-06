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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Install order by requires(): chains, failed and missing requirements, cycles and misbehaving modules. */
class ModuleOrderTest {

    private final RingLog log = new RingLog();
    private final List<String> installed = new ArrayList<String>();

    private static ModuleLoader.Record module(final String id, final String... requires) {
        return new ModuleLoader.Record(id, "1", new AgentModule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public Set<String> requires() {
                if (requires.length == 1 && "<throws>".equals(requires[0])) {
                    throw new IllegalStateException("requires boom");
                }
                return requires.length == 1 && "<null>".equals(requires[0]) ? null
                        : new HashSet<String>(Arrays.asList(requires));
            }

            @Override
            public void install(AgentContext ctx) {
            }
        }, "test");
    }

    private void install(List<ModuleLoader.Record> records, final String... failing) {
        new ModuleLoader(log).installAll(records, new ModuleLoader.Installer() {
            @Override
            public void install(ModuleLoader.Record record) {
                if (Arrays.asList(failing).contains(record.id)) {
                    record.fail("install failed");
                } else {
                    record.state = "INSTALLED";
                    installed.add(record.id);
                }
            }
        });
    }

    @Test
    void requirementsComeFirstAndDiscoveryOrderBreaksTies() {
        List<ModuleLoader.Record> records = Arrays.asList(module("d", "c"), module("c", "b", "a"), module("x"),
                module("b", "a"), module("a"), module("y", "<null>"));
        install(records);
        assertEquals(Arrays.asList("x", "a", "b", "c", "d", "y"), installed);
    }

    @Test
    void cyclesSelfRequirementsAndFailedRequirementsFailOnlyTheModulesInvolved() {
        ModuleLoader.Record selfish = module("selfish", "selfish");
        ModuleLoader.Record p = module("p", "q");
        ModuleLoader.Record q = module("q", "p");
        ModuleLoader.Record broken = module("broken");
        ModuleLoader.Record needsBroken = module("needs.broken", "broken");
        ModuleLoader.Record transitively = module("transitively", "needs.broken");
        ModuleLoader.Record throwing = module("throwing", "<throws>");
        ModuleLoader.Record fine = module("fine");
        install(Arrays.asList(selfish, p, q, broken, needsBroken, transitively, throwing, fine), "broken");
        assertEquals(Arrays.asList("fine"), installed);
        for (ModuleLoader.Record cyclic : new ModuleLoader.Record[] {selfish, p, q}) {
            assertEquals("FAILED", cyclic.state, cyclic.id);
            assertTrue(cyclic.message.contains("dependency cycle"), cyclic.message);
        }
        assertEquals("FAILED", needsBroken.state);
        assertTrue(needsBroken.message.contains("'broken'"), needsBroken.message);
        assertEquals("FAILED", transitively.state);
        assertTrue(transitively.message.contains("'needs.broken'"), transitively.message);
        assertEquals("FAILED", throwing.state);
        assertTrue(throwing.message.contains("requires boom"), throwing.message);
        assertTrue(String.valueOf(log.tail(50)).contains("module p (test) FAILED"));
    }
}
