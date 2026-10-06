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

package ai.skillroy.testjava.scenario.resolve;

/**
 * The kind of a {@link StoredColumn}: how a {@code where} value is passed as a statement parameter
 * (phase2-contracts.md 7.4): {@code TEXT} as text, {@code INTEGER} as a {@code Long}, {@code BOOLEAN} as a
 * {@code Boolean}, {@code DECIMAL} as its plain decimal string, {@code TIMESTAMP} as the SQL-standard text
 * {@code yyyy-MM-dd HH:mm:ss[.fraction]} (written from an ISO-8601 local date-time).
 */
public enum ColumnKind {
    /** Text. */
    TEXT,
    /** A whole number, passed as a {@code Long}. */
    INTEGER,
    /** A decimal, passed as its plain string; the column's {@code paramSql} casts. */
    DECIMAL,
    /** A boolean. */
    BOOLEAN,
    /**
     * A local date and time, passed as {@code yyyy-MM-dd HH:mm:ss[.fraction]}; the scenario or a reference gives it
     * in ISO-8601 with a {@code T} or a space; an offset, {@code Z} or a date alone is refused.
     */
    TIMESTAMP
}
