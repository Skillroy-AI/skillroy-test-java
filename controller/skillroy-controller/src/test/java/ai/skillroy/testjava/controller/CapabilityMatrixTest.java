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

import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityMatrixTest {

    private static CapabilityMatrix matrix(Capability... capabilities) {
        Map<String, Capability> map = new LinkedHashMap<>();
        for (Capability c : capabilities) {
            map.put(c.id(), c);
        }
        return new CapabilityMatrix(map);
    }

    private final CapabilityMatrix matrix = matrix(
            new Capability("lending.loan", CapabilityState.OK, null, null),
            new Capability("swing.tree", CapabilityState.UNAVAILABLE, "headless", null),
            new Capability("swing.click.robot", CapabilityState.UNAVAILABLE, null, null),
            new Capability("swing.capture.paint", CapabilityState.DEGRADED, "slow", "paint"),
            new Capability("data.query", CapabilityState.UNAVAILABLE, "  ", null));

    @Test
    void metRequirementsProduceNothing() {
        assertTrue(matrix.unmet(List.of("lending.loan", "swing.capture.paint")).isEmpty());
        assertTrue(matrix.unmet(List.of()).isEmpty());
        assertTrue(matrix.unmet(null).isEmpty());
    }

    @Test
    void missingAndUnavailableAreReportedWithReasonsInOrder() {
        assertEquals(List.of(
                        "swing.tree: UNAVAILABLE (headless)",
                        "lending.state: missing",
                        "swing.click.robot: UNAVAILABLE",
                        "data.query: UNAVAILABLE"),
                matrix.unmet(List.of("swing.tree", "lending.loan", "lending.state", "swing.click.robot",
                        "data.query")));
    }

    @Test
    void alternativesAreMetWhenAnyOneIsMet() {
        assertTrue(matrix.unmet(List.of("swing.tree | lending.loan")).isEmpty());
        assertTrue(matrix.unmet(List.of("lending.missing|swing.capture.paint")).isEmpty());
        assertEquals(List.of("swing.tree | lending.state: swing.tree: UNAVAILABLE (headless); lending.state: missing"),
                matrix.unmet(List.of("swing.tree|lending.state")));
    }

    @Test
    void normalisesWhitespaceAndSkipsBlankAndDuplicateEntries() {
        assertEquals(List.of("lending.state: missing", "a | b: a: missing; b: missing"),
                matrix.unmet(Arrays.asList(" lending.state ", null, "", "  ", "|", "a |b", "lending.state", "a| b",
                        "a | a | b")));
        assertEquals(List.of("x: missing"), matrix.unmet(List.of(" | x | ")));
    }

    @Test
    void accessors() {
        assertTrue(matrix.isOk("lending.loan"));
        assertFalse(matrix.isOk("swing.capture.paint"));
        assertFalse(matrix.isOk("swing.tree"));
        assertFalse(matrix.isOk("nothing"));
        assertNull(matrix.get("nothing"));
        assertEquals("headless", matrix.get("swing.tree").reason());
        assertEquals(List.of("data.query", "lending.loan", "swing.capture.paint", "swing.click.robot", "swing.tree"),
                List.copyOf(matrix.all().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> matrix.all().clear());
        assertTrue(new CapabilityMatrix(null).all().isEmpty());
    }

    @Test
    void rejectsKeysThatDisagreeWithTheCapabilityId() {
        Map<String, Capability> map = new LinkedHashMap<>();
        map.put("a", new Capability("b", CapabilityState.OK, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityMatrix(map));
    }

    @Test
    void readsTheCapabilitiesEndpointShape() {
        CapabilityMatrix parsed = CapabilityMatrix.fromJson(Json.parseObject(
                "{\"capabilities\":{\"swing.tree\":{\"state\":\"UNAVAILABLE\",\"reason\":\"headless\","
                        + "\"strategy\":null},\"lending.loan\":{\"state\":\"OK\",\"reason\":null,"
                        + "\"strategy\":null}}}"));
        assertEquals(matrix(new Capability("lending.loan", CapabilityState.OK, null, null),
                new Capability("swing.tree", CapabilityState.UNAVAILABLE, "headless", null)), parsed);
        assertThrows(JsonException.class, () -> CapabilityMatrix.fromJson(Json.parseObject("{}")));
        assertThrows(JsonException.class,
                () -> CapabilityMatrix.fromJson(Json.parseObject("{\"capabilities\":{\"a\":1}}")));
        assertThrows(JsonException.class,
                () -> CapabilityMatrix.fromJson(Json.parseObject("{\"capabilities\":{\"a\":{\"state\":\"NO\"}}}")));
    }

    /** phase6-contracts.md 6.4: the archived wire shape, ids sorted, and its hash over the canonical JSON. */
    @Test
    void toJsonIsTheEndpointShapeAndTheHashFollowsEveryField() {
        Map<String, Object> json = matrix.toJson();
        assertEquals(List.of("capabilities"), List.copyOf(json.keySet()));
        Map<?, ?> entries = (Map<?, ?>) json.get("capabilities");
        assertEquals(List.of("data.query", "lending.loan", "swing.capture.paint", "swing.click.robot", "swing.tree"),
                List.copyOf(entries.keySet()));
        Map<?, ?> paint = (Map<?, ?>) entries.get("swing.capture.paint");
        assertEquals("DEGRADED", paint.get("state"));
        assertEquals("slow", paint.get("reason"));
        assertEquals("paint", paint.get("strategy"));
        assertTrue(((Map<?, ?>) entries.get("lending.loan")).containsKey("reason"), "absent fields are written null");
        assertEquals(matrix, CapabilityMatrix.fromJson(Json.parseObject(Json.write(json))), "it reads back");

        String hash = matrix.hash();
        assertTrue(hash.matches("sha256:[0-9a-f]{64}"), hash);
        assertEquals(hash, CapabilityMatrix.fromJson(Json.parseObject(Json.write(json))).hash(), "stable");
        CapabilityMatrix otherReason = matrix(new Capability("lending.loan", CapabilityState.OK, "x", null));
        CapabilityMatrix sameIds = matrix(new Capability("lending.loan", CapabilityState.OK, null, null));
        assertFalse(otherReason.hash().equals(sameIds.hash()), "a reason changes it");
        assertEquals(new CapabilityMatrix(null).hash(), new CapabilityMatrix(Map.of()).hash());
    }
}
