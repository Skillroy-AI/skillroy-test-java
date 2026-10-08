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

package ai.skillroy.testjava.cdp;

import java.util.List;

/**
 * The CDP path could not probe or attach (phase4-contracts.md sections 5.1 and 8.1). Every kind is
 * {@code ERROR} / {@code ENVIRONMENT} for the run; {@link Kind#FROZEN_TARGETS} carries the frozen targets.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class CdpException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why the probe or the attach failed. */
    public enum Kind {
        /** {@code /json/version} (or {@code /json/list}, or the browser's WebSocket) could not be reached. */
        UNREACHABLE,
        /** {@code connectOverCDP} did not complete within the connect deadline; the Playwright driver was closed. */
        TIMEOUT,
        /** At least one page target did not answer the probe within its deadline; {@link #targets()} lists them. */
        FROZEN_TARGETS,
        /** The endpoint answered, but not in the shape of the protocol, or Playwright failed in another way. */
        PROTOCOL
    }

    private final Kind kind;
    private final transient List<TargetProbe.Target> targets;

    /**
     * @param targets the targets the failure concerns (the frozen ones for {@code FROZEN_TARGETS}); null means none
     */
    public CdpException(Kind kind, String message, List<TargetProbe.Target> targets) {
        this(kind, message, targets, null);
    }

    /**
     * @param targets the targets the failure concerns; null means none
     */
    public CdpException(Kind kind, String message, List<TargetProbe.Target> targets, Throwable cause) {
        super(message, cause);
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        this.kind = kind;
        this.targets = targets == null ? List.of() : List.copyOf(targets);
    }

    public Kind kind() {
        return kind;
    }

    /** Unmodifiable, never null. */
    public List<TargetProbe.Target> targets() {
        return targets == null ? List.of() : targets;
    }
}
