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
import java.util.Objects;

/**
 * A catalogue item (one {@code item} row): a tool the library lends against a deposit, which is handed back when the
 * tool comes back. The unit deposit of a per-day item is per day. Immutable.
 */
public final class Item {

    private final String code;
    private final String barcode;
    private final String title;
    private final BigDecimal unitDeposit;
    private final boolean active;
    private final boolean inductionRequired;
    private final boolean perDay;

    public Item(String code, String barcode, String title, BigDecimal unitDeposit, boolean active,
                boolean inductionRequired, boolean perDay) {
        this.code = Objects.requireNonNull(code, "code");
        this.barcode = barcode;
        this.title = title;
        this.unitDeposit = Deposits.money(Objects.requireNonNull(unitDeposit, "unitDeposit"));
        this.active = active;
        this.inductionRequired = inductionRequired;
        this.perDay = perDay;
    }

    /** @return the item code ({@code item.code}) */
    public String code() {
        return code;
    }

    public String barcode() {
        return barcode;
    }

    /** @return the item's title, for example {@code "Claw hammer"} */
    public String title() {
        return title;
    }

    /** @return the deposit per unit ({@code item.deposit}), or per day for a per-day item; scale 2 */
    public BigDecimal unitDeposit() {
        return unitDeposit;
    }

    /** @return false for a retired item that may no longer be lent */
    public boolean active() {
        return active;
    }

    /** @return true if lending it needs the induction check prompt */
    public boolean inductionRequired() {
        return inductionRequired;
    }

    /** @return true if it is lent by the day (the number of days must be entered with QTY first) */
    public boolean perDay() {
        return perDay;
    }

    @Override
    public String toString() {
        return code + " " + title + " " + Deposits.format(unitDeposit);
    }
}
