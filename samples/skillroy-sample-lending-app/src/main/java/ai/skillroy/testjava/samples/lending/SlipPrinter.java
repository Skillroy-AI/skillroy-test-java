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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The desk's printer: formats a recorded loan into a fixed-width loan slip, keeps the most recent slips and tells
 * listeners. The user interface's slip panel is one such listener.
 *
 * <p>The format is deterministic: it depends only on the loan and the header (no clock), so the same loan always
 * prints the same text. Layout, {@value #WIDTH} columns:
 * <pre>
 * header line(s)
 * LOAN 1001                      STAFF 100      ("RETURN 1002 ..." for a return)
 * ----------------------------------------
 * per line:  title
 *              code  qty x unit deposit        amount    (" day"/" days" after the quantity of a per-day item)
 * ----------------------------------------
 * SUBTOTAL                            amount    (only when there are waivers)
 * WAIVER &lt;rule code&gt;                -amount    (one per waiver)
 * DEPOSIT DUE                         amount
 * RECEIVED                            amount    (one per deposit)
 * CHANGE                              amount
 * RETURN_OVERRIDE AUTHORISED BY &lt;code&gt;         (one per authorisation)
 * ----------------------------------------
 * </pre>
 *
 * <p>Thread-safe. {@link #print} is called by the engine thread; the accessors may be called from any thread.
 */
public final class SlipPrinter {

    /** Characters per slip line. */
    public static final int WIDTH = 40;
    /** How many slips are kept; older ones are discarded. */
    public static final int KEEP = 100;

    private static final Logger LOG = Logger.getLogger(SlipPrinter.class.getName());

    private final String header;
    private final Deque<Slip> slips = new ArrayDeque<Slip>();
    private final CopyOnWriteArrayList<SlipListener> listeners = new CopyOnWriteArrayList<SlipListener>();
    private int count;

    /** @param header text printed at the top of every slip ({@code "\n"} separates lines); null for none */
    public SlipPrinter(String header) {
        this.header = header;
    }

    /**
     * Prints a posted loan: formats it, stores the slip and notifies listeners on the calling thread.
     *
     * @param loan a posted loan (with an id)
     * @return the slip
     */
    public Slip print(Loan loan) {
        if (!loan.posted()) {
            throw new IllegalArgumentException("only a posted loan can be printed");
        }
        Slip slip = new Slip(loan.id(), format(loan, header));
        synchronized (slips) {
            slips.addLast(slip);
            while (slips.size() > KEEP) {
                slips.removeFirst();
            }
            count++;
        }
        for (SlipListener listener : listeners) {
            try {
                listener.printed(slip);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "slip listener failed", e);
            }
        }
        return slip;
    }

    /** @return the kept slips, oldest first */
    public List<Slip> slips() {
        synchronized (slips) {
            return Collections.unmodifiableList(new ArrayList<Slip>(slips));
        }
    }

    /** @return the most recent slip, or null if none */
    public Slip last() {
        synchronized (slips) {
            return slips.peekLast();
        }
    }

    /** @return up to {@code n} most recent slips, newest first */
    public List<Slip> last(int n) {
        List<Slip> list = new ArrayList<Slip>();
        synchronized (slips) {
            Iterator<Slip> it = slips.descendingIterator();
            while (it.hasNext() && list.size() < n) {
                list.add(it.next());
            }
        }
        return Collections.unmodifiableList(list);
    }

    /** @return how many slips were printed since start-up (including discarded ones) */
    public int count() {
        synchronized (slips) {
            return count;
        }
    }

    /** Discards the kept slips (the count is kept). */
    public void clear() {
        synchronized (slips) {
            slips.clear();
        }
    }

    /** @return the header printed at the top of every slip, or null */
    public String header() {
        return header;
    }

    /** Adds a listener. Thread-safe. */
    public void addListener(SlipListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Removes a listener. Thread-safe. */
    public void removeListener(SlipListener listener) {
        listeners.remove(listener);
    }

    /**
     * Formats a recorded loan. Pure function.
     *
     * @param loan the loan (id may be null for a preview; it prints as "-")
     * @param header header text, or null
     * @return the slip lines
     */
    public static List<String> format(Loan loan, String header) {
        List<String> out = new ArrayList<String>();
        if (header != null && !header.trim().isEmpty()) {
            for (String h : header.split("\n")) {
                out.add(clip(h));
            }
        }
        String rule = repeat('-', WIDTH);
        out.add(row((loan.returning() ? "RETURN " : "LOAN ") + (loan.id() == null ? "-" : loan.id()),
            "STAFF " + loan.staffCode()));
        out.add(rule);
        for (LoanLine line : loan.lines()) {
            out.add(clip(line.title() == null ? line.code() : line.title()));
            String qty = Deposits.formatLineQty(line.qty(), line.perDay());
            out.add(row("  " + line.code() + "  " + qty + " x " + Deposits.format(line.unitDeposit()),
                Deposits.format(line.amount())));
        }
        out.add(rule);
        if (!loan.waivers().isEmpty()) {
            out.add(row("SUBTOTAL", Deposits.format(loan.subtotal())));
            for (LoanWaiver w : loan.waivers()) {
                out.add(row("WAIVER " + w.ruleCode(), Deposits.format(w.amount().negate())));
            }
        }
        out.add(row("DEPOSIT DUE", Deposits.format(loan.total())));
        for (LoanDeposit d : loan.deposits()) {
            out.add(row("RECEIVED", Deposits.format(d.amount())));
        }
        BigDecimal change = loan.change();
        out.add(row("CHANGE", Deposits.format(change == null ? BigDecimal.ZERO : change)));
        for (LoanAuthorisation a : loan.authorisations()) {
            out.add(clip(a.kind() + " AUTHORISED BY " + a.staffCode()));
        }
        out.add(rule);
        return out;
    }

    private static String row(String left, String right) {
        int room = WIDTH - right.length() - 1;
        String l = left.length() > room ? left.substring(0, Math.max(0, room)) : left;
        return l + repeat(' ', WIDTH - l.length() - right.length()) + right;
    }

    private static String clip(String s) {
        return s.length() > WIDTH ? s.substring(0, WIDTH) : s;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
