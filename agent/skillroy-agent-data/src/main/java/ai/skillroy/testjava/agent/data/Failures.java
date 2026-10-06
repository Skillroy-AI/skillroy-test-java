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

package ai.skillroy.testjava.agent.data;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Describes driver and provider failures without their text (finding S22). A driver's message can echo parameter or
 * cell values, including the very values a {@code MaskPolicy} masks, and a {@code MaskPolicy} cannot scrub free text,
 * so the module writes only the exception class, the SQL state and the vendor error code to its log and its errors.
 *
 * <p>Stateless; safe from any thread.
 */
final class Failures {

    /** How far down a cause chain {@link #sqlCause} looks. */
    static final int MAX_CAUSE_DEPTH = 16;

    private Failures() {
    }

    /**
     * The first {@code SQLException} in the cause chain of {@code t} ({@code t} itself included), at most
     * {@link #MAX_CAUSE_DEPTH} deep and safe against a cyclic chain; null if there is none.
     */
    static SQLException sqlCause(Throwable t) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable c = t;
        for (int depth = 0; c != null && depth < MAX_CAUSE_DEPTH && seen.add(c); depth++) {
            if (c instanceof SQLException) {
                return (SQLException) c;
            }
            c = c.getCause();
        }
        return null;
    }

    /**
     * {@code java.sql.SQLException (sqlState=42501, errorCode=-5501)} for a failure with an {@code SQLException} in its
     * cause chain, else the class name alone; never the message.
     */
    static String describe(Throwable t) {
        SQLException sql = sqlCause(t);
        String name = t.getClass().getName();
        return sql == null ? name : name + " (sqlState=" + sql.getSQLState() + ", errorCode=" + sql.getErrorCode()
                + ")";
    }
}
