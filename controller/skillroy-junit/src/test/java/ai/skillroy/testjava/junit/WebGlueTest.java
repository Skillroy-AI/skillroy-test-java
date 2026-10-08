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

package ai.skillroy.testjava.junit;

import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.scenario.RunOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase4-contracts.md 12.1 item 6, the glue's part: a {@code WebPath} on the class path is found through the loader
 * the glue finds packs with and serves the web calls; without {@code web.cdp} the agent serves them; nothing about
 * web paths makes a scenario DEGRADED, and the DEGRADED message is the one it was.
 */
class WebGlueTest {

    private static final String WEB = "scenario: mini-web\npack: mini\nsteps:\n"
            + "  - webClick: { role: button, name: Borrow }\n";

    private final MiniAgent agent = new MiniAgent();

    @BeforeEach
    void setUp() {
        MiniCdpPath.SERVED.clear();
        MiniCdpPath.refuse = false;
        for (String id : List.of("web.browsers", "web.evaluate")) {
            agent.extra.put(id, new Capability(id, CapabilityState.OK, null, null));
        }
    }

    @AfterEach
    void tearDown() {
        MiniCdpPath.refuse = false;
    }

    private DynamicTest test(Path dir, String yaml, Path out) throws Exception {
        Files.writeString(dir.resolve("s.yaml"), yaml);
        return ScenarioTests.dynamicTests(ScenarioSource.directory(dir), () -> agent, RunOptions.defaults()
                .withOutputDir(out)).toList().get(0);
    }

    @Test
    void theGlueFindsAClassPathWebPath(@TempDir Path dir) throws Throwable {
        agent.extra.put("web.cdp", new Capability("web.cdp", CapabilityState.OK, null, null));
        Path out = dir.resolve("out");
        test(Files.createDirectories(dir.resolve("s")), WEB, out).getExecutable().execute();
        assertEquals(List.of("web.dom.click"), MiniCdpPath.SERVED);
        String log = Files.readString(out.resolve("mini-web/run.jsonl"));
        assertTrue(log.contains("\"type\":\"web.path\",") && log.contains("\"path\":\"cdp\""), log);
    }

    @Test
    void withoutWebCdpOrWhenThePathRefusesTheAgentServesAndTheScenarioPasses(@TempDir Path dir) throws Throwable {
        Path out = dir.resolve("out");
        test(Files.createDirectories(dir.resolve("a")), WEB, out).getExecutable().execute();
        assertEquals(List.of(), MiniCdpPath.SERVED);
        assertTrue(Files.readString(out.resolve("mini-web/run.jsonl")).contains("\"reason\":\"capability web.cdp is"
                + " missing\""));

        agent.extra.put("web.cdp", new Capability("web.cdp", CapabilityState.OK, null, null));
        MiniCdpPath.refuse = true;
        test(Files.createDirectories(dir.resolve("b")), WEB, out).getExecutable().execute();
        String log = Files.readString(out.resolve("mini-web/run.jsonl"));
        assertTrue(log.contains("\"type\":\"web.fallback\""), "a fall-back is recorded, and is no degradation: "
                + log);
    }

    @Test
    void theDegradedMessageIsUnchanged(@TempDir Path dir) throws Exception {
        agent.extra.put("web.cdp", new Capability("web.cdp", CapabilityState.OK, null, null));
        String yaml = "scenario: mini-degraded\npack: mini\nsteps:\n"
                + "  - webClick: { role: button, name: Borrow }\n"
                + "  - expect: { \"app.mini.state\": { equals: IDLE, capability: mini.optional } }\n";
        TestAbortedException degraded = assertThrows(TestAbortedException.class,
                () -> test(Files.createDirectories(dir.resolve("d")), yaml, dir.resolve("out")).getExecutable()
                        .execute());
        assertEquals("mini-degraded DEGRADED: skipped expectations: s2: app.mini.state {\"equals\":\"IDLE\"} skipped:"
                + " capability mini.optional is missing", degraded.getMessage());
        assertEquals(List.of("web.dom.click"), MiniCdpPath.SERVED);
    }
}
