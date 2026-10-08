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

package ai.skillroy.testjava.samples.lending.pack;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.ScenarioSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The lending verbs' argument schemas (phase5-contracts.md 9.5, 14.1): one for every verb, each an object of the
 * keys the verb takes, within the subset of JSON Schema the scenario schema's validator reads and a provider accepts,
 * and in agreement with what the verb compiles: the forms a scenario writes are valid, the mistakes the verb refuses
 * are refused with the key's path, and the few places where the two differ by design are listed here.
 */
class LendingStepSchemasTest {

    /** The keywords a verb's schema may use: the validator's subset, less $ref, plus the annotation it reads. */
    private static final Set<String> KEYWORDS = Set.of("type", "properties", "required", "additionalProperties",
            "items", "minItems", "pattern", "enum", "const", "anyOf", "oneOf", "description");

    private final LendingStepsTest.Ctx ctx = new LendingStepsTest.Ctx();

    private static Map<String, Object> schema(String verb) {
        Map<String, Object> schema = LendingSteps.INSTANCE.argsSchema(verb);
        assertNotNull(schema, verb);
        return schema;
    }

    private static List<ScenarioSchema.Problem> validate(String verb, Object args) {
        return ScenarioSchema.validate(schema(verb), args);
    }

    private boolean compiles(String verb, Object args) {
        try {
            LendingSteps.INSTANCE.compile(verb, args, ctx);
            return true;
        } catch (ScenarioException e) {
            return false;
        }
    }

    @Test
    void everyVerbDeclaresAnObjectSchemaOfItsKeys() {
        Map<String, List<String>> keys = new LinkedHashMap<>();
        Map<String, List<Object>> required = new LinkedHashMap<>();
        for (String verb : LendingSteps.INSTANCE.verbs()) {
            Map<String, Object> schema = schema(verb);
            assertEquals("object", schema.get("type"), verb);
            assertEquals(Boolean.FALSE, schema.get("additionalProperties"), verb + ": no other key");
            assertTrue(schema.get("description") instanceof String text && !text.isBlank(), verb);
            Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
            keys.put(verb, properties.keySet().stream().map(String::valueOf).toList());
            required.put(verb, schema.get("required") instanceof List<?> list ? List.copyOf(list) : List.of());
            for (Map.Entry<?, ?> property : properties.entrySet()) {
                assertTrue(((Map<?, ?>) property.getValue()).get("description") instanceof String,
                        verb + "." + property.getKey() + " says what it means");
            }
        }
        assertEquals(Map.of("signOn", List.of("staff"), "signOff", List.of(), "scan", List.of("item", "barcode",
                "times"), "key", List.of("action", "value"), "enter", List.of("value"), "quantity", List.of("n"),
                "deposit", List.of("amount"), "expectPrompt", List.of("id", "text", "within"), "answerPrompt",
                List.of("choice", "id"), "authorise", List.of("staff")), keys, "the keys each verb's compile reads");
        assertEquals(Map.of("signOn", List.of("staff"), "signOff", List.of(), "scan", List.of(), "key",
                List.of("action"), "enter", List.of("value"), "quantity", List.of("n"), "deposit", List.of(),
                "expectPrompt", List.of(), "answerPrompt", List.of("choice"), "authorise", List.of("staff")),
                required, "the keys each verb's compile requires");
        assertNull(LendingSteps.INSTANCE.argsSchema("teleport"), "not a lending verb");
        assertNull(LendingSteps.INSTANCE.argsSchema("click"), "a base verb is the base library's");
        assertNull(LendingSteps.INSTANCE.argsSchema(null));
    }

    @Test
    void everyCallBuildsNewMapsAndTheSameSchema() {
        for (String verb : LendingSteps.INSTANCE.verbs()) {
            Map<String, Object> first = schema(verb);
            first.put("description", "changed by a caller");
            Map<String, Object> second = schema(verb);
            assertNotSame(first, second);
            assertFalse(second.get("description").equals("changed by a caller"), verb);
            assertEquals(Json.write(second), Json.write(schema(verb)), verb + ": a fixed key order");
        }
    }

    /**
     * Only the subset's keywords, no {@code $ref} (a verb's schema stands alone as a tool's input schema), no numeric
     * or length bound anywhere, every {@code required} key a property, every pattern a regular expression.
     */
    @Test
    void theSchemasStayWithinTheSubsetAndStandAlone() {
        for (String verb : LendingSteps.INSTANCE.verbs()) {
            walk(verb, schema(verb));
            String text = Json.write(schema(verb));
            for (String forbidden : List.of("\"$ref\"", "minimum", "maximum", "minLength", "maxLength", "maxItems",
                    "\"format\"", "http")) {
                assertFalse(text.contains(forbidden), verb + " uses " + forbidden);
            }
        }
    }

