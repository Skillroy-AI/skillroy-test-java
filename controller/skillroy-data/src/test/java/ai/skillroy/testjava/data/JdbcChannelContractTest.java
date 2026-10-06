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

import org.junit.jupiter.api.AfterEach;

/** {@link DataChannelContract} over {@link JdbcDataChannel}, as the read-only account of the test database. */
class JdbcChannelContractTest extends DataChannelContract {

    private JdbcDataChannel channel;

    @Override
    DataChannel channel() throws Exception {
        if (channel == null) {
            channel = new JdbcDataChannel(TestDatabase.credentials(SOURCE, db.source(db.url)), null);
        }
        return channel;
    }

    @AfterEach
    void close() {
        if (channel != null) {
            channel.close();
        }
    }
}
