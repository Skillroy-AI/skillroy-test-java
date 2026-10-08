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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thrown by {@link WebPath#open} when the path's attach failed (phase4-contracts.md sections 5.3 and 8.1): the
 * executor reports {@code ERROR} / {@code ENVIRONMENT} with the detail when the scenario pinned the path, and falls
 * back to the agent path, recording the reason, under the {@code auto} policy. A path that is merely not usable
 * (no endpoint, for example) returns a reason from {@code open} instead of throwing.
 */
public final class WebPathOpenException extends Exception {

    public enum Kind { UNREACHABLE, TIMEOUT, PROTOCOL, FROZEN_TARGETS }

    private final Kind kind;
    private final Map<String, Object> detail;

    /** {@code detail} is written into the failure detail as given (for {@code FROZEN_TARGETS}: {@code frozenTargets}). */
    public WebPathOpenException(Kind kind, String message, Map<String, Object> detail) {
        super(message);
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        this.kind = kind;
        this.detail = detail == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(detail));
    }

    public Kind kind() {
        return kind;
    }

    public Map<String, Object> detail() {
        return detail;
    }
}
