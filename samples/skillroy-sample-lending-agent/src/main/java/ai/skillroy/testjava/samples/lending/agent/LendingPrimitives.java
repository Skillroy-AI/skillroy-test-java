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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.agent.api.Primitive;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.Primitives;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.Topics;
import ai.skillroy.testjava.samples.lending.AccessControl;
import ai.skillroy.testjava.samples.lending.AccessDecision;
import ai.skillroy.testjava.samples.lending.Catalogue;
import ai.skillroy.testjava.samples.lending.Engine;
import ai.skillroy.testjava.samples.lending.EngineStatus;
import ai.skillroy.testjava.samples.lending.IdleView;
import ai.skillroy.testjava.samples.lending.Item;
import ai.skillroy.testjava.samples.lending.LendingApp;
import ai.skillroy.testjava.samples.lending.LendingInput;
import ai.skillroy.testjava.samples.lending.Loan;
import ai.skillroy.testjava.samples.lending.Prompt;
import ai.skillroy.testjava.samples.lending.Slip;
import ai.skillroy.testjava.samples.lending.Staff;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code lending.*} primitives (contract section 9.2, Phase 2 section 13.2 for {@code lending.deposit},
 * {@code lending.limits} and the {@code autoSignOn} field of {@code lending.access.check}, and Phase 3 section 10.2 for
 * {@code lending.waiver.evaluate}). Each one runs under {@code ThreadPolicy.APP}, that is on the module's worker
 * thread, and reads the desk's immutable snapshots or posts to its inbox; none of them ever runs on or waits for the
 * engine thread. Each depends on the capability of the same name, which is UNAVAILABLE while the desk is not running.
 *
 * <p>The read-only primitives that answer with the application's own decisions ({@code lending.access.check},
 * {@code lending.deposit}, {@code lending.limits}, {@code lending.waiver.evaluate}) exist so that a test framework can
 * verify a value it found in the database, or computed itself, against the code the desk itself runs: the access
 * rules, the catalogue lookup a scan makes, the system maximum the desk loaded at start-up, the waiver rules the desk
 * applies on its business date ({@link LendingWaivers}).
 *
 * <p>Every primitive first gives the module's engine listener a chance to attach ({@link ListenerSlot#tryAttach}):
 * an agent that found the desk asking a question is ready without the listener, and the first look at the
 * desk after the question was answered puts it in place. {@code lending.prompt.answer} itself waits (bounded) for
 * that moment when it answers without a listener, and says in {@code listening} whether events now follow.
 *
 * <p>Stateless apart from the shared {@link LendingAccess} and {@link ListenerSlot}; safe from any thread.
 */
final class LendingPrimitives {

    static final String INPUT = "lending.input";
    static final String STATE = "lending.state";
    static final String LOAN = "lending.loan";
    static final String PROMPT = "lending.prompt";
    static final String PROMPT_ANSWER = "lending.prompt.answer";
    static final String SLIP = "lending.slip";
    static final String ACCESS_CHECK = "lending.access.check";
    static final String DEPOSIT = "lending.deposit";
    static final String LIMITS = "lending.limits";
    static final String WAIVER_EVALUATE = "lending.waiver.evaluate";
    /** The engine's own state at its idle point (phase8-contracts.md 4.1, 12.2). */
    static final String ENGINE_VIEW = "lending.engine.view";
    /** {@value #ENGINE_VIEW}'s quiescent budget. */
    static final long ENGINE_VIEW_BUDGET_MS = 20L;

    /** Every primitive name, which is also its capability id. */
    static final String[] NAMES = {INPUT, STATE, LOAN, PROMPT, PROMPT_ANSWER, SLIP, ACCESS_CHECK, DEPOSIT, LIMITS,
        WAIVER_EVALUATE, ENGINE_VIEW};

    /** Default and largest acknowledgement wait of {@code lending.input}. */
    static final long DEFAULT_ACK_MS = 5000L;
    static final long MAX_ACK_MS = 60000L;
    /** Most slips {@code lending.slip} returns. */
    static final int MAX_SLIPS = 100;
    /**
     * How long {@code lending.prompt.answer}, when it answers without an engine listener, waits for the desk to
     * wait for input again (the moment the listener can be attached).
     */
    static final long LATE_ATTACH_WAIT_MS = 2000L;

