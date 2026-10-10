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

import java.awt.Component;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;

/**
 * One live browser, as a {@link WebEngineAdapter} found it. Methods complete futures; the caller (a primitive on an
 * agent worker) applies the bounded wait. An adapter never waits on the EDT and never makes a blocking vendor call on
 * the EDT.
 *
 * <p><b>Identity.</b> Two handles for the same live browser are {@code equal()} and have the same hash code:
 * implementations base both on the identity of {@link #vendorObject()}. A later {@link WebEngineAdapter#browsers}
 * call may return an equal handle with other capabilities (JCEF: once the application has created the native
 * browser behind it).
 *
 * <p><b>Threads.</b> Every method is called on agent workers. The accessors answer from what the adapter knows; where
 * one has to ask the technology, the adapter bounds that wait itself. What the technology answers asynchronously
 * arrives through a future. Such a future may complete, and a listener may be called, on one of the technology's
 * threads (CEF's UI thread is the EDT with off-screen rendering): a caller never runs blocking or lengthy work in a
 * dependent stage of such a future, except through an executor of its own.
 *
 * <p><b>Failures.</b> Nothing here blocks to report a failure. Without the {@link WebCapability} a method names, its
 * future completes exceptionally with {@link UnsupportedOperationException} ({@link #addInitScript} throws it),
 * except that {@link #frames()} answers an empty list, {@link #cdpTargetId()} completes with null and
 * {@link #clearResize()} completes normally. Once the browser has closed, every future completes exceptionally,
 * within the caller's bound, with an {@link IllegalStateException} that says so ({@link #addInitScript} throws it,
 * {@link #frames()} answers an empty list). An exception that completes a future or leaves a method is a JDK type or
 * one of this package's, never a vendor's: the adapter translates the technology's own.
 */
public interface WebBrowser {

    /** The {@link WebEngineAdapter#id()} of the adapter that made this handle. */
    String adapterId();

    /** Identity for the weak handle registry (the vendor's browser object); never serialised. */
    Object vendorObject();

    /**
     * The technology's own browser id when it has one (JCEF {@code getIdentifier()}, as text), else null.
     * Informational.
     */
    String nativeId();

    /**
     * What this handle can do, decided when the handle is created from the probe and the browser's state (a browser
     * the technology has not created yet has none). Never null; callers do not modify it.
     */
    Set<WebCapability> capabilities();

    /** True once the browser has closed; from then on every operation fails as the class comment says. */
    boolean isClosed();

    /** The main frame's URL; {@code ""} when there is no document yet. Never null. */
    String url();

    /**
     * The Swing component that shows this browser, or null (a browser without a view). Its state
     * ({@code isShowing()}, bounds) is read on the EDT.
     */
    Component component();

    /** How the browser renders; {@link WebRendering#UNKNOWN} when the adapter cannot tell. Never null. */
    WebRendering rendering();

    /**
     * The layout viewport in CSS pixels as the adapter knows it, or null if unknown. A new object on every call, which
     * the caller may keep.
     */
    Dimension viewport();

    /**
     * Snapshot of the frame tree, main frame first; empty when there is no document. Needs
     * {@link WebCapability#FRAMES} (empty without it).
     */
    List<WebFrame> frames();

    /**
     * Evaluates an expression and completes with its value as JSON text ({@code "null"} for undefined and null). A
     * script error completes exceptionally with {@link WebScriptException} (message, stack text); a frame key that no
     * longer exists with {@link WebFrameGoneException}. Needs {@link WebCapability#EVALUATE};
     * {@link WebEval#awaitPromise() awaitPromise} needs {@link WebCapability#AWAIT_PROMISE}. When
     * {@link WebEval#timeoutMs()} passes first, the adapter abandons the call and completes the future exceptionally
     * with a {@link java.util.concurrent.TimeoutException}.
     */
    CompletableFuture<String> evaluate(WebEval request);

    /**
     * Delivers load, navigation, console and closure events until cancelled. Needs {@link WebCapability#EVENTS_LOAD}
     * / {@link WebCapability#EVENTS_CONSOLE} for the respective kinds; {@link WebEvent.Kind#CLOSED} is always
     * delivered when the adapter can observe it. Always succeeds; the adapter catches whatever the listener throws.
     */
    WebSubscription subscribe(WebEventListener listener);

    /**
     * Registers a script that runs in every new document of this browser, before the page's scripts where the
     * technology allows it ({@link WebCapability#INIT_SCRIPT_DOCUMENT_START}), else as early as it can (after the
     * document loaded). The returned subscription's {@link WebInitScript#timing()} says which. Cancelling removes it
     * for future documents. Needs {@link WebCapability#EVALUATE}.
     */
    WebInitScript addInitScript(String source);

    /**
     * Changes the layout viewport without touching Swing where the technology allows it
     * ({@link WebCapability#RESIZE}).
     *
     * @param width CSS pixels, positive
     * @param height CSS pixels, positive
     */
    CompletableFuture<Void> resize(int width, int height);

    /**
     * Clears a viewport override made by {@link #resize}; completes at once when there is none (so also without
     * {@link WebCapability#RESIZE}).
     */
    CompletableFuture<Void> clearResize();

