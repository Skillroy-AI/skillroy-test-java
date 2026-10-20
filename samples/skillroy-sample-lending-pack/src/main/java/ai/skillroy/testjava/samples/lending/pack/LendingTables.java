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

package ai.skillroy.testjava.samples.lending.pack;

import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.scenario.resolve.ColumnKind;
import ai.skillroy.testjava.scenario.resolve.StoredColumn;
import ai.skillroy.testjava.scenario.resolve.StoredTable;

import java.util.List;

/**
 * The desk's tables a scenario may check with {@code db.<name>} (phase2-contracts.md 13.3), and the controller-side
 * row mask. Logical and physical names coincide; decimal columns cast their parameter ({@code qty} at three places),
 * so that a decimal travels as a plain string on either channel (section 7.1), and so does the timestamp
 * {@code posted_at}, which travels as {@code yyyy-MM-dd HH:mm:ss[.fraction]} (section 7.4).
 *
 * <p>The row mask repeats the agent module's {@code MaskPolicy} exactly: in rows of source {@value
 * LendingResolvers#SOURCE}, a non-null value of a column whose label is {@code password} (any case) becomes
 * {@code <redacted>}; a column renamed in the statement is not masked. Why both sides: rows read over JDBC never pass
 * the agent, and the binder applies this mask to every row sample whichever channel read it.
 *
 * <p>Immutable; safe to share between threads.
 */
final class LendingTables {

    private static final String MONEY = "CAST(? AS DECIMAL(10,2))";
    private static final String QTY = "CAST(? AS DECIMAL(10,3))";
    private static final String TIMESTAMP = "CAST(? AS TIMESTAMP)";

    /** The masked column. */
    static final String PASSWORD_COLUMN = "password";

    static final List<StoredTable> ALL = List.of(
            StoredTable.of("loan", LendingResolvers.SOURCE, "loan", List.of(
                    StoredColumn.of("id", ColumnKind.INTEGER),
                    StoredColumn.of("staff_code", ColumnKind.TEXT),
                    StoredColumn.of("posted_at", ColumnKind.TIMESTAMP).param(TIMESTAMP),
                    StoredColumn.of("total", ColumnKind.DECIMAL).param(MONEY)), List.of("id")),
            StoredTable.of("loan_line", LendingResolvers.SOURCE, "loan_line", List.of(
                    StoredColumn.of("loan_id", ColumnKind.INTEGER),
                    StoredColumn.of("line_no", ColumnKind.INTEGER),
                    StoredColumn.of("item_code", ColumnKind.TEXT),
                    StoredColumn.of("qty", ColumnKind.DECIMAL).param(QTY),
                    StoredColumn.of("unit_deposit", ColumnKind.DECIMAL).param(MONEY),
                    StoredColumn.of("amount", ColumnKind.DECIMAL).param(MONEY),
                    StoredColumn.of("returned", ColumnKind.BOOLEAN)), List.of("loan_id", "line_no")),
            StoredTable.of("loan_waiver", LendingResolvers.SOURCE, "loan_waiver", List.of(
                    StoredColumn.of("loan_id", ColumnKind.INTEGER),
                    StoredColumn.of("rule_code", ColumnKind.TEXT),
                    StoredColumn.of("amount", ColumnKind.DECIMAL).param(MONEY)), List.of("loan_id", "rule_code")),
            StoredTable.of("loan_authorisation", LendingResolvers.SOURCE, "loan_authorisation", List.of(
                    StoredColumn.of("loan_id", ColumnKind.INTEGER),
                    StoredColumn.of("kind", ColumnKind.TEXT),
                    StoredColumn.of("staff_code", ColumnKind.TEXT)), List.of("loan_id", "kind", "staff_code")));

    /** The agent module's positional source, the desk's report reader (phase8-contracts.md 12.2, 12.3). */
    static final String REPORT_SOURCE = "lending-report";

    /** The controller-side twin of the agent module's password {@code MaskPolicy}, over both of its sources. */
    static final RowMask PASSWORD_MASK = (sourceId, column, value) ->
            value != null && (LendingResolvers.SOURCE.equals(sourceId) || REPORT_SOURCE.equals(sourceId))
                    && PASSWORD_COLUMN.equalsIgnoreCase(column) ? SecretKeys.REDACTED : value;

    private LendingTables() {
    }
}
