# BS13: the Claude API facts the Phase 5 contract relies on

Run on 2026-10-07 (evening). **Proves** (TODO.md, "Base spikes"): each API fact that `docs/design/phase5-contracts.md`
§2.2, §3, §4.1, §4.4, §7.2, §8.3, §10.2 and §15.3 rely on, checked once through the SDK version the contract pins
(`com.anthropic:anthropic-java` 2.34.0), so that the build does not guess.

**Verdict: incomplete, blocked on the key.** The key available to the spike is not scoped to a workspace, and the API
answers every request made with it, metadata and token counts included, with HTTP 400 *"This API key is not scoped to
a workspace, so this request must include the anthropic-workspace-id header with the ID of the workspace to use"*
(F-W). No workspace id was available, so no model was called: **the live parts of items 1-7, the 404 and 400 cases of
item 9 and the live part of item 10 were not run**, and the spend is USD 0.00. What could be settled without a model
call was: the SDK's own behaviour, from its source (`anthropic-java-core-2.34.0-sources.jar`) and from offline runs of
the probe against loopback sockets (retries, timeouts, cancellation, deadlines, error classes, the 401 case with a
literal invalid key); the exact request bodies the SDK serialises for every live item (a dry run that records each
request and answers it from a canned stub); the parsing of the documented refusal and fallback shapes; the Claude Code
CLI's flags and its failure envelope; and item 11 in full (dependency trees, artifact ids, licences, the mediated CLI
class path). The probe takes the workspace id from `ANTHROPIC_WORKSPACE_ID` (or the variable `-Dbs13.workspaceVariable`
names) and runs every live item with one command (`README.md`, "Run"); the summary table names what that run must
show. Three findings change the contract regardless of the live run: the SDK is **MIT**-licensed, not Apache-2.0
(F-11); the SDK does **not** bound a call's total time across retries, so the gateway must (F-9); and keys that are not
scoped to a workspace need a **`workspaceId` setting** that SDK 2.34.0 does not send by itself (F-W).

## 1. What was run

- `Bs13Probe` (`src/main/java/…/Bs13Probe.java`, Java 25, SDK 2.34.0 from Maven Central): one `main`, one sub-command
  per group of facts. Every HTTP exchange goes through a recording `HttpClient` that the probe inserts where
  `AnthropicOkHttpClient.fromEnv()` puts its OkHttp transport (below the SDK's retry and logging layers, so each
  attempt is one exchange; above the transport, which adds `anthropic-version` and the key header itself, so the key
  never reaches the recorder). It records the method, path, query, the request body as serialised, the values of seven
  harmless request headers (`anthropic-beta`, `content-type`, `accept` and four `x-stainless-*`; other headers by name
  only), the response status, request id, `retry-after`/`x-should-retry` and body. Thinking signatures are recorded by
  length. Each report is checked not to contain the key or the workspace id before it is written
  (`target/results/<command>.json`).
- Offline sub-commands: `client` (defaults), `shapes` (documented response shapes parsed by the SDK), `transport`
  (refused connections and a server that accepts and never answers, on 127.0.0.1). The dry run
  (`-Dbs13.dryRun=true … live`) runs every live flow against a canned stub inside the recorder: the requests are what
  the SDK would send, the responses are not evidence (`target/results-dryrun/`).
- Live: `models` and `count` (free) were run once each and stopped at F-W; `auth` was run with
  `ANTHROPIC_API_KEY=invalid-key-for-the-spike` (HTTP 401, request `req_011Cfp2iRukzPw4wQrszLbzq`).
- The key was read from the owner's login-shell environment by variable name (`-Dbs13.keyVariable`), never put on a
  command line, in a file or in a report; mapping it to `ANTHROPIC_API_KEY` in the shell for one command
  (`zsh -lc 'ANTHROPIC_API_KEY="$…" <command>'`) was refused by the worktree-isolation check of the session that ran
  the spike, which is why the probe reads the variable itself.
- The CLI (`claude` 2.1.286) was run without a key only (`--bare`), so that nothing was billed to its own login.
- Item 11: `mvn dependency:resolve dependency:tree` on the probe and on the four POMs under `deps/`, licences read
  from the POMs in the local Maven repository with the snippet of `docs/licence-audit.md` §9 step 6 (extended to list
  licence files and `Bundle-License` in each jar), class-file versions with `javap -v`.

## 2. Summary

"Not run" below means: **not run: the key is an organisation-level key and the API requires anthropic-workspace-id;
set ANTHROPIC_WORKSPACE_ID and re-run** (`java -jar spikes/BS13/target/bs13-probe.jar live`, `README.md`).

