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


package ai.skillroy.testjava.samples.webhost;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The host's three pages: class-path resources {@code webhost/front.html}, {@code webhost/back.html} and
 * {@code webhost/detached.html}, loaded as {@code data:} URLs (no network, no file system, no scheme handler).
 *
 * <p>Every page has a title and a heading ("Front page", "Back page", "Detached page"), a form ({@code #item} text
 * box labelled "Item", {@code #qty} number input labelled "Quantity", {@code #borrow} button "Borrow"), a status line
 * ({@code #status}, role {@code status}), an {@code iframe} ({@code #terms}, name {@code terms}, title "Loan terms")
 * whose {@code src} is a {@code data:} URL (never {@code srcdoc}, which did not load in spike BS8), and one script,
 * the same in all three pages, which
 * <ol>
 *   <li>dispatches on {@code window} the {@code CustomEvent} {@code webhost.ready} ({@code detail: {page}}) in the
 *       load handler, and {@code webhost.borrowed} ({@code detail: {item, qty}}, {@code qty} a number, 0 when the
 *       field is empty) when the button is clicked, after it has set the status line to
 *       {@code "borrowed <item> x <qty>"};</li>
 *   <li>listens on {@code window} for {@code webhost.setItem} ({@code detail: {item}}: puts the item into
 *       {@code #item}) and {@code webhost.reset} (clears both fields and the status line);</li>
 *   <li>acts on two URL fragments, only when the fragment changes to them ({@code hashchange}), never at load: a
 *       reload keeps the fragment and must bring back a responsive page (the frozen-target recovery reloads). A
 *       page loaded with either fragment already in its URL stays responsive; to stop it again, set another
 *       fragment first. {@code #freeze} sets {@code window.webhostFrozen = true} ({@code false} until then) and runs
 *       a busy loop for ever: the frozen target. {@code #stall} blocks the main thread in chunks of 30 s of busy work
 *       with a zero-delay {@code setTimeout} yield between chunks, for ever: unresponsive to a 2 s probe, but a
 *       navigation is processed at a yield (for when Chromium cannot reload a renderer stuck in an endless loop).
 *       The page logs {@code "webhost <page> frozen"} or {@code "webhost <page> stalling"} as it starts;</li>
 *   <li>sets {@code window.webhostReady = true} 300 ms after load ({@code false} until then);</li>
 *   <li>logs {@code "webhost <page> item: <value>"} with {@code console.log} whenever the value of {@code #item}
 *       changes (on {@code input}, {@code change} and the two events above), on purpose: a secret filled there must
 *       come out redacted;</li>
 *   <li>wraps {@code EventTarget.prototype.addEventListener} and {@code removeEventListener} so that
 *       {@code window.webhostDiagnostics()} returns {@code {listeners, dispatchWrapped}}: the number of listeners
 *       whose event name starts with {@code webhost.} that are registered on {@code window} through those methods
 *       after the page's script ran (the page's own two included), and whether {@code window.dispatchEvent} is an
 *       own property of {@code window} (a wrapper someone left behind). Listeners an init script added before the
 *       page's script are not seen.</li>
 * </ol>
 * The submit event of the form is cancelled, so nothing navigates the page away.
 *
 * <p>Each page's URL contains the marker {@link #marker(String)} ({@code webhost-front}, ...) verbatim (the body's
 * id), which a {@code by: {url: ...}} selection can match; the page names themselves also occur in shared text
 * (CSS {@code background}), so a bare {@code back} is not a usable pattern.
 *
 * <p>Thread-safe; the resources are read once.
 */
public final class WebHostPages {

    /** The visible page in the front card. */
    public static final String FRONT = "front";
    /** The page in the hidden back card. */
    public static final String BACK = "back";
    /** The page of the browser without a view (off-screen rendering only). */
    public static final String DETACHED = "detached";
    /** All page names. */
    public static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(FRONT, BACK, DETACHED));
    /** What every page URL starts with; the rest is the page's text, URL-encoded. */
    public static final String URL_PREFIX = "data:text/html;charset=utf-8,";

    private static final ConcurrentMap<String, String> HTML = new ConcurrentHashMap<String, String>();

    private WebHostPages() {
    }

    /** @return the class-path resource name of a page: {@code webhost/<page>.html} */
    public static String resource(String page) {
        return "webhost/" + check(page) + ".html";
    }

    /** @return the page's title and heading: "Front page", "Back page" or "Detached page" */
    public static String title(String page) {
        String p = check(page);
        return p.substring(0, 1).toUpperCase(Locale.ROOT) + p.substring(1) + " page";
    }

    /** @return the marker that occurs verbatim in the page's URL: {@code webhost-<page>} */
    public static String marker(String page) {
        return "webhost-" + check(page);
    }

    /**
     * @return the page's text (UTF-8)
     * @throws UncheckedIOException when the resource cannot be read
     */
    public static String html(String page) {
        String p = check(page);
        String html = HTML.get(p);
        if (html == null) {
            html = read(resource(p));
            HTML.putIfAbsent(p, html);
        }
        return html;
    }

    /**
     * @return {@value #URL_PREFIX} followed by the page's text, URL-encoded as
     *     {@code URLEncoder.encode(html, "UTF-8")} with every {@code +} replaced by {@code %20}
     */
    public static String url(String page) {
        return URL_PREFIX + encode(html(page));
    }

    /** {@code URLEncoder.encode(text, "UTF-8")} with {@code %20} for spaces (a data: URL decodes no {@code +}). */
    static String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is always supported", e);
        }
    }

    private static String check(String page) {
        if (!NAMES.contains(page)) {
            throw new IllegalArgumentException("no such page: " + page + " (pages: " + NAMES + ")");
        }
        return page;
    }

    private static String read(String resource) {
        InputStream in = WebHostPages.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new UncheckedIOException(new IOException("resource not found: " + resource));
        }
        try {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + resource, e);
        }
    }
}