    private final AgentContext ctx;
    private final LendingAccess access;
    private final ListenerSlot slot;

    LendingPrimitives(AgentContext ctx, LendingAccess access, ListenerSlot slot) {
        this.ctx = ctx;
        this.access = access;
        this.slot = slot;
    }

    /** The primitives, in name order. */
    List<Primitive> all() {
        List<Primitive> list = new ArrayList<Primitive>();
        list.add(Primitives.of(PrimitiveSpec.builder(ACCESS_CHECK)
                .description("Asks the desk's own access control whether a staff member holds a permission "
                        + "(and, for returns, whether an amount is within their limit and the system maximum): "
                        + "{allowed, limit, reason, autoSignOn}. autoSignOn is true when the desk signs the staff "
                        + "member on without asking for a password (false for unknown or inactive staff).")
                .param("staff", "string", true, "staff code")
                .param("permission", "string", true, "permission name, e.g. RETURN")
                .param("amount", "string|number", false, "amount to check, as a decimal string")
                .thread(ThreadPolicy.APP).timeoutMs(5000).capability(ACCESS_CHECK).build(), this::accessCheck));
        list.add(Primitives.of(PrimitiveSpec.builder(DEPOSIT)
                .description("Looks an item up the way the desk does: by barcode as a scan does, or by item code. "
                        + "{found:true, code, barcode, title, deposit, active, perDay, inductionRequired} with the "
                        + "deposit as a decimal string, or {found:false}. Exactly one of barcode and code.")
                .param("barcode", "string", false, "the barcode, matched exactly (what a scan does)")
                .param("code", "string", false, "the item code, matched ignoring case")
                .thread(ThreadPolicy.APP).timeoutMs(5000).capability(DEPOSIT).build(), this::deposit));
        list.add(Primitives.of(PrimitiveSpec.builder(INPUT)
                .description("Posts one input to the desk (Entry, Barcode or Key.<ACTION>) after checking that "
                        + "the current state accepts its source, then waits for the engine to accept it.")
                .param("source", "string", true, "Entry, Barcode, Key.DUE, Key.DEPOSIT, Key.RETURN, Key.CANCEL, "
                        + "Key.SIGN_OFF or Key.QTY")
                .param("value", "string|number", false, "entry text, barcode, or key value (DEPOSIT amount, QTY)")
                .param("ackTimeoutMs", "integer", false, "how long to wait for the engine to accept the input "
                        + "(default 5000, at most 60000)")
                .thread(ThreadPolicy.APP).timeoutMs(15000).capability(INPUT).mutating().build(), this::input));
        list.add(Primitives.of(PrimitiveSpec.builder(LIMITS)
                .description("The system maximum return the desk loaded at start-up and enforces: {maxReturn} as a "
                        + "decimal string, or null when no maximum is configured.")
                .thread(ThreadPolicy.APP).timeoutMs(5000).capability(LIMITS).build(), this::limits));
        list.add(Primitives.of(PrimitiveSpec.builder(LOAN)
                .description("The open loan, else the most recently recorded one; money as decimal strings.")
                .thread(ThreadPolicy.APP).timeoutMs(5000).capability(LOAN).build(), this::loan));
        list.add(Primitives.of(PrimitiveSpec.builder(PROMPT)
                .description("The desk's open modal prompt {id, text, buttons}, or null.")
                .thread(ThreadPolicy.APP).timeoutMs(5000).capability(PROMPT).build(), this::prompt));
        list.add(Primitives.of(PrimitiveSpec.builder(PROMPT_ANSWER)
                .description("Answers the open prompt with one of its buttons (exact, else ignoring case): "
                        + "{answered, id, choice, listening}. listening is false when the module has no engine "
                        + "listener (the agent arrived while a prompt was open and the desk asked again), so "
                        + "that no input.requested or prompt.waiting event follows the answer.")
                .param("choice", "string", true, "button label")
                .param("id", "string", false, "answer only if the open prompt has this id")
                .thread(ThreadPolicy.APP).timeoutMs(5000).capability(PROMPT_ANSWER).mutating().build(),
                this::answer));
        list.add(Primitives.of(PrimitiveSpec.builder(SLIP)
                .description("The most recent slips, newest first: {slips:[{loanId, lines, text}]}.")
                .param("last", "integer", false, "how many (default 1, at most 100)")
                .thread(ThreadPolicy.APP).timeoutMs(5000).capability(SLIP).build(), this::slip));
        list.add(Primitives.of(PrimitiveSpec.builder(STATE)
                .description("The desk's state: {name, prompt, validInputs, staff, returnMode, locked, "
                        + "awaitingInput}.")
                .thread(ThreadPolicy.APP).timeoutMs(5000).capability(STATE).build(), this::state));
        list.add(Primitives.of(PrimitiveSpec.builder(WAIVER_EVALUATE)
                .description("The desk's own dry run of its waiver rules on counted loan lines, on its business "
                        + "date or the given one: {date, waivers:[{ruleCode, amount}]}, one waiver per rule that "
                        + "waives something, in rule-id order, amounts as decimal strings; empty when nothing is "
                        + "waived. Each line is exactly one of code and barcode, plus qty; an unknown item is "
                        + "INVALID_PARAMS, a per-day item is ignored by the rules.")
                .param("lines", "array", true, "1 to " + LendingWaivers.MAX_LINES + " loan lines {code or barcode, "
                        + "qty}, qty a whole number from 1 to " + LendingWaivers.MAX_QTY)
                .param("date", "string", false, "ISO-8601 local date to evaluate the rules on (default: the desk's "
                        + "business date)")
                .thread(ThreadPolicy.APP).timeoutMs(5000).capability(WAIVER_EVALUATE).build(),
                this::waiverEvaluate));
        list.add(engineView());
        return list;
    }

