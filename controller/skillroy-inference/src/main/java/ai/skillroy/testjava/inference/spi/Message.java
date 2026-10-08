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

package ai.skillroy.testjava.inference.spi;

import java.util.List;
import java.util.Objects;

/**
 * One turn of a conversation (phase5-contracts.md 3.2): what comes from the target or a person goes into {@code USER}
 * messages, labelled (6.2); {@code ASSISTANT} messages echo what the model answered, opaque parts included.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param role who speaks
 * @param parts what is said, at least one part
 */
public record Message(Role role, List<Content> parts) {

    /** Who speaks. */
    public enum Role {
        /** The caller: the per-call facts, a person's request, tool results. */
        USER,
        /** The model, echoed back in a request's history. */
        ASSISTANT
    }

    /**
     * Copies the parts.
     *
     * @throws IllegalArgumentException if there is no part or a part is null
     */
    public Message {
        Objects.requireNonNull(role, "role");
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("a message has at least one part");
        }
        for (Content part : parts) {
            Objects.requireNonNull(part, "a message part is null");
        }
        parts = List.copyOf(parts);
    }

    /** A user message of these parts. */
    public static Message user(Content... parts) {
        return new Message(Role.USER, List.of(parts));
    }

    /** A user message of one text. */
    public static Message user(String text) {
        return new Message(Role.USER, List.of(Content.Text.of(text)));
    }

    /** An assistant message of these parts (a response's parts, echoed). */
    public static Message assistant(List<Content> parts) {
        return new Message(Role.ASSISTANT, parts);
    }
}
