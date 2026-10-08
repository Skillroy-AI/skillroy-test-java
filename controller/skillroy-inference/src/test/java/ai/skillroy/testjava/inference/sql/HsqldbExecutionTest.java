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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.stream.Stream;

import ai.skillroy.testjava.inference.sql.Corpus.Entry;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * phase5-contracts.md 15.1 item 5: every HSQLDB accept-corpus statement of spike BS6, as the guard rewrote it, runs on
 * the embedded lending schema with the corpus's parameter values and returns at most 200 rows.
 */
class HsqldbExecutionTest {

    private static Connection db;
    private static SqlGuard guard;

    @BeforeAll
    static void open() throws Exception {
        db = LendingFixture.hsqldb("bs6exec");
        guard = new SqlGuard(LendingFixture.allowList(SqlDialect.HSQLDB));
    }

    @AfterAll
    static void close() throws Exception {
        guard.close();
        db.close();
    }

    static Stream<Entry> entries() {
        return Corpus.accept(SqlDialect.HSQLDB).entries().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    void runs(Entry e) throws Exception {
        GuardedStatement g = guard.guard(e.sql());
        assertEquals(g.parameterCount(), e.params().size(), "the corpus entry must give a value per parameter");
        try (PreparedStatement ps = db.prepareStatement(g.sql())) {
            LendingFixture.bind(ps, e.params());
            if (ps.execute()) {
                int rows = 0;
                try (ResultSet rs = ps.getResultSet()) {
                    while (rs.next()) {
                        rows++;
                    }
                }
                assertTrue(rows <= SqlGuard.ROW_CAP, rows + " rows");
            }
        }
    }
}
