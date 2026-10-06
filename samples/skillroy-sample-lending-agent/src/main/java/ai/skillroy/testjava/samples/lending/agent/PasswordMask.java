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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.agent.api.spi.MaskPolicy;
import ai.skillroy.testjava.protocol.SecretKeys;

/**
 * The module's {@link MaskPolicy} (Phase 2 contract section 13.2): in rows that {@code data.query} reads from source
 * {@value LendingDataSource#ID}, the value of a column named {@value #COLUMN} (compared ignoring case) becomes
 * {@value SecretKeys#REDACTED}. Why: the desk's {@code staff} table holds its sample passwords in clear text, and no
 * row a test framework reads (a resolver's row sample, a stored-row check's evidence) may carry one out of the JVM.
 *
 * <p>The rule, which the domain pack's controller-side row mask repeats for rows read over JDBC: the source id must
 * be exactly {@value LendingDataSource#ID}; the column is the label the driver reports, so a statement that renames
 * the column ({@code password AS p}) is not masked, as for any rule by column name; a null value stays null (there
 * is no secret in it); every other value, whatever its type, is replaced. Other columns and other sources pass
 * unchanged.
 *
 * <p>Stateless; the data module may call it from any thread.
 */
final class PasswordMask implements MaskPolicy {

    /** The masked column. */
    static final String COLUMN = "password";

    @Override
    public Object mask(String sourceId, String column, Object value) {
        if (value != null && LendingDataSource.ID.equals(sourceId) && COLUMN.equalsIgnoreCase(column)) {
            return SecretKeys.REDACTED;
        }
        return value;
    }
}
