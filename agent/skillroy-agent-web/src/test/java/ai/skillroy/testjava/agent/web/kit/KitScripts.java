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

package ai.skillroy.testjava.agent.web.kit;

/**
 * The JavaScript expressions the adapter contract kit evaluates through an adapter, in one place so that a fake of a
 * technology (which evaluates by table, not with an engine) can recognise exactly these. Each is an expression, as
 * {@code WebBrowser.evaluate} takes it.
 */
public final class KitScripts {

    /** A number: 42. */
    public static final String NUMBER = "6 * 7";
    /** A string: "kit-string". */
    public static final String STRING = "'kit' + '-' + 'string'";
    /** An object: {"a":1,"b":[true,null],"c":"x"}. */
    public static final String OBJECT = "({a: 1, b: [true, null], c: 'x'})";
    /** {@code undefined}, which crosses as "null". */
    public static final String UNDEFINED = "undefined";
    /** A thrown error whose message holds "kit boom". */
    public static final String THROWS = "(function(){ throw new Error('kit boom'); })()";
    /** A promise of 7 after 30 ms. */
    public static final String PROMISE = "new Promise(function(r){ setTimeout(function(){ r(7); }, 30); })";
    /** A string of 2 MiB ("x" repeated 2097152 times), which the script library cuts at 1 MiB. */
    public static final String BIG_STRING = "new Array(2097153).join('x')";
    /** The page's own title. */
    public static final String TITLE = "document.title";
    /** {@code innerWidth + "x" + innerHeight}. */
    public static final String INNER_SIZE = "innerWidth + 'x' + innerHeight";
    /** The enumerable own properties of the window, joined by commas. */
    public static final String WINDOW_KEYS = "Object.keys(window).join(',')";
    /** Logs {@link #CONSOLE_MARKER}. */
    public static final String CONSOLE_LOG = "console.log('kit-console-marker')";
    /** What the kit's main page and {@link #CONSOLE_LOG} log. */
    public static final String CONSOLE_MARKER = "kit-console-marker";
    /** The init script: a non-enumerable marker on the window. */
    public static final String INIT_SOURCE =
            "Object.defineProperty(window, '__kitInit', {value: 'ran', configurable: true, enumerable: false})";
    /** "string" when the init script ran in this document, else "undefined". */
    public static final String INIT_NOW = "typeof window.__kitInit";
    /** What the page's inline script saw of the marker while the document was parsed. */
    public static final String INIT_AT_PARSE = "document.documentElement.getAttribute('data-init-at-parse')";
    /**
     * Marks the current document (item 12): a non-enumerable property that a new document does not have. The
     * expression ends with a string, since an engine that evaluates by value cannot serialise what
     * {@code Object.defineProperty} returns (the window; Chromium: "Object reference chain is too long").
     */
    public static final String DOCUMENT_MARK = "(Object.defineProperty(window, '__kitMark', "
            + "{value: 'set', configurable: true, enumerable: false}), 'set')";
    /** "string" while the document is the one {@link #DOCUMENT_MARK} marked, else "undefined". */
    public static final String DOCUMENT_MARK_NOW = "typeof window.__kitMark";

    private KitScripts() {
    }

    /** One of 100 distinct expressions for the run of evaluations: {@code i * 2}. */
    public static String twice(int i) {
        return i + " * 2";
    }
}
