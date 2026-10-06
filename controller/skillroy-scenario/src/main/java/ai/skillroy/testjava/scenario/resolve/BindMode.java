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

import java.util.Locale;

/**
 * How a run binds its query slots and oracles (phase2-contracts.md 8.3): {@link #REPLAY} reads a reviewed bindings
 * file and re-runs the verifiers, never a resolver; {@link #LIVE} resolves now; {@link #APPROVE} resolves now, asks an
 * approver once and writes the bindings file. The name {@code re-resolve} is reserved for Phase 5.
 */
public enum BindMode {
    /** Use the committed bindings file; resolvers never run. The default. */
    REPLAY,
    /** Resolve, ask the approver, write the bindings file. */
    APPROVE,
    /** Resolve now; write nothing unless asked to. */
    LIVE;

    /** The mode's name as options write it: {@code replay}, {@code approve}, {@code live}. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The mode an option names (any case).
     *
     * @throws IllegalArgumentException for {@code re-resolve} ("not part of this version") or any other text
     */
    public static BindMode parse(String text) {
        String t = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if ("re-resolve".equals(t)) {
            throw new IllegalArgumentException("mode re-resolve is not part of this version");
        }
        for (BindMode mode : values()) {
            if (mode.label().equals(t)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown mode '" + text + "'; modes: replay, approve, live");
    }
}
