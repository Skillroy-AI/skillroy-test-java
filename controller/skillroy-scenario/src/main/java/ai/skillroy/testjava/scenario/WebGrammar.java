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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.scenario.web.FrozenTargetPolicy;
import ai.skillroy.testjava.scenario.web.WebSettings;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The pieces of the web grammar (phase4-contracts.md 7.1, 7.2) that the scenario loader, the base web verbs and the
 * plan reader share: the values of {@code web.path} and {@code web.frozenTargets}, and the {@code by} object that
 * selects a browser ({@code {url}}, {@code {tag}}, {@code {title}} or {@code {nativeId}}, P4 4.2).
 *
 * <p>Stateless; safe from any thread.
 */
final class WebGrammar {

    /** The keys of a {@code by} object, in documentation order; exactly one is given. */
    static final List<String> BY_KEYS = List.of("url", "tag", "title", "nativeId");

    /** Why {@code frozenTargets: close} is refused (decision D52); the caller names the key first. */
    static final String CLOSE_REFUSED = "close is not available in Phase 4: closing a browser the application owns"
            + " can break it (decision D52); use fail or reload";

    private WebGrammar() {
    }

    /** {@code auto}, {@code cdp} or {@code agent} in any case; null for anything else. */
    static WebSettings.Path path(String text) {
        if (text == null) {
            return null;
        }
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "auto" -> WebSettings.Path.AUTO;
            case "cdp" -> WebSettings.Path.CDP;
            case "agent" -> WebSettings.Path.AGENT;
            default -> null;
        };
    }

    /** {@code fail}, {@code reload} or {@code close} in any case; null for anything else. */
    static FrozenTargetPolicy frozenTargets(String text) {
        if (text == null) {
            return null;
        }
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "fail" -> FrozenTargetPolicy.FAIL;
            case "reload" -> FrozenTargetPolicy.RELOAD;
            case "close" -> FrozenTargetPolicy.CLOSE;
            default -> null;
        };
    }

    /**
     * Null when {@code value} is a {@code by} object: a mapping with exactly one of {@code url}, {@code tag},
     * {@code title}, {@code nativeId}, whose value is a non-empty string; {@code url} and {@code title} are regular
     * expressions (checked unless they hold a {@code $reference}, which is resolved when the scenario runs).
     * Otherwise the complete reason, naming {@code name} ({@code 'web.browser' must be ...}).
     */
    static String byProblem(String name, Object value) {
        if (!(value instanceof Map<?, ?> map) || map.size() != 1) {
            return "'" + name + "' must be a mapping with exactly one of url, tag, title, nativeId, found "
                    + ScenarioLoader.describe(value) + (value instanceof Map<?, ?> m ? " with keys " + m.keySet() : "");
        }
        Map.Entry<?, ?> only = map.entrySet().iterator().next();
        String key = String.valueOf(only.getKey());
        if (!BY_KEYS.contains(key)) {
            return "'" + name + "' must have one of the keys url, tag, title, nativeId, found '" + key + "'";
        }
        if (!(only.getValue() instanceof String text) || text.isBlank()) {
            return "'" + name + "." + key + "' must be a non-empty string, found "
                    + ScenarioLoader.describe(only.getValue());
        }
        if ((key.equals("url") || key.equals("title")) && !References.containsReference(text)) {
            try {
                Pattern.compile(text);
            } catch (PatternSyntaxException e) {
                return "'" + name + "." + key + "' is not a valid regular expression: " + e.getDescription();
            }
        }
        return null;
    }
}
