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

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Typed content parts (phase5-contracts.md 3.2, B7, B16): a request's system parts and messages, and a response, are
 * lists of these, so that a capture can travel without a signature change. Sealed: a provider maps each.
 *
 * <p>Every part is immutable (JSON-like values are deep copies; an image's bytes are copied in and out); safe to
 * share between threads.
 */
public sealed interface Content permits Content.Text, Content.Image, Content.ToolUse, Content.ToolResult,
        Content.Opaque {

    /**
     * Text. In a request's system list, {@code cacheBreakpoint} marks where the provider's cache breakpoint goes (the
     * last part so marked; phase5-contracts.md 4.1).
     *
     * @param text the text, never null
     * @param cacheBreakpoint true on a system part that ends the cached prefix
     */
    record Text(String text, boolean cacheBreakpoint) implements Content {

        /** @throws NullPointerException if the text is null */
        public Text {
            Objects.requireNonNull(text, "text");
        }

        /** A text part without a cache breakpoint. */
        public static Text of(String text) {
            return new Text(text, false);
        }

        /** A text part that ends the cached prefix. */
        public static Text cached(String text) {
            return new Text(text, true);
        }
    }

    /**
     * An image. Reserved for Phase 7: the Claude gateway sends it (as base64), the recorded gateway hashes it, the mask
     * treats it as personal data until phase5-contracts.md 6.2 says otherwise, and no Phase 5 request builder creates
     * one.
     *
     * @param mediaType {@code image/png} or {@code image/jpeg}
     * @param bytes the image as given (copied)
     * @param caption a caption, or null
     */
    record Image(String mediaType, byte[] bytes, String caption) implements Content {

        /** The media types an image part may have. */
        public static final java.util.Set<String> MEDIA_TYPES = java.util.Set.of("image/png", "image/jpeg");

        /** @throws IllegalArgumentException for another media type or no bytes */
        public Image {
            if (mediaType == null || !MEDIA_TYPES.contains(mediaType)) {
                throw new IllegalArgumentException("an image part is image/png or image/jpeg");
            }
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("an image part needs its bytes");
            }
            bytes = bytes.clone();
        }

        /** A copy of the bytes. */
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        /** {@code sha256:<hex>} of the bytes: what a fingerprint and a recording hold in place of the image. */
        public String sha256() {
            return Prompts.sha256(bytes);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Image other && mediaType.equals(other.mediaType) && Arrays.equals(bytes, other.bytes)
                    && Objects.equals(caption, other.caption);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mediaType, Arrays.hashCode(bytes), caption);
        }

        /** The media type, size and hash; never the bytes. */
        @Override
        public String toString() {
            return "Image[" + mediaType + ", " + bytes.length + " bytes, " + sha256() + "]";
        }
    }

    /**
     * A tool call the model made (in a response) or that the caller echoes back (in a request's history).
     *
     * @param id the provider's id of the call, which the {@link ToolResult} names
     * @param name the tool's name
     * @param input the arguments, JSON-like (copied)
     */
    record ToolUse(String id, String name, Map<String, Object> input) implements Content {

        /** @throws NullPointerException if the id or name is null */
        public ToolUse {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            input = JsonLike.freezeMap(input);
        }
    }

    /**
     * The caller's answer to a {@link ToolUse}.
     *
     * @param toolUseId the id of the call it answers
     * @param content a text or a JSON-like value (copied); the Claude gateway sends JSON-like content as JSON text
     * @param error true when the tool failed
     */
    record ToolResult(String toolUseId, Object content, boolean error) implements Content {

        /** @throws NullPointerException if the id is null */
        public ToolResult {
            Objects.requireNonNull(toolUseId, "toolUseId");
            content = JsonLike.freeze(content);
        }
    }

    /**
     * A provider's own block that a caller must echo back unchanged (a thinking block with its signature, a fallback
     * block): never built by a caller from its own data, never rendered as text, kept by {@link ModelResponse#parts()}
     * and sent back by a loop that continues the conversation; outside the fingerprint (signatures differ per call).
     * BS13 F-2, F-7.
     *
     * @param provider the provider that produced it ({@code "claude"})
     * @param type the block's type as the provider names it ({@code thinking}, {@code fallback})
     * @param raw the block's raw JSON-like form (copied)
     */
    record Opaque(String provider, String type, Object raw) implements Content {

        private static final Pattern TYPE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

        /** @throws IllegalArgumentException if the provider is blank or the type is not a short name */
        public Opaque {
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException("an opaque part names its provider");
            }
            if (type == null || !TYPE.matcher(type).matches()) {
                throw new IllegalArgumentException("an opaque part's type is a short name");
            }
            raw = JsonLike.freeze(raw);
        }
    }
}
