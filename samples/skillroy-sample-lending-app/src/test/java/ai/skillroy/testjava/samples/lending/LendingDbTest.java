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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

/** The schema, the two seeds and the read-only connection. */
class LendingDbTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static LendingDb create(Seed seed) throws SQLException {
        return LendingDb.create("dbtest_" + COUNTER.incrementAndGet(), seed, TODAY);
    }

    private static List<String> column(LendingDb db, String sql) throws SQLException {
        List<String> values = new ArrayList<String>();
        try (Connection c = db.openReadOnlyConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    @Test
    void schemaHasTheContractTables() throws Exception {
        try (LendingDb db = create(Seed.A)) {
            List<String> tables = column(db, "SELECT LOWER(table_name) FROM information_schema.tables"
                + " WHERE table_schema = 'PUBLIC' ORDER BY 1");
            List<String> expected = new ArrayList<String>();
            Collections.addAll(expected, "desk_setting", "item", "loan", "loan_authorisation", "loan_deposit",
                "loan_line", "loan_waiver", "role", "role_cap", "role_permission", "staff", "staff_role",
                "waiver_rule", "waiver_rule_item");
            assertEquals(expected, tables);
            assertEquals("jdbc:hsqldb:mem:" + db.name(), db.url());
            assertEquals(Seed.A, db.seed());
            List<String> money = column(db, "SELECT LOWER(table_name || '.' || column_name) FROM"
                + " information_schema.columns WHERE table_schema = 'PUBLIC' AND data_type = 'DECIMAL'"
                + " AND numeric_scale = 2 ORDER BY 1");
            assertEquals(java.util.Arrays.asList("item.deposit", "loan.total", "loan_deposit.amount",
                "loan_line.amount", "loan_line.unit_deposit", "loan_waiver.amount", "role_cap.amount"), money);
        }
    }

    @Test
    void readOnlyConnectionCannotWrite() throws Exception {
        try (LendingDb db = create(Seed.A); Connection c = db.openReadOnlyConnection();
             Statement st = c.createStatement()) {
            assertTrue(c.isReadOnly());
            assertThrows(SQLException.class,
                () -> st.executeUpdate("UPDATE item SET deposit = 0 WHERE code = 'YRD-001'"));
            assertEquals("max_return", db.settings().keySet().iterator().next());
        }
    }

    @Test
    void readersNeverWaitForTheEnginesOpenTransactionAndSeeOnlyCommittedRows() throws Exception {
        try (LendingDb db = create(Seed.A)) {
            Connection engine = db.connection();
            assertFalse(engine.getAutoCommit());
            try (Statement st = engine.createStatement()) {
                st.executeUpdate("INSERT INTO loan (id, staff_code, posted_at, total)"
                    + " VALUES (1, '100', TIMESTAMP '2026-10-01 10:00:00', 3.50)");
                st.executeUpdate("UPDATE item SET deposit = 9.99 WHERE code = 'YRD-001'");
            }
            // The engine's transaction is open. A reader on its own connection must answer at once.
            List<String> counts = assertTimeoutPreemptively(Duration.ofSeconds(60),
                () -> column(db, "SELECT COUNT(*) FROM loan"));
            assertEquals(Collections.singletonList("0"), counts, "uncommitted rows are invisible");
            assertEquals(Collections.singletonList("3.50"), assertTimeoutPreemptively(Duration.ofSeconds(60),
                () -> column(db, "SELECT deposit FROM item WHERE code = 'YRD-001'")));
            engine.commit();
            assertEquals(Collections.singletonList("1"), column(db, "SELECT COUNT(*) FROM loan"));
            assertEquals(Collections.singletonList("9.99"),
                column(db, "SELECT deposit FROM item WHERE code = 'YRD-001'"));
        }
    }

    @Test
    void aClosedDatabaseRefusesNewConnections() throws Exception {
        LendingDb db = create(Seed.A);
        Connection reader = db.openReadOnlyConnection();
        db.close();
        db.close();
        assertTrue(db.isClosed());
        assertThrows(SQLException.class, db::openReadOnlyConnection,
            "it must not silently create an empty database of the same name");
        assertThrows(SQLException.class, db::settings);
        assertThrows(SQLException.class, () -> reader.createStatement().executeQuery("SELECT * FROM staff"));
    }

    @Test
    void recreatingADatabaseStartsAfresh() throws Exception {
        String name = "dbtest_again_" + COUNTER.incrementAndGet();
        LendingDb first = LendingDb.create(name, Seed.A, TODAY);
        first.connection().close();
        try (LendingDb second = LendingDb.create(name, Seed.B, TODAY)) {
            assertEquals(Collections.singletonList("350.00"),
                column(second, "SELECT value FROM desk_setting WHERE name = 'max_return'"));
            assertFalse(column(second, "SELECT code FROM staff").contains("100"));
        }
    }

    @ParameterizedTest
    @EnumSource(Seed.class)
    void eachSeedHasEveryKindOfStaffAndItem(Seed seed) throws Exception {
        try (LendingDb db = create(seed)) {
            AccessControl access = AccessControl.load(db.connection());
            Catalogue catalogue = Catalogue.load(db.connection());
            WaiverEngine waiverRules = WaiverEngine.load(db.connection());
            BigDecimal max = access.systemMaxReturn();
            assertTrue(max != null && max.signum() > 0);

            BigDecimal lowAssistantLimit = null;
            BigDecimal supervisorLimit = null;
            boolean noReturn = false;
            boolean manager = false;
            boolean inactive = false;
            boolean auto = false;
            boolean cannotSignOn = false;
            for (Staff s : access.staff()) {
                boolean signOn = access.hasPermission(s.code(), AccessControl.SIGN_ON);
                boolean canReturn = access.hasPermission(s.code(), AccessControl.RETURN);
                boolean override = access.hasPermission(s.code(), AccessControl.RETURN_OVERRIDE);
                BigDecimal limit = access.returnLimit(s.code());
                inactive |= !s.active();
                auto |= s.active() && s.autoSignOn() && signOn;
                cannotSignOn |= s.active() && !signOn;
                noReturn |= signOn && !canReturn;
                if (signOn && canReturn && !override && limit != null && limit.compareTo(new BigDecimal("50")) < 0
                    && !s.autoSignOn()) {
                    lowAssistantLimit = lowAssistantLimit == null || limit.compareTo(lowAssistantLimit) < 0
                        ? limit : lowAssistantLimit;
                }
                if (override && limit != null) {
                    supervisorLimit = limit;
                }
                manager |= override && limit == null;
            }
            assertTrue(lowAssistantLimit != null, "an assistant with a low return limit");
            assertTrue(supervisorLimit != null && supervisorLimit.compareTo(lowAssistantLimit) > 0,
                "a supervisor with a higher limit");
            assertTrue(supervisorLimit.compareTo(max) < 0);
            assertTrue(noReturn, "an assistant without RETURN");
            assertTrue(manager, "a manager without a return limit row");
            assertTrue(inactive, "an inactive staff member");
            assertTrue(auto, "an auto-sign-on staff member");
            assertTrue(cannotSignOn, "a staff member who may not sign on");

            List<Item> items = catalogue.items();
            assertTrue(items.size() >= 12);
            boolean aboveAssistant = false;
            boolean aboveSupervisor = false;
            boolean aboveMax = false;
            for (Item item : items) {
                BigDecimal deposit = item.unitDeposit();
                aboveAssistant |= item.active() && deposit.compareTo(lowAssistantLimit) > 0
                    && deposit.compareTo(supervisorLimit) <= 0;
                aboveSupervisor |= item.active() && deposit.compareTo(supervisorLimit) > 0
                    && deposit.compareTo(max) <= 0;
                aboveMax |= item.active() && deposit.compareTo(max) > 0;
            }
            assertTrue(aboveAssistant && aboveSupervisor && aboveMax);
            assertTrue(items.stream().anyMatch(x -> x.active() && x.inductionRequired()));
            assertTrue(items.stream().anyMatch(x -> x.active() && x.perDay()));
            assertTrue(items.stream().anyMatch(x -> !x.active()));

            List<WaiverRule> effective = waiverRules.effectiveOn(TODAY);
            assertEquals(1, effective.size(), "exactly one effective waiver rule");
            WaiverRule rule = effective.get(0);
            assertEquals(WaiverRule.BORROW_N_WAIVE_M, rule.kind());
            assertEquals(2, rule.borrowQty());
            assertEquals(1, rule.waivedQty());
            assertTrue(rule.itemCodes().size() >= 2 && rule.itemCodes().size() <= 3);
            assertTrue(waiverRules.waiverRules().stream().anyMatch(x -> !x.active()), "an inactive waiver rule");
            assertTrue(waiverRules.waiverRules().stream().anyMatch(x -> x.active() && !x.effectiveOn(TODAY)),
                "an active waiver rule outside its window");
        }
    }

    @Test
    void seedsDiffer() throws Exception {
        try (LendingDb a = create(Seed.A); LendingDb b = create(Seed.B)) {
            assertDisjoint(column(a, "SELECT code FROM staff"), column(b, "SELECT code FROM staff"));
            assertDisjoint(column(a, "SELECT name FROM staff"), column(b, "SELECT name FROM staff"));
            assertDisjoint(column(a, "SELECT code FROM item"), column(b, "SELECT code FROM item"));
            assertDisjoint(column(a, "SELECT barcode FROM item"), column(b, "SELECT barcode FROM item"));
            assertDisjoint(column(a, "SELECT title FROM item"),
                column(b, "SELECT title FROM item"));
            assertDisjoint(column(a, "SELECT id FROM role"), column(b, "SELECT id FROM role"));
            assertDisjoint(column(a, "SELECT code FROM waiver_rule"), column(b, "SELECT code FROM waiver_rule"));
            assertDisjoint(column(a, "SELECT amount FROM role_cap"), column(b, "SELECT amount FROM role_cap"));
            assertFalse(column(a, "SELECT value FROM desk_setting WHERE name = 'max_return'")
                .equals(column(b, "SELECT value FROM desk_setting WHERE name = 'max_return'")));
        }
    }

    @Test
    void ruleDatesFollowTheBusinessDate() throws Exception {
        LocalDate later = TODAY.plusDays(400);
        try (LendingDb db = LendingDb.create("dbtest_dates_" + COUNTER.incrementAndGet(), Seed.A, later)) {
            WaiverEngine waiverRules = WaiverEngine.load(db.connection());
            assertEquals(1, waiverRules.effectiveOn(later).size());
            assertTrue(waiverRules.effectiveOn(TODAY).isEmpty());
        }
    }

    @Test
    void rejectsBadNamesAndSeeds() {
        assertThrows(IllegalArgumentException.class, () -> LendingDb.create("bad;name", Seed.A, TODAY));
        assertThrows(IllegalArgumentException.class, () -> Seed.parse("C"));
        assertEquals(Seed.A, Seed.parse(null));
        assertEquals(Seed.B, Seed.parse(" b "));
    }

    private static void assertDisjoint(List<String> a, List<String> b) {
        Set<String> common = new HashSet<String>(a);
        common.retainAll(b);
        assertTrue(common.isEmpty(), "values shared by both seeds: " + common);
        assertFalse(a.isEmpty());
    }
}
