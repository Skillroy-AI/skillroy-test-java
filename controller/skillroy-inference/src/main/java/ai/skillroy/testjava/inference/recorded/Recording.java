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

package ai.skillroy.testjava.inference.recorded;

import ai.skillroy.testjava.controller.OutputFiles;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.JsonLike;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.Prompts;
import ai.skillroy.testjava.inference.spi.ToolSpec;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.inference.tools.ToolProjection;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One recorded exchange (phase5-contracts.md 4.2, decision D71): the file {@code <fingerprint>.json} in a recordings
 * directory, holding {@code {version: 1, fingerprint, purpose, modelClass, requestedModel, model, recordedAt, request:
 * {effort, system, messages, tools, outputSchema}, response: {parts, stop, structured, usage}}}. The request is masked
 * and canonical (an image as its SHA-256 and media type, never its bytes); the file is written with every key sorted,
 * two-space indented, so that it is byte-stable and a reviewer can read it. The fingerprint is
 * {@link ModelRequest#fingerprint()}: {@code maxTokens}, {@code cache} and the metadata are outside it, so a budget or a
 * label change does not invalidate a recording.
 *
 * <p>Immutable.
 *
 * @param fingerprint the request's fingerprint, also the file's name
 * @param purpose the request's purpose
 * @param response the recorded answer, as recorded (its usage names the provider that answered)
 */
public record Recording(String fingerprint, String purpose, ModelResponse response) {

    /** The file format version. */
    public static final int VERSION = 1;

    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    /** @throws IllegalArgumentException for a fingerprint that is not 64 hex digits */
    public Recording {
        if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("a fingerprint is 64 lower-case hex digits");
        }
        Objects.requireNonNull(response, "response");
    }

    /** The file of a fingerprint in a directory. */
    public static Path file(Path dir, String fingerprint) {
        return dir.resolve(fingerprint + ".json");
    }

    /**
     * The recording's JSON-like form, the request and the response passed through {@code mask} once more.
     *
     * @param recordedAt when the exchange happened
     */
    public static Map<String, Object> json(ModelRequest request, ModelResponse response, PromptMask mask,
                                           Instant recordedAt) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", (long) VERSION);
        json.put("fingerprint", request.fingerprint());
        json.put("purpose", request.purpose());
        json.put("modelClass", request.modelClass().name());
        json.put("requestedModel", response.usage().requestedModel());
        json.put("model", response.usage().model());
        json.put("recordedAt", recordedAt.toString());
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("effort", request.effort().name());
        // phase7-contracts.md 7.3: an image is written by its hash (Prompts.part never writes the bytes), so the file's
        // mask lets it through; a request with an image reached the gateway only under images: true.
        PromptMask parts = mask.withImages(true);
        req.put("system", Prompts.parts(parts.parts(request.system()), false));
        List<Object> messages = new ArrayList<>();
        for (Message m : request.messages()) {
            messages.add(Prompts.message(new Message(m.role(), parts.parts(m.parts())), false));
        }
        req.put("messages", messages);
        List<Object> tools = new ArrayList<>();
        for (ToolSpec t : request.tools()) {
            // S49: a tool's description and input schema through the mask too; the fingerprint is the request's as sent.
            tools.add(Prompts.tool(ToolProjection.masked(t, mask)));
        }
        req.put("tools", tools);
        // Phase 7 security fix S136: the output schema through the mask too, as the tools' schemas are (unchanged for a
        // schema that holds nothing a key or value rule would mask, so no earlier recording's bytes change).
        req.put("outputSchema", request.outputSchema() == null ? null : mask.schema(request.outputSchema()));
        if (!request.providerTools().isEmpty()) {
            // phase7-contracts.md 7.3: the provider tools as the fingerprint holds them, only when there are any, so that
            // no earlier recording's bytes change.
            List<Object> provided = new ArrayList<>();
            for (ai.skillroy.testjava.inference.spi.ProviderTool t : request.providerTools()) {
                provided.add(mask.jsonLike(Prompts.providerTool(t)));
            }
            req.put("providerTools", provided);
        }
        json.put("request", req);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("parts", Prompts.parts(parts.parts(response.parts()), true));
        res.put("stop", response.stop().name());
        res.put("structured", mask.jsonLike(response.structured()));
        res.put("usage", response.usage().toJson());
        json.put("response", res);
        return json;
    }

    /** The bytes of a recording file: sorted keys, two-space indent, a final newline. */
    public static byte[] bytes(Map<String, Object> json) {
        return Json.writePretty(JsonLike.sorted(json)).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Writes the recording of an exchange into {@code dir} (created if missing), as {@code <fingerprint>.json}.
     *
     * @return the file
     * @throws IOException when it cannot be written
     */
    public static Path write(Path dir, ModelRequest request, ModelResponse response, PromptMask mask,
                             Instant recordedAt) throws IOException {
        Path file = file(dir, request.fingerprint());
        // Phase 6 security fix, pass 2, P2-L8-F3: the temporary file has a name nobody can predict and is created
        // exclusively (OutputFiles); the move replaces the entry of the final name itself, never what a link there
        // names.
        Path tmp = dir.resolve(request.fingerprint() + "." + java.util.UUID.randomUUID() + ".json.tmp");
        OutputFiles.write(tmp, bytes(json(request, response, mask, recordedAt)));
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return file;
    }

    /**
     * Reads a recording file.
     *
     * @throws IOException when it cannot be read
     * @throws IllegalArgumentException when it is not a recording of version 1 (the message names the file)
     */
    public static Recording read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, Object> json;
        try {
            json = Json.parseObject(text);
        } catch (JsonException e) {
            throw new IllegalArgumentException(file + " is not a JSON recording");
        }
        if (!Long.valueOf(VERSION).equals(json.get("version"))) {
            throw new IllegalArgumentException(file + " is not a recording of version " + VERSION);
        }
        Object fingerprint = json.get("fingerprint");
        Object purpose = json.get("purpose");
        if (!(json.get("response") instanceof Map<?, ?> res) || !(fingerprint instanceof String f)) {
            throw new IllegalArgumentException(file + " lacks its fingerprint or response");
        }
        List<Content> parts = new ArrayList<>();
        if (res.get("parts") instanceof List<?> list) {
            for (Object p : list) {
                if (p instanceof Map<?, ?> part) {
                    parts.add(Prompts.partFromJson(part));
                }
            }
        }
        if (!(res.get("usage") instanceof Map<?, ?> usage)) {
            throw new IllegalArgumentException(file + " lacks the response's usage");
        }
        ModelResponse.StopReason stop;
        try {
            stop = ModelResponse.StopReason.valueOf(String.valueOf(res.get("stop")));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file + " has an unknown stop reason");
        }
        ModelResponse response = new ModelResponse(parts, stop, Usage.fromJson(usage), res.get("structured"));
        return new Recording(f, purpose instanceof String p ? p : null, response);
    }
}
