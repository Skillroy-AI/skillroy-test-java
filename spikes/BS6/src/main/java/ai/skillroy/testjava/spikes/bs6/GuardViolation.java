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

package ai.skillroy.testjava.spikes.bs6;

/** A statement the guard refuses: the reason code of section 5.3 and the fragment that violates it. */
public final class GuardViolation extends Exception {

    /**
     * The reason codes of section 5.3 in rule order, plus {@link #UNSUPPORTED}, which the contract lacks: a SELECT
     * construct outside the guard's grammar (CONNECT BY, PIVOT, LATERAL, APPLY, window functions' frames, table
     * functions, recursive CTEs and the like). RESULT.md proposes adding it.
     */
    public enum Reason {
        PARSE, NOT_SELECT, DML, DDL, PROCEDURE, SET_OPERATION, LOCKING, TABLE, COLUMN, STAR, PERSONAL, FUNCTION,
        LITERAL, ORDER, LIMIT, SIZE, COMMENT, UNSUPPORTED
    }

    private final Reason reason;
    private final String fragment;

    public GuardViolation(Reason reason, String fragment) {
        super(reason + ": " + fragment);
        this.reason = reason;
        this.fragment = fragment;
    }

    public Reason reason() {
        return reason;
    }

    /** The violating fragment and why, never the whole statement. */
    public String fragment() {
        return fragment;
    }
}
