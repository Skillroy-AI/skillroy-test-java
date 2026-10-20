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
 * (phase6-contracts.md section 4.3), and to a Byteman rule's bindings. Since Phase 8 also applied to every scalar of
 * every event payload before the bus keeps it, as {@code mask("event:" + topic, column, value)} where the column is
 * the map keys from the payload root joined with {@code '.'} (a list element adds no segment) and the value is a
 * {@code String}, {@code Long}, {@code Double} or {@code Boolean}, never null (phase8-contracts.md section 5.1). A
 * policy written for rows checks {@code sourceId}: one that ignores it also masks the event fields named like the
 * columns it masks, and must not mask a field that a wait or the controller matches on (a prompt's {@code id}, an
 * acknowledgement's {@code inputId}, {@code web.tap}'s {@code name}, {@code web.ready}'s {@code signal}).
 *
 * <p>Called on agent worker threads, or, for a {@code CAPTURE} hook or a Byteman rule's bindings, on the application
 * thread that called the hooked method; possibly concurrently. Also called on the thread that publishes an event,
 * which may be the EDT, an application thread, an adapter's thread or an agent worker, once per scalar of every
 * event. It must be thread-safe and fast, and must never block, wait on the EDT or take a lock of the application.
 * A policy that throws is counted as a hook failure (on the event route, in {@code agent.info.events.maskFailures})
 * and the value is replaced by {@code <redacted>}.
 */
public interface MaskPolicy {

    /** The value to return in place of {@code value} (return it unchanged to leave it as is). */
    Object mask(String sourceId, String column, Object value);
}