    private static void walk(String where, Map<?, ?> schema) {
        for (Map.Entry<?, ?> entry : schema.entrySet()) {
            String keyword = String.valueOf(entry.getKey());
            assertTrue(KEYWORDS.contains(keyword), where + ": keyword " + keyword);
            Object value = entry.getValue();
            switch (keyword) {
                case "properties" -> {
                    for (Map.Entry<?, ?> property : ((Map<?, ?>) value).entrySet()) {
                        walk(where + "." + property.getKey(), (Map<?, ?>) property.getValue());
                    }
                }
                case "anyOf", "oneOf" -> {
                    int i = 0;
                    for (Object alternative : (List<?>) value) {
                        walk(where + "|" + i++, (Map<?, ?>) alternative);
                    }
                }
                case "items" -> walk(where + "[]", (Map<?, ?>) value);
                case "additionalProperties" -> assertEquals(Boolean.FALSE, value, where);
                case "pattern" -> Pattern.compile((String) value);
                case "required" -> {
                    Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
                    for (Object key : (List<?>) value) {
                        assertTrue(properties.containsKey(key), where + ": required " + key + " is a property");
                    }
                }
                default -> {
                }
            }
        }
    }

    /** The forms the committed scenarios and the verb's own tests write: valid, and they compile. */
    @Test
    void whatTheVerbsCompileTheSchemasAccept() {
        Object[][] valid = {
            {"signOn", Map.of("staff", "$assistant")},
            {"signOff", Map.of()},
            {"scan", Map.of("item", "$item")},
            {"scan", Map.of("item", "$item", "times", 2L)},
            {"scan", Map.of("barcode", "0000000000000")},
            {"scan", Map.of("barcode", "$item.barcode")},
            {"scan", Map.of("item", Map.of("code", "YRD-001", "barcode", "5000000000017"))},
            {"key", Map.of("action", "DUE")},
            {"key", Map.of("action", "$keys.RETURN")},
            {"key", Map.of("action", "QTY", "value", 3L)},
            {"enter", Map.of("value", "YRD-001")},
            {"enter", Map.of("value", "$assistant.code")},
            {"enter", Map.of("value", 42L)},
            {"quantity", Map.of("n", 2L)},
            {"quantity", Map.of("n", 0.25)},
            {"quantity", Map.of("n", "$oracle.needed.qty")},
            {"deposit", Map.of()},
            {"deposit", Map.of("amount", "10.00")},
            {"deposit", Map.of("amount", 7.5)},
            {"deposit", Map.of("amount", "$oracle.due.total")},
            {"expectPrompt", Map.of()},
            {"expectPrompt", Map.of("id", "supervisor-required", "within", "10s")},
            {"expectPrompt", Map.of("text", "Unknown item", "within", 5000L)},
            {"answerPrompt", Map.of("choice", "OK")},
            {"answerPrompt", Map.of("choice", "Yes", "id", "induction-check")},
            {"authorise", Map.of("staff", "$supervisor")},
        };
        for (Object[] example : valid) {
            String verb = (String) example[0];
            assertEquals(List.of(), validate(verb, example[1]), verb + " " + example[1]);
            assertTrue(compiles(verb, example[1]), verb + " " + example[1] + " compiles");
        }
    }

    /** Mistakes the verb refuses: the schema refuses them too, and says where. */
    @Test
    void whatTheVerbsRefuseTheSchemasRefuseWithThePath() {
        Object[][] invalid = {
            {"signOn", Map.of("staff", "100"), "$.staff"},
            {"signOn", Map.of("staff", 100L), "$.staff"},
            {"signOn", Map.of("staff", Map.of("code", "100")), "$.staff"},
            {"signOn", Map.of("staff", "$assistant.code"), "$.staff"},
            {"signOn", Map.of("staff", "$assistant", "extra", 1L), "$.extra"},
            {"signOn", Map.of(), "$"},
            {"authorise", Map.of("staff", "$supervisor", "choice", "OK"), "$.choice"},
            {"signOff", Map.of("staff", "$assistant"), "$.staff"},
            {"scan", Map.of("item", "$item", "colour", "red"), "$.colour"},
            {"scan", Map.of("barcode", "1", "times", "2"), "$.times"},
            {"scan", Map.of("item", Map.of("code", "T1001")), "$.item"},
            {"key", Map.of("action", "PRINT"), "$.action"},
            {"key", Map.of("action", "$keys"), "$.action"},
            {"key", Map.of(), "$"},
            {"enter", Map.of("value", List.of("a")), "$.value"},
            {"enter", Map.of(), "$"},
            {"quantity", Map.of(), "$"},
            {"quantity", Map.of("n", List.of(1L)), "$.n"},
            {"deposit", Map.of("amount", "1.00", "note", "x"), "$.note"},
            {"deposit", Map.of("amount", List.of("1.00")), "$.amount"},
            {"deposit", "10.00", "$"},
            {"expectPrompt", Map.of("within", "soon"), "$.within"},
            {"expectPrompt", Map.of("button", "OK"), "$.button"},
            {"answerPrompt", Map.of(), "$"},
            {"answerPrompt", Map.of("choice", "OK", "button", 2L), "$.button"},
        };
        for (Object[] example : invalid) {
            String verb = (String) example[0];
            List<ScenarioSchema.Problem> problems = validate(verb, example[1]);
            if (problems.isEmpty()) {
                fail(verb + " " + example[1] + " is valid against the schema");
            }
            assertEquals(example[2], problems.get(0).path(), verb + " " + example[1] + ": " + problems);
            assertFalse(compiles(verb, example[1]), verb + " " + example[1] + " compiles");
        }
    }

