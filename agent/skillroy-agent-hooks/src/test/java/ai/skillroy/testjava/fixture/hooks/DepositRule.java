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


package ai.skillroy.testjava.fixture.hooks;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A toy deposit rule of the lending desk, hooked by the hooks module's tests. Outside the protected prefixes
 * (phase6-contracts.md section 2.3), so that the engine hooks it as it would a target application's class.
 */
public class DepositRule {

    private final BigDecimal base;

    public DepositRule() {
        this(new BigDecimal("20.00"));
    }

    public DepositRule(BigDecimal base) {
        this.base = base;
    }

    /** The deposit for one item: the base, doubled for a power tool. */
    public BigDecimal depositFor(String itemCode) {
        return itemCode != null && itemCode.startsWith("P") ? base.add(base) : base;
    }

    /** Whether a waiver reason waives the deposit. */
    public boolean waives(String reason) {
        return "member".equals(reason);
    }

    /** The rate's name, a constant another agent's transformer can rewrite. */
    public String rateName() {
        return "rate:standard";
    }

    /** Holds a return at the desk; returns nothing. */
    public void holdReturn(String itemCode) {
        if (itemCode == null) {
            throw new IllegalArgumentException("no item");
        }
    }

    /** Always fails, with a message that carries a secret pair. */
    public BigDecimal failing(String itemCode) {
        throw new IllegalStateException("deposit rule broke for " + itemCode + ": password=hunter2 limit=5");
    }

    /** Rounds an amount to cents: the static method a STUB hook overrides. */
    public static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** The codes of the first n items. */
    public List<String> codes(int n) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < n; i++) {
            out.add("T" + i);
        }
        return out;
    }
}
