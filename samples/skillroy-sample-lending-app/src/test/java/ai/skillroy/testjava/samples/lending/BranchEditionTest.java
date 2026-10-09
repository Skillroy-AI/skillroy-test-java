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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * The desk's branch edition (phase6-contracts.md 11.3, 13.1 item 6): the renamed limit column and system maximum
 * setting, the same data, the return path through {@link ReturnCeilings} in the branch edition and through
 * {@link AccessControl#returnLimit} in the standard one, and the access decision reaching {@code returnLimit} in
 * neither (phase6-contracts.md 6.3: the hook on {@code returnLimit} must mean "the return path applied this limit").
 */
class BranchEditionTest {

    private static BigDecimal money(String s) {
        return new BigDecimal(s);
    }

    @Test
    void theEditionIsReadFromItsPropertyAndDefaultsToStandard() {
        assertEquals(Edition.STANDARD, LendingOptions.defaults().edition());
        Properties props = new Properties();
        assertEquals(Edition.STANDARD, LendingOptions.fromProperties(props).edition());
        props.setProperty(LendingOptions.PROP_EDITION, " Branch ");
        assertEquals(Edition.BRANCH, LendingOptions.fromProperties(props).edition());
        props.setProperty(LendingOptions.PROP_EDITION, "regional");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> LendingOptions.fromProperties(props));
        assertTrue(e.getMessage().contains("standard or branch"), e.getMessage());
        assertEquals("branch", Edition.BRANCH.label());
        assertEquals("lending.edition", LendingOptions.PROP_EDITION);
    }

    @Test
    void theBranchSchemaRenamesTheLimitColumnAndTheMaximumSettingAndHoldsTheSameData() throws Exception {
        try (EngineHarness branch = EngineHarness.start(Seed.A, Edition.BRANCH);
             EngineHarness standard = EngineHarness.start(Seed.A)) {
            assertEquals(Edition.BRANCH, branch.db.edition());
            assertEquals(Edition.STANDARD, standard.db.edition());
            assertEquals(Arrays.asList("ROLE_ID", "KIND", "CEILING"), columns(branch, "ROLE_CAP"));
            assertEquals(Arrays.asList("ROLE_ID", "KIND", "AMOUNT"), columns(standard, "ROLE_CAP"));
            assertEquals(standard.query("SELECT role_id, kind, amount FROM role_cap ORDER BY role_id, kind"),
                branch.query("SELECT role_id, kind, ceiling FROM role_cap ORDER BY role_id, kind"));
            assertEquals(Collections.singletonList(Collections.<Object>singletonList("500.00")),
                branch.query("SELECT value FROM desk_setting WHERE name = 'return_ceiling'"));
            assertTrue(branch.query("SELECT value FROM desk_setting WHERE name = 'max_return'").isEmpty());
            assertEquals(Collections.singletonList(Collections.<Object>singletonList("500.00")),
                standard.query("SELECT value FROM desk_setting WHERE name = 'max_return'"));
            assertTrue(standard.query("SELECT value FROM desk_setting WHERE name = 'return_ceiling'").isEmpty());
            for (String table : Arrays.asList("STAFF", "ITEM", "LOAN", "LOAN_LINE", "LOAN_WAIVER",
                    "LOAN_AUTHORISATION", "DESK_SETTING")) {
                assertEquals(columns(standard, table), columns(branch, table), table + " is the same");
            }
            // The access rules read the edition's names and come to the same answers.
            assertEquals(money("500.00"), branch.access.systemMaxReturn());
            assertEquals(money("20.00"), branch.access.limit("100", AccessControl.LIMIT_RETURN));
            assertEquals(money("300.00"), branch.access.limit("200", AccessControl.LIMIT_RETURN));
            assertNull(branch.access.limit("300", AccessControl.LIMIT_RETURN));
        }
    }

    @Test
    void theStandardReturnPathCallsReturnLimitAndTheBranchPathReturnCeilings() throws Exception {
        try (EngineHarness standard = EngineHarness.start(Seed.A)) {
            assertNull(standard.engine.returnCeilings(), "the standard edition has no ceilings");
            returnHandSaw(standard);
            assertTrue(standard.access.returnLimitCalls.get() >= 1, "the return line's check");
        }
        try (EngineHarness branch = EngineHarness.start(Seed.A, Edition.BRANCH)) {
            ReturnCeilings ceilings = branch.engine.returnCeilings();
            assertNotNull(ceilings);
            returnHandSaw(branch);
            assertTrue(ceilings.calls.get() >= 1, "the return line's check");
            assertEquals(0L, branch.access.returnLimitCalls.get(), "returnLimit is never reached in the branch");
        }
    }

    @Test
    void theSupervisorsLimitComesFromTheEditionsPathToo() throws Exception {
        try (EngineHarness branch = EngineHarness.start(Seed.A, Edition.BRANCH)) {
            branch.signOn("100", "1111");
            branch.key(KeyAction.RETURN);
            branch.scan("PWR-001");
            branch.answer("OK");
            branch.entry("200");
            branch.entry("3333");
            Loan loan = branch.engine.currentLoan();
            assertEquals(money("-34.99"), loan.lines().get(0).amount());
            assertEquals("200", loan.authorisations().get(0).staffCode());
            assertTrue(branch.engine.returnCeilings().calls.get() >= 2, "the line's and the supervisor's limit");
            assertEquals(0L, branch.access.returnLimitCalls.get());
        }
    }

    /**
     * {@link AccessControl#check} (what the verifier's {@code lending.access.check} calls), {@code canReturn} and
     * {@code canAuthoriseReturn} read the limit through {@link AccessControl#limit} in both editions.
     */
    @Test
    void theAccessDecisionReachesReturnLimitInNeitherEdition() throws Exception {
        for (Edition edition : Edition.values()) {
            try (EngineHarness h = EngineHarness.start(Seed.A, edition)) {
                long before = h.access.returnLimitCalls.get();
                AccessDecision decision = h.access.check("100", AccessControl.RETURN, money("19.99"));
                assertTrue(decision.allowed(), edition + ": " + decision);
                assertEquals(money("20.00"), decision.limit());
                assertEquals(money("300.00"), h.access.check("200", AccessControl.RETURN_OVERRIDE, null).limit());
                assertTrue(h.access.canReturn("100", money("1.00")));
                assertTrue(h.access.canAuthoriseReturn("200", money("34.99")));
                assertEquals(before, h.access.returnLimitCalls.get(), edition + ": check reached returnLimit");
            }
        }
    }

    private static void returnHandSaw(EngineHarness h) throws SQLException {
        h.signOn("100", "1111");
        h.key(KeyAction.RETURN);
        h.scan("HND-002");
        LoanLine line = h.engine.currentLoan().lines().get(0);
        assertTrue(line.returned());
        assertEquals(money("-1.45"), line.amount());
        h.key(KeyAction.DUE);
        h.key(KeyAction.DEPOSIT);
        assertEquals(Collections.singletonList(Arrays.<Object>asList("-1.45")),
            h.query("SELECT total FROM loan WHERE id = 1001"));
    }

    private static List<Object> columns(EngineHarness h, String table) throws SQLException {
        List<Object> names = new ArrayList<Object>();
        for (List<Object> row : h.query("SELECT column_name FROM information_schema.columns WHERE table_schema ="
                + " 'PUBLIC' AND table_name = ? ORDER BY ordinal_position", table)) {
            names.add(row.get(0));
        }
        return names;
    }
}
