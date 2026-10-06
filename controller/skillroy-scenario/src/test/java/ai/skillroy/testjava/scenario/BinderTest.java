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

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.resolve.Approval;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.Provenance;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.Resolver;
import ai.skillroy.testjava.scenario.resolve.ResolverCandidate;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.Verifier;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The binder against the toy pack and a fake connection (phase2-contracts.md 8, 14.1 item 6): every mode, every
 * reason code of section 10 from the situation 8.3 names, the read-only view, the budget, the credential join and
 * the bindings file.
 */
class BinderTest {

    static final String YAML = """
            scenario: toy-resolve
            pack: toy
            resolve:
              max: { query: Limit, args: { type: RETURN } }
              item: { query: Item, args: { deposit: { between: ["2.00", "50.00"], lte: $max } } }
              assistant: { query: Staff, args: { limitBelow: $item.deposit }, credential: { realm: staff, key: code } }
            oracle:
              neg: { fn: Negate, args: [$item.deposit] }
              needs: { fn: Needs, args: { limit: $assistant.limit, amount: $item.deposit }, must_equal: PROMPT }
            steps:
              - signOn: { staff: $assistant }
              - enter: { value: $item.code }
              - expect: { "loan.total": $oracle.neg }
            """;

    @TempDir
    Path root;

    private final ToyResolution toy = new ToyResolution();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private final List<String> events = new ArrayList<>();
    private final List<Map<String, Object>> records = new ArrayList<>();
    private ToyPack pack;

    @BeforeEach
    void setUp() {
        toy.install(agent);
        pack = toy.pack();
    }

