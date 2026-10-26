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

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.data.DataException;
import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.resolve.ContributionException;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedColumn;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedTable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The check of an SQL allow-list against its source's schema and the pack's row masks (phase5-contracts.md 5.2,
 * decision D100): what makes rule 4 of the guard sound, since the guard trusts {@code otherColumns} to name every
 * column a table has (a column the allow-list does not know could otherwise be read through a correlated name, and a
 * later release of the application adds columns). Run where a data channel exists: by the tier (b) resolvers once per
 * bind, before the prompt catalogue is built, and by the MCP server at its start, before {@code data_query} is offered.
 *
 * <p>For every allow-listed table, {@code SELECT * FROM <table> WHERE 1 = 0} runs through the source (no row comes
 * back, so no value passes any mask) and the result's column names must be exactly the table's readable columns plus
 * its {@code otherColumns}, compared without regard to case (the sources fold unquoted names, and a column's label is
 * reported in the source's case; a table with two columns whose names differ only in case is refused, since the
 * allow-list cannot tell them apart). A table the source cannot read, a column the allow-list does not name and a name
 * the table does not have are each a {@link ContributionException} naming the table and the column names, never a
 * value. Every readable column then goes through the pack's row masks with a sentinel of each value type a mask sees
 * (text, a whole number, a decimal, a boolean), under the name the allow-list declares and the name the source
 * reports: a mask that alters a sentinel, or fails on it, marks a secret column, which may not be allow-listed (P2 4's
 * rule). Whether a key column is nullable needs the source's metadata, which a data channel does not expose: it is not
 * checked here (the sample's own test checks it on its schema).
 *
 * <p>Phase 8 (phase8-contracts.md 4.4, 4.5): the logic and the statement text are unchanged; a refusal over a source
 * that reported no column, or only names of the form {@code c<digits>} (a provider that answers by position), says
 * that the source's {@code DataSourceProvider} should answer {@code columns(table)} (gap 117), a message and never a
 * pass; and {@link #proceduresRefusal} words the refusal of a list that names procedures (gap 119).
 *
 * <p>Stateless; safe from any thread.
 */
public final class AllowListCheck {

    /** The sentinels a row mask is asked about, one per value type it can see (P2 7.3). */
    static final List<Object> SENTINELS = List.of("skillroy-allow-list-probe", 1L, 1.5d, Boolean.TRUE);

    private AllowListCheck() {
    }

    /**
     * Checks one allow-list against its source and the pack's row masks.
     *
     * @param data the channel that serves the list's source (statements through it are recorded where the channel
     *     records them)
     * @param rowMasks the pack's row masks; null is none
     * @param timeout each statement's timeout; null is the source's default
     * @throws ContributionException for a table the source cannot read, a column set that differs from the
     *     allow-list's, or a readable column a row mask alters
     * @throws DataException when the source cannot be reached (anything but the statement's own SQL failure)
     * @throws AgentLostException when the agent serving the source is gone
     * @throws InterruptedException when the thread is interrupted
     */
    public static void check(SqlAllowList list, DataChannel data, List<RowMask> rowMasks, Duration timeout)
            throws ContributionException, DataException, AgentLostException, InterruptedException {
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(data, "data");
        for (AllowedTable table : list.tables()) {
            Rows rows;
            try {
                rows = data.query(list.source(), "SELECT * FROM " + table.name() + " WHERE 1 = 0", List.of(), 1,
                        timeout);
            } catch (DataException e) {
                if (e.kind() != DataException.Kind.SQL) {
                    throw e;
                }
                throw new ContributionException("the SQL allow-list of source " + list.source() + " names table "
                        + table.name() + ", which the source cannot read (an unknown table, or one its account may"
                        + " not read)");
            }
            Map<String, String> reported = columns(list.source(), table, rows);
            Set<String> listed = new LinkedHashSet<>();
            for (AllowedColumn column : table.columns()) {
                listed.add(fold(column.name()));
            }
            for (String other : table.otherColumns()) {
                listed.add(fold(other));
            }
            List<String> unknown = new ArrayList<>();
            for (AllowedColumn column : table.columns()) {
                if (!reported.containsKey(fold(column.name()))) {
                    unknown.add(column.name());
                }
            }
            for (String other : table.otherColumns()) {
                if (!reported.containsKey(fold(other))) {
                    unknown.add(other);
                }
            }
            if (!unknown.isEmpty() || positional(rows)) {
                throw new ContributionException("the SQL allow-list of source " + list.source() + " does not match"
                        + " table " + table.name() + (unknown.isEmpty() ? "" : ": names the table does not have: "
                        + unknown)
                        + (positional(rows) ? "; source " + list.source() + " reports no column names for table "
                        + table.name() + " (a provider that answers by position); its DataSourceProvider should"
                        + " answer columns(table) (gap 117)" : ""));
            }
            for (AllowedColumn column : table.columns()) {
                maskCheck(list.source(), table, column, reported.get(fold(column.name())), rowMasks);
            }
        }
    }

    /**
     * The refusal of a list that names procedures (phase8-contracts.md 4.5, gap 119, D533), or null when it names
     * none: no data channel runs a procedure call, and the field is reserved for gap 13's prepared reads. Where a list
     * is put to use (the inference contributor at load, the MCP server at start), this text refuses it.
     */
    public static String proceduresRefusal(SqlAllowList list) {
        Objects.requireNonNull(list, "list");
        if (list.procedures().isEmpty()) {
            return null;
        }
        return "the SQL allow-list of source " + list.source() + " names procedures " + list.procedures()
                + "; no data channel runs a procedure call (gap 119; the field is reserved for a prepared read, gap 13)";
    }

    /** True when the source reported no column, or only names of the form {@code c<digits>} (gap 117). */
    private static boolean positional(Rows rows) {
        for (Rows.Column column : rows.columns()) {
            if (!column.name().matches("c[0-9]+")) {
                return false;
            }
        }
        return true;
    }

    /**
     * The row-mask half alone, under the names the allow-list declares (no channel needed): what the contributor
     * checks when it is loaded.
     *
     * @throws ContributionException for a readable column a row mask alters
     */
    public static void rowMasks(SqlAllowList list, List<RowMask> rowMasks) throws ContributionException {
        Objects.requireNonNull(list, "list");
        for (AllowedTable table : list.tables()) {
            for (AllowedColumn column : table.columns()) {
                maskCheck(list.source(), table, column, null, rowMasks);
            }
        }
    }

    /** The table's column names, folded, with the name as the source reported it; refuses a fold that collides. */
    private static Map<String, String> columns(String source, AllowedTable table, Rows rows)
            throws ContributionException {
        Map<String, String> reported = new LinkedHashMap<>();
        for (Rows.Column column : rows.columns()) {
            String previous = reported.putIfAbsent(fold(column.name()), column.name());
            if (previous != null) {
                throw new ContributionException("table " + table.name() + " of source " + source + " has two columns"
                        + " whose names differ only in case (" + previous + ", " + column.name() + "); an SQL"
                        + " allow-list cannot tell them apart");
            }
        }
        return reported;
    }

    private static void maskCheck(String source, AllowedTable table, AllowedColumn column, String reportedName,
                                  List<RowMask> rowMasks) throws ContributionException {
        if (rowMasks == null || rowMasks.isEmpty()) {
            return;
        }
        Set<String> names = new LinkedHashSet<>(List.of(column.name(), column.name().toUpperCase(Locale.ROOT),
                column.name().toLowerCase(Locale.ROOT)));
        if (reportedName != null) {
            names.add(reportedName);
        }
        for (int m = 0; m < rowMasks.size(); m++) {
            RowMask mask = rowMasks.get(m);
            for (String name : names) {
                for (Object sentinel : SENTINELS) {
                    Object out;
                    try {
                        out = mask.mask(source, name, sentinel);
                    } catch (RuntimeException e) {
                        out = e;
                    }
                    if (!sentinel.equals(out)) {
                        throw new ContributionException("the SQL allow-list of source " + source + " lets a model"
                                + " read column " + table.name() + "." + column.name() + ", which row mask " + (m + 1)
                                + " of the pack " + (out instanceof RuntimeException ? "fails on" : "alters") + ": a"
                                + " masked column is secret and may not be allow-listed; name it in otherColumns");
                    }
                }
            }
        }
    }

    private static String fold(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
