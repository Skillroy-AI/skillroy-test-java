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

package ai.skillroy.testjava.scenario.pack;

import ai.skillroy.testjava.scenario.UiSettings;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;
import ai.skillroy.testjava.scenario.web.WebSettings;

import java.util.Set;

/**
 * What a verb may use while it compiles, besides its arguments.
 *
 * <p>ctx.addCleanup(call): a call that must run when the scenario ends, however it ends (for example re-enabling a
 * timer a verb switched off). Cleanup calls run in reverse registration order; their failures are logged in
 * run.jsonl and never change the verdict. ctx.sourceLine() and ctx.pack() are also available.
 *
 * <p>Implemented by the compiler; valid only during the {@link StepLibrary#compile} call it was passed to, and only
 * on that thread.
 */
public interface CompileContext {

    /** Registers a call for the plan's {@code cleanup} list. */
    void addCleanup(PlannedCall call);

    /** 1-based line of the step in the scenario file, or 0 when the parser could not tell. */
    int sourceLine();

    /** The pack the scenario is compiled with. */
    DomainPack pack();

    /**
     * The scenario's step timeout in milliseconds ({@code timeouts.step}, default 10000), for verbs that need a
     * default wait such as an {@code expectPrompt} without {@code within}.
     */
    default long stepTimeoutMs() {
        return 10_000L;
    }

    /**
     * Marks {@code call} (an element of the list this verb returns) as one that answers or dismisses an application
     * prompt, for example a call to the pack's prompt-answer primitive. A prompt event that arrives before such a call
     * in the same or the next step is expected and is not handed to the {@link PromptPolicy}. Calls to
     * {@code sync.await} on a prompt topic and calls to {@code swing.dismiss} are recognised without marking; a call
     * with {@link Sync#EVENT} is not, even when its {@link AwaitSpec} names a prompt topic. The call is matched by
     * identity.
     */
    default void markHandlesPrompt(PlannedCall call) {
    }

    /**
     * The scenario's {@code ui} settings (amendment A29), {@link UiSettings#DEFAULT} when it has no {@code ui} key.
     * The base {@code click} verb applies them; a pack verb that clicks through {@code swing.click} may honour them
     * too, so that a scenario's choice of click fidelity covers its own verbs.
     */
    default UiSettings ui() {
        return UiSettings.DEFAULT;
    }

    /**
     * The scenario's {@code web} settings (phase4-contracts.md 7.2), {@link WebSettings#DEFAULT} when it has no
     * {@code web} key. The base web verbs take their default browser from it ({@code web.browser}, compiled into the
     * primitive's {@code by} parameter when the step names no {@code browser} of its own); a pack verb that drives a
     * web view may do the same.
     */
    default WebSettings web() {
        return WebSettings.DEFAULT;
    }

    /**
     * What the scenario declares for a slot, or null when there is no such slot (phase2-contracts.md 6.1). Never a
     * value. Lets a verb reject, at compile time, an argument whose slot lacks what the verb needs (a sign-on whose
     * slot has no {@code credential}).
     *
     * @param name the slot name, without {@code $}
     */
    default SlotInfo slot(String name) {
        return null;
    }

    /**
     * A slot as the scenario declares it.
     *
     * @param name the slot name
     * @param kind {@code fixture}, {@code query} or {@code capture}
     * @param queryKind the query kind of a query slot, else null
     * @param credential the slot's credential declaration, or null
     * @param captureFields the field names of a captured slot; empty otherwise
     */
    record SlotInfo(String name, String kind, String queryKind, CredentialSpec credential,
                    Set<String> captureFields) {

        /** Copies the field set; null is empty. */
        public SlotInfo {
            captureFields = captureFields == null ? Set.of() : Set.copyOf(captureFields);
        }
    }
}
