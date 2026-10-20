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

package ai.skillroy.testjava.agent.api;

import ai.skillroy.testjava.agent.api.spi.DataSourceProvider;
import ai.skillroy.testjava.agent.api.spi.DialogInfo;
import ai.skillroy.testjava.agent.api.spi.QueryResult;
import ai.skillroy.testjava.protocol.CapabilityState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueTypesTest {

    @Test
    void probeResultFactories() {
        assertSame(ProbeResult.ok(), ProbeResult.ok());
        assertEquals(CapabilityState.OK, ProbeResult.ok().state());
        assertNull(ProbeResult.ok().reason());
        assertNull(ProbeResult.ok().strategy());
        assertEquals("paint", ProbeResult.ok("paint").strategy());
        assertEquals(CapabilityState.OK, ProbeResult.ok("paint").state());
        assertEquals(CapabilityState.DEGRADED, ProbeResult.degraded("slow").state());
        assertEquals("slow", ProbeResult.degraded("slow").reason());
        assertEquals(CapabilityState.UNAVAILABLE, ProbeResult.unavailable("headless").state());
        assertEquals("headless", ProbeResult.unavailable("headless").reason());
        assertEquals(ProbeResult.degraded("x"), ProbeResult.degraded("x"));
        assertEquals(ProbeResult.degraded("x").hashCode(), ProbeResult.degraded("x").hashCode());
        assertNotEquals(ProbeResult.degraded("x"), ProbeResult.unavailable("x"));
        assertTrue(ProbeResult.unavailable("headless").toString().contains("headless"));
    }

    @Test
    void dialogInfoCopiesButtons() {
        List<String> buttons = new ArrayList<String>(Arrays.asList("OK", "Cancel"));
        DialogInfo info = new DialogInfo("lending", "Unknown item", "Item not found", buttons);
        buttons.add("Later");
        assertEquals(Arrays.asList("OK", "Cancel"), info.buttons());
        assertThrows(UnsupportedOperationException.class, () -> info.buttons().add("x"));
        assertEquals("lending", info.extractorId());
        assertEquals("Unknown item", info.title());
        assertEquals("Item not found", info.text());
        assertTrue(new DialogInfo(null, null, null, null).buttons().isEmpty());
        assertEquals(info, new DialogInfo("lending", "Unknown item", "Item not found", Arrays.asList("OK", "Cancel")));
        assertNotEquals(info, new DialogInfo("lending", "Unknown item", "Item not found", Arrays.asList("OK")));
    }

    @Test
    void queryResultCopiesItsListsAndAllowsUnnamedColumns() {
        List<String> names = new ArrayList<String>(Arrays.asList("code", "deposit"));
        List<Object> row = new ArrayList<Object>(Arrays.<Object>asList("YRD-001", null));
        List<List<Object>> rows = new ArrayList<List<Object>>();
        rows.add(row);
        QueryResult result = new QueryResult(names, null, rows, true);
        names.add("later");
        row.add("later");
        rows.clear();
        assertEquals(Arrays.asList("code", "deposit"), result.columnNames());
        assertNull(result.columnTypes());
        assertEquals(1, result.rows().size());
        assertEquals(Arrays.<Object>asList("YRD-001", null), result.rows().get(0));
        assertTrue(result.truncated());
        assertThrows(UnsupportedOperationException.class, () -> result.columnNames().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> result.rows().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.rows().get(0).set(0, "x"));

        QueryResult unnamed = new QueryResult(null, null, null, false);
        assertNull(unnamed.columnNames());
        assertTrue(unnamed.rows().isEmpty());
        assertFalse(unnamed.truncated());
        assertEquals(unnamed, new QueryResult(null, null, new ArrayList<List<Object>>(), false));
        assertEquals(unnamed.hashCode(), new QueryResult(null, null, null, false).hashCode());
        assertNotEquals(unnamed, new QueryResult(null, null, null, true));
        assertNotEquals(unnamed, result);
        assertThrows(NullPointerException.class,
                () -> new QueryResult(null, null, Collections.<List<Object>>singletonList(null), false));
        assertFalse(result.toString().contains("YRD-001"), "toString must not leak cell values");
    }

    @Test
    void dataSourceProviderFormsDefaultToNotOffered() throws Exception {
        DataSourceProvider provider = new DataSourceProvider() {
            @Override
            public String id() {
                return "lending";
            }

            @Override
            public String describe() {
                return "in-memory";
            }
        };
        assertNull(provider.open());
        assertNull(provider.query("SELECT 1", Collections.emptyList(), 201, 10000L));
    }

    @Test
    void agentContextExposesTheHostContext() throws Exception {
        assertEquals(Object.class, AgentContext.class.getMethod("hostContext").getReturnType());
    }

    @Test
    void threadPolicyNamesAreStable() {
        // phase8-contracts.md 2.3, D524: appended, never reordered.
        assertEquals(Arrays.asList("CALLER", "EDT_SYNC", "EDT_ASYNC", "WORKER", "APP", "APP_QUIESCENT", "LANE"),
                Arrays.asList(names(ThreadPolicy.values())));
    }

    private static String[] names(ThreadPolicy[] values) {
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].name();
        }
        return names;
    }
}
