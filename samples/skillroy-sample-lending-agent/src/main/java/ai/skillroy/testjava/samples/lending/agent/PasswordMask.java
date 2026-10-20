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
 * {@value LendingDataSource#ID} or {@value LendingRowsSource#ID} (Phase 8, phase8-contracts.md 4.4: the positional
 * source's columns carry the statement's labels), the value of a column named {@value #COLUMN} (compared ignoring
 * case) becomes
 * {@value SecretKeys#REDACTED}. Why: the desk's {@code staff} table holds its sample passwords in clear text, and no
 * row a test framework reads (a resolver's row sample, a stored-row check's evidence) may carry one out of the JVM.
 *
 * <p>The rule, which the domain pack's controller-side row mask repeats for rows read over JDBC: the source id must
 * be exactly {@value LendingDataSource#ID}; the column is the label the driver reports, so a statement that renames
 * the column ({@code password AS p}) is not masked, as for any rule by column name; a null value stays null (there
 * is no secret in it); every other value, whatever its type, is replaced. Other columns and other sources pass
 * unchanged.
 *
 * <p>The event rule (Phase 8, phase8-contracts.md 5.1, 12.2): every event payload passes the registered policies at
 * publish under the source id {@code event:<topic>}; in {@code staff.signedOn} the column {@value #NAME_COLUMN} (the
 * staff member's name, personal data) becomes {@value #PERSONAL}; its {@code code} and every other topic pass
 * unchanged. The rule checks the source id exactly, so that no row column called {@code name} is touched.
 *
 * <p>Stateless; the data module, and every thread that publishes an event, may call it.
 */
final class PasswordMask implements MaskPolicy {

    /** The masked column. */
    static final String COLUMN = "password";

    /** The event rule's source id: the ring's {@code event:} prefix and the topic. */
    static final String EVENT_SOURCE = "event:" + EngineEvents.STAFF_SIGNED_ON;
    /** The event rule's column. */
    static final String NAME_COLUMN = "name";
    /** What the event rule answers for a staff member's name. */
    static final String PERSONAL = "<personal>";

    @Override
    public Object mask(String sourceId, String column, Object value) {
        if (value != null && (LendingDataSource.ID.equals(sourceId) || LendingRowsSource.ID.equals(sourceId))
                && COLUMN.equalsIgnoreCase(column)) {
            return SecretKeys.REDACTED;
        }
        if (value != null && EVENT_SOURCE.equals(sourceId) && NAME_COLUMN.equals(column)) {
            return PERSONAL;
        }
        return value;
    }
}
