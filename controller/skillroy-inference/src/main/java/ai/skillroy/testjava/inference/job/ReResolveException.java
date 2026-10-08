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

package ai.skillroy.testjava.inference.job;

/**
 * A {@code re-resolve} job could not start, or its outputs could not be written (phase5-contracts.md 8.2). Before
 * anything ran ({@link #report()} null): no job budget, gateway {@code none}, a gateway that cannot be opened (no key),
 * two plans with one scenario id; the CLI exits 64 for these. After the job ran ({@link #report()} set): the report or
 * the ledger could not be written to the output directory, while the bindings files were written as the report says;
 * the CLI exits 70. The message is one line and never holds a secret.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class ReResolveException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient ReResolveReport report;

    /**
     * @param message one line, no secret
     * @param report the job's report when the job ran, or null when it did not start
     */
    public ReResolveException(String message, ReResolveReport report) {
        super(message);
        this.report = report;
    }

    /** The report of a job that ran but whose outputs could not be written; null when the job did not start. */
    public ReResolveReport report() {
        return report;
    }

    /** True when the job did not start (a usage or settings problem: exit 64). */
    public boolean beforeStart() {
        return report == null;
    }
}
