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

import org.cef.browser.CefBrowser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The static registry, filled with placeholder browsers (no CEF). */
class WebHostBrowsersTest {

    @AfterEach
    void clear() {
        WebHostBrowsers.clear();
    }

    @Test
    void emptyUntilAHostRegisters() throws InterruptedException {
        assertFalse(WebHostBrowsers.isRegistered());
        assertNull(WebHostBrowsers.front());
        assertNull(WebHostBrowsers.back());
        assertNull(WebHostBrowsers.detached());
        assertNull(WebHostBrowsers.cefApp());
        assertNull(WebHostBrowsers.client());
        assertFalse(WebHostBrowsers.await(20, TimeUnit.MILLISECONDS), "nothing to wait for");
    }

    @Test
    void registeredBrowsersAreHandedOutAsTheyAre() throws InterruptedException {
        CefBrowser front = Placeholders.browser("front");
        CefBrowser back = Placeholders.browser("back");
        CefBrowser detached = Placeholders.browser("detached");
        WebHostBrowsers.register(null, null, front, back, detached);
        assertTrue(WebHostBrowsers.isRegistered());
        assertSame(front, WebHostBrowsers.front());
        assertSame(back, WebHostBrowsers.back());
        assertSame(detached, WebHostBrowsers.detached());
        assertTrue(WebHostBrowsers.await(0, TimeUnit.MILLISECONDS), "registered: no wait");

        WebHostBrowsers.register(null, null, back, null, null);
        assertSame(back, WebHostBrowsers.front(), "a registration replaces the earlier one");
        assertNull(WebHostBrowsers.back());
        assertNull(WebHostBrowsers.detached());

        WebHostBrowsers.clear();
        assertNull(WebHostBrowsers.front());
        assertThrows(NullPointerException.class, () -> WebHostBrowsers.register(null, null, null, back, null));
    }

    @Test
    void aHostClearsOnlyItsOwnRegistration() {
        CefBrowser mine = Placeholders.browser("mine");
        CefBrowser other = Placeholders.browser("other");
        WebHostBrowsers.register(null, null, other, null, null);
        WebHostBrowsers.clearIfFront(mine);
        assertSame(other, WebHostBrowsers.front());
        WebHostBrowsers.clearIfFront(other);
        assertNull(WebHostBrowsers.front());
    }

    @Test
    void awaitWakesUpWhenAHostRegisters() throws InterruptedException {
        final CountDownLatch waiting = new CountDownLatch(1);
        final AtomicBoolean result = new AtomicBoolean();
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                waiting.countDown();
                try {
                    result.set(WebHostBrowsers.await(30, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "registry-waiter");
        waiter.start();
        assertTrue(waiting.await(30, TimeUnit.SECONDS));
        WebHostBrowsers.register(null, null, Placeholders.browser("front"), null, null);
        waiter.join(30000L);
        assertFalse(waiter.isAlive());
        assertTrue(result.get());
    }
}
