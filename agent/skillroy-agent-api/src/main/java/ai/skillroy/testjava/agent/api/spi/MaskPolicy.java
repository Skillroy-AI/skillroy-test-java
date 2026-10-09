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

package ai.skillroy.testjava.agent.api.spi;

/**
 * Applied to every value data.query returns before it leaves the JVM. Keeps sensitive column values (passwords,
 * personal data) out of controller logs and reports. Since Phase 6 also applied to every encoded scalar a
 * {@code CAPTURE} hook copies out of a call, as {@code mask("hook.<id>", "arg<i>" | "self" | "return", value)}
 * (phase6-contracts.md section 4.3), and to a Byteman rule's bindings.
 *
 * <p>Called on agent worker threads, or, for a {@code CAPTURE} hook or a Byteman rule's bindings, on the application
 * thread that called the hooked method; possibly concurrently. It must be thread-safe, cheap, and must never block or
 * take a lock of the application. A policy that throws is counted as a hook failure and the value is replaced by
 * {@code <redacted>}.
 */
public interface MaskPolicy {

    /** The value to return in place of {@code value} (return it unchanged to leave it as is). */
    Object mask(String sourceId, String column, Object value);
}
