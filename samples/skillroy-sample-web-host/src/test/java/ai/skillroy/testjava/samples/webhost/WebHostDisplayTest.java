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


package ai.skillroy.testjava.samples.webhost;

import ai.skillroy.testjava.samples.webhost.WebHostOptions.Rendering;
import org.cef.CefApp;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The real host in this test JVM, off screen: CEF starts (the first-run files make that possible), the three
 * browsers load their pages, the registry is filled, the window behaves, and the host shuts down so that the JVM can
 * end. Needs a display, so it is skipped headless and runs under {@code ci/run-in-linux.sh ... -Pjcef
 * -Dskillroy.headless=false} (with {@code -Dskillroy.jcef.platform=linux-arm64} on an arm64 image). It runs in a JVM
 * of its own (the module's {@code reuseForks=false}), because CEF starts once per JVM.
 */
class WebHostDisplayTest {

    private static final long WAIT_S = 60;

    @BeforeAll
    static void requireDisplay() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
    }

    @Test
    void theOffScreenHostShowsThreeBrowsersAndShutsDown() throws Exception {
        final WebHostApp app = WebHostApp.launch(WebHostOptions.defaults().rendering(Rendering.OSR));
        try {
            assertSame(app, WebHostApp.running());
            assertSame(app, WebHostApp.launch(WebHostOptions.defaults().rendering(Rendering.OSR)), "one host per JVM");
            assertThrows(IllegalStateException.class, () -> WebHostApp.launch(WebHostOptions.defaults()),
                    "a windowed host while an off-screen one runs");
            assertEquals(CefApp.CefAppState.INITIALIZED, CefApp.getState());
            for (String name : FirstRunSentinels.NAMES) {
                assertTrue(Files.isRegularFile(app.cacheDir().resolve(name)), name);
            }

            assertTrue(WebHostBrowsers.await(0, TimeUnit.SECONDS), "registered once launch returned");
            assertSame(app.front(), WebHostBrowsers.front());
            assertSame(app.back(), WebHostBrowsers.back());
            assertSame(app.detached(), WebHostBrowsers.detached());
            assertSame(app.cefApp(), WebHostBrowsers.cefApp());
            assertSame(app.client(), WebHostBrowsers.client());
            for (String page : WebHostPages.NAMES) {
                assertTrue(app.awaitLoaded(page, WAIT_S, TimeUnit.SECONDS), page + " loaded");
            }
            String frontUrl = WebHostBrowsers.front().getURL();
            assertTrue(frontUrl.startsWith("data:text/html"), abbreviate(frontUrl));
            assertTrue(frontUrl.contains(WebHostPages.marker("front")), abbreviate(frontUrl));
            assertTrue(WebHostBrowsers.back().getURL().contains(WebHostPages.marker("back")));
            assertTrue(WebHostBrowsers.detached().getURL().contains(WebHostPages.marker("detached")));

            assertEquals("Web host", onEdt(() -> app.frame().getTitle()));
            assertTrue(onEdt(() -> app.frame().isShowing()));
            assertTrue(onEdt(() -> app.front().getUIComponent().isShowing()), "front card shown");
            assertTrue(onEdt(() -> app.back().getUIComponent().isDisplayable()), "back card displayable");
            assertFalse(onEdt(() -> app.back().getUIComponent().isShowing()), "back card hidden");
            assertFalse(onEdt(() -> app.detached().getUIComponent().isDisplayable()), "detached: in no window");
            app.swap();
            assertTrue(onEdt(() -> app.back().getUIComponent().isShowing()), "swapped: back card shown");
            assertFalse(onEdt(() -> app.front().getUIComponent().isShowing()), "swapped: front card hidden");
            app.swap();
            assertTrue(onEdt(() -> app.front().getUIComponent().isShowing()), "swapped back");
        } finally {
            app.shutdown();
        }
        assertTrue(app.isShutDown());
        assertNull(WebHostBrowsers.front(), "the registry is cleared");
        assertNull(WebHostApp.running());
        assertThrows(IllegalStateException.class,
                () -> WebHostApp.launch(WebHostOptions.defaults().rendering(Rendering.OSR)),
                "CEF cannot be started twice in one JVM");
    }

    /** Runs a read on the event dispatch thread (after everything posted before it). */
    private static <T> T onEdt(final Callable<T> read) throws Exception {
        final AtomicReference<T> result = new AtomicReference<T>();
        final AtomicReference<Exception> failure = new AtomicReference<Exception>();
        EventQueue.invokeAndWait(() -> {
            try {
                result.set(read.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private static String abbreviate(String url) {
        return url == null || url.length() <= 80 ? String.valueOf(url) : url.substring(0, 80) + "...";
    }
}
