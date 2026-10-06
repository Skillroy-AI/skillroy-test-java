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

package ai.skillroy.testjava.scenario.resolve;

import java.util.List;
import java.util.Locale;

/**
 * How a slot's value was obtained. A scenario restricts the strategies of a query slot with the tokens
 * {@code sql}, {@code java}, {@code llm-sql}, {@code llm-reason} and {@code llm-vision} (phase2-contracts.md 3.1);
 * Phase 2 builds only the tier (a) strategies, the others are reserved for the inference module (Phase 5).
 */
public enum Strategy {
    /** A literal value written in the scenario. */
    FIXTURE(null),
    /** Committed SQL in the pack ({@code sql}). */
    CANONICAL_SQL("sql"),
    /** Committed Java in the pack ({@code java}). */
    CANONICAL_JAVA("java"),
    /** Model-written SQL (Phase 5, {@code llm-sql}). */
    LLM_SQL("llm-sql"),
    /** Model reasoning over read data (Phase 5, {@code llm-reason}). */
    LLM_REASON("llm-reason"),
    /** Model reading of the screen (Phase 5, {@code llm-vision}). */
    LLM_VISION("llm-vision"),
    /** Taken from a bindings file; reported in a run's own {@code bindings.json}, never written into the file. */
    REPLAY(null);

    /** The scenario tokens, in documentation order. */
    public static final List<String> TOKENS = List.of("sql", "java", "llm-sql", "llm-reason", "llm-vision");

    private final String token;

    Strategy(String token) {
        this.token = token;
    }

    /** The scenario token ({@code sql}, …), or null for {@link #FIXTURE} and {@link #REPLAY}. */
    public String token() {
        return token;
    }

    /**
     * The tier: {@link Tier#A} for the canonical strategies and fixtures, {@link Tier#B} for {@link #LLM_SQL},
     * {@link Tier#C} for {@link #LLM_REASON} and {@link #LLM_VISION}; null for {@link #REPLAY}, which replays a
     * recorded value whose own strategy is kept in the bindings file.
     */
    public Tier tier() {
        return switch (this) {
            case FIXTURE, CANONICAL_SQL, CANONICAL_JAVA -> Tier.A;
            case LLM_SQL -> Tier.B;
            case LLM_REASON, LLM_VISION -> Tier.C;
            case REPLAY -> null;
        };
    }

    /** True for the strategies that need a model (Phase 5). */
    public boolean needsModel() {
        return this == LLM_SQL || this == LLM_REASON || this == LLM_VISION;
    }

    /** The strategy of a scenario token (any case), or null when the token is not one of {@link #TOKENS}. */
    public static Strategy ofToken(String token) {
        if (token == null) {
            return null;
        }
        String t = token.strip().toLowerCase(Locale.ROOT);
        for (Strategy s : values()) {
            if (t.equals(s.token)) {
                return s;
            }
        }
        return null;
    }
}
