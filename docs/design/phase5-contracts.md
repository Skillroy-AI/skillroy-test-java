# Phase 5 contracts: the AI layer (inference gateway, model-written SQL, approval and budgets, batch re-resolution, MCP, the planner, triage, the interactive profile)

**Status: specified 2026-10-08, not built.** The questions of §17 are answered provisionally by the orchestrator and
recorded as decision **B23** (`../DECISIONS.md`, the owner interview page); nothing of this contract is code until the
owner has seen them. Written in the manner of `phase2-contracts.md`, `phase3-contracts.md` and `phase4-contracts.md`:
the contract is changed first, then the code, and the build marks every passage it builds or deviates from. Section
references `P1 §n`, `P2 §n`, `P3 §n` and `P4 §n` are to the four earlier contracts; `design §n` is the design overview
`base-framework-design.md`; `ai-inference §Fn/Rn` is the research note `../research/ai-inference.md`. Decisions taken
here are D70 onwards (§16); amendment ids continue the global sequence (A35-A38 are the Phase 4.x amendments of
`phase4-contracts.md` §16; this contract uses none).

What Phase 5 is for, in one paragraph. Phases 2 and 3 built resolution without a model: committed SQL or Java finds
the data a scenario needs, the application's own decision code verifies it, and CI replays the recorded bindings with
zero model calls. Phase 5 adds the model where decisions B6, B7 and B15 allow it and nowhere else: a model may write
the SQL that finds a binding (tier (b)), behind a syntactic guard, a verifier and a person's approval; a model may
author a scenario from a brief at authoring time, through the primitives exposed as tools; a model may label and
summarise a failure that deterministic code has already classified; and in the interactive profile a model may choose
the next action, through the executor's step protocol, journaled, so that the session exports an ordinary scenario.
CI stays replay-only with zero model calls, and **nothing in this repository's build needs a model key**: every test of
the AI layer runs against a recorded or scripted gateway, and the live gateway is exercised only by an opt-in profile
that is skipped without a key.

---

## 1. Scope and non-goals

Phase 5 delivers:

- **`skillroy-inference`**, a new controller-side module (Java 25): the `InferenceGateway` SPI with typed content
  parts (text, image, tool use, tool result; B7, B16), its Claude implementation on the Anthropic Java SDK, a
  **recorded** gateway that replays exchanges from files and a **scripted** gateway for tests, the gateway settings
  and the rule that `replay` never constructs a live gateway (§3, §4);
- **tier (b) resolution**: a controller-contributed resolver `inference.sql` (strategy `LLM_SQL`) whose statements a
  model writes as structured output and `SqlGuard` validates against the pack's allow-list before they run, with every
  row, column name and sample masked before it can reach a prompt; the pack's `SqlAllowList`; the prompt catalogue;
  spike BS6 as the gate (§5, §6);
- **approval and budgets**: approval mandatory for a tier (b) binding, the approval screen with the statement, the
  model's rationale, the masked row sample, the verifier evidence and the cost; `replay` refusing a tier (b) binding
  that was not approved; budget meters per scenario, per nightly job and per interactive session, consulted before
  every model call and recorded in the cost ledger, which gains a writer (§7);
- **batch re-resolution**: the binding mode `re-resolve` and the CLI command of the same name, which re-resolve every
  scenario of an environment through the provider's batch interface in graph rounds, verify against the live target,
  write the bindings files and a review report; the pull request is the pipeline's job and a sample workflow shows it
  (§8);
- **`skillroy-mcp`**, a new controller-side module: a Model Context Protocol server over stdio that projects the
  target's primitive catalogue and the pack's verbs as tools, with read-only and destructive hints, deferred loading
  of the low-level tools, the resources of design §5, and the hosting of an interactive session (§9);
- **the planner**: `skillroy author`, which runs the Claude Code CLI headless (`claude -p`) against the MCP server in
  read-only mode with the scenario JSON schema as the output constraint, validates what comes back, writes the YAML,
  compiles the plan and leaves the review to a person; the sample's scenario C is authored that way (§10);
- **triage**: `skillroy triage`, an opt-in command that reads a finished run's evidence after deterministic
  classification and writes a label and a summary beside it; never part of a run, never a verdict (§11);
- **the interactive profile** (B15): the executor's public single-call entry `ExecutorSession`, the `StepJournal`, the
  export of a journal to scenario steps and bindings that replay with zero model calls, and the session service that
  lets a person, an MCP client or an in-application overlay drive a live target with a model choosing actions, within
  a per-session budget and the safety rules of design §3.4 (§12);
- **scenario YAML and `plan.json` version 5**: the strategy token `llm-sql` accepted, the plan field `inference` written
  as `bind` when a plan needs a model at bind time, the existing base verb `call` confirmed as the form of an exported
  step that no verb covers (§13);
