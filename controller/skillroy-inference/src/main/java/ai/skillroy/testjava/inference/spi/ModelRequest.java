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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One request to a model (phase5-contracts.md 3.2).
 *
 * <p>{@code system} is the frozen prefix, first (the prompt catalogue, the tool definitions' instructions, the fixed
 * instructions); the last part marked {@link Content.Text#cacheBreakpoint()} is where the provider's cache breakpoint
 * goes. {@code messages} hold the per-call facts, last. {@code outputSchema} is a JSON Schema the answer must satisfy,
 * or null for free text. {@code tools} are offered with tool choice {@code auto} always. {@code metadata} is for the
 * ledger and the recordings' labels (scenario id, slot, purpose); it is never sent as prompt text.
 *
 * <p>Immutable (lists and maps are deep copies); safe to share between threads.
 *
 * @param purpose {@code resolve}, {@code triage}, {@code session}, {@code plan}, {@code summary}
 * @param modelClass which model, by role
 * @param effort how much the model may think
 * @param maxTokens the most the answer may take, at least 1
 * @param cache whether the frozen prefix should be cached, and for how long
 * @param system the system parts, in order; may be empty
 * @param messages the conversation, at least one message, starting with a user message
 * @param tools the tools offered; may be empty
 * @param outputSchema the answer's JSON Schema, or null
 * @param metadata labels for the ledger and recordings; never prompt text
 * @param providerTools provider-defined tool entries the gateway passes through unread (phase7-contracts.md 7.1,
 *     D318); may be empty; in the fingerprint only when non-empty
 */
public record ModelRequest(String purpose, ModelClass modelClass, Effort effort, int maxTokens, CacheHint cache,
                           List<Content> system, List<Message> messages, List<ToolSpec> tools,
                           Map<String, Object> outputSchema, Map<String, String> metadata,
                           List<ProviderTool> providerTools) {

    /** The metadata key of the scenario id. */
    public static final String META_SCENARIO = "scenarioId";
    /** The metadata key of the slot. */
    public static final String META_SLOT = "slot";
    /** The metadata key of the query kind. */
    public static final String META_KIND = "kind";

    /**
     * Copies everything.
     *
     * @throws IllegalArgumentException if the purpose is blank, {@code maxTokens} is below 1, there is no message,
     *     the first message is not a user message, a list holds a null or two tools share a name
     */
    public ModelRequest {
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("a model request names its purpose");
        }
        Objects.requireNonNull(modelClass, "modelClass");
        Objects.requireNonNull(effort, "effort");
        Objects.requireNonNull(cache, "cache");
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens is at least 1");
        }
        system = copy(system, "system part");
        messages = copy(messages, "message");
        tools = copy(tools, "tool");
        providerTools = copy(providerTools, "provider tool");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("a model request has at least one message");
        }
        if (messages.get(0).role() != Message.Role.USER) {
            throw new IllegalArgumentException("a model request's first message is a user message");
        }
        List<String> names = new ArrayList<>();
        for (ToolSpec tool : tools) {
            if (names.contains(tool.name())) {
                throw new IllegalArgumentException("tool " + tool.name() + " is offered twice");
            }
            names.add(tool.name());
        }
        outputSchema = outputSchema == null ? null : JsonLike.freezeMap(outputSchema);
        Map<String, String> meta = new LinkedHashMap<>();
        if (metadata != null) {
            for (Map.Entry<String, String> e : metadata.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    meta.put(e.getKey(), e.getValue());
                }
            }
        }
        metadata = Collections.unmodifiableMap(meta);
    }

    /** The Phase 5 shape: no provider tool. */
    public ModelRequest(String purpose, ModelClass modelClass, Effort effort, int maxTokens, CacheHint cache,
                        List<Content> system, List<Message> messages, List<ToolSpec> tools,
                        Map<String, Object> outputSchema, Map<String, String> metadata) {
        this(purpose, modelClass, effort, maxTokens, cache, system, messages, tools, outputSchema, metadata, List.of());
    }

    /**
     * A builder with the defaults {@link Effort#MEDIUM}, 4096 tokens, {@link CacheHint#SHORT}, no system part, no
     * tools and no schema.
     */
    public static Builder builder(String purpose, ModelClass modelClass) {
        return new Builder(purpose, modelClass);
    }

    /**
     * A stable fingerprint of what the model sees (phase5-contracts.md 4.2): the SHA-256, as 64 lower-case hex digits,
     * of the canonical JSON ({@link Prompts#fingerprintJson}) of the model class, the effort, the system parts, the
     * messages, the tools and the output schema. Not the purpose, {@code maxTokens}, {@code cache}, the metadata or a
     * part's cache breakpoint, so that a budget or a label change does not invalidate a recording; an image enters by
     * its SHA-256; an opaque part (a thinking block's signature) does not enter at all. Map key order never matters.
     */
    public String fingerprint() {
        return Prompts.hex(Prompts.canonical(Prompts.fingerprintJson(this)));
    }

    /** This request with another cache hint (what a batch round sets). */
    public ModelRequest withCache(CacheHint hint) {
        return new ModelRequest(purpose, modelClass, effort, maxTokens, hint, system, messages, tools, outputSchema,
                metadata, providerTools);
    }

    /** This request with these messages (a conversation that goes on). */
    public ModelRequest withMessages(List<Message> newMessages) {
        return new ModelRequest(purpose, modelClass, effort, maxTokens, cache, system, newMessages, tools,
                outputSchema, metadata, providerTools);
    }

    private static <E> List<E> copy(List<E> list, String what) {
        if (list == null) {
            return List.of();
        }
        for (E e : list) {
            if (e == null) {
                throw new IllegalArgumentException("a " + what + " is null");
            }
        }
        return List.copyOf(list);
    }

    /** Builds a {@link ModelRequest}. Not thread-safe. */
    public static final class Builder {

        private final String purpose;
        private final ModelClass modelClass;
        private Effort effort = Effort.MEDIUM;
        private int maxTokens = 4096;
        private CacheHint cache = CacheHint.SHORT;
        private final List<Content> system = new ArrayList<>();
        private final List<Message> messages = new ArrayList<>();
        private final List<ToolSpec> tools = new ArrayList<>();
        private final List<ProviderTool> providerTools = new ArrayList<>();
        private Map<String, Object> outputSchema;
        private final Map<String, String> metadata = new LinkedHashMap<>();

        private Builder(String purpose, ModelClass modelClass) {
            this.purpose = purpose;
            this.modelClass = modelClass;
        }

        public Builder effort(Effort value) {
            this.effort = value;
            return this;
        }

        public Builder maxTokens(int value) {
            this.maxTokens = value;
            return this;
        }

        public Builder cache(CacheHint value) {
            this.cache = value;
            return this;
        }

        /** Appends a system part. */
        public Builder system(Content part) {
            system.add(part);
            return this;
        }

        /** Appends a message. */
        public Builder message(Message message) {
            messages.add(message);
            return this;
        }

        /** Appends messages. */
        public Builder messages(List<Message> more) {
            messages.addAll(more);
            return this;
        }

        /** Appends a tool. */
        public Builder tool(ToolSpec tool) {
            tools.add(tool);
            return this;
        }

        /** Appends tools. */
        public Builder tools(List<ToolSpec> more) {
            tools.addAll(more);
            return this;
        }

        /** Appends a provider-defined tool entry (phase7-contracts.md 7.1). */
        public Builder providerTool(ProviderTool tool) {
            providerTools.add(tool);
            return this;
        }

        /** Appends provider-defined tool entries. */
        public Builder providerTools(List<ProviderTool> more) {
            providerTools.addAll(more);
            return this;
        }

        /** The answer's JSON Schema; null for free text. */
        public Builder outputSchema(Map<String, Object> schema) {
            this.outputSchema = schema;
            return this;
        }

        /** A metadata label; a null value removes the key. */
        public Builder metadata(String key, String value) {
            if (value == null) {
                metadata.remove(key);
            } else {
                metadata.put(key, value);
            }
            return this;
        }

        /** @throws IllegalArgumentException as the record's constructor */
        public ModelRequest build() {
            return new ModelRequest(purpose, modelClass, effort, maxTokens, cache, system, messages, tools,
                    outputSchema, metadata, providerTools);
        }
    }
}
