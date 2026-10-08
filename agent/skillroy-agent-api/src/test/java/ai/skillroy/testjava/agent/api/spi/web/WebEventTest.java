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

package ai.skillroy.testjava.agent.api.spi.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link WebEvent}: one factory per kind, the fields that do not apply are null or 0, value semantics (Phase 4
 * contract §3, §12.1 item 1).
 */
class WebEventTest {

    @Test
    void loadFinishedCarriesTheFrameAndTheUrl() {
        WebEvent event = WebEvent.loadFinished("F-2", "data:,loans");
        assertEquals(WebEvent.Kind.LOAD_FINISHED, event.kind());
        assertEquals("F-2", event.frameKey());
        assertEquals("data:,loans", event.url());
        assertNull(event.error());
        assertNoConsoleFields(event);
    }

    @Test
    void loadFailedAddsTheError() {
        WebEvent event = WebEvent.loadFailed("F-2", "data:,loans", "net::ERR_ABORTED");
        assertEquals(WebEvent.Kind.LOAD_FAILED, event.kind());
        assertEquals("F-2", event.frameKey());
        assertEquals("data:,loans", event.url());
        assertEquals("net::ERR_ABORTED", event.error());
        assertNoConsoleFields(event);
    }

    @Test
    void navigatedCarriesTheFrameAndTheNewUrl() {
        WebEvent event = WebEvent.navigated("F-2", "data:,items");
        assertEquals(WebEvent.Kind.NAVIGATED, event.kind());
        assertEquals("F-2", event.frameKey());
        assertEquals("data:,items", event.url());
        assertNull(event.error());
        assertNoConsoleFields(event);
    }

    @Test
    void aMainFrameEventHasNoFrameKey() {
        assertNull(WebEvent.loadFinished(null, "data:,desk").frameKey());
        assertNull(WebEvent.loadFailed(null, "data:,desk", "net::ERR_FAILED").frameKey());
        assertNull(WebEvent.navigated(null, "data:,desk").frameKey());
    }

    @Test
    void consoleCarriesLevelTextSourceAndLine() {
        WebEvent event = WebEvent.console("warning", "deposit missing", "data:,desk", 12);
        assertEquals(WebEvent.Kind.CONSOLE, event.kind());
        assertEquals("warning", event.level());
        assertEquals("deposit missing", event.text());
        assertEquals("data:,desk", event.source());
        assertEquals(12, event.line());
        assertNull(event.frameKey());
        assertNull(event.url());
        assertNull(event.error());

        WebEvent unplaced = WebEvent.console("log", "ready", null, 0);
        assertNull(unplaced.source());
        assertEquals(0, unplaced.line());
    }

    @Test
    void closedCarriesNoField() {
        WebEvent event = WebEvent.closed();
        assertEquals(WebEvent.Kind.CLOSED, event.kind());
        assertNull(event.frameKey());
        assertNull(event.url());
        assertNull(event.error());
        assertNoConsoleFields(event);
        assertSame(event, WebEvent.closed());
    }

    @Test
    void eventsAreEqualWhenTheKindAndEveryFieldAreEqual() {
        WebEvent load = WebEvent.loadFinished("F-1", "data:,a");
        assertEquals(load, WebEvent.loadFinished("F-1", "data:,a"));
        assertEquals(load.hashCode(), WebEvent.loadFinished("F-1", "data:,a").hashCode());
        assertNotEquals(load, WebEvent.navigated("F-1", "data:,a"), "the same fields under another kind");
        assertNotEquals(load, WebEvent.loadFinished("F-2", "data:,a"));
        assertNotEquals(load, WebEvent.loadFinished(null, "data:,a"));
        assertNotEquals(load, WebEvent.loadFinished("F-1", "data:,b"));
        assertNotEquals(WebEvent.loadFailed("F-1", "data:,a", "net::ERR_ABORTED"),
                WebEvent.loadFailed("F-1", "data:,a", "net::ERR_FAILED"));

        WebEvent console = WebEvent.console("log", "ready", "data:,a", 1);
        assertEquals(console, WebEvent.console("log", "ready", "data:,a", 1));
        assertEquals(console.hashCode(), WebEvent.console("log", "ready", "data:,a", 1).hashCode());
        assertNotEquals(console, WebEvent.console("info", "ready", "data:,a", 1));
        assertNotEquals(console, WebEvent.console("log", "steady", "data:,a", 1));
        assertNotEquals(console, WebEvent.console("log", "ready", "data:,b", 1));
        assertNotEquals(console, WebEvent.console("log", "ready", "data:,a", 2));
        assertNotEquals(console, WebEvent.closed());
        assertFalse(console.equals(null));
        assertFalse(console.equals("ready"));
    }

    @Test
    void toStringShowsTheLengthOfAConsoleMessageNeverItsText() {
        String message = "pin=4711";
        assertEquals("WebEvent{kind=CONSOLE, frameKey=null, url=null, level=log, text=<" + message.length()
                + " chars>, source=data:,desk, line=3, error=null}",
                WebEvent.console("log", message, "data:,desk", 3).toString());
        assertEquals("WebEvent{kind=LOAD_FAILED, frameKey=F-1, url=data:,desk, level=null, text=null, source=null,"
                + " line=0, error=net::ERR_ABORTED}",
                WebEvent.loadFailed("F-1", "data:,desk", "net::ERR_ABORTED").toString());
        assertEquals("WebEvent{kind=CLOSED, frameKey=null, url=null, level=null, text=null, source=null, line=0,"
                + " error=null}", WebEvent.closed().toString());
    }

    private static void assertNoConsoleFields(WebEvent event) {
        assertNull(event.level());
        assertNull(event.text());
        assertNull(event.source());
        assertEquals(0, event.line());
    }
}
