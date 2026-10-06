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

import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.resolve.ColumnKind;

import java.math.BigDecimal;

/**
 * The aggregates of stored-row checks, computed on the controller over the rows a check's statement returned
 * (phase3-contracts.md 3.2, 7, decision D40), so that they mean the same over either data channel and in every SQL
 * dialect: {@code sum} is the exact sum of the non-null cells, {@code min} and {@code max} compare the non-null cells
 * as numbers; null cells are ignored, as SQL aggregates ignore them, and the aggregate of no cells is null.
 *
 * <p>The cells arrive in the forms the data channels produce (decimals as plain strings, whole numbers as
 * {@code Long}) and already masked. A {@code DECIMAL} result is its plain decimal string; an {@code INTEGER} result is
 * a {@code Long} when it is whole and fits one, else its plain decimal string. Stateless; safe from any thread.
 */
final class Aggregates {

    /**
     * An aggregate's value, or why it has none.
     *
     * @param value the value in the form described above; null over no cells, or when {@code problem} is set
     * @param problem why the aggregate cannot be computed (a cell that is not a number: a masked column cannot be
     *     aggregated), or null
     */
    record Result(Object value, String problem) {
    }

    private Aggregates() {
    }

    /**
     * Computes {@code fn} over column {@code column} of every row.
     *
     * @param fn {@code sum}, {@code min} or {@code max}
     * @param kind the column's kind: how the result is written
     * @param name the column's name, for the problem text
     */
    static Result compute(String fn, ColumnKind kind, Rows rows, int column, String name) {
        BigDecimal acc = null;
        for (int r = 0; r < rows.rowCount(); r++) {
            Object cell = rows.get(r, column);
            if (cell == null) {
                continue;
            }
            BigDecimal d = Values.decimal(cell);
            if (d == null) {
                return new Result(null, "row " + (r + 1) + " holds a value in column " + name + " that is not a"
                        + " number");
            }
            if (acc == null) {
                acc = d;
            } else {
                acc = switch (fn) {
                    case "sum" -> acc.add(d);
                    case "min" -> d.compareTo(acc) < 0 ? d : acc;
                    case "max" -> d.compareTo(acc) > 0 ? d : acc;
                    default -> throw new IllegalArgumentException("unknown aggregate " + fn);
                };
            }
        }
        return new Result(acc == null ? null : form(kind, acc), null);
    }

    /** {@code INTEGER}: a {@code Long} when whole and in range; otherwise (and for {@code DECIMAL}) the plain text. */
    private static Object form(ColumnKind kind, BigDecimal value) {
        if (kind == ColumnKind.INTEGER) {
            try {
                return value.longValueExact();
            } catch (ArithmeticException e) {
                return value.toPlainString();
            }
        }
        return value.toPlainString();
    }
}
