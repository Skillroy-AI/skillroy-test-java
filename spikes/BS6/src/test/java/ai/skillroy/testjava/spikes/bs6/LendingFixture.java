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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The spike's lending desk: one allow-list over the schema of {@code lending-schema.sql}, with the function names each
 * dialect's corpus needs beyond rule 5's built-in set, and the embedded HSQLDB the HSQLDB accept corpus runs on.
 */
final class LendingFixture {

    private LendingFixture() {
    }

    /** Functions beyond the built-in set, per dialect, as a pack would declare them in SqlAllowList.functions. */
    static final Map<SqlDialect, List<String>> FUNCTIONS = Map.of(
            SqlDialect.HSQLDB, List.of("CURRENT_TIMESTAMP", "EXTRACT", "DATEADD", "DATEDIFF", "TIMESTAMPADD",
                    "DATE_ADD", "DATE_SUB", "CONCAT", "NVL", "IFNULL", "CHAR_LENGTH", "YEAR", "MONTH", "MOD"),
            SqlDialect.MYSQL, List.of("CURRENT_TIMESTAMP", "NOW", "CURDATE", "DATE_ADD", "DATE_SUB", "DATEDIFF",
                    "TIMESTAMPDIFF", "EXTRACT", "CONCAT", "IFNULL", "CHAR_LENGTH", "YEAR", "MONTH"),
            SqlDialect.MARIADB, List.of("CURRENT_TIMESTAMP", "NOW", "CURDATE", "DATE_ADD", "DATE_SUB", "DATEDIFF",
                    "TIMESTAMPDIFF", "EXTRACT", "CONCAT", "IFNULL", "CHAR_LENGTH", "YEAR", "MONTH"),
            SqlDialect.POSTGRESQL, List.of("CURRENT_TIMESTAMP", "NOW", "EXTRACT", "DATE_TRUNC", "CONCAT",
                    "STRING_AGG", "CHAR_LENGTH"),
            SqlDialect.DB2, List.of("CURRENT_TIMESTAMP", "ADD_DAYS", "DAYS", "YEAR", "MONTH", "VALUE", "CONCAT",
                    "DATE", "SUBSTR"),
            SqlDialect.ORACLE, List.of("SYSDATE", "SYSTIMESTAMP", "ADD_MONTHS", "TRUNC", "NVL", "TO_CHAR",
                    "TO_DATE", "MONTHS_BETWEEN", "EXTRACT", "LISTAGG"),
            SqlDialect.SQLSERVER, List.of("CURRENT_TIMESTAMP", "GETDATE", "DATEADD", "DATEDIFF", "ISNULL", "LEN",
                    "YEAR", "MONTH", "CONVERT", "CONCAT", "IIF"),
            SqlDialect.GENERIC, List.of("CURRENT_TIMESTAMP", "EXTRACT", "CHAR_LENGTH", "POSITION"));

    /** The allow-list: every table of the schema but audit_log, every column but staff.pin_hash. */
    static SqlAllowList allowList(SqlDialect dialect) {
        return SqlAllowList.builder("lending", dialect)
                .table("staff", List.of("code"), "code VARCHAR", "name VARCHAR personal", "email VARCHAR personal",
                        "active BOOLEAN", "joined DATE")
                .table("staff_role", List.of("staff_code", "role"), "staff_code VARCHAR", "role VARCHAR sample")
                .table("role_permission", List.of("role", "permission"), "role VARCHAR", "permission VARCHAR sample")
                .table("role_cap", List.of("role", "cap"), "role VARCHAR", "cap VARCHAR sample", "max_amount DECIMAL")
                .table("item", List.of("code"), "code VARCHAR", "barcode VARCHAR", "title VARCHAR",
                        "category VARCHAR sample", "deposit DECIMAL", "per_day DECIMAL", "active BOOLEAN")
                .table("waiver_rule", List.of("id"), "id INTEGER", "category VARCHAR", "min_days INTEGER",
                        "percent_off INTEGER", "role VARCHAR")
                .table("loan", List.of("id"), "id INTEGER", "borrower VARCHAR personal", "staff_code VARCHAR",
                        "opened DATE", "due DATE", "status VARCHAR sample")
                .table("loan_line", List.of("loan_id", "line_no"), "loan_id INTEGER", "line_no INTEGER",
                        "item_code VARCHAR", "qty INTEGER", "deposit_paid DECIMAL", "returned DATE")
                .procedure("open_loan_count")
                .functions(FUNCTIONS.get(dialect).toArray(String[]::new))
                .build();
    }

    /** A fresh in-memory HSQLDB with the schema and its rows. */
    static Connection hsqldb(String name) throws SQLException, IOException {
        Connection c = DriverManager.getConnection("jdbc:hsqldb:mem:" + name + ";shutdown=true", "SA", "");
        String schema;
        try (InputStream in = LendingFixture.class.getResourceAsStream("/lending-schema.sql")) {
            schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder current = new StringBuilder();
        for (String line : schema.split("\n")) {
            if (line.startsWith("--") || line.isBlank()) {
                continue;
            }
            current.append(line).append('\n');
            if (line.trim().endsWith(";")) {
                String sql = current.toString().trim();
                try (Statement st = c.createStatement()) {
                    st.execute(sql.substring(0, sql.length() - 1));
                }
                current.setLength(0);
            }
        }
        return c;
    }

    /** Binds the corpus entry's parameter values: 'text', integers, decimals, DATE yyyy-mm-dd, true and false. */
    static void bind(PreparedStatement ps, List<String> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            String v = values.get(i).trim();
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

    static List<String> splitParams(String line) {
        List<String> out = new ArrayList<>();
        for (String part : line.split("\\|")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }
}
