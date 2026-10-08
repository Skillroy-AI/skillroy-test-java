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

package ai.skillroy.testjava.agent.web.fake;

import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;

import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The neutral fake's frame: {@code executeJavaScript(String)} (blocking; never on the EDT),
 * {@code executeJavaScript(String, Consumer)} (asynchronous; the result is delivered on the EDT, so that an adapter
 * that runs anything there is caught by the kit's watchdog), {@code isMain()}, {@code name()}, {@code parent()},
 * {@code children()}, {@code browser()}. A frame removed by a navigation is closed: calling it throws.
 */
public final class FakeFrame {

    static {
        ClassInitialisations.record(FakeFrame.class);
    }

    private final FakeBrowser browser;
    private final FakeFrame parent;
    final List<FakeFrame> children = new CopyOnWriteArrayList<FakeFrame>();
    volatile FakeDocument document;
    volatile boolean closed;

    FakeFrame(FakeBrowser browser, FakeFrame parent) {
        this.browser = browser;
        this.parent = parent;
    }

    /** Evaluates and returns the value (blocking: the vendor's synchronous variant). */
    @SuppressWarnings("unchecked")
    public <T> T executeJavaScript(final String script) {
        browser.engine().violations.blockingCall("Frame.executeJavaScript(String)");
        requireOpen();
        return (T) browser.engine().onRenderer(new Callable<Object>() {
            @Override
            public Object call() {
                return evaluate(script);
            }
        });
    }

    /** Evaluates on the fake renderer and hands the value to the callback on the EDT. */
    public void executeJavaScript(final String script, final Consumer<Object> callback) {
        requireOpen();
        browser.asyncEvaluations.incrementAndGet();
        browser.engine().renderer().execute(new Runnable() {
            @Override
            public void run() {
                final Object value = evaluate(script);
                EventQueue.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        callback.accept(value);
                    }
                });
            }
        });
    }

    /** The value as the vendor would hand it over; a thrown error gives null (the asynchronous variant's way). */
    private Object evaluate(String script) {
        FakeDocument doc = document;
        if (doc == null || closed) {
            return null;
        }
        try {
            Object v = FakeScript.evaluate(doc, script);
            return v == FakeScript.UNDEFINED ? null : v;
        } catch (FakeScript.Thrown t) {
            return null;
        }
    }

    private void requireOpen() {
        if (closed || browser.isClosedNow()) {
            throw new IllegalStateException("the frame is closed");
        }
    }

    public boolean isMain() {
        return parent == null;
    }

    public String name() {
        FakeDocument doc = document;
        return doc == null ? "" : doc.name();
    }

    public Optional<FakeFrame> parent() {
        return Optional.ofNullable(parent);
    }

    public List<FakeFrame> children() {
        return new ArrayList<FakeFrame>(children);
    }

    public FakeBrowser browser() {
        return browser;
    }

    /** The document the frame shows (for tests). */
    public FakeDocument document() {
        return document;
    }
}
