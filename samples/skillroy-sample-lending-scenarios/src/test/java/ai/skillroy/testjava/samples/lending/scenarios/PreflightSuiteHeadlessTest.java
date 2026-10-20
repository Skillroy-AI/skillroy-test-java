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

import ai.skillroy.testjava.junit.ScenarioSource;
import ai.skillroy.testjava.junit.ScenarioTests;
import ai.skillroy.testjava.samples.lending.pack.LendingPack;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.RunOptions;
import ai.skillroy.testjava.scenario.SuiteCache;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * phase8-contracts.md 9.3, 12.3, 13.1 row 50: the lending pack's {@code lending.database} check is {@code SUITE}-scoped,
 * so a JUnit glue stream of three committed seed-A scenarios on one headless desk evaluates it once (its record
 * {@code cached: false} in the first scenario, {@code cached: true} in the two others); a second desk is a new agent
 * start (a new session), so the same cache evaluates it again there. The scenarios' own outcomes do not matter here
 * (a run reaches the pre-flight before its bind): only the pre-flight records are read.
 */
class PreflightSuiteHeadlessTest {

    /** Three committed seed-A scenarios without launch properties, in the order the glue runs them (by file name). */
    static final List<String> FILES = List.of("return-over-limit-needs-supervisor.yaml", "return-refunds-deposit.yaml",
            "return-within-limit-observed.yaml");

    @TempDir
    Path dir;

    private Path scenarios() throws Exception {
        Path copied = Files.createDirectories(dir.resolve("scenarios"));
        for (String file : FILES) {
            Files.copy(ItEnv.scenario(file), copied.resolve(file));
        }
        return copied;
    }

    private static String id(String file) {
        return file.substring(0, file.length() - ".yaml".length());
    }

    /** Runs every test of the stream; a failing scenario is fine here. */
    private static void runAll(List<DynamicTest> tests) {
        for (DynamicTest test : tests) {
            try {
                test.getExecutable().execute();
            } catch (Throwable ignored) {
                // Only the pre-flight records matter.
            }
        }
    }

    private static List<Object> cached(Path out) throws Exception {
        List<Object> cached = new ArrayList<>();
        for (String file : FILES) {
            List<Map<String, Object>> log = Runs.runLog(out.resolve(id(file)));
            List<Map<String, Object>> records = log.stream().filter(r -> "preflight".equals(r.get("type"))).toList();
            assertEquals(1, records.size(), file + ": " + log);
            assertEquals(LendingPack.PREFLIGHT_DATABASE, records.get(0).get("id"));
            assertEquals(Boolean.TRUE, records.get(0).get("ok"), String.valueOf(records.get(0)));
            assertEquals("suite", records.get(0).get("scope"));
            cached.add(records.get(0).get("cached"));
        }
        return cached;
    }

    @Test
    void aStreamChecksTheDatabaseOncePerDesk() throws Exception {
        Path source = scenarios();
        SuiteCache cache = SuiteCache.create();
        for (String desk : List.of("first", "second")) {
            try (HeadlessDesk headless = HeadlessDesk.launch(Env.SEED_A, false, true)) {
                Path out = dir.resolve(desk);
                BindOptions bind = BindOptions.defaults().withEnvId(Env.SEED_A.id).withBindingsRoot(
                        ScenarioA.bindingsRoot()).withCredentials(headless.credentials(dir.resolve("credentials-"
                        + desk))).withLedger(new CostLedger());
                runAll(ScenarioTests.dynamicTests(ScenarioSource.directory(source), () -> headless, RunOptions
                        .defaults().withOutputDir(out).withBind(bind).withSuite(cache), Runs.PACK).toList());
                assertEquals(List.of(false, true, true), cached(out), desk + " desk: evaluated once, by its first"
                        + " scenario");
            }
        }
        assertEquals(2, cache.size(), "one answer per agent session");
    }
}