    /**
     * {@value #ENGINE_VIEW} (phase8-contracts.md 4.1, 12.2): {@code ThreadPolicy.APP_QUIESCENT} with a budget of
     * {@value #ENGINE_VIEW_BUDGET_MS} ms, so that its body runs on the engine thread itself at the engine's idle point
     * (the module's quiescent point, driven by {@code EngineEvents.idle}) and reads what no published snapshot holds:
     * {@code Engine.idleView()} as JSON. It looks at nothing else and attaches no listener (it runs inside an engine
     * callback). {@code NOT_READY} while no idle point comes in its time, a prompt open for example.
     */
    private Primitive engineView() {
        return Primitives.of(PrimitiveSpec.builder(ENGINE_VIEW)
                .description("The desk engine's own state at its idle point, read on the engine thread: {state, step, "
                        + "pendingQty, returnMode, lockedFrom, lineCount, idleLockRemainingMs}. NOT_READY while the "
                        + "engine offers no idle point within the call's time (a prompt is open, an input runs).")
                .thread(ThreadPolicy.APP_QUIESCENT).quiescentBudgetMs(ENGINE_VIEW_BUDGET_MS).timeoutMs(5000)
                .capability(ENGINE_VIEW).build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return idleViewJson(access.requireApp().engine().idleView());
                    }
                });
    }

    /** {@code IdleView} as JSON: the quantity as a plain decimal string, the states by name. */
    private static Map<String, Object> idleViewJson(IdleView view) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("state", view.state() == null ? null : view.state().name());
        m.put("step", view.step());
        m.put("pendingQty", view.pendingQty() == null ? null : view.pendingQty().toPlainString());
        m.put("returnMode", Boolean.valueOf(view.returnMode()));
        m.put("lockedFrom", view.lockedFrom() == null ? null : view.lockedFrom().name());
        m.put("lineCount", Long.valueOf(view.lineCount()));
        m.put("idleLockRemainingMs", view.idleLockRemainingMs());
        return m;
    }

    // ------------------------------------------------------------------ bodies

    private Object input(Invocation inv) throws Exception {
        JsonView args = inv.args();
        String source = args.requireStr("source");
        String value = args.has("value") ? args.str("value") : null;
        long ackTimeoutMs = args.num("ackTimeoutMs", DEFAULT_ACK_MS);
        if (ackTimeoutMs < 0 || ackTimeoutMs > MAX_ACK_MS) {
            throw invalid("ackTimeoutMs", "must be between 0 and " + MAX_ACK_MS);
        }
        LendingApp app = app();
        Engine engine = app.engine();
        EngineStatus status = engine.status();
        if (!status.validInputs().contains(source)) {
            Map<String, Object> data = EngineEvents.map("source", source, "state", stateName(status),
                    "validInputs", new ArrayList<String>(status.validInputs()), "prompt", status.prompt(),
                    "awaitingInput", Boolean.valueOf(status.awaitingInput()),
                    "modalPrompt", LendingJson.prompt(app.prompts().current()));
            throw new RpcException(ErrorCodes.INPUT_REJECTED, "the desk does not accept " + source + " now ("
                    + (status.awaitingInput() ? "state " + stateName(status)
                    : "busy" + (app.prompts().current() != null ? " with a prompt open" : "") + ", state "
                    + stateName(status)) + "; valid: " + status.validInputs() + ")", data);
        }
        EngineEvents events = slot.events();
        if (events == null) {
            throw new RpcException(ErrorCodes.NOT_READY, "the lending module's engine listener is not attached");
        }
        LendingInput input = new LendingInput(source, value);
        EngineEvents.AckWait wait = events.expectAck(input);
        long t0 = System.nanoTime();
        if (!app.inbox().offer(input)) {
            events.forget(input);
            throw new RpcException(ErrorCodes.INPUT_REJECTED, "the desk's inbox is full",
                    EngineEvents.map("source", source, "inboxSize", Long.valueOf(app.inbox().size())));
        }
        long budget = Math.max(0L, inv.remainingMs() - 250L);
        boolean acked;
        try {
            acked = wait.await(Math.min(ackTimeoutMs, budget));
        } finally {
            events.forget(input);
        }
        long ackMs = (System.nanoTime() - t0) / 1000000L;
        if (acked) {
            ctx.events().publish(Topics.INPUT_ACKED, EngineEvents.SOURCE, EngineEvents.map("source", source));
        }
        return EngineEvents.map("accepted", Boolean.TRUE, "acked", Boolean.valueOf(acked), "ackMs",
                Long.valueOf(ackMs));
    }

    private Object state(Invocation inv) {
        return LendingJson.state(app().engine().status());
    }

    private Object loan(Invocation inv) {
        Engine engine = app().engine();
        Loan open = engine.currentLoan();
        if (open != null) {
            return LendingJson.loan(open, true);
        }
        return LendingJson.loan(engine.lastLoan(), false);
    }

    private Object prompt(Invocation inv) {
        return LendingJson.prompt(app().prompts().current());
    }

    private Object answer(Invocation inv) {
        JsonView args = inv.args();
        String choice = args.requireStr("choice");
        String id = args.str("id", null);
        LendingApp app = app();
        Prompt open = app.prompts().current();
        if (open == null) {
            throw new RpcException(ErrorCodes.NOT_FOUND, "no prompt is open", EngineEvents.map("choice", choice));
        }
        boolean listening = slot.events() != null;
        String button = open.matchButton(choice);
        boolean answered;
        try {
            answered = app.prompts().answer(id, choice);
        } catch (IllegalArgumentException e) {
            throw new RpcException(ErrorCodes.INVALID_PARAMS, "'" + choice + "' is not a button of prompt "
                    + open.id() + " " + open.buttons(), EngineEvents.map("param", "choice", "choice", choice,
                    "prompt", open.id(), "buttons", new ArrayList<String>(open.buttons())));
        }
        if (!answered) {
            String why = id != null && !id.equals(open.id()) ? "the open prompt is " + open.id() + ", not " + id
                    : "the prompt was answered meanwhile";
            throw new RpcException(ErrorCodes.NOT_FOUND, why, EngineEvents.map("choice", choice, "id", id));
        }
        if (!listening) {
            // No listener, so no event will say what the answer led to: wait for the desk to wait for input
            // (the listener is attached then and announces it with input.requested) or to ask again.
            listening = slot.attachWhenWaiting(app, Math.max(0L, Math.min(LATE_ATTACH_WAIT_MS,
                    inv.remainingMs() - 250L)));
        }
        return EngineEvents.map("answered", Boolean.TRUE, "id", open.id(), "choice", button, "listening",
                Boolean.valueOf(listening));
    }

    private Object slip(Invocation inv) {
        long last = inv.args().num("last", 1L);
        if (last < 1 || last > MAX_SLIPS) {
            throw invalid("last", "must be between 1 and " + MAX_SLIPS);
        }
        List<Object> slips = new ArrayList<Object>();
        for (Slip r : app().printer().last((int) last)) {
            slips.add(LendingJson.slip(r));
        }
        return EngineEvents.map("slips", slips);
    }

    private Object accessCheck(Invocation inv) {
        JsonView args = inv.args();
        String staff = args.requireStr("staff");
        String permission = args.requireStr("permission");
        BigDecimal amount = null;
        if (args.has("amount")) {
            String text = args.str("amount");
            try {
                amount = new BigDecimal(text.trim());
            } catch (NumberFormatException e) {
                throw invalid("amount", "not a decimal number: " + text);
            }
        }
        return accessCheck(app().access(), staff, permission, amount);
    }

    /**
     * {@code lending.access.check}'s answer: the desk's own decision ({@code AccessControl.check}) and whether the
     * desk signs the staff member on without a password, which is what its engine reads at sign-on: the member's own
     * flag, and false for an unknown or inactive staff member (contract 13.2), whom the desk does not sign on at all.
     */
    static Map<String, Object> accessCheck(AccessControl access, String staff, String permission, BigDecimal amount) {
        AccessDecision decision = access.check(staff, permission, amount);
        Staff member = access.findStaff(staff);
        return EngineEvents.map("allowed", Boolean.valueOf(decision.allowed()), "limit",
                LendingJson.money(decision.limit()), "reason", decision.reason(), "autoSignOn",
                Boolean.valueOf(member != null && member.active() && member.autoSignOn()));
    }

    private Object deposit(Invocation inv) {
        JsonView args = inv.args();
        boolean byBarcode = args.has("barcode");
        boolean byCode = args.has("code");
        if (byBarcode && byCode) {
            throw invalid("code", "must not be given together with barcode: give exactly one of barcode and code");
        }
        if (!byBarcode && !byCode) {
            throw invalid("barcode", "is missing: give exactly one of barcode and code");
        }
        Catalogue catalogue = app().catalogue();
        return deposit(byBarcode ? catalogue.findByBarcode(args.str("barcode"))
                : catalogue.findByCode(args.str("code")));
    }

    /** {@code lending.deposit}'s answer for the item the catalogue returned, or null for none. */
    static Map<String, Object> deposit(Item item) {
        if (item == null) {
            return EngineEvents.map("found", Boolean.FALSE);
        }
        return EngineEvents.map("found", Boolean.TRUE, "code", item.code(), "barcode", item.barcode(), "title",
                item.title(), "deposit", LendingJson.money(item.unitDeposit()), "active",
                Boolean.valueOf(item.active()), "perDay", Boolean.valueOf(item.perDay()), "inductionRequired",
                Boolean.valueOf(item.inductionRequired()));
    }

    private Object limits(Invocation inv) {
        return limits(app().access());
    }

    /** {@code lending.limits}'s answer: the system maximum the desk loaded and enforces, null when none is set. */
    static Map<String, Object> limits(AccessControl access) {
        return EngineEvents.map("maxReturn", LendingJson.money(access.systemMaxReturn()));
    }

    /**
     * {@code lending.waiver.evaluate}: the desk's own waiver rules and catalogue, on its business date unless the call
     * names another ({@link LendingWaivers#evaluate}, which a test connection without an agent calls too).
     */
    private Object waiverEvaluate(Invocation inv) {
        LendingApp app = app();
        return LendingWaivers.evaluate(app.waiverRules(), app.catalogue(), inv.args().raw(),
                LendingWaivers.businessDate(app));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The running desk; the module's engine listener is attached on the way if it is not yet and the engine
     * allows it now.
     *
     * @throws RpcException CAPABILITY_UNAVAILABLE when the desk is not running
     */
    private LendingApp app() {
        LendingApp app = access.requireApp();
        slot.tryAttach(app);
        return app;
    }

    private static String stateName(EngineStatus status) {
        return status.state() == null ? null : status.state().name();
    }

    private static RpcException invalid(String param, String reason) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("param", param);
        data.put("reason", reason);
        return new RpcException(ErrorCodes.INVALID_PARAMS, param + " " + reason, data);
    }
}
