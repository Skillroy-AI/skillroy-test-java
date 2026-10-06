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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.data.CredentialsException;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.samples.lending.pack.LendingPack;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.PlanExecutor;
import ai.skillroy.testjava.scenario.RunOptions;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.pack.DomainPack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs plans with the real {@link PlanExecutor} and checks what a run wrote. The executor's only timed wait is the
 * pause between expectation attempts (its {@link RunOptions.Sleeper}); every run here counts those pauses, so that
 * "zero sleeps" is measured rather than assumed.
 *
 * <p>Every run binds with the generated credentials of its environment (phase2-contracts.md 13.4): a sample scenario's
 * staff slot declares a {@code credential}, and the password is joined from {@code credentials.yaml} in memory. The
 * runs without explicit bind options are seed A runs ({@code seed-a}, the module's {@code bindings/} root).
 */
final class Runs {

    static final LendingPack PACK = new LendingPack();

    /** A run's result, how many times the executor paused, and its output directory. */
    record Outcome(RunResult result, int sleeps, Path dir) {

        /** The verdict and, when it is not PASS, why: for assertion messages. */
        String describe() {
            Map<String, Object> detail = new LinkedHashMap<>(result.detail());
            detail.remove("snapshot");
            return result.scenarioId() + " " + result.status() + " " + result.failureKind() + " at "
                    + result.failedStepId() + ": " + result.message() + " detail=" + Json.write(detail)
                    + " skipped=" + result.skippedExpectations() + " (outputs in " + dir + ")";
        }
    }

    private static final Map<Env, Credentials> CREDENTIALS = new ConcurrentHashMap<>();

    private Runs() {
    }

    /**
     * The generated credentials of an environment (staff only), written once per JVM into an owner-only directory
     * below {@code java.io.tmpdir} and loaded through the real reader.
     */
    static Credentials credentials(Env env) {
        return CREDENTIALS.computeIfAbsent(env, e -> {
            try {
                Path dir = Files.createTempDirectory("skillroy-it-credentials-" + e.id);
                dir.toFile().deleteOnExit();
                Credentials loaded = SampleCredentials.load(dir, e, null);
                Files.deleteIfExists(dir.resolve(Credentials.FILE_NAME));
                return loaded;
            } catch (IOException | CredentialsException failure) {
                throw new IllegalStateException("could not write the test credentials of " + e.id, failure);
            }
        });
    }

    /** {@code replay} on the environment, with its credentials and the module's {@code bindings/} root. */
    static BindOptions bind(Env env) {
        return BindOptions.defaults().withEnvId(env.id).withBindingsRoot(ScenarioA.bindingsRoot())
                .withCredentials(credentials(env));
    }

    /** A seed A run: {@link #bind}({@code SEED_A}). */
    static Outcome run(AgentConnection agent, Plan plan, Path dir) {
        return run(agent, plan, dir, bind(Env.SEED_A));
    }

    static Outcome run(AgentConnection agent, Plan plan, Path dir, BindOptions bind) {
        return run(PACK, agent, plan, dir, bind);
    }

    /** A run with another pack, for example one that wraps the lending pack to script a failure. */
    static Outcome run(DomainPack pack, AgentConnection agent, Plan plan, Path dir, BindOptions bind) {
        AtomicInteger sleeps = new AtomicInteger();
        RunOptions options = RunOptions.defaults().withOutputDir(dir).withBind(bind).withSleeper(duration -> {
            sleeps.incrementAndGet();
            Thread.sleep(Math.max(1L, duration.toMillis()));
        });
        RunResult result = new PlanExecutor(pack, agent, options).run(plan);
        return new Outcome(result, sleeps.get(), dir);
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

    /**
     * A passing run that never paused: PASS, no sleeper call, every expectation held at its first attempt, every
     * wait completed by an event or a settled UI, no call ran at a lower fidelity than requested (amendment A29), and
     * a {@code run.jsonl} from {@code run-start} to {@code run-end}.
     */
    static void assertPassedWithoutSleeping(Outcome outcome) throws IOException {
        assertEquals(Status.PASS, outcome.result().status(), outcome.describe());
        assertEquals(0, outcome.sleeps(), "the executor paused: " + outcome.describe());
        assertEquals(List.of(), outcome.result().degradedFidelity(), outcome.describe());
        List<Map<String, Object>> log = runLog(outcome.dir());
        assertFalse(log.stream().anyMatch(r -> "fidelity".equals(r.get("type"))), "no degraded-fidelity record");
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
        assertTrue(waits > 0 || log.stream().anyMatch(r -> "sync.await".equals(r.get("primitive"))),
                "the scenario synchronises on events");
        assertFalse(log.stream().anyMatch(r -> "prompt".equals(r.get("type")) && Boolean.FALSE.equals(
                r.get("expected"))), "no unexpected prompt");
    }

    /** The successful {@code call} records of a run's {@code run.jsonl} for one primitive, in order. */
    static List<Map<String, Object>> calls(Path dir, String primitive) throws IOException {
        return runLog(dir).stream().filter(r -> "call".equals(r.get("type")) && primitive.equals(r.get("primitive"))
                && Boolean.TRUE.equals(r.get("ok"))).toList();
    }

    /**
     * The run of {@code basic-loan-ui} clicked through the COMPONENT rung (amendment A29, {@code clickRung:
     * COMPONENT}): each of its {@code count} clicks was sent with {@code rung: COMPONENT}, {@code activateWindow: true}
     * and {@code fallback: ACTION}, and fired on that rung at full fidelity, so the fall-back was never needed.
     */
    static void assertComponentClicksAtFullFidelity(Path dir, int count) throws IOException {
        List<Map<String, Object>> clicks = calls(dir, "swing.click");
        assertEquals(count, clicks.size(), "clicks in " + dir);
        for (Map<String, Object> click : clicks) {
            Map<?, ?> params = (Map<?, ?>) click.get("params");
            assertEquals("COMPONENT", params.get("rung"), String.valueOf(click));
            assertEquals(Boolean.TRUE, params.get("activateWindow"), String.valueOf(click));
            assertEquals("ACTION", params.get("fallback"), String.valueOf(click));
            Map<?, ?> value = (Map<?, ?>) click.get("value");
            assertEquals("COMPONENT", value.get("rung"), "the click fired on the rung asked for: " + click);
            assertEquals("COMPONENT", value.get("requestedRung"), String.valueOf(click));
            assertEquals("FULL", value.get("fidelity"), String.valueOf(click));
            assertEquals(Boolean.TRUE, value.get("fired"), String.valueOf(click));
        }
    }
}
