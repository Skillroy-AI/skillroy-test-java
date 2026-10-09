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

package ai.skillroy.testjava.telemetry;

import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.TelemetryFixture;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.observe.RunStart;
import ai.skillroy.testjava.scenario.observe.StepStart;
import ai.skillroy.testjava.scenario.observe.TelemetrySettings;
import ai.skillroy.testjava.scenario.observe.ToolCallRecord;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The span builder behind the in-memory exporter (phase6-contracts.md 10.4, 10.6, 13.1 item 10): the span tree of a
 * passing, a failing, a {@code SYNC_TIMEOUT} and a {@code REBIND_NEEDED} run against the fake agent; the statuses; the
 * {@code gen_ai} span from a ledger entry with details; the {@code agent} span centred inside its call; the trace parent
 * written into {@code _meta} and absent when off; {@code minimal} against {@code standard}, the exact list included;
 * the session's spans and a tool call continuing the client's trace; and over every span collected, a secret scan and
 * the check that no attribute or event value equals a public binding value of the run.
 */
class TelemetryObserverTest {

    @TempDir
    Path dir;

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

    private TelemetryObserver observer(boolean minimal) {
        TelemetrySettings settings = new TelemetrySettings("file", null, null, null, "test-version",
                minimal ? "minimal" : "standard", null, 2_000L, null);
        // The in-memory exporter forgets its spans when it is shut down: the observer's close must not reach it.
        SpanExporter keeping = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return exporter.export(spans);
            }

            @Override
            public CompletableResultCode flush() {
                return exporter.flush();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
        TelemetryObserver observer = TelemetryObservers.withExporter(settings, keeping);
        observer.runStarted(new RunStart("run", "nightly", "approve", "seed-a", "test-version",
                new RunStart.Target(4242L, "1.8.0_504", "JAVAAGENT", "0.1.0", "1.0", "0123456789abcdef"), null));
        return observer;
    }

    private List<SpanData> spans() {
        return exporter.getFinishedSpanItems();
    }

    private static SpanData one(List<SpanData> spans, String name) {
        List<SpanData> found = spans.stream().filter(s -> s.getName().equals(name)).toList();
        assertEquals(1, found.size(), name + " in " + names(spans));
        return found.get(0);
    }

    private static List<SpanData> named(List<SpanData> spans, String prefix) {
        return spans.stream().filter(s -> s.getName().startsWith(prefix)).toList();
    }

    private static List<String> names(List<SpanData> spans) {
        return spans.stream().map(SpanData::getName).toList();
    }

    private static String attr(SpanData span, String key) {
        Object value = span.getAttributes().get(AttributeKey.stringKey(key));
        return value == null ? null : value.toString();
    }

