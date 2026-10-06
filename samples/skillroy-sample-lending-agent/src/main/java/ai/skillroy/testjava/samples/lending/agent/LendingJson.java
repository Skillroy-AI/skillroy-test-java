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

import ai.skillroy.testjava.samples.lending.Deposits;
import ai.skillroy.testjava.samples.lending.EngineStatus;
import ai.skillroy.testjava.samples.lending.Loan;
import ai.skillroy.testjava.samples.lending.LoanAuthorisation;
import ai.skillroy.testjava.samples.lending.LoanDeposit;
import ai.skillroy.testjava.samples.lending.LoanLine;
import ai.skillroy.testjava.samples.lending.LoanWaiver;
import ai.skillroy.testjava.samples.lending.Prompt;
import ai.skillroy.testjava.samples.lending.Slip;
import ai.skillroy.testjava.samples.lending.Staff;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The desk's immutable snapshots as JSON values. Money is always a decimal string with two decimals
 * ({@code "3.50"}, {@code "-3.50"}) and quantities are decimal strings ({@code "2"}, {@code "0.250"}), so that no
 * amount ever passes through a binary floating-point number. Stateless.
 */
final class LendingJson {

    private LendingJson() {
    }

    /**
     * {@code lending.state}: {name, prompt, validInputs, staff:{code,name}|null, returnMode, locked, awaitingInput}.
     */
    static Map<String, Object> state(EngineStatus status) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", status.state() == null ? null : status.state().name());
        m.put("prompt", status.prompt());
        m.put("validInputs", new ArrayList<String>(status.validInputs()));
        m.put("staff", staff(status.staff()));
        m.put("returnMode", Boolean.valueOf(status.returnMode()));
        m.put("locked", Boolean.valueOf(status.locked()));
        m.put("awaitingInput", Boolean.valueOf(status.awaitingInput()));
        return m;
    }

    static Map<String, Object> staff(Staff staff) {
        if (staff == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", staff.code());
        m.put("name", staff.name());
        return m;
    }

    /**
     * {@code lending.loan}: {id|null, open, returning, staffCode, lines, waivers, subtotal, waiverTotal, total,
     * deposits, change, authorisations, postedAt}. {@code loan} null gives the empty shape (no loan since start-up).
     *
     * @param open true for the open loan, false for a recorded one
     */
    static Map<String, Object> loan(Loan loan, boolean open) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        List<Object> lines = new ArrayList<Object>();
        List<Object> waivers = new ArrayList<Object>();
        List<Object> deposits = new ArrayList<Object>();
        List<Object> authorisations = new ArrayList<Object>();
        if (loan == null) {
            m.put("id", null);
            m.put("open", Boolean.FALSE);
            m.put("returning", Boolean.FALSE);
            m.put("staffCode", null);
            m.put("lines", lines);
            m.put("waivers", waivers);
            m.put("subtotal", "0.00");
            m.put("waiverTotal", "0.00");
            m.put("total", "0.00");
            m.put("deposits", deposits);
            m.put("change", null);
            m.put("authorisations", authorisations);
            m.put("postedAt", null);
            return m;
        }
        for (LoanLine line : loan.lines()) {
            Map<String, Object> l = new LinkedHashMap<String, Object>();
            l.put("lineNo", Long.valueOf(line.lineNo()));
            l.put("code", line.code());
            l.put("title", line.title());
            l.put("qty", Deposits.formatQty(line.qty()));
            l.put("unitDeposit", money(line.unitDeposit()));
            l.put("amount", money(line.amount()));
            l.put("returned", Boolean.valueOf(line.returned()));
            l.put("perDay", Boolean.valueOf(line.perDay()));
            lines.add(l);
        }
        for (LoanWaiver w : loan.waivers()) {
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("ruleCode", w.ruleCode());
            x.put("amount", money(w.amount()));
            waivers.add(x);
        }
        for (LoanDeposit d : loan.deposits()) {
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("amount", money(d.amount()));
            deposits.add(x);
        }
        for (LoanAuthorisation a : loan.authorisations()) {
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("kind", a.kind());
            x.put("staffCode", a.staffCode());
            authorisations.add(x);
        }
        m.put("id", loan.id());
        m.put("open", Boolean.valueOf(open));
        m.put("returning", Boolean.valueOf(loan.returning()));
        m.put("staffCode", loan.staffCode());
        m.put("lines", lines);
        m.put("waivers", waivers);
        m.put("subtotal", money(loan.subtotal()));
        m.put("waiverTotal", money(loan.waiverTotal()));
        m.put("total", money(loan.total()));
        m.put("deposits", deposits);
        m.put("change", money(loan.change()));
        m.put("authorisations", authorisations);
        m.put("postedAt", loan.postedAt() == null ? null : loan.postedAt().toString());
        return m;
    }

    /** {@code {id, text, buttons}}, or null. */
    static Map<String, Object> prompt(Prompt prompt) {
        if (prompt == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", prompt.id());
        m.put("text", prompt.text());
        m.put("buttons", new ArrayList<String>(prompt.buttons()));
        return m;
    }

    /** {@code {loanId, lines, text}}, or null. */
    static Map<String, Object> slip(Slip slip) {
        if (slip == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("loanId", Long.valueOf(slip.loanId()));
        m.put("lines", new ArrayList<String>(slip.lines()));
        m.put("text", slip.text());
        return m;
    }

    /** A money amount as a two-decimal string, or null. */
    static String money(BigDecimal amount) {
        return amount == null ? null : Deposits.format(amount);
    }
}
