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

package ai.skillroy.testjava.samples.lending;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The lending database schema (contract tables and columns). Money columns are {@code DECIMAL(10,2)}; quantities are
 * {@code DECIMAL(10,3)} so that per-day items fit. Loan ids come from the sequence {@code loan_id_seq}, whose start
 * differs per seed.
 */
final class LendingSchema {

    static final String LOAN_ID_SEQUENCE = "loan_id_seq";

    private static final String[] DDL = {
        "CREATE TABLE staff (code VARCHAR(16) PRIMARY KEY, name VARCHAR(64) NOT NULL, password VARCHAR(64),"
            + " active BOOLEAN DEFAULT TRUE NOT NULL, auto_sign_on BOOLEAN DEFAULT FALSE NOT NULL)",
        "CREATE TABLE role (id INTEGER PRIMARY KEY, name VARCHAR(32) NOT NULL UNIQUE)",
        "CREATE TABLE staff_role (staff_code VARCHAR(16) NOT NULL REFERENCES staff(code),"
            + " role_id INTEGER NOT NULL REFERENCES role(id), PRIMARY KEY (staff_code, role_id))",
        "CREATE TABLE role_permission (role_id INTEGER NOT NULL REFERENCES role(id),"
            + " permission VARCHAR(32) NOT NULL, PRIMARY KEY (role_id, permission))",
        "CREATE TABLE role_cap (role_id INTEGER NOT NULL REFERENCES role(id), kind VARCHAR(16) NOT NULL,"
            + " amount DECIMAL(10,2) NOT NULL, PRIMARY KEY (role_id, kind))",
        "CREATE TABLE desk_setting (name VARCHAR(32) PRIMARY KEY, value VARCHAR(128))",
        "CREATE TABLE item (code VARCHAR(16) PRIMARY KEY, barcode VARCHAR(32) UNIQUE,"
            + " title VARCHAR(64) NOT NULL, deposit DECIMAL(10,2) NOT NULL,"
            + " active BOOLEAN DEFAULT TRUE NOT NULL, induction_required BOOLEAN DEFAULT FALSE NOT NULL,"
            + " per_day BOOLEAN DEFAULT FALSE NOT NULL)",
        "CREATE TABLE waiver_rule (id INTEGER PRIMARY KEY, code VARCHAR(32) NOT NULL UNIQUE, description VARCHAR(64),"
            + " kind VARCHAR(32) NOT NULL, borrow_qty INTEGER NOT NULL, waived_qty INTEGER NOT NULL,"
            + " active BOOLEAN NOT NULL, starts_on DATE, ends_on DATE)",
        "CREATE TABLE waiver_rule_item (rule_id INTEGER NOT NULL REFERENCES waiver_rule(id),"
            + " item_code VARCHAR(16) NOT NULL REFERENCES item(code), PRIMARY KEY (rule_id, item_code))",
        "CREATE TABLE loan (id BIGINT PRIMARY KEY, staff_code VARCHAR(16) NOT NULL REFERENCES staff(code),"
            + " posted_at TIMESTAMP NOT NULL, total DECIMAL(10,2) NOT NULL)",
        "CREATE TABLE loan_line (loan_id BIGINT NOT NULL REFERENCES loan(id), line_no INTEGER NOT NULL,"
            + " item_code VARCHAR(16) NOT NULL REFERENCES item(code), qty DECIMAL(10,3) NOT NULL,"
            + " unit_deposit DECIMAL(10,2) NOT NULL, amount DECIMAL(10,2) NOT NULL, returned BOOLEAN NOT NULL,"
            + " PRIMARY KEY (loan_id, line_no))",
        "CREATE TABLE loan_waiver (loan_id BIGINT NOT NULL REFERENCES loan(id),"
            + " rule_code VARCHAR(32) NOT NULL, amount DECIMAL(10,2) NOT NULL,"
            + " PRIMARY KEY (loan_id, rule_code))",
        "CREATE TABLE loan_deposit (loan_id BIGINT NOT NULL REFERENCES loan(id), amount DECIMAL(10,2) NOT NULL)",
        "CREATE TABLE loan_authorisation (loan_id BIGINT NOT NULL REFERENCES loan(id), kind VARCHAR(32) NOT NULL,"
            + " staff_code VARCHAR(16) NOT NULL REFERENCES staff(code))",
    };

    private LendingSchema() {
    }

    /** Creates every table and the loan id sequence starting at {@code firstLoanId}. */
    static void create(Connection connection, long firstLoanId) throws SQLException {
        try (Statement st = connection.createStatement()) {
            for (String ddl : DDL) {
                st.execute(ddl);
            }
            st.execute("CREATE SEQUENCE " + LOAN_ID_SEQUENCE + " AS BIGINT START WITH " + firstLoanId);
        }
    }
}
