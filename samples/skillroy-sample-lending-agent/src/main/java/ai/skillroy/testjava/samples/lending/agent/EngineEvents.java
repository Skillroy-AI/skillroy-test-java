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

import java.util.ArrayList;
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
 * {@code loan.posted {loanId,total}}, {@code slip.printed {loanId}}. {@code input.acked {source, inputId}} (never the
 * value) is published by the call that posted the input, on the calling thread, when the engine took the input within
 * the call, so that it belongs to the call; when the engine takes it after the call returned, this listener publishes
 * it from the engine thread with the originating call's correlation id (phase8-contracts.md 7.4: exactly one
 * acknowledgement per input).
 *
 * <p>The pending table ({@link #expectAck}): every posted input, by identity, with its {@code inputId}, source, the
 * desk's prompt line when it was posted, the call's correlation id and when it was posted; kept after the call
 * returned so that a late acknowledgement can still be published, at most {@value #MAX_PENDING} entries (the oldest
 * dropped when a new one is added to a full table), every entry cleared by {@link #deactivate()}.
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
    static final String SLIP_PRINTED = "slip.printed";
    static final String PROMPT_ANSWERED = "prompt.answered";

    /** The most inputs the pending table keeps (phase8-contracts.md 7.4). */
    static final int MAX_PENDING = 256;

    /** One posted input waiting for the engine to take it (the pending table's entry). */
    static final class AckWait {
        final String inputId;
        final String source;
        /** The desk's prompt line when the input was posted (the question the call addressed). */
        final String promptLine;
        /** The posting call's correlation id, for a late acknowledgement; may be null. */
        final String correlationId;
        final long createdNanos;
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile boolean acked;
        /** The desk's prompt line when the engine took the input, or null before. */
        private volatile String takenPromptLine;
        /** True once the posting call returned without the acknowledgement; guarded by the pending table. */
        private boolean returned;

        AckWait(String inputId, String source, String promptLine, String correlationId) {
            this.inputId = inputId;
            this.source = source;
            this.promptLine = promptLine;
            this.correlationId = correlationId;
            this.createdNanos = System.nanoTime();
        }

        /** Waits up to {@code timeoutMs}; true if the engine accepted the input. */
        boolean await(long timeoutMs) throws InterruptedException {
            done.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
            return acked;
        }

        boolean acked() {
            return acked;
        }

        /** The prompt line when the engine took the input; null when it has not (yet). */
        String takenPromptLine() {
            return takenPromptLine;
        }

        void complete(boolean accepted) {
            acked = accepted;
            done.countDown();
        }
    }

    /** A pending table key: the input by identity (the engine hands the very same object to inputAccepted). */
    private static final class Key {
        private final LendingInput input;

        Key(LendingInput input) {
            this.input = input;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key && ((Key) o).input == input;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(input);
        }
    }

    private final AgentContext ctx;
    private final Engine engine;
    private volatile boolean active = true;
    /** Posted inputs by identity, oldest first; at most {@link #MAX_PENDING}. Guarded by itself. */
    private final LinkedHashMap<Key, AckWait> pending = new LinkedHashMap<Key, AckWait>();
    private final Object requestedLock = new Object();
    private long requestedCount;
    /** The seq of the last {@code input.requested} and its prompt line; guarded by {@link #requestedLock}. */
    private long lastRequestedSeq;
    private String lastRequestedLine;
    /** The last prompt shown {id, text, seq}, or null; guarded by {@link #requestedLock}. */
    private Map<String, Object> lastPrompt;
    private long lastPromptSeq;

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

    /**
     * Registers an input before it is offered, so that its acknowledgement cannot be missed. A full table drops its
     * oldest entry (released as "not acknowledged").
     *
     * @param inputId the input's id (phase8-contracts.md 7.4)
     * @param promptLine the desk's prompt line now, the question the input addresses
     * @param correlationId the posting call's correlation id, or null
     */
    AckWait expectAck(LendingInput input, String inputId, String promptLine, String correlationId) {
        AckWait wait = new AckWait(inputId, input.source(), promptLine, correlationId);
        if (!active) {
            wait.complete(false);
            return wait;
        }
        AckWait dropped = null;
        synchronized (pending) {
            if (pending.size() >= MAX_PENDING) {
                Map.Entry<Key, AckWait> oldest = pending.entrySet().iterator().next();
                dropped = oldest.getValue();
                pending.remove(oldest.getKey());
            }
            pending.put(new Key(input), wait);
        }
        if (dropped != null) {
            dropped.complete(false);
        }
        return wait;
    }

    /** Drops the entry of an input that was refused before it was handed over. */
    void forget(LendingInput input) {
        synchronized (pending) {
            pending.remove(new Key(input));
        }
    }

    /**
     * The posting call returns (phase8-contracts.md 7.4): true when the engine took the input within the call (the
     * call publishes {@code input.acked} itself, on its own thread); otherwise the entry stays in the table, marked
     * so that {@link #inputAccepted} publishes the acknowledgement later, and the answer is false. Atomic with
     * {@link #inputAccepted}, so that exactly one acknowledgement is published per input.
     */
    boolean callReturned(LendingInput input, AckWait wait) {
        synchronized (pending) {
            if (wait.acked()) {
                return true;
            }
            if (pending.get(new Key(input)) == wait) {
                wait.returned = true;
            }
            return false;
        }
    }

    /** The number of entries in the pending table (tests). */
    int pendingCount() {
        synchronized (pending) {
            return pending.size();
        }
    }

    /** A fresh input id, {@code lending-<n>}, unique within the module's life in the agent. */
    static String mintInputId() {
        return "lending-" + INPUT_IDS.incrementAndGet();
    }

    private static final java.util.concurrent.atomic.AtomicLong INPUT_IDS
            = new java.util.concurrent.atomic.AtomicLong();

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
     * Waits until the engine has called {@code inputRequested} more than {@code seenRequested} times in total, or has
     * shown a prompt whose {@code prompt.waiting} event is later than {@code seenPromptSeq}, or the timeout passes
     * (phase8-contracts.md 7.5: a composite's bounded wait for the desk's next question). Not called on the engine
     * thread.
     */
    void awaitChange(long seenRequested, long seenPromptSeq, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        synchronized (requestedLock) {
            while (requestedCount <= seenRequested && lastPromptSeq <= seenPromptSeq) {
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) {
                    return;
                }
                requestedLock.wait(remaining);
            }
        }
    }

    /** The last prompt the desk showed, {@code {id, text, seq}} ({@code seq} of its event), or null. */
    Map<String, Object> lastPrompt() {
        synchronized (requestedLock) {
            return lastPrompt == null ? null : new LinkedHashMap<String, Object>(lastPrompt);
        }
    }

    /** The seq of the last {@code prompt.waiting} this listener published, 0 before the first. */
    long lastPromptSeq() {
        synchronized (requestedLock) {
            return lastPromptSeq;
        }
    }

    /** The seq of the last {@code input.requested} this listener published, 0 before the first. */
    long lastRequestedSeq() {
        synchronized (requestedLock) {
            return lastRequestedSeq;
        }
    }

    /** The prompt line of the last {@code input.requested}, or null. */
    String lastRequestedLine() {
        synchronized (requestedLock) {
            return lastRequestedLine;
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
            }
        } catch (Throwable t) {
            swallow("stateChanged", t);
        }
    }

    @Override
    public void inputRequested(LendingState state, String prompt) {
        long seq = 0L;
        try {
            if (active) {
                seq = publish(Topics.INPUT_REQUESTED, map("state", name(state), "prompt", prompt, "validInputs",
                        new ArrayList<String>(engine.status().validInputs())));
            }
        } catch (Throwable t) {
            swallow("inputRequested", t);
        } finally {
            try {
                synchronized (requestedLock) {
                    requestedCount++;
                    if (seq > 0) {
                        lastRequestedSeq = seq;
                        lastRequestedLine = prompt;
                    }
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
            boolean late;
            synchronized (pending) {
                wait = pending.remove(new Key(input));
                late = wait != null && wait.returned;
                if (wait != null) {
                    // The engine published its status before this callback: the question the input answers.
                    wait.takenPromptLine = engine.status().prompt();
                }
            }
            if (wait != null) {
                wait.complete(active);
                if (late && active) {
                    // phase8-contracts.md 7.4: taken after its call returned; published from the seam, never blocks.
                    ctx.events().publish(Topics.INPUT_ACKED, SOURCE, map("source", wait.source, "inputId",
                            wait.inputId), wait.correlationId);
                }
            }
        } catch (Throwable t) {
            swallow("inputAccepted", t);
        }
    }

    @Override
    public void promptShown(String id, String text, List<String> buttons) {
        try {
            if (active) {
                long seq = publish(Topics.PROMPT_WAITING, map("id", id, "text", text, "buttons",
                        buttons == null ? new ArrayList<String>() : new ArrayList<String>(buttons)));
                synchronized (requestedLock) {
                    if (seq > 0) {
                        lastPromptSeq = seq;
                        lastPrompt = map("id", id, "text", text, "seq", Long.valueOf(seq));
                    }
                    requestedLock.notifyAll();
                }
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

    private long publish(String topic, Map<String, Object> payload) {
        return ctx.events().publish(topic, SOURCE, payload);
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
