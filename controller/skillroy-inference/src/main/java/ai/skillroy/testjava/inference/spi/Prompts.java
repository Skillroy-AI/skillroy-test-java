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

import ai.skillroy.testjava.protocol.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical JSON for fingerprints and recordings (phase5-contracts.md 3.3, 4.2): the JSON-like form of every part,
 * message and tool, rendered with {@link Json#writeCanonical} (keys sorted recursively), so that the same request
 * gives the same text on any machine and in any map order. Stateless; safe from any thread.
 */
public final class Prompts {

    private Prompts() {
    }

    /** The canonical JSON text of a JSON-like value (keys sorted at every depth, no insignificant whitespace). */
    public static String canonical(Object jsonLike) {
        return Json.writeCanonical(jsonLike);
    }

    /** {@code sha256:<hex>} of the bytes. */
    public static String sha256(byte[] bytes) {
        return "sha256:" + HexFormat.of().formatHex(digest(bytes));
    }

    /** The SHA-256 of a text's UTF-8 bytes as 64 lower-case hex digits. */
    public static String hex(String text) {
        return HexFormat.of().formatHex(digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }

    /**
     * The JSON-like form of a part as a fingerprint and a recording hold it: {@code {type: text, text}},
     * {@code {type: image, mediaType, sha256, caption}} (never the bytes), {@code {type: tool_use, id, name, input}},
     * {@code {type: tool_result, toolUseId, content, error}}, {@code {type: opaque, provider, blockType, raw}}. A text's
     * cache breakpoint is not part of it (a cache concern, like {@link CacheHint}). Phase 7 (phase7-contracts.md 7.2):
     * a tool call's and a tool result's {@code toolsetName} is written only when it is set, and a result's parts list as
     * the list of its parts' forms (an image by its hash), so that no earlier fingerprint changes.
     */
    public static Map<String, Object> part(Content part) {
        Map<String, Object> json = new LinkedHashMap<>();
        switch (part) {
            case Content.Text t -> {
                json.put("type", "text");
                json.put("text", t.text());
            }
            case Content.Image i -> {
                json.put("type", "image");
                json.put("mediaType", i.mediaType());
                json.put("sha256", i.sha256());
                json.put("caption", i.caption());
            }
            case Content.ToolUse u -> {
                json.put("type", "tool_use");
                json.put("id", u.id());
                json.put("name", u.name());
                json.put("input", u.input());
                if (u.toolsetName() != null) {
                    json.put("toolsetName", u.toolsetName());
                }
            }
            case Content.ToolResult r -> {
                json.put("type", "tool_result");
                json.put("toolUseId", r.toolUseId());
                List<Content> nested = r.parts();
                json.put("content", nested == null ? r.content() : parts(nested, true));
                json.put("error", r.error());
                if (r.toolsetName() != null) {
                    json.put("toolsetName", r.toolsetName());
                }
            }
            case Content.Opaque o -> {
                json.put("type", "opaque");
                json.put("provider", o.provider());
                json.put("blockType", o.type());
                json.put("raw", o.raw());
            }
        }
        return json;
    }

    /** The parts' JSON-like forms; opaque parts left out when {@code withOpaque} is false. */
    public static List<Object> parts(List<Content> parts, boolean withOpaque) {
        List<Object> out = new ArrayList<>(parts.size());
        for (Content part : parts) {
            if (withOpaque || !(part instanceof Content.Opaque)) {
                out.add(part(part));
            }
        }
        return out;
    }

    /**
     * A part read back from {@link #part}; an image cannot be (its bytes are not kept), so a result's parts list is
     * read back as the list of its parts' forms.
     */
    public static Content partFromJson(Map<?, ?> json) {
        Object type = json.get("type");
        return switch (String.valueOf(type)) {
            case "text" -> Content.Text.of(string(json, "text"));
            case "tool_use" -> new Content.ToolUse(string(json, "id"), string(json, "name"), map(json.get("input")),
                    optionalString(json, "toolsetName"));
            case "tool_result" -> new Content.ToolResult(string(json, "toolUseId"), json.get("content"),
                    Boolean.TRUE.equals(json.get("error")), optionalString(json, "toolsetName"));
            case "opaque" -> new Content.Opaque(string(json, "provider"), string(json, "blockType"), json.get("raw"));
            default -> throw new IllegalArgumentException("a part of type " + type + " cannot be read back");
        };
    }

    /** {@code {role, parts}} with every part, opaque ones included when {@code withOpaque}. */
    public static Map<String, Object> message(Message message, boolean withOpaque) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("role", message.role().name());
        json.put("parts", parts(message.parts(), withOpaque));
        return json;
    }

    /** {@code {name, description, inputSchema, readOnly, destructive}}. */
    public static Map<String, Object> tool(ToolSpec tool) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", tool.name());
        json.put("description", tool.description());
        json.put("inputSchema", tool.inputSchema());
        json.put("readOnly", tool.readOnly());
        json.put("destructive", tool.destructive());
        return json;
    }

    /**
     * A provider tool's JSON-like form as a fingerprint and a recording hold it: {@code {provider, type, raw}}
     * (phase7-contracts.md 7.1).
     */
    public static Map<String, Object> providerTool(ProviderTool tool) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("provider", tool.provider());
        json.put("type", tool.type());
        json.put("raw", tool.raw());
        return json;
    }

    /**
     * The width and the height in pixels of a PNG (its {@code IHDR}) or a JPEG (its first start-of-frame marker), or
     * null when the bytes are neither or are cut short (phase7-contracts.md 7.5: what the budget estimate's image tokens
     * are counted from). Reads at most the first 64 KiB of a JPEG.
     */
    public static int[] imageSize(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes.length >= 24 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[12] == 'I' && bytes[13] == 'H' && bytes[14] == 'D' && bytes[15] == 'R') {
            int w = int32(bytes, 16);
            int h = int32(bytes, 20);
            return w > 0 && h > 0 ? new int[] {w, h} : null;
        }
        if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            int i = 2;
            int limit = Math.min(bytes.length, 65_536);
            while (i + 9 < limit) {
                if ((bytes[i] & 0xFF) != 0xFF) {
                    return null;
                }
                int marker = bytes[i + 1] & 0xFF;
                int length = ((bytes[i + 2] & 0xFF) << 8) | (bytes[i + 3] & 0xFF);
                boolean startOfFrame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8
                        && marker != 0xCC;
                if (startOfFrame) {
                    int h = ((bytes[i + 5] & 0xFF) << 8) | (bytes[i + 6] & 0xFF);
                    int w = ((bytes[i + 7] & 0xFF) << 8) | (bytes[i + 8] & 0xFF);
                    return w > 0 && h > 0 ? new int[] {w, h} : null;
                }
                if (length < 2) {
                    return null;
                }
                i += 2 + length;
            }
        }
        return null;
    }

    private static int int32(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    /**
     * The tokens the provider counts for an image of this size: {@code ceil(w / 28) * ceil(h / 28)}
     * (phase7-contracts.md 7.5, D324; BS12: a 1024 x 768 capture counts 1,039, the formula gives 1,036); 0 for an image
     * whose size cannot be read.
     */
    public static long imageTokens(byte[] bytes) {
        int[] size = imageSize(bytes);
        if (size == null) {
            return 0;
        }
        return (long) Math.ceil(size[0] / 28.0) * (long) Math.ceil(size[1] / 28.0);
    }

    /**
     * What a fingerprint is taken over: {@code {modelClass, effort, system, messages, tools, outputSchema}}, opaque
     * parts left out, and {@code providerTools} when the request has any (phase7-contracts.md 7.1, D318).
     */
    public static Map<String, Object> fingerprintJson(ModelRequest request) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("modelClass", request.modelClass().name());
        json.put("effort", request.effort().name());
        json.put("system", parts(request.system(), false));
        List<Object> messages = new ArrayList<>();
        for (Message m : request.messages()) {
            messages.add(message(m, false));
        }
        json.put("messages", messages);
        List<Object> tools = new ArrayList<>();
        for (ToolSpec t : request.tools()) {
            tools.add(tool(t));
        }
        json.put("tools", tools);
        json.put("outputSchema", request.outputSchema());
        if (!request.providerTools().isEmpty()) {
            List<Object> provided = new ArrayList<>();
            for (ProviderTool t : request.providerTools()) {
                provided.add(providerTool(t));
            }
            json.put("providerTools", provided);
        }
        return json;
    }

    private static String optionalString(Map<?, ?> json, String key) {
        return json.get(key) instanceof String s ? s : null;
    }

    private static String string(Map<?, ?> json, String key) {
        Object value = json.get(key);
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("a part needs the text '" + key + "'");
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("a tool call's input is a mapping");
        }
        return (Map<String, Object>) value;
    }
}
