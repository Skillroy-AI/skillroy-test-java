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

package ai.skillroy.testjava.agent.web.reflect;

import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebEvent;
import ai.skillroy.testjava.agent.api.spi.web.WebEventListener;
import ai.skillroy.testjava.agent.api.spi.web.WebSubscription;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The subscribers of one browser handle: {@link #add} returns the {@link WebSubscription} the SPI's
 * {@code subscribe} returns ({@code cancel()} idempotent, never throws, and once it returned no new delivery starts),
 * {@link #deliver} calls every live listener and catches what it throws, {@link #clear} drops them all (an adapter's
 * {@code detach()}).
 *
 * <p>Thread-safe; delivery runs on the calling thread (the adapter's own event thread).
 */
public final class Listeners {

    private final CopyOnWriteArrayList<Sub> subs = new CopyOnWriteArrayList<Sub>();

    /** Adds a listener; the subscription removes it. */
    public WebSubscription add(WebEventListener listener) {
        final Sub s = new Sub(listener);
        subs.add(s);
        return new WebSubscription() {
            @Override
            public void cancel() {
                s.live.set(false);
                subs.remove(s);
            }
        };
    }

    /** True while at least one listener is subscribed. */
    public boolean any() {
        return !subs.isEmpty();
    }

    /** Delivers the event to every live listener; what a listener throws is caught. */
    public void deliver(WebBrowser browser, WebEvent event) {
        for (Sub s : subs) {
            if (!s.live.get()) {
                continue;
            }
            try {
                s.listener.onEvent(browser, event);
            } catch (Throwable t) {
                // a listener never breaks the adapter's event thread
            }
        }
    }

    /** Drops every listener. */
    public void clear() {
        for (Sub s : subs) {
            s.live.set(false);
        }
        subs.clear();
    }

    private static final class Sub {
        final WebEventListener listener;
        final AtomicBoolean live = new AtomicBoolean(true);

        Sub(WebEventListener listener) {
            this.listener = listener;
        }
    }
}
