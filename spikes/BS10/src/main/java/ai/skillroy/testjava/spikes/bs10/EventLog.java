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

package ai.skillroy.testjava.spikes.bs10;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Every mouse, motion and wheel event as AWT dispatches it to a component (an {@link AWTEventListener}: it sees the
 * event that reaches the component after the lightweight dispatcher's retargeting, whatever its origin). Consecutive
 * moves or drags to one component are collapsed into one entry with a count.
 */
final class EventLog implements AWTEventListener {

    private final List<String> entries = new ArrayList<String>();
    private String lastKey;
    private int lastCount;
    private volatile boolean recording;
    private String firstPressTarget;

    void install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(this, AWTEvent.MOUSE_EVENT_MASK
                | AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_WHEEL_EVENT_MASK);
    }

    synchronized void start() {
        entries.clear();
        lastKey = null;
        lastCount = 0;
        firstPressTarget = null;
        recording = true;
    }

    synchronized List<String> stop() {
        recording = false;
        flush();
        return new ArrayList<String>(entries);
    }

    synchronized String firstPressTarget() {
        return firstPressTarget;
    }

    @Override
    public synchronized void eventDispatched(AWTEvent event) {
        if (!recording || !(event instanceof MouseEvent)) {
            return;
        }
        MouseEvent e = (MouseEvent) event;
        String who = name(e.getComponent());
        int id = e.getID();
        if (id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_DRAGGED) {
            String key = (id == MouseEvent.MOUSE_MOVED ? "MOVED " : "DRAGGED ") + who
                    + (id == MouseEvent.MOUSE_DRAGGED ? " " + mods(e) : "");
            if (key.equals(lastKey)) {
                lastCount++;
                return;
            }
            flush();
            lastKey = key;
            lastCount = 1;
            return;
        }
        flush();
        StringBuilder sb = new StringBuilder();
        switch (id) {
            case MouseEvent.MOUSE_PRESSED:
                sb.append("PRESSED");
                if (firstPressTarget == null) {
                    firstPressTarget = who;
                }
                break;
            case MouseEvent.MOUSE_RELEASED:
                sb.append("RELEASED");
                break;
            case MouseEvent.MOUSE_CLICKED:
                sb.append("CLICKED");
                break;
            case MouseEvent.MOUSE_ENTERED:
                sb.append("ENTERED");
                break;
            case MouseEvent.MOUSE_EXITED:
                sb.append("EXITED");
                break;
            case MouseEvent.MOUSE_WHEEL:
                sb.append("WHEEL");
                break;
            default:
                sb.append("ID").append(id);
        }
        sb.append(' ').append(who);
        if (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED || id == MouseEvent.MOUSE_CLICKED) {
            sb.append(" b").append(e.getButton()).append(" n").append(e.getClickCount()).append(' ').append(mods(e));
            if (e.isPopupTrigger()) {
                sb.append(" popupTrigger");
            }
        }
        if (e instanceof MouseWheelEvent) {
            MouseWheelEvent w = (MouseWheelEvent) e;
            sb.append(" rot").append(w.getWheelRotation()).append(" type").append(w.getScrollType())
                    .append(" amt").append(w.getScrollAmount());
        }
        entries.add(sb.toString());
    }

    private void flush() {
        if (lastKey != null) {
            entries.add(lastKey + (lastCount > 1 ? " x" + lastCount : ""));
            lastKey = null;
            lastCount = 0;
        }
    }

    private static String mods(MouseEvent e) {
        int m = e.getModifiersEx() & (InputEvent.BUTTON1_DOWN_MASK | InputEvent.BUTTON2_DOWN_MASK
                | InputEvent.BUTTON3_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK
                | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK);
        String t = InputEvent.getModifiersExText(m);
        return "{" + t.replace('+', ',') + "}";
    }

    static String name(Component c) {
        if (c == null) {
            return "null";
        }
        if (c.getName() != null) {
            return c.getName();
        }
        String n = c.getClass().getName();
        return n.substring(n.lastIndexOf('.') + 1);
    }
}
