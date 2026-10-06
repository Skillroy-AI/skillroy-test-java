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

package ai.skillroy.testjava.agent.swing;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.protocol.Topics;

import java.awt.AWTEvent;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The AWT tap: one {@link AWTEventListener} for window and window-focus events that publishes {@code window.opened}
 * and {@code window.closed} with {@code {ref, type, title, modal}}, and records the open order used to pick the
 * topmost modal dialog.
 *
 * <p>AWT raises {@code WINDOW_OPENED} only the first time a window is shown and {@code WINDOW_CLOSED} when it is
 * disposed; hiding a window with {@code setVisible(false)}, or showing it again, raises neither. So the tap keeps the
 * set of windows it has reported showing and compares it with the windows that are showing now ({@link #sync()}) on
 * every window and window-focus event and whenever a primitive asks (at the start of {@code swing.settle} and of its
 * markers, {@code swing.snapshot}, {@code swing.windows} and {@code swing.dialogs}). A window that became showing again
 * gets {@code window.opened} with {@code reshown: true}; one that stopped showing, whether hidden or disposed, gets
 * {@code window.closed}. Every {@code window.opened} is therefore followed by exactly one {@code window.closed} once the
 * window stops showing, and a window shown again gets a new pair. A window hidden and shown again between two
 * comparisons is not seen. A window already showing when the tap is activated gets its {@code window.opened} from the
 * first comparison.
 *
 * <p>Created at install, before AWT runs; nothing here touches AWT until the module {@linkplain #activate() activates}
 * the tap, which it does only once the application has started AWT. Inactive, every method is a no-op.
 *
 * <p>Thread-safe: the listener and {@link #sync()} run on the EDT and serialise on this object; they never throw into
 * the EDT and do no blocking work.
 */
final class WindowTap implements AWTEventListener {

    /** Source name of the events this module publishes. */
    static final String SOURCE = "swing";
    /** The events the tap listens to: window events and window-focus events. */
    static final long EVENT_MASK = AWTEvent.WINDOW_EVENT_MASK | AWTEvent.WINDOW_FOCUS_EVENT_MASK;

    private final AgentContext ctx;
    private final Windows windows;
    /** Windows reported showing (a window.opened without a window.closed since); weak, never keeps one alive. */
    private final Map<Window, Boolean> showing = new WeakHashMap<Window, Boolean>();
    private volatile boolean active;

    WindowTap(AgentContext ctx, Windows windows) {
        this.ctx = ctx;
        this.windows = windows;
    }

    /** Starts reporting; called once AWT runs, before the tap is added to the toolkit. */
    void activate() {
        active = true;
    }

    /** Stops reporting and forgets the windows it reported; called on uninstall. */
    void deactivate() {
        active = false;
        synchronized (this) {
            showing.clear();
        }
    }

    boolean active() {
        return active;
    }

    @Override
    public void eventDispatched(AWTEvent event) {
        if (!active || !(event instanceof WindowEvent)) {
            return;
        }
        try {
            int id = event.getID();
            Window w = ((WindowEvent) event).getWindow();
            synchronized (this) {
                if (w != null && id == WindowEvent.WINDOW_OPENED) {
                    if (!showing.containsKey(w)) {
                        opened(w);
                    }
                } else if (w != null && id == WindowEvent.WINDOW_CLOSED) {
                    if (showing.remove(w) != null) {
                        publish(Topics.WINDOW_CLOSED, w, false);
                    }
                }
                diff();
            }
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            warn(t);
        }
    }

    /**
     * Compares the windows showing now with those reported showing and publishes the difference. EDT only; never
     * throws; a no-op while the tap is inactive.
     */
    void sync() {
        if (!active) {
            return;
        }
        try {
            synchronized (this) {
                diff();
            }
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            warn(t);
        }
    }

    /** Caller holds this object's lock. */
    private void diff() {
        Set<Window> now = Collections.newSetFromMap(new IdentityHashMap<Window, Boolean>());
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) {
                now.add(w);
                if (!showing.containsKey(w)) {
                    opened(w);
                }
            }
        }
        List<Window> gone = new ArrayList<Window>();
        for (Window w : showing.keySet()) {
            if (w != null && !now.contains(w)) {
                gone.add(w);
            }
        }
        for (Window w : gone) {
            showing.remove(w);
            publish(Topics.WINDOW_CLOSED, w, false);
        }
    }

    /** Caller holds this object's lock. */
    private void opened(Window w) {
        boolean first = windows.recordOpen(w);
        showing.put(w, Boolean.TRUE);
        publish(Topics.WINDOW_OPENED, w, !first);
    }

    private void publish(String topic, Window w, boolean reshown) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("ref", ctx.handles().handleFor(w, ComponentView.KIND));
        payload.put("type", Windows.type(w));
        payload.put("title", Windows.title(w));
        payload.put("modal", Boolean.valueOf(Windows.modal(w)));
        if (reshown) {
            payload.put("reshown", Boolean.TRUE);
        }
        ctx.events().publish(topic, SOURCE, payload);
    }

    private void warn(Throwable t) {
        try {
            ctx.log().warn("swing: window tap failed: " + t, t);
        } catch (Throwable ignored) {
            // never let the agent's own failure reach the EDT
        }
    }
}