| # | Fact | Result | Contract | Verdict |
|---|---|---|---|---|
| W | Workspace scoping of the key | Every request 400 `invalid_request_error`, no request id, `x-should-retry: false`; SDK 2.34.0 sends `anthropic-workspace-id` only for profile, OAuth and workload-identity credentials, never with a static key (F-W) | §4.1, §4.4 | contract must change: a `workspaceId` setting (`ANTHROPIC_WORKSPACE_ID`), sent by the gateway as the header; this 400 is a settings error |
| 1 | Structured output: typed `.outputConfig(Class)`, manual `JsonOutputFormat`, limits | Not run. Offline: both forms serialise to `output_config.format {type: json_schema, schema}`; the typed overload **replaces `output_config` and drops an `effort` set before it**, and adds `$schema` (draft 2020-12); the manual form carries `effort` and `format` together (F-1) | §3.2, §3.3, §4.1 `outputSchema` | as specified for the manual form; contract should say the gateway uses the manual form; the limit errors (numeric `minimum`, `maxLength`, `minItems: 2`) not run |
| 2 | Strict tool with `auto`; forced `tool_choice` refused | Not run. Offline: request shape as specified (`strict: true` on the tool, `additionalProperties: false`, all required, `tool_choice {type: auto}`); `{type: tool}` is prepared as the one call expected to fail; a follow-up turn without the `thinking` blocks is prepared beside the unchanged echo (F-2) | §3.2 `ToolSpec`, §4.1, §12 | shape as specified; contract must change: `Content` needs an opaque part for `thinking` (and `fallback`) blocks, which a tool loop echoes unchanged |
| 3 | No `thinking` field, `effort: low`, on Sonnet 5.5 and Opus 5.5 | Not run. Offline: the request carries `output_config {effort}` and no `thinking`; 2.34.0 knows efforts `low`…`max` and display `summarized`/`omitted` (no `between_tools`, no `updates`); its `Usage` types `cache_creation` (5 min / 1 h split), the cache counts, `inference_geo`, `server_tool_use`, `service_tier` (F-3) | §4.1 `Effort` | as specified (pending the run) |
| 4 | Prompt caching, 5 min and 1 h, a short prefix | Not run. Offline: `cache_control {type: ephemeral}` and `{type: ephemeral, ttl: "1h"}` on the system block, no beta header; prefixes of 2,700 and 1,247 characters (sizes in tokens come from `count`) (F-4) | §4.1 `CacheHint`, §7.2, §15.3 (1) | contract should change: price cache writes from `usage.cache_creation` (it splits 5 min and 1 h), not from the `CacheHint` |
| 5 | `countTokens` against reported input | Not run (`count_tokens` is refused by F-W too). Offline: the same body without `max_tokens`, `output_config` and `cache_control` accepted by the SDK type (F-5) | §4.1, §7.2, §15.3 (1) | pending |
| 6 | Message Batches: two requests, poll, stream, match by `custom_id`; `cache_control` inside | Not run. Offline: 2.34.0's batch requests use their own params type (`BatchCreateParams.Request.Params`); results come as JSON Lines from `/v1/messages/batches/{id}/results`; a second batch with `fallbacks` inside is prepared (documented as rejected) (F-6) | §4.1 batch row, §8.3, §15.3 (2) | contract should say: never send `fallbacks` in a batch; two param types |
| 7 | `fallbacks: "default"` with `server-side-fallback-2026-07-01` | Not run. 2.34.0 has no builder method, no beta constant, no `fallback` block type; it is sent with `addBeta(…)` and `putAdditionalBodyProperty("fallbacks", "default")` (beta endpoint, `?beta=true`) or on the GA endpoint with the header added by hand; a `fallback` block parses as an unknown `ContentBlock` that makes `validate()` and visitors without `unknown` throw; SDK 2.69.0 has `BetaFallbacksParam.ofDefault()` (F-7) | §4.1 `fallbacks`, §4.4, §7.2 | contract must change: tolerate unknown blocks, read `usage.iterations` raw, price the fallback model (`claude-sonnet-5`), or pin a newer SDK |
| 8 | `stop_reason`, `stop_details` accessors | `Message.stopDetails(): Optional<RefusalStopDetails>`, `category(): Optional<Category>` (2.34.0 knows `CYBER`, `BIO`; `frontier_llm` parses as `_UNKNOWN`, `asString()` keeps it), `explanation()`, extra fields in `_additionalProperties()`; `StopReason.REFUSAL`; absent on `end_turn` (F-8) | §3.2, §4.1 `StopReason` | as specified; read the category with `asString()` |
| 9 | Errors, retries, timeouts | 401: `UnauthorizedException`, `errorType()` `authentication_error`, request id present. 404/400: not run (classes from source). Defaults: 2 retries on 408, 409, 429, 5xx and I/O errors, `retry-after` obeyed without a cap; timeouts per attempt 1 min connect, 10 min read/request. Measured: no bound across retries; an async cancel does not stop the attempt; the async path does not retry I/O errors (F-9) | §2.4, §3.2 `Kind`, §4.1 errors | contract must change: `maxRetries(0)` and the gateway's own retries under `callMs`; map 529, 402 and 413 explicitly |
| 10 | `claude -p --bare --output-format json --json-schema`, `--resume`, `--max-budget-usd` | Live run not done (option c). 2.1.286: `--max-budget-usd` exists ("only works with --print"); `--max-turns` is accepted but not in `--help`; failure envelope has `is_error: true` with `subtype: "success"` and no `structured_output`, exit 1; `--resume` of an unknown id prints plain text, exit 1 (F-10) | §10.2 | contract should change: use `--max-budget-usd`, `--tools ""`; judge by exit code and `is_error`; tolerate non-JSON stdout |
| 11 | Dependency sets and licences | Anthropic SDK 2.34.0: 29 artifacts, 30.2 MB, **MIT** (POM); JSqlParser 5.4 (no 5.4.x patch exists), one jar, LGPL-2.1 and Apache-2.0; MCP SDK 2.0.1: `mcp-core` + `mcp-json-jackson2` (12 artifacts, MIT core), or the `mcp` artifact, which brings Jackson 3; protocol versions up to 2025-11-25; in the CLI class path the Anthropic SDK's Jackson modules stay at 2.18.2 beside 2.22.3 (F-11) | §2.2, §2.6 | contract must change: the SDK licence, the MCP ids, Jackson alignment |

## 3. Findings

### F-W The key is not scoped to a workspace (new; not in the contract)

`GET /v1/models/claude-sonnet-5-5` and `POST /v1/messages/count_tokens` both answered HTTP 400 with
`{"type":"error","error":{"type":"invalid_request_error","message":"This API key is not scoped to a workspace, so this
request must include the anthropic-workspace-id header with the ID of the workspace to use. Add the header, or use an
API key that is scoped to a workspace."},"request_id":null}`, no `request-id` header, `x-should-retry: false`, in
183-282 ms (`target/results/models.json`, `count.json`). The SDK raised `BadRequestException`, `errorType()`
`invalid_request_error`. The orchestrator confirmed it independently with one metadata request.

