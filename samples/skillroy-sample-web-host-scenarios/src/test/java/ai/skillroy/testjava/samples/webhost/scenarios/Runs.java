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

package ai.skillroy.testjava.samples.webhost.scenarios;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.samples.webhost.pack.WebHostPack;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.PlanExecutor;
import ai.skillroy.testjava.scenario.RunOptions;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.Status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs plans with the real {@link PlanExecutor} (which finds the CDP path through {@code ServiceLoader<WebPath>} on
 * this module's test class path) and checks what a run wrote. The executor's only timed wait is the pause between
 * expectation attempts (its {@link RunOptions.Sleeper}); every run counts those pauses, so that "zero sleeps" is
 * measured rather than assumed.
 */
final class Runs {

    static final WebHostPack PACK = new WebHostPack();

    /** A run's result, how many times the executor paused, its output directory and its web summary. */
    record Outcome(RunResult result, int sleeps, Path dir, Map<String, Object> web) {

        /** The verdict and, when it is not PASS, why: for assertion messages. */
        String describe() {
            Map<String, Object> detail = new LinkedHashMap<>(result.detail());
            detail.remove("snapshot");
            return result.scenarioId() + " " + result.status() + " " + result.failureKind() + " at "
                    + result.failedStepId() + ": " + result.message() + " detail=" + Json.write(detail) + " web="
                    + Json.write(web) + " (outputs in " + dir + ")";
        }
    }

    private Runs() {
    }

    static Outcome run(AgentConnection agent, Plan plan, Path dir) {
        return run(agent, plan, dir, BindOptions.defaults());
    }

    static Outcome run(AgentConnection agent, Plan plan, Path dir, BindOptions bind) {
        AtomicInteger sleeps = new AtomicInteger();
        RunOptions options = RunOptions.defaults().withOutputDir(dir).withBind(bind).withSleeper(duration -> {
            sleeps.incrementAndGet();
            Thread.sleep(Math.max(1L, duration.toMillis()));
        });
        PlanExecutor.Resolved resolved = new PlanExecutor(PACK, agent, options).runWithOutcome(plan);
        return new Outcome(resolved.result(), sleeps.get(), dir, resolved.web());
    }

    /** A committed plan, read as it is checked in ({@code plan/<id>.plan.json}). */
    static Plan committed(String id) throws Exception {
        return Plan.read(ItEnv.plan(id));
    }

    /** A negative scenario, compiled now. */
    static Plan negative(String file) throws Exception {
        return PlanCompiler.compile(ItEnv.negative(file), PACK);
    }

    /** The records of a run's {@code run.jsonl}. */
    static List<Map<String, Object>> runLog(Path dir) throws IOException {
        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("run.jsonl"), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                records.add(Json.parseObject(line));
            }
        }
        return records;
    }

    /** The records of one type, in order. */
    static List<Map<String, Object>> records(Path dir, String type) throws IOException {
        return runLog(dir).stream().filter(r -> type.equals(r.get("type"))).toList();
    }

    /** The {@code call} records of one primitive, in order (cleanup calls included). */
    static List<Map<String, Object>> calls(Path dir, String primitive) throws IOException {
        return runLog(dir).stream().filter(r -> "call".equals(r.get("type")) && primitive.equals(r.get("primitive")))
                .toList();
    }

    /**
     * A passing run that never paused: PASS, no sleeper call, every expectation held at its first attempt, every
     * wait completed by an event (with its sequence number) or a settled UI, no call at a lower fidelity than asked,
     * and a {@code run.jsonl} from {@code run-start} to {@code run-end}.
     */
    static void assertPassedWithoutSleeping(Outcome outcome) throws IOException {
        assertEquals(Status.PASS, outcome.result().status(), outcome.describe());
        assertEquals(0, outcome.sleeps(), "the executor paused: " + outcome.describe());
        assertEquals(List.of(), outcome.result().degradedFidelity(), outcome.describe());
        List<Map<String, Object>> log = runLog(outcome.dir());
        assertEquals("run-start", log.get(0).get("type"));
        Map<String, Object> end = log.get(log.size() - 1);
        assertEquals("run-end", end.get("type"));
        assertEquals("PASS", end.get("status"));
        int expectations = 0;
        int waits = 0;
        for (Map<String, Object> record : log) {
            if ("expect".equals(record.get("type"))) {
                expectations++;
                assertEquals(1L, record.get("attempts"), "an expectation needed a retry: " + record);
                assertEquals("held", record.get("result"), record.toString());
            }
            if ("sync".equals(record.get("type"))) {
                waits++;
                assertEquals(Boolean.TRUE, record.get("ok"), record.toString());
                assertTrue(record.containsKey("eventSeq") || "SETTLE".equals(record.get("mode")),
                        "a wait ended by an event or the UI settle: " + record);
            }
        }
        assertTrue(expectations > 0, "the scenario checks something");
        assertTrue(waits > 0, "the scenario synchronises on events or the UI");
        assertFalse(log.stream().anyMatch(r -> "fidelity".equals(r.get("type"))), "no degraded-fidelity record");
    }

    /** Every {@code web.*} call record of the run names the path that served it, and returns those paths in order. */
    static List<String> webCallPaths(Path dir) throws IOException {
        List<String> paths = new ArrayList<>();
        for (Map<String, Object> r : runLog(dir)) {
            if ("call".equals(r.get("type")) && String.valueOf(r.get("primitive")).startsWith("web.")) {
                assertTrue(r.get("path") instanceof String, "a web call names its path: " + r);
                paths.add((String) r.get("path"));
            }
        }
        return paths;
    }
}
