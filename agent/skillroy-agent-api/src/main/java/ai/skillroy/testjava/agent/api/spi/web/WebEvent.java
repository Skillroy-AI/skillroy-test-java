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
 * Something a browser did, delivered to a {@link WebEventListener}: a document finished or failed to load, a frame
 * navigated, the page wrote to the console, or the browser closed. An adapter builds it with the factory of its kind;
 * a field that does not apply to the kind is null ({@link #line()}: 0). The other fields are kept as given.
 *
 * <p>For a load or navigation event a null {@link #frameKey()} means the main frame, as in
 * {@link WebEval#frameKey()}, so that "the main frame finished loading" can be told without
 * {@link WebCapability#FRAMES}.
 *
 * <p>There is no time stamp: the agent module publishes each event on the agent's event bus as it arrives, and the bus
 * numbers and stamps it.
 *
 * <p>Immutable; safe to share between threads. Equal when the kind and every field are equal.
 */
public final class WebEvent {

    /** What happened. */
    public enum Kind {
        /** A document finished loading: {@link WebEvent#frameKey()}, {@link WebEvent#url()}. */
        LOAD_FINISHED,
        /**
         * A document failed to load: {@link WebEvent#frameKey()}, {@link WebEvent#url()}, {@link WebEvent#error()}.
         */
        LOAD_FAILED,
        /** A frame navigated to a new document: {@link WebEvent#frameKey()}, {@link WebEvent#url()}. */
        NAVIGATED,
        /**
         * The page wrote to the console: {@link WebEvent#level()}, {@link WebEvent#text()}, {@link WebEvent#source()},
         * {@link WebEvent#line()}.
         */
        CONSOLE,
        /** The browser closed; no field applies. */
        CLOSED
    }

    private static final WebEvent CLOSED = new WebEvent(Kind.CLOSED, null, null, null, null, null, 0, null);

    private final Kind kind;
    private final String frameKey;
    private final String url;
    private final String level;
    private final String text;
    private final String source;
    private final int line;
    private final String error;

    private WebEvent(Kind kind, String frameKey, String url, String level, String text, String source, int line,
                     String error) {
        this.kind = kind;
        this.frameKey = frameKey;
        this.url = url;
        this.level = level;
        this.text = text;
        this.source = source;
        this.line = line;
        this.error = error;
    }

    /**
     * A document finished loading.
     *
     * @param frameKey the frame's {@link WebFrame#key()}; null for the main frame
     * @param url the document's URL
     */
    public static WebEvent loadFinished(String frameKey, String url) {
        return new WebEvent(Kind.LOAD_FINISHED, frameKey, url, null, null, null, 0, null);
    }

    /**
     * A document failed to load.
     *
     * @param frameKey the frame's {@link WebFrame#key()}; null for the main frame
     * @param url the URL that failed
     * @param error the technology's description of the failure (for example {@code net::ERR_NAME_NOT_RESOLVED})
     */
    public static WebEvent loadFailed(String frameKey, String url, String error) {
        return new WebEvent(Kind.LOAD_FAILED, frameKey, url, null, null, null, 0, error);
    }

    /**
     * A frame navigated to a new document.
     *
     * @param frameKey the frame's {@link WebFrame#key()}; null for the main frame
     * @param url the new document's URL
     */
    public static WebEvent navigated(String frameKey, String url) {
        return new WebEvent(Kind.NAVIGATED, frameKey, url, null, null, null, 0, null);
    }

    /**
     * The page wrote to the console (or threw an exception nothing caught, level {@code error}).
     *
     * @param level in lower case: adapters map their technology's levels onto {@code debug}, {@code log},
     *     {@code info}, {@code warning} and {@code error} where one fits
     * @param text the message as the console shows it
     * @param source the URL of the script that wrote it, or null
     * @param line the 1-based line in {@code source}, or 0 when unknown
     */
    public static WebEvent console(String level, String text, String source, int line) {
        return new WebEvent(Kind.CONSOLE, null, null, level, text, source, line, null);
    }

    /** The browser closed. */
    public static WebEvent closed() {
        return CLOSED;
    }

    /** Never null. */
    public Kind kind() {
        return kind;
    }

    /** The frame of a load or navigation event; null for the main frame and for the other kinds. */
    public String frameKey() {
        return frameKey;
    }

    /** The document's URL for a load or navigation event; null for the other kinds. */
    public String url() {
        return url;
    }

    /** {@link Kind#CONSOLE}: the level in lower case; null for the other kinds. */
    public String level() {
        return level;
    }

    /** {@link Kind#CONSOLE}: the message; null for the other kinds. */
    public String text() {
        return text;
    }

    /** {@link Kind#CONSOLE}: the URL of the script that wrote the message, or null. */
    public String source() {
        return source;
    }

    /** {@link Kind#CONSOLE}: the 1-based line in {@link #source()}; 0 when unknown and for the other kinds. */
    public int line() {
        return line;
    }

    /** {@link Kind#LOAD_FAILED}: the technology's description of the failure; null for the other kinds. */
    public String error() {
        return error;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WebEvent)) {
            return false;
        }
        WebEvent other = (WebEvent) o;
        return kind == other.kind && Objects.equals(frameKey, other.frameKey) && Objects.equals(url, other.url)
                && Objects.equals(level, other.level) && Objects.equals(text, other.text)
                && Objects.equals(source, other.source) && line == other.line && Objects.equals(error, other.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, frameKey, url, level, text, source, Integer.valueOf(line), error);
    }

    /**
     * Shows the length of a console message, never its text: a page may log what was typed into it, a secret
     * included.
     */
    @Override
    public String toString() {
        return "WebEvent{kind=" + kind + ", frameKey=" + frameKey + ", url=" + url + ", level=" + level + ", text="
                + (text == null ? "null" : "<" + text.length() + " chars>") + ", source=" + source + ", line=" + line
                + ", error=" + error + "}";
    }
}
