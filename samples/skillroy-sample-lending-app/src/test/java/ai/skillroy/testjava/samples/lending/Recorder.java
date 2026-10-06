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

package ai.skillroy.testjava.samples.lending;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Records every engine callback in order, so tests can wait for an event that happened after a mark without
 * sleeping (polling with a deadline on a monitor).
 */
final class Recorder implements EngineListener {

    enum Kind { STATE, REQUESTED, ACCEPTED, PROMPT_SHOWN, PROMPT_ANSWERED, LOAN_POSTED, SLIP_PRINTED }

    /** One recorded callback. */
    static final class Ev {
        final int index;
        final Kind kind;
        final LendingState from;
        final LendingState state;
        final String prompt;
        final LendingInput input;
        final String id;
        final List<String> buttons;
        final String choice;
        final long loanId;
        final List<String> lines;
        final String thread;

        Ev(int index, Kind kind, LendingState from, LendingState state, String prompt, LendingInput input, String id,
           List<String> buttons, String choice, long loanId, List<String> lines) {
            this.index = index;
            this.kind = kind;
            this.from = from;
            this.state = state;
            this.prompt = prompt;
            this.input = input;
            this.id = id;
            this.buttons = buttons;
            this.choice = choice;
            this.loanId = loanId;
            this.lines = lines;
            this.thread = Thread.currentThread().getName();
        }

        @Override
        public String toString() {
            return index + ":" + kind + (from != null ? " from=" + from : "") + (state != null ? " state=" + state : "")
                + (prompt != null ? " prompt=" + prompt : "") + (input != null ? " input=" + input : "")
                + (id != null ? " id=" + id : "") + (choice != null ? " choice=" + choice : "")
                + (loanId != 0 ? " loan=" + loanId : "");
        }
    }

    static final long TIMEOUT_MS = 30_000;

    private final List<Ev> events = new ArrayList<Ev>();

    private synchronized void add(Kind kind, LendingState from, LendingState state, String prompt, LendingInput input,
                                  String id, List<String> buttons, String choice, long loanId, List<String> lines) {
        events.add(new Ev(events.size(), kind, from, state, prompt, input, id, buttons, choice, loanId, lines));
        notifyAll();
    }

    synchronized int size() {
        return events.size();
    }

    synchronized List<Ev> events() {
        return Collections.unmodifiableList(new ArrayList<Ev>(events));
    }

    /** @return the first event at index >= from matching the predicate; fails after {@link #TIMEOUT_MS} */
    synchronized Ev await(int from, Predicate<Ev> match) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        int i = from;
        while (true) {
            for (; i < events.size(); i++) {
                if (match.test(events.get(i))) {
                    return events.get(i);
                }
            }
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                fail("no matching engine event after index " + from + "; events: " + events);
            }
            try {
                wait(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
    }

    /** @return the events from index {@code from} on */
    synchronized List<Ev> since(int from) {
        return Collections.unmodifiableList(new ArrayList<Ev>(events.subList(Math.min(from, events.size()),
            events.size())));
    }

    @Override
    public void stateChanged(LendingState from, LendingState to) {
        add(Kind.STATE, from, to, null, null, null, null, null, 0, null);
    }

    @Override
    public void inputRequested(LendingState state, String prompt) {
        add(Kind.REQUESTED, null, state, prompt, null, null, null, null, 0, null);
    }

    @Override
    public void inputAccepted(LendingInput input) {
        add(Kind.ACCEPTED, null, null, null, input, null, null, null, 0, null);
    }

    @Override
    public void promptShown(String id, String text, List<String> buttons) {
        add(Kind.PROMPT_SHOWN, null, null, text, null, id, buttons, null, 0, null);
    }

    @Override
    public void promptAnswered(String id, String choice) {
        add(Kind.PROMPT_ANSWERED, null, null, null, null, id, null, choice, 0, null);
    }

    @Override
    public void loanPosted(long loanId) {
        add(Kind.LOAN_POSTED, null, null, null, null, null, null, null, loanId, null);
    }

    @Override
    public void slipPrinted(long loanId, List<String> lines) {
        add(Kind.SLIP_PRINTED, null, null, null, null, null, null, null, loanId, lines);
    }
}
