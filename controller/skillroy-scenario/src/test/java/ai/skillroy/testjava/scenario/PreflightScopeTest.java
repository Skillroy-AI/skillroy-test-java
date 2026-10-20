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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.scenario.hooks.HooksMode;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.pack.CheckScope;
import ai.skillroy.testjava.scenario.pack.PackCapability;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pre-flight scope and the suite cache (phase8-contracts.md 9.3, gap 112): the API of Phase 8's early commit (the
 * scope defaults, {@link SuiteCache}, the options' {@code suite} component).
 */
class PreflightScopeTest {

    private static SuiteCache.Key key(String id, String session) {
        return new SuiteCache.Key(SuiteCache.KIND_PREFLIGHT, "toy", "1.0.0", id, "default", session);
    }

    @Test
    void theScopesDefaultToScenarioAndAPackDeclaresNothing() {
        PreflightCheck check = new PreflightCheck() {
            @Override
            public String id() {
                return "desk";
            }

            @Override
            public String check(AgentConnection agent) {
                return null;
            }
        };
        assertEquals(CheckScope.SCENARIO, check.scope());
        PackCapability capability = new PackCapability() {
            @Override
            public String id() {
                return "toy.env.ready";
            }

            @Override
            public Capability evaluate(PreflightCheck.PreflightContext ctx) {
                return new Capability(id(), CapabilityState.OK, null, null);
            }
        };
        assertEquals(CheckScope.SCENARIO, capability.scope());
        assertNull(capability.description());
        ToyPack pack = new ToyPack();
        assertEquals(List.of(), pack.capabilities());
        assertEquals(Set.of(), pack.warningCodes());
        assertEquals(List.of("SCENARIO", "SUITE"), List.of(CheckScope.values()).stream().map(Enum::name).toList());
    }

    @Test
    void theSuiteCacheIsBoundedAndKeyedBySession() {
        assertEquals(SuiteCache.DEFAULT_MAX_ENTRIES, SuiteCache.create().maxEntries());
        assertEquals(1000, SuiteCache.DEFAULT_MAX_ENTRIES);
        assertThrows(IllegalArgumentException.class, () -> SuiteCache.create(0));
        assertThrows(IllegalArgumentException.class, () -> SuiteCache.create(100_001));
        assertEquals(100_000, SuiteCache.create(100_000).maxEntries());

        SuiteCache cache = SuiteCache.create(1);
        assertEquals(0, cache.size());
        cache.put(key("desk", "s-1"), "ok");
        assertEquals("ok", cache.get(key("desk", "s-1")));
        assertNull(cache.get(key("desk", "s-2")), "another agent start is another key");
        assertNull(cache.get(new SuiteCache.Key(SuiteCache.KIND_CAPABILITY, "toy", "1.0.0", "desk", "default",
                "s-1")), "a capability is another kind");
        cache.put(key("printer", "s-1"), "ok");
        assertEquals(1, cache.size(), "full: nothing more is kept");
        assertNull(cache.get(key("printer", "s-1")));
        cache.put(key("desk", "s-1"), "desk closed");
        assertEquals("desk closed", cache.get(key("desk", "s-1")), "the last answer wins");
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get(key("desk", "s-1")));

        assertThrows(NullPointerException.class, () -> key("desk", null), "no session is never cached for");
        assertThrows(IllegalArgumentException.class, () -> new SuiteCache.Key("other", "toy", "1", "desk", "default",
                "s-1"));
        assertThrows(NullPointerException.class, () -> cache.put(key("desk", "s-1"), null));
    }

    @Test
    void theRunAndSessionOptionsCarryTheSuiteCache() {
        SuiteCache cache = SuiteCache.create();
        assertNull(RunOptions.defaults().suite());
        RunOptions run = RunOptions.defaults().withSuite(cache);
        assertSame(cache, run.suite());
        assertSame(cache, run.withRobot(true).withHooks(HooksMode.OFF).withOutputDir(null).withStepTimeout(
                Duration.ofSeconds(3)).withBind(BindOptions.defaults()).withObserver(RunObserver.NONE).suite(),
                "every copy keeps it");
        assertNull(run.withSuite(null).suite());
        RunOptions thirteen = new RunOptions(null, null, null, null, null, null, null, true, BindOptions.defaults(),
                false, HooksMode.AUTO, RunObserver.NONE, true);
        assertNull(thirteen.suite(), "the Phase 7 constructor keeps none");
        assertEquals(true, thirteen.robot());

        assertNull(SessionOptions.defaults(null).suite());
        SessionOptions session = SessionOptions.defaults(null).withSuite(cache);
        assertSame(cache, session.suite());
        assertSame(cache, session.withRobot(true).withHost(Set.of("x")).withId("s-1").withReadOnly(true).suite(),
                "every copy keeps it");
        SessionOptions fourteen = new SessionOptions(null, null, null, null, false, false, null, null, null, null,
                HooksMode.AUTO, RunObserver.NONE, true, Set.of("x"));
        assertNull(fourteen.suite(), "the Phase 7 constructor keeps none");
        assertEquals(Set.of("x"), fourteen.host());
    }
}
