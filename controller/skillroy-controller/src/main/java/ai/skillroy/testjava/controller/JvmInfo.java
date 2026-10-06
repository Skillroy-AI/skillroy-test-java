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

package ai.skillroy.testjava.controller;

/**
 * One JVM of the current OS user, as listed by {@link AttachSession#listJvms()}: what a person or the CLI needs to
 * pick an attach target. A plain value so that it carries no attach API type.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param id the attach API's id of the JVM (the pid on HotSpot)
 * @param displayName the main class or jar and its arguments as the JVM reports them, may be empty
 */
public record JvmInfo(String id, String displayName) {

    /** The id as a pid, or -1 when it is not numeric. */
    public long pid() {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
