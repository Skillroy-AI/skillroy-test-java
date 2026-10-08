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

package ai.skillroy.testjava.inference;

import ai.skillroy.testjava.inference.claude.ClaudeGateway;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.recorded.RecordedGateway;
import ai.skillroy.testjava.inference.recorded.RecordingGateway;
import ai.skillroy.testjava.inference.spi.BatchHandle;
import ai.skillroy.testjava.inference.spi.BatchResult;
import ai.skillroy.testjava.inference.spi.BatchStatus;
import ai.skillroy.testjava.inference.spi.GatewayCapability;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.InferenceException.Kind;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds the gateway the settings name (phase5-contracts.md 4.4): {@code claude} (the key from the environment),
 * {@code recorded} (the settings' recordings directory), {@code none} (a gateway whose every call is
 * {@code UNSUPPORTED} "no gateway"); a live gateway is wrapped in the {@link RecordingGateway} when {@code record} is
 * set, writing its recordings through the prompt mask of whoever opened it first ({@link #open(InferenceSettings,
 * PromptMask)}; the key rule and the bounds alone without one). One gateway per distinct gateway-relevant settings, shared and reference-counted: each {@link #open} returns a
 * lease whose {@code close()} releases it once, and the gateway is closed with its last lease (the re-resolve job parks
 * many binds at once, each with its own contributor, and they should share one HTTP client and one batch). A caller may
 * {@link #serve} settings with a gateway of its own (a scripted one, in tests and in a sample's recording generator).
 *
 * <p>Thread-safe.
 */
public final class Gateways {

    /** What decides which gateway serves a settings object (the budgets and thresholds do not). */
    private record Key(String gateway, Path recordings, boolean record, String workspaceId, Map<String, String> models,
                       Path prices, boolean fallbacks, boolean images, Map<String, Long> timeouts) {

        static Key of(InferenceSettings s) {
            return new Key(s.gateway(), s.recordings(), s.record(), s.workspaceId(), s.models(), s.prices(),
                    s.fallbacks(), s.images(), s.timeouts());
        }
    }

    private static final class Shared {
        final InferenceGateway gateway;
        /** True for a gateway a caller registered with {@link #serve}: the caller's, never closed here. */
        final boolean served;
        /** True while a {@link #serve} registration holds the entry, leases or not. */
        boolean registered;
        int leases;

        Shared(InferenceGateway gateway, boolean served) {
            this.gateway = gateway;
            this.served = served;
            this.registered = served;
        }
    }

    /** The hold of {@link #serve} on its settings; closing it ends the registration, once. */
    public interface Served extends AutoCloseable {
        @Override
        void close();
    }

    private static final Map<Key, Shared> OPEN = new HashMap<>();

    private Gateways() {
    }

    /**
     * A lease on the gateway of these settings, opened on first use.
     *
     * @throws InferenceException {@code AUTH} without the model key for {@code claude}; {@code UNSUPPORTED} for an
     *     unknown gateway or {@code recorded} without a directory
     */
    public static InferenceGateway open(InferenceSettings settings) throws InferenceException {
        return open(settings, null);
    }

    /**
     * A lease on the gateway of these settings, opened on first use with the opener's prompt mask: the mask the
     * {@link RecordingGateway} of {@code record: true} writes its recordings through (phase5-contracts.md 4.2, the
     * second pass; the request builders apply the full mask of 6.2 before a request reaches a gateway). The shared
     * gateway keeps the mask of the opener that built it; a later opener's mask is not applied.
     *
     * @param mask the opener's mask (the run's credentials, the pack's allow-lists and row masks); null is the key rule
     *     and the bounds alone
     * @throws InferenceException as {@link #open(InferenceSettings)}
     */
    public static InferenceGateway open(InferenceSettings settings, PromptMask mask) throws InferenceException {
        Objects.requireNonNull(settings, "settings");
        Key key = Key.of(settings);
        synchronized (OPEN) {
            Shared shared = OPEN.get(key);
            if (shared == null) {
                shared = new Shared(build(settings, mask), false);
                OPEN.put(key, shared);
            }
            shared.leases++;
            return new Lease(key, shared);
        }
    }

    /**
     * Serves these settings with a gateway the caller owns (phase5-contracts.md 4.3): until the returned registration
     * is closed, {@link #open} with settings that select the same gateway (equal in what decides which gateway serves
     * them: the gateway's name, the recordings, the models and the rest of {@code Key}; not the budgets) returns
     * leases on {@code gateway} instead of building one. This is how a test, or a scenarios module's recording
     * generator, runs the real binder and the real contributor through a {@code ScriptedGateway}, or a
     * {@code RecordingGateway} around one, with no key; the settings may name any gateway, {@code scripted} included,
     * since nothing is built for them. The caller keeps its gateway and closes it: neither a lease nor the
     * registration does. Closing the registration ends it; leases still held go on answering until they are closed.
     *
     * @throws IllegalStateException when a gateway is open or served for such settings already
     */
    public static Served serve(InferenceSettings settings, InferenceGateway gateway) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(gateway, "gateway");
        Key key = Key.of(settings);
        synchronized (OPEN) {
            if (OPEN.containsKey(key)) {
                throw new IllegalStateException("a gateway is open or served for these settings already (gateway "
                        + settings.gateway() + ")");
            }
            Shared shared = new Shared(gateway, true);
            OPEN.put(key, shared);
            AtomicBoolean ended = new AtomicBoolean();
            return () -> {
                if (!ended.compareAndSet(false, true)) {
                    return;
                }
                synchronized (OPEN) {
                    shared.registered = false;
                    if (shared.leases <= 0 && OPEN.get(key) == shared) {
                        OPEN.remove(key);
                    }
                }
            };
        }
    }

    /** How many distinct gateways are open (for tests). */
    static int openGateways() {
        synchronized (OPEN) {
            return OPEN.size();
        }
    }

    private static InferenceGateway build(InferenceSettings settings, PromptMask mask) throws InferenceException {
        return switch (settings.gateway()) {
            case InferenceSettings.GATEWAY_CLAUDE -> recording(ClaudeGateway.open(settings), settings, mask);
            case InferenceSettings.GATEWAY_RECORDED -> {
                if (settings.recordings() == null) {
                    throw new InferenceException(Kind.UNSUPPORTED, "the recorded gateway needs a recordings"
                            + " directory");
                }
                yield new RecordedGateway(settings.recordings());
            }
            case InferenceSettings.GATEWAY_NONE -> NONE;
            default -> throw new InferenceException(Kind.UNSUPPORTED, "unknown gateway " + settings.gateway());
        };
    }

    /**
     * The live gateway as the settings want it: wrapped in a {@link RecordingGateway} over the settings' recordings
     * directory, writing through {@code mask} (null: the key rule and the bounds alone), when {@code record} is set;
     * else as it is.
     */
    static InferenceGateway recording(InferenceGateway live, InferenceSettings settings, PromptMask mask) {
        if (!settings.record() || settings.recordings() == null) {
            return live;
        }
        return new RecordingGateway(live, settings.recordings(), mask == null ? PromptMask.keysOnly() : mask,
                Boolean.getBoolean(RecordingGateway.OVERWRITE_PROPERTY), Clock.systemUTC());
    }

    /** The gateway of {@code gateway: none}: every call is {@code UNSUPPORTED} "no gateway". */
    public static final InferenceGateway NONE = new InferenceGateway() {
        @Override
        public String provider() {
            return InferenceSettings.GATEWAY_NONE;
        }

        @Override
        public Set<GatewayCapability> capabilities() {
            return Set.of();
        }

        @Override
        public ModelResponse complete(ModelRequest request) throws InferenceException {
            throw new InferenceException(Kind.UNSUPPORTED, "no gateway");
        }

        @Override
        public BatchHandle submitBatch(List<ModelRequest> requests) throws InferenceException {
            throw new InferenceException(Kind.UNSUPPORTED, "no gateway");
        }

        @Override
        public BatchStatus batchStatus(BatchHandle handle) throws InferenceException {
            throw new InferenceException(Kind.UNSUPPORTED, "no gateway");
        }

        @Override
        public List<BatchResult> collectBatch(BatchHandle handle) throws InferenceException {
            throw new InferenceException(Kind.UNSUPPORTED, "no gateway");
        }

        @Override
        public void cancelBatch(BatchHandle handle) throws InferenceException {
            throw new InferenceException(Kind.UNSUPPORTED, "no gateway");
        }

        @Override
        public long countTokens(ModelRequest request) throws InferenceException {
            throw new InferenceException(Kind.UNSUPPORTED, "no gateway");
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return "NoGateway";
        }
    };

    /** One user's hold on a shared gateway; closing it releases the hold once. */
    private static final class Lease implements InferenceGateway {

        private final Key key;
        private final Shared shared;
        private final AtomicBoolean released = new AtomicBoolean();

        Lease(Key key, Shared shared) {
            this.key = key;
            this.shared = shared;
        }

        private InferenceGateway gateway() throws InferenceException {
            if (released.get()) {
                throw new InferenceException(Kind.UNSUPPORTED, "the gateway lease is closed");
            }
            return shared.gateway;
        }

        @Override
        public String provider() {
            return shared.gateway.provider();
        }

        @Override
        public Set<GatewayCapability> capabilities() {
            return shared.gateway.capabilities();
        }

        @Override
        public ModelResponse complete(ModelRequest request) throws InferenceException, InterruptedException {
            return gateway().complete(request);
        }

        @Override
        public ModelResponse complete(ModelRequest request, Duration timeout)
                throws InferenceException, InterruptedException {
            return gateway().complete(request, timeout);
        }

        @Override
        public BatchHandle submitBatch(List<ModelRequest> requests) throws InferenceException {
            return gateway().submitBatch(requests);
        }

        @Override
        public BatchStatus batchStatus(BatchHandle handle) throws InferenceException {
            return gateway().batchStatus(handle);
        }

        @Override
        public List<BatchResult> collectBatch(BatchHandle handle) throws InferenceException {
            return gateway().collectBatch(handle);
        }

        @Override
        public void cancelBatch(BatchHandle handle) throws InferenceException {
            gateway().cancelBatch(handle);
        }

        @Override
        public long countTokens(ModelRequest request) throws InferenceException {
            return gateway().countTokens(request);
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            synchronized (OPEN) {
                shared.leases--;
                if (shared.leases <= 0 && !shared.registered && OPEN.get(key) == shared) {
                    OPEN.remove(key);
                    if (!shared.served) {
                        shared.gateway.close();
                    }
                }
            }
        }

        @Override
        public String toString() {
            return "Lease[" + shared.gateway + "]";
        }
    }
}