    /**
     * A bitmap of the page as rendered ({@link WebCapability#BITMAP}). A technology that does not render a browser
     * whose component is not showing (JCEF 152 renders neither a hidden card nor a view-less browser) completes
     * exceptionally with {@link UnsupportedOperationException}; the module reports that as the capability being
     * unavailable for that browser. The rendered page of a showing browser is captured whatever its window state.
     */
    CompletableFuture<BufferedImage> bitmap();

    /**
     * The CDP target id of this browser, or null when unknown ({@link WebCapability#CDP_TARGET_ID}; null without
     * it).
     */
    CompletableFuture<String> cdpTargetId();

    /**
     * Reloads the main frame's document and completes once the reload was requested: the new document's load arrives
     * as a {@link WebEvent.Kind#LOAD_FINISHED} through {@link #subscribe} (with {@link WebCapability#EVENTS_LOAD}),
     * not through this future. Names no {@link WebCapability} flag: every browser that can evaluate can reload. Fails
     * as the class comment says (a closed browser: {@link IllegalStateException}). Amendment A37 (Phase 4 contract
     * §3): a default method, so an adapter built before it links and runs unchanged.
     *
     * <p><b>The default</b> evaluates {@code location.reload()} in the main frame through {@link #evaluate}, with
     * {@link WebEval}'s default timeout ({@code location.reload(true)} when {@code ignoreCache} is set: a non-standard
     * argument that Chromium ignores, so {@code ignoreCache} is honoured only by an adapter that overrides this
     * method). It completes when that evaluation answers and fails with the evaluation's own failure (without
     * {@link WebCapability#EVALUATE}: {@link UnsupportedOperationException}); an {@code evaluate} that throws fails
     * the future instead; cancelling the returned future cancels the evaluation. A reload by the page's own script is
     * a navigation the page starts, which a technology may refuse (Chromium refuses it for a {@code data:} document)
     * and which a page that runs no script (a frozen one) never makes: an adapter whose technology can reload from
     * outside the page overrides this method, as both built-in adapters do ({@code CefBrowser.reload()} and
     * {@code reloadIgnoreCache()}; {@code Navigation.reload()} and {@code reloadIgnoringCache()}).
     *
     * @param ignoreCache bypass the cache, where the adapter's reload can
     * @return completes once the reload was requested
     */
    default CompletableFuture<Void> reload(boolean ignoreCache) {
        final CompletableFuture<String> evaluation;
        try {
            evaluation = evaluate(WebEval.of(ignoreCache ? "location.reload(true)" : "location.reload()").build());
        } catch (RuntimeException e) {
            CompletableFuture<Void> failed = new CompletableFuture<Void>();
            failed.completeExceptionally(e);
            return failed;
        }
        if (evaluation == null) {
            CompletableFuture<Void> failed = new CompletableFuture<Void>();
            failed.completeExceptionally(new IllegalStateException("evaluate() returned no future"));
            return failed;
        }
        final CompletableFuture<Void> requested = new CompletableFuture<Void>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                evaluation.cancel(mayInterruptIfRunning);
                return cancelled;
            }
        };
        evaluation.whenComplete(new BiConsumer<String, Throwable>() {
            @Override
            public void accept(String value, Throwable failure) {
                if (failure == null) {
                    requested.complete(null);
                } else {
                    requested.completeExceptionally(failure instanceof CompletionException
                            && failure.getCause() != null ? failure.getCause() : failure);
                }
            }
        });
        return requested;
    }

    /**
     * Where {@link #bitmap()} comes from, which {@code web.bitmap} reports as its {@code source}: informational,
     * answered from what the adapter knows (never by asking the technology), never null. The default is
     * {@link BitmapSource#VENDOR}; an adapter whose bitmap is a DevTools-protocol screenshot answers
     * {@link BitmapSource#PROTOCOL} (the built-in jcef adapter). Amendment A37 (Phase 4 contract §3).
     */
    default BitmapSource bitmapSource() {
        return BitmapSource.VENDOR;
    }

    /**
     * Dispatches one synthetic pointer event into the page ({@link WebCapability#INPUT}) and completes once the
     * technology has handled it (on the protocol path: once {@code Input.dispatchMouseEvent} was answered). The event's
     * point is in CSS pixels of the main frame's layout viewport. Fails as the class comment says: without
     * {@link WebCapability#INPUT} the future completes exceptionally with {@link UnsupportedOperationException}; a
     * closed browser with {@link IllegalStateException}; a null event with {@link IllegalArgumentException}. The
     * caller bounds the wait; an adapter never waits on the EDT, never retries a dispatch, and never sends one again on
     * a shared channel (a pointer event is mutating). Amendment A66 (phase7-contracts.md section 4.1): a default
     * method, so an adapter built before it links and runs unchanged.
     *
     * <p><b>The default</b> has no {@link WebCapability#INPUT}: it completes exceptionally with
     * {@link UnsupportedOperationException} and touches nothing.
     *
     * @param event the event to dispatch
     * @return completes once the technology has handled the event
     */
    default CompletableFuture<Void> dispatchPointer(WebPointerEvent event) {
        CompletableFuture<Void> refused = new CompletableFuture<Void>();
        refused.completeExceptionally(new UnsupportedOperationException("the adapter has no INPUT: synthetic pointer"
                + " input into a page is not supported"));
        return refused;
    }
}
