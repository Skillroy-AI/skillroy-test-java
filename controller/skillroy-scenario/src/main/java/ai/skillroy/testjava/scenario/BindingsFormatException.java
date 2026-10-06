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

package ai.skillroy.testjava.scenario;

/**
 * A file is not a bindings file of a version this code knows (phase2-contracts.md 9). {@code replay} reports it as
 * REBIND_NEEDED reason {@code UNREADABLE}; {@code diff-bindings} exits 2.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class BindingsFormatException extends Exception {

    private static final long serialVersionUID = 1L;

    /** @param message what is wrong, naming the place in the file */
    public BindingsFormatException(String message) {
        super(message);
    }
}
