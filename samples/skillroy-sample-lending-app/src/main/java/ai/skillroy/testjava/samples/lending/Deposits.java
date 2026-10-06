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
import java.math.RoundingMode;

/**
 * Money and quantity arithmetic shared by the engine, the waiver rule engine and the slip. Money is a
 * {@link BigDecimal} with scale 2 rounded half-up; quantities are whole numbers (scale 0) for counted items and up to
 * three decimals (days) for per-day items. Stateless and thread-safe.
 */
public final class Deposits {

    /** Scale of every money amount. */
    public static final int MONEY_SCALE = 2;
    /** Maximum scale of a per-day quantity. */
    public static final int QTY_SCALE = 3;
    /** Largest quantity the desk accepts. */
    public static final BigDecimal MAX_QTY = new BigDecimal("999");
    /** Largest amount the desk accepts as received. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("99999.99");

    private Deposits() {
    }

    /** @return the amount at scale 2, rounded half-up; null stays null */
    public static BigDecimal money(BigDecimal amount) {
        return amount == null ? null : amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** @return {@code unitDeposit * qty} as money */
    public static BigDecimal lineAmount(BigDecimal unitDeposit, BigDecimal qty) {
        return money(unitDeposit.multiply(qty));
    }

    /** @return the amount as a plain decimal string with two decimals, for example {@code "-3.50"} */
    public static String format(BigDecimal amount) {
        return money(amount).toPlainString();
    }

    /** @return a quantity as shown to users: {@code "2"} or {@code "0.250"} */
    public static String formatQty(BigDecimal qty) {
        return isWhole(qty) ? qty.setScale(0, RoundingMode.UNNECESSARY).toPlainString()
            : qty.setScale(QTY_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * @return a line quantity as shown to users: {@link #formatQty} for a counted item, followed by {@code " day"}
     *         (exactly one) or {@code " days"} for a per-day item, for example {@code "1 day"} or {@code "0.250 days"}
     */
    public static String formatLineQty(BigDecimal qty, boolean perDay) {
        String text = formatQty(qty);
        if (!perDay) {
            return text;
        }
        return text + (qty.compareTo(BigDecimal.ONE) == 0 ? " day" : " days");
    }

    /** @return true if the value has no fractional part */
    public static boolean isWhole(BigDecimal value) {
        return value.signum() == 0 || value.stripTrailingZeros().scale() <= 0;
    }

    /**
     * Parses an amount typed by a user.
     *
     * @return the amount at scale 2, or null unless the text is a non-negative decimal with at most two decimals and
     *     at most {@link #MAX_AMOUNT}
     */
    public static BigDecimal parseMoney(String text) {
        BigDecimal value = parse(text);
        if (value == null || value.signum() < 0 || value.compareTo(MAX_AMOUNT) > 0
            || value.stripTrailingZeros().scale() > MONEY_SCALE) {
            return null;
        }
        return money(value);
    }

    /**
     * Parses a quantity typed by a user.
     *
     * @return the quantity (scale 0 when whole, else the typed scale), or null unless the text is a positive decimal
     *     with at most three decimals and at most {@link #MAX_QTY}
     */
    public static BigDecimal parseQuantity(String text) {
        BigDecimal value = parse(text);
        if (value == null || value.signum() <= 0 || value.compareTo(MAX_QTY) > 0
            || value.stripTrailingZeros().scale() > QTY_SCALE) {
            return null;
        }
        return isWhole(value) ? value.setScale(0, RoundingMode.UNNECESSARY) : value;
    }

    private static BigDecimal parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.isEmpty() || t.length() > 16 || !t.matches("[+-]?[0-9]*(\\.[0-9]*)?") || t.matches("[+-]?\\.?")) {
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
