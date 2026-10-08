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

/**
 * Thrown by a {@link WebPath} that cannot serve one call (phase4-contracts.md section 6.1): the executor then uses
 * the agent path for that call and records a {@code web.fallback} line. Failures of the call itself are reported
 * with the agent's error codes, never with this exception.
 */
public final class WebPathException extends Exception {

    public enum Kind { UNSUPPORTED }

    private final Kind kind;

    public WebPathException(Kind kind, String reason) {
        super(reason);
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static WebPathException unsupported(String reason) {
        return new WebPathException(Kind.UNSUPPORTED, reason);
    }
}
