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

package ai.skillroy.testjava.samples.lending;

import java.util.Locale;

/**
 * The desk's two editions (phase6-contracts.md 11.3, decision D146): one application jar, configured by the system
 * property {@code lending.edition}. The branch edition names a few things differently, so that a deployment variant
 * of the same desk needs a pack overlay: the role limit column is {@code role_cap.ceiling} (not {@code amount}), the
 * system maximum's setting is {@code return_ceiling} (not {@code max_return}), and the return path computes the
 * applicable limit through {@link ReturnCeilings#applicable} (not {@link AccessControl#returnLimit}). Everything else
 * is the same: the waiver engine, the slips, the prompts, the stored tables. Independent of the {@link Seed}.
 */
public enum Edition {
    /** The default edition. */
    STANDARD("amount", "max_return"),
    /** The branch edition. */
    BRANCH("ceiling", "return_ceiling");

    private final String limitColumn;
    private final String maxReturnSetting;

    Edition(String limitColumn, String maxReturnSetting) {
        this.limitColumn = limitColumn;
        this.maxReturnSetting = maxReturnSetting;
    }

    /** The column of {@code role_cap} that holds a role's limit in this edition. */
    public String limitColumn() {
        return limitColumn;
    }

    /** The {@code desk_setting} name of the system maximum return in this edition. */
    public String maxReturnSetting() {
        return maxReturnSetting;
    }

    /** The lower-case name, as {@code lending.edition} takes it and {@code app.state} reports it. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * @param text {@code "standard"} or {@code "branch"} (any case); null or blank means {@link #STANDARD}
     * @throws IllegalArgumentException for any other value
     */
    public static Edition parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return STANDARD;
        }
        String t = text.trim().toUpperCase(Locale.ROOT);
        for (Edition edition : values()) {
            if (edition.name().equals(t)) {
                return edition;
            }
        }
        throw new IllegalArgumentException("unknown lending edition '" + text + "' (expected standard or branch)");
    }
}
