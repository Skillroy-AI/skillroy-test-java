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

package ai.skillroy.testjava.cdp;

import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.scenario.web.WebPath;
import ai.skillroy.testjava.scenario.web.WebPathException;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the CDP path serves (phase4-contracts.md section 5.2) and how the executor finds it (D49). */
class CdpWebPathServesTest {

    /** Every primitive of phase4-contracts.md section 4.2, and the two of phase7-contracts.md 4.2 and 4.3. */
    private static final List<String> WEB_PRIMITIVES = List.of("web.browsers", "web.evaluate", "web.ariaSnapshot",
            "web.dom.text", "web.dom.click", "web.dom.fill", "web.dispatchEvent", "web.tap", "web.waitFlag",
            "web.resize", "web.bitmap", "web.reload", "web.cdp.status", "web.hitTest", "web.pointer");

    @Test
    void servesEveryWebPrimitiveButTheAgentsOwnFacts() {
        CdpWebPath path = new CdpWebPath();
        Set<String> agentOnly = Set.of("web.cdp.status", "web.reload", "web.browsers");
        for (String primitive : WEB_PRIMITIVES) {
            assertEquals(!agentOnly.contains(primitive), path.serves(primitive), primitive);
        }
        for (String other : Arrays.asList("swing.click", "web.navigate", "web.close", "web.protocol.send",
                "swing.pointer", "swing.hitTest", "", null)) {
            assertFalse(path.serves(other), String.valueOf(other));
        }
        assertEquals("cdp", path.id());
    }

    @Test
    void theMutatingPrimitivesAreThoseOfD51AndThePointer() {
        assertEquals(Set.of("web.evaluate", "web.dom.click", "web.dom.fill", "web.dispatchEvent", "web.tap",
                "web.resize", "web.pointer"), CdpWebPath.MUTATING);
        assertTrue(CdpWebPath.SERVED.containsAll(CdpWebPath.MUTATING));
    }

    @Test
    void serviceLoaderFindsThePathThroughTheServiceFile() throws Exception {
        List<WebPath> paths = ServiceLoader.load(WebPath.class, Thread.currentThread().getContextClassLoader())
                .stream().map(ServiceLoader.Provider::get).toList();
        assertEquals(1, paths.stream().filter(p -> p instanceof CdpWebPath).count(), paths.toString());
        WebPath found = paths.stream().filter(p -> p.id().equals("cdp")).findFirst().orElseThrow();
        assertNull(found.openInfo(), "nothing is opened or started by the service loader");
        try (InputStream in = CdpWebPath.class.getClassLoader().getResourceAsStream(
                "META-INF/services/ai.skillroy.testjava.scenario.web.WebPath")) {
            assertNotNull(in);
            assertEquals("ai.skillroy.testjava.cdp.CdpWebPath", new String(in.readAllBytes(),
                    StandardCharsets.UTF_8).strip());
        }
        WebPathException e = assertThrows(WebPathException.class, () -> found.call("web.dom.text", Map.of(),
                CallOptions.defaults()));
        assertEquals(WebPathException.Kind.UNSUPPORTED, e.kind());
        assertEquals("the CDP path is not open", e.getMessage());
        assertTrue(found.eventsSince(0, null).isEmpty());
        found.close();
        found.close();
    }

    @Test
    void theBudgetIsTheCallsOrThePrimitivesDefaultWithinTheCaps() {
        assertEquals(10_000, CdpWebPath.budgetMs("web.evaluate", CallOptions.defaults()));
        assertEquals(20_000, CdpWebPath.budgetMs("web.bitmap", CallOptions.defaults()));
        assertEquals(1_234, CdpWebPath.budgetMs("web.dom.text", CallOptions.defaults().withTimeoutMs(1_234L)));
        assertEquals(120_000, CdpWebPath.budgetMs("web.dom.text", CallOptions.defaults().withTimeoutMs(500_000L)));
        assertEquals(500_000, CdpWebPath.budgetMs("web.waitFlag", CallOptions.defaults().withTimeoutMs(500_000L)));
        assertEquals(600_000, CdpWebPath.budgetMs("web.waitFlag", CallOptions.defaults().withTimeoutMs(900_000L)));
    }
}
