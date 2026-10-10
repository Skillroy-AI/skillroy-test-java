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
import ai.skillroy.testjava.agent.api.spi.web.WebEngineAdapter;
import ai.skillroy.testjava.agent.api.spi.web.WebSubscription;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One browser of the index: its ref (minted by the agent's {@code HandleRegistry} from the vendor object, kind
 * {@code browser}, so it is stable for the life of the vendor object), the adapter that found it, the latest handle
 * the adapter returned for it (equal to the earlier ones), and what the module keeps about it: the seed's tag, the
 * component (and its ref) and whether it showed at the last look, whether this module's viewport override is in place,
 * the last CDP target id read successfully, the load events
 * (for the read retry of phase4-contracts.md section 4.2 and for {@code web.waitFlag}), and its event subscription.
 *
 * <p>Thread-safe: fields are volatile or atomic; the load watch synchronises on itself.
 */
final class Entry {

    final String ref;
    final WebEngineAdapter adapter;
    final LoadWatch loads = new LoadWatch();
    /** Set once {@code web.closed} was published for this browser. */
    final AtomicBoolean closedPublished = new AtomicBoolean();
    private volatile WebBrowser browser;
    volatile String tag;
    volatile String componentRef;
    /**
     * The component the adapter reported at the last rebuild (a {@code java.awt.Component}, typed {@code Object} so
     * that the index names no AWT class), or null: what the module's {@code SurfaceSource} answers from without asking
     * the browser (phase7-contracts.md section 4.8).
     */
    volatile Object component;
    volatile boolean showing;
    /**
     * True while a viewport override this module set with {@code web.resize} is in place: a component point is then
     * not converted (phase7-contracts.md section 4.8, {@code "viewport override"}).
     */
    volatile boolean overridden;
    /**
     * The last CDP target id read successfully. A page target's id does not change while the browser lives; a later
     * read that times out (a frozen renderer cannot answer {@code Page.getFrameTree}) reports this one, which the CDP
     * path's frozen-target recovery needs (phase4-contracts.md section 5.3).
     */
    volatile String cachedTargetId;
    volatile WebSubscription subscription;

    Entry(String ref, WebEngineAdapter adapter, WebBrowser browser) {
        this.ref = ref;
        this.adapter = adapter;
        this.browser = browser;
    }

    /** The latest handle the adapter returned for this browser. */
    WebBrowser browser() {
        return browser;
    }

    void browser(WebBrowser latest) {
        this.browser = latest;
    }

    /** The vendor object the ref was minted from. */
    Object vendor() {
        return browser.vendorObject();
    }

    @Override
    public String toString() {
        return "Entry{" + ref + ", " + adapter.id() + "}";
    }

    /**
     * The browser's load events as seen by the event bridge: a future that completes at the next one, and when the
     * last one happened. Completing the future runs nothing but the waiting caller's wake-up, so it is safe on the
     * technology's thread that delivered the event.
     */
    static final class LoadWatch {
        private CompletableFuture<Void> next = new CompletableFuture<Void>();
        private long lastNanos;
        private boolean any;
        private long count;

        /** A load event arrived. */
        void loaded() {
            CompletableFuture<Void> done;
            synchronized (this) {
                done = next;
                next = new CompletableFuture<Void>();
                any = true;
                count++;
                lastNanos = System.nanoTime();
            }
            done.complete(null);
        }

        /** Completes at the next load event. */
        synchronized CompletableFuture<Void> next() {
            return next;
        }

        /** How many load events arrived so far. */
        synchronized long count() {
            return count;
        }

        /** Milliseconds since the last load event, or {@code Long.MAX_VALUE} when none arrived yet. */
        synchronized long msSinceLast() {
            return any ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastNanos) : Long.MAX_VALUE;
        }
    }
}
