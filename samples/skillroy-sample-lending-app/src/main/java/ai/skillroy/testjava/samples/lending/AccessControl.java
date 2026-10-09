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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Staff, permissions and monetary limits: the desk's own access rules, used by the engine for sign-on and
 * returns and usable by anything else that needs the same answers.
 *
 * <p>Rules:
 * <ul>
 *   <li>A staff member's permissions are the union of {@code role_permission} over their roles
 *       ({@code staff_role}). Unknown and inactive staff have no permission.</li>
 *   <li>A staff member's limit of a kind (only {@code RETURN} is used) is the maximum {@code role_cap.amount} over
 *       their roles' rows of that kind; when none of their roles has a row of that kind the limit is unlimited
 *       ({@code null}). A role without a row does not make the limit unlimited if another role has one.</li>
 *   <li>The system maximum return is {@code desk_setting max_return}; null when not configured.</li>
 * </ul>
 *
 * <p>In the branch edition ({@link Edition#BRANCH}, phase6-contracts.md 11.3) the limit column is
 * {@code role_cap.ceiling} and the system maximum's setting is {@code return_ceiling}; the rules are the same. The
 * access decision ({@link #check}) reads the return limit through {@link #limit}, so that {@link #returnLimit} is
 * called by the engine's return path alone (phase6-contracts.md 6.3).
 *
 * <p>The data is read once by {@link #load} at start-up; the database is not written while the desk runs.
 * Immutable after loading and safe to call from any thread.
 */
public final class AccessControl {

    /** Permission to sign on. */
    public static final String SIGN_ON = "SIGN_ON";
    /** Permission to enter return lines (up to the staff member's return limit). */
    public static final String RETURN = "RETURN";
    /** Permission to authorise another staff member's return above their limit (up to one's own limit). */
    public static final String RETURN_OVERRIDE = "RETURN_OVERRIDE";
    /** {@code role_cap.kind} of return limits. */
    public static final String LIMIT_RETURN = "RETURN";
    /** {@code desk_setting} name of the system maximum return (the standard edition's; see {@link Edition}). */
    public static final String SETTING_MAX_RETURN = "max_return";

    private static final class StaffRecord {
        final Staff staff;
        final String password;
        final Set<Integer> roles = new TreeSet<Integer>();

        StaffRecord(Staff staff, String password) {
            this.staff = staff;
            this.password = password;
        }
    }

    private final Map<String, StaffRecord> staff;
    private final Map<Integer, Set<String>> rolePermissions;
    private final Map<Integer, Map<String, BigDecimal>> roleCaps;
    private final BigDecimal systemMaxReturn;
    /** Calls of {@link #returnLimit}, for the application's own tests of which path calls it (P6 13.1 item 6). */
    final AtomicLong returnLimitCalls = new AtomicLong();

    private AccessControl(Map<String, StaffRecord> staff, Map<Integer, Set<String>> rolePermissions,
                          Map<Integer, Map<String, BigDecimal>> roleCaps, BigDecimal systemMaxReturn) {
        this.staff = staff;
        this.rolePermissions = rolePermissions;
        this.roleCaps = roleCaps;
        this.systemMaxReturn = systemMaxReturn;
    }

    /**
     * Reads staff, roles, permissions, limits and the system maximum return of a standard-edition database.
     *
     * @param connection any connection to the lending database; not closed
     */
    public static AccessControl load(Connection connection) throws SQLException {
        return load(connection, Edition.STANDARD);
    }

    /**
     * Reads staff, roles, permissions, limits and the system maximum return from the columns and settings of
     * {@code edition}.
     *
     * @param connection any connection to the lending database; not closed
     */
    public static AccessControl load(Connection connection, Edition edition) throws SQLException {
        Map<String, StaffRecord> staff = new LinkedHashMap<String, StaffRecord>();
        Map<Integer, Set<String>> permissions = new HashMap<Integer, Set<String>>();
        Map<Integer, Map<String, BigDecimal>> limits = new HashMap<Integer, Map<String, BigDecimal>>();
        BigDecimal max = null;
        try (Statement st = connection.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT code, name, password, active, auto_sign_on FROM staff ORDER BY code")) {
                while (rs.next()) {
                    Staff s = new Staff(rs.getString(1), rs.getString(2), rs.getBoolean(4), rs.getBoolean(5));
                    staff.put(s.code(), new StaffRecord(s, rs.getString(3)));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT staff_code, role_id FROM staff_role")) {
                while (rs.next()) {
                    StaffRecord record = staff.get(rs.getString(1));
                    if (record != null) {
                        record.roles.add(rs.getInt(2));
                    }
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT role_id, permission FROM role_permission")) {
                while (rs.next()) {
                    Set<String> set = permissions.get(rs.getInt(1));
                    if (set == null) {
                        set = new TreeSet<String>();
                        permissions.put(rs.getInt(1), set);
                    }
                    set.add(rs.getString(2));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT role_id, kind, " + edition.limitColumn() + " FROM role_cap")) {
                while (rs.next()) {
                    Map<String, BigDecimal> byKind = limits.get(rs.getInt(1));
                    if (byKind == null) {
                        byKind = new HashMap<String, BigDecimal>();
                        limits.put(rs.getInt(1), byKind);
                    }
                    byKind.put(rs.getString(2), Deposits.money(rs.getBigDecimal(3)));
                }
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT value FROM desk_setting WHERE name = '" + edition.maxReturnSetting() + "'")) {
                if (rs.next() && rs.getString(1) != null) {
                    max = Deposits.money(new BigDecimal(rs.getString(1).trim()));
                }
            }
        }
        return new AccessControl(staff, permissions, limits, max);
    }

    /** @return the staff member with this code (active or not), or null if unknown */
    public Staff findStaff(String code) {
        StaffRecord record = code == null ? null : staff.get(code.trim());
        return record == null ? null : record.staff;
    }

    /** @return every staff member, sorted by code */
    public List<Staff> staff() {
        List<Staff> list = new ArrayList<Staff>(staff.size());
        for (StaffRecord record : staff.values()) {
            list.add(record.staff);
        }
        return Collections.unmodifiableList(list);
    }

    /** @return true if the code is known and the password is exactly the stored one (active or not) */
    public boolean passwordMatches(String code, String password) {
        StaffRecord record = code == null ? null : staff.get(code.trim());
        return record != null && record.password != null && record.password.equals(password);
    }

    /** @return the role ids of a staff member, sorted; empty if unknown */
    public Set<Integer> roles(String code) {
        StaffRecord record = code == null ? null : staff.get(code.trim());
        return record == null ? Collections.<Integer>emptySet() : Collections.unmodifiableSet(record.roles);
    }

    /** @return the union of the permissions of the staff member's roles (ignoring {@code active}); sorted */
    public Set<String> permissions(String code) {
        Set<String> result = new TreeSet<String>();
        for (Integer role : roles(code)) {
            Set<String> set = rolePermissions.get(role);
            if (set != null) {
                result.addAll(set);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** @return true if the staff member is known, active and one of their roles grants the permission */
    public boolean hasPermission(String code, String permission) {
        Staff s = findStaff(code);
        return s != null && s.active() && permissions(code).contains(permission);
    }

    /**
     * @param code staff code
     * @param kind limit kind, for example {@link #LIMIT_RETURN}
     * @return the maximum amount over the staff member's roles' rows of that kind, or null when there is no such
     *     row (unlimited) or the staff member is unknown
     */
    public BigDecimal limit(String code, String kind) {
        BigDecimal max = null;
        boolean any = false;
        for (Integer role : roles(code)) {
            Map<String, BigDecimal> byKind = roleCaps.get(role);
            BigDecimal amount = byKind == null ? null : byKind.get(kind);
            if (amount != null) {
                max = !any || amount.compareTo(max) > 0 ? amount : max;
                any = true;
            }
        }
        return max;
    }

    /**
     * The limit the standard edition's return path applies to a return line and to a supervisor's authorisation;
     * nothing else calls it (the access decision reads {@link #limit} directly, phase6-contracts.md 6.3).
     *
     * @return the staff member's return limit, null for unlimited (or unknown staff: check {@link #findStaff})
     */
    public BigDecimal returnLimit(String code) {
        returnLimitCalls.incrementAndGet();
        return limit(code, LIMIT_RETURN);
    }

    /** @return {@code desk_setting max_return}, or null when not configured (no maximum) */
    public BigDecimal systemMaxReturn() {
        return systemMaxReturn;
    }

    /**
     * @param amount the return value (its absolute value is used)
     * @return true if the staff member may accept a return of this value on their own authority
     */
    public boolean canReturn(String code, BigDecimal amount) {
        return check(code, RETURN, amount).allowed();
    }

    /**
     * @param amount the return value (its absolute value is used)
     * @return true if the staff member may authorise a return of this amount: active, {@link #RETURN_OVERRIDE},
     *     their return limit is unlimited or at least the amount, and the amount is within the system maximum
     */
    public boolean canAuthoriseReturn(String code, BigDecimal amount) {
        return check(code, RETURN_OVERRIDE, amount).allowed();
    }

    /**
     * The desk's complete access decision for one staff member, permission and optional amount.
     *
     * <p>For {@link #RETURN} and {@link #RETURN_OVERRIDE} with an amount, the amount must also be within the staff
     * member's return limit and the system maximum. For other permissions the amount is ignored.
     *
     * @param code staff code
     * @param permission permission name
     * @param amount amount to check, or null to check the permission only (its absolute value is used)
     */
    public AccessDecision check(String code, String permission, BigDecimal amount) {
        Staff s = findStaff(code);
        if (s == null) {
            return new AccessDecision(false, null, "unknown staff code");
        }
        boolean limited = RETURN.equals(permission) || RETURN_OVERRIDE.equals(permission);
        BigDecimal limit = limited ? limit(code, LIMIT_RETURN) : null;
        if (!s.active()) {
            return new AccessDecision(false, limit, "staff member is not active");
        }
        if (!permissions(code).contains(permission)) {
            return new AccessDecision(false, limit, "missing permission " + permission);
        }
        if (amount == null || !limited) {
            return new AccessDecision(true, limit, "permitted");
        }
        BigDecimal value = Deposits.money(amount.abs());
        if (systemMaxReturn != null && value.compareTo(systemMaxReturn) > 0) {
            return new AccessDecision(false, limit,
                "amount exceeds the system maximum " + Deposits.format(systemMaxReturn));
        }
        if (limit != null && value.compareTo(limit) > 0) {
            return new AccessDecision(false, limit, "amount exceeds the limit " + Deposits.format(limit));
        }
        return new AccessDecision(true, limit, "permitted");
    }
}
