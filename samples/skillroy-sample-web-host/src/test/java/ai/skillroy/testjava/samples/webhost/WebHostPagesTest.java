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

import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three pages: resources, markup, the shared script and the {@code data:} URLs. What the script does is shown by
 * the display test and the acceptance in a real browser; here the markup and the wiring the acceptance relies on.
 */
class WebHostPagesTest {

    @Test
    void theResourcesExist() {
        assertEquals(Arrays.asList("front", "back", "detached"), WebHostPages.NAMES);
        for (String page : WebHostPages.NAMES) {
            assertEquals("webhost/" + page + ".html", WebHostPages.resource(page));
            assertNotNull(WebHostPages.class.getClassLoader().getResource(WebHostPages.resource(page)), page);
            assertTrue(WebHostPages.html(page).startsWith("<!DOCTYPE html>"), page);
        }
        assertThrows(IllegalArgumentException.class, () -> WebHostPages.html("side"));
        assertThrows(IllegalArgumentException.class, () -> WebHostPages.url(null));
    }

    @Test
    void everyPageHasItsTitleHeadingAndBodyMarker() {
        assertEquals("Front page", WebHostPages.title("front"));
        assertEquals("Back page", WebHostPages.title("back"));
        assertEquals("Detached page", WebHostPages.title("detached"));
        for (String page : WebHostPages.NAMES) {
            String html = WebHostPages.html(page);
            String title = WebHostPages.title(page);
            assertEquals(1, count(html, "<title>" + title + "</title>"), page);
            assertEquals(1, count(html, "<h1>" + title + "</h1>"), page);
            assertEquals(1, count(html, "<body id=\"" + WebHostPages.marker(page) + "\">"), page);
            assertEquals("webhost-" + page, WebHostPages.marker(page));
        }
    }

    @Test
    void everyPageHasTheFormTheStatusLineAndADataUrlIframe() {
        for (String page : WebHostPages.NAMES) {
            String html = WebHostPages.html(page);
            assertEquals(1, count(html, "<label for=\"item\">Item</label>"), page);
            assertEquals(1, count(html, "<input id=\"item\" name=\"item\" type=\"text\""), page);
            assertEquals(1, count(html, "<label for=\"qty\">Quantity</label>"), page);
            assertEquals(1, count(html, "<input id=\"qty\" name=\"qty\" type=\"number\""), page);
            assertEquals(1, count(html, "<button id=\"borrow\" type=\"button\">Borrow</button>"), page);
            assertEquals(1, count(html, "<p id=\"status\" role=\"status\"></p>"), page);
            assertEquals(1, count(html, "<form id=\"loan\">"), page);
            Matcher iframe = Pattern.compile("<iframe id=\"terms\" name=\"terms\" title=\"Loan terms\"\\s+"
                    + "src=\"(data:text/html,[^\"]*)\"></iframe>").matcher(html);
            assertTrue(iframe.find(), page + ": an iframe whose src is a data: URL");
            assertTrue(iframe.group(1).contains("<title>"), iframe.group(1));
            assertFalse(iframe.find(), page + ": one iframe");
            assertFalse(html.toLowerCase(Locale.ROOT).contains("srcdoc"), page + ": srcdoc did not load in spike BS8");
            assertEquals(1, count(html, "<script>"), page);
        }
    }

    @Test
    void theThreePagesShareOneScript() {
        String front = script("front");
        assertEquals(front, script("back"));
        assertEquals(front, script("detached"));
        for (String wiring : new String[] {
            "'webhost.ready'", "'webhost.borrowed'", "'webhost.setItem'", "'webhost.reset'", "'hashchange'",
            "'hashchange', function () {\n    if (location.hash === '#freeze') {\n"
                + "      win.webhostFrozen = true;",
            "if (location.hash === '#stall') {", "var end = Date.now() + 30000;", "setTimeout(stall, 0);",
            "win.webhostFrozen = false;", "win.webhostReady = false;", "win.webhostReady = true;", "}, 300);",
            "console.log('webhost ' + page + ' item: ' + logged);", "win.webhostDiagnostics = function",
            "type.indexOf('webhost.') === 0", "Object.prototype.hasOwnProperty.call(win, 'dispatchEvent')",
            "'borrowed ' + detail.item + ' x ' + detail.qty", "qty.valueAsNumber", "e.preventDefault();",
            "win.dispatchEvent(new CustomEvent(name, {detail: detail}));",
            // Phase 7 (phase7-contracts.md 4.9): the pointer-event counter on the Borrow button.
            "['pointerdown', 'mousedown', 'mouseup', 'click'].forEach(function (type) {",
            "document.getElementById('borrow').addEventListener(type, function (e) {",
            "pointerLog.push(type + ':' + e.isTrusted);", "win.webhostPointer = function () {",
            "return pointerLog.join(',');"}) {
            assertTrue(front.contains(wiring), "the script contains " + wiring);
        }
    }

    @Test
    void theFragmentsAreActedOnOnlyWhenTheyChangeNeverAtLoad() {
        String script = script("front");
        String load = script.substring(script.indexOf("win.addEventListener('load'"));
        assertFalse(load.contains("#freeze") || load.contains("#stall") || load.contains("stall()"),
                "the load handler neither freezes nor stalls: a reload keeps the fragment");
        assertEquals(1, count(script, "location.hash === '#freeze'"));
        assertEquals(1, count(script, "location.hash === '#stall'"));
        assertEquals(1, count(script, "for (;;) {"));
    }

    @Test
    void theUrlIsTheEncodedPage() throws Exception {
        for (String page : WebHostPages.NAMES) {
            String url = WebHostPages.url(page);
            String html = WebHostPages.html(page);
            assertTrue(url.startsWith("data:text/html;charset=utf-8,"), page);
            assertEquals(WebHostPages.URL_PREFIX + URLEncoder.encode(html, "UTF-8").replace("+", "%20"), url);
            String data = url.substring(WebHostPages.URL_PREFIX.length());
            assertFalse(data.contains("+") || data.contains(" ") || data.contains("#") || data.contains("\n"), page);
            assertEquals(html, URLDecoder.decode(data, "UTF-8"), page);
            for (String other : WebHostPages.NAMES) {
                assertEquals(other.equals(page), url.contains(WebHostPages.marker(other)),
                        page + " URL and marker of " + other);
            }
        }
    }

    private static String script(String page) {
        String html = WebHostPages.html(page);
        int start = html.indexOf("<script>");
        int end = html.indexOf("</script>");
        assertTrue(start >= 0 && end > start, page);
        return html.substring(start, end);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }
}
