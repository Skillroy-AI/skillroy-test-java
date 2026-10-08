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

package ai.skillroy.testjava.scenario.web;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * The scenario key {@code web} (phase4-contracts.md sections 6.1 and 7.2): the path policy, the default browser
 * selector (the {@code by:} object every web verb uses unless it names its own), the frozen-target policy, the
 * attach deadline of the CDP path and whether CDP tracing is written to the failure evidence.
 */
public record WebSettings(Path path, Map<String, Object> defaultBrowser, FrozenTargetPolicy frozenTargets,
                          Duration attachDeadline, boolean trace) {

    /** {@code auto}: CDP when a path with id {@code cdp} is present, {@code web.cdp} is OK and the attach succeeds. */
    public enum Path { AUTO, CDP, AGENT }

    /** {@code auto}, no default browser, {@code fail}, 30 seconds, no trace. */
    public static final WebSettings DEFAULT = new WebSettings(Path.AUTO, null, FrozenTargetPolicy.FAIL,
            Duration.ofSeconds(30), false);

    public WebSettings {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        if (frozenTargets == null) {
            throw new IllegalArgumentException("frozenTargets is required");
        }
        if (attachDeadline == null || attachDeadline.isNegative() || attachDeadline.isZero()) {
            throw new IllegalArgumentException("attachDeadline must be positive");
        }
        defaultBrowser = defaultBrowser == null || defaultBrowser.isEmpty() ? null
                : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(defaultBrowser));
    }
}
