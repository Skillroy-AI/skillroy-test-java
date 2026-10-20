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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One entry of the agent's {@code web.browsers} value (phase4-contracts.md section 4.2), as far as the CDP path needs
 * it: the ref, the selection keys, the CDP target id and (Phase 7) whether a modal dialog blocks the browser.
 *
 * @param ref the browser's ref
 * @param nativeId the technology's own id as text, or null
 * @param url the main frame's URL ("" when none)
 * @param title the title, or null when the agent omitted it
 * @param closed whether the browser is closed
 * @param visibleInSwing whether its component is showing
 * @param cdpTargetId the CDP target id, or null when the agent does not know it
 * @param tag the pack's tag of the browser, or null
 * @param blocked whether the agent reported a modal dialog blocking the browser's window ({@code blockedBy}, Phase 7:
 *     the CDP path then hands {@code web.pointer} to the agent, which answers {@code MODAL_OPEN})
 * @param masked whether the agent reported the browser masked ({@code masked: true}: Phase 7 security fix S111, a
 *     pack's {@code CaptureMask} names its component; Phase 8, phase8-contracts.md 5.2, a {@code BrowserMask} names it
 *     or it has no view while a mask is registered): the CDP path then hands the four masked reads and the three
 *     actions to the agent, which refuses the reads and answers the actions without page data, and withholds the page
 *     data of its own events of the browser
 */
record AgentBrowser(String ref, String nativeId, String url, String title, boolean closed, boolean visibleInSwing,
                    String cdpTargetId, String tag, boolean blocked, boolean masked) {

    /** An entry the agent reported no modal dialog for. */
    AgentBrowser(String ref, String nativeId, String url, String title, boolean closed, boolean visibleInSwing,
                 String cdpTargetId, String tag) {
        this(ref, nativeId, url, title, closed, visibleInSwing, cdpTargetId, tag, false, false);
    }

    /** An entry the agent reported no mask for. */
    AgentBrowser(String ref, String nativeId, String url, String title, boolean closed, boolean visibleInSwing,
                 String cdpTargetId, String tag, boolean blocked) {
        this(ref, nativeId, url, title, closed, visibleInSwing, cdpTargetId, tag, blocked, false);
    }

    /**
     * A {@code web.browsers} value as the CDP path reads it (Phase 8, phase8-contracts.md 5.2 (iii), D548, D551).
     *
     * @param browsers the entries, entries without a ref skipped
     * @param masksRegistered the answer's {@code masksRegistered}: whether the agent had a {@code CaptureMask} or a
     *     {@code BrowserMask} registered; an answer without the field (an older agent) counts as true (fail closed:
     *     a page the path cannot map may then be a masked view-less browser)
     */
    record Listing(List<AgentBrowser> browsers, boolean masksRegistered) {
    }

    /**
     * The entries of a {@code web.browsers} value ({@code {browsers: [...], masksRegistered}}) and its
     * {@code masksRegistered} flag (absent or not a boolean: true); entries without a ref are skipped.
     */
    static Listing parse(Object value) {
        boolean masks = !(value instanceof Map<?, ?> v) || !Boolean.FALSE.equals(v.get("masksRegistered"));
        if (!(value instanceof Map<?, ?> m) || !(m.get("browsers") instanceof List<?> list)) {
            return new Listing(List.of(), masks);
        }
        List<AgentBrowser> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> b) || !(b.get("ref") instanceof String ref) || ref.isEmpty()) {
                continue;
            }
            out.add(new AgentBrowser(ref, text(b.get("nativeId")), b.get("url") == null ? "" : String.valueOf(
                    b.get("url")), text(b.get("title")), Boolean.TRUE.equals(b.get("closed")),
                    Boolean.TRUE.equals(b.get("visibleInSwing")), text(b.get("cdpTargetId")), text(b.get("tag")),
                    b.get("blockedBy") != null, Boolean.TRUE.equals(b.get("masked"))));
        }
        return new Listing(Collections.unmodifiableList(out), masks);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isEmpty() ? null : s;
    }
}
