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

package ai.skillroy.testjava.inference.prompt;

import ai.skillroy.testjava.inference.spi.ToolSpec;
import ai.skillroy.testjava.inference.tools.ToolProjection;
import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 security fix, pass 2, P2-L5-F1 and P2-L5-S1 (the sceptics' shapes A, D, F, G, H and I; J and K the controls
 * that S49 already covered): {@code PromptMask.schema} carries a secret-named property's secrecy into everything nested
 * under it and into a definition it references, and treats a data keyword's value as data, dropping it whole when it
 * holds a secret-named key. Each mark is absent from the masked schema (the catalogue's and the planner's), from the
 * masked strict tool (the session's and the MCP server's tools, a recording's {@code request.tools}) and from the
 * session's system-part line; the property names and types stay. Also the capture rule of P2-L6-F2/F3: a model never
 * reads an image's bytes as text.
 */
class PromptMaskSchemaTest {

    private final PromptMask mask = PromptMask.keysOnly();

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> o = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            o.put((String) kv[i], kv[i + 1]);
        }
        return o;
    }

    /** The mark absent at every site the vocabulary reaches a model; the masked schema. */
    private String assertMasked(Map<String, Object> schema, String... marks) {
        String schemaOut = Json.writeCanonical(mask.schema(schema));
        ToolSpec tool = ToolProjection.masked(ToolProjection.verb("lending", "probeVerb", schema), mask);
        String toolOut = Json.writeCanonical(tool.inputSchema());
        String vocab = mask.text("- probeVerb (tool lending_probe_verb): " + schemaOut);
        for (String mark : marks) {
            assertFalse(schemaOut.contains(mark), mark + " in the schema: " + schemaOut);
            assertFalse(toolOut.contains(mark), mark + " in the tool: " + toolOut);
            assertFalse(vocab.contains(mark), mark + " in the system part: " + vocab);
        }
        return schemaOut;
    }

    @Test
    void aSecretObjectsSubPropertiesLoseTheirValues() {
        Map<String, Object> a = m("type", "object", "properties", m("password", m("type", "object", "properties",
                m("current", m("type", "string", "default", "Probe-A-Default", "enum", List.of("Probe-A-Enum")),
                        "hint", m("type", "string", "const", "Probe-A-Const")))));
        String out = assertMasked(a, "Probe-A-Default", "Probe-A-Enum", "Probe-A-Const");
        assertTrue(out.contains("\"current\":{\"type\":\"string\"}"), "the name and the type stay: " + out);
        Map<String, Object> i = m("type", "object", "properties", m("password", m("type", "array", "items",
                m("type", "object", "properties", m("v", m("type", "string", "default", "Probe-I-ItemsObj"))))));
        assertMasked(i, "Probe-I-ItemsObj");
    }

    @Test
    void aDefinitionASecretPropertyReferencesIsSecretAlongItsReferences() {
        Map<String, Object> d = m("type", "object", "$defs", m("Pin", m("type", "string", "default", "Probe-D-Def")),
                "properties", m("password", m("$ref", "#/$defs/Pin")));
        String out = assertMasked(d, "Probe-D-Def");
        assertTrue(out.contains("\"$ref\":\"#/$defs/Pin\""), "the reference stays: " + out);
        Map<String, Object> chain = m("type", "object", "definitions", m(
                "Outer", m("type", "object", "properties", m("inner", m("$ref", "#/definitions/Inner"))),
                "Inner", m("type", "string", "enum", List.of("Probe-Chain-Enum")),
                "Plain", m("type", "string", "default", "kept-plain")),
                "properties", m("credential", m("$ref", "#/definitions/Outer"), "note", m("$ref",
                        "#/definitions/Plain")));
        String chained = assertMasked(chain, "Probe-Chain-Enum");
        assertTrue(chained.contains("kept-plain"), "a definition no secret references keeps its data: " + chained);
        Map<String, Object> named = m("type", "object", "$defs", m("apiKey", m("type", "string", "default",
                "Probe-Named-Def")), "properties", m("x", m("type", "string")));
        assertMasked(named, "Probe-Named-Def");
    }

    @Test
    void aPatternThatNamesASecretPropertyIsSecret() {
        Map<String, Object> h = m("type", "object", "patternProperties", m("^password", m("type", "string",
                "default", "Probe-H-Pattern")));
        assertMasked(h, "Probe-H-Pattern");
        Map<String, Object> dependent = m("type", "object", "dependentSchemas", m("token", m("properties",
                m("scope", m("type", "string", "default", "Probe-Dependent")))));
        assertMasked(dependent, "Probe-Dependent");
    }

    @Test
    void aDataValueThatHoldsASecretKeyIsDroppedWhole() {
        Map<String, Object> f = m("type", "object", "properties", m("signOn", m("type", "object",
                "properties", m("staff", m("type", "string"), "password", m("type", "string")),
                "default", m("staff", "ann", "password", "Probe-F-ObjDefault"))));
        String out = assertMasked(f, "Probe-F-ObjDefault");
        assertFalse(out.contains("\"default\""), "dropped whole, never pinned to <redacted>: " + out);
        Map<String, Object> g = m("type", "object", "properties", m("signOn", m("type", "object",
                "examples", List.of(m("staff", "ann", "password", "Probe-G-Examples")))));
        assertMasked(g, "Probe-G-Examples");
        Map<String, Object> plain = m("type", "object", "properties", m("item", m("type", "object",
                "default", m("code", "HAM-1", "note", "token=Probe-Text-Rule"), "enum", List.of(1L, 2L))));
        String kept = assertMasked(plain, "Probe-Text-Rule");
        assertTrue(kept.contains("\"code\":\"HAM-1\""), "data without a secret key stays, through the text rule: "
                + kept);
        assertTrue(kept.contains("\"enum\":[1,2]"), kept);
    }

    @Test
    void theControlsStayAsS49MadeThem() {
        assertMasked(m("type", "object", "properties", m("password", m("type", "string", "default",
                "Probe-J-Flat"))), "Probe-J-Flat");
        assertMasked(m("type", "object", "properties", m("password", m("type", "object", "additionalProperties",
                m("type", "string", "default", "Probe-K-AddlProps")))), "Probe-K-AddlProps");
        Map<String, Object> ordinary = m("type", "object", "properties", m("item", m("type", "string",
                "description", "the item's code", "enum", List.of("HAM-1", "SAW-2"))), "required", List.of("item"));
        assertEquals(ordinary, mask.schema(ordinary), "an ordinary schema is unchanged");
    }

    @Test
    void aModelNeverReadsAnImagesBytesAsText() {
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5E"
                + "rkJggg==";
        Map<String, Object> capture = m("format", "png", "width", 1L, "height", 1L, "base64", png);
        String text = Json.write(mask.jsonLike(m("value", m("deep", List.of(capture)))));
        assertFalse(text.contains("iVBORw0KGgo"), text);
        assertTrue(text.contains("<png: 70 bytes>"), text);
        String block = mask.block("target-state", "{\"view\":{\"format\":\"png\",\"base64\":\"" + png + "\"}}");
        assertFalse(block.contains("iVBORw0KGgo"), block);
        assertEquals("<img src=\"data:image/png;base64,<png: 70 bytes>\">",
                mask.text("<img src=\"data:image/png;base64," + png + "\">"));
    }
}