SDK 2.34.0 sends `anthropic-workspace-id` only through its `AuthorizingHttpClient`, which it installs for credentials
resolved from a profile, OAuth or workload identity federation (`CredentialResult.workspaceId`, read from
`ANTHROPIC_WORKSPACE_ID` or the profile); with a static `ANTHROPIC_API_KEY`, `AnthropicBackend.resolveCredentials`
returns early and **no workspace header is ever sent, whatever `ANTHROPIC_WORKSPACE_ID` holds**. Two ways to send it
with a static key: a client default header (`ClientOptions.Builder.putHeader("anthropic-workspace-id", …)`, what the
probe does; checked offline: the header goes out on every request, `target/results-workspace-header-check/`), or
`ANTHROPIC_CUSTOM_HEADERS` (newline-separated `Name: value` lines), which `ClientOptions.fromEnv()` reads. SDK 2.69.0
adds a per-request `workspaceId(…)` to both `MessageCreateParams` builders. The workspace id is an account
identifier: the gateway must never log it, as it never logs the key.

### F-1 Structured output (live part not run)

Request shapes as SDK 2.34.0 serialises them (`target/results-dryrun/structured.json`):

- **Typed overload** `MessageCreateParams.builder()…outputConfig(OutputConfig.builder().effort(LOW).build())
  .outputConfig(LoanTerms.class)` on `record LoanTerms(String itemCategory, int loanDays)` sends
  `"output_config":{"format":{"schema":{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
  "properties":{"itemCategory":{"type":"string"},"loanDays":{"type":"integer"}},"required":["itemCategory","loanDays"],
  "additionalProperties":false},"type":"json_schema"}}`: **the effort is gone**. `StructuredMessageCreateParams.Builder
  .outputConfig(Class)` replaces `output_config` with one that holds only the format (source:
  `paramsBuilder.outputConfig(OutputConfig.builder().format(format).build())`); a second `outputConfig(Class)` throws,
  and an `outputConfig(OutputConfig)` after it replaces the format in turn. The schema comes from victools `jsonschema-generator` (draft 2020-12, every field required,
  `additionalProperties: false`) and is checked locally against the SDK's own copy of the API's schema rules
  (`JsonSchemaValidator`: no numeric or string bounds, `minItems` only, …) before anything is sent. The answer is
  parsed into the record by the SDK (`StructuredMessage<T>`, `content()…text().text()` returns `T`).
- **Manual form** `OutputConfig.builder().effort(LOW).format(JsonOutputFormat.builder().schema(Schema.builder()
  .putAdditionalProperty(k, JsonValue.from(v))…))` sends both: `"output_config":{"effort":"low","format":{"schema":
  {…},"type":"json_schema"}}`, the schema exactly as given (here `additionalProperties: false`, `required`, an enum, a
  nested object, an array). The SDK does not check a manual schema locally; only the API does.
- Prepared and not run: the same schema with `"minimum": 1` on an integer, with `"maxLength": 40` on a string, and with
  `"minItems": 2` on the array, each sent once and recorded whether accepted or refused (status, `errorType()`,
  message, request id).

### F-2 A strict tool with tool choice `auto`; forced tool choice; the follow-up turn (live part not run)

Shapes (`target/results-dryrun/tools.json`): `Tool.builder().name("record_loan").strict(true).inputSchema(
InputSchema.builder().properties(…).required(List.of("item","days","borrower")).putAdditionalProperty(
"additionalProperties", JsonValue.from(false)))` serialises as `{"input_schema":{"type":"object","properties":{…},
"required":[…],"additionalProperties":false},"name":"record_loan","description":"…","strict":true}`, and
`toolChoice(ToolChoiceAuto…)` as `{"type":"auto"}`, at `output_config.effort: medium` (the session effort). Prepared:
`ToolChoiceTool.builder().name("record_loan")` (`{"name":"record_loan","type":"tool"}`, the one call expected to fail
with 400); then the tool result sent back twice, once after the assistant turn **without** its `thinking` blocks (all
the SPI's `Content` types can carry today) and once after the unchanged turn (`message.toParam()`, which echoes the
block as `{"signature":"…","thinking":"","type":"thinking"}`). The documentation for Sonnet 5.5 says to pass thinking
blocks back unchanged and that the history-editing check rejects edited history on accounts created after 2026-08-31;
whether dropping them is refused or tolerated is what the live run will show.

### F-3 Thinking and effort (live part not run)

The request is `{"max_tokens":1024,"messages":[…],"model":"claude-opus-5-5","output_config":{"effort":"low"}}`: no
`thinking` field, which the 5.5 models run as adaptive thinking. In 2.34.0: `OutputConfig.Effort` has `LOW`, `MEDIUM`,
`HIGH`, `XHIGH`, `MAX`; `ThinkingConfigAdaptive.Display` has `SUMMARIZED` and `OMITTED` only (no `updates`), and the
thinking config union has no `between_tools` (Sonnet 5.5's lowest setting; it would need
`putAdditionalBodyProperty("thinking", …)`); the `Model` constants stop at `claude-opus-4-7` (the string overload
`model(String)` takes any id). `Usage` types `input_tokens`, `output_tokens`, `cache_creation_input_tokens`,
`cache_read_input_tokens`, `cache_creation` (`ephemeral_5m_input_tokens`, `ephemeral_1h_input_tokens`),
`inference_geo`, `server_tool_use`, `service_tier`; other fields (`iterations`, `speed`) land in
`_additionalProperties()` (`BetaUsage` types `iterations()` and `speed()`). The run records which blocks come back
(the documented default is `display: "omitted"`: `thinking` blocks with empty text and a signature) and every usage
field present.

### F-4 Prompt caching (live part not run)

