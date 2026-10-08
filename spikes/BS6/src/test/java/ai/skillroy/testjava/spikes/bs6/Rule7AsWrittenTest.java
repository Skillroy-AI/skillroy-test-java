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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Why rule 7 of section 5.3 cannot be applied as written ("ends with every key column of the driving table"): the
 * statements below are what that rule produces, run on the embedded database. RESULT.md proposes the replacement the
 * prototype implements.
 */
class Rule7AsWrittenTest {

    private static Connection db;

    @BeforeAll
    static void open() throws Exception {
        db = LendingFixture.hsqldb("bs6rule7");
    }

    @AfterAll
    static void close() throws Exception {
        db.close();
    }

    @Test
    void theDrivingKeyAfterDistinctIsInvalid() {
        assertThrows(SQLException.class,
                () -> run("SELECT DISTINCT i.category FROM item i ORDER BY i.code LIMIT 200"));
    }

    @Test
    void theDrivingKeyAfterGroupByIsInvalid() {
        assertThrows(SQLException.class, () -> run("SELECT x.item_code, SUM(x.qty) AS total FROM loan_line x "
                + "GROUP BY x.item_code ORDER BY x.loan_id, x.line_no LIMIT 200"));
    }

    @Test
    void theDrivingKeyAfterASingleRowAggregateIsInvalid() {
        assertThrows(SQLException.class, () -> run("SELECT COUNT(*) AS n FROM loan l ORDER BY l.id LIMIT 200"));
    }

    @Test
    void theDrivingKeyOfAOneToManyJoinLeavesTies() throws Exception {
        // Ordered by the driving table's key alone, the lines of one loan tie: their order is the database's choice.
        Set<Integer> seen = new HashSet<>();
        boolean tie = false;
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT l.id, x.line_no FROM loan l JOIN loan_line x ON x.loan_id = l.id "
                     + "ORDER BY l.id LIMIT 200")) {
            while (rs.next()) {
                tie |= !seen.add(rs.getInt(1));
            }
        }
        assertTrue(tie, "two rows share the driving key");
    }

    private static void run(String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rs.getObject(1);
            }
        }
    }
}
