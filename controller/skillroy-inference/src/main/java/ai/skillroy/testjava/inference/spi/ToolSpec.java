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

import java.util.Map;
import java.util.regex.Pattern;

/**
 * A tool the model may call (phase5-contracts.md 3.2, 9.3). The input schema is meant to be strict (every object
 * {@code additionalProperties: false} with every property required, optional values nullable), which the Claude
 * gateway sends with {@code strict: true}; a free-form schema (an object without declared properties, the projection of
 * a verb without an {@code argsSchema}) cannot be strict and is sent without it. {@code readOnly} and
 * {@code destructive} mirror the primitive descriptor and become the MCP hints.
 *
 * <p>Immutable (the schema is a deep copy); safe to share between threads.
 *
 * @param name {@code [a-z0-9_]{1,64}}
 * @param description what the tool does, for the model
 * @param inputSchema a JSON Schema of the input, JSON-like
 * @param readOnly true when calling the tool does not change the target
 * @param destructive true for a destructive primitive (never offered to a model; the flag exists for the hint)
 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema, boolean readOnly,
                       boolean destructive) {

    /** What a tool name must match (the provider forbids dots, control-channel F22). */
    public static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,64}");

    /**
     * Copies the schema.
     *
     * @throws IllegalArgumentException if the name does not match {@link #NAME} or the schema is missing
     */
    public ToolSpec {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a tool name matches [a-z0-9_]{1,64}, found " + name);
        }
        if (inputSchema == null) {
            throw new IllegalArgumentException("tool " + name + " needs an input schema");
        }
        description = description == null ? "" : description;
        inputSchema = JsonLike.freezeMap(inputSchema);
    }
}
