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

import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedColumn;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;

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
 * Spike BS6's lending desk, lifted with its corpora: one allow-list over the schema of {@code lending-schema.sql}, with
 * the function names each dialect's corpus needs beyond the guard's built-in and dialect sets, as a pack would declare
 * them in {@code SqlAllowList.functions}, and the embedded HSQLDB the HSQLDB accept corpus runs on. The allow-list of
 * the corpora names no other columns (as the spike's did), so that unqualified correlated references stay refused as
 * the corpora expect; {@link #withOtherColumns} gives the variant with {@code staff.pin_hash} named.
 */
public final class LendingFixture {

    private LendingFixture() {
    }

    /** Functions beyond the built-in and dialect sets, per dialect: what the spike's corpora need. */
    static final Map<SqlDialect, List<String>> FUNCTIONS = Map.of(
            SqlDialect.HSQLDB, List.of("CONCAT", "NVL", "IFNULL", "CHAR_LENGTH", "MOD"),
            SqlDialect.MYSQL, List.of("CONCAT", "IFNULL", "CHAR_LENGTH"),
            SqlDialect.MARIADB, List.of("CONCAT", "IFNULL", "CHAR_LENGTH"),
            SqlDialect.POSTGRESQL, List.of("CONCAT", "STRING_AGG", "CHAR_LENGTH"),
            SqlDialect.DB2, List.of("VALUE", "CONCAT", "SUBSTR"),
            SqlDialect.ORACLE, List.of("NVL", "TO_CHAR", "LISTAGG"),
            SqlDialect.SQLSERVER, List.of("ISNULL", "LEN", "CONVERT", "CONCAT", "IIF"),
            SqlDialect.GENERIC, List.of("CHAR_LENGTH", "POSITION"));

    /** The allow-list: every table of the schema but audit_log, every column but staff.pin_hash. */
    public static SqlAllowList allowList(SqlDialect dialect) {
        return allowList(dialect, false);
    }

    /** {@link #allowList} with {@code staff.pin_hash} and the hidden columns of every table named as other columns. */
    public static SqlAllowList withOtherColumns(SqlDialect dialect) {
        return allowList(dialect, true);
    }

    private static SqlAllowList allowList(SqlDialect dialect, boolean others) {
        SqlAllowList.Builder b = SqlAllowList.builder("lending", dialect);
        table(b, "staff", List.of("code"), others ? List.of("pin_hash") : List.of(), "code VARCHAR",
                "name VARCHAR personal", "email VARCHAR personal", "active BOOLEAN", "joined DATE");
        table(b, "staff_role", List.of("staff_code", "role"), List.of(), "staff_code VARCHAR", "role VARCHAR sample");
        table(b, "role_permission", List.of("role", "permission"), List.of(), "role VARCHAR",
                "permission VARCHAR sample");
        table(b, "role_cap", List.of("role", "cap"), List.of(), "role VARCHAR", "cap VARCHAR sample",
                "max_amount DECIMAL");
        table(b, "item", List.of("code"), List.of(), "code VARCHAR", "barcode VARCHAR", "title VARCHAR",
                "category VARCHAR sample", "deposit DECIMAL", "per_day DECIMAL", "active BOOLEAN");
        table(b, "waiver_rule", List.of("id"), List.of(), "id INTEGER", "category VARCHAR", "min_days INTEGER",
                "percent_off INTEGER", "role VARCHAR");
        table(b, "loan", List.of("id"), List.of(), "id INTEGER", "borrower VARCHAR personal", "staff_code VARCHAR",
                "opened DATE", "due DATE", "status VARCHAR sample");
        table(b, "loan_line", List.of("loan_id", "line_no"), List.of(), "loan_id INTEGER", "line_no INTEGER",
                "item_code VARCHAR", "qty INTEGER", "deposit_paid DECIMAL", "returned DATE");
        b.procedure("open_loan_count");
        for (String f : FUNCTIONS.get(dialect)) {
            b.function(f);
        }
        return b.build();
    }

    /** Columns are written "name type", "name type personal" or "name type sample". */
    private static void table(SqlAllowList.Builder b, String name, List<String> key, List<String> others,
                              String... columns) {
        List<AllowedColumn> cols = new ArrayList<>();
        for (String spec : columns) {
            String[] parts = spec.trim().split("\\s+");
            boolean personal = parts.length > 2 && parts[2].equals("personal");
            boolean sample = parts.length > 2 && parts[2].equals("sample");
            cols.add(new AllowedColumn(parts[0], parts[1], personal, sample, null));
        }
        b.table(name, key, null, cols, others);
    }

    /** A fresh in-memory HSQLDB with the schema and its rows. */
    public static Connection hsqldb(String name) throws SQLException, IOException {
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
