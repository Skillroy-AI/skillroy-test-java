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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** BORROW_N_WAIVE_M evaluation: the lowest deposits are waived, deterministic, date windows. */
class WaiverEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    private static Item item(String code, String deposit) {
        return new Item(code, code + "-bc", code, new BigDecimal(deposit), true, false, false);
    }

    private static WaiverEngine engine(int borrow, int waived, String... itemCodes) {
        return new WaiverEngine(Collections.singletonList(new WaiverRule(1, "W", "rule",
            WaiverRule.BORROW_N_WAIVE_M, borrow, waived, true, TODAY.minusDays(1), TODAY.plusDays(1),
            Arrays.asList(itemCodes))));
    }

    private static List<LoanLine> lines(Object... codeDepositQty) {
        List<LoanLine> list = new ArrayList<LoanLine>();
        for (int i = 0; i < codeDepositQty.length; i += 3) {
            list.add(LoanLine.of(list.size() + 1, item((String) codeDepositQty[i], (String) codeDepositQty[i + 1]),
                BigDecimal.valueOf((Integer) codeDepositQty[i + 2]), false));
        }
        return list;
    }

    private static String waiver(WaiverEngine e, List<LoanLine> lines) {
        List<LoanWaiver> w = e.evaluate(lines, TODAY);
        return w.isEmpty() ? "none" : Deposits.format(w.get(0).amount());
    }

    @Test
    void lowestDepositsAcrossItemsAreWaived() {
        WaiverEngine e = engine(2, 1, "A", "B", "C");
        assertEquals("none", waiver(e, lines("A", "5.00", 2)));
        assertEquals("1.00", waiver(e, lines("A", "5.00", 2, "B", "1.00", 1)));
        assertEquals("2.00", waiver(e, lines("A", "5.00", 3, "B", "1.00", 3)),
            "6 units, 2 waived: the two lowest deposits overall");
        assertEquals("2.00", waiver(e, lines("A", "5.00", 4, "C", "1.00", 3)), "7 units, 2 waived");
        assertEquals("none", waiver(e, lines("A", "5.00", 1, "X", "1.00", 5)), "X is not in the rule");
    }

    @Test
    void returnAndPerDayLinesDoNotCount() {
        WaiverEngine e = engine(2, 1, "A");
        List<LoanLine> list = new ArrayList<LoanLine>(lines("A", "2.00", 2));
        list.add(LoanLine.of(3, item("A", "2.00"), BigDecimal.ONE, true));
        assertEquals("none", waiver(e, list));
        Item perDay = new Item("A", "w", "A", new BigDecimal("2.00"), true, false, true);
        list = new ArrayList<LoanLine>(lines("A", "2.00", 2));
        list.add(LoanLine.of(3, perDay, new BigDecimal("1.5"), false));
        assertEquals("none", waiver(e, list));
    }

    @Test
    void deterministicAndOrderIndependent() {
        WaiverEngine e = engine(1, 1, "A", "B");
        List<LoanLine> one = lines("A", "1.00", 1, "B", "1.00", 1, "A", "3.00", 1, "B", "3.00", 1);
        List<LoanLine> two = new ArrayList<LoanLine>(one);
        Collections.reverse(two);
        assertEquals("2.00", waiver(e, one));
        assertEquals(waiver(e, one), waiver(e, two));
    }

    @Test
    void onlyEffectiveWaiverRulesApply() {
        List<WaiverRule> list = Arrays.asList(
            new WaiverRule(2, "OFF", "inactive", WaiverRule.BORROW_N_WAIVE_M, 1, 1, false, null, null,
                Collections.singletonList("A")),
            new WaiverRule(1, "LATER", "future", WaiverRule.BORROW_N_WAIVE_M, 1, 1, true, TODAY.plusDays(1), null,
                Collections.singletonList("A")),
            new WaiverRule(3, "OTHER", "unknown kind", "PERCENT_OFF", 1, 1, true, null, null,
                Collections.singletonList("A")),
            new WaiverRule(4, "NOW", "open window", WaiverRule.BORROW_N_WAIVE_M, 1, 1, true, null, TODAY,
                Collections.singletonList("A")));
        WaiverEngine e = new WaiverEngine(list);
        List<LoanWaiver> w = e.evaluate(lines("A", "2.00", 2), TODAY);
        assertEquals(1, w.size());
        assertEquals("NOW", w.get(0).ruleCode());
        assertTrue(e.evaluate(lines("A", "2.00", 2), TODAY.plusDays(1)).stream()
            .anyMatch(x -> x.ruleCode().equals("LATER")));
        List<WaiverRule> rules = e.waiverRules();
        assertEquals(Arrays.asList(1, 2, 3, 4), Arrays.asList(rules.get(0).id(), rules.get(1).id(), rules.get(2).id(),
            rules.get(3).id()));
    }

    @Test
    void dryRunUsesCatalogueDeposits() {
        Catalogue catalogue = new Catalogue(Arrays.asList(item("A", "1.50"), item("B", "0.75")));
        WaiverEngine e = engine(2, 1, "A", "B");
        Map<String, Integer> units = new LinkedHashMap<String, Integer>();
        units.put("A", 2);
        units.put("B", 1);
        units.put("UNKNOWN", 4);
        assertEquals(new BigDecimal("0.75"), e.dryRun(units, catalogue, TODAY).get(0).amount());
    }
}
