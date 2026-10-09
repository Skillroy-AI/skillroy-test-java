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

import ai.skillroy.testjava.protocol.text.BoundedRegex;
import ai.skillroy.testjava.scenario.web.WebPathException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The browser selection of a web call on the CDP path (phase4-contracts.md sections 4.2 and 5.2):
 * <ul>
 *   <li>{@code browser: <ref>}: the ref's {@code cdpTargetId} in the agent's {@code web.browsers};</li>
 *   <li>{@code by: {url: <regex>}} or {@code by: {title: <regex>}}: the regular expression (Java syntax, found
 *       anywhere in the text, as {@code RegExp.test} does) on the pages' own {@code url()} or {@code title()};</li>
 *   <li>{@code by: {tag: <text>}} or {@code by: {nativeId: <text>}}: the agent's {@code web.browsers} entry with that
 *       tag or native id, then its {@code cdpTargetId};</li>
 *   <li>neither: the single browser of the agent's list that is showing in Swing ({@code visibleInSwing}).</li>
 * </ul>
 * {@code browser} wins over {@code by}. Closed browsers never match. Whenever the CDP path cannot pick exactly one
 * page (nothing or several match, a ref without a target id, a target without a page, a selector it does not know)
 * it throws {@link WebPathException} ({@code UNSUPPORTED}): the executor then sends the call to the agent, which
 * answers with its own {@code NOT_FOUND}, {@code AMBIGUOUS} or {@code STALE_HANDLE} and their data, so the two paths
 * never disagree on selection errors.
 *
 * <p>The regular expression of {@code by.url} or {@code by.title} is searched under the S24 budget (security fix S46,
 * phase4-contracts.md 4.2): {@link BoundedRegex}, at most {@value BoundedRegex#BUDGET_MS} ms for the whole pick and
 * never past the call's deadline, the subject at most 1 Mi characters. A search that runs out throws
 * {@link RegexStopped}, which the path answers as {@code INVALID_PARAMS} (not {@code UNSUPPORTED}: the agent would
 * spin on the same pattern).
 *
 * <p>Immutable.
 */
final class Selection {

    /** How the agent's browsers are read; implemented by the path with its caches. */
    interface Source {

        /** The agent's entry for a ref (refreshing the cache once when the ref is unknown), or null. */
        AgentBrowser byRef(String ref) throws Exception;

        /** The agent's current list. */
        List<AgentBrowser> current() throws Exception;
    }

    /**
     * A {@code by.url} or {@code by.title} search that ran out of its budget, recursed too deeply, or met a subject
     * over 1 Mi characters (security fix S46); the path answers it as {@code INVALID_PARAMS} with {@code data
     * {param, reason}}. Never carries the subject.
     */
    static final class RegexStopped extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String param;
        private final String reason;

        RegexStopped(String param, String reason, String message) {
            super(message, null, false, false);
            this.param = param;
            this.reason = reason;
        }

        /** {@code by.url} or {@code by.title}. */
        String param() {
            return param;
        }

        /** {@link BoundedRegex#REGEX_TOO_SLOW} or {@link BoundedRegex#TEXT_TOO_LONG}. */
        String reason() {
            return reason;
        }
    }

    private enum Kind { TARGET, URL, TITLE }

    private final Kind kind;
    private final String targetId;
    private final String ref;
    private final Pattern pattern;
    /** The call's deadline ({@link System#nanoTime()}), bounding the regex search; 0 for none. */
    private final long deadlineNanos;

    private Selection(Kind kind, String targetId, String ref, Pattern pattern, long deadlineNanos) {
        this.kind = kind;
        this.targetId = targetId;
        this.ref = ref;
        this.pattern = pattern;
        this.deadlineNanos = deadlineNanos;
    }

    /** A selection of a known target. */
    static Selection target(String targetId, String ref) {
        return new Selection(Kind.TARGET, targetId, ref, null, 0L);
    }

    /** Resolves the call's {@code browser} or {@code by} parameter as far as it can be without the pages. */
    static Selection resolve(Map<String, Object> params, Source source) throws Exception {
        return resolve(params, source, 0L);
    }

    /**
     * As {@link #resolve(Map, Source)}, with the call's deadline ({@link System#nanoTime()}; 0 for none), which bounds
     * a {@code by.url} or {@code by.title} search as well as the S24 budget.
     */
    static Selection resolve(Map<String, Object> params, Source source, long deadlineNanos) throws Exception {
        Object browser = params.get("browser");
        if (browser != null) {
            if (!(browser instanceof String ref) || ref.isBlank()) {
                throw WebPathException.unsupported("browser must be a browser ref");
            }
            AgentBrowser b = source.byRef(ref);
            if (b == null) {
                throw WebPathException.unsupported("browser " + ref + " is not in the agent's web.browsers");
            }
            return of(b, "browser " + ref);
        }
        Object by = params.get("by");
        if (by != null) {
            if (!(by instanceof Map<?, ?> m) || m.size() != 1) {
                throw WebPathException.unsupported("by must hold exactly one of url, title, tag, nativeId");
            }
            Map.Entry<?, ?> e = m.entrySet().iterator().next();
            String key = String.valueOf(e.getKey());
            Object value = e.getValue();
            switch (key) {
                case "url", "title" -> {
                    if (!(value instanceof String regex)) {
                        throw WebPathException.unsupported("by." + key + " must be a regular expression");
                    }
                    try {
                        return new Selection(key.equals("url") ? Kind.URL : Kind.TITLE, null, null,
                                Pattern.compile(regex), deadlineNanos);
                    } catch (PatternSyntaxException x) {
                        throw WebPathException.unsupported("by." + key + " is not a regular expression: "
                                + x.getDescription());
                    }
                }
                case "tag", "nativeId" -> {
                    if (value == null) {
                        throw WebPathException.unsupported("by." + key + " must not be null");
                    }
                    String wanted = String.valueOf(value);
                    List<AgentBrowser> hits = new ArrayList<>();
                    for (AgentBrowser b : source.current()) {
                        if (!b.closed() && wanted.equals(key.equals("tag") ? b.tag() : b.nativeId())) {
                            hits.add(b);
                        }
                    }
                    return single(hits, "by." + key + " " + wanted);
                }
                default -> throw WebPathException.unsupported("by." + key + " is not a selection the CDP path knows");
            }
        }
        List<AgentBrowser> showing = new ArrayList<>();
        for (AgentBrowser b : source.current()) {
            if (!b.closed() && b.visibleInSwing()) {
                showing.add(b);
            }
        }
        return single(showing, "the default browser (the one showing in Swing)");
    }

    /**
     * The target id of the one page this selection picks among {@code pages} (target id to page).
     *
     * @throws WebPathException if no page or several pages match
     * @throws RegexStopped if a {@code by.url} or {@code by.title} search runs out of its budget (S46)
     */
    <P> String pick(Map<String, P> pages, Function<P, String> url, Function<P, String> title)
            throws WebPathException {
        if (kind == Kind.TARGET) {
            if (!pages.containsKey(targetId)) {
                throw WebPathException.unsupported("no CDP page has the target id " + targetId
                        + (ref == null ? "" : " of browser " + ref));
            }
            return targetId;
        }
        String param = "by." + (kind == Kind.URL ? "url" : "title");
        long start = System.nanoTime();
        long until = start + TimeUnit.MILLISECONDS.toNanos(BoundedRegex.BUDGET_MS);
        if (deadlineNanos != 0L && deadlineNanos - until < 0) {
            until = deadlineNanos;
        }
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, P> e : pages.entrySet()) {
            String text = kind == Kind.URL ? url.apply(e.getValue()) : title.apply(e.getValue());
            if (text == null) {
                continue;
            }
            try {
                if (BoundedRegex.find(pattern, text, until - System.nanoTime())) {
                    hits.add(e.getKey());
                }
            } catch (BoundedRegex.TooSlow x) {
                throw new RegexStopped(param, x.reason(), x.describe(param, BoundedRegex.BUDGET_MS));
            }
        }
        String what = param + " " + pattern.pattern();
        if (hits.isEmpty()) {
            throw WebPathException.unsupported("no CDP page matches " + what);
        }
        if (hits.size() > 1) {
            throw WebPathException.unsupported(hits.size() + " CDP pages match " + what);
        }
        return hits.get(0);
    }

    /** The ref when the selection named or found one, else null (a {@code url}/{@code title} selection). */
    String ref() {
        return ref;
    }

    /** The target id of a target selection, else null. */
    String targetId() {
        return targetId;
    }

    /** The call's deadline ({@link System#nanoTime()}) this selection was resolved with; 0 for none. */
    long deadlineNanos() {
        return deadlineNanos;
    }

    @Override
    public String toString() {
        return kind == Kind.TARGET ? "target " + targetId + (ref == null ? "" : " (" + ref + ")")
                : (kind == Kind.URL ? "url ~ " : "title ~ ") + pattern.pattern();
    }

    private static Selection single(List<AgentBrowser> hits, String what) throws WebPathException {
        if (hits.isEmpty()) {
            throw WebPathException.unsupported("no browser matches " + what);
        }
        if (hits.size() > 1) {
            throw WebPathException.unsupported(hits.size() + " browsers match " + what);
        }
        return of(hits.get(0), what);
    }

    private static Selection of(AgentBrowser b, String what) throws WebPathException {
        if (b.closed()) {
            throw WebPathException.unsupported("browser " + b.ref() + " is closed");
        }
        if (b.cdpTargetId() == null) {
            throw WebPathException.unsupported("browser " + b.ref() + " (" + what + ") has no CDP target id");
        }
        return target(b.cdpTargetId(), b.ref());
    }
}
