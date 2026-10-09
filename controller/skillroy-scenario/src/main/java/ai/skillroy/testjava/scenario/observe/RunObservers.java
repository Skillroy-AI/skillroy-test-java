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

package ai.skillroy.testjava.scenario.observe;

import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.StepResult;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.SettingsException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.regex.Pattern;

/**
 * Helpers around {@link RunObserver} (phase6-contracts.md 10.2): {@link #open} finds the provider of the settings'
 * exporter through {@code ServiceLoader}, {@link #safe} keeps an observer from changing a verdict, and
 * {@link #traceparent} validates a W3C {@code traceparent} as the MCP server receives one.
 *
 * <p>Stateless; safe from any thread.
 */
public final class RunObservers {

    private static final System.Logger LOG = System.getLogger(RunObservers.class.getName());

    /** {@code 00-<32 hex trace id>-<16 hex span id>-<2 hex flags>}, lower case (W3C Trace Context, version 00). */
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");

    private RunObservers() {
    }

    /**
     * The observer the settings ask for: {@link RunObserver#NONE} for {@code none} (no provider is looked for, nothing
     * loads), else the one the provider of the settings' exporter creates, found through {@code ServiceLoader} on
     * {@code loader}.
     *
     * @param loader the class loader to look in; null means the thread's context class loader
     * @throws SettingsException when no provider serves the exporter (the {@code skillroy-telemetry} module is not on
     *     the class path) or the provider refuses the settings
     */
    public static RunObserver open(TelemetrySettings settings, ClassLoader loader) throws SettingsException {
        Objects.requireNonNull(settings, "settings");
        if (!settings.enabled()) {
            return RunObserver.NONE;
        }
        ClassLoader effective = loader != null ? loader : Thread.currentThread().getContextClassLoader();
        List<String> found = new ArrayList<>();
        try {
            for (RunObserverProvider provider : ServiceLoader.load(RunObserverProvider.class, effective)) {
                String exporter = provider.exporter();
                found.add(exporter);
                if (settings.exporter().equals(exporter)) {
                    RunObserver observer = provider.create(settings);
                    return observer == null ? RunObserver.NONE : observer;
                }
            }
        } catch (ServiceConfigurationError e) {
            throw new SettingsException("the telemetry exporter " + settings.exporter() + " cannot be loaded ("
                    + e.getClass().getSimpleName() + ")");
        }
        throw new SettingsException("no telemetry exporter " + settings.exporter() + " on the class path"
                + (found.isEmpty() ? " (the skillroy-telemetry module is not there)" : "; found " + found));
    }

    /**
     * An observer that never throws: a failure of {@code observer} is logged once (its class, never its message) and
     * the observer is replaced by {@link RunObserver#NONE} for the rest of the wrapper's life (one run). {@code NONE}
     * itself is returned as it is, and so is an observer that is already such a wrapper.
     */
    public static RunObserver safe(RunObserver observer) {
        if (observer == null || observer == RunObserver.NONE) {
            return RunObserver.NONE;
        }
        return observer instanceof Safe ? observer : new Safe(observer);
    }

    /** True when {@code observer} is {@link RunObserver#NONE} (or null): callers then build no record at all. */
    public static boolean isNone(RunObserver observer) {
        return observer == null || observer == RunObserver.NONE;
    }

    /**
     * {@code value} when it is a valid W3C {@code traceparent} of version {@code 00} with a trace id and a span id that
     * are not all zeros (lower-cased and stripped first), else null: a malformed one is dropped (phase6-contracts.md
     * 10.2).
     */
    public static String traceparent(Object value) {
        if (!(value instanceof String text) || text.length() > 64) {
            return null;
        }
        String normal = text.strip().toLowerCase(Locale.ROOT);
        var m = TRACEPARENT.matcher(normal);
        if (!m.matches() || m.group(1).chars().allMatch(c -> c == '0') || m.group(2).chars().allMatch(c -> c == '0')) {
            return null;
        }
        return normal;
    }

    /** The never-throwing wrapper of {@link #safe}. */
    private static final class Safe implements RunObserver {

        private volatile RunObserver delegate;

        Safe(RunObserver delegate) {
            this.delegate = delegate;
        }

        private void failed(Throwable e) {
            RunObserver was = delegate;
            delegate = RunObserver.NONE;
            if (was != RunObserver.NONE) {
                LOG.log(System.Logger.Level.WARNING, "the telemetry observer failed (" + e.getClass().getName()
                        + "); telemetry is off for the rest of the run");
            }
        }

        @Override
        public void runStarted(RunStart run) {
            try {
                delegate.runStarted(run);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void scenarioStarted(ScenarioStart scenario) {
            try {
                delegate.scenarioStarted(scenario);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void scenarioEnded(RunResult result) {
            try {
                delegate.scenarioEnded(result);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void bindEvent(String type, Map<String, Object> fields) {
            try {
                delegate.bindEvent(type, fields);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void scenarioRecord(String type, Map<String, Object> fields) {
            try {
                delegate.scenarioRecord(type, fields);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void stepStarted(StepStart step) {
            try {
                delegate.stepStarted(step);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void stepEnded(StepResult result) {
            try {
                delegate.stepEnded(result);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void callStarted(CallStart call) {
            try {
                delegate.callStarted(call);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void callEnded(CallEnd call) {
            try {
                delegate.callEnded(call);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void await(AwaitRecord sync) {
            try {
                delegate.await(sync);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void modelCall(CostLedger.Entry entry) {
            try {
                delegate.modelCall(entry);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void sessionStep(StepOutcome outcome) {
            try {
                delegate.sessionStep(outcome);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void sessionTurn(TurnRecord turn) {
            try {
                delegate.sessionTurn(turn);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void toolCall(ToolCallRecord record) {
            try {
                delegate.toolCall(record);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public String currentTraceparent() {
            try {
                return traceparent(delegate.currentTraceparent());
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
                return null;
            }
        }

        @Override
        public void flush() {
            try {
                delegate.flush();
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        /** Closes the wrapped observer only through its owner: a run's wrapper never ends the invocation's trace. */
        @Override
        public void close() {
            flush();
        }

        @Override
        public void sessionStarted(SessionStart session) {
            try {
                delegate.sessionStarted(session);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void sessionStepStarted(StepStart step) {
            try {
                delegate.sessionStepStarted(step);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void sessionRecord(String type) {
            try {
                delegate.sessionRecord(type);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void sessionEnded(String reason) {
            try {
                delegate.sessionEnded(reason);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void turnStarted(int index) {
            try {
                delegate.turnStarted(index);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public void toolCallStarted(String tool, String traceparent) {
            try {
                delegate.toolCallStarted(tool, traceparent);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
            }
        }

        @Override
        public String traceId() {
            try {
                String id = delegate.traceId();
                return id != null && id.matches("[0-9a-f]{32}") ? id : null;
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                failed(e);
                return null;
            }
        }

        @Override
        public String toString() {
            return "safe(" + delegate + ")";
        }
    }
}
