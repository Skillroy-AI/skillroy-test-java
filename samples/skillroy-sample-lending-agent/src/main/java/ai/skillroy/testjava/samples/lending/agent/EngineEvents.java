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
import ai.skillroy.testjava.protocol.Topics;
import ai.skillroy.testjava.samples.lending.Deposits;
import ai.skillroy.testjava.samples.lending.Engine;
import ai.skillroy.testjava.samples.lending.EngineListener;
import ai.skillroy.testjava.samples.lending.EngineStatus;
import ai.skillroy.testjava.samples.lending.LendingInput;
import ai.skillroy.testjava.samples.lending.LendingState;
import ai.skillroy.testjava.samples.lending.Loan;
import ai.skillroy.testjava.samples.lending.Staff;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The module's one {@link EngineListener}: turns the desk's callbacks into agent events and completes the
 * acknowledgement waits of {@code lending.input}. The events are what the lending pack's sync policy waits on, so
 * "ready" is a fact the application reports rather than a guess from quiet time.
 *
 * <p>Events (source {@value #SOURCE}): {@code state.changed {from,to}}, {@code input.requested {state,prompt,
 * validInputs}}, {@code prompt.waiting {id,text,buttons}}, {@code prompt.answered {id,choice}},
 * {@code loan.posted {loanId,total}}, {@code slip.printed {loanId}}, and (Phase 8, phase8-contracts.md 5.1, 12.2)
 * {@code staff.signedOn {code,name}} after {@code state.changed} to {@code IDLE} from {@code PASSWORD} or
 * {@code SIGNED_OFF}: the sample's realistic personal value in an event, which {@link PasswordMask}'s event rule
 * masks at publish. {@code input.acked} is published by {@code lending.input} itself, on the calling thread, so that it
 * belongs to the call.
 *
 * <p><b>Threading and host safety.</b> Every callback runs on the engine thread while the engine waits for it: each
 * one does a little bookkeeping, publishes without blocking and returns. Nothing ever escapes a callback, not even an
 * {@code Error}: the engine catches only {@code RuntimeException}, and anything else would end its thread. After
 * {@link #deactivate()} the listener is inert (it publishes nothing and completes nothing) but still signals
 * {@link #awaitRequested}, which is how the module waits for a moment when the engine allows it to be removed.
 */
final class EngineEvents implements EngineListener {

    /** Event source of everything this module publishes. */
    static final String SOURCE = "lending";
    /** Topics the lending desk publishes besides the generic ones in {@link Topics}. */
    static final String LOAN_POSTED = "loan.posted";
    /** Phase 8 (gap 109's proof): a staff member signed on, {@code {code, name}}; the name is personal data. */
    static final String STAFF_SIGNED_ON = "staff.signedOn";
    static final String SLIP_PRINTED = "slip.printed";
    static final String PROMPT_ANSWERED = "prompt.answered";

    /** One {@code lending.input} waiting for its input to be accepted by the engine. */
    static final class AckWait {
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile boolean acked;

        /** Waits up to {@code timeoutMs}; true if the engine accepted the input. */
        boolean await(long timeoutMs) throws InterruptedException {
            done.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
            return acked;
        }

        void complete(boolean accepted) {
            acked = accepted;
            done.countDown();
        }
    }

    private final AgentContext ctx;
    private final Engine engine;
    private volatile boolean active = true;
    /** Inputs offered by lending.input, by identity: the engine hands the very same object to inputAccepted. */
    private final Map<LendingInput, AckWait> pending = new IdentityHashMap<LendingInput, AckWait>();
    private final Object requestedLock = new Object();
    private long requestedCount;

    EngineEvents(AgentContext ctx, Engine engine) {
        this.ctx = ctx;
        this.engine = engine;
    }

    Engine engine() {
        return engine;
    }

    boolean isActive() {
        return active;
    }

    /** Makes the listener inert and releases every pending acknowledgement wait as "not acknowledged". */
    void deactivate() {
        active = false;
        List<AckWait> waits;
        synchronized (pending) {
            waits = new ArrayList<AckWait>(pending.values());
            pending.clear();
        }
        for (AckWait wait : waits) {
            wait.complete(false);
        }
    }

    /** Registers an input before it is offered, so that its acknowledgement cannot be missed. */
    AckWait expectAck(LendingInput input) {
        AckWait wait = new AckWait();
        if (!active) {
            wait.complete(false);
            return wait;
        }
        synchronized (pending) {
            pending.put(input, wait);
        }
        return wait;
    }

    /** Drops the wait of an input that was refused or whose wait ended. */
    void forget(LendingInput input) {
        synchronized (pending) {
            pending.remove(input);
        }
    }

    /** Number of {@code inputRequested} callbacks so far. */
    long requestedCount() {
        synchronized (requestedLock) {
            return requestedCount;
        }
    }

    /**
     * Waits until the engine has called {@code inputRequested} more than {@code seen} times in total, or the timeout
     * passes. Not called on the engine thread.
     */
    void awaitRequested(long seen, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        synchronized (requestedLock) {
            while (requestedCount <= seen) {
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) {
                    return;
                }
                requestedLock.wait(remaining);
            }
        }
    }

    /**
     * Publishes {@code input.requested} from the engine's current status, if it is waiting for input. For a listener
     * attached after the module reported ready (a prompt was open then): the engine made its own
     * {@code inputRequested} callback before this listener existed, and a controller that answered the prompt waits
     * for exactly that event. Called on an agent thread, never on the engine thread.
     */
    void announceWaiting() {
        try {
            EngineStatus status = engine.status();
            if (active && status.awaitingInput()) {
                publish(Topics.INPUT_REQUESTED, map("state", name(status.state()), "prompt", status.prompt(),
                        "validInputs", new ArrayList<String>(status.validInputs())));
            }
        } catch (Throwable t) {
            swallow("announceWaiting", t);
        }
    }

    // ------------------------------------------------------------------ callbacks (engine thread)

    @Override
    public void stateChanged(LendingState from, LendingState to) {
        try {
            if (active) {
                publish(Topics.STATE_CHANGED, map("from", name(from), "to", name(to)));
                staffSignedOn(from, to);
            }
        } catch (Throwable t) {
            swallow("stateChanged", t);
        }
    }

    /**
     * Phase 8 (phase8-contracts.md 5.1, 12.2): publishes {@code staff.signedOn {code, name}} when the state changes to
     * {@code IDLE} from {@code PASSWORD} or {@code SIGNED_OFF} with a signed-on staff member (the engine publishes its
     * status before the callback); an unlock ({@code LOCKED} to {@code IDLE}) and every other change publish nothing.
     */
    private void staffSignedOn(LendingState from, LendingState to) {
        if (to != LendingState.IDLE || from != LendingState.PASSWORD && from != LendingState.SIGNED_OFF) {
            return;
        }
        Staff staff = engine.status().staff();
        if (staff != null) {
            publish(STAFF_SIGNED_ON, map("code", staff.code(), "name", staff.name()));
        }
    }

    @Override
    public void inputRequested(LendingState state, String prompt) {
        try {
            if (active) {
                publish(Topics.INPUT_REQUESTED, map("state", name(state), "prompt", prompt, "validInputs",
                        new ArrayList<String>(engine.status().validInputs())));
            }
        } catch (Throwable t) {
            swallow("inputRequested", t);
        } finally {
            try {
                synchronized (requestedLock) {
                    requestedCount++;
                    requestedLock.notifyAll();
                }
            } catch (Throwable t) {
                swallow("inputRequested", t);
            }
        }
    }

    @Override
    public void inputAccepted(LendingInput input) {
        try {
            AckWait wait;
            synchronized (pending) {
                wait = pending.remove(input);
            }
            if (wait != null) {
                wait.complete(active);
            }
        } catch (Throwable t) {
            swallow("inputAccepted", t);
        }
    }

    @Override
    public void promptShown(String id, String text, List<String> buttons) {
        try {
            if (active) {
                publish(Topics.PROMPT_WAITING, map("id", id, "text", text, "buttons",
                        buttons == null ? new ArrayList<String>() : new ArrayList<String>(buttons)));
            }
        } catch (Throwable t) {
            swallow("promptShown", t);
        }
    }

    @Override
    public void promptAnswered(String id, String choice) {
        try {
            if (active) {
                publish(PROMPT_ANSWERED, map("id", id, "choice", choice));
            }
        } catch (Throwable t) {
            swallow("promptAnswered", t);
        }
    }

    @Override
    public void loanPosted(long loanId) {
        try {
            if (active) {
                Loan last = engine.lastLoan();
                String total = last != null && last.id() != null && last.id().longValue() == loanId
                        ? Deposits.format(last.total()) : null;
                publish(LOAN_POSTED, map("loanId", Long.valueOf(loanId), "total", total));
            }
        } catch (Throwable t) {
            swallow("loanPosted", t);
        }
    }

    @Override
    public void slipPrinted(long loanId, List<String> lines) {
        try {
            if (active) {
                publish(SLIP_PRINTED, map("loanId", Long.valueOf(loanId)));
            }
        } catch (Throwable t) {
            swallow("slipPrinted", t);
        }
    }

    private void publish(String topic, Map<String, Object> payload) {
        ctx.events().publish(topic, SOURCE, payload);
    }

    private void swallow(String callback, Throwable t) {
        try {
            ctx.log().warn("lending: engine listener " + callback + " failed: " + t, t);
        } catch (Throwable ignored) {
            // Nothing may reach the engine thread.
        }
    }

    private static String name(LendingState state) {
        return state == null ? null : state.name();
    }

    /** {@code map("k", v, "k2", v2)}: an insertion-ordered map. */
    static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return m;
    }
}
