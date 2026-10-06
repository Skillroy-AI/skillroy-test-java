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

import java.util.List;

/**
 * Observer of the desk engine. Every method has an empty default, so a listener overrides only what it needs.
 *
 * <p><b>Threading.</b> Every callback runs on the engine thread ({@code LendingEngine}), synchronously, while the
 * engine waits for it: a callback must return quickly, must not block and must not wait for the engine (posting to
 * the {@link Inbox} is fine). Exceptions thrown by a callback are logged and otherwise ignored.
 *
 * <p><b>Order</b> for one consumed input: {@code inputAccepted}, then whatever the input caused
 * ({@code promptShown}/{@code promptAnswered}, {@code loanPosted}, {@code slipPrinted}, {@code stateChanged}), then
 * {@code inputRequested} when the engine waits again. When a callback runs, the engine's published status
 * ({@link Engine#status()}) and loan snapshot already reflect the change it reports.
 *
 * <p>Register listeners with {@link Engine#addListener} before the engine starts, or while it is blocked waiting
 * for input (see that method).
 */
public interface EngineListener {

    /** The state changed. Not called when an input leaves the state as it was. */
    default void stateChanged(LendingState from, LendingState to) {
    }

    /**
     * The engine started waiting for input: after start-up, after every consumed input and after locking. Not called
     * again after an input was dropped, because the engine keeps waiting for the same thing.
     *
     * @param state the current state
     * @param prompt the prompt line, see {@link LendingState}
     */
    default void inputRequested(LendingState state, String prompt) {
    }

    /** An input was taken from the inbox and is valid in the current state; called before it is processed. */
    default void inputAccepted(LendingInput input) {
    }

    /** A modal prompt is open; the engine is blocked until it is answered. {@link PromptService#current()} is set. */
    default void promptShown(String id, String text, List<String> buttons) {
    }

    /** The modal prompt was answered with {@code choice}, one of its buttons. */
    default void promptAnswered(String id, String choice) {
    }

    /** A loan was written to the database (committed). */
    default void loanPosted(long loanId) {
    }

    /** The slip of a posted loan was printed. */
    default void slipPrinted(long loanId, List<String> lines) {
    }
}
