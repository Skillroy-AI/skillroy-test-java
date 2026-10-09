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

import ai.skillroy.testjava.scenario.hooks.HooksMode;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.web.WebSettings;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * How an {@link ExecutorSession} runs (phase5-contracts.md 12.2).
 *
 * <p>Immutable; safe to share between threads if the clock and the class loader are.
 *
 * @param outputDir where {@code journal.jsonl} is written; null keeps the journal in memory only
 * @param stepTimeout the step timeout of every call (a call's 0 timeout, an EVENT wait without its own); null means
 *     10 s
 * @param clock what durations are measured on and the journal's {@code startedAt} is read from; null means the system
 *     UTC clock
 * @param bind the credentials, the environment, the ledger and the clock of the session's resolutions, always run in
 *     {@code live} mode (another mode given here is replaced by {@code live}); null means
 *     {@link BindOptions#defaults()} in {@code live}
 * @param readOnly true refuses every mutating or destructive call before it is sent
 * @param recoverOnClose true calls the pack's {@code recover} when the session closes after a mutating call
 * @param web the web path policy, as a scenario's {@code web} key gives it; null means {@link WebSettings#DEFAULT} (and
 *     an export writes no {@code web} key)
 * @param ui the click fidelity, as a scenario's {@code ui} key gives it; null means {@link UiSettings#DEFAULT} (and an
 *     export writes no {@code ui} key)
 * @param webPathLoader the class loader through which {@code ServiceLoader<WebPath>} finds a controller-side web path;
 *     null means the thread's context class loader when the session opens
 * @param id the session id, {@code [a-z0-9-]+}; null generates one from the clock
 * @param hooks the hooks switch {@link ExecutorSession#open} installs the pack's hook catalogue under
 *     (phase6-contracts.md 5.3, 6.1, decision D129), as a run's step 0b does; null means {@link HooksMode#AUTO}
 * @param observer what the session tells an exporter (phase6-contracts.md 10.2; as built, Phase 6, TL: the session's
 *     observer travels here, as a run's does on {@code RunOptions}, decision D162 of the build); null means
 *     {@link RunObserver#NONE}. The session wraps it ({@code RunObservers.safe}) and never closes it. The twelfth
 *     component, after {@code hooks}
 */
public record SessionOptions(Path outputDir, Duration stepTimeout, Clock clock, BindOptions bind, boolean readOnly,
                             boolean recoverOnClose, WebSettings web, UiSettings ui, ClassLoader webPathLoader,
                             String id, HooksMode hooks, RunObserver observer) {

    /** The step timeout when none is given. */
    public static final Duration DEFAULT_STEP_TIMEOUT = Duration.ofSeconds(10);

    private static final Pattern ID = Pattern.compile("[a-z0-9-]+");

    /**
     * Fills defaults for null components and puts the bind options in {@code live} mode.
     *
     * @throws IllegalArgumentException if the step timeout is not positive or the id is not {@code [a-z0-9-]+}
     */
    public SessionOptions {
        stepTimeout = stepTimeout == null ? DEFAULT_STEP_TIMEOUT : stepTimeout;
        if (stepTimeout.isZero() || stepTimeout.isNegative()) {
            throw new IllegalArgumentException("stepTimeout must be positive: " + stepTimeout);
        }
        clock = clock == null ? Clock.systemUTC() : clock;
        bind = bind == null ? BindOptions.defaults() : bind;
        if (bind.mode() != BindMode.LIVE) {
            bind = bind.withMode(BindMode.LIVE);
        }
        if (id != null && !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("a session id must match [a-z0-9-]+, found \"" + id + "\"");
        }
        hooks = hooks == null ? HooksMode.AUTO : hooks;
        observer = observer == null ? RunObserver.NONE : observer;
    }

    /**
     * Options with the hooks switch {@link HooksMode#AUTO} and no observer (the Phase 5 shape).
     *
     * @throws IllegalArgumentException as the canonical constructor
     */
    public SessionOptions(Path outputDir, Duration stepTimeout, Clock clock, BindOptions bind, boolean readOnly,
                          boolean recoverOnClose, WebSettings web, UiSettings ui, ClassLoader webPathLoader,
                          String id) {
        this(outputDir, stepTimeout, clock, bind, readOnly, recoverOnClose, web, ui, webPathLoader, id,
                HooksMode.AUTO, RunObserver.NONE);
    }

    /** Options writing the journal into {@code outputDir}, with every other component's default. */
    public static SessionOptions defaults(Path outputDir) {
        return new SessionOptions(outputDir, null, null, null, false, false, null, null, null, null, HooksMode.AUTO,
                RunObserver.NONE);
    }

    /** A copy with another step timeout (null: 10 s). */
    public SessionOptions withStepTimeout(Duration timeout) {
        return new SessionOptions(outputDir, timeout, clock, bind, readOnly, recoverOnClose, web, ui, webPathLoader,
                id, hooks, observer);
    }

    /** A copy with another clock. */
    public SessionOptions withClock(Clock newClock) {
        return new SessionOptions(outputDir, stepTimeout, newClock, bind, readOnly, recoverOnClose, web, ui,
                webPathLoader, id, hooks, observer);
    }

    /** A copy with other bind options (put in {@code live} mode). */
    public SessionOptions withBind(BindOptions newBind) {
        return new SessionOptions(outputDir, stepTimeout, clock, newBind, readOnly, recoverOnClose, web, ui,
                webPathLoader, id, hooks, observer);
    }

    /** A copy that is or is not read-only. */
    public SessionOptions withReadOnly(boolean on) {
        return new SessionOptions(outputDir, stepTimeout, clock, bind, on, recoverOnClose, web, ui, webPathLoader,
                id, hooks, observer);
    }

    /** A copy that does or does not recover when it closes after a mutating call. */
    public SessionOptions withRecoverOnClose(boolean on) {
        return new SessionOptions(outputDir, stepTimeout, clock, bind, readOnly, on, web, ui, webPathLoader, id, hooks,
                observer);
    }

    /** A copy with other web settings (null: the default, and no {@code web} key in an export). */
    public SessionOptions withWeb(WebSettings settings) {
        return new SessionOptions(outputDir, stepTimeout, clock, bind, readOnly, recoverOnClose, settings, ui,
                webPathLoader, id, hooks, observer);
    }

    /** A copy with other UI settings (null: the default, and no {@code ui} key in an export). */
    public SessionOptions withUi(UiSettings settings) {
        return new SessionOptions(outputDir, stepTimeout, clock, bind, readOnly, recoverOnClose, web, settings,
                webPathLoader, id, hooks, observer);
    }

    /** A copy that finds web paths through {@code loader}. */
    public SessionOptions withWebPathLoader(ClassLoader loader) {
        return new SessionOptions(outputDir, stepTimeout, clock, bind, readOnly, recoverOnClose, web, ui, loader,
                id, hooks, observer);
    }

    /** A copy with this session id (null: generated). */
    public SessionOptions withId(String newId) {
        return new SessionOptions(outputDir, stepTimeout, clock, bind, readOnly, recoverOnClose, web, ui,
                webPathLoader, newId, hooks, observer);
    }

    /** A copy with this hooks switch (null: {@link HooksMode#AUTO}); phase6-contracts.md 6.1. */
    public SessionOptions withHooks(HooksMode mode) {
        return new SessionOptions(outputDir, stepTimeout, clock, bind, readOnly, recoverOnClose, web, ui,
                webPathLoader, id, mode, observer);
    }

    /** A copy with this observer (null: {@link RunObserver#NONE}); the session never closes it. */
    public SessionOptions withObserver(RunObserver newObserver) {
        return new SessionOptions(outputDir, stepTimeout, clock, bind, readOnly, recoverOnClose, web, ui,
                webPathLoader, id, hooks, newObserver);
    }

    /** The web settings that apply: {@link #web()}, or the default. */
    public WebSettings effectiveWeb() {
        return web != null ? web : WebSettings.DEFAULT;
    }

    /** The UI settings that apply: {@link #ui()}, or the default. */
    public UiSettings effectiveUi() {
        return ui != null ? ui : UiSettings.DEFAULT;
    }
}