    /**
     * Where the schema and the verb differ, by design: the verb's own bounds (a count from 1 to 99, a wait of at most
     * 119 s, exactly one of item and barcode) are in the descriptions only, since a provider's schemas carry no numeric
     * bound and a tool's input has no top-level alternative; the YAML form without a value ({@code - signOff:}) is
     * not a tool's input; a quantity written as text is the verb's leniency, not a form to offer.
     */
    @Test
    void theDifferencesBetweenSchemaAndVerbAreTheDeclaredOnes() {
        List<String> looser = new ArrayList<>();
        for (Object[] example : new Object[][] {
            {"scan", Map.of()},
            {"scan", Map.of("item", "$item", "barcode", "1")},
            {"scan", Map.of("barcode", "1", "times", 100L)},
            {"scan", Map.of("barcode", "1", "times", 0L)},
            {"expectPrompt", Map.of("within", "200s")},
        }) {
            assertEquals(List.of(), validate((String) example[0], example[1]));
            assertFalse(compiles((String) example[0], example[1]));
            looser.add(example[0] + " " + example[1]);
        }
        assertEquals(5, looser.size());
        String scan = String.valueOf(schema("scan").get("description"));
        assertTrue(scan.contains("exactly one of item and barcode"), scan);
        assertTrue(String.valueOf(((Map<?, ?>) ((Map<?, ?>) schema("scan").get("properties")).get("times"))
                .get("description")).contains("from 1 to 99"));
        assertTrue(String.valueOf(((Map<?, ?>) ((Map<?, ?>) schema("expectPrompt").get("properties")).get("within"))
                .get("description")).contains("at most 119 s"));
        for (Object[] example : new Object[][] {
            {"signOff", null},
            {"deposit", null},
            {"expectPrompt", null},
            {"quantity", Map.of("n", "1.5")},
        }) {
            assertFalse(validate((String) example[0], example[1]).isEmpty(), example[0] + " " + example[1]);
            assertTrue(compiles((String) example[0], example[1]), example[0] + " " + example[1]);
        }
    }

    /**
     * A key that takes a reference says so ("or a $reference"), and {@code staff}, which takes nothing else, is
     * typed as one: a {@code $slot} and no field of it.
     */
    @Test
    void referencesAreDescribedAndStaffIsOnlyAReference() {
        for (String[] key : new String[][] {{"scan", "item"}, {"scan", "barcode"}, {"key", "action"}, {"key", "value"},
                {"enter", "value"}, {"quantity", "n"}, {"deposit", "amount"}, {"expectPrompt", "id"},
                {"expectPrompt", "text"}, {"answerPrompt", "choice"}}) {
            Map<?, ?> property = (Map<?, ?>) ((Map<?, ?>) schema(key[0]).get("properties")).get(key[1]);
            assertTrue(String.valueOf(property.get("description")).contains("$reference"), key[0] + "." + key[1]);
        }
        for (String verb : List.of("signOn", "authorise")) {
            Map<?, ?> staff = (Map<?, ?>) ((Map<?, ?>) schema(verb).get("properties")).get("staff");
            assertEquals("string", staff.get("type"));
            assertEquals(LendingStepSchemas.SLOT_REFERENCE, staff.get("pattern"));
            assertTrue(String.valueOf(staff.get("description")).contains("credential"), verb);
        }
        Map<?, ?> amount = (Map<?, ?>) ((Map<?, ?>) schema("deposit").get("properties")).get("amount");
        assertEquals(List.of(Map.of("type", "string"), Map.of("type", "number")), amount.get("anyOf"),
                "money as the queries' arguments take it: a decimal string or a number");
    }
}
