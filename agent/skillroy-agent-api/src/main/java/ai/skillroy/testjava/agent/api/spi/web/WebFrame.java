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
 * One frame of a browser's frame tree as {@link WebBrowser#frames()} saw it: a snapshot value, not a live object. The
 * key is opaque, minted by the adapter (JCEF: the protocol's frame id; JxBrowser: one per frame object) and stable
 * while the frame lives; {@link WebEval.Builder#frameKey(String)} takes it.
 *
 * <p>Immutable; safe to share between threads. Equal when every field is equal.
 */
public final class WebFrame {

    private final String key;
    private final String parentKey;
    private final String name;
    private final String url;
    private final boolean main;

    /**
     * @param key opaque and stable while the frame lives; neither null nor empty
     * @param parentKey the parent frame's key; null for the main frame
     * @param name the frame's name; null (unnamed) becomes {@code ""}
     * @param url the frame's document URL; null (no document yet) becomes {@code ""}
     * @param main true for the browser's main frame, which has no parent
     * @throws NullPointerException if key is null
     * @throws IllegalArgumentException if key is empty, or main is true and parentKey is not null
     */
    public WebFrame(String key, String parentKey, String name, String url, boolean main) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("key must not be empty");
        }
        if (main && parentKey != null) {
            throw new IllegalArgumentException("the main frame has no parent: parentKey must be null, was "
                    + parentKey);
        }
        this.key = key;
        this.parentKey = parentKey;
        this.name = name == null ? "" : name;
        this.url = url == null ? "" : url;
        this.main = main;
    }

    /** Opaque, stable while the frame lives; minted by the adapter. Never null or empty. */
    public String key() {
        return key;
    }

    /** The parent frame's key; null for the main frame. */
    public String parentKey() {
        return parentKey;
    }

    /** The frame's name; {@code ""} when unnamed. */
    public String name() {
        return name;
    }

    /** The frame's document URL; {@code ""} when it has no document yet. */
    public String url() {
        return url;
    }

    /** True for the browser's main frame. */
    public boolean main() {
        return main;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WebFrame)) {
            return false;
        }
        WebFrame other = (WebFrame) o;
        return key.equals(other.key) && Objects.equals(parentKey, other.parentKey) && name.equals(other.name)
                && url.equals(other.url) && main == other.main;
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, parentKey, name, url, Boolean.valueOf(main));
    }

    @Override
    public String toString() {
        return "WebFrame{key=" + key + ", parentKey=" + parentKey + ", name=" + name + ", url=" + url + ", main="
                + main + "}";
    }
}