The system part is one text block, the lending desk's 22 rules (2,700 characters), with
`cacheControl(CacheControlEphemeral.builder().build())` → `"cache_control":{"type":"ephemeral"}` or
`.ttl(CacheControlEphemeral.Ttl.TTL_1H)` → `{"type":"ephemeral","ttl":"1h"}`; the GA type carries the TTL and the
SDK adds no beta header for it (its `extended-cache-ttl-2025-04-11` constant is not used; item 4c shows whether the API
accepts the field without one, as the current documentation says). The run makes, with the same structured `{answer}` schema
and `effort: low` (both part of the cached prefix): the 22 rules twice (expect `cache_creation_input_tokens` then
`cache_read_input_tokens`), a different edition of the same rules with `ttl: 1h` (expect
`cache_creation.ephemeral_1h_input_tokens`; `batch` then reads that entry), and the first 9 rules (1,247 characters)
twice (expect zero written and read: below the 512-token minimum the documentation gives for Sonnet 5.5). `count`
reports each prefix's tokens first, and `-Dbs13.shortRules` resizes the short one if needed. Because
`usage.cache_creation` splits writes by TTL, the gateway can price them from the response, which `PriceTable.cost(
Usage, CacheHint)` (§7.2) does not need the hint for.

### F-5 `countTokens` (live part not run)

`client.messages().countTokens(MessageCountTokensParams.builder().model(…).systemOfTextBlockParams(…)
.outputConfig(…).addUserMessage(…))` posts the same body without `max_tokens` to `/v1/messages/count_tokens`
(`cache_control` and `output_config` with format and effort are part of the type). The run compares the count with
`input_tokens + cache_creation_input_tokens + cache_read_input_tokens` of the matching response (§15.3 item 1 asks for
10 %); the count is free and refused by F-W like everything else.

### F-6 Message Batches (live part not run)

`client.messages().batches().create(BatchCreateParams.builder().addRequest(Request.builder().customId("bs13-q0")
.params(Request.Params.builder()…)))`: the per-request params are their own type, not `MessageCreateParams` (same
fields, separate builder), so the gateway builds a batch request from its own model, not from the message params.
`retrieve(id).processingStatus()` (`in_progress`, `canceling`, `ended`) and `requestCounts()` for polling;
`resultsStreaming(id)` reads JSON Lines from `/v1/messages/batches/{id}/results` (the SDK builds that path; it does not
use `results_url`) as `MessageBatchIndividualResponse` (`customId()`, `result().succeeded()…message()`,
`errored()…error()`), matched by `custom_id`. The run submits two Sonnet requests that share the one-hour prefix of
F-4 (`cache_control` inside a batch request), polls every 15 s for at most 20 minutes, and records the time to
`ended`, the order of the results and each result's usage. It also submits a one-request batch with
`"fallbacks":"default"` and the beta header on the create call, which the documentation says the Batches API rejects.

### F-7 The server-side refusal fallback, `default` form (live part not run)

SDK 2.34.0 (2026-05-21) predates the `server-side-fallback-*` betas: no `fallbacks` builder method, no `AnthropicBeta`
constant, no `fallback` content block type. It can still be sent: on the beta endpoint
`com.anthropic.models.beta.messages.MessageCreateParams.builder().addBeta("server-side-fallback-2026-07-01")
.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))` posts to `/v1/messages?beta=true` with
`anthropic-beta: server-side-fallback-2026-07-01` and `"fallbacks":"default"` in the body; on the GA endpoint
`putAdditionalHeader("anthropic-beta", …)` plus the same body property gives the same request without `?beta=true`
(both shapes in `target/results-dryrun/fallbacks.json`; the run sends both on Sonnet 5.5, and once without the header,
expected to be refused). Reading a fallback-served response with 2.34.0 (offline, `target/results/shapes.json`, the
documented shape): the `fallback` block is a `ContentBlock` with no known variant (`isValid()` false, raw JSON in
`_json()`); `content()` and text extraction work; **`Message.validate()` throws** and **a `ContentBlock.Visitor`
without an `unknown` override throws** `AnthropicInvalidDataException: Unknown ContentBlock`; `usage.iterations` is
only in `_additionalProperties()` of the GA `Usage`. SDK 2.69.0 (2026-10-07) has `fallbacks(BetaFallbacksParam
.ofDefault())`, `BetaFallbackBlock`, `BetaFallbackMessageIterationUsage` and a client-side
`BetaRefusalFallbackInterceptor`. Per the documentation, Sonnet 5.5's `default` fallback retries only `cyber` and
`frontier_llm` declines, on `claude-sonnet-5`, which the built-in price table of §7.2 does not list.

### F-8 `stop_reason` and `stop_details` (settled offline)

`Message.stopReason(): Optional<StopReason>` (`END_TURN`, `MAX_TOKENS`, `STOP_SEQUENCE`, `TOOL_USE`, `PAUSE_TURN`,
`REFUSAL`); `Message.stopDetails(): Optional<RefusalStopDetails>` with `category(): Optional<RefusalStopDetails
.Category>`, `explanation(): Optional<String>`, and unknown fields (`recommended_model`, the fallback-credit fields) in
`_additionalProperties()`. 2.34.0's `Category` knows `CYBER` and `BIO` only; the documented shape with
`"category":"frontier_llm"` parses, `value()` is `_UNKNOWN` and `asString()` returns `frontier_llm`; `"category":null`
gives an empty `Optional`; on `end_turn` with `"stop_details":null` `stopDetails()` is empty; a refusal before output
has `content()` empty. Sonnet 5.5's documented categories are `cyber`, `bio`, `frontier_llm`, `reasoning_extraction`,
`general_harms`. No refusal was provoked.

### F-9 Errors, retries, timeouts and deadlines (partly live, mostly offline)

- **401** (live, `ANTHROPIC_API_KEY=invalid-key-for-the-spike`, through `AnthropicOkHttpClient.fromEnv()` exactly as
  §4.1 builds it): `com.anthropic.errors.UnauthorizedException`, `statusCode()` 401, `errorType()`
  `authentication_error`, body `{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"},
  "request_id":"req_011Cfp2iRukzPw4wQrszLbzq"}`, `request-id` header present.
