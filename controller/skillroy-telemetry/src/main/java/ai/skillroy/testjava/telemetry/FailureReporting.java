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


package ai.skillroy.testjava.telemetry;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.io.PrintStream;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * How an export failure is reported (phase6-contracts.md 2.4, 10.4): the first failed batch once on stderr, in the
 * module's own words (the endpoint's host and port, never a header), every failed batch counted for the summary line;
 * and the {@code io.opentelemetry} {@code java.util.logging} loggers at {@code WARNING} with a handler of the module's
 * own that keeps their records (whose text the SDK writes) off the console. Never fails or slows a run: the exporter
 * runs on its own threads and its time is bounded by its timeout.
 */
final class FailureReporting {

    /** The SDK's logger name: every logger of the SDK is below it. */
    static final String SDK_LOGGER = "io.opentelemetry";

    /** Held so that the configuration is not lost with a collected logger. */
    private static final Logger SDK = Logger.getLogger(SDK_LOGGER);
    private static final AtomicBoolean CONFIGURED = new AtomicBoolean();

    private FailureReporting() {
    }

    /** Sets the {@code io.opentelemetry} loggers to WARNING with the module's quiet handler (once per JVM). */
    static void configureLogging() {
        if (!CONFIGURED.compareAndSet(false, true)) {
            return;
        }
        SDK.setLevel(Level.WARNING);
        SDK.setUseParentHandlers(false);
        SDK.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                // Counted by the exporter's wrapper; the SDK's text never reaches the console.
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    /**
     * A span exporter that counts the batches its delegate could not deliver and reports the first one on
     * {@code err}.
     */
    static final class Counting implements SpanExporter {

        private final SpanExporter delegate;
        private final TelemetryObserver.Dropped dropped;
        private final String where;
        private final PrintStream err;
        private final AtomicBoolean reported = new AtomicBoolean();

        /**
         * @param where {@code host:port} of the endpoint, for the one line
         */
        Counting(SpanExporter delegate, TelemetryObserver.Dropped dropped, String where, PrintStream err) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.dropped = dropped;
            this.where = where;
            this.err = err;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            CompletableResultCode result;
            try {
                result = delegate.export(spans);
            } catch (RuntimeException e) {
                failed();
                return CompletableResultCode.ofFailure();
            }
            result.whenComplete(() -> {
                if (!result.isSuccess()) {
                    failed();
                }
            });
            return result;
        }

        private void failed() {
            dropped.add();
            if (reported.compareAndSet(false, true) && err != null) {
                err.println("skillroy: telemetry: the export to " + where + " failed; the spans of this batch are"
                        + " dropped (further failures are counted in the summary)");
                err.flush();
            }
        }

        @Override
        public CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }
    }
}
