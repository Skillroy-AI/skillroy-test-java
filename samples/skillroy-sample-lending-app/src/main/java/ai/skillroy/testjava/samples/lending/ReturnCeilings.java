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
import java.util.concurrent.atomic.AtomicLong;

/**
 * The branch edition's return ceilings (phase6-contracts.md 11.3): the limit the desk applies to a return line and to
 * a supervisor's authorisation, computed from the staff member's roles' {@code role_cap} rows of kind
 * {@link AccessControl#LIMIT_RETURN}. Used by the engine's return path in the {@link Edition#BRANCH} edition only, in
 * place of {@link AccessControl#returnLimit}; the access decision ({@link AccessControl#check}) never calls it.
 *
 * <p>Immutable; safe to call from any thread.
 */
public final class ReturnCeilings {

    private final AccessControl access;
    /** Calls of {@link #applicable}, for the application's own tests of which path calls it (P6 13.1 item 6). */
    final AtomicLong calls = new AtomicLong();

    /** @param access the desk's loaded access rules */
    public ReturnCeilings(AccessControl access) {
        if (access == null) {
            throw new NullPointerException("access");
        }
        this.access = access;
    }

    /**
     * @param staffCode the staff member
     * @return the return ceiling that applies to the staff member, null for unlimited (or unknown staff)
     */
    public BigDecimal applicable(String staffCode) {
        calls.incrementAndGet();
        return access.limit(staffCode, AccessControl.LIMIT_RETURN);
    }
}
