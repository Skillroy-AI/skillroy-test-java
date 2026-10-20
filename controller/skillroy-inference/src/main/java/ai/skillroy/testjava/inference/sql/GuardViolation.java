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

package ai.skillroy.testjava.inference.sql;

import java.util.Objects;

/**
 * A statement {@link SqlGuard} refuses: the reason code of phase5-contracts.md 5.3 and the fragment that violates it
 * (never the whole statement). Lifted from spike BS6.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class GuardViolation extends Exception {

    private static final long serialVersionUID = 1L;

    /** The reason codes of phase5-contracts.md 5.3, by rule. */
    public enum Reason {
        /** Rule 1: the text does not parse as exactly one statement in the dialect, or was only captured. */
        PARSE,
        /** Rule 2: a statement other than a SELECT. */
        NOT_SELECT,
        /** Rule 2: a statement or a CTE body that writes, or {@code INTO} of any kind. */
        DML,
        /** Rule 2: a statement that changes the schema. */
        DDL,
        /**
         * Rule 2: every CALL or EXEC (no data channel runs a procedure call, gap 119, phase8-contracts.md 4.5), and a
         * procedural block.
         */
        PROCEDURE,
        /** Rule 2: UNION, INTERSECT, EXCEPT, MINUS. */
        SET_OPERATION,
        /** Rule 2: table hints, isolation clauses, every FOR clause, LOCK statements. */
        LOCKING,
        /** Rule 3: a table that is not on the allow-list, qualified, over a database link, or shadowed by a CTE. */
        TABLE,
        /** Rule 4: a column reference that does not resolve to an allow-listed column, or is ambiguous. */
        COLUMN,
        /** Rule 4: {@code SELECT *}, {@code t.*}, {@code COUNT(t.*)}. */
        STAR,
        /** Rule 4: a personal column (or a value derived from one) in the select list. */
        PERSONAL,
        /** Rule 5: a function outside the built-in, dialect and allow-list sets, or an analytic form. */
        FUNCTION,
        /** Rule 6: a literal form whose meaning depends on server settings, a long string, a named parameter. */
        LITERAL,
        /** Rule 7: an ORDER BY the guard cannot complete or resolve, or one inside a subquery. */
        ORDER,
        /** Rule 8: a row limit that is a parameter, zero, a percentage, with ties, an offset, ROWNUM, two forms. */
        LIMIT,
        /** Rules 1 and 9: nesting, depth, tables, select-list width or text size beyond the bounds. */
        SIZE,
        /** Rule 1: a hint, executable, nested or unterminated comment. */
        COMMENT,
        /** Rule 2: a construct outside the guard's grammar (CONNECT BY, PIVOT, LATERAL, APPLY, DISTINCT ON, …). */
        UNSUPPORTED,
        /** Rule 10: the rewritten text (what would run) failed the output check; a guard defect, failing closed. */
        REWRITE
    }

    private final Reason reason;
    private final String fragment;

    /**
     * @param reason the reason code
     * @param fragment the violating fragment and why, never the whole statement
     */
    public GuardViolation(Reason reason, String fragment) {
        super(reason + ": " + fragment);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.fragment = fragment;
    }

    /** The reason code. */
    public Reason reason() {
        return reason;
    }

    /** The violating fragment and why, never the whole statement. */
    public String fragment() {
        return fragment;
    }
}
