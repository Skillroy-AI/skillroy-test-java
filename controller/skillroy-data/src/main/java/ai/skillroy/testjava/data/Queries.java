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

import ai.skillroy.testjava.protocol.sql.ReadOnlyJdbc;

import java.time.Duration;
import java.util.List;

/**
 * The argument checks every {@link DataChannel} applies before anything else, so that a pack defect (a parameter of a
 * type decision D23 does not allow) is the same {@code IllegalArgumentException} on both channels. Stateless.
 */
final class Queries {

    /** Upper bound of a statement timeout, as the agent's dispatcher caps {@code _meta.timeoutMs}. */
    static final Duration MAX_TIMEOUT = Duration.ofSeconds(120);

    private Queries() {
    }

    /** @throws IllegalArgumentException as {@link DataChannel#query} documents */
    static void check(String source, String sql, List<Object> params, int maxRows, Duration timeout) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("a data source id is required");
        }
        if (sql == null) {
            throw new IllegalArgumentException("a statement is required");
        }
        if (maxRows < 0 || maxRows > DataChannel.MAX_ROWS) {
            throw new IllegalArgumentException("maxRows must be between 0 and " + DataChannel.MAX_ROWS + ", was "
                    + maxRows);
        }
        if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
            throw new IllegalArgumentException("a statement timeout must be positive");
        }
        ReadOnlyJdbc.checkParams(params);
    }

    /** The timeout in whole seconds, rounded up, at least 1 and at most 120. */
    static int seconds(Duration timeout) {
        Duration capped = timeout.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : timeout;
        long ms = capped.toMillis();
        return (int) Math.max(1, (ms + 999) / 1000);
    }

    /** The timeout in milliseconds, at least 1 and at most 120000. */
    static long millis(Duration timeout) {
        Duration capped = timeout.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : timeout;
        return Math.max(1, capped.toMillis());
    }
}
