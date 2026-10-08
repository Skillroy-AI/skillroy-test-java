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

package ai.skillroy.testjava.inference.scripted;

import ai.skillroy.testjava.inference.spi.BatchHandle;
import ai.skillroy.testjava.inference.spi.BatchResult;
import ai.skillroy.testjava.inference.spi.BatchStatus;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.GatewayCapability;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.InferenceException.Kind;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.ModelResponse.StopReason;
import ai.skillroy.testjava.inference.spi.Structured;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.protocol.Json;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A gateway for tests that scripts an exchange without files (phase5-contracts.md 4.3): {@link #answering},
 * {@link #sequence}, {@link #failing}, or {@link #of} a script that may itself fail; every request is kept in
 * {@link #requests()} for assertions. In main scope (as {@code Approver.APPROVE_ALL} is), so that the scenarios
 * modules' tests and a pack's tests can use it without a test-jar. Batches are answered by the same script, request by
 * request, when collected. The helpers {@link #structured}, {@link #text} and {@link #refusal} build answers.
 *
 * <p>Thread-safe if the script is; owns no thread.
 */
public final class ScriptedGateway implements InferenceGateway {

    /** The provider id of a scripted answer's usage. */
    public static final String PROVIDER = "scripted";
    /** The model id a scripted answer names. */
    public static final String MODEL = "scripted-model";

    /** One answer per request; may fail like a gateway. */
    @FunctionalInterface
    public interface Script {
        ModelResponse answer(ModelRequest request) throws InferenceException;
    }

    private final Script script;
    private final List<ModelRequest> requests = new ArrayList<>();
    private final Map<String, List<ModelRequest>> batches = new ConcurrentHashMap<>();
    private final AtomicInteger batchIds = new AtomicInteger();
    private volatile boolean closed;

    private ScriptedGateway(Script script) {
        this.script = Objects.requireNonNull(script, "script");
    }

    /** Answers every request with the function's answer. */
    public static ScriptedGateway answering(Function<ModelRequest, ModelResponse> answer) {
        Objects.requireNonNull(answer, "answer");
        return new ScriptedGateway(answer::apply);
    }

    /** Answers with a script that may fail. */
    public static ScriptedGateway of(Script script) {
        return new ScriptedGateway(script);
    }

    /** Answers the requests in order with these responses; a request beyond the last is {@code UNSUPPORTED}. */
    public static ScriptedGateway sequence(List<ModelResponse> responses) {
        List<ModelResponse> copy = List.copyOf(responses);
        AtomicInteger next = new AtomicInteger();
        return new ScriptedGateway(request -> {
            int i = next.getAndIncrement();
            if (i >= copy.size()) {
                throw new InferenceException(Kind.UNSUPPORTED, "the script has no answer " + (i + 1));
            }
            return copy.get(i);
        });
    }

    /** Fails every request with this exception. */
    public static ScriptedGateway failing(InferenceException failure) {
        Objects.requireNonNull(failure, "failure");
        return new ScriptedGateway(request -> {
            throw failure;
        });
    }

    /** A structured answer (a record, or JSON-like data) with its JSON text as the text part, at no cost. */
    public static ModelResponse structured(Object answer) {
        return structured(answer, Usage.none(PROVIDER, MODEL));
    }

    /** A structured answer with the given usage. */
    public static ModelResponse structured(Object answer, Usage usage) {
        Object json = answer instanceof Record || answer instanceof Map<?, ?> || answer instanceof List<?>
                ? Structured.encode(answer) : answer;
        return new ModelResponse(List.of(Content.Text.of(Json.write(json))), StopReason.END_TURN, usage, json);
    }

    /** A free-text answer at no cost. */
    public static ModelResponse text(String text) {
        return new ModelResponse(List.of(Content.Text.of(text)), StopReason.END_TURN, Usage.none(PROVIDER, MODEL),
                null);
    }

    /** A refusal with the provider's category in the usage's note. */
    public static ModelResponse refusal(String category) {
        Usage usage = new Usage(PROVIDER, MODEL, MODEL, null, 0, 0, 0, 0, 0, false, "0", Duration.ZERO,
                "refusal: " + category);
        return new ModelResponse(List.of(), StopReason.REFUSAL, usage, null);
    }

    /** A usage of a scripted call with these tokens and cost. */
    public static Usage usage(long inputTokens, long outputTokens, String costUsd) {
        return new Usage(PROVIDER, MODEL, MODEL, "scripted-" + inputTokens + "-" + outputTokens, inputTokens,
                outputTokens, 0, 0, 0, false, costUsd, Duration.ZERO, null);
    }

    /** The requests made so far, in order (batch requests included when collected). */
    public synchronized List<ModelRequest> requests() {
        return List.copyOf(requests);
    }

    /** True once closed. */
    public boolean closed() {
        return closed;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public Set<GatewayCapability> capabilities() {
        return Set.copyOf(EnumSet.complementOf(EnumSet.of(GatewayCapability.IMAGES)));
    }

    @Override
    public ModelResponse complete(ModelRequest request) throws InferenceException {
        synchronized (this) {
            requests.add(request);
        }
        ModelResponse response = script.answer(request);
        if (response == null) {
            throw new InferenceException(Kind.PROVIDER, "the script answered null");
        }
        return response;
    }

    @Override
    public BatchHandle submitBatch(List<ModelRequest> batch) {
        if (batch == null || batch.isEmpty()) {
            throw new IllegalArgumentException("a batch holds at least one request");
        }
        String id = "scripted-batch-" + batchIds.incrementAndGet();
        batches.put(id, List.copyOf(batch));
        return new BatchHandle(PROVIDER, id, Instant.EPOCH, batch.size());
    }

    @Override
    public BatchStatus batchStatus(BatchHandle handle) throws InferenceException {
        return new BatchStatus(BatchStatus.State.ENDED, batch(handle).size(), 0, 0, 0, 0);
    }

    @Override
    public List<BatchResult> collectBatch(BatchHandle handle) throws InferenceException {
        List<ModelRequest> batch = batch(handle);
        List<BatchResult> out = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            try {
                ModelResponse r = complete(batch.get(i));
                Usage u = r.usage();
                Usage asBatch = new Usage(u.provider(), u.requestedModel(), u.model(), u.requestId(), u.inputTokens(),
                        u.outputTokens(), u.cacheReadTokens(), u.cacheWrite5mTokens(), u.cacheWrite1hTokens(), true,
                        u.costUsd(), u.elapsed(), u.note());
                out.add(new BatchResult(i, new ModelResponse(r.parts(), r.stop(), asBatch, r.structured()), null));
            } catch (InferenceException e) {
                out.add(new BatchResult(i, null, e));
            }
        }
        return List.copyOf(out);
    }

    @Override
    public void cancelBatch(BatchHandle handle) {
        batches.remove(handle.id());
    }

    private List<ModelRequest> batch(BatchHandle handle) throws InferenceException {
        List<ModelRequest> batch = batches.get(handle.id());
        if (batch == null) {
            throw new InferenceException(Kind.INVALID_REQUEST, "no scripted batch " + handle.id());
        }
        return batch;
    }

    /** Text length / 4 over every text part. */
    @Override
    public long countTokens(ModelRequest request) {
        long chars = 0;
        for (Content c : request.system()) {
            chars += c instanceof Content.Text t ? t.text().length() : 0;
        }
        for (Message m : request.messages()) {
            for (Content c : m.parts()) {
                chars += c instanceof Content.Text t ? t.text().length() : 0;
            }
        }
        return chars / 4;
    }

    @Override
    public void close() {
        closed = true;
    }
}
