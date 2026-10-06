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

package ai.skillroy.testjava.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The names both ends of the channel must spell identically (hardening H2 and H3). */
class ProtocolConstantsTest {

    @Test
    void versionAndNames() {
        assertEquals("0.2", Protocol.VERSION);
        assertEquals("skillroy.agent.session", Protocol.PROP_SESSION);
        assertEquals("sessionId", Protocol.KEY_SESSION_ID);
        assertEquals(100, Protocol.MAX_BATCH);
        assertEquals("secretParams", Protocol.META_SECRET_PARAMS);
    }
}
