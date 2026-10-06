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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * The data of seeds {@link Seed#A} and {@link Seed#B}: two tool libraries. Waiver rule dates are relative to the
 * business date passed in, so the seeds never go stale.
 *
 * <p>Both seeds contain the same kinds of people and items with different codes, names, deposits and limits:
 * a desk assistant with a low return limit, an assistant without the {@code RETURN} permission, a supervisor with
 * {@code RETURN_OVERRIDE} and a high limit, a manager with no return limit row (unlimited), an inactive staff
 * member, an auto-sign-on staff member, a staff member whose roles cannot sign on (the workshop), hand tools with
 * small deposits, a power tool whose return is above the assistant limit but below the system maximum, one above the
 * supervisor's limit but below the maximum, a high-value item above the maximum, an induction-required item, a
 * per-day item, a retired (inactive) item, one effective borrow-2-waive-1 waiver rule, one inactive waiver rule and
 * one outside its date window.
 *
 * <p>Passwords are stored in plain text in the {@code staff} table. This is a sample application with made-up
 * staff; a real lending desk would store salted hashes.
 */
final class SeedData {

    private SeedData() {
    }

    /** @return the first loan id of the seed */
    static long firstLoanId(Seed seed) {
        return seed == Seed.A ? 1001L : 50001L;
    }

    /** Inserts the seed's rows. */
    static void apply(Connection c, Seed seed, LocalDate today) throws SQLException {
        if (seed == Seed.A) {
            seedA(c, today);
        } else {
            seedB(c, today);
        }
    }

    private static void seedA(Connection c, LocalDate today) throws SQLException {
        settings(c, new String[][] {
            {"max_return", "500.00"},
            {"slip_header", "TOOL LIBRARY - MILL LANE"},
        });
        roles(c, new Object[][] {
            {1, "DESK_ASSISTANT"}, {2, "TRAINEE"}, {3, "SUPERVISOR"}, {4, "MANAGER"}, {5, "WORKSHOP"},
        });
        permissions(c, new Object[][] {
            {1, "SIGN_ON"}, {1, "RETURN"},
            {2, "SIGN_ON"},
            {3, "SIGN_ON"}, {3, "RETURN"}, {3, "RETURN_OVERRIDE"},
            {4, "SIGN_ON"}, {4, "RETURN"}, {4, "RETURN_OVERRIDE"},
        });
        limits(c, new Object[][] {
            {1, "RETURN", "20.00"},
            {3, "RETURN", "300.00"},
        });
        // code, name, password (plain text: sample data), active, auto_sign_on, role ids
        staff(c, new Object[][] {
            {"100", "Alice Archer", "1111", true, false, new int[] {1}},
            {"101", "Ben Baker", "2222", true, false, new int[] {2}},
            {"102", "Eve Evans", "5555", false, false, new int[] {1}},
            {"103", "Fay Fisher", "6666", true, true, new int[] {1}},
            {"104", "Gus Green", "7777", true, false, new int[] {5}},
            {"200", "Carol Cooper", "3333", true, false, new int[] {1, 3}},
            {"300", "Dan Dyer", "4444", true, false, new int[] {4}},
        });
        // code, barcode, title, deposit, active, induction_required, per_day
        items(c, new Object[][] {
            {"CLN-001", "2000000000107", "Carpet cleaner, per day", "1.20", true, false, true},
            {"HND-001", "5000000000048", "Claw hammer", "1.10", true, false, false},
            {"HND-002", "5000000000055", "Hand saw", "1.45", true, false, false},
            {"HND-003", "5000000000062", "Screwdriver set", "0.99", true, false, false},
            {"LAD-001", "5000000000086", "Extension ladder 3-section", "6.50", true, true, false},
            {"PNT-001", "5000000000031", "Paint sprayer", "4.25", true, false, false},
            {"PNT-900", "5000000000109", "Contractor paint sprayer", "649.00", true, false, false},
            {"PWR-001", "5000000000093", "Cordless drill", "34.99", true, false, false},
            {"SND-001", "5000000000123", "Floor sander", "389.00", true, false, false},
            {"STK-001", "5000000000079", "Stud finder", "1.35", true, false, false},
            {"TIL-001", "5000000000116", "Tile cutter", "4.99", false, false, false},
            {"YRD-001", "5000000000017", "Garden spade", "3.50", true, false, false},
            {"YRD-002", "5000000000024", "Garden fork", "2.95", true, false, false},
        });
        // id, code, description, kind, borrow, waived, active, starts_on, ends_on, item codes
        waiverRules(c, new Object[][] {
            {1, "B2W1-HAND", "Hand tools: borrow 2, third deposit waived", WaiverRule.BORROW_N_WAIVE_M,
                2, 1, true, today.minusDays(30), today.plusDays(30), new String[] {"HND-001", "HND-002", "HND-003"}},
            {2, "B2W1-GARDEN", "Garden tools: borrow 2, third deposit waived", WaiverRule.BORROW_N_WAIVE_M,
                2, 1, false, today.minusDays(30), today.plusDays(30), new String[] {"YRD-001", "YRD-002"}},
            {3, "B1W1-PAINT", "Painting: borrow 1, second deposit waived", WaiverRule.BORROW_N_WAIVE_M,
                1, 1, true, today.minusDays(60), today.minusDays(1), new String[] {"PNT-001"}},
        });
    }

    private static void seedB(Connection c, LocalDate today) throws SQLException {
        settings(c, new String[][] {
            {"max_return", "350.00"},
            {"slip_header", "TOOL LIBRARY - CANAL STREET"},
        });
        roles(c, new Object[][] {
            {11, "COUNTER_STAFF"}, {12, "APPRENTICE"}, {13, "TEAM_LEADER"}, {14, "SITE_MANAGER"},
            {15, "CARETAKER"}, {16, "RETURNS_DESK"},
        });
        permissions(c, new Object[][] {
            {11, "SIGN_ON"}, {11, "RETURN"},
            {12, "SIGN_ON"},
            {13, "SIGN_ON"}, {13, "RETURN"}, {13, "RETURN_OVERRIDE"},
            {14, "SIGN_ON"}, {14, "RETURN"}, {14, "RETURN_OVERRIDE"},
            {16, "RETURN"},
        });
        limits(c, new Object[][] {
            {11, "RETURN", "15.00"},
            {13, "RETURN", "200.00"},
            {16, "RETURN", "40.00"},
        });
        // code, name, password (plain text: sample data), active, auto_sign_on, role ids
        staff(c, new Object[][] {
            {"4711", "Hana Hill", "9182", true, false, new int[] {11}},
            {"4712", "Ivan Irwin", "7364", true, false, new int[] {12}},
            {"4713", "Lee Lowe", "1357", false, false, new int[] {11}},
            {"4714", "Mo Moss", "2468", true, true, new int[] {11}},
            {"4715", "Ned Noble", "8642", true, false, new int[] {11, 16}},
            {"4716", "Ola Owen", "1029", true, false, new int[] {15}},
            {"4720", "Jill Jones", "5546", true, false, new int[] {13}},
            {"4790", "Kit Knox", "3928", true, false, new int[] {14}},
        });
        // code, barcode, title, deposit, active, induction_required, per_day
        items(c, new Object[][] {
            {"T1001", "4006000000011", "Bike pump", "0.89", true, false, false},
            {"T1002", "4006000000028", "Tyre levers", "0.79", true, false, false},
            {"T1003", "4006000000035", "Spirit level", "1.99", true, false, false},
            {"T1004", "4006000000042", "Caulking gun", "2.80", true, false, false},
            {"T1005", "4006000000059", "Pipe wrench", "2.15", true, false, false},
            {"T1006", "4006000000066", "Socket set", "3.60", true, false, false},
            {"T1007", "2006000000013", "Pressure washer, per day", "2.40", true, false, true},
            {"T1008", "4006000000073", "Angle grinder", "8.99", true, true, false},
            {"T1009", "4006000000080", "Jigsaw", "27.50", true, false, false},
            {"T1010", "4006000000097", "Tile saw", "249.00", true, false, false},
            {"T1011", "4006000000103", "Scaffold tower", "429.00", true, false, false},
            {"T1012", "4006000000110", "Bulb planter", "1.25", false, false, false},
            {"T1013", "4006000000127", "Puncture repair kit", "1.15", true, false, false},
        });
        // id, code, description, kind, borrow, waived, active, starts_on, ends_on, item codes
        waiverRules(c, new Object[][] {
            {21, "B2W1-CYCLE", "Cycle tools: borrow 2, third deposit waived", WaiverRule.BORROW_N_WAIVE_M,
                2, 1, true, today.minusDays(7), today.plusDays(7), new String[] {"T1001", "T1002", "T1013"}},
            {22, "B2W1-SEALING", "Sealing tools: borrow 2, third deposit waived", WaiverRule.BORROW_N_WAIVE_M,
                2, 1, false, today.minusDays(7), today.plusDays(30), new String[] {"T1004"}},
            {23, "B3W1-WRENCH", "Wrenches: borrow 3, fourth deposit waived", WaiverRule.BORROW_N_WAIVE_M,
                3, 1, true, today.plusDays(10), today.plusDays(40), new String[] {"T1005", "T1006"}},
        });
    }

    private static void settings(Connection c, String[][] rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO desk_setting (name, value) VALUES (?, ?)")) {
            for (String[] r : rows) {
                ps.setString(1, r[0]);
                ps.setString(2, r[1]);
                ps.executeUpdate();
            }
        }
    }

    private static void roles(Connection c, Object[][] rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO role (id, name) VALUES (?, ?)")) {
            for (Object[] r : rows) {
                ps.setInt(1, (Integer) r[0]);
                ps.setString(2, (String) r[1]);
                ps.executeUpdate();
            }
        }
    }

    private static void permissions(Connection c, Object[][] rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO role_permission (role_id, permission) VALUES (?, ?)")) {
            for (Object[] r : rows) {
                ps.setInt(1, (Integer) r[0]);
                ps.setString(2, (String) r[1]);
                ps.executeUpdate();
            }
        }
    }

    private static void limits(Connection c, Object[][] rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO role_cap (role_id, kind, amount) VALUES (?, ?, ?)")) {
            for (Object[] r : rows) {
                ps.setInt(1, (Integer) r[0]);
                ps.setString(2, (String) r[1]);
                ps.setBigDecimal(3, new BigDecimal((String) r[2]));
                ps.executeUpdate();
            }
        }
    }

    private static void staff(Connection c, Object[][] rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO staff (code, name, password, active, auto_sign_on) VALUES (?, ?, ?, ?, ?)");
             PreparedStatement roles = c.prepareStatement(
                 "INSERT INTO staff_role (staff_code, role_id) VALUES (?, ?)")) {
            for (Object[] r : rows) {
                ps.setString(1, (String) r[0]);
                ps.setString(2, (String) r[1]);
                ps.setString(3, (String) r[2]);
                ps.setBoolean(4, (Boolean) r[3]);
                ps.setBoolean(5, (Boolean) r[4]);
                ps.executeUpdate();
                for (int role : (int[]) r[5]) {
                    roles.setString(1, (String) r[0]);
                    roles.setInt(2, role);
                    roles.executeUpdate();
                }
            }
        }
    }

    private static void items(Connection c, Object[][] rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO item (code, barcode, title, deposit,"
                + " active, induction_required, per_day) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (Object[] r : rows) {
                ps.setString(1, (String) r[0]);
                ps.setString(2, (String) r[1]);
                ps.setString(3, (String) r[2]);
                ps.setBigDecimal(4, new BigDecimal((String) r[3]));
                ps.setBoolean(5, (Boolean) r[4]);
                ps.setBoolean(6, (Boolean) r[5]);
                ps.setBoolean(7, (Boolean) r[6]);
                ps.executeUpdate();
            }
        }
    }

    private static void waiverRules(Connection c, Object[][] rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO waiver_rule (id, code, description, kind,"
                + " borrow_qty, waived_qty, active, starts_on, ends_on) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement items = c.prepareStatement(
                 "INSERT INTO waiver_rule_item (rule_id, item_code) VALUES (?, ?)")) {
            for (Object[] r : rows) {
                ps.setInt(1, (Integer) r[0]);
                ps.setString(2, (String) r[1]);
                ps.setString(3, (String) r[2]);
                ps.setString(4, (String) r[3]);
                ps.setInt(5, (Integer) r[4]);
                ps.setInt(6, (Integer) r[5]);
                ps.setBoolean(7, (Boolean) r[6]);
                ps.setDate(8, Date.valueOf((LocalDate) r[7]));
                ps.setDate(9, Date.valueOf((LocalDate) r[8]));
                ps.executeUpdate();
                for (String code : (String[]) r[9]) {
                    items.setInt(1, (Integer) r[0]);
                    items.setString(2, code);
                    items.executeUpdate();
                }
            }
        }
    }
}
