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

package ai.skillroy.testjava.inference.spi;

import ai.skillroy.testjava.inference.resolve.SqlProposal;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.Schemas;
import ai.skillroy.testjava.scenario.resolve.CodecException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 15.1 item 1: {@code Schemas.ofRecord} on this contract's records (compliant schemas; the refused
 * shapes refused) and {@code Structured.decode} through the codec, with the one addition the codec lacks (a
 * {@code double}).
 */
class StructuredTest {

    record Loan(String code, BigDecimal deposit, LocalDate due, List<Line> lines, double score) {
    }

    record Line(String item, int qty) {
    }

    record Bad(Optional<String> maybe) {
    }

    record Self(Self next) {
    }

    private static Map<String, Object> proposal() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("sql", "SELECT i.code FROM item i WHERE i.category = ?");
        json.put("params", List.of(Map.of("name", "category", "value", "POWER", "type", "STRING")));
        json.put("rationale", "the items of the category");
        json.put("tablesUsed", List.of("item"));
        json.put("orderBy", "i.code");
        json.put("confidence", 0.9);
        json.put("expectedRows", Map.of("min", 1L, "max", 5L));
        json.put("cannotAnswer", false);
        json.put("cannotAnswerReason", "");
        return json;
    }

    @Test
    void aProposalDecodesThroughTheCodecWithItsConfidence() throws Exception {
        SqlProposal p = Structured.decode(SqlProposal.class, proposal());
        assertEquals(0.9, p.confidence());
        assertEquals("POWER", p.params().get(0).value());
        assertEquals(new SqlProposal.RowBounds(1, 5), p.expectedRows());
        Map<String, Object> whole = proposal();
        whole.put("confidence", 1L);
        assertEquals(1.0, Structured.decode(SqlProposal.class, whole).confidence(), "a whole number is a number");
        Map<String, Object> text = proposal();
        text.put("confidence", "0.9");
        assertThrows(CodecException.class, () -> Structured.decode(SqlProposal.class, text), "text is not a number");
        Map<String, Object> unknown = proposal();
        unknown.put("extra", 1L);
        assertTrue(assertThrows(CodecException.class, () -> Structured.decode(SqlProposal.class, unknown))
                .getMessage().contains("unknown key 'extra'"));
        Map<String, Object> missing = proposal();
        missing.remove("cannotAnswer");
        assertThrows(CodecException.class, () -> Structured.decode(SqlProposal.class, missing),
                "a primitive needs a value");
    }

    @Test
    void nestedRecordsListsAndScalarsFollowTheCodecsRules() throws Exception {
        Map<String, Object> json = Map.of("code", "L-1", "deposit", "40.00", "due", "2026-10-15",
                "lines", List.of(Map.of("item", "DRL-01", "qty", 2L)), "score", 0.5);
        Loan loan = Structured.decode(Loan.class, json);
        assertEquals(new BigDecimal("40.00"), loan.deposit());
        assertEquals(2, loan.lines().get(0).qty());
        CodecException e = assertThrows(CodecException.class, () -> Structured.decode(Loan.class, Map.of("code",
                "L-1", "deposit", "40.00", "due", "15.10.2026", "lines", List.of(), "score", 0.5)));
        assertTrue(e.getMessage().startsWith("due"), e.getMessage());
        assertFalse(e.getMessage().contains("15.10.2026"), "never quotes a value");
        assertEquals(new Line("x", 1), Structured.decode(Line.class, Map.of("item", "x", "qty", 1L)),
                "a record the codec supports goes to the codec");
        Object encoded = Structured.encode(loan);
        assertEquals(loan, Structured.decode(Loan.class, encoded), "round trip with a double");
        assertEquals("{\"code\":\"L-1\",\"deposit\":\"40.00\",\"due\":\"2026-10-15\",\"lines\":[{\"item\":\"DRL-01\","
                + "\"qty\":2}],\"score\":0.5}", Json.write(encoded));
    }

    @Test
    void theContractsRecordsHaveStrictSchemasAndTheRefusedShapesAreRefused() {
        Map<String, Object> schema = Schemas.ofRecord(SqlProposal.class);
        assertEquals("object", schema.get("type"));
        assertEquals(false, schema.get("additionalProperties"));
        assertEquals(List.of("sql", "params", "rationale", "tablesUsed", "orderBy", "confidence", "expectedRows",
                "cannotAnswer", "cannotAnswerReason"), schema.get("required"), "every component is required");
        Map<?, ?> props = (Map<?, ?>) schema.get("properties");
        assertEquals("number", ((Map<?, ?>) props.get("confidence")).get("type"));
        Map<?, ?> params = (Map<?, ?>) props.get("params");
        assertEquals("array", params.get("type"));
        assertEquals(false, ((Map<?, ?>) params.get("items")).get("additionalProperties"));
        String text = Json.write(schema);
        for (String forbidden : List.of("minimum", "maximum", "maxLength", "minLength", "$ref", "minItems")) {
            assertFalse(text.contains(forbidden), forbidden);
        }
        assertThrows(IllegalArgumentException.class, () -> Schemas.ofRecord(Bad.class));
        assertThrows(IllegalArgumentException.class, () -> Schemas.ofRecord(Self.class), "no recursion");
    }
}
