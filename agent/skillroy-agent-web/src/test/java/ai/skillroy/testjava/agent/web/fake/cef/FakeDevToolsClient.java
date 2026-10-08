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

package ai.skillroy.testjava.agent.web.fake.cef;

import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;
import ai.skillroy.testjava.protocol.Json;

import java.awt.EventQueue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The neutral fake of JCEF's DevTools client: {@code executeDevToolsMethod(String, String)} answers on the EDT (as
 * JCEF does off-screen, BS8), {@code addEventListener}/{@code removeEventListener}, {@code isClosed()}, and a
 * {@code close()} that records a violation when an adapter calls it (the client is the application's). A client may
 * be born closed for its first call (BS8: the first client of a browser created with {@code createImmediately()}
 * reported "Client is closed"). Scripts added for new documents and the device-metrics override belong to the
 * client's session, as in Chromium. The listeners are a set, as JCEF 152 keeps them: adding the same listener twice
 * registers it once.
 */
public final class FakeDevToolsClient {

    static {
        ClassInitialisations.record(FakeDevToolsClient.class);
    }

    /** The listener interface, nested as JCEF nests it. */
    public interface EventListener {
        void onEvent(String eventName, String messageAsJson);
    }

    private static final java.util.concurrent.atomic.AtomicInteger EXECUTED =
            new java.util.concurrent.atomic.AtomicInteger();

    private final FakeCefBrowserBase browser;
    private final Set<EventListener> listeners = new CopyOnWriteArraySet<EventListener>();
    final Map<String, String> scripts = new LinkedHashMap<String, String>();
    volatile int[] override;
    private volatile boolean closed;
    private volatile boolean failFirstCall;
    private int nextScript;

    FakeDevToolsClient(FakeCefBrowserBase browser, boolean failFirstCall) {
        this.browser = browser;
        this.failFirstCall = failFirstCall;
    }

    public CompletableFuture<String> executeDevToolsMethod(final String method, final String parametersAsJson) {
        EXECUTED.incrementAndGet();
        final CompletableFuture<String> out = new CompletableFuture<String>();
        if (closed || failFirstCall) {
            failFirstCall = false;
            closed = true;
            fail(out, "Client is closed");
            return out;
        }
        browser.app.renderer.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    browser.execute(FakeDevToolsClient.this, method, Json.parseObject(parametersAsJson), out);
                } catch (RuntimeException e) {
                    fail(out, String.valueOf(e.getMessage()));
                }
            }
        });
        return out;
    }

    /**
     * The next call is refused before it is sent ("Client is closed") and the client is closed from then on: a client
     * that closes between the adapter's look at it and its call.
     */
    public void refuseNextCall() {
        failFirstCall = true;
    }

    public void addEventListener(EventListener listener) {
        listeners.add(listener);
    }

    public void removeEventListener(EventListener listener) {
        listeners.remove(listener);
    }

    public boolean isClosed() {
        return closed;
    }

    /** The technology's close: an adapter must never call it (the client is the application's), so it is recorded. */
    public void close() {
        FakeCefBrowserBase.violation("CefDevToolsClient.close() was called");
        closeInternal();
    }

    /** The application closes its client (BS8 F9: the agent's listeners go with it). */
    public void closeByApplication() {
        closeInternal();
    }

    void closeInternal() {
        closed = true;
        listeners.clear();
    }

    /** How many calls were sent through any fake client (for tests: the raw channel sends none). */
    public static int executed() {
        return EXECUTED.get();
    }

    /** How many listeners are registered (for tests). */
    public int listenerCount() {
        return listeners.size();
    }

    synchronized String addScript(String source) {
        String id = String.valueOf(++nextScript);
        scripts.put(id, source);
        return id;
    }

    synchronized void removeScript(String id) {
        scripts.remove(id);
    }

    synchronized List<String> scriptSources() {
        return new java.util.ArrayList<String>(scripts.values());
    }

    /** Delivers an event to the listeners, on the EDT. */
    void fire(final String name, final Map<String, Object> params) {
        if (closed) {
            return;
        }
        final String json = Json.write(params);
        for (final EventListener l : listeners) {
            EventQueue.invokeLater(new Runnable() {
                @Override
                public void run() {
                    if (listeners.contains(l)) {
                        l.onEvent(name, json);
                    }
                }
            });
        }
    }

    static void complete(final CompletableFuture<String> out, final Map<String, Object> result) {
        final String json = Json.write(result);
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                out.complete(json);
            }
        });
    }

    static void fail(final CompletableFuture<String> out, final String message) {
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                out.completeExceptionally(new FakeDevToolsException(message));
            }
        });
    }
}
