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

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the duration values scenarios use ({@code timeouts.step}, {@code within}): {@code "10s"}, {@code "500ms"},
 * {@code "2m"}, or a whole number of milliseconds. Public so that pack verbs read durations the same way.
 *
 * <p>Stateless; safe from any thread.
 */
public final class Durations {

    private static final Pattern TEXT = Pattern.compile("(\\d{1,12})\\s*(ms|s|m)");

    private Durations() {
    }

    /**
     * @param value a String such as {@code "10s"}, or an integral Number of milliseconds
     * @param what the key being parsed, for the error message
     * @return the duration in milliseconds, always positive
     * @throws ScenarioException if the value is not a positive duration in one of the accepted forms
     */
    public static long toMillis(Object value, String what) throws ScenarioException {
        long ms;
        if (value instanceof Long || value instanceof Integer || value instanceof Short) {
            ms = ((Number) value).longValue();
        } else if (value instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) {
            ms = d.longValue();
        } else if (value instanceof String text) {
            String trimmed = text.trim().toLowerCase(Locale.ROOT);
            if (trimmed.chars().allMatch(Character::isDigit) && !trimmed.isEmpty() && trimmed.length() <= 15) {
                ms = Long.parseLong(trimmed);
            } else {
                Matcher m = TEXT.matcher(trimmed);
                if (!m.matches()) {
                    throw invalid(value, what);
                }
                long amount = Long.parseLong(m.group(1));
                ms = switch (m.group(2)) {
                    case "ms" -> amount;
                    case "s" -> Math.multiplyExact(amount, 1000L);
                    default -> Math.multiplyExact(amount, 60_000L);
                };
            }
        } else {
            throw invalid(value, what);
        }
        if (ms <= 0) {
            throw new ScenarioException(what + " must be positive: " + value);
        }
        return ms;
    }

    private static ScenarioException invalid(Object value, String what) {
        return new ScenarioException(what + ": expected a duration such as \"10s\", \"500ms\", \"2m\" or a number of"
                + " milliseconds, found " + (value instanceof String ? "\"" + value + "\"" : String.valueOf(value)));
    }
}
