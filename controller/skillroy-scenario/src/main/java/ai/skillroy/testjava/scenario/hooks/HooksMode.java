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

package ai.skillroy.testjava.scenario.hooks;

import java.util.Locale;

/**
 * The run-level hooks switch (phase6-contracts.md 6.1, decision D127): {@code RunOptions.hooks}, the CLI's
 * {@code --hooks auto|on|off} and the JUnit glue's {@code -Dskillroy.hooks}. Never a scenario key: the committed
 * scenarios run unchanged with hooks on and off.
 */
public enum HooksMode {

    /**
     * Install the pack's catalogue when the target's {@code hook.engine} is {@code OK}; skip, recorded, when the pack
     * has no catalogue or the engine is absent or unavailable (the default).
     */
    AUTO,
    /**
     * Install the pack's catalogue; a pack without one, or a target without a usable engine, is PRECONDITION_FAILED.
     */
    ON,
    /** Install nothing (recorded). */
    OFF;

    /**
     * The mode named {@code text} ({@code auto}, {@code on}, {@code off}, any case, surrounding blanks ignored); null
     * and blank mean {@link #AUTO}.
     *
     * @throws IllegalArgumentException for any other text; the message lists the three values
     */
    public static HooksMode parse(String text) {
        if (text == null || text.isBlank()) {
            return AUTO;
        }
        switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "auto":
                return AUTO;
            case "on":
                return ON;
            case "off":
                return OFF;
            default:
                throw new IllegalArgumentException("hooks must be auto, on or off, found \"" + text.strip() + "\"");
        }
    }

    /** The lower-case name, as {@code run.jsonl}'s {@code hooks} record and the CLI write it. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
