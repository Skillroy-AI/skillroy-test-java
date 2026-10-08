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

package ai.skillroy.testjava.agent.web.fake;

import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One document of a fake browser's frame: what the table-driven evaluator ({@link FakeScript}) reads and changes. It
 * knows what a page of the kit (or of a test) declares in its HTML, read with regular expressions, not a parser:
 * the {@code <title>}, the first {@code background: rgb(r, g, b)} (the bitmap's colour), every
 * {@code <iframe name="..." src="data:...">} (child documents), every {@code console.log('...')} of an inline
 * script, an inline script that records {@code data-init-at-parse}, and the body's text. Its window has enumerable
 * globals (the page's own) and hidden ones (the script library's slot, the kit's init-script marker).
 *
 * <p>Thread-safe (synchronised); the fakes evaluate on their renderer thread.
 */
public final class FakeDocument {

    static {
        ClassInitialisations.record(FakeDocument.class);
    }

    /** What a document asks of the browser it lives in. */
    public interface Host {
        /** The page logged a console message. */
        void console(FakeDocument document, String level, String text);

        /** The page asked for a reload ({@code location.reload()}). */
        void reload(boolean ignoreCache);

        /** The layout viewport, CSS pixels: {@code {innerWidth, innerHeight}}. */
        int[] innerSize();
    }

    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern BACKGROUND = Pattern.compile("background:\\s*rgb\\((\\d+),\\s*(\\d+),\\s*(\\d+)\\)");
    private static final Pattern IFRAME = Pattern.compile("<iframe\\s+name=\"([^\"]*)\"\\s+src=\"([^\"]*)\"");
    private static final Pattern CONSOLE = Pattern.compile("console\\.log\\('([^']*)'\\)");
    private static final Pattern BODY = Pattern.compile("<body>(.*)</body>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    final Host host;
    final String url;
    final String html;
    final String title;
    final int background;
    final String name;
    final String bodyText;
    final List<FakeDocument> children = new ArrayList<FakeDocument>();
    final List<String> consoleLogs = new ArrayList<String>();
    final Map<String, Object> globals = new LinkedHashMap<String, Object>();
    final Map<String, Object> hidden = new LinkedHashMap<String, Object>();
    final Map<String, Object> deferred = new LinkedHashMap<String, Object>();
    final Deque<Map<String, Object>> tapBuffer = new ArrayDeque<Map<String, Object>>();
    List<String> tapPatterns = new ArrayList<String>();
    String tapTarget = "window";
    long tapDropped;
    boolean libraryInstalled;
    String initAtParse;
    int nextDefer;
    volatile boolean gone;

    private FakeDocument(Host host, String url, String html, String name) {
        this.host = host;
        this.url = url;
        this.html = html;
        this.name = name == null ? "" : name;
        Matcher t = TITLE.matcher(html);
        this.title = t.find() ? t.group(1).trim() : "";
        Matcher b = BACKGROUND.matcher(html);
        this.background = b.find() ? (Integer.parseInt(b.group(1)) << 16) | (Integer.parseInt(b.group(2)) << 8)
                | Integer.parseInt(b.group(3)) : 0xffffff;
        Matcher body = BODY.matcher(html);
        String text = body.find() ? body.group(1) : "";
        text = text.replaceAll("(?s)<iframe.*?</iframe>", " ").replaceAll("(?s)<script.*?</script>", " ");
        this.bodyText = TAG.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").trim();
        Matcher c = CONSOLE.matcher(html);
        while (c.find()) {
            consoleLogs.add(c.group(1));
        }
    }

    /** A document and its iframes' documents, parsed from the HTML (nothing runs yet). */
    public static FakeDocument parse(Host host, String url, String html) {
        return parse(host, url, html, "");
    }

    private static FakeDocument parse(Host host, String url, String html, String name) {
        FakeDocument doc = new FakeDocument(host, url, html, name);
        Matcher m = IFRAME.matcher(html);
        while (m.find()) {
            String src = m.group(2);
            doc.children.add(parse(host, src, decodeDataUrl(src), m.group(1)));
        }
        return doc;
    }

    /** The HTML of a {@code data:text/html;charset=utf-8,...} URL (empty for anything else). */
    static String decodeDataUrl(String src) {
        int comma = src.indexOf(',');
        if (!src.startsWith("data:") || comma < 0) {
            return "";
        }
        try {
            return URLDecoder.decode(src.substring(comma + 1).replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs the page's inline scripts: the init-at-parse record and the console lines. */
    public synchronized void runInlineScripts() {
        if (html.contains("data-init-at-parse")) {
            initAtParse = hidden.containsKey("__kitInit") ? "string" : "undefined";
        }
        for (String text : consoleLogs) {
            host.console(this, "log", text);
        }
    }

    /** This document and its descendants, depth first, this one first. */
    public List<FakeDocument> tree() {
        List<FakeDocument> out = new ArrayList<FakeDocument>();
        out.add(this);
        for (FakeDocument c : children) {
            out.addAll(c.tree());
        }
        return out;
    }

    public String url() {
        return url;
    }

    public String title() {
        return title;
    }

    public String name() {
        return name;
    }

    public int background() {
        return background;
    }

    public List<FakeDocument> children() {
        return children;
    }

    /** True once the document was replaced by another. */
    public boolean gone() {
        return gone;
    }

    /** The document was replaced by another (a fake's load). */
    public void markGone() {
        gone = true;
    }

    /** The page dispatches an event of its own (for tap tests): recorded when the tap matches its name. */
    public synchronized void pageEvent(String eventName, Object detail) {
        FakeScript.record(this, eventName, detail);
    }

    /** Sets an enumerable global, as a page script would. */
    public synchronized void pageGlobal(String name, Object value) {
        globals.put(name, value);
    }
}
