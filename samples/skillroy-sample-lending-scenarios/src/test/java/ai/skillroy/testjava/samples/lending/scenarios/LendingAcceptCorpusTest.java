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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.inference.sql.GuardedStatement;
import ai.skillroy.testjava.inference.sql.SqlGuard;
import ai.skillroy.testjava.samples.lending.LendingDb;
import ai.skillroy.testjava.samples.lending.Seed;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Phase 5 acceptance 15.1 item 5, the accept corpus on the sample's database (phase5-contracts.md 5.3, 14.1): every
 * statement of {@code corpus/lending-accept.sql} (the format of spike BS6's corpora, with the rows read back), written
 * over the tables of the lending pack's SQL allow-list, is accepted by {@code SqlGuard} with that allow-list
 * ({@code LendingAllowList}, through the pack), rewritten into the expected text byte for byte on two runs, and the
 * rewritten statement runs on the sample's own database ({@link LendingDb}, seed A, business date
 * {@link #BUSINESS_DATE}, as the read-only account a tier (b) statement runs as) with the entry's parameter values,
 * giving exactly the entry's rows in order. Together the statements read every allow-listed table.
 *
 * <p>The stored tables are empty on a fresh seed, so the test first posts the corpus's two loans ({@link #POSTED}) as
 * the desk records them. Spike BS6's corpora stay where they are: they run on BS6's own schema in the inference module
 * ({@code HsqldbExecutionTest}), which is not the sample's.
 */
class LendingAcceptCorpusTest {

    /** The corpus, a test resource. */
    static final String CORPUS = "/corpus/lending-accept.sql";
    /** The business date the seed's waiver rule windows are relative to. */
    static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 8);
    /** The read-only account's password: test-local, the database has no listener. */
    private static final String READER_PASSWORD = "Accept-Corpus-Reader-5318";
    /**
     * The corpus's two posted loans, as the desk records them: loan 1001 by staff member 100, three hand tools with the
     * B2W1-HAND waiver (total 2.55); loan 1002 by staff member 100, the return of PWR-001 above the assistant's limit,
     * authorised by supervisor 200.
     */
    static final List<String> POSTED = List.of(
            "INSERT INTO loan (id, staff_code, posted_at, total) VALUES (1001, '100', TIMESTAMP '2026-10-08 10:00:00',"
                    + " 2.55)",
            "INSERT INTO loan_line VALUES (1001, 1, 'HND-001', 1.000, 1.10, 1.10, FALSE)",
            "INSERT INTO loan_line VALUES (1001, 2, 'HND-002', 1.000, 1.45, 1.45, FALSE)",
            "INSERT INTO loan_line VALUES (1001, 3, 'HND-003', 1.000, 0.99, 0.99, FALSE)",
            "INSERT INTO loan_waiver VALUES (1001, 'B2W1-HAND', 0.99)",
            "INSERT INTO loan_deposit VALUES (1001, 2.55)",
            "INSERT INTO loan (id, staff_code, posted_at, total) VALUES (1002, '100', TIMESTAMP '2026-10-08 11:00:00',"
                    + " -34.99)",
            "INSERT INTO loan_line VALUES (1002, 1, 'PWR-001', 1.000, 34.99, -34.99, TRUE)",
            "INSERT INTO loan_authorisation VALUES (1002, 'RETURN_OVERRIDE', '200')");

    private static LendingDb db;
    private static Connection reader;
    private static SqlGuard guard;

    /**
     * One entry of the corpus.
     *
     * @param line the line of its {@code @@} head
     * @param params the parameter values as written
     * @param expected the expected rewrite
     * @param rows the rows read back, cells joined with {@code " | "}
     */
    record Entry(int line, String label, String sql, List<String> params, String expected, List<String> rows) {
        @Override
        public String toString() {
            return "lending-accept:" + line + " " + label;
        }
    }

    @BeforeAll
    static void open() throws Exception {
        SqlAllowList list = allowList();
        guard = new SqlGuard(list);
        db = LendingDb.create("acceptcorpus", Seed.A, BUSINESS_DATE);
        db.createReadOnlyUser(READER_PASSWORD);
        try (Statement st = db.connection().createStatement()) {
            for (String insert : POSTED) {
                st.execute(insert);
            }
        }
        db.connection().commit();
        reader = DriverManager.getConnection(db.url(), LendingDb.READER_USER, READER_PASSWORD);
    }

    @AfterAll
    static void close() throws Exception {
        if (reader != null) {
            reader.close();
        }
        if (guard != null) {
            guard.close();
        }
        if (db != null) {
            db.close();
        }
    }

    /** The pack's one allow-list: source {@code lending}, HSQLDB. */
    static SqlAllowList allowList() {
        List<SqlAllowList> lists = Runs.PACK.sqlAllowLists();
        assertEquals(1, lists.size());
        assertEquals("lending", lists.get(0).source());
        assertEquals(SqlDialect.HSQLDB, lists.get(0).dialect());
        return lists.get(0);
    }

    static Stream<Entry> entries() {
        return load().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    void acceptedRewrittenAndRunOnTheSamplesDatabase(Entry e) throws Exception {
        GuardedStatement guarded = guard.guard(e.sql());
        assertEquals(e.expected(), guarded.sql(), "the rewrite");
        assertEquals(guarded.sql(), guard.guard(e.sql()).sql(), "byte-identical on a second run");
        assertEquals(e.params().size(), guarded.parameterCount(), "a value per parameter");
        List<String> rows = new ArrayList<>();
        try (PreparedStatement ps = reader.prepareStatement(guarded.sql())) {
            bind(ps, e.params());
            try (ResultSet rs = ps.executeQuery()) {
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<String> cells = new ArrayList<>();
                    for (int c = 1; c <= columns; c++) {
                        cells.add(rs.getString(c));
                    }
                    rows.add(String.join(" | ", cells));
                }
            }
        }
        assertEquals(e.rows(), rows, "the rows read back");
    }

    @Test
    void theCorpusReadsEveryAllowListedTable() throws Exception {
        Set<String> read = new TreeSet<>();
        for (Entry e : load()) {
            read.addAll(guard.guard(e.sql()).tables());
        }
        assertEquals(allowList().tables().stream().map(SqlAllowList.AllowedTable::name)
                .collect(Collectors.toCollection(TreeSet::new)), read);
        assertFalse(load().stream().anyMatch(e -> e.rows().isEmpty()), "every statement reads rows back");
    }

    /** The corpus's entries, parsed as spike BS6's corpora are, plus the {@code <<} rows. */
    static List<Entry> load() {
        String text;
        try (InputStream in = LendingAcceptCorpusTest.class.getResourceAsStream(CORPUS)) {
            if (in == null) {
                throw new IllegalStateException("no corpus " + CORPUS);
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Entry> entries = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length && !lines[i].startsWith("@@ ")) {
            i++;
        }
        while (i < lines.length) {
            int head = i + 1;
            String label = lines[i].substring(3).trim();
            i++;
            List<String> body = new ArrayList<>();
            List<String> params = List.of();
            String expected = null;
            List<String> rows = new ArrayList<>();
            while (i < lines.length && !lines[i].startsWith("@@ ")) {
                String l = lines[i];
                if (l.startsWith("?? ")) {
                    params = cells(l.substring(3));
                } else if (l.startsWith("=> ")) {
                    expected = l.substring(3);
                } else if (l.startsWith("<< ")) {
                    rows.add(String.join(" | ", cells(l.substring(3))));
                } else {
                    body.add(l);
                }
                i++;
            }
            entries.add(new Entry(head, label, String.join("\n", body).strip(), params, expected, List.copyOf(rows)));
        }
        return entries;
    }

    private static List<String> cells(String line) {
        List<String> out = new ArrayList<>();
        for (String part : line.split("\\|")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    /** Binds the entry's values as BS6's corpora write them: 'text', integers, decimals, DATE yyyy-mm-dd, booleans. */
    private static void bind(PreparedStatement ps, List<String> values) throws Exception {
        for (int i = 0; i < values.size(); i++) {
            String v = values.get(i);
            int index = i + 1;
            if (v.startsWith("'") && v.endsWith("'")) {
                ps.setString(index, v.substring(1, v.length() - 1));
            } else if (v.startsWith("DATE ")) {
                ps.setDate(index, Date.valueOf(v.substring(5).trim()));
            } else if (v.equals("true") || v.equals("false")) {
                ps.setBoolean(index, Boolean.parseBoolean(v));
            } else if (v.contains(".")) {
                ps.setBigDecimal(index, new BigDecimal(v));
            } else {
                ps.setInt(index, Integer.parseInt(v));
            }
        }
    }
}
