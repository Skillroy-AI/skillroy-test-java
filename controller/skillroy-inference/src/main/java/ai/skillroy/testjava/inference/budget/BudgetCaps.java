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

package ai.skillroy.testjava.inference.budget;

import ai.skillroy.testjava.scenario.resolve.CostLedger;

import java.math.BigDecimal;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The cap a session puts on the tier (b) resolves it runs (phase5-contracts.md 12.4, decision D101): while the session
 * service resolves a slot through its session, it caps the scenario budget of that resolve at what is left of the
 * session's budget; the contributor that the resolve's engine loads reads the cap of the ledger it is given and makes
 * its scenario meter's limit the smaller of the two. The ledger is the link: both hosts pass the session's one ledger
 * to the session's binds and to the service, and the inference settings stay strings across the scenario module's
 * seam (D70), so no budget type crosses it.
 *
 * <p>Thread-safe; a cap is held for the length of one resolve and released by closing it.
 */
public final class BudgetCaps {

    private static final Map<CostLedger, BigDecimal> CAPS = new IdentityHashMap<>();

    private BudgetCaps() {
    }

    /** A cap in force; closing it restores what was there before, once. */
    public interface Cap extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Caps the scenario budget of the binds that record into {@code ledger} at {@code limitUsd} until the returned cap
     * is closed.
     *
     * @param limitUsd the most a bind's scenario meter may allow; null puts no cap
     */
    public static Cap cap(CostLedger ledger, BigDecimal limitUsd) {
        Objects.requireNonNull(ledger, "ledger");
        BigDecimal previous;
        synchronized (CAPS) {
            previous = limitUsd == null ? CAPS.get(ledger) : CAPS.put(ledger, limitUsd.max(BigDecimal.ZERO));
        }
        if (limitUsd == null) {
            return () -> { };
        }
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (CAPS) {
                if (previous == null) {
                    CAPS.remove(ledger);
                } else {
                    CAPS.put(ledger, previous);
                }
            }
        };
    }

    /** The cap on the binds that record into {@code ledger}, or null when there is none. */
    public static BigDecimal capOf(CostLedger ledger) {
        if (ledger == null) {
            return null;
        }
        synchronized (CAPS) {
            return CAPS.get(ledger);
        }
    }

    /**
     * The smaller of a scenario limit and the ledger's cap: {@code limitUsd} when there is no cap, the cap when the
     * limit is null (unlimited).
     */
    public static BigDecimal capped(BigDecimal limitUsd, CostLedger ledger) {
        BigDecimal cap = capOf(ledger);
        if (cap == null) {
            return limitUsd;
        }
        return limitUsd == null ? cap : limitUsd.min(cap);
    }
}
