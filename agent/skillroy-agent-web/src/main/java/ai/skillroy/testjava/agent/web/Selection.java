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

package ai.skillroy.testjava.agent.web;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.text.BoundedRegex;
import ai.skillroy.testjava.protocol.web.WebLib;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The browser selection rules of phase4-contracts.md section 4.2. A call names a browser with {@code browser: <ref>}
 * <b>or</b> {@code by: {url: <regex>} | {tag: <string>} | {title: <regex>} | {nativeId: <string>}} (several keys
 * must all match; a regex is found anywhere in the text, Java syntax); with neither, the default is the single browser
 * whose component is showing. Zero matches answer {@code NOT_FOUND} with {@code data.browsers}: every open browser of
 * the index (closed ones are left out, as {@code web.browsers} leaves them out by default) as {@link #summary}
 * {@code {ref, adapter, nativeId?, url, title?, closed, visibleInSwing, component?, capabilities, tag?}}, the title
 * only where the selection read it ({@code by.title}; no page is read for the error); several answer
 * {@code AMBIGUOUS} with {@code data.candidates} in the same shape. A ref the index does not know
 * rebuilds the index once; a ref the agent does not know (or that was released when its browser left the index)
 * answers {@code STALE_HANDLE}, a ref of something else {@code INVALID_PARAMS}, a browser that has closed
 * {@code STALE_HANDLE}. A {@code by} or default selection rebuilds an index older than {@link BrowserIndex#STALE_MS}
 * or one in which nothing matched.
 *
 * <p>The regular expressions of {@code by.url} and {@code by.title} are searched under the S24 budget (security fix
 * S46, phase4-contracts.md 4.2; {@link BoundedRegex}): at most {@value BoundedRegex#BUDGET_MS} ms for the whole
 * selection and never past its own bound, the subject at most 1 Mi characters; a search that runs out answers
 * {@code INVALID_PARAMS} ("the regex in by.title ran longer than 1000 ms and was stopped; simplify it",
 * {@code data {param, regex, reason: "regex too slow"}}; {@code "text too long"} for the subject), as the Swing
 * locator does. A summary's URL and title are cut at {@link WebLib#MAX_URL_CHARS} (S43).
 *
 * <p>A masked browser (Phase 8, phase8-contracts.md 5.2 (ii), D550; its {@link Entry#masked} flag) is never matched by
 * {@code by.url} or {@code by.title} (a regex on withheld text would reveal it), its title is never read for a match,
 * and its summary in the error data says {@code url: ""}, no {@code title}, {@code masked: true}; {@code by.tag},
 * {@code by.nativeId} and {@code browser: <ref>} still select it, so that a scenario can act on it. The flags are
 * judged afresh before a {@code by.url} or {@code by.title} match and before the error data is built (Phase 8 security
 * fixes S168 and S172, FE: {@link BrowserIndex#rejudge}).
 *
 * <p>Thread-safe; runs on agent workers.
 */
final class Selection {

    /** Bound of one {@code document.title} read for a {@code by.title} match. */
    static final long TITLE_MS = 2000;

    private static final Set<String> BY_KEYS = new HashSet<String>(Arrays.asList("url", "tag", "title", "nativeId"));

    private final AgentContext ctx;
    private final BrowserIndex index;
    private final SeedWalk walk;

    Selection(AgentContext ctx, BrowserIndex index, SeedWalk walk) {
        this.ctx = ctx;
        this.index = index;
        this.walk = walk;
    }

    /** The browser the call names, by the rules of the class comment. */
    Entry select(JsonView args, long budgetMs) {
        boolean hasRef = args.has("browser");
        boolean hasBy = args.has("by");
        if (hasRef && hasBy) {
            throw Errors.invalid("give either browser or by, not both", Errors.data("param", "by"));
        }
        if (hasRef) {
            return byRef(args.str("browser"), budgetMs);
        }
        if (hasBy) {
            return byMatch(args.obj("by"), budgetMs);
        }
        return byDefault(budgetMs);
    }

    /** The browser behind a ref (see the class comment). */
    Entry byRef(String ref, long budgetMs) {
        Entry e = index.snapshot().byRef.get(ref);
        if (e == null) {
            e = index.rebuild(budgetMs).byRef.get(ref);
        }
        if (e == null) {
            Object o = ctx.handles().resolve(ref); // STALE_HANDLE when unknown, collected or released
            throw Errors.invalid("handle " + ref + " is not a browser (it is a " + o.getClass().getSimpleName()
                    + "); use a ref from web.browsers", Errors.data("param", "browser", "handle", ref));
        }
        requireOpen(e);
        return e;
    }

    private Entry byMatch(JsonView by, long budgetMs) {
        Map<String, Object> raw = by.raw();
        if (raw.isEmpty()) {
            throw Errors.invalid("by needs one of url, tag, title, nativeId", Errors.data("param", "by"));
        }
        for (String key : raw.keySet()) {
            if (!BY_KEYS.contains(key)) {
                throw Errors.invalid("unknown key '" + key + "' in by: expected url, tag, title or nativeId",
                        Errors.data("param", "by." + key));
            }
        }
        final Pattern url = pattern(by, "url");
        final Pattern title = pattern(by, "title");
        final String tag = by.str("tag");
        final String nativeId = by.str("nativeId");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        long regexBudget = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BoundedRegex.BUDGET_MS);
        long regexDeadline = deadline - regexBudget < 0 ? deadline : regexBudget;
        BrowserIndex.Snapshot s = index.snapshot();
        boolean rebuilt = false;
        if (!s.built || s.ageMs() > BrowserIndex.STALE_MS) {
            s = index.rebuild(remainingMs(deadline));
            rebuilt = true;
        }
        Map<Entry, String> titles = new IdentityHashMap<Entry, String>();
        List<Entry> matches = match(s, url, title, tag, nativeId, deadline, regexDeadline, titles);
        if (matches.isEmpty() && !rebuilt) {
            s = index.rebuild(remainingMs(deadline));
            matches = match(s, url, title, tag, nativeId, deadline, regexDeadline, titles);
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.isEmpty()) {
            List<Entry> listed = open(s.entries);
            rejudged(listed, deadline);
            Map<String, Object> data = Errors.data("by", Json.parse(Json.write(raw)), "browsers",
                    summaries(listed, titles));
            throw Errors.notFound("no browser matches by " + Json.write(raw), data);
        }
        rejudged(matches, deadline);
        Map<String, Object> data = Errors.data("by", Json.parse(Json.write(raw)), "candidates",
                summaries(matches, titles));
        throw Errors.ambiguous(matches.size() + " browsers match by " + Json.write(raw)
                + "; name one with browser: <ref>", data);
    }

    /**
     * The browsers that match; the titles read for a {@code by.title} match are added to {@code titlesRead}. The
     * regular expressions are searched until {@code regexDeadline} at most (S46).
     */
    private List<Entry> match(BrowserIndex.Snapshot s, Pattern url, Pattern title, String tag, String nativeId,
                              long deadline, long regexDeadline, Map<Entry, String> titlesRead) {
        List<Entry> keyed = new ArrayList<Entry>();
        for (Entry e : s.entries) {
            WebBrowser b = e.browser();
            if (isClosed(b)) {
                continue;
            }
            if (tag != null && !tag.equals(e.tag)) {
                continue;
            }
            if (nativeId != null && !nativeId.equals(safeNativeId(b))) {
                continue;
            }
            keyed.add(e);
        }
        if (url != null || title != null) {
            // D550: no match on a masked browser's withheld URL or title. Phase 8 security fix S172 (FE): judged afresh
            // before any regex runs (one bounded EDT read for the browsers with a view, only while a mask is
            // registered), so that a browser that became masked since the last judgement is never matched.
            rejudged(keyed, deadline);
        }
        List<Entry> candidates = new ArrayList<Entry>();
        for (Entry e : keyed) {
            if ((url != null || title != null) && e.masked) {
                continue;
            }
            if (url != null && !find(url, "by.url", safeUrl(e.browser()), regexDeadline)) {
                continue;
            }
            candidates.add(e);
        }
        if (title == null || candidates.isEmpty()) {
            return candidates;
        }
        Map<Entry, String> titles = titles(candidates, Math.min(TITLE_MS, remainingMs(deadline)));
        titlesRead.putAll(titles);
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : candidates) {
            String t = titles.get(e);
            if (t != null && find(title, "by.title", t, regexDeadline)) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * A bounded search (S46): {@code INVALID_PARAMS} with {@code data {param, regex, reason}} when it runs out of time,
     * recurses too deeply or meets a subject over 1 Mi characters; never the subject in the message.
     */
    private static boolean find(Pattern pattern, String param, String subject, long regexDeadline) {
        try {
            return BoundedRegex.find(pattern, subject, regexDeadline - System.nanoTime());
        } catch (BoundedRegex.TooSlow e) {
            throw Errors.invalid(e.describe(param, BoundedRegex.BUDGET_MS), Errors.data("param", param, "regex",
                    pattern.pattern(), "reason", e.reason()));
        }
    }

    /**
     * Judges the entries' {@link Entry#masked} flags afresh for this selection (phase8-contracts.md 5.2; Phase 8 security
     * fixes S168 and S172, FE): {@link BrowserIndex#rejudge}, the {@link BrowserMasks} rule on the component each adapter
     * reports now and one bounded EDT read of the {@code CaptureMask}s for the ones with a view, made only while a mask
     * is registered and the selection has time (a read that fails or has no time masks them). Used before a
     * {@code by: {url|title}} match and before the error data's summaries, so that they agree.
     */
    private void rejudged(List<Entry> entries, long deadline) {
        if (entries.isEmpty()) {
            return;
        }
        long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        index.rejudge(entries, Math.min(BrowserIndex.BUDGET_MS, Math.max(0, left)));
    }

    /** The single browser whose component is showing (see the class comment). */
    Entry byDefault(long budgetMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        BrowserIndex.Snapshot s = index.snapshot();
        boolean rebuilt = false;
        if (!s.built || s.ageMs() > BrowserIndex.STALE_MS || s.entries.isEmpty()) {
            s = index.rebuild(remainingMs(deadline));
            rebuilt = true;
        }
        List<Entry> showing = showing(s, deadline);
        if (showing.isEmpty() && !rebuilt) {
            s = index.rebuild(remainingMs(deadline));
            showing = showing(s, deadline);
        }
        if (showing.size() == 1) {
            return showing.get(0);
        }
        Map<Entry, String> none = Collections.emptyMap();
        if (showing.isEmpty()) {
            List<Entry> listed = open(s.entries);
            rejudged(listed, deadline);
            throw Errors.notFound("no browser is showing; name one with browser: <ref> or by: {...}",
                    Errors.data("browsers", summaries(listed, none)));
        }
        rejudged(showing, deadline);
        throw Errors.ambiguous(showing.size() + " browsers are showing; name one with browser: <ref> or by: {...}",
                Errors.data("candidates", summaries(showing, none)));
    }

    private List<Entry> showing(BrowserIndex.Snapshot s, long deadline) {
        List<Object> components = new ArrayList<Object>();
        Map<Object, Entry> owners = new IdentityHashMap<Object, Entry>();
        for (Entry e : s.entries) {
            if (isClosed(e.browser())) {
                continue;
            }
            Object c = safeComponent(e.browser());
            if (c != null) {
                components.add(c);
                owners.put(c, e);
            }
        }
        List<Entry> out = new ArrayList<Entry>();
        if (components.isEmpty()) {
            return out;
        }
        Map<Object, Boolean> showing;
        try {
            showing = walk.showing(components, Math.max(1, remainingMs(deadline)));
        } catch (Exception ex) {
            throw Errors.internal(null, "cannot read which browser is showing: " + ex);
        }
        for (Object c : components) {
            if (Boolean.TRUE.equals(showing.get(c))) {
                Entry e = owners.get(c);
                e.showing = true;
                out.add(e);
            } else {
                owners.get(c).showing = false;
            }
        }
        return out;
    }

    /** STALE_HANDLE for a browser that has closed (and {@code web.closed} once). */
    static void requireOpen(Entry e) {
        if (isClosed(e.browser())) {
            throw Errors.staleBrowser(e.ref, "has closed");
        }
    }

    /**
     * The read of a page's title: the title when it is a primitive string, cut at {@link WebLib#MAX_URL_CHARS} in the
     * page and checked by its primitive {@code length} afterwards (a page may replace {@code slice}), else "". Phase 7
     * security fix S131 (P4 4.6): a page chooses its title, never the size of what crosses the channel.
     */
    static final String TITLE = "(function(){var t;try{t=document.title;}catch(e){return \"\";}"
            + "if(typeof t!==\"string\"){return \"\";}if(t.length>" + WebLib.MAX_URL_CHARS + "){try{t=t.slice(0,"
            + WebLib.MAX_URL_CHARS + ");}catch(e){return \"\";}}return typeof t===\"string\"&&t.length<="
            + WebLib.MAX_URL_CHARS + "?t:\"\";})()";

    /** The titles of the given browsers that can evaluate, read concurrently within the bound; absent on failure. */
    static Map<Entry, String> titles(Collection<Entry> entries, long boundMs) {
        Map<Entry, CompletableFuture<String>> pending = new LinkedHashMap<Entry, CompletableFuture<String>>();
        for (Entry e : entries) {
            WebBrowser b = e.browser();
            if (!b.capabilities().contains(WebCapability.EVALUATE) || isClosed(b)) {
                continue;
            }
            try {
                pending.put(e, b.evaluate(WebEval.of(TITLE).timeoutMs(Math.max(1, boundMs)).build()));
            } catch (RuntimeException ex) {
                // no title for this one
            }
        }
        Map<Entry, String> out = new IdentityHashMap<Entry, String>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, boundMs));
        for (Map.Entry<Entry, CompletableFuture<String>> p : pending.entrySet()) {
            try {
                String json = p.getValue().get(Math.max(1, remainingMs(deadline)), TimeUnit.MILLISECONDS);
                // S131: through the channel check, as every page answer is.
                Object value = WebPrimitives.parse(p.getKey(), json);
                if (value instanceof String) {
                    out.put(p.getKey(), (String) value);
                }
            } catch (Exception ex) {
                p.getValue().cancel(false);
            }
        }
        return out;
    }

    /**
     * The compact description of a browser used in error data, in the key order of {@code web.browsers}:
     * {@code {ref, adapter, nativeId?, url, masked?, title?, closed, visibleInSwing, component?, capabilities, tag?}};
     * {@code title} only when the selection read it (null: not read), {@code capabilities} without a page read. A
     * masked entry (Phase 8, D550) says {@code url: ""} and {@code masked: true} and never carries a title.
     */
    static Map<String, Object> summary(Entry e, String title) {
        WebBrowser b = e.browser();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ref", e.ref);
        m.put("adapter", e.adapter.id());
        String nativeId = safeNativeId(b);
        if (nativeId != null) {
            m.put("nativeId", nativeId);
        }
        boolean masked = e.masked;
        if (masked) {
            m.put("url", "");
            m.put("masked", Boolean.TRUE);
        } else {
            m.put("url", WebLib.cut(safeUrl(b), WebLib.MAX_URL_CHARS));
        }
        if (title != null && !masked) {
            m.put("title", WebLib.cut(title, WebLib.MAX_URL_CHARS));
        }
        m.put("closed", Boolean.valueOf(isClosed(b)));
        m.put("visibleInSwing", Boolean.valueOf(e.showing));
        if (e.componentRef != null) {
            m.put("component", e.componentRef);
        }
        List<Object> caps = new ArrayList<Object>();
        Set<WebCapability> flags = safeCapabilities(b);
        for (WebCapability c : WebCapability.values()) {
            if (flags.contains(c)) {
                caps.add(c.name());
            }
        }
        m.put("capabilities", caps);
        if (e.tag != null) {
            m.put("tag", e.tag);
        }
        return m;
    }

    /** The summaries of the entries, with the titles the selection read. */
    static List<Object> summaries(List<Entry> entries, Map<Entry, String> titles) {
        List<Object> out = new ArrayList<Object>();
        for (Entry e : entries) {
            out.add(summary(e, titles.get(e)));
        }
        return out;
    }

    /** The entries whose browser has not closed. */
    static List<Entry> open(List<Entry> entries) {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : entries) {
            if (!isClosed(e.browser())) {
                out.add(e);
            }
        }
        return out;
    }

    private static Set<WebCapability> safeCapabilities(WebBrowser b) {
        try {
            Set<WebCapability> c = b.capabilities();
            return c == null ? Collections.<WebCapability>emptySet() : c;
        } catch (RuntimeException e) {
            return Collections.emptySet();
        }
    }

    private static Pattern pattern(JsonView by, String key) {
        String text = by.str(key);
        if (text == null) {
            return null;
        }
        try {
            return Pattern.compile(text);
        } catch (PatternSyntaxException e) {
            throw Errors.invalid("by." + key + " is not a valid regular expression: " + e.getDescription(),
                    Errors.data("param", "by." + key));
        }
    }

    static boolean isClosed(WebBrowser b) {
        try {
            return b.isClosed();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String safeUrl(WebBrowser b) {
        try {
            String u = b.url();
            return u == null ? "" : u;
        } catch (RuntimeException e) {
            return "";
        }
    }

    static String safeNativeId(WebBrowser b) {
        try {
            return b.nativeId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Object safeComponent(WebBrowser b) {
        try {
            return b.component();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long remainingMs(long deadlineNanos) {
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }
}
