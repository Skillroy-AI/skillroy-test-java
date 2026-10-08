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

package ai.skillroy.testjava.inference.planner;

import ai.skillroy.testjava.scenario.resolve.CostLedger;

import java.math.BigDecimal;
import java.util.List;

/**
 * The planner wrote no scenario (phase5-contracts.md 10.2): a usage problem before it ran ({@link #EXIT_USAGE}: no
 * {@code claude} executable, the id exists, no MCP configuration, a bad request), or the planner failed, or its repairs
 * ran out ({@link #EXIT_FAILED}). It carries what the planner's calls cost (their ledger entries) and the last
 * document it rendered, for the person to look at. The message is one line and never holds a secret.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class AuthoringException extends Exception {

    /** A usage problem: the command's exit code 64. */
    public static final int EXIT_USAGE = 64;
    /** The planner failed or its repairs ran out: the command's exit code 1. */
    public static final int EXIT_FAILED = 1;

    private static final long serialVersionUID = 1L;

    private final int exitCode;
    private final String lastYaml;
    private final transient List<CostLedger.Entry> ledger;

    /**
     * @param exitCode {@link #EXIT_USAGE} or {@link #EXIT_FAILED}
     * @param message one line, no secret
     * @param lastYaml the last scenario the planner returned, rendered, or null
     * @param ledger the planner's calls so far; null is none
     */
    public AuthoringException(int exitCode, String message, String lastYaml, List<CostLedger.Entry> ledger) {
        super(message);
        this.exitCode = exitCode;
        this.lastYaml = lastYaml;
        this.ledger = ledger == null ? List.of() : List.copyOf(ledger);
    }

    /** A usage problem before the planner ran. */
    static AuthoringException usage(String message) {
        return new AuthoringException(EXIT_USAGE, message, null, List.of());
    }

    /** The command's exit code: 64 or 1. */
    public int exitCode() {
        return exitCode;
    }

    /** The last scenario the planner returned, as YAML, or null. */
    public String lastYaml() {
        return lastYaml;
    }

    /** The planner's calls (provider {@code claude-code}, purpose {@code plan}). */
    public List<CostLedger.Entry> ledger() {
        return ledger;
    }

    /** What the planner's calls cost. */
    public BigDecimal costUsd() {
        return Planner.cost(ledger);
    }
}