- **the sample**: the lending pack's `SqlAllowList`, its prompt catalogue, scenario C `return-refunds-deposit`
  authored by the planner from a one-paragraph brief and replaying green on both seeds, scenario D
  `supervisor-return-llm-sql` (scenario A's supervisor query re-bound through tier (b) against the recorded gateway),
  an interactive session on the desk exported from its journal and replayed; the recordings that make all of it run
  without a key (§14);
- **acceptance** without a display (the normal build), end to end in the Linux image (no key), and live (profile
  `inference-live`, skipped without a key) (§15).

**Non-goals.** Named so that their seams are respected, not built:

| Not in Phase 5 | Seam reserved here | Phase |
|---|---|---|
| Tier (c) resolution at bind time (`LLM_REASON`: a model reasoning over primitive outputs to find a binding) | `Strategy.LLM_REASON` and the token `llm-reason` stay parsed and rejected ("not in this version"); the gateway's tool-use parts and the tool projection of §9 exist, so a 5.x amendment can add the resolver without a signature change | 5.x, after the interactive profile has shown what the loop needs |
| The vision loop (`LLM_VISION`, `aiAct`, `aiExpect`, `pointer`, `expectImage`, `inference.vision`) | the `Image` content part of §3.2 carries a capture and the recorded gateway hashes it; the capability id `inference.vision` is reserved and `UNAVAILABLE` | 7 (B16) |
| A model drafting oracle Java at authoring time (design §3.3, ai-inference §R3) | none: the planner emits YAML that references committed oracles only; a drafting command is a later authoring aid | later |
| Provider implementations other than Claude (Bedrock, Vertex, another vendor) | the SPI of §3 is all a second implementation needs; `GatewayCapability` says what it lacks (B7) | when someone needs one |
| Streamable HTTP transport for the MCP server; MCP Tasks; Claude Code Channels; the Messages API MCP connector | stdio only (design §5, control-channel F17, F21; ai-inference F14) | later, if a remote controller needs it |
| A healer that patches a failing plan | triage writes a summary and, at most, a *proposal* in prose; nothing is applied (ai-inference F6, Risk6) | later, as proposals for review |
| Opening the pull request of the nightly job from the base | `re-resolve` writes files and a report; a sample workflow in `ci/` commits and opens the pull request with the pipeline's own tool | none (B11: files only) |
| A fluent Java DSL; Gherkin | unchanged (B5) | later |
| OTel export of model calls | the ledger and `inference.jsonl` carry what an exporter needs (B11) | 6 |

Phase 5 adds **two new controller-side modules** (`skillroy-inference`, `skillroy-mcp`) and **three new third-party
dependencies, controller side only** (the Anthropic Java SDK, JSqlParser, the MCP Java SDK; §2.2, §2.6). Nothing of
Phase 5 touches the agent side: no agent module changes, no new primitive, no new wire type, no model SDK in any
target JVM (B4, B15).

---

## 2. Module layout, dependencies, versions and build

### 2.1 Decision

- **`controller/skillroy-inference`** (package `ai.skillroy.testjava.inference`, Java 25) holds the gateway SPI, the
  Claude, recorded and scripted gateways, the prompt mask and catalogue, `SqlGuard`, the tier (b) resolver, the budget
  meters and the ledger writer, batch re-resolution, triage, the planner and the session service. It depends on
  `skillroy-scenario` (the resolution SPI, the executor's session entry, the journal), `skillroy-data` and
  `skillroy-controller`. P2 §2.1 decided this direction: the query and resolution model stays in `skillroy-scenario`,
  and `skillroy-inference` contributes to it. How a module that `skillroy-scenario` does not depend on gets its resolver
  into a bind is the one new seam of this layout, **`ResolverContributor`** (§5.5): a `ServiceLoader` SPI of
  `skillroy-scenario`, in the manner of `WebPath` (P4 §6.1, D49), through which `skillroy-inference` offers
  `inference.sql` to every bind whose settings name a gateway. Nothing else of the engine changes shape.
- **`controller/skillroy-mcp`** (package `ai.skillroy.testjava.mcp`, Java 25) holds the MCP server: the tool
  projection, the resources, the session host. It depends on `skillroy-inference`, `skillroy-scenario`,
  `skillroy-controller` and the MCP Java SDK. It is a library with a `main` class; the CLI starts it (`skillroy mcp`),
  and so does the planner.
- **`skillroy-scenario`** gains: `ExecutorSession` and `StepJournal` (the names P2 §11.5 reserved) with their export;
  the `ResolverContributor` SPI and `InferenceSettings`; the `call` base verb and plan version 5; the `inference`
  field written as `bind`; approval states and the `replay` rule for tier (b); `BindOptions.inference` (§5.5); the
  scenario JSON schema generator the planner needs (§10.2). It gains **no dependency**.
- **`skillroy-cli`** gains the commands `re-resolve`, `mcp`, `author`, `triage` and `session`, and the inference options
  of `run` and `resolve` (§13.4). It depends on `skillroy-inference` and `skillroy-mcp`; the distribution zip therefore
  carries the three new third-party sets (§2.6).
- **`skillroy-junit`** is unchanged in dependencies: a JUnit stream in `replay` needs no gateway. A stream in `approve`
  or `live` that needs tier (b) finds the contributor through `ServiceLoader` when `skillroy-inference` is on the test
  class path, as a scenarios module that wants it declares.
- **The sample**: `skillroy-sample-lending-pack` gains the allow-list and the prompt catalogue (no new dependency: the
  types are `skillroy-scenario`'s); `skillroy-sample-lending-scenarios` gains scenarios C and D, the recordings, and a
  test dependency on `skillroy-inference` and `skillroy-mcp`.

Dependency graph after Phase 5 (arrows = "depends on"; new edges in **bold**):

```
protocol                                   controller -> protocol
data -> controller, protocol               scenario -> controller, data
**inference -> scenario, data, controller, anthropic-java, jsqlparser**
**mcp -> inference, scenario, controller, mcp-sdk**
junit -> scenario                          cli -> scenario, data, controller, protocol, **inference, mcp**
cdp -> scenario, playwright (optional, unchanged)
lending-pack -> scenario, data             lending-scenarios -> lending-pack, junit, dist, app, agent (test), **inference (test), mcp (test)**
later: vision -> inference, scenario
```

No cycle. `skillroy-scenario` reaches `skillroy-inference` only through `ServiceLoader` at run time, never at compile
time.

### 2.2 Versions

| Piece | Version | Where | Licence (audited in `../licence-audit.md` §5 when built) |
|---|---|---|---|
| Anthropic Java SDK (`com.anthropic:anthropic-java`) | the current line at build time, 2.69.x on 2026-10-08 (D88; the research note's 2.34.0 predates the 5.5 models, the fallback parameter and the per-request workspace id, spike BS13 F-11); the exact version is pinned in the root POM and audited | `skillroy-inference`, main scope; the CLI distribution | **MIT** (BS13: the POMs of all three artifacts; the jars carry no licence file, so `THIRD-PARTY.txt` carries the MIT text and the copyright line) |
| JSqlParser (`com.github.jsqlparser:jsqlparser`) | 5.4 (the 5.4 line has no patch release; ai-inference F24; the design's BS6) | `skillroy-inference`, main scope; the CLI distribution; no dependencies of its own | dual, LGPL-2.1 and Apache-2.0 (two `<license>` entries in its POM, BS13 F-11); the project takes Apache-2.0 |
| MCP Java SDK: `io.modelcontextprotocol.sdk:mcp-core` and `io.modelcontextprotocol.sdk:mcp-json-jackson2` (BS13 F-11: the convenience artifact `mcp` is `mcp-core` plus the Jackson 3 module, which would bring a second Jackson) | 2.0.1, specification line 2025-11-25 confirmed in its `ProtocolVersions` (design §5; control-channel F18) | `skillroy-mcp`, main scope; the CLI distribution | MIT (both; BS13 F-11), with `reactor-core` (Apache-2.0), `reactive-streams` (MIT-0), `slf4j-api` (MIT) and `json-schema-validator` (Apache-2.0) in their trees |
| Claude Code CLI (`claude`) | whatever is on the `PATH`; 2.1 or newer is what the planner was written against | a run-time precondition of `skillroy author` only; never a dependency, never bundled (B3) | not distributed |
| Models | `claude-sonnet-5-5` (resolvers, sessions, summaries), `claude-opus-5-5` (the planner through `claude -p`), `claude-haiku-4-5` (triage labels) (ai-inference R7; B7) | `inference.yaml` (§4.4), all three replaceable | – |

The three SDK sets reach the local Maven cache before the build (the build runs offline, `-o`); the download is
authorised by the owner's "install what is needed" (B3) and recorded in `../TODO.md` under "Environment notes". Their
transitive sets are audited in `../licence-audit.md` before they are declared (B1 checklist item 2); spike BS13
(`../../spikes/BS13/RESULT.md` F-11) lists every artifact of the three trees with its licence and evidence in the
audit's own format, for the 2.34.0 line; the build re-lists the pinned SDK version's tree the same way. What the audit
finds goes into §5 of that file and the CLI's `THIRD-PARTY.txt`.

### 2.3 Build conventions

As P2 §2.3 and P4 §2.3: `--release 25` for both new modules; the Apache header on every file; no `mvn install`; the
distribution zip of the CLI gains the new jars and their attribution. Two new Maven profiles, both off by default:

- **`inference-live`**: the tests of §15.3, which call the live Claude API. The profile activates only when the
  environment variable `ANTHROPIC_API_KEY` is set **and** `-Pinference-live` is given; without the key the tests are
  skipped with the reason "no model key", never failed. These tests are never part of `mvn verify` in CI.
- **`inference-record`**: refreshes the recordings of §4.2 from the live API (a maintainer's task; needs the key).

The key reaches the JVM through the environment variable only (the SDK's `fromEnv()`): never a system property on a
command line (visible in `ps`), never a file in the repository, never `credentials.yaml` (that file holds the
*environment's* secrets, P2 §4; the model key is the *operator's*). The CLI accepts no `--api-key`.

### 2.4 Threads and bounds

Every gateway call runs on the thread that asked (the binder's thread during a bind; the session's thread in a session;
the batch job's thread), under a per-call deadline (`inference.yaml` `timeouts.callMs`, default 120000). The SDK's own
retries are **off** (`maxRetries(0)`; BS13 F-9: its timeout is per attempt, it obeys `retry-after` without a cap, an
asynchronous cancel does not stop the attempt, and its asynchronous path does not retry I/O errors): the gateway retries
`RATE_LIMIT`, `OVERLOADED` and `TRANSPORT` itself, at most twice, with the provider's `retry-after` honoured only while
it fits, and gives each attempt what is left of the deadline (`RequestOptions.timeout`), so that a call never outlives
`callMs`. No gateway starts a thread of its
own except the SDK's HTTP client pool, which is closed with the gateway. The binder's budget rule of P2 §6.4 holds: a
model call's deadline is clamped to what is left of `bindTimeoutMs`, and a bind that runs out of time during a model
call fails as `ENVIRONMENT` ("binding timed out in slot …"), not as a resolver failure. Time inside `Approver.review`
is still not counted. A session (§12) has no bind budget; it has the per-session money budget and the step protocol's
bounds.

### 2.5 What a target JVM sees

Nothing. No new primitive, no new wire type, no new agent module, no model SDK, no JSqlParser in any agent jar (B4).
The agent's `DistributionContentIT` keeps its seven jars (P4 §14.1).

### 2.6 What the CLI distribution carries

The CLI zip gains the three SDK sets with their transitive dependencies (the Anthropic SDK brings OkHttp, Okio,
Kotlin's standard library, Jackson's jdk8, jsr310 and Kotlin modules, the victools schema generator, Apache HttpClient 5
and `standardwebhooks`; JSqlParser is self-contained; the MCP SDK brings `reactor-core`, `slf4j-api` and
`json-schema-validator` with its YAML and time libraries), each attributed in `THIRD-PARTY.txt`: about 38 third-party
jars and 33 MB in place of today's five (BS13 F-11, for the 2.34.0 line). The root POM imports `jackson-bom` so that
every Jackson module is at one version, manages `kotlin-stdlib` and `kotlin-reflect` to one version (the newer Jackson
Kotlin module brings a newer `kotlin-reflect`), and keeps `slf4j-api` at the MCP set's 2.x; a test drives the SDK's
serialisation and parsing on the final class path without a key (§15.1 item 2). Playwright stays out of it (D49).

---

## 3. The inference gateway (`ai.skillroy.testjava.inference.spi`)

### 3.1 Decision

One interface, `InferenceGateway`, is the only way any code of this repository talks to a model (B7). It is thin on
purpose: a structured completion with typed content parts, an optional batch submit and collect, an optional token
count, a usage report per call. Everything the Anthropic API offers beyond that (server tools, computer use, memory,
compaction, files) stays outside the SPI; a later phase that needs one adds a capability flag and a method, as the web
SPI did (P4 §3). The SPI is provider-neutral in its types and Claude-shaped in its defaults: a second implementation
maps what it can and declares the rest absent through `GatewayCapability`.

### 3.2 Types

```java
package ai.skillroy.testjava.inference.spi;

/** The one seam to a model provider (B7). Instances are thread-safe; calls are synchronous and bounded. */
public interface InferenceGateway extends AutoCloseable {
    String provider();                                 // "claude", "recorded", "scripted"
    Set<GatewayCapability> capabilities();
    /** One completion. Throws InferenceException with a kind; never returns null. */
    ModelResponse complete(ModelRequest request) throws InferenceException, InterruptedException;
    /** BATCH: submit many requests for asynchronous, cheaper processing; collect when ended. */
    default BatchHandle submitBatch(List<ModelRequest> requests) throws InferenceException { throw new UnsupportedOperationException("BATCH"); }
    default BatchStatus batchStatus(BatchHandle handle) throws InferenceException { throw new UnsupportedOperationException("BATCH"); }
    default List<BatchResult> collectBatch(BatchHandle handle) throws InferenceException { throw new UnsupportedOperationException("BATCH"); }
    default void cancelBatch(BatchHandle handle) throws InferenceException { throw new UnsupportedOperationException("BATCH"); }
    /** TOKEN_COUNT: the input tokens of a request as the provider would count them; -1 when unknown. */
    default long countTokens(ModelRequest request) throws InferenceException { return -1; }
    @Override void close();
}

public enum GatewayCapability { STRUCTURED_OUTPUT, TOOLS, CACHING, BATCH, IMAGES, TOKEN_COUNT }

/** Which model, by role, so that callers never name a vendor's model id (B7). The settings map each to an id. */
public enum ModelClass { OPUS_CLASS, SONNET_CLASS, HAIKU_CLASS }
public enum Effort { LOW, MEDIUM, HIGH }
public enum CacheHint { NONE, SHORT /* the provider's default TTL */, LONG /* the longest the provider offers */ }

/** system: the frozen prefix, first (the prompt catalogue, the tool definitions, the instructions); the last part
 *  with cacheBreakpoint = true is where the provider's cache breakpoint goes. messages: the per-call facts last.
 *  outputSchema: a JSON Schema (a JSON-like Map) that the answer must satisfy, or null for free text.
 *  tools: offered with tool choice "auto" always (forced tool choice is refused by current models, ai-inference F12).
 *  metadata: for the ledger and the recordings' labels (scenario id, slot, purpose); never sent as prompt text. */
public record ModelRequest(String purpose /* "resolve", "triage", "session", "plan", "summary" */,
                           ModelClass modelClass, Effort effort, int maxTokens, CacheHint cache,
                           List<Content> system, List<Message> messages, List<ToolSpec> tools,
                           Map<String,Object> outputSchema /* nullable */, Map<String,String> metadata) {
    public static Builder builder(String purpose, ModelClass modelClass);   // defaults: MEDIUM, 4096, SHORT, no tools, no schema
    /** A stable fingerprint of what the model sees: sha256 over the canonical JSON of modelClass, effort, system,
     *  messages, tools and outputSchema; not maxTokens, cache, metadata (§4.2). Image parts enter by their sha256;
 *  Opaque parts (a thinking block's signature) do not enter at all. */
    public String fingerprint();
}
public record Message(Role role, List<Content> parts) { public enum Role { USER, ASSISTANT } }

/** Typed content parts (B7, B16): a capture can travel without a signature change. Sealed: a provider maps each. */
public sealed interface Content permits Content.Text, Content.Image, Content.ToolUse, Content.ToolResult, Content.Opaque {
    record Text(String text, boolean cacheBreakpoint) implements Content { public static Text of(String text); }
    /** mediaType image/png or image/jpeg; bytes as given. Reserved for Phase 7; the Claude gateway sends it, the
     *  recorded gateway hashes it, the mask treats it as personal data until §6.2 says otherwise. */
    record Image(String mediaType, byte[] bytes, String caption) implements Content { }
    /** A tool call the model made (in a response) or that the caller echoes back (in a request's history). */
    record ToolUse(String id, String name, Map<String,Object> input) implements Content { }
    /** The caller's answer to a ToolUse; content is text or JSON-like data; error marks a failed tool. */
    record ToolResult(String toolUseId, Object content, boolean error) implements Content { }
    /** A provider's own block that a caller must echo back unchanged (a thinking block with its signature, a fallback
     *  block): provider, the block's type and its raw JSON-like form. Never built by a caller, never rendered as text,
     *  kept by ModelResponse.parts and sent back by a loop that continues the conversation (§12.4); outside the
     *  fingerprint of §4.2 (signatures differ per call). BS13 F-2, F-7. */
    record Opaque(String provider, String type, Object raw) implements Content { }
}

/** A tool the model may call. inputSchema: JSON Schema with additionalProperties false and every property required
 *  (strict); readOnly and destructive mirror the primitive descriptor and become the MCP hints (§9). */
public record ToolSpec(String name /* [a-z0-9_]{1,64} */, String description, Map<String,Object> inputSchema,
                       boolean readOnly, boolean destructive) { }

public record ModelResponse(List<Content> parts, StopReason stop, Usage usage,
                            Object structured /* the parsed JSON-like answer when outputSchema was given, else null */) {
    public enum StopReason { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL }
    public String text();                                // the Text parts joined; "" when none
    public List<Content.ToolUse> toolUses();
}

/** What one call cost. costUsd is computed by the gateway from its price table (the API reports tokens, not money),
 *  as a plain decimal string; requestedModel differs from model when a fallback answered (§4.1). */
public record Usage(String provider, String requestedModel, String model, String requestId,
                    long inputTokens, long outputTokens, long cacheReadTokens,
                    long cacheWrite5mTokens, long cacheWrite1hTokens /* the provider splits writes by TTL, BS13 F-4 */,
                    boolean batch, String costUsd, Duration elapsed, String note /* a refusal's category, a fallback */) {
    public long cacheWriteTokens();                      // the sum; what the ledger entry carries
}

public record BatchHandle(String provider, String id, Instant submitted, int requests) { }
public record BatchStatus(State state, int succeeded, int errored, int expired, int canceled, int processing) {
    public enum State { IN_PROGRESS, ENDED, CANCELING }
}
/** One batch result, matched to its request by index (the gateway keys the provider's custom ids itself). */
public record BatchResult(int index, ModelResponse response /* null when not succeeded */, InferenceException error /* null when succeeded */) { }

public final class InferenceException extends Exception {
    public enum Kind { AUTH, BILLING, RATE_LIMIT, OVERLOADED, INVALID_REQUEST, REFUSAL, TRANSPORT, TIMEOUT, NO_RECORDING,
                       BUDGET, UNSUPPORTED, PROVIDER }     // BILLING: HTTP 402, not retryable (BS13 F-9)
    public Kind kind();  public String requestId();  public boolean retryable();   // RATE_LIMIT, OVERLOADED, TRANSPORT
}
```

Rules.

- *Parts, not strings.* A request's system and messages are lists of `Content`; a provider renders them. A caller never
  concatenates a user-supplied text into a system part (prompt-injection hygiene, design §5: what comes from the target
  is untrusted input and goes into `USER` messages, labelled).
- *Structured output first.* Where a caller needs data back, it passes `outputSchema` and reads `structured`; it never
  parses free text for JSON. Schemas obey the provider's limits (ai-inference F11: no recursion, no numeric bounds, no
  string lengths, `minItems` 0 or 1, no external references); `Schemas.ofRecord(Class)` (§3.3) builds compliant schemas
  from records and refuses what it cannot express.
- *Tools are optional and auto.* A request with tools gets tool choice `auto` and an instruction in its system text;
  a `TOOL_USE` stop returns the `ToolUse` parts and it is the caller's loop (the session service, §12) that answers
  them. The gateway itself never runs a tool.
- *Refusal is an answer.* `StopReason.REFUSAL` (with the provider's category in the usage's request id line of the
  ledger) is returned, not thrown; a caller treats it as "no answer" and records it. A gateway never retries a refusal.
- *Every call is accounted.* `complete` returns `Usage` on success; the caller records it in the `CostLedger` through
  `LedgerWriter` (§7.3). A failed call that the provider billed (a `MAX_TOKENS` stop is a success; a timeout after the
  provider started generating is not knowable) is recorded when the exception carries usage.
- *Bounded.* The deadline of §2.4 applies to `complete`, `countTokens` and each batch method; a batch's own lifetime
  is the provider's (24 h) and the nightly job bounds its wait (§8).

### 3.3 Helpers in the SPI package

- `Schemas.ofRecord(Class<? extends Record>)`: a JSON Schema (`type: object`, `properties`, `required` = every
  component, `additionalProperties: false`) from a record's components, recursively for nested records, lists and
  maps with string keys, enums as `enum`, `BigDecimal` as `string` with a `description`, `Optional`/arrays/recursion
  refused with `IllegalArgumentException`; `@Doc("…")` (a small annotation of this package) on a component becomes its
  `description`. The same schema serves structured outputs and strict tools.
- `Structured.decode(Type, Object jsonLike)`: decodes a `structured` answer through `RecordCodec.DEFAULT` of
  `skillroy-scenario` (P2 §6.2), so that a model's answer and a bindings file obey one set of rules.
- `Prompts`: canonical JSON rendering (`Json.writeCanonical` of `skillroy-protocol`) for fingerprints and recordings.

---

## 4. The three gateways and the settings

### 4.1 `ClaudeGateway` (`…inference.claude`)

Built on `com.anthropic:anthropic-java` (the version of §2.2), one `AnthropicOkHttpClient` per gateway, closed with it,
**built explicitly** from `ANTHROPIC_API_KEY` (and `ANTHROPIC_BASE_URL` when set), with `maxRetries(0)` and the SDK's
logging off: not `fromEnv()`, which also reads `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_PROFILE`, the workload-identity
variables, profiles on disk, `ANTHROPIC_CUSTOM_HEADERS` and `ANTHROPIC_LOG`, and would make the gateway's credential
source and its logging depend on an operator's shell (BS13 F-9). Structured output goes through the manual
`OutputConfig.builder().effort(…).format(JsonOutputFormat…)` form, because the typed `.outputConfig(Class)` overload
drops `effort` (BS13 F-1); the schema is the project's (`Schemas.ofRecord`). Response content is read tolerantly
(unknown block types become `Opaque` parts; the SDK's `validate()` and visitors without an `unknown` branch are not
used), and `stop_details.category` is read as a string (the SDK's enum lags the provider's categories; BS13 F-8).

| SPI element | Claude mapping |
|---|---|
| the key and the workspace | `ANTHROPIC_API_KEY` from the environment (`fromEnv()`); an organisation-level key (one not scoped to a workspace) is refused by the API with HTTP 400 unless every request carries the header `anthropic-workspace-id`, and SDK 2.34.0 adds that header on its own only for profile, OAuth and workload-identity credentials, so the gateway adds it itself for a static key from `inference.yaml` `workspaceId`, else the environment variable `ANTHROPIC_WORKSPACE_ID` (found by BS13 on 2026-10-08: the owner's key is such a key); a 400 whose message names the workspace is reported as `AUTH` with that message, never with the key |
| `ModelClass` | the ids of `inference.yaml` `models:` (defaults in §2.2); `requestedModel` in `Usage` |
| `Content.Opaque` | a `thinking` block (with its signature) or a `fallback` block of a response, kept as it came; a request's history that carries one sends it back unchanged (the 5.5 models return thinking blocks before tool calls, BS13 F-2) |
| `Effort` | `output_config.effort` `low`/`medium`/`high`; thinking is left at the model's default (adaptive; it cannot be disabled on Opus 5.5 and is not disabled anywhere, ai-inference F12) |
| `maxTokens` | `max_tokens` |
| `CacheHint` | `cache_control {type: ephemeral}` on the last system part marked `cacheBreakpoint` (`SHORT`: the default 5-minute TTL; `LONG`: `ttl: 1h`); none when `NONE`. The provider's minimum cacheable prefix is 512 tokens on the 5.5 models and 4096 on Haiku 4.5 (ai-inference F13): a shorter prefix silently does not cache, which the ledger shows as `cacheReadTokens = 0` |
| `Content.Text` / `Image` | text and image blocks (`base64`) |
| `ToolUse` / `ToolResult` | `tool_use` and `tool_result` blocks; a `ToolResult` with JSON-like content is rendered as JSON text |
| `ToolSpec` | a tool with `strict: true`, `additionalProperties: false` and every property required; tool choice `auto` always (forced choice is HTTP 400 on the 5.5 models) |
| `outputSchema` | `output_config.format` of type `json_schema`; the answer's text is parsed with `Json` into `structured` |
| `StopReason` | `end_turn`, `tool_use`, `max_tokens`, `refusal` (`stop_details.category` goes into the ledger's `note`) |
| `Usage` | `usage.input_tokens`, `output_tokens`, `cache_read_input_tokens`, `cache_creation.ephemeral_5m_input_tokens` and `ephemeral_1h_input_tokens` (the split by TTL; `cache_creation_input_tokens` is their sum), the response id, the model the response names, `usage.iterations` when a fallback ran; `costUsd` from the price table (§7.2), priced from the split, not from the `CacheHint` |
| `submitBatch` … `collectBatch` | the Message Batches API (`client.messages().batches()`, whose request params are their own type): one batch per call, `custom_id` = the request's index, results read as JSON Lines (`resultsStreaming`) and matched by `custom_id`, never by position; `costUsd` at the batch rate (50 % off, stacking with cache reads); no `fallbacks` in a batch |
| `countTokens` | `messages.countTokens` |
| errors | classified by `statusCode()` and `errorType()`, not by the exception class alone (BS13 F-9: 529 arrives as `InternalServerException` with `overloaded_error`, 402 and 413 as `UnexpectedStatusCodeException`): 401/403 `AUTH`; 402 `BILLING`; 429 `RATE_LIMIT`; 529 or `overloaded_error` `OVERLOADED`; 400/404/413/422 `INVALID_REQUEST`, except the 400 whose message names the workspace, which is `AUTH` with that message; `AnthropicIoException` `TRANSPORT`, or `TIMEOUT` when its cause is a timeout or the deadline passed; the rest `PROVIDER`; retries as §2.4 |
| fallbacks | `inference.yaml` `fallbacks: true` (the provisional default, §17 Q6) sends the server-side refusal fallback in its `default` form (the beta header `server-side-fallback-2026-07-01`, `fallbacks: "default"`; typed in the SDK line of §2.2, an extra body property on 2.34.0): a refused request is answered by a model the provider picks by refusal category, `Usage.model` names it and a `fallback` block comes back as an `Opaque` part; `false` sends none; **never in a batch request** (the provider rejects it; BS13 F-7). Either way the ledger records both model names |

What the gateway never does: it never logs a prompt or a response body (the run writes `inference.jsonl`, §7.4,
with metadata and masked structured answers only, and the prompt text itself only under `--log-prompts`); it never
reads a key from anywhere but the environment; it never selects a model the settings do not name; it never sends an
`Image` part when `IMAGES` is disabled in the settings (`images: false`, the default until Phase 7: a capture is
personal data until masked, design §5). Zero-data-retention: the 5.5 models and Haiku are eligible, Fable is not and is
never configured by default (ai-inference F10, R15).

### 4.2 `RecordedGateway` and `RecordingGateway` (`…inference.recorded`)

This is what lets CI, the Linux image and every unit test run the AI layer with no key.

- **A recording** is one file `<fingerprint>.json` in a recordings directory, holding `{version: 1, fingerprint,
  purpose, modelClass, requestedModel, model, recordedAt, request: {effort, system, messages, tools, outputSchema}
  /* canonical, masked */, response: {parts, stop, structured, usage}}`. The fingerprint is `ModelRequest.fingerprint()`
  (§3.2): the same request text, tools and schema give the same file name on any machine; `maxTokens`, `cache` and
  `metadata` are outside it, so a budget or a label change does not invalidate a recording. An `Image` part is
  recorded as its sha256 and media type, never its bytes.
- **`RecordedGateway(Path dir)`** answers `complete` from the file with the request's fingerprint, with the recorded
  usage marked `provider: "recorded"` and `costUsd: "0"`; a request with no file is `InferenceException(NO_RECORDING)`
  with the fingerprint and the purpose, which a resolver reports as `ENVIRONMENT` ("no recording for the request of slot
  …; record with -Pinference-record"), so that a changed prompt is seen, never guessed. Batches are served the same way,
  request by request, with `batch: true` in the usage. `countTokens` answers the recorded input tokens, or -1.
  Capabilities: all but `IMAGES`.
- **`RecordingGateway(InferenceGateway live, Path dir)`** delegates every call and writes the recording on the way back,
  through the prompt mask of §6.2 once more (a recording holds nothing a prompt may not hold, and a prompt holds no
  secret). A recording that already exists is overwritten only under the profile `inference-record`'s
  `-Dskillroy.inference.record.overwrite=true`; otherwise the live answer is used and the file kept, so that a
  maintainer refreshes deliberately.
- **Where the base keeps recordings**: `samples/skillroy-sample-lending-scenarios/src/test/resources/inference/`
  (the sample's tier (b) queries on both seeds, the triage of the sample's two negative runs, the session of §14.4, the
  planner's post-processing fixtures) and `controller/skillroy-inference/src/test/resources/inference/` (unit fixtures,
  hand-written where no live call is needed). They are committed, reviewed like fixtures, and byte-stable
  (`Json.writeCanonical`, sorted keys). A recording's request text is masked text: a reviewer can read it.
- **Determinism**: a recorded answer is replayed verbatim, so a test that depends on a recording is deterministic; the
  code path that *produced* the request (the prompt assembly, the catalogue, the mask) is what the fingerprint pins. The
  tests of §15.1 assert the fingerprint of each committed recording against the request the code builds today, so that
  a prompt change fails the build with "recording missing", not with a silently different model answer.

### 4.3 `ScriptedGateway` (`…inference.scripted`)

For unit tests that want to script an exchange without files: `ScriptedGateway.answering(Function<ModelRequest,
ModelResponse>)`, `ScriptedGateway.sequence(List<ModelResponse>)`, `ScriptedGateway.failing(InferenceException)`;
every request is kept in `requests()` for assertions. In main scope (as `Approver.APPROVE_ALL` is), so that the
scenarios modules' tests and a pack's tests can use it without a test-jar.

### 4.4 Settings: `inference.yaml`, properties and options

`InferenceSettings` (a record in `skillroy-scenario`, strings only, §5.5) is built from, in order of precedence, the CLI
options of §13.4, the system properties below, and the file `inference.yaml` found beside `credentials.yaml` (the same
lookup rule as P2 §4, the same strict reader; the file is optional and holds no secret, so it may be committed):

```yaml
version: 1
gateway: claude                 # claude | recorded | none; `replay` forces none (§5.5)
recordings: inference/          # for recorded: a directory, relative to the file; also where `record` writes
record: false                   # true: wrap the live gateway in RecordingGateway (profile inference-record)
workspaceId: null              # the workspace id for an organisation-level key (§4.1); else ANTHROPIC_WORKSPACE_ID
models:
  opus: claude-opus-5-5
  sonnet: claude-sonnet-5-5
  haiku: claude-haiku-4-5
effort: {resolve: low, triage: low, session: medium, summary: low}
budget:
  scenarioUsd: "0.50"           # B6; per bind of one scenario
  jobUsd: null                  # re-resolve: must be set, no built-in value (B6)
  sessionUsd: "1.00"            # B15; per interactive session
thresholds: {confidence: "0.8"}  # the tier (b) gates of §5.4 that are settings
prices: null                    # a YAML file with the price table of §7.2; null = the built-in table of this version
fallbacks: true                 # §4.1
images: false                   # Phase 7 turns it on
timeouts: {callMs: 120000, batchWaitMs: 7200000}
logPrompts: false               # true writes masked prompt text into inference.jsonl (§7.4)
```

| System property / option | Meaning |
|---|---|
| `skillroy.inference` / `--inference claude\|recorded\|none` | the gateway; default: `claude` when `ANTHROPIC_API_KEY` is set and the mode is `live`, `approve` or `re-resolve`, else `none`; `replay` always `none` |
| `skillroy.inference.recordings` / `--recordings <dir>` | the directory of the recorded gateway |
| `skillroy.inference.workspaceId` / `--workspace-id <id>` | the workspace id of an organisation-level key (§4.1); an account identifier, never a secret, but kept out of run outputs all the same |
| `skillroy.inference.budget.scenario`, `.job`, `.session` / `--budget-scenario <usd>` etc. | the budgets, decimal strings |
| `skillroy.inference.file` / `--inference-file <path>` | the settings file; default: beside the credentials file |
| `skillroy.inference.logPrompts` / `--log-prompts` | §7.4 |

A `gateway: claude` with no key is a settings error found before any scenario runs (exit 64 / the `environment`
testcase), with the message "the model key is not set (ANTHROPIC_API_KEY)" and never the value of anything.

---

## 5. Tier (b): model-written SQL behind `SqlGuard`

### 5.1 What a tier (b) resolution is

A scenario's query slot may list the strategy token `llm-sql` (`Strategy.LLM_SQL`, tier B) after its tier (a) tokens:
`strategy: [sql, llm-sql]`. The resolver ladder of P2 §8.3 is unchanged: the pack's resolvers for the kind are asked in
catalogue order, and when none of their candidates passes every verifier, the next resolver is asked; `inference.sql`
(§5.4) is always last on the ladder, so **a model writes SQL only when the committed SQL found nothing acceptable**, or
when the scenario lists `llm-sql` alone (a query kind the pack has no committed resolver for: the main use, ai-inference
R16 item 2). What it returns is candidates, as any resolver; the engine runs the verifiers; **the verifier is the gate
regardless of tier** (design §3.3). On top of the verifier, tier (b) adds three gates of its own: the guard (§5.3), the
model's confidence and row-count bounds (§5.4), and a person's approval before the binding is written (§7.1). In
`replay` nothing of this runs: the bindings file holds the value, the statement, the rationale and the approval, and
the verifiers re-check the value as for any binding.

### 5.2 `SqlAllowList` and the prompt catalogue (pack side, `skillroy-scenario`)

```java
package ai.skillroy.testjava.scenario.resolve;

/** What a model may read, and how the guard checks a statement. A pack declares one per data source. */
public record SqlAllowList(String source /* the DataChannel source id */, SqlDialect dialect,
                           List<AllowedTable> tables, List<String> procedures /* empty: no CALL */,
                           List<String> functions /* allowed function names beyond the built-in set; empty: none */) {
    public record AllowedTable(String name, List<AllowedColumn> columns, List<String> key /* the tie-breaker columns */,
                               String description /* for the catalogue; nullable */,
                               List<String> otherColumns /* every other column of the table, by name: never readable, so
                                   that the guard resolves every reference (BS6, §5.3 rule 4); may be empty */) { }
    public record AllowedColumn(String name, String type /* as the catalogue names it */, boolean personal /* masked
                                in samples and never selected into a candidate, §6.2 */, boolean sample /* small
                                enum-like: its distinct values go into the catalogue, masked */, String description) { }
    public static Builder builder(String source, SqlDialect dialect);
}
public enum SqlDialect { HSQLDB, MYSQL, MARIADB, POSTGRESQL, DB2, ORACLE, SQLSERVER, GENERIC }

public interface DomainPack {
    // … Phase 1-4 members unchanged …
    /** Allow-lists per source; empty: tier (b) is unavailable for this pack (the contributor offers nothing). */
    default List<SqlAllowList> sqlAllowLists() { return List.of(); }
}
```

Rules: an allow-listed column is a column of a `StoredTable` or of a table the resolvers' committed SQL already reads
(the catalogue builder checks the names against the schema through the fingerprint's statements, P2 §8.2, and refuses
an unknown table or column at compile time, so that an allow-list cannot name what the schema does not have); the
`key` columns of every table must exist and be non-nullable where the schema says so; a `personal` column may be
allow-listed for `WHERE` (a model may filter on it) but is never selected into a candidate value and never sampled;
a secret column (P2 §4's rule: a column the pack's `RowMask` or a `SecretKeys` pattern marks) may not be allow-listed at
all (a compile error).

**The prompt catalogue** (`…inference.prompt.SchemaCatalogue`) is the frozen text the model sees about the schema,
built once per bind (and cached per source and fingerprint across the scenarios of one run): per table, its name,
description, columns with types and descriptions, the key, and for each `sample` column up to 20 distinct values read
with one allow-listed `SELECT DISTINCT … LIMIT 21` (`truncated` when 21), every value through the mask of §6.2; plus the
dialect, the row cap (200), the rule set of §5.3 in words, and the shape the answer must take. It is rendered as the
first system part with `cacheBreakpoint: true` and padded with its own instructions so that it clears the provider's
512-token minimum on the sample (ai-inference F13); a catalogue under the minimum still works, uncached. The catalogue
text contains no credential, no personal value, no row of a non-sample column and nothing read from the target's UI.

### 5.3 `SqlGuard` (`…inference.sql`)

JSqlParser 5.4 parses the statement into an AST; the guard walks it and either returns a `GuardedStatement` (the
statement rewritten with the injected clauses, its parameters, the tables and columns it reads) or throws
`GuardViolation` with a reason code. The rules below are those spike BS6 proved on 2026-10-08
(`../../spikes/BS6/RESULT.md`: a prototype of the guard on every dialect of `SqlDialect`, 264 accept and 382 reject
statements, every HSQLDB rewrite executed; the first draft's rules are corrected where the corpora broke them). In
order:

| # | Rule | Reason code |
|---|---|---|
| 1 | **The guard's own lexical pass runs first**, before the parser (the parser drops comments silently, among them MySQL's executable `/*! … */` comments, which the server runs): plain comments are stripped; a hint (`/*+`), an executable, a nested or an unterminated comment is refused; parentheses nested more than 32 deep are refused (the parser's time and stack grow with depth: 640 levels overflow a 1 MB stack within the size limit of rule 9). Then the text parses in the dialect's parser preset as exactly one statement; a parse failure, and an `UnsupportedStatement` the parser returns for what it does not know, is a violation, never a pass-through (the statement is not run unparsed) | `COMMENT`, `SIZE`, `PARSE` |
| 2 | It is a `SELECT` (a plain select or a select with `WITH`); not `INSERT`/`UPDATE`/`DELETE`/`MERGE`, also inside a `WITH` (PostgreSQL allows `WITH d AS (DELETE … RETURNING)`), no DDL, no `INTO` of any kind (`DML`); no `CALL`/`EXEC` unless the procedure is allow-listed, then exactly `CALL p(?, …)` with parameters only, on every dialect, which the channel runs through the JDBC escape `{call …}` (T-SQL has no `CALL` of its own); no set operations (`UNION` and the like) in Phase 5; no locking of any form: table hints, isolation clauses (`WITH UR` and kin), every `FOR` clause, `LOCK IN SHARE MODE`; constructs outside the guard's grammar are refused as `UNSUPPORTED`: `CONNECT BY`, `PIVOT`, `LATERAL`, `APPLY`, `DISTINCT ON`, recursive CTEs, `GROUPING SETS`, query hints | `NOT_SELECT`, `DML`, `DDL`, `PROCEDURE`, `SET_OPERATION`, `LOCKING`, `UNSUPPORTED` |
| 3 | Every table (in `FROM`, joins, subqueries, CTEs) is on the allow-list of the one source, **by its unqualified name**: a qualified name (`schema.table`) and a database link (`table@link`; the parser's table name drops the link, so the guard checks the raw name) are refused; a quoted name must spell the declared name as the dialect folds case; aliases resolve; a CTE's name shadows nothing on the allow-list; `DUAL` is a keyless one-row table on Oracle, MySQL and MariaDB | `TABLE` |
| 4 | Every column reference resolves (through aliases, CTE columns and derived-table columns) to an allow-listed column of its table; **the allow-list carries every column name of each allow-listed table** (`AllowedTable.otherColumns`, the names the model may not read), so that an unqualified reference in a subquery cannot bind, in the guard's view, to an allowed outer column while the database binds it to a hidden inner one: a reference that could bind to more than one table is refused unless qualified; `SELECT *` and `t.*` are refused; a `personal` column may appear in `WHERE`, `JOIN … ON`, `GROUP BY`, `HAVING` and `ORDER BY` but not in the select list, and the flag follows a value through CTEs, derived tables and scalar subqueries | `COLUMN`, `STAR`, `PERSONAL` |
| 5 | Functions: the built-in set (`COUNT`, `SUM`, `MIN`, `MAX`, `AVG`, `COALESCE`, `NULLIF`, `CASE`, `CAST` to a scalar type only (no catalogue types such as PostgreSQL's `::regclass`), `LOWER`, `UPPER`, `TRIM`, `LENGTH`, `SUBSTRING`, `ABS`, `ROUND`, `CURRENT_DATE`, `CURRENT_TIMESTAMP`, `EXTRACT`), the dialect's default date functions (a fixed list per dialect in the guard, for example `DATEADD`/`DATEDIFF` on SQL Server and HSQLDB, `DATE_ADD`/`DATE_SUB` on MySQL and MariaDB, `ADD_DAYS`/`DAYS` on DB2, `ADD_MONTHS`/`TRUNC` on Oracle) and the allow-list's `functions`; a bare word is read as a function only from a fixed list (`SYSDATE`, `CURRENT_DATE`, …), so that a hidden column cannot be read under a function's name; window and ordered-set forms (`OVER`, `WITHIN GROUP`, `FILTER`, `KEEP`) are refused even for allowed names; anything else is refused (no `SLEEP`, no `LOAD_FILE`, no dialect side effects) | `FUNCTION` |
| 6 | Literals: numbers, plain strings, `DATE`/`TIMESTAMP`/`INTERVAL` literals, `TRUE`/`FALSE`/`NULL`; refused: a string longer than 200 characters, backslash escapes and MySQL double-quoted strings (their meaning depends on the server's settings), dollar-quoted, `E''`, `q''` and `U&''` strings, charset introducers, hexadecimal and binary literals; a value the model was told to bind as a parameter must appear as `?` (§5.4) | `LITERAL` |
| 7 | **`ORDER BY` is completed with a tie-breaker the guard derives**, not "the key of the driving table" (invalid after `GROUP BY` or `DISTINCT` and after a single-row aggregate, and not unique under a one-to-many join): the `GROUP BY` expressions for a grouped select, the select list for a `DISTINCT` select, nothing for a single-row aggregate, else the key columns of every table in `FROM` (a CTE or derived table contributes its derived key, or the statement is refused); the `ORDER BY` must **contain** them, in any position, and is completed by appending what is missing (an absent `ORDER BY` is added); an `ORDER BY` inside a subquery is refused; a column joined with `USING` is written unqualified (Oracle requires it); an `ORDER BY` expression the guard cannot resolve to allow-listed columns is refused | `ORDER` |
| 8 | A row limit of at most 200 in **the dialect's one form**, injected when absent and capped when larger: `LIMIT n` on HSQLDB, MySQL, MariaDB and PostgreSQL; `FETCH FIRST n ROWS ONLY` on DB2, Oracle 12c and newer, and `GENERIC`; `TOP n` on SQL Server (its `FETCH` needs `OFFSET`); refused: a parameter or `0` as the limit, `PERCENT`, `WITH TIES`, `LIMIT a, b`, `OFFSET`, `ROWNUM` | `LIMIT` |
| 9 | Depth and size: at most 3 nested subqueries, 8 tables, 20 columns in the select list, 32 nesting parentheses, 4000 characters | `SIZE` |
| 10 | **The output check**: the deparsed text, which is what runs (never the model's own text), contains no comment marker, parses back to itself, and passes rules 2-9 again with the same parameters, tables and columns | `REWRITE` |

The guard is deterministic and pure: the same text and allow-list give the same `GuardedStatement` or the same
violation on every run (BS6: each accept statement carries its expected rewrite, byte for byte). Its rewrite is textual
through JSqlParser's deparser, which keeps the dialect's own syntax (backticks, `::` casts); the guard runs in about
0.1 ms per statement once warm. **BS6 is the gate, and it passed on 2026-10-08**: its accept and reject corpora for
HSQLDB, MySQL, MariaDB, PostgreSQL, DB2, Oracle, SQL Server and `GENERIC` become the guard's unit tests in
`skillroy-inference` (the prototype's rules are lifted, not rewritten), every reject-corpus statement refused with the
reason the corpus names, every accept-corpus statement accepted and rewritten identically on every run, the HSQLDB
accept corpus also executed on the embedded database. What the parser cannot handle per dialect is recorded in the
spike's `parser-limits.sql` with the guard's outcome for each. Per dialect, in the words of this section: HSQLDB,
MySQL, MariaDB and PostgreSQL are served; DB2 is served, with the prompt catalogue telling the model to write
`ADD_DAYS`/`DAYS()` or singular durations (plural durations such as `- 7 DAYS` are refused as `PARSE`); Oracle is
served for 12c and newer (11g stays unavailable: no `FETCH FIRST`, and `ROWNUM` is refused); SQL Server is served with
`CALL` through the JDBC escape; `GENERIC` only for a database that accepts `FETCH FIRST`. A dialect without a passing
corpus is refused by the allow-list builder with "no BS6 corpus" rather than served loosely (design §3.9, ai-inference
Risk2). Before a dialect other than HSQLDB serves a production target, its accept corpus should also run on a real
server of that dialect (an open item of the spike; the base runs HSQLDB only).

### 5.4 The resolver `inference.sql` (`…inference.resolve.LlmSqlResolver`)

A `Resolver<Q, T>` for every query kind of the pack whose value type the codec supports (P2 §6.2's table) and whose
pack declares at least one `SqlAllowList`; strategy `LLM_SQL`; id `inference.sql` (the reserved prefix of P2 §3.3).
`candidates(query, ctx)`:

1. **Budget check** (§7.2): the scenario's meter must have room for the estimated cost of one call (the catalogue's
   cached size plus 2000 tokens, at the price table), else `ResolutionException(BUDGET)` ends the slot
   (`RESOLUTION_FAILED` reason `BUDGET`, with the meter's figures in the detail).
2. **The request**: system = the prompt catalogue (cached part) plus the fixed instructions (write one `SELECT` for
   the dialect; select the columns of the value type by these names; bind every value that comes from the query's
   arguments as `?` in order and list them in `params`; order deterministically; at most 200 rows; refuse if the
   question cannot be answered from the allow-listed tables); messages = one `USER` message with the query kind, the
   value type's fields and types, and the query's arguments **after the mask** (§6.2) rendered as canonical JSON; output
   schema = `SqlProposal`; model class `SONNET_CLASS`, effort `resolve` (low), `maxTokens` 2048, `CacheHint.SHORT`
   (`LONG` under `re-resolve`). A `ToolSpec` list is not offered: tier (b) is one structured answer, not a loop.

   ```java
   public record SqlProposal(String sql, List<Param> params, String rationale, List<String> tablesUsed,
                             String orderBy, double confidence /* 0..1 */, RowBounds expectedRows, boolean cannotAnswer,
                             String cannotAnswerReason) {
       public record Param(String name /* the query argument it binds */, String value /* as text */, String type /* STRING, NUMBER, DATE, TIMESTAMP, BOOLEAN */) { }
       public record RowBounds(int min, int max) { }
   }
   ```
3. **The gate**: `cannotAnswer` → no candidates, with the reason in `Provenance.notes`; `confidence < 0.8` (the
   threshold of ai-inference R13, a setting `thresholds.confidence` of `inference.yaml`) → no candidates, recorded as
   rejected `{resolver: inference.sql, label: "proposal", reason: "confidence 0.6 below 0.8"}`; the statement through
   `SqlGuard` → a violation gives no candidates with the reason code and the violating fragment (never the whole
   statement in a *message* a person did not ask for; the full statement goes into provenance, masked). Every `Param`
   must name an argument of the query (or a field of one) whose text equals the value the model wrote, so that a model
   cannot smuggle a literal through a parameter; a param that matches nothing is rejected (`PARAM`).
4. **Execution** through `ctx.data()` on the allow-list's source with the guarded statement and its parameters in the
   forms of P2 §7.1 (a `DATE`/`TIMESTAMP` param in the forms the channel accepts), under the slot's remaining budget;
   the rows pass the channel's masks as any read (P2 §7.3). The row count must lie in `expectedRows` (bounds the model
   stated, clamped to 1..200; a count outside is rejected "row count 0 outside 1..5"); the rows are decoded to `T`
   through the value type's codec by column name (a missing or extra column is rejected `SHAPE`), at most
   `ctx.maxCandidates()` of them, in the statement's order.
5. **Provenance**: the `Statement` record carries the guarded statement, the parameters (masked), the row count and
   the masked sample as for tier (a); `Provenance` gains a `rationale` component (the model's text, masked, at most
   2000 characters; null for tier (a)) and the injected clauses stand in the statement's text itself (the guard's
   rewrite is what ran), so that the bindings file, which keeps `notes` out as P2 §9 says (no dates, D47), still
   shows a reviewer why the statement was written; `Provenance.notes` carries the rest, for the run record only; the
   `Resolution` gets `confidence` from the proposal and `usage` from the ledger entries of the call (P2 §6.3's "where
   the Phase 5 fields get their values"); `Approval` is `REQUIRED` until a person approves (§7.1).

A refusal or a `MAX_TOKENS` stop is "no candidates" with a note. An `InferenceException` of a retryable kind is tried
once more within the slot's budget, then the slot fails `ENVIRONMENT` ("the model provider could not be reached:
<kind>"); `AUTH` and `INVALID_REQUEST` fail at once the same way; `NO_RECORDING` as §4.2 says. The resolver never
calls a mutating primitive and never sees a credential (it is a resolver of P2 §6.4 like any other).

### 5.5 `ResolverContributor` and `InferenceSettings` (`skillroy-scenario`)

```java
package ai.skillroy.testjava.scenario.resolve;

/** A controller-side module that offers resolvers to a bind without being a pack. Found through ServiceLoader on the
 *  thread context class loader (as WebPath, P4 §6.1); consulted once per bind in live, approve and re-resolve;
 *  never in replay. Ids must start with "inference." (the reserved prefix). */
public interface ResolverContributor extends AutoCloseable {
    /** Resolvers for this pack and these settings; empty when the settings disable it (gateway none). May open a
     *  gateway; closed when the bind ends. */
    List<Resolver<?,?>> resolvers(DomainPack pack, InferenceSettings settings, CostLedger ledger, Clock clock)
            throws ContributionException;
    /** Capability ids this module contributes to the matrix (inference.gateway, inference.sql), with their state. */
    default Map<String, CapabilityState> capabilities(InferenceSettings settings) { return Map.of(); }
    @Override default void close() { }
}
/** Strings only, so that skillroy-scenario names no inference type (§4.4 says how it is filled). */
public record InferenceSettings(String gateway, Path recordings, boolean record, String workspaceId, Map<String,String> models,
                                Map<String,String> effort, Map<String,String> budgets, Map<String,String> thresholds,
                                Path prices, boolean fallbacks, boolean images, Map<String,Long> timeouts,
                                boolean logPrompts, Path file) {
    public static InferenceSettings none();            // gateway "none": what replay uses
    public static InferenceSettings fromSystemProperties(Path credentialsDir) throws SettingsException;
    // as built 2026-10-08 (E2): also load(mode, dir, options[, properties, environment]) for the CLI and tests,
    // enabled(), and the budget keys scenario, job, session; a null map means the defaults; an unknown key, a wrong
    // type or another version is a SettingsException naming the line, never a value; `claude` without the key and
    // `recorded` without a directory are refused there
}
```

`BindOptions` gains `InferenceSettings inference` (default `none()`), `withInference(…)`, `reuseBindings` (default
true, §7.1) and `fromSystemProperties(...)` reads §4.4 (as built 2026-10-08: the nine-argument Phase 2 constructor
stays; `inference.yaml` is looked for beside the credentials file, else in `<root>/<env>/`; a `SettingsException` is
rethrown as `IllegalArgumentException` so that the JUnit glue's `environment` test catches it unchanged). The engine,
when it builds a slot's ladder: the pack's resolvers first (P2 §8.3 step 4b), then the contributed ones in
`ServiceLoader` order, both restricted by the slot's `strategy` list; a slot whose list names `llm-sql` while no
contributor offers it is `ENVIRONMENT` ("no resolver for strategy llm-sql: the gateway is none / skillroy-inference is
not on the class path / the pack declares no allow-list"). As built (2026-10-08, E2): **a contributed resolver joins a
ladder only when the slot names its token** (a slot without a strategy list keeps the pack's resolvers alone, so that a
plan whose `inference` is `none` makes no model call); contributors are loaded once per engine, on the first slot that
names a model token, validated when loaded (an `inference.` id and a model strategy, else `ENVIRONMENT` "contributor …
offered …") and closed with the engine; `ResolveContext` gains `slot()` and `scenarioId()` so that a ledger entry can
name both; `replay` writes an `inference` record saying that `none` was substituted. **In `replay` the
engine neither loads a contributor nor constructs a gateway**, whatever the settings say (`InferenceSettings.none()`
is substituted and logged); that, with the ledger check, is the "zero model calls" assertion's mechanism. A contributed
resolver is never part of the pack's catalogue (`ResolutionCatalogue.build()` keeps refusing an `LLM_*` strategy and an
`inference.` id for a pack's own resolvers): the engine appends it to the ladder after the catalogue's resolvers and
checks the inverse rule, an id starting with `inference.` and an `LLM_*` strategy, refusing a contributor that offers
anything else (`ENVIRONMENT`, "contributor … offered …").

---

## 6. Masking before any prompt

### 6.1 Decision

Nothing reaches a model that a bindings file could not hold, and less: a prompt holds no credential, no secret column,
no personal value, no row of a column that was not allow-listed for sampling, and nothing read from the target's UI
or state except through the labelled, bounded channels of the session service (§12.3). The mechanism is one class,
`PromptMask`, applied by the request builders of every purpose (resolve, triage, session, summary) to every part before
`ModelRequest` is built, and by `RecordingGateway` once more on the way to disk. Masking happens on the controller; the
agent's own redaction (A34, `MaskPolicy`) and the controller's `RowMask` and `SecretMask` keep their roles (P2 §7.3) and
run first, so `PromptMask` adds a layer rather than replacing one.

### 6.2 `PromptMask` (`…inference.prompt`)

```java
public final class PromptMask {
    /** secrets: the run's SecretMask (credentials, fixture secrets, A25); allow: the pack's allow-lists; rowMasks: the
     *  pack's; keys: SecretKeys.DEFAULT_KEYS plus the module-supplied patterns the agent reports (A21). */
    public static PromptMask of(SecretMask secrets, List<SqlAllowList> allow, List<RowMask> rowMasks);
    public String text(String text);                        // SecretMask.apply + the key rule on "key: value" and "key=value" shapes
    public Object jsonLike(Object value);                   // recursively: secret keys -> "<redacted>", values through the SecretMask
    public Rows rows(String source, Rows rows);             // RowMask per column, then: a column not on the source's allow-list is dropped; a personal column's values become "<personal>"
    public List<Content> parts(List<Content> parts);        // text and tool parts through the rules above; an Image part is refused unless images are enabled
}
```

Rules.

- *By value.* Every credential value the run knows (`Credentials.mask()`, with the environment's `masking.minLengthInText`,
  P2 §4) is replaced wherever it appears in a text or a value, as `SecretMask` does for run outputs.
- *By key.* Any JSON-like key that `SecretKeys.looksSecret` matches, or that a module's redaction keys match, has its
  value replaced; the same for `key: value` and `key=value` runs inside text (the shapes the agent's redaction covers).
- *By column.* A row that goes into a prompt (a sample in the catalogue, a row sample in a triage request, a `db`
  fact in a session) keeps only allow-listed columns of its source, with `personal` columns replaced by `<personal>`
  and the pack's `RowMask` applied first. A source with no allow-list contributes no row to any prompt.
- *By origin.* Text read from the target (a dialog's text, a snapshot, an event payload, application state) enters a
  prompt only inside a `USER` message, wrapped in a labelled block (`<target-ui>` … `</target-ui>`, `<target-event>`,
  `<target-state>`) that the system text declares to be data, not instructions; a session's system text never embeds it.
  The person's own request to a session is data too (`<person>`).
- *Captures.* An `Image` part is personal data until Phase 7 says how a capture is masked (design §3.10); with `images:
  false` (the default) the mask refuses it with `IllegalStateException`, and no Phase 5 request builder creates one.
- *Bounded.* A text part is cut at 100000 characters and a rows sample at 20 rows before masking, with `[truncated]`
  appended, so that a runaway snapshot cannot carry a secret past the mask by size.

The mask is tested with the project's existing adversarial fixtures (short and long secrets, quoted secrets, secrets
inside JSON strings, the key patterns) and with the sample's `password` column; a test proves that a prompt built for
every purpose of this contract contains none of the environment's credential values and none of the sample's
`personal` values.

---

## 7. Approval, budgets and the ledger

### 7.1 Approval of a tier (b) binding

`Approval.State` gains `REQUIRED` (between `NOT_REQUIRED` and `APPROVED`); the record keeps its shape. Rules by mode:

| Mode | A tier (b) resolution |
|---|---|
| `live` | runs and is used by the run; `approval` is `REQUIRED`; with `--write-bindings` the file is written with `REQUIRED` (so that a later `replay` refuses it until a person approves) |
| `approve` | runs (or is reused, below); the approval screen shows what §7.1 lists; approved → the file holds `APPROVED` with `by`; not approved → `RESOLUTION_FAILED` / `NOT_APPROVED` as today, nothing written |
| `replay` | a recorded slot with `strategy: LLM_SQL` whose `approval.state` is not `APPROVED` is `REBIND_NEEDED` reason `NOT_APPROVED` (the file and the slot in the detail); otherwise as any recorded slot: the verifiers re-run on the value, no model is involved |
| `re-resolve` | §8: written `APPROVED` with `by: "re-resolve:<jobId>"`, the review of the pull request being the gate (Q1) |

**The approval screen** (P2 §12.1) shows, for a tier (b) resolution, after the value and the verifier reasons: the
strategy (`llm-sql`), the model, the request id and the cost; the rationale; the guarded statement, with the clauses
the guard injected marked `[injected]`; the parameters and which argument each binds; the masked row sample; the
confidence and the row bounds the model stated and the count found. For the JUnit path nothing changes: `approve` in a
test is scripted or fails (`NOT_APPROVED`).

**Reuse in `approve`** (`--reuse-bindings`, default on; Q2). Before a tier (b) slot calls a model in `approve`, the
binder looks at the existing bindings file: when the file is readable, its `resolveHash` equals the plan's, the slot's
`argsHash` equals the one just computed and the recorded value passes every verifier now, the recorded value is
offered on the screen as the candidate ("recorded 2026-10-08, approved by …; verified now; no model call") and, if
approved, written again with the new approval; a model is called only for a slot that fails that check. Tier (a) slots
are not reused (recomputing them is free). `--no-reuse-bindings` resolves every slot afresh. The reuse is recorded in
the `resolve` run record (`reused: true`, `reusedFrom: <approval.by>`) and nowhere in the bindings file, which must
come out byte-identical when nothing changed (§15.2; P2 §9 keeps notes and dates out of the file). As built
(2026-10-08, E2): the reuse needs the same file, scenario and environment, an equal `resolveHash`, the same kind, an
equal `argsHash`, the credential still present and every verifier passing now; then the contributor is not even loaded;
`live --write-bindings` writes `REQUIRED` with `by: null`; the `replay` refusal's detail carries `bindingsFile`,
`slot`, `strategy` and `approval`, and the `resolve` record of a model value carries `confidence`, `approval` and `llm`.

### 7.2 Budgets: `BudgetMeter`, `PriceTable` (`…inference.budget`)

```java
/** Money spent through the ledger within one scope, against a limit. Thread-safe. */
public final class BudgetMeter {
    public enum Scope { SCENARIO, JOB, SESSION }
    public BudgetMeter(Scope scope, String id, BigDecimal limitUsd /* null: unlimited, logged */, CostLedger ledger);
    public BigDecimal spent();  public BigDecimal remaining();  public BigDecimal limit();
    /** Called before a model call with an estimate; throws when spent + estimate > limit. The estimate is the
     *  gateway's countTokens when it has TOKEN_COUNT, else text length / 4 plus maxTokens, at the price table. */
    public void reserve(BigDecimal estimateUsd) throws BudgetException;
    public void settle(Usage usage);                      // the real cost, from the ledger entry
}
public final class PriceTable {
    public record Price(String model, BigDecimal inputPerMTok, BigDecimal outputPerMTok, BigDecimal cacheReadPerMTok,
                        BigDecimal cacheWrite5mFactor, BigDecimal cacheWrite1hFactor, BigDecimal batchFactor) { }
    public static PriceTable builtIn();                   // this version's table, below
    public static PriceTable load(Path yaml) throws SettingsException;   // overrides and additions, strict YAML
    public BigDecimal cost(Usage usage);                 // 6 decimals, HALF_UP, from the usage's own cache-write split; an unknown model costs at the OPUS_CLASS price and is noted
}
```

The built-in table, from the published rates of ai-inference F10 (USD per million tokens; to be re-checked when built,
and replaced by the file when they move):

| Model | Input | Output | Cache read | Cache write (5 min / 1 h) | Batch |
|---|---|---|---|---|---|
| `claude-opus-5-5` | 4.00 | 20.00 | 0.20 | ×1.25 / ×2 | ×0.5 |
| `claude-sonnet-5-5` | 2.00 | 10.00 | 0.20 | ×1.25 / ×2 | ×0.5 |
| `claude-haiku-4-5` | 1.00 | 5.00 | 0.10 | ×1.25 / ×2 | ×0.5 |
| `claude-sonnet-5` (Sonnet 5.5's documented fallback target, BS13 F-7) | 2.00 | 10.00 | 0.20 | ×1.25 / ×2 | ×0.5 |
| `claude-opus-4-8` (an Opus-line fallback target) | 5.00 | 25.00 | 0.50 | ×1.25 / ×2 | ×0.5 |

Scopes and limits: **per scenario** (`budget.scenarioUsd`, default 0.50, B6), one meter per bind, created by the
contributor; **per job** (`budget.jobUsd`, no default: `re-resolve` refuses to start without it, B6); **per session**
(`budget.sessionUsd`, default 1.00, B15, "a starting value to be replaced by the measurements of BS12"). A limit of `0`
means "no model call at all" and is what `replay` would have if it consulted a meter; it does not, because the engine
never loads the contributor there (§5.5). Exhaustion: a resolver's `BudgetException` is `ResolutionException(BUDGET)`,
so the slot fails `RESOLUTION_FAILED` reason `BUDGET` with `{scope, id, limitUsd, spentUsd, estimateUsd}` in the
detail (Q13); in a session the turn ends with `budget: exhausted` and the service refuses further model calls (the
person may still act, §12.4); in the nightly job the remaining scenarios are reported `BUDGET` and no further batch is
submitted (§8.3). The `--json` of every command and the run summary print `model cost: $0.0231 (budget $0.50)`.

### 7.3 The ledger writer and `CostLedger.Entry`

`CostLedger.Entry` gains `String purpose`, `long cacheReadTokens`, `long cacheWriteTokens`, `boolean batch` and `String
note`; the Phase 2 nine-argument constructor stays (purpose `"resolve"`, zeros, `false`, `null`). `cost-ledger.jsonl`
writes the new fields after the old ones. `…inference.budget.LedgerWriter` is what every caller of a gateway uses:
`Usage record(ModelRequest, ModelResponse, String scenarioId, String slot, Strategy strategy)` builds the entry from
the response's usage, records it, settles the meter and appends the record of §7.4. A `RecordedGateway` call is
recorded with provider `recorded` and cost `0`, so that a `live`/`approve` run against recordings still shows its
calls (`model calls: 2`), while `replay` has none. The JUnit stream's trailing `skillroy: zero model calls` test and the
executor's ledger check are unchanged and keep meaning "zero gateway calls of any provider".

### 7.4 `inference.jsonl`

Per run (beside `cost-ledger.jsonl`), and per `re-resolve` job, `triage` and `session` output directory: one masked
record per gateway call, `{type: "inference", t, purpose, scenarioId?, slot?, provider, requestedModel, model,
requestId, fingerprint, stop, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, batch, costUsd, elapsedMs,
structured? (the masked structured answer), note?}`; with `logPrompts: true` also `system` and `messages` (masked
parts) and the response `parts`. A refusal's category is in `note`. The file is evidence of what the model was asked
and answered, bounded to what the mask allows; P2 §11.4's "never written" list gains "a prompt that was not masked, a
model key, a response to a request that named a secret".

---

## 8. Batch re-resolution (`re-resolve`)

### 8.1 What it is

The nightly job of B6: re-resolve every scenario of an environment that needs bindings, through the provider's batch
interface where a model is needed, verify every binding against the live target, write the bindings files, and leave a
report for a person to review as a pull request. It is a CLI command and a `BindMode`, never a JUnit mode and never
part of a `run`. `BindMode` gains `RE_RESOLVE` (label `re-resolve`); `BindMode.parse` accepts it; `run --mode
re-resolve` and `skillroy.mode=re-resolve` are refused with "re-resolve is a command: skillroy re-resolve" (exit 64; the
JUnit `environment` test).

### 8.2 `skillroy re-resolve`

`re-resolve <yaml|plan.json|dir>… --env <id> --bindings <dir> [--credentials <file>] --budget-job <usd> [--inference …]
[--out <dir>] [--approved-by <label>] [--dry-run] [--pack <jar>]… [--json]` plus the connection options of P1 §8.6.

1. Loads and compiles every scenario; those that need no bindings are listed and skipped. Connects, checks `requires`
   and launch properties, runs the pre-flight once (as `resolve` does, P2 §12.1). Refuses to start without a job
   budget or with gateway `none` (exit 64).
2. **Rounds.** Every scenario is bound in `RE_RESOLVE` mode, which behaves as `live` (P2 §8.3 steps 3-6) with one
   difference: a tier (b) call is not made inline. The resolver of §5.4 implements `BatchableResolver` (below); when a
   scenario's ladder reaches it, the job takes the prepared request, **parks** the scenario at that slot and goes on with
   the next scenario. When every scenario is parked or finished, the parked requests of the round go into **one batch**
   (`submitBatch`), the job waits (`batchStatus` every `timeouts.batchPollMs`, default 30 s, until `ENDED` or
   `timeouts.batchWaitMs`, default 2 h; a wait that times out cancels the batch and ends the job with the parked
   scenarios reported `BATCH_TIMEOUT`), collects, and resumes each parked scenario with its answer through `complete`.
   A scenario is parked again when a later slot needs a model; the rounds continue until no scenario is parked. The
   number of rounds is the longest chain of tier (b) slots in one scenario's graph (one or two in practice).
3. Every binding then goes through the verifiers, the oracles, the cross-checks and the guard exactly as in `live`
   (the engine code is the same; only the resolver's call is deferred), against the live target, in the round in which
   the scenario resumed. Tier (a) slots are resolved inline, as in `live`.
4. **Writing.** The bindings file is written as `approve` writes it (P2 §8.3 step 8) with `approval: {state: APPROVED,
   by: "re-resolve:<jobId>"}` (`--approved-by` replaces the label) for every slot the job resolved, and an identical file
   is left untouched. `--dry-run` writes nothing and reports what would change.
5. **The report**, `<out>/re-resolve-report.md` and `.json` (default `<out>` = `run-output/re-resolve-<jobId>/`): per
   scenario `UNCHANGED`, `CHANGED` (with the `BindingsDiff` against the previous file, one line per change), `NEW`,
   `FAILED` (the scenario's failure kind and reason as the binder reports them: `RESOLUTION_FAILED` with its reason,
   `BUDGET` included, `ORACLE_SUSPECT`, `REBIND_NEEDED`, `ENVIRONMENT`; a scenario whose batch did not end in time is
   `ENVIRONMENT` with the report's own reason `BATCH_TIMEOUT`, a reason of the job, not a `FailureKind`); the job's cost, the budget, the number of batches and requests, the cache-hit ratio
   (`cacheReadTokens / (inputTokens + cacheReadTokens)` over the job's ledger), the model ids; and the instruction for
   the reviewer ("approve by merging; a slot's statement and rationale are in the file's provenance"). The job's
   `cost-ledger.jsonl` and `inference.jsonl` are written beside it.
6. **Exit codes**: 0 when every scenario resolved (changed or not); 1 when any scenario failed (the report says which;
   files of the others are still written); 2, 3, 64, 70 as `resolve`.

A pull request is opened by the pipeline, not by the base: `ci/examples/nightly-re-resolve.yml` is a GitHub Actions
workflow that runs the command in the Linux image on a schedule, with the key from the repository's secrets, and
opens the pull request with the report as its body when `git status` shows changed bindings files. It is an example,
not run by this repository's own CI.

### 8.3 `BatchableResolver` (`skillroy-scenario`) and the budget

```java
/** A resolver whose model call can be deferred into a batch (re-resolve). The engine calls prepare where it would
 *  call candidates, parks, and later calls complete with the answer. Outside re-resolve, candidates() is used. */
public interface BatchableResolver<Q extends Query<T>, T> extends Resolver<Q, T> {
    Object prepare(Q query, ResolveContext ctx) throws ResolutionException, DataException, …;   // an opaque request the job submits
    List<ResolverCandidate<T>> complete(Q query, ResolveContext ctx, Object answer) throws …;   // the batch's answer for it
}
```

As built (2026-10-08, E2), the engine's API for the job is `Binder.bindStepwise(plan, pack, agent, options, listener)`,
which returns a `BindStep`: `Done(BindOutcome)` or `Parked` (`scenarioId()`, `slot()`, `kind()`, `resolverId()`, the
opaque `request()`, `resume(answer)` returning the next step, `close()` to abandon; time parked is not counted against
the bind budget; the engine closes its data channels while parked and reopens them on resumption; outside
`RE_RESOLVE` nothing parks and a `BatchableResolver` is asked through `candidates()`; the one-shot `Binder.bind`
refuses `RE_RESOLVE` as `ENVIRONMENT`, since it cannot return a parked slot). The file is written `APPROVED` by
`approvedBy` (default `re-resolve`) only with `writeBindings`; `resolver` records carry `parked` and `resumed`. The
job should share one gateway across the scenarios it parks (each engine holds its own contributor; the inference
module's contributor hands out one gateway per settings, reference-counted). The job's meter is the `JOB` scope: `reserve` is called for the whole round's batch before it is submitted (the sum of
the estimates, at the batch rate); when the round does not fit, the scenarios whose requests were not submitted are
reported `BUDGET` and the round goes on with the rest; a batch in flight is always awaited and collected (it is paid
for). The nightly cost thus never exceeds the job budget by more than one batch's estimate error. Caching: the
catalogue part carries `CacheHint.LONG` in this mode (one hour; the batch's requests share the prefix), which is where
the exit criterion's "cache hits above 80 %" comes from and what the report measures.

---

## 9. `skillroy-mcp`: the primitives and the pack as tools

### 9.1 Decision

One MCP server, `skillroy mcp`, over stdio, on the MCP Java SDK 2.0.1 (specification 2025-11-25; the agent never hosts
it, control-channel F18). It projects what a connected target offers: the pack's verbs and the catalogue's primitives as
tools, the controller's workflow commands as tools, and the run's state as resources. It is what the planner (§10)
talks to, what an MCP client such as Claude Code uses for interactive work, and one host of the session service (§12).
It is read-only by default, offers mutating tools by a flag, and never offers a destructive primitive.

### 9.2 Starting it

`skillroy mcp [connection options] [--pack <jar>]… [--env <id>] [--credentials <file>] [--bindings <dir>] [--mutating]
[--inference …] [--out <dir>] [--session-budget <usd>]`. It connects as `run` does (attach or discovery), reads the
schema and the capability matrix once, selects the pack, and serves on stdin/stdout until the client closes the
stream or the agent is lost (then it exits 3 after reporting the loss as a tool error to any call in flight). Its own
log goes to stderr, masked. The `claude` CLI's `--mcp-config` names it as `{"mcpServers": {"skillroy": {"command":
"skillroy", "args": ["mcp", …]}}}`; `skillroy author` writes that file itself (§10).

### 9.3 Tools

Names use `[a-z0-9_]` only (the Anthropic API forbids dots, control-channel F22), at most 64 characters: a primitive
`a.b.c` becomes `a_b_c`; a verb `signOn` of pack `lending` becomes `lending_sign_on`; a workflow tool is `skillroy_*`.
A collision is a start-up error. Every tool carries MCP annotations: `readOnlyHint` from the descriptor's `mutating`
(false → read-only), `destructiveHint` from `destructive`, `idempotentHint` false, `openWorldHint` false; its input
schema is the descriptor's `paramsSchema` (less `_meta`) or the verb's `argsSchema`; its description is the
descriptor's, prefixed with the thread and timeout. Results are text content: the call's `value` as pretty JSON,
masked, cut at 25000 characters with `[truncated: N more]` (control-channel F19); a result that holds an image (a
capture, a bitmap) is written to `<out>/mcp/<id>.png` and returned as a resource link.

| Group | Tools | Offered |
|---|---|---|
| Pack verbs | `<domain>_<verb>` for every verb of the pack's `StepLibrary` with its `argsSchema` (§9.5); the call compiles the verb and executes its calls through `ExecutorSession.execute(verb, args)` (§12.2), so the step protocol, the prompt policy and the journal apply | mutating verbs only with `--mutating` |
| Domain primitives | `<domain>_…` for the domain module's primitives (`lending_access_check`, `lending_deposit`, …) | by `mutating`/`destructive` as below |
| Base primitives | `jvm_*`, `app_*`, `swing_*`, `web_*`, `sync_*`, `data_query` | the reads always; mutating with `--mutating`; `data_query` only with the source's allow-list and through `SqlGuard` (the model's SQL is guarded even here) |
| Workflow | `skillroy_catalogue` (verbs with schemas, query kinds with their argument records, oracle functions, capability states); `skillroy_snapshot` (the `UiSnapshot` text, `maxChars`); `skillroy_facts {root, path?}`; `skillroy_events {sinceSeq?, topics?, max?}`; `skillroy_resolve {slot, kind, args, strategy?}`; `skillroy_scenario_compile {yaml}` (problems or the plan summary); `skillroy_scenario_run {yaml, mode: live\|approve}` (`--mutating`; runs with recover; the `RunResult` summary); `skillroy_session_ask {request}`, `skillroy_session_check {path, matcher}`, `skillroy_session_observe {path}`, `skillroy_session_note {text}`, `skillroy_session_export {scenarioId, approvedBy?}` (§12) | `skillroy_scenario_run` and `skillroy_session_ask` need `--mutating` unless the session policy is read-only |

Never offered: a destructive primitive (a person runs it with `skillroy call --confirm`); `agent.disable`,
`agent.capabilities {reprobe}`, module upload, hook installation; `data.query` on a source without an allow-list;
anything with `confirm: true` set by the server on a model's behalf (design §3.4: the model's request is never the
confirmation). In read-only mode (the default) a mutating tool is not listed at all, so a client cannot even ask.

### 9.4 Resources

`skillroy://env/profile` (pack id and version, environment id, the capability matrix, the target's Java and pid);
`skillroy://state/current` (the `app.state` fact, masked); `skillroy://events/recent` (the last 200 events, masked);
`skillroy://schema/primitives` (the descriptors); `skillroy://schema/scenario` (the scenario JSON schema of §10.2 for
this pack); `skillroy://run/<id>/run.jsonl`, `skillroy://run/<id>/journal.jsonl` and `skillroy://run/<id>/failure/<file>`
for runs and sessions the server started. Resources are listed with `tools/list`-style annotations (`readOnly`) and
served as text, or as `image/png` for a capture.

### 9.5 `StepLibrary.argsSchema` (`skillroy-scenario`)

```java
public interface StepLibrary {
    // … unchanged …
    /** The JSON Schema of a verb's arguments, for tools and for the scenario schema; null = free-form object (the
     *  verb is projected with {type: object} and the planner is told so). Keys that take references ($slot) are typed
     *  as the reference's value type with "or a $reference" in the description. */
    default Map<String,Object> argsSchema(String verb) { return null; }
}
```

The sample's pack declares a schema for every verb (§14.1). The compiler does not validate arguments against it
(the verb's `compile` stays the authority); the schema is for tools and for authoring.

### 9.6 Conformance

The server is tested in process with the SDK's client over piped streams (no subprocess): `initialize`, `tools/list`
(names, hints, schemas, the read-only default and `--mutating`), a read tool, a verb tool through the session entry
against the executor tests' fake agent, `resources/list` and `resources/read`, the 25000-character cut, the resource
link for an image, a mutating tool refused in read-only mode, and the server's reaction to a lost agent. End to end,
the Linux image runs the server as a subprocess against the sample desk (§15.2).

---

## 10. The planner: `skillroy author`

### 10.1 Decision

The planner is the Claude Code CLI run headless from Java (`claude -p`, B3; ai-inference F18, R9), with the MCP server
of §9 as its only tool source, the scenario JSON schema as its output constraint and the pack's verbs as its vocabulary.
The base runs it as a subprocess, never links an Agent SDK (there is none for Java, ai-inference F17), and never runs
it in CI. What it produces is a YAML file and a plan for a person to review; the person's commit is the acceptance.

### 10.2 `skillroy author`

`author --brief "<one paragraph>" | --brief-file <path> --id <scenario-id> --out <scenarios dir> [--pack <jar>]…
[--env <id>] [--credentials <file>] [--bindings <dir>] [--model opus|sonnet] [--max-turns <n> (40)] [--max-repairs
<n> (2)] [--budget-usd <usd> (2.00)] [--run] [--claude-command <path>] [--json]` plus the connection options.

1. Connects, pre-flights, selects the pack, as `resolve` does. Refuses an `--id` that exists in `--out`.
2. **The scenario schema** (`…scenario.ScenarioSchema.generate(DomainPack, List<PrimitiveDescriptor>)`): a JSON
   Schema (draft 2020-12 as `claude -p --json-schema` reads it) of the scenario document: the top-level keys of P1 §8.1
   with their shapes; `resolve` slots whose `query` is one of the pack's kinds with the kind's argument record
   (`Schemas.ofRecord(queryClass)`, references allowed as strings matching `^\$`), `strategy` tokens limited to those the
   pack can serve (`sql`, `java`, and `llm-sql` when an allow-list exists); `oracle` entries over the pack's functions;
   `steps` as a list of single-key objects, one key per verb (the base verbs with their argument shapes, the pack's verbs
   with `argsSchema` or a free-form object), each with the optional `expect`; `verify`, `cleanup`, `requires` (from the
   capability ids the target reports), `ui`, `web`, `timeouts`. The same generator feeds the MCP resource; a test
   validates every committed sample scenario against it with the small validator of §13.3.
3. **The MCP configuration**: a private, owner-only temporary file naming this CLI's own launcher with `mcp
   --mutating` (the planner may try steps; the target is a disposable test instance), the connection options, the pack
   jars, `--env` and the credentials file; deleted when the command ends.
4. **The prompt**: the planner instructions (explore with the read tools first; use the verbs, never the low-level
   primitives, in steps; name no person or item, resolve them with queries; expectations through facts and `db`
   checks, never through what the UI shows unless the brief asks for the UI; prefer committed strategies; return
   exactly one scenario document) with the brief inside a `<brief>` block, the catalogue summary and the verbs with
   their schemas. The command runs
   `claude -p --bare --output-format json --json-schema <schema> --mcp-config <file> --strict-mcp-config --tools ""
   --allowedTools "mcp__skillroy__*" --permission-mode dontAsk --model <id> --max-turns <n> --max-budget-usd <usd>
   "<prompt>"` (`--tools ""` leaves the CLI's built-in shell and file tools out; `--strict-mcp-config` admits no other
   server; `--max-budget-usd` is the CLI's own budget, BS13 F-10) with `ANTHROPIC_API_KEY` inherited when set (the CLI
   also accepts its own login; a key that needs a workspace id needs it here too, through `ANTHROPIC_CUSTOM_HEADERS`,
   to be verified when the live run is possible), the working directory a private temporary directory, stdin closed,
   stdout read in full under a deadline of `timeouts.plannerMs` (default 15 min), stderr to the command's log, masked.
5. **The result**: the JSON envelope's `structured_output` (the scenario; optional in the envelope), `total_cost_usd`,
   `session_id`, `num_turns`, `is_error`, `mcp_server_errors` (optional). Success is exit code 0 with `is_error: false`,
   never the envelope's `subtype` (it reads `success` on failures too, BS13 F-10); a non-zero exit, `is_error`, a
   missing `structured_output` or a non-empty `mcp_server_errors` ends the command (exit 1) with the message (a failed
   `--resume` prints plain text, not JSON, which is reported as such); the cost is recorded in the ledger as provider `claude-code`, model as reported, purpose
   `plan`. The document is rendered to YAML (sorted top-level keys in the order of P1 §8.1; the brief, the date, the
   model, the session id and the cost as a leading comment) and compiled with `PlanCompiler`; compile problems start a
   **repair round**: `claude -p --resume <session_id>` with the problems as the prompt, at most `--max-repairs` times.
6. **Checks**: the plan is bound with `resolve --dry-run` semantics (live mode, nothing written) so that the planner's
   queries are seen to resolve on this environment; with `--run` the scenario is run once in `live` mode (with recover)
   and the `RunResult` summary is printed. Neither writes bindings: the first `approve` run does, by a person.
7. **Written**: `<out>/<id>.yaml`, `<out>/plan/<id>.plan.json` (as `compile` writes it), `<out>/<id>.authoring.json`
   (`{brief, model, sessionId, turns, repairs, costUsd, checks: {compiled, resolved, ran?}, claudeVersion, date}`), and
   the instruction "review the YAML and the plan, then `skillroy resolve --env … <id>.yaml` to approve its bindings".
   Exit 0 when the file was written and compiled; 1 when the planner failed or the repairs ran out; 64 for usage.

`--claude-command <path>` (also `skillroy.claude.command`) names the executable; tests pass a script that prints a
canned envelope, so that steps 5-7 are tested without the CLI (§15.1). With the real CLI absent the command exits 64
("claude not found on PATH"). The planner's budget is passed as the CLI's `--max-budget-usd` (present in the 2.1
line, BS13 F-10) and `total_cost_usd` is still checked against it afterwards and reported.

### 10.3 What the planner is not

It is not a run-time component, not a healer and not an oracle writer: a scenario it authors is reviewed and committed
like one a person wrote, its oracles are the pack's committed functions, and its bindings are approved by a person.
Its cost is not a scenario's cost (it is not metered by the scenario budget) and is never incurred in CI.

---

## 11. Triage: `skillroy triage`

### 11.1 Decision

Triage is opt-in, after the fact, and never changes an outcome (design §5 (C), ai-inference R14): deterministic
classification happens in the run (`FailureKind`); triage reads the finished run's evidence and writes a label and a
summary beside it. It is a separate command over an output directory, not a step of `run`, so that `replay`'s zero-call
assertion stays absolute and a pipeline chooses where to spend.

### 11.2 `skillroy triage <run output dir>… [--inference …] [--labels-only] [--json]`

For each directory with a `run.jsonl` whose `run-end` record is `FAIL` or `ERROR` (a `PASS` or `DEGRADED` run prints
"nothing to triage" and is skipped): the request holds the scenario id and the failed step (verb, line, the masked
call and its result), the failure kind, reason and masked detail, the last 50 events (masked), the dialogs, the
snapshot text (bounded), the application state, the bind records of the failed slot if the failure is a bind failure,
and the `failure/` files' names; no image. Two calls: `HAIKU_CLASS` with the structured output `TriageLabel {label:
APPLICATION_DEFECT | SCENARIO_DEFECT | ENVIRONMENT | DATA_DRIFT | TIMING | UNKNOWN, confidence, oneLine}`; then, unless
`--labels-only`, `SONNET_CLASS` with `TriageSummary {summary (≤ 200 words), lookAt: [string], proposal?: string}`
(`proposal` is prose for a person; nothing is applied). Written: `failure/triage.json` (both answers, the models,
request ids, cost) and `failure/triage.md` (readable), plus the command's `inference.jsonl` and `cost-ledger.jsonl` in
`<run dir>/triage/`. The run's `run.jsonl`, `junit.xml` and status are untouched. Exit 0; 1 when the gateway failed for
a directory; 64 for usage (no directory, gateway `none`).

---

## 12. The interactive profile: `ExecutorSession`, `StepJournal`, export, the session service

### 12.1 Decision

B15's "one way in": everything an interactive session does goes through the executor's step protocol by a public
single-call entry, and is journaled; the journal exports an ordinary scenario. Phase 5 builds the two reserved types
of P2 §11.5 in `skillroy-scenario`, by extracting the executor's private `executeCall` into a package-private call
runner that both the plan run and the session use (D79): one implementation of substitution, `_meta.secretParams`, web
routing, sync, capture, the prompt check and failure classification. The session service in `skillroy-inference` is a
client of that entry, as the MCP server and the CLI are; the model is a client of the service.

### 12.2 `ExecutorSession` and `StepOutcome` (`ai.skillroy.testjava.scenario`)

```java
public final class ExecutorSession implements AutoCloseable {
    /** Opens a session: pack, agent, options; checks requires/launch properties and runs the pre-flight as a run
     *  does; opens the web path (P4 §6.1) with the given settings; computes the fingerprint; writes the journal's
     *  first record. No model is involved. */
    public static ExecutorSession open(DomainPack pack, AgentConnection agent, SessionOptions options)
            throws SessionOpenException, AgentLostException, InterruptedException;
    /** One call with the step protocol: substitution against the session's bindings, secret parameters, web routing,
     *  the sync of the call, the capture, the prompt policy, classification. Never throws for a failed call: the
     *  outcome says. Journaled. */
    public StepOutcome execute(PlannedCall call, Origin origin) throws AgentLostException, InterruptedException;
    /** A verb (base or pack) with its arguments, compiled as the compiler would compile a step (references allowed),
     *  then executed call by call; stops at the first call that is not PASS. Journaled as one step. */
    public StepOutcome execute(String verb, Object args, Origin origin) throws AgentLostException, InterruptedException;
    /** Live resolution of one query through a ResolutionEngine in LIVE mode (tier (a) and contributed resolvers);
     *  the result joins the session's bindings under the slot name. Journaled. */
    public Resolution resolve(String slot, String kind, Map<String,Object> args, List<String> strategy, List<String> verify,
                              CredentialSpec credential, Origin origin) throws BindException, AgentLostException, InterruptedException;
    public Object evaluate(String name, String fn, Object args, Origin origin) throws BindException;     // an oracle, joins bindings as $oracle.name
    /** An expectation evaluated now (P1 §8.4 matchers over facts and the snapshot); journaled with its outcome. */
    public CheckOutcome check(String path, Object matcher, Origin origin) throws AgentLostException, InterruptedException;
    /** The current value at a path, for an exported expectation marked "observed" (§12.3). Journaled. */
    public Object observe(String path, Origin origin) throws AgentLostException, InterruptedException;
    public String snapshotText(int maxChars) throws AgentLostException, InterruptedException;   // the UiSnapshot renderer's text
    public Object fact(String root, String path) throws AgentLostException, InterruptedException;
    public List<Event> eventsSince(long seq, String topics, int max);   // the agent's and the web path's events
    public void note(String text, Origin origin);                        // a journal note
    public Bindings bindings();  public StepJournal journal();  public SessionOptions options();  public String id();
    /** Closes the web path, the engine and the journal; with options.recoverOnClose and a mutating call journaled,
     *  calls the pack's recover first (bounded as a run's). */
    @Override public void close();
}
public record SessionOptions(Path outputDir, Duration stepTimeout, Clock clock, BindOptions bind /* LIVE */,
                             boolean readOnly, boolean recoverOnClose, WebSettings web, UiSettings ui,
                             ClassLoader webPathLoader, String id /* null: generated */) { public static SessionOptions defaults(Path outputDir); }
public record Origin(Kind kind, String by /* a person's label, a model id, "system" */, String intent /* nullable */,
                     String requestId /* nullable */, String note) { public enum Kind { PERSON, MODEL, SYSTEM } }
public record StepOutcome(String stepId, String verb /* nullable for a raw call */, List<PlannedCall> calls, int executed,
                          Status status, FailureKind failureKind, String message, Map<String,Object> detail /* masked */,
                          RpcResult lastResult /* masked */, List<Event> events, long seqBefore, long seqAfter,
                          List<String> degradedFidelity, Duration duration) { }
public record CheckOutcome(String path, Object matcher, Object actual /* masked */, boolean held, String reason, Duration duration) { }
```

Rules. A session has no plan: `requires` is what a verb's calls need (checked per call against the matrix, as the
dispatcher would refuse them anyway), the step timeout is the option's, and an unexpected prompt is handled by the
pack's `PromptPolicy` as in a run, except that `FAIL` ends the *step* (`FAIL` / `ASSERTION`, the dialog in the detail)
and not the session. As built (2026-10-08, E1; D79): the executor's private call runner became the package-private
`CallRunner`, used by the plan run and the session alike, and the plan run is unchanged; correlation ids are
`<sessionId>/<stepId>/<n>` (one verb step may make several calls, as `<scenarioId>/<stepId>/<n>` in a run); a `confirm`
argument is taken out of every call's parameters (plan steps, cleanup and sessions) and sent as `_meta.confirm`
(`CallRunner.confirmation`), which the executor did not do before, so that a scenario's `confirm: true` reaches the
agent (D89); `readOnly` also refuses a primitive the target's catalogue does not list (it cannot be shown to be
read-only: `ERROR` / `ENVIRONMENT` with `READ_ONLY` in the detail, journaled with `executed: 0`); a `MODEL` origin's
`confirm` is stripped from a verb's top-level and nested `args` too; a check is evaluated once, without waiting, and a
`db.<table>` check is refused with a reason; each `resolve` gets the default 60 s budget and a failed one throws and
is not journaled; a verb's cleanup calls are not run (`recoverOnClose` takes their place); the web path opens with
tracing off (a session keeps no failure evidence and any step may type a secret); `budget(scope, spent, limit,
exhausted)` writes the `budget` record and `primitives()` answers the catalogue. `readOnly` refuses any mutating or destructive call before it is sent (`READ_ONLY` in the outcome,
nothing journaled as executed). A destructive call needs `confirm: true` in its arguments, which only an `Origin` of kind
`PERSON` may carry: the session strips `confirm` from a `MODEL` origin's call and the call then fails with
`CONFIRM_REQUIRED` as the agent answers it (design §3.4). Every outcome's `detail` and `lastResult` pass the session's
`SecretMask` (credentials, fixture secrets joined into the bindings). A session's calls carry a `correlationId`
`<sessionId>/<stepId>`.

### 12.3 `StepJournal` and the export

**The journal file**, `<outputDir>/journal.jsonl`, is written as the session runs (append-only, masked), one record per
entry, in order: `session-start {id, pack, packVersion, envId, env: fingerprint and components, readOnly, options}`,
`step {stepId, origin, verb?, args? (as given, references unsubstituted), calls [{primitive, args (substituted,
masked), sync, timeoutMs, await?, capture?}], outcome: {status, kind?, message?, detail?, executed, seqBefore,
seqAfter, durationMs, degradedFidelity}, snapshotHash? (sha256 of the snapshot text taken after a PASS, when the
origin asked for one)}`, `resolve {slot, kind, args, strategy, resolution (the bindings-file shape of the slot, P2 §9,
with provenance, verifier results, confidence, approval, usage)}`, `oracle {name, fn, inputsHash, value}`, `check
{path, matcher, actual, held, reason}`, `observe {path, value}`, `note {origin, text}`, `budget {scope, spentUsd,
limitUsd, exhausted}`, `session-end {reason, steps, cost}`. Model provenance lives in `origin` (`kind: MODEL, by: the
model id, requestId, intent`); the cost of a model call is in the session's `cost-ledger.jsonl` and `inference.jsonl`,
not in the journal.

**`StepJournal` (the in-memory side)** is read back from the file or kept by the session: `List<Entry> entries()`,
`ExportResult export(ExportOptions)`.

**The export** (`export(ExportOptions{scenarioId, includeFailed (false), approvedBy (null), description})`) produces
`ExportResult {String yaml, BindingsFile bindings, Report report}`:

1. **Steps.** Every `step` with status `PASS` (and, with `includeFailed`, every step up to and including the first
   failure) becomes one scenario step, in journal order: a step journaled with a `verb` exports as that verb with its
   `args` as given (references intact, so that `$assistant.code` stays a reference); a step journaled as raw calls
   exports as the base verb `call` (§13.1) per call, with the call's substituted arguments **except** where a value
   equals a bound slot's field, which is written back as the reference (so that an exported scenario never carries a
   bound value as a literal, and never a secret: a value that equals a joined credential field is written as its
   reference, and an argument the journal masked is refused with the step named). A `sync` and an `await` go with the
   call; the step's `expect` is empty unless the next entries say otherwise.
2. **Expectations.** A `check` that held after a step exports as that step's `expect` entry with its matcher. An
   `observe` exports as `expect: {<path>: <value>}` with a YAML comment `# observed in session <id> at step <n>, not
   derived` on the line, and the report lists every such line under "Observed expectations: derive or confirm"; this is
   what Phase 5 does for gap 26's calibrated-oracle case (§16, D86): the reviewer, not the run, decides whether an
   observed value is a specification. A `check` that did not hold is not exported (listed in the report).
3. **Resolution.** Every `resolve` entry exports as a `resolve:` slot with its kind, args and strategy tokens, in the
   order resolved; every `oracle` entry as an `oracle:` entry. The bindings file is built from the journal's resolutions
   exactly as `approve` would write them: a tier (a) slot `NOT_REQUIRED`; a tier (b) slot `REQUIRED`, or `APPROVED`
   with `approvedBy` when given (the person exporting is the approver, Q12).
4. **Head.** `scenario: <id>`, `pack`, `description` (the option, else "exported from session <id> on <date>"),
   `requires` (the capability ids the exported calls' primitives name, deduplicated), `ui` and `web` from the session's
   options, `timeouts` from the step timeout; a leading comment block with the session id, the date, the origins that
   acted (`person: 12 steps, model claude-sonnet-5-5: 7 steps`) and the model cost.
5. **The check.** The YAML is compiled with `PlanCompiler` against the pack, and `Binder.check` is run with the
   exported bindings; a problem is an `ExportException` with the step and the reason (the export never writes a scenario
   it cannot compile). The plan's `inference` is `bind` if a tier (b) slot was exported, else `none`; the compiled plan
   contains no model call and, in `replay` with the exported bindings file, makes none.

`skillroy session export` (§13.4) and `skillroy_session_export` (§9.3) write the three files (`<id>.yaml`,
`plan/<id>.plan.json`, `bindings/<env>/<id>.bindings.json`) and print the report.

As built (2026-10-08, E1). The journal's `session-start` also carries `startedAt` and `primitives` (primitive to
capability id, which an export from a file needs for `requires`), a `resolve` entry `verify` and `credential`, an
`oracle` entry `args`, and a step's `outcome.detail.captured` (so that later values are written back as references); the
`description` goes into the leading comment block, not a key (the strict loader has no `description` key), with the
session, pack, environment, "Acted: …" and "Model cost: …" lines; `DEGRADED` counts as passed; without `includeFailed`
every failed step is left out and the passing steps after it are kept, with it the first failed step is included and
nothing after it; a raw call's own `timeoutMs` is dropped with a report note and `await.timeoutMs` is written as
`within: "<n>ms"`, while a capture without an `await` is an `ExportException`; an `observe` whose value was masked is
skipped with a report note (a masked step argument still fails the export); a `REQUIRED` approval carries `by: "live"`
(the binder's label for what `live` writes) and a tier (a) slot `NOT_REQUIRED` with `by` the exporter's label or
`live`; the export reads a journal back from its file to the same result. `ScenarioSchema` validates every committed
sample scenario (`ScenarioSchemaCommittedTest` in both samples) and reports problems with paths such as
`$.resolve.supervisor.strategy`; `requires` items are strings with the capability ids in a description (the grammar
allows alternatives); `Schemas` lives in `ai.skillroy.testjava.scenario` (not the inference SPI package of §3.3: the
scenario module needed it first; the inference module reuses it) with `ofRecord` (strict) and `ofArguments` (lenient,
`$` references allowed, primitive components only); the plan reader also checks that a version 5 plan's `inference`
equals what its slots imply.

### 12.4 The session service (`…inference.session`)

```java
public final class SessionService implements AutoCloseable {
    public SessionService(ExecutorSession session, InferenceGateway gateway, SessionPolicy policy, BudgetMeter meter,
                          LedgerWriter ledger, PriceTable prices, Clock clock);
    /** One turn: the person's request, the model's actions through the session, the model's final answer. Bounded by
     *  policy.maxActionsPerTurn, the step timeouts and the budget. */
    public Turn ask(String request) throws AgentLostException, InterruptedException;
    public List<Turn> turns();
    @Override public void close();                      // closes nothing of the session; the caller owns it
}
public record SessionPolicy(boolean readOnly, boolean allowMutatingVerbs, boolean allowBasePrimitives,
                            int maxActionsPerTurn /* 10 */, int maxHistoryTurns /* 20 */, int snapshotMaxChars /* 20000 */) {
    public static SessionPolicy readOnly();  public static SessionPolicy acting();
}
public record Turn(int index, String request, List<Action> actions, String answer, Outcome outcome /* DONE, QUESTION,
                   LIMIT, BUDGET, LOST */, Usage totalUsage, Duration duration) {
    public record Action(String tool, Map<String,Object> input /* masked */, StepOutcome step /* null for a read */, String result /* masked, bounded */) { }
}
```

The loop of one `ask` (design §5, "Session service"):

1. The request is a `USER` message with the person's text in a `<person>` block; the system part (cached, `SHORT`)
   holds the service's instructions (act through the tools; one action at a time; observe before acting; never guess a
   person's or an item's identity, resolve it; stop and ask when unsure; say `done` with a one-paragraph answer), the
   pack's verbs with their schemas, the policy's limits, and the tool list. The tools offered are, by policy: the reads
   (`skillroy_snapshot`, `skillroy_facts`, `skillroy_events`, `skillroy_catalogue`), `skillroy_resolve`,
   `skillroy_check`, `skillroy_observe`, `skillroy_note`; with `allowMutatingVerbs` the pack's verbs; with
   `allowBasePrimitives` the base `swing_*` and `web_*` mutating primitives (off by default: the verbs are the
   vocabulary a scenario can export); and `done {answer}` and `ask_person {question}`. Never a destructive primitive,
   never `data_query`, never `evaluate` (design §5: nothing the model produces reaches `data.query`, hooks or uploads).
   The tool definitions are the same `ToolSpec`s the MCP server projects (§9.3), built by one projection class
   (`…inference.tools.ToolProjection`), so that a scenario authored over MCP and a session's actions share one
   vocabulary.
2. The model answers with `TOOL_USE` parts; the service runs them **one at a time in order** through the session
   (a read: the result text, bounded by `snapshotMaxChars`; a verb or primitive: `ExecutorSession.execute(verb, args,
   Origin(MODEL, model, intent = the model's text before the call, requestId))`; a `resolve` likewise), appends each
   `ToolResult` (the `StepOutcome` as masked JSON: status, message, detail, the events' topics, the new snapshot hash)
   and calls the model again with the history; a failed step is a result, not an exception, so the model may react
   (answer a prompt through a verb, or stop). After `maxActionsPerTurn` actions the turn ends `LIMIT` with what was
   done; `done` ends it `DONE`, `ask_person` ends it `QUESTION`; a `BudgetException` ends it `BUDGET` and the service
   makes no further model call in this session (the person may still `do` and `export`); an agent loss ends it `LOST`.
3. Every action is in the journal with its model origin; the turn's requests are in the ledger and `inference.jsonl`;
   the history is kept at most `maxHistoryTurns` turns back (older ones summarised by one `SUMMARY`-purpose call at
   `HAIKU_CLASS`, recorded like any call), so that a long session's prompt stays bounded.

**Timing.** The model's thinking happens between actions; each action is one bounded step with the pack's
`PromptPolicy` and sync waits, so the target is never held mid-step by a model call (design §3.4). The service does
not sleep: it waits on the gateway's call and on the session's step.

**Hosts.** (1) `skillroy session` (§13.4): a REPL where the person types `ask …`, `do <verb> {…}`, `call <primitive>
{…}`, `resolve …`, `check …`, `observe …`, `snapshot`, `note …`, `export <id>`, `quit`; (2) the MCP server's
`skillroy_session_*` tools, where the MCP client is the person's side and `skillroy_session_ask` lets a *second* model
act (the client's own model is then the person's assistant and the service's model the actor; both are journaled by
origin); (3) **the overlay bridge** (`OverlayBridge`): subscribes to the topic `assist.requested` through an
`EventSubscription` seam (`AutoCloseable subscribe(long sinceSeq, String topics, Consumer<Event>)`, which the CLI binds
to `AgentClient.events`), takes `{requestId, text}` from each event (masked, `<person>`), runs `ask(text)` and answers
through the primitive the pack names in `DomainPack.assistAnswerPrimitive()` (a new default method returning null = no
overlay) with `{requestId, answer}`. The sample has no overlay; the bridge is tested against the executor tests' fake
agent with scripted events (§15.1). An overlay's agent module never sees a model SDK (B15).

---

## 13. Scenario YAML, `plan.json` version 5, CLI and JUnit

### 13.1 Scenario YAML

- **`strategy` tokens.** `llm-sql` is accepted in a slot's `strategy` list (P2 §3.1) when the running pack declares
  an `SqlAllowList` for at least one source; the loader's and the plan reader's rejection ("needs the inference
  module") is replaced by a compile-time check against the pack ("pack lending declares no SQL allow-list: llm-sql
  cannot be served"). `llm-reason` and `llm-vision` stay rejected with "not in this version". A slot that lists
  `llm-sql` alone is allowed (the model is the only resolver asked).
- **The base verb `call`** exists since Phase 1 (P1 §8.3, `BaseSteps`: `call: {primitive, args?, sync? (default NONE),
  timeoutMs?, await? (with its capture inside)}` compiles to one `PlannedCall` verbatim; corrected 2026-10-08: the
  first draft of this contract wrongly introduced it as new). Phase 5 amends nothing of its shape; it confirms that a
  destructive primitive's `confirm: true` in `args` reaches the call (the executor honours `CallOptions.confirm`) and
  that a pack may not name a verb `call` (reserved with the other base verb names). The journal export uses it as it
  is for a step that no verb covers (§12.3), so that an export can always be written; it is not the preferred way to
  write a scenario, and `skillroy author` is told not to use it.
- **`inference`** is not a YAML key: the compiler writes the plan field (§13.2).
- Nothing else changes: the grammar of P1 §8.1 with the additions of P2-P4 stands.

### 13.2 `plan.json` version 5

`Plan.VERSION` becomes 5; the reader accepts 1-5 (P2 D18, P3 D41, P4 D58: one writer, older executors refuse newer
plans). The field `inference` is written `bind` when any slot's strategy list contains a token whose strategy
`needsModel()` and `none` otherwise; the reader accepts `none` and `bind` and refuses `run` with "not in this
version": no writer of Phase 5 produces `run`, and Phase 7 replaces that refusal by B16's rule (a `run` plan is
reported as skipped in `replay`). `resolveHash` is unchanged in what it covers (slots with their strategy tokens, oracles, graph), so a
plan that gains `llm-sql` on a slot changes its hash, as a strategy change should. Every committed plan of the sample is regenerated as version 5 and, because
`planHash` enters every bindings file, the sample's bindings files are regenerated once (as P3 D45 and P4 did), with
no value changing (Q10). As built (2026-10-08, E1): `Plan.inferenceOf(slots)` writes the field; `run` is refused with
"inference 'run' (a model at run time) is not in this version", `bind` below version 5 and unknown values are refused;
`llm-sql` below version 5 is "needs a version 5 plan"; the seven committed plans changed `planVersion` only and the four
bindings files `planHash` only (regenerated through `PlansUpToDateTest` and `PackOnRealSchemaTest` with their update
properties, as Phase 4 did).

### 13.3 The scenario JSON schema and its validator (`skillroy-scenario`)

`ScenarioSchema.generate(DomainPack, List<PrimitiveDescriptor>)` (§10.2) is the single description of what a scenario
may contain, derived from the grammar as the loader implements it; `ScenarioSchema.validate(schema, document)` is a
small validator for the subset the generator emits (`object`/`properties`/`required`/`additionalProperties`,
`array`/`items`/`minItems`, `string`/`pattern`/`enum`/`const`, `number`/`integer`/`boolean`/`null`, `oneOf`/`anyOf`,
local `$ref`/`$defs`), returning problems with JSON paths; it exists so that the planner's answer is checked before
the loader sees it and so that a test keeps the generator honest against the committed scenarios. It is not a general
JSON Schema library and adds no dependency.

### 13.4 CLI

New options of `run` and `resolve` (P2 §12.1 stands): `--inference claude|recorded|none`, `--recordings <dir>`,
`--inference-file <path>`, `--budget-scenario <usd>`, `--log-prompts`; `run --mode re-resolve` is refused (§8.1).
`run` and `resolve` print `model cost: $x (budget $y)` after `model calls: N`, and `--json` carries `modelCost`,
`budget` and the `inference` records' summary. The approval screen is §7.1's.

New commands (each with `--help`, `--json`, the connection options where it connects, and the exit codes of P2 §12.1
unless stated):

| Command | Does |
|---|---|
| `re-resolve …` | §8.2 |
| `mcp …` | §9.2; exit 0 when the client closed the stream, 3 when the agent was lost |
| `author …` | §10.2 |
| `triage …` | §11.2 |
| `session [connection] [--pack]… [--env] [--credentials] [--out <dir>] [--read-only] [--allow-base-primitives] [--inference …] [--budget-session <usd>] [--max-actions <n>] [--id <sessionId>]` | opens an `ExecutorSession` and, unless `--inference none`, a `SessionService`; a REPL on the terminal (§12.4; scripted in tests through the `Terminal` seam): `ask <text>`, `do <verb> <json>`, `call <primitive> <json>`, `resolve <slot> <kind> <json> [--strategy a,b]`, `check <path> <matcher-json>`, `observe <path>`, `snapshot [max]`, `facts <root> [path]`, `events [since]`, `note <text>`, `export <scenarioId> [--approved-by <label>] [--out <dir>]`, `quit`. Without a terminal, `--script <file>` runs the same commands from a file and exits; exit 0, or 1 when the agent was lost or the script's last `export` failed |

`Main`'s command list gains the five; `Cli` is unchanged. Everything printed passes the mask and the S26
control-character rule.

### 13.5 JUnit

`ScenarioTests` is unchanged in its contract: `replay` streams need no gateway; `approve` and `live` streams with
`llm-sql` slots work when `skillroy-inference` is on the class path and `skillroy.inference` names a gateway (the
properties of §4.4); `skillroy.mode=re-resolve` is the single failing `environment` test with the message of §8.1. The
trailing `skillroy: zero model calls` test is unchanged.

---

## 14. The sample

### 14.1 Pack (`skillroy-sample-lending-pack`, version 0.4.0)

- **`sqlAllowLists()`**: one list for source `lending`, dialect `HSQLDB`: the tables `staff` (`code` key; `name`
  personal; `active`; `password` **not** allow-listed: it is the secret column, masked by `PASSWORD_MASK`),
  `staff_role`, `role_permission` (`permission` sampled), `role_cap`, `desk_setting` (`key` sampled), `item` (`code`
  key; `per_day` sampled), `waiver_rule` (`kind` sampled), `waiver_rule_item`, and the four stored tables of
  `LendingTables.ALL` with their keys; the exact column lists are the schema's as the fingerprinter reads it, and a test
  proves every allow-listed column exists (§5.2). No procedure, no extra function.
- **`argsSchema`** for every verb of the pack's `StepLibrary` (§9.5), with references typed as described there; a test
  compiles every committed scenario's steps and checks each step's arguments against its verb's schema with the
  validator of §13.3.
- Nothing else changes in the pack: the queries, resolvers, verifiers, oracles, cross-check and fingerprinter stand.
  The version becomes 0.4.0 (the allow-list is new pack surface).

### 14.2 Scenario D: `supervisor-return-llm-sql` (tier (b) end to end, no key)

Scenario A's YAML (`return-over-limit-needs-supervisor`, P2 §13.4) with one change: the supervisor slot's strategy is
`[llm-sql]`, so that the pack's committed `lending.sql.StaffWithPermission` is not asked and the model's SQL finds the
supervisor; the assistant and the item keep their committed strategies (two tiers in one scenario, one bindings file).
Its recordings (§4.2) are made once, live, by a maintainer with the key, for both seeds, and committed; the bindings
files for `seed-a` and `seed-b` are written by `approve` against the recorded gateway with `--approved-by
"recorded 2026-10-08"` and committed. In the acceptance (§15.2) `replay` runs it on both seeds in every load mode with
zero model calls, and `approve --yes` against the recordings reproduces the committed bindings files byte for byte.
The verifier `lending.access.check` is the gate on the model's answer as on the committed one; `ExpectedAuthorisation`
and the rest of scenario A are unchanged, so the scenario still tests what scenario A tests.

### 14.3 Scenario C: `return-refunds-deposit` (authored by the planner)

The brief, in full: *"A borrower returns the only item of a loan on the same day it was lent. The desk takes the item
back, hands the deposit back in cash, and prints a slip for the return. Choose an item that needs no induction and an
assistant who may lend and take returns within the item's deposit. Check the stored loan line is marked returned,
the loan's total is back to zero, and the slip shows the deposit handed back."* It is authored once, live, with
`skillroy author --id return-refunds-deposit --run` against the sample desk on seed A (the planner's envelope is kept
as the fixture `author/return-refunds-deposit.envelope.json`, so that the post-processing is replayed in the build with
the fake `claude` script, §15.1); the YAML and the plan are **reviewed and edited by a person** as the exit criterion
says, committed with their `.authoring.json`, bound with `approve` on both seeds (tier (a) only: the planner is told to
prefer committed strategies and the brief needs none other) and replayed in the acceptance. If the desk's behaviour
makes a part of the brief unachievable (a return slip that does not show the deposit, say), the reviewer narrows the
scenario and the `.authoring.json` records what was changed and why; the brief stays as the test of the planner.

### 14.4 An interactive session on the desk, exported and replayed

A scripted session (`skillroy session --script`, the gateway `recorded`) in which the person signs on through a verb,
asks the model to lend one plain item to the desk's walk-in borrower and settle the deposit, checks the loan through a
fact, and exports the journal as `session-basic-loan`; the export's YAML, plan and bindings file are compared with the
committed copies (byte-identical: the recorded model answers make the session deterministic), compiled, and replayed
with zero model calls on seed A in the acceptance. The same journal, read back from its file, exports the same scenario
(the export is a function of the journal).

### 14.5 Recordings and the two profiles in the sample

`src/test/resources/inference/` holds the recordings of §14.2 and §14.4, the triage of the two negative runs of
`ResolutionChecks` that end `FAIL` and `ERROR`, and the planner envelope of §14.3; `-Pinference-record` regenerates
them in the Linux image with the key passed through the environment (`ci/run-in-linux.sh` passes `ANTHROPIC_API_KEY`
into the container when it is set, without printing it); `-Pinference-live` runs §15.3.

---

## 15. Acceptance

### 15.1 Without a display (unit and module tests; the normal build; no key, no `claude` CLI)

1. **The SPI**: `Schemas.ofRecord` on the records of this contract and the sample's queries (compliant schemas; the
   refused shapes refused); `ModelRequest.fingerprint()` stable across part order of maps, independent of `maxTokens`,
   `cache` and `metadata`, sensitive to a one-character change of any text; `Structured.decode` through the codec.
2. **`ClaudeGateway`** against a loopback fake of the provider's HTTP API (the JDK `HttpServer` in the test, canned
   JSON): the request body for each mapping row of §4.1 (system parts with the cache marker and its TTL, strict tools,
   `tool_choice: auto`, `output_config` for effort and format, no `thinking` field, the fallback field when enabled,
   image refused when disabled), the response mapping (parts, each stop reason, usage with cache tokens, the cost from
   the table), each error status to its `Kind`, the deadline, the batch endpoints (create, retrieve, results keyed by
   `custom_id` out of order), `count_tokens`; the key read from the environment only (a test with a dummy key in a
   child JVM's environment). No test reaches the network.
3. **`RecordedGateway`/`RecordingGateway`/`ScriptedGateway`**: round trip, `NO_RECORDING`, the mask on the way to
   disk, overwrite rules, batch replay.
4. **`PromptMask`**: §6.2's rules on the project's adversarial secret fixtures and the sample's personal column; a
   prompt built for every purpose contains no credential value and no personal value.
5. **`SqlGuard`**: the BS6 corpora (accept and reject per dialect, byte-identical rewrites, the listed unsupported
   constructs), every reason code of §5.3 with a minimal statement each, the injected `ORDER BY` and `LIMIT`, alias
   and CTE resolution, the `personal` rule; the sample's allow-list against the real schema (`PackOnRealSchemaTest`:
   every allow-listed column exists; every accept-corpus statement over the sample's tables also executes on the
   embedded database).
6. **`LlmSqlResolver`** with a scripted gateway against the sample's pack on the embedded database (the `JdbcDataChannel`
   of the scenario tests): a good proposal yields candidates in statement order that the engine verifies; `cannotAnswer`,
   low confidence, a guard violation, a param that matches no argument, a row count outside the bounds, a shape
   mismatch, a refusal, `NO_RECORDING`, a retryable error retried once; the ledger entry, `Resolution.usage` and
   `confidence` filled, the provenance's statement and notes; the budget exhausted before and after a call.
7. **The engine and binder**: the contributor found and ordered after the pack's resolvers; `replay` loading no
   contributor whatever the settings; `REBIND_NEEDED` / `NOT_APPROVED` for an unapproved tier (b) slot; `live
   --write-bindings` writing `REQUIRED`; `approve` writing `APPROVED` and the reuse rule (reused when identical and
   verified, re-resolved when the value fails a verifier or the args changed, `--no-reuse-bindings`); the approval
   screen's tier (b) lines (masked); `BindMode.RE_RESOLVE` parsed, refused by `run` and the JUnit glue; the bindings file
   with `REQUIRED` read and written; `plan.json` version 5 (`inference: bind`, `call` steps, the reader's 1-5 and its
   refusal of `run`); every committed sample plan byte-identical after regeneration; the `call` verb compiled, a pack
   verb named `call` refused.
8. **Budgets and the ledger**: `BudgetMeter` arithmetic (reserve, settle, exhaustion, unlimited), `PriceTable`
   (built-in values, a file override, an unknown model), the extended `Entry` with the old constructor, `inference.jsonl`
   with and without `logPrompts`, the cache-hit ratio.
9. **`re-resolve`** with a scripted batch gateway over three scenarios of two graph depths: two rounds, one batch per
   round, scenarios parked and resumed in order, the verifiers run on resumption, files written `APPROVED by
   re-resolve:<jobId>`, the report's statuses and diff, the dry run, the job budget cutting a round, a batch that times
   out (`BATCH_TIMEOUT`, the cancel sent), `--mode re-resolve` refused by `run`.
10. **`skillroy-mcp`** in process (§9.6).
11. **The planner's post-processing** with a fake `claude` script (an executable the test writes, printing the fixture
    envelope; the arguments it received are asserted: `-p`, `--bare`, `--output-format json`, `--json-schema`,
    `--mcp-config` naming a readable owner-only file that names this CLI's `mcp --mutating`, `--allowedTools`,
    `--permission-mode dontAsk`, the model id): the YAML written with its comment head, the plan compiled, the
    `.authoring.json`, a compile problem starting a repair round with `--resume`, the repair limit, `is_error`,
    `mcp_server_errors`, a missing CLI (exit 64), the ledger entry with the envelope's cost; `ScenarioSchema` validates
    every committed sample scenario and refuses a malformed one with a JSON path.
12. **Triage** with a scripted gateway over a copied failed run directory: the request's content (masked, no image,
    bounded), `triage.json`/`.md`, a `PASS` run skipped, `--labels-only`, the run's own files untouched.
13. **`ExecutorSession`, `StepJournal`, the export** against the executor tests' fake agent and the sample pack: a verb
    step, a raw call, a read-only session refusing a mutating call, a `MODEL` origin's `confirm` stripped, the prompt
    policy ending a step and not the session, `resolve` joining the bindings, `check` and `observe`, the journal file's
    records (masked), the export rules of §12.3 one by one (verb steps, `call` steps with references written back, a
    masked argument refused, observed expectations marked and listed, failed steps excluded and included, `REQUIRED`
    and `APPROVED` tier (b) slots, the head, the compile check), the export of a journal read back from its file equal
    to the live export, and `recoverOnClose`.
14. **The session service** with a scripted gateway: the tool set by policy, one action at a time, a failed step
    returned as a result, `done`, `ask_person`, the action limit, the budget ending the turn and the session's model
    calls, the history summary at the limit, the agent lost; the overlay bridge with scripted `assist.requested`
    events answering through the pack's primitive.
15. **The CLI**: `session --script` end to end on the fake agent (then `export` and a `replay` of the export), the new
    commands' usage errors, `--inference` with no key (exit 64, no value printed), the `model cost` lines.
16. **Settings**: `inference.yaml` read strictly, properties and options overriding it, `replay` forcing `none`, the
    key never in any output (a scan of every file the tests wrote for a dummy key value).

### 15.2 End to end (the Linux image; no key)

Extends P2 §14.2, P3 §11.2 and P4 §12.2 inside `LendingEndToEndIT` and a new `InferenceEndToEndIT`, on a Java 8 and
a Java 25 target, in every load mode, with the `recorded` gateway and the committed recordings:

1. Scenario D replays green on seeds A and B with the committed bindings, `model calls: 0`, the ledger empty; the JUnit
   stream's trailing test passes.
2. `approve --yes --approved-by "recorded 2026-10-08" --inference recorded --no-reuse-bindings` of scenario D on both
   seeds writes bindings files byte-identical to the committed ones (`model calls: 1`, provider `recorded`); the same
   command with the reuse rule on (the default, §7.1) writes the identical file with `model calls: 0`; a deliberately
   changed prompt (a test flag that appends a word to the catalogue) ends `ENVIRONMENT` with "no recording"; a
   scripted gateway's reject-corpus statement ends `RESOLUTION_FAILED` with the guard's reason.
3. Scenario C replays green on both seeds; its plan and the committed one are identical; its `.authoring.json` is
   present.
4. The session script of §14.4 runs against the desk, exports, and the export replays with zero model calls.
5. `skillroy mcp` as a subprocess against the desk: `tools/list` shows the lending verbs, the reads and no mutating
   tool; with `--mutating` the verbs; a snapshot and a fact read; a verb through the session entry; a resource read.
6. `skillroy triage` over the failed run directories of `ResolutionChecks` with the recorded gateway writes
   `triage.json`; the run's files are unchanged.
7. `re-resolve --dry-run` of scenarios A, B and D on seed A with the recorded gateway reports `UNCHANGED` for all three
   and a cache-hit ratio line (from the recorded usage).
8. The A34 and secret scans of the acceptance cover the new files (`inference.jsonl`, `journal.jsonl`, the exports,
   `triage.*`, the report): no credential value anywhere.

### 15.3 Live (profile `inference-live`; needs `ANTHROPIC_API_KEY`; skipped without it; never in CI)

1. One structured completion on `SONNET_CLASS` with a 600-token cached system part made twice within a minute: the
   second's `cacheReadTokens > 0`; the cost computed; `countTokens` within 10 % of the reported input tokens.
2. One batch of two requests submitted, polled and collected within the test's 20-minute bound, results matched by id.
3. Scenario D resolved live on seed A (the desk launched by the test as the scenario tests launch it): the model's
   statement passes the guard and the verifier, and the value equals the committed binding's.
4. `re-resolve` of scenarios A, B and D on seed A with a job budget of $1.00: under budget, cache hits above 80 %
   (the exit criterion); the figures recorded in `../TODO.md`.
5. With the `claude` CLI on the `PATH`: `skillroy author` on the brief of §14.3 against the desk produces a scenario
   that compiles and resolves (the authored content is not compared with the committed one: a person reviewed that).
6. One `session` turn live on the desk (`ask "lend one plain item to the walk-in borrower"`) ends `DONE` within the
   action limit and exports a scenario that compiles.

Exit criteria of the roadmap (design §8): (1) is §14.3 with §15.2 item 3; (2) is §15.3 item 4; (3) is §14.4 with §15.2
item 4; all three are also exercised without a key through the recordings.

---

## 16. Decisions taken in this contract

| # | Decision | Alternatives rejected | Reason |
|---|---|---|---|
| D70 | `skillroy-inference` reaches a bind through the `ServiceLoader` SPI `ResolverContributor` of `skillroy-scenario`, with string-only `InferenceSettings`; the engine never loads a contributor in `replay` | packs depending on `skillroy-inference` and registering the resolver themselves; `skillroy-scenario` depending on `skillroy-inference` | a pack must stay buildable and testable without a model SDK; P2 D1 forbids the reverse dependency; the `WebPath` precedent (D49) works |
| D71 | The recorded gateway is keyed by a fingerprint of the request's content and the recordings are committed, masked, byte-stable files; a missing recording fails loudly | mocks per test; a cassette keyed by call order; a tolerant match | a prompt change must be visible as a build failure and reviewable as a file diff; order-keyed cassettes break on any refactor; a tolerant match would hide a changed prompt |
| D72 | `replay` forces the gateway to `none`, loads no contributor, and refuses a tier (b) binding that is not `APPROVED` (`REBIND_NEEDED` / `NOT_APPROVED`); `Approval.State.REQUIRED` marks what `live` wrote | trusting the settings; a tier (b) binding accepted on the verifier alone | B6's zero-call CI is a mechanism, not a convention; R13's "approval on first use" needs a state the file can carry |
| D73 | `SqlGuard`'s rule set (§5.3): one `SELECT`, allow-listed tables and columns with aliases resolved, no set operations, a function allow-list, literals bounded, values bound as parameters that must match the query's arguments, `ORDER BY` completed with the key, `LIMIT` ≤ 200 injected, size bounds, a dialect served only with a passing BS6 corpus | the agent's `StatementGuard` alone (keywords); a looser guard that trusts the read-only role; set operations and arbitrary functions allowed | ai-inference F7 and F24: unordered results and unguarded SQL are the two known sources of flaky and unsafe generated tests; the read-only role is a second wall, not the first; a parameter that must equal an argument stops a literal smuggled through the model |
| D74 | Budget meters reserve an estimate before a call and settle after; exhaustion is `RESOLUTION_FAILED` / `BUDGET` for a slot, ends a session's model calls, and stops a job's further batches; a job budget has no default | a soft budget (warn and continue); `ENVIRONMENT` for exhaustion; a default job budget | B6 says the budget aborts; a slot whose model call the budget refused has no answer, which is what `RESOLUTION_FAILED` means; B6 left the nightly figure to the deployment |
| D75 | `re-resolve` is a command and a `BindMode`, resolves in graph rounds with one batch per round through `BatchableResolver`, writes files `APPROVED` by the job and a report; the pull request is the pipeline's | one batch of one request per slot inline; a mode of `run`; the base opening pull requests; files written `REQUIRED` | one batch per round is the batch rate without a serial wait per slot; B11 keeps the base to files; a `REQUIRED` file would make the merged bindings unusable until a second manual step, while the pull request review is the approval B6 asked for (Q1) |
| D76 | The MCP server is stdio only, read-only by default, offers mutating tools by flag, never a destructive primitive or `confirm`, names in `[a-z0-9_]`, results cut at 25000 characters, captures as resource links | Streamable HTTP now; destructive tools behind a flag; a per-tool confirmation prompt inside the server | design §5 and control-channel F17-F22; a server cannot know that a person confirmed, so destructive stays with the CLI's `--confirm` |
| D77 | The planner is `claude -p --bare` as a subprocess with the generated scenario JSON schema, at most two repair rounds by `--resume`, tested with a substitute executable; its cost is recorded as provider `claude-code` | an Agent SDK sidecar in TypeScript; a planner loop written on the gateway with the tool projection | B3 chose `claude -p`; a loop of our own would re-implement the CLI's tool search, permissions and compaction (ai-inference F17, Risk5) for no gain in a test framework |
| D78 | Triage is a separate command over a finished run, two calls (a Haiku label, a Sonnet summary), never a change of outcome or of the run's files | triage inside `run` behind a flag; a label only | the zero-call assertion of `replay` must stay absolute; a summary is what a person reads (ai-inference R14) |
| D79 | `ExecutorSession` is built by extracting the executor's private call runner so that a plan step and a session step share one implementation; `StepOutcome` carries the classification instead of throwing | a second executor for sessions; the session calling the agent directly with its own waits | B15's "one way in"; two implementations of the step protocol would drift |
| D80 | The journal is a masked append-only file with typed records and model provenance in `origin`; the export writes verb steps when a step was journaled by verb and the existing base verb `call` otherwise, references written back for bound values, observed expectations marked, failed steps excluded by default, a compile check before anything is written | exporting primitives only; exporting verbs by reverse-mapping calls; writing literals | a scenario should read as a person would write it, and only the verb the person or the model chose is known for certain; a literal that equals a bound value would hide the dependency and could leak a secret |
| D81 | Plan version 5 (`inference: bind`, the `call` verb), the reader accepting 1-5; the sample's plans and bindings regenerated once | keeping 4 and tolerating the keys | P2 D18, P3 D41, P4 D58 |
| D82 | `approve` reuses a recorded, verified, unchanged tier (b) value by default (`--reuse-bindings`) and calls a model only for the rest | always re-resolving in `approve` | a person approving a scenario should not pay and wait for an answer that has not changed (Q2) |
| D83 | `CostLedger.Entry` gains purpose, cache tokens, batch and a note with the old constructor kept; every run, job, triage and session writes `inference.jsonl` with metadata and masked structured answers, prompt text only on request | a new ledger type; prompts always logged | the ledger is the zero-call assertion's evidence and must not change shape under its readers; prompts are personal data by default (ai-inference R15, C8) |
| D84 | Model classes with the defaults Sonnet (resolve, session, summary), Opus (the planner), Haiku (labels); refusal fallbacks on and recorded; images off; Fable never configured by default | one model everywhere; fallbacks off; images on | ai-inference R7 and F10 (cost and retention); a fallback that answers a refusal keeps a nightly job alive and the ledger says who answered (Q6) |
| D85 | The agent's `StatementGuard` stays as it is and `SqlGuard` lives on the controller only | JSqlParser inside the agent for `data.query` | B4: nothing but the JDK in a target JVM; the model's SQL runs through the controller's channel or through `data.query` after the controller's guard |
| D86 | The calibrated-oracle case of gap 26 is answered by the export: an observed value is an ordinary expectation marked in the YAML and listed in the report for the reviewer; no run-time status | a `DEGRADED` outcome for a scenario with observed expectations; refusing to export them | the reviewer who commits the scenario decides; a run cannot tell an observed value from a specified one after the comment is removed |
| D89 | A `confirm` argument of any call (a plan step, a cleanup call, a session step) is taken out of the parameters and sent as the request's `_meta.confirm` (2026-10-08, E1) | leaving `confirm` as an ordinary parameter | the agent reads the confirmation only from `_meta`, so a scenario's `confirm: true` never reached it before; no primitive in the base or the samples has a parameter of that name; a `MODEL` origin's `confirm` is stripped before this mapping (§12.2) |
| D88 | The Anthropic Java SDK is pinned to its current line at build time (2.69.x on 2026-10-08) rather than the research note's 2.34.0; the client is built explicitly from `ANTHROPIC_API_KEY` with the SDK's retries and logging off, and the gateway retries within its own deadline; MCP comes as `mcp-core` + `mcp-json-jackson2`; the root POM imports `jackson-bom` | staying on 2.34.0 with work-arounds (an untyped fallback parameter, no typed workspace id, unknown 5.5 content blocks); `fromEnv()`; the SDK's own retries; the `mcp` convenience artifact | spike BS13 (F-2, F-7, F-9, F-11): 2.34.0 predates the 5.5 models and the fallback betas; `fromEnv()` reads profiles, auth tokens and custom headers from the operator's shell; the SDK's retries are per attempt without a cap on `retry-after`; `mcp` brings a second Jackson |
| D87 | The session service offers the pack's verbs (and base primitives only by policy), one action at a time, with the model's `confirm` stripped; the overlay bridge rides the existing event stream and answers through a pack-named primitive | a free tool set; parallel actions; a dedicated transport for overlays | design §5 and B15: the verbs are what exports well, a model's request is never a confirmation, no new transport |

### 16.1 Changes to the Phase 1-4 contracts that Phase 5 needs

Written into the files named when the build starts, marked "(Phase 5, specified 2026-10-08, not yet built; P5 §n)",
and "(Phase 5, built …)" once built:

| Contract and section | Change |
|---|---|
| P1 §8.1 scenario YAML | the existing base verb `call` confirmed (§13.1): `confirm` reaches the call; `call` reserved as a verb name |
| P1 §8.6 CLI | the commands `re-resolve`, `mcp`, `author`, `triage`, `session`; the inference options of `run` |
| P1 §8.5 outputs | `inference.jsonl` per run; `failure/triage.json` and `triage.md` written by `triage`, not by the run |
| P2 §3.1 grammar | `llm-sql` accepted when the pack declares an allow-list; `llm-reason`, `llm-vision` still rejected |
| P2 §5 `plan.json` | version 5; `inference: bind` |
| P2 §6.1 `DomainPack` | `sqlAllowLists()`, `assistAnswerPrimitive()`; `StepLibrary.argsSchema` |
| P2 §6.3 | `Approval.State.REQUIRED`; `Resolution.usage` filled from the ledger (the contradiction the inventory of 2026-10-08 found: the engine passes null today); `confidence` from the proposal |
| P2 §6.4 rules | a resolver may be a `BatchableResolver`; contributed resolvers after the pack's |
| P2 §6.6 | `BindMode.RE_RESOLVE`; `BindOptions.inference`; `CostLedger.Entry`'s new fields; `ResolverContributor`, `InferenceSettings` |
| P2 §8.3 algorithm | the ladder with contributed resolvers; `replay` step 6 refusing an unapproved tier (b) slot; `approve`'s reuse; the `re-resolve` mode |
| P2 §9 bindings file | `approval.state` may be `REQUIRED`; `llm` filled for tier (b); `provenance.rationale` for tier (b); notes stay out of the file |
| P2 §10 taxonomy | `REBIND_NEEDED` reason `NOT_APPROVED`; `RESOLUTION_FAILED` reason `BUDGET`; `ENVIRONMENT` with the job's reason `BATCH_TIMEOUT` |
| P2 §11.4 never written | a prompt that was not masked; a model key; a response to a request that named a secret |
| P2 §11.5 | built: `ExecutorSession`, `StepJournal` as §12 |
| P2 §12 CLI and JUnit | §13.4, §13.5 |
| P3 §4 | plan version 5 supersedes 4 as the written version |
| P4 §6.1 | `ExecutorSession.open` opens a web path as a run does |
| design §3.1, §3.4, §5, §7, §8 | the two modules, the settings, the session service as specified, the roadmap row |

### 16.2 Open SPI and DSL gaps: what Phase 5 takes

Numbers are those of `../TODO.md`, "Open SPI and DSL gaps".

| # | Gap | In Phase 5 |
|---|---|---|
| 13 | Session-scoped data reads | unchanged; a tier (b) statement is one guarded `SELECT`, and a `CALL` only to an allow-listed procedure |
| 26 | Oracle cross-check outcomes, the calibrated-oracle case | the export marks and lists observed expectations (D86); no run-time status |
| 29 | A pack-contributed CLI subcommand | unchanged; the MCP server projects a pack's verbs, which is the need the gap recorded in another form |

New, for the gaps table: **a verb without an `argsSchema` is projected as a free-form tool** (the planner authors it
blind; the sample declares every schema); **`data_query` over MCP needs an allow-list** (a source without one is not
offered); **the planner needs the `claude` CLI on the `PATH`** (a run-time precondition of one command, never of CI);
**tier (c) at bind time is not built** (the first non-goal); **the session's base primitives are off by default** (a
scenario exports verbs; a session that must click a nameless button opts in).

---

## 17. Questions for the owner: answered provisionally 2026-10-08 (B23)

Answered by the orchestrator in the manner of P2 §17, P3 §14 and P4 §15, so that the build can start; written into the
sections named. A veto removes or changes what the section says; its form is stated.

| # | Question | Provisional answer | Written into | A veto means |
|---|---|---|---|---|
| Q1 | **Who approves the nightly job's bindings?** The job writes `APPROVED by re-resolve:<jobId>` and the pull request review is the gate, or the job writes `REQUIRED` and a person approves each file afterwards? | The job writes `APPROVED`; the pull request is the review (D75) | §7.1, §8.2 | `B23 Q1 veto`: files written `REQUIRED`; merged bindings stay unusable in `replay` until `skillroy resolve` approves them |
| Q2 | **Should `approve` reuse an unchanged, verified tier (b) value without a model call?** | Yes, by default (`--reuse-bindings`, D82) | §7.1 | `B23 Q2 veto`: `approve` always re-resolves tier (b) slots |
| Q3 | **The tier (b) gates beyond the verifier**: confidence ≥ 0.8 and the model's own row bounds (ai-inference R13), or the verifier alone? | Both gates, as the research recommends; the threshold is a setting | §5.4 | `B23 Q3 veto`: the verifier alone; confidence and bounds recorded, not enforced |
| Q4 | **`SqlGuard`'s strictness**: no set operations, a function allow-list, values as parameters that must match the query's arguments, `ORDER BY` completed with the key? | Yes to all (D73) | §5.3 | `B23 Q4 veto`: name which rule to drop (`B23 Q4 veto functions`, `… union`, `… params`) |
| Q5 | **Committed recordings** of model exchanges in the repository (masked, reviewable, a few hundred KB) so that CI needs no key, or fixtures generated at build time by a maintainer? | Committed (D71) | §4.2, §14.5 | `B23 Q5 veto`: recordings kept outside the repository; the acceptance's tier (b) items run only with a key |
| Q6 | **Refusal fallbacks on by default** (a refused request answered by a provider-chosen model, both names in the ledger)? | On (D84) | §4.1 | `B23 Q6 veto`: off; a refusal is "no answer" |
| Q7 | **The MCP server read-only by default, mutating by flag, destructive never?** | Yes (D76) | §9.3 | `B23 Q7 veto`: name the change (`… mutating by default`, `… destructive by flag`) |
| Q8 | **The planner as the `claude` CLI headless** (B3), with the person's own Claude Code login or the key, tested in the build with a substitute executable? | Yes (D77) | §10 | `B23 Q8 veto`: a planner loop on the gateway with the tool projection (more code, no CLI dependency) |
| Q9 | **Triage as a separate command**, never inside `run`? | Yes (D78) | §11 | `B23 Q9 veto`: `run --triage` in `live` and `approve` |
| Q10 | **Plan version 5** and the sample's bindings files regenerated once? | Yes (D81) | §13.2 | `B23 Q10 veto`: keep 4 |
| Q11 | ~~**The base verb `call`** for exported steps and for primitives without a verb?~~ Withdrawn 2026-10-08: the verb has existed since Phase 1 (P1 §8.3) and the export uses it as it is; nothing to decide | (withdrawn) | §13.1 | – |
| Q12 | **The export's approval**: a tier (b) slot of an exported session is `REQUIRED` unless the exporter names `--approved-by`? | Yes | §12.3 | `B23 Q12 veto`: always `REQUIRED` |
| Q13 | **Budget exhaustion as `RESOLUTION_FAILED` / `BUDGET`** (not `ENVIRONMENT`), a session's model calls ending, a job's further batches stopped? | Yes (D74) | §7.2 | `B23 Q13 veto`: `ENVIRONMENT` |
| Q14 | **Prompts not logged by default**: `inference.jsonl` holds metadata and masked structured answers; prompt text only with `--log-prompts`? | Yes (D83) | §7.4 | `B23 Q14 veto`: masked prompts always logged |
| Q15 | **Model defaults**: Sonnet for resolvers, sessions and summaries; Opus for the planner; Haiku for labels; Fable never by default; `images: false` until Phase 7? | Yes (D84) | §2.2, §4.4 | `B23 Q15 veto`: name the class to change (`… Opus for resolvers`) |
| Q16 | **The session's default tool set**: the pack's verbs, not the base `swing_*`/`web_*` primitives (opt-in)? | Yes (D87) | §12.4 | `B23 Q16 veto`: base primitives offered by default |
| Q17 | **Three new controller-side dependencies** (the Anthropic Java SDK, JSqlParser, the MCP Java SDK) in the CLI distribution, audited before they are declared? | Yes | §2.2, §2.6 | `B23 Q17 veto`: name the piece to keep out (`… no MCP` makes `skillroy-mcp` a separate repository) |
| Q18 | **`inference.yaml` beside `credentials.yaml`**, committed, holding no secret; the key only in the environment? | Yes | §2.3, §4.4 | `B23 Q18 veto`: everything by options and properties, no file |
