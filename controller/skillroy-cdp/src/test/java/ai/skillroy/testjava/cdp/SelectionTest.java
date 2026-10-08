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

package ai.skillroy.testjava.cdp;

import ai.skillroy.testjava.scenario.web.WebPathException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The {@code by:} resolution rules of the CDP path (phase4-contracts.md sections 4.2 and 5.2). */
class SelectionTest {

    private static final List<AgentBrowser> BROWSERS = List.of(
            new AgentBrowser("b-front", "1", "data:text/html,front", "Front", false, true, "T1", "front"),
            new AgentBrowser("b-back", "2", "data:text/html,back", "Back", false, false, "T2", "back"),
            new AgentBrowser("b-detached", "3", "data:text/html,detached", "Detached", false, false, null,
                    "detached"),
            new AgentBrowser("b-gone", "4", "data:text/html,gone", "Gone", true, true, "T4", "gone"));

    /** A page as the selection sees it: target id, url, title. */
    private record P(String url, String title) {
    }

    private static final Map<String, P> PAGES = new LinkedHashMap<>();

    static {
        PAGES.put("T1", new P("data:text/html,front", "Front page"));
        PAGES.put("T2", new P("data:text/html,back", "Back page"));
        PAGES.put("T9", new P("data:text/html,popup", "Popup"));
    }

    private final List<String> sourceCalls = new ArrayList<>();

    private Selection.Source source(List<AgentBrowser> browsers) {
        return new Selection.Source() {
            @Override
            public AgentBrowser byRef(String ref) {
                sourceCalls.add("byRef " + ref);
                return browsers.stream().filter(b -> b.ref().equals(ref)).findFirst().orElse(null);
            }

            @Override
            public List<AgentBrowser> current() {
                sourceCalls.add("current");
                return browsers;
            }
        };
    }

    private String pick(Map<String, Object> params) throws Exception {
        return Selection.resolve(params, source(BROWSERS)).pick(PAGES, P::url, P::title);
    }

    private String unsupported(Map<String, Object> params) {
        return assertThrows(WebPathException.class, () -> pick(params)).getMessage();
    }

    @Test
    void aRefIsMappedToItsTargetId() throws Exception {
        assertEquals("T2", pick(Map.of("browser", "b-back")));
        assertEquals(List.of("byRef b-back"), sourceCalls);
        Selection s = Selection.resolve(Map.of("browser", "b-front"), source(BROWSERS));
        assertEquals("b-front", s.ref());
        assertEquals("T1", s.targetId());
    }

    @Test
    void aRefWithoutTargetIdOrUnknownOrClosedIsUnsupported() {
        assertEquals("browser b-detached (browser b-detached) has no CDP target id",
                unsupported(Map.of("browser", "b-detached")));
        assertEquals("browser b-nope is not in the agent's web.browsers", unsupported(Map.of("browser", "b-nope")));
        assertEquals("browser b-gone is closed", unsupported(Map.of("browser", "b-gone")));
        assertEquals("browser must be a browser ref", unsupported(Map.of("browser", 7)));
    }

    @Test
    void aTargetWithoutAPageIsUnsupported() {
        List<AgentBrowser> stale = List.of(new AgentBrowser("b-x", null, "u", null, false, true, "T7", null));
        WebPathException e = assertThrows(WebPathException.class, () -> Selection.resolve(Map.of("browser", "b-x"),
                source(stale)).pick(PAGES, P::url, P::title));
        assertEquals("no CDP page has the target id T7 of browser b-x", e.getMessage());
    }

    @Test
    void urlAndTitleAreRegularExpressionsFoundInThePagesOwnText() throws Exception {
        assertEquals("T2", pick(Map.of("by", Map.of("url", "back$"))));
        assertEquals("T9", pick(Map.of("by", Map.of("url", "popup"))));
        assertEquals("T1", pick(Map.of("by", Map.of("title", "^Front"))));
        assertEquals("T9", pick(Map.of("by", Map.of("title", "Pop"))), "a page the agent does not list");
        assertEquals(List.of(), sourceCalls, "the pages' own url and title, no agent call");
        assertEquals("no CDP page matches by.url nothing", unsupported(Map.of("by", Map.of("url", "nothing"))));
        assertEquals("3 CDP pages match by.url ^data:", unsupported(Map.of("by", Map.of("url", "^data:"))));
        assertEquals("by.title is not a regular expression: Unclosed group",
                unsupported(Map.of("by", Map.of("title", "("))));
        assertEquals("by.url must be a regular expression", unsupported(Map.of("by", Map.of("url", 3))));
    }

    @Test
    void tagAndNativeIdGoThroughTheAgentsList() throws Exception {
        assertEquals("T2", pick(Map.of("by", Map.of("tag", "back"))));
        assertEquals("T1", pick(Map.of("by", Map.of("nativeId", "1"))));
        assertEquals("T1", pick(Map.of("by", Map.of("nativeId", 1L))), "compared as text");
        assertEquals("no browser matches by.tag gone", unsupported(Map.of("by", Map.of("tag", "gone"))),
                "closed browsers never match");
        assertEquals("browser b-detached (by.tag detached) has no CDP target id",
                unsupported(Map.of("by", Map.of("tag", "detached"))));
    }

    @Test
    void withNeitherTheSingleBrowserShowingInSwingIsChosen() throws Exception {
        assertEquals("T1", pick(Map.of()));
        List<AgentBrowser> two = new ArrayList<>(BROWSERS);
        two.add(new AgentBrowser("b-other", null, "u", null, false, true, "T9", null));
        WebPathException several = assertThrows(WebPathException.class, () -> Selection.resolve(Map.of(),
                source(two)));
        assertEquals("2 browsers match the default browser (the one showing in Swing)", several.getMessage());
        WebPathException none = assertThrows(WebPathException.class, () -> Selection.resolve(Map.of(),
                source(BROWSERS.subList(1, 3))));
        assertEquals("no browser matches the default browser (the one showing in Swing)", none.getMessage());
    }

    @Test
    void browserWinsOverBy() throws Exception {
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("browser", "b-back");
        both.put("by", Map.of("tag", "front"));
        assertEquals("T2", pick(both));
    }

    @Test
    void malformedByIsUnsupported() {
        assertEquals("by must hold exactly one of url, title, tag, nativeId",
                unsupported(Map.of("by", Map.of("url", "a", "tag", "b"))));
        assertEquals("by must hold exactly one of url, title, tag, nativeId", unsupported(Map.of("by", "front")));
        assertEquals("by.colour is not a selection the CDP path knows",
                unsupported(Map.of("by", Map.of("colour", "red"))));
        Map<String, Object> nullTag = new LinkedHashMap<>();
        nullTag.put("tag", null);
        assertEquals("by.tag must not be null", unsupported(Map.of("by", nullTag)));
    }

    @Test
    void aUrlSelectionHasNoRefUntilThePagesAreKnown() throws Exception {
        Selection s = Selection.resolve(Map.of("by", Map.of("url", "front")), source(BROWSERS));
        assertNull(s.ref());
        assertNull(s.targetId());
        Function<P, String> boom = p -> {
            throw new AssertionError("titles are not read for a url selection");
        };
        assertEquals("T1", s.pick(PAGES, P::url, boom));
    }
}
