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

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.agent.api.spi.web.WebFrame;
import ai.skillroy.testjava.agent.api.spi.web.WebPointerEvent;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.web.WebInput;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The bodies of {@code web.hitTest} and {@code web.pointer} (phase7-contracts.md sections 4.2, 4.3 and 4.8), on the
 * parameters, plan and shapes the CDP path shares ({@link WebInput}).
 *
 * <p><b>{@code web.hitTest}</b> (a read, capability {@code web.hittest}): the script library's {@code hit} at the point
 * in the selected frame (the main frame by default), read as a read is (retried once after a load); when the element is
 * a frame element, {@code hit} is evaluated again in the child frame that {@link WebInput#childFrame} names, at the
 * point the library translated, at most {@value WebInput#MAX_FRAME_DEPTH} times. Every rectangle of the result is in
 * the viewport of the frame the call started in (§4's one space; for a frame of the main frame that is "in the
 * parent").
 *
 * <p><b>{@code web.pointer}</b> (mutating, capability {@code web.pointer}): one bounded EDT read of the browser's
 * component (showing, size, scale, the modal dialog that blocks its window: {@code MODAL_OPEN} unless {@code force}; a
 * browser without a component is not blocked), then {@code hit} at the point (a point outside the viewport is
 * {@code NOT_FOUND} and nothing is sent; a hit that cannot be read is left out of the result), then the events of
 * {@link WebInput#plan}, each through {@link WebBrowser#dispatchPointer} and awaited before the next under the call's
 * budget; a dispatch is never retried. When an event after a press fails, one release of that button is sent (at most
 * {@value #BALANCE_MS} ms, its outcome not awaited further) so that no button is left held in the page, and the call
 * answers the failure.
 *
 * <p>A component point ({@code space: COMPONENT}) is converted with the factor {@code innerWidth / component width}
 * (1 when they are equal), {@code scaleAssumed} saying whether the screen's scale is other than 1 (CEF's device scale
 * is not consulted); refused with {@code INVALID_PARAMS} ({@code data.reason: "viewport override"}) while this
 * module's {@code web.resize} override is in place.
 *
 * <p>Thread-safe: no mutable state of its own.
 */
final class Pointing {

    /** The bound of the EDT read of the browser's component. */
    static final long STATE_MS = 2000;
    /** The hit read before a pointer action takes at most this share of the budget and this long. */
    static final long HIT_MS = 5000;
    /** The balancing release after a failed sequence waits at most this long. */
    static final long BALANCE_MS = 1000;
    /** The read of the page's inner size for a component point. */
    static final String INNER_SIZE = "[window.innerWidth, window.innerHeight]";

    private final AgentContext ctx;
    private final SeedWalk walk;
    private final Secrets secrets;
    private final WebPrimitives web;

    Pointing(AgentContext ctx, SeedWalk walk, Secrets secrets, WebPrimitives web) {
        this.ctx = ctx;
        this.walk = walk;
        this.secrets = secrets;
        this.web = web;
    }

    // ------------------------------------------------------------------------------------------------ hit-test

    Object hitTest(Invocation inv) {
        secrets.remember(inv);
        WebInput.Target target;
        try {
            target = WebInput.target(inv.args().raw());
        } catch (WebInput.Invalid e) {
            throw Errors.invalidParam(e.param(), e.getMessage());
        }
        String frame = inv.args().str("frame");
        Entry e = web.select(inv);
        WebPrimitives.require(e, WebCapability.EVALUATE, "web.hitTest", WebCapabilities.HITTEST);
        long deadline = deadline(inv);
        int x = target.x();
        int y = target.y();
        Boolean scaleAssumed = null;
        if (target.component()) {
            Converted c = convert(e, target, deadline, "web.hitTest");
            x = c.x;
            y = c.y;
            scaleAssumed = Boolean.valueOf(c.scaleAssumed);
        }
        return descend(e, frame, x, y, scaleAssumed, deadline);
    }

    /** The hit at the point, through frame elements (see the class comment). */
    private Object descend(Entry e, String startFrame, int x, int y, Boolean scaleAssumed, long deadline) {
        List<WebInput.Frame> frames = frames(e);
        String foundIn = startFrame != null ? startFrame : (frames.isEmpty() ? null : frames.get(0).key());
        WebInput.Hit hit = hitIn(e, startFrame, x, y, remaining(deadline), "web.hitTest");
        int px = x;
        int py = y;
        int offX = 0;
        int offY = 0;
        String iframeKey = null;
        Map<String, Object> iframeRect = null;
        for (int depth = 0; hit.child() != null && depth < WebInput.MAX_FRAME_DEPTH; depth++) {
            WebInput.Child child = hit.child();
            String childKey = WebInput.childFrame(frames, foundIn, child);
            if (childKey == null) {
                break;
            }
            WebInput.Hit inner;
            try {
                inner = hitIn(e, childKey, child.x(), child.y(), remaining(deadline), "web.hitTest");
            } catch (RpcException ex) {
                int code = ex.error().code();
                if (code == ErrorCodes.NOT_FOUND || code == ErrorCodes.SCRIPT_ERROR) {
                    break; // the frame's border or padding, a frame gone, or a child that cannot answer: the element
                }
                throw ex;
            }
            iframeKey = childKey;
            iframeRect = rectOf(hit.translated(offX, offY));
            offX += px - child.x();
            offY += py - child.y();
            px = child.x();
            py = child.y();
            foundIn = childKey;
            hit = inner;
        }
        return WebInput.hitResult(e.ref, foundIn, x, y, offX == 0 && offY == 0 ? hit : hit.translated(offX, offY),
                iframeKey, iframeRect, scaleAssumed);
    }

    private static Map<String, Object> rectOf(WebInput.Hit hit) {
        @SuppressWarnings("unchecked")
        Map<String, Object> rect = (Map<String, Object>) hit.element().get("rect");
        return rect;
    }

    /** The library's {@code hit} in a frame (null: the main frame), read and bounded. */
    private WebInput.Hit hitIn(Entry e, String frame, int x, int y, long budgetMs, String primitive) {
        Object value = web.libraryIn(e, frame, Math.max(1, budgetMs), primitive, true, "hit", Long.valueOf(x),
                Long.valueOf(y));
        try {
            return WebInput.hit(value);
        } catch (WebInput.Malformed m) {
            throw Errors.internal(e.ref, m.getMessage() + " (browser " + e.ref + "; a page may replace the script "
                    + "library)");
        }
    }

    /** The browser's frames as {@link WebInput.Frame}s, main frame first; empty without FRAMES. */
    private static List<WebInput.Frame> frames(Entry e) {
        List<WebInput.Frame> out = new ArrayList<WebInput.Frame>();
        if (!WebPrimitives.has(e.browser(), WebCapability.FRAMES)) {
            return out;
        }
        List<WebFrame> frames;
        try {
            frames = e.browser().frames();
        } catch (RuntimeException ex) {
            return out;
        }
        if (frames == null) {
            return out;
        }
        for (WebFrame f : frames) {
            if (f != null) {
                out.add(new WebInput.Frame(f.key(), f.parentKey(), f.name(), f.url()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ pointer

    Object pointer(Invocation inv) {
        secrets.remember(inv);
        WebInput.Pointer p;
        try {
            p = WebInput.pointer(inv.args().raw());
        } catch (WebInput.Invalid e) {
            throw Errors.invalidParam(e.param(), e.getMessage());
        }
        Entry e = web.select(inv);
        WebPrimitives.require(e, WebCapability.INPUT, "web.pointer", WebCapabilities.POINTER);
        long deadline = deadline(inv);
        Object component = component(e);
        ComponentState state = component == null ? null : state(e, component, deadline);
        if (state != null && state.blocker != null && !p.force()) {
            String dialog = ctx.handles().handleFor(state.blocker, "component");
            throw new RpcException(ErrorCodes.MODAL_OPEN, "browser " + e.ref + " is in a window blocked by the modal "
                    + "dialog '" + state.blockerTitle + "' (" + dialog + "); answer the dialog first, or pass "
                    + "force:true to act anyway", Errors.data("dialog", dialog, "title", state.blockerTitle, "browser",
                    e.ref));
        }
        int x = p.target().x();
        int y = p.target().y();
        if (p.target().component()) {
            Converted c = convert(e, p.target(), deadline, "web.pointer");
            x = c.x;
            y = c.y;
        }
        Map<String, Object> hit = null;
        if (WebPrimitives.has(e.browser(), WebCapability.EVALUATE)) {
            try {
                hit = hitIn(e, null, x, y, Math.min(HIT_MS, remaining(deadline) / 2), "web.pointer").summary();
            } catch (RpcException ex) {
                int code = ex.error().code();
                if (code == ErrorCodes.NOT_FOUND || code == ErrorCodes.STALE_HANDLE) {
                    throw ex; // outside the viewport (nothing is sent), or a browser that closed
                }
                // a hit that cannot be read is left out; the action goes on
            }
        }
        if (x < 0 || y < 0 || x > WebPointerEvent.MAX_COORDINATE || y > WebPointerEvent.MAX_COORDINATE) {
            throw Errors.notFound("the point " + x + "," + y + " lies outside the viewport or hits no element",
                    Errors.data("reason", "outside", "x", Long.valueOf(x), "y", Long.valueOf(y), "browser", e.ref));
        }
        List<String> dispatched = new ArrayList<String>();
        WebInput.Step held = null;
        try {
            for (WebInput.Step step : WebInput.plan(p)) {
                dispatch(e, event(step, x, y, p), remaining(deadline));
                dispatched.add(step.type());
                if (WebInput.PRESSED.equals(step.type())) {
                    held = step;
                } else if (WebInput.RELEASED.equals(step.type())) {
                    held = null;
                }
            }
        } catch (RuntimeException ex) {
            if (held != null) {
                balance(e, held, x, y, p);
            }
            throw ex;
        }
        return WebInput.pointerResult(WebPrimitives.bitmapSource(e.browser()).name(), x, y, dispatched, hit,
                state != null && state.showing);
    }

    /** One event through the adapter, awaited within the bound; a dispatch is never retried. */
    private static void dispatch(Entry e, WebPointerEvent event, long boundMs) {
        CompletableFuture<Void> f;
        try {
            f = e.browser().dispatchPointer(event);
        } catch (RuntimeException ex) {
            throw Await.map(ex, e, "web.pointer", WebCapabilities.POINTER, boundMs);
        }
        if (f == null) {
            throw Errors.internal(e.ref, "browser " + e.ref + " (adapter " + e.adapter.id() + ") returned no future "
                    + "for a pointer event");
        }
        Await.get(f, Math.max(1, boundMs), e, "web.pointer", WebCapabilities.POINTER);
    }

    /** One release of the held button after a failed sequence, at most {@link #BALANCE_MS}; never throws. */
    private void balance(Entry e, WebInput.Step held, int x, int y, WebInput.Pointer p) {
        try {
            WebPointerEvent release = WebPointerEvent.of(WebPointerEvent.Action.RELEASE, x, y)
                    .button(button(held.button())).clickCount(Math.max(1, held.clickCount()))
                    .modifiers(modifiers(p)).build();
            CompletableFuture<Void> f = e.browser().dispatchPointer(release);
            if (f != null) {
                try {
                    f.get(BALANCE_MS, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    f.cancel(false);
                }
            }
        } catch (RuntimeException ex) {
            ctx.log().warn("web: the balancing release after a failed pointer sequence failed: " + ex);
        }
    }

    /** The SPI's event for one step of the plan. */
    static WebPointerEvent event(WebInput.Step step, int x, int y, WebInput.Pointer p) {
        String type = step.type();
        WebPointerEvent.Action action = WebInput.PRESSED.equals(type) ? WebPointerEvent.Action.PRESS
                : WebInput.RELEASED.equals(type) ? WebPointerEvent.Action.RELEASE
                : WebInput.WHEEL.equals(type) ? WebPointerEvent.Action.WHEEL : WebPointerEvent.Action.MOVE;
        WebPointerEvent.Builder b = WebPointerEvent.of(action, x, y).button(button(step.button()))
                .clickCount(step.clickCount()).modifiers(modifiers(p));
        if (action == WebPointerEvent.Action.WHEEL) {
            b.delta(p.deltaX(), p.deltaY());
        }
        return b.build();
    }

    private static WebPointerEvent.Button button(String name) {
        return "left".equals(name) ? WebPointerEvent.Button.LEFT : "middle".equals(name)
                ? WebPointerEvent.Button.MIDDLE : "right".equals(name) ? WebPointerEvent.Button.RIGHT
                : WebPointerEvent.Button.NONE;
    }

    private static EnumSet<WebPointerEvent.Modifier> modifiers(WebInput.Pointer p) {
        EnumSet<WebPointerEvent.Modifier> out = EnumSet.noneOf(WebPointerEvent.Modifier.class);
        for (String m : p.modifiers()) {
            out.add(WebPointerEvent.Modifier.valueOf(m));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** A component point converted into the viewport. */
    private static final class Converted {
        final int x;
        final int y;
        final boolean scaleAssumed;

        Converted(int x, int y, boolean scaleAssumed) {
            this.x = x;
            this.y = y;
            this.scaleAssumed = scaleAssumed;
        }
    }

    /** A component point in the viewport (see the class comment). */
    private Converted convert(Entry e, WebInput.Target t, long deadline, String primitive) {
        if (e.overridden) {
            throw Errors.invalid("a component point is not converted while a web.resize viewport override is in place"
                    + " on browser " + e.ref + ": clear it, or give a VIEWPORT point", Errors.data("param", "space",
                    "reason", "viewport override", "browser", e.ref));
        }
        Object component = component(e);
        if (component == null) {
            throw Errors.invalid("browser " + e.ref + " has no Swing component: give a VIEWPORT point",
                    Errors.data("param", "space", "reason", "no component", "browser", e.ref));
        }
        if (!WebPrimitives.has(e.browser(), WebCapability.EVALUATE)) {
            throw Errors.unavailable(primitive, WebCapabilities.EVALUATE, "a component point needs the page's width "
                    + "(EVALUATE), which browser " + e.ref + " cannot give", e.ref);
        }
        ComponentState state = state(e, component, deadline);
        if (state.width <= 0) {
            throw Errors.invalid("browser " + e.ref + "'s component has no size: give a VIEWPORT point",
                    Errors.data("param", "space", "reason", "no component size", "browser", e.ref));
        }
        int innerWidth = innerWidth(e, remaining(deadline), primitive);
        boolean scaled = Math.abs(state.scale - 1.0) > 1e-9;
        if (innerWidth == state.width || innerWidth <= 0) {
            return new Converted(t.x(), t.y(), scaled);
        }
        double factor = innerWidth / (double) state.width;
        return new Converted((int) Math.round(t.x() * factor), (int) Math.round(t.y() * factor), scaled);
    }

    /** The main frame's {@code innerWidth}, read as a read is; 0 when the page answers no number. */
    private static int innerWidth(final Entry e, long budgetMs, String primitive) {
        final long budget = Math.max(1, budgetMs);
        String json = Await.read(new Await.Attempt<String>() {
            @Override
            public CompletableFuture<String> start() {
                return e.browser().evaluate(WebEval.of(INNER_SIZE).timeoutMs(budget).build());
            }
        }, budget, e, primitive, WebCapabilities.EVALUATE);
        Object v = WebPrimitives.parse(e, json);
        if (v instanceof List && !((List<?>) v).isEmpty() && ((List<?>) v).get(0) instanceof Number) {
            double w = ((Number) ((List<?>) v).get(0)).doubleValue();
            return w > 0 && w <= WebInput.MAX_COORDINATE ? (int) Math.round(w) : 0;
        }
        return 0;
    }

    /** The browser's component as the adapter reports it now, or null. */
    private static Object component(Entry e) {
        try {
            Object c = e.browser().component();
            return c != null ? c : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The bounded EDT read of the component's state. */
    private ComponentState state(Entry e, Object component, long deadline) {
        try {
            return walk.state(component, Math.max(1, Math.min(STATE_MS, remaining(deadline))));
        } catch (RpcException ex) {
            throw ex;
        } catch (Exception ex) {
            throw Errors.internal(e.ref, "could not read browser " + e.ref + "'s component on the event-dispatch "
                    + "thread: " + ex);
        }
    }

    private static long deadline(Invocation inv) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Await.budget(inv));
    }

    private static long remaining(long deadlineNanos) {
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }
}
