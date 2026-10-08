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

package ai.skillroy.testjava.agent.api.spi.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link CdpEndpoint}: a value type with a host, a port in range and an optional engine label. */
class CdpEndpointTest {

    @Test
    void theAccessorsReturnTheFieldsAndEqualityCoversThemAll() {
        CdpEndpoint endpoint = new CdpEndpoint("127.0.0.1", 9222, "jcef");
        assertEquals("127.0.0.1", endpoint.host());
        assertEquals(9222, endpoint.port());
        assertEquals("jcef", endpoint.engineLabel());
        assertEquals(endpoint, new CdpEndpoint("127.0.0.1", 9222, "jcef"));
        assertEquals(endpoint.hashCode(), new CdpEndpoint("127.0.0.1", 9222, "jcef").hashCode());
        assertNotEquals(endpoint, new CdpEndpoint("localhost", 9222, "jcef"));
        assertNotEquals(endpoint, new CdpEndpoint("127.0.0.1", 9223, "jcef"));
        assertNotEquals(endpoint, new CdpEndpoint("127.0.0.1", 9222, "desk-profile"));
        assertNotEquals(endpoint, new CdpEndpoint("127.0.0.1", 9222, null));
        assertFalse(endpoint.equals(null));
        assertFalse(endpoint.equals("127.0.0.1:9222"));
        assertEquals("CdpEndpoint{host=127.0.0.1, port=9222, engineLabel=jcef}", endpoint.toString());
    }

    @Test
    void theEngineLabelMayBeUnknown() {
        CdpEndpoint endpoint = new CdpEndpoint("127.0.0.1", 9222, null);
        assertNull(endpoint.engineLabel());
        assertEquals(endpoint, new CdpEndpoint("127.0.0.1", 9222, null));
    }

    @Test
    void theHostIsRequired() {
        NullPointerException missing = assertThrows(NullPointerException.class,
                () -> new CdpEndpoint(null, 9222, "jcef"));
        assertEquals("host must not be null", missing.getMessage());
        for (String blank : new String[] {"", " ", "\t"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new CdpEndpoint(blank, 9222, "jcef"));
            assertEquals("host must not be blank", e.getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void aPortOutOfRangeIsRejected(int port) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CdpEndpoint("127.0.0.1", port, "jcef"));
        assertEquals("port must be 1..65535: " + port, e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 9222, 65535})
    void aPortInRangeIsAccepted(int port) {
        assertEquals(port, new CdpEndpoint("127.0.0.1", port, "jcef").port());
    }
}
