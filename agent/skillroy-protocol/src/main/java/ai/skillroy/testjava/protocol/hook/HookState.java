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

package ai.skillroy.testjava.protocol.hook;

/**
 * The health of an installed hook (phase6-contracts.md section 3.6, D123), computed on {@code hook.health} from the
 * engine's ledger, its registry and {@code Spy}'s counters; never by a thread of its own.
 */
public enum HookState {
    /** Installed (its classes retransformed) and not fired since. */
    INSTALLED,
    /** At least one call reached the handler since the install. */
    FIRING,
    /** A hooked class's loader has been collected. */
    STALE,
    /** The handler threw for this hook, a mask policy threw while encoding it, or a retransform or restore failed. */
    ERROR
}
