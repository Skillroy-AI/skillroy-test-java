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

package ai.skillroy.testjava.agent.byteman;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.protocol.hook.ClassPattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The module's options (phase6-contracts.md 3.7): {@code byteman.jar} (no default, no environment fallback) and the
 * bounds it shares with the hooks module, {@code hooks.maxClasses} (the most classes one rule may retransform),
 * {@code hooks.maxClassBytes}, {@code hooks.maxMethods}, {@code hooks.detachBudgetMs} and
 * {@code hooks.capture.maxBytes} (the input budget of one {@code rule.fired}'s encoding), each with the hooks module's
 * range (3.7 as built, D176; fix wave, L1-F4: {@code hooks.maxClasses} is 1 to 1024 here too). A value that is not a
 * number or out of its range fails the module's install, naming the key (S14: a FAILED record, never fatal).
 *
 * <p>{@code hooks.deny} (phase8-contracts.md section 6.5 item 1, A81): the class patterns the hooks module reads under
 * the same key, so that one option rules whichever module is loaded; comma-separated, trimmed, empty items dropped, at
 * most {@link ClassPattern#MAX_PER_SOURCE}; an invalid pattern fails the install naming the key.
 */
final class Settings {

    static final String BYTEMAN_JAR = "byteman.jar";
    static final String MAX_CLASSES = "hooks.maxClasses";
    static final String MAX_CLASS_BYTES = "hooks.maxClassBytes";
    static final String MAX_METHODS = "hooks.maxMethods";
    static final String DETACH_BUDGET_MS = "hooks.detachBudgetMs";
    static final String CAPTURE_MAX_BYTES = "hooks.capture.maxBytes";
    static final String DENY = "hooks.deny";

    final String bytemanJar;
    final int maxClasses;
    final long maxClassBytes;
    final int maxMethods;
    final long detachBudgetMs;
    final int captureMaxBytes;
    /** The patterns of {@code hooks.deny}, in the order given; unmodifiable, never null. */
    final List<String> deny;

    Settings(String bytemanJar, int maxClasses, long maxClassBytes, int maxMethods, long detachBudgetMs,
             int captureMaxBytes) {
        this(bytemanJar, maxClasses, maxClassBytes, maxMethods, detachBudgetMs, captureMaxBytes,
                Collections.<String>emptyList());
    }

    Settings(String bytemanJar, int maxClasses, long maxClassBytes, int maxMethods, long detachBudgetMs,
             int captureMaxBytes, List<String> deny) {
        this.deny = Collections.unmodifiableList(new ArrayList<String>(deny));
        this.bytemanJar = bytemanJar;
        this.maxClasses = maxClasses;
        this.maxClassBytes = maxClassBytes;
        this.maxMethods = maxMethods;
        this.detachBudgetMs = detachBudgetMs;
        this.captureMaxBytes = captureMaxBytes;
    }

    /** The defaults of 3.7 with no Byteman jar. */
    static Settings defaults() {
        return new Settings(null, 64, 262144L, 2000, 8000L, 16384);
    }

    /**
     * Reads the options through {@link AgentContext#option} (the start-time snapshot, then the live system property).
     *
     * @throws IllegalArgumentException naming the key, for a value that is not a number or out of its range
     */
    static Settings read(AgentContext ctx) {
        String jar = ctx.option(BYTEMAN_JAR);
        return new Settings(jar == null || jar.trim().isEmpty() ? null : jar.trim(),
                (int) number(ctx, MAX_CLASSES, 64, 1, 1024),
                number(ctx, MAX_CLASS_BYTES, 262144, 1024, 16L * 1024 * 1024),
                (int) number(ctx, MAX_METHODS, 2000, 1, 65535),
                number(ctx, DETACH_BUDGET_MS, 8000, 1000, 9000),
                (int) number(ctx, CAPTURE_MAX_BYTES, 16384, 1024, 1024 * 1024),
                patterns(ctx.option(DENY)));
    }

    /**
     * The class patterns of {@code hooks.deny}: trimmed, empty items dropped, distinct, each checked (the hooks module
     * reads the key by the same rule).
     *
     * @throws IllegalArgumentException {@code option hooks.deny: pattern <i> <violation>}, or more than 64 patterns
     */
    static List<String> patterns(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        int i = 0;
        for (String item : text.split(",", -1)) {
            String p = item.trim();
            if (p.isEmpty()) {
                continue;
            }
            String violation = ClassPattern.violation(p);
            if (violation != null) {
                throw new IllegalArgumentException("option " + DENY + ": pattern " + i + " " + violation);
            }
            if (!out.contains(p)) {
                out.add(p);
            }
            i++;
        }
        if (out.size() > ClassPattern.MAX_PER_SOURCE) {
            throw new IllegalArgumentException("option " + DENY + ": more than " + ClassPattern.MAX_PER_SOURCE
                    + " patterns (" + out.size() + ")");
        }
        return Collections.unmodifiableList(out);
    }

    private static long number(AgentContext ctx, String key, long def, long min, long max) {
        String text = ctx.option(key);
        if (text == null || text.trim().isEmpty()) {
            return def;
        }
        long value;
        try {
            value = Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("option " + key + " is not a number: '" + text + "'");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("option " + key + " is " + value + ", outside " + min + " to " + max);
        }
        return value;
    }
}
