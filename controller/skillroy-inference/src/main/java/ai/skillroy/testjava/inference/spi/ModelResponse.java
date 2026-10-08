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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A model's answer (phase5-contracts.md 3.2). A refusal is an answer ({@link StopReason#REFUSAL}, the provider's
 * category in {@link Usage#note()}), never an exception; a caller treats it as "no answer" and records it.
 *
 * <p>Immutable (the structured answer is a deep copy); safe to share between threads.
 *
 * @param parts what the model returned, in order, opaque provider blocks included
 * @param stop why the model stopped
 * @param usage what the call cost
 * @param structured the parsed JSON-like answer when the request had an output schema, else null
 */
public record ModelResponse(List<Content> parts, StopReason stop, Usage usage, Object structured) {

    /** Why the model stopped. */
    public enum StopReason {
        /** It finished its answer. */
        END_TURN,
        /** It called tools; the caller's loop answers them. */
        TOOL_USE,
        /** It ran out of {@code maxTokens}: a success, but the answer is cut. */
        MAX_TOKENS,
        /** It declined to answer. */
        REFUSAL
    }

    /** Copies the parts and the structured answer. */
    public ModelResponse {
        parts = parts == null ? List.of() : List.copyOf(parts);
        Objects.requireNonNull(stop, "stop");
        Objects.requireNonNull(usage, "usage");
        structured = JsonLike.freeze(structured);
    }

    /** The text parts joined; {@code ""} when there is none. */
    public String text() {
        StringBuilder out = new StringBuilder();
        for (Content part : parts) {
            if (part instanceof Content.Text t) {
                out.append(t.text());
            }
        }
        return out.toString();
    }

    /** The tool calls, in order. */
    public List<Content.ToolUse> toolUses() {
        List<Content.ToolUse> out = new ArrayList<>();
        for (Content part : parts) {
            if (part instanceof Content.ToolUse u) {
                out.add(u);
            }
        }
        return List.copyOf(out);
    }
}
