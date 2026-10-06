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

package ai.skillroy.testjava.protocol.sql;

import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowsTest {

    private static Rows sample(boolean truncated) {
        List<Rows.Column> columns = Arrays.asList(new Rows.Column("CODE", "VARCHAR"),
                new Rows.Column("Deposit", "DECIMAL"), new Rows.Column("c3", null));
        List<List<Object>> rows = new ArrayList<List<Object>>();
        rows.add(Arrays.<Object>asList("100", "34.99", Boolean.TRUE));
        rows.add(Arrays.<Object>asList("200", null, Long.valueOf(7)));
        rows.add(Arrays.<Object>asList("300", "0.50", Double.valueOf(1.5)));
        return Rows.of(columns, rows, truncated);
    }

    @Test
    void roundTripsThroughTheWireForm() {
        for (boolean truncated : new boolean[] {false, true}) {
            Rows rows = sample(truncated);
            Map<String, Object> json = rows.toJson();
            assertEquals(Arrays.asList("columns", "rows", "rowCount", "truncated"),
                    new ArrayList<String>(json.keySet()));
            assertEquals(3L, json.get("rowCount"));
            assertEquals(rows, Rows.fromJson(json));
            // Exactly as the wire carries it: written and parsed by the codec.
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) Json.parse(Json.write(json));
            Rows back = Rows.fromJson(parsed);
            assertEquals(rows, back);
            assertEquals(rows.hashCode(), back.hashCode());
            assertEquals(truncated, back.truncated());
        }
    }

    @Test
    void theWireFormIsTheDataQueryShape() {
        assertEquals("{\"columns\":[{\"name\":\"CODE\",\"type\":\"VARCHAR\"},{\"name\":\"Deposit\",\"type\":"
                + "\"DECIMAL\"},{\"name\":\"c3\",\"type\":null}],\"rows\":[[\"100\",\"34.99\",true],[\"200\",null,7],"
                + "[\"300\",\"0.50\",1.5]],\"rowCount\":3,\"truncated\":false}", Json.write(sample(false).toJson()));
    }

    @Test
    void indexIgnoresCaseAndRefusesAmbiguity() {
        Rows rows = sample(false);
        assertEquals(0, rows.index("code"));
        assertEquals(0, rows.index("CODE"));
        assertEquals(1, rows.index("deposit"));
        assertEquals(2, rows.index("C3"));
        assertEquals(-1, rows.index("missing"));
        assertEquals(-1, rows.index(null));
        Rows twice = Rows.of(Arrays.asList(new Rows.Column("id", "INTEGER"), new Rows.Column("ID", "INTEGER")),
                Collections.singletonList(Arrays.<Object>asList(1L, 2L)), false);
        assertEquals(-1, twice.index("id"), "ambiguous");
    }

    @Test
    void accessorsAndImmutability() {
        Rows rows = sample(false);
        assertEquals(3, rows.rowCount());
        assertEquals("34.99", rows.get(0, 1));
        assertEquals("VARCHAR", rows.columns().get(0).type());
        assertThrows(UnsupportedOperationException.class, () -> rows.rows().add(null));
        assertThrows(UnsupportedOperationException.class, () -> rows.rows().get(0).set(0, "x"));
        assertThrows(UnsupportedOperationException.class, () -> rows.columns().clear());
        List<List<Object>> source = new ArrayList<List<Object>>();
        source.add(new ArrayList<Object>(Arrays.<Object>asList("a")));
        Rows copy = Rows.of(Collections.singletonList(new Rows.Column("x", null)), source, false);
        source.get(0).set(0, "b");
        assertEquals("a", copy.get(0, 0), "the parts are copied");
        assertThrows(IndexOutOfBoundsException.class, () -> rows.get(3, 0));
    }

    @Test
    void equalityCoversColumnsRowsAndTruncation() {
        assertEquals(sample(false), sample(false));
        assertNotEquals(sample(false), sample(true));
        Rows renamed = Rows.of(Arrays.asList(new Rows.Column("code", "VARCHAR"), new Rows.Column("Deposit", "DECIMAL"),
                new Rows.Column("c3", null)), sample(false).rows(), false);
        assertNotEquals(sample(false), renamed);
    }

    @Test
    void textNeverHoldsCellValues() {
        String text = sample(false).toString();
        assertTrue(text.contains("rowCount=3"), text);
        assertFalse(text.contains("34.99"), text);
        assertFalse(text.contains("100"), text);
    }

    @Test
    void malformedPartsAreRefused() {
        List<Rows.Column> one = Collections.singletonList(new Rows.Column("a", null));
        assertThrows(IllegalArgumentException.class, () -> new Rows.Column(null, "X"));
        assertThrows(IllegalArgumentException.class, () -> Rows.of(null, Collections.<List<Object>>emptyList(), false));
        assertThrows(IllegalArgumentException.class, () -> Rows.of(one,
                Collections.singletonList(Arrays.<Object>asList("x", "y")), false));
        Map<String, Object> json = new LinkedHashMap<String, Object>(sample(false).toJson());
        json.put("rowCount", 2L);
        assertThrows(IllegalArgumentException.class, () -> Rows.fromJson(json));
        json.put("rowCount", 3L);
        json.put("truncated", "no");
        assertThrows(IllegalArgumentException.class, () -> Rows.fromJson(json));
        json.remove("truncated");
        assertFalse(Rows.fromJson(json).truncated(), "absent truncated means false");
        json.put("columns", "x");
        assertThrows(IllegalArgumentException.class, () -> Rows.fromJson(json));
        assertThrows(IllegalArgumentException.class, () -> Rows.fromJson(null));
    }
}
