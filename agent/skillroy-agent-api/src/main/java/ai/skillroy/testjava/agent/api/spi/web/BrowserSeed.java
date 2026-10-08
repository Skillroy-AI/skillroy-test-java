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

import java.util.Objects;

/**
 * One object a {@link BrowserRegistryAdapter} hands the agent (a browser, a view, an engine or a client, as its
 * technology knows them) with the pack's optional tag for it: a name such as {@code "hidden-card"}, which
 * {@code web.browsers} reports and a {@code by: {tag: …}} selection matches.
 *
 * <p>Immutable; safe to share between threads. Two seeds are equal when they hold the same object (identity, not
 * {@code equals}) and equal tags. {@link #equals}, {@link #hashCode()} and {@link #toString()} never call a method of
 * the object, which may belong to a vendor and block or throw.
 */
public final class BrowserSeed {

    private final Object object;
    private final String tag;

    private BrowserSeed(Object object, String tag) {
        this.object = object;
        this.tag = tag;
    }

    /**
     * An untagged seed.
     *
     * @throws NullPointerException if object is null
     */
    public static BrowserSeed of(Object object) {
        return of(object, null);
    }

    /**
     * A tagged seed.
     *
     * @param tag the pack's name for the browser, kept as given; null for none
     * @throws NullPointerException if object is null
     */
    public static BrowserSeed of(Object object, String tag) {
        Objects.requireNonNull(object, "object must not be null");
        return new BrowserSeed(object, tag);
    }

    /** Never null. */
    public Object object() {
        return object;
    }

    /** The pack's name for the browser, or null. */
    public String tag() {
        return tag;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BrowserSeed)) {
            return false;
        }
        BrowserSeed other = (BrowserSeed) o;
        return object == other.object && Objects.equals(tag, other.tag);
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(object) + Objects.hashCode(tag);
    }

    /** The object's class name and identity hash, as {@code Object.toString()} would give them, and the tag. */
    @Override
    public String toString() {
        return "BrowserSeed{object=" + object.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(object)) + ", tag=" + tag + "}";
    }
}
