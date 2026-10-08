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

import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.resolve.Approval;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.ContributionException;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.LlmUsage;
import ai.skillroy.testjava.scenario.resolve.Provenance;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.ResolverContributor;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The binder and the engine with contributed, model-backed resolvers (phase5-contracts.md 5.5, 7.1, 7.2, 8.2, 8.3;
 * acceptance 15.1 item 7): the test tree's {@link FakeContributor}, found through {@code META-INF/services}, offers
 * fake {@code LLM_SQL} resolvers for the toy pack's {@code Item} kind, one of them batchable. The ladder order, replay
 * loading nothing, approval {@code REQUIRED} and the replay refusal {@code NOT_APPROVED}, approve's reuse, the usage
 * from the ledger, {@code BUDGET}, and the stepwise bind of {@code re-resolve} that parks and resumes.
 *
 * <p>The plans are compiled from YAML without model tokens and given their {@code strategy} lists afterwards: until
 * the compiler accepts {@code llm-sql} (P5 13.1, built by another wave) it refuses them.
 */
class ContributedResolutionTest {

    static final String ONE = """
            scenario: toy-llm
            pack: toy
            resolve:
              item: { query: Item, args: { deposit: { between: ["2.00", "50.00"] } } }
            steps: []
            """;

    static final String WITH_MAX = """
            scenario: toy-llm
            pack: toy
            resolve:
              max: { query: Limit, args: { type: RETURN } }
              item: { query: Item, args: { deposit: { between: ["2.00", "50.00"], lte: $max } } }
            steps: []
            """;

    static final String TWO = """
            scenario: toy-llm
            pack: toy
            resolve:
              item: { query: Item, args: { deposit: { between: ["2.00", "50.00"] } } }
              dearer: { query: Item, args: { deposit: { gt: $item.deposit } } }
            steps: []
            """;

    @TempDir
    Path root;

    private final ToyResolution toy = new ToyResolution();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private final List<Map<String, Object>> records = new ArrayList<>();
    private final FakeContributor.FakeLlm llm = new FakeContributor.FakeLlm();
    private final FakeContributor.FakeBatch batch = new FakeContributor.FakeBatch();
    private ToyPack pack;

    @BeforeEach
    void setUp() {
        FakeContributor.reset();
        toy.install(agent);
        pack = toy.pack();
    }

    @AfterEach
    void tearDown() {
        FakeContributor.reset();
    }

