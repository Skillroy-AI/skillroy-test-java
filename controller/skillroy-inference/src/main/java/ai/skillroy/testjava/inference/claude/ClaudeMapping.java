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

package ai.skillroy.testjava.inference.claude;

import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.spi.CacheHint;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.Effort;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.InferenceException.Kind;
import ai.skillroy.testjava.inference.spi.JsonLike;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.ModelResponse.StopReason;
import ai.skillroy.testjava.inference.spi.ToolSpec;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Claude mapping of phase5-contracts.md 4.1, as BS13 corrected it: a {@link ModelRequest} to the SDK's typed
 * {@link MessageCreateParams} (the manual {@code OutputConfig} form for effort and format, the effort for the Opus and
 * Sonnet classes only, strict tools with tool choice {@code auto} unless the request's tools hold more union-typed
 * parameters than the provider compiles ({@link #MAX_STRICT_UNION_PARAMETERS}), the cache marker on the last system
 * part marked as a breakpoint, the refusal fallback as an extra body property and the beta header), and a response read
 * tolerantly from its JSON form (every block the SDK knows or not: text and tool use as parts, everything else as an
 * {@link Content.Opaque} part; the stop category as a string; the cache writes split by time to live), never through
 * {@code validate()} or a visitor.
 *
 * <p>Stateless; safe from any thread.
 */
final class ClaudeMapping {

    /** The provider id. */
    static final String PROVIDER = "claude";
    /** The beta header value of the server-side refusal fallback (BS13 F-7). */
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    static final JsonMapper MAPPER = ObjectMappers.jsonMapper();

    /** What a dated snapshot id adds to the id asked for: {@code -} and the eight digits of a date. */
    private static final Pattern SNAPSHOT_SUFFIX = Pattern.compile("-[0-9]{8}");
    /** The longest provider identifier kept (a response id, a request id, a model id; P2-L5-F3). */
    static final int ID_MAX = 200;
    /** The longest provider word kept (a stop reason, a refusal category, an error type; P2-L5-F3). */
    static final int WORD_MAX = 100;

    /**
     * The most parameters with a union type the tools of one request may hold and still go out strict. The provider
     * refused more (2026-10-08, a live session request with the lending pack's verbs as strict tools: HTTP 400
     * invalid_request_error "Schemas contains too many parameters with union types (20 parameters with type arrays or
     * anyOf). This causes exponential compilation cost. Reduce the number of nullable or union-typed parameters
     * (limit: 16 parameters with unions)."); above it every tool of the request goes out without {@code strict}.
     */
    static final int MAX_STRICT_UNION_PARAMETERS = 16;

    /** How deep the union count walks a tool's schema. */
    private static final int SCHEMA_DEPTH = 64;

    private ClaudeMapping() {
    }

    // ------------------------------------------------------------------------------------------------ requests

    /**
     * The SDK params of a request. {@code output_config.effort} goes out for the Opus and Sonnet classes only: the
     * Haiku class's model refuses the field (HTTP 400 "This model does not support the effort parameter", 4.1's
     * {@code Effort} row as verified live), so its request carries the format alone, and no {@code output_config} at
     * all without an output schema; the request's effort stays part of its fingerprint either way.
     *
     * @param model the model id the settings name for the request's class
     * @param fallbacks true: the refusal fallback in its {@code default} form (never in a batch)
     * @throws InferenceException {@code UNSUPPORTED} for an image while images are off, {@code INVALID_REQUEST} for a
     *     system part that is not text or an opaque part of another provider
     */
    static MessageCreateParams params(ModelRequest request, String model, boolean fallbacks, boolean images)
            throws InferenceException {
        MessageCreateParams.Builder b = MessageCreateParams.builder().model(model).maxTokens(request.maxTokens());
        List<TextBlockParam> system = system(request);
        if (!system.isEmpty()) {
            b.systemOfTextBlockParams(system);
        }
        for (Message m : request.messages()) {
            List<ContentBlockParam> blocks = new ArrayList<>();
            for (Content part : m.parts()) {
                blocks.add(block(part, images));
            }
            b.addMessage(MessageParam.builder().role(m.role() == Message.Role.USER ? MessageParam.Role.USER
                    : MessageParam.Role.ASSISTANT).contentOfBlockParams(blocks).build());
        }
        boolean withEffort = request.modelClass() != ModelClass.HAIKU_CLASS;
        if (withEffort || request.outputSchema() != null) {
            OutputConfig.Builder output = OutputConfig.builder();
            if (withEffort) {
                output.effort(effort(request.effort()));
            }
            if (request.outputSchema() != null) {
                JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
                request.outputSchema().forEach((k, v) -> schema.putAdditionalProperty(k, JsonValue.from(v)));
                output.format(JsonOutputFormat.builder().schema(schema.build()).build());
            }
            b.outputConfig(output.build());
        }
        if (!request.tools().isEmpty()) {
            boolean strictAllowed = unionParameters(request.tools()) <= MAX_STRICT_UNION_PARAMETERS;
            for (ToolSpec tool : request.tools()) {
                b.addTool(tool(tool, strictAllowed));
            }
            b.toolChoice(ToolChoiceAuto.builder().build());
        }
        if (fallbacks) {
            b.putAdditionalHeader("anthropic-beta", FALLBACK_BETA);
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return b.build();
    }

    /** The request body as JSON-like data (what the batch and count-tokens bodies are derived from). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> body(MessageCreateParams params) {
        return MAPPER.convertValue(params._body(), LinkedHashMap.class);
    }

    private static List<TextBlockParam> system(ModelRequest request) throws InferenceException {
        int breakpoint = -1;
        for (int i = 0; i < request.system().size(); i++) {
            if (!(request.system().get(i) instanceof Content.Text t)) {
                throw new InferenceException(Kind.INVALID_REQUEST, "a system part is text");
            }
            if (t.cacheBreakpoint()) {
                breakpoint = i;
            }
        }
        List<TextBlockParam> out = new ArrayList<>();
        for (int i = 0; i < request.system().size(); i++) {
            Content.Text t = (Content.Text) request.system().get(i);
            TextBlockParam.Builder text = TextBlockParam.builder().text(t.text());
            if (i == breakpoint && request.cache() != CacheHint.NONE) {
                CacheControlEphemeral.Builder cache = CacheControlEphemeral.builder();
                if (request.cache() == CacheHint.LONG) {
                    cache.ttl(CacheControlEphemeral.Ttl.TTL_1H);
                }
                text.cacheControl(cache.build());
            }
            out.add(text.build());
        }
        return out;
    }

    private static ContentBlockParam block(Content part, boolean images) throws InferenceException {
        return switch (part) {
            case Content.Text t -> ContentBlockParam.ofText(TextBlockParam.builder().text(t.text()).build());
            case Content.Image i -> {
                if (!images) {
                    throw new InferenceException(Kind.UNSUPPORTED, "an image part is not sent while images are off"
                            + " (inference.yaml images: false)");
                }
                yield ContentBlockParam.ofImage(ImageBlockParam.builder().source(Base64ImageSource.builder()
                        .data(Base64.getEncoder().encodeToString(i.bytes()))
                        .mediaType("image/png".equals(i.mediaType()) ? Base64ImageSource.MediaType.IMAGE_PNG
                                : Base64ImageSource.MediaType.IMAGE_JPEG).build()).build());
            }
            case Content.ToolUse u -> {
                ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
                u.input().forEach((k, v) -> input.putAdditionalProperty(k, JsonValue.from(v)));
                yield ContentBlockParam.ofToolUse(ToolUseBlockParam.builder().id(u.id()).name(u.name())
                        .input(input.build()).build());
            }
            case Content.ToolResult r -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(r.toolUseId())
                    .content(r.content() instanceof String s ? s : Json.write(r.content() == null ? Map.of()
                            : r.content()))
                    .isError(r.error()).build());
            case Content.Opaque o -> {
                if (!PROVIDER.equals(o.provider())) {
                    throw new InferenceException(Kind.INVALID_REQUEST, "an opaque part of provider " + o.provider()
                            + " cannot be sent to " + PROVIDER);
                }
                yield MAPPER.convertValue(o.raw(), ContentBlockParam.class);
            }
        };
    }

    private static OutputConfig.Effort effort(Effort effort) {
        return switch (effort) {
            case LOW -> OutputConfig.Effort.LOW;
            case MEDIUM -> OutputConfig.Effort.MEDIUM;
            case HIGH -> OutputConfig.Effort.HIGH;
        };
    }

    /**
     * A tool as the provider gets it: strict when {@code strictAllowed} and its schema can be strict, not strict when
     * it cannot (a free-form object), and without the {@code strict} field at all when strict tools are not allowed for
     * the request ({@link #unionParameters}); the schema is sent as it is either way.
     */
    static Tool tool(ToolSpec spec, boolean strictAllowed) {
        Tool.Builder tool = Tool.builder().name(spec.name()).description(spec.description())
                .inputSchema(MAPPER.convertValue(spec.inputSchema(), Tool.InputSchema.class));
        if (strictAllowed) {
            tool.strict(strict(spec.inputSchema()));
        }
        return tool.build();
    }

    /**
     * How many parameters of these tools take a union, the provider's count against {@link
     * #MAX_STRICT_UNION_PARAMETERS}: a property, at any depth of a tool's input schema (under {@code properties},
     * {@code items}, {@code anyOf}, {@code oneOf}, {@code allOf}, {@code additionalProperties}, {@code $defs} or
     * {@code definitions}), whose schema has {@code anyOf} or {@code oneOf} or a {@code type} that is an array.
     */
    static int unionParameters(List<ToolSpec> tools) {
        int count = 0;
        for (ToolSpec tool : tools) {
            count += unionParameters(tool.inputSchema(), 0);
        }
        return count;
    }

    private static int unionParameters(Object schema, int depth) {
        if (!(schema instanceof Map<?, ?> map) || depth > SCHEMA_DEPTH) {
            return 0;
        }
        int count = 0;
        if (map.get("properties") instanceof Map<?, ?> properties) {
            for (Object property : properties.values()) {
                if (property instanceof Map<?, ?> p && (p.containsKey("anyOf") || p.containsKey("oneOf")
                        || p.get("type") instanceof List<?>)) {
                    count++;
                }
                count += unionParameters(property, depth + 1);
            }
        }
        for (String key : List.of("items", "additionalProperties")) {
            Object sub = map.get(key);
            if (sub instanceof List<?> list) {
                for (Object item : list) {
                    count += unionParameters(item, depth + 1);
                }
            } else {
                count += unionParameters(sub, depth + 1);
            }
        }
        for (String key : List.of("anyOf", "oneOf", "allOf")) {
            if (map.get(key) instanceof List<?> branches) {
                for (Object branch : branches) {
                    count += unionParameters(branch, depth + 1);
                }
            }
        }
        for (String key : List.of("$defs", "definitions")) {
            if (map.get(key) instanceof Map<?, ?> definitions) {
                for (Object definition : definitions.values()) {
                    count += unionParameters(definition, depth + 1);
                }
            }
        }
        return count;
    }

    /**
     * True when a schema satisfies the provider's strict mode: every object has {@code additionalProperties: false}
     * and requires every property it declares, recursively through properties, items and {@code anyOf}.
     */
    static boolean strict(Object schema) {
        if (!(schema instanceof Map<?, ?> map)) {
            return true;
        }
        Object type = map.get("type");
        boolean object = "object".equals(type) || type instanceof List<?> types && types.contains("object")
                || map.containsKey("properties");
        if (object) {
            if (!Boolean.FALSE.equals(map.get("additionalProperties"))) {
                return false;
            }
            Map<?, ?> properties = map.get("properties") instanceof Map<?, ?> p ? p : Map.of();
            List<?> required = map.get("required") instanceof List<?> r ? r : List.of();
            for (Map.Entry<?, ?> e : properties.entrySet()) {
                if (!required.contains(e.getKey()) || !strict(e.getValue())) {
                    return false;
                }
            }
        }
        if (map.get("items") != null && !strict(map.get("items"))) {
            return false;
        }
        if (map.get("anyOf") instanceof List<?> any) {
            for (Object branch : any) {
                if (!strict(branch)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------ responses

    /**
     * A response from the message's JSON form (the SDK's {@code Message} converted back with its own mapper, unknown
     * blocks and fields included).
     *
     * @param json the message as JSON-like data
     * @param request the request it answers, or null when not known (a batch collected by another gateway)
     * @param requestedModel the model id asked for
     * @param batch true for a batch result (priced at the batch rate)
     * @param secrets values that must never appear in a response (the key, the workspace id): every string of the
     *     message (and every key) is scrubbed of them before anything is read from it (phase6 security fix, pass 2,
     *     P2-L5-F3), and the provider's identifiers and free texts are bounded after the scrub ({@code id} and
     *     {@code model} at {@value #ID_MAX} characters, {@code stop_reason} and {@code stop_details.category} at
     *     {@value #WORD_MAX}, one line), so that whatever {@code ANTHROPIC_BASE_URL} names cannot write the key or an
     *     unbounded text into a ledger, a recording, the journal's origin, triage's files or a span
     * @throws InferenceException {@code PROVIDER} when a structured answer is not JSON (with the billed usage)
     */
    static ModelResponse response(Map<?, ?> raw, ModelRequest request, String requestedModel, boolean batch,
                                  Duration elapsed, PriceTable prices, List<String> secrets)
            throws InferenceException {
        Map<?, ?> json = scrubbed(raw, secrets, 0) instanceof Map<?, ?> m ? m : Map.of();
        List<Content> parts = new ArrayList<>();
        if (json.get("content") instanceof List<?> blocks) {
            for (Object b : blocks) {
                if (b instanceof Map<?, ?> block) {
                    parts.add(part(block));
                }
            }
        }
        String stopText = json.get("stop_reason") instanceof String s ? s : null;
        StopReason stop = stop(stopText);
        String model = json.get("model") instanceof String s ? bounded(s, ID_MAX) : requestedModel;
        List<String> notes = new ArrayList<>();
        if (stop == StopReason.REFUSAL) {
            Object details = json.get("stop_details");
            Object category = details instanceof Map<?, ?> d ? d.get("category") : null;
            notes.add("refusal: " + (category instanceof String c ? bounded(c, WORD_MAX) : "unknown"));
        }
        if (stopText != null && !List.of("end_turn", "tool_use", "max_tokens", "refusal", "stop_sequence")
                .contains(stopText)) {
            notes.add("stop_reason " + bounded(stopText, WORD_MAX));
        }
        if (requestedModel != null && model != null && !sameModel(requestedModel, model)) {
            notes.add("fallback: answered by " + model);
        }
        String priceNote = prices.note(model);
        if (priceNote != null) {
            notes.add(priceNote);
        }
        Map<?, ?> u = json.get("usage") instanceof Map<?, ?> m ? m : Map.of();
        long write5m = count(u, "cache_creation", "ephemeral_5m_input_tokens");
        long write1h = count(u, "cache_creation", "ephemeral_1h_input_tokens");
        if (write5m == 0 && write1h == 0) {
            long unsplit = count(u, null, "cache_creation_input_tokens");
            if (request != null && request.cache() == CacheHint.LONG) {
                write1h = unsplit;
            } else {
                write5m = unsplit;
            }
        }
        Usage usage = new Usage(PROVIDER, requestedModel, model,
                json.get("id") instanceof String id ? bounded(id, ID_MAX) : null,
                count(u, null, "input_tokens"), count(u, null, "output_tokens"),
                count(u, null, "cache_read_input_tokens"), write5m, write1h, batch, "0", elapsed,
                notes.isEmpty() ? null : String.join("; ", notes));
        usage = usage.withCost(prices.cost(usage).toPlainString(), usage.note());
        Object structured = null;
        if (request != null && request.outputSchema() != null && stop != StopReason.REFUSAL) {
            String text = textOf(parts);
            try {
                structured = text.isBlank() ? null : JsonLike.freeze(Json.parse(text));
            } catch (JsonException | IllegalArgumentException e) {
                if (stop != StopReason.MAX_TOKENS) {
                    throw new InferenceException(Kind.PROVIDER, "the structured answer is not JSON", usage.requestId(),
                            usage, null);
                }
            }
        }
        return new ModelResponse(parts, stop, usage, structured);
    }

    /**
     * True when the model that answered is the one asked for: the same id, or that id followed by {@code -} and
     * exactly eight digits, the dated snapshot id the provider names in its answer (BS13 live: a request for
     * {@code claude-haiku-4-5} is answered by {@code claude-haiku-4-5-20251001}; the 5.5 models answer with their plain
     * ids). Anything else answered as a fallback.
     */
    static boolean sameModel(String requested, String answered) {
        return answered.equals(requested) || answered.startsWith(requested)
                && SNAPSHOT_SUFFIX.matcher(answered.substring(requested.length())).matches();
    }

    private static Content part(Map<?, ?> block) {
        Object type = block.get("type");
        if ("text".equals(type) && block.get("text") instanceof String text) {
            return Content.Text.of(text);
        }
        if ("tool_use".equals(type) && block.get("id") instanceof String id && block.get("name") instanceof String n) {
            @SuppressWarnings("unchecked")
            Map<String, Object> input = block.get("input") instanceof Map<?, ?> in ? (Map<String, Object>) in
                    : Map.of();
            return new Content.ToolUse(id, n, input);
        }
        String t = type instanceof String s && s.matches("[A-Za-z0-9_.-]{1,64}") ? s : "unknown";
        return new Content.Opaque(PROVIDER, t, block);
    }

    private static StopReason stop(String text) {
        if (text == null) {
            return StopReason.END_TURN;
        }
        return switch (text) {
            case "tool_use" -> StopReason.TOOL_USE;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            case "refusal" -> StopReason.REFUSAL;
            default -> StopReason.END_TURN;
        };
    }

    private static String textOf(List<Content> parts) {
        StringBuilder out = new StringBuilder();
        for (Content p : parts) {
            if (p instanceof Content.Text t) {
                out.append(t.text());
            }
        }
        return out.toString();
    }

    private static long count(Map<?, ?> usage, String nested, String key) {
        Map<?, ?> from = usage;
        if (nested != null) {
            if (!(usage.get(nested) instanceof Map<?, ?> inner)) {
                return 0;
            }
            from = inner;
        }
        return from.get(key) instanceof Number n ? Math.max(0, n.longValue()) : 0;
    }

    /** The JSON-like form of an SDK model object (a message, a batch result), unknown fields included. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> json(Object sdkObject) {
        return (Map<String, Object>) JsonLike.freeze(MAPPER.convertValue(sdkObject, LinkedHashMap.class));
    }

    // ------------------------------------------------------------------------------------------------ errors

    /**
     * The failure kind of an error response (phase5-contracts.md 4.1, BS13 F-9), by status and error type, not by the
     * exception class alone: 401/403 AUTH; 402 BILLING; 429 RATE_LIMIT; 529 or {@code overloaded_error} OVERLOADED;
     * 400/404/413/422 INVALID_REQUEST, except a 400 whose message names the workspace, which is AUTH; the rest
     * PROVIDER. The message holds the status, the error type and the provider's own message; {@code secrets} (the key,
     * the workspace id) are cut out of it in case a provider ever echoed one.
     */
    static InferenceException classify(AnthropicServiceException e, List<String> secrets) {
        int status = e.statusCode();
        Map<?, ?> body = errorBody(e);
        Map<?, ?> error = body.get("error") instanceof Map<?, ?> m ? m : Map.of();
        String type = error.get("type") instanceof String s ? s
                : e.errorType().map(t -> t.asString()).orElse(null);
        String providerMessage = error.get("message") instanceof String s ? s : null;
        String requestId = first(e, "request-id");
        if (requestId == null && body.get("request_id") instanceof String rid) {
            requestId = rid;
        }
        // Phase 6 security fix, pass 2, P2-L5-F3: the request id and the error type are the provider's (or whatever
        // ANTHROPIC_BASE_URL names) as well: scrubbed, then bounded, like the message.
        requestId = requestId == null ? null : bounded(scrub(requestId, secrets), ID_MAX);
        type = type == null ? null : bounded(scrub(type, secrets), WORD_MAX);
        Kind kind;
        if (status == 401 || status == 403) {
            kind = Kind.AUTH;
        } else if (status == 402) {
            kind = Kind.BILLING;
        } else if (status == 429) {
            kind = Kind.RATE_LIMIT;
        } else if (status == 529 || "overloaded_error".equals(type)) {
            kind = Kind.OVERLOADED;
        } else if (status == 400 && providerMessage != null
                && providerMessage.toLowerCase(Locale.ROOT).contains("workspace")) {
            kind = Kind.AUTH;
        } else if (status == 400 || status == 404 || status == 413 || status == 422) {
            kind = Kind.INVALID_REQUEST;
        } else {
            kind = Kind.PROVIDER;
        }
        // Phase 6 security fix S54: scrubbed before it is cut, so that a key the cut would split is still replaced.
        String message = "HTTP " + status + (type == null ? "" : " " + type)
                + (providerMessage == null ? "" : ": " + bounded(scrub(providerMessage, secrets), 300));
        if (kind == Kind.AUTH && status == 400) {
            message += " (set workspaceId in inference.yaml or ANTHROPIC_WORKSPACE_ID)";
        }
        return new InferenceException(kind, scrub(message, secrets), requestId);
    }

    private static Map<?, ?> errorBody(AnthropicServiceException e) {
        try {
            Object body = MAPPER.convertValue(e.body(), Object.class);
            return body instanceof Map<?, ?> m ? m : Map.of();
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    /**
     * The provider's {@code retry-after-ms} or {@code retry-after} (seconds), or null: also null for a value that is
     * not a finite number of at least 0 ({@code NaN}, {@code Infinity}, {@code -5}), so that the gateway's own
     * back-off applies; a finite value too large for a {@code long} of milliseconds is {@code Long.MAX_VALUE}
     * milliseconds, which never fits a deadline.
     */
    static Duration retryAfter(AnthropicServiceException e) {
        String ms = first(e, "retry-after-ms");
        try {
            if (ms != null) {
                return wait(Double.parseDouble(ms.strip()));
            }
            String seconds = first(e, "retry-after");
            if (seconds != null) {
                return wait(Double.parseDouble(seconds.strip()) * 1000);
            }
        } catch (NumberFormatException ignored) {
            // An HTTP date or garbage: the gateway's own back-off applies.
        }
        return null;
    }

    /** A wait of {@code millis}, or null when it is not a finite number of at least 0. */
    static Duration wait(double millis) {
        if (Double.isNaN(millis) || Double.isInfinite(millis) || millis < 0) {
            return null;
        }
        // The narrowing conversion saturates: 1e19 is Long.MAX_VALUE.
        return Duration.ofMillis((long) millis);
    }

    private static String first(AnthropicServiceException e, String header) {
        try {
            List<String> values = e.headers().values(header);
            return values.isEmpty() ? null : values.get(0);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String bounded(String text, int max) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() > max ? oneLine.substring(0, max - 3) + "..." : oneLine;
    }

    /**
     * The value with every string (a map's keys included) scrubbed of {@code secrets} ({@link #scrub}); a new
     * structure for a map or a list. Deeper than 256 levels, {@code "<too deep>"}.
     */
    static Object scrubbed(Object value, List<String> secrets, int depth) {
        if (depth > 256) {
            return "<too deep>";
        }
        if (value instanceof String s) {
            return scrub(s, secrets);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(scrub(String.valueOf(e.getKey()), secrets), scrubbed(e.getValue(), secrets, depth + 1));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(scrubbed(item, secrets, depth + 1));
            }
            return out;
        }
        return value;
    }

    /** {@code text} with every non-blank secret replaced by {@code <redacted>}. */
    static String scrub(String text, List<String> secrets) {
        String out = text;
        for (String s : secrets) {
            if (s != null && !s.isBlank()) {
                out = out.replace(s, "<redacted>");
            }
        }
        return out;
    }
}
