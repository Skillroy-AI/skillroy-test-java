# BS13: the Claude API facts the Phase 5 contract relies on

Run offline on 2026-10-07 (evening) and live on 2026-10-08 (afternoon, 19:15-19:24 UTC). **Proves** (TODO.md, "Base
spikes"): each API fact that `docs/design/phase5-contracts.md` §2.2, §3, §4.1, §4.4, §7.2, §8.3, §10.2 and §15.3 rely
on, checked once through the SDK version the contract pins (`com.anthropic:anthropic-java`: 2.34.0 when the spike was
written, 2.70.0 since D88), so that the build does not guess.

**Verdict: complete.** Every item ran live except the parts that concern the SDK alone. The live part ran on 2026-10-08
through SDK 2.70.0, the version the build pins: items 1-7, the models endpoint, the 404 and 400 cases of item 9, and
item 10 (the Claude Code CLI, which uses no SDK, natively with the key). Five more facts were measured the same day
outside the probe: Haiku 4.5 refuses `effort` and accepts `fallbacks`; the CLI's `--max-budget-usd` bounds an
invocation's own spend; the CLI (2.1.294) refuses a `--json-schema` that declares the draft 2020-12 `$schema`; and the
API refuses a request whose strict tools hold more than 16 union-typed parameters. The SDK-only parts stand from the
offline run of 2026-10-07 through SDK 2.34.0: item 8's accessors, item 9's error classes, retries, timeouts and
cancellation (with its 401, sent live with a literal invalid key), and item 11's dependency sets and licences. The key
of 2026-10-07 was an organisation-level key, which the API refused on every request without `anthropic-workspace-id`
(F-W, kept as history); the owner's key of 2026-10-08 is scoped to a workspace, so no workspace id was needed or sent
(`workspaceHeaderSent: false` in every live report) and the models endpoint answered it; the `workspaceId` setting the
contract took from F-W (§4.1, §4.4) stays, for organisation-level keys. **Spend: USD 0.0322**, the two CLI calls
included and one Opus call among them (§4; the contract's §4.1 says USD 0.032): `spend.json` sums USD 0.034013, which
counts the first CLI call twice, because a resumed call's `total_cost_usd` is the session's running total; what was
measured outside the probe cost about USD 0.0075 more. Most of what the live run saw is what the contract already said,
and SDK 2.70.0's typed structured-output overload now keeps `effort` (F-1). What the live run changed is in the contract
now, all of it made in the live session (§5, items 10 and 16-22): **Haiku 4.5 refuses `output_config.effort`** (HTTP
400), so the Haiku class is sent no `effort`; a Haiku response names the dated id `claude-haiku-4-5-20251001`, which
counts as the model asked for and is priced as its alias; `usage.iterations` comes back whenever `fallbacks` is sent,
not only when a fallback ran; Opus 5.5's fallback targets include `claude-opus-5`, now priced; a resumed CLI call's
`total_cost_usd` is the session's running total, so the planner records each call's difference, and `--max-budget-usd`
bounds the call's own spend, so a repair round is given what is left of the planner's budget; a batch request did not
read a cache entry that a standard request had written (one observation); a string `maxLength` is accepted; and §4.1
gives 2.70.0's reason for the manual structured-output form. From the facts measured outside the probe, the planner
strips `$schema` from its schema and the gateway sends tools without `strict` above 16 union-typed parameters (§5, items
23 and 24). Nothing in §5 is left open.

## 1. What was run

- `Bs13Probe` (`src/main/java/…/Bs13Probe.java`, Java 25, the SDK from Maven Central: 2.34.0 by default, 2.70.0 for
  the live run through `-Danthropic-java.version=2.70.0`): one `main`, one sub-command per group of facts. Every HTTP
  exchange goes through a recording `HttpClient` that the probe inserts where `AnthropicOkHttpClient.fromEnv()` puts its
  OkHttp transport (below the SDK's retry and logging layers, so each attempt is one exchange; above the transport,
  which adds `anthropic-version` and the key header itself, so the key never reaches the recorder). It records the
  method, path, query, the request body as serialised, the values of seven harmless request headers (`anthropic-beta`,
  `content-type`, `accept` and four `x-stainless-*`; other headers by name only), the response status, request id,
  `retry-after`/`x-should-retry` and body. Thinking signatures are recorded by length. Each report is checked not to
  contain the key or the workspace id before it is written (`target/results/<command>.json`).
- Offline sub-commands (2026-10-07, SDK 2.34.0): `client` (defaults), `shapes` (documented response shapes parsed by
  the SDK), `transport` (refused connections and a server that accepts and never answers, on 127.0.0.1). The dry run
  (`-Dbs13.dryRun=true … live`) runs every live flow against a canned stub inside the recorder: the requests are what
  the SDK would send, the responses are not evidence (`target/results-dryrun/`).
- Live, 2026-10-07, with the first key: `models` and `count` (free) were run once each and stopped at F-W; `auth` was
  run with `ANTHROPIC_API_KEY=invalid-key-for-the-spike` (HTTP 401, request `req_011Cfp2iRukzPw4wQrszLbzq`).
- Live, 2026-10-08, with the owner's workspace-scoped key, through SDK 2.70.0: `java -jar
  spikes/BS13/target/bs13-probe.jar live`, which ran `models`, `count`, `errors`, `structured`, `tools`, `thinking
  sonnet`, `thinking opus`, `fallbacks`, `cache` and `batch` in that order, from 19:15:49 to 19:24:01 UTC, and ended
  with `spend`. 70 HTTP exchanges, every one a first attempt (`X-Stainless-Retry-Count: 0`,
  `X-Stainless-Package-Version: 2.70.0`): 18 model answers (17 from Sonnet 5.5, one from Opus 5.5; two of them batch
  results), 7 refusals (1c, 1e, 2b, 6x, 7c, 9a, 9b), 6 token counts, 9 model lookups, and the batch's create, 30 polls
  and results.
- The key: on 2026-10-08 it was in the probe's environment as `ANTHROPIC_API_KEY`, exported by the owner's login shell,
  and read by the SDK's `fromEnv()` (`keyReadBy` in every report); no workspace id was set. On 2026-10-07 the probe read
  it by variable name (`-Dbs13.keyVariable`), because mapping it to `ANTHROPIC_API_KEY` in the shell for one command
  (`zsh -lc 'ANTHROPIC_API_KEY="$…" <command>'`) was refused by the worktree-isolation check of the session that ran
  the spike. Neither time was the key put on a command line, in a file or in a report.
- The CLI (`claude` 2.1.286): on 2026-10-07 without a key only (`--bare`), so that nothing was billed to its own
  login; on 2026-10-08 natively (not in the Linux image) with the key in the environment, the two commands of
  `README.md` (with `--bare` the key is its only credential), their envelopes copied to `target/results/cli-first.json`
  and `cli-resume.json`, and once `--resume` with an id that is not a UUID; later that day, by the orchestrator, two
  more `--resume` calls of the same session under `--max-budget-usd` (F-10).
- Haiku 4.5 (2026-10-08): raw requests to the Messages API by the orchestrator, outside the probe, with and without
  `output_config.effort` and with `fallbacks`. No report under `target/results/` holds these or the two budget calls.
- Two facts from the live session's own runs (2026-10-08), outside the probe: the Claude Code CLI 2.1.294 (the Linux
  build run in the test image) given `--json-schema` with and without a `$schema` key (F-10), and the session request
  that the live session test (§15.3 item 6) sent with the sample's verbs as strict tools (F-2).
- Item 11 (2026-10-07): `mvn dependency:resolve dependency:tree` on the probe and on the four POMs under `deps/`,
  licences read from the POMs in the local Maven repository with the snippet of `docs/licence-audit.md` §9 step 6
  (extended to list licence files and `Bundle-License` in each jar), class-file versions with `javap -v`.
- `target/` is build output and is not committed, so this document carries the request ids and figures. The files of
  the 2026-10-07 run that it cites (the dry run's, the `client`, `shapes`, `transport` and `auth` reports, the
  dependency trees, the class-path listings and the other `target/results-*` directories) are no longer there:
  `target/` now holds the 2.70.0 build and the live reports. The commands of `README.md` make them again.

## 2. Summary

Live means 2026-10-08, through SDK 2.70.0 with the workspace-scoped key; offline means 2026-10-07, through SDK 2.34.0
(the SDK's own behaviour, which the live run did not repeat). The verdicts read against the contract as it stands
after the live session.

| # | Fact | Result | Contract | Verdict |
|---|---|---|---|---|
| W | Workspace scoping of the key | First key (2026-10-07): every request 400 `invalid_request_error`, no request id, `x-should-retry: false`; SDK 2.34.0 sends `anthropic-workspace-id` only for profile, OAuth and workload-identity credentials, never with a static key. Second key (2026-10-08): scoped to a workspace; no header sent (`workspaceHeaderSent: false` in every live report), every request answered (F-W) | §4.1, §4.4 | in the contract: a `workspaceId` setting for organisation-level keys, the 400 reported as `AUTH`; the current key needs none |
| 1 | Structured output: typed `.outputConfig(Class)`, manual `JsonOutputFormat`, limits | Typed form: 2.70.0 **keeps `effort`** (`effortAfterOutputConfigClass: low`; 2.34.0 dropped it) and adds `$schema`; the answer was parsed into the record. Manual form (enum, nested object, array): the answer parses and satisfies the schema. Integer `minimum`: 400 "For 'integer' type, property 'minimum' is not supported"; `minItems: 2`: 400 "'minItems' values other than 0 or 1 are not supported (got: [2, 5])"; string `maxLength: 40`: **accepted** (F-1) | §3.2, §3.3, §4.1 `outputSchema` | as specified (the manual form; §3.2 records `maxLength` and keeps refusing it; §4.1 gives 2.70.0's reason for the manual form since the live session, §5 item 10) |
| 2 | Strict tool with `auto`; forced `tool_choice` refused | `tool_choice: auto` at effort medium: `record_loan` called with schema-valid input, no thinking block; forced `{type: tool}`: 400 `tool_choice: type "tool" and "any" are not supported for this model.`; the follow-up turn with the tool result accepted with and without the (absent) thinking block, so dropping a thinking block was not exercised (`responseHadThinkingBlock: false`, `meaningful: false`). A request whose strict tools hold 20 union-typed parameters (the sample's verbs as tools, in the live session test) is refused: 400 "Schemas contains too many parameters with union types (20 parameters with type arrays or anyOf). … (limit: 16 parameters with unions)." (F-2) | §3.2 `ToolSpec`, §4.1, §12 | as specified (`auto` always, `Content.Opaque` echoed unchanged); the gateway sends a request's tools without `strict` when they hold more than 16 union-typed parameters (§4.1, since the live session); whether a dropped thinking block is refused stays open |
| 3 | No `thinking` field, `effort: low`, on Sonnet 5.5 and Opus 5.5 | No `thinking` sent. Sonnet 5.5 at `low` (and at `medium`): no thinking block, `thinking_tokens: 0`; Opus 5.5 at `low`: a `thinking` block with empty text and a 948-character signature, `thinking_tokens: 82`. Usage fields in every response: `input_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`, `cache_creation` {5m, 1h}, `output_tokens`, `output_tokens_details.thinking_tokens`, `service_tier`, `inference_geo`; `iterations` only when `fallbacks` was sent. Models endpoint: both 5.5 models take `adaptive` thinking only and every effort from `low` to `max` (F-3) | §4.1 `Effort` | as specified; a loop over Opus echoes the opaque part (`Content.Opaque`) |
| H | Haiku 4.5: `effort` and `fallbacks` (raw requests by the orchestrator, not the probe) | The models endpoint reports `effort.supported: false` for `claude-haiku-4-5`; a request with `output_config.effort` is refused with 400 "This model does not support the effort parameter."; without it, and with a structured-output format, Haiku 4.5 answers, and the response names the model `claude-haiku-4-5-20251001`. With `fallbacks: "default"` and the beta header `server-side-fallback-2026-07-01` it answers too (HTTP 200, `usage.iterations` present), although it has no fallback targets (F-3, F-7) | §3.2 `Effort`, §4.1, §4.4 `effort`, §7.2, §11, §12.4 | in the contract and built since the live session: no `effort` for `HAIKU_CLASS` (§4.1 `Effort`); a dated id is the model asked for and is priced as its alias (§4.1 `Usage`, §7.2); `fallbacks` sent for every class (§5 items 16, 21) |
| 4 | Prompt caching, 5 min and 1 h, a short prefix | 1,135 tokens written (5 min), then read by the identical second call 2.4 s later; the `ttl: 1h` edition written as `ephemeral_1h_input_tokens: 1135` with no beta header; the "short" prefix was 626 tokens (above the 512 minimum), so it cached: written, then read; the structured-output format adds about 200 tokens to the cached prefix (the short system text about 430 tokens, the prefix 626) (F-4) | §4.1 `CacheHint`, §7.2, §15.3 (1) | as specified (writes priced from `usage.cache_creation`) |
| 5 | `countTokens` against reported input | Exact: 1,161 and 652 counted, against the `input + cache_creation + cache_read` reported for the same requests: 0 % difference in both cases (F-5) | §4.1, §7.2, §15.3 (1) | as specified (the 10 % of §15.3 item 1 holds with room) |
| 6 | Message Batches: two requests, poll, stream, match by `custom_id`; `cache_control` inside | Two requests sharing the 1 h prefix, submitted 19:16:22 UTC, `ended` after 458 s (30 polls of 15 s), both `succeeded`, `service_tier: batch`; the first wrote the prefix (`ephemeral_1h_input_tokens: 1135`), the second read 1,135 tokens; the first did **not** read the identical 1 h entry a standard request had written about 5 s before the submission (one observation); the results streamed in reverse order (`bs13-q1` before `bs13-q0`). 6x: a batch with `fallbacks` refused at create, 400 "requests[0] (custom_id='bs13-fallback'): The `fallbacks` parameter is not supported for batch requests." (F-6) | §4.1 batch row, §8.3, §15.3 (2), (4) | as specified (own params type, match by `custom_id`, no `fallbacks` in a batch); the batch tier's cache noted in §4.1's batch row since the live session (§5 item 20) |
| 7 | `fallbacks: "default"` with `server-side-fallback-2026-07-01` | Accepted on the beta endpoint (`?beta=true`) and on the GA endpoint with the header `anthropic-beta: server-side-fallback-2026-07-01`, both with the body property `"fallbacks": "default"` (2.70.0 types the parameter on the beta request type; the probe sent the extra body property on both); without the header 400 "fallbacks: Extra inputs are not permitted"; the response then carries `usage.iterations` (one entry, `type: message`, `model: null`, its own token counts) although no fallback ran; no refusal provoked. Models endpoint, allowed fallback models: Sonnet 5.5 → `claude-sonnet-5`; Opus 5.5 → `claude-opus-4-8`, `claude-opus-5`; Haiku 4.5 → none, though a Haiku 4.5 request with `fallbacks` is accepted (F-7) | §4.1 `fallbacks`, §4.4, §7.2 | in the contract since the live session: the header required, `usage.iterations` whenever `fallbacks` is sent, `claude-opus-5` priced (§5 items 17, 18) |
| 8 | `stop_reason`, `stop_details` accessors | Offline, unchanged: `Message.stopDetails(): Optional<RefusalStopDetails>`, `category(): Optional<Category>` (2.34.0 knows `CYBER`, `BIO`; `frontier_llm` parses as `_UNKNOWN`, `asString()` keeps it), `explanation()`, extra fields in `_additionalProperties()`; `StopReason.REFUSAL`. Live: every response had `stop_details: null`, with `end_turn` (17) or `tool_use` (1) (F-8) | §3.2, §4.1 `StopReason` | as specified (the category read as a string) |
| 9 | Errors, retries, timeouts | Live: 404 unknown model → `NotFoundException`, `not_found_error`, "model: claude-bs13-no-such-model"; 400 empty messages → `BadRequestException`, `invalid_request_error`, "messages: at least one message is required"; request ids present, `x-should-retry: false`. The 401 of 2026-10-07 stands: `UnauthorizedException`, `authentication_error`. Offline: 2 retries on 408, 409, 429, 5xx and I/O errors, `retry-after` obeyed without a cap; timeouts per attempt; no bound across retries; an async cancel does not stop the attempt; the async path does not retry I/O errors (F-9) | §2.4, §3.2 `Kind`, §4.1 errors | as specified (`maxRetries(0)` and the gateway's retries under `callMs`; errors by status and `errorType()`) |
| 10 | `claude -p --bare --output-format json --json-schema`, `--resume`, `--max-budget-usd` | 2.1.286, natively with the key: exit 0, `is_error: false`, `subtype: "success"`, `structured_output: {"answer":"OK"}`, `total_cost_usd: 0.0018205`, `num_turns: 2`, a `session_id`, `modelUsage` per model with `costUSD`, `usage` with `cache_creation` and `iterations`. `--resume <session_id>`: accepted, the same session id, `structured_output: {"answer":"YES"}`, the first call's 513-token prefix read from the cache, `total_cost_usd: 0.0026886`, **the session's running total** (the first call's cost included). `--max-budget-usd` bounds an invocation's own spend, not the running total (two later resumes by the orchestrator): under 0.001 the call (about USD 0.0014) was made and the CLI ended, exit 1, `is_error: true`, `subtype: "error_max_budget_usd"`, `terminal_reason: "budget_exhausted"`; under 0.0045, with the running total already at 0.0041, the call succeeded. `--resume` of an id that is not a UUID: refused before any API call, plain text on stderr ("Error: --resume requires a valid session ID or session title…"), exit 1, nothing on stdout; of a UUID-shaped unknown id (2026-10-07): plain text on stdout, exit 1. CLI 2.1.294 (the Linux build in the test image) refuses a `--json-schema` whose text declares the draft 2020-12 `$schema`: `Error: --json-schema is not a valid JSON Schema: no schema with key or ref "https://json-schema.org/draft/2020-12/schema"` on stderr, exit 1, nothing on stdout; it accepts the schema without the key, `$defs` or `definitions` references and a draft-07 `$schema` (F-10) | §10.2 | as specified (exit code and `is_error`, `structured_output` optional, `--max-budget-usd`); in the contract since the live session: the ledger takes the differences of the running total and a repair round is given what is left of the budget (§10.2 item 5, §5 item 22); the planner strips `$schema` from its schema (§10.2, §5 item 23) |
| 11 | Dependency sets and licences | Offline, unchanged, for 2.34.0: Anthropic SDK 29 artifacts, 30.2 MB, **MIT** (POM); JSqlParser 5.4 (no 5.4.x patch exists), one jar, LGPL-2.1 and Apache-2.0; MCP SDK 2.0.1: `mcp-core` + `mcp-json-jackson2` (12 artifacts, MIT core), or the `mcp` artifact, which brings Jackson 3; protocol versions up to 2025-11-25; in the CLI class path the Anthropic SDK's Jackson modules stay at 2.18.2 beside 2.22.3. The build pins 2.70.0 and the live part ran on it (26 artifacts alone, Jackson 2.19.4, no Apache HttpClient 5) (F-11) | §2.2, §2.6 | in the contract (MIT, `mcp-core` + `mcp-json-jackson2`, `jackson-bom`; 2.70.0 audited in `docs/licence-audit.md` §5) |

## 3. Findings

### F-W The first key was not scoped to a workspace (2026-10-07; in the contract since)

On 2026-10-07 `GET /v1/models/claude-sonnet-5-5` and `POST /v1/messages/count_tokens` both answered HTTP 400 with
`{"type":"error","error":{"type":"invalid_request_error","message":"This API key is not scoped to a workspace, so this
request must include the anthropic-workspace-id header with the ID of the workspace to use. Add the header, or use an
API key that is scoped to a workspace."},"request_id":null}`, no `request-id` header, `x-should-retry: false`, in
183-282 ms (that day's `models` and `count` reports; the files of those names now hold the live run's). The SDK raised
`BadRequestException`, `errorType()` `invalid_request_error`. The orchestrator confirmed it independently with one
metadata request.

SDK 2.34.0 sends `anthropic-workspace-id` only through its `AuthorizingHttpClient`, which it installs for credentials
resolved from a profile, OAuth or workload identity federation (`CredentialResult.workspaceId`, read from
`ANTHROPIC_WORKSPACE_ID` or the profile); with a static `ANTHROPIC_API_KEY`, `AnthropicBackend.resolveCredentials`
returns early and **no workspace header is ever sent, whatever `ANTHROPIC_WORKSPACE_ID` holds**. Two ways to send it
with a static key: a client default header (`ClientOptions.Builder.putHeader("anthropic-workspace-id", …)`, what the
probe does; checked offline: the header goes out on every request, `target/results-workspace-header-check/`), or
`ANTHROPIC_CUSTOM_HEADERS` (newline-separated `Name: value` lines), which `ClientOptions.fromEnv()` reads. SDK 2.69.0
adds a per-request `workspaceId(…)` to both `MessageCreateParams` builders. The workspace id is an account
identifier: the gateway must never log it, as it never logs the key.

The owner's second key (2026-10-08) is scoped to a workspace. The live run sent no `anthropic-workspace-id` (the
recorder saw no such header; `workspaceHeaderSent: false` in every live report) and every request was answered, the
models endpoint first (`GET /v1/models/claude-sonnet-5-5`, HTTP 200, `req_011CfqJgdkDVxh2jt7v3u1ug`). The contract's
`workspaceId` setting (§4.1, §4.4), which the gateway sends as the header, stays for organisation-level keys; that
`ANTHROPIC_CUSTOM_HEADERS` carries the header for the CLI (F-10) is still unverified, since no such key was at hand.

### F-1 Structured output

Request shapes as SDK 2.34.0 serialises them (the dry run, `target/results-dryrun/structured.json`), and what the live
run through 2.70.0 showed (`target/results/structured.json`, Sonnet 5.5, effort `low`):

- **Typed overload** `MessageCreateParams.builder()…outputConfig(OutputConfig.builder().effort(LOW).build())
  .outputConfig(LoanTerms.class)` on `record LoanTerms(String itemCategory, int loanDays)` sends, in 2.34.0,
  `"output_config":{"format":{"schema":{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
  "properties":{"itemCategory":{"type":"string"},"loanDays":{"type":"integer"}},"required":["itemCategory","loanDays"],
  "additionalProperties":false},"type":"json_schema"}}`: **the effort is gone**. `StructuredMessageCreateParams.Builder
  .outputConfig(Class)` replaces `output_config` with one that holds only the format (source:
  `paramsBuilder.outputConfig(OutputConfig.builder().format(format).build())`); a second `outputConfig(Class)` throws,
  and an `outputConfig(OutputConfig)` after it replaces the format in turn. The schema comes from victools
  `jsonschema-generator` (draft 2020-12, every field required, `additionalProperties: false`) and is checked locally
  against the SDK's own copy of the API's schema rules (`JsonSchemaValidator`: no numeric or string bounds, `minItems`
  only, …) before anything is sent. The answer is parsed into the record by the SDK (`StructuredMessage<T>`,
  `content()…text().text()` returns `T`).
- **2.70.0 keeps the effort** (1a): the same builder calls sent `"output_config":{"effort":"low","format":{…}}`, with
  the same `$schema` and schema (`effortAfterOutputConfigClass: low`). The answer,
  `{"itemCategory":"power tool","loanDays":3}` (332 input and 22 output tokens, `req_011CfqJgtMYU9TJw6BGEKU1n`), was
  parsed into `LoanTerms[itemCategory=power tool, loanDays=3]`.
- **Manual form** `OutputConfig.builder().effort(LOW).format(JsonOutputFormat.builder().schema(Schema.builder()
  .putAdditionalProperty(k, JsonValue.from(v))…))` sends both: `"output_config":{"effort":"low","format":{"schema":
  {…},"type":"json_schema"}}`, the schema exactly as given (here `additionalProperties: false`, `required`, an enum, a
  nested object, an array). The SDK does not check a manual schema locally; only the API does. Live (1b,
  `req_011CfqJh2kpkMvLq8nzvKZDX`): the answer (`"decision":"lend"`, a two-day ladder loan, three reasons) parses and
  satisfies the schema (the probe's check found no problem). The manual schema has no record behind it.
- **The limits**, each sent once on the manual schema (live):
  - `"minimum": 1` on the integer `loan.days`: HTTP 400 `invalid_request_error`, *"output_config.format.schema: For
    'integer' type, property 'minimum' is not supported"* (`req_011CfqJhFERCNaC38MzxnL4N`);
  - `"maxLength": 40` on the string `loan.item`: **accepted** (HTTP 200, `req_011CfqJhFxa4WkQAjY5fFaXX`); the answer's
    `item` was `ladder`, so whether the API enforces the bound is not shown;
  - `"minItems": 2` on the array `reasons`: HTTP 400, *"output_config.format.schema: For 'array' type, 'minItems'
    values other than 0 or 1 are not supported (got: [2, 5])"* (`req_011CfqJhPAf64sYvXg2GJLcZ`; the request's only
    `minItems` was 2).

  So the provider's limits are those of ai-inference F11 except string lengths, which a structured-output schema may
  carry (not tried in a strict tool's input schema).

### F-2 A strict tool with tool choice `auto`; forced tool choice; the follow-up turn

Shapes (the dry run, `target/results-dryrun/tools.json`; the live requests through 2.70.0 were the same):
`Tool.builder().name("record_loan").strict(true).inputSchema(InputSchema.builder().properties(…).required(List.of(
"item","days","borrower")).putAdditionalProperty("additionalProperties", JsonValue.from(false)))` serialises as
`{"input_schema":{"type":"object","properties":{…},"required":[…],"additionalProperties":false},"name":"record_loan",
"description":"…","strict":true}`, and `toolChoice(ToolChoiceAuto…)` as `{"type":"auto"}`, at
`output_config.effort: medium` (the session effort). Live (Sonnet 5.5, `target/results/tools.json`):

- 2a: the model called `record_loan` (`stop_reason: tool_use`, `req_011CfqJhQ8Sv1QkguFu1SgAV`) with
  `{"item":"hedge trimmer","days":2,"borrower":"walk_in"}`, which satisfies the strict schema (the probe's check found
  no problem). The response had **no `thinking` block** (`responseHadThinkingBlock: false`, `thinking_tokens: 0`). The
  `tool_use` block also carries `"caller":{"type":"direct"}`, which the SDK's `toParam()` echoed in the follow-up
  turns; a history without it was not tried.
- 2b: `ToolChoiceTool.builder().name("record_loan")` (`{"name":"record_loan","type":"tool"}`): HTTP 400
  `invalid_request_error`, `tool_choice: type "tool" and "any" are not supported for this model.`
  (`req_011CfqJhfuUBKz54azdbu17s`), as §4.1 says.
- 2c and 2d: the tool result (`"Recorded as loan 4711."`) sent back twice, once after the assistant turn **without**
  its `thinking` blocks (all that the SPI's `Content` types could carry when the spike was written) and once after the
  unchanged turn (`message.toParam()`, which echoes a thinking block as
  `{"signature":"…","thinking":"","type":"thinking"}`): both accepted, with the same answer (*"I've recorded loan 4711:
  the hedge trimmer goes to a walk-in borrower for 2 days."*, 641 input tokens). Since 2a returned no thinking block,
  the two requests were the same and the question was **not exercised** (`meaningful: false`). The documentation for
  Sonnet 5.5 says to pass thinking blocks back unchanged and that the history-editing check rejects edited history on
  accounts created after 2026-08-31; whether dropping them is refused stays open. The contract echoes them unchanged
  (`Content.Opaque`, §3.2), and Opus 5.5 returns one even at effort low (F-3), so on a loop over Opus the echo is what
  keeps the history whole.
- The limit on union types (2026-10-08, seen by the live session test of §15.3 item 6, not by the probe): a request
  whose strict tools hold 20 union-typed parameters (the sample's verbs projected as tools, with "value or a
  `$reference`" `anyOf`s and nullable optional keys) is refused with HTTP 400 `invalid_request_error`, *"Schemas
  contains too many parameters with union types (20 parameters with type arrays or anyOf). This causes exponential
  compilation cost. Reduce the number of nullable or union-typed parameters (limit: 16 parameters with unions)."*
  (`req_011CfqPveMeMAPsw2UNN4QfC`). The probe's one strict tool had none. The gateway sends a request's tools without
  `strict` when they hold more than 16 union-typed parameters (§4.1).

### F-3 Thinking and effort

The request is `{"max_tokens":1024,"messages":[…],"model":"claude-opus-5-5","output_config":{"effort":"low"}}`: no
`thinking` field, which the 5.5 models run as adaptive thinking. In 2.34.0: `OutputConfig.Effort` has `LOW`, `MEDIUM`,
`HIGH`, `XHIGH`, `MAX`; `ThinkingConfigAdaptive.Display` has `SUMMARIZED` and `OMITTED` only (no `updates`), and the
thinking config union has no `between_tools` (Sonnet 5.5's lowest setting; it would need
`putAdditionalBodyProperty("thinking", …)`); the `Model` constants stop at `claude-opus-4-7` (the string overload
`model(String)` takes any id). `Usage` types `input_tokens`, `output_tokens`, `cache_creation_input_tokens`,
`cache_read_input_tokens`, `cache_creation` (`ephemeral_5m_input_tokens`, `ephemeral_1h_input_tokens`),
`inference_geo`, `server_tool_use`, `service_tier`; other fields (`iterations`, `speed`) land in
`_additionalProperties()` (`BetaUsage` types `iterations()` and `speed()`). Live, the same question (a late-fee sum
whose answer is 3650) on each model (`target/results/thinking-sonnet.json`, `thinking-opus.json`):

- Sonnet 5.5 at effort `low` (`req_011CfqJhu5yYUqAeQxjvLG81`) and at `medium` (`req_011CfqJi1dszfjoMXPfqPWFw`): **no
  thinking block**, `output_tokens_details.thinking_tokens: 0`; the text shows the working and ends with 3650 (141 and
  143 output tokens) although the prompt asked for the number only, one more reason for structured output wherever a
  value is wanted (§3.2).
- Opus 5.5 at effort `low` (`req_011CfqJi9EGvmEWjGjBmW35L`): **a `thinking` block with empty text and a 948-character
  signature** (the documented default display, `omitted`), then the text `3650`; `thinking_tokens: 82` of 86 output
  tokens. A tool loop over Opus must echo that block unchanged (`Content.Opaque`, §3.2).
- Usage, in every response of the run: `input_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`,
  `cache_creation` (`ephemeral_5m_input_tokens`, `ephemeral_1h_input_tokens`), `output_tokens`,
  `output_tokens_details.thinking_tokens`, `service_tier` (`standard`, or `batch` in a batch result) and
  `inference_geo` (`global`); `iterations` only in the two answers to requests that carried `fallbacks` (F-7); no
  `server_tool_use` or `speed`. Every message also carried `"container": null` and `"diagnostics": null`.
- The models endpoint (`target/results/models.json`): Sonnet 5.5 and Opus 5.5 take every effort from `low` to `max`
  and only `adaptive` thinking (`enabled` and `disabled` are not supported: thinking cannot be turned off on either),
  with 1,000,000 input and 128,000 output tokens. Haiku 4.5 takes **no effort** (`effort.supported: false`), `enabled`
  and `disabled` thinking but not `adaptive`, and 200,000 and 64,000 tokens; asked for `claude-haiku-4-5`, the endpoint
  answers with the id `claude-haiku-4-5-20251001`.
- Haiku 4.5 and `effort`, checked by the orchestrator with raw requests outside the probe: a request to
  `claude-haiku-4-5` with `output_config.effort` is refused with HTTP 400 *"This model does not support the effort
  parameter."*; without it, and with a structured-output format, Haiku 4.5 answers, and the response names the model
  `claude-haiku-4-5-20251001`. So no Haiku-class call may carry `effort` (§5 item 16); sent at the settings' efforts
  (`effort.triage` and `effort.summary`, both `low`), it would fail the triage label (§11) and the session's history
  summary (§12.4) on every live call. Haiku 4.5 also accepts `fallbacks: "default"` with the beta header
  `server-side-fallback-2026-07-01` (HTTP 200, `usage.iterations` present), although it has no fallback models (F-7).

### F-4 Prompt caching

The system part is one text block, the lending desk's 22 rules (2,700 characters), with
`cacheControl(CacheControlEphemeral.builder().build())` → `"cache_control":{"type":"ephemeral"}` or
`.ttl(CacheControlEphemeral.Ttl.TTL_1H)` → `{"type":"ephemeral","ttl":"1h"}`; the GA type carries the TTL and the SDK
adds no beta header for it (2.34.0's `extended-cache-ttl-2025-04-11` constant is not used, and 2.70.0 sent none
either). Live (Sonnet 5.5, `effort: low`, the structured `{answer}` schema, the question "Which rule number covers
ladders?" after the breakpoint; `target/results/cache.json`):

- 4a and 4b, the 22 rules at the default TTL: the first call **wrote 1,135 tokens** (`cache_creation_input_tokens:
  1135`, all of them `ephemeral_5m_input_tokens`; `req_011CfqJiQ8Dp6Es2MhBwKqeg`); the identical second call, sent 2.4 s
  after the first, as soon as it had answered, **read them** (`cache_read_input_tokens: 1135`,
  `req_011CfqJiaJQfxvBLFnKzDxFk`). Each call had 26 uncached input tokens and 15 output tokens.
- 4c, a second edition of the rules with `"ttl":"1h"` and **no beta header**: accepted, as the current documentation
  says, and written as `cache_creation.ephemeral_1h_input_tokens: 1135` (`req_011CfqJigGaWtq5TJ18AH47q`). The batch of
  F-6, which was meant to read this entry, did not.
- 4d and 4e, the "short" prefix, the first 9 rules (1,247 characters, about 430 tokens of system text): it turned out
  to be a **626-token** prefix, above the 512-token minimum the documentation gives for Sonnet 5.5, so it cached:
  written (`ephemeral_5m_input_tokens: 626`), then read (626). The expectation that it would not cache was wrong because
  the structured-output format, with the request's framing, is part of the cached prefix: about 196 tokens of each
  prefix are not the system text (1,135 against about 939 for the long edition, 626 against about 430 for the short,
  from `count`'s figures). Whether a prefix reaches the minimum is a question for `countTokens` on the whole request,
  not for the length of the system text; `-Dbs13.shortRules` was not needed.
- The five answers were the same (`{"answer":"Rule 3"}`); the five calls cost USD 0.010305 at the §7.2 rates.

Because `usage.cache_creation` splits writes by TTL, the gateway prices them from the response (§4.1 `Usage`, §7.2),
as `PriceTable.cost(Usage)` does as built.

### F-5 `countTokens`

`client.messages().countTokens(MessageCountTokensParams.builder().model(…).systemOfTextBlockParams(…)
.outputConfig(…).addUserMessage(…))` posts the same body without `max_tokens` to `/v1/messages/count_tokens`
(`cache_control` and `output_config` with format and effort are part of the type, and the API took both). Live
(`target/results/count.json`, `cache.json`): **exact**. The count of 4a's request was 1,161 against
`input_tokens + cache_creation_input_tokens + cache_read_input_tokens` = 26 + 1,135 + 0 of its response, and that of
4d's 652 against 26 + 626: 0 % off in both cases, where §15.3 item 1 allows 10 %. Before the cache calls, `count`
(free) gave 222 tokens for the request with a one-line system part, 1,161 for each 22-rule edition and 652 for the 9
rules.

### F-6 Message Batches

`client.messages().batches().create(BatchCreateParams.builder().addRequest(Request.builder().customId("bs13-q0")
.params(Request.Params.builder()…)))`: the per-request params are their own type, not `MessageCreateParams` (same
fields, separate builder), so the gateway builds a batch request from its own model, not from the message params.
`retrieve(id).processingStatus()` (`in_progress`, `canceling`, `ended`) and `requestCounts()` for polling;
`resultsStreaming(id)` reads JSON Lines from `/v1/messages/batches/{id}/results` (the SDK builds that path; it does not
use `results_url`) as `MessageBatchIndividualResponse` (`customId()`, `result().succeeded()…message()`,
`errored()…error()`), matched by `custom_id`. Live (`target/results/batch.json`):

- Two Sonnet 5.5 requests (`bs13-q0` and `bs13-q1`, two questions, `max_tokens` 256), each with the one-hour prefix of
  4c inside its params (the same edition, `"ttl":"1h"`), were submitted at 19:16:22 UTC
  (`msgbatch_019B7khDj4rJavtuonE4SkEa`: `in_progress`, `results_url: null`, `expires_at` 24 hours after `created_at`).
  Polled every 15 s, the batch was `ended` at the 30th poll, **458 s** after the submission (`ended_at` 19:23:59 UTC),
  both requests `succeeded`, none errored, expired or was cancelled; `results_url` then named the path the SDK reads.
- Each result's usage says `service_tier: batch`. `bs13-q0` **wrote** the prefix (`ephemeral_1h_input_tokens: 1135`)
  and `bs13-q1` **read** 1,135 tokens. `bs13-q0` did **not** read the identical one-hour entry that 4c, a standard
  request, had written about 5 s before the submission: one observation, in which a standard request's entry did not
  serve the batch (whether `bs13-q1` read `bs13-q0`'s entry or 4c's, the data cannot tell).
- The results streamed in **reverse order** (`bs13-q1` before `bs13-q0`): match by `custom_id`, never by position.
  The two cost USD 0.002595 at the batch rate.
- 6x: a one-request batch with `"fallbacks":"default"` in its params and the beta header on the create call was refused
  at create, HTTP 400 *"requests[0] (custom_id='bs13-fallback'): The `fallbacks` parameter is not supported for batch
  requests."* (`req_011CfqJj4jSD5otDdkc1P7HK`), as documented.

### F-7 The server-side refusal fallback, `default` form

SDK 2.34.0 (2026-05-21) predates the `server-side-fallback-*` betas: no `fallbacks` builder method, no `AnthropicBeta`
constant, no `fallback` content block type. It can still be sent: on the beta endpoint
`com.anthropic.models.beta.messages.MessageCreateParams.builder().addBeta("server-side-fallback-2026-07-01")
.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))` posts to `/v1/messages?beta=true` with
`anthropic-beta: server-side-fallback-2026-07-01` and `"fallbacks":"default"` in the body; on the GA endpoint
`putAdditionalHeader("anthropic-beta", …)` plus the same body property gives the same request without `?beta=true`
(both shapes in the dry run, `target/results-dryrun/fallbacks.json`). Reading a fallback-served response with 2.34.0
(offline, the `shapes` report, the documented shape): the `fallback` block is a `ContentBlock` with no known variant
(`isValid()` false, raw JSON in `_json()`); `content()` and text extraction work; **`Message.validate()` throws** and
**a `ContentBlock.Visitor` without an `unknown` override throws** `AnthropicInvalidDataException: Unknown
ContentBlock`; `usage.iterations` is only in `_additionalProperties()` of the GA `Usage`. SDK 2.69.0 (2026-10-07) has
`fallbacks(BetaFallbacksParam.ofDefault())`, `BetaFallbackBlock`, `BetaFallbackMessageIterationUsage` and a
client-side `BetaRefusalFallbackInterceptor`; in 2.70.0, which the build pins, the typed parameter exists on the beta
request type only (§4.1, as built). The probe still sent it as the extra body property on both endpoints, and both
worked. Live (Sonnet 5.5, "Say OK.", `target/results/fallbacks.json`):

- 7a, the beta endpoint (`POST /v1/messages?beta=true`; `addBeta` sent `anthropic-beta:
  server-side-fallback-2026-07-01`) with `"fallbacks":"default"` in the body: accepted (`req_011CfqJiFZ2mghyjWDbyYeMA`).
- 7b, the GA endpoint (`POST /v1/messages`) with the same header added by hand and the same body property: accepted
  (`req_011CfqJiK8aoa2XubXUTen3k`).
- 7c, the GA endpoint with the body property and no header: HTTP 400 *"fallbacks: Extra inputs are not permitted"*
  (`req_011CfqJiP9CE8netgNSwFFaG`): the header is what makes the property legal.
- Both accepted responses carry **`usage.iterations`** although no fallback ran (`end_turn`,
  `model: claude-sonnet-5-5`, no `fallback` block): one entry, `type: message`, `model: null`, with its own token
  counts (13 input, 5 output, the cache fields); 2.70.0's beta types parse it (`BetaMessageIterationUsage`). The
  field's presence says that `fallbacks` was sent, not that a fallback answered. No refusal was provoked, so no
  `fallback` block, fallback credit or second model was seen.
- The models endpoint gives `allowed_fallback_models` only to a request that carries a `server-side-fallback-*` beta
  header (the same lists under `2026-06-01` and `2026-07-01`): Sonnet 5.5 → `claude-sonnet-5`; Opus 5.5 →
  `claude-opus-4-8` and `claude-opus-5`; Haiku 4.5 → none. A Haiku 4.5 request with `"fallbacks":"default"` and the
  header is accepted all the same (HTTP 200, `usage.iterations` present; the orchestrator's raw request), so the
  gateway sends the parameter for every class. Per the documentation, Sonnet 5.5's `default` fallback retries only
  `cyber` and `frontier_llm` declines, on `claude-sonnet-5`.

### F-8 `stop_reason` and `stop_details` (settled offline)

`Message.stopReason(): Optional<StopReason>` (`END_TURN`, `MAX_TOKENS`, `STOP_SEQUENCE`, `TOOL_USE`, `PAUSE_TURN`,
`REFUSAL`); `Message.stopDetails(): Optional<RefusalStopDetails>` with `category(): Optional<RefusalStopDetails
.Category>`, `explanation(): Optional<String>`, and unknown fields (`recommended_model`, the fallback-credit fields) in
`_additionalProperties()`. 2.34.0's `Category` knows `CYBER` and `BIO` only; the documented shape with
`"category":"frontier_llm"` parses, `value()` is `_UNKNOWN` and `asString()` returns `frontier_llm`; `"category":null`
gives an empty `Optional`; on `end_turn` with `"stop_details":null` `stopDetails()` is empty; a refusal before output
has `content()` empty. Sonnet 5.5's documented categories are `cyber`, `bio`, `frontier_llm`, `reasoning_extraction`,
`general_harms`. No refusal was provoked.

Live: all 18 responses of the run had `"stop_details": null`, with `stop_reason` `end_turn` (17) or `tool_use` (1).

### F-9 Errors, retries, timeouts and deadlines (partly live, mostly offline)

- **401** (live, 2026-10-07, SDK 2.34.0, `ANTHROPIC_API_KEY=invalid-key-for-the-spike`, through
  `AnthropicOkHttpClient.fromEnv()` exactly as §4.1 then built it): `com.anthropic.errors.UnauthorizedException`,
  `statusCode()` 401, `errorType()` `authentication_error`, body
  `{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"},
  "request_id":"req_011Cfp2iRukzPw4wQrszLbzq"}`, `request-id` header present.
- **404 and 400** (live, 2026-10-08, SDK 2.70.0, `target/results/errors.json`): an unknown model id →
  `NotFoundException`, `statusCode()` 404, `errorType()` `not_found_error`, *"model: claude-bs13-no-such-model"*
  (`req_011CfqJgqqVXQTyFfRfSQBKC`); an empty `messages` → `BadRequestException`, 400, `invalid_request_error`,
  *"messages: at least one message is required"* (`req_011CfqJgrvEEo3sZbAMQV3F3`). Both carry the request id in the
  body and the `request-id` header, and `x-should-retry: false`, as did every 400 of the run (1c, 1e, 2b, 6x, 7c). From
  the SDK's `ErrorHandler` (2.34.0 source): 400 `BadRequestException`, 401 `UnauthorizedException`, 403
  `PermissionDeniedException`, 404 `NotFoundException`, 422 `UnprocessableEntityException`, 429 `RateLimitException`,
  **every 5xx including 529 `InternalServerException`** (the overload is told by `errorType()` `overloaded_error`),
  **every other status (402 `billing_error`, 408, 409, 413 `request_too_large`) `UnexpectedStatusCodeException`**; I/O
  failures and timeouts `AnthropicIoException` (cause `ConnectException`, `SocketTimeoutException: Read timed out` or
  `InterruptedIOException: timeout`). `errorType()` reads `body.error.type` (`ErrorType` constants: invalid_request,
  authentication, permission, not_found, rate_limit, timeout, overloaded, api, billing).
- **Defaults** (`client`): `maxRetries` 2; timeouts per attempt: connect 1 min, read, write and request 10 min; log
  level from `ANTHROPIC_LOG` (off when unset), response validation off. Retried (source, `RetryingHttpClient`): 408,
  409, 429, every 5xx, and `IOException`/`AnthropicIoException`/`AnthropicRetryableException`; an `x-should-retry`
  header overrides; the back-off is 0.5 s × 2^(n-1) (at most 8 s) times a jitter of 0.75-1, **except that
  `retry-after-ms` or `retry-after` is obeyed without any cap**. The max-tokens-scaled default timeout that
  `RequestOptions` contains (30 s to 10 min) never applies, because `ClientOptions` always carries a `Timeout` (source).
- **Measured** (`transport`, 2026-10-07, loopback only): a refused connection: 3 attempts
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
- **Live retries**: none. The 70 exchanges of 2026-10-08 were all first attempts (`X-Stainless-Retry-Count: 0`); no
  429, 529, 5xx or I/O error was met.

### F-10 The Claude Code CLI, headless

`claude` 2.1.286. `claude --help` lists `--bare` ("Anthropic auth is strictly ANTHROPIC_API_KEY or apiKeyHelper via
--settings (OAuth and keychain are never read)"), `--output-format json`, `--json-schema`, `--mcp-config`,
`--strict-mcp-config`, `--allowedTools`, `--tools` (`""` disables every built-in tool), `--permission-mode` (choices
include `dontAsk`), `--permission-prompts none`, `--model`, `--resume`, `--no-session-persistence` and
**`--max-budget-usd`** ("Maximum dollar amount to spend on API calls (only works with --print)"). **`--max-turns` is not
in the help but is accepted** (an unknown option fails with `error: unknown option '…'`, exit 1; `--max-turns 1` and
`2` did not). Run without a key (2026-10-07), `claude -p --bare --output-format json --json-schema '{…}' --model
claude-sonnet-5-5 --max-turns 2 --max-budget-usd 0.25 --tools "" --permission-mode dontAsk "…"` exits **1** and prints
one envelope: `type: "result"`, **`subtype: "success"` with `is_error: true`**, `terminal_reason: "api_error"`,
`api_error_status: null`, `result: "Not logged in · Please run /login"`, `session_id`, `uuid`, `num_turns: 1`,
`total_cost_usd: 0`, `duration_ms`, `duration_api_ms: 0`, `stop_reason`, `usage` (with `cache_creation`,
`iterations`, `speed`, `fallback_credit`, `output_tokens_details.thinking_tokens`), `modelUsage: {}`,
`permission_denials: []`, `fast_mode_state`, `subagent_stats`, `queued_turn_count`, `result_index`; **no
`structured_output` key** and no `mcp_server_errors` key (no MCP server was configured).
`--resume <that session id>` with a second prompt is accepted, keeps the session id and fails the same way (exit 1);
**`--resume` of an unknown, UUID-shaped id exits 1 with plain text on stdout** ("No conversation found with session
ID: …"), not an envelope, despite `--output-format json`.

Live (2026-10-08, natively, the key in the environment, the two commands of `README.md`;
`target/results/cli-first.json` and `cli-resume.json`):

- The first call: exit 0 and one envelope: `type: "result"`, `subtype: "success"`, **`is_error: false`**,
  `terminal_reason: "completed"`, `api_error_status: null`, **`structured_output: {"answer":"OK"}`** (the same as JSON
  text in `result`), `session_id`, **`total_cost_usd: 0.0018205`**, `duration_ms: 1272`, `num_turns: 2` and
  `stop_reason: "tool_use"` for what its cost shows to be one model request, `usage` (4 input tokens, 513 written to the
  5-minute cache, 53 output; with `cache_creation`, `iterations`, `server_tool_use`, `speed`, `fallback_credit: null`
  and `output_tokens_details.thinking_tokens: 0`), `modelUsage` keyed by model (`claude-sonnet-5-5`: its tokens,
  `costUSD`, `contextWindow: 1000000`, `maxOutputTokens: 128000`, `costBasis: "list"`), `permission_denials: []`; no
  `mcp_server_errors` key (no MCP server was configured).
- `--resume <session_id>` with a second prompt: exit 0, the same `session_id`, `structured_output: {"answer":"YES"}`,
  the first call's 513-token prefix read from the cache (`cache_read_input_tokens: 513`; 91 tokens written). **Its
  `total_cost_usd`, 0.0026886, is the session's running total, not the call's**: this call's `usage` at the §7.2 Sonnet
  5.5 rates costs USD 0.0008681, and 0.0018205 + 0.0008681 = 0.0026886; its `modelUsage` adds up both calls too (8
  input, 106 output, 513 read, 604 written tokens), while `usage` and `num_turns` (2) are the call's own. The CLI's
  figures are at list prices (`costBasis: "list"`) and match the §7.2 Sonnet 5.5 rates to the last digit (input 2.00,
  output 10.00 and cache read 0.20 per million tokens, a 5-minute write at ×1.25).
- `--resume` of an id that is not a UUID: refused before any API call, exit 1, nothing on stdout and plain text **on
  stderr** ("Error: --resume requires a valid session ID or session title…"); no envelope either.
- `--max-budget-usd` on a resumed call (two later `--resume` calls of the same session by the orchestrator, outside
  the probe; no report under `target/results/` holds them) bounds **the invocation's own spend, not the session's
  running total**. Under `--max-budget-usd 0.001` the call was made (about USD 0.0014) and the CLI then ended, exit 1,
  with an envelope `is_error: true`, `subtype: "error_max_budget_usd"`, `terminal_reason: "budget_exhausted"`,
  `result: null`, `num_turns: 2` and `total_cost_usd` the running total (0.0040606): the bound is checked after a model
  call, so one call can pass it. Under `--max-budget-usd 0.0045`, with the running total already at 0.0041, the call
  succeeded (`is_error: false`, its own cost about USD 0.0016, the total 0.0056286). A budget failure has its own
  `subtype`, unlike the failure without a key above.
- `--json-schema` and `$schema` (2026-10-08, the Claude Code CLI 2.1.294, the Linux build run in the test image; the
  native 2.1.286 was not tried for this): a schema whose text declares the draft 2020-12 `$schema`
  (`"$schema": "https://json-schema.org/draft/2020-12/schema"`) is refused, exit 1, nothing on stdout, and on stderr
  `Error: --json-schema is not a valid JSON Schema: no schema with key or ref
  "https://json-schema.org/draft/2020-12/schema"`. The same schema without its `$schema` key is accepted, and so are
  `$defs` with `#/$defs/…` references, `definitions` with `#/definitions/…` references and a draft-07 `$schema`
  (three calls, about USD 0.0045). Item 10's schema had no `$schema` key. The planner strips the key before it passes
  its schema (§10.2).
- Not exercised: `--max-turns` at its bound, and `ANTHROPIC_CUSTOM_HEADERS` carrying a workspace header for an
  organisation-level key (the key needs none).

### F-11 Dependencies, licences and the CLI class path

Offline, 2026-10-07, for the SDK line then pinned (2.34.0). The build has pinned 2.70.0 since D88
(`anthropic-java.version` in the root POM), audited in `docs/licence-audit.md` §5, and the live part of this spike ran
on it: the probe's class path for 2.70.0 alone (`target/lib`) is 26 artifacts, 46,728,768 bytes
(`anthropic-java-core` 37,180,630), with Jackson 2.19.4, Kotlin 1.9.0 and OkHttp 4.12.0, and without Apache
HttpClient 5.

**Artifact ids** (Maven Central, 2026-10-07): JSqlParser's latest on the 5.4 line is `5.4` itself (5.0-5.4 exist, no
5.4.x patch). The MCP Java SDK 2.0.1 (group `io.modelcontextprotocol.sdk`) is split: **`mcp-core`** (core, depends on
`slf4j-api`, `jackson-annotations` and `reactor-core`), **`mcp-json-jackson2`** (the Jackson 2 JSON module) and
`mcp-json-jackson3` (Jackson 3, packages `tools.jackson`); the convenience artifact **`mcp` is `mcp-core` plus
`mcp-json-jackson3`**, so declaring `mcp` as §2.2 then said brings a second Jackson. `mcp-core` + `mcp-json-jackson2`
shares Jackson 2 with the Anthropic SDK and the project. `io.modelcontextprotocol.spec.ProtocolVersions` in 2.0.1:
`2024-11-05`, `2025-03-26`, `2025-06-18`, `2025-11-25`. Class files: SDK 52 (Java 8), JSqlParser 55 (Java 11), MCP 61
(Java 17), all fine for the Java 25 controller and never in a target JVM. The Anthropic SDK was at 2.69.0 on Maven
Central that day (2.34.1 is the 2.34 patch; 2.70.0 followed on 2026-10-08); 2.69.0 depends on Jackson 2.19.4 and
Kotlin 1.9.0.

**The trees** (`target/tree-*.txt`, `tree-anthropic-verbose.txt`): the Anthropic set is 29 artifacts, 30,206,891
bytes (`anthropic-java-core` alone 18,910,699 bytes, 9,618 entries, nothing outside `com/anthropic/` and
`META-INF/`); it pulls OkHttp, Okio, Kotlin's standard library 1.8.0 (Okio asks for 1.9.10 and loses to the SDK's
direct 1.8.0), Jackson 2.18.2 with the jdk8, jsr310 and Kotlin modules, victools `jsonschema-generator` with its
Jackson and Swagger modules, `swagger-annotations`, Apache HttpClient 5 and `standardwebhooks`. JSqlParser has no
dependency. The MCP set (core + Jackson 2) is 12 artifacts, 6,024,741 bytes, with `json-schema-validator` 2.0.4 and
its YAML and time libraries.

**On one class path** (`deps/cli-classpath`, `target/list-cli-classpath*.txt`): with the root POM's management as it
was then (`jackson-databind` and `jackson-dataformat-yaml` at 2.22.3), the CLI would carry 38 third-party jars
(35,879,055 bytes; then 5): Jackson core, databind, annotations and YAML at 2.22.x but **`jackson-datatype-jdk8`,
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

USD 0.034013 as `target/results/spend.json` sums the reports at the end of the live run, the two CLI envelopes
included:

| Report | USD | What |
|---|---|---|
| `structured.json` | 0.005390 | three Sonnet 5.5 answers (1a, 1b, 1d); 1c and 1e refused |
| `tools.json` | 0.005382 | three Sonnet 5.5 calls (2a, 2c, 2d); 2b refused |
| `thinking-sonnet.json` | 0.003400 | two Sonnet 5.5 calls |
| `thinking-opus.json` | 0.002280 | the one Opus 5.5 call (140 input, 86 output tokens) |
| `fallbacks.json` | 0.000152 | two Sonnet 5.5 calls; 7c refused |
| `cache.json` | 0.010305 | five Sonnet 5.5 calls: two 5-minute writes, one 1-hour write, two reads; two free counts |
| `batch.json` | 0.002595 | two batch requests at half price (one 1-hour write, one read); 6x refused |
| `models.json`, `count.json`, `errors.json` | 0 | metadata, token counts, refused requests |
| `cli-first.json` | 0.001821 | the CLI's first call: its `total_cost_usd` |
| `cli-resume.json` | 0.002689 | the CLI's resumed call: its `total_cost_usd`, the session's running total |
| **Total** | **0.034013** | |

The probe prices each response's usage at the built-in rates of §7.2 (the API reports tokens, not money; a batch result
at ×0.5); the CLI's figures are its own, at list prices, and agree with the same Sonnet 5.5 rates (F-10). Because the
resumed envelope's `total_cost_usd` includes the first call, the total counts that call twice: the money spent was
**USD 0.0322** (the probe's USD 0.029504 and the item-10 CLI calls' USD 0.0026886), of which the one Opus call is USD
0.00228. What was measured outside the probe cost about USD 0.0075 beyond that sum: the orchestrator's raw probes
(the Haiku 4.5 requests with and without `effort` and with `fallbacks`; the two budget calls of F-10 at about USD
0.0014 and 0.0016, which took the CLI session's running total to USD 0.0056286; the three `--json-schema` calls of
F-10, about USD 0.0045) and the union-type refusal of F-2, which cost nothing extra (the sample's own call in the live
session test, refused with HTTP 400). The run stayed well under the estimate of USD 0.25 and the probe's stop at USD
1.00. On 2026-10-07 nothing was spent: two requests refused by F-W and one with an invalid key.

## 5. What the contract should say differently

Items 1-15 are the offline run's proposals, 16-22 the live run's, 23 and 24 from what the live session measured
outside the probe. Every item is struck: each is in the contract now (as built on 2026-10-08, or as corrected in the
live session the same day), and the live run contradicted none of them.

1. ~~**§2.2, the Anthropic SDK's licence is MIT**~~: in §2.2, and audited for 2.70.0 (`docs/licence-audit.md` §5).
2. ~~**§2.2, the MCP row**~~: `mcp-core` and `mcp-json-jackson2` 2.0.1 in §2.2.
3. ~~**§2.2/§2.6, Jackson on the CLI class path**~~: §2.6 imports `jackson-bom`, manages Kotlin's libraries to one
   version and keeps a test without a key on the final class path.
4. ~~**§2.2, the SDK version**~~: the build pins 2.70.0 (D88), and the live part of this spike ran on it.
5. ~~**§4.1/§4.4, a `workspaceId` setting**~~: in §4.1 and §4.4 (the 400 that names the workspace is reported as
   `AUTH` with its message, not as a settings error); the owner's current key needs none (F-W).
6. ~~**§4.1, building the client**~~: explicitly from `ANTHROPIC_API_KEY`, with `maxRetries(0)` and the SDK's logging
   off.
7. ~~**§2.4/§4.1, deadlines and retries**~~: in §2.4; the live run met nothing to retry (F-9).
8. ~~**§4.1, the error table**~~: by `statusCode()` and `errorType()`, with `BILLING` for 402; the live 404 and 400
   arrived as `NotFoundException` and `BadRequestException`, as the table expects.
9. ~~**§3.2/§12, provider blocks in `Content`**~~: `Content.Opaque`, outside the fingerprint. Item 2c could not say
   whether dropping a thinking block is refused (Sonnet 5.5 returned none); Opus 5.5 returns one even at effort low.
10. ~~**§4.1, `outputSchema`**~~ (reworded in the live session): the manual form stays (the schema is the project's and
    goes out as built); §4.1's opening paragraph now says that the typed `.outputConfig(Class)` overload dropped
    `effort` in 2.34.0 and keeps it in 2.70.0 (F-1), and why the manual form stays all the same.
11. ~~**§4.1/§7.2, cache writes**~~: `Usage` carries both TTLs and `PriceTable.cost` prices from them; every live
    response carried the split.
12. ~~**§4.1/§4.4/§7.2/§8, fallbacks**~~: never in a batch (6x: refused at create), the header with the extra body
    property (7c: refused without the header), unknown blocks tolerated, `usage.iterations` read from the raw JSON,
    `claude-sonnet-5` priced; what the live run added is items 17 and 18.
13. ~~**§4.1, `StopReason`**~~: the category read as a string.
14. ~~**§8.3, batches**~~: their own params type, `resultsStreaming`, a match by `custom_id`, never by position (the
    live results came in reverse order).
15. ~~**§10.2, the planner's CLI call**~~: `--max-budget-usd`, `--tools ""`, `--strict-mcp-config`, success by exit code
    0 and `is_error: false`, `structured_output` and `mcp_server_errors` optional, a failed `--resume` in plain text
    (on stdout for a UUID-shaped unknown id, on stderr for an id that is not a UUID). Unverified still: the workspace
    header through `ANTHROPIC_CUSTOM_HEADERS`, which only an organisation-level key needs. The live run added item 22.
16. ~~**§3.2/§4.1, no `effort` for the Haiku class**~~ (in §4.1's `Effort` row and built since the live session):
    Haiku 4.5 refuses `output_config.effort` with HTTP 400 and the models endpoint reports `effort.supported: false`,
    so with `effort` every Haiku-class call (the triage label, §11; the session's history summary, §12.4) would fail.
    The open question of `fallbacks` on that class is closed: a Haiku 4.5 request with `fallbacks: "default"` and the
    beta header is accepted (HTTP 200, `usage.iterations` present) although Haiku 4.5 has no fallback models, so the
    gateway keeps sending the parameter for every class.
17. ~~**§4.1, `usage.iterations`**~~ (in §4.1's fallbacks and `Usage` rows since the live session): present whenever
    `fallbacks` is sent, one entry with `model: null` when no fallback ran (F-7), so its presence says nothing.
18. ~~**§7.2, `claude-opus-5`**~~ (priced in §7.2 since the live session): the models endpoint names it beside
    `claude-opus-4-8` as a fallback target of Opus 5.5.
19. ~~**§3.2, string lengths**~~ (in §3.2 since the live session): a string `maxLength` was accepted in a
    structured-output schema (1d; not tried in a strict tool's input schema), while a numeric `minimum` and a `minItems`
    other than 0 or 1 are refused as listed; `Schemas` keeps refusing string lengths, the conservative side.
20. ~~**§4.1/§8.3, the batch tier's cache**~~ (in §4.1's batch row since the live session): a batch request did not
    read the one-hour entry a standard request had written about 5 s before the submission, while the batch's second
    request read 1,135 tokens (one observation, F-6). So the warm prefix of §8.3's reading of the cache-hit criterion
    (the second job reads what the first job's batch wrote) must come from a batch, not from a standard request; that
    a later batch reads an earlier batch's entry is what §15.3 item 4's two jobs will show, since BS13 saw the requests
    of one batch only. A standard request reading a batch's entry was not tried either.
21. ~~**§7.2, the Haiku model id**~~ (built in the live session: §4.1's `Usage` row, §7.2's `cost`): a Haiku 4.5
    response names the dated id `claude-haiku-4-5-20251001` (the models endpoint answers the alias with it too), and
    the price table matched ids exactly, so a Haiku call would have been priced as `claude-opus-5-5`, at four times
    its rate, and noted as an unknown model, and the gateway would have noted it as a fallback, since the id differs
    from the one asked for. Now a response model that is the requested id followed by `-` and eight digits is the same
    model (no fallback note; `Usage.model` keeps the id the response names) and is priced as its alias; a truly
    unknown id is still priced at the Opus rate with the note. The 5.5 models answered under the ids asked for.
22. ~~**§10.2 item 5, the planner's cost over `--resume`**~~ (in §10.2 item 5 since the live session): a resumed call's
    `total_cost_usd` (and its `modelUsage`) is the session's running total, not the call's (F-10). The planner as
    first built made every envelope's `total_cost_usd` a ledger entry and added them up, which counted the first
    invocation once more for each repair round, in the cost it reported and in what it left of `--budget-usd`. Now
    each invocation's entry holds the difference from the session's highest total so far (`Planner.record`; a falling
    total costs 0 and is noted), and the session costs the last envelope's figure. `--max-budget-usd` bounds an
    invocation's own spend, not the running total (measured, F-10), so a repair invocation is given what is left of
    the planner's budget, floored at 0.01; the CLI checks the bound after a model call, so one call can pass it.
23. ~~**§10.2, `$schema` in the planner's schema**~~ (in §10.2 since the live session): the CLI 2.1.294 refuses a
    `--json-schema` whose text declares the draft 2020-12 `$schema` and accepts the same schema without the key
    (F-10), so the planner strips the key; `$defs` and `definitions` references stay usable.
24. ~~**§4.1, union-typed parameters in strict tools**~~ (in §4.1 since the live session): a request whose strict
    tools hold more than 16 union-typed parameters is refused with HTTP 400 (20 in the sample's session request, F-2),
    so the gateway sends such a request's tools without `strict`.

## 6. How to reproduce

Nothing is left to run. `README.md` has every command; the two runs used:

- Offline (2026-10-07, SDK 2.34.0, the probe's default): `mvn -f spikes/BS13/pom.xml package`; `client`, `shapes`,
  `transport`, the dry run and `auth` with the invalid key; the four POMs under `deps/` for item 11.
- Live (2026-10-08, SDK 2.70.0): the probe rebuilt with `mvn -f spikes/BS13/pom.xml -Danthropic-java.version=2.70.0
  package`, which needed Jackson 2.19.4, 2.70.0's own, downloaded into the local Maven cache (the build manages
  Jackson to 2.22.3); then `java -jar spikes/BS13/target/bs13-probe.jar live` in the owner's login shell, which exports
  the key as `ANTHROPIC_API_KEY` (no workspace id: the key is scoped to one), eight minutes in all, most of them the
  batch's wait, ending with `spend`.
- Item 10: the two `claude` commands of `README.md`, natively with the key in the environment, their envelopes copied
  to `target/results/cli-first.json` and `cli-resume.json` before `spend` ran; `--resume` once with an id that is not
  a UUID; and later, by the orchestrator, two `--resume` calls of the same session under `--max-budget-usd` 0.001 and
  0.0045.
- Haiku 4.5: raw requests to the Messages API by the orchestrator (with and without `output_config.effort`, and with
  `fallbacks: "default"` and the beta header), outside the probe, which has no sub-command for them.
- `--json-schema` and `$schema`: the Claude Code CLI 2.1.294 in the test image, given a schema with the draft 2020-12
  `$schema`, then without it, with `$defs` and `definitions` references, and with a draft-07 `$schema` (three billed
  calls). The union-type refusal came from the live session test (§15.3 item 6), not from a probe.

For each item the findings object of its report holds the answer (`1a`-`1e`, `2a`-`2d`, `3 …`, `4a`-`4e` with `5 …`,
`6 batch` with `6x`, `7a`-`7c`, `9a`/`9b`) and the exchanges hold the request ids, timings and usage. Since `target/`
is not committed, the figures quoted here are the lasting record; a re-run costs about the same (USD 0.03) and takes
about eight minutes.
