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

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The web host's browsers, for code in the same JVM that is not the host: the sample's agent module hands them to the
 * agent as seeds ({@code BrowserRegistryAdapter}), and tests reach them here. Plays the role the lending desk's
 * {@code LendingApp.instance()} plays for that sample.
 *
 * <p>{@link WebHostApp} fills the registry once the host is ready (its CEF application built, the window shown and
 * the pages loaded or their load bound passed) and clears it when it shuts down. Before that every accessor answers
 * null. Loading this class touches nothing of CEF or AWT: the fields' types are loaded only with a registration.
 *
 * <p>Thread-safe: the five values change together, atomically.
 */
public final class WebHostBrowsers {

    private static final Object LOCK = new Object();
    private static volatile Registration current;

    private WebHostBrowsers() {
    }

    /** @return the browser in the front card (visible), or null while nothing is registered */
    public static CefBrowser front() {
        Registration r = current;
        return r == null ? null : r.front;
    }

    /** @return the browser in the back card (hidden until swapped), or null */
    public static CefBrowser back() {
        Registration r = current;
        return r == null ? null : r.back;
    }

    /** @return the browser without a view (off-screen rendering only), or null */
    public static CefBrowser detached() {
        Registration r = current;
        return r == null ? null : r.detached;
    }

    /** @return the host's CEF application, or null */
    public static CefApp cefApp() {
        Registration r = current;
        return r == null ? null : r.cefApp;
    }

    /** @return the host's client, which created the three browsers, or null */
    public static CefClient client() {
        Registration r = current;
        return r == null ? null : r.client;
    }

    /** @return whether a host has registered its browsers (that is, {@link #front()} is not null) */
    public static boolean isRegistered() {
        return current != null;
    }

    /**
     * Registers a host's browsers, replacing any earlier registration, and wakes {@link #await} callers.
     *
     * @param cefApp the CEF application, or null (tests)
     * @param client the client that created the browsers, or null (tests)
     * @param front the front browser; required
     * @param back the back browser, or null
     * @param detached the browser without a view, or null
     */
    public static void register(CefApp cefApp, CefClient client, CefBrowser front, CefBrowser back,
                                CefBrowser detached) {
        Registration r = new Registration(cefApp, client, Objects.requireNonNull(front, "front"), back, detached);
        synchronized (LOCK) {
            current = r;
            LOCK.notifyAll();
        }
    }

    /** Forgets the registration; every accessor answers null again. */
    public static void clear() {
        synchronized (LOCK) {
            current = null;
        }
    }

    /** Clears the registration only when it holds the given front browser (a host clears only its own). */
    static void clearIfFront(CefBrowser front) {
        synchronized (LOCK) {
            Registration r = current;
            if (r != null && r.front == front) {
                current = null;
            }
        }
    }

    /**
     * Waits until a host has registered its browsers.
     *
     * @return true when registered (at once if already), false when the time ran out first
     */
    public static boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (LOCK) {
            while (current == null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(LOCK, remaining);
            }
            return true;
        }
    }

    /** One registration: immutable. */
    private static final class Registration {
        final CefApp cefApp;
        final CefClient client;
        final CefBrowser front;
        final CefBrowser back;
        final CefBrowser detached;

        Registration(CefApp cefApp, CefClient client, CefBrowser front, CefBrowser back, CefBrowser detached) {
            this.cefApp = cefApp;
            this.client = client;
            this.front = front;
            this.back = back;
            this.detached = detached;
        }
    }
}