- **404 and 400**: not run (an unknown model id and an empty `messages` are prepared in `errors`). From the SDK's
  `ErrorHandler`: 400 `BadRequestException`, 401 `UnauthorizedException`, 403 `PermissionDeniedException`, 404
  `NotFoundException`, 422 `UnprocessableEntityException`, 429 `RateLimitException`, **every 5xx including 529
  `InternalServerException`** (the overload is told by `errorType()` `overloaded_error`), **every other status (402
  `billing_error`, 408, 409, 413 `request_too_large`) `UnexpectedStatusCodeException`**; I/O failures and timeouts
  `AnthropicIoException` (cause `ConnectException`, `SocketTimeoutException: Read timed out` or
  `InterruptedIOException: timeout`). `errorType()` reads `body.error.type` (`ErrorType` constants: invalid_request,
  authentication, permission, not_found, rate_limit, timeout, overloaded, api, billing).
- **Defaults** (`client`): `maxRetries` 2; timeouts per attempt: connect 1 min, read, write and request 10 min; log
  level from `ANTHROPIC_LOG` (off when unset), response validation off. Retried (source, `RetryingHttpClient`): 408,
  409, 429, every 5xx, and `IOException`/`AnthropicIoException`/`AnthropicRetryableException`; an `x-should-retry`
  header overrides; the back-off is 0.5 s × 2^(n-1) (at most 8 s) times a jitter of 0.75-1, **except that
  `retry-after-ms` or `retry-after` is obeyed without any cap**. The max-tokens-scaled default timeout that
  `RequestOptions` contains (30 s to 10 min) never applies, because `ClientOptions` always carries a `Timeout` (source).
- **Measured** (`target/results/transport.json`, loopback only): a refused connection: 3 attempts
  (`X-Stainless-Retry-Count` 0, 1, 2) in 1,680 ms. A server that never answers, client timeout 1 s, synchronous call: 3
  attempts (connections at 1, 1,400 and 3,335 ms), `AnthropicIoException` after **4,336 ms**: the call took three
  timeouts plus back-off, so a per-call deadline is not something the SDK keeps. The same asynchronously, the future
  cancelled at 1.5 s (`cancel` returned true): **the attempt went on until its own 2 s timeout** (the cancel does not
  reach OkHttp: `MessageServiceAsync` hands back a dependent future), and no retry followed, because **2.34.0's
  asynchronous path does not retry I/O errors at all** (its logging layer wraps them in `CompletionException`, which the
  retry layer does not recognise; a refused connection asynchronously: 1 attempt, against 3 synchronously). A client
  with `maxRetries(0)` (also available per client through `withOptions`) and a call with
  `RequestOptions.builder().timeout(Duration.ofMillis(1500))`: 1 attempt, 1,503 ms, which is how the gateway can hold
  its deadline.
- **Logging**: `ANTHROPIC_LOG=debug` makes the SDK print every request and response header and body to `System.err`;
  it redacts `authorization`, `api-key`, `x-api-key`, `cookie`, `set-cookie` only (the key header is added below the
  logger anyway). `ClientOptions.fromEnv()` also reads `ANTHROPIC_CUSTOM_HEADERS`, and `AnthropicOkHttpClient.fromEnv()`
  enables the full credential chain: without `ANTHROPIC_API_KEY` it takes `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_PROFILE`,
  the workload-identity variables, or a profile on disk (`~/.config/anthropic`).

### F-10 The Claude Code CLI, headless (no live run, by decision)

`claude` 2.1.286. `claude --help` lists `--bare` ("Anthropic auth is strictly ANTHROPIC_API_KEY or apiKeyHelper via
--settings (OAuth and keychain are never read)"), `--output-format json`, `--json-schema`, `--mcp-config`,
`--strict-mcp-config`, `--allowedTools`, `--tools` (`""` disables every built-in tool), `--permission-mode` (choices
include `dontAsk`), `--permission-prompts none`, `--model`, `--resume`, `--no-session-persistence` and
**`--max-budget-usd`** ("Maximum dollar amount to spend on API calls (only works with --print)"). **`--max-turns` is not
in the help but is accepted** (an unknown option fails with `error: unknown option '…'`, exit 1; `--max-turns 1` and
`2` did not). Run without a key, `claude -p --bare --output-format json --json-schema '{…}' --model claude-sonnet-5-5
--max-turns 2 --max-budget-usd 0.25 --tools "" --permission-mode dontAsk "…"` exits **1** and prints one envelope:
`type: "result"`, **`subtype: "success"` with `is_error: true`**, `terminal_reason: "api_error"`,
`api_error_status: null`, `result: "Not logged in · Please run /login"`, `session_id`, `uuid`, `num_turns: 1`,
`total_cost_usd: 0`, `duration_ms`, `duration_api_ms: 0`, `stop_reason`, `usage` (with `cache_creation`,
`iterations`, `speed`, `fallback_credit`, `output_tokens_details.thinking_tokens`), `modelUsage: {}`,
`permission_denials: []`, `fast_mode_state`, `subagent_stats`, `queued_turn_count`, `result_index`; **no
`structured_output` key** and no `mcp_server_errors` key (no MCP server was configured).
`--resume <that session id>` with a second prompt is accepted, keeps the session id and fails the same way (exit 1);
**`--resume` of an unknown id exits 1 with plain text on stdout** ("No conversation found with session ID: …"), not an
envelope, despite `--output-format json`. Not verified: `structured_output`, `total_cost_usd` and
`--max-budget-usd` on a real run, and whether `ANTHROPIC_CUSTOM_HEADERS` carries the workspace header for an unscoped
key (the commands are in `README.md`).

### F-11 Dependencies, licences and the CLI class path

**Artifact ids** (Maven Central, 2026-10-07): JSqlParser's latest on the 5.4 line is `5.4` itself (5.0-5.4 exist, no
5.4.x patch). The MCP Java SDK 2.0.1 (group `io.modelcontextprotocol.sdk`) is split: **`mcp-core`** (core, depends on
`slf4j-api`, `jackson-annotations` and `reactor-core`), **`mcp-json-jackson2`** (the Jackson 2 JSON module) and
`mcp-json-jackson3` (Jackson 3, packages `tools.jackson`); the convenience artifact **`mcp` is `mcp-core` plus
`mcp-json-jackson3`**, so declaring `mcp` as §2.2 says brings a second Jackson. `mcp-core` + `mcp-json-jackson2` shares
Jackson 2 with the Anthropic SDK and the project. `io.modelcontextprotocol.spec.ProtocolVersions` in 2.0.1:
`2024-11-05`, `2025-03-26`, `2025-06-18`, `2025-11-25`. Class files: SDK 52 (Java 8), JSqlParser 55 (Java 11), MCP 61
(Java 17), all fine for the Java 25 controller and never in a target JVM. The Anthropic SDK is at 2.69.0 on Maven
Central (2.34.1 is the 2.34 patch); 2.69.0 depends on Jackson 2.19.4 and Kotlin 1.9.0.

