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

package ai.skillroy.testjava.spikes.bs13;

import com.anthropic.backends.AnthropicBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientImpl;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.client.okhttp.OkHttpClient;
import com.anthropic.core.ClientOptions;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.Timeout;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.HttpClient;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpRequestBody;
import com.anthropic.core.http.HttpResponse;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.ErrorType;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCountTokensParams;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RefusalStopDetails;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolChoiceTool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.models.models.ModelRetrieveParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Spike BS13: checks, live and once, the Claude API facts that the Phase 5 contract relies on, through the Anthropic
 * Java SDK version the contract pins. One sub-command per group of facts (see {@link #usage()}); each writes
 * {@code target/results/<command>.json} with the HTTP exchanges as the SDK sent and received them (method, path, the
 * request body, the values of a short list of harmless request headers, the response status, request id and body)
 * and the findings derived from them.
 *
 * <p>The model key: the SDK reads {@code ANTHROPIC_API_KEY} from the environment; this class never reads it except to
 * check that no report contains it before the report is written. The recorder sits between the SDK's retry and
 * logging layers and its OkHttp transport; the transport adds the key header itself, below the recorder, so the key
 * never passes through it. Response headers are not recorded except the request id.
 */
public final class Bs13Probe {

    static final String SONNET = "claude-sonnet-5-5";
    static final String OPUS = "claude-opus-5-5";
    static final String HAIKU = "claude-haiku-4-5";
    /** The only key value the {@code auth} sub-command accepts (it must never run with a real key). */
    static final String AUTH_TEST_KEY = "invalid-key-for-the-spike";
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    static final String FALLBACK_ARRAY_BETA = "server-side-fallback-2026-06-01";

    static final JsonMapper MAPPER = ObjectMappers.jsonMapper();

    /** Request headers whose values are recorded; of every other header only the name is recorded. */
    static final Set<String> REQUEST_HEADER_VALUES = Set.of("anthropic-beta", "content-type", "accept",
            "x-stainless-retry-count", "x-stainless-package-version", "x-stainless-lang",
            "x-stainless-runtime-version");

    /** The rules of a tool-library lending desk: a system part long enough to be cached (about 650 tokens). */
    static final List<String> DESK_RULES = List.of(
            "Every item in the tool library has a catalogue number, a category (hand tool, power tool, ladder, garden, measuring) and a condition grade from A to D.",
            "Members may borrow up to four items at a time; walk-in borrowers may borrow one item and must show a proof of address.",
            "The standard loan is seven days for hand tools and measuring tools, three days for power tools, and two days for ladders.",
            "A loan may be extended once by the same length if no other borrower has reserved the item.",
            "Every loan prints a slip with the catalogue number, the borrower's card number, the due date and the deposit taken.",
            "Power tools and ladders carry a refundable deposit: twenty pounds for power tools and thirty pounds for ladders.",
            "Garden tools carry a deposit of ten pounds; hand tools and measuring tools carry no deposit.",
            "A member with three or more completed loans and no late return in the last year qualifies for the deposit waiver.",
            "The deposit waiver never applies to ladders, and a supervisor must countersign the slip when it is used.",
            "Late returns cost fifty pence per item per day, capped at the item's deposit or at five pounds when there is none.",
            "A returned item is inspected at the desk; a drop of one condition grade is normal wear and costs nothing.",
            "A drop of two or more condition grades is damage: the desk keeps the deposit and records the damage on the item.",
            "Items graded D are withdrawn from lending until they are repaired and re-graded by a volunteer.",
            "Batteries and chargers are lent with their power tool and are listed on the same slip as separate lines.",
            "A reservation holds an item for two days after it is returned; an unclaimed reservation lapses without charge.",
            "Children's tool kits may be borrowed only by members, for fourteen days, and carry no deposit.",
            "Refunds of deposits are paid in the form the deposit was taken: cash for cash, card for card.",
            "A lost item is charged at its replacement value minus any deposit held; the slip records the loss.",
            "The desk closes its day with a count of items out, items due and deposits held, printed as a day slip.",
            "Corrections to a slip are made by voiding it and printing a new one; a voided slip is kept for audit.",
            "Volunteers may not lend to themselves; another volunteer or the supervisor processes their loans.",
            "When rules conflict, the stricter rule applies and the supervisor is told before the loan is made.");

    static final int SHORT_RULES = Integer.getInteger("bs13.shortRules", 9);

    static String deskRules(String edition, int count) {
        StringBuilder sb = new StringBuilder("You answer questions about the rules of a tool-library lending desk. Desk rules, ")
                .append(edition).append(".\n");
        for (int i = 0; i < count; i++) {
            sb.append("Rule ").append(i + 1).append(". ").append(DESK_RULES.get(i)).append('\n');
        }
        return sb.append("Answer with the rule number and nothing else unless asked.").toString();
    }

    /** The structured answer of the cache and batch requests: {answer: string}. */
    static Map<String, Object> answerSchema() {
        return map("type", "object", "additionalProperties", false, "required", List.of("answer"),
                "properties", map("answer", map("type", "string")));
    }

    static final String LATE_FEE_PROMPT = "At the lending desk, late returns cost 50 pence per item per day, capped at "
            + "the item's deposit. A borrower returns a hedge trimmer (deposit 20 pounds) 3 days late, a ladder (deposit "
            + "30 pounds) 70 days late and a tape measure (no deposit, so the cap is 5 pounds) 12 days late. What is the "
            + "total late fee in pence? Answer with the number only.";

    private final String command;
    private final String variant;
    private final Recorder recorder = new Recorder();
    private final ObjectNode findings = MAPPER.createObjectNode();
    private final Instant started = Instant.now();
    private AnthropicClient api;

    private Bs13Probe(String command, String variant) {
        this.command = command;
        this.variant = variant;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(64);
        }
        if ("live".equals(args[0])) {
            System.exit(live());
        }
        String variant = args.length > 1 ? args[1] : "";
        System.exit(new Bs13Probe(args[0], variant).run());
    }

    /**
     * Every live item in one run, in an order that keeps the one-hour cache entry of {@code cache} warm for
     * {@code batch}; stops when the recorded spend passes {@code -Dbs13.maxSpendUsd} (default 1.00). Ends with
     * {@code spend}.
     */
    static int live() throws Exception {
        double limit = Double.parseDouble(System.getProperty("bs13.maxSpendUsd", "1.00"));
        String[][] steps = {{"models", ""}, {"count", ""}, {"errors", ""}, {"structured", ""}, {"tools", ""},
                {"thinking", "sonnet"}, {"thinking", "opus"}, {"fallbacks", ""}, {"cache", ""}, {"batch", ""}};
        double spent = 0;
        int failures = 0;
        for (String[] step : steps) {
            Bs13Probe probe = new Bs13Probe(step[0], step[1]);
            System.out.println("== " + String.join(" ", step).strip());
            int rc = probe.run();
            if (rc == 64) {
                return 64;
            }
            failures += rc == 0 ? 0 : 1;
            spent += probe.costOfExchanges();
            System.out.printf("== spent so far: %.6f USD%n", spent);
            if (spent > limit) {
                System.out.printf("== stopping: spend %.4f USD passed the limit %.2f USD%n", spent, limit);
                break;
            }
        }
        new Bs13Probe("spend", "").run();
        return failures == 0 ? 0 : 1;
    }

    static void usage() {
        System.err.println("""
                usage: java [-Dbs13.keyVariable=NAME] [-Dbs13.workspaceVariable=NAME] -jar bs13-probe.jar <command> [variant]
                  offline (no key, no spend): client | shapes | transport | spend
                  free (key, no tokens):      models | count | errors
                  live (key; spends a little): structured | tools | thinking sonnet|opus | fallbacks | cache | batch [msgbatch_id]
                  live                         all of the above in one run (stops past -Dbs13.maxSpendUsd, default 1.00)
                  auth   only with ANTHROPIC_API_KEY=invalid-key-for-the-spike (expects HTTP 401)
                the key: ANTHROPIC_API_KEY, or the variable named by -Dbs13.keyVariable
                the workspace id (keys not scoped to a workspace): ANTHROPIC_WORKSPACE_ID, or -Dbs13.workspaceVariable
                results: target/results/<command>[-variant].json""");
    }

    /**
     * The environment variable that holds the model key: {@code ANTHROPIC_API_KEY} (what the SDK's {@code fromEnv()}
     * reads), or the variable named by {@code -Dbs13.keyVariable} when the key cannot be put under that name for the
     * one command (the probe then hands the value to the SDK's backend builder). The value is never printed, logged
     * or written; only its presence is reported.
     */
    static String keyVariable() {
        return System.getProperty("bs13.keyVariable", "ANTHROPIC_API_KEY");
    }

    static String key() {
        return System.getenv(keyVariable());
    }

    /**
     * {@code -Dbs13.dryRun=true}: nothing leaves the machine. The recorder records each request as the SDK serialises
     * it and answers with a canned response (a structured answer made from the request's own schema, a tool call made
     * from the tool's own schema, canned batches and counts), so that every live flow runs and every request shape is
     * recorded without a key. The responses are not evidence of anything; the reports say so.
     */
    static final boolean DRY_RUN = Boolean.getBoolean("bs13.dryRun");

    /**
     * The workspace id for a key that is not scoped to a workspace: {@code ANTHROPIC_WORKSPACE_ID}, or the variable
     * named by {@code -Dbs13.workspaceVariable}. Sent as the {@code anthropic-workspace-id} header (SDK 2.34.0 sends
     * it by itself only for profile, OAuth and federation credentials, not with a static key); an account identifier,
     * so never printed or written either.
     */
    static String workspace() {
        String value = System.getenv(System.getProperty("bs13.workspaceVariable", "ANTHROPIC_WORKSPACE_ID"));
        return value == null || value.isBlank() ? null : value.strip();
    }

    int run() throws Exception {
        Set<String> offline = Set.of("client", "shapes", "transport", "spend");
        boolean keyPresent = key() != null;
        if (DRY_RUN) {
            findings.put("dryRun", "requests recorded as the SDK serialises them; every response is canned by the probe and is not evidence");
            recorder.dryRun = true;
        } else if (!offline.contains(command) && !keyPresent) {
            System.err.println("the model key is not set (ANTHROPIC_API_KEY); '" + command + "' calls the API (see README.md)");
            return 64;
        }
        if ("auth".equals(command)
                && (!"ANTHROPIC_API_KEY".equals(keyVariable()) || !AUTH_TEST_KEY.equals(System.getenv("ANTHROPIC_API_KEY")))) {
            System.err.println("'auth' runs only with ANTHROPIC_API_KEY=" + AUTH_TEST_KEY);
            return 64;
        }
        findings.put("keyPresent", keyPresent);
        findings.put("keyReadBy", "ANTHROPIC_API_KEY".equals(keyVariable())
                ? "the SDK (AnthropicBackend.fromEnv)" : "the probe, from a named variable, handed to AnthropicBackend.Builder.apiKey");
        int rc = 0;
        try {
            switch (command) {
                case "client" -> client();
                case "shapes" -> shapes();
                case "transport" -> transport();
                case "models" -> models();
                case "count" -> count();
                case "structured" -> structured();
                case "tools" -> tools();
                case "thinking" -> thinking();
                case "cache" -> cache();
                case "batch" -> batch();
                case "fallbacks" -> fallbacks();
                case "errors" -> errors();
                case "auth" -> auth();
                case "spend" -> {
                    spend();
                    return 0;
                }
                default -> {
                    usage();
                    return 64;
                }
            }
        } catch (Exception e) {
            recordError(findings.putObject("abortedBy"), e);
            rc = 1;
        } finally {
            write();
        }
        return rc;
    }

    // ------------------------------------------------------------------------------------------------- clients

    /**
     * The composition {@code AnthropicOkHttpClient.fromEnv()} makes (ClientOptions from the environment, the
     * Anthropic backend from the environment, the OkHttp transport), with the recorder as the raw HTTP client that
     * the SDK wraps in its logging and retrying layers.
     */
    static AnthropicClient recordedClient(Recorder recorder, AnthropicBackend backend,
                                          Consumer<ClientOptions.Builder> tweak) {
        ClientOptions.Builder options = ClientOptions.builder().fromEnv();
        tweak.accept(options);
        HttpClient raw = OkHttpClient.builder().timeout(options.timeout()).backend(backend).build();
        backend.applyCredentials(raw, options);
        recorder.delegate = raw;
        return new AnthropicClientImpl(options.httpClient(recorder).build());
    }

    AnthropicClient api() {
        if (api == null) {
            AnthropicBackend backend = DRY_RUN
                    ? AnthropicBackend.builder().baseUrl("http://dry-run.invalid").apiKey("dry-run-placeholder").build()
                    : "ANTHROPIC_API_KEY".equals(keyVariable())
                    ? AnthropicBackend.fromEnv()
                    : AnthropicBackend.builder().fromEnv().apiKey(key()).build();
            String workspace = workspace();
            findings.put("workspaceHeaderSent", workspace != null);
            api = recordedClient(recorder, backend, b -> {
                if (workspace != null) {
                    b.putHeader("anthropic-workspace-id", workspace);
                }
            });
        }
        return api;
    }

    static MessageCreateParams.Builder base(String model, OutputConfig.Effort effort, long maxTokens) {
        return MessageCreateParams.builder().model(model).maxTokens(maxTokens)
                .outputConfig(OutputConfig.builder().effort(effort).build());
    }

    // ------------------------------------------------------------------------------------------------- client

    /** Fact 9 (settings): the client's retry and timeout defaults, without a network call. */
    void client() {
        ClientOptions.Builder builder = ClientOptions.builder().fromEnv();
        Timeout t = builder.timeout();
        ObjectNode d = findings.putObject("defaults");
        d.put("timeout.connect", t.connect().toString());
        d.put("timeout.read", t.read().toString());
        d.put("timeout.write", t.write().toString());
        d.put("timeout.request", t.request().toString());
        ClientOptions built = builder.httpClient(new Unreachable()).build();
        d.put("maxRetries", built.maxRetries());
        d.put("logLevel", String.valueOf(built.logLevel()));
        d.put("responseValidation", built.responseValidation());
        d.put("defaultHeaderNames", new TreeSet<>(built.headers().names()).toString());
        built.close();
        AnthropicClient fromEnv = AnthropicOkHttpClient.fromEnv();
        AnthropicClient tuned = fromEnv.withOptions(o -> o.maxRetries(0).timeout(Duration.ofSeconds(30)));
        findings.put("fromEnvBuildsWithoutNetwork", true);
        findings.put("withOptionsIsPerClient", tuned != fromEnv);
        findings.put("perRequestTimeout", "RequestOptions.builder().timeout(Duration) on every service call");
        fromEnv.close();
        System.out.println(findings.toPrettyString());
    }

    // ------------------------------------------------------------------------------------------------- shapes

    /** Fact 8 and the 2.34.0 model of responses it does not know: offline parsing of documented shapes. */
    void shapes() throws Exception {
        ObjectNode api = findings.putObject("javaApi");
        api.put("Message.stopDetails()", Message.class.getMethod("stopDetails").getGenericReturnType().getTypeName());
        api.put("Message.stopReason()", Message.class.getMethod("stopReason").getGenericReturnType().getTypeName());
        api.put("RefusalStopDetails.category()",
                RefusalStopDetails.class.getMethod("category").getGenericReturnType().getTypeName());
        api.put("RefusalStopDetails.explanation()",
                RefusalStopDetails.class.getMethod("explanation").getGenericReturnType().getTypeName());
        api.put("StopReason constants", constants(StopReason.class).toString());
        api.put("RefusalStopDetails.Category constants", constants(RefusalStopDetails.Category.class).toString());
        api.put("OutputConfig.Effort constants", constants(OutputConfig.Effort.class).toString());
        api.put("ErrorType constants", constants(ErrorType.class).toString());

        // A refusal as the docs describe it (HTTP 200, stop_reason "refusal", stop_details, empty content).
        String refusal = """
                {"id":"msg_offline_refusal","type":"message","role":"assistant","model":"claude-sonnet-5-5",
                 "content":[],"stop_reason":"refusal","stop_sequence":null,
                 "stop_details":{"type":"refusal","category":"frontier_llm","explanation":"(synthetic)",
                                 "recommended_model":null},
                 "usage":{"input_tokens":12,"output_tokens":0}}""";
        Message r = MAPPER.readValue(refusal, Message.class);
        ObjectNode rf = findings.putObject("refusal");
        rf.put("stopReason", r.stopReason().map(StopReason::asString).orElse("(absent)"));
        rf.put("isRefusal", r.stopReason().map(StopReason.REFUSAL::equals).orElse(false));
        rf.put("stopReasonValue", r.stopReason().map(s -> String.valueOf(s.value())).orElse("(absent)"));
        Optional<RefusalStopDetails> details = r.stopDetails();
        rf.put("stopDetailsPresent", details.isPresent());
        details.ifPresent(sd -> {
            rf.put("category.asString", sd.category().map(RefusalStopDetails.Category::asString).orElse("(null)"));
            rf.put("category.value", sd.category().map(c -> String.valueOf(c.value())).orElse("(null)"));
            rf.put("explanation", sd.explanation().orElse("(null)"));
            rf.put("additionalProperties", sd._additionalProperties().keySet().toString());
        });
        rf.put("contentSize", r.content().size());
        String cyber = refusal.replace("frontier_llm", "cyber");
        rf.put("cyber.value", MAPPER.readValue(cyber, Message.class).stopDetails()
                .flatMap(RefusalStopDetails::category).map(c -> String.valueOf(c.value())).orElse("(null)"));
        String nullCategory = refusal.replace("\"frontier_llm\"", "null");
        rf.put("nullCategory.present", MAPPER.readValue(nullCategory, Message.class).stopDetails()
                .flatMap(RefusalStopDetails::category).isPresent());
        Message plain = MAPPER.readValue(refusal.replace("\"refusal\",\"stop_sequence\"", "\"end_turn\",\"stop_sequence\"")
                .replaceAll("\"stop_details\":\\{[^}]*\\}", "\"stop_details\":null"), Message.class);
        rf.put("endTurn.stopDetailsPresent", plain.stopDetails().isPresent());

        // A response that a server-side fallback served, with the documented "fallback" block and usage.iterations.
        String fallback = """
                {"id":"msg_offline_fallback","type":"message","role":"assistant","model":"claude-sonnet-5",
                 "content":[{"type":"fallback","from":{"model":"claude-sonnet-5-5"},"to":{"model":"claude-sonnet-5"}},
                            {"type":"text","text":"OK"}],
                 "stop_reason":"end_turn","stop_sequence":null,"stop_details":null,
                 "usage":{"input_tokens":12,"output_tokens":2,
                          "iterations":[{"type":"message","model":"claude-sonnet-5-5","input_tokens":12,"output_tokens":0},
                                        {"type":"fallback_message","model":"claude-sonnet-5","input_tokens":12,"output_tokens":2}]}}""";
        Message f = MAPPER.readValue(fallback, Message.class);
        ObjectNode fb = findings.putObject("fallbackServed");
        fb.put("contentSize", f.content().size());
        ArrayNode blocks = fb.putArray("blocks");
        for (ContentBlock b : f.content()) {
            ObjectNode o = blocks.addObject();
            o.put("isText", b.isText());
            o.put("isValid", b.isValid());
            o.put("rawJson", b._json().map(String::valueOf).orElse("(none)"));
            try {
                b.accept(new ContentBlock.Visitor<String>() {
                    @Override public String visitText(com.anthropic.models.messages.TextBlock text) { return "text"; }
                    @Override public String visitThinking(com.anthropic.models.messages.ThinkingBlock t) { return "thinking"; }
                    @Override public String visitRedactedThinking(com.anthropic.models.messages.RedactedThinkingBlock t) { return "redacted"; }
                    @Override public String visitToolUse(ToolUseBlock t) { return "tool_use"; }
                    @Override public String visitServerToolUse(com.anthropic.models.messages.ServerToolUseBlock t) { return "server_tool_use"; }
                    @Override public String visitWebSearchToolResult(com.anthropic.models.messages.WebSearchToolResultBlock t) { return "web_search"; }
                    @Override public String visitWebFetchToolResult(com.anthropic.models.messages.WebFetchToolResultBlock t) { return "web_fetch"; }
                    @Override public String visitCodeExecutionToolResult(com.anthropic.models.messages.CodeExecutionToolResultBlock t) { return "code"; }
                    @Override public String visitBashCodeExecutionToolResult(com.anthropic.models.messages.BashCodeExecutionToolResultBlock t) { return "bash"; }
                    @Override public String visitTextEditorCodeExecutionToolResult(com.anthropic.models.messages.TextEditorCodeExecutionToolResultBlock t) { return "editor"; }
                    @Override public String visitToolSearchToolResult(com.anthropic.models.messages.ToolSearchToolResultBlock t) { return "tool_search"; }
                    @Override public String visitContainerUpload(com.anthropic.models.messages.ContainerUploadBlock t) { return "container_upload"; }
                });
                o.put("visitorWithoutUnknown", "returned");
            } catch (RuntimeException e) {
                o.put("visitorWithoutUnknown", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        fb.put("textJoined", textOf(f));
        fb.put("model", f.model().asString());
        fb.put("usage.additionalProperties", f.usage()._additionalProperties().keySet().toString());
        try {
            f.validate();
            fb.put("validate", "passed");
        } catch (RuntimeException e) {
            fb.put("validate", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        System.out.println(findings.toPrettyString());
    }

    static List<String> constants(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Field field : type.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
                names.add(field.getName());
            }
        }
        return names;
    }

    // ------------------------------------------------------------------------------------------------- transport

    /** Fact 9 (retries and deadlines), against local sockets only: refused connections and a server that hangs. */
    void transport() throws Exception {
        MessageCreateParams params = base(SONNET, OutputConfig.Effort.LOW, 16).addUserMessage("unused").build();

        // (a) connection refused: what is thrown, how many attempts, how long.
        int closedPort;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort();
        }
        ObjectNode a = findings.putObject("a connection refused, defaults");
        recorder.label = "a refused";
        AnthropicClient refused = recordedClient(recorder, localBackend(closedPort), o -> { });
        long t0 = System.nanoTime();
        try {
            refused.messages().create(params);
        } catch (RuntimeException e) {
            recordError(a, e);
        }
        a.put("elapsedMs", ms(t0));
        a.put("attempts", count("a refused"));
        a.set("retryCountHeaders", headerValues("a refused", "X-Stainless-Retry-Count"));

        // (b) a server that accepts and never answers; client timeout 1 s; synchronous call; defaults otherwise.
        try (HangingServer server = new HangingServer()) {
            ObjectNode b = findings.putObject("b hanging server, sync, timeout 1 s, maxRetries 2");
            recorder.label = "b hanging sync";
            AnthropicClient c = recordedClient(recorder, localBackend(server.port()), o -> o.timeout(Duration.ofSeconds(1)));
            t0 = System.nanoTime();
            try {
                c.messages().create(params);
            } catch (RuntimeException e) {
                recordError(b, e);
            }
            b.put("elapsedMs", ms(t0));
            b.put("attempts", count("b hanging sync"));
            b.set("acceptsAtMs", MAPPER.valueToTree(server.accepts()));
        }

        // (c) the same, asynchronous, cancelled after 1.5 s; client timeout 2 s: does the call (and its retries) stop?
        try (HangingServer server = new HangingServer()) {
            ObjectNode c = findings.putObject("c hanging server, async, cancelled at 1.5 s, timeout 2 s");
            recorder.label = "c hanging async";
            AnthropicClient client = recordedClient(recorder, localBackend(server.port()), o -> o.timeout(Duration.ofSeconds(2)));
            t0 = System.nanoTime();
            CompletableFuture<Message> future = client.async().messages().create(params);
            try {
                future.get(1500, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                c.put("cancelReturned", future.cancel(true));
                c.put("cancelledAtMs", ms(t0));
            }
            Thread.sleep(9000);
            c.set("acceptsAtMs", MAPPER.valueToTree(server.accepts()));
            c.put("acceptsAfterCancel", server.accepts().stream().filter(x -> x > 1600).count());
            c.put("recordedAttempts", count("c hanging async"));
            c.set("attemptEndsAtMs", elapsedOf("c hanging async"));
        }

        // (e) the asynchronous path against a refused connection: are transport failures retried there?
        ObjectNode e5 = findings.putObject("e connection refused, async, defaults");
        recorder.label = "e refused async";
        AnthropicClient refusedAsync = recordedClient(recorder, localBackend(closedPort), o -> { });
        t0 = System.nanoTime();
        try {
            refusedAsync.async().messages().create(params).get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            recordError(e5, e.getCause());
        }
        e5.put("elapsedMs", ms(t0));
        e5.put("attempts", count("e refused async"));

        // (d) the gateway's own deadline: maxRetries 0 and a per-request timeout of 1.5 s.
        try (HangingServer server = new HangingServer()) {
            ObjectNode d = findings.putObject("d hanging server, maxRetries 0, per-request timeout 1.5 s");
            recorder.label = "d deadline";
            AnthropicClient client = recordedClient(recorder, localBackend(server.port()), o -> o.maxRetries(0));
            t0 = System.nanoTime();
            try {
                client.messages().create(params, RequestOptions.builder().timeout(Duration.ofMillis(1500)).build());
            } catch (RuntimeException e) {
                recordError(d, e);
            }
            d.put("elapsedMs", ms(t0));
            d.put("attempts", count("d deadline"));
        }
        System.out.println(findings.toPrettyString());
    }

    static AnthropicBackend localBackend(int port) {
        return AnthropicBackend.builder().baseUrl("http://127.0.0.1:" + port).apiKey("offline-placeholder").build();
    }

    long count(String label) {
        synchronized (recorder.exchanges) {
            return recorder.exchanges.stream().filter(e -> label.equals(e.path("label").asText())).count();
        }
    }

    ArrayNode elapsedOf(String label) {
        ArrayNode values = MAPPER.createArrayNode();
        synchronized (recorder.exchanges) {
            for (ObjectNode e : recorder.exchanges) {
                if (label.equals(e.path("label").asText())) {
                    values.add(e.path("elapsedMs").asLong());
                }
            }
        }
        return values;
    }

    ArrayNode headerValues(String label, String header) {
        ArrayNode values = MAPPER.createArrayNode();
        synchronized (recorder.exchanges) {
            for (ObjectNode e : recorder.exchanges) {
                if (label.equals(e.path("label").asText())) {
                    values.add(e.path("request").path("headers").path(header).asText("(absent)"));
                }
            }
        }
        return values;
    }

    /** A loopback server that accepts connections and never answers; it records when each connection came. */
    static final class HangingServer implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final long start = System.nanoTime();
        private final List<Long> accepts = Collections.synchronizedList(new ArrayList<>());
        private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());

        HangingServer() throws IOException {
            Thread thread = new Thread(this::serve, "bs13-hanging-server");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        List<Long> accepts() {
            synchronized (accepts) {
                return new ArrayList<>(accepts);
            }
        }

        private void serve() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    accepts.add((System.nanoTime() - start) / 1_000_000);
                    sockets.add(socket);
                } catch (IOException e) {
                    return;
                }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            synchronized (sockets) {
                for (Socket s : sockets) {
                    s.close();
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- models

    /** The Models API (free): limits, capabilities, and the allowed fallback models under the fallback beta. */
    void models() {
        AnthropicClient c = api();
        for (String id : List.of(SONNET, OPUS, HAIKU)) {
            recorder.label = "models " + id;
            c.models().retrieve(id);
            ObjectNode m = findings.putObject(id);
            JsonNode body = lastBody();
            m.put("max_input_tokens", body.path("max_input_tokens").asLong());
            m.put("max_tokens", body.path("max_tokens").asLong());
            m.set("capabilities", body.path("capabilities"));
            for (String beta : List.of(FALLBACK_ARRAY_BETA, FALLBACK_BETA)) {
                recorder.label = "models " + id + " " + beta;
                try {
                    c.models().retrieve(ModelRetrieveParams.builder().modelId(id).addBeta(beta).build());
                    m.set("allowed_fallback_models (" + beta + ")", lastBody().path("allowed_fallback_models"));
                } catch (AnthropicServiceException e) {
                    recordError(m.putObject("error (" + beta + ")"), e);
                }
            }
        }
        System.out.println(findings.toPrettyString());
    }

    // ------------------------------------------------------------------------------------------------- count

    /** Free: the token counts of the cache probes' system parts, to size them before anything is spent. */
    void count() {
        AnthropicClient c = api();
        String question = "Which rule number covers ladders? Reply in the answer field.";
        long overhead = countTokens(c, "count overhead", "Desk rules.", question, null);
        findings.put("overhead (system 'Desk rules.' + question)", overhead);
        for (String[] v : new String[][] {{"edition 1", "22"}, {"edition 2", "22"}, {"edition 3", String.valueOf(SHORT_RULES)}}) {
            String system = deskRules(v[0], Integer.parseInt(v[1]));
            long total = countTokens(c, "count " + v[0], system, question, null);
            ObjectNode o = findings.putObject(v[0]);
            o.put("rules", Integer.parseInt(v[1]));
            o.put("characters", system.length());
            o.put("requestTokens", total);
            o.put("systemPartTokensApprox", total - overhead);
        }
        System.out.println(findings.toPrettyString());
    }

    long countTokens(AnthropicClient c, String label, String system, String question, CacheControlEphemeral cache) {
        recorder.label = label;
        TextBlockParam.Builder sys = TextBlockParam.builder().text(system);
        if (cache != null) {
            sys.cacheControl(cache);
        }
        return c.messages().countTokens(MessageCountTokensParams.builder().model(SONNET)
                .systemOfTextBlockParams(List.of(sys.build()))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW)
                        .format(JsonOutputFormat.builder().schema(schemaOf(answerSchema())).build()).build())
                .addUserMessage(question).build()).inputTokens();
    }

    // ------------------------------------------------------------------------------------------------- structured

    /** A record with two fields for the typed overload. */
    public record LoanTerms(String itemCategory, int loanDays) { }

    static final String LOAN_PROMPT = "A member borrows a cordless drill on Friday and brings it back on Monday. "
            + "Give the item category (one of: hand tool, power tool, ladder, garden, measuring) and the number of loan "
            + "days, counting Friday to Monday as three days.";

    /** Fact 1: structured output, the typed overload, the manual form, and schemas beyond the published limits. */
    void structured() throws Exception {
        AnthropicClient c = api();

        // 1a: the typed overload. The effort set before it is part of the experiment: does it survive?
        recorder.label = "1a outputConfig(LoanTerms.class)";
        StructuredMessageCreateParams<LoanTerms> typed = base(SONNET, OutputConfig.Effort.LOW, 1024)
                .outputConfig(LoanTerms.class)
                .addUserMessage(LOAN_PROMPT)
                .build();
        ObjectNode a = findings.putObject("1a typed");
        a.put("effortAfterOutputConfigClass",
                typed.rawParams().outputConfig().flatMap(OutputConfig::effort).map(String::valueOf).orElse("(absent)"));
        try {
            StructuredMessage<LoanTerms> answer = c.messages().create(typed);
            a.set("sentSchema", lastRequest().path("body").path("output_config"));
            a.set("response", summarize(lastBody()));
            a.put("stopReason", answer.stopReason().map(StopReason::asString).orElse("?"));
            List<LoanTerms> values = answer.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).toList();
            a.put("parsed", values.toString());
        } catch (RuntimeException e) {
            recordError(a.putObject("error"), e);
        }

        // 1b: the manual form with additionalProperties false, required, an enum, a nested object and an array.
        Map<String, Object> schema = decisionSchema(Map.of());
        recorder.label = "1b manual JsonOutputFormat";
        ObjectNode b = findings.putObject("1b manual");
        try {
            Message m = c.messages().create(decisionParams(schema));
            b.set("response", summarize(lastBody()));
            String text = textOf(m);
            b.put("text", text);
            JsonNode parsed = MAPPER.readTree(text);
            List<String> problems = new ArrayList<>();
            check(MAPPER.valueToTree(schema), parsed, "$", problems);
            b.put("parses", true);
            b.put("schemaValid", problems.isEmpty());
            b.set("problems", MAPPER.valueToTree(problems));
        } catch (IOException e) {
            b.put("parses", false);
            b.put("parseError", e.getMessage());
        } catch (RuntimeException e) {
            recordError(b.putObject("error"), e);
        }

        // 1c-1e: schemas beyond the published limits (each expected to be refused; nothing is retried).
        String[][] variants = {
                {"1c numeric minimum", "minimum"},
                {"1d string maxLength", "maxLength"},
                {"1e minItems 2", "minItems"},
        };
        for (String[] v : variants) {
            recorder.label = v[0];
            ObjectNode o = findings.putObject(v[0]);
            Map<String, Map<String, Object>> extra = switch (v[1]) {
                case "minimum" -> Map.of("days", map("minimum", 1));
                case "maxLength" -> Map.of("item", map("maxLength", 40));
                default -> Map.of("reasons", map("minItems", 2));
            };
            try {
                Message ok = c.messages().create(decisionParams(decisionSchema(extra)));
                o.put("accepted", true);
                o.set("response", summarize(lastBody()));
                o.put("text", textOf(ok));
            } catch (AnthropicServiceException e) {
                o.put("accepted", false);
                recordError(o, e);
            }
        }
        System.out.println(findings.toPrettyString());
    }

    /** The decision schema of 1b, with extra keywords merged into named properties for 1c-1e. */
    static Map<String, Object> decisionSchema(Map<String, Map<String, Object>> extra) {
        Map<String, Object> item = merge(map("type", "string", "description", "the item lent"), extra.get("item"));
        Map<String, Object> days = merge(map("type", "integer", "description", "loan length in days"), extra.get("days"));
        Map<String, Object> reasons = merge(map("type", "array", "items", map("type", "string")), extra.get("reasons"));
        return map("type", "object", "additionalProperties", false,
                "required", List.of("decision", "loan", "reasons"),
                "properties", map(
                        "decision", map("type", "string", "enum", List.of("lend", "refuse", "refer")),
                        "loan", map("type", "object", "additionalProperties", false, "required", List.of("item", "days"),
                                "properties", map("item", item, "days", days)),
                        "reasons", reasons));
    }

    static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> extra) {
        if (extra != null) {
            base.putAll(extra);
        }
        return base;
    }

    static MessageCreateParams decisionParams(Map<String, Object> schema) {
        return MessageCreateParams.builder().model(SONNET).maxTokens(1024)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW)
                        .format(JsonOutputFormat.builder().schema(schemaOf(schema)).build()).build())
                .system("You decide loans at a tool-library lending desk. Ladders are lent for at most two days; a "
                        + "walk-in borrower may borrow one item and must show a proof of address.")
                .addUserMessage("A walk-in borrower who has shown a proof of address asks to borrow a ladder for "
                        + "three days. Decide, and give the loan you would make and your reasons.")
                .build();
    }

    // ------------------------------------------------------------------------------------------------- tools

    static Tool recordLoanTool() {
        return Tool.builder().name("record_loan").description("Records one loan at the lending desk.")
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("item", JsonValue.from(map("type", "string", "description", "the item lent")))
                                .putAdditionalProperty("days", JsonValue.from(map("type", "integer", "description", "loan length in days")))
                                .putAdditionalProperty("borrower", JsonValue.from(map("type", "string", "enum", List.of("member", "walk_in"))))
                                .build())
                        .required(List.of("item", "days", "borrower"))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    /** Fact 2: a strict tool with tool choice auto; forced tool choice; and what a tool loop must echo back. */
    void tools() {
        AnthropicClient c = api();
        MessageCreateParams first = base(SONNET, OutputConfig.Effort.MEDIUM, 1024)
                .system("You are the assistant of a tool-library lending desk. When the user describes a loan, call "
                        + "the record_loan tool once with its details, then confirm the loan in one short sentence.")
                .addTool(recordLoanTool())
                .toolChoice(ToolChoiceAuto.builder().build())
                .addUserMessage("A walk-in borrower is taking the hedge trimmer for two days.")
                .build();

        // 2a: tool choice auto with an instruction naming the tool.
        recorder.label = "2a strict tool, auto";
        ObjectNode a = findings.putObject("2a strict tool, auto");
        Message m;
        try {
            m = c.messages().create(first);
        } catch (RuntimeException e) {
            recordError(a.putObject("error"), e);
            System.out.println(findings.toPrettyString());
            return;
        }
        a.set("sentTool", lastRequest().path("body").path("tools"));
        a.set("sentToolChoice", lastRequest().path("body").path("tool_choice"));
        a.set("response", summarize(lastBody()));
        Optional<ToolUseBlock> use = m.content().stream().flatMap(b -> b.toolUse().stream()).findFirst();
        a.put("toolCalled", use.isPresent());
        JsonNode input = MAPPER.createObjectNode();
        for (JsonNode block : lastBody().path("content")) {
            if ("tool_use".equals(block.path("type").asText())) {
                input = block.path("input");
            }
        }
        List<String> problems = new ArrayList<>();
        check(MAPPER.valueToTree(map("type", "object", "additionalProperties", false,
                "required", List.of("item", "days", "borrower"),
                "properties", map("item", map("type", "string"), "days", map("type", "integer"),
                        "borrower", map("type", "string", "enum", List.of("member", "walk_in"))))), input, "$", problems);
        a.put("inputSchemaValid", use.isPresent() && problems.isEmpty());
        a.set("inputProblems", MAPPER.valueToTree(problems));
        boolean hadThinking = m.content().stream().anyMatch(ContentBlock::isThinking);
        a.put("responseHadThinkingBlock", hadThinking);

        // 2b: forced tool choice (expected HTTP 400; one call, not retried).
        recorder.label = "2b forced tool choice";
        ObjectNode b = findings.putObject("2b forced tool choice");
        try {
            c.messages().create(first.toBuilder().toolChoice(ToolChoiceTool.builder().name("record_loan").build()).build());
            b.put("accepted", true);
            b.set("response", summarize(lastBody()));
        } catch (AnthropicServiceException e) {
            b.put("accepted", false);
            recordError(b, e);
        }

        if (use.isEmpty()) {
            findings.put("2c/2d", "skipped: no tool call to answer");
            System.out.println(findings.toPrettyString());
            return;
        }
        ContentBlockParam result = ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(use.get().id()).content("Recorded as loan 4711.").build());

        // 2c: the assistant turn echoed without its thinking blocks (what the SPI's Content types can carry today).
        recorder.label = "2c echo without thinking blocks";
        ObjectNode cc = findings.putObject("2c echo without thinking blocks");
        cc.put("meaningful", hadThinking);
        List<ContentBlockParam> stripped = m.content().stream()
                .filter(x -> !x.isThinking() && !x.isRedactedThinking()).map(ContentBlock::toParam).toList();
        try {
            c.messages().create(first.toBuilder().addAssistantMessageOfBlockParams(stripped)
                    .addUserMessageOfBlockParams(List.of(result)).build());
            cc.put("accepted", true);
            cc.set("response", summarize(lastBody()));
        } catch (AnthropicServiceException e) {
            cc.put("accepted", false);
            recordError(cc, e);
        }

        // 2d: the assistant turn echoed unchanged, thinking blocks included.
        recorder.label = "2d echo unchanged";
        ObjectNode d = findings.putObject("2d echo unchanged");
        try {
            c.messages().create(first.toBuilder().addMessage(m.toParam()).addUserMessageOfBlockParams(List.of(result)).build());
            d.put("accepted", true);
            d.set("response", summarize(lastBody()));
        } catch (AnthropicServiceException e) {
            d.put("accepted", false);
            recordError(d, e);
        }
        System.out.println(findings.toPrettyString());
    }

    // ------------------------------------------------------------------------------------------------- thinking

    /** Fact 3: no thinking field, effort low (and medium on Sonnet): what comes back, and the usage fields. */
    void thinking() {
        AnthropicClient c = api();
        List<Object[]> runs = "opus".equals(variant)
                ? List.<Object[]>of(new Object[] {OPUS, OutputConfig.Effort.LOW})
                : List.<Object[]>of(new Object[] {SONNET, OutputConfig.Effort.LOW}, new Object[] {SONNET, OutputConfig.Effort.MEDIUM});
        for (Object[] run : runs) {
            String model = (String) run[0];
            OutputConfig.Effort effort = (OutputConfig.Effort) run[1];
            String label = "3 " + model + " effort " + effort.asString();
            recorder.label = label;
            ObjectNode o = findings.putObject(label);
            try {
                Message m = c.messages().create(base(model, effort, 1024).addUserMessage(LATE_FEE_PROMPT).build());
                o.put("thinkingFieldSent", lastRequest().path("body").has("thinking"));
                o.set("sentOutputConfig", lastRequest().path("body").path("output_config"));
                o.set("response", summarize(lastBody()));
                o.put("answer", textOf(m));
                o.set("usageFields", MAPPER.valueToTree(fieldNames(lastBody().path("usage"))));
            } catch (RuntimeException e) {
                recordError(o.putObject("error"), e);
            }
        }
        System.out.println(findings.toPrettyString());
    }

    // ------------------------------------------------------------------------------------------------- cache

    /** Facts 4 and 5: prompt caching on Sonnet 5.5 with structured answers, and the token count of the same request. */
    void cache() {
        AnthropicClient c = api();
        String question = "Which rule number covers ladders? Reply in the answer field.";
        CacheControlEphemeral fiveMinutes = CacheControlEphemeral.builder().build();
        CacheControlEphemeral oneHour = CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build();
        String edition1 = deskRules("edition 1", DESK_RULES.size());
        String edition2 = deskRules("edition 2", DESK_RULES.size());
        String edition3 = deskRules("edition 3", SHORT_RULES);

        cacheCall(c, "4a first (5 min)", edition1, fiveMinutes, question);
        cacheCall(c, "4b second (5 min)", edition1, fiveMinutes, question);
        cacheCall(c, "4c one hour (edition 2)", edition2, oneHour, question);
        cacheCall(c, "4d short prefix", edition3, fiveMinutes, question);
        cacheCall(c, "4e short prefix again", edition3, fiveMinutes, question);

        // Fact 5: count_tokens for the requests of 4a and 4d, against what the responses reported.
        for (String[] v : new String[][] {{"4a first (5 min)", edition1}, {"4d short prefix", edition3}}) {
            ObjectNode o = findings.putObject("5 count_tokens like " + v[0]);
            try {
                long counted = countTokens(c, "5 count_tokens like " + v[0], v[1], question, fiveMinutes);
                JsonNode usage = findings.path(v[0]).path("usage");
                long reported = usage.path("input_tokens").asLong() + usage.path("cache_creation_input_tokens").asLong()
                        + usage.path("cache_read_input_tokens").asLong();
                o.put("counted", counted);
                o.put("reportedInputPlusCache", reported);
                o.put("difference", counted - reported);
                o.put("differencePercent", reported == 0 ? 0 : Math.round(10000.0 * (counted - reported) / reported) / 100.0);
            } catch (RuntimeException e) {
                recordError(o.putObject("error"), e);
            }
        }
        System.out.println(findings.toPrettyString());
    }

    void cacheCall(AnthropicClient c, String label, String system, CacheControlEphemeral cache, String question) {
        recorder.label = label;
        Instant at = Instant.now();
        ObjectNode o = findings.putObject(label);
        o.put("at", at.toString());
        o.put("systemCharacters", system.length());
        try {
            Message m = c.messages().create(MessageCreateParams.builder().model(SONNET).maxTokens(256)
                    .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(system).cacheControl(cache).build()))
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW)
                            .format(JsonOutputFormat.builder().schema(schemaOf(answerSchema())).build()).build())
                    .addUserMessage(question).build());
            o.set("sentCacheControl", lastRequest().path("body").path("system").path(0).path("cache_control"));
            o.put("requestId", lastResponse().path("requestId").asText());
            o.set("usage", lastBody().path("usage"));
            o.put("answer", textOf(m));
        } catch (RuntimeException e) {
            recordError(o.putObject("error"), e);
        }
    }

    // ------------------------------------------------------------------------------------------------- batch

    /** Fact 6: a batch of two, polled to the end (bounded), results matched by custom_id; and fallbacks in a batch. */
    void batch() throws Exception {
        AnthropicClient c = api();
        String system = deskRules("edition 2", DESK_RULES.size());
        CacheControlEphemeral oneHour = CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build();
        List<String> questions = List.of(
                "Which rule number sets the deposit for ladders? Reply in the answer field.",
                "Which rule number sets the fee for late returns? Reply in the answer field.");
        ObjectNode o = findings.putObject("6 batch");
        ObjectNode x = findings.putObject("6x fallbacks in a batch request");
        MessageBatch batch;
        Instant submitted = Instant.now();
        String fallbackBatch = null;
        if (variant.startsWith("msgbatch_")) {
            // Collect a batch submitted by an earlier run (its own bound ran out).
            recorder.label = "6 retrieve earlier batch";
            batch = c.messages().batches().retrieve(variant);
            submitted = batch.createdAt().toInstant();
            o.put("id", batch.id());
            o.put("collectedLater", true);
            x.put("skipped", "collect-only run");
        } else {
            BatchCreateParams.Builder request = BatchCreateParams.builder();
            for (int i = 0; i < questions.size(); i++) {
                request.addRequest(BatchCreateParams.Request.builder().customId("bs13-q" + i)
                        .params(batchParams(system, oneHour, questions.get(i)).build()).build());
            }
            recorder.label = "6 create";
            submitted = Instant.now();
            batch = c.messages().batches().create(request.build());
            o.put("id", batch.id());
            o.put("submittedAt", submitted.toString());
            o.put("statusAtSubmit", batch.processingStatus().asString());
            o.set("sentRequest0", lastRequest().path("body").path("requests").path(0));
            fallbackBatch = submitFallbackBatch(c, x);
        }
        pollAndCollect(c, batch, submitted, fallbackBatch, o, x);
    }

    /** 6x: the server-side fallback inside a batch request (documented as rejected on the Batches API). */
    String submitFallbackBatch(AnthropicClient c, ObjectNode x) {
        String fallbackBatch = null;
        recorder.label = "6x create with fallbacks";
        try {
            MessageBatch fb = c.messages().batches().create(BatchCreateParams.builder()
                    .putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .addRequest(BatchCreateParams.Request.builder().customId("bs13-fallback")
                            .params(BatchCreateParams.Request.Params.builder().model(SONNET).maxTokens(64)
                                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                                    .addUserMessage("Say OK.")
                                    .putAdditionalProperty("fallbacks", JsonValue.from("default"))
                                    .build())
                            .build())
                    .build());
            fallbackBatch = fb.id();
            x.put("createAccepted", true);
            x.put("id", fb.id());
            x.set("sentRequest", lastRequest().path("body").path("requests").path(0));
            x.set("sentHeaders", lastRequest().path("headers"));
        } catch (AnthropicServiceException e) {
            x.put("createAccepted", false);
            recordError(x, e);
        }
        return fallbackBatch;
    }

    /** Polls until both batches have ended (bounded), then streams the results and matches them by custom_id. */
    void pollAndCollect(AnthropicClient c, MessageBatch batch, Instant submitted, String fallbackBatch,
                        ObjectNode o, ObjectNode x) throws Exception {
        Duration bound = Duration.ofMinutes(Long.getLong("bs13.batchWaitMinutes", 20));
        long pollMs = Long.getLong("bs13.batchPollMs", 15000);
        String mainStatus = batch.processingStatus().asString();
        String fallbackStatus = fallbackBatch == null ? "ended" : "in_progress";
        int polls = 0;
        Instant endedAt = null;
        while (Duration.between(submitted, Instant.now()).compareTo(bound) < 0) {
            Thread.sleep(pollMs);
            polls++;
            recorder.label = "6 poll";
            MessageBatch now = c.messages().batches().retrieve(batch.id());
            mainStatus = now.processingStatus().asString();
            if ("ended".equals(mainStatus) && endedAt == null) {
                endedAt = Instant.now();
                o.set("requestCountsAtEnd", lastBody().path("request_counts"));
                o.put("endedAtServer", now.endedAt().map(Object::toString).orElse("?"));
            }
            if (fallbackBatch != null && !"ended".equals(fallbackStatus)) {
                recorder.label = "6x poll";
                fallbackStatus = c.messages().batches().retrieve(fallbackBatch).processingStatus().asString();
            }
            System.out.printf("  poll %d: %s / fallback batch %s (%d s)%n", polls, mainStatus, fallbackStatus,
                    Duration.between(submitted, Instant.now()).toSeconds());
            if ("ended".equals(mainStatus) && "ended".equals(fallbackStatus)) {
                break;
            }
        }
        o.put("polls", polls);
        o.put("pollIntervalMs", pollMs);
        o.put("finalStatus", mainStatus);
        o.put("secondsToEnded", endedAt == null ? -1 : Duration.between(submitted, endedAt).toSeconds());
        if (!"ended".equals(mainStatus)) {
            o.put("note", "not ended within the bound; collect later with: batch " + batch.id());
            return;
        }

        // Results, streamed, matched by custom_id.
        recorder.label = "6 results";
        Map<String, MessageBatchIndividualResponse> byId = new TreeMap<>();
        try (StreamResponse<MessageBatchIndividualResponse> results = c.messages().batches().resultsStreaming(batch.id())) {
            results.stream().forEach(r -> byId.put(r.customId(), r));
        }
        ObjectNode res = o.putObject("results");
        List<String> order = new ArrayList<>();
        for (JsonNode line : lastBody()) {
            order.add(line.path("custom_id").asText());
        }
        o.set("resultOrderAsStreamed", MAPPER.valueToTree(order));
        for (Map.Entry<String, MessageBatchIndividualResponse> e : byId.entrySet()) {
            ObjectNode r = res.putObject(e.getKey());
            r.put("type", e.getValue().result().isSucceeded() ? "succeeded" : String.valueOf(e.getValue().result()._json()));
            e.getValue().result().succeeded().ifPresent(s -> {
                r.put("answer", textOf(s.message()));
                r.put("model", s.message().model().asString());
                r.put("messageId", s.message().id());
            });
            e.getValue().result().errored().ifPresent(err -> r.put("error", String.valueOf(err.error())));
        }
        for (JsonNode line : lastBody()) {
            res.with(line.path("custom_id").asText()).set("usage", line.path("result").path("message").path("usage"));
        }
        if (fallbackBatch != null && "ended".equals(fallbackStatus)) {
            recorder.label = "6x results";
            try (StreamResponse<MessageBatchIndividualResponse> results = c.messages().batches().resultsStreaming(fallbackBatch)) {
                results.stream().forEach(r -> x.put("result", String.valueOf(r.result()._json().orElse(null))));
            }
            x.set("resultLines", lastBody());
        }
        System.out.println(findings.toPrettyString());
    }

    static BatchCreateParams.Request.Params.Builder batchParams(String system, CacheControlEphemeral cache, String question) {
        return BatchCreateParams.Request.Params.builder().model(SONNET).maxTokens(256)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(system).cacheControl(cache).build()))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW)
                        .format(JsonOutputFormat.builder().schema(schemaOf(answerSchema())).build()).build())
                .addUserMessage(question);
    }

    // ------------------------------------------------------------------------------------------------- fallbacks

    /** Fact 7: the server-side refusal fallback in its "default" form on Sonnet 5.5. */
    void fallbacks() {
        AnthropicClient c = api();

        // 7a: the beta messages call with the beta header and "fallbacks": "default" as an extra body property.
        recorder.label = "7a beta, fallbacks default";
        ObjectNode a = findings.putObject("7a beta endpoint, fallbacks default");
        try {
            BetaMessage m = c.beta().messages().create(com.anthropic.models.beta.messages.MessageCreateParams.builder()
                    .model(SONNET).maxTokens(256)
                    .addBeta(FALLBACK_BETA)
                    .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .addUserMessage("Say OK.")
                    .build());
            a.put("accepted", true);
            a.set("sentHeaders", lastRequest().path("headers"));
            a.set("sentQuery", lastRequest().path("query"));
            a.set("sentBody", lastRequest().path("body"));
            a.set("response", lastBody());
            a.put("model", m.model().asString());
            a.put("usage.iterations", m.usage().iterations().map(String::valueOf).orElse("(absent)"));
        } catch (AnthropicServiceException e) {
            a.put("accepted", false);
            recordError(a, e);
        }

        // 7b: the same on the GA messages call, header and body property added by hand.
        recorder.label = "7b GA endpoint, fallbacks default";
        ObjectNode b = findings.putObject("7b GA endpoint, fallbacks default");
        try {
            c.messages().create(base(SONNET, OutputConfig.Effort.LOW, 256)
                    .putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .addUserMessage("Say OK.").build());
            b.put("accepted", true);
            b.set("sentHeaders", lastRequest().path("headers"));
            b.set("response", lastBody());
        } catch (AnthropicServiceException e) {
            b.put("accepted", false);
            recordError(b, e);
        }

        // 7c: "fallbacks": "default" without the beta header (expected HTTP 400).
        recorder.label = "7c without the beta header";
        ObjectNode cc = findings.putObject("7c without the beta header");
        try {
            c.messages().create(base(SONNET, OutputConfig.Effort.LOW, 256)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .addUserMessage("Say OK.").build());
            cc.put("accepted", true);
            cc.set("response", lastBody());
        } catch (AnthropicServiceException e) {
            cc.put("accepted", false);
            recordError(cc, e);
        }
        System.out.println(findings.toPrettyString());
    }

    // ------------------------------------------------------------------------------------------------- errors

    /** Fact 9: exception classes and error types for an unknown model (404) and a malformed request (400). */
    void errors() {
        AnthropicClient c = api();
        recorder.label = "9a unknown model";
        ObjectNode a = findings.putObject("9a unknown model");
        try {
            c.messages().create(base("claude-bs13-no-such-model", OutputConfig.Effort.LOW, 16).addUserMessage("x").build());
            a.put("accepted", true);
        } catch (RuntimeException e) {
            recordError(a, e);
        }
        recorder.label = "9b malformed (empty messages)";
        ObjectNode b = findings.putObject("9b malformed (empty messages)");
        try {
            c.messages().create(base(SONNET, OutputConfig.Effort.LOW, 16).messages(List.of()).build());
            b.put("accepted", true);
        } catch (RuntimeException e) {
            recordError(b, e);
        }
        System.out.println(findings.toPrettyString());
    }

    /** Fact 9: a wrong key (401), through the exact construction the contract names. */
    void auth() {
        ObjectNode a = findings.putObject("9c wrong key");
        AnthropicClient c = AnthropicOkHttpClient.fromEnv();
        try {
            c.messages().create(base(HAIKU, OutputConfig.Effort.LOW, 16).addUserMessage("x").build());
            a.put("accepted", true);
        } catch (RuntimeException e) {
            recordError(a, e);
        } finally {
            c.close();
        }
        System.out.println(findings.toPrettyString());
    }

    // ------------------------------------------------------------------------------------------------- spend

    /** Sums the cost of every recorded result file and every CLI envelope in the results directory. */
    void spend() throws IOException {
        Path dir = outDir();
        double total = 0;
        try (var files = Files.list(dir)) {
            for (Path p : files.sorted().toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".json") || name.equals("spend.json")) {
                    continue;
                }
                JsonNode node = MAPPER.readTree(p.toFile());
                double usd = node.has("findings") ? node.path("findings").path("costUsd").asDouble()
                        : node.path("total_cost_usd").asDouble();
                total += usd;
                findings.put(name, round6(usd));
            }
        }
        findings.put("totalUsd", round6(total));
        System.out.println(findings.toPrettyString());
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(findings);
        guard(json);
        Files.writeString(dir.resolve("spend.json"), json);
    }

    // ------------------------------------------------------------------------------------------------- reporting

    void write() throws IOException {
        ObjectNode report = MAPPER.createObjectNode();
        report.put("command", command + (variant.isEmpty() ? "" : " " + variant));
        report.put("startedAt", started.toString());
        report.put("java", System.getProperty("java.version"));
        report.put("costUsd", round6(costOfExchanges()));
        findings.put("costUsd", round6(costOfExchanges()));
        report.set("findings", findings);
        ArrayNode exchanges = report.putArray("exchanges");
        synchronized (recorder.exchanges) {
            recorder.exchanges.forEach(exchanges::add);
        }
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        guard(json);
        Path dir = outDir();
        Files.createDirectories(dir);
        Path file = dir.resolve(command + (variant.isEmpty() ? "" : "-" + variant) + ".json");
        Files.writeString(file, json);
        System.out.println("written: " + file + " (cost " + round6(costOfExchanges()) + " USD)");
    }

    static Path outDir() {
        String configured = System.getProperty("bs13.out");
        if (configured != null) {
            return Path.of(configured);
        }
        String name = DRY_RUN ? "results-dryrun" : "results";
        try {
            Path jar = Path.of(Bs13Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.getParent().resolve(name);
        } catch (Exception e) {
            return Path.of("target", name);
        }
    }

    /** Refuses to let a report contain the model key (whole, or its first 24 or last 16 characters). */
    static void guard(String text) {
        String workspace = workspace();
        if (workspace != null && workspace.length() >= 8 && text.contains(workspace)) {
            throw new IllegalStateException("refusing to write the report: it would contain the workspace id");
        }
        for (String key : new String[] {key(), System.getenv("ANTHROPIC_API_KEY")}) {
            if (key == null || key.length() < 40 || AUTH_TEST_KEY.equals(key)) {
                continue;
            }
            if (text.contains(key) || text.contains(key.substring(0, 24))
                    || text.contains(key.substring(key.length() - 16))) {
                throw new IllegalStateException("refusing to write the report: it would contain the model key");
            }
        }
    }

    record Price(double input, double output, double cacheRead) { }

    /** The built-in table of phase5-contracts.md 7.2 (USD per million tokens), plus the documented fallback target. */
    static final Map<String, Price> PRICES = Map.of(
            OPUS, new Price(4.00, 20.00, 0.20),
            SONNET, new Price(2.00, 10.00, 0.20),
            "claude-sonnet-5", new Price(2.00, 10.00, 0.20),
            HAIKU, new Price(1.00, 5.00, 0.10));

    static double cost(String model, JsonNode usage, boolean batch) {
        Price p = PRICES.getOrDefault(model, PRICES.get(OPUS));
        long in = usage.path("input_tokens").asLong();
        long out = usage.path("output_tokens").asLong();
        long read = usage.path("cache_read_input_tokens").asLong();
        long created = usage.path("cache_creation_input_tokens").asLong();
        JsonNode split = usage.path("cache_creation");
        long w1h = split.path("ephemeral_1h_input_tokens").asLong();
        long w5m = split.isMissingNode() || split.isNull() ? created : split.path("ephemeral_5m_input_tokens").asLong();
        double usd = (in * p.input() + out * p.output() + read * p.cacheRead()
                + w5m * p.input() * 1.25 + w1h * p.input() * 2.0) / 1_000_000.0;
        return batch ? usd * 0.5 : usd;
    }

    double costOfExchanges() {
        double total = 0;
        synchronized (recorder.exchanges) {
            for (ObjectNode ex : recorder.exchanges) {
                if (ex.path("response").path("status").asInt() != 200) {
                    continue;
                }
                JsonNode body = ex.path("response").path("body");
                if (body.isObject() && body.has("usage") && body.has("model")) {
                    total += cost(body.path("model").asText(), body.path("usage"), false);
                } else if (body.isArray()) {
                    for (JsonNode line : body) {
                        JsonNode msg = line.path("result").path("message");
                        if (msg.has("usage")) {
                            total += cost(msg.path("model").asText(), msg.path("usage"), true);
                        }
                    }
                }
            }
        }
        return total;
    }

    static double round6(double usd) {
        return Math.round(usd * 1_000_000.0) / 1_000_000.0;
    }

    JsonNode lastExchange() {
        synchronized (recorder.exchanges) {
            return recorder.exchanges.isEmpty() ? MAPPER.createObjectNode() : recorder.exchanges.getLast();
        }
    }

    JsonNode lastRequest() {
        return lastExchange().path("request");
    }

    JsonNode lastResponse() {
        return lastExchange().path("response");
    }

    JsonNode lastBody() {
        return lastResponse().path("body");
    }

    /** id, model, stop reason and details, the content blocks (thinking text and signatures by length), usage. */
    static ObjectNode summarize(JsonNode body) {
        ObjectNode s = MAPPER.createObjectNode();
        s.put("id", body.path("id").asText());
        s.put("model", body.path("model").asText());
        s.put("stop_reason", body.path("stop_reason").asText());
        s.set("stop_details", body.path("stop_details"));
        ArrayNode blocks = s.putArray("content");
        for (JsonNode block : body.path("content")) {
            ObjectNode b = blocks.addObject();
            String type = block.path("type").asText();
            b.put("type", type);
            switch (type) {
                case "thinking" -> {
                    b.put("thinkingLength", block.path("thinking").asText().length());
                    b.put("signature", block.path("signature").asText());
                }
                case "text" -> b.put("text", block.path("text").asText());
                case "tool_use" -> {
                    b.put("name", block.path("name").asText());
                    b.set("input", block.path("input"));
                }
                default -> b.set("raw", block);
            }
        }
        s.set("usage", body.path("usage"));
        return s;
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    static String textOf(Message m) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : m.content()) {
            b.text().ifPresent(t -> sb.append(t.text()));
        }
        return sb.toString();
    }

    ObjectNode recordError(ObjectNode into, Throwable e) {
        into.put("exception", e.getClass().getName());
        if (e instanceof AnthropicServiceException s) {
            into.put("status", s.statusCode());
            into.put("errorType", s.errorType().map(ErrorType::asString).orElse("(none)"));
            try {
                into.set("body", MAPPER.readTree(MAPPER.writeValueAsString(s.body())));
            } catch (IOException io) {
                into.put("body", String.valueOf(s.body()));
            }
            List<String> rid = s.headers().values("request-id");
            if (!rid.isEmpty()) {
                into.put("requestId", rid.get(0));
            }
        }
        into.put("message", String.valueOf(e.getMessage()));
        if (e.getCause() != null) {
            into.put("cause", e.getCause().getClass().getName() + ": " + e.getCause().getMessage());
        }
        return into;
    }

    static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static JsonOutputFormat.Schema schemaOf(Map<String, Object> schema) {
        JsonOutputFormat.Schema.Builder b = JsonOutputFormat.Schema.builder();
        schema.forEach((k, v) -> b.putAdditionalProperty(k, JsonValue.from(v)));
        return b.build();
    }

    /** A checker for the subset of JSON Schema the probe's schemas use. */
    static void check(JsonNode schema, JsonNode value, String path, List<String> problems) {
        switch (schema.path("type").asText("")) {
            case "object" -> {
                if (!value.isObject()) {
                    problems.add(path + ": not an object");
                    return;
                }
                JsonNode props = schema.path("properties");
                for (JsonNode r : schema.path("required")) {
                    if (!value.has(r.asText())) {
                        problems.add(path + ": missing " + r.asText());
                    }
                }
                if (schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").asBoolean()) {
                    value.fieldNames().forEachRemaining(f -> {
                        if (!props.has(f)) {
                            problems.add(path + ": unexpected property " + f);
                        }
                    });
                }
                props.fields().forEachRemaining(e -> {
                    if (value.has(e.getKey())) {
                        check(e.getValue(), value.get(e.getKey()), path + "." + e.getKey(), problems);
                    }
                });
            }
            case "array" -> {
                if (!value.isArray()) {
                    problems.add(path + ": not an array");
                    return;
                }
                int i = 0;
                for (JsonNode item : value) {
                    check(schema.path("items"), item, path + "[" + i++ + "]", problems);
                }
            }
            case "string" -> {
                if (!value.isTextual()) {
                    problems.add(path + ": not a string");
                }
            }
            case "integer" -> {
                if (!value.isIntegralNumber()) {
                    problems.add(path + ": not an integer");
                }
            }
            default -> { }
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode e : schema.get("enum")) {
                found |= e.equals(value);
            }
            if (!found) {
                problems.add(path + ": " + value + " is not in the enum");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- recorder

    /**
     * Records each HTTP exchange as the SDK makes it: below the SDK's retry and logging layers (so each attempt is one
     * exchange), above its OkHttp transport (which adds the API version and the key header itself, so neither reaches
     * this class). Of the request headers only a short list of values is kept; of the response headers only the
     * request id and the retry hints. Bodies are kept whole except thinking signatures, which are kept by length.
     */
    static final class Recorder implements HttpClient {
        HttpClient delegate;
        volatile String label = "";
        volatile boolean dryRun;
        final List<ObjectNode> exchanges = Collections.synchronizedList(new ArrayList<>());
        private int cannedCount;
        private final Map<String, JsonNode> cannedBatches = new LinkedHashMap<>();

        @Override
        public HttpResponse execute(HttpRequest request, RequestOptions requestOptions) {
            ObjectNode ex = begin(request);
            long t0 = System.nanoTime();
            if (dryRun) {
                return finish(ex, canned(request, ex.path("request").path("body")), t0);
            }
            try {
                return finish(ex, delegate.execute(request, requestOptions), t0);
            } catch (RuntimeException e) {
                fail(ex, e, t0);
                throw e;
            }
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            return execute(request, RequestOptions.none());
        }

        @Override
        public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions requestOptions) {
            if (dryRun) {
                return CompletableFuture.completedFuture(execute(request, requestOptions));
            }
            ObjectNode ex = begin(request);
            long t0 = System.nanoTime();
            // Completes with the transport's own exception (not wrapped), so that the SDK's retry layer above sees
            // exactly what it would see without the recorder.
            CompletableFuture<HttpResponse> out = new CompletableFuture<>();
            delegate.executeAsync(request, requestOptions).whenComplete((response, error) -> {
                if (error != null) {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                    fail(ex, cause, t0);
                    out.completeExceptionally(cause);
                    return;
                }
                try {
                    out.complete(finish(ex, response, t0));
                } catch (RuntimeException e) {
                    out.completeExceptionally(e);
                }
            });
            return out;
        }

        @Override
        public CompletableFuture<HttpResponse> executeAsync(HttpRequest request) {
            return executeAsync(request, RequestOptions.none());
        }

        @Override
        public void close() {
            delegate.close();
        }

        private ObjectNode begin(HttpRequest request) {
            ObjectNode ex = MAPPER.createObjectNode();
            ex.put("label", label);
            ex.put("at", Instant.now().toString());
            ObjectNode req = ex.putObject("request");
            req.put("method", request.method().name());
            StringBuilder path = new StringBuilder();
            for (String segment : request.pathSegments()) {
                path.append('/').append(segment);
            }
            req.put("path", path.toString());
            ObjectNode query = req.putObject("query");
            for (String key : request.queryParams().keys()) {
                query.put(key, String.join(",", request.queryParams().values(key)));
            }
            ObjectNode headers = req.putObject("headers");
            for (String name : new TreeSet<>(request.headers().names())) {
                headers.put(name, REQUEST_HEADER_VALUES.contains(name.toLowerCase(Locale.ROOT))
                        ? String.join(", ", request.headers().values(name)) : "(value not recorded)");
            }
            HttpRequestBody body = request.body();
            if (body != null) {
                if (body.repeatable()) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    body.writeTo(buffer);
                    req.set("body", shorten(parse(buffer.toByteArray())));
                } else {
                    req.put("body", "(not recorded: the body is not repeatable)");
                }
            }
            return ex;
        }

        private HttpResponse finish(ObjectNode ex, HttpResponse response, long t0) {
            byte[] bytes;
            try (InputStream in = response.body()) {
                bytes = in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ObjectNode res = ex.putObject("response");
            res.put("status", response.statusCode());
            for (String h : List.of("request-id", "retry-after", "x-should-retry")) {
                List<String> v = response.headers().values(h);
                if (!v.isEmpty()) {
                    res.put(h.equals("request-id") ? "requestId" : h, v.get(0));
                }
            }
            res.set("body", shorten(parse(bytes)));
            ex.put("elapsedMs", ms(t0));
            exchanges.add(ex);
            System.out.printf("  [%s] %s %s -> %d %s (%d ms)%n", ex.path("label").asText(),
                    ex.path("request").path("method").asText(), ex.path("request").path("path").asText(),
                    response.statusCode(), res.path("requestId").asText(""), ms(t0));
            return new Buffered(response, bytes);
        }

        /** The dry run's answer to a request (see {@link #DRY_RUN}). */
        private synchronized HttpResponse canned(HttpRequest request, JsonNode body) {
            int n = ++cannedCount;
            String path = "/" + String.join("/", request.pathSegments());
            String now = Instant.now().toString();
            JsonNode out;
            if (path.equals("/v1/messages/count_tokens")) {
                out = MAPPER.createObjectNode().put("input_tokens", 0);
            } else if (path.equals("/v1/messages")) {
                out = cannedMessage(body, n);
            } else if (path.equals("/v1/messages/batches") && "POST".equals(request.method().name())) {
                String id = "msgbatch_dryrun_" + n;
                cannedBatches.put(id, body.path("requests"));
                out = cannedBatch(id, "in_progress", now);
            } else if (path.matches("/v1/messages/batches/[^/]+/results")) {
                String id = request.pathSegments().get(3);
                StringBuilder lines = new StringBuilder();
                for (JsonNode r : cannedBatches.getOrDefault(id, MAPPER.createArrayNode())) {
                    ObjectNode line = MAPPER.createObjectNode().put("custom_id", r.path("custom_id").asText());
                    line.putObject("result").put("type", "succeeded").set("message", cannedMessage(r.path("params"), n));
                    lines.append(line).append('\n');
                }
                return new Canned(200, lines.toString().getBytes(StandardCharsets.UTF_8), "req_dryrun_" + n);
            } else if (path.startsWith("/v1/messages/batches/")) {
                out = cannedBatch(request.pathSegments().get(3), "ended", now);
            } else if (path.startsWith("/v1/models/")) {
                out = MAPPER.createObjectNode().put("type", "model").put("id", request.pathSegments().get(2))
                        .put("display_name", "(dry run)").put("created_at", "2026-01-01T00:00:00Z");
            } else {
                out = MAPPER.createObjectNode().put("type", "error");
            }
            return new Canned(200, out.toString().getBytes(StandardCharsets.UTF_8), "req_dryrun_" + n);
        }

        private static ObjectNode cannedBatch(String id, String status, String now) {
            ObjectNode b = MAPPER.createObjectNode().put("id", id).put("type", "message_batch")
                    .put("processing_status", status).put("created_at", now).put("expires_at", now)
                    .put("ended_at", "ended".equals(status) ? now : null).putNull("archived_at").putNull("cancel_initiated_at")
                    .putNull("results_url");
            b.putObject("request_counts").put("processing", 0).put("succeeded", 0).put("errored", 0)
                    .put("canceled", 0).put("expired", 0);
            return b;
        }

        /** A message answering the request: a tool call on a first turn with tools, else the schema's sample, else text. */
        private static ObjectNode cannedMessage(JsonNode request, int n) {
            ObjectNode m = MAPPER.createObjectNode().put("id", "msg_dryrun_" + n).put("type", "message")
                    .put("role", "assistant").put("model", request.path("model").asText());
            ArrayNode content = m.putArray("content");
            JsonNode messages = request.path("messages");
            JsonNode last = messages.isArray() && !messages.isEmpty() ? messages.get(messages.size() - 1) : MAPPER.createObjectNode();
            boolean answersToolResult = last.path("content").isArray()
                    && last.path("content").findValues("type").stream().anyMatch(t -> "tool_result".equals(t.asText()));
            JsonNode tools = request.path("tools");
            JsonNode schema = request.path("output_config").path("format").path("schema");
            String stop = "end_turn";
            if (tools.isArray() && !tools.isEmpty() && !answersToolResult) {
                content.addObject().put("type", "thinking").put("thinking", "").put("signature", "dry-run-signature");
                content.addObject().put("type", "tool_use").put("id", "toolu_dryrun_" + n)
                        .put("name", tools.get(0).path("name").asText()).set("input", sample(tools.get(0).path("input_schema")));
                stop = "tool_use";
            } else if (!schema.isMissingNode()) {
                content.addObject().put("type", "text").put("text", sample(schema).toString());
            } else {
                content.addObject().put("type", "text").put("text", "(dry run)");
            }
            m.put("stop_reason", stop).putNull("stop_sequence").putNull("stop_details");
            m.putObject("usage").put("input_tokens", 0).put("output_tokens", 0)
                    .put("cache_creation_input_tokens", 0).put("cache_read_input_tokens", 0);
            return m;
        }

        private void fail(ObjectNode ex, Throwable e, long t0) {
            ex.put("failure", e.getClass().getName() + ": " + e.getMessage()
                    + (e.getCause() == null ? "" : " / " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage()));
            ex.put("elapsedMs", ms(t0));
            exchanges.add(ex);
            System.out.printf("  [%s] %s %s -> %s (%d ms)%n", ex.path("label").asText(),
                    ex.path("request").path("method").asText(), ex.path("request").path("path").asText(),
                    e.getClass().getSimpleName(), ms(t0));
        }
    }

    /** A response whose body was read by the recorder and is served again from memory. */
    record Buffered(HttpResponse delegate, byte[] bytes) implements HttpResponse {
        @Override
        public int statusCode() {
            return delegate.statusCode();
        }

        @Override
        public Headers headers() {
            return delegate.headers();
        }

        @Override
        public InputStream body() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public Optional<String> requestId() {
            return delegate.requestId();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /** A canned response of the dry run. */
    record Canned(int status, byte[] bytes, String cannedRequestId) implements HttpResponse {
        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public Headers headers() {
            return Headers.builder().put("request-id", cannedRequestId).put("content-type", "application/json").build();
        }

        @Override
        public InputStream body() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public Optional<String> requestId() {
            return Optional.of(cannedRequestId);
        }

        @Override
        public void close() { }
    }

    /** The smallest value a schema of the probe's subset accepts (the first enum value, "x", 1, every required key). */
    static JsonNode sample(JsonNode schema) {
        if (schema.has("enum")) {
            return schema.get("enum").get(0);
        }
        switch (schema.path("type").asText("")) {
            case "object" -> {
                ObjectNode o = MAPPER.createObjectNode();
                JsonNode props = schema.path("properties");
                props.fieldNames().forEachRemaining(name -> o.set(name, sample(props.get(name))));
                return o;
            }
            case "array" -> {
                ArrayNode a = MAPPER.createArrayNode();
                for (int i = 0; i < schema.path("minItems").asInt(0); i++) {
                    a.add(sample(schema.path("items")));
                }
                return a;
            }
            case "integer" -> {
                return MAPPER.getNodeFactory().numberNode(1);
            }
            case "number" -> {
                return MAPPER.getNodeFactory().numberNode(1.0);
            }
            case "boolean" -> {
                return MAPPER.getNodeFactory().booleanNode(false);
            }
            case "string" -> {
                return TextNode.valueOf("x");
            }
            default -> {
                return MAPPER.getNodeFactory().nullNode();
            }
        }
    }

    /** An HTTP client for options that are only inspected. */
    static final class Unreachable implements HttpClient {
        @Override
        public HttpResponse execute(HttpRequest request, RequestOptions requestOptions) {
            throw new UnsupportedOperationException("no network in this sub-command");
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            return execute(request, RequestOptions.none());
        }

        @Override
        public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions requestOptions) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("no network in this sub-command"));
        }

        @Override
        public CompletableFuture<HttpResponse> executeAsync(HttpRequest request) {
            return executeAsync(request, RequestOptions.none());
        }

        @Override
        public void close() { }
    }

    /** JSON, JSON Lines (an array of the lines) or text. */
    static JsonNode parse(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            return TextNode.valueOf("");
        }
        String[] lines = text.split("\n");
        if (lines.length > 1) {
            try {
                ArrayNode array = MAPPER.createArrayNode();
                for (String line : lines) {
                    if (!line.isBlank()) {
                        array.add(MAPPER.readTree(line));
                    }
                }
                return array;
            } catch (IOException e) {
                // not JSON Lines
            }
        }
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            return TextNode.valueOf(text.length() > 4000 ? text.substring(0, 4000) + "..." : text);
        }
    }

    /** Thinking signatures and fallback credit tokens are kept by length only. */
    static JsonNode shorten(JsonNode node) {
        if (node.isObject()) {
            ObjectNode o = (ObjectNode) node;
            for (String name : fieldNames(o)) {
                JsonNode child = o.get(name);
                if ((name.equals("signature") || name.equals("fallback_credit_token")) && child.isTextual()
                        && child.asText().length() > 48) {
                    o.put(name, "(" + child.asText().length() + " characters, not recorded)");
                } else {
                    shorten(child);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                shorten(child);
            }
        }
        return node;
    }

    static List<String> sortedCopy(Set<String> s) {
        List<String> list = new ArrayList<>(s);
        Collections.sort(list);
        return list;
    }

    static String join(String... parts) {
        return String.join(" ", Arrays.asList(parts));
    }
}
