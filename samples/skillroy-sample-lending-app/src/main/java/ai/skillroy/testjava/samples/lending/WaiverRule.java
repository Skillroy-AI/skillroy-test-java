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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A waiver rule ({@code waiver_rule} row plus its {@code waiver_rule_item} item codes). Immutable.
 */
public final class WaiverRule {

    /**
     * The only kind the desk evaluates: for every {@code borrowQty + waivedQty} units across the rule's items, the
     * lowest {@code waivedQty} deposits are waived.
     */
    public static final String BORROW_N_WAIVE_M = "BORROW_N_WAIVE_M";

    private final int id;
    private final String code;
    private final String description;
    private final String kind;
    private final int borrowQty;
    private final int waivedQty;
    private final boolean active;
    private final LocalDate startsOn;
    private final LocalDate endsOn;
    private final List<String> itemCodes;

    public WaiverRule(int id, String code, String description, String kind, int borrowQty, int waivedQty,
                      boolean active, LocalDate startsOn, LocalDate endsOn, List<String> itemCodes) {
        this.id = id;
        this.code = Objects.requireNonNull(code, "code");
        this.description = description;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.borrowQty = borrowQty;
        this.waivedQty = waivedQty;
        this.active = active;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        List<String> copy = new ArrayList<String>(itemCodes);
        Collections.sort(copy);
        this.itemCodes = Collections.unmodifiableList(copy);
    }

    public int id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public String kind() {
        return kind;
    }

    public int borrowQty() {
        return borrowQty;
    }

    public int waivedQty() {
        return waivedQty;
    }

    /** @return the {@code active} flag (see {@link #effectiveOn} for the date window) */
    public boolean active() {
        return active;
    }

    /** @return first day the waiver rule applies, or null for no start */
    public LocalDate startsOn() {
        return startsOn;
    }

    /** @return last day the waiver rule applies (inclusive), or null for no end */
    public LocalDate endsOn() {
        return endsOn;
    }

    /** @return the item codes the waiver rule covers, sorted */
    public List<String> itemCodes() {
        return itemCodes;
    }

    /** @return true if active and {@code startsOn <= date <= endsOn} (null bounds are open) */
    public boolean effectiveOn(LocalDate date) {
        return active && (startsOn == null || !date.isBefore(startsOn)) && (endsOn == null || !date.isAfter(endsOn));
    }

    @Override
    public String toString() {
        return code + " " + kind + " " + borrowQty + "+" + waivedQty + " " + itemCodes;
    }
}