**The trees** (`target/tree-*.txt`, `tree-anthropic-verbose.txt`): the Anthropic set is 29 artifacts, 30,206,891
bytes (`anthropic-java-core` alone 18,910,699 bytes, 9,618 entries, nothing outside `com/anthropic/` and
`META-INF/`); it pulls OkHttp, Okio, Kotlin's standard library 1.8.0 (Okio asks for 1.9.10 and loses to the SDK's
direct 1.8.0), Jackson 2.18.2 with the jdk8, jsr310 and Kotlin modules, victools `jsonschema-generator` with its
Jackson and Swagger modules, `swagger-annotations`, Apache HttpClient 5 and `standardwebhooks`. JSqlParser has no
dependency. The MCP set (core + Jackson 2) is 12 artifacts, 6,024,741 bytes, with `json-schema-validator` 2.0.4 and
its YAML and time libraries.

**On one class path** (`deps/cli-classpath`, `target/list-cli-classpath*.txt`): with the root POM's management as it
is (`jackson-databind` and `jackson-dataformat-yaml` at 2.22.3), the CLI would carry 38 third-party jars (35,879,055
bytes; today 5): Jackson core, databind, annotations and YAML at 2.22.x but **`jackson-datatype-jdk8`,
`jackson-datatype-jsr310` and `jackson-module-kotlin` at 2.18.2**, `slf4j-api` 2.0.16 (the MCP set's) over 1.7.36,
`snakeyaml` 2.5. Importing `jackson-bom` 2.22.3 aligns all Jackson modules but brings `kotlin-reflect` 2.1.21 beside
`kotlin-stdlib` 1.8.0. The SDK accepts any Jackson 2 from 2.13.4 except 2.18.1 (`checkJacksonVersionCompatibility`);
the probe's offline paths (`shapes`, `transport`) ran unchanged on both class paths
(`target/results-cp-root-mgmt/`, `target/results-cp-jackson-bom/`), which exercises serialisation and parsing but not
every SDK path.

**Licences**, in the format of `docs/licence-audit.md` §5 (versions as each set resolves alone; "CLI" means the set
ships in the CLI distribution, §2.6, so the audit's §4 obligations apply too):

| Artifact | Version | Scope and modules | Licence (SPDX) | Evidence |
|---|---|---|---|---|
| `com.anthropic:anthropic-java` | 2.34.0 | compile, `skillroy-inference`; CLI | MIT | POM (`<name>MIT</name>`, no URL); the jar has no licence file |
| `com.anthropic:anthropic-java-client-okhttp` | 2.34.0 | compile, through `anthropic-java` | MIT | POM; no licence file |
| `com.anthropic:anthropic-java-core` | 2.34.0 | compile, through the client | MIT | POM; no licence file; nothing shaded |
| `com.fasterxml.jackson.core:jackson-core` | 2.18.2 (2.22.3 in the CLI) | compile | Apache-2.0, with embedded MIT parts (FastDoubleParser) | POM; JAR `META-INF/LICENSE`, `NOTICE`, `FastDoubleParser-LICENSE`, `FastDoubleParser-NOTICE`, `thirdparty-LICENSE` |
| `com.fasterxml.jackson.core:jackson-databind` | 2.18.2 (2.22.3) | compile | Apache-2.0 | POM; JAR `LICENSE`, `NOTICE` |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.18.2 (2.22) | compile | Apache-2.0 | POM; JAR `LICENSE`, `NOTICE` |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.18.2 | runtime | Apache-2.0 | POM chain `jackson-modules-java8` > `jackson-base`; JAR `LICENSE`, `NOTICE` |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.18.2 | runtime | Apache-2.0 | as above |
| `com.fasterxml.jackson.module:jackson-module-kotlin` | 2.18.2 | runtime | Apache-2.0 | POM; JAR `LICENSE`, `NOTICE` |
| `com.fasterxml:classmate` | 1.7.0 | runtime | Apache-2.0 | POM; JAR `LICENSE`, `NOTICE` |
| `com.github.victools:jsonschema-generator` | 4.38.0 | runtime | Apache-2.0 | POM parent `jsonschema-generator-parent` |
| `com.github.victools:jsonschema-module-jackson` | 4.38.0 | runtime | Apache-2.0 | as above |
| `com.github.victools:jsonschema-module-swagger-2` | 4.38.0 | runtime | Apache-2.0 | as above |
| `io.swagger.core.v3:swagger-annotations` | 2.2.31 | compile | Apache-2.0 | POM parent `swagger-project`; JAR `LICENSE`, `NOTICE` |
| `com.google.errorprone:error_prone_annotations` | 2.33.0 | compile | Apache-2.0 (the POM says "Apache 2.0") | POM; manifest `Bundle-License` |
| `com.standardwebhooks:standardwebhooks` | 1.1.0 | compile | MIT | POM ("The MIT License") |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | runtime | Apache-2.0; its `publicsuffixes.gz` is compiled from the Public Suffix List under MPL-2.0 | POM; JAR `okhttp3/internal/publicsuffix/NOTICE` |
| `com.squareup.okio:okio` | 3.6.0 | runtime | Apache-2.0 | POM |
| `com.squareup.okio:okio-jvm` | 3.6.0 | runtime | Apache-2.0 | POM |
| `org.apache.httpcomponents.client5:httpclient5` | 5.3.1 | runtime | Apache-2.0 | POM chain `httpclient5-parent` > `httpcomponents-parent` > `apache`; JAR `LICENSE`, `NOTICE` |
| `org.apache.httpcomponents.core5:httpcore5` | 5.2.4 | runtime | Apache-2.0 | POM chain `httpcore5-parent` > `httpcomponents-parent` > `apache`; JAR `LICENSE`, `NOTICE` |
| `org.apache.httpcomponents.core5:httpcore5-h2` | 5.2.4 | runtime | Apache-2.0 | as above |
| `org.slf4j:slf4j-api` | 1.7.36 (2.0.16 in the CLI) | runtime | MIT | POM parent `slf4j-parent` |
| `org.jetbrains.kotlin:kotlin-stdlib` | 1.8.0 | compile | Apache-2.0 | POM |
| `org.jetbrains.kotlin:kotlin-stdlib-common` | 1.8.0 | compile | Apache-2.0 | POM |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk7` | 1.8.0 | compile | Apache-2.0 | POM |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk8` | 1.8.0 | compile | Apache-2.0 | POM |
| `org.jetbrains.kotlin:kotlin-reflect` | 1.8.10 | runtime | Apache-2.0 | POM |
| `org.jetbrains:annotations` | 13.0 | compile | Apache-2.0 | POM |
| `com.github.jsqlparser:jsqlparser` | 5.4 | compile, `skillroy-inference`; CLI; no dependencies; 2,008,413 bytes | LGPL-2.1 and Apache-2.0, two `<license>` entries (whether "or later" is not stated; the project takes Apache-2.0, §2.2) | POM; manifest `Bundle-License` names both; no licence file |
| `io.modelcontextprotocol.sdk:mcp-core` | 2.0.1 | compile, `skillroy-mcp`; CLI | MIT | POM; manifest `Bundle-License` |
| `io.modelcontextprotocol.sdk:mcp-json-jackson2` | 2.0.1 | compile, `skillroy-mcp`; CLI | MIT | POM; manifest `Bundle-License` |
| `io.projectreactor:reactor-core` | 3.7.0 | compile, through `mcp-core` | Apache-2.0 | POM |
| `org.reactivestreams:reactive-streams` | 1.0.4 | compile, through `reactor-core` | MIT-0 | POM |
| `org.slf4j:slf4j-api` | 2.0.16 | compile, through `mcp-core` | MIT | POM chain `slf4j-parent` > `slf4j-bom`; JAR `META-INF/LICENSE.txt` |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.21 (2.22 in the CLI) | compile, through `mcp-core` | Apache-2.0 | POM; JAR `LICENSE`, `NOTICE` |
| `com.fasterxml.jackson.core:jackson-databind` | 2.21.1 (2.22.3) | compile, through `mcp-json-jackson2` | Apache-2.0 | POM; JAR `LICENSE`, `NOTICE` |
| `com.fasterxml.jackson.core:jackson-core` | 2.21.1 (2.22.3) | compile, through `jackson-databind` | Apache-2.0, with embedded MIT parts | POM; JAR licence files as above |
| `com.networknt:json-schema-validator` | 2.0.4 | compile, through `mcp-json-jackson2` | Apache-2.0 | POM; manifest `Bundle-License` |
| `com.ethlo.time:itu` | 1.14.0 | compile, through `json-schema-validator` | Apache-2.0 | POM; manifest `Bundle-License` |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | 2.18.4 (2.22.3) | compile, through `json-schema-validator` | Apache-2.0 | POM parent `jackson-dataformats-text`; JAR `LICENSE`, `NOTICE` |
| `org.yaml:snakeyaml` | 2.3 (2.5) | compile, through the YAML module | Apache-2.0 | POM; manifest `Bundle-License` |

The alternative `io.modelcontextprotocol.sdk:mcp` 2.0.1 (MIT, POM) brings instead `mcp-json-jackson3` 2.0.1 (MIT),
`tools.jackson.core:jackson-databind` and `jackson-core` 3.1.4 (Apache-2.0; `jackson-core` carries FastDoubleParser
and Schubfach licence files), `com.networknt:json-schema-validator` 3.0.6 (Apache-2.0),
`tools.jackson.dataformat:jackson-dataformat-yaml` 3.1.4 and `org.snakeyaml:snakeyaml-engine` 3.0.1 (both
Apache-2.0), with `mcp-core` and its three dependencies as above.

## 4. Spend

USD 0.00 (`target/results/spend.json`). The only requests that reached the API were two metadata/count requests
refused by F-W and one request with an invalid key; the CLI calls failed before any API call. Expected for the live
run: well under USD 0.25 (about thirty short Sonnet 5.5 requests, one Opus 5.5 request at `max_tokens` 1024, two batch
requests at half price); the probe stops once USD 1.00 has been spent.

## 5. What the contract should say differently

1. **§2.2, the Anthropic SDK's licence is MIT** (POM of all three artifacts; no licence file in the jars, so
   `THIRD-PARTY.txt` must carry the MIT text and the copyright line from the SDK's source repository).
2. **§2.2, the MCP row**: `io.modelcontextprotocol.sdk:mcp-core:2.0.1` and
   `io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.1`, not `mcp` (which is `mcp-core` + `mcp-json-jackson3`, a second
   Jackson). Specification line 2025-11-25 confirmed in 2.0.1.
3. **§2.2/§2.6, Jackson on the CLI class path**: the root POM manages only `jackson-databind` and
   `jackson-dataformat-yaml`, so the SDK's `jackson-datatype-jdk8`, `-jsr310` and `jackson-module-kotlin` stay at 2.18.2
   beside 2.22.3; import `jackson-bom` (and then manage Kotlin's `kotlin-stdlib`/`kotlin-reflect` to one version, since
   the 2.22 Kotlin module brings `kotlin-reflect` 2.1.21), and keep a test that drives the SDK's (de)serialisation on the
   final class path without a key. The CLI distribution grows from 5 to 38 third-party jars and by 33,087,453 bytes.
4. **§2.2, the SDK version**: 2.34.0 predates the 5.5 models, the fallback betas, `between_tools` and the per-request
   `workspaceId`. Either keep it with the work-arounds below, or pin a current version (2.69.0 has typed
   `fallbacks(BetaFallbacksParam.ofDefault())`, `BetaFallbackBlock` and `workspaceId(…)`), audit its set (Jackson
   2.19.4, Kotlin 1.9.0) and re-run BS13 on it.
5. **§4.1/§4.4, a `workspaceId` setting** (`inference.yaml` `workspaceId`, default from `ANTHROPIC_WORKSPACE_ID`): a key
   not scoped to a workspace gets 400 on every request without `anthropic-workspace-id`, and SDK 2.34.0 sends that
   header only for profile, OAuth and federation credentials, so the gateway adds it as a client header. It is an
   account identifier: never logged, never in a recording. The 400 "not scoped to a workspace" (no request id) is a
   settings error (exit 64 / the `environment` testcase), like a missing key, not `INVALID_REQUEST`.
6. **§4.1, building the client**: `AnthropicOkHttpClient.fromEnv()` reads more than `ANTHROPIC_API_KEY` and
   `ANTHROPIC_BASE_URL` (`ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_PROFILE`, the federation variables, on-disk profiles,
   `ANTHROPIC_CUSTOM_HEADERS`, `ANTHROPIC_LOG`). To keep "never reads a key from anywhere but the environment" and "never
   logs a body", build the backend from `ANTHROPIC_API_KEY` explicitly and set `logLevel(LogLevel.OFF)`.
7. **§2.4/§4.1, deadlines and retries**: "a retry counts against the same deadline" is not what the SDK does: its
   timeout is per attempt (default 10 min), `retry-after` is obeyed without a cap, an asynchronous cancel does not stop
   the HTTP attempt, and the asynchronous path does not retry I/O errors. The gateway sets `maxRetries(0)`, retries
   `RATE_LIMIT`, `OVERLOADED` and `TRANSPORT` itself while `callMs` lasts (honouring `retry-after` only within it), and
   gives each attempt the remaining time through `RequestOptions.timeout`.
8. **§4.1, the error table**: classify by `statusCode()` and `errorType()`, not by exception class alone: 529 arrives
   as `InternalServerException` with `overloaded_error`; 402 (`billing_error`) and 413 (`request_too_large`) arrive as
   `UnexpectedStatusCodeException`; timeouts as `AnthropicIoException` with a timeout cause. Add a kind for 402
   (billing, not retryable) or fold it into `AUTH`.
9. **§3.2/§12, provider blocks in `Content`**: the 5.5 models return `thinking` blocks (empty text and a signature under
   the default display) before tool calls and, with fallbacks, `fallback` blocks; a session must echo the assistant turn
   unchanged on the same model. `Content` needs an opaque part (provider, type, raw JSON) that `ModelResponse.parts`
   keeps and the session service sends back, and the fingerprint of §4.2 must leave signatures out (they differ per
   call). The live item 2c will say whether dropping them is refused or merely loses the reasoning.
10. **§4.1, `outputSchema`**: the gateway uses the manual `OutputConfig.builder().effort(…).format(JsonOutputFormat…)`;
    the SDK's typed `.outputConfig(Class)` drops `effort` and adds `$schema`. `Schemas.ofRecord` stays the project's.
11. **§4.1/§7.2, cache writes**: `usage.cache_creation` splits writes into `ephemeral_5m_input_tokens` and
    `ephemeral_1h_input_tokens`; `Usage` should carry both and `PriceTable.cost` should price from them instead of from
    the `CacheHint`.
12. **§4.1/§4.4/§7.2/§8, fallbacks**: never put `fallbacks` in a Message Batches request (documented as rejected; the
    live 6x checks it); with 2.34.0 send it as an extra body property plus the header, tolerate unknown content blocks
    (never `validate()`, never a visitor without `unknown`), read `usage.iterations` from the raw JSON; add
    `claude-sonnet-5` (Sonnet 5.5's documented fallback target, USD 2/10 per MTok) and the Opus 5.5 targets to the
    built-in price table.
13. **§4.1, `StopReason`**: record `stop_details.category` with `asString()`; 2.34.0's enum knows only `cyber` and
    `bio` of the five categories Sonnet 5.5 uses.
14. **§8.3, batches**: the SDK's batch request params are their own type; results are read with
    `resultsStreaming(id)` (JSON Lines) and matched by `custom_id`.
15. **§10.2, the planner's CLI call**: use `--max-budget-usd` (it exists in 2.1.286) instead of the after-the-fact check;
    `--max-turns` works but is undocumented; add `--tools ""` (no built-in tools: fewer tokens, no shell or file tools)
    and `--strict-mcp-config`; judge success by exit code 0 and `is_error: false`, never by `subtype` ("success" also on
    failure); read `structured_output` and `mcp_server_errors` as optional; a failed `--resume` prints plain text, not
    JSON; with a key not scoped to a workspace the CLI needs the header too (`ANTHROPIC_CUSTOM_HEADERS`, to be
    verified).

## 6. How to reproduce, and how to finish

`README.md` has every command. To finish the spike: export the workspace id (`ANTHROPIC_WORKSPACE_ID`, or name the
variable with `-Dbs13.workspaceVariable`), run `java -jar spikes/BS13/target/bs13-probe.jar live` with the key in the
environment (about two minutes plus the batch's wait), run the two CLI commands of `README.md`, and fill the "Not run"
rows of §2 from `target/results/*.json`: for each item the findings object of its file holds the answer (`1a`-`1e`,
`2a`-`2d`, `3 …`, `4a`-`4e` with `5 …`, `6 batch` with `6x`, `7a`-`7c`, `9a`/`9b`), and the exchanges hold the request
ids and the usage.