    private Plan plan(String yaml) throws ScenarioException {
        return PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "toy-resolve.yaml", pack);
    }

    private BindOptions options(BindMode mode) {
        return TestCredentials.bind().withMode(mode).withEnvId("seed-a").withBindingsRoot(root).withClock(clock);
    }

    private BindOutcome bind(Plan plan, BindOptions options) throws Exception {
        events.clear();
        records.clear();
        return Binder.bind(plan, pack, agent, options, (type, fields) -> {
            events.add(type);
            Map<String, Object> copy = new LinkedHashMap<>(fields);
            copy.put("type", type);
            records.add(copy);
        });
    }

    private BindException fails(Plan plan, BindOptions options) {
        return assertThrows(BindException.class, () -> bind(plan, options));
    }

    private Path file() {
        return BindingsFile.locate(root, "seed-a", "toy-resolve");
    }

    private void approve() throws Exception {
        bind(plan(YAML), options(BindMode.APPROVE).withApprover(Approver.APPROVE_ALL));
    }

    // ------------------------------------------------------------------------------------------------ live

    @Test
    void liveResolvesInGraphOrderVerifiesAndJoinsTheCredential() throws Exception {
        Plan plan = plan(YAML);
        assertEquals(List.of("max", "item", "assistant", "oracle.neg", "oracle.needs"), plan.resolutionGraph());
        BindOutcome outcome = bind(plan, options(BindMode.LIVE));
        Bindings b = outcome.bindings();
        assertEquals("100.00", b.values().get("max"));
        assertEquals(Map.of("code", "B2", "deposit", "5.00"), b.values().get("item"));
        assertEquals(Map.of("code", "100", "limit", "2.00", "password", TestCredentials.PW_100),
                b.values().get("assistant"), "the join: the secret field is in memory for call args");
        assertEquals(Map.of("code", "100", "limit", "2.00"), b.resolutions().get("assistant").value(),
                "the resolution holds no secret field");
        assertEquals("-5.00", b.oracle("neg"));
        assertEquals("PROMPT", b.oracle("needs"));
        assertTrue(b.secretValues().contains(TestCredentials.PW_100));
        assertNull(outcome.written());
        assertFalse(Files.exists(root.resolve("seed-a")), "live writes nothing without writeBindings");
        assertEquals("toy.sql.Item", b.resolutions().get("item").provenance().resolverId());
        Provenance.Statement statement = b.resolutions().get("item").provenance().statements().get(0);
        assertEquals("agent", statement.channel());
        assertEquals(ToyResolution.ITEM_SQL, statement.sql());
        assertEquals(3, statement.rowCount());
        assertEquals(List.of(List.of("A1", "1.00"), List.of("B2", "5.00"), List.of("C3", "9.99")),
                statement.rowSample());
        assertEquals(List.of("fingerprint", "resolver", "verifier", "resolve", "resolver", "verifier", "resolve",
                "resolver", "verifier", "resolve", "oracle", "oracle", "guard"), events);
        assertEquals(Strategy.CANONICAL_SQL, b.resolutions().get("item").strategy());
        assertEquals("sha256:", b.resolutions().get("item").argsHash().substring(0, 7));
        assertEquals("staff/100", b.credential("assistant").toString());
    }

    @Test
    void theFirstVerifiedCandidateWinsAndTheRejectedAreRecorded() throws Exception {
        toy.inactive.add("B2");
        Resolution item = bind(plan(YAML), options(BindMode.LIVE)).bindings().resolutions().get("item");
        assertEquals(Map.of("code", "C3", "deposit", "9.99"), item.value());
        assertEquals(List.of(new Provenance.Rejected("toy.sql.Item", "item B2", "toy.item.check: item B2 is not"
                + " active")), item.provenance().rejected());
        assertEquals(2, item.provenance().candidates());
    }

    @Test
    void aSecondResolverIsAskedOnlyWhenEveryCandidateOfTheFirstIsRejected() throws Exception {
        toy.javaItems = List.of(new ToyResolution.ItemRef("J9", new java.math.BigDecimal("7.00")));
        pack = toy.pack();
        bind(plan(YAML), options(BindMode.LIVE));
        assertEquals(0, toy.javaResolverCalls.get(), "the first resolver's candidate passed");
        toy.inactive.addAll(List.of("B2", "C3"));
        Resolution item = bind(plan(YAML), options(BindMode.LIVE)).bindings().resolutions().get("item");
        assertEquals(1, toy.javaResolverCalls.get(), "the next resolver is asked (D25)");
        assertEquals("toy.java.Item", item.provenance().resolverId());
        assertEquals(Strategy.CANONICAL_JAVA, item.strategy());
        assertEquals(Map.of("code", "J9", "deposit", "7.00"), item.value());
        assertEquals(List.of("toy.sql.Item", "toy.sql.Item"), item.provenance().rejected().stream()
                .map(Provenance.Rejected::resolverId).toList(), "rejected candidates keep their resolver");
        assertEquals(3, item.provenance().candidates());
        BindException strategy = fails(plan(YAML.replace("lte: $max } } }", "lte: $max } }, strategy: [sql] }")),
                options(BindMode.LIVE));
        assertEquals("ALL_REJECTED", strategy.reason(), "a strategy restriction keeps the Java resolver out");
    }

    @Test
    void noCandidateAllRejectedAndTheUpstreamSlots() throws Exception {
        BindException none = fails(plan(YAML.replace("between: [\"2.00\", \"50.00\"]", "between: [\"500\","
                + " \"600\"]")), options(BindMode.LIVE));
        assertEquals(FailureKind.RESOLUTION_FAILED, none.kind());
        assertEquals("NO_CANDIDATE", none.reason());
        assertEquals("item", none.slot());
        assertEquals(List.of(Map.of("slot", "max", "label", "100.00")), none.detail().get("fixedUpstream"));

        toy.inactive.addAll(List.of("B2", "C3"));
        BindException rejected = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals("ALL_REJECTED", rejected.reason());
        assertEquals(List.of(Map.of("resolver", "toy.sql.Item", "label", "item B2", "reason",
                "toy.item.check: item B2 is not active"), Map.of("resolver", "toy.sql.Item", "label", "item C3",
                "reason", "toy.item.check: item C3 is not active")), rejected.detail().get("rejected"));
        assertEquals(1, ((List<?>) rejected.detail().get("fixedUpstream")).size());
    }

    @Test
    void aFailedGuardInLiveIsResolutionFailedAndNamesTheFixedSlots() throws Exception {
        toy.staff.set(0, List.of("100", "4.99"));
        Plan plan = plan(YAML.replace("limitBelow: $item.deposit", "limitBelow: \"99\""));
        toy.staff.set(0, List.of("100", "60.00"));
        BindException guard = fails(plan, options(BindMode.LIVE));
        assertEquals(FailureKind.RESOLUTION_FAILED, guard.kind());
        assertEquals("GUARD", guard.reason());
        assertEquals("needs", guard.detail().get("oracle"));
        assertEquals(Map.of("mustEqual", "PROMPT", "actual", "NONE"), guard.detail().get("guard"));
        List<?> upstream = (List<?>) guard.detail().get("fixedUpstream");
        assertEquals(List.of("item", "assistant"), upstream.stream().map(u -> ((Map<?, ?>) u).get("slot")).toList());
        assertTrue(events.contains("guard"));
    }

    @Test
    void candidatesWithoutACredentialAreDroppedBeforeTheCap() throws Exception {
        toy.staff.clear();
        for (int i = 0; i < 25; i++) {
            toy.staff.add(List.of("5" + String.format("%03d", i), "1.00"));
        }
        toy.staff.add(List.of("300", "1.00"));
        Resolution assistant = bind(plan(YAML), options(BindMode.LIVE)).bindings().resolutions().get("assistant");
        assertEquals("300", ((Map<?, ?>) assistant.value()).get("code"),
                "26th candidate, but the 25 before it had no credential");
        assertEquals(ResolutionEngine.MAX_REJECTED, assistant.provenance().rejected().size());
        assertEquals("no credential staff/5000", assistant.provenance().rejected().get(0).reason());
        assertEquals(1, assistant.provenance().candidates());

        BindException missing = fails(plan(YAML), options(BindMode.LIVE).withCredentials(Credentials.empty()));
        assertEquals("ALL_REJECTED", missing.reason());
        assertTrue(String.valueOf(missing.detail().get("rejected")).contains("no credential staff/5000"));
    }

    @Test
    void aTruncatedLookupAndTheResolversOwnReasons() throws Exception {
        toy.truncateItems = true;
        Resolution item = bind(plan(YAML), options(BindMode.LIVE)).bindings().resolutions().get("item");
        assertTrue(item.provenance().candidatesTruncated(), "a truncated candidate statement is allowed (D32)");
        toy.requireCompleteItems = true;
        BindException truncated = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals(FailureKind.RESOLUTION_FAILED, truncated.kind());
        assertEquals("TRUNCATED", truncated.reason());

        toy.requireCompleteItems = false;
        toy.beforeItem = ctx -> {
            throw new ResolutionException("the toy shop is closed", "nothing to see");
        };
        BindException resolver = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals("RESOLVER", resolver.reason());
        assertEquals("nothing to see", resolver.detail().get("message"));
        toy.beforeItem = ctx -> {
            throw new ResolutionException("CLOSED", null, "the toy shop is closed");
        };
        assertEquals("CLOSED", fails(plan(YAML), options(BindMode.LIVE)).reason());
    }

    @Test
    void argumentsThatDoNotDecodeAreArgs() throws Exception {
        Plan plan = plan(YAML.replace("max: { query: Limit, args: { type: RETURN } }",
                "max: { query: Limit, args: { type: RETURN } }\n  word: { fixture: \"abc\" }")
                .replace("lte: $max", "lte: $word"));
        BindException args = fails(plan, options(BindMode.LIVE));
        assertEquals(FailureKind.RESOLUTION_FAILED, args.kind());
        assertEquals("ARGS", args.reason());
        assertEquals("item", args.slot());
    }

    // ------------------------------------------------------------------------------------------------ pack defects

    @Test
    void theReadOnlyViewRefusesMutatingCallsAndAwaitsAndRecordsCalls() throws Exception {
        agent.describe("toy.mutate", true).describe("toy.peek", false);
        agent.value("toy.mutate", Map.of());
        agent.value("toy.peek", Map.of("ok", true));
        toy.beforeItem = ctx -> ctx.agent().call("toy.mutate", Map.of());
        BindException mutating = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, mutating.kind());
        assertTrue(mutating.getMessage().contains("pack defect") && mutating.getMessage().contains("toy.mutate"),
                mutating.getMessage());
        assertTrue(agent.callsTo("toy.mutate").isEmpty(), "never sent");

        toy.beforeItem = ctx -> {
            try {
                ctx.agent().call("toy.mutate", Map.of());
            } catch (IllegalStateException swallowed) {
                // a pack that swallows the refusal is still a pack defect
            }
        };
        assertEquals(FailureKind.ENVIRONMENT, fails(plan(YAML), options(BindMode.LIVE)).kind());

        toy.beforeItem = ctx -> ctx.agent().await("x", 0, null, Duration.ofSeconds(1));
        BindException await = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, await.kind());
        assertTrue(await.getMessage().contains("UnsupportedOperationException"), await.getMessage());

        toy.beforeItem = ctx -> {
            ctx.agent().call("toy.peek", Map.of("q", 1L), CallOptions.defaults().withTimeoutMs(119_000L));
            try {
                ctx.agent().call("toy.unknown", Map.of());
            } catch (AgentRpcException e) {
                assertEquals(ErrorCodes.METHOD_NOT_FOUND, e.error().code(), "an unknown primitive is passed through");
            }
            ctx.note("peeked");
        };
        Resolution item = bind(plan(YAML), options(BindMode.LIVE)).bindings().resolutions().get("item");
        assertEquals(List.of(new Provenance.Call("toy.peek", Map.of("q", 1L)), new Provenance.Call("toy.unknown",
                Map.of())), item.provenance().calls());
        assertEquals(List.of("peeked"), item.provenance().notes());
        assertEquals(60_000L, agent.callsTo("toy.peek").get(0).options().timeoutMs(),
                "clamped to what is left of the 60 s budget");
    }

    @Test
    void aStatementWithoutATimeoutGetsItsSourcesDefaultClampedToTheBudget() throws Exception {
        toy.beforeItem = ctx -> {
            ctx.data().query(ToyResolution.SOURCE, ToyResolution.LIMIT_SQL, List.of("max_want"), 1,
                    Duration.ofSeconds(119));
            ctx.data().query(ToyResolution.SOURCE, ToyResolution.LIMIT_SQL, List.of("max_none"), 1,
                    Duration.ofMillis(1500));
        };
        bind(plan(YAML), options(BindMode.LIVE));
        assertEquals(10_000L, dataQuery(ToyResolution.ITEM_SQL).options().timeoutMs(), "no data.query descriptor:"
                + " the agent's default of 10 s, not the whole 60 s budget");
        assertEquals(10_000L, dataQuery(ToyResolution.STAFF_SQL).options().timeoutMs());
        assertEquals(60_000L, dataQueryWith("max_want").options().timeoutMs(), "an explicit timeout is clamped to"
                + " what is left of the budget");
        assertEquals(1_500L, dataQueryWith("max_none").options().timeoutMs());

        agent.calls.clear();
        agent.describe("data.query", false);
        bind(plan(YAML), options(BindMode.LIVE));
        assertEquals(5_000L, dataQuery(ToyResolution.ITEM_SQL).options().timeoutMs(), "the data.query"
                + " descriptor's timeout");

        agent.calls.clear();
        toy.beforeItem = ctx -> { };
        bind(plan(YAML.replace("steps:", "timeouts: { bind: 3s }\nsteps:")), options(BindMode.LIVE));
        assertEquals(3_000L, dataQuery(ToyResolution.ITEM_SQL).options().timeoutMs(), "clamped to a smaller"
                + " budget");
    }

    @Test
    void aJdbcStatementWithoutATimeoutGetsTheSourcesStatementTimeoutClampedToTheBudget() throws Exception {
        String url = "jdbc:hsqldb:mem:binder-timeout-" + System.nanoTime();
        try (java.sql.Connection db = java.sql.DriverManager.getConnection(url, "SA", "");
             java.sql.Statement st = db.createStatement()) {
            st.execute("CREATE TABLE setting (name VARCHAR(40) PRIMARY KEY, value VARCHAR(40))");
            st.execute("INSERT INTO setting VALUES ('max_return', '100.00')");
            Credentials jdbc = TestCredentials.of(Map.of("version", 1L, "jdbc", Map.of(ToyResolution.SOURCE,
                    Map.of("url", url, "user", "SA", "statementTimeout", "5s"))));
            String limitOnly = """
                    scenario: toy-resolve
                    pack: toy
                    resolve:
                      max: { query: Limit, args: { type: RETURN } }
                    steps: []
                    """;
            for (String yaml : List.of(limitOnly, limitOnly.replace("steps:", "timeouts: { bind: 2s }\nsteps:"))) {
                try (ai.skillroy.testjava.data.DataChannels channels = ai.skillroy.testjava.data.DataChannels.of(
                        agent, jdbc, List.of())) {
                    TimeoutRecorder recorder = new TimeoutRecorder(channels);
                    BindOutcome outcome = Binder.bind(plan(yaml), pack, agent, options(BindMode.LIVE)
                            .withCredentials(jdbc), null, recorder);
                    assertEquals("jdbc", outcome.bindings().resolutions().get("max").provenance().statements()
                            .get(0).channel());
                    assertEquals(List.of(yaml.contains("bind: 2s") ? Duration.ofSeconds(2) : Duration.ofSeconds(5)),
                            recorder.timeouts, "statementTimeout 5s, clamped to the budget");
                }
            }
            assertTrue(agent.callsTo("data.query").isEmpty(), "the JDBC source is never read through the agent");
        }
    }

    @Test
    void aJdbcConnectThatNeverReturnsEndsTheBindWithinItsBudget() throws Exception {
        HangingDriver.release = new java.util.concurrent.CountDownLatch(1);
        Credentials hanging = TestCredentials.of(Map.of("version", 1L, "jdbc", Map.of(ToyResolution.SOURCE, Map.of(
                "url", "jdbc:skillroy-hang:toy", "driver", HangingDriver.class.getName(), "loginTimeout", "5s"))));
        String yaml = """
                scenario: toy-resolve
                pack: toy
                resolve:
                  max: { query: Limit, args: { type: RETURN } }
                timeouts: { bind: 300ms }
                steps: []
                """;
        try {
            long start = System.nanoTime();
            BindException e = fails(plan(yaml), options(BindMode.LIVE).withCredentials(hanging));
            long ms = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(FailureKind.ENVIRONMENT, e.kind());
            assertTrue(e.getMessage().contains("binding timed out in slot max (budget 300 ms)"), e.getMessage());
            assertTrue(ms < 4_000, "bounded by the 300 ms budget, not by the 5 s loginTimeout: " + ms + " ms");
        } finally {
            HangingDriver.release.countDown();
        }
    }

    @Test
    void theAgentsListOfSourcesIsAskedForWithinTheBudget() throws Exception {
        bind(plan(YAML), options(BindMode.LIVE));
        assertEquals(5_000L, agent.callsTo("data.sources").get(0).options().timeoutMs(), "5 s at most");
        agent.calls.clear();
        bind(plan(YAML.replace("steps:", "timeouts: { bind: 3s }\nsteps:")), options(BindMode.LIVE));
        assertEquals(3_000L, agent.callsTo("data.sources").get(0).options().timeoutMs(), "what is left of 3 s");
    }

    private FakeAgentConnection.Call dataQuery(String sql) {
        return agent.callsTo("data.query").stream().filter(c -> sql.equals(c.params().get("sql"))).findFirst()
                .orElseThrow();
    }

    private FakeAgentConnection.Call dataQueryWith(String param) {
        return agent.callsTo("data.query").stream().filter(c -> List.of(param).equals(c.params().get("params")))
                .findFirst().orElseThrow();
    }

    /** A data channel that records the timeout each statement was given. */
    private static final class TimeoutRecorder implements ai.skillroy.testjava.data.DataChannel {

        final ai.skillroy.testjava.data.DataChannel inner;
        final List<Duration> timeouts = new ArrayList<>();

        TimeoutRecorder(ai.skillroy.testjava.data.DataChannel inner) {
            this.inner = inner;
        }

        @Override
        public boolean serves(String source) throws ai.skillroy.testjava.controller.AgentLostException {
            return inner.serves(source);
        }

        @Override
        public Rows query(String source, String sql, List<Object> params, int maxRows, Duration timeout)
                throws ai.skillroy.testjava.data.DataException, ai.skillroy.testjava.controller.AgentLostException,
                InterruptedException {
            timeouts.add(timeout);
            return inner.query(source, sql, params, maxRows, timeout);
        }

        @Override
        public String kind(String source) {
            return inner.kind(source);
        }

        @Override
        public Duration defaultTimeout(String source) {
            return inner.defaultTimeout(source);
        }
    }

    @Test
    void theBudgetIsMeasuredOnTheClockAndReviewTimeIsNotCounted() throws Exception {
        toy.beforeItem = ctx -> clock.advance(Duration.ofSeconds(61));
        BindException spent = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, spent.kind());
        assertTrue(spent.getMessage().contains("binding timed out in slot"), spent.getMessage());

        toy.beforeItem = ctx -> clock.advance(Duration.ofSeconds(50));
        Plan plan = plan(YAML.replace("steps:", "timeouts: { bind: 55s }\nsteps:"));
        assertEquals(55_000L, plan.bindTimeoutMs());
        toy.beforeItem = ctx -> { };
        BindOutcome approved = bind(plan, options(BindMode.APPROVE).withApprover(request -> {
            clock.advance(Duration.ofMinutes(10));
            return new Approver.Decision(true, "reviewer", null);
        }));
        assertNotNull(approved.written(), "ten minutes of review do not spend a 55 s budget");
    }

    @Test
    void exceptionsFromPackCodeAreEnvironment() throws Exception {
        toy.beforeItem = ctx -> {
            throw new IllegalArgumentException("boom");
        };
        BindException e = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, e.kind());
        assertTrue(e.getMessage().contains("pack defect"), e.getMessage());
        toy.beforeItem = ctx -> ctx.data().query("nowhere", "SELECT 1 FROM x ORDER BY 1", List.of(), 1, null);
        BindException data = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, data.kind());
        assertEquals("UNKNOWN_SOURCE", data.detail().get("dataKind"));
        toy.beforeItem = ctx -> ctx.data().query(ToyResolution.SOURCE, "DELETE FROM item", List.of(), 1, null);
        assertEquals("GUARD", fails(plan(YAML), options(BindMode.LIVE)).detail().get("dataKind"));
    }

    // ------------------------------------------------------------------------------------------------ approve

    @Test
    void approveWritesOnlyOnApprovalAtomicallyAndLeavesAnIdenticalFileUntouched() throws Exception {
        BindException refused = fails(plan(YAML), options(BindMode.APPROVE));
        assertEquals("NOT_APPROVED", refused.reason());
        assertFalse(Files.exists(root.resolve("seed-a")), "nothing written");

        List<Approver.ApprovalRequest> seen = new ArrayList<>();
        BindOutcome first = bind(plan(YAML), options(BindMode.APPROVE).withApprover(request -> {
            seen.add(request);
            return new Approver.Decision(true, null, null);
        }));
        assertEquals(file(), first.written());
        Approver.ApprovalRequest request = seen.get(0);
        assertEquals(3, request.resolutions().size());
        assertFalse(request.resolutions().get(1).provenance().statements().get(0).rowSample().isEmpty(),
                "the approver sees the row sample");
        assertFalse(request.resolutions().get(1).verified().get(0).evidence().isEmpty(), "and the evidence");
        assertEquals(2, request.oracles().size());
        assertNull(request.againstExisting());
        BindingsFile written = BindingsFile.read(file());
        assertEquals(new Approval(Approval.State.APPROVED, "approve"), written.resolutions().get("item").approval());
        assertTrue(Files.list(root.resolve("seed-a")).allMatch(p -> p.equals(file())), "no temporary file left");

        FileTime before = Files.getLastModifiedTime(file());
        Files.setLastModifiedTime(file(), FileTime.fromMillis(before.toMillis() - 60_000));
        FileTime old = Files.getLastModifiedTime(file());
        BindOutcome again = bind(plan(YAML), options(BindMode.APPROVE).withApprover(r -> {
            assertTrue(r.againstExisting().isEmpty(), "no difference against the existing file");
            return new Approver.Decision(true, null, null);
        }));
        assertNull(again.written(), "an identical file is left untouched");
        assertEquals(old, Files.getLastModifiedTime(file()));

        bind(plan(YAML), options(BindMode.APPROVE).withApprover(Approver.APPROVE_ALL).withApprovedBy("Mx Reviewer"));
        assertEquals("Mx Reviewer", BindingsFile.read(file()).resolutions().get("max").approval().by());
    }

    @Test
    void approveWithoutABindingsRootIsRefusedBeforeAnythingRuns() throws Exception {
        BindException approve = fails(plan(YAML), options(BindMode.APPROVE).withApprover(Approver.APPROVE_ALL)
                .withBindingsRoot(null));
        assertEquals(FailureKind.ENVIRONMENT, approve.kind());
        assertTrue(approve.getMessage().startsWith("approve needs a bindings root"), approve.getMessage());
        assertEquals("approve", approve.detail().get("mode"));
        assertEquals(0, toy.itemResolverCalls.get(), "no resolver was called");
        assertEquals(0, toy.verifierCalls.get(), "no verifier either");
        assertTrue(agent.calls.isEmpty(), "nothing was sent to the target, not even for the fingerprint: "
                + agent.methods());
        assertEquals(List.of(), events);

        BindException write = fails(plan(YAML), options(BindMode.LIVE).withWriteBindings(true)
                .withBindingsRoot(null));
        assertTrue(write.getMessage().startsWith("live with writeBindings needs a bindings root"), write.getMessage());
        assertTrue(agent.calls.isEmpty());

        assertNotNull(bind(plan(YAML), options(BindMode.LIVE).withBindingsRoot(null)).bindings(),
                "live without writing needs no root");
        Plan fixtures = plan("""
                scenario: toy-resolve
                pack: toy
                resolve:
                  assistant: { fixture: { code: "100" }, credential: { realm: staff, key: code } }
                steps: []
                """);
        assertNull(bind(fixtures, options(BindMode.APPROVE).withBindingsRoot(null)).written(),
                "a plan without query slots and oracles writes no file and needs no root");
    }

    @Test
    void theBindingsFileIsByteStableKeepsItsCredentialAndHoldsNoSecretTimeOrPath() throws Exception {
        approve();
        String text = Files.readString(file());
        BindingsFile read = BindingsFile.read(file());
        assertEquals(text, read.toJsonText(), "read and written again, it is byte-identical");
        assertTrue(text.endsWith("}\n"));
        assertFalse(text.contains(TestCredentials.PW_100));
        assertFalse(text.contains(root.toString()));
        assertFalse(text.contains("rowSample") || text.contains("evidence") || text.contains("notes"), text);
        assertFalse(text.matches("(?s).*20\\d\\d-\\d\\d-\\d\\dT.*"), "no timestamp");
        Map<String, Object> json = Json.parseObject(text);
        Map<?, ?> assistant = (Map<?, ?>) ((Map<?, ?>) json.get("resolutions")).get("assistant");
        assertEquals(Map.of("realm", "staff", "id", "100"), assistant.get("credential"));
        assertEquals(List.of("bindingsVersion", "scenarioId", "envId", "pack", "packVersion", "planHash",
                "resolveHash", "env", "resolutions", "oracles"), List.copyOf(json.keySet()));
        assertEquals(List.of("max", "item", "assistant"), List.copyOf(read.resolutions().keySet()));
        assertEquals(Map.of("skillroy.fingerprint", "1", "skillroy.protocolMajor", "0", "toy.seed", "A"),
                read.env().components());
        String again = Files.readString(file());
        approve();
        assertEquals(again, Files.readString(file()), "two approvals of one environment agree byte for byte");
    }

    @Test
    void recordedCallParamsAndEvidenceAreWrittenWithSortedKeys() throws Exception {
        agent.describe("toy.peek", false);
        agent.value("toy.peek", Map.of("ok", true));
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("y", 1L);
        inner.put("b", 2L);
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("zeta", 1L);
        reversed.put("alpha", List.of(inner));
        reversed.put("mid", inner);
        toy.beforeItem = ctx -> {
            // Map.of iterates in an order that differs between JVMs; the LinkedHashMap is unsorted on purpose.
            ctx.agent().call("toy.peek", Map.of("zeta", 1L, "alpha", 2L, "mid", 3L, "beta", 4L, "omega", 5L));
            ctx.agent().call("toy.peek", reversed);
        };
        List<Approver.ApprovalRequest> seen = new ArrayList<>();
        bind(plan(YAML), options(BindMode.APPROVE).withApprover(request -> {
            seen.add(request);
            return new Approver.Decision(true, null, null);
        }));
        String text = Files.readString(file());
        Map<?, ?> provenance = (Map<?, ?>) slot(Json.parseObject(text), "item").get("provenance");
        List<?> calls = (List<?>) provenance.get("calls");
        Map<?, ?> first = (Map<?, ?>) ((Map<?, ?>) calls.get(0)).get("params");
        assertEquals(List.of("alpha", "beta", "mid", "omega", "zeta"), List.copyOf(first.keySet()),
                "the file keeps the order it was written in: sorted");
        Map<?, ?> second = (Map<?, ?>) ((Map<?, ?>) calls.get(1)).get("params");
        assertEquals(List.of("alpha", "mid", "zeta"), List.copyOf(second.keySet()));
        assertEquals(List.of("b", "y"), List.copyOf(((Map<?, ?>) second.get("mid")).keySet()), "at every depth");
        assertEquals(List.of("b", "y"), List.copyOf(((Map<?, ?>) ((List<?>) second.get("alpha")).get(0))
                .keySet()), "inside lists too");

        Provenance.Call approved = seen.get(0).resolutions().get(1).provenance().calls().get(1);
        assertEquals(List.of("alpha", "mid", "zeta"), List.copyOf(((Map<?, ?>) approved.params()).keySet()),
                "the approval request shows the same order");
        Map<String, Object> resolve = records.stream().filter(r -> "resolve".equals(r.get("type"))
                && "item".equals(r.get("slot"))).findFirst().orElseThrow();
        Map<?, ?> recordCall = (Map<?, ?>) ((List<?>) resolve.get("calls")).get(1);
        assertEquals(List.of("alpha", "mid", "zeta"), List.copyOf(((Map<?, ?>) recordCall.get("params")).keySet()),
                "and so does the resolve record");

        toy.beforeItem = ctx -> {
            ctx.agent().call("toy.peek", Map.of("zeta", 1L, "alpha", 2L, "mid", 3L, "beta", 4L, "omega", 5L));
            Map<String, Object> reordered = new LinkedHashMap<>();
            reordered.put("mid", Map.of("y", 1L, "b", 2L));
            reordered.put("zeta", 1L);
            reordered.put("alpha", List.of(Map.of("b", 2L, "y", 1L)));
            ctx.agent().call("toy.peek", reordered);
        };
        BindOutcome rebound = bind(plan(YAML), options(BindMode.APPROVE).withApprover(Approver.APPROVE_ALL));
        assertNull(rebound.written(), "the same calls in another key order write the same bytes: left untouched");
        assertEquals(text, Files.readString(file()));

        Map<String, Object> json = Json.parseObject(text);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstCall = (Map<String, Object>) ((List<?>) ((Map<?, ?>) slot(json, "item")
                .get("provenance")).get("calls")).get(0);
        List<Object> keys = new ArrayList<>(((Map<?, ?>) firstCall.get("params")).keySet());
        java.util.Collections.reverse(keys);
        Map<String, Object> reversedParams = new LinkedHashMap<>();
        for (Object key : keys) {
            reversedParams.put((String) key, ((Map<?, ?>) firstCall.get("params")).get(key));
        }
        firstCall.put("params", reversedParams);
        String edited = Json.writePretty(json);
        assertFalse(edited.equals(text));
        assertEquals(text, BindingsFile.parse(edited).toJsonText(), "read back, a file is written in canonical"
                + " order whatever order its call params were edited into");
    }

    @Test
    void verifierEvidenceIsRecordedWithSortedKeys() throws Exception {
        agent.on("toy.item", params -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("found", true);
            answer.put("active", true);
            answer.put("deposit", "5.00");
            return answer;
        });
        bind(plan(YAML), options(BindMode.LIVE));
        Map<String, Object> resolve = records.stream().filter(r -> "resolve".equals(r.get("type"))
                && "item".equals(r.get("slot"))).findFirst().orElseThrow();
        Map<?, ?> verified = (Map<?, ?>) ((List<?>) resolve.get("verified")).get(0);
        Map<?, ?> evidence = (Map<?, ?>) verified.get("evidence");
        Map<?, ?> answer = (Map<?, ?>) evidence.get("toy.item");
        assertEquals(List.of("active", "deposit", "found"), List.copyOf(answer.keySet()),
                "the application's answer as evidence, keys sorted");
    }

    @Test
    void liveWithWriteBindingsWritesNotRequired() throws Exception {
        BindOutcome outcome = bind(plan(YAML), options(BindMode.LIVE).withWriteBindings(true));
        assertEquals(file(), outcome.written());
        assertEquals(new Approval(Approval.State.NOT_REQUIRED, "live"),
                BindingsFile.read(file()).resolutions().get("item").approval());
    }

    @Test
    void approveRefusesATextThatHoldsACredentialValue() throws Exception {
        toy.items.add(0, List.of(TestCredentials.PW_200, "6.00"));
        toy.items.removeIf(row -> row.get(0).equals("B2"));
        BindException leak = fails(plan(YAML), options(BindMode.APPROVE).withApprover(Approver.APPROVE_ALL));
        assertEquals(FailureKind.ENVIRONMENT, leak.kind());
        assertTrue(leak.getMessage().contains("equals a credential"), leak.getMessage());
        assertFalse(Files.exists(file()));
    }

    @Test
    void approveRefusesACredentialValueWhateverItsJsonTextLooksLike() throws Exception {
        String quoted = "pw\"quote\\slash-100";
        Map<String, Object> staff = new LinkedHashMap<>();
        staff.put("100", Map.of("password", TestCredentials.PW_100));
        staff.put("300", Map.of("password", quoted));
        staff.put("301", Map.of("password", "4711"));
        BindOptions approve = options(BindMode.APPROVE).withApprover(Approver.APPROVE_ALL).withCredentials(
                TestCredentials.of(Map.of("version", 1L, "credentials", Map.of("staff", staff))));
        toy.items.removeIf(row -> row.get(0).equals("B2"));
        for (String code : List.of(quoted, "4711")) {
            toy.items.add(0, List.of(code, "6.00"));
            BindException leak = fails(plan(YAML), approve);
            assertEquals(FailureKind.ENVIRONMENT, leak.kind(), code);
            assertTrue(leak.getMessage().contains("equals a credential"), leak.getMessage());
            assertFalse(Files.exists(file()), "nothing written for " + code);
            toy.items.remove(0);
        }
    }

    // ------------------------------------------------------------------------------------------------ replay
    // ------------------------------------------------------------------------------------------------ replay

    @Test
    void replayVerifiesTheRecordedValuesAndNeverCallsAResolver() throws Exception {
        approve();
        int itemCalls = toy.itemResolverCalls.get();
        toy.verifierCalls.set(0);
        BindOutcome outcome = bind(plan(YAML), options(BindMode.REPLAY));
        assertEquals(itemCalls, toy.itemResolverCalls.get(), "a replay never calls a resolver");
        assertFalse(events.contains("resolver"));
        assertEquals(3, toy.verifierCalls.get(), "every slot's verifier ran once");
        assertEquals(3, events.stream().filter("verifier"::equals).count());
        assertEquals("REPLAY", outcome.bindings().strategy("item"));
        assertEquals(TestCredentials.PW_100, ((Map<?, ?>) outcome.bindings().values().get("assistant"))
                .get("password"));
        Map<String, Object> resolve = records.stream().filter(r -> "resolve".equals(r.get("type"))).findFirst()
                .orElseThrow();
        assertEquals("REPLAY", resolve.get("strategy"));
        assertEquals("CANONICAL_SQL", resolve.get("recordedStrategy"));
        Map<String, Object> fingerprint = records.get(0);
        assertEquals(fingerprint.get("hash"), fingerprint.get("recorded"));
        assertNull(outcome.written());
    }

    @Test
    void replayReasons() throws Exception {
        BindException noRoot = fails(plan(YAML), options(BindMode.REPLAY).withBindingsRoot(null));
        assertEquals(FailureKind.REBIND_NEEDED, noRoot.kind());
        assertEquals("NO_BINDINGS", noRoot.reason());
        BindException noFile = fails(plan(YAML), options(BindMode.REPLAY));
        assertEquals("NO_BINDINGS", noFile.reason());
        assertEquals("seed-a/toy-resolve.bindings.json", noFile.detail().get("bindingsFile"));

        approve();
        String good = Files.readString(file());
        Files.writeString(file(), "{ not json");
        assertEquals("UNREADABLE", fails(plan(YAML), options(BindMode.REPLAY)).reason());
        Files.writeString(file(), good.replace("\"envId\": \"seed-a\"", "\"envId\": \"seed-b\""));
        assertEquals("UNREADABLE", fails(plan(YAML), options(BindMode.REPLAY)).reason());
        Files.writeString(file(), good.replace("\"toy.seed\": \"A\"", "\"toy.seed\": \"B\""));
        BindException edited = fails(plan(YAML), options(BindMode.REPLAY));
        assertEquals("UNREADABLE", edited.reason());
        assertTrue(edited.getMessage().contains("edited by hand"), edited.getMessage());
        Files.writeString(file(), good.replace("\"bindingsVersion\": 1", "\"bindingsVersion\": 9"));
        assertEquals("UNREADABLE", fails(plan(YAML), options(BindMode.REPLAY)).reason());

        Files.writeString(file(), good);
        BindException changed = fails(plan(YAML.replace("between: [\"2.00\", \"50.00\"]",
                "between: [\"2.00\", \"60.00\"]")), options(BindMode.REPLAY));
        assertEquals("PLAN_CHANGED", changed.reason());
        assertNotNull(changed.detail().get("expected"));

        toy.seed = "B";
        toy.verifierCalls.set(0);
        BindException foreign = fails(plan(YAML), options(BindMode.REPLAY));
        assertEquals("FINGERPRINT", foreign.reason());
        assertEquals(List.of(Map.of("name", "toy.seed", "recorded", "A", "actual", "B")),
                foreign.detail().get("components"));
        assertEquals(0, toy.verifierCalls.get(), "no verifier runs after a fingerprint mismatch (D9)");
        toy.seed = "A";

        Map<String, Object> json = Json.parseObject(good);
        Map<String, Object> item = slot(json, "item");
        item.put("argsHash", "sha256:0000");
        Files.writeString(file(), Json.writePretty(json));
        BindException args = fails(plan(YAML), options(BindMode.REPLAY));
        assertEquals("ARGS_CHANGED", args.reason());
        assertEquals("item", args.slot());

        Files.writeString(file(), good);
        toy.inactive.add("B2");
        BindException verifier = fails(plan(YAML), options(BindMode.REPLAY));
        assertEquals("VERIFIER", verifier.reason());
        assertEquals("item", verifier.slot());
        assertEquals("toy.item.check", ((Map<?, ?>) verifier.detail().get("verifier")).get("id"));
        assertNotNull(((Map<?, ?>) verifier.detail().get("verifier")).get("evidence"));
        toy.inactive.clear();

        json = Json.parseObject(good);
        ((Map<String, Object>) ((Map<String, Object>) json.get("oracles")).get("neg")).put("value", "-6.00");
        Files.writeString(file(), Json.writePretty(json));
        BindException oracle = fails(plan(YAML), options(BindMode.REPLAY));
        assertEquals("ORACLE_CHANGED", oracle.reason());
        assertEquals("neg", oracle.detail().get("oracle"));

        json = Json.parseObject(good);
        slot(json, "item").put("value", Map.of("code", "B2", "deposit", "not a number"));
        Files.writeString(file(), Json.writePretty(json));
        assertEquals("UNREADABLE", fails(plan(YAML), options(BindMode.REPLAY)).reason());

        Files.writeString(file(), good);
        BindException credentials = fails(plan(YAML), options(BindMode.REPLAY).withCredentials(Credentials.empty()));
        assertEquals(FailureKind.ENVIRONMENT, credentials.kind(), "the binding is right, the credentials file is"
                + " incomplete (D12)");
        assertTrue(credentials.getMessage().contains("staff/100"), credentials.getMessage());
    }

    @Test
    void aChangedQueryKindIsPlanChangedAndAHandEditedOneUnreadable() throws Exception {
        approve();
        String good = Files.readString(file());
        BindException changed = fails(plan(YAML.replace("max: { query: Limit, args: { type: RETURN } }",
                "max: { query: Staff, args: { limitBelow: \"3.00\" } }")), options(BindMode.REPLAY));
        assertEquals(FailureKind.REBIND_NEEDED, changed.kind());
        assertEquals("PLAN_CHANGED", changed.reason(), "8.3 step 4: the resolveHash covers the query kind");

        Files.writeString(file(), good.replace("\"query\": \"Limit\"", "\"query\": \"Staff\""));
        BindException edited = fails(plan(YAML), options(BindMode.REPLAY));
        assertEquals("UNREADABLE", edited.reason(), "the plan did not change, so the file was edited");
        assertTrue(edited.getMessage().contains("records query Staff but the plan asks Limit"), edited.getMessage());
    }

    @Test
    void anOracleWhoseRecordedInputsNoLongerDecodeIsUnreadableInReplay() throws Exception {
        approve();
        pack.resolution = ResolutionCatalogue.builder(toy.catalogue()).replace(new BooleanNegate()).build();
        BindException replay = fails(plan(YAML), options(BindMode.REPLAY));
        assertEquals(FailureKind.REBIND_NEEDED, replay.kind(), "section 10: no RESOLUTION_FAILED in replay");
        assertEquals("UNREADABLE", replay.reason());
        assertEquals("oracle.neg", replay.slot());
        assertNotNull(replay.detail().get("message"));

        BindException live = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals(FailureKind.RESOLUTION_FAILED, live.kind(), "live keeps ARGS");
        assertEquals("ARGS", live.reason());
    }

    /** {@code Negate} after a pack change of its input type. */
    static final class BooleanNegate implements ai.skillroy.testjava.scenario.resolve.Oracle<Boolean, Boolean> {
        @Override
        public String fn() {
            return "Negate";
        }

        @Override
        public Boolean compute(Boolean input) {
            return !input;
        }
    }

    @Test
    void replayIsUnreadableForAFileOfAnotherScenarioOrWithOtherSlotsOrOracles() throws Exception {
        approve();
        String good = Files.readString(file());
        List<java.util.function.Consumer<Map<String, Object>>> edits = List.of(
                json -> json.put("scenarioId", "other-scenario"),
                json -> obj(json, "resolutions").remove("item"),
                json -> obj(json, "oracles").remove("needs"),
                json -> obj(json, "resolutions").put("extra", obj(json, "resolutions").get("max")),
                json -> obj(json, "oracles").put("extra", obj(json, "oracles").get("neg")));
        List<String> expected = List.of("made for scenario other-scenario", "records the query slots",
                "records the oracles", "records the query slots", "records the oracles");
        for (int i = 0; i < edits.size(); i++) {
            Map<String, Object> json = Json.parseObject(good);
            edits.get(i).accept(json);
            Files.writeString(file(), Json.writePretty(json));
            toy.verifierCalls.set(0);
            BindException e = fails(plan(YAML), options(BindMode.REPLAY));
            assertEquals(FailureKind.REBIND_NEEDED, e.kind(), expected.get(i));
            assertEquals("UNREADABLE", e.reason(), expected.get(i));
            assertTrue(e.getMessage().contains(expected.get(i)), e.getMessage());
            assertEquals("seed-a/toy-resolve.bindings.json", e.detail().get("bindingsFile"));
            assertEquals(0, toy.verifierCalls.get(), "8.3 step 3 comes before any verifier");
        }
    }

    @Test
    void anOracleWhoseInputsChangedIsArgsChangedNamingTheOracle() throws Exception {
        approve();
        Map<String, Object> json = Json.parseObject(Files.readString(file()));
        obj(obj(json, "oracles"), "neg").put("inputsHash", "sha256:0000");
        Files.writeString(file(), Json.writePretty(json));
        BindException e = fails(plan(YAML), options(BindMode.REPLAY));
        assertEquals(FailureKind.REBIND_NEEDED, e.kind());
        assertEquals("ARGS_CHANGED", e.reason());
        assertEquals("oracle.neg", e.slot());
        assertEquals("neg", e.detail().get("oracle"));
        assertEquals("sha256:0000", e.detail().get("expected"));
        assertTrue(String.valueOf(e.detail().get("actual")).startsWith("sha256:"));
    }

    @Test
    void noBacktrackingAcrossSlotsAndTheFixedUpstreamSlotsOfEachFailure() throws Exception {
        // Staff limits 6.00 and 50.00: nobody is below item B2's deposit 5.00, although item C3 (9.99) would let
        // staff 100 qualify. The binder does not go back to the item slot (D5).
        toy.staff.clear();
        toy.staff.add(List.of("100", "6.00"));
        toy.staff.add(List.of("200", "50.00"));
        BindException none = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals("NO_CANDIDATE", none.reason());
        assertEquals("assistant", none.slot());
        assertEquals(List.of(Map.of("slot", "item", "label", "{\"code\":\"B2\",\"deposit\":\"5.00\"}")),
                none.detail().get("fixedUpstream"));
        assertEquals(1, toy.itemResolverCalls.get(), "the item resolver was asked once");

        toy.staff.set(0, List.of("100", "2.00"));
        BindException rejected = fails(plan(YAML), options(BindMode.LIVE).withCredentials(Credentials.empty()));
        assertEquals("ALL_REJECTED", rejected.reason());
        assertEquals(List.of("item"), ((List<?>) rejected.detail().get("fixedUpstream")).stream()
                .map(u -> ((Map<?, ?>) u).get("slot")).toList());
        assertEquals(2, toy.itemResolverCalls.get(), "once more for the second bind, never again within it");

        toy.staff.set(0, List.of("100", "60.00"));
        BindException guard = fails(plan(YAML.replace("limitBelow: $item.deposit", "limitBelow: \"99\"")),
                options(BindMode.LIVE));
        assertEquals("GUARD", guard.reason());
        assertEquals(List.of("item", "assistant"), ((List<?>) guard.detail().get("fixedUpstream")).stream()
                .map(u -> ((Map<?, ?>) u).get("slot")).toList());
        assertEquals(3, toy.itemResolverCalls.get());
    }

    @Test
    void aCredentialFieldThatIsAlsoAValueFieldOrAMissingKeyFieldIsEnvironment() throws Exception {
        Map<String, Object> staff = Map.of("100", Map.of("password", TestCredentials.PW_100, "limit", "1.00"));
        BindException collision = fails(plan(YAML), options(BindMode.LIVE).withCredentials(TestCredentials.of(
                Map.of("version", 1L, "credentials", Map.of("staff", staff)))));
        assertEquals(FailureKind.ENVIRONMENT, collision.kind());
        assertEquals("assistant", collision.slot());
        assertTrue(collision.getMessage().contains("the credential field 'limit' of staff/100 is already a field"
                + " of the value"), collision.getMessage());
        assertFalse(collision.getMessage().contains(TestCredentials.PW_100));

        BindException key = fails(plan(YAML.replace("realm: staff, key: code", "realm: staff, key: badge")),
                options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, key.kind());
        assertTrue(key.getMessage().contains("key 'badge', but its value has no such scalar field"),
                key.getMessage());
    }

    @Test
    void nullsAndExceptionsFromPackCodeAreEnvironment() throws Exception {
        List<java.util.function.Supplier<List<ResolverCandidate<ToyResolution.ItemRef>>>> answers = List.of(
                () -> null,
                () -> java.util.Arrays.asList(ResolverCandidate.of(new ToyResolution.ItemRef("B2",
                        new java.math.BigDecimal("5.00")), "item B2"), null),
                () -> List.of(ResolverCandidate.of(null, "item ?")));
        List<String> expected = List.of("resolver toy.sql.Item returned null",
                "resolver toy.sql.Item returned a null candidate", "resolver toy.sql.Item returned a null value");
        for (int i = 0; i < answers.size(); i++) {
            pack.resolution = ResolutionCatalogue.builder(toy.catalogue()).replace(itemResolver(answers.get(i)))
                    .build();
            BindException e = fails(plan(YAML), options(BindMode.LIVE));
            assertEquals(FailureKind.ENVIRONMENT, e.kind(), expected.get(i));
            assertEquals("item", e.slot());
            assertTrue(e.getMessage().contains("pack defect: " + expected.get(i)), e.getMessage());
        }
        pack.resolution = toy.catalogue();

        toy.beforeStaffCheck = ctx -> {
            throw new IllegalStateException("verifier bug");
        };
        BindException verifier = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, verifier.kind());
        assertEquals("assistant", verifier.slot());
        assertTrue(verifier.getMessage().contains("pack defect: verifier toy.staff.check failed"),
                verifier.getMessage());
        toy.beforeStaffCheck = ctx -> { };

        pack.resolution = ResolutionCatalogue.builder(toy.catalogue()).replace(new FailingNegate()).build();
        BindException oracle = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, oracle.kind());
        assertEquals("neg", oracle.detail().get("oracle"));
        assertTrue(oracle.getMessage().contains("pack defect: oracle Negate failed"), oracle.getMessage());
    }

    @Test
    void aPlanNamingAVerifierThePackLacksIsEnvironment() throws Exception {
        Plan plan = plan(YAML);
        assertEquals(List.of("toy.item.check"), plan.slot("item").query().verify());
        Verifier<ToyResolution.Item, ToyResolution.ItemRef> renamed = new Verifier<>() {
            @Override
            public String id() {
                return "toy.item.renamed";
            }

            @Override
            public Class<ToyResolution.Item> queryClass() {
                return ToyResolution.Item.class;
            }

            @Override
            public VerifierResult verify(ToyResolution.Item query, ToyResolution.ItemRef candidate,
                                         ai.skillroy.testjava.scenario.resolve.VerifyContext ctx) {
                return VerifierResult.pass("renamed", Map.of());
            }
        };
        pack.resolution = ResolutionCatalogue.builder()
                .query(ai.skillroy.testjava.scenario.resolve.QueryType.of("Item", ToyResolution.Item.class))
                .query(ai.skillroy.testjava.scenario.resolve.QueryType.of("Staff", ToyResolution.Staff.class))
                .query(ai.skillroy.testjava.scenario.resolve.QueryType.of("Limit", ToyResolution.Limit.class))
                .resolver(toy.new ItemResolver()).resolver(toy.new StaffResolver()).resolver(toy.new LimitResolver())
                .verifier(renamed).verifier(toy.new StaffCheck()).verifier(toy.new LimitCheck())
                .oracle(new ToyResolution.Negate()).oracle(new ToyResolution.Needs()).build();
        for (BindMode mode : List.of(BindMode.LIVE, BindMode.REPLAY)) {
            BindException e = fails(plan, options(mode));
            assertEquals(FailureKind.ENVIRONMENT, e.kind(), mode.label());
            assertEquals("item", e.slot());
            assertTrue(e.getMessage().contains("names verifier toy.item.check for kind Item, which pack toy"),
                    e.getMessage());
            assertTrue(e.getMessage().endsWith("recompile"), e.getMessage());
        }
        assertEquals(0, toy.itemResolverCalls.get(), "8.3 step 1 comes before any pack call that reads");
    }

    private Resolver<ToyResolution.Item, ToyResolution.ItemRef> itemResolver(
            java.util.function.Supplier<List<ResolverCandidate<ToyResolution.ItemRef>>> answer) {
        return new Resolver<>() {
            @Override
            public String id() {
                return "toy.sql.Item";
            }

            @Override
            public Class<ToyResolution.Item> queryClass() {
                return ToyResolution.Item.class;
            }

            @Override
            public Strategy strategy() {
                return Strategy.CANONICAL_SQL;
            }

            @Override
            public List<ResolverCandidate<ToyResolution.ItemRef>> candidates(ToyResolution.Item query,
                    ai.skillroy.testjava.scenario.resolve.ResolveContext ctx) {
                return answer.get();
            }
        };
    }

    /** {@code Negate} with a bug. */
    static final class FailingNegate implements ai.skillroy.testjava.scenario.resolve.Oracle<java.math.BigDecimal,
            java.math.BigDecimal> {
        @Override
        public String fn() {
            return "Negate";
        }

        @Override
        public java.math.BigDecimal compute(java.math.BigDecimal input) {
            throw new ArithmeticException("oracle bug");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Map<String, Object> json, String key) {
        return (Map<String, Object>) json.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> slot(Map<String, Object> json, String name) {
        return (Map<String, Object>) ((Map<String, Object>) json.get("resolutions")).get(name);
    }

    @Test
    void aFailedGuardInReplayIsRebindNeeded() throws Exception {
        String yaml = """
                scenario: toy-resolve
                pack: toy
                resolve:
                  max: { query: Limit, args: { type: RETURN } }
                  want: { query: Limit, args: { type: WANT } }
                oracle:
                  same: { fn: Identity, args: [$max], must_equal: $want }
                steps: []
                """;
        bind(plan(yaml), options(BindMode.APPROVE).withApprover(Approver.APPROVE_ALL));
        Map<String, Object> json = Json.parseObject(Files.readString(file()));
        slot(json, "want").put("value", "99.00");
        Files.writeString(file(), Json.writePretty(json));
        toy.limitVerifierAccepts = true;
        BindException guard = fails(plan(yaml), options(BindMode.REPLAY));
        assertEquals(FailureKind.REBIND_NEEDED, guard.kind());
        assertEquals("GUARD", guard.reason());
        assertEquals(Map.of("mustEqual", "99.00", "actual", "100.00"), guard.detail().get("guard"));
    }

    // ------------------------------------------------------------------------------------------------ secrets

    @Test
    void aJoinedSecretFieldResolvesOnlyInCallArguments() throws Exception {
        String inResolve = YAML.replace("args: { type: RETURN } }", "args: { type: RETURN } }\n"
                + "  first: { fixture: { code: \"100\" }, credential: { realm: staff, key: code } }\n"
                + "  copy: { fixture: $first.password }");
        BindException resolve = fails(plan(inResolve), options(BindMode.LIVE));
        assertEquals(FailureKind.RESOLUTION_FAILED, resolve.kind());
        assertEquals("REFERENCE", resolve.reason());

        BindException oracle = fails(plan(YAML.replace("args: [$item.deposit]", "args: [$assistant.password]")),
                options(BindMode.LIVE));
        assertEquals("REFERENCE", oracle.reason());

        BindException expectation = fails(plan(YAML + "  - expect: { \"state.name\": $assistant.password }\n"),
                options(BindMode.LIVE));
        assertEquals("REFERENCE", expectation.reason());
        assertTrue(expectation.getMessage().contains("credential field"), expectation.getMessage());

        pack.storedTables = List.of(ai.skillroy.testjava.scenario.resolve.StoredTable.of("staff", "toy", "staff",
                List.of(ai.skillroy.testjava.scenario.resolve.StoredColumn.of("code",
                        ai.skillroy.testjava.scenario.resolve.ColumnKind.TEXT)), List.of("code")));
        BindException db = fails(plan(YAML + "verify:\n  - db.staff: { where: { code: $assistant.password },"
                + " rows: 1 }\n"), options(BindMode.LIVE));
        assertEquals("REFERENCE", db.reason());

        toy.verifierCalls.set(0);
        BindOutcome ok = bind(plan(YAML), options(BindMode.LIVE));
        for (Resolution r : ok.bindings().resolutions().values()) {
            assertFalse(Json.write(r.args()).contains(TestCredentials.PW_100));
            assertFalse(r.argsHash().contains(TestCredentials.PW_100));
        }
        for (FakeAgentConnection.Call call : agent.calls) {
            assertFalse(String.valueOf(call.params()).contains(TestCredentials.PW_100), "no resolver or verifier"
                    + " call carries the secret: " + call);
        }
    }

    @Test
    void noBinderMessageQuotesAJoinedSecret() throws Exception {
        String secret = "pw-joined-secret";
        Bindings joined = Bindings.of(Map.of("assistant", Map.of("code", "100"))).withCredential("assistant",
                Map.of("password", secret));
        Plan plan = plan("""
                scenario: toy-resolve
                pack: toy
                resolve:
                  assistant: { fixture: { code: "100" }, credential: { realm: staff, key: code } }
                steps:
                  - enter: { value: $assistant.password.first }
                """);
        ResolutionException check = assertThrows(ResolutionException.class, () -> Binder.check(plan, joined));
        assertFalse(check.getMessage().contains(secret), check.getMessage());
        assertTrue(check.getMessage().contains("'password' is a credential field of slot 'assistant'"),
                check.getMessage());
        assertEquals("REFERENCE", check.reason());
        for (String reference : List.of("x $assistant.password.0", "$assistant.password.first")) {
            ResolutionException e = assertThrows(ResolutionException.class, () -> joined.substitute(reference));
            assertFalse(e.getMessage().contains(secret), e.getMessage());
        }
        ResolutionException lookup = assertThrows(ResolutionException.class,
                () -> joined.lookup("$assistant.password.last"));
        assertFalse(lookup.getMessage().contains(secret), lookup.getMessage());
        assertEquals(secret, joined.lookup("$assistant.password"), "the field itself resolves for call arguments");
        assertEquals("100", joined.lookup("$assistant.code"));

        BindException bind = fails(plan, options(BindMode.LIVE));
        assertEquals("REFERENCE", bind.reason());
        assertFalse(bind.getMessage().contains(TestCredentials.PW_100), bind.getMessage());
        assertFalse(String.valueOf(bind.detail()).contains(TestCredentials.PW_100), String.valueOf(bind.detail()));
    }

    // ------------------------------------------------------------------------------------------------ others
    // ------------------------------------------------------------------------------------------------ others

    @Test
    void aPlanWithoutQuerySlotsIsBoundAsInPhaseOnePlusTheJoin() throws Exception {
        Plan plan = plan("""
                scenario: toy-resolve
                pack: toy
                resolve:
                  assistant: { fixture: { code: "100" }, credential: { realm: staff, key: code } }
                  label: { fixture: "env $env.id" }
                steps:
                  - signOn: { staff: $assistant }
                """);
        assertFalse(plan.needsBindings());
        BindOutcome outcome = bind(plan, options(BindMode.REPLAY).withBindingsRoot(null));
        assertNull(outcome.env(), "no fingerprint");
        assertNull(outcome.file());
        assertEquals(List.of(), events);
        assertEquals("env seed-a", outcome.bindings().values().get("label"));
        assertEquals(TestCredentials.PW_100, ((Map<?, ?>) outcome.bindings().values().get("assistant"))
                .get("password"));
        BindException missing = fails(plan, options(BindMode.REPLAY).withCredentials(Credentials.empty()));
        assertEquals(FailureKind.ENVIRONMENT, missing.kind());
        assertTrue(missing.getMessage().contains("staff/100"), missing.getMessage());
    }

    @Test
    void aPlanNamingWhatTheRunningPackLacksIsEnvironment() throws Exception {
        Plan plan = plan(YAML);
        pack.resolution = ResolutionCatalogue.EMPTY;
        BindException e = fails(plan, options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, e.kind());
        assertTrue(e.getMessage().contains("recompile"), e.getMessage());
    }

    @Test
    void aVariantPackBuiltOnTheBaseCatalogueBindsThePlanOfTheBase() throws Exception {
        Plan plan = plan(YAML);
        AtomicInteger variantCalls = new AtomicInteger();
        Resolver<ToyResolution.Item, ToyResolution.ItemRef> variant = new Resolver<>() {
            @Override
            public String id() {
                return "toy.sql.Item";
            }

            @Override
            public Class<ToyResolution.Item> queryClass() {
                return ToyResolution.Item.class;
            }

            @Override
            public Strategy strategy() {
                return Strategy.CANONICAL_SQL;
            }

            @Override
            public List<ResolverCandidate<ToyResolution.ItemRef>> candidates(ToyResolution.Item query,
                                                                            ai.skillroy.testjava.scenario.resolve
                                                                                    .ResolveContext ctx) {
                variantCalls.incrementAndGet();
                return List.of(ResolverCandidate.of(new ToyResolution.ItemRef("C3",
                        new java.math.BigDecimal("9.99")), "variant C3"));
            }
        };
        pack.resolution = ResolutionCatalogue.builder(toy.catalogue()).replace(variant).build();
        Resolution item = bind(plan, options(BindMode.LIVE)).bindings().resolutions().get("item");
        assertEquals(1, variantCalls.get());
        assertEquals(Map.of("code", "C3", "deposit", "9.99"), item.value());
    }

    @Test
    void theResolutionEngineResolvesOneQueryOutsideAPlan() throws Exception {
        try (ResolutionEngine engine = new ResolutionEngine(pack, agent, options(BindMode.LIVE),
                Duration.ofSeconds(30), null)) {
            Resolution staff = engine.resolve("who", "Staff", Map.of("limitBelow", "3.00"), List.of("sql"), List.of(),
                    new ai.skillroy.testjava.scenario.resolve.CredentialSpec("staff", "code"));
            assertEquals(Map.of("code", "100", "limit", "2.00"), staff.value());
            assertEquals("-3.50", engine.evaluate("Negate", List.of("3.50")));
            assertEquals("PROMPT", engine.evaluate("Needs", Map.of("limit", "1", "amount", "2")));
            assertEquals("A", engine.fingerprint().components().get("toy.seed"));
            assertEquals(TestCredentials.PW_100, ((Map<?, ?>) engine.bindings().values().get("who"))
                    .get("password"));
            BindException unknown = assertThrows(BindException.class, () -> engine.evaluate("Nope", 1L));
            assertEquals(FailureKind.ENVIRONMENT, unknown.kind());
        }
    }

    @Test
    void theFingerprintRejectsKeysWithoutThePackPrefixAndTruncatedStatements() throws Exception {
        pack.fingerprinter = ctx -> Map.of("other.seed", "A");
        BindException prefix = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, prefix.kind());
        assertTrue(prefix.getMessage().contains("pack prefix"), prefix.getMessage());
        toy.tables.put("SELECT n FROM big ORDER BY n", Rows.of(List.of(new Rows.Column("N", "INTEGER")),
                List.of(List.of(1L)), true));
        pack.fingerprinter = ctx -> {
            ctx.data().query(ToyResolution.SOURCE, "SELECT n FROM big ORDER BY n", List.of(), 200, null);
            return Map.of("toy.big", "x");
        };
        BindException truncated = fails(plan(YAML), options(BindMode.LIVE));
        assertTrue(truncated.getMessage().contains("truncated"), truncated.getMessage());
    }
}
