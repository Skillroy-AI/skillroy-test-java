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

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One synthetic pointer event for a page, in CSS pixels of the main frame's layout viewport, for
 * {@link WebBrowser#dispatchPointer} (phase7-contracts.md section 4.1, amendment A66). Built with
 * {@link #of(Action, int, int)}, for example
 * {@code WebPointerEvent.of(Action.PRESS, 120, 48).button(Button.LEFT).clickCount(1).build()}.
 *
 * <p><b>Bounds.</b> {@code x} and {@code y} lie in {@code 0..}{@value #MAX_COORDINATE} (the largest viewport
 * {@code web.resize} accepts); {@code clickCount} in {@code 0..}{@value #MAX_CLICK_COUNT}; each wheel delta in
 * {@code -}{@value #MAX_DELTA}{@code ..}{@value #MAX_DELTA}. A {@link Action#PRESS} or {@link Action#RELEASE} names a
 * button other than {@link Button#NONE} and has a click count of at least 1; a delta is given for {@link Action#WHEEL}
 * only. The defaults: {@link Button#LEFT} and a click count of 1 for a press or a release, {@link Button#NONE} and 0
 * for a move or a wheel; no modifier; no delta.
 *
 * <p>Immutable; safe to share between threads. Equal when every field is equal.
 */
public final class WebPointerEvent {

    /** The largest coordinate, in CSS pixels (the largest viewport side {@code web.resize} accepts). */
    public static final int MAX_COORDINATE = 16384;
    /** The largest click count (a triple click). */
    public static final int MAX_CLICK_COUNT = 3;
    /** The largest wheel delta per axis, in CSS pixels. */
    public static final int MAX_DELTA = 16384;

    /** What the event does. */
    public enum Action {
        /** The pointer moves to the point. */
        MOVE,
        /** A button goes down at the point. */
        PRESS,
        /** A button goes up at the point. */
        RELEASE,
        /** The wheel turns at the point by the event's deltas. */
        WHEEL
    }

    /** The button of a press or a release; {@link #NONE} for a move or a wheel. */
    public enum Button {
        NONE,
        LEFT,
        MIDDLE,
        RIGHT
    }

    /** A modifier key held during the event. */
    public enum Modifier {
        ALT,
        CTRL,
        META,
        SHIFT
    }

    private final Action action;
    private final int x;
    private final int y;
    private final Button button;
    private final int clickCount;
    private final Set<Modifier> modifiers;
    private final int deltaX;
    private final int deltaY;

    private WebPointerEvent(Builder b) {
        this.action = b.action;
        this.x = b.x;
        this.y = b.y;
        this.button = b.button;
        this.clickCount = b.clickCount;
        this.modifiers = Collections.unmodifiableSet(b.modifiers.isEmpty() ? EnumSet.noneOf(Modifier.class)
                : EnumSet.copyOf(b.modifiers));
        this.deltaX = b.deltaX;
        this.deltaY = b.deltaY;
    }

    /**
     * Starts an event at a point of the main frame's viewport.
     *
     * @param action what the event does
     * @param x CSS pixels from the viewport's left edge, {@code 0..}{@value #MAX_COORDINATE}
     * @param y CSS pixels from the viewport's top edge, {@code 0..}{@value #MAX_COORDINATE}
     * @throws NullPointerException if action is null
     * @throws IllegalArgumentException if a coordinate is out of bounds
     */
    public static Builder of(Action action, int x, int y) {
        return new Builder(action, x, y);
    }

    /** What the event does; never null. */
    public Action action() {
        return action;
    }

    /** CSS pixels from the left edge of the main frame's viewport. */
    public int x() {
        return x;
    }

    /** CSS pixels from the top edge of the main frame's viewport. */
    public int y() {
        return y;
    }

    /** The button; {@link Button#NONE} for a move or a wheel unless one was given; never null. */
    public Button button() {
        return button;
    }

    /** The click count: at least 1 for a press or a release, 0 by default for a move or a wheel. */
    public int clickCount() {
        return clickCount;
    }

    /** The modifier keys held; never null, unmodifiable, possibly empty. */
    public Set<Modifier> modifiers() {
        return modifiers;
    }

    /** The wheel's horizontal delta in CSS pixels (positive to the right); 0 unless a wheel. */
    public int deltaX() {
        return deltaX;
    }

    /** The wheel's vertical delta in CSS pixels (positive towards the user, down); 0 unless a wheel. */
    public int deltaY() {
        return deltaY;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WebPointerEvent)) {
            return false;
        }
        WebPointerEvent other = (WebPointerEvent) o;
        return action == other.action && x == other.x && y == other.y && button == other.button
                && clickCount == other.clickCount && modifiers.equals(other.modifiers) && deltaX == other.deltaX
                && deltaY == other.deltaY;
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, Integer.valueOf(x), Integer.valueOf(y), button, Integer.valueOf(clickCount),
                modifiers, Integer.valueOf(deltaX), Integer.valueOf(deltaY));
    }

    @Override
    public String toString() {
        return "WebPointerEvent{" + action + " at " + x + "," + y + ", button=" + button + ", clickCount="
                + clickCount + ", modifiers=" + modifiers + (action == Action.WHEEL ? ", delta=" + deltaX + ","
                + deltaY : "") + "}";
    }

    /** Collects the parts of a {@link WebPointerEvent}. Not thread-safe; {@link #build()} may be called again. */
    public static final class Builder {

        private final Action action;
        private final int x;
        private final int y;
        private Button button;
        private int clickCount;
        private Set<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
        private int deltaX;
        private int deltaY;
        private boolean delta;

        private Builder(Action action, int x, int y) {
            Objects.requireNonNull(action, "action must not be null");
            if (x < 0 || x > MAX_COORDINATE || y < 0 || y > MAX_COORDINATE) {
                throw new IllegalArgumentException("x and y must be 0.." + MAX_COORDINATE + ": " + x + "," + y);
            }
            this.action = action;
            this.x = x;
            this.y = y;
            boolean pressing = action == Action.PRESS || action == Action.RELEASE;
            this.button = pressing ? Button.LEFT : Button.NONE;
            this.clickCount = pressing ? 1 : 0;
        }

        /**
         * Default {@link Button#LEFT} for a press or a release, {@link Button#NONE} otherwise.
         *
         * @throws NullPointerException if button is null
         */
        public Builder button(Button button) {
            this.button = Objects.requireNonNull(button, "button must not be null");
            return this;
        }

        /**
         * Default 1 for a press or a release, 0 otherwise.
         *
         * @throws IllegalArgumentException if not {@code 0..}{@value WebPointerEvent#MAX_CLICK_COUNT}
         */
        public Builder clickCount(int clickCount) {
            if (clickCount < 0 || clickCount > MAX_CLICK_COUNT) {
                throw new IllegalArgumentException("clickCount must be 0.." + MAX_CLICK_COUNT + ": " + clickCount);
            }
            this.clickCount = clickCount;
            return this;
        }

        /**
         * The modifier keys held; default none. The set is copied.
         *
         * @throws NullPointerException if the set or an element is null
         */
        public Builder modifiers(Set<Modifier> modifiers) {
            Objects.requireNonNull(modifiers, "modifiers must not be null");
            EnumSet<Modifier> copy = EnumSet.noneOf(Modifier.class);
            for (Modifier m : modifiers) {
                copy.add(Objects.requireNonNull(m, "a modifier must not be null"));
            }
            this.modifiers = copy;
            return this;
        }

        /**
         * The wheel's deltas in CSS pixels; for {@link Action#WHEEL} only.
         *
         * @throws IllegalArgumentException if a delta is outside
         *     {@code -}{@value WebPointerEvent#MAX_DELTA}{@code ..}{@value WebPointerEvent#MAX_DELTA}
         */
        public Builder delta(int dx, int dy) {
            if (Math.abs((long) dx) > MAX_DELTA || Math.abs((long) dy) > MAX_DELTA) {
                throw new IllegalArgumentException("a wheel delta must be -" + MAX_DELTA + ".." + MAX_DELTA + ": " + dx
                        + "," + dy);
            }
            this.deltaX = dx;
            this.deltaY = dy;
            this.delta = true;
            return this;
        }

        /**
         * An immutable event; later builder changes do not affect it.
         *
         * @throws IllegalStateException if a press or a release has no button or a click count of 0, or a delta was
         *     given for another action than a wheel
         */
        public WebPointerEvent build() {
            boolean pressing = action == Action.PRESS || action == Action.RELEASE;
            if (pressing && (button == Button.NONE || clickCount < 1)) {
                throw new IllegalStateException(action + " needs a button and a click count of at least 1");
            }
            if (delta && action != Action.WHEEL) {
                throw new IllegalStateException("a delta is for WHEEL only, not " + action);
            }
            return new WebPointerEvent(this);
        }
    }
}
