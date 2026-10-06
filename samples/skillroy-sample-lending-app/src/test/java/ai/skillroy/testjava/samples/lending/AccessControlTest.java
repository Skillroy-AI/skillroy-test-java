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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Permission and limit rules against both seeds. */
class AccessControlTest {

    private static LendingDb dbA;
    private static LendingDb dbB;
    private static AccessControl a;
    private static AccessControl b;

    @BeforeAll
    static void load() throws Exception {
        dbA = LendingDb.create("access_a", Seed.A, LocalDate.of(2026, 10, 1));
        dbB = LendingDb.create("access_b", Seed.B, LocalDate.of(2026, 10, 1));
        a = AccessControl.load(dbA.connection());
        b = AccessControl.load(dbB.connection());
    }

    @AfterAll
    static void close() {
        dbA.close();
        dbB.close();
    }

    private static BigDecimal m(String s) {
        return new BigDecimal(s);
    }

    @Test
    void limitIsTheMaximumOverRolesAndNoRowIsUnlimited() {
        assertEquals(m("20.00"), a.returnLimit("100"));
        assertEquals(m("300.00"), a.returnLimit("200"), "DESK_ASSISTANT 20 and SUPERVISOR 300: the maximum");
        assertNull(a.returnLimit("300"), "MANAGER has no limit row");
        assertEquals(m("15.00"), b.returnLimit("4711"));
        assertEquals(m("40.00"), b.returnLimit("4715"), "COUNTER_STAFF 15 and RETURNS_DESK 40");
        assertNull(b.returnLimit("4790"));
        assertEquals(m("500.00"), a.systemMaxReturn());
        assertEquals(m("350.00"), b.systemMaxReturn());
        assertEquals(new TreeSet<Integer>(Arrays.asList(1, 3)), a.roles("200"));
    }

    @Test
    void permissions() {
        assertTrue(a.hasPermission("100", AccessControl.SIGN_ON));
        assertTrue(a.hasPermission("100", AccessControl.RETURN));
        assertFalse(a.hasPermission("100", AccessControl.RETURN_OVERRIDE));
        assertFalse(a.hasPermission("101", AccessControl.RETURN));
        assertFalse(a.hasPermission("102", AccessControl.SIGN_ON), "inactive staff have no permission");
        assertTrue(a.permissions("102").contains(AccessControl.SIGN_ON), "but their roles still grant it");
        assertFalse(a.hasPermission("104", AccessControl.SIGN_ON));
        assertFalse(a.hasPermission("nobody", AccessControl.SIGN_ON));
        assertTrue(a.passwordMatches("100", "1111"));
        assertFalse(a.passwordMatches("100", "1112"));
        assertFalse(a.passwordMatches("nobody", "1111"));
        assertNull(a.findStaff("4711"));
        assertTrue(b.findStaff("4714").autoSignOn());
    }

    @Test
    void returnDecisions() {
        assertTrue(a.canReturn("100", m("20.00")));
        assertFalse(a.canReturn("100", m("20.01")));
        assertTrue(a.canReturn("100", m("-20.00")), "the absolute value counts");
        assertFalse(a.canReturn("101", m("1.00")));
        assertTrue(a.canAuthoriseReturn("200", m("34.99")));
        assertFalse(a.canAuthoriseReturn("200", m("389.00")));
        assertTrue(a.canAuthoriseReturn("300", m("389.00")));
        assertFalse(a.canAuthoriseReturn("300", m("649.00")), "never above the system maximum");
        assertFalse(a.canAuthoriseReturn("100", m("1.00")));
        assertTrue(b.canAuthoriseReturn("4720", m("27.50")));
        assertFalse(b.canAuthoriseReturn("4720", m("249.00")));
    }

    @Test
    void checkExplainsItself() {
        AccessDecision d = a.check("100", AccessControl.RETURN, m("34.99"));
        assertFalse(d.allowed());
        assertEquals(m("20.00"), d.limit());
        assertEquals("amount exceeds the limit 20.00", d.reason());

        d = a.check("300", AccessControl.RETURN_OVERRIDE, m("649.00"));
        assertFalse(d.allowed());
        assertNull(d.limit());
        assertEquals("amount exceeds the system maximum 500.00", d.reason());

        d = a.check("101", AccessControl.RETURN, null);
        assertFalse(d.allowed());
        assertEquals("missing permission RETURN", d.reason());

        d = a.check("102", AccessControl.SIGN_ON, null);
        assertEquals("staff member is not active", d.reason());

        d = a.check("999", AccessControl.SIGN_ON, null);
        assertEquals("unknown staff code", d.reason());

        d = a.check("100", AccessControl.SIGN_ON, m("1000.00"));
        assertTrue(d.allowed(), "the amount is ignored for permissions without a limit");
        assertNull(d.limit());
        assertEquals("permitted", d.reason());
    }
}
