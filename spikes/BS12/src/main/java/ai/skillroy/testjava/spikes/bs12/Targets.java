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

package ai.skillroy.testjava.spikes.bs12;

import java.awt.Rectangle;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The grounding targets: a natural-language description of one element on one screen, and how the harness finds the
 * element's ground truth in that screen's snapshot. Five screens of the desk: {@code signed-off}, {@code loan} (five
 * lines), {@code induction} (the Yes/No prompt over the loan), {@code deposit} (after DUE) and {@code supervisor}
 * (return mode, the OK/Cancel prompt).
 */
final class Targets {

    /**
     * @param kind one of {@code icon-key} (the keypad's icon-only keys), {@code painted-button} (the prompt's
     *     buttons, plain components), {@code text-field}, {@code table-row}, {@code table}, {@code label}
     * @param ambiguous a deliberately ambiguous or dense target
     * @param sample part of the smaller sample run at the model's default effort
     */
    record Target(String id, String screen, String intent, String kind, boolean ambiguous, boolean sample,
                  Predicate<Screen.Node> node, int row, String rowContains) {
    }

    /** The ground truth of a target on a screen. */
    record Truth(Screen.Node node, int row, Rectangle r) {
    }

    static final List<String> SCREENS = List.of("signed-off", "loan", "induction", "deposit", "supervisor");

    static final List<Target> ALL = List.of(
        // signed off
        t("t01", "signed-off", "the field where the staff code is typed", "text-field", false, true, id("entry")),
        t("t02", "signed-off", "the key that signs the staff member off", "icon-key", false, false, key("SIGN_OFF")),
        t("t03", "signed-off", "the cancel key", "icon-key", false, false, key("CANCEL")),
        t("t04", "signed-off", "the status text at the top right that says whether anyone is signed on", "label", false,
            false, id("statusLine")),
        t("t05", "signed-off", "the empty table where loan lines will appear", "table", false, false, id("loanTable")),
        // loan in progress
        t("t06", "loan", "the key that ends item entry and works out the deposit due", "icon-key", false, true,
            key("DUE")),
        t("t07", "loan", "the quantity key", "icon-key", false, false, key("QTY")),
        t("t08", "loan", "the key that abandons this loan", "icon-key", false, false, key("CANCEL")),
        t("t09", "loan", "the key that switches the desk to return mode", "icon-key", false, true, key("RETURN")),
        t("t10", "loan", "the item entry field", "text-field", false, false, id("entry")),
        row("t11", "loan", "the second line in the loan table", true, true, 1),
        rowWith("t12", "loan", "the line for the cordless drill", false, false, "Cordless drill"),
        rowWith("t13", "loan", "the line of the item with the highest deposit", true, false, "Cordless drill"),
        t("t14", "loan", "the running totals under the table", "label", false, false, id("totalsLine")),
        t("t15", "loan", "the key that takes the deposit", "icon-key", true, true, key("DEPOSIT")),
        t("t16", "loan", "the column headings of the loan table", "table", true, false, cls("JTableHeader")),
        // the induction prompt over the loan
        t("t17", "induction", "the button that confirms the borrower completed the safety induction", "painted-button",
            false, true, prompt("Yes")),
        t("t18", "induction", "the button that answers no to the question", "painted-button", false, false,
            prompt("No")),
        // the deposit step
        t("t19", "deposit", "the key that takes the deposit", "icon-key", false, false, key("DEPOSIT")),
        t("t20", "deposit", "the field where the amount received is typed", "text-field", false, true, id("entry")),
        rowWith("t21", "deposit", "the line for the extension ladder", false, false, "Extension ladder"),
        t("t22", "deposit", "the prompt line that tells the staff member what to do next", "label", false, false,
            id("promptLine")),
        t("t23", "deposit", "the key that abandons this loan", "icon-key", false, false, key("CANCEL")),
        // return mode with the supervisor prompt
        t("t24", "supervisor", "the OK button", "painted-button", false, false, prompt("OK")),
        t("t25", "supervisor", "the Cancel button", "painted-button", true, true, prompt("Cancel")),
        t("t26", "supervisor", "the key that leaves return mode", "icon-key", false, false, key("RETURN")),
        t("t27", "supervisor", "the status text that shows the desk is in return mode", "label", false, true,
            id("statusLine")));

    private Targets() {
    }

    static List<Target> on(String screen) {
        return ALL.stream().filter(t -> t.screen().equals(screen)).toList();
    }

    /** The ground truth of {@code t} on {@code s}, or an exception naming what is missing. */
    static Truth truth(Target t, Screen s) {
        Screen.Node found = null;
        for (Screen.Node n : s.nodes) {
            if (n.showing && t.node().test(n)) {
                if (found != null) {
                    throw new IllegalStateException(t.id() + ": more than one node matches on " + s.name);
                }
                found = n;
            }
        }
        if (found == null) {
            throw new IllegalStateException(t.id() + ": no node matches on " + s.name);
        }
        int row = t.row();
        if (t.rowContains() != null) {
            row = -1;
            for (int i = 0; i < found.rowCount(); i++) {
                if (found.row(i).stream().anyMatch(c -> String.valueOf(c).contains(t.rowContains()))) {
                    row = i;
                }
            }
            if (row < 0) {
                throw new IllegalStateException(t.id() + ": no row contains " + t.rowContains());
            }
        }
        Rectangle r = row >= 0 ? s.rowRect(found, row) : found.r;
        return new Truth(found, row, r);
    }

    private static Target t(String id, String screen, String intent, String kind, boolean ambiguous, boolean sample,
                            Predicate<Screen.Node> p) {
        return new Target(id, screen, intent, kind, ambiguous, sample, p, -1, null);
    }

    private static Target row(String id, String screen, String intent, boolean ambiguous, boolean sample, int row) {
        return new Target(id, screen, intent, "table-row", ambiguous, sample, id("loanTable"), row, null);
    }

    private static Target rowWith(String id, String screen, String intent, boolean ambiguous, boolean sample,
                                  String contains) {
        return new Target(id, screen, intent, "table-row", ambiguous, sample, id("loanTable"), -1, contains);
    }

    private static Predicate<Screen.Node> id(String id) {
        return n -> id.equals(n.id);
    }

    private static Predicate<Screen.Node> cls(String cls) {
        return n -> cls.equals(n.cls);
    }

    /** A keypad key: the {@code IconKey} the lending module's name enricher names after its action. */
    private static Predicate<Screen.Node> key(String action) {
        return n -> "IconKey".equals(n.cls) && action.equals(n.name);
    }

    /** A painted button of the prompt dialog. */
    private static Predicate<Screen.Node> prompt(String label) {
        return n -> "PromptButton".equals(n.cls) && label.toLowerCase(Locale.ROOT).equals(n.label().toLowerCase(Locale.ROOT));
    }
}
