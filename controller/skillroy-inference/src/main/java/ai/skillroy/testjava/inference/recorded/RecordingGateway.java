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

import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.spi.BatchHandle;
import ai.skillroy.testjava.inference.spi.BatchResult;
import ai.skillroy.testjava.inference.spi.BatchStatus;
import ai.skillroy.testjava.inference.spi.GatewayCapability;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.InferenceException.Kind;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Delegates every call to a live gateway and writes the recording of each answer on the way back
 * (phase5-contracts.md 4.2), through the prompt mask once more: a recording holds nothing a prompt may not hold, and a
 * prompt holds no secret. A recording that already exists is overwritten only with
 * {@code -D}{@value #OVERWRITE_PROPERTY}{@code =true} (profile {@code inference-record}); otherwise the live answer is
 * used and the file kept, so that a maintainer refreshes deliberately. A recording that cannot be written fails the
 * call ({@code PROVIDER}, with the usage the provider billed, so that the ledger still records the cost).
 *
 * <p>Thread-safe if the live gateway is; owns no thread of its own.
 */
public final class RecordingGateway implements InferenceGateway {

    /** The system property that lets an existing recording be overwritten. */
    public static final String OVERWRITE_PROPERTY = "skillroy.inference.record.overwrite";

    private final InferenceGateway live;
    private final Path dir;
    private final PromptMask mask;
    private final boolean overwrite;
    private final Clock clock;
    private final Map<String, List<ModelRequest>> batches = new ConcurrentHashMap<>();

    /** Records into {@code dir} through the key rule and bounds of the mask; overwriting as the property says. */
    public RecordingGateway(InferenceGateway live, Path dir) {
        this(live, dir, PromptMask.keysOnly(), Boolean.getBoolean(OVERWRITE_PROPERTY), Clock.systemUTC());
    }

    /**
     * @param live the gateway that answers
     * @param dir where the recordings go
     * @param mask the run's prompt mask
     * @param overwrite true: an existing recording is replaced
     * @param clock the recordings' time
     */
    public RecordingGateway(InferenceGateway live, Path dir, PromptMask mask, boolean overwrite, Clock clock) {
        this.live = Objects.requireNonNull(live, "live");
        this.dir = Objects.requireNonNull(dir, "dir");
        this.mask = mask == null ? PromptMask.keysOnly() : mask;
        this.overwrite = overwrite;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public String provider() {
        return live.provider();
    }

    @Override
    public Set<GatewayCapability> capabilities() {
        return live.capabilities();
    }

    @Override
    public ModelResponse complete(ModelRequest request) throws InferenceException, InterruptedException {
        return record(request, live.complete(request));
    }

    @Override
    public ModelResponse complete(ModelRequest request, Duration timeout)
            throws InferenceException, InterruptedException {
        return record(request, live.complete(request, timeout));
    }

    private ModelResponse record(ModelRequest request, ModelResponse response) throws InferenceException {
        Path file = Recording.file(dir, request.fingerprint());
        if (Files.exists(file) && !overwrite) {
            return response;
        }
        try {
            Recording.write(dir, request, response, mask, clock.instant());
        } catch (IOException | RuntimeException e) {
            throw new InferenceException(Kind.PROVIDER, "the recording " + file.getFileName() + " could not be"
                    + " written (" + e.getClass().getSimpleName() + ")", response.usage().requestId(),
                    response.usage(), null);
        }
        return response;
    }

    @Override
    public BatchHandle submitBatch(List<ModelRequest> requests) throws InferenceException {
        BatchHandle handle = live.submitBatch(requests);
        batches.put(handle.id(), List.copyOf(requests));
        return handle;
    }

    @Override
    public BatchStatus batchStatus(BatchHandle handle) throws InferenceException {
        return live.batchStatus(handle);
    }

    /** The live results; each answer is recorded (a failed write turns that result into its failure). */
    @Override
    public List<BatchResult> collectBatch(BatchHandle handle) throws InferenceException {
        List<BatchResult> results = live.collectBatch(handle);
        List<ModelRequest> requests = batches.get(handle.id());
        if (requests == null) {
            return results;
        }
        List<BatchResult> out = new java.util.ArrayList<>(results.size());
        for (BatchResult r : results) {
            if (r.succeeded() && r.index() < requests.size()) {
                try {
                    out.add(new BatchResult(r.index(), record(requests.get(r.index()), r.response()), null));
                } catch (InferenceException e) {
                    out.add(new BatchResult(r.index(), null, e));
                }
            } else {
                out.add(r);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public void cancelBatch(BatchHandle handle) throws InferenceException {
        live.cancelBatch(handle);
    }

    @Override
    public long countTokens(ModelRequest request) throws InferenceException {
        return live.countTokens(request);
    }

    /** Closes the live gateway. */
    @Override
    public void close() {
        batches.clear();
        live.close();
    }

    @Override
    public String toString() {
        return "RecordingGateway[" + live + " -> " + dir + "]";
    }
}
