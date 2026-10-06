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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** Parsing and formatting of typed amounts and quantities. */
class DepositsTest {

    @Test
    void parsesMoney() {
        assertEquals(new BigDecimal("7.00"), Deposits.parseMoney("7"));
        assertEquals(new BigDecimal("7.50"), Deposits.parseMoney(" 7.5 "));
        assertEquals(new BigDecimal("0.00"), Deposits.parseMoney("0"));
        assertNull(Deposits.parseMoney("7.005"));
        assertNull(Deposits.parseMoney("-1"));
        assertNull(Deposits.parseMoney("1e3"));
        assertNull(Deposits.parseMoney("."));
        assertNull(Deposits.parseMoney(""));
        assertNull(Deposits.parseMoney(null));
        assertNull(Deposits.parseMoney("100000"));
    }

    @Test
    void parsesQuantities() {
        assertEquals("3", Deposits.formatQty(Deposits.parseQuantity("3")));
        assertEquals("3", Deposits.formatQty(Deposits.parseQuantity("3.000")));
        assertEquals("0.250", Deposits.formatQty(Deposits.parseQuantity("0.25")));
        assertNull(Deposits.parseQuantity("0"));
        assertNull(Deposits.parseQuantity("1.0005"));
        assertNull(Deposits.parseQuantity("1000"));
        assertNull(Deposits.parseQuantity("two"));
    }

    @Test
    void lineQuantitiesOfPerDayItemsNameTheDays() {
        assertEquals("3", Deposits.formatLineQty(new BigDecimal("3"), false));
        assertEquals("1 day", Deposits.formatLineQty(BigDecimal.ONE, true));
        assertEquals("1 day", Deposits.formatLineQty(new BigDecimal("1.000"), true));
        assertEquals("2 days", Deposits.formatLineQty(new BigDecimal("2"), true));
        assertEquals("0.250 days", Deposits.formatLineQty(new BigDecimal("0.25"), true));
    }

    @Test
    void formatsMoney() {
        assertEquals("-3.50", Deposits.format(new BigDecimal("-3.5")));
        assertEquals("0.60", Deposits.format(Deposits.lineAmount(new BigDecimal("1.20"), new BigDecimal("0.5"))));
        assertEquals("0.31", Deposits.format(Deposits.lineAmount(new BigDecimal("1.25"), new BigDecimal("0.250"))));
    }
}
