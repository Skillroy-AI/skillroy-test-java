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
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.PayloadMatch;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.protocol.Topics;
import ai.skillroy.testjava.samples.lending.AccessControl;
import ai.skillroy.testjava.samples.lending.AccessDecision;
import ai.skillroy.testjava.samples.lending.Catalogue;
import ai.skillroy.testjava.samples.lending.Deposits;
import ai.skillroy.testjava.samples.lending.Engine;
import ai.skillroy.testjava.samples.lending.EngineListener;
import ai.skillroy.testjava.samples.lending.EngineStatus;
import ai.skillroy.testjava.samples.lending.Item;
import ai.skillroy.testjava.samples.lending.LendingDb;
import ai.skillroy.testjava.samples.lending.LendingInput;
import ai.skillroy.testjava.samples.lending.LendingState;
import ai.skillroy.testjava.samples.lending.Loan;
import ai.skillroy.testjava.samples.lending.LoanAuthorisation;
import ai.skillroy.testjava.samples.lending.LoanDeposit;
import ai.skillroy.testjava.samples.lending.LoanLine;
import ai.skillroy.testjava.samples.lending.LoanWaiver;
import ai.skillroy.testjava.samples.lending.Prompt;
import ai.skillroy.testjava.samples.lending.PromptService;
import ai.skillroy.testjava.samples.lending.Seed;
import ai.skillroy.testjava.samples.lending.Slip;
import ai.skillroy.testjava.samples.lending.SlipPrinter;
import ai.skillroy.testjava.samples.lending.Staff;
import ai.skillroy.testjava.samples.lending.WaiverEngine;
import ai.skillroy.testjava.samples.lending.agent.LendingWaivers;
import ai.skillroy.testjava.samples.lending.pack.LendingPack;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.JdbcSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The sample desk in this JVM, headless and without an agent: the application's own engine, catalogue, access rules,
 * waiver rules, prompt service and slip printer on a seeded in-memory database, behind an {@link AgentConnection}
 * that answers what a session's verbs, facts, verifiers and the executor's bookkeeping ask, in the answer shapes of the
 * lending module's primitives (the agent module's {@code LendingPrimitives}, {@code LendingJson} and
 * {@code EngineEvents}): {@code lending.input} (posted to the engine's inbox and acknowledged when the engine takes
 * it), {@code lending.state}, {@code lending.loan}, {@code lending.prompt}, {@code lending.prompt.answer},
 * {@code lending.slip}, {@code lending.access.check}, {@code lending.deposit}, {@code lending.limits},
 * {@code lending.waiver.evaluate}, {@code app.state}, {@code swing.find} (the pack's key actions), {@code trace.mark},
 * {@code evt.query} and {@code sync.await} (as the agent's core primitive answers it, for a plan's {@code waitFor});
 * the engine's callbacks become the events the pack's sync policy waits on
 * ({@code input.acked}, {@code input.requested}, {@code prompt.waiting}, {@code state.changed},
 * {@code loan.posted}, {@code slip.printed}). Every other method is {@code METHOD_NOT_FOUND} ({@code swing.snapshot}
 * among them: the snapshot text of a session is then the application state alone).
 *
 * <p>What makes it the native stand-in for the live desk of the session acceptance (phase5-contracts.md 14.4): its
 * clock is fixed at {@link #NOW} (the business date, every posting time and every event time derive from it), a
 * call's answer carries no events (those of the real agent depend on the engine thread's timing), so that a session
 * driven through it sends a model exactly the same requests on every run. The database is the controller-side JDBC
 * channel's ({@link #credentials}), as in the other headless tests.
 *
 * <p>Thread-safe: the engine runs on its own thread; the bus and the waits are synchronised on this object.
 */
final class HeadlessDesk implements AgentConnection {

    /** The desk's clock: every session through it starts on the same day at the same time. */
    static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    /** The business date {@link #NOW} gives. */
    static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    /** The read-only account's password: test-local, the database has no listener. */
    private static final String READER_PASSWORD = "Headless-Desk-Reader-6243";
    private static final AtomicInteger DATABASES = new AtomicInteger();
    /** Numbers the desks' agent sessions (phase8-contracts.md 9.3.2): one per launch, as one per agent start. */
    private static final AtomicInteger SESSIONS = new AtomicInteger();
    private static final long DEFAULT_ACK_MS = 5000L;
    private static final List<String> MUTATING = List.of("lending.input", "lending.prompt.answer");
    private static final List<String> PRIMITIVES = List.of("lending.access.check", "lending.deposit",
            "lending.input", "lending.limits", "lending.loan", "lending.prompt", "lending.prompt.answer",
            "lending.slip", "lending.state", "lending.waiver.evaluate");

    private final Env env;
    private final LendingDb db;
    private final Engine engine;
    /**
     * Phase 6 (phase6-contracts.md 13.1 item 7): a scripted hooks module with the pack's catalogue installed, which
     * reports {@code hook.limit.applied} for every return line the engine commits; off by default.
     */
    private final boolean hooks;
    /** Whether the matrix declares {@code swing.tree}, which a plan with an {@code ActionKey} slot requires. */
    private final boolean swingTree;
    /** The returned lines of the current loan already reported (the engine thread only). */
    private int returnedLinesSeen;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final List<Event> bus = new ArrayList<>();
    private final Map<LendingInput, CountDownLatch> acks = new IdentityHashMap<>();
    private long seq;
    /** The primitives asked, in order (the executor's {@code evt.query} and {@code trace.mark} included). */
    final List<String> calls = new ArrayList<>();
    /** This launch's agent session (phase8-contracts.md 9.3.2): a new desk is a new agent start. */
    private final String session = "headless-desk-" + SESSIONS.incrementAndGet();

    private HeadlessDesk(Env env, LendingDb db, Engine engine, boolean hooks, boolean swingTree) {
        this.env = env;
        this.db = db;
        this.engine = engine;
        this.hooks = hooks;
        this.swingTree = swingTree;
    }

    /** A seeded desk, its engine waiting for a staff code. */
    static HeadlessDesk launch(Env env) throws Exception {
        return launch(env, false, false);
    }

    /**
     * A seeded desk, with the scripted hooks module when {@code hooks} (phase6-contracts.md 13.1 item 7):
     * {@code hook.engine} and {@code hook.limit.applied} {@code OK} in its matrix, {@code hook.health} naming the pack's
     * catalogue as installed, and one {@code hook.limit.applied} event for every return line the engine commits, with
     * the signed-on staff code and the limit the desk's access rules give, published before the engine asks for the
     * next input (as the real advice fires inside the engine's return path). With {@code swingTree} the matrix also
     * declares {@code swing.tree}, which a plan with an {@code ActionKey} slot requires (its verifier asks
     * {@code swing.find}, which this desk answers for the pack's key actions); the session recordings' desk declares
     * neither.
     */
    static HeadlessDesk launch(Env env, boolean hooks, boolean swingTree) throws Exception {
        LendingDb db = LendingDb.create("headless" + env.seed + DATABASES.incrementAndGet(), Seed.parse(env.seed),
                TODAY);
        try {
            db.createReadOnlyUser(READER_PASSWORD);
            AccessControl access = AccessControl.load(db.connection());
            Catalogue catalogue = Catalogue.load(db.connection());
            WaiverEngine waivers = WaiverEngine.load(db.connection());
            if (!db.connection().getAutoCommit()) {
                db.connection().commit();
            }
            SlipPrinter printer = new SlipPrinter(db.settings().get("slip_header"));
            Engine engine = new Engine(db, catalogue, access, waivers, new PromptService(), printer,
                    Clock.fixed(NOW, ZoneOffset.UTC), 0);
            HeadlessDesk desk = new HeadlessDesk(env, db, engine, hooks, swingTree);
            engine.addListener(desk.new Callbacks());
            engine.start();
            return desk;
        } catch (Exception | Error e) {
            db.close();
            throw e;
        }
    }

    /** The environment's staff credentials and the JDBC source {@code lending} pointing at this desk's database. */
    Credentials credentials(Path dir) throws Exception {
        return SampleCredentials.load(dir, env, new JdbcSource(db.url(), LendingDb.READER_USER, READER_PASSWORD));
    }

    /** The JDBC password of {@link #credentials}, which no output may hold. */
    static String readerPassword() {
        return READER_PASSWORD;
    }

    Env env() {
        return env;
    }

    /** The engine's current status. */
    EngineStatus status() {
        return engine.status();
    }

    // ------------------------------------------------------------------------------------------------ the bus

    private synchronized long publish(String topic, Map<String, Object> payload) {
        seq++;
        bus.add(new Event(seq, NOW.toEpochMilli() + seq, topic, "lending", null, null, payload));
        notifyAll();
        return seq;
    }

    private synchronized long seq() {
        return seq;
    }

    @Override
    public synchronized Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout)
            throws AgentRpcException {
        TopicFilter filter;
        try {
            filter = TopicFilter.parse(topics);
        } catch (IllegalArgumentException e) {
            throw new AgentRpcException("sync.await", new RpcError(ErrorCodes.INVALID_PARAMS, e.getMessage(), null));
        }
        long deadline = System.nanoTime() + Math.max(0L, timeout == null ? 10_000L : timeout.toMillis()) * 1_000_000L;
        int seen = 0;
        while (true) {
            for (int i = seen; i < bus.size(); i++) {
                Event event = bus.get(i);
                if (event.seq() > sinceSeq && filter.matches(event.topic()) && matches(event, match)) {
                    return event;
                }
            }
            seen = bus.size();
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (left <= 0) {
                return null;
            }
            try {
                wait(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /** The agent's payload rule, {@code PayloadMatch} (phase8-contracts.md 8.9). */
    private static boolean matches(Event event, Map<String, Object> match) {
        return PayloadMatch.matches(event.payload(), match);
    }

    private synchronized Map<String, Object> query(Map<String, Object> p) {
        long since = p.get("since") instanceof Number n ? n.longValue() : 0L;
        int limit = p.get("limit") instanceof Number n ? n.intValue() : 200;
        TopicFilter filter = TopicFilter.parse(p.get("topics") == null ? "*" : String.valueOf(p.get("topics")));
        List<Object> out = new ArrayList<>();
        for (Event event : bus) {
            if (event.seq() > since && filter.matches(event.topic()) && out.size() < limit) {
                out.add(event.toJson());
            }
        }
        return map("events", out, "lastSeq", seq, "oldestSeq", bus.isEmpty() ? 0L : bus.get(0).seq());
    }

    /** The engine's callbacks as the lending module's events; each publishes and returns at once. */
    private final class Callbacks implements EngineListener {

        @Override
        public void stateChanged(LendingState from, LendingState to) {
            publish(Topics.STATE_CHANGED, map("from", name(from), "to", name(to)));
        }

        @Override
        public void inputRequested(LendingState state, String prompt) {
            limitApplied();
            publish(Topics.INPUT_REQUESTED, map("state", name(state), "prompt", prompt, "validInputs",
                    new ArrayList<>(engine.status().validInputs())));
        }

        @Override
        public void inputAccepted(LendingInput input) {
            CountDownLatch latch;
            synchronized (acks) {
                latch = acks.remove(input);
            }
            if (latch != null) {
                latch.countDown();
            }
        }

        @Override
        public void promptShown(String id, String text, List<String> buttons) {
            publish(Topics.PROMPT_WAITING, map("id", id, "text", text, "buttons", buttons == null ? List.of()
                    : new ArrayList<>(buttons)));
        }

        @Override
        public void promptAnswered(String id, String choice) {
            publish("prompt.answered", map("id", id, "choice", choice));
        }

        @Override
        public void loanPosted(long loanId) {
            Loan last = engine.lastLoan();
            String total = last != null && last.id() != null && last.id() == loanId ? Deposits.format(last.total())
                    : null;
            publish("loan.posted", map("loanId", loanId, "total", total));
        }

        @Override
        public void slipPrinted(long loanId, List<String> lines) {
            publish("slip.printed", map("loanId", loanId));
        }
    }

    /** The scripted {@code hook.limit.applied}: one event per return line the engine committed since the last. */
    private void limitApplied() {
        Loan loan = engine.currentLoan();
        int returned = 0;
        if (loan != null) {
            for (LoanLine line : loan.lines()) {
                if (line.returned()) {
                    returned++;
                }
            }
        }
        if (hooks && returned > returnedLinesSeen && engine.currentStaff() != null) {
            String staff = engine.currentStaff().code();
            publish("hook.limit.applied", map("hook", "limit.applied", "class", AccessControl.class.getName(),
                    "method", "returnLimit", "descriptor", "(Ljava/lang/String;)Ljava/math/BigDecimal;", "thread",
                    Engine.THREAD_NAME, "durationNs", 1L, "stubbed", false, "args", map("0", staff), "returned",
                    money(engine.access().limit(staff, AccessControl.LIMIT_RETURN))));
        }
        returnedLinesSeen = returned;
    }

    /**
     * {@code hook.health} of the scripted module: the pack's catalogue installed, {@code limit.applied} only, and the
     * pack's deny held (phase8-contracts.md 6.6, 12.3: the echo the installer requires on the {@code alreadyInstalled}
     * path), no class reported.
     */
    private synchronized Map<String, Object> hookHealth() {
        long fired = bus.stream().filter(e -> e.topic().equals("hook.limit.applied")).count();
        Map<String, Object> limit = map("state", fired == 0 ? "INSTALLED" : "FIRING", "count", fired, "armed", false);
        HookCatalogueRef catalogue = HookCatalogueRef.of();
        return map("hooks", map("limit.applied", limit), "stubs", List.of(), "spy", map("version", 1L),
                "catalogue", map("id", catalogue.id(), "hash", catalogue.hash(), "installedAt", NOW.toEpochMilli()),
                "classes", List.of(), "deny", map("catalogue", Runs.PACK.hooks().deny(), "from", List.of("lending"),
                        "option", List.of(), "modules", 0L));
    }

    /** {@code hook.events}: the bus's events of the hook's topic after {@code since}, oldest first. */
    private synchronized Map<String, Object> hookEvents(Map<String, Object> p) {
        long since = p.get("since") instanceof Number n ? n.longValue() : 0L;
        String topic = "hook." + (p.get("id") == null ? "limit.applied" : p.get("id"));
        List<Object> out = new ArrayList<>();
        for (Event event : bus) {
            if (event.seq() > since && event.topic().equals(topic)) {
                out.add(event.toJson());
            }
        }
        return map("events", out);
    }

    /** The pack's catalogue as the scripted module reports it installed. */
    private record HookCatalogueRef(String id, String hash) {
        static HookCatalogueRef of() {
            return new HookCatalogueRef(Runs.PACK.hooks().id(), Runs.PACK.hooks().hash());
        }
    }

    // ------------------------------------------------------------------------------------------------ calls

    @Override
    public RpcResult call(String method, Map<String, Object> params) throws AgentRpcException {
        return call(method, params, CallOptions.defaults());
    }

    @Override
    public RpcResult call(String method, Map<String, Object> params, CallOptions options) throws AgentRpcException {
        synchronized (this) {
            calls.add(method);
        }
        Map<String, Object> p = params == null ? Map.of() : params;
        long before = seq();
        Object value;
        try {
            value = switch (method) {
                case "lending.input" -> input(p);
                case "lending.state" -> state(engine.status());
                case "lending.loan" -> engine.currentLoan() != null ? loan(engine.currentLoan(), true)
                        : loan(engine.lastLoan(), false);
                case "lending.prompt" -> prompt(engine.prompts().current());
                case "lending.prompt.answer" -> answer(p);
                case "lending.slip" -> slips(p);
                case "lending.access.check" -> accessCheck(p);
                case "lending.deposit" -> deposit(p);
                case "lending.limits" -> map("maxReturn", money(engine.access().systemMaxReturn()));
                case "lending.waiver.evaluate" -> LendingWaivers.evaluate(engine.waiverRules(), engine.catalogue(), p,
                        TODAY);
                case "app.state" -> map("lending", appState());
                case "swing.find" -> find(p);
                case "trace.mark" -> map("seq", publish(Topics.TRACE_MARK, new LinkedHashMap<>(p)));
                case "evt.query" -> query(p);
                case "sync.await" -> syncAwait(p);
                case "hook.health" -> {
                    if (!hooks) {
                        throw new RpcException(ErrorCodes.METHOD_NOT_FOUND, "unknown method " + method);
                    }
                    yield hookHealth();
                }
                case "hook.events" -> {
                    if (!hooks) {
                        throw new RpcException(ErrorCodes.METHOD_NOT_FOUND, "unknown method " + method);
                    }
                    yield hookEvents(p);
                }
                default -> throw new RpcException(ErrorCodes.METHOD_NOT_FOUND, "unknown method " + method);
            };
        } catch (RpcException e) {
            throw new AgentRpcException(method, e.error());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentRpcException(method, new RpcError(ErrorCodes.TIMEOUT, "interrupted", null));
        }
        return new RpcResult(value, List.of(), before, seq(), 0L, "headless-desk");
    }

    /** {@code lending.input}: the input posted when the state accepts it, acknowledged when the engine takes it. */
    private Map<String, Object> input(Map<String, Object> p) throws InterruptedException {
        Object source = p.get("source");
        if (!(source instanceof String s) || s.isBlank()) {
            throw invalid("source", "is missing");
        }
        String value = p.get("value") == null ? null : String.valueOf(p.get("value"));
        long ackMs = p.get("ackTimeoutMs") instanceof Number n ? n.longValue() : DEFAULT_ACK_MS;
        EngineStatus status = engine.status();
        if (!status.validInputs().contains(s)) {
            throw new RpcException(ErrorCodes.INPUT_REJECTED, "the desk does not accept " + s + " now (state "
                    + name(status.state()) + "; valid: " + status.validInputs() + ")", map("source", s, "state",
                    name(status.state()), "validInputs", new ArrayList<>(status.validInputs()), "prompt",
                    status.prompt(), "awaitingInput", status.awaitingInput(), "modalPrompt",
                    prompt(engine.prompts().current()), "reason", "notAccepted"));
        }
        LendingInput input = new LendingInput(s, value);
        // phase8-contracts.md 7.4 (ES): the input's id (the caller's, or one minted as the module does) in the result
        // and in its acknowledgement; the value never.
        String inputId = p.get("inputId") instanceof String given ? given : "lending-" + INPUT_IDS.incrementAndGet();
        CountDownLatch latch = new CountDownLatch(1);
        synchronized (acks) {
            acks.put(input, latch);
        }
        if (!engine.inbox().offer(input)) {
            synchronized (acks) {
                acks.remove(input);
            }
            throw new RpcException(ErrorCodes.INPUT_REJECTED, "the desk's inbox is full", map("source", s,
                    "reason", "inboxFull"));
        }
        boolean acked = latch.await(Math.max(0L, ackMs), TimeUnit.MILLISECONDS);
        synchronized (acks) {
            acks.remove(input);
        }
        if (acked) {
            publish(Topics.INPUT_ACKED, map("source", s, "inputId", inputId));
        }
        return map("accepted", true, "acked", acked, "ackMs", 0L, "inputId", inputId, "consumed",
                acked ? "CONSUMED" : "QUEUED");
    }

    /** The ids {@link #input} mints, as the lending module's {@code lending-<n>}. */
    private static final java.util.concurrent.atomic.AtomicLong INPUT_IDS
            = new java.util.concurrent.atomic.AtomicLong();

    /**
     * {@code sync.await}, as the agent's core primitive answers it: the first event after {@code since} whose topic
     * matches {@code topics} (a glob or a list of globs) and whose payload has the {@code match} entries, as its JSON,
     * or {@code TIMEOUT} with the newest sequence number after {@code timeoutMs} (default 10 s).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> syncAwait(Map<String, Object> p) throws AgentRpcException {
        Object topics = p.get("topics");
        String globs = topics instanceof List<?> list ? String.join(",", list.stream().map(String::valueOf).toList())
                : topics instanceof String s ? s : null;
        if (globs == null) {
            throw invalid("topics", "is missing");
        }
        if (!(p.get("since") instanceof Number since)) {
            throw invalid("since", "is missing");
        }
        long timeoutMs = p.get("timeoutMs") instanceof Number n ? n.longValue() : 10_000L;
        Map<String, Object> match = p.get("match") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        Event event = await(globs, since.longValue(), match, Duration.ofMillis(timeoutMs));
        if (event == null) {
            throw new RpcException(ErrorCodes.TIMEOUT, "no matching event within " + timeoutMs + " ms", map(
                    "lastSeq", seq(), "topics", globs, "since", since.longValue(), "timeoutMs", timeoutMs));
        }
        return event.toJson();
    }

    private Map<String, Object> answer(Map<String, Object> p) {
        Object choice = p.get("choice");
        if (!(choice instanceof String c)) {
            throw invalid("choice", "is missing");
        }
        String id = p.get("id") == null ? null : String.valueOf(p.get("id"));
        PromptService prompts = engine.prompts();
        Prompt open = prompts.current();
        if (open == null) {
            throw new RpcException(ErrorCodes.NOT_FOUND, "no prompt is open", map("choice", c));
        }
        String button = open.matchButton(c);
        boolean answered;
        try {
            answered = prompts.answer(id, c);
        } catch (IllegalArgumentException e) {
            throw new RpcException(ErrorCodes.INVALID_PARAMS, "'" + c + "' is not a button of prompt " + open.id()
                    + " " + open.buttons(), map("param", "choice", "choice", c));
        }
        if (!answered) {
            throw new RpcException(ErrorCodes.NOT_FOUND, "the open prompt is " + open.id() + ", not " + id,
                    map("choice", c, "id", id));
        }
        return map("answered", true, "id", open.id(), "choice", button, "listening", true);
    }

    private Map<String, Object> slips(Map<String, Object> p) {
        long last = p.get("last") instanceof Number n ? n.longValue() : 1L;
        List<Object> slips = new ArrayList<>();
        for (Slip slip : engine.printer().last((int) Math.max(1, Math.min(100, last)))) {
            slips.add(map("loanId", slip.loanId(), "lines", new ArrayList<>(slip.lines()), "text", slip.text()));
        }
        return map("slips", slips);
    }

    private Map<String, Object> accessCheck(Map<String, Object> p) {
        String staff = String.valueOf(p.get("staff"));
        BigDecimal amount = p.get("amount") == null ? null : new BigDecimal(String.valueOf(p.get("amount")));
        AccessDecision decision = engine.access().check(staff, String.valueOf(p.get("permission")), amount);
        Staff member = engine.access().findStaff(staff);
        return map("allowed", decision.allowed(), "limit", money(decision.limit()), "reason", decision.reason(),
                "autoSignOn", member != null && member.active() && member.autoSignOn());
    }

    private Map<String, Object> deposit(Map<String, Object> p) {
        if (p.containsKey("barcode") == p.containsKey("code")) {
            throw invalid(p.containsKey("code") ? "code" : "barcode", "give exactly one of barcode and code");
        }
        Item item = p.containsKey("barcode") ? engine.catalogue().findByBarcode(String.valueOf(p.get("barcode")))
                : engine.catalogue().findByCode(String.valueOf(p.get("code")));
        if (item == null) {
            return map("found", false);
        }
        return map("found", true, "code", item.code(), "barcode", item.barcode(), "title", item.title(), "deposit",
                money(item.unitDeposit()), "active", item.active(), "perDay", item.perDay(), "inductionRequired",
                item.inductionRequired());
    }

    private static Map<String, Object> find(Map<String, Object> p) {
        Object action = p.get("by") instanceof Map<?, ?> by ? by.get("action") : null;
        List<Object> matches = new ArrayList<>();
        if (action != null && LendingPack.KEY_ACTIONS.contains(String.valueOf(action))) {
            matches.add(map("name", action, "class", "IconKey"));
        }
        return map("matches", matches);
    }

    /** {@code app.state}'s {@code lending} section, as the lending module's state provider reports it. */
    private Map<String, Object> appState() {
        EngineStatus status = engine.status();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", engine.isRunning());
        m.putAll(state(status));
        m.put("modalPrompt", prompt(engine.prompts().current()));
        m.put("loanOpen", engine.currentLoan() != null);
        m.put("lastLoanId", engine.lastLoan() == null ? null : engine.lastLoan().id());
        m.put("slips", (long) engine.printer().count());
        m.put("engineListeners", (long) engine.listenerCount());
        m.put("seed", env.seed);
        m.put("dbPort", null);
        m.put("today", TODAY.toString());
        return m;
    }

    // ------------------------------------------------------------------------------------------------ JSON shapes

    private static Map<String, Object> state(EngineStatus status) {
        Staff staff = status.staff();
        return map("name", name(status.state()), "prompt", status.prompt(), "validInputs",
                new ArrayList<>(status.validInputs()), "staff", staff == null ? null : map("code", staff.code(),
                        "name", staff.name()), "returnMode", status.returnMode(), "locked", status.locked(),
                "awaitingInput", status.awaitingInput());
    }

    private static Map<String, Object> loan(Loan loan, boolean open) {
        if (loan == null) {
            return map("id", null, "open", false, "returning", false, "staffCode", null, "lines", List.of(),
                    "waivers", List.of(), "subtotal", "0.00", "waiverTotal", "0.00", "total", "0.00", "deposits",
                    List.of(), "change", null, "authorisations", List.of(), "postedAt", null);
        }
        List<Object> lines = new ArrayList<>();
        for (LoanLine line : loan.lines()) {
            lines.add(map("lineNo", (long) line.lineNo(), "code", line.code(), "title", line.title(), "qty",
                    Deposits.formatQty(line.qty()), "unitDeposit", money(line.unitDeposit()), "amount",
                    money(line.amount()), "returned", line.returned(), "perDay", line.perDay()));
        }
        List<Object> waivers = new ArrayList<>();
        for (LoanWaiver w : loan.waivers()) {
            waivers.add(map("ruleCode", w.ruleCode(), "amount", money(w.amount())));
        }
        List<Object> deposits = new ArrayList<>();
        for (LoanDeposit d : loan.deposits()) {
            deposits.add(map("amount", money(d.amount())));
        }
        List<Object> authorisations = new ArrayList<>();
        for (LoanAuthorisation a : loan.authorisations()) {
            authorisations.add(map("kind", a.kind(), "staffCode", a.staffCode()));
        }
        return map("id", loan.id(), "open", open, "returning", loan.returning(), "staffCode", loan.staffCode(),
                "lines", lines, "waivers", waivers, "subtotal", money(loan.subtotal()), "waiverTotal",
                money(loan.waiverTotal()), "total", money(loan.total()), "deposits", deposits, "change",
                money(loan.change()), "authorisations", authorisations, "postedAt", loan.postedAt() == null ? null
                        : loan.postedAt().toString());
    }

    private static Map<String, Object> prompt(Prompt prompt) {
        return prompt == null ? null : map("id", prompt.id(), "text", prompt.text(), "buttons",
                new ArrayList<>(prompt.buttons()));
    }

    private static String money(BigDecimal amount) {
        return amount == null ? null : Deposits.format(amount);
    }

    private static String name(LendingState state) {
        return state == null ? null : state.name();
    }

    private static RpcException invalid(String param, String reason) {
        return new RpcException(ErrorCodes.INVALID_PARAMS, param + " " + reason, map("param", param, "reason",
                reason));
    }

    /** A map that may hold null values, in the order given. */
    static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            m.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------------ the rest

    /** Every lending primitive's capability OK, and the two module capabilities without a primitive. */
    @Override
    public CapabilityMatrix capabilities() {
        Map<String, Capability> caps = new TreeMap<>();
        for (String name : PRIMITIVES) {
            caps.put(name, new Capability(name, CapabilityState.OK, null, "headless desk"));
        }
        for (String name : List.of("lending.sql", "lending.loan.authorisations")) {
            caps.put(name, new Capability(name, CapabilityState.OK, null, "headless desk"));
        }
        if (hooks) {
            for (String name : List.of("hook.engine", "hook.limit.applied")) {
                caps.put(name, new Capability(name, CapabilityState.OK, null, "advice"));
            }
        }
        if (swingTree) {
            caps.put("swing.tree", new Capability("swing.tree", CapabilityState.OK, null, "headless desk"));
        }
        return new CapabilityMatrix(caps);
    }

    /** The lending primitives with their capability ids; the two that change the desk are mutating. */
    @Override
    public List<PrimitiveDescriptor> schema() {
        List<PrimitiveDescriptor> list = new ArrayList<>();
        for (String name : PRIMITIVES) {
            list.add(new PrimitiveDescriptor(name, "the headless desk's " + name, Map.of(), Map.of(), "APP",
                    name.equals("lending.input") ? 15_000L : 5000L, name, MUTATING.contains(name), false));
        }
        return list;
    }

    @Override
    public Map<String, Object> ping() {
        return map("ready", true, "protocol", Protocol.VERSION, Protocol.KEY_SESSION_ID, session);
    }

    /** This launch's agent session, also in {@link #ping()} (phase8-contracts.md 9.3.2); no I/O. */
    @Override
    public String agentSession() {
        return session;
    }

    /** Stops the engine and shuts the database down. */
    @Override
    public void close() {
        engine.stop();
        db.close();
    }
}
