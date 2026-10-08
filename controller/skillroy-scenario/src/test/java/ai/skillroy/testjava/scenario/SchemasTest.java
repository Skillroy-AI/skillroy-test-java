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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.resolve.NumberRange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 (phase5-contracts.md 3.3): JSON Schemas of records within a provider's limits, strict for structured outputs
 * and lenient for what a scenario writes; the refused shapes refused.
 */
class SchemasTest {

    enum Kind { LEND, RETURN }

    record Line(String code, @Schemas.Doc("units lent") int qty, BigDecimal deposit) {
    }

    record Proposal(@Schemas.Doc("one SELECT") String sql, List<Line> lines, Map<String, Long> counts, Kind kind,
                    Boolean urgent, double confidence, LocalDate day, Instant at) {
    }

    record Loop(String name, Loop next) {
    }

    record Indirect(Wrapper wrapper) {
    }

    record Wrapper(List<Indirect> inner) {
    }

    record WithOptional(Optional<String> name) {
    }

    record WithArray(String[] names) {
    }

    record WithIntKeys(Map<Integer, String> names) {
    }

    record WithObject(Object anything) {
    }

    @Test
    void aRecordGivesAStrictSchemaEveryComponentRequired() {
        Map<String, Object> schema = Schemas.ofRecord(Proposal.class);
        assertEquals("object", schema.get("type"));
        assertEquals(false, schema.get("additionalProperties"));
        assertEquals(List.of("sql", "lines", "counts", "kind", "urgent", "confidence", "day", "at"),
                schema.get("required"));
        Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
        assertEquals(Map.of("type", "string", "description", "one SELECT"), properties.get("sql"));
        Map<?, ?> lines = (Map<?, ?>) properties.get("lines");
        assertEquals("array", lines.get("type"));
        Map<?, ?> line = (Map<?, ?>) lines.get("items");
        assertEquals(List.of("code", "qty", "deposit"), line.get("required"));
        assertEquals(Map.of("type", "integer", "description", "units lent"), ((Map<?, ?>) line.get("properties"))
                .get("qty"));
        Map<?, ?> deposit = (Map<?, ?>) ((Map<?, ?>) line.get("properties")).get("deposit");
        assertEquals("string", deposit.get("type"), "a decimal travels as text");
        assertTrue(String.valueOf(deposit.get("description")).contains("decimal"));
        assertEquals(Map.of("type", "object", "additionalProperties", Map.of("type", "integer")),
                properties.get("counts"));
        assertEquals(Map.of("type", "string", "enum", List.of("LEND", "RETURN")), properties.get("kind"));
        assertEquals(Map.of("type", "boolean"), properties.get("urgent"));
        assertEquals(Map.of("type", "number"), properties.get("confidence"));
        assertEquals("string", ((Map<?, ?>) properties.get("day")).get("type"));
        assertEquals("string", ((Map<?, ?>) properties.get("at")).get("type"));
        String text = Json.write(schema);
        for (String forbidden : List.of("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems",
                "$ref", "format")) {
            assertFalse(text.contains("\"" + forbidden + "\""), forbidden + " is outside the provider's limits");
        }
    }

    @Test
    void theShapesAProviderCannotTakeAreRefusedNamingTheComponent() {
        assertTrue(refused(Loop.class).contains("Loop.next: record Loop contains itself"), refused(Loop.class));
        assertTrue(refused(Indirect.class).contains("contains itself"), "recursion through a list and a record");
        assertTrue(refused(WithOptional.class).contains("WithOptional.name: Optional is not supported"));
        assertTrue(refused(WithArray.class).contains("arrays are not supported"));
        assertTrue(refused(WithIntKeys.class).contains("a Map needs String keys"));
        assertTrue(refused(WithObject.class).contains("java.lang.Object is not supported"));
        assertThrows(IllegalArgumentException.class, () -> Schemas.ofArguments(Loop.class));
    }

    private static String refused(Class<? extends Record> type) {
        return assertThrows(IllegalArgumentException.class, () -> Schemas.ofRecord(type)).getMessage();
    }

    /** What a scenario writes: components optional (a missing one is null), references wherever a value goes. */
    @Test
    void theArgumentsOfAQueryAcceptReferencesAndLeaveComponentsOptional() {
        Map<String, Object> range = Schemas.ofArgumentObject(NumberRange.class);
        assertEquals("object", range.get("type"));
        assertFalse(range.containsKey("required"), "every component may be left out");
        Map<String, Object> schema = Map.of("$defs", Map.of(), "type", "object", "properties", Map.of("deposit",
                range), "additionalProperties", false);
        assertEquals(List.of(), ScenarioSchema.validate(schema, Map.of("deposit", Map.of("between", List.of("20.00",
                "200.00"), "lte", "$systemMax"))));
        assertEquals(List.of(), ScenarioSchema.validate(schema, Map.of("deposit", Map.of("gte", 3L))),
                "a number is a decimal too");
        assertEquals("$.deposit.gt", ScenarioSchema.validate(schema, Map.of("deposit", Map.of("gt", true))).get(0)
                .path());
        assertEquals("$.deposit.lower", ScenarioSchema.validate(schema, Map.of("deposit", Map.of("lower", 1L)))
                .get(0).path(), "an unknown key, as the codec refuses it");
        Map<String, Object> line = Schemas.ofArgumentObject(Line.class);
        assertEquals(List.of("qty"), line.get("required"), "a primitive component cannot be left out");
        Map<String, Object> whole = Schemas.ofArguments(Line.class);
        assertTrue(whole.containsKey("anyOf"), "the value itself may be a reference");
        assertEquals(List.of(), ScenarioSchema.validate(Map.of("$defs", Map.of(), "properties", Map.of("x", whole)),
                Map.of("x", "$item")));
    }
}
