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
import ai.skillroy.testjava.protocol.CompositeResult;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.InputResult;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.Topics;
import ai.skillroy.testjava.samples.lending.Engine;
import ai.skillroy.testjava.samples.lending.EngineStatus;
import ai.skillroy.testjava.samples.lending.LendingApp;
import ai.skillroy.testjava.samples.lending.LendingInput;
import ai.skillroy.testjava.samples.lending.LendingState;
import ai.skillroy.testjava.samples.lending.Prompt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The desk's two composite primitives (phase8-contracts.md 7.5, gap 19, decision D584): logic that depends on which
 * question the desk asks next lives in one agent-side primitive that owns its nested questions, so that a plan stays
 * linear. Each is mutating, runs on the module's worker ({@code APP}), waits on the desk only through the engine
 * listener's bounded waits (within the call's remaining time less 250 ms; never on the EDT or the engine thread),
 * takes every nested answer as a parameter, never answers a question it does not own (it returns and leaves the
 * question open for the step's prompt check), and reports in {@link CompositeResult}'s shape, never with a password:
 *
 * <ul>
 *   <li>{@value #SIGN_ON} {@code {staff, password?}}: posts the staff code; the desk signs an auto-sign-on member on
 *       (branch {@code auto}), asks for a password, which the call answers when it has one (branch {@code password};
 *       {@code passwordRequired} with the pack's warning {@code lending.PASSWORD_REQUIRED} when it has none, the desk
 *       left waiting for it), or refuses with a prompt ({@code refused}, the prompt left open);</li>
 *   <li>{@value #SCAN_CHECKED} {@code {barcode, inducted}}: posts the barcode; answers the desk's induction check
 *       with {@code Yes} or {@code No} as {@code inducted} says (branch {@code induction}); another prompt is
 *       {@code refused} and left open; none is {@code plain}.</li>
 * </ul>
 *
 * <p>Both may keep the listener's {@code prompt.waiting} events: the composite lists the {@code seq} of every prompt it
 * answered, and the executor expects exactly those (phase8-contracts.md 7.5, D586). Stateless; safe from any thread.
 */
final class LendingComposites {

    static final String SIGN_ON = "lending.signOn";
    static final String SCAN_CHECKED = "lending.scanChecked";

    /** The composites' names, which are also their capability ids. */
    static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(SCAN_CHECKED, SIGN_ON));

    /** The pack's warning code of a sign-on that needs a password the call did not have. */
    static final String PASSWORD_REQUIRED = "lending.PASSWORD_REQUIRED";

    /** The reserve kept from the call's remaining time for answering. */
    static final long RESERVE_MS = 250L;
    /** The longest wait for the engine's acknowledgement of one input. */
    static final long ACK_WAIT_MS = 5000L;

    private final AgentContext ctx;
    private final LendingAccess access;
    private final ListenerSlot slot;

    LendingComposites(AgentContext ctx, LendingAccess access, ListenerSlot slot) {
        this.ctx = ctx;
        this.access = access;
        this.slot = slot;
    }

    /** The two primitives, in name order. */
    List<Primitive> all() {
        List<Primitive> list = new ArrayList<Primitive>();
        list.add(Primitives.of(PrimitiveSpec.builder(SCAN_CHECKED)
                .description("Scans an item and answers the desk's induction check if it asks one: "
                        + "{composite: {branch: induction|plain|refused, answered}, inputId, inputIds, consumed}. "
                        + "Another prompt is left open (branch refused).")
                .param("barcode", "string", true, "the barcode, as a scan sends it")
                .param("inducted", "boolean", true, "whether the borrower completed the safety induction (the "
                        + "answer Yes or No)")
                .thread(ThreadPolicy.APP).timeoutMs(15000).capability(SCAN_CHECKED).mutating().build(),
                this::scanChecked));
        list.add(Primitives.of(PrimitiveSpec.builder(SIGN_ON)
                .description("Signs a staff member on whichever way the desk asks: {composite: {branch: auto|"
                        + "password|passwordRequired|refused, answered}, inputId, inputIds, consumed}; never the "
                        + "password. Without a password for a member who needs one: branch passwordRequired and the "
                        + "warning lending.PASSWORD_REQUIRED, the desk left waiting for it. A refusal's prompt is "
                        + "left open (branch refused).")
                .param("staff", "string", true, "the staff code")
                .param("password", "string", false, "the password, when the member needs one (a secret)")
                .thread(ThreadPolicy.APP).timeoutMs(15000).capability(SIGN_ON).mutating().build(), this::signOn));
        return list;
    }

    // ------------------------------------------------------------------ lending.signOn

    private Object signOn(Invocation inv) throws Exception {
        JsonView args = inv.args();
        String staff = args.requireStr("staff");
        String password = args.has("password") ? args.str("password") : null;
        Flow flow = start(inv, LendingInput.ENTRY, LendingState.SIGNED_OFF);
        flow.post(LendingInput.entry(staff), "staff");
        Prompt prompt = flow.app.prompts().current();
        EngineStatus status = flow.app.engine().status();
        if (prompt != null) {
            // unknown-staff, staff-inactive, sign-on-not-allowed: the desk's question, left open for the prompt check.
            return flow.result("refused");
        }
        if (status.state() == LendingState.IDLE) {
            return flow.result("auto");
        }
        if (status.state() != LendingState.PASSWORD || !status.awaitingInput()) {
            throw unexpected(SIGN_ON, status);
        }
        if (password == null) {
            inv.warn(PASSWORD_REQUIRED, "staff member " + staff + " needs a password, and the call had none",
                    EngineEvents.map("staff", staff));
            return flow.result("passwordRequired");
        }
        flow.post(LendingInput.entry(password), "password");
        if (flow.app.prompts().current() != null) {
            return flow.result("refused");
        }
        EngineStatus after = flow.app.engine().status();
        if (after.state() != LendingState.IDLE) {
            throw unexpected(SIGN_ON, after);
        }
        return flow.result("password");
    }

    // ------------------------------------------------------------------ lending.scanChecked

    private Object scanChecked(Invocation inv) throws Exception {
        JsonView args = inv.args();
        String barcode = args.requireStr("barcode");
        Boolean inducted = args.raw().get("inducted") instanceof Boolean ? (Boolean) args.raw().get("inducted")
                : null;
        if (inducted == null) {
            throw new RpcException(ErrorCodes.INVALID_PARAMS, "inducted must be true or false",
                    EngineEvents.map("param", "inducted"));
        }
        Flow flow = start(inv, LendingInput.BARCODE, null);
        flow.post(LendingInput.barcode(barcode), null);
        Prompt prompt = flow.app.prompts().current();
        if (prompt == null) {
            return flow.result("plain");
        }
        if (!Engine.PROMPT_INDUCTION_CHECK.equals(prompt.id())) {
            return flow.result("refused");
        }
        String choice = inducted.booleanValue() ? "Yes" : "No";
        Map<String, Object> shown = flow.events.lastPrompt();
        Long seq = shown != null && prompt.id().equals(shown.get("id")) && shown.get("seq") instanceof Long
                ? (Long) shown.get("seq") : null;
        long seenRequested = flow.events.requestedCount();
        long seenPrompt = flow.events.lastPromptSeq();
        if (!flow.app.prompts().answer(prompt.id(), choice)) {
            throw new RpcException(ErrorCodes.NOT_FOUND, "the induction check was answered meanwhile",
                    EngineEvents.map("prompt", prompt.id()));
        }
        flow.builder.answered(Topics.PROMPT_WAITING, prompt.id(), prompt.text(), seq, "inducted", choice, null);
        flow.events.awaitChange(seenRequested, seenPrompt, flow.remaining());
        if (flow.app.prompts().current() == null && !flow.app.engine().status().awaitingInput()) {
            throw unexpected(SCAN_CHECKED, flow.app.engine().status());
        }
        return flow.result("induction");
    }

    // ------------------------------------------------------------------ the flow

    /** Checks the desk accepts {@code source} (and is in {@code state}, when given) and starts a flow. */
    private Flow start(Invocation inv, String source, LendingState state) {
        LendingApp app = access.requireApp();
        slot.tryAttach(app);
        EngineStatus status = app.engine().status();
        if (!status.validInputs().contains(source) || (state != null && status.state() != state)) {
            throw LendingPrimitives.notAccepted(app, status, source);
        }
        EngineEvents events = slot.events();
        if (events == null) {
            throw new RpcException(ErrorCodes.NOT_READY, "the lending module's engine listener is not attached");
        }
        return new Flow(inv, app, events);
    }

    private static RpcException unexpected(String primitive, EngineStatus status) {
        return new RpcException(ErrorCodes.TIMEOUT, primitive + ": the desk did not answer in time (state "
                + LendingPrimitives.stateName(status) + ", awaiting input " + status.awaitingInput() + ")",
                EngineEvents.map("primitive", primitive, "state", LendingPrimitives.stateName(status)));
    }

    /** One composite call: its inputs, the questions they answered, and the bounded waits. */
    private final class Flow {
        final Invocation inv;
        final LendingApp app;
        final EngineEvents events;
        final CompositeResult builder = CompositeResult.builder();
        final List<Object> inputIds = new ArrayList<Object>();
        String lastInputId;
        boolean consumed = true;

        Flow(Invocation inv, LendingApp app, EngineEvents events) {
            this.inv = inv;
            this.app = app;
            this.events = events;
        }

        long remaining() {
            return Math.max(0L, inv.remainingMs() - RESERVE_MS);
        }

        /**
         * Posts one input, waits for the engine to take it and then for the desk's next question (it waits for input
         * again or shows a prompt), all within the call's time; {@code by} names the parameter that answered the
         * question the desk showed when the input was posted (null: the input answered none of the composite's own).
         */
        void post(LendingInput input, String by) throws Exception {
            long seenRequested = events.requestedCount();
            long seenPrompt = events.lastPromptSeq();
            long questionSeq = events.lastRequestedSeq();
            String question = app.engine().status().prompt();
            InputPosting.Posted posted = InputPosting.post(ctx, events, app, input, null, inv.correlationId(),
                    InputPosting.inbox(app), Math.min(ACK_WAIT_MS, remaining()));
            inputIds.add(posted.inputId);
            lastInputId = posted.inputId;
            consumed &= posted.acked;
            if (by != null) {
                builder.answered(Topics.INPUT_REQUESTED, null, question, questionSeq > 0 ? Long.valueOf(questionSeq)
                        : null, by, null, posted.inputId);
            }
            if (!posted.acked) {
                throw new RpcException(ErrorCodes.TIMEOUT, input.source() + " was not taken by the desk in time",
                        EngineEvents.map("inputId", posted.inputId));
            }
            events.awaitChange(seenRequested, seenPrompt, remaining());
        }

        Map<String, Object> result(String branch) {
            Map<String, Object> out = builder.branch(branch).build();
            out.put(InputResult.INPUT_ID, lastInputId);
            out.put(InputResult.INPUT_IDS, new ArrayList<Object>(inputIds));
            out.put(InputResult.CONSUMED, consumed ? InputResult.STATE_CONSUMED : InputResult.STATE_QUEUED);
            return out;
        }
    }
}
