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

/**
 * The outcome of {@link AccessControl#check}: whether the staff member may do it, the monetary limit that applied
 * (null = no limit), and a short human-readable reason. Immutable.
 */
public final class AccessDecision {

    private final boolean allowed;
    private final BigDecimal limit;
    private final String reason;

    public AccessDecision(boolean allowed, BigDecimal limit, String reason) {
        this.allowed = allowed;
        this.limit = limit;
        this.reason = reason;
    }

    public boolean allowed() {
        return allowed;
    }

    /** @return the staff member's limit for the permission's limit kind, or null for unlimited or not applicable */
    public BigDecimal limit() {
        return limit;
    }

    /** @return {@code "permitted"} or why not, for example {@code "amount exceeds the limit 20.00"} */
    public String reason() {
        return reason;
    }

    @Override
    public String toString() {
        return "AccessDecision{allowed=" + allowed + ", limit=" + limit + ", reason=" + reason + "}";
    }
}
