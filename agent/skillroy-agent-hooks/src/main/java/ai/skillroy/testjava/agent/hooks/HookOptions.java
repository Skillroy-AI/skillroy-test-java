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


package ai.skillroy.testjava.agent.hooks;

import ai.skillroy.testjava.agent.api.AgentContext;

import java.io.File;

/**
 * The {@code hooks.*} agent options (phase6-contracts.md section 3.7). A value out of its range or not a number fails
 * the module's install, which the core records as FAILED naming the key (S14: never fatal). {@code hooks.spyJar} is
 * read live ({@link AgentContext#option}), so a host or a test can name the jar after the start.
 *
 * <p>Immutable; safe to share between threads.
 */
final class HookOptions {

    static final String SPY_JAR = "hooks.spyJar";
    static final String MAX_CLASS_BYTES = "hooks.maxClassBytes";
    static final String MAX_METHODS = "hooks.maxMethods";
    static final String MAX_CLASSES = "hooks.maxClasses";
    static final String DETACH_BUDGET_MS = "hooks.detachBudgetMs";
    static final String CAPTURE_MAX_BYTES = "hooks.capture.maxBytes";
    /** The spy jar's name at the home's root (phase6-contracts.md section 2.1). */
    static final String SPY_JAR_NAME = "skillroy-agent-spy.jar";

    final String spyJar;
    /**
     * True when {@code hooks.spyJar} named the jar (an operator's path), false for the home's own: a named jar must
     * pass the shared jar rule before the append (P2-L1-F5), the home's is trusted as the home is.
     */
    final boolean spyJarNamed;
    final long maxClassBytes;
    final int maxMethods;
    final int maxClasses;
    final long detachBudgetMs;
    final int captureMaxBytes;

    private HookOptions(String spyJar, boolean spyJarNamed, long maxClassBytes, int maxMethods, int maxClasses,
                        long detachBudgetMs, int captureMaxBytes) {
        this.spyJar = spyJar;
        this.spyJarNamed = spyJarNamed;
        this.maxClassBytes = maxClassBytes;
        this.maxMethods = maxMethods;
        this.maxClasses = maxClasses;
        this.detachBudgetMs = detachBudgetMs;
        this.captureMaxBytes = captureMaxBytes;
    }

    /**
     * Reads and checks the options.
     *
     * @throws IllegalArgumentException naming the key, for a value that is not a number or out of its range
     */
    static HookOptions read(AgentContext ctx) {
        String spyJar = ctx.option(SPY_JAR);
        boolean named = spyJar != null && !spyJar.trim().isEmpty();
        if (!named) {
            String home = ctx.options().get("home");
            spyJar = home == null || home.trim().isEmpty() ? null : new File(home.trim(), SPY_JAR_NAME).getPath();
        } else {
            spyJar = spyJar.trim();
        }
        return new HookOptions(spyJar, named,
                number(ctx, MAX_CLASS_BYTES, 262144L, 1024L, 16L * 1024 * 1024),
                (int) number(ctx, MAX_METHODS, 2000L, 1L, 65535L),
                (int) number(ctx, MAX_CLASSES, 64L, 1L, 1024L),
                number(ctx, DETACH_BUDGET_MS, 8000L, 1000L, 9000L),
                (int) number(ctx, CAPTURE_MAX_BYTES, 16384L, 1024L, 1024L * 1024));
    }

    private static long number(AgentContext ctx, String key, long dflt, long min, long max) {
        String text = ctx.option(key);
        if (text == null || text.trim().isEmpty()) {
            return dflt;
        }
        long value;
        try {
            value = Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("option " + key + " is not a number: " + text.trim());
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("option " + key + " is out of range [" + min + ", " + max + "]: "
                    + value);
        }
        return value;
    }
}
