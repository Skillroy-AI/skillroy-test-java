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

package ai.skillroy.testjava.data;

/**
 * The controller-side counterpart of the agent's {@code MaskPolicy}, with the same signature and meaning
 * (phase2-contracts.md 7.3): applied by {@link JdbcDataChannel} to every value it returns, so that rows read over JDBC
 * never skip the masking rows read through the agent get; the binder applies the same masks to every row sample it
 * keeps. A pack declares the same rule on both sides.
 *
 * <p>Called on the thread that runs the query, possibly concurrently for different sources; must be thread-safe and
 * fast. A mask that throws fails the whole read ({@link DataException.Kind#MASK}) and the value appears nowhere.
 */
@FunctionalInterface
public interface RowMask {

    /**
     * The value to keep in place of {@code value} (return it unchanged to leave it as is).
     *
     * @param sourceId the source the row was read from
     * @param column the column's label as the source reported it (case as the driver reports it)
     * @param value the converted value (null, String, Boolean, Long or Double)
     */
    Object mask(String sourceId, String column, Object value);
}
