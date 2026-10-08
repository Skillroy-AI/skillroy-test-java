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

import ai.skillroy.testjava.inference.spi.BatchHandle;
import ai.skillroy.testjava.inference.spi.BatchResult;
import ai.skillroy.testjava.inference.spi.BatchStatus;
import ai.skillroy.testjava.inference.spi.GatewayCapability;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.InferenceException.Kind;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.Usage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The gateway that answers from recordings (phase5-contracts.md 4.2, decision D71): what lets CI, the Linux image and
 * every unit test run the AI layer with no key. {@link #complete} answers from the file named by the request's
 * fingerprint, the recorded usage marked provider {@value #PROVIDER} and cost {@code "0"}; a request with no file is
 * {@code NO_RECORDING} with the fingerprint and the purpose, so that a changed prompt is seen, never guessed. Batches
 * are served the same way, request by request, with {@code batch: true} in the usage. {@link #countTokens} answers the
 * recorded input tokens, or -1. Capabilities: all but images.
 *
 * <p>Thread-safe; owns no thread.
 */
public final class RecordedGateway implements InferenceGateway {

    /** The provider id of a recorded answer's usage. */
    public static final String PROVIDER = "recorded";
    /**
     * How a missing recording is made, as the {@code NO_RECORDING} messages say it: the {@code inference-record}
     * profile is specified but not built (phase5-contracts.md 2.3), so the route that exists is the settings'
     * {@code record: true}, which wraps the live gateway in the {@link RecordingGateway}.
     */
    public static final String RECORD_HINT = "record with a recording gateway (record: true, see the recordings"
            + " README of the sample)";

    private final Path dir;
    private final Clock clock;
    private final Map<String, List<ModelRequest>> batches = new ConcurrentHashMap<>();
    private final AtomicLong batchIds = new AtomicLong();

    /** A gateway over the recordings in {@code dir}. */
    public RecordedGateway(Path dir) {
        this(dir, Clock.systemUTC());
    }

    RecordedGateway(Path dir, Clock clock) {
        this.dir = Objects.requireNonNull(dir, "dir");
        this.clock = clock;
    }

    /** The recordings directory. */
    public Path dir() {
        return dir;
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
        return answer(request, false);
    }

    /** The recorded answer, or {@code NO_RECORDING}. */
    private ModelResponse answer(ModelRequest request, boolean batch) throws InferenceException {
        Recording recording = recording(request);
        if (recording == null) {
            String slot = request.metadata().get(ModelRequest.META_SLOT);
            throw new InferenceException(Kind.NO_RECORDING, "no recording for the " + request.purpose() + " request"
                    + (slot == null ? "" : " of slot " + slot) + " (fingerprint " + request.fingerprint()
                    + "); " + RECORD_HINT);
        }
        ModelResponse r = recording.response();
        Usage usage = r.usage().replayed(PROVIDER, "0", batch, Duration.ZERO);
        return new ModelResponse(r.parts(), r.stop(), usage, r.structured());
    }

    private Recording recording(ModelRequest request) throws InferenceException {
        Path file = Recording.file(dir, request.fingerprint());
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Recording.read(file);
        } catch (IOException | IllegalArgumentException e) {
            throw new InferenceException(Kind.PROVIDER, "the recording " + file.getFileName() + " cannot be read: "
                    + e.getClass().getSimpleName());
        }
    }

    @Override
    public BatchHandle submitBatch(List<ModelRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("a batch holds at least one request");
        }
        String id = "recorded-batch-" + batchIds.incrementAndGet();
        batches.put(id, List.copyOf(requests));
        return new BatchHandle(PROVIDER, id, clock.instant(), requests.size());
    }

    @Override
    public BatchStatus batchStatus(BatchHandle handle) throws InferenceException {
        List<ModelRequest> requests = requests(handle);
        int found = 0;
        for (ModelRequest r : requests) {
            found += Files.isRegularFile(Recording.file(dir, r.fingerprint())) ? 1 : 0;
        }
        return new BatchStatus(BatchStatus.State.ENDED, found, requests.size() - found, 0, 0, 0);
    }

    @Override
    public List<BatchResult> collectBatch(BatchHandle handle) throws InferenceException {
        List<ModelRequest> requests = requests(handle);
        List<BatchResult> out = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            try {
                out.add(new BatchResult(i, answer(requests.get(i), true), null));
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

    private List<ModelRequest> requests(BatchHandle handle) throws InferenceException {
        List<ModelRequest> requests = batches.get(handle.id());
        if (requests == null) {
            throw new InferenceException(Kind.INVALID_REQUEST, "no recorded batch " + handle.id());
        }
        return requests;
    }

    /** The recorded input tokens (cache reads and writes included), or -1 without a recording. */
    @Override
    public long countTokens(ModelRequest request) throws InferenceException {
        Recording recording = recording(request);
        if (recording == null) {
            return -1;
        }
        Usage u = recording.response().usage();
        return u.inputTokens() + u.cacheReadTokens() + u.cacheWriteTokens();
    }

    @Override
    public void close() {
        batches.clear();
    }

    @Override
    public String toString() {
        return "RecordedGateway[" + dir + "]";
    }
}
