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

package ai.skillroy.testjava.controller;

import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.LoadMode;
import ai.skillroy.testjava.protocol.RpcError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeamTypesTest {

    @Test
    void callOptionsDefaultsAndCopies() {
        CallOptions defaults = CallOptions.defaults();
        assertNull(defaults.correlationId());
        assertNull(defaults.timeoutMs());
        assertFalse(defaults.confirm());
        assertSame(defaults, CallOptions.defaults());
        CallOptions custom = defaults.withCorrelationId("c-1").withTimeoutMs(500L).withConfirm(true);
        assertEquals(new CallOptions("c-1", 500L, true), custom);
        assertNull(custom.withTimeoutMs(null).timeoutMs());
        assertThrows(IllegalArgumentException.class, () -> new CallOptions(null, 0L, false));
        assertEquals(List.of(), defaults.secretParams(), "the three-argument form announces no secret");
        CallOptions secret = custom.withSecretParams(List.of("value"));
        assertEquals(new CallOptions("c-1", 500L, true, List.of("value")), secret);
        // Phase 6 (phase6-contracts.md 10.2): the trace parent, a fifth component; the four-argument form has none.
        CallOptions traced = secret.withTraceparent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        assertEquals("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", traced.traceparent());
        assertEquals(secret, traced.withTraceparent(" "), "a blank trace parent is none");
        assertEquals(null, new CallOptions("c-1", 500L, true, List.of("value")).traceparent());
        assertEquals(traced.traceparent(), traced.withConfirm(false).withTimeoutMs(1L).withCorrelationId("x")
                .withSecretParams(List.of()).traceparent(), "every copy keeps it");
        assertEquals(List.of("value"), secret.withTimeoutMs(9L).withConfirm(false).withCorrelationId("c-2")
                .secretParams(), "the copies keep the secret params");
        assertEquals(List.of(), secret.withSecretParams(null).secretParams());
        assertThrows(IllegalArgumentException.class, () -> defaults.withSecretParams(List.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> defaults.withSecretParams(java.util.Collections.nCopies(
                101, "p")));
        assertThrows(NullPointerException.class, () -> defaults.withSecretParams(java.util.Arrays.asList("a",
                null)));
    }

    @Test
    void endpointValidatesAndHidesTheToken() {
        Endpoint endpoint = new Endpoint("127.0.0.1", 51234, "s3cr3t-token", "n-1", 4242, 1790000000000L,
                LoadMode.JAVAAGENT);
        assertFalse(endpoint.toString().contains("s3cr3t"), endpoint.toString());
        assertTrue(endpoint.toString().contains("51234"));
        assertEquals("s3cr3t-token", endpoint.token());
        assertThrows(IllegalArgumentException.class, () -> new Endpoint("127.0.0.1", 0, null, null, 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new Endpoint("127.0.0.1", 65536, null, null, 0, 0, null));
        assertThrows(NullPointerException.class, () -> new Endpoint(null, 1, null, null, 0, 0, null));
    }

    @Test
    void agentRpcExceptionNamesMethodAndCode() {
        RpcError error = new RpcError(ErrorCodes.INPUT_REJECTED, "not valid in IDLE", null);
        AgentRpcException e = new AgentRpcException("lending.input", error);
        assertEquals("lending.input", e.method());
        assertSame(error, e.error());
        assertEquals("lending.input failed: INPUT_REJECTED (-32005): not valid in IDLE", e.getMessage());
    }

    @Test
    void lostAndAttachFailuresKeepTheirCause() {
        Exception cause = new java.net.ConnectException("refused");
        assertSame(cause, new AgentLostException("gone", cause).getCause());
        assertSame(cause, new AttachFailedException("no", cause).getCause());
        assertEquals("gone", new AgentLostException("gone").getMessage());
    }
}
