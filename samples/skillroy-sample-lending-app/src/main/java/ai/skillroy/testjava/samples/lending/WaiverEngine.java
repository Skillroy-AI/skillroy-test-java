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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates waiver rules on the lines of a loan. Read once at start-up by {@link #load}; immutable afterwards.
 * {@link #evaluate} is a pure function of its arguments, so it doubles as a dry run on synthetic lines. Thread-safe.
 *
 * <p><b>BORROW_N_WAIVE_M.</b> For each waiver rule effective on the date (active and inside its date window), in
 * rule-id order: the units are the loan (non-return) lines of counted (not per-day) items whose item code the rule
 * covers, one unit per item of quantity. With {@code n} units, {@code waived = (n / (borrowQty + waivedQty)) *
 * waivedQty} units have their deposit waived, and they are the lowest deposits across all of the rule's items: units
 * sorted by unit deposit ascending, ties broken by item code and then by line number, and the first {@code waived}
 * are taken. The waiver is the sum of their unit deposits; a rule with no waived unit yields no waiver. A unit covered
 * by several effective rules counts in each of them (the seeds avoid such overlaps). Other kinds are ignored.
 */
public final class WaiverEngine {

    private final List<WaiverRule> waiverRules;

    /** @param waiverRules every waiver rule, active or not */
    public WaiverEngine(List<WaiverRule> waiverRules) {
        List<WaiverRule> copy = new ArrayList<WaiverRule>(waiverRules);
        Collections.sort(copy, (a, b) -> Integer.compare(a.id(), b.id()));
        this.waiverRules = Collections.unmodifiableList(copy);
    }

    /**
     * Reads every waiver rule and its items.
     *
     * @param connection any connection to the lending database; not closed
     */
    public static WaiverEngine load(Connection connection) throws SQLException {
        Map<Integer, List<String>> items = new HashMap<Integer, List<String>>();
        Map<Integer, Object[]> rows = new LinkedHashMap<Integer, Object[]>();
        try (Statement st = connection.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT rule_id, item_code FROM waiver_rule_item")) {
                while (rs.next()) {
                    List<String> list = items.get(rs.getInt(1));
                    if (list == null) {
                        list = new ArrayList<String>();
                        items.put(rs.getInt(1), list);
                    }
                    list.add(rs.getString(2));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT id, code, description, kind, borrow_qty, waived_qty, active,"
                + " starts_on, ends_on FROM waiver_rule ORDER BY id")) {
                while (rs.next()) {
                    rows.put(rs.getInt(1), new Object[] {rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getInt(6), rs.getBoolean(7), toLocal(rs.getDate(8)),
                        toLocal(rs.getDate(9))});
                }
            }
        }
        List<WaiverRule> list = new ArrayList<WaiverRule>();
        for (Map.Entry<Integer, Object[]> e : rows.entrySet()) {
            Object[] r = e.getValue();
            List<String> itemCodes = items.get(e.getKey());
            list.add(new WaiverRule(e.getKey(), (String) r[0], (String) r[1], (String) r[2], (Integer) r[3],
                (Integer) r[4], (Boolean) r[5], (LocalDate) r[6], (LocalDate) r[7],
                itemCodes == null ? Collections.<String>emptyList() : itemCodes));
        }
        return new WaiverEngine(list);
    }

    private static LocalDate toLocal(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    /** @return every waiver rule, active or not, in id order */
    public List<WaiverRule> waiverRules() {
        return waiverRules;
    }

    /** @return the waiver rules effective on the date, in id order */
    public List<WaiverRule> effectiveOn(LocalDate date) {
        List<WaiverRule> list = new ArrayList<WaiverRule>();
        for (WaiverRule rule : waiverRules) {
            if (rule.effectiveOn(date)) {
                list.add(rule);
            }
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * Computes the waivers for the lines of a loan.
     *
     * @param lines the loan lines; return and per-day lines are ignored
     * @param date the business date
     * @return one waiver per rule that waives something, in rule-id order
     */
    public List<LoanWaiver> evaluate(List<LoanLine> lines, LocalDate date) {
        List<LoanWaiver> result = new ArrayList<LoanWaiver>();
        for (WaiverRule rule : effectiveOn(date)) {
            BigDecimal waiver = benefit(rule, lines);
            if (waiver.signum() > 0) {
                result.add(new LoanWaiver(rule.code(), waiver));
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Dry run on synthetic lines of counted items.
     *
     * @param unitsByCode units per item code, in the order lines would be entered
     * @param catalogue where deposits come from; unknown item codes are ignored
     * @param date the business date
     * @return the waivers, as {@link #evaluate} computes them
     */
    public List<LoanWaiver> dryRun(Map<String, Integer> unitsByCode, Catalogue catalogue, LocalDate date) {
        List<LoanLine> lines = new ArrayList<LoanLine>();
        int lineNo = 0;
        for (Map.Entry<String, Integer> e : unitsByCode.entrySet()) {
            Item item = catalogue.findByCode(e.getKey());
            if (item != null && e.getValue() != null && e.getValue() > 0) {
                lines.add(LoanLine.of(++lineNo, item, BigDecimal.valueOf(e.getValue()), false));
            }
        }
        return evaluate(lines, date);
    }

    private static BigDecimal benefit(WaiverRule rule, List<LoanLine> lines) {
        if (!WaiverRule.BORROW_N_WAIVE_M.equals(rule.kind()) || rule.borrowQty() < 0 || rule.waivedQty() <= 0) {
            return BigDecimal.ZERO;
        }
        List<LoanLine> units = new ArrayList<LoanLine>();
        for (LoanLine line : lines) {
            if (line.returned() || line.perDay() || !rule.itemCodes().contains(line.code())
                || !Deposits.isWhole(line.qty())) {
                continue;
            }
            int n = line.qty().intValue();
            for (int i = 0; i < n; i++) {
                units.add(line);
            }
        }
        int group = rule.borrowQty() + rule.waivedQty();
        int waived = (units.size() / group) * rule.waivedQty();
        if (waived == 0) {
            return BigDecimal.ZERO;
        }
        Collections.sort(units, Comparator.comparing(LoanLine::unitDeposit)
            .thenComparing(LoanLine::code)
            .thenComparingInt(LoanLine::lineNo));
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < waived; i++) {
            sum = sum.add(units.get(i).unitDeposit());
        }
        return Deposits.money(sum);
    }
}
