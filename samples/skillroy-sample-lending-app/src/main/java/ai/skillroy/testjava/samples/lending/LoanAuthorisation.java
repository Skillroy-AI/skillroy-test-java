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

import java.util.Objects;

/**
 * A supervisor's authorisation recorded on a loan ({@code loan_authorisation}); the only kind is
 * {@code RETURN_OVERRIDE}. Immutable.
 */
public final class LoanAuthorisation {

    private final String kind;
    private final String staffCode;

    public LoanAuthorisation(String kind, String staffCode) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.staffCode = Objects.requireNonNull(staffCode, "staffCode");
    }

    public String kind() {
        return kind;
    }

    /** @return the code of the staff member who authorised */
    public String staffCode() {
        return staffCode;
    }

    @Override
    public String toString() {
        return kind + " by " + staffCode;
    }
}
