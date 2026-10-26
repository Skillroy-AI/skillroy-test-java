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
import ai.skillroy.testjava.inference.spi.BatchHandle;
import ai.skillroy.testjava.inference.spi.BatchResult;
import ai.skillroy.testjava.inference.spi.BatchStatus;
import ai.skillroy.testjava.inference.spi.GatewayCapability;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.InferenceException.Kind;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.SettingsException;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.LogLevel;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCountTokensParams;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.batches.BatchCancelParams;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.BatchResultsParams;
import com.anthropic.models.messages.batches.BatchRetrieveParams;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The Claude gateway (phase5-contracts.md 4.1, as spike BS13 corrected it) on the Anthropic Java SDK.
 *
 * <p>The client is built explicitly, never with the SDK's {@code fromEnv()} (which also reads auth tokens, profiles,
 * custom headers and the log level from the operator's shell): the key from the environment variable
 * {@value #KEY_VARIABLE}, read once and never logged or put into a message; the base URL from
 * {@value #BASE_URL_VARIABLE} when set; the SDK's retries off ({@code maxRetries(0)}) and its logging off; the header
 * {@value #WORKSPACE_HEADER} from the settings' {@code workspaceId}, else {@value #WORKSPACE_VARIABLE}, for a key that is
 * not scoped to a workspace.
 *
 * <p>Every call runs on the caller's thread under the per-call deadline ({@code timeouts.callMs}, clamped further by
 * {@link #complete(ModelRequest, Duration)}): {@code RATE_LIMIT}, {@code OVERLOADED} and {@code TRANSPORT} are
 * retried at most twice, the provider's {@code retry-after} honoured only while it fits, each attempt given what is
 * left of the deadline (2.4); the deadline passing is {@code TIMEOUT}. Errors are classified by status and error type
 * (4.1's table). The refusal fallback ({@code fallbacks: true}) goes out as the extra body property
 * {@code "fallbacks": "default"} with the beta header {@value ClaudeMapping#FALLBACK_BETA} on the regular endpoint (the
 * SDK's typed parameter exists only on its beta endpoint's request type), never in a batch. Batches go through the
 * Message Batches API, each request's {@code custom_id} its index, results matched by it.
 *
 * <p>Thread-safe. The one thread it owns is the SDK's HTTP client pool, closed by {@link #close()}.
 */
public final class ClaudeGateway implements InferenceGateway {

    /** The environment variable of the model key (only the environment; never a property, a file or an option). */
    public static final String KEY_VARIABLE = "ANTHROPIC_API_KEY";
    /** The environment variable of the API's base URL (a proxy or a test double); optional. */
    public static final String BASE_URL_VARIABLE = "ANTHROPIC_BASE_URL";
    /** The environment variable of the workspace id an organisation-level key needs; the settings win over it. */
    public static final String WORKSPACE_VARIABLE = "ANTHROPIC_WORKSPACE_ID";
    /** The header that carries the workspace id. */
    public static final String WORKSPACE_HEADER = "anthropic-workspace-id";

    /** The workspace id's form: the settings' own pattern ({@code InferenceSettings}' reader), ASCII only. */
    private static final Pattern WORKSPACE_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}");

    /** How many times a retryable failure is tried again within the deadline. */
    static final int MAX_RETRIES = 2;
    private static final long FIRST_BACKOFF_MS = 500;
    private static final long MAX_BACKOFF_MS = 8000;

    /** A bounded wait between attempts (a test passes one that does not wait). */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** What the gateway takes from the settings. */
    record Config(Map<ModelClass, String> models, boolean fallbacks, boolean images, long callMs, PriceTable prices) {

        Config {
            models = Collections.unmodifiableMap(new EnumMap<>(models));
        }

        static Config of(InferenceSettings settings, PriceTable prices) {
            Map<ModelClass, String> models = new EnumMap<>(ModelClass.class);
            for (ModelClass c : ModelClass.values()) {
                String id = settings.models().get(c.settingsKey());
                if (id != null) {
                    models.put(c, id);
                }
            }
            Long callMs = settings.timeouts().get(InferenceSettings.TIMEOUT_CALL_MS);
            return new Config(models, settings.fallbacks(), settings.images(),
                    callMs == null ? 120_000L : callMs, prices);
        }
    }

    /** The requests of a submitted batch, kept to map its results. */
    private record Submitted(List<ModelRequest> requests, List<String> models) {
    }

    private final AnthropicClient client;
    private final Config config;
    private final List<String> secrets;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Map<String, Submitted> batches = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * A gateway over an already-built client (tests point it at a loopback fake).
     *
     * @param secrets values that must never appear in a message (the key, the workspace id); may hold nulls
     */
    ClaudeGateway(AnthropicClient client, Config config, List<String> secrets, Clock clock, Sleeper sleeper) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.config = java.util.Objects.requireNonNull(config, "config");
        List<String> s = new ArrayList<>();
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                s.add(secret);
            }
        }
        this.secrets = List.copyOf(s);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.sleeper = sleeper == null ? Thread::sleep : sleeper;
    }

    /**
     * The gateway of these settings, its key and base URL read from the process environment.
     *
     * @throws InferenceException {@code AUTH} "the model key is not set (ANTHROPIC_API_KEY)" without the key, and
     *     {@code AUTH} naming the variable (never its value) for a key or a workspace id a request header cannot
     *     carry, or an {@code ANTHROPIC_BASE_URL} that is not {@code https} (or {@code http} on a loopback host) or
     *     carries user-info (phase6 security fix S51); {@code INVALID_REQUEST} when the settings' price table cannot
     *     be read or a configured model is not in it (S52)
     */
    public static ClaudeGateway open(InferenceSettings settings) throws InferenceException {
        return open(settings, System::getenv);
    }

    /**
     * {@link #open(InferenceSettings)} with the environment given (each variable is read once, here). A key or a
     * workspace id that a request header cannot carry is refused here, before any client exists, with {@code AUTH}
     * and a message that names where the value came from and never the value: the HTTP client's own refusal quotes
     * the header's value (a key with a no-break space, a zero-width space or a control character would otherwise
     * reach a run's outputs through that message).
     */
    static ClaudeGateway open(InferenceSettings settings, UnaryOperator<String> environment)
            throws InferenceException {
        String key = environment.apply(KEY_VARIABLE);
        if (key == null || key.isBlank()) {
            throw new InferenceException(Kind.AUTH, "the model key is not set (" + KEY_VARIABLE + ")");
        }
        if (!headerSafe(key.strip())) {
            throw new InferenceException(Kind.AUTH, "the model key (" + KEY_VARIABLE + ") holds a character a request"
                    + " header cannot carry (only printable ASCII, no space); set the variable again without it");
        }
        String baseUrl = environment.apply(BASE_URL_VARIABLE);
        String baseUrlProblem = baseUrlProblem(baseUrl);
        if (baseUrlProblem != null) {
            throw new InferenceException(Kind.AUTH, BASE_URL_VARIABLE + " " + baseUrlProblem + "; set it again (the"
                    + " key travels to that address)");
        }
        boolean fromSettings = settings.workspaceId() != null;
        String workspace = fromSettings ? settings.workspaceId() : environment.apply(WORKSPACE_VARIABLE);
        if (workspace != null && !workspace.isBlank() && !WORKSPACE_ID.matcher(workspace.strip()).matches()) {
            throw new InferenceException(Kind.AUTH, "the workspace id (" + (fromSettings ? "workspaceId of the"
                    + " inference settings" : WORKSPACE_VARIABLE) + ") is not a workspace id (letters, digits, '_',"
                    + " '-', '.' and ':', at most 200)");
        }
        PriceTable prices;
        try {
            prices = PriceTable.of(settings);
        } catch (SettingsException e) {
            throw new InferenceException(Kind.INVALID_REQUEST, "the price table cannot be read: " + e.getMessage());
        }
        Config config = Config.of(settings, prices);
        AnthropicClient client;
        try {
            client = client(key, baseUrl, workspace, config.callMs());
        } catch (RuntimeException e) {
            // Never the exception's message: it may quote a value of the environment.
            throw new InferenceException(Kind.INVALID_REQUEST, "the provider's client cannot be built from "
                    + KEY_VARIABLE + ", " + BASE_URL_VARIABLE + " and the workspace id (" + e.getClass().getSimpleName()
                    + ")");
        }
        return new ClaudeGateway(client, config, List.of(key.strip(), workspace == null ? "" : workspace.strip()),
                Clock.systemUTC(), Thread::sleep);
    }

    /**
     * Why {@code ANTHROPIC_BASE_URL} may not be used, never quoting it, or null when it may (or is not set): it must
     * parse as an absolute URL with a host, use {@code https} unless the host is {@code 127.0.0.1}, {@code ::1} or
     * {@code localhost} (a local proxy or a test's fake), and carry no user-info (phase6 security fix S51: the key
     * travels to that address).
     */
    static String baseUrlProblem(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        java.net.URI uri;
        try {
            uri = new java.net.URI(baseUrl.strip());
        } catch (java.net.URISyntaxException e) {
            return "is not a URL";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = uri.getHost();
        if (!uri.isAbsolute() || host == null || host.isBlank()) {
            return "is not an absolute URL with a host";
        }
        if (uri.getRawUserInfo() != null) {
            return "carries a user name or password";
        }
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        boolean loopback = bare.equals("127.0.0.1") || bare.equals("::1") || bare.equals("0:0:0:0:0:0:0:1")
                || bare.equalsIgnoreCase("localhost");
        if (scheme.equals("https") || scheme.equals("http") && loopback) {
            return null;
        }
        return "must use https (http only for 127.0.0.1, ::1 or localhost)";
    }

    /** True when every character is printable ASCII without the space (0x21 to 0x7E): what a header value carries. */
    static boolean headerSafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * The SDK client as the gateway builds it: the key given (never {@code fromEnv()}), the base URL when given, the
     * SDK's retries and logging off, its timeout the call deadline, the workspace header when given.
     */
    static AnthropicClient client(String key, String baseUrl, String workspace, long callMs) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(key.strip()).maxRetries(0)
                .logLevel(LogLevel.OFF).timeout(Duration.ofMillis(callMs));
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl.strip());
        }
        if (workspace != null && !workspace.isBlank()) {
            builder.putHeader(WORKSPACE_HEADER, workspace.strip());
        }
        return builder.build();
    }

    @Override
    public String provider() {
        return ClaudeMapping.PROVIDER;
    }

    @Override
    public Set<GatewayCapability> capabilities() {
        Set<GatewayCapability> caps = EnumSet.of(GatewayCapability.STRUCTURED_OUTPUT, GatewayCapability.TOOLS,
                GatewayCapability.CACHING, GatewayCapability.BATCH, GatewayCapability.TOKEN_COUNT,
                GatewayCapability.PROVIDER_TOOLS);
        if (config.images()) {
            caps.add(GatewayCapability.IMAGES);
        }
        return Collections.unmodifiableSet(caps);
    }

    @Override
    public ModelResponse complete(ModelRequest request) throws InferenceException, InterruptedException {
        return complete(request, null);
    }

    @Override
    public ModelResponse complete(ModelRequest request, Duration timeout)
            throws InferenceException, InterruptedException {
        String model = model(request.modelClass());
        MessageCreateParams params = ClaudeMapping.params(request, model, config.fallbacks(), config.images());
        long started = System.nanoTime();
        var message = call("the completion", timeout, options -> client.messages().create(params, options));
        return ClaudeMapping.response(ClaudeMapping.json(message), request, model, false,
                Duration.ofNanos(System.nanoTime() - started), config.prices(), secrets);
    }

    @Override
    public long countTokens(ModelRequest request) throws InferenceException {
        String model = model(request.modelClass());
        Map<String, Object> body = new java.util.LinkedHashMap<>(ClaudeMapping.body(
                ClaudeMapping.params(request, model, false, config.images())));
        body.remove("max_tokens");
        MessageCountTokensParams params = MessageCountTokensParams.builder().body(ClaudeMapping.MAPPER.convertValue(
                body, MessageCountTokensParams.Body.class)).build();
        return uninterrupted(() -> call("the token count", null,
                options -> client.messages().countTokens(params, options).inputTokens()));
    }

    @Override
    public BatchHandle submitBatch(List<ModelRequest> requests) throws InferenceException {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("a batch holds at least one request");
        }
        List<BatchCreateParams.Request> items = new ArrayList<>();
        List<String> models = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            String model = model(requests.get(i).modelClass());
            models.add(model);
            // Never the refusal fallback in a batch: the provider rejects it (BS13 F-7).
            Map<String, Object> body = ClaudeMapping.body(ClaudeMapping.params(requests.get(i), model, false,
                    config.images()));
            items.add(BatchCreateParams.Request.builder().customId(Integer.toString(i))
                    .params(ClaudeMapping.MAPPER.convertValue(body, BatchCreateParams.Request.Params.class)).build());
        }
        BatchCreateParams params = BatchCreateParams.builder().requests(items).build();
        String id = uninterrupted(() -> call("the batch submit", null,
                options -> client.messages().batches().create(params, options).id()));
        batches.put(id, new Submitted(List.copyOf(requests), List.copyOf(models)));
        return new BatchHandle(provider(), id, clock.instant(), requests.size());
    }

    @Override
    public BatchStatus batchStatus(BatchHandle handle) throws InferenceException {
        BatchRetrieveParams params = BatchRetrieveParams.builder().messageBatchId(handle.id()).build();
        Map<String, Object> json = uninterrupted(() -> call("the batch status", null,
                options -> ClaudeMapping.json(client.messages().batches().retrieve(params, options))));
        BatchStatus.State state = switch (String.valueOf(json.get("processing_status"))) {
            case "ended" -> BatchStatus.State.ENDED;
            case "canceling" -> BatchStatus.State.CANCELING;
            default -> BatchStatus.State.IN_PROGRESS;
        };
        Map<?, ?> counts = json.get("request_counts") instanceof Map<?, ?> m ? m : Map.of();
        return new BatchStatus(state, count(counts, "succeeded"), count(counts, "errored"), count(counts, "expired"),
                count(counts, "canceled"), count(counts, "processing"));
    }

    @Override
    public List<BatchResult> collectBatch(BatchHandle handle) throws InferenceException {
        BatchResultsParams params = BatchResultsParams.builder().messageBatchId(handle.id()).build();
        List<Map<String, Object>> lines = uninterrupted(() -> call("the batch results", null, options -> {
            try (StreamResponse<MessageBatchIndividualResponse> results = client.messages().batches()
                    .resultsStreaming(params, options)) {
                List<Map<String, Object>> out = new ArrayList<>();
                results.stream().forEach(r -> out.add(ClaudeMapping.json(r)));
                return out;
            }
        }));
        Submitted submitted = batches.get(handle.id());
        BatchResult[] byIndex = new BatchResult[handle.requests()];
        for (Map<String, Object> line : lines) {
            int index = index(line.get("custom_id"), handle.requests());
            if (index < 0) {
                continue;
            }
            byIndex[index] = result(index, line, submitted);
        }
        List<BatchResult> out = new ArrayList<>(byIndex.length);
        for (int i = 0; i < byIndex.length; i++) {
            out.add(byIndex[i] != null ? byIndex[i] : new BatchResult(i, null,
                    new InferenceException(Kind.PROVIDER, "no result for batch request " + i)));
        }
        return List.copyOf(out);
    }

    @Override
    public void cancelBatch(BatchHandle handle) throws InferenceException {
        BatchCancelParams params = BatchCancelParams.builder().messageBatchId(handle.id()).build();
        uninterrupted(() -> call("the batch cancel", null,
                options -> client.messages().batches().cancel(params, options).id()));
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            client.close();
        }
    }

    /** The provider and the model ids; never the key or the workspace id. */
    @Override
    public String toString() {
        return "ClaudeGateway[" + config.models().values() + "]";
    }

    // ------------------------------------------------------------------------------------------------ internals

    private String model(ModelClass modelClass) throws InferenceException {
        String id = config.models().get(modelClass);
        if (id == null) {
            throw new InferenceException(Kind.INVALID_REQUEST, "the settings name no model for " + modelClass);
        }
        return id;
    }

    private BatchResult result(int index, Map<String, Object> line, Submitted submitted) {
        Map<?, ?> result = line.get("result") instanceof Map<?, ?> m ? m : Map.of();
        String type = String.valueOf(result.get("type"));
        ModelRequest request = submitted == null ? null : submitted.requests().get(index);
        String model = submitted == null ? null : submitted.models().get(index);
        switch (type) {
            case "succeeded" -> {
                if (result.get("message") instanceof Map<?, ?> message) {
                    try {
                        return new BatchResult(index, ClaudeMapping.response(message, request, model, true,
                                Duration.ZERO, config.prices(), secrets), null);
                    } catch (InferenceException e) {
                        return new BatchResult(index, null, e);
                    }
                }
                return new BatchResult(index, null, new InferenceException(Kind.PROVIDER,
                        "batch request " + index + " succeeded without a message"));
            }
            case "errored" -> {
                Map<?, ?> outer = result.get("error") instanceof Map<?, ?> m ? m : Map.of();
                Map<?, ?> inner = outer.get("error") instanceof Map<?, ?> m ? m : outer;
                String errorType = String.valueOf(inner.get("type"));
                String message = inner.get("message") instanceof String s ? s : errorType;
                // S54: scrubbed before the cut, as the gateway's RuntimeException branch does; the type bounded too
                // (phase6 security fix, pass 2, P2-L5-F3).
                return new BatchResult(index, null, new InferenceException(kindOf(errorType), ClaudeMapping.scrub(
                        "batch request " + index + ": " + ClaudeMapping.bounded(ClaudeMapping.scrub(errorType,
                                secrets), ClaudeMapping.WORD_MAX) + ": "
                                + ClaudeMapping.bounded(ClaudeMapping.scrub(message, secrets), 300), secrets)));
            }
            case "expired" -> {
                return new BatchResult(index, null, new InferenceException(Kind.TIMEOUT, "batch request " + index
                        + " expired before the provider processed it"));
            }
            case "canceled" -> {
                return new BatchResult(index, null, new InferenceException(Kind.PROVIDER, "batch request " + index
                        + " was canceled"));
            }
            default -> {
                return new BatchResult(index, null, new InferenceException(Kind.PROVIDER, "batch request " + index
                        + " has a result of an unknown type"));
            }
        }
    }

    private static Kind kindOf(String errorType) {
        return switch (errorType) {
            case "invalid_request_error", "not_found_error", "request_too_large" -> Kind.INVALID_REQUEST;
            case "authentication_error", "permission_error" -> Kind.AUTH;
            case "billing_error" -> Kind.BILLING;
            case "rate_limit_error" -> Kind.RATE_LIMIT;
            case "overloaded_error" -> Kind.OVERLOADED;
            default -> Kind.PROVIDER;
        };
    }

    private static int index(Object customId, int size) {
        if (!(customId instanceof String s) || !s.matches("\\d{1,9}")) {
            return -1;
        }
        int i = Integer.parseInt(s);
        return i < size ? i : -1;
    }

    private static int count(Map<?, ?> counts, String key) {
        return counts.get(key) instanceof Number n ? n.intValue() : 0;
    }

    /** One SDK call under the deadline, with the gateway's retries. */
    @FunctionalInterface
    private interface Attempt<T> {
        T run(RequestOptions options);
    }

    @FunctionalInterface
    private interface Interruptible<T> {
        T run() throws InferenceException, InterruptedException;
    }

    /** A batch method or a token count: an interrupt is {@code TIMEOUT}, with the interrupt flag set again. */
    private static <T> T uninterrupted(Interruptible<T> call) throws InferenceException {
        try {
            return call.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InferenceException(Kind.TIMEOUT, "interrupted");
        }
    }

    /**
     * Runs {@code attempt} within {@code timeout} (null: the call deadline) or the call deadline, whichever is shorter:
     * each attempt gets what is left; {@code RATE_LIMIT}, {@code OVERLOADED} and {@code TRANSPORT} are tried again at
     * most {@value #MAX_RETRIES} times, after the provider's {@code retry-after} or the gateway's back-off (0.5, 1 s),
     * only while the wait fits before the deadline.
     */
    private <T> T call(String what, Duration timeout, Attempt<T> attempt)
            throws InferenceException, InterruptedException {
        if (closed.get()) {
            throw new InferenceException(Kind.UNSUPPORTED, "the gateway is closed");
        }
        long budget = timeout == null ? config.callMs() : Math.min(config.callMs(), Math.max(0, timeout.toMillis()));
        long deadline = clock.millis() + budget;
        for (int tries = 0; ; tries++) {
            long left = deadline - clock.millis();
            if (left <= 0) {
                throw new InferenceException(Kind.TIMEOUT, what + " ran out of its " + budget + " ms");
            }
            InferenceException failure;
            Duration retryAfter = null;
            try {
                return attempt.run(RequestOptions.builder().timeout(Duration.ofMillis(left)).build());
            } catch (AnthropicServiceException e) {
                failure = ClaudeMapping.classify(e, secrets);
                retryAfter = ClaudeMapping.retryAfter(e);
            } catch (AnthropicIoException e) {
                if (Thread.interrupted()) {
                    throw new InterruptedException(what + " was interrupted");
                }
                boolean timedOut = timedOut(e) || clock.millis() >= deadline;
                failure = new InferenceException(timedOut ? Kind.TIMEOUT : Kind.TRANSPORT, what + (timedOut
                        ? " timed out" : " could not reach the provider (" + rootName(e) + ")"));
            } catch (AnthropicException e) {
                failure = new InferenceException(Kind.PROVIDER, what + ": the provider's answer could not be read ("
                        + e.getClass().getSimpleName() + ")");
            } catch (RuntimeException e) {
                // Anything else the SDK or its HTTP client throws (an IllegalArgumentException quoting a header's
                // value, say) leaves as PROVIDER with the known secrets scrubbed and no cause: the SPI throws
                // InferenceException only, and nothing of the exception may carry the key on.
                if (Thread.interrupted()) {
                    throw new InterruptedException(what + " was interrupted");
                }
                String message = e.getMessage();
                throw new InferenceException(Kind.PROVIDER, what + " failed (" + e.getClass().getSimpleName() + ")"
                        + (message == null ? "" : ": " + ClaudeMapping.bounded(ClaudeMapping.scrub(message, secrets),
                        300)));
            }
            if (!failure.retryable() || tries >= MAX_RETRIES) {
                throw failure;
            }
            long wait = retryAfter != null ? retryAfter.toMillis()
                    : Math.min(MAX_BACKOFF_MS, FIRST_BACKOFF_MS << tries);
            // Compared as a difference: a wait near Long.MAX_VALUE must not overflow past the deadline.
            if (wait >= deadline - clock.millis()) {
                throw failure;
            }
            sleeper.sleep(wait);
        }
    }

    private static boolean timedOut(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof TimeoutException) {
                return true;
            }
            if (t instanceof InterruptedIOException && String.valueOf(t.getMessage()).contains("timeout")) {
                return true;
            }
        }
        return false;
    }

    private static String rootName(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName();
    }
}
