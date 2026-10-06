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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Slip format and printer bookkeeping. */
class SlipPrinterTest {

    private static Loan loan() {
        Item spade = new Item("YRD-001", "1", "Garden spade", new BigDecimal("3.50"), true, false, false);
        Item cleaner = new Item("CLN-001", "2", "Carpet cleaner, per day", new BigDecimal("1.20"), true, false,
            true);
        List<LoanLine> lines = Arrays.asList(LoanLine.of(1, spade, new BigDecimal("3"), false),
            LoanLine.of(2, cleaner, new BigDecimal("0.250"), false));
        return Loan.open("100", false).withLines(lines)
            .withWaivers(Arrays.asList(new LoanWaiver("B2W1-GARDEN", new BigDecimal("3.50"))))
            .withDeposit(new LoanDeposit(new BigDecimal("10.00")), new BigDecimal("2.70"))
            .posted(1001L, LocalDateTime.of(2026, 10, 1, 10, 0));
    }

    @Test
    void formatIsDeterministicAndFixedWidth() {
        List<String> lines = SlipPrinter.format(loan(), "TOOL LIBRARY");
        assertEquals(Arrays.asList(
            "TOOL LIBRARY",
            "LOAN 1001                      STAFF 100",
            "----------------------------------------",
            "Garden spade",
            "  YRD-001  3 x 3.50                10.50",
            "Carpet cleaner, per day",
            "  CLN-001  0.250 days x 1.20        0.30",
            "----------------------------------------",
            "SUBTOTAL                           10.80",
            "WAIVER B2W1-GARDEN                 -3.50",
            "DEPOSIT DUE                         7.30",
            "RECEIVED                           10.00",
            "CHANGE                              2.70",
            "----------------------------------------"), lines);
        assertEquals(lines, SlipPrinter.format(loan(), "TOOL LIBRARY"));
        for (String line : lines) {
            assertTrue(line.length() <= SlipPrinter.WIDTH, line);
        }
    }

    @Test
    void returnSlipNamesTheAuthorisingStaff() {
        Item drill = new Item("PWR-001", "3", "Cordless drill", new BigDecimal("34.99"), true, false, false);
        Loan returning = Loan.open("100", true)
            .withLines(Arrays.asList(LoanLine.of(1, drill, BigDecimal.ONE, true)))
            .withAuthorisation(new LoanAuthorisation("RETURN_OVERRIDE", "200"))
            .withDeposit(new LoanDeposit(new BigDecimal("-34.99")), BigDecimal.ZERO)
            .posted(1002L, LocalDateTime.of(2026, 10, 1, 10, 0));
        String text = String.join("\n", SlipPrinter.format(returning, null));
        assertTrue(text.startsWith("RETURN 1002"), text);
        assertTrue(text.contains("  PWR-001  1 x 34.99              -34.99"), text);
        assertTrue(text.contains("\nDEPOSIT DUE                       -34.99\n"), text);
        assertTrue(text.contains("\nRECEIVED                          -34.99\n"), text);
        assertTrue(text.contains("\nCHANGE                              0.00\n"), text);
        assertTrue(text.contains("RETURN_OVERRIDE AUTHORISED BY 200"), text);
    }

    @Test
    void printerKeepsSlipsAndNotifies() {
        SlipPrinter printer = new SlipPrinter("HEADER");
        List<Slip> seen = new ArrayList<Slip>();
        printer.addListener(seen::add);
        SlipListener failing = r -> {
            throw new IllegalStateException("a failing slip listener is logged and ignored");
        };
        printer.addListener(failing);
        assertNull(printer.last());
        Slip r = printer.print(loan());
        assertEquals(1001L, r.loanId());
        assertEquals(r, printer.last());
        assertEquals(Arrays.asList(r), seen);
        assertEquals(1, printer.count());
        assertTrue(r.text().startsWith("HEADER\nLOAN 1001"));
        printer.removeListener(failing);
        for (int i = 0; i < SlipPrinter.KEEP + 5; i++) {
            printer.print(loan());
        }
        assertEquals(SlipPrinter.KEEP, printer.slips().size());
        assertEquals(SlipPrinter.KEEP + 6, printer.count());
        assertEquals(3, printer.last(3).size());
        printer.clear();
        assertNull(printer.last());
        assertThrows(IllegalArgumentException.class, () -> printer.print(Loan.open("100", false)));
    }
}
