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

package ai.skillroy.testjava.agent.web;

import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebEvent;
import ai.skillroy.testjava.agent.api.spi.web.WebEventListener;
import ai.skillroy.testjava.agent.web.fake.FakeBrowser;
import ai.skillroy.testjava.agent.web.fake.FakeConsoleMessageReceived;
import ai.skillroy.testjava.agent.web.fake.FakeEngine;
import ai.skillroy.testjava.agent.web.fake.FakeJxFixture;
import ai.skillroy.testjava.agent.web.fake.FakeRenderingMode;
import ai.skillroy.testjava.agent.web.jxbrowser.JxBrowserAdapter;
import ai.skillroy.testjava.agent.web.reflect.EventBudget;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security fix P2-L4-S1 in the {@code jxbrowser} adapter, against the neutral fake: a page that floods its console
 * stops the browser's console capture once its output exceeds the receive budget ({@link EventBudget}): the
 * adapter's console subscription is cancelled (so it reads no console message of that browser any more), its
 * subscribers get the warning once and no console event after it; and capture resumes, subscribed again, at the
 * first main-frame navigation {@link EventBudget#RESUME_AFTER_MS} ms or more after it stopped.
 */
class JxBrowserConsoleBudgetFakeTest {

    private static final long BOUND_MS = 10000;
    private static final int CHUNK = 1 << 20;
    private static final String PAGE = "<!doctype html><html><head><title>Loan desk</title></head>"
            + "<body><p id=\"status\">ready</p></body></html>";

    @Test
    void aConsoleFloodStopsCaptureOnceAndANavigationLaterResumesIt() throws Exception {
        FakeEngine engine = FakeEngine.newInstance(null, Paths.get("console"), FakeRenderingMode.OFF_SCREEN);
        try {
            FakeBrowser b = engine.newBrowser();
            b.loadAndWait(PAGE);
            JxBrowserAdapter adapter = new JxBrowserAdapter(FakeJxFixture.DESCRIPTOR, null,
                    JxBrowserAdapter.NO_OPTIONS);
            try {
                adapter.probe(JxBrowserConsoleBudgetFakeTest.class.getClassLoader());
                WebBrowser h = adapter.browsers(Collections.singletonList(b)).get(0);
                final List<WebEvent> console = new CopyOnWriteArrayList<WebEvent>();
                h.subscribe(new WebEventListener() {
                    @Override
                    public void onEvent(WebBrowser browser, WebEvent event) {
                        if (event.kind() == WebEvent.Kind.CONSOLE) {
                            console.add(event);
                        }
                    }
                });
                assertEquals(1, b.observerCount(FakeConsoleMessageReceived.class), "the adapter reads the console");
                char[] chars = new char[CHUNK];
                Arrays.fill(chars, 'x');
                String chunk = new String(chars);
                for (int i = 0; i < 30; i++) {
                    b.pageConsole(chunk);
                }
                assertNotNull(await(console, EventBudget.STOPPED_TEXT), "the subscribers are told capture stopped");
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS);
                while (b.observerCount(FakeConsoleMessageReceived.class) > 0 && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(0, b.observerCount(FakeConsoleMessageReceived.class), "the console subscription is gone");
                int delivered = 0;
                int warnings = 0;
                for (WebEvent e : console) {
                    if (EventBudget.STOPPED_TEXT.equals(e.text())) {
                        warnings++;
                    } else {
                        delivered++;
                    }
                }
                assertEquals(1, warnings, "once");
                assertTrue(delivered <= EventBudget.CONSOLE_BURST_CHARS / CHUNK + 1, "within the burst: " + delivered);
                int seen = console.size();
                b.pageConsole("after the stop");
                Thread.sleep(300);
                assertEquals(seen, console.size(), "nothing after the warning");

                Thread.sleep(EventBudget.RESUME_AFTER_MS);
                b.loadAndWait(PAGE);
                deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS);
                while (b.observerCount(FakeConsoleMessageReceived.class) == 0 && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(1, b.observerCount(FakeConsoleMessageReceived.class), "subscribed again");
                b.pageConsole("back");
                assertNotNull(await(console, "back"), "console capture resumed");
            } finally {
                adapter.detach();
            }
            assertEquals(Collections.emptyList(), engine.violations());
        } finally {
            engine.shutdown();
        }
    }

    private static WebEvent await(List<WebEvent> events, String text) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS);
        while (System.nanoTime() < deadline) {
            for (WebEvent e : events) {
                if (text.equals(e.text())) {
                    return e;
                }
            }
            Thread.sleep(10);
        }
        return null;
    }
}
