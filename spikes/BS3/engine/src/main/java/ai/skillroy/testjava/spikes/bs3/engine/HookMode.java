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

package ai.skillroy.testjava.spikes.bs3.engine;

/** The three hook modes of design 2.6, plus OFF (installed, handler does nothing). A handler setting, not bytecode. */
public enum HookMode {
    /** Enter and exit events with the arguments and the return value or throwable, rendered safely. */
    OBSERVE,
    /** As OBSERVE, and the argument and result objects are kept for later reads. */
    CAPTURE,
    /** The original body is skipped and the handler's value is returned. */
    STUB,
    /** Installed, but the handler returns at once. */
    OFF
}
