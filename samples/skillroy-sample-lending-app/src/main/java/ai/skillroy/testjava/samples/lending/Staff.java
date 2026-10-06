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
 * A staff member as the desk sees them. The password is deliberately not part of this view; check it with
 * {@link AccessControl#passwordMatches}. Immutable.
 */
public final class Staff {

    private final String code;
    private final String name;
    private final boolean active;
    private final boolean autoSignOn;

    /**
     * @param code staff code (what is typed at sign-on)
     * @param name display name
     * @param active false for staff who may no longer sign on
     * @param autoSignOn true if entering the code signs on without a password
     */
    public Staff(String code, String name, boolean active, boolean autoSignOn) {
        this.code = Objects.requireNonNull(code, "code");
        this.name = name;
        this.active = active;
        this.autoSignOn = autoSignOn;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public boolean active() {
        return active;
    }

    /** @return true if this staff member signs on with the code alone (no password step) */
    public boolean autoSignOn() {
        return autoSignOn;
    }

    @Override
    public String toString() {
        return code + " " + name;
    }
}
