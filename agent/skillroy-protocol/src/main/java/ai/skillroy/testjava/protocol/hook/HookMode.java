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
 * What a hook does when its method is called (phase6-contracts.md section 3.4). The mode lives in the hook engine's
 * registry, not in the bytecode: every mode uses the same advice, so a stub armed or cleared needs no
 * retransformation.
 */
public enum HookMode {
    /** One event per call with the duration and, if the body threw, the throwable's class and message; no values. */
    OBSERVE,
    /** As {@link #OBSERVE}, plus encoded, masked and bounded copies of the arguments, receiver or result asked for. */
    CAPTURE,
    /** While a value is armed ({@code hook.stub}) the body is skipped and the value returned; else as OBSERVE. */
    STUB
}