    /** The plan of {@code yaml} with these strategy lists on its query slots. */
    private Plan plan(String yaml, Map<String, List<String>> strategies) throws ScenarioException {
        return withStrategy(PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "toy-llm.yaml", pack),
                strategies);
    }

    /** The plan with other strategy lists, and {@code inference: bind} when one names a model token. */
    static Plan withStrategy(Plan plan, Map<String, List<String>> strategies) {
        // E1: once the compiler accepts llm-sql (P5 13.1) and writes inference: bind (13.2), compile these from YAML.
        List<Plan.Slot> slots = new ArrayList<>();
        boolean model = false;
        for (Plan.Slot slot : plan.slots()) {
            List<String> tokens = strategies.get(slot.name());
            if (tokens != null && "query".equals(slot.kind())) {
                Plan.QuerySpec q = slot.query();
                slot = new Plan.Slot(slot.name(), slot.kind(), slot.value(), new Plan.QuerySpec(q.query(), q.args(),
                        tokens, q.verify()), slot.credential(), slot.capture());
                model |= tokens.stream().anyMatch(t -> Strategy.ofToken(t) != null && Strategy.ofToken(t).needsModel());
            }
            slots.add(slot);
        }
        return new Plan(plan.planVersion(), plan.scenarioId(), plan.pack(), plan.packVersion(), plan.sourceHash(),
                plan.requires(), slots, plan.steps(), plan.verify(), plan.cleanup(), plan.launchProperties(),
                plan.artifacts(), plan.onPrompt(), plan.stepTimeoutMs(), plan.ui(), model ? "bind" : "none",
                plan.oracles(), plan.resolutionGraph(), plan.bindTimeoutMs(), plan.web());
    }

    /** Settings that name a gateway, so that the contributor offers what the test set. */
    static InferenceSettings gateway() {
        return new InferenceSettings(InferenceSettings.GATEWAY_RECORDED, Path.of("recordings"), false, null, null,
                null, null, null, null, true, false, null, false, null);
    }

    private BindOptions options(BindMode mode) {
        return TestCredentials.bind().withMode(mode).withEnvId("seed-a").withBindingsRoot(root).withClock(clock)
                .withInference(gateway());
    }

    private void record(String type, Map<String, Object> fields) {
        Map<String, Object> copy = new LinkedHashMap<>(fields);
        copy.put("type", type);
        records.add(copy);
    }

    private List<Map<String, Object>> records(String type) {
        return records.stream().filter(r -> type.equals(r.get("type"))).toList();
    }

    private Map<String, Object> resolveRecord(String slot) {
        return records("resolve").stream().filter(r -> slot.equals(r.get("slot"))).reduce((a, b) -> b)
                .orElseThrow();
    }

    private BindOutcome bind(Plan plan, BindOptions options) throws Exception {
        records.clear();
        return Binder.bind(plan, pack, agent, options, this::record);
    }

    private BindException fails(Plan plan, BindOptions options) {
        return assertThrows(BindException.class, () -> bind(plan, options));
    }

    private BindStep stepwise(Plan plan, BindOptions options) throws Exception {
        records.clear();
        return Binder.bindStepwise(plan, pack, agent, options, this::record);
    }

    private Path file() {
        return BindingsFile.locate(root, "seed-a", "toy-llm");
    }

    private long statements(String sql) {
        return agent.callsTo("data.query").stream().filter(c -> sql.equals(c.params().get("sql"))).count();
    }

    /** The open sessions of the embedded database. */
    private static long sessions(java.sql.Statement st) throws java.sql.SQLException {
        try (java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SYSTEM_SESSIONS")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Map<?, ?> slotJson(Map<String, Object> file, String slot) {
        return (Map<?, ?>) ((Map<?, ?>) file.get("resolutions")).get(slot);
    }

    // ------------------------------------------------------------------------------------------------ the ladder

    @Test
    void theContributedResolverIsAskedAfterThePacksAndOnlyWhenTheyFoundNothingAcceptable() throws Exception {
        List<Object> given = new ArrayList<>();
        FakeContributor.offer = (p, settings, ledger, c) -> {
            given.addAll(List.of(p, settings, ledger, c));
            return List.of(llm);
        };
        llm.codes = List.of("A1");
        Plan plan = plan(ONE, Map.of("item", List.of("sql", "llm-sql")));
        BindOptions live = options(BindMode.LIVE);
        Resolution first = bind(plan, live).bindings().resolutions().get("item");
        assertEquals("toy.sql.Item", first.provenance().resolverId(), "the pack's resolver first; its candidate passed");
        assertEquals(0, llm.calls.get(), "no model call when the committed SQL found an acceptable value");
        assertEquals(new Approval(Approval.State.NOT_REQUIRED, "live"), first.approval(), "tier (a) as in Phase 2");
        assertNull(first.usage());
        assertEquals(1, FakeContributor.CONSTRUCTED.get(), "loaded once, for the slot that names llm-sql");
        assertEquals(1, FakeContributor.ASKED.get());
        assertEquals(1, FakeContributor.CLOSED.get(), "closed when the bind ended");
        assertEquals(List.of(pack, gateway(), live.ledger(), clock), given, "the pack, the settings, the run's ledger"
                + " and the bind clock");
        Map<String, Object> inference = records("inference").get(0);
        assertEquals("live", inference.get("mode"));
        assertEquals("recorded", inference.get("gateway"));
        assertEquals(List.of(FakeContributor.class.getName()), inference.get("contributors"));
        assertEquals(List.of("inference.fake-sql"), inference.get("resolvers"));
        assertNull(inference.get("loadError"));

        toy.inactive.addAll(List.of("B2", "C3"));
        Resolution second = bind(plan, live).bindings().resolutions().get("item");
        assertEquals(1, llm.calls.get(), "asked once the pack's candidates were all rejected");
        assertEquals(List.of("toy.sql.Item", "inference.fake-sql"), records("resolver").stream()
                .map(r -> r.get("resolver")).toList(), "the ladder: the pack's, then the contributed");
        assertEquals("inference.fake-sql", second.provenance().resolverId());
        assertEquals(Strategy.LLM_SQL, second.strategy());
        assertEquals(Map.of("code", "A1", "deposit", "1.00"), second.value());
        assertEquals(0.9, second.confidence(), "the accepted candidate's confidence");
        assertEquals(new Approval(Approval.State.REQUIRED, null), second.approval(), "a model's value needs approval");
        assertEquals(List.of("toy.sql.Item", "toy.sql.Item"), second.provenance().rejected().stream()
                .map(Provenance.Rejected::resolverId).toList());
        assertEquals(3, second.provenance().candidates(), "over every resolver asked");
        assertEquals(List.of(ToyResolution.ITEM_SQL), second.provenance().statements().stream()
                .map(Provenance.Statement::sql).toList(), "the model resolver's own statements");
    }

    @Test
    void aSlotThatListsLlmSqlAloneAsksOnlyTheModel() throws Exception {
        FakeContributor.offering(llm);
        llm.codes = List.of("C3");
        Resolution item = bind(plan(ONE, Map.of("item", List.of("llm-sql"))), options(BindMode.LIVE)).bindings()
                .resolutions().get("item");
        assertEquals(0, toy.itemResolverCalls.get(), "the pack's resolver is not on the ladder");
        assertEquals("inference.fake-sql", item.provenance().resolverId());
        FakeContributor.Seen seen = llm.seen.get(0);
        assertEquals("item", seen.slot(), "the context names the slot for the ledger entry");
        assertEquals("toy-llm", seen.scenarioId(), "and the scenario");
    }

    @Test
    void aSlotWithoutAModelTokenNeverReachesAModelNorLoadsAContributor() throws Exception {
        FakeContributor.offering(llm);
        llm.codes = List.of("A1");
        toy.inactive.addAll(List.of("B2", "C3"));
        BindException unrestricted = fails(plan(ONE, Map.of()), options(BindMode.LIVE));
        assertEquals("ALL_REJECTED", unrestricted.reason(), "no strategy list keeps the pack's resolvers alone");
        BindException sqlOnly = fails(plan(ONE, Map.of("item", List.of("sql"))), options(BindMode.LIVE));
        assertEquals("ALL_REJECTED", sqlOnly.reason());
        assertEquals(0, llm.calls.get());
        assertEquals(0, FakeContributor.CONSTRUCTED.get(), "no contributor is even instantiated");
        assertTrue(records("inference").isEmpty());
    }

    @Test
    void replayLoadsNoContributorAndConstructsNoGatewayWhateverTheSettings() throws Exception {
        FakeContributor.offering(llm);
        llm.codes = List.of("C3");
        Plan plan = plan(ONE, Map.of("item", List.of("llm-sql")));
        bind(plan, options(BindMode.APPROVE).withApprover(Approver.APPROVE_ALL));
        assertEquals(1, llm.calls.get());
        FakeContributor.reset();

        CostLedger ledger = new CostLedger();
        BindOutcome replay = bind(plan, options(BindMode.REPLAY).withLedger(ledger));
        assertEquals("REPLAY", replay.bindings().strategy("item"));
        assertEquals(0, FakeContributor.CONSTRUCTED.get(), "replay instantiates no contributor");
        assertEquals(1, llm.calls.get());
        assertTrue(ledger.isEmpty(), "zero model calls");
        Map<String, Object> substituted = records("inference").get(0);
        assertEquals("replay", substituted.get("mode"));
        assertEquals("recorded", substituted.get("requested"));
        assertEquals("none", substituted.get("gateway"), "none() substituted and logged");
        assertEquals("inference", records.get(0).get("type"), "before anything else of the bind");

        bind(plan, options(BindMode.REPLAY).withInference(InferenceSettings.none()));
        assertTrue(records("inference").isEmpty(), "nothing to log when nothing was substituted");

        try (ResolutionEngine engine = new ResolutionEngine(pack, agent, options(BindMode.REPLAY), null, null)) {
            assertSame(InferenceSettings.none(), engine.inference());
            BindException e = assertThrows(BindException.class, () -> engine.resolve("item", "Item",
                    Map.of("deposit", Map.of("gt", "1")), List.of("llm-sql"), List.of(), null));
            assertEquals(FailureKind.ENVIRONMENT, e.kind());
            assertEquals("no resolver for strategy llm-sql: replay loads no contributor and makes no model call",
                    e.getMessage());
        }
        assertEquals(0, FakeContributor.CONSTRUCTED.get(), "not through the engine's public entry either");
    }

    @Test
    void aModelTokenThatNoContributorOffersIsEnvironment() throws Exception {
        Plan plan = plan(ONE, Map.of("item", List.of("sql", "llm-sql")));
        BindException none = fails(plan, options(BindMode.LIVE).withInference(InferenceSettings.none()));
        assertEquals(FailureKind.ENVIRONMENT, none.kind());
        assertEquals("item", none.slot());
        assertTrue(none.getMessage().startsWith("no resolver for strategy llm-sql: the gateway is none"),
                none.getMessage());
        assertEquals("llm-sql", none.detail().get("strategy"));
        assertEquals(0, toy.itemResolverCalls.get(), "refused before any resolver is asked, the committed SQL too");
        assertEquals(1, FakeContributor.ASKED.get(), "the contributor was consulted and offered nothing for none");

        BindException nothing = fails(plan, options(BindMode.LIVE));
        assertEquals("no resolver for strategy llm-sql: no contributor offers one for kind Item of pack toy (does the"
                + " pack declare an SQL allow-list?)", nothing.getMessage());

        Path services = root.resolve("cp/META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(ResolverContributor.class.getName()), "no.such.Contributor\n");
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader empty = new URLClassLoader(new URL[] {root.resolve("cp").toUri().toURL()}, null)) {
            Thread.currentThread().setContextClassLoader(empty);
            BindException missing = fails(plan, options(BindMode.LIVE));
            assertTrue(missing.getMessage().startsWith("no resolver for strategy llm-sql: skillroy-inference is not on"
                    + " the class path (a contributor could not be loaded: "), missing.getMessage());
            assertTrue(missing.getMessage().contains("no.such.Contributor"), missing.getMessage());
            assertEquals(List.of(), records("inference").get(0).get("contributors"));
        } finally {
            Thread.currentThread().setContextClassLoader(before);
        }
    }

    @Test
    void aContributedResolverMustBeAnInferenceModelResolver() throws Exception {
        Plan plan = plan(ONE, Map.of("item", List.of("sql", "llm-sql")));
        String contributor = "contributor " + FakeContributor.class.getName() + " offered ";
        FakeContributor.offering(new FakeContributor.FakeLlm("toy.sneaky", Strategy.LLM_SQL));
        BindException id = fails(plan, options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, id.kind());
        assertEquals(contributor + "resolver toy.sneaky: a contributed resolver's id must start with inference.",
                id.getMessage());
        FakeContributor.offering(new FakeContributor.FakeLlm("inference.sneaky", Strategy.CANONICAL_SQL));
        BindException strategy = fails(plan, options(BindMode.LIVE));
        assertTrue(strategy.getMessage().startsWith(contributor + "resolver inference.sneaky with strategy"
                + " CANONICAL_SQL"), strategy.getMessage());
        assertEquals(0, toy.itemResolverCalls.get(), "refused before the ladder runs: a sql token cannot reach it");
        FakeContributor.offering(new FakeContributor.FakeLlm("inference.", Strategy.LLM_SQL));
        assertTrue(fails(plan, options(BindMode.LIVE)).getMessage().contains("must start with inference."));
        FakeContributor.offering(llm, new FakeContributor.FakeLlm());
        assertTrue(fails(plan, options(BindMode.LIVE)).getMessage().contains("inference.fake-sql a second time"));
        FakeContributor.offer = (p, s, l, c) -> null;
        assertTrue(fails(plan, options(BindMode.LIVE)).getMessage().endsWith("offered null instead of a list of"
                + " resolvers"));
        FakeContributor.offer = (p, s, l, c) -> {
            throw new ContributionException("the model key is not set (ANTHROPIC_API_KEY)");
        };
        BindException contribution = fails(plan, options(BindMode.LIVE));
        assertEquals("contributor " + FakeContributor.class.getName() + " cannot offer its resolvers: the model key"
                + " is not set (ANTHROPIC_API_KEY)", contribution.getMessage());
        assertEquals(FailureKind.ENVIRONMENT, contribution.kind());
        FakeContributor.offer = (p, s, l, c) -> {
            throw new IllegalStateException("boom");
        };
        assertTrue(fails(plan, options(BindMode.LIVE)).getMessage().endsWith("failed: java.lang.IllegalStateException:"
                + " boom"));
        assertEquals(7, FakeContributor.CONSTRUCTED.get());
        assertEquals(FakeContributor.CONSTRUCTED.get(), FakeContributor.CLOSED.get(), "every contributor loaded was"
                + " closed, also after a refused offer");
    }

    // ------------------------------------------------------------------------------------------------ approval

    @Test
    void aModelWrittenValueIsRequiredUntilAPersonApprovesIt() throws Exception {
        FakeContributor.offering(llm);
        llm.codes = List.of("C3");
        Plan plan = plan(WITH_MAX, Map.of("item", List.of("llm-sql")));
        BindOutcome live = bind(plan, options(BindMode.LIVE).withWriteBindings(true));
        assertEquals(file(), live.written());
        String text = Files.readString(file());
        BindingsFile written = BindingsFile.read(file());
        assertEquals(new Approval(Approval.State.REQUIRED, null), written.resolutions().get("item").approval(),
                "live --write-bindings writes REQUIRED");
        assertEquals(new Approval(Approval.State.NOT_REQUIRED, "live"), written.resolutions().get("max").approval(),
                "tier (a) as before");
        assertTrue(text.contains("\"state\": \"REQUIRED\""), text);
        assertEquals(text, written.toJsonText(), "REQUIRED reads back and writes the same bytes");
        assertEquals(Approval.State.REQUIRED, live.bindings().resolutions().get("item").approval().state());

        int verified = toy.verifierCalls.get();
        BindException replay = fails(plan, options(BindMode.REPLAY));
        assertEquals(FailureKind.REBIND_NEEDED, replay.kind());
        assertEquals("NOT_APPROVED", replay.reason());
        assertEquals("item", replay.slot());
        assertEquals("item", replay.detail().get("slot"));
        assertEquals("seed-a/toy-llm.bindings.json", replay.detail().get("bindingsFile"));
        assertEquals("LLM_SQL", replay.detail().get("strategy"));
        assertEquals("REQUIRED", ((Map<?, ?>) replay.detail().get("approval")).get("state"));
        assertTrue(replay.getMessage().contains("not approved"), replay.getMessage());
        assertEquals(verified + 1, toy.verifierCalls.get(), "only the tier (a) slot before it was verified");

        List<Approver.ApprovalRequest> seen = new ArrayList<>();
        bind(plan, options(BindMode.APPROVE).withApprover(request -> {
            seen.add(request);
            return new Approver.Decision(true, null, null);
        }));
        Resolution shown = seen.get(0).resolutions().get(1);
        assertEquals(Approval.State.REQUIRED, shown.approval().state(), "shown for approval as a model's value");
        assertEquals(Strategy.LLM_SQL, shown.strategy());
        assertEquals(0.9, shown.confidence());
        assertEquals(new Approval(Approval.State.APPROVED, "approve"), BindingsFile.read(file()).resolutions()
                .get("item").approval(), "approve writes APPROVED");
        assertEquals("REPLAY", bind(plan, options(BindMode.REPLAY)).bindings().strategy("item"), "now it replays");

        Files.writeString(file(), Files.readString(file()).replace("\"APPROVED\"", "\"NOT_REQUIRED\""));
        BindException edited = fails(plan, options(BindMode.REPLAY));
        assertEquals("NOT_APPROVED", edited.reason(), "anything but APPROVED");
        assertEquals("NOT_REQUIRED", ((Map<?, ?>) edited.detail().get("approval")).get("state"));
    }

    /**
     * P5 5.4 steps 3 and 5, the provenance seams: a contributed resolver's {@code ctx.rationale} becomes the slot's
     * {@code Provenance.rationale} (masked, in the bindings file and the resolve record, never a note), and its
     * {@code ctx.reject} joins the slot's rejected list under its id (masked), in the file and in a failure's detail;
     * the rationale survives approve's reuse.
     */
    @Test
    void aContributedResolversRationaleAndRefusalsReachTheProvenanceAndTheBindingsFile() throws Exception {
        FakeContributor.offering(llm);
        llm.codes = List.of("C3");
        llm.rationale = "the dearest active item within the return limit; never " + TestCredentials.PW_100;
        llm.rejections = List.of(List.of("proposal", "confidence 0.6 below 0.8"),
                List.of("row B2", "the value loader refused it: " + TestCredentials.PW_200));
        Plan plan = plan(WITH_MAX, Map.of("item", List.of("llm-sql")));
        BindOutcome live = bind(plan, options(BindMode.LIVE).withWriteBindings(true));
        Resolution item = live.bindings().resolutions().get("item");
        String rationale = item.provenance().rationale();
        assertNotNull(rationale, "the rationale reached the provenance");
        assertTrue(rationale.startsWith("the dearest active item within the return limit; never "), rationale);
        assertFalse(rationale.contains(TestCredentials.PW_100), "masked by the engine: " + rationale);
        assertTrue(item.provenance().notes().stream().noneMatch(n -> n.contains("dearest")), "not a note");
        List<Provenance.Rejected> rejected = item.provenance().rejected();
        assertEquals(new Provenance.Rejected("inference.fake-sql", "proposal", "confidence 0.6 below 0.8"),
                rejected.get(0), "a refused proposal under the resolver's id");
        assertEquals("row B2", rejected.get(1).label());
        assertFalse(rejected.get(1).reason().contains(TestCredentials.PW_200), "masked: " + rejected.get(1));
        assertEquals(rationale, resolveRecord("item").get("rationale"), "the resolve record shows it");
        assertNull(resolveRecord("max").get("rationale"), "tier (a) has none");

        String text = Files.readString(file());
        BindingsFile written = BindingsFile.read(file());
        assertEquals(rationale, written.resolutions().get("item").provenance().rationale(), "written and read");
        assertEquals(text, written.toJsonText(), "byte-identical when read back");
        assertEquals(2, written.resolutions().get("item").provenance().rejected().size());
        Map<String, Object> json = Json.parseObject(text);
        assertFalse(((Map<?, ?>) slotJson(json, "max").get("provenance")).containsKey("rationale"),
                "a tier (a) slot's file is unchanged: no rationale key");
        assertEquals(rationale, ((Map<?, ?>) slotJson(json, "item").get("provenance")).get("rationale"));

        bind(plan, options(BindMode.APPROVE).withApprover(Approver.APPROVE_ALL));
        assertEquals(1, llm.calls.get(), "approve reused the value");
        assertEquals(rationale, BindingsFile.read(file()).resolutions().get("item").provenance().rationale(),
                "the reused value keeps its rationale");

        llm.codes = List.of();
        llm.rejections = List.of(List.of("proposal", "guard STAR: *"));
        BindException none = fails(plan, options(BindMode.LIVE));
        assertEquals("NO_CANDIDATE", none.reason());
        assertEquals(List.of(Map.of("resolver", "inference.fake-sql", "label", "proposal", "reason",
                "guard STAR: *")), none.detail().get("rejected"), "the failure's detail names the refusal");
    }

    @Test
    void approveReusesAnUnchangedVerifiedTierBValueWithoutAskingAResolver() throws Exception {
        FakeContributor.offering(llm);
        llm.codes = List.of("C3");
        Plan plan = plan(WITH_MAX, Map.of("item", List.of("llm-sql")));
        BindOptions approve = options(BindMode.APPROVE).withApprover(Approver.APPROVE_ALL);
        bind(plan, approve);
        assertEquals(1, llm.calls.get());
        String text = Files.readString(file());
        long limitStatements = statements(ToyResolution.LIMIT_SQL);

        List<Approver.ApprovalRequest> seen = new ArrayList<>();
        BindOutcome reused = bind(plan, approve.withApprover(request -> {
            seen.add(request);
            return new Approver.Decision(true, null, null);
        }));
        assertEquals(1, llm.calls.get(), "unchanged and verified now: no resolver is asked");
        assertEquals(1, FakeContributor.CONSTRUCTED.get(), "the contributor is not even loaded");
        assertNull(reused.written(), "a re-approval of an unchanged value leaves the file as it was");
        assertEquals(text, Files.readString(file()));
        Map<String, Object> record = resolveRecord("item");
        assertEquals(true, record.get("reused"));
        assertEquals("approve", record.get("reusedFrom"), "the approval.by of the file it came from");
        assertFalse(records("resolver").stream().anyMatch(r -> "item".equals(r.get("slot"))));
        Resolution offered = seen.get(0).resolutions().get(1);
        assertEquals(List.of("reused from the bindings file of approve: verified now, no model call"),
                offered.provenance().notes(), "the approver sees it; the file never holds notes");
        assertEquals(Approval.State.REQUIRED, offered.approval().state());
        assertEquals(1, offered.verified().size(), "verified now");
        assertEquals(limitStatements + 1, statements(ToyResolution.LIMIT_SQL), "a tier (a) slot is never reused");
        assertNull(resolveRecord("max").get("reused"));

        toy.inactive.add("C3");
        llm.codes = List.of("B2");
        Resolution failsNow = bind(plan, approve).bindings().resolutions().get("item");
        assertEquals(2, llm.calls.get(), "the recorded value fails a verifier now: resolved afresh");
        assertEquals(Map.of("code", "B2", "deposit", "5.00"), failsNow.value());
        assertEquals(List.of("not reused from the bindings file: verifier toy.item.check refuses it now: item C3 is"
                + " not active"), failsNow.provenance().notes());

        toy.settings.put("max_return", "90.00");
        Resolution argsChanged = bind(plan, approve).bindings().resolutions().get("item");
        assertEquals(3, llm.calls.get(), "an upstream value moved, so did the arguments: resolved afresh");
        assertEquals(List.of("not reused from the bindings file: the slot's arguments changed"),
                argsChanged.provenance().notes());

        bind(plan, approve);
        assertEquals(3, llm.calls.get(), "unchanged again: reused");
        bind(plan, approve.withReuseBindings(false));
        assertEquals(4, llm.calls.get(), "reuse off: every slot afresh");

        Plan changed = plan(WITH_MAX, Map.of("item", List.of("sql", "llm-sql")));
        Resolution planChanged = bind(changed, approve).bindings().resolutions().get("item");
        assertEquals("toy.sql.Item", planChanged.provenance().resolverId());
        assertEquals(List.of("not reused from the bindings file: the scenario's resolve section changed since it"
                + " was written"), planChanged.provenance().notes());
    }

    @Test
    void aReusedValueKeepsItsCredentialOnlyWhileTheCredentialsHaveTheEntry() throws Exception {
        FakeContributor.FakeStaff staff = new FakeContributor.FakeStaff();
        FakeContributor.offering(staff);
        toy.staff.add(List.of("300", "10.00"));
        staff.codes = List.of("200", "300");
        Plan plan = plan("""
                scenario: toy-llm
                pack: toy
                resolve:
                  assistant: { query: Staff, args: { limitBelow: "60.00" }, credential: { realm: staff, key: code } }
                steps: []
                """, Map.of("assistant", List.of("llm-sql")));
        BindOptions approve = options(BindMode.APPROVE).withApprover(Approver.APPROVE_ALL);
        BindOutcome first = bind(plan, approve);
        assertEquals("staff/200", first.bindings().credential("assistant").toString());
        assertEquals(TestCredentials.PW_200, ((Map<?, ?>) first.bindings().values().get("assistant")).get("password"),
                "the join, as for any slot");
        assertEquals(1, staff.calls.get());

        BindOutcome reused = bind(plan, approve);
        assertEquals(1, staff.calls.get(), "reused: the entry is still there");
        assertEquals("staff/200", reused.bindings().credential("assistant").toString());
        assertEquals(TestCredentials.PW_200, ((Map<?, ?>) reused.bindings().values().get("assistant")).get("password"));
        assertNull(reused.written());

        Map<String, Object> others = new LinkedHashMap<>();
        others.put("100", Map.of("password", TestCredentials.PW_100));
        others.put("300", Map.of("password", TestCredentials.PW_300));
        BindOutcome without = bind(plan, approve.withCredentials(TestCredentials.of(Map.of("version", 1L,
                "credentials", Map.of("staff", others)))));
        assertEquals(2, staff.calls.get(), "the entry is gone: resolved afresh");
        Resolution fresh = without.bindings().resolutions().get("assistant");
        assertEquals("300", ((Map<?, ?>) fresh.value()).get("code"));
        assertEquals(List.of("not reused from the bindings file: the credentials have no entry staff/200"),
                fresh.provenance().notes());
        assertEquals("no credential staff/200", fresh.provenance().rejected().get(0).reason());
    }

    @Test
    void reuseIsApproveOnly() throws Exception {
        FakeContributor.offering(llm);
        llm.codes = List.of("C3");
        Plan plan = plan(ONE, Map.of("item", List.of("llm-sql")));
        bind(plan, options(BindMode.APPROVE).withApprover(Approver.APPROVE_ALL));
        bind(plan, options(BindMode.LIVE));
        assertEquals(2, llm.calls.get(), "live never reuses: it resolves now");
    }

    // ------------------------------------------------------------------------------------------------ usage, budget

    @Test
    void usageIsFilledFromTheLedgerEntriesOfTheSlotsResolverCalls() throws Exception {
        FakeContributor.offering(llm);
        llm.codes = List.of("C3");
        llm.entries = List.of(
                new CostLedger.Entry("toy-llm", "item", Strategy.LLM_SQL, "recorded", "model-a", "req-1", 1200, 80,
                        "0.003600", "resolve", 1000, 0, false, null),
                new CostLedger.Entry("toy-llm", null, Strategy.LLM_SQL, "recorded", "model-b", "req-2", 300, 20,
                        "0.0012"),
                new CostLedger.Entry("toy-llm", "elsewhere", Strategy.LLM_SQL, "recorded", "model-c", "req-3", 5, 5,
                        "9"),
                new CostLedger.Entry("another-scenario", "item", Strategy.LLM_SQL, "recorded", "model-d", "req-4", 5,
                        5, "7"));
        CostLedger ledger = new CostLedger();
        BindOutcome outcome = bind(plan(WITH_MAX, Map.of("item", List.of("llm-sql"))), options(BindMode.LIVE)
                .withLedger(ledger).withWriteBindings(true));
        Resolution item = outcome.bindings().resolutions().get("item");
        assertEquals(new LlmUsage("model-a", "req-1", 1500, 100, "0.004800"), item.usage(), "summed over the slot's"
                + " entries; another slot's or scenario's are left out");
        assertNull(outcome.bindings().resolutions().get("max").usage(), "tier (a): none");
        assertEquals(4, ledger.size());
        Map<?, ?> llmJson = (Map<?, ?>) slotJson(Json.parseObject(Files.readString(file())), "item").get("llm");
        assertEquals(Map.of("model", "model-a", "requestId", "req-1", "inputTokens", 1500L, "outputTokens", 100L,
                "costUsd", "0.004800"), llmJson, "written as llm");
        Map<String, Object> record = resolveRecord("item");
        assertEquals(0.9, record.get("confidence"));
        assertEquals("REQUIRED", record.get("approval"));
        assertEquals("model-a", ((Map<?, ?>) record.get("llm")).get("model"));
        assertNull(resolveRecord("max").get("llm"), "a tier (a) record keeps its Phase 2 shape");

        llm.entries = List.of();
        assertNull(bind(plan(ONE, Map.of("item", List.of("llm-sql"))), options(BindMode.LIVE)).bindings()
                .resolutions().get("item").usage(), "no entry, no usage");
        assertEquals(new LlmUsage("m", "r", 3, 4, null), ResolutionEngine.usage(List.of(new CostLedger.Entry("s",
                null, Strategy.LLM_SQL, "p", "m", "r", 1, 2, null), new CostLedger.Entry("s", null, Strategy.LLM_SQL,
                "p", "m2", "r2", 2, 2, "n/a"))), "a cost no entry states as a decimal is null");
    }

    @Test
    void aBudgetRefusalIsResolutionFailedBudgetWithTheMetersFigures() throws Exception {
        FakeContributor.offering(llm);
        llm.failure = ResolutionException.budget("SCENARIO", "toy-llm", "0.50", "0.49", "0.02");
        Plan plan = plan(ONE, Map.of("item", List.of("llm-sql")));
        BindException budget = fails(plan, options(BindMode.LIVE));
        assertEquals(FailureKind.RESOLUTION_FAILED, budget.kind());
        assertEquals("BUDGET", budget.reason());
        assertEquals("item", budget.detail().get("slot"));
        assertEquals("SCENARIO", budget.detail().get("scope"));
        assertEquals("toy-llm", budget.detail().get("id"));
        assertEquals("0.50", budget.detail().get("limitUsd"));
        assertEquals("0.49", budget.detail().get("spentUsd"));
        assertEquals("0.02", budget.detail().get("estimateUsd"));
        assertEquals("the scenario budget of $0.50 has $0.49 spent and no room for a model call estimated at $0.02",
                budget.detail().get("message"));

        llm.failure = new ResolutionException("BUDGET", null, "no room", Map.of("reason", "MINE", "slot", "x",
                "message", "y", "scope", "JOB"));
        BindException kept = fails(plan, options(BindMode.LIVE));
        assertEquals("BUDGET", kept.detail().get("reason"), "a resolver's detail cannot replace the binder's keys");
        assertEquals("item", kept.detail().get("slot"));
        assertEquals("no room", kept.detail().get("message"));
        assertEquals("JOB", kept.detail().get("scope"));
    }

    // ------------------------------------------------------------------------------------------------ the executor

    @Test
    void theExecutorReportsBudgetAndWritesTheLedgersNewFieldsAfterTheOldOnes() throws Exception {
        agent.capabilities("toy.item");
        FakeContributor.offering(llm);
        llm.codes = List.of("C3");
        llm.entries = List.of(new CostLedger.Entry("toy-llm", "item", Strategy.LLM_SQL, "recorded", "model-a",
                "req-1", 1200, 80, "0.003600", "resolve", 1000, 24, true, "fallback model-b"));
        Plan plan = plan(ONE, Map.of("item", List.of("llm-sql")));
        RunOptions run = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper()).withOutputDir(
                root.resolve("out")).withBind(options(BindMode.LIVE));
        RunResult passed = new PlanExecutor(pack, agent, run).run(plan);
        assertEquals(Status.PASS, passed.status(), passed.message());
        assertEquals(1, passed.modelCalls().size());
        List<String> lines = Files.readAllLines(root.resolve("out/cost-ledger.jsonl"));
        assertEquals(1, lines.size());
        Map<String, Object> line = Json.parseObject(lines.get(0));
        assertEquals(List.of("scenarioId", "slot", "strategy", "provider", "model", "requestId", "inputTokens",
                "outputTokens", "costUsd", "purpose", "cacheReadTokens", "cacheWriteTokens", "batch", "note"),
                List.copyOf(line.keySet()), "the Phase 2 fields, then Phase 5's");
        assertEquals(1000L, line.get("cacheReadTokens"));
        assertEquals(24L, line.get("cacheWriteTokens"));
        assertEquals(true, line.get("batch"));
        assertEquals("fallback model-b", line.get("note"));
        assertEquals("resolve", line.get("purpose"));

        llm.entries = List.of();
        llm.failure = ResolutionException.budget("SCENARIO", "toy-llm", "0.50", "0.50", "0.01");
        RunResult budget = new PlanExecutor(pack, agent, run).run(plan);
        assertEquals(Status.ERROR, budget.status());
        assertEquals(FailureKind.RESOLUTION_FAILED, budget.failureKind());
        assertEquals("BUDGET", budget.detail().get("reason"));
        assertEquals("SCENARIO", budget.detail().get("scope"));
        assertEquals("0.50", budget.detail().get("limitUsd"));
        assertEquals("0.01", budget.detail().get("estimateUsd"));

        RunResult reResolve = new PlanExecutor(pack, agent, run.withBind(options(BindMode.RE_RESOLVE))).run(plan);
        assertEquals(Status.ERROR, reResolve.status(), "a run never re-resolves");
        assertEquals(FailureKind.ENVIRONMENT, reResolve.failureKind());
        assertTrue(reResolve.message().startsWith("re-resolve is a command: skillroy re-resolve"),
                reResolve.message());
    }

    /**
     * phase5-contracts.md 7.4 and decision D91: the run writes {@code inference.jsonl} beside {@code cost-ledger.jsonl}
     * from the run's ledger entries that carry details, one line each: {@code type: inference}, the details in their
     * order, then the entry's fields the details lack; masked by the run's secrets; no file when no entry of the run
     * has details (an old one is removed, as every output of an earlier run).
     */
    @Test
    void theRunWritesInferenceJsonlFromTheLedgerEntriesThatCarryDetails() throws Exception {
        agent.capabilities("toy.item");
        FakeContributor.offering(llm);
        llm.codes = List.of("C3");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", "inference");
        details.put("t", "2026-10-08T10:00:00Z");
        details.put("purpose", "resolve");
        details.put("scenarioId", "toy-llm");
        details.put("slot", "item");
        details.put("provider", "recorded");
        details.put("requestedModel", "model-a");
        details.put("model", "model-a");
        details.put("requestId", "req-1");
        details.put("fingerprint", "sha256:f1");
        details.put("stop", "END_TURN");
        details.put("costUsd", "0");
        details.put("structured", Map.of("rationale", "the staff code " + TestCredentials.PW_100 + " is not used"));
        CostLedger.Entry withDetails = new CostLedger.Entry("toy-llm", "item", Strategy.LLM_SQL, "recorded",
                "model-a", "req-1", 120, 8, "0", "resolve", 0, 0, false, null, details);
        CostLedger.Entry plain = new CostLedger.Entry("toy-llm", "item", Strategy.LLM_SQL, "recorded", "model-a",
                "req-2", 10, 2, "0");
        llm.entries = List.of(withDetails, plain);
        Plan plan = plan(ONE, Map.of("item", List.of("llm-sql")));
        Path out = root.resolve("out");
        RunOptions run = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper()).withOutputDir(out)
                .withBind(options(BindMode.LIVE));
        RunResult passed = new PlanExecutor(pack, agent, run).run(plan);
        assertEquals(Status.PASS, passed.status(), passed.message());
        assertEquals(2, passed.modelCalls().size());
        assertEquals(2, Files.readAllLines(out.resolve("cost-ledger.jsonl")).size(), "every entry in the ledger");
        List<String> lines = Files.readAllLines(out.resolve(PlanExecutor.INFERENCE_FILE));
        assertEquals(1, lines.size(), "one record per entry with details: " + lines);
        Map<String, Object> record = Json.parseObject(lines.get(0));
        List<String> keys = new ArrayList<>(details.keySet());
        keys.addAll(List.of("strategy", "inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens",
                "batch"));
        assertEquals(keys, List.copyOf(record.keySet()), "the details in their order, then the entry's other"
                + " non-null fields");
        assertEquals("LLM_SQL", record.get("strategy"));
        assertEquals(120L, record.get("inputTokens"));
        assertEquals("sha256:f1", record.get("fingerprint"));
        assertFalse(lines.get(0).contains(TestCredentials.PW_100), "masked by the run's secrets: " + lines.get(0));
        assertTrue(lines.get(0).contains("<redacted>"), lines.get(0));
        Map<String, Object> direct = PlanExecutor.inferenceRecord(new CostLedger.Entry("s", null, null, "p", "m",
                "r", 1, 1, "0.01", "triage", 0, 0, false, "a note", Map.of("purpose", "triage")));
        assertEquals(List.of("type", "purpose", "scenarioId", "provider", "model", "requestId", "inputTokens",
                "outputTokens", "costUsd", "cacheReadTokens", "cacheWriteTokens", "batch", "note"),
                List.copyOf(direct.keySet()), "type first even when the details lack it; null fields left out");

        llm.entries = List.of(plain);
        RunResult again = new PlanExecutor(pack, agent, run).run(plan);
        assertEquals(Status.PASS, again.status(), again.message());
        assertEquals(1, Files.readAllLines(out.resolve("cost-ledger.jsonl")).size());
        assertFalse(Files.exists(out.resolve(PlanExecutor.INFERENCE_FILE)), "no entry with details: no file, and the"
                + " earlier run's is gone");
    }

    // ------------------------------------------------------------------------------------------------ re-resolve

    @Test
    void aReResolveBindParksAtEachBatchableSlotAndResumesWithTheBatchesAnswers() throws Exception {
        FakeContributor.offering(batch);
        batch.entries = List.of(new CostLedger.Entry("toy-llm", null, Strategy.LLM_SQL, "scripted", "model-x",
                "batch-1", 900, 60, "0.000900", "resolve", 800, 0, true, null));
        toy.inactive.add("A1");
        Plan plan = plan(TWO, Map.of("item", List.of("llm-sql"), "dearer", List.of("llm-sql")));
        BindOptions reResolve = options(BindMode.RE_RESOLVE).withWriteBindings(true).withApprovedBy("re-resolve:job-7");

        BindStep.Parked atItem = assertInstanceOf(BindStep.Parked.class, stepwise(plan, reResolve));
        assertEquals("toy-llm", atItem.scenarioId());
        assertEquals("item", atItem.slot());
        assertEquals("Item", atItem.kind());
        assertEquals("inference.fake-batch", atItem.resolverId());
        assertEquals(Map.of("slot", "item", "mode", "re-resolve", "lowerBound", "2.00"), atItem.request());
        assertEquals(0, batch.candidateCalls.get(), "in re-resolve the model call is deferred, never made inline");
        assertEquals(0, toy.verifierCalls.get());
        assertEquals(0, FakeContributor.CLOSED.get(), "the parked bind holds its engine and contributors");

        clock.advance(Duration.ofHours(1));
        BindStep.Parked atDearer = assertInstanceOf(BindStep.Parked.class, atItem.resume(List.of("B2")));
        assertFalse(atItem.isOpen());
        assertThrows(IllegalStateException.class, () -> atItem.resume(List.of("B2")));
        assertEquals(1, toy.verifierCalls.get(), "the first slot's value was verified on resumption");
        assertEquals("dearer", atDearer.slot());
        assertEquals("5.00", ((Map<?, ?>) atDearer.request()).get("lowerBound"), "parked with the upstream value");

        clock.advance(Duration.ofHours(1));
        BindStep.Done done = assertInstanceOf(BindStep.Done.class, atDearer.resume(List.of("A1", "C3")));
        BindOutcome outcome = done.outcome();
        Map<String, Resolution> resolutions = outcome.bindings().resolutions();
        assertEquals(Map.of("code", "B2", "deposit", "5.00"), resolutions.get("item").value());
        assertEquals(Map.of("code", "C3", "deposit", "9.99"), resolutions.get("dearer").value());
        assertEquals(List.of(new Provenance.Rejected("inference.fake-batch", "item A1", "toy.item.check: item A1 is"
                + " not active")), resolutions.get("dearer").provenance().rejected(), "the verifiers gate the answer");
        assertEquals(new Approval(Approval.State.APPROVED, "re-resolve:job-7"), resolutions.get("item").approval());
        assertEquals(new LlmUsage("model-x", "batch-1", 900, 60, "0.000900"), resolutions.get("item").usage(),
                "from the entry recorded while completing");
        assertEquals(List.of(ToyResolution.ITEM_SQL), resolutions.get("item").provenance().statements().stream()
                .map(Provenance.Statement::sql).toList());
        assertEquals(file(), outcome.written());
        BindingsFile written = BindingsFile.read(file());
        assertEquals(new Approval(Approval.State.APPROVED, "re-resolve:job-7"), written.resolutions().get("dearer")
                .approval(), "written as approve writes it, approved by the job");
        assertEquals(1, FakeContributor.CLOSED.get(), "the engine closed at the end");
        assertEquals(List.of("prepare", "complete", "prepare", "complete"), batch.seen.stream()
                .map(FakeContributor.Seen::call).toList());
        assertEquals(List.of("item", "item", "dearer", "dearer"), batch.seen.stream().map(FakeContributor.Seen::slot)
                .toList());
        assertEquals(List.of("A1", "C3"), batch.seen.get(3).answer());
        List<Map<String, Object>> resolverRecords = records("resolver");
        assertEquals(true, resolverRecords.get(0).get("parked"));
        assertEquals(true, resolverRecords.get(1).get("resumed"));
        assertEquals(1, resolverRecords.get(1).get("candidates"));
        assertEquals(2, resolverRecords.get(3).get("candidates"));

        assertEquals("REPLAY", bind(plan, options(BindMode.REPLAY)).bindings().strategy("dearer"),
                "an approved file replays");
    }

    @Test
    void outsideReResolveABatchableResolverIsAskedForItsCandidates() throws Exception {
        FakeContributor.offering(batch);
        batch.codes = List.of("C3");
        Plan plan = plan(ONE, Map.of("item", List.of("llm-sql")));
        BindOutcome live = assertInstanceOf(BindStep.Done.class, stepwise(plan, options(BindMode.LIVE))).outcome();
        assertEquals(Map.of("code", "C3", "deposit", "9.99"), live.bindings().resolutions().get("item").value());
        assertEquals(1, batch.candidateCalls.get());
        bind(plan, options(BindMode.LIVE));
        assertEquals(2, batch.candidateCalls.get(), "and in a one-shot bind");
        assertInstanceOf(BindStep.Done.class, stepwise(plan, options(BindMode.APPROVE)
                .withApprover(Approver.APPROVE_ALL)));
        assertInstanceOf(BindStep.Done.class, stepwise(plan, options(BindMode.REPLAY)));
        assertTrue(batch.seen.stream().noneMatch(s -> s.call().equals("prepare")), "nothing parks outside re-resolve");
    }

    @Test
    void aOneShotBindRefusesReResolve() throws Exception {
        BindException e = fails(plan(ONE, Map.of()), options(BindMode.RE_RESOLVE));
        assertEquals(FailureKind.ENVIRONMENT, e.kind());
        assertTrue(e.getMessage().startsWith("re-resolve is a command: skillroy re-resolve"), e.getMessage());
        assertEquals("re-resolve", e.detail().get("mode"));
        assertTrue(agent.calls.isEmpty(), "nothing was sent to the target");
        BindException root = assertThrows(BindException.class, () -> stepwise(plan(ONE, Map.of()),
                options(BindMode.RE_RESOLVE).withWriteBindings(true).withBindingsRoot(null)));
        assertTrue(root.getMessage().startsWith("re-resolve with writeBindings needs a bindings root"),
                root.getMessage());
    }

    @Test
    void aParkedBindThatIsClosedOrFailsReleasesItsEngine() throws Exception {
        FakeContributor.offering(batch);
        Plan plan = plan(TWO, Map.of("item", List.of("llm-sql"), "dearer", List.of("llm-sql")));
        BindOptions reResolve = options(BindMode.RE_RESOLVE);
        BindStep.Parked parked = assertInstanceOf(BindStep.Parked.class, stepwise(plan, reResolve));
        assertTrue(parked.isOpen());
        parked.close();
        assertEquals(1, FakeContributor.CLOSED.get(), "closing abandons the bind and releases its engine");
        parked.close();
        assertEquals(1, FakeContributor.CLOSED.get(), "idempotent");
        assertThrows(IllegalStateException.class, () -> parked.resume(List.of("B2")));
        assertTrue(parked.toString().contains("closed"), parked.toString());

        BindStep.Parked again = assertInstanceOf(BindStep.Parked.class, stepwise(plan, reResolve));
        BindStep.Parked atDearer = assertInstanceOf(BindStep.Parked.class, again.resume(List.of("B2")));
        toy.inactive.add("C3");
        BindException rejected = assertThrows(BindException.class, () -> atDearer.resume(List.of("C3")));
        assertEquals("ALL_REJECTED", rejected.reason());
        assertEquals("dearer", rejected.slot());
        assertEquals(List.of("item"), ((List<?>) rejected.detail().get("fixedUpstream")).stream()
                .map(u -> ((Map<?, ?>) u).get("slot")).toList(), "the slots it depended on (D5)");
        assertEquals(2, FakeContributor.CLOSED.get(), "a bind that fails on resumption is over");
        assertFalse(Files.exists(file()), "nothing was written");

        batch.prepareFailure = ResolutionException.budget("JOB", "job-7", "1.00", "1.00", "0.01");
        BindException budget = assertThrows(BindException.class, () -> stepwise(plan, reResolve));
        assertEquals("BUDGET", budget.reason());
        assertEquals("JOB", budget.detail().get("scope"));
        assertEquals(3, FakeContributor.CLOSED.get());

        batch.prepareFailure = null;
        batch.prepareNothing = true;
        BindException nothing = assertThrows(BindException.class, () -> stepwise(plan, reResolve));
        assertEquals(FailureKind.ENVIRONMENT, nothing.kind());
        assertTrue(nothing.getMessage().endsWith("resolver inference.fake-batch prepared no request"),
                nothing.getMessage());
        assertEquals(4, FakeContributor.CLOSED.get());
    }

    @Test
    void aParkedSlotsAnswerIsReadThroughTheJdbcChannelOnTheEmbeddedDatabase() throws Exception {
        String url = "jdbc:hsqldb:mem:stepwise-" + System.nanoTime();
        try (java.sql.Connection db = java.sql.DriverManager.getConnection(url, "SA", "");
             java.sql.Statement st = db.createStatement()) {
            st.execute("CREATE TABLE item (code VARCHAR(10) PRIMARY KEY, deposit DECIMAL(10,2))");
            st.execute("INSERT INTO item VALUES ('A1', 1.00), ('B2', 5.00), ('C3', 9.99)");
            Credentials jdbc = TestCredentials.of(Map.of("version", 1L, "jdbc", Map.of(ToyResolution.SOURCE,
                    Map.of("url", url, "user", "SA"))));
            FakeContributor.offering(batch);
            toy.inactive.addAll(List.of("B2", "C3"));
            Plan plan = plan(ONE, Map.of("item", List.of("sql", "llm-sql")));
            BindStep.Parked parked = assertInstanceOf(BindStep.Parked.class, stepwise(plan,
                    options(BindMode.RE_RESOLVE).withCredentials(jdbc)));
            assertEquals(1, toy.itemResolverCalls.get(), "the committed SQL read the database first, inline");
            assertEquals(1, sessions(st), "while parked the bind holds no connection: only this test's own is open");
            String modelSql = "SELECT code, deposit FROM item WHERE deposit < 2 ORDER BY deposit, code";
            BindOutcome outcome = assertInstanceOf(BindStep.Done.class, parked.resume(Map.of("sql", modelSql,
                    "codes", List.of("A1")))).outcome();
            Resolution item = outcome.bindings().resolutions().get("item");
            assertEquals(Map.of("code", "A1", "deposit", "1.00"), item.value(), "read through a channel opened again");
            assertEquals("inference.fake-batch", item.provenance().resolverId());
            Provenance.Statement statement = item.provenance().statements().get(0);
            assertEquals("jdbc", statement.channel());
            assertEquals(modelSql, statement.sql());
            assertEquals(1, statement.rowCount());
            assertEquals(1, sessions(st), "and the engine's channels closed at the end");
            assertNull(outcome.written(), "without writeBindings re-resolve is a dry run");
            assertNotNull(outcome.file(), "the file it would write");
            assertEquals(new Approval(Approval.State.APPROVED, "re-resolve"), outcome.file().resolutions().get("item")
                    .approval(), "approved by the mode's name when the job gives no label");
            assertFalse(Files.exists(file()));
            assertTrue(agent.callsTo("data.query").isEmpty(), "the JDBC source is never read through the agent");
        }
    }
}