    private static Long longAttr(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.longKey(key));
    }

    private static boolean childOf(SpanData child, SpanData parent) {
        return child.getParentSpanId().equals(parent.getSpanId()) && child.getTraceId().equals(parent.getTraceId());
    }

    // ------------------------------------------------------------------------------------------------ the tree

    @Test
    void aPassingRunIsATreeUnderTheRootSpan() throws Exception {
        TelemetryFixture fixture = new TelemetryFixture(dir);
        RunObserver observer = observer(false);
        RunResult approved = fixture.run(TelemetryFixture.BIND_YAML, BindMode.APPROVE, observer);
        assertEquals(Status.PASS, approved.status(), approved.message());
        RunResult replayed = fixture.run(TelemetryFixture.BIND_YAML, BindMode.REPLAY, observer);
        assertEquals(Status.PASS, replayed.status(), replayed.message());
        observer.close();
        List<SpanData> spans = spans();

        SpanData root = one(spans, "skillroy run");
        assertEquals("0000000000000000", root.getParentSpanId());
        assertEquals("seed-a", attr(root, TelemetryAttributes.ENV_ID));
        assertEquals(4242L, longAttr(root, TelemetryAttributes.TARGET_PID));
        assertEquals("JAVAAGENT", attr(root, TelemetryAttributes.TARGET_LOAD_MODE));
        assertEquals(List.of("environment"), root.getEvents().stream().map(EventData::getName).toList());
        assertEquals("test-version", root.getResource().getAttributes().get(AttributeKey.stringKey(
                "service.version")));
        assertEquals("skillroy", root.getResource().getAttributes().get(AttributeKey.stringKey("service.name")));

        List<SpanData> scenarios = named(spans, "scenario ");
        assertEquals(2, scenarios.size(), names(spans).toString());
        for (SpanData scenario : scenarios) {
            assertTrue(childOf(scenario, root), "one trace, scenarios as children of the root");
            assertEquals("toy-resolve", attr(scenario, TelemetryAttributes.TEST_CASE_NAME));
            assertEquals("pass", attr(scenario, TelemetryAttributes.TEST_CASE_RESULT_STATUS));
            assertEquals("PASS", attr(scenario, TelemetryAttributes.STATUS));
            assertEquals("toy", attr(scenario, TelemetryAttributes.PACK));
            assertEquals(StatusCode.UNSET, scenario.getStatus().getStatusCode());
            Set<String> events = scenario.getEvents().stream().map(EventData::getName).collect(Collectors.toSet());
            assertTrue(events.containsAll(Set.of("requires", "fingerprint", "cleanup")), String.valueOf(events));
            SpanData bind = spans.stream().filter(s -> s.getName().equals("bind") && childOf(s, scenario))
                    .findFirst().orElseThrow();
            assertTrue(bind.getEvents().stream().anyMatch(e -> e.getName().equals("resolve item")
                    && e.getAttributes().get(AttributeKey.longKey(TelemetryAttributes.DURATION_MS)) != null),
                    "the resolve events carry durationMs: " + bind.getEvents());
            assertTrue(bind.getEvents().stream().anyMatch(e -> e.getName().equals("oracle neg")));
            assertTrue(Boolean.TRUE.equals(bind.getAttributes().get(AttributeKey.booleanKey(TelemetryAttributes.OK))));
            List<SpanData> steps = spans.stream().filter(s -> s.getName().startsWith("step ") && childOf(s,
                    scenario)).toList();
            assertEquals(List.of("step s1", "step s2", "step s3"), steps.stream().map(SpanData::getName).toList());
            for (SpanData step : steps) {
                assertTrue(step.getStartEpochNanos() >= bind.getEndEpochNanos(), "steps after the bind");
            }
        }
        assertEquals("approve", attr(one(spans.stream().filter(s -> s.getName().equals("bind")).toList()
                .subList(0, 1), "bind"), TelemetryAttributes.BIND_MODE));

        // Every call of a step is a child of its step; its agent span is centred inside it; waits are its children.
        List<SpanData> calls = named(spans, "call ");
        assertFalse(calls.isEmpty());
        for (SpanData call : calls) {
            SpanData parent = spans.stream().filter(s -> s.getSpanId().equals(call.getParentSpanId())).findFirst()
                    .orElseThrow();
            assertTrue(parent.getName().startsWith("step ") || parent.getName().startsWith("scenario "),
                    call.getName() + " under " + parent.getName());
        }
        List<SpanData> agents = named(spans, "agent ");
        assertFalse(agents.isEmpty(), "the reconstructed agent spans");
        for (SpanData agent : agents) {
            SpanData call = spans.stream().filter(s -> s.getSpanId().equals(agent.getParentSpanId())).findFirst()
                    .orElseThrow();
            assertTrue(agent.getStartEpochNanos() >= call.getStartEpochNanos()
                    && agent.getEndEpochNanos() <= call.getEndEpochNanos(), "centred inside its call");
            assertEquals("reconstructed", attr(agent, TelemetryAttributes.AGENT_TIMING));
        }
        List<SpanData> awaits = named(spans, "await");
        assertFalse(awaits.isEmpty(), "the ACK and READY waits");
        for (SpanData await : awaits) {
            assertTrue(calls.stream().anyMatch(c -> childOf(await, c)), "a wait is its call's child");
        }

        // The trace parent of every step primitive's call is the open call span.
        Set<String> callSpanIds = calls.stream().map(SpanData::getSpanId).collect(Collectors.toSet());
        List<CallOptions> sent = fixture.inputOptions();
        assertFalse(sent.isEmpty());
        for (CallOptions options : sent) {
            assertNotNull(options.traceparent(), "the call carries _meta.traceparent");
            String[] parts = options.traceparent().split("-");
            assertEquals(root.getTraceId(), parts[1]);
            assertTrue(callSpanIds.contains(parts[2]), "the open call span: " + options.traceparent());
        }
    }

    @Test
    void theTraceParentIsAbsentWhenTelemetryIsOff() throws Exception {
        TelemetryFixture fixture = new TelemetryFixture(dir);
        RunResult result = fixture.run(TelemetryFixture.BIND_YAML, BindMode.APPROVE, RunObserver.NONE);
        assertEquals(Status.PASS, result.status(), result.message());
        assertFalse(fixture.inputOptions().isEmpty());
        assertTrue(fixture.sentOptions().stream().allMatch(o -> o.traceparent() == null));
    }

    @Test
    void aFailingRunsScenarioAndStepAreErrors() throws Exception {
        TelemetryFixture fixture = new TelemetryFixture(dir);
        RunObserver observer = observer(false);
        RunResult result = fixture.run(TelemetryFixture.FAILING_YAML, BindMode.REPLAY, observer);
        assertEquals(Status.FAIL, result.status(), result.message());
        observer.close();
        List<SpanData> spans = spans();
        SpanData scenario = one(spans, "scenario toy-fails");
        assertEquals(StatusCode.ERROR, scenario.getStatus().getStatusCode());
        assertEquals("fail", attr(scenario, TelemetryAttributes.TEST_CASE_RESULT_STATUS));
        assertEquals("ASSERTION", attr(scenario, TelemetryAttributes.FAILURE_KIND));
        assertEquals("s2", attr(scenario, TelemetryAttributes.FAILED_STEP));
        assertNotNull(attr(scenario, TelemetryAttributes.MESSAGE));
        SpanData step = one(spans, "step s2");
        assertEquals("FAIL", attr(step, TelemetryAttributes.STEP_STATUS));
        assertEquals(StatusCode.ERROR, step.getStatus().getStatusCode());
        assertTrue(step.getEvents().stream().anyMatch(e -> e.getName().equals("expect") && Boolean.FALSE.equals(
                e.getAttributes().get(AttributeKey.booleanKey(TelemetryAttributes.HELD)))), "the expect event");
        assertEquals(StatusCode.UNSET, one(spans, "step s1").getStatus().getStatusCode());
    }

    @Test
    void aSyncTimeoutIsAFailedWait() throws Exception {
        TelemetryFixture fixture = new TelemetryFixture(dir);
        RunObserver observer = observer(false);
        RunResult result = fixture.run(TelemetryFixture.SYNC_TIMEOUT_YAML, BindMode.REPLAY, observer);
        assertEquals(FailureKind.SYNC_TIMEOUT, result.failureKind(), result.message());
        observer.close();
        List<SpanData> spans = spans();
        assertEquals("SYNC_TIMEOUT", attr(one(spans, "scenario toy-waits"), TelemetryAttributes.FAILURE_KIND));
        SpanData call = one(spans, "call toy.state");
        assertTrue(Boolean.TRUE.equals(call.getAttributes().get(AttributeKey.booleanKey(TelemetryAttributes.OK))),
                "the call itself returned");
        assertTrue(named(spans, "await").stream().anyMatch(a -> a.getStatus().getStatusCode() == StatusCode.ERROR
                && Boolean.FALSE.equals(a.getAttributes().get(AttributeKey.booleanKey(TelemetryAttributes.OK)))),
                "the wait that ended without its event");
    }

    @Test
    void aRebindNeededRunIsAFailedBindWithoutSteps() throws Exception {
        TelemetryFixture fixture = new TelemetryFixture(dir);
        RunObserver observer = observer(false);
        RunResult result = fixture.rebindNeeded(observer);
        assertEquals(FailureKind.REBIND_NEEDED, result.failureKind(), result.message());
        observer.close();
        List<SpanData> spans = spans();
        SpanData scenario = one(spans, "scenario toy-resolve");
        assertEquals("REBIND_NEEDED", attr(scenario, TelemetryAttributes.FAILURE_KIND));
        SpanData bind = one(spans, "bind");
        assertTrue(childOf(bind, scenario));
        assertEquals(StatusCode.ERROR, bind.getStatus().getStatusCode());
        assertEquals("REBIND_NEEDED", bind.getStatus().getDescription());
        assertTrue(named(spans, "step ").isEmpty(), "nothing ran after the bind");
    }

    @Test
    void aModelCallIsAGenAiSpanPlacedFromTheLedger() throws Exception {
        TelemetryFixture fixture = new TelemetryFixture(dir);
        fixture.aModelCallInTheBind();
        RunObserver observer = observer(false);
        RunResult result = fixture.run(TelemetryFixture.BIND_YAML, BindMode.APPROVE, observer);
        assertEquals(1, result.modelCalls().size(), result.message());
        observer.close();
        List<SpanData> spans = spans();
        SpanData chat = one(spans, "chat claude-sonnet-5-5");
        SpanData bind = one(spans, "bind");
        assertTrue(childOf(chat, bind), "a bind slot's call is a child of the bind");
        long end = TimeUnit.SECONDS.toNanos(Instant.parse("2026-10-09T12:00:01.500Z").getEpochSecond())
                + 500_000_000L;
        assertEquals(end, chat.getEndEpochNanos());
        assertEquals(end - TimeUnit.MILLISECONDS.toNanos(1500), chat.getStartEpochNanos());
        Attributes a = chat.getAttributes();
        assertEquals("chat", a.get(AttributeKey.stringKey(TelemetryAttributes.GEN_AI_OPERATION_NAME)));
        assertEquals("anthropic", a.get(AttributeKey.stringKey(TelemetryAttributes.GEN_AI_PROVIDER_NAME)));
        assertEquals("claude-sonnet-5-5", a.get(AttributeKey.stringKey(TelemetryAttributes.GEN_AI_REQUEST_MODEL)));
        assertEquals("req_01", a.get(AttributeKey.stringKey(TelemetryAttributes.GEN_AI_RESPONSE_ID)));
        assertEquals(1200L, a.get(AttributeKey.longKey(TelemetryAttributes.GEN_AI_USAGE_INPUT_TOKENS)));
        assertEquals(80L, a.get(AttributeKey.longKey(TelemetryAttributes.GEN_AI_USAGE_OUTPUT_TOKENS)));
        assertEquals(900L, a.get(AttributeKey.longKey(TelemetryAttributes.CACHE_READ_TOKENS)));
        assertEquals(List.of("TOOL_USE"), a.get(AttributeKey.stringArrayKey(
                TelemetryAttributes.GEN_AI_RESPONSE_FINISH_REASONS)));
        assertEquals("assistant", a.get(AttributeKey.stringKey(TelemetryAttributes.SLOT)));
        assertEquals("0.0048", a.get(AttributeKey.stringKey(TelemetryAttributes.MODEL_COST_USD)));
        assertEquals(1L, longAttr(one(spans, "scenario toy-resolve"), TelemetryAttributes.MODEL_CALLS));
    }

    // ------------------------------------------------------------------------------------------------ minimal

    @Test
    void minimalDropsExactlyItsListAndHashesTheIds() throws Exception {
        assertEquals(Set.of("skillroy.message", "skillroy.step.message", "skillroy.pack", "skillroy.suite",
                "skillroy.bindings.file", "skillroy.slot"), TelemetryAttributes.MINIMAL_DROPS, "the exact list");
        assertEquals(Set.of("skillroy.primitive", "gen_ai.tool.name", "skillroy.step.verb", "skillroy.expect.path",
                "skillroy.topic"), TelemetryAttributes.MINIMAL_REPLACED);
        assertEquals(Set.of("test.case.name", "skillroy.failed_step", "skillroy.correlation_id",
                "skillroy.pack.version", "skillroy.env.id", "skillroy.agent.thread", "skillroy.secret_params",
                "skillroy.id", "skillroy.ids", "skillroy.armed", "skillroy.names", "skillroy.table", "skillroy.source",
                "skillroy.resolver", "skillroy.verifiers", "skillroy.fn", "skillroy.oracle", "skillroy.contributors"),
                TelemetryAttributes.MINIMAL_HASHED, "the exact list (fix wave, FC)");

        TelemetryFixture fixture = new TelemetryFixture(dir);
        fixture.aModelCallInTheBind();
        RunObserver observer = observer(true);
        fixture.run(TelemetryFixture.BIND_YAML, BindMode.APPROVE, observer);
        fixture.run(TelemetryFixture.FAILING_YAML, BindMode.REPLAY, observer);
        observer.close();
        List<SpanData> spans = spans();
        for (SpanData span : spans) {
            String name = span.getName();
            assertTrue(name.equals("skillroy run") || Set.of("scenario", "bind", "step", "call", "agent", "await",
                    "chat").contains(name), "a minimal span name: " + name);
            for (String dropped : TelemetryAttributes.MINIMAL_DROPS) {
                assertNull(span.getAttributes().get(AttributeKey.stringKey(dropped)), dropped + " on " + name);
                for (EventData event : span.getEvents()) {
                    assertNull(event.getAttributes().get(AttributeKey.stringKey(dropped)), dropped + " on "
                            + event.getName());
                }
            }
            if (TelemetryAttributes.MINIMAL_SPAN_NAMES.contains(name)) {
                assertTrue(attr(span, TelemetryAttributes.ID_HASH).startsWith("sha256:"), name + " has its id hash");
            }
            for (EventData event : span.getEvents()) {
                assertFalse(event.getName().startsWith("resolve ") || event.getName().startsWith("oracle "),
                        "event names without the slot: " + event.getName());
                String path = event.getAttributes().get(AttributeKey.stringKey(TelemetryAttributes.EXPECT_PATH));
                assertTrue(path == null || !path.contains("."), "an expect path's root only: " + path);
            }
        }
        for (SpanData scenario : spans.stream().filter(s -> s.getName().equals("scenario")).toList()) {
            assertTrue(attr(scenario, TelemetryAttributes.TEST_CASE_NAME).startsWith("sha256:"));
        }
        SpanData failed = spans.stream().filter(s -> s.getName().equals("scenario") && attr(s,
                TelemetryAttributes.FAILED_STEP) != null).findFirst().orElseThrow();
        assertEquals(TelemetryAttributes.hash("s2"), attr(failed, TelemetryAttributes.FAILED_STEP));
        assertTrue(spans.stream().filter(s -> s.getName().equals("call")).allMatch(s -> Set.of("pack", "trace.mark",
                "sync.await", "swing.settle").contains(attr(s, TelemetryAttributes.PRIMITIVE))), "toy.* is pack");
        assertTrue(spans.stream().filter(s -> s.getName().equals("step")).allMatch(s -> Set.of("verb", "expect",
                "waitFor", "call").contains(attr(s, TelemetryAttributes.STEP_VERB))), "a pack verb is verb");

        // The standard set keeps every one of them.
        exporter.reset();
        RunObserver standard = observer(false);
        new TelemetryFixture(dir.resolve("standard")).run(TelemetryFixture.FAILING_YAML, BindMode.REPLAY, standard);
        standard.close();
        SpanData scenario = one(spans(), "scenario toy-fails");
        assertEquals("toy-fails", attr(scenario, TelemetryAttributes.TEST_CASE_NAME));
        assertEquals("toy", attr(scenario, TelemetryAttributes.PACK));
        assertNotNull(attr(scenario, TelemetryAttributes.MESSAGE));
        assertEquals("nightly", attr(one(spans(), "skillroy run"), TelemetryAttributes.SUITE));
        assertEquals("enter", attr(one(spans(), "step s1"), TelemetryAttributes.STEP_VERB));
        assertEquals("toy.input", attr(one(spans(), "call toy.input"), TelemetryAttributes.PRIMITIVE));
    }

    /**
     * Fix wave (Phase 6 review, FC, 2026-10-09): every attribute name {@code TelemetryAttributes} holds is in exactly
     * one of {@code minimal}'s four lists, and no list names anything else, so that an attribute added later must be
     * placed (and is hashed until it is listed as kept, {@link TelemetryAttributes#minimal}).
     */
    @Test
    void everyAttributeNameHasExactlyOneMinimalRule() {
        Set<String> names = new java.util.TreeSet<>(TelemetryAttributes.all());
        Map<String, Set<String>> lists = Map.of("MINIMAL_DROPS", TelemetryAttributes.MINIMAL_DROPS,
                "MINIMAL_REPLACED", TelemetryAttributes.MINIMAL_REPLACED, "MINIMAL_KEPT", TelemetryAttributes.MINIMAL_KEPT,
                "MINIMAL_HASHED", TelemetryAttributes.MINIMAL_HASHED);
        List<String> wrong = new ArrayList<>();
        for (String name : names) {
            List<String> in = lists.entrySet().stream().filter(e -> e.getValue().contains(name)).map(Map.Entry::getKey)
                    .sorted().toList();
            if (in.size() != 1) {
                wrong.add(name + " in " + in);
            }
        }
        assertEquals(List.of(), wrong, "each attribute name in exactly one list");
        for (Map.Entry<String, Set<String>> list : lists.entrySet()) {
            assertTrue(names.containsAll(list.getValue()), list.getKey() + " names only attributes of the allow-list");
        }
        // The rule itself: dropped, replaced, kept and hashed; a name no list holds is hashed.
        assertNull(TelemetryAttributes.minimal(TelemetryAttributes.PACK, "lending"));
        assertEquals("pack", TelemetryAttributes.minimal(TelemetryAttributes.PRIMITIVE, "lending.input"));
        assertEquals("swing.click", TelemetryAttributes.minimal(TelemetryAttributes.PRIMITIVE, "swing.click"));
        assertEquals("hook", TelemetryAttributes.minimal(TelemetryAttributes.TOPIC, "hook.limit.applied"));
        assertEquals("pack", TelemetryAttributes.minimal(TelemetryAttributes.TOPIC, "loan.posted"));
        assertEquals("input.requested", TelemetryAttributes.minimal(TelemetryAttributes.TOPIC, "input.requested"));
        assertEquals("hooks.stubbed", TelemetryAttributes.minimal(TelemetryAttributes.TOPIC, "hooks.stubbed"));
        assertEquals("REPLAY", TelemetryAttributes.minimal(TelemetryAttributes.STATUS, "REPLAY"));
        assertEquals(TelemetryAttributes.hash("t/s1/1"), TelemetryAttributes.minimal(
                TelemetryAttributes.CORRELATION_ID, "t/s1/1"));
        assertEquals(TelemetryAttributes.hash("0.5.0+lending-branch@0.1.0"), TelemetryAttributes.minimal(
                TelemetryAttributes.PACK_VERSION, "0.5.0+lending-branch@0.1.0"));
        assertEquals(TelemetryAttributes.hash("x"), TelemetryAttributes.minimal("skillroy.not.listed", "x"));
    }

    /**
     * Fix wave (Phase 6 review, FC, 2026-10-09; findings L1-F1, L2-F9, L1-S1): a {@code minimal} run carries none of
     * the identifiers of its scenarios, steps, pack, overlay, hooks, resolvers, verifiers, oracles, tables, sources,
     * topics or threads in clear, on any span or event, whatever attribute would carry it: the fixture's runs, then a
     * scenario and a session of an overlay pack reported directly with every record type the observer exports.
     */
    @Test
    void minimalCarriesNoIdentifierOfTheScenarioThePackOrItsOverlayInClear() throws Exception {
        TelemetryFixture fixture = new TelemetryFixture(dir);
        fixture.aModelCallInTheBind();
        TelemetryObserver observer = observer(true);
        fixture.run(TelemetryFixture.BIND_YAML, BindMode.APPROVE, observer);
        fixture.run(TelemetryFixture.BIND_YAML, BindMode.REPLAY, observer);
        fixture.run(TelemetryFixture.FAILING_YAML, BindMode.REPLAY, observer);
        fixture.run(TelemetryFixture.SYNC_TIMEOUT_YAML, BindMode.REPLAY, observer);
        reportAnOverlayScenarioAndSession(observer);
        observer.close();

        List<String> texts = new ArrayList<>();
        List<String> misplaced = new ArrayList<>();
        Set<String> seen = new java.util.TreeSet<>();
        for (SpanData span : spans()) {
            texts.add(span.getName());
            strings(span.getAttributes(), texts);
            listed(span.getName(), span.getAttributes(), seen, misplaced);
            for (EventData event : span.getEvents()) {
                texts.add(event.getName());
                strings(event.getAttributes(), texts);
                listed(span.getName() + "/" + event.getName(), event.getAttributes(), seen, misplaced);
            }
        }
        assertEquals(List.of(), misplaced, "every attribute of a minimal run is kept, replaced or hashed by its list");
        assertTrue(seen.containsAll(Set.of(TelemetryAttributes.CORRELATION_ID, TelemetryAttributes.PACK_VERSION,
                TelemetryAttributes.IDS, TelemetryAttributes.ARMED, TelemetryAttributes.RECORD_ID,
                TelemetryAttributes.NAMES, TelemetryAttributes.RESOLVER, TelemetryAttributes.VERIFIERS,
                TelemetryAttributes.ORACLE, TelemetryAttributes.FN, TelemetryAttributes.CONTRIBUTORS,
                TelemetryAttributes.TABLE, TelemetryAttributes.SOURCE, TelemetryAttributes.TOPIC,
                TelemetryAttributes.AGENT_THREAD, TelemetryAttributes.SECRET_PARAMS, TelemetryAttributes.ENV_ID)),
                "the scan saw every attribute the review named: " + seen);
        List<String> leaks = new ArrayList<>();
        for (String text : texts) {
            for (String id : MINIMAL_NEEDLES) {
                if (holdsId(text, id)) {
                    leaks.add(id + " in \"" + text + "\"");
                }
            }
        }
        assertEquals(List.of(), leaks, "minimal holds an identifier in clear");
    }

    /**
     * Records in {@code misplaced} every attribute of {@code attributes} that {@code minimal} should not write: a
     * dropped one, one no list holds, or a hashed one whose value (or an element) is not a {@code sha256:} hash.
     */
    private static void listed(String where, Attributes attributes, Set<String> seen, List<String> misplaced) {
        attributes.forEach((key, value) -> {
            String name = key.getKey();
            seen.add(name);
            if (TelemetryAttributes.MINIMAL_DROPS.contains(name)) {
                misplaced.add(where + ": " + name + " is dropped in minimal");
            } else if (!TelemetryAttributes.MINIMAL_KEPT.contains(name)
                    && !TelemetryAttributes.MINIMAL_REPLACED.contains(name)
                    && !TelemetryAttributes.MINIMAL_HASHED.contains(name)) {
                misplaced.add(where + ": " + name + " is in no list");
            } else if (TelemetryAttributes.MINIMAL_HASHED.contains(name)) {
                List<?> values = value instanceof List<?> list ? list : List.of(value);
                for (Object v : values) {
                    if (!(v instanceof String s) || !s.startsWith("sha256:")) {
                        misplaced.add(where + ": " + name + " = " + v + " is not hashed");
                    }
                }
            }
        });
    }

    /**
     * The identifiers {@link #minimalCarriesNoIdentifierOfTheScenarioThePackOrItsOverlayInClear} looks for: the
     * fixture's scenario, step, pack, primitive, slot, oracle and environment ids, and those of
     * {@link #reportAnOverlayScenarioAndSession}.
     */
    private static final List<String> MINIMAL_NEEDLES = List.of(
            // the fixture's runs
            "toy-resolve", "toy-fails", "toy-waits", "toy", "s1", "s2", "s3", "seed-a", "nightly", "Limit", "Negate",
            "Needs", "neg", "needs", "assistant", "never.published",
            // the overlay's scenario and session
            "desk-return-observed", "return-line", "returnItem", "lending", "lending-branch", "seed-b",
            "limit.applied", "access.checked", "line.amount", "desk-open", "lending.edition", "Staff",
            "signs-on", "expected-waiver", "WaiverFor", "lending.waivers", "loan_lines", "loan.posted",
            "LendingEngine", "loanId", "pin");

    /**
     * True when {@code text} holds {@code id}: as the whole text, as one of its tokens (split on everything but
     * letters, digits, {@code -} and {@code _}), or, for an id of five characters or more, anywhere in it.
     */
    private static boolean holdsId(String text, String id) {
        if (text.equals(id) || id.length() >= 5 && text.contains(id)) {
            return true;
        }
        for (String token : text.split("[^A-Za-z0-9_-]+")) {
            if (token.equals(id)) {
                return true;
            }
        }
        for (String token : text.split("[^A-Za-z0-9]+")) {
            if (token.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A scenario of an overlay pack ({@code lending} with the overlay {@code lending-branch}) and a session of it,
     * reported directly: every scenario record type, every bind event type, a step with a call, its wait on a hook's
     * topic and an agent event of a pack topic, a stored-row check, an expectation on the {@code hooks} root and a
     * capture.
     */
    private static void reportAnOverlayScenarioAndSession(TelemetryObserver observer) {
        String overlayVersion = "0.5.0+lending-branch@0.1.0";
        observer.scenarioStarted(new ai.skillroy.testjava.scenario.observe.ScenarioStart("desk-return-observed",
                "lending", overlayVersion, 3, "sha256:00ab", "replay", "seed-b"));
        observer.scenarioRecord("requires", Map.of("requires", List.of("lending.input", "hook.limit.applied"),
                "unmet", List.of()));
        observer.scenarioRecord("launchProperties", Map.of("expected", Map.of("lending.edition", "branch"),
                "ok", true));
        observer.scenarioRecord("preflight", Map.of("id", "lending.desk-open", "ok", true));
        observer.scenarioRecord("hooks", Map.of("mode", "auto", "catalogue", Map.of("id", "lending-branch",
                "hash", "sha256:00cd"), "installed", List.of("limit.applied", "access.checked"),
                "unavailable", List.of(Map.of("id", "waiver.evaluated")), "armed", List.of("line.amount")));
        observer.scenarioRecord("capabilities", Map.of("hash", "sha256:00ef", "ids", 40L));
        observer.bindEvent("fingerprint", Map.of("hash", "sha256:0011", "durationMs", 3L));
        observer.bindEvent("resolve", Map.of("slot", "assistant", "strategy", "QUERY", "resolver",
                "lending.sql.Staff", "candidates", 3L, "verified", List.of(Map.of("id", "lending.staff.signs-on",
                        "passed", true)), "durationMs", 4L));
        observer.bindEvent("oracle", Map.of("name", "expected-waiver", "fn", "WaiverFor", "durationMs", 1L));
        observer.bindEvent("guard", Map.of("oracle", "expected-waiver", "skipped", false));
        observer.bindEvent("crosscheck", Map.of("oracle", "expected-waiver", "fn", "WaiverFor", "agreed", true));
        observer.bindEvent("inference", Map.of("mode", "live", "gateway", "claude", "contributors",
                List.of("lending.waivers")));
        observer.bindEvent("bind", Map.of("mode", "replay", "envId", "seed-b", "bindingsFile",
                "/runs/desk-return-observed.bindings.json", "ok", true));
        observer.stepStarted(new StepStart("return-line", "returnItem", 7, null));
        observer.callStarted(new ai.skillroy.testjava.scenario.observe.CallStart("return-line", 1, "lending.input",
                "desk-return-observed/return-line/1", List.of("pin"), false));
        observer.await(new ai.skillroy.testjava.scenario.observe.AwaitRecord("EVENT", true, 12L,
                "hook.limit.applied", 40L, "agent"));
        observer.callEnded(new ai.skillroy.testjava.scenario.observe.CallEnd(true, "agent", null, 10L, 14L, 50L,
                20L, "LendingEngine", List.of(new ai.skillroy.testjava.scenario.observe.CallEnd.EventFact(
                        "loan.posted", 13L, "lending", 1L))));
        observer.scenarioRecord("db", Map.of("table", "loan_lines", "source", "lending", "channel", "jdbc",
                "rowCount", 1L, "ok", true));
        observer.scenarioRecord("expect", Map.of("path", "hooks[id=limit.applied].last.returned", "matcher",
                Map.of("equals", "20.00"), "result", "held"));
        observer.scenarioRecord("capture", Map.of("slot", "loanId", "ok", true));
        observer.scenarioRecord("cleanup", Map.of("primitive", "lending.input", "ok", true));
        observer.sessionStarted(new ai.skillroy.testjava.scenario.observe.SessionStart("sess-9", "lending",
                overlayVersion, "acting"));
        observer.sessionStepStarted(new StepStart("return-line", "returnItem", 0, "PERSON"));
        observer.sessionStep(null);
        observer.sessionEnded("closed");
    }

    // ------------------------------------------------------------------------------------------------ sessions, MCP

    @Test
    void aSessionIsASpanWithItsStepsAndItsRecords() throws Exception {
        TelemetryFixture fixture = new TelemetryFixture(dir);
        RunObserver observer = observer(false);
        fixture.session(observer);
        observer.close();
        List<SpanData> spans = spans();
        SpanData session = one(spans, "session sess-1");
        assertTrue(childOf(session, one(spans, "skillroy run")));
        assertEquals("acting", attr(session, TelemetryAttributes.SESSION_POLICY));
        assertEquals(List.of("session-start", "step", "step", "note", "session-end"),
                session.getEvents().stream().map(EventData::getName).toList());
        SpanData s1 = one(spans, "step s1");
        SpanData s2 = one(spans, "step s2");
        assertTrue(childOf(s1, session) && childOf(s2, session));
        assertEquals("PERSON", attr(s1, TelemetryAttributes.ORIGIN));
        assertEquals("MODEL", attr(s2, TelemetryAttributes.ORIGIN));
        assertEquals("PASS", attr(s1, TelemetryAttributes.STEP_STATUS));
        assertTrue(named(spans, "call ").stream().anyMatch(c -> childOf(c, s1)), "the step's calls are its children");
    }

    @Test
    void aToolCallContinuesTheClientsTraceAndAMalformedParentIsDropped() {
        TelemetryObserver observer = observer(false);
        String client = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        observer.toolCallStarted("toy_enter", client);
        observer.sessionStepStarted(new StepStart("s1", "enter", 0, "CLIENT"));
        observer.sessionStep(null);
        observer.toolCall(new ToolCallRecord("toy_enter", "sess-1", false, 12L));
        observer.toolCallStarted("skillroy_snapshot", "00-zz-00f067aa0ba902b7-01");
        observer.toolCall(new ToolCallRecord("skillroy_snapshot", "sess-1", true, 3L));
        observer.close();
        List<SpanData> spans = spans();
        SpanData continued = one(spans, "tools/call toy_enter");
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", continued.getTraceId());
        assertEquals("00f067aa0ba902b7", continued.getParentSpanId());
        assertEquals("tools/call", attr(continued, TelemetryAttributes.MCP_METHOD_NAME));
        assertEquals("sess-1", attr(continued, TelemetryAttributes.MCP_SESSION_ID));
        SpanData own = one(spans, "tools/call skillroy_snapshot");
        assertTrue(childOf(own, one(spans, "skillroy run")), "a malformed parent is dropped: the root");
        assertEquals(StatusCode.ERROR, own.getStatus().getStatusCode());
    }

    // ------------------------------------------------------------------------------------------------ the scans

    @Test
    void noSpanHoldsASecretOrABindingValue() throws Exception {
        TelemetryFixture fixture = new TelemetryFixture(dir);
        fixture.aModelCallInTheBind();
        RunObserver observer = observer(false);
        fixture.run(TelemetryFixture.BIND_YAML, BindMode.APPROVE, observer);
        fixture.run(TelemetryFixture.BIND_YAML, BindMode.REPLAY, observer);
        fixture.run(TelemetryFixture.FAILING_YAML, BindMode.REPLAY, observer);
        fixture.session(observer);
        observer.close();
        List<String> texts = new ArrayList<>();
        for (SpanData span : spans()) {
            texts.add(span.getName());
            strings(span.getAttributes(), texts);
            for (EventData event : span.getEvents()) {
                texts.add(event.getName());
                strings(event.getAttributes(), texts);
            }
        }
        assertFalse(texts.isEmpty());
        for (String secret : TelemetryFixture.secrets()) {
            for (String text : texts) {
                assertFalse(SpanScan.holds(text, secret), "a span holds a credential: " + text);
            }
        }
        List<String> values = new ArrayList<>();
        values.addAll(TelemetryFixture.bindingValues(fixture.outputDir("toy-resolve", BindMode.APPROVE)));
        values.addAll(TelemetryFixture.bindingValues(fixture.outputDir("toy-resolve", BindMode.REPLAY)));
        assertTrue(values.contains("B2"), "the binding values are read: " + values);
        for (String value : values) {
            assertFalse(texts.contains(value), "a span value equals the binding value " + value);
        }
    }

    private static void strings(Attributes attributes, List<String> out) {
        attributes.forEach((key, value) -> {
            if (value instanceof String s) {
                out.add(s);
            } else if (value instanceof List<?> list) {
                list.forEach(item -> {
                    if (item instanceof String s) {
                        out.add(s);
                    }
                });
            }
        });
    }

    /** The scanning rule of the secret scan, for one text (the module's own copy of P5's rule; strings only). */
    static final class SpanScan {

        private SpanScan() {
        }

        /**
         * True when {@code text} holds {@code secret} as a value: the whole text, one of its tokens (split on
         * whitespace, quotes, commas, colons and backslashes), or, for a secret of eight characters or more, any part
         * of it.
         */
        static boolean holds(String text, String secret) {
            if (text.equals(secret) || secret.length() >= 8 && text.contains(secret)) {
                return true;
            }
            for (String token : text.split("[\\s\"'`,:\\\\]+")) {
                if (token.replaceAll("\\.+$", "").equals(secret)) {
                    return true;
                }
            }
            return false;
        }
    }

    @Test
    void theFileExporterWritesOneLinePerSpanInItsOwnFormat() throws Exception {
        TelemetrySettings settings = new TelemetrySettings("file", null, null, "desk-ci", "9.9.9", null,
                dir.resolve("telemetry"), 2000L, null);
        RunObserver observer = TelemetryObservers.file(settings, System.err);
        observer.runStarted(new RunStart("run", null, "replay", "seed-a", "9.9.9", null, null));
        String traceId = observer.traceId();
        new TelemetryFixture(dir).run(TelemetryFixture.FAILING_YAML, BindMode.REPLAY, observer);
        observer.close();
        List<String> lines = java.nio.file.Files.readAllLines(dir.resolve("telemetry").resolve("spans.jsonl"));
        assertTrue(lines.size() > 3, String.valueOf(lines.size()));
        for (String line : lines) {
            Map<String, Object> span = ai.skillroy.testjava.protocol.Json.parseObject(line);
            assertEquals(List.of("format", "traceId", "spanId", "parentSpanId", "name", "kind", "startEpochNanos",
                    "endEpochNanos", "status", "attributes", "events", "resource"), List.copyOf(span.keySet()));
            assertEquals("skillroy-spans/1", span.get("format"));
            assertEquals(traceId, span.get("traceId"));
            assertEquals("desk-ci", ((Map<?, ?>) span.get("resource")).get("service.name"));
            for (String secret : TelemetryFixture.secrets()) {
                assertFalse(line.contains(secret), line);
            }
        }
        assertEquals("skillroy run", ai.skillroy.testjava.protocol.Json.parseObject(lines.get(lines.size() - 1))
                .get("name"), "the root ends last, at close");
        assertEquals("telemetry: file " + dir.resolve("telemetry") + ", trace " + traceId + ", 0 batches dropped",
                ExportStatus.summaryLine(observer, traceId));
    }
}
