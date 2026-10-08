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

import ai.skillroy.testjava.data.yaml.StrictYaml;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The export's YAML writer: what it writes, the strict reader reads back to equal values. */
class YamlTextTest {

    private static Object readBack(String text) throws Exception {
        return StrictYaml.read(text.getBytes(StandardCharsets.UTF_8)).root();
    }

    @Test
    void trickyScalarsReadBackAsTheyWere() throws Exception {
        List<Object> values = List.of("plain", "$item.code", "$$5", "100", "0100", "5000000000017", "3.50", "1e5",
                "true", "No", "on", "null", "~", "", " padded ", "a: b", "a #b", "- x", "[x]", "{x}", "x,y", "\"q\"",
                "back\\slash", "line\nbreak", "tab\there", "\u001b[31m", "é ü", "日本", " ", "<redacted>",
                "2026-10-08", "*star", "&anchor", "!tag", "%pct", "@at", "`tick`", "?q", "|pipe", ">gt", "'single'",
                7L, -3L, 0L, 2.5, 1.0e10, -0.125, true, false);
        Map<String, Object> document = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            document.put("k" + i, values.get(i));
        }
        document.put("nested", Map.of("list", List.of(Map.of("a", "$x"), List.of(), Map.of())));
        document.put("null value", null);
        document.put("weird: key", "v");
        YamlText yaml = new YamlText();
        for (Map.Entry<String, Object> e : document.entrySet()) {
            yaml.entry(0, e.getKey(), e.getValue(), "a comment # with a hash");
        }
        assertEquals(document, readBack(yaml.text()), yaml.text());
    }

    @Test
    void decimalsAndWholeNumbersKeepTheirKind() throws Exception {
        YamlText yaml = new YamlText();
        yaml.entry(0, "big", new BigDecimal("12.50"), null);
        yaml.entry(0, "whole", new BigDecimal("12"), null);
        yaml.entry(0, "fraction", 1.0, null);
        Map<?, ?> read = (Map<?, ?>) readBack(yaml.text());
        assertEquals(12.5, read.get("big"));
        assertEquals(12.0, read.get("whole"), "a decimal stays a number with a fraction");
        assertEquals(1.0, read.get("fraction"));
        assertThrows(IllegalArgumentException.class, () -> YamlText.scalar(Double.NaN));
    }

    @Test
    void aLongValueGoesOntoBlockLinesAndStepsKeepTheirForm() throws Exception {
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add("item-" + i);
        }
        Map<String, Object> step = Map.of("call", new LinkedHashMap<>(Map.of("primitive", "toy.input", "args",
                Map.of("values", many))));
        YamlText yaml = new YamlText();
        yaml.line("steps:");
        yaml.item(2, step, "observed");
        yaml.item(2, Map.of("deposit", Map.of()), null);
        yaml.item(2, Map.of("dismiss", "OK"), null);
        String text = yaml.text();
        for (String line : text.split("\n")) {
            assertTrue(line.length() <= YamlText.WIDTH, line);
        }
        assertTrue(text.startsWith("steps:\n  - call:    # observed\n"), text);
        assertTrue(text.contains("  - deposit: {}\n"), text);
        Map<?, ?> read = (Map<?, ?>) readBack(text);
        assertEquals(List.of(step, Map.of("deposit", Map.of()), Map.of("dismiss", "OK")), read.get("steps"));
        assertEquals(text.split("\n").length, yaml.lines(), "lines are counted");
    }
}
