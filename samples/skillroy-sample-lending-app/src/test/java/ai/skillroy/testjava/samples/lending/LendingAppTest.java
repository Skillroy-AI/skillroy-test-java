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

package ai.skillroy.testjava.samples.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/** Wiring, options and plugins, without a window. */
class LendingAppTest {

    @Test
    void optionsFromProperties() {
        Properties p = new Properties();
        LendingOptions d = LendingOptions.fromProperties(p);
        assertEquals(Seed.A, d.seed());
        assertEquals(0, d.idleLockMillis());
        assertEquals("Tool Library Lending Desk", d.title());
        assertEquals("lending", d.dbName());
        assertTrue(d.plugins().isEmpty());

        p.setProperty("lending.seed", "B");
        p.setProperty("lending.plugins", " a.B , c.D,,");
        p.setProperty("lending.idleLockSeconds", "30");
        p.setProperty("lending.title", "Desk 2");
        LendingOptions o = LendingOptions.fromProperties(p);
        assertEquals(Seed.B, o.seed());
        assertEquals(Arrays.asList("a.B", "c.D"), o.plugins());
        assertEquals(30_000, o.idleLockMillis());
        assertEquals("Desk 2", o.title());

        p.setProperty("lending.idleLockSeconds", "soon");
        assertThrows(IllegalArgumentException.class, () -> LendingOptions.fromProperties(p));
        p.setProperty("lending.idleLockSeconds", "0");
        p.setProperty("lending.seed", "Z");
        assertThrows(IllegalArgumentException.class, () -> LendingOptions.fromProperties(p));
    }

    @Test
    void launchesHeadlessWithPluginsAndShutsDown() throws Exception {
        RecordingPlugin.LOG.clear();
        LendingApp app = LendingApp.launch(LendingOptions.defaults().seed(Seed.B).dbName("app_test").ui(false)
            .plugins(Arrays.asList("no.such.Plugin", RecordingPlugin.Failing.class.getName(),
                RecordingPlugin.class.getName())));
        try {
            assertSame(app, LendingApp.instance());
            assertNull(app.frame());
            assertEquals(Seed.B, app.seed());
            assertEquals(1, app.plugins().size(), "the failing and missing plugins are skipped");
            assertEquals(Arrays.asList("start B true true"), RecordingPlugin.LOG);
            assertSame(app.engine().inbox(), app.inbox());
            assertEquals("TOOL LIBRARY - CANAL STREET", app.printer().header());
            assertTrue(app.access().findStaff("4711") != null);
            assertTrue(app.catalogue().findByCode("T1001") != null);
            assertEquals(3, app.waiverRules().waiverRules().size());

            Recorder rec = new Recorder();
            long deadline = System.currentTimeMillis() + Recorder.TIMEOUT_MS;
            while (!app.engine().isAwaitingInput() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            app.engine().addListener(rec);
            assertTrue(app.inbox().offer(LendingInput.entry("4714")));
            assertEquals(LendingState.IDLE, rec.await(0, e -> e.kind == Recorder.Kind.REQUESTED).state);
            assertTrue(app.inbox().offer(LendingInput.barcode("0000")));
            rec.await(0, e -> e.kind == Recorder.Kind.PROMPT_SHOWN);
            assertTrue(app.prompts().answer("OK"), "without a window prompts are answered through the service");
            rec.await(0, e -> e.kind == Recorder.Kind.PROMPT_ANSWERED);
        } finally {
            app.shutdown();
        }
        assertNull(LendingApp.instance());
        assertEquals(Arrays.asList("start B true true", "stop"), RecordingPlugin.LOG);
        assertFalse(app.engine().isRunning());
        assertTrue(app.db().isClosed());
        app.shutdown();
        assertEquals(2, RecordingPlugin.LOG.size(), "shutdown is idempotent");
    }
}
