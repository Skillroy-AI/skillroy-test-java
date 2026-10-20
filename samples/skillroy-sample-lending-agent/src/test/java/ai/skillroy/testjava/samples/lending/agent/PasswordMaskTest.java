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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.protocol.SecretKeys;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The module's {@link PasswordMask} (Phase 2 contract section 13.2, and Phase 8, phase8-contracts.md 5.1, 12.2, section
 * 13.1 row 21): the row rule (a {@code password} column of source {@code lending}, any case, any type, null left
 * alone) and the event rule ({@code <personal>} for the column {@code name} under {@code event:staff.signedOn} only).
 */
class PasswordMaskTest {

    private final PasswordMask mask = new PasswordMask();

    @Test
    void theRowRuleMasksThePasswordColumnOfTheLendingSourceOnly() {
        assertEquals(SecretKeys.REDACTED, mask.mask("lending", "password", "1111"));
        assertEquals(SecretKeys.REDACTED, mask.mask("lending", "PASSWORD", Long.valueOf(1111)));
        assertNull(mask.mask("lending", "password", null), "a null value stays null");
        assertEquals("Alice Archer", mask.mask("lending", "name", "Alice Archer"), "other columns pass");
        assertEquals("1111", mask.mask("other", "password", "1111"), "other sources pass");
        assertEquals(new BigDecimal("3.50"), mask.mask("lending", "deposit", new BigDecimal("3.50")));
    }

    @Test
    void theEventRuleMasksTheStaffNameOfStaffSignedOnOnly() {
        assertEquals("event:staff.signedOn", PasswordMask.EVENT_SOURCE);
        assertEquals("<personal>", mask.mask("event:staff.signedOn", "name", "Alice Archer"));
        assertEquals("<personal>", mask.mask("event:staff.signedOn", "name", "<personal>"),
                "idempotent: a second pass changes nothing");
        assertEquals("100", mask.mask("event:staff.signedOn", "code", "100"), "the code is not personal");
        assertNull(mask.mask("event:staff.signedOn", "name", null));
        assertEquals("Alice Archer", mask.mask("event:state.changed", "name", "Alice Archer"),
                "another topic passes");
        assertEquals("Alice Archer", mask.mask("lending", "name", "Alice Archer"),
                "a row column called name is never touched by the event rule");
        assertEquals("Alice Archer", mask.mask("staff.signedOn", "name", "Alice Archer"),
                "the source id is exact: the ring's event: prefix");
        assertEquals("Alice Archer", mask.mask("event:staff.signedOn", "staff.name", "Alice Archer"),
                "the column is exact");
        assertEquals("1111", mask.mask("event:staff.signedOn", "password", "1111"),
                "the row rule is the lending source's; a secret key in an event is the core's key rule");
    }
}
