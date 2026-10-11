# Phase 5 contracts: the AI layer (inference gateway, model-written SQL, approval and budgets, batch re-resolution, MCP, the planner, triage, the interactive profile)

**Status: specified 2026-10-08; built 2026-10-08 on the then provisional B23 answers; reviewed (three lenses, each
with a sceptic) and fixed the same day; verified in the Linux matrix (`../TODO.md`, "Status at a glance"); B23 and B24
confirmed by the owner on 2026-10-08, every row.** The questions of §17 are answered by the orchestrator and recorded as
decision **B23** (`../DECISIONS.md`, the owner interview page), confirmed by the owner; the decisions the build and the
review's fix wave took are D88-D104 and D110 (§16, **B24**), confirmed by the owner. **The live items were closed in
the afternoon of 2026-10-08** with the owner's workspace-scoped model key (`ANTHROPIC_API_KEY`, no workspace id
needed): BS13's live part, scenario D's live recordings, the profiles `inference-live` and `inference-record` with
§15.3 run live (every item green in the second run), scenario C authored by the planner and the nightly job's figures
(`../TODO.md`, "Phase 5, the live items"); the decisions of that session are D111-D113 (§16, **B25**), confirmed by the owner on 2026-10-09.
Every section the build changed carries an "As built" note; the review's fixes carry "fix wave" notes; the live
session's passages are marked "the live session". Written in the manner of `phase2-contracts.md`, `phase3-contracts.md` and `phase4-contracts.md`:
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
  target's primitive catalogue and the pack's verbs as tools, with read-only and destructive hints, the resources of
  design §5, and the hosting of an interactive session (§9); the deferral of the low-level tools is the client's
  (Claude Code's tool search), not the server's;
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
  `inference-live`, skipped without a key; specified, not built: D110) (§15).

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
| Anthropic Java SDK (`com.anthropic:anthropic-java`) | the current line at build time: **2.70.0** as built 2026-10-08 (released that day; D88; the research note's 2.34.0 predates the 5.5 models, the fallback parameter and the per-request workspace id, spike BS13 F-11); the exact version is pinned in the root POM and audited | `skillroy-inference`, main scope; the CLI distribution | **MIT** (BS13: the POMs of all three artifacts; the jars carry no licence file, so `THIRD-PARTY.txt` carries the MIT text and the copyright line) |
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

- **`inference-live`**: the tests of §15.3, which call the live Claude API. The profile is given explicitly
  (`-Pinference-live`) and its tests run only when the environment variable `ANTHROPIC_API_KEY` is set as well; without
  the key they are skipped with the reason "no model key", never failed. These tests are never part of `mvn verify` in
  CI.
- **`inference-record`**: refreshes the recordings of §4.2 from the live API (a maintainer's task; needs the key).

**Both profiles (2026-10-08, the live session; D111)**, built in the same change as their first live run and BS13's live
part, as D110 asked (until then no POM declared either, so `-Pinference-live` only made Maven warn, and the recording
route that existed was `record: true` with `recordings:` in an `inference.yaml` of `gateway: claude`, which stands:
`Gateways.build` wraps the live gateway in a `RecordingGateway`, and `-Dskillroy.inference.record.overwrite=true` lets
it replace a file). A profile is explicit (`-P`) and never activated by the environment: Maven cannot make a profile
depend on a variable *and* a flag at once, and a profile that activated on the key alone would make `mvn verify` call a
model on any machine that has one. Each profile sets one system property for the test JVMs through the surefire and
failsafe plugin management of the root POM, as the `pass-it-target-java` profile does: `skillroy.inference.live=true`,
and `skillroy.inference.record=true` together with `skillroy.inference.record.overwrite=true` (§4.2). The live tests
are failsafe classes (`ClaudeGatewayLiveIT` in `skillroy-inference`; `InferenceLiveIT` and the record route
`ScenarioDRecordIT` in the sample's scenarios module), compiled with every build and run by every `verify`, and each
begins with two assumptions: the property of its profile ("the profile inference-live is not active", or
`inference-record`) and the key in the environment ("no model key"), so that a build without the profile, or with the
profile and no key, reports them skipped, never failed, and a profile that does not exist can no longer read as a
pass. The key reaches a test JVM as it reaches the CLI: through the environment, inherited by the forked JVM, and in
the Linux image through `ci/run-in-linux.sh`'s pass-through by name. The live tests spend money, and each states its
bound (§15.3); `ClaudeGatewayLiveIT` and `ScenarioDRecordIT` run natively (no display), `InferenceLiveIT` launches the
desk and runs in the image.

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
  string lengths, `minItems` 0 or 1, no external references; BS13 live, 2026-10-08: a numeric `minimum` and a
  `minItems` other than 0 or 1 are refused with HTTP 400, a string `maxLength` was accepted, which the conservative
  rule keeps refusing all the same); `Schemas.ofRecord(Class)` (§3.3) builds compliant schemas from records and
  refuses what it cannot express.
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

As built (2026-10-08, I1): `InferenceGateway` also has a default `complete(request, Duration)` (the way a model call
stays inside what is left of a bind budget); `InferenceException` carries `usage()` so that a billed failure reaches
the ledger; `fingerprint()` is 64 hex digits without a prefix (it is a file name) and leaves `purpose` and the
breakpoint flag out as well; `Content.Image` has `sha256()`; `Usage` has `toJson`/`fromJson`; `Effort` has
`parse`/`label`, `ModelClass` a `settingsKey()`; `JsonLike` makes deep frozen copies.

(Phase 7, built 2026-10-10, VM; P7 §7) `ModelRequest` gains the eleventh component `providerTools` (`ProviderTool(provider, type,
raw)`, the ten-argument constructor keeps it empty; in the fingerprint only when non-empty); `GatewayCapability` gains
`PROVIDER_TOOLS`; `Content.ToolUse` and `Content.ToolResult` gain a nullable `toolsetName` (in a part's JSON only when set);
a `ToolResult`'s content may be a list of `Text` and `Image` parts, an error result with an image refused at
construction; `CacheHint` gains `AUTO` (the system breakpoint plus the request's top-level `cache_control`);
`Prompts.imageSize`/`imageTokens`.

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
**built explicitly** from `ANTHROPIC_API_KEY` (and `ANTHROPIC_BASE_URL` when set, which (Phase 6 security fix S51,
A51, built 2026-10-09, SR) must be `https` (or `http` on `127.0.0.1`, `::1` or `localhost`) and carry no user-info,
else `AUTH` naming the variable and never its value, since the key travels there), with `maxRetries(0)` and the SDK's
logging off: not `fromEnv()`, which also reads `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_PROFILE`, the workload-identity
variables, profiles on disk, `ANTHROPIC_CUSTOM_HEADERS` and `ANTHROPIC_LOG`, and would make the gateway's credential
source and its logging depend on an operator's shell (BS13 F-9). Structured output goes through the manual
`OutputConfig.builder().effort(…).format(JsonOutputFormat…)` form, because the typed `.outputConfig(Class)` overload
dropped `effort` in 2.34.0 (BS13 F-1; 2.70.0 keeps it, and the manual form stays because it is explicit and
builds the schema the project chose); the schema is the project's (`Schemas.ofRecord`). Response content is read tolerantly
(unknown block types become `Opaque` parts; the SDK's `validate()` and visitors without an `unknown` branch are not
used), and `stop_details.category` is read as a string (the SDK's enum lags the provider's categories; BS13 F-8).

| SPI element | Claude mapping |
|---|---|
| the key and the workspace | `ANTHROPIC_API_KEY` from the environment (`fromEnv()`); an organisation-level key (one not scoped to a workspace) is refused by the API with HTTP 400 unless every request carries the header `anthropic-workspace-id`, and SDK 2.34.0 adds that header on its own only for profile, OAuth and workload-identity credentials, so the gateway adds it itself for a static key from `inference.yaml` `workspaceId`, else the environment variable `ANTHROPIC_WORKSPACE_ID` (found by BS13 on 2026-10-08: the owner's key is such a key); a 400 whose message names the workspace is reported as `AUTH` with that message, never with the key |
| `ModelClass` | the ids of `inference.yaml` `models:` (defaults in §2.2); `requestedModel` in `Usage` |
| `Content.Opaque` | a `thinking` block (with its signature) or a `fallback` block of a response, kept as it came; a request's history that carries one sends it back unchanged (the 5.5 models return thinking blocks before tool calls, BS13 F-2) |
| `Effort` | `output_config.effort` `low`/`medium`/`high` for `OPUS_CLASS` and `SONNET_CLASS`; **no `effort` field for `HAIKU_CLASS`** (BS13 live, 2026-10-08: the models endpoint reports `effort.supported: false` for `claude-haiku-4-5` and a request that carries it is refused with HTTP 400 "This model does not support the effort parameter"; the request's `effort` stays part of its fingerprint, so a recording's identity does not change); thinking is left at the model's default (adaptive; it cannot be disabled on the 5.5 models and is not disabled anywhere, ai-inference F12; the models endpoint reports `thinking.types.disabled.supported: false` for Sonnet 5.5 and Opus 5.5 alike). Live (BS13): Sonnet 5.5 at `low` answered with no thinking block; Opus 5.5 at `low` answered with a `thinking` block of empty text and a signature (82 thinking tokens), which is the `Opaque` part a loop echoes |
| `maxTokens` | `max_tokens` |
| `CacheHint` | `cache_control {type: ephemeral}` on the last system part marked `cacheBreakpoint` (`SHORT`: the default 5-minute TTL; `LONG`: `ttl: 1h`); none when `NONE`. The provider's minimum cacheable prefix is 512 tokens on the 5.5 models and 4096 on Haiku 4.5 (ai-inference F13): a shorter prefix silently does not cache, which the ledger shows as `cacheReadTokens = 0` |
| `Content.Text` / `Image` | text and image blocks (`base64`) |
| `ToolUse` / `ToolResult` | `tool_use` and `tool_result` blocks; a `ToolResult` with JSON-like content is rendered as JSON text |
| `ToolSpec` | a tool with `strict: true`, `additionalProperties: false` and every property required; tool choice `auto` always (forced choice is HTTP 400 on the 5.5 models). **The union limit** (the live session, 2026-10-08): the provider refuses a request whose strict tools hold more than 16 union-typed parameters (a property with `anyOf`, `oneOf` or a `type` array, at any depth, counted over all the request's tools) with HTTP 400 "Schemas contains too many parameters with union types … (limit: 16 parameters with unions)" (the sample's verbs as session tools hold 20: the "value or a `$reference`" unions and the nullable optional keys strict mode forces), so the gateway counts them before rendering and sends every tool of such a request without `strict` (the schemas unchanged; the pack's verbs validate their arguments at the step), and with `strict: true` otherwise |
| `outputSchema` | `output_config.format` of type `json_schema`; the answer's text is parsed with `Json` into `structured` |
| `StopReason` | `end_turn`, `tool_use`, `max_tokens`, `refusal` (`stop_details.category` goes into the ledger's `note`) |
| `Usage` | `usage.input_tokens`, `output_tokens`, `cache_read_input_tokens`, `cache_creation.ephemeral_5m_input_tokens` and `ephemeral_1h_input_tokens` (the split by TTL; `cache_creation_input_tokens` is their sum), the response id, the model the response names (a dated id such as `claude-haiku-4-5-20251001` for the alias `claude-haiku-4-5`, BS13 live: the requested id followed by `-` and a date is the same model, not a fallback, and is priced as its alias), `usage.iterations` whenever `fallbacks` was sent (one entry per iteration; a second entry, or a `Usage.model` other than the requested one, is what says a fallback ran); `costUsd` from the price table (§7.2), priced from the split, not from the `CacheHint` |
| `submitBatch` … `collectBatch` | the Message Batches API (`client.messages().batches()`, whose request params are their own type): one batch per call, `custom_id` = the request's index, results read as JSON Lines (`resultsStreaming`) and matched by `custom_id`, never by position; `costUsd` at the batch rate (50 % off, stacking with cache reads); no `fallbacks` in a batch. Live (BS13, 2026-10-08): a batch of two requests ended after 458 s, its results streamed in reverse order (the match by `custom_id` is what saved them), `service_tier: batch` in each usage; the first request wrote the one-hour prefix (`ephemeral_1h_input_tokens`) although a standard request had written the identical entry five seconds before the submission, and the second read 1135 tokens (the first's entry, or that one; one observation: a batch request did not read the standard tier's entry, which §8.3's reading of the cache criterion allows for) |
| `countTokens` | `messages.countTokens` (live, BS13: exact, 0 % off the reported `input_tokens + cache_creation_input_tokens + cache_read_input_tokens` in both measurements; the structured-output format adds about 200 tokens to a request's prefix, counted on both sides) |
| errors | classified by `statusCode()` and `errorType()`, not by the exception class alone (BS13 F-9: 529 arrives as `InternalServerException` with `overloaded_error`, 402 and 413 as `UnexpectedStatusCodeException`): 401/403 `AUTH`; 402 `BILLING`; 429 `RATE_LIMIT`; 529 or `overloaded_error` `OVERLOADED`; 400/404/413/422 `INVALID_REQUEST`, except the 400 whose message names the workspace, which is `AUTH` with that message; `AnthropicIoException` `TRANSPORT`, or `TIMEOUT` when its cause is a timeout or the deadline passed; the rest `PROVIDER`; retries as §2.4 |
| fallbacks | `inference.yaml` `fallbacks: true` (the provisional default, §17 Q6) sends the server-side refusal fallback in its `default` form (the beta header `server-side-fallback-2026-07-01`, `fallbacks: "default"`; typed in the SDK line of §2.2, an extra body property on 2.34.0): a refused request is answered by a model the provider picks by refusal category, `Usage.model` names it and a `fallback` block comes back as an `Opaque` part; `false` sends none; **never in a batch request** (the provider rejects it; BS13 F-7; live: HTTP 400 "The `fallbacks` parameter is not supported for batch requests"). Either way the ledger records both model names. Live (BS13, 2026-10-08): the body property is accepted on the regular endpoint only together with the beta header (without it HTTP 400 "fallbacks: Extra inputs are not permitted"); a response to a request that carried `fallbacks` has `usage.iterations` whether or not a fallback ran (one entry per iteration with its own token counts, `model: null` for the primary), so its presence means nothing and a fallback is read from a second iteration or from `Usage.model`; the models endpoint names the allowed targets: `claude-sonnet-5` for Sonnet 5.5, `claude-opus-4-8` and `claude-opus-5` for Opus 5.5, none for Haiku 4.5, which accepts the parameter all the same (one raw request, 2026-10-08) (all in the price table of §7.2) |

As built (2026-10-08, I1): `ClaudeGateway.open(InferenceSettings)`; a package-private constructor takes a client for
the loopback fake; responses are read from their JSON form so that unknown, thinking and fallback blocks become
`Opaque`; the fallback parameter goes to the regular endpoint as an extra body property plus the beta header (in
2.70.0 the typed form exists on the beta request type only); a tool is sent `strict: true` only when its schema can
be strict (a free-form schema cannot); the SDK's tree no longer brings Apache HttpClient 5.

As built (2026-10-08, fix wave): `open` refuses, with `AUTH` and a message that names the variable and never the value,
a key with any character outside printable ASCII without the space (0x21-0x7E: a no-break space, a zero-width space or
a control character survives `strip()`) and a workspace id, from the settings or from `ANTHROPIC_WORKSPACE_ID`, that is
not of the settings' form (`[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}`): the HTTP client's own refusal of such a header quotes
its value, which would otherwise have reached a run's outputs, an MCP tool error and the CLI's stack trace; a client
that cannot be built is `INVALID_REQUEST` without the exception's message; and any other `RuntimeException` of the SDK
or its HTTP client during a call leaves the gateway as `PROVIDER` with the known secrets scrubbed and no cause (the
batch methods included). A `retry-after` (or `retry-after-ms`) that is not a finite number of at least 0 is ignored (the
gateway's own back-off applies), and a wait is taken only when it is shorter than what is left of the deadline,
compared as a difference, so that a value near `Long.MAX_VALUE` cannot overflow past it.

As verified live (2026-10-08, BS13's live part through SDK 2.70.0, the owner's workspace-scoped key, USD 0.032 in all, the two CLI calls included;
`../../spikes/BS13/RESULT.md`): the mapping rows above hold as the build made them, with the corrections marked "live"
in the rows (no `effort` for the Haiku class; the fallback header mandatory and `usage.iterations` present whenever
`fallbacks` is sent; `claude-opus-5` priced; the batch tier's own cache). The strict tool with `tool_choice: auto`
was called with schema-valid input and a forced choice was refused as the row says; the structured answer of both
the typed and the manual `OutputConfig` forms parsed (2.70.0's typed form keeps `effort`, which 2.34.0 dropped; the
gateway keeps the manual form); 404 and 400 arrive as `NotFoundException` and `BadRequestException` with request ids;
a 1135-token system part was written to the cache (five-minute TTL) and read by the identical call two seconds
later, and written again as a one-hour entry with `ttl: 1h` and no beta header; `count_tokens` was exact. No refusal was provoked, so `stop_details` stayed `null`
on every response. The Haiku rule was found by the orchestrator with one raw request and is built in the same
change as the live profiles (D111).

(Phase 6 security fix, pass 2, P2-L5-F3, built 2026-10-09, SC) What the provider returns is scrubbed of the known
secrets (the key, the workspace id) before anything is read from it, not its error message only (S54): every string of
a response (`model`, `id`, `stop_reason`, `stop_details.category`, text blocks, `tool_use` input, unknown blocks, map
keys included) and an error's `request-id` header and `type`; then the identifiers are bounded at 200 characters
(`id`, `model`, `request-id`) and the words at 100 (`stop_reason`, `stop_details.category`, the error `type`, in a
call's error and a batch result's), one line, so that whatever `ANTHROPIC_BASE_URL` names cannot write the key or an
unbounded text into a ledger, `inference.jsonl`, a recording, a session journal's origin, triage's files, a re-resolve
report or a span.

What the gateway never does: it never logs a prompt or a response body (the run writes `inference.jsonl`, §7.4,
with metadata and masked structured answers only, and the prompt text itself only under `--log-prompts`); it never
reads a key from anywhere but the environment; it never selects a model the settings do not name; it never sends an
`Image` part when `IMAGES` is disabled in the settings (`images: false`, the default until Phase 7: a capture is
personal data until masked, design §5), and over MCP (§9.4; Phase 6 security fix S58, A57, built 2026-10-09, SR;
D159). Zero-data-retention: the 5.5 models and Haiku are eligible, Fable is not and is
never configured by default (ai-inference F10, R15). (Phase 6 security fix S52, A52, built 2026-10-09, SR) Fable may be
named only with a `prices:` row, and naming it leaves the zero-data-retention regime.

(Phase 7, built 2026-10-10, VM; P7 §7.1, 7.2, 7.4) The computer toolset goes out typed
(`ComputerToolset20260801` with its `configs`), another `claude` provider tool raw, another provider's
`INVALID_REQUEST`; `toolset_name` is read from a `tool_use` block and sent on the echoed call and its result; a parts
result is `contentOfBlocks` (an image only while images are on); `AUTO` adds the top-level marker; the gateway declares
`PROVIDER_TOOLS`. A call of `claude-haiku-5-5` above 100K input tokens carries the price table's tier note.

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
- **The profile `inference-record`** of the two bullets above (the `-Pinference-record` of the missing recording's
  message, the profile of the overwrite property), built 2026-10-08 in the live session (D111; until then specified
  only, D110, the route that existed being `record: true`, §2.3, which stands and which the overwrite property serves
  without the profile too): `ScenarioDRecordIT` in the sample's scenarios module (§14.2), run by `verify` under
  `-Pinference-record` with the key, removes the module's committed `resolve`-purpose recordings, binds scenario D in
  `approve` without reuse on each seed through the live gateway wrapped in a `RecordingGateway` (settings `gateway:
  claude`, `record: true`, `recordings:` the module's directory; the profile's overwrite property), and then writes the
  committed bindings files of both seeds from the new recordings through the recorded gateway, as `approve` writes
  them, with the label `recorded <date>` (the UTC date of the run). The message of a missing recording names the
  profile and the sample's recordings README. As built (2026-10-08, the live session, after the review lens): the route
  records and writes into a temporary directory first, checks there what the unit tests pin (the provider, a Sonnet
  model id, the confidence at or above the threshold, a non-empty rationale, the guarded statement, the value loader's
  statement, the label, the bindings files' canonical form) and runs the secret scan over the four files with both
  seeds' passwords, the desk PIN and the reader password, and only then replaces the committed recordings and bindings
  files, so that a failure on either seed leaves the module as it was. The fixture update property (`-Dskillroy.inference.fixtures.update=true`)
  regenerates only what stand-ins still make, the session's recordings and export (§14.4), and rewrites scenario D's
  bindings files from whatever recordings exist with their existing label; it records no tier (b) stand-in any more
  (the generator is gone), so that it can never replace a live recording with a scripted one.
- **Where the base keeps recordings**: `samples/skillroy-sample-lending-scenarios/src/test/resources/inference/`
  (the sample's tier (b) queries on both seeds, the triage of the sample's two negative runs, the session of §14.4, the
  planner's post-processing fixtures) and `controller/skillroy-inference/src/test/resources/inference/` (unit fixtures,
  hand-written where no live call is needed). They are committed, reviewed like fixtures, and byte-stable
  (`Json.writeCanonical`, sorted keys). A recording's request text is masked text: a reviewer can read it.
- As built (2026-10-08, I1): a recording is pretty-printed JSON with keys sorted at every depth (byte-stable and
  reviewable); `RecordingGateway(live, dir[, mask, overwrite, clock])`; `Gateways.open(settings)` returns a shared,
  reference-counted lease per distinct settings (`Gateways.NONE` for `none`), so that the parked binds of a
  `re-resolve` job share one gateway; the inference module's own tests pin one committed recording of the test pack's
  prompt (`-Dskillroy.inference.fixtures.update=true` regenerates it).
- As built (2026-10-08, fix wave): the recording's second pass through the prompt mask applies the mask of whoever
  opened the gateway: `Gateways.open(settings, mask)` hands the opener's `PromptMask` to the `RecordingGateway` it
  builds for `record: true` (the shared lease keeps the first opener's mask); the contributor (its mask: the
  allow-lists, the row masks, the key rule) and the `re-resolve` job (its mask also knows the environment's
  credentials) open their gateways so, as the session's opener does with the session's mask; `Gateways.open(settings)`
  keeps the key rule and the bounds alone. The by-value and by-column rules are the request builders' (every builder
  masks its parts with the full mask of 6.2 before the gateway sees the request), so the second pass is a safety net,
  not the mask. A request with no recording is `NO_RECORDING` with "record with a recording gateway (record: true, see
  the recordings README of the sample)", the route that exists, in place of the profile this section names.
- **Determinism**: a recorded answer is replayed verbatim, so a test that depends on a recording is deterministic; the
  code path that *produced* the request (the prompt assembly, the catalogue, the mask) is what the fingerprint pins. The
  tests of §15.1 assert the fingerprint of each committed recording against the request the code builds today, so that
  a prompt change fails the build with "recording missing", not with a silently different model answer.

(Phase 7, built 2026-10-10, VM; P7 §7.3) The recorded
gateway declares every capability (`IMAGES` and `PROVIDER_TOOLS` included: a recording is keyed by a fingerprint that
hashes them); a recording's request holds `providerTools` when there are any, and every part with its `toolsetName`
when set and an image by its hash (the file's mask passes images; the bytes are never written).

### 4.3 `ScriptedGateway` (`…inference.scripted`)

For unit tests that want to script an exchange without files: `ScriptedGateway.answering(Function<ModelRequest,
ModelResponse>)`, `ScriptedGateway.sequence(List<ModelResponse>)`, `ScriptedGateway.failing(InferenceException)`;
every request is kept in `requests()` for assertions. In main scope (as `Approver.APPROVE_ALL` is), so that the
scenarios modules' tests and a pack's tests can use it without a test-jar.

As built (2026-10-08, S2): `Gateways.serve(InferenceSettings, InferenceGateway)` registers a caller's own gateway (a
scripted one, or a recording wrapper around it) for the settings, so that the real contributor and binder use it; the
gateway stays the caller's and `Gateways` never closes it. This is how the sample's stand-in recordings are made
and how the scripted negatives of §15.1 and §15.2 reach the engine.

(Phase 7, built 2026-10-10, VM; P7 §7.3) The scripted gateway declares every
capability, `IMAGES` and `PROVIDER_TOOLS` included, so that the vision loop's CI route runs on it; its token count adds an
image's tokens.

### 4.4 Settings: `inference.yaml`, properties and options

`InferenceSettings` (a record in `skillroy-scenario`, strings only, §5.5) is built from, in order of precedence, the CLI
options of §13.4, the system properties below, and the file `inference.yaml` found beside `credentials.yaml` (the same
lookup rule as P2 §4, the same strict reader; the file is optional and holds no secret, so it may be committed):

```yaml
version: 1
gateway: claude                 # claude | recorded | none; `replay` forces none (§5.5)
recordings: inference/          # for recorded: a directory, relative to the file; also where `record` writes
record: false                   # true: wrap the live gateway in RecordingGateway (the maintainer's re-record route; D110)
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
thresholds: {confidence: "0.6"}  # the tier (b) gates of §5.4 that are settings (0.8 until D113, 2026-10-08)
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

(Phase 6 security fix, pass 2, P2-L8-F1, built 2026-10-09, SF) **`inference.yaml` is read only when nobody but the
current user could have written it**, by the rule `telemetry.yaml` follows (P6 §10.3; `SettingsFiles` in
`skillroy-data`): a regular file, or a symbolic link of the current user's whose file is then judged; owned by the
current user or root; not writable by others, nor by its group unless the group is the owner's user-private group (ACL:
no write right for anybody but the current user, the system account and the administrators group); a volume without
permissions refused; the same file before and after the read. Others may read it (it holds no secret and may be
committed). Any entry of that name beside the credentials file (or in `<bindings>/<env>/`) is judged, and a refusal is
a settings error naming the file and the rule ("inference settings file <file> cannot be used: others may write to it
(…)"), never a silent skip. Until then a file another user planted there was read: it could set `recordings:` and
`record: true` (the masked prompts and answers of the operator's live calls written into a directory of its choosing),
the gateway, the budgets, the prices, `images` and `logPrompts`.

(Phase 7, built 2026-10-10, VM; P7 §7.3) `effort` gains the key
`vision` (default `low`, BS12): the vision loop's calls and its visual questions. `images` is the one switch for pixels
(Phase 7 turns it on: an image part reaches a provider only under `images: true`). The `inference-live` profile's
settings file `samples/skillroy-sample-lending-scenarios/src/test/resources/inference/inference-live.yaml` maps
`models.haiku` to `claude-haiku-5-5` for `VisionLiveIT`.

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

As built (2026-10-08, fix wave; D100): the names are checked against the schema where a data channel exists, not at
compile time (no connection exists then): `…inference.sql.AllowListCheck.check(list, data, rowMasks, timeout)` runs
`SELECT * FROM <table> WHERE 1 = 0` for every allow-listed table through the source and requires the result's column
names to be exactly the table's readable columns plus its `otherColumns`, compared without regard to case (the sources
fold unquoted names and report labels in their own case; two columns whose names differ only in case are refused), so
that the guard's rule 4, which trusts `otherColumns` to be complete, stays sound when a later release adds a column; an
unknown table (an SQL failure of the statement), a column the list does not name and a name the table does not have
are a `ContributionException` naming the table and the columns, never a value. Every readable column goes through the
pack's row masks with a sentinel of each value type a mask sees, under its declared name in three cases and the name
the source reports, and one a mask alters or fails on is refused. The tier (b) resolvers run the whole check once per
bind before the prompt catalogue is built (its statements are the bind's and are recorded, with the catalogue's
samples, in the provenance of the first tier (b) slot, so scenario D's bindings files show them); a failure ends that
slot `ENVIRONMENT` as a pack defect with the check's message, before any model call. The contributor runs the row-mask
half (`AllowListCheck.rowMasks`) when it is loaded (a `ContributionException`, `ENVIRONMENT`); the MCP server runs the
whole check at its start. That a key column is non-nullable needs the source's metadata, which a data channel does not
expose: it is not checked (the sample's `AllowListOnRealSchemaTest` checks it on the sample's schema).

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
violation on every run (BS6: each accept statement carries its expected rewrite, byte for byte). As built
(2026-10-08, I1, lifted from BS6): `SqlGuard.guard`, `guardDistinct` (the catalogue's sample reads), `served(dialect)`;
the per-dialect default functions are the date functions plus `CURRENT_TIMESTAMP` and `EXTRACT`; an allow-list table
declared as `schema.table` is matched by its table part; `otherColumns` let an unqualified name resolve outward only
when every inner relation's columns are fully known; the BS6 corpora run as the module's tests with and without
`otherColumns`. Its rewrite is textual
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

As built (2026-10-08, fix wave): rule 4's flag also follows a subquery's truth. A predicate subquery (`EXISTS`, `IN`,
`ANY`/`ALL`) or a scalar subquery inside a select-list expression or a derived column is tainted with every personal
column it references, in its select list, its `WHERE`, its joins, its own subqueries or correlated to the enclosing
query, so that `CASE WHEN EXISTS (SELECT 1 FROM staff s2 WHERE s2.code = s.code AND s2.name LIKE 'A%') THEN 1 ELSE 0
END`, its `IN` form and `(SELECT COUNT(*) FROM staff s2 WHERE … s2.name LIKE 'A%')` cannot carry one bit of a name per
row into a candidate (`PERSONAL`); the same subqueries as filters (`WHERE`, `JOIN … ON`, `GROUP BY`, `HAVING`,
`ORDER BY`, or a derived column that is only filtered on) are accepted as before, and every BS6 corpus statement keeps
its outcome. Rule 6's parameter clause is enforced: `GuardedStatement` gains `literals()` (the text of each string
literal, unescaped, in order), and the tier (b) resolver rejects `PARAM` a statement with a string literal equal to the
text of one of the query's scalar string arguments (strings only, so that `TRUE` or `1` never collides; a blank
argument is never matched), naming the argument and never the value.

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
3. **The gate**: `cannotAnswer` → no candidates, with the reason in `Provenance.notes`; `confidence` below the
   threshold (a setting `thresholds.confidence` of `inference.yaml`; 0.8 as ai-inference R13 recommended, **0.6 since
   D113**, 2026-10-08: Sonnet 5.5 at effort `low` reported 0.8, 0.8, 0.8 and 0.7 on four correct answers to the sample's
   supervisor query, so 0.8 refused one correct statement in four, and the verifier is the gate that decides) → no
   candidates, recorded as rejected `{resolver: inference.sql, label: "proposal", reason: "confidence 0.5 below 0.6"}`,
   which the binder's "no candidate" message names (the live session, 2026-10-08); the statement through
   `SqlGuard` → a violation gives no candidates with the reason code and the violating fragment (never the whole
   statement in a *message* a person did not ask for; the full statement goes into provenance, masked). Every `Param`
   must name an argument of the query (or a field of one) whose text equals the value the model wrote, so that a model
   cannot smuggle a literal through a parameter; a param that matches nothing is rejected (`PARAM`).
4. **Execution** through `ctx.data()` on the allow-list's source with the guarded statement and its parameters in the
   forms of P2 §7.1 (a `DATE`/`TIMESTAMP` param in the forms the channel accepts), under the slot's remaining budget;
   the rows pass the channel's masks as any read (P2 §7.3). The row count must lie in `expectedRows` (bounds the model
   stated, clamped to 1..200; a count outside is rejected "row count 0 outside 1..5"); the rows become candidates, at most
   `ctx.maxCandidates()` of them, in the statement's order. **How a row becomes a `T`** (D90, 2026-10-08, from the
   sample: a flat row cannot fill `StaffRef(code, permissions: List, limits: Map)`): when the pack registers a
   `ValueLoader` for the kind (§5.4a), the catalogue tells the model to select exactly the loader's identity columns, by
   name, and each row's identity values go to `load`, which builds the typed value with the pack's own committed reads
   (the same code a tier (a) resolver uses; a `ResolutionException` from it rejects that row with its reason, a
   `RuntimeException` is `ENVIRONMENT` as for any pack defect); the candidate's label is the identity values joined.
   Without a loader every component of a flat record is taken from a column by name and scalars go through the codec
   (a missing or extra column is rejected `SHAPE`); a list or map component without a loader is refused `SHAPE` with a
   message naming `ValueLoader`.
5. **Provenance**: the `Statement` record carries the guarded statement, the parameters (masked), the row count and
   the masked sample as for tier (a); `Provenance` gains a `rationale` component (the model's text, masked, at most
   2000 characters; null for tier (a)) and the injected clauses stand in the statement's text itself (the guard's
   rewrite is what ran), so that the bindings file, which keeps `notes` out as P2 §9 says (no dates, D47), still
   shows a reviewer why the statement was written; `Provenance.notes` carries the rest, for the run record only; the
   `Resolution` gets `confidence` from the proposal and `usage` from the ledger entries of the call (P2 §6.3's "where
   the Phase 5 fields get their values"); `Approval` is `REQUIRED` until a person approves (§7.1).

As built (2026-10-08, I1): the resolver ids are `inference.sql.<Kind>` (one per kind; the engine refuses two contributed
resolvers with one id); the engine owns `Provenance`, so the resolver cannot yet set `rationale` or `rejected` and
writes them as notes ("rationale: …", "rejected proposal: …" with the refused statement masked) until the engine gains
`ResolveContext.rationale(text)` and `reject(label, reason)` (the next wave); a provider failure is thrown as a
`DataException` of kind `UNAVAILABLE` or `TIMEOUT` on the source `inference` (a resolver cannot throw `BindException`),
which the engine reports as `ENVIRONMENT` with its own prefix; the catalogue is cached per bind and its sample
statements are recorded in the provenance of the first tier (b) slot; a loader's identity columns are named in the
user message, not in the cached catalogue; the estimate before a call is the catalogue's tokens plus 2000 in and 2048
out at the Sonnet price, without a cache discount; the contributor's mask knows the allow-lists, the pack's row masks
and the key rule but no credential value (a resolver never sees one); `SqlProposal.confidence` is a `double`, which
`RecordCodec` did not read at first, so `Structured.decode` handled `double`/`float` itself until wave 2c taught the
codec `Double`/`double` and `Float`/`float` as JSON numbers (P2 §6.2); `Structured` now adds nothing of its own
(corrected in the fix wave of 2026-10-08).

As built (2026-10-08, fix wave): step 3's parameter rule also refuses a value written inline: after the parameters
match, a string literal of the guarded statement that equals the text of one of the query's scalar string arguments
rejects the proposal `PARAM: the statement writes the value of the argument <path> as a literal …` (5.3 rule 6), and
nothing runs; the verifiers remain the gate on what does. A provider failure the resolver records is a ledger entry
(D102) that names the gateway's provider and the settings' Sonnet id, so that a slot whose first attempt failed still
names a model in its `Resolution.usage`; the `NO_RECORDING` message names the recording gateway (4.2).

A refusal or a `MAX_TOKENS` stop is "no candidates" with a note. An `InferenceException` of a retryable kind is tried
once more within the slot's budget, then the slot fails `ENVIRONMENT` ("the model provider could not be reached:
<kind>"); `AUTH` and `INVALID_REQUEST` fail at once the same way; `NO_RECORDING` as §4.2 says. The resolver never
calls a mutating primitive and never sees a credential (it is a resolver of P2 §6.4 like any other).

### 5.4a `ValueLoader` (`skillroy-scenario`, package `…scenario.resolve`)

```java
/** Completes a tier (b) candidate: the model's SQL selects the identity columns the pack names; the pack loads the
 *  typed value from them with its own committed reads, so that a value type with a list or a map can be found by a
 *  model without the model aggregating it (D90). Registered through ResolutionCatalogue.Builder.valueLoader and found
 *  by ResolutionCatalogue.valueLoader(kind); build() refuses a loader for an undeclared kind or two for one kind. */
public interface ValueLoader<Q extends Query<T>, T> {
    String kind();
    List<String> identityColumns();          // the columns a statement must select, by name; order free
    T load(Q query, Map<String,Object> identity, ResolveContext ctx)
            throws ResolutionException, DataException, AgentRpcException, AgentLostException, InterruptedException;
}
```

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

As built (2026-10-08, fix wave; D103): the planner (`plan`, 10.2) is the one purpose whose prompt is not built as a
`ModelRequest`: its prompt is the operator's brief, which is exempt from the mask (the operator's own words, typed at
the command line, neither read from the target nor holding the environment's secrets; only a tag that could close or
open its block is neutralised), and the pack's vocabulary (kinds and their argument schemas, oracle functions, fact
roots, stored tables, verbs and their schemas), which goes through the mask: every text by the key rule and the
bounds, and in a schema every description, default, example, enum and constant, a property whose name looks secret
losing its values whole while keeping its name. The planner reads no data, so its prompt carries no row and no sample.

(Phase 6 security fix S49, A50, built 2026-10-09, SR) The pack's vocabulary goes through the mask wherever a model
receives it: the planner's prompt, the session's system part and `skillroy_catalogue` result, and the projected tools
of the session and of the MCP server (`ToolProjection.project(primitives, pack, policy, PromptMask)` and
`ToolProjection.masked`, which `WorkflowTools.session(…, mask)` and `SkillroyMcpServer` use; `PlanPolicy`, which reads
names and flags only, keeps the unmasked projection), and a recording's `request.tools` (`Recording`, the fingerprint
staying the request's as sent). The one rule is `PromptMask.schema`: in a schema a property whose name looks secret
loses `default`, `examples`, `example`, `const` and `enum` whole, keeping its name and type, so that the schema stays
valid (the planner's walk used to write `<redacted>` in their place, which could make a strict schema invalid); every
text through the key rule and the bounds. As built: a projected tool is masked after its schema is made strict
(masking removes data keywords only, so the strict shape stays). The five committed session stand-ins of §14.5 were
regenerated (`-Dskillroy.inference.fixtures.update=true`, no live call; the exported scenario did not change): no
sample verb's schema holds a secret-looking property, but the key rule now rewrites the `signOn` and `authorise`
descriptions' "credential: {realm: staff, key: code}" to "credential: <redacted> staff, key: code}" in the tools as
it already did in the planner's prompt (D103), which changed the requests' fingerprints. A pack author who wants the
example kept writes it without a `key: value` shape after a secret word.

(Phase 6 security fix, pass 2, P2-L5-F1, P2-L5-S1, built 2026-10-09, SC) A secret-named property loses `default`,
`examples`, `example`, `const` and `enum` whole **in the property and in everything nested under it**: its
`properties`, its `patternProperties` and `dependentSchemas` (whose keys are asked as well: a pattern `^password` names
a secret property), its `items`, its branches, and a definition (`$defs`, `definitions`) that a local `$ref` under it
names or whose own name looks secret (a fixed point over the local references; a definition shared with a non-secret
property loses its data keywords for both). And a data keyword of a non-secret property is data, not a schema: its
value goes through the by-key and by-value rules as a JSON-like value (`PromptMask.jsonLike`), and **a data value
(`default`, `examples`, `example`, `enum`, `const`) that holds, at any depth, a key that looks secret is dropped
whole** (`default: {staff: ann, password: …}`), never pinned to `<redacted>`, so the schema stays valid. The one rule
serves every site of the paragraph above. The sample's schemas have none of these shapes, so no prompt and no
recording of the sample changes.

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
  (Phase 6 security fix, pass 2, P2-L6-F2, P2-L6-F3, P2-L6-S1, built 2026-10-09, SC) A capture's bytes inside a text
  or a value (a map's base64 `base64` entry, a PNG's base64 run) are elided to `<png: N bytes>` by `text` and
  `jsonLike` whatever `images` says (§9.4's rule, `…scenario.Images`): an image reaches a model as an image part or
  not at all.
- *Bounded.* A text part is ~~cut at 100000 characters and a rows sample at 20 rows before masking~~ **masked, then**
  cut at 100000 characters (and a rows sample at 20 rows) with `[truncated]` appended, so that a runaway snapshot cannot
  carry a secret past the mask by size (Phase 6 security fix S53, A53, built 2026-10-09, SR; the build's margin of 1000
  characters past the cut is replaced by masking the whole text first, since a longer secret straddling the cut kept
  its head). As built: the overlay bridge (§12.4) no longer cuts a request before the mask either: it keeps a request's
  text whole up to `OverlayBridge.MAX_QUEUED_TEXT` (100000) and asks with `SessionService.ask(text, MAX_TEXT)`, which
  masks the whole text and then cuts it at the bridge's 10000 characters; a longer request is answered "the request is
  longer than 100000 characters; shorten it" without a model call (D209).

The mask is tested with the project's existing adversarial fixtures (short and long secrets, quoted secrets, secrets
inside JSON strings, the key patterns) and with the sample's `password` column; a test proves that a prompt built for
every purpose of this contract contains none of the environment's credential values and none of the sample's
`personal` values.

As built (2026-10-08, fix wave): the session's and the summary's requests are proven masked through the service's own
builder (`SessionServiceTest`: values only the key rule catches, under a secret-looking key and in `key=value` runs,
fed through the facts, the snapshot and the events the model reads, the person's text and a folded summary, and a
credential inside a text, reach no part of any request, the summary call's included; `OverlayBridgeTest` does the same
for an overlay's text), the planner's vocabulary in `PlannerTest` (D103), the verbs' schemas included (Phase 6
security fix S49, A50, built 2026-10-09, SR: `SessionServiceTest`, `RecordingTest`, the MCP `ToolListTest`), triage's
request in `TriageTest`. Three
limits are recorded. The module key patterns of A21 cannot reach the controller (no wire type carries them, and the
agent applies them to its own outputs before anything leaves the target): `PromptMask.withKeys` is the seam a later
amendment fills, and no caller uses it now. The tier (b) contributor's mask knows the allow-lists, the row masks and
the key rule but no credential value: nothing in a contributor's context offers the credentials' `SecretMask`
(`ResolveContext.credentials()` answers only whether an entry exists, and adding it would be a new seam across D70),
and a resolver never sees a credential (the binder refuses a credential reference in a query's arguments), so the
by-value rule has nothing to replace there. An `Opaque` part (a thinking block with its signature, a fallback block)
passes the mask untouched and is recorded as received: it is the model's own output, built from masked input, and must
be echoed back unchanged (3.2); `RecordedGatewayTest` round-trips one.

(Phase 7, built 2026-10-10, VM; P7 §6.4, 7.2, 9.2) An image part passes under
`withImages(true)` (redacted by the agent before it leaves the JVM), its `base64` never masked and never on a text route;
a tool result's parts list is masked part by part; `LABELS` gains `model-question` (the model's own question to a visual
check, declared data like the others); `holdsSecret(text)` says whether a text holds a secret value the mask knows (what
the vision recorder and the session facade refuse to type, D342). The session's vocabulary names no secret field of a
slot (its `$reference` sentence, D342), and a tool set's instructions and the pack's screen legend are masked like it.

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
    public BigDecimal cost(Usage usage);                 // 6 decimals, HALF_UP, from the usage's own cache-write split; a dated id (a known id followed by -YYYYMMDD, as the provider names Haiku 4.5's snapshot; BS13 live) is priced as the known id; an unknown answering model costs at the OPUS_CLASS price and is noted
}
```

(Phase 6 security fix S52, A52, built 2026-10-09, SR) A dated id is priced as the known id; an unknown **answering**
model costs at the `OPUS_CLASS` price and is noted; **a configured model must be in the effective table, else the
gateway refuses to open** (`INVALID_REQUEST`: "model <id> is not in the price table; name it under `prices:` with its
price"), so that a configured id is never under-counted. The owner confirmed the refusal on 2026-10-10 (B27, no veto);
the alternative, a warning on stderr at `open`, was not taken (a configuration that worked before is now refused).

The built-in table, from the published rates of ai-inference F10 (USD per million tokens; to be re-checked when built,
and replaced by the file when they move):

| Model | Input | Output | Cache read | Cache write (5 min / 1 h) | Batch |
|---|---|---|---|---|---|
| `claude-opus-5-5` | 4.00 | 20.00 | 0.20 | ×1.25 / ×2 | ×0.5 |
| `claude-sonnet-5-5` | 2.00 | 10.00 | 0.20 | ×1.25 / ×2 | ×0.5 |
| `claude-haiku-4-5` | 1.00 | 5.00 | 0.10 | ×1.25 / ×2 | ×0.5 |
| `claude-sonnet-5` (Sonnet 5.5's documented fallback target, BS13 F-7) | 2.00 | 10.00 | 0.20 | ×1.25 / ×2 | ×0.5 |
| `claude-opus-4-8` (an Opus-line fallback target) | 5.00 | 25.00 | 0.50 | ×1.25 / ×2 | ×0.5 |
| `claude-opus-5` (the other Opus-line fallback target the models endpoint names for Opus 5.5; BS13 live, 2026-10-08) | 5.00 | 25.00 | 0.50 | ×1.25 / ×2 | ×0.5 |

Scopes and limits: **per scenario** (`budget.scenarioUsd`, default 0.50, B6), one meter per bind, created by the
contributor; **per job** (`budget.jobUsd`, no default: `re-resolve` refuses to start without it, B6); **per session**
(`budget.sessionUsd`, default 1.00, B15, "a starting value to be replaced by the measurements of BS12"). A limit of `0`
means "no model call at all" and is what `replay` would have if it consulted a meter; it does not, because the engine
never loads the contributor there (§5.5). Exhaustion: a resolver's `BudgetException` is `ResolutionException(BUDGET)`,
so the slot fails `RESOLUTION_FAILED` reason `BUDGET` with `{scope, id, limitUsd, spentUsd, estimateUsd}` in the
detail (Q13); in a session the turn ends with `budget: exhausted` and the service refuses further model calls (the
person may still act, §12.4); in the nightly job the remaining scenarios are reported `BUDGET` and no further batch is
submitted (§8.3). The `--json` of every command and the run summary print `model cost: $0.0231 (budget $0.50)`.

(Phase 7, built 2026-10-10, VM; P7 §7.3, 7.5) The built-in table
gains `claude-haiku-5-5` at USD 0.10 / 0.50, cache read 0.01 (its prompt tier above 100K input tokens, 0.50 / 2.50, is
not modelled: `PriceTable.tierNote`); the Haiku class's default stays `claude-haiku-4-5` (D84). The session's estimate
fallback adds `ceil(w / 28) * ceil(h / 28)` tokens per image.

### 7.3 The ledger writer and `CostLedger.Entry`

`CostLedger.Entry` gains `String purpose`, `long cacheReadTokens`, `long cacheWriteTokens`, `boolean batch` and `String
note`; the Phase 2 nine-argument constructor stays (purpose `"resolve"`, zeros, `false`, `null`). `cost-ledger.jsonl`
writes the new fields after the old ones. As built and amended (2026-10-08, I1; D91): the contributor is never told a run's output directory, so the record of
§7.4 travels with the ledger: `CostLedger.Entry` gains a `Map<String,Object> details` component (the fingerprint, the
stop reason, the elapsed time, the requested model, the cache-write split and the masked structured answer; null when
there is none; the earlier constructors keep it null), and the run writer that writes `cost-ledger.jsonl` writes
`inference.jsonl` from the same entries (one record per entry that has details). `…inference.budget.LedgerWriter` is
what every caller of a gateway uses:
`Usage record(ModelRequest, ModelResponse, String scenarioId, String slot, Strategy strategy)` builds the entry from
the response's usage, records it, settles the meter and appends the record of §7.4. A `RecordedGateway` call is
recorded with provider `recorded` and cost `0`, so that a `live`/`approve` run against recordings still shows its
calls (`model calls: 2`), while `replay` has none. The JUnit stream's trailing `skillroy: zero model calls` test and the
executor's ledger check are unchanged and keep meaning "zero gateway calls of any provider".

(Phase 7, built 2026-10-10, VM; P7 §7.3, 10) `CostLedger.Entry.purpose` gains
`vision` (`PURPOSE_VISION`): every vision call goes through `SessionService.visionCall` (the facade's `complete`, the
player's `aiExpect`), metered on the session meter before and ledgered after, a failed call too; a logged prompt holds an
image by its hash.

### 7.4 `inference.jsonl`

Per run (beside `cost-ledger.jsonl`), and per `re-resolve` job, `triage` and `session` output directory: one masked
record per gateway call, `{type: "inference", t, purpose, scenarioId?, slot?, provider, requestedModel, model,
requestId, fingerprint, stop, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, batch, costUsd, elapsedMs,
structured? (the masked structured answer), note?}`; with `logPrompts: true` also `system` and `messages` (masked
parts) and the response `parts`. A refusal's category is in `note`. The file is evidence of what the model was asked
and answered, bounded to what the mask allows; P2 §11.4's "never written" list gains "a prompt that was not masked, a
model key, a response to a request that named a secret".

As built (2026-10-08, fix wave; D102): a failed call has its record too. `LedgerWriter.recordFailure` records every
failure as a `CostLedger.Entry` whose details are its record (`error` the kind, `note` the masked message, `stop`
null): with the usage the provider billed when the failure carries it, else with zero usage, cost `"0"`, the
gateway's provider and the model that was asked (`recordFailure(request, failure, provider, model, …)`; the resolver,
the session service and triage pass them) and the entry's note `failed: <kind>`, so that the run's writer, which sees
only the ledger (D91), writes a line for a `NO_RECORDING`, a `TIMEOUT`, an `AUTH` or a 5xx too. A run's `model calls`
count and its ledger therefore count failed calls; their cost is 0 unless billed, and `replay` still makes none.
`InferenceLog.forLedger(ledger)` is public (a static method of a public interface), so that the CLI session and the MCP
server write the records of their binds' tier (b) calls beside their own.

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
   `cost-ledger.jsonl` and `inference.jsonl` are written beside it. (Phase 6 security fix S50, built 2026-10-09, SR; no
   amendment number) Model- and binder-origin texts are rendered as code: a failed scenario's reason and message and
   every note are inline code (a backtick replaced, one line, at most 1000 characters), as the changed and new lines
   already were, so that a link or an image a model steered into them is inert in the pull request; `triage.md`
   (§11.2) likewise writes the label's line and each `lookAt` item as inline code and the summary and the proposal in
   a fenced block one backtick longer than the longest backtick run of the text.
6. **Exit codes**: 0 when every scenario resolved (changed or not); 1 when any scenario failed (the report says which;
   files of the others are still written); 2, 3, 64, 70 as `resolve`.

As built (2026-10-08, I2, `…inference.job`): `ReResolver.run(plans, pack, agent, options, JobOptions)` (and an overload
taking a gateway the caller owns), `JobOptions(outDir, jobId, jobBudgetUsd, dryRun, approvedBy, batchWait, batchPoll,
clock)` with `approvalLabel()` = `re-resolve:<jobId>` unless `approvedBy` is given, `ReResolveReport` (a record with the
per-scenario `ScenarioReport`s, the statuses `UNCHANGED` (byte-identical file), `CHANGED` (the `BindingsDiff` lines plus
approval, rationale and confidence lines), `NEW`, `FAILED` and `SKIPPED` (needs no bindings), the cost, the budget, the
rounds, batches and requests, the cache-hit ratio, the models; `toMarkdown`, `toJson`, `writeTo(outDir)` for the two
report files, the job's ledger and `inference.jsonl`; `exitCode()`), `ReResolveException` (`beforeStart()` for no budget,
gateway `none`, a gateway that cannot open or two plans with one id, which the CLI reports as exit 64; otherwise the
report could not be written, exit 70). The pre-flight runs once; `requires` and the launch properties are the CLI's
check. When a round's estimate does not fit the job budget, the requests that fit (from the front) are submitted,
the rest are `BUDGET`, and no later round submits a batch; the job meter is settled from the ledger entries, so a
resolver's inline retry counts; a failed submit or collect is handed to each resolver (`ENVIRONMENT` or one inline
retry); a gateway without `BATCH` answers request by request and the report notes it. A nightly label changes every
file's `approval.by` each night, so the sample workflow passes a stable `--approved-by re-resolve:nightly`.

A pull request is opened by the pipeline, not by the base: `ci/examples/nightly-re-resolve.yml` is a GitHub Actions
workflow that runs the command in the Linux image on a schedule, with the key from the repository's secrets, and
opens the pull request with the report as its body when `git status` shows changed bindings files. It is an example,
not run by this repository's own CI. **(Phase 6 security fix S40, A43, built 2026-10-09, SR)** the example persists
no checkout token into the work tree the container mounts (`persist-credentials: false`; the push step sets up git
with `gh auth setup-git` under its own `GH_TOKEN`), and names the credentials secret `SKILLROY_CREDENTIALS_YAML` (the
base's own variable `SKILLROY_CREDENTIALS` is a path, the secret is the file's content); comments say that `--pid` and
`--discovery-dir` do not reach a host JVM from inside the container (use `--port` with a `--token-file` under a
mounted path) and that a copy should pin the base image by digest and the actions by commit SHA. The pinning itself
is not made in the example: one of the five low parts the owner accepted (P6 §12.2, §17 Q22), with the release note
"pin the image and the actions in your copy of `ci/examples/nightly-re-resolve.yml`".

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

As built (2026-10-08, fix wave): the bound holds with the inline retries too. A failed submission, status read or
collection answers every request of the round with the failure, and a resolver retries a retryable one inline at the
regular rate; before a bind resumes with such an answer, the job meter now reserves that call's estimate (the
gateway's token count, else text length / 4, plus `maxTokens`, at the regular rate), releases it when the bind
returns and settles the ledger entries the retry added, so that no retry runs on the resolver's scenario meter alone
after the round's reservation was released; when the job meter has no room, the scenario fails `RESOLUTION_FAILED` /
`BUDGET` without the call and no later batch is submitted (`budgetCut`).

The exit criterion's reading (2026-10-08, the live session; D111): the ratio is measured over a job's ledger, so a job
with one tier (b) request and a cold cache reads nothing (its one request writes the prefix) and reports 0 %, however
well the prefix caches; the criterion speaks of a job whose requests share the prefix, where every request after the
first reads it. The live acceptance (§15.3 item 4) therefore runs the sample's one-request job twice within the hour
and reads the ratio of the second, which is what the second and later requests of a larger job see; the figures of
both runs are in `../TODO.md`.

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

(Phase 6 security fix S57, A56, built 2026-10-09, SR) `--client <label>` (a short name, `[A-Za-z0-9._-]{1,64}`, default
`mcp`): the label of the server's origin, `Origin.client(<label>)` (§12.3), under which the journal and the export name
the client's steps (`client <label>`); `skillroy author` passes `--client planner` (§10.2). `--session-budget`'s help
says that it bounds `skillroy_session_ask`, `skillroy_resolve` and `skillroy_scenario_run` together (S56, A55).

(Phase 7, built 2026-10-10, VM; P7 §9.4) `ServerOptions` gains `vision` (the tool options of
`skillroy_session_ask`'s actor, from `skillroy mcp --vision`; empty is off) and `robot` (`--allow-robot`, V3 inside
`skillroy_session_ask` only; `v3` without it is refused); the server sets the session's robot flag and, when the vision
module is present, a gateway is named and `images: true`, its host features (`vision-session`, `vision-tier:<tier>`)
before it opens.

### 9.3 Tools

Names use `[a-z0-9_]` only (the Anthropic API forbids dots, control-channel F22), at most 64 characters: a primitive
`a.b.c` becomes `a_b_c`; a verb `signOn` of pack `lending` becomes `lending_sign_on`; a workflow tool is `skillroy_*`.
A collision is a start-up error. Every tool carries MCP annotations: `readOnlyHint` from the descriptor's `mutating`
(false → read-only) **and `script` (Phase 6 security fix S42, A44, built 2026-10-09, SR; P6 D156: a `script`
primitive, or one of the named `SCRIPT_PRIMITIVES` of an older agent's catalogue without the flag, is never
`readOnlyHint: true`; `web_wait_flag` is offered by a read-only server only with `--allow-evaluate`, D98 unchanged)**, `destructiveHint` from `destructive`, `idempotentHint` false, `openWorldHint` false; its input
schema is the descriptor's `paramsSchema` (less `_meta`) or the verb's `argsSchema`; its description is the
descriptor's, prefixed with the thread and timeout. Results are text content: the call's `value` as pretty JSON,
masked, cut at 25000 characters with `[truncated: N more]` (control-channel F19); a result that holds an image (a
capture, a bitmap) is written to `<out>/mcp/<id>.png` and returned as a resource link (Phase 6 security fix S58, A57,
built 2026-10-09, SR; D159: a link to the PNG only under `images: true`, else to a text resource naming the file, §9.4).
(Phase 6 security fix, pass 2, P2-L6-F3, built 2026-10-09, SC) This holds for **any tool's result that holds an image,
at any depth**, not a step's value only and not only to eight levels: a fact (`skillroy_facts`), an event's payload
(`skillroy_events`), a resolution, a check's `actual`, an observed value, a step's `detail` and events, a run's summary
(`ToolCalls.reply` links every image before the masks); an image the server cannot decode, and anything a link did not
replace, has its bytes elided by the mask (§9.4), so no tool answers a capture's bytes as text.

| Group | Tools | Offered |
|---|---|---|
| Pack verbs | `<domain>_<verb>` for every verb of the pack's `StepLibrary` with its `argsSchema` (§9.5); the call compiles the verb and executes its calls through `ExecutorSession.execute(verb, args)` (§12.2), so the step protocol, the prompt policy and the journal apply | mutating verbs only with `--mutating` |
| Domain primitives | `<domain>_…` for the domain module's primitives (`lending_access_check`, `lending_deposit`, …) | by `mutating`/`destructive` as below |
| Base primitives | `jvm_*`, `app_*`, `swing_*`, `web_*`, `sync_*`, `data_query` | the reads always; mutating with `--mutating`, but `web.evaluate` and `web.waitFlag` (each runs a caller's script in the target's pages) only when a person adds `--allow-evaluate` (D98; `web_evaluate` with `--mutating` as well); `data_query` only with the source's allow-list and through `SqlGuard` (the model's SQL is guarded even here), its guard reading the allow-list less its personal and row-masked columns (D99) |
| Workflow | `skillroy_catalogue` (verbs with schemas, query kinds with their argument records, oracle functions, capability states); `skillroy_snapshot` (the `UiSnapshot` text, `maxChars`); `skillroy_facts {root, path?}`; `skillroy_events {sinceSeq?, topics?, max?}`; `skillroy_resolve {slot, kind, args, strategy?}`; `skillroy_scenario_compile {yaml}` (problems or the plan summary); `skillroy_scenario_run {yaml, mode: live\|approve}` (`--mutating`; refused unless every call of the scenario is one the server offers, D96; runs with recover; the `RunResult` summary); `skillroy_session_ask {request}`, `skillroy_session_check {path, matcher}`, `skillroy_session_observe {path}`, `skillroy_session_note {text}`, `skillroy_session_export {scenarioId}` (§12; no approver over MCP, D97) | `skillroy_scenario_run` and `skillroy_session_ask` need `--mutating` unless the session policy is read-only |

A collision between a verb and a domain primitive (the sample's verb `deposit` and its read primitive
`lending.deposit`) is resolved deterministically (D92, 2026-10-08): the primitive's tool name takes the suffix
`_primitive` (`lending_deposit_primitive`), the verb keeps the plain name, both are listed, the renaming is logged at
start and `skillroy_catalogue` reports it under `renamedPrimitives`; every other collision refuses the start.

Never offered: a destructive primitive (a person runs it with `skillroy call --confirm`); `agent.disable`,
`agent.capabilities {reprobe}`, module upload, hook installation; `data.query` on a source without an allow-list;
`web.evaluate` and `web.waitFlag` unless a person started the server with `--allow-evaluate` (D98); a pack verb whose
calls reach any of these (D96); anything with `confirm: true` set by the server on a model's behalf (design §3.4: the
model's request is never the confirmation). In read-only mode (the default) a mutating tool is not listed at all, so a
client cannot even ask.

(Phase 6, built 2026-10-09, CP; P6 §4.2) "Hook installation" is widened and named: `ToolProjection.NEVER_OFFERED`
(`hook.probe`, `hook.install`, `hook.stub`, `hook.remove`, `rule.check`, `rule.submit`, `rule.unload`) is offered by no
policy whatever the flags (`hook.install` and `hook.remove` are not mutating, `hook.probe` is a read), and
`PlanPolicy` refuses every `hook.*` and `rule.*` primitive in a client's plan ("never run for a client") except the
three reads of `ToolProjection.HOOK_READS`, `hook.health`, `hook.events` and `rule.list`, which every policy offers as
reads, the session service's included (D187). The pack's catalogue is installed by the server itself at its start
under `--hooks` (P6 §6.1), through its session's open; the server reads the matrix again after it and writes
`capabilities.json` into its `--out`.

As built (2026-10-08, I1, `…inference.tools.ToolProjection` and `WorkflowTools`): every verb counts as mutating (a
verb has no mutating flag); a session never offers `web.evaluate`; a verb's optional keys become nullable in the strict
tool schema, numeric bounds and lengths are dropped and `required` lists the schema's own required keys first, then
the rest by name, so that fingerprints stay stable; the scenario schema keeps the verb's original `argsSchema`.

As built (2026-10-08, M; the MCP Java SDK's `McpServer.sync` on a `StdioServerTransportProvider`, `SyncToolSpecification`
with `ToolAnnotations`, `SyncResourceSpecification`, the Jackson 2 JSON mapper and schema validator built explicitly):
`SkillroyMcpServer.serve(ServerOptions, in, out[, log]) → int` (0 when the client closed the stream, 3 when the agent
was lost), `start`/`awaitExit`/`agentLost`/`close`, `ServerOptions` with its builder (a null `outDir` gives a kept
temporary directory), the `SessionActor`/`SessionActorFactory` seam through which the CLI wires `SessionService`
(`ask` declares the agent-loss and interruption exceptions); one `ExecutorSession` for the server's life, read-only
unless mutating; the server strips `confirm` and `_meta` from every argument a client sends (a client is often a
model), uses `SETTLE` for a mutating `swing.*` primitive called directly and `NONE` otherwise, drops null optional
keys (the strict schemas made them nullable) and leaves the SDK's own input validation off; `data_query` runs only
on a source with an allow-list, through `SqlGuard` and `PromptMask.rows`; images are resources `skillroy://mcp/<n>.png`;
runs live under `<out>/runs/run-<n>/`, exports directly under `<out>`, and of a run's `failure/` files only `.json`,
`.jsonl`, `.txt` and `.png` are served (the mask cannot read a trace zip); a `readOnly` flag on a resource travels in
its `_meta` (MCP resource annotations have none); `skillroy_scenario_run` in `approve` uses the session's approver
(`REJECT_ALL` by default, so the server never approves on a client's behalf); calls are serialised on the session;
the stdin bridge lets the SDK see the end of its input only once every request has been answered (the SDK's stdio
transport stops writing the moment its input ends); a lost agent while idle is noticed at the next call unless the
host calls `agentLost` (the CLI watches the event stream); `slf4j-api` finds no provider and prints its notice once
unless the distribution adds `slf4j-nop`. Deferred loading of the low-level tools is the client's business (Claude
Code's tool search), not a field of the protocol. Open: a read primitive called directly is journaled as a step and
exported as a `call` step (the export could leave read-only raw calls out); the prompts a `skillroy_scenario_run`
handled are unknown to the session's prompt cursor (a seam on `ExecutorSession`).

(Phase 6 security fix S55, A54, built 2026-10-09, SR) `sql` and `params` are literal: a `$reference` anywhere in
`sql`, or in a `params` string at any depth, is refused as a tool error that names the rule ("`data_query` takes literal
SQL and literal parameter values; a slot's field (`$slot.field`) is not substituted here"), never a value, before the
guard runs; and the call runs with the executor's reference substitution off (`PlannedCall.literalArgs`, §12.2), so
that the statement the agent receives is byte-equal to the guarded text (a `$$` or a `$` that starts no reference
reaches the agent as written). The executor substitutes references in the arguments of every other call after the
guard would have run; a resolved value is target content (`threat-model.md` §5). As built: a reference is recognised by
its syntax (`References.containsReference`, `$$` excluded), whether or not a slot of that name exists, so `'$notaslot'`
is refused as well (D208).

(Phase 6 security fix S56, A55, built 2026-10-09, SR; D157) `skillroy_scenario_run` is metered on the session's budget
as `skillroy_resolve` is: one tier (b) estimate per slot whose strategy names a model is reserved on the server's
session meter first (refused `BUDGET`, with the meter's figures (`refused`, `scope`, `id`, `limitUsd`, `spentUsd`,
`estimateUsd`) and no model call, when there is no room or the session is exhausted; a refused reservation exhausts it,
D74), the run executes under a cap of the session's room keyed by the server's ledger (`BudgetCaps`), the entries it
added are settled afterwards and a `budget` record journaled. The server owns the meter (`ServerOptions.sessionMeter`,
built by `skillroy mcp` from `budget.sessionUsd` and the session's ledger when a gateway is named, and handed to the
`skillroy_session_ask` actor), so that the three tools share one meter and one exhausted state
(`BudgetMeter.markExhausted`/`exhausted`); a `skillroy_resolve` without an actor that names a model is metered on it the
same way. As built: the estimate and the settlement are `SessionMetering`'s (`…inference.budget`), which
`SessionService.resolve` uses too.

(Phase 6 security fix, pass 2, P2-L6-F1, built 2026-10-09, SC) `skillroy_scenario_run` runs under the server's
`--hooks` switch (`RunOptions.withHooks(session.options().hooks())`, the switch the server's own session was opened
with, P6 §6.1), never the run default `auto`: a client cannot install, replace or remove hooks through a run; a
`--hooks off` server's runs install nothing and leave an edition already on the target alone (no `replace`, so no
`hook.remove` of everything first); under `auto` or `on` a run finds the server's own install (`alreadyInstalled`).
A client's run re-applies the server's switch: to change a target's hooks while a `--hooks auto|on` server runs
(`skillroy hooks remove`, or `install` of another edition), restart the server, or start it with `--hooks off`.

As built (2026-10-08, fix wave; `…mcp.PlanPolicy`, `DataQueryLists`, `StdioPipe`): a scenario
is never stronger than the tools the same server offers one by one (D96). `skillroy_scenario_run` compiles the YAML
and checks the plan before anything runs: a call of a base verb (`call`, the UI, web and wait verbs, `mark`) must name
a primitive the server's own projection offers, other than `data.query` (the `data_query` tool is the guarded way to
read), or `trace.mark` (the base verb `mark`'s marker on the agent's own bus); a call a pack verb compiled, and a
cleanup call, must name none the server never offers (a destructive one, `agent.*`, `data.query`, and what the policy
excludes, `web.evaluate` and `web.waitFlag` unless opted in), since a pack's verbs are reviewed code over any module's
primitives; no call's arguments may hold `confirm` or `_meta`. A refusal is a tool error that names each step (id,
verb, line) or cleanup call and its primitive, never a value. The same rule applies to the verb tools: at start the
server compiles each verb the policy would offer, alone, with probe arguments (an empty mapping, then placeholders for
its schema's required keys, then for all of them), and does not offer a verb a probe of which compiles to a call or a
cleanup call that reaches what the server never offers or carries `confirm` or `_meta`; the withheld verbs are logged
once and listed by `skillroy_catalogue` under `withheldVerbs` (verb, tool, reason). (Phase 6 security fix S57, A56,
built 2026-10-09, SR; this replaces "its tool's origin is `person("mcp")`, so a verb's own `confirm` would be
honoured" and "a verb that compiles with no probe is offered; one whose arguments choose other primitives is judged by
the probes only") A verb tool's step is compiled with the client's arguments (`ExecutorSession.compile`), and its calls
and cleanup calls are checked by the same rule (`PlanPolicy.packRefusals`, over `CallPolicy`) before the first call
runs (a refusal is a tool error naming each primitive, never a value; nothing runs or is journaled); the probes at start
decide only the catalogue's `withheldVerbs`. The server's origin is `client(<label>)` (`--client`, default `mcp`;
`author` passes `planner`): a client's request is never a confirmation, so `confirm` and `_meta` are stripped from its
arguments and from every call a verb compiles to, and a verb's own `confirm` fails `CONFIRM_REQUIRED`. A scenario run
for a client evaluates every
expectation on masked values (the executor's `RunOptions.withExpectationsOnMaskedValues(true)`, as a session check
does), so that PASS or FAIL is no oracle on a credential. `skillroy://schema/scenario` lets the base verb `call` name
only what the server offers (`ScenarioSchema.generate(pack, primitives, callable)`; the two-argument form keeps the
whole catalogue). A pack verb's tool now strips `_meta` as well as `confirm` from the client's arguments (top level and
a nested `args`), as the note above says. `skillroy_session_export` takes no approver (D97): a model-found binding is
always written `REQUIRED`, an `approvedBy` sent anyway is ignored and the report says so, and a person approves with
`skillroy resolve`, which reuses the recorded value without a model call (D82). (Phase 6 security fix S61, A59, built
2026-10-09, SR) It refuses an id whose files exist in `<out>` (`<id>.yaml`, `plan/<id>.plan.json` or the bindings file:
"`<file>` exists; export under another id", nothing written) and creates each file new (`CREATE_NEW`), as `author`
does; the CLI's `session export` (a person's) may overwrite. `web.evaluate` and `web.waitFlag`
(`ToolProjection.SCRIPT_PRIMITIVES`) are excluded by `Policy.readOnly()`, `Policy.mutating()` and
`Policy.session(…)`; `readOnly(true)` offers `web_wait_flag` and `mutating(true)` both, through
`ServerOptions.allowEvaluate` (`skillroy mcp --allow-evaluate`, D98); the plan check refuses either, the base verb
`webWaitFlag` included. `data_query`'s guard reads a copy of
each allow-list in which every personal column, and every column one of the pack's row masks alters (probed with a
text, a whole-number, a decimal and a boolean sentinel under the declared, upper- and lower-case names; a mask that
throws counts), is one of the table's `otherColumns`, so that a statement may not name it anywhere (`COLUMN`); a table
whose key holds such a column, or with no readable column left, is not readable here (D99); the catalogue's
`dataQuerySources` lists the copies. A row mask whose effect depends on the value is not found by the probe; the agent
still masks its values in the rows. `skillroy_resolve` goes through the actor's `resolve` when the server has an actor
factory (the actor is made on first use; the CLI's actor is the session service, whose metered entry counts a model
token on the session's budget, §13.4), and resolves on the session otherwise. The stdin bridge takes a line of at most
8 MiB (its newline not counted): a longer one is a protocol error, the reader stops without handing it on, the calls
read before it are answered and the server ends with exit code 1 (`EXIT_PROTOCOL_ERROR`); at most 16 MiB of lines wait
for the transport (one longer line alone), beyond which the reader waits and the client's writes wait in its pipe
(back-pressure; nothing read is dropped but the line held when the server ends the transport's input).

As built (2026-10-08, fix wave, the reconciliation; `SkillroyMcpServer`, `…inference.sql.AllowListCheck`): when the
target offers `data.query`, the server runs `AllowListCheck.check` on each allow-list of the pack at its start, before
it builds the `data_query` copies and projects the tools (§5.2, D100), through the data channel the session's binds use
and within a session bind's budget in all (`Plan.DEFAULT_BIND_TIMEOUT_MS`, the one a tier (b) bind's check and
catalogue share). A list naming a table the source cannot read, whose columns are not exactly the table's, or that lets
a model read a column a row mask alters refuses the start (`SessionOpenException` `ENVIRONMENT`, naming the table and
the columns, never a value), so a pack whose list allow-lists a row-masked column serves no `data_query` at all (P2
§4's rule); the copy of D99 still hides such columns, a second wall for a mask the check's sentinels do not move. A
source the check cannot reach (not served, timed out) is not read by `data_query`, whose refusal and the server's log
say why, and the server starts (D104); the check's statements are not journaled (the session is not open yet).

As built (2026-10-08, the Linux matrix's MCP timeout; `…mcp.SerialSends`): every message to the client is handed to
the SDK's transport one at a time. The SDK's stdio transport puts each outgoing message into a Reactor sink that refuses
a thread while another is still emitting: the refused message is dropped ("Failed to enqueue message", logged nowhere
without an SLF4J provider), and a refused answer also ends the transport's handling of the client, which has no error
path: it stops reading and writing while the process stays up, so the call and every later one wait for the client's
timeout. The server sends from more than one thread: a call's answer and the `notifications/resources/list_changed` of
a run's resources from the call's thread, the answers to `initialize`, `ping`, `tools/list` and `resources/list` (which
a client sends on that notification) from the transport's reading thread; under load even strictly alternating calls
overlap, since the emitting thread lets go of the sink only after its message is on the way. The transport the server
hands the SDK subscribes each send under one lock (the SDK emits on the subscribing thread once its transport has
started, which it has before the client's first message is read) and signals the send's end outside it.

(Phase 7, built 2026-10-10, VM; P7 §9.4) A raw tool's arguments meet `CallPolicy.arguments(primitive, args, false)`
after the `confirm`/`_meta` stripping: `redact: false` and `mode: ROBOT` on `swing_capture`, `force` on the five acting
primitives and `via: ROBOT` on `swing_pointer` are refused, never stripped, whatever `--allow-robot` says;
`skillroy_catalogue` shows the session's merged matrix with each entry's `source`; `skillroy_scenario_run`'s
`PlanPolicy` refuses `aiAct` and `aiExpect` steps ("run through skillroy session --play"), a person's arguments in a
client's plan (`via: ROBOT`, `force`, …) and the credential rule's plan form (a base verb's or raw call's reference, and a
pack verb's arguments as the client wrote them, to a field of a credential slot its fixture does not provide). The new raw
tools follow the snake-case rule (`swing_hit_test`, `swing_rendering`, `web_hit_test` reads; `swing_pointer`,
`web_pointer` under `--mutating`).

### 9.4 Resources

`skillroy://env/profile` (pack id and version, environment id, the capability matrix, the target's Java and pid);
`skillroy://state/current` (the `app.state` fact, masked); `skillroy://events/recent` (the last 200 events, masked);
`skillroy://schema/primitives` (the descriptors); `skillroy://schema/scenario` (the scenario JSON schema of §10.2 for
this pack); `skillroy://run/<id>/run.jsonl`, `skillroy://run/<id>/journal.jsonl` and `skillroy://run/<id>/failure/<file>`
for runs and sessions the server started. Resources are listed with `tools/list`-style annotations (`readOnly`) and
served as text, or as `image/png` for a capture.

(Phase 6 security fix S58, A57, built 2026-10-09, SR; D159) A capture's bytes (`skillroy://mcp/<n>.png`,
`skillroy://run/<id>/failure/*.png`) are served only when the server's inference settings set `images: true`, the same
opt-in that lets an image part reach a model (D84; null settings count as `false`); otherwise the file is written and a
text resource (`skillroy://mcp/<n>.png.txt`, `text/plain`: "capture written to <path>; served over MCP only with
inference.yaml images: true (a capture is personal data until masked, design 3.10)") names its path for the person to
open, the tool's resource link names that text, and a run's `failure/*.png` is not listed. The planner's server
(`--inference none`) never serves them. There is no `--serve-captures` flag.

(Phase 6 security fix, pass 2, P2-L6-F2, P2-L6-F3, built 2026-10-09, SC; D159 kept as one rule, D244) **A capture's
bytes leave the server only under `images: true`, and then only as the PNG blob behind a tool's resource link; on every
text route they are elided, whatever `images` says**: a tool's result (§9.3), a resource served as text
(`state/current`, `events/recent`, the session journal, a run's `run.jsonl` and `failure/*.json`), a fact, an event, a
call record. The rule is `…scenario.Images` (an image's bytes: the string of a map's `base64` entry that is base64
text, and in a text a base64 run that starts with a PNG's signature `iVBORw0KGgo` or the base64 string of a JSON member
named `base64`; elided to `<png: N bytes>`, `<base64: N bytes>`, N the bytes it encodes, the map's other entries
kept). It is applied where the files are written (`run.jsonl` by `RunLog.write`, every record type and the telemetry
observer's copy; the session journal by `StepJournal.append`; the executor's `failure/` JSON and text files), once more
by `Masking` on everything the server answers or serves as text (for files written before the fix), and by
`PromptMask` on everything a model reads (§6.2). The pixels are kept only in the PNG files (`<out>/mcp/<n>.png`,
`artifacts/capture.png`, `failure/capture.png`), which the person opens and which D159 gates.

(Phase 6, built 2026-10-09, CP; P6 §4.2, §6.4) `skillroy://env/profile`'s matrix is the one read after the start's
hook install (with the `hook.<id>` capabilities); a run the server started holds its `capabilities.json` beside its
`run.jsonl`. No resource serves a hook spec or takes one.

(Phase 7, built 2026-10-10, VM; P7 §9.4, 9.6) After each `skillroy_session_ask` the session's
`captures/<sha256>.png` files are listed as `skillroy://captures/<sha256>.png`, only under `images: true` (otherwise
nothing: the journal names the file); a `ROBOT` capture is never among them.

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
   Schema (draft 2020-12 in content; passed to `claude -p --json-schema` **without its `$schema` key**, since the CLI's
   validator refuses one that names the 2020-12 meta-schema, "no schema with key or ref …", and accepts the same text
   without it, `$defs` and `$ref`s included: the live session, 2026-10-08) of the scenario document: the top-level keys of P1 §8.1
   with their shapes; `resolve` slots whose `query` is one of the pack's kinds with the kind's argument record
   (`Schemas.ofRecord(queryClass)`, references allowed as strings matching `^\$`), `strategy` tokens limited to those the
   pack can serve (`sql`, `java`, and `llm-sql` when an allow-list exists); `oracle` entries over the pack's functions;
   `steps` as a list of single-key objects, one key per verb (the base verbs with their argument shapes, the pack's verbs
   with `argsSchema` or a free-form object), each with the optional `expect`; `verify`, `cleanup`, `requires` (from the
   capability ids the target reports), `ui`, `web`, `timeouts`. The same generator feeds the MCP resource; a test
   validates every committed sample scenario against it with the small validator of §13.3.
3. **The MCP configuration**: a private, owner-only temporary file naming this CLI's own launcher with `mcp
   --mutating` (the planner may try steps; the target is a disposable test instance), the connection options, the pack
   jars, `--env` and the credentials file; deleted when the command ends. **Phase 6, built 2026-10-09, BW (P6 §9.2,
   §9.3, D137, D138; not verified on a Windows host):** the file is written through the controller's
   `SecureFiles.writeOwnerOnly` (created exclusively, POSIX `rw-------` or an owner-only ACL, refused where the file
   system has neither), as is the planner's private work directory (`SecureFiles.createOwnerOnlyTempDirectory(tmp,
   "skillroy-author-")`, which no longer falls back to a plain temporary directory). A launcher that is a `.bat` or
   `.cmd` (case-insensitive; `bin\skillroy.bat` names itself) is written as `"command": "cmd.exe", "args": ["/c",
   "<launcher>", "mcp", …]`, because Node-based clients refuse to spawn a batch file without a shell since the
   CVE-2024-27980 fix; until a Windows host shows which quoted form survives the planner's client (P6 §9.5) this plain
   form is written, and a launcher path or argument holding `"`, `%`, `^`, `&`, `|`, `<`, `>` or white space is refused
   (exit code 64, "move the installation or the pack to a path without spaces or special characters").
   (Phase 6 security fix S60, A58, built 2026-10-09, SR) The server's arguments also carry `--client planner` (D158,
   S59) and `--out <work>/mcp`, under the planner's private working directory, which the command now makes first
   (`Planner.workDirectory()`, handed to the planner as `PlannerRequest.workDirectory`), so that the server's journal,
   run evidence and captures are deleted with it when the command ends; `--keep-work` keeps it and prints its path on
   stderr. (Before the fix the server, started without `--out`, kept a `skillroy-mcp-*` temporary directory that
   `author` never deleted.)
4. **The prompt**: the planner instructions (explore with the read tools first; use the verbs, never the low-level
   primitives, in steps; name no person or item, resolve them with queries; expectations through facts and `db`
   checks, never through what the UI shows unless the brief asks for the UI; prefer committed strategies; return
   exactly one scenario document) with the brief inside a `<brief>` block, the catalogue summary and the verbs with
   their schemas. The command runs
   `claude -p --bare --output-format json --json-schema <schema> --mcp-config <file> --strict-mcp-config --tools ""
   --allowedTools "mcp__skillroy__*" --permission-mode dontAsk --model <id> --max-turns <n> --max-budget-usd <usd>`,
   the prompt on standard input (the paragraph below) (`--tools ""` leaves the CLI's built-in shell and file tools
   out; `--strict-mcp-config` admits no other server; `--max-budget-usd` is the CLI's own budget, BS13 F-10) with
   `ANTHROPIC_API_KEY` inherited when set (the CLI also accepts its own login; a key that needs a workspace id needs
   it here too, through `ANTHROPIC_CUSTOM_HEADERS`, to be verified when the live run is possible), the working
   directory a private temporary directory (Phase 6 security fix S60, A58, built 2026-10-09, SR: the `claude` CLI's
   own session store and configuration live in the CLI's configuration directory, outside the base's control, and a
   `--resume` needs them; a release note says so, and relocating them with `CLAUDE_CONFIG_DIR` is not built, B26 Q22),
   stdin the prompt file (the paragraph below), stdout read in full under a deadline of `timeouts.plannerMs` (default
   15 min), stderr to the command's log, masked.
   **(Phase 6 security fix, pass 2, P2-L7-F3, built 2026-10-09, SD) No text of the brief, of the model or of a
   repair is ever an argument.** The prompt, the first invocation's and each repair's, is written as UTF-8 to
   `prompt-<n>.txt` in the private working directory and is the CLI's **standard input** (`claude -p` reads its prompt
   from stdin when no prompt argument is given); the command line ends with `--max-budget-usd <usd>`, and only
   `claude --version` keeps a closed stdin. The arguments are then the executable, the fixed flags, the schema (the
   pack's and the target's catalogue texts, never the brief's or the model's), the configuration's path, the model id
   of the inference settings, two numbers and, for a repair, `--resume <session_id>`, where the session id must match
   `[A-Za-z0-9_-]{1,128}` (the CLI's are UUIDs) or the repair is refused (exit 1) and nothing is started. A planner
   whose file name ends with `.cmd` or `.bat` (case-insensitive) is refused before anything starts, on every platform
   (exit 64, naming the remedy: the native `claude.exe`, or `--claude-command`): Windows starts a batch file through
   `cmd.exe /c`, which expands `%…%` anywhere on the line and splits at `&`, `|`, `<` and `>` outside the quotes it
   pairs, and the schema, the one argument that must hold quotes, cannot pass through it intact. On Windows the schema
   argument is handed to `ProcessBuilder` already quoted by the C runtime's rules (`"…"` around it, an inner `"` as
   `\"`, a run of backslashes before a quote doubled), which the JDK's default argument handling passes on as it is,
   so that the executable reads it back as one argument whatever the pack's or the target's texts in it hold, and the
   whole command line must fit Windows' 32767 characters (with room for a `--resume`), else the command is refused
   (exit 64) before anything starts. Before the fix the prompt was the last argument; on Windows, where npm's
   `claude.cmd` was found first (D198), a model's quote, `&` or `%VAR%` that a problem echoed into a repair prompt
   reached `cmd.exe` (code execution in the controller's account, and the model key expanded into the prompt).
   `PlannerTest` reads the substitute's arguments and stdin; `PlannerCommandLineTest` builds the Windows command line
   as the JDK does and parses it back by the C runtime's rules (not run on a Windows host: §15.3 and P6 §9.5 keep the
   live and host checks). **As checked live (2026-10-10, native macOS, `claude` 2.1.286; §15.3):** the first
   invocation and a forced repair round read their prompts on stdin (14625 and 374 bytes), no text of the brief, the
   instructions or the problems was in any argument, each command line ended with `--max-budget-usd <usd>`, and
   `claude -p --resume <session_id>` with the problems on stdin resumed the session. A resumed envelope's
   `total_cost_usd` and `modelUsage` are the session's running totals, while its `num_turns`, `usage` and
   `duration_ms` are the invocation's own, which is how the planner reads them (one ledger entry per invocation at
   the difference of the totals, turns summed).
5. **The result**: the JSON envelope's `structured_output` (the scenario; optional in the envelope), `total_cost_usd`,
   `session_id`, `num_turns`, `is_error`, `mcp_server_errors` (optional). Success is exit code 0 with `is_error: false`,
   never the envelope's `subtype` (it reads `success` on failures too, BS13 F-10); a non-zero exit, `is_error`, a
   missing `structured_output` or a non-empty `mcp_server_errors` ends the command (exit 1) with the message (a failed
   `--resume` prints plain text, not JSON, which is reported as such, and an invocation that printed no envelope is
   reported with the last line of its stderr, masked and bounded, which is where the CLI explains itself: the live
   session, 2026-10-08); the cost is recorded in the ledger as provider `claude-code`, model as reported, purpose
   `plan`: one entry per invocation holding the **difference** between this envelope's `total_cost_usd` and the previous
   envelope's of the same session, because the envelope reports the session's running total (BS13 live, 2026-10-08:
   the `--resume` envelope's total was the first call's plus the second's), and the repair invocation is given what is left of the planner's budget, floored at 0.01 (the CLI's
   `--max-budget-usd` bounds an invocation's own spend, not the session's total: two live probes on 2026-10-08, a resume
   under a budget below the running total succeeded, one under a budget below its own cost ended `is_error: true`,
   `subtype: "error_max_budget_usd"`, `terminal_reason: "budget_exhausted"`, `result: null`, after its one turn). The document is rendered to YAML (sorted top-level keys in the order of P1 §8.1; the brief, the date, the
   model, the session id and the cost as a leading comment) and compiled with `PlanCompiler`; compile problems start a
   **repair round**: `claude -p --resume <session_id>` with the problems as the prompt, at most `--max-repairs` times.
6. **Checks**: the plan is bound with `resolve --dry-run` semantics (live mode, nothing written) so that the planner's
   queries are seen to resolve on this environment; with `--run` the scenario is run once in `live` mode (with recover)
   and the `RunResult` summary is printed. Neither writes bindings: the first `approve` run does, by a person.
7. **Written**: `<out>/<id>.yaml`, `<out>/plan/<id>.plan.json` (as `compile` writes it), `<out>/<id>.authoring.json`
   (`{brief, model, sessionId, turns, repairs, costUsd, checks: {compiled, resolved, ran?}, claudeVersion, date}`),
   `<out>/<id>.envelope.json` (2026-10-08, the live session; D112: the envelopes the CLI printed, the first
   invocation's and each repair's, in order, passed through the prompt mask, so that a planner's run can be reviewed as
   a whole and replayed in the build by the fixture test of §15.1 item 11; a person deletes it before committing the
   scenario or keeps it as that fixture), and the instruction "review the YAML and the plan, then `skillroy resolve
   --env … <id>.yaml` to approve its bindings". Exit 0 when the file was written and compiled; 1 when the planner
   failed or the repairs ran out; 64 for usage.

As built (2026-10-08, I2, `…inference.planner`): `Planner.author(PlannerRequest) → AuthoringResult` and
`Planner.recordChecks(authoring, resolved, ran)`; `PlannerRequest` with the fourteen components (nulls take the
defaults), `AuthoringResult` (the files, the plan, the model, the session id, turns, repairs, the cost, `overBudget`,
the CLI's version, the ledger entries, the masked log) and `AuthoringException(exitCode 64 or 1, message, lastYaml,
ledger)`; `claude --version` is run first; `--json-schema` is given the schema's JSON text (the form spike BS13 ran),
so the schema and the prompt are each limited to 120000 characters (a single argument is bounded on Linux); the
document is validated against the schema before it is compiled and the requested id replaces its `scenario`; stdout
and stderr go to files in the private temporary directory (no reader thread) and the process tree is killed at the
deadline; the planner's ledger entries are returned, not recorded (the request carries no ledger); the MCP configuration
file belongs to the caller.

As built (2026-10-08, fix wave). The prompt's brief is exempt from the prompt mask and its vocabulary goes through it
(D103, 6.1). The stdout and stderr files are bounded on disk by what is read of them (32 MiB and 1 MiB): between bounded
waits for the process's end (100 ms each, within the deadline) their sizes are looked at, a process whose output passes
a bound is stopped there with its whole tree, a file longer than its bound is cut to it, and the log and the failure's
message say so (never what was cut). The strict settings reader accepts `timeouts.plannerMs` (default 900000, 15
minutes), which the CLI passes as the request's deadline. `PlannerTest` proves the deadline kill on a substitute that
forks a child and waits for it (both processes are gone afterwards; without the descendants' kill the child survives)
and the one deadline over every round with a clock that passes it once the first invocation has run (the repair round
is refused "the planner's deadline … has passed" before it starts).

`--claude-command <path>` (also `skillroy.claude.command`) names the executable; tests pass a script that prints a
canned envelope, so that steps 5-7 are tested without the CLI (§15.1). With the real CLI absent the command exits 64
("claude not found on PATH"). **`PATHEXT` (Phase 6, built 2026-10-09, BW; P6 §9.2, D137):** on Windows (`os.name`
starting with `Windows`) the `PATH` lookup also accepts `claude` plus each extension of `PATHEXT` (`.EXE`, `.CMD`,
`.BAT`, …, in its order and without regard to case) and tries those **before** a file named exactly `claude`, since
npm installs an extensionless shell shim beside `claude.cmd` that Windows cannot start (as built: the contract named
no order); the `PATH` and `PATHEXT` names are read without regard to case. The lookup takes the environment as a
parameter, so `AuthorCommandWindowsTest` proves it on Linux with a fake `PATH` and `PATHEXT`. The planner's budget is passed as the CLI's `--max-budget-usd` (present in the 2.1
line, BS13 F-10) and `total_cost_usd` is still checked against it afterwards and reported.

As built (2026-10-08, fix wave; steps 2, 3, 4 and 6): the planner's server is `mcp --mutating` without
`--allow-evaluate`, always (D98), so its projection is `Policy.mutating(false)`; `author` gives the planner the
catalogue of the primitives that server offers a scenario (`PlanPolicy.callableCatalogue()`: what its base verb `call`
may name, `trace.mark` described when the target lists none), so the scenario schema's `call` enum names only those and
the `requires` hint names their capability ids (step 2). The deadline is `timeouts.plannerMs` of the inference settings
(`InferenceSettings.timeouts()`, read by name; the planner's own 15 minutes when the settings carry none) (step 4).
With `--run` (step 6), the plan is checked against the planner's server's projection first, as `skillroy_scenario_run`
checks it (§9.3, D96): a refused plan does not run, the `--run` check fails (`ran: false`, the refusal named in the
output) and the exit code stays 0; a plan that may run runs with every expectation on masked values
(`RunOptions.withExpectationsOnMaskedValues(true)`), so that its PASS or FAIL is no oracle on a credential.

As built (2026-10-08, the live session; D112, step 7): `<out>/<id>.envelope.json` is a JSON array of the envelopes as
the CLI printed them, pretty-printed as the other files are, through the mask of the planner's log
(`PromptMask.keysOnly()`: the planner knows no secret value, so the key rule and the bounds): every text through the
text rule as a log line is, a text, an object or an array under a key that names a secret replaced whole, and a number
or a boolean kept under any key, because an envelope's `usage` counts its tokens under keys that name a token
(`input_tokens`, `cache_read_input_tokens`), which `jsonLike`'s key rule would blank and the text rule over the JSON
text would leave unparseable. A failure after an envelope arrived (an `is_error` envelope, a non-zero exit, an MCP
server error, no document, the repairs or the budget run out, the deadline before a repair round, a plain-text
`--resume`) writes the file with what arrived, `AuthoringException.envelope()` names it and `author` prints it on stderr
("the planner's envelopes: <path>"); a failure before any envelope writes none, and a later run of the same id replaces
the file. `AuthoringResult.envelope()` is the path, the instruction adds "`<id>.envelope.json` holds the planner's
envelopes: delete it before committing or keep it as the fixture of the planner's replay", and `author` prints the
path in its `written:` lines and as `envelope` in `--json`.

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
a directory; 64 for usage (no directory, gateway `none`). As built (2026-10-08, I2, `…inference.triage`):
`Triage.run(runDir, gateway, mask, prices, TriageOptions(labelsOnly, clock)) → TriageResult` (outcome `TRIAGED`,
`NOTHING_TO_TRIAGE` or `FAILED`, the label, the summary, the models and request ids, the cost, the two texts;
`exitCode()`); a run's outputs carry no step record with a verb, so the request names the failed step by its id with
its `call` and `expect` records; the events come from `failure/events.json`; effort is `LOW`; the evidence travels in
`<target-state>`, `<target-ui>` and `<target-event>` blocks.

(Phase 6 security fix, pass 2, P2-L6-S1, P2-L5-F2, built 2026-10-09, SC) No image, **and no image's bytes inside a
record**: the records triage keeps (`call`, `expect`, the failed slot's) have a capture's `base64` elided to
`<png: N bytes>` (`…scenario.Images`, §9.4) before they are rendered, which covers a run log written before the run log
elided images, and the request's mask elides any left (§6.2). In `triage.md` the answering models' ids and the
summary-failure line, which quotes the provider's error text, are inline code, as the model's texts are (S50): those
strings are the provider's, or whatever `ANTHROPIC_BASE_URL` names.

As built (2026-10-08, fix wave): no row of the run's data reaches a triage request: every `rowSample` of the run's
records (the statements of the `resolve` records of the slot that resolved last) and any other rows-shaped value (a
list of lists, such as a `data.query` call's `rows`) is left out of the evidence, while a statement's text, its
parameters, its `rowCount` and the verifiers' outcomes stay; a row sample carries no column names, so the by-column rule
of 6.2 cannot be applied to it, and triage knows no allow-list. So the command needs no `--credentials` or `--pack`:
the evidence is already masked, and row samples are not sent at all. `TriageOptions` gains `logPrompts`, the effort of
both calls and the model ids the estimates are taken at, and `TriageOptions.of(settings, labelsOnly, clock)` takes
them from the settings (`logPrompts`, `effort.triage`, `models`).

As built (2026-10-08, fix wave): `triage --log-prompts` (one of the gateway options every model-calling command takes)
reaches the triage: the command builds `TriageOptions` with `logPrompts` from the settings, so that the masked prompts
go into `triage/inference.jsonl` with the option and never without it (D83), as for every other command.

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

(Phase 6, built 2026-10-09, CP; P6 §5.3, §6.3, §6.4) `open` installs the pack's hooks under `SessionOptions.hooks`
(P4 §6.1's note), writes `capabilities.json` and exposes the `hooks` record as `hooks()`; the session's base root
`hooks` counts a hook's events from the session's own start mark.

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
exhausted)` writes the `budget` record and `primitives()` answers the catalogue. As built (2026-10-08, fix wave): every
origin but `PERSON`, a `SYSTEM` one as well as a `MODEL` one, is stripped of `confirm` and `_meta` (at the top of a raw
call's or a verb's arguments, in a nested `args`, the base verb `call`'s form, and in the calls a verb compiles to), and
the journal holds the arguments as stripped; `CallRunner` drops a parameter named `_meta` from every call it sends, for
every caller (plan steps, cleanup calls, sessions and so the MCP server; D79), since a request's `_meta` is built from
the call's `CallOptions` alone, and `AgentClient` no longer merges a `_meta` of the params into the request's (P1 §7),
which let `{_meta: {confirm: true}}` confirm a call; the compiler refuses a `_meta` key at any depth of a step's
arguments (§13.1), so a person's raw call that carries one is refused as not compiling; and a `check` (and an `expect`
step) is evaluated on the value as the session's mask leaves it: a value the mask changes holds a secret and satisfies
no matcher, `equals: "<redacted>"` included, the outcome saying why, while a check on any other value is unchanged (a
session's outputs pass its mask, §6.1; matched against the unmasked value, `matches` and `contains` rebuilt a
credential character by character). A plan run judges its expectations (`expect` steps, `verify`) and stored-row
checks (row count, columns, aggregates) by the same rule when its `RunOptions` say
`withExpectationsOnMaskedValues(true)`, so that a run's PASS or FAIL answers no question about a secret either; the
default is false (a scenario a person committed is evaluated on the values as read), and the hosts that run a plan a
model or a client wrote (the MCP server's `skillroy_scenario_run`, `author --run`) set it. `readOnly` refuses any
mutating or destructive call before it is sent (`READ_ONLY` in the outcome, nothing journaled as executed). A
destructive call needs `confirm: true` in its arguments, which only an `Origin` of kind `PERSON` may carry: the
session strips `confirm` from a `MODEL` origin's call and the call then fails with `CONFIRM_REQUIRED` as the agent
answers it (design §3.4). Every outcome's `detail` and `lastResult` pass the session's
`SecretMask` (credentials, fixture secrets joined into the bindings). A session's calls carry a `correlationId`
`<sessionId>/<stepId>`.

(Phase 6, built 2026-10-09, TL; P6 §10.2.) `SessionOptions` gains the component `observer` (default `NONE`,
`withObserver`; the ten-argument constructor stays): the session wraps it, reports its start (`sessionStarted`,
the policy `read-only` or `acting`), each step's start and its masked `StepOutcome` (with the origin's kind, never the
intent text), the type of every journal record and its close, observes its calls as a run's, and hands the same
observer to the session service (`turnStarted`, `sessionTurn` with a `TurnRecord` of the turn's facts) and the MCP
server (`toolCallStarted` with the client's `_meta.traceparent`, `toolCall`) through `ExecutorSession.observer()`.

(Phase 6 security fix S55, A54, built 2026-10-09, SR) `PlannedCall` gains the boolean component `literalArgs` (the
six-, five- and four-argument constructors stay and give `false`; `withLiteralArgs()`): a call with `literalArgs` is
compiled and run without reference substitution (every `$` of its string arguments, at any depth, is escaped as `$$`
before `PlanCompiler.sessionCall`, so that the runner's substitution gives each string back unchanged), and the journal
records its arguments as given. The MCP server's `data_query` is the one caller (§9.3).

(Phase 6 security fix S57, A56, built 2026-10-09, SR; D158) `Origin.Kind` gains `CLIENT` (`Origin.client(by)`, read by
`fromJson`): an MCP client, often a model, which acts through the session like a person but confirms nothing; the
session strips `confirm` and `_meta` from a `CLIENT` origin's arguments and from every call its verb compiles to, as it
does a `MODEL`'s or the `SYSTEM`'s (`confirms` stays `PERSON` only). `ExecutorSession.compile(verb, args)` answers
`Compiled(calls, cleanup, refusal)`: the calls of the one-step scenario the verb compiles to with these arguments and the
cleanup calls it registered, each as the verb wrote them, without running or journaling anything (a step that does not
compile is the refusal reason), so that a host checks a verb's calls with the real arguments before the first runs (the
MCP server's verb tools and the session service, §9.3, §12.4).

(Phase 7, specified 2026-10-10; P7 §9.2, built by VC, whose notes there say how)
`ExecutorSession` gains the unjournaled `read(primitive, args)` (bounded, non-mutating only, masked but an image's
`base64`, the agent's error thrown), the four-argument `execute` forms with the provenance a vision tool hands in,
`capabilities()` (the merged matrix), `secretsInFlight()` and `expectImage(step, origin)`; `SessionOptions` gains
`robot` and `host`; the robot gates and the credential rule (D342) hold for every origin but a person's.

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
not in the journal. (Phase 6 security fix S57, A56, built 2026-10-09, SR) `origin.kind` is `PERSON`, `MODEL`, `SYSTEM`
or `CLIENT` (an MCP client, labelled with its server's `--client`; never a confirmation); the export's head names a
client's steps `client <label>` ("Acted: client mcp: 3 steps"), never `person`.

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
skipped with a report note (a masked step argument still fails the export); a `REQUIRED` approval carries `by: null`,
as the binder writes a value `live` found (corrected in the fix wave of 2026-10-08: the export wrote `by: "live"` and
called it the binder's label, which the binder never writes for `REQUIRED`), and a tier (a) slot `NOT_REQUIRED` with
`by` the exporter's label or `live`; the export reads a journal back from its file to the same result. As built
(2026-10-08, fix wave): arguments are written with sorted keys, at every depth (a slot's, an oracle's, a verb step's
and a raw call's arguments, and a check's matcher), the order the recordings' tool inputs have, so that an export is a
function of the journal's content and not of the key order of a live model's answers, which made a regeneration
rewrite the committed export, its plan's `sourceHash` and its bindings' `planHash`. `ScenarioSchema` validates every
committed sample scenario (`ScenarioSchemaCommittedTest` in both samples) and reports problems with paths such as
`$.resolve.supervisor.strategy`; `requires` items are strings with the capability ids in a description (the grammar
allows alternatives); `Schemas` lives in `ai.skillroy.testjava.scenario` (not the inference SPI package of §3.3: the
scenario module needed it first; the inference module reuses it) with `ofRecord` (strict) and `ofArguments` (lenient,
`$` references allowed, primitive components only); the plan reader also checks that a version 5 plan's `inference`
equals what its slots imply.

(Phase 7, specified 2026-10-10; P7 §6.6, §9.2, built by VC) A journal
step carries the optional `provenance` its acting call handed in (tier, via, capture, point, hit-test, anchor; no bytes);
cost stays in the ledger, found by the request id. The export writes a vision-derived step as its verb step with one
masked comment (no hash, handle or mark), the report's "Vision-derived steps" and "Live-only" sections, and the anchors'
`images` section in its bindings file.

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
   (Phase 6 security fix S62, A60, built 2026-10-09, SR; D158) A pack verb the policy offers is checked by the same
   rule as the MCP server's verb tools (D96, `…inference.tools.CallPolicy`, to which `PlanPolicy.never` and
   `arguments` delegate): at start with probe arguments (`CallPolicy.probes`: an empty mapping, then placeholders for
   the schema's required keys, then for all), and at call time on the calls it compiles to with the model's arguments
   (`ExecutorSession.compile(verb, args, origin)`); a verb reaching a destructive, `agent.*`, `data.query`, `hook.*` or
   `rule.*` (but the three reads) or script primitive, or carrying `confirm` or `_meta`, is withheld (left out of the
   tools and listed by `skillroy_catalogue` under `withheldVerbs`; not logged, the service having no log of its own) or
   its call refused as a tool result error naming the primitive, nothing run or journaled.
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

As built (2026-10-08, I2, `…inference.session`): the signatures as stated, with `tools()`, `policy()`, `exhausted()`
added and the read-only policy factory named `readOnlyPolicy()` (`readOnly()` would clash with the record's
accessor); `ExecutorSession.pack()` added (the service needs the verbs and their schemas); a refusal, a cut answer and
a provider failure end a turn as `QUESTION` with the reason as the answer, and a lost agent as `LOST` (the throws clause
stays); the history beyond the limit is folded by one `summary` call. `OverlayBridge.open(service, agent, pack,
subscription, sinceSeq)` owns no thread: the subscription's consumer queues requests (16 at most, the rest dropped and
counted) and the host serves them with `serveNext(Duration)`; the answer is a plain call of the pack's answer primitive,
not a journaled step.

As built (2026-10-08, fix wave; D101): the session's tier (b) resolves are metered on the session's budget.
`SessionService.resolve(slot, kind, args, strategy, verify, credential, origin)` is the session's metered resolve
(the signature of `ExecutorSession.resolve`), which the model's `skillroy_resolve` uses and every host uses for a
resolve of a session that has a service (the CLI session's `resolve`, the MCP server's `skillroy_resolve`): a strategy
list without a model token runs as it stands; one that names `llm-sql` reserves one tier (b) call's estimate on the
session meter (2000 tokens in and 2048 out at the price of the settings' Sonnet id; the catalogue is not counted) and is
refused `RESOLUTION_FAILED` / `BUDGET` (the meter's figures in the detail, no model call, the service exhausted) when it
has no room or the service is exhausted; otherwise it runs with its scenario budget capped at what was left of the
session's (`BudgetCaps`, keyed by the session's ledger, which both hosts pass to the session's binds and to the
service; the contributor's scenario meter takes the smaller limit), and the ledger entries it added are settled on the
session meter afterwards, whatever its outcome, with the journal's `budget` record written (Phase 6 security fix S56,
A55, built 2026-10-09, SR; D157: and the MCP server meters `skillroy_scenario_run` on the same meter; the server owns
one SESSION meter from `budget.sessionUsd`, shared with the `skillroy_session_ask` actor, `SessionServices.open` taking
it, and the service's exhausted state is the meter's). The service also reads the
session's settings from its bind options (where both hosts put the invocation's settings): the turns' calls take
`effort.session`, the summary `effort.summary`, and the estimates the model ids the settings give the Sonnet and Haiku
classes. As found live (2026-10-08): the sample's verbs projected as strict tools hold 20 union-typed parameters,
above the provider's limit of 16 (§4.1, `ToolSpec`), so a session's request on the sample goes out with its tools
unstrict; the first live request of the session had been refused with HTTP 400 before the gateway learned the rule.

**Timing.** The model's thinking happens between actions; each action is one bounded step with the pack's
`PromptPolicy` and sync waits, so the target is never held mid-step by a model call (design §3.4). The service does
not sleep: it waits on the gateway's call and on the session's step.

**Hosts.** (1) `skillroy session` (§13.4): a REPL where the person types `ask …`, `do <verb> {…}`, `call <primitive>
{…}`, `resolve …`, `check …`, `observe …`, `snapshot`, `note …`, `export <id>`, `quit`; (2) the MCP server's
`skillroy_session_*` tools, where (Phase 6 security fix S57, A56, built 2026-10-09, SR; D158; this replaces "the MCP
client is the person's side") the MCP client is a client (`Origin.Kind.CLIENT`): it acts through the session like a
person but confirms nothing, and the journal and the export name it `client <label>`; `skillroy_session_ask` lets a
*second* model act (the service's model is the actor; both are journaled by origin); (3) **the overlay bridge** (`OverlayBridge`): subscribes to the topic `assist.requested` through an
`EventSubscription` seam (`AutoCloseable subscribe(long sinceSeq, String topics, Consumer<Event>)`, which the CLI binds
to `AgentClient.events`), takes `{requestId, text}` from each event (masked, `<person>`), runs `ask(text)` and answers
through the primitive the pack names in `DomainPack.assistAnswerPrimitive()` (a new default method returning null = no
overlay) with `{requestId, answer}`. The sample has no overlay; the bridge is tested against the executor tests' fake
agent with scripted events (§15.1). An overlay's agent module never sees a model SDK (B15).

(Phase 7, built 2026-10-10, VM; P7 §9.1, 10) The seam `SessionTools`
(`ServiceLoader`, `load(ClassLoader)`), the facade `SessionActions` and `SessionExtensions`: the eighth constructor
parameter; an applying tool set's tools after the projection's and before `done`/`ask_person`, its provider tools, its
instructions and the pack's screen legend as system parts (masked), `CacheHint.AUTO`, purpose `vision` and
`effort.vision` while it applies; its `atTurnStart` parts in a turn's first message and its `afterBatch` parts after a
batch's results; `act` consulted for its names; `endTurn` `QUESTION`/`LIMIT`; `vision.maxActions` (30) and
`vision.maxTurns` (20) enforced by the service, which then closes the tool sets; `visionCall(ModelRequest)`; the raw
primitive branch under `CallPolicy.arguments(…, false)` (refused, never stripped). The vocabulary's `$reference`
sentence names a non-secret field of a resolved slot only (D342).

---

## 13. Scenario YAML, `plan.json` version 5, CLI and JUnit

### 13.1 Scenario YAML

- **`strategy` tokens.** `llm-sql` is accepted in a slot's `strategy` list (P2 §3.1) when the running pack declares
  an `SqlAllowList` for at least one source; the loader's and the plan reader's rejection ("needs the inference
  module") is replaced by a compile-time check against the pack ("pack lending declares no SQL allow-list: llm-sql
  cannot be served"). `llm-reason` and `llm-vision` stay rejected with "not in this version". A slot that lists
  `llm-sql` alone is allowed (the model is the only resolver asked).
- **The base verb `call`** exists since Phase 1 (P1 §8.3, `BaseSteps`: `call: {primitive, args?, sync? (default NONE),
  await? (with its capture inside)}`, unknown keys being errors, compiles to one `PlannedCall` verbatim; corrected
  2026-10-08: the first draft of this contract wrongly introduced it as new, and the fix wave took out a `timeoutMs?`
  the verb never had). Phase 5 amends nothing of its shape; it confirms that a destructive primitive's `confirm: true`
  in `args` reaches the call (the executor honours `CallOptions.confirm`) and that a pack may not name a verb `call`
  (reserved with the other base verb names). The journal export uses it as it
  is for a step that no verb covers (§12.3), so that an export can always be written; it is not the preferred way to
  write a scenario, and `skillroy author` is told not to use it. As built (2026-10-08, fix wave): the compiler refuses
  a `_meta` key at any depth of a step's arguments, whatever the verb (`expect` aside: its matchers are evaluated by
  the executor, never sent), and of the calls a verb compiles to, cleanup calls and a session's raw call included,
  with "a step's arguments may not carry _meta" and the key's path: a call's `_meta` is the executor's, built from the
  call's options (§12.2), so a scenario, written, generated or exported, cannot carry one.
- **`inference`** is not a YAML key: the compiler writes the plan field (§13.2).
- Nothing else changes: the grammar of P1 §8.1 with the additions of P2-P4 stands.

### 13.2 `plan.json` version 5

(Phase 6, built 2026-10-09, CP; P6 §5.2, D127) Version 5 stays the written version through Phase 6: the hook
catalogue, the hooks switch and the overlay add nothing to a plan but the composite `packVersion` of an overlaid pack.

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

(Phase 6, built 2026-10-09, CP; P6 §6.1, §6.2) `run`, `session` and `mcp` gain `--hooks auto|on|off` (`mcp` installs
at the server's start; `--hooks on` refuses the start when the hooks cannot be installed), and the command
`skillroy hooks status|install|remove` is listed after `session` (P1 §8.6).

New options of `run` and `resolve` (P2 §12.1 stands): `--inference claude|recorded|none`, `--recordings <dir>`,
`--inference-file <path>`, `--budget-scenario <usd>`, `--log-prompts`; `run --mode re-resolve` is refused (§8.1).
`run` and `resolve` print `model cost: $x (budget $y)` after `model calls: N`, and `--json` carries `modelCost`,
`budget` and the `inference` records' summary. The approval screen is §7.1's.

New commands (each with `--help`, `--json`, the connection options where it connects, and the exit codes of P2 §12.1
unless stated):

| Command | Does |
|---|---|
| `re-resolve …` | §8.2 |
| `mcp …` | §9.2; exit 0 when the client closed the stream, 3 when the agent was lost; (Phase 6 security fixes S56, S57, built 2026-10-09, SR) `--client <label>` (default `mcp`), and `--session-budget` bounding `skillroy_session_ask`, `skillroy_resolve` and `skillroy_scenario_run` together |
| `author …` | §10.2; (Phase 6 security fix S60, built 2026-10-09, SR) `--keep-work` |
| `triage …` | §11.2 |
| `session [connection] [--pack]… [--env] [--credentials] [--out <dir>] [--read-only] [--allow-base-primitives] [--inference …] [--budget-session <usd>] [--max-actions <n>] [--id <sessionId>]` | opens an `ExecutorSession` and, unless `--inference none`, a `SessionService`; a REPL on the terminal (§12.4; scripted in tests through the `Terminal` seam): `ask <text>`, `do <verb> <json>`, `call <primitive> <json>`, `resolve <slot> <kind> <json> [--strategy a,b]`, `check <path> <matcher-json>`, `observe <path>`, `snapshot [max]`, `facts <root> [path]`, `events [since]`, `note <text>`, `export <scenarioId> [--approved-by <label>] [--out <dir>]`, `quit`. Without a terminal, `--script <file>` runs the same commands from a file and exits; exit 0, or 1 when the agent was lost or the script's last `export` failed |

`Main`'s command list gains the five; `Cli` is unchanged. Everything printed passes the mask and the S26
control-character rule.

As built (2026-10-08, C). `run` and `resolve`: the options as stated plus `--workspace-id`, loaded through
`InferenceSettings.load(mode, credentialsDir, options)` (a settings error is exit 64 with the settings' own message,
never a value; `run` also writes the JUnit `environment` case then); `resolve` prints the model-calls and cost lines
too; `run --mode replay` prints a note when inference options are given; `--json` gains `modelCalls`, `modelCost`,
`budget`, an `inference` summary (gateway, calls, cost, providers, models, purposes) and per-call records, masked; the
approval screen reads three provenance notes of the resolver (`GUARD_NOTE`, `PARAMS_NOTE`, `ROWS_NOTE`) for the
injected clauses, the parameter binding and the row bounds, and the reused line names no date (the file carries
none). `re-resolve`: the job budget may also come from `inference.yaml`; the job id is the UTC start time
(`yyyyMMdd-HHmmss`); one job takes one pack; a plan that fails its `requires` or launch-property check is reported
`FAILED` (`REQUIRES`, `LAUNCH_PROPERTY`, `LAUNCH_PROPERTIES`) and plans that need no bindings are not checked; exit
codes from the report, 2 for a load or compile error, 3 for an unreachable agent, 64 before the job starts, 70 for a
report that cannot be written. `mcp`: no `--json` (stdout is the protocol); `start`/`awaitExit` so that the command
watches the agent's event stream and calls `agentLost`; `skillroy_session_ask` only with a gateway; exit 1 when the
server cannot start or the credentials cannot load. `author`: the MCP configuration adds `--inference none` and
`--discovery-dir` and names this CLI's launcher through `-Dskillroy.launcher` (set by `bin/skillroy`; in-process the
JVM's own `java` and class path); the pre-flight runs first; exit 0 even when the dry-run bind or the `--run` fail (they
are information for the reviewer, recorded by `recordChecks`); `--run` writes no evidence directory; the planner's
ledger is printed, not written. `triage`: no `--credentials` or `--pack` (the evidence is already masked); a directory
without `run.jsonl` is a usage error. `session`: the REPL as stated plus `resolve … --credential <realm>.<key>` (a
sign-on slot cannot be declared otherwise), `--bindings` and `--json` (one object per line); output under
`run-output/<session id>/`, the person `Origin.person("cli")`, a mutating `swing.*` call settles afterwards, a bare
word is a `check` matcher, the session writes its own `cost-ledger.jsonl`; **the session closes with `recoverOnClose` on (D95)**: after a mutating call the pack's `recover` runs when the session ends, as after a run, so that the target is as the export's replay expects it (found by the Linux matrix of 2026-10-08: the replay of §14.4's export signed on to a desk the session had left signed on, and the desk took the staff code for an item). The run writer: `inference.jsonl` records are
the entry's details plus its non-null fields with `type: "inference"`. The journal's `session-start` gains `reads`
(the primitives that are not mutating) so that the export can leave read-only raw calls out (`ExportOptions.includeReads`
keeps them); `ExecutorSession.promptsHandledUpTo(seq)` also raises the highest seen sequence number. The CLI depends on
`skillroy-inference`, `skillroy-mcp` and `slf4j-nop` 2.0.16 (the `slf4j-api` version the SDKs bring); the distribution
zip is 48,687,408 bytes with 43 jars (7 project, 36 third-party; `anthropic-java-core` alone 37 MB) against about 3.9 MB
of jars before Phase 5, and `THIRD-PARTY.txt` lists 36 artifacts with the MIT and MIT-0 texts and the MPL-2.0 note.
`ci/run-in-linux.sh` passes `ANTHROPIC_API_KEY` and `ANTHROPIC_WORKSPACE_ID` into the container by name when set.

As built (2026-10-08, fix wave). A resolve of a session that has a session service (`--inference` other than `none`)
goes through the service's metered entry (`SessionService.resolve(slot, kind, args, strategy, verify, credential,
origin)`; the REPL passes no verifier list), so that a model token is reserved and settled on the session's budget and
shows in its `budget` record (§12.4, D74): the `session` REPL's `resolve`, and `mcp`'s `skillroy_resolve` through the
server's actor (made on first use; without a gateway there is no actor and the session resolves, no model token being
servable then). Both hosts write, when the session or the server ends, the records of the resolutions' model calls
(`InferenceLog.forLedger(ledger)`, kept with the ledger by the contributor) into `inference.jsonl` beside
`cost-ledger.jsonl`, through the credentials' mask, after the session model's own records (no record, no file); `mcp`
writes the session's `cost-ledger.jsonl` when the server ends whether or not its actor was ever made. `mcp` gains
`--allow-evaluate`, which offers `web_wait_flag`, and with `--mutating` `web_evaluate` too (D98), and ends with exit
code 1 when the client breaks the protocol (a line longer than 8 MiB, §9.3). `author` reads the planner's deadline
from `timeouts.plannerMs` and checks a `--run` against the planner's server's projection (§10.2, D96), and runs it with
every expectation on masked values; `triage` builds its options from the settings (`TriageOptions.of`: `logPrompts`,
`effort.triage` and the model ids, §11.2). The CLI's session service opens its gateway with the session's prompt mask
(`Gateways.open(settings, mask)`), so that the recording gateway of `record: true` writes through it (§4.2).

(Phase 6, built 2026-10-09, TL; P6 §10.3.) `run`, `session`, `mcp`, `re-resolve`, `triage` and `author` take the
telemetry options (`--telemetry`, `--telemetry-endpoint`, `--telemetry-attributes`, `--telemetry-file`); `mcp` prints
its telemetry line on stderr; `re-resolve` and `triage` export their root span, `author` its root span, its planner's
model calls and its bind and run (the planner's own server child exports nothing).

(Phase 7, built 2026-10-10, VM; P7 §9.3) `skillroy session` gains `--vision v1 | v2 | v3 | off` (default v1 when the
vision module is present, a gateway is named and `images: true`), `--vision-model`, `--vision-tools`, `--allow-robot`
(required for `v3`), `--play <scenario.yaml>` and, with it, `--export <id>`; its start-up line names the vision tier or
why it is off. `skillroy mcp` gains `--vision v1 | v2 | v3` (default off) and `--allow-robot` (V3 inside
`skillroy_session_ask` only).

### 13.5 JUnit

`ScenarioTests` is unchanged in its contract: `replay` streams need no gateway; `approve` and `live` streams with
`llm-sql` slots work when `skillroy-inference` is on the class path and `skillroy.inference` names a gateway (the
properties of §4.4); `skillroy.mode=re-resolve` is the single failing `environment` test with the message of §8.1. The
trailing `skillroy: zero model calls` test is unchanged.

---

## 14. The sample

### 14.1 Pack (`skillroy-sample-lending-pack`, version 0.4.0)

(Phase 6, built 2026-10-09, CP; P6 §6.3, §11.3) The pack is **0.5.0**: its hook catalogue
`META-INF/skillroy/hooks/lending.yaml` (`limit.applied`, `access.checked`, `waiver.evaluated`, `sign.on.checked` off,
`line.amount`), `settings()` (`lending.maxReturnSetting = max_return`, which the `SystemLimit` resolver reads through
its context), and the public surface an overlay needs (`LendingResolvers` with its resolver classes, their ids and SQL
texts and constructors taking a statement; `LendingAllowList.of(limitColumn, maxReturnSetting)`; the verifier ids as
`LendingPack` constants). The allow-list's descriptions are unchanged for the standard desk, so scenario D's recorded
requests still match; the session's scripted stand-ins, whose vocabulary names the pack version, were regenerated by the
fixture update path. Gap 49 (`LendableItem` without induction) is not taken. The sample application gains the branch
edition (`lending.edition`), and `AccessControl.check` reads the return limit through `limit(code, LIMIT_RETURN)`; the
agent module's `app.state` gains `edition`.

- **`sqlAllowLists()`**: one list for source `lending`, dialect `HSQLDB`: the tables `staff` (`code` key; `name`
  personal; `active`; `password` **not** allow-listed: it is the secret column, masked by `PASSWORD_MASK`),
  `staff_role`, `role_permission` (`permission` sampled), `role_cap`, `desk_setting` (`key` sampled), `item` (`code`
  key; `per_day` sampled), `waiver_rule` (`kind` sampled), `waiver_rule_item`, and the four stored tables of
  `LendingTables.ALL` with their keys; the exact column lists are the schema's as the fingerprinter reads it, and a test
  proves every allow-listed column exists (§5.2). No procedure, no extra function.
- **`argsSchema`** for every verb of the pack's `StepLibrary` (§9.5), with references typed as described there; a test
  compiles every committed scenario's steps and checks each step's arguments against its verb's schema with the
  validator of §13.3.
- `ValueLoader`s (§5.4a, D90) for `StaffWithPermission` (identity `code`; loads `StaffRef` with the staff resolver's own
  reads) and `LendableItem` (identity `code`; loads `ItemRef`), so that scenario D's supervisor can be found by a model.
- Nothing else changes in the pack: the queries, resolvers, verifiers, oracles, cross-check and fingerprinter stand.
  The version becomes 0.4.0 (the allow-list is new pack surface).

As built (2026-10-08, S2): `LendingLoaders` registers `StaffLoader` (identity `code`; the staff resolver's two reads and
the building of a `StaffRef` are factored out and shared; refusal `UNKNOWN_STAFF`; the query's conditions are not
re-applied, the access check covers them) and `ItemLoader` (identity `code`; the item read shared with its resolver;
refusal `NOT_LENDABLE`; the query's deposit range is applied, since the `lending.deposit` verifier never checks it);
`ValueLoadersOnRealSchemaTest` proves on both seeds that every key the tier (a) resolvers answer gets the identical
value, that unknown keys are refused with a reason and that loaded values pass the verifiers. As built (2026-10-08, S1,
before the loaders): `LendingAllowList` with the fingerprinter's twelve tables, every
non-secret column readable (`staff.auto_sign_on` included: the committed staff SQL reads it), `staff.password` the one
`otherColumns` entry, `desk_setting.name` sampled (the column is `name`, not `key`), the schema's own types (`BIGINT`
for the loan ids); `AllowListOnRealSchemaTest` (not `PackOnRealSchemaTest`) proves the list against the embedded
database's schema (a primary-key column counts as NOT NULL, since HSQLDB's schema view reports it nullable); the
`argsSchema`s (`LendingStepSchemas`) are top-level objects with `additionalProperties: false`, references typed as the
referenced value plus "or a $reference", the verbs' numeric bounds and "exactly one of" rules in descriptions only (the
provider's schema limits); a step written without a value (`- signOff:`) is therefore valid to the compiler and
invalid to the schema, and `quantity` as text likewise; `ScenarioSchemaCommittedTest` validates every pack step of the
sixteen committed and negative scenarios against its verb's schema.

### 14.2 Scenario D: `supervisor-return-llm-sql` (tier (b) end to end, no key)

Scenario A's YAML (`return-over-limit-needs-supervisor`, P2 §13.4) with one change: the supervisor slot's strategy is
`[llm-sql]`, so that the pack's committed `lending.sql.StaffWithPermission` is not asked and the model's SQL finds the
supervisor; the assistant and the item keep their committed strategies (two tiers in one scenario, one bindings file).
Its recordings (§4.2) are made once, live, by a maintainer with the key, for both seeds, and committed; the bindings
files for `seed-a` and `seed-b` are written by `approve` against the recorded gateway with `--approved-by
"recorded 2026-10-08"` and committed. In the acceptance (§15.2) `replay` runs it on both seeds in every load mode with
zero model calls, and `approve --yes` against the recordings reproduces the committed bindings files byte for byte.
The verifier `lending.access.check` is the gate on the model's answer as on the committed one; `ExpectedAuthorisation`
and the rest of scenario A are unchanged, so the scenario still tests what scenario A tests. As built (2026-10-08,
S1): the YAML and the version-5 plan (`inference: bind`, pack 0.4.0; a test checks that the plan equals scenario A's
apart from the id, the source hash, `inference`, the line numbers and that one strategy) are committed; the bindings
files and the recordings wait for the key (`ScenarioD.HAS_BINDINGS` keeps the bindings-dependent tests and the JUnit
glue's end-to-end check away from it until then); the supervisor's `StaffRef` is completed through the pack's
`ValueLoader` (D90). **As built (2026-10-08, S2): the recordings are scripted stand-ins until the key works**: no live
model call was possible (the key's workspace id, `../TODO.md` "Walls"), so `ScenarioDRecordedTest` with
`-Dskillroy.inference.fixtures.update=true` binds scenario D in `approve` on both seeds through a `RecordingGateway`
around a `ScriptedGateway` whose hand-written `SqlProposal`s (one `SELECT`, five `?` parameters bound to the query's
arguments, confidence 0.9, rows 1..5) find the same supervisor the committed SQL finds; the seven recordings carry
`provider: "scripted"` and the bindings files the label `recorded 2026-10-08 (scripted stand-in)`; with the key,
`-Pinference-record` re-records them live and only the provider and the model answers change. `ScenarioD.HAS_BINDINGS`
is true. The headless acceptance (13 tests on both seeds): `approve` without reuse makes one recorded call and writes
the committed file byte for byte (apart from the channel: `jdbc` headless, `agent` in the committed file), with reuse
none and the same file, `replay` no call, a changed catalogue `ENVIRONMENT` "no recording", a reject-corpus `DELETE`
`RESOLUTION_FAILED` with the guard's reason, the nightly dry run `UNCHANGED` for A, B and D.

The `-Pinference-record` re-record of the S2 note above, built 2026-10-08 in the live session (D111; until then
specified only, D110, the route that existed being `record: true`, §2.3). A live re-record changes more than "the
provider and the model answers": the provider, the model, the statement, the rationale and the usage of the recordings
and of the bindings files written from them, and their `approval.by` label. `ScenarioDRecordIT` (§4.2) makes the
recordings live, one request per seed on the Sonnet class at `effort.resolve`, and writes both bindings files through
the recorded gateway with the label `recorded <date>`; the assertions that pinned the stand-ins
(`ScenarioDRecordedTest`, `BindingsUpToDateTest`, the image's items 2 and 7, the label `ScriptedAnswers.APPROVED_BY`)
now pin what a live answer must hold and nothing a model may word differently: the recording's provider `claude` and a
model id of the Sonnet line, a confidence at or above the settings' threshold, a non-empty rationale, one guarded
`SELECT` over the allow-listed tables ending with the injected `LIMIT 200`, the value loader's statement in the
provenance, and above all the value: the supervisor the committed SQL finds on the same seed (the verifier
`lending.access.check` is the gate, as in scenario A). The label is read from the committed file itself
(`ScenarioD.approvedBy(env)`, which must match `recorded <date>`), so that a later re-record changes the files and not
the tests; the stand-in generator of the tier (b) answers is gone (§4.2), and the session's recordings stay scripted
(§14.5). **The first live re-record (2026-10-08, `ScenarioDRecordIT`, natively, USD 0.029):** both seeds' requests
were answered by `claude-sonnet-5-5` at effort `low` on the first attempt, each statement passed the guard and the
desk's access check, and each found the supervisor the committed SQL finds (scenario A's value on the same seed); the
recordings carry `provider: "claude"` (seed A's with an `opaque` thinking part, empty text and a signature, before the
structured answer; seed B's with the answer alone), confidence 0.8 (the threshold exactly; the stand-ins said 0.9), a rationale in the model's words and one
guarded `SELECT … ORDER BY s.code LIMIT 200` over `staff`, `staff_role`, `role_permission` and `role_cap` with four
parameters (the stand-in's statement had five and treated a missing limit as unlimited; the model's requires a cap
at or above the deposit, which on both seeds names the same first supervisor); the bindings files carry the label
`recorded 2026-10-08`, the model, the request id and `costUsd: "0"` (written through the recorded gateway). Seed A's
call: 475 tokens in, 877 out, 5024 written to the cache, USD 0.0223, 9.5 s; seed B's four seconds later: 476 in, 515
out, 5024 read from the cache, USD 0.0071, 4.0 s. The fingerprints did not change (the requests did not), so the
file names stayed.

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

As built (2026-10-08, the live session): authored in the Linux image (the arm64 build, natively) against the desk
launched with the agent on seed A, with a Linux build of the `claude` CLI (2.1.294) mounted on the container's `PATH`
(`ci/run-in-linux.sh`, `SKILLROY_CLAUDE_BIN`; the image and the repository carry no CLI, B3) and the key passed through
by name: `skillroy author --brief-file … --id return-refunds-deposit --run --out <the sample's scenarios directory>
--pack … --env seed-a --credentials …`, Opus 5.5, 43 turns, no repair round, USD 0.80, 161 s with the checks; the
plan compiled, both slots resolved, the scenario ran green on seed A. The orchestrator reviewed it on the owner's
instruction and kept it as written; the `review` section of `return-refunds-deposit.authoring.json` records the two
readings the brief needed ("an item that needs no induction" cannot be stated through `LendableItem`, which has no
`inductionRequired` argument, and holds by the resolver's order on both seeds; "the loan's total is back to zero" is
the net of the lent posting and the return posting the desk makes, each checked). The bindings of both seeds were
approved with `skillroy resolve` (label `approve`, tier (a) only). The planner's envelope is the fixture
`src/test/resources/author/return-refunds-deposit.envelope.json`, beside `return-refunds-deposit.authored.yaml`, the
YAML as the planner's post-processing rendered it (equal to the committed one today), which the replay test of §15.1
item 11 compares with. The live acceptance's own authoring (§15.3 item 5, 35 turns, USD 0.91) produced a scenario of
the same shape, which is not committed. The owner's own review of the committed scenario stays open.

### 14.4 An interactive session on the desk, exported and replayed

A scripted session (`skillroy session --script`, the gateway `recorded`) in which the person signs on through a verb,
asks the model to lend one plain item to the desk's walk-in borrower and settle the deposit, checks the loan through a
fact, and exports the journal as `session-basic-loan`; the export's YAML, plan and bindings file are compared with the
committed copies (byte-identical: the recorded model answers make the session deterministic), compiled, and replayed
with zero model calls on seed A in the acceptance. The same journal, read back from its file, exports the same scenario
(the export is a function of the journal). As built (2026-10-08, S2): headless on a `HeadlessDesk` (the application's
real engine, access rules, catalogue, waiver rules and slip printer in the test JVM with a fixed clock and no events),
which makes the recorded session deterministic enough to commit its export under `scenarios/exported/`
(`session-basic-loan.yaml`, `plan/`, `bindings/seed-a/`); the person resolves a staff member and signs on through the
verb, the model (five recorded calls) resolves an item, scans, presses DUE and takes the deposit, then `done`; the
person checks `loan.total` and `state.name`; the export equals the committed copies byte for byte, is valid against
the pack's schema and replays on a fresh desk with zero model calls. On the live desk in the image a recorded session
cannot replay (each request carries the snapshot hash and the event topics, which depend on the desk's date and the
engine's timing), so the image drives the model with the scripted session script answering by position, and its
export must still equal the committed copies.

### 14.5 Recordings and the two profiles in the sample

`src/test/resources/inference/` holds the recordings of §14.2 (live since the afternoon of 2026-10-08, §14.2) and §14.4
(the session's scripted stand-ins; as first built, 2026-10-08, scenario D's were stand-ins too); no triage recording is
committed, since a triage request carries the failed run's own durations, timestamps and business date, so the image's
check records its own two failed runs' triage and replays it in the same run; the planner's envelope of scenario C is
the fixture of §14.3 once it is authored. `-Pinference-record` re-records scenario D's requests natively
(`ScenarioDRecordIT`, below); `-Pinference-live` runs §15.3, in the image with the key passed through the environment
(`ci/run-in-linux.sh` passes `ANTHROPIC_API_KEY` into the container by name when it is set, without printing it, and
mounts a Linux `claude` for item 5 when `SKILLROY_CLAUDE_BIN` names one).

Both profiles of this section, built 2026-10-08 in the live session (D111; the mechanics in §2.3): `-Pinference-record`
runs `ScenarioDRecordIT` (§4.2, §14.2) natively, since it binds through the controller-side JDBC channel against the
sample's database in the test JVM; `-Pinference-live` runs `ClaudeGatewayLiveIT` natively and `InferenceLiveIT` in the
Linux image with the key passed through the environment (§15.3). The stand-ins that remain are the session's
(regenerated with `-Dskillroy.inference.fixtures.update=true`, §14.4) and the triage answers the image's check records
for its own failed runs: a live session is a new session with its own answers, not a refresh of the recorded one, so
the image's session check (§15.2 item 4) keeps driving the scripted session by position and comparing its export with
the committed copies, and a triage request carries the run's own times.

---

## 15. Acceptance

### 15.1 Without a display (unit and module tests; the normal build; no key, no `claude` CLI)

1. **The SPI**: `Schemas.ofRecord` on the records of this contract and the sample's queries (compliant schemas; the
   refused shapes refused); `ModelRequest.fingerprint()` stable across part order of maps, independent of `maxTokens`,
   `cache` and `metadata`, sensitive to a one-character change of any text; `Structured.decode` through the codec.
   As built (2026-10-08, review fix wave): `Schemas.ofRecord` serves the records of this contract that a model answers
   with (`SqlProposal`, `TriageLabel`, `TriageSummary`; `SchemasTest`, `StructuredTest`); no main code path applies it
   to a pack's query record: the planner, the session service, the MCP server and the scenario schema describe a
   query's arguments with `Schemas.ofArguments` (references allowed; `Planner`, `SessionService`, `ToolCalls`,
   `ScenarioSchema`), so the sample's queries are proven through that schema (`ScenarioSchemaCommittedTest` validates
   every committed and negative sample scenario against the pack's scenario schema), not through `ofRecord`.
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
   and CTE resolution, the `personal` rule; the sample's allow-list against the real schema (`AllowListOnRealSchemaTest`:
   every allow-listed column exists; every accept-corpus statement over the sample's tables also executes on the
   embedded database). As built (2026-10-08, review fix wave): BS6's corpora run on BS6's own schema in the inference
   module (`HsqldbExecutionTest`, `lending-schema.sql`), which is not the sample's; `AllowListOnRealSchemaTest` proves
   the list's tables, columns, types and keys against the sample's `LendingDb`; the sample's own accept corpus,
   `corpus/lending-accept.sql` in the scenarios module (BS6's format, with `<<` lines for the rows read back), ten
   statements that together read all twelve allow-listed tables, is guarded with the pack's allow-list, each rewrite
   compared with the corpus's byte for byte on two runs, and run by `LendingAcceptCorpusTest` on `LendingDb` (seed A,
   business date 2026-10-08, as the read-only account, with two posted loans the test inserts for the stored tables)
   with the corpus's parameters, the rows read back equal to the corpus's.
6. **`LlmSqlResolver`** with a scripted gateway against the sample's pack on the embedded database (the `JdbcDataChannel`
   of the scenario tests): a good proposal yields candidates in statement order that the engine verifies; `cannotAnswer`,
   low confidence, a guard violation, a param that matches no argument, a row count outside the bounds, a shape
   mismatch, a refusal, `NO_RECORDING`, a retryable error retried once; the ledger entry, `Resolution.usage` and
   `confidence` filled, the provenance's statement and notes; the budget exhausted before and after a call. As built
   (2026-10-08, review fix wave): `LlmSqlResolverTest` runs these on a mirror pack over BS6's schema (`DeskPack`, the
   embedded database of `lending-schema.sql`), since the inference module cannot depend on the sample's pack; the
   sample's pack itself, on its own database, covers the good path, the guard's refusal and the missing recording
   (`ScenarioDRecordedTest`, both seeds).
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
   command with the reuse rule on (the default, §7.1) writes the identical file with `model calls: 0` (as built: an unchanged file is not rewritten, approve step 8, so the outcome names no written file and the file on disk is the identical one); a deliberately
   changed prompt (a test flag that appends a word to the catalogue) ends `ENVIRONMENT` with "no recording"; a
   scripted gateway's reject-corpus statement ends `RESOLUTION_FAILED` with the guard's reason.
3. Scenario C replays green on both seeds; its plan and the committed one are identical; its `.authoring.json` is
   present.
   As built (2026-10-08, the live session): `ScenarioC` (the test helper, as `ScenarioB`), the replay check of
   `ResolutionChecks` (`@Order(14)`, after scenario B's, on both seeds in every load mode: the committed plan runs in
   `replay` with the committed bindings and passes at the first attempt of every check, no resolver, every verifier
   once, zero model calls, the lent and the return postings read back through their rows, `lending.loan` and the slip;
   the `.authoring.json` present), the plan and bindings shape tests (`PlansUpToDateTest`, `BindingsUpToDateTest`,
   `DiffCommittedBindingsTest`: the seeds differ in the item, the assistant, the refund and the fingerprint only), the
   scenario count of `ScenarioSchemaCommittedTest` (17, the authoring record not a scenario), a headless replay on
   both seeds (`ScenarioCReplayTest`, the same assertions through `ScenarioC.assertReplayed`; `HeadlessDesk` answers
   `sync.await`), and `AuthorEnvelopeTest` for §15.1 item 11 on the real envelope (the YAML the post-processing
   renders equals the pre-review fixture byte for byte, head included, under a clock at the authoring's date; the
   `--json-schema` argument equals the generated schema without `$schema`; the authoring record and the ledger hold
   the envelope's session, turns and cost; the envelope file written equals the fixture). The plan lives in `plan/`
   beside the other scenarios' (the CLI looks for a plan's bindings beside its directory, D31). The planner wrote no
   `requires` list, so the plan requires the verifiers' capabilities only; and its slip check follows a wait for
   `loan.posted` alone (the desk prints the slip after it posts), which held at the first attempt in every run so far
   (600 headless replays by the implementer; the image's runs): if it ever retries, a `waitFor slip.printed` after the
   return's posting is the change, with both seeds' bindings re-approved.
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

As specified (2026-10-08, the live session; D111), built in the same change as the first live run (until then no test
of this section existed and `-Pinference-live` only made Maven warn, D110). `ClaudeGatewayLiveIT` (`skillroy-inference`,
natively) runs items 1 and 2 through `ClaudeGateway.open` on the default settings: item 1's system part is a
lending-vocabulary text of about 600 tokens marked `cacheBreakpoint` (above the 512-token minimum of the Sonnet line)
with a small structured answer; the first call must write the cache (`cacheWrite5mTokens > 0`), the second, within a
minute, read it (`cacheReadTokens > 0`), `costUsd` is positive and the pair costs under USD 0.05, and `countTokens` of
the same request is within 10 % of `inputTokens + cacheReadTokens + cacheWriteTokens` of a call; item 2 submits two
requests (`maxTokens` 64, distinct questions), polls `batchStatus` every 15 s for at most 20 minutes (a batch that has
not ended by then is cancelled and the test fails with the last status), collects, and asserts each result succeeded
with the answer of its own request (matched by index, not by order of arrival). `InferenceLiveIT` (the scenarios
module, in the image; the desk launched on seed A with the agent as `InferenceChecks` launches it) runs items 3 to 6 in
order, each with its own bound: item 3 binds scenario D in `approve` without reuse through the live gateway without
recording, under the settings' scenario budget (USD 0.50), and asserts the guard and the verifier passed, the value
equals the committed binding's and the one ledger entry names provider `claude` and a positive cost; item 4 runs the
nightly job over copies of the committed files in a temporary bindings root (seed A, job budget USD 1.00, batch wait
20 minutes; written, not a dry run, so that the written file can be read): once over scenarios A and B with the label
`approve` (no model: both `UNCHANGED`, no request), then over scenario D with the committed file's label twice in a row,
asserting for each D job that the scenario is `CHANGED` (a live answer carries a new request id and cost, so the file is
never byte-identical) with the written supervisor equal to the committed one, one batch of one request, the cost under
the budget, and for the second job a cache-hit ratio above 0.80 (the exit criterion, read as §8.3 says: the first job's
request writes the one-hour prefix the second reads, as a job's second tier (b) request would; a one-request job on a
cold cache reads nothing by construction), and prints both jobs' figures (cost, tokens, ratio, duration), which
`../TODO.md` records; item 5 is
skipped with "no claude on the PATH" when the CLI is absent and otherwise runs the assembled launcher's `author` as a
subprocess with the brief of §14.3 (id `live-return-refunds-deposit`, `--budget-usd 2.00`, the default turns and
repairs, no `--run`, `--json`) under the planner's deadline plus a minute, and asserts exit 0 with `checks.compiled` and
`checks.resolved` true (the authored scenario is not compared with the committed one); item 6 signs on as §14.4's
script does, asks the live session service one turn (`SessionPolicy.acting()`, the settings' session budget USD 1.00)
"lend one plain item to the walk-in borrower", asserts the turn ended `DONE` within `maxActionsPerTurn` with a
non-empty answer, exports `live-session-basic-loan` and compiles the export. Every file the class wrote passes the
secret scan of §15.2 item 8 last. The figures of the first live run are in `../TODO.md` ("Status at a glance", the live
table).

As built (2026-10-08, S2, `InferenceEndToEndIT`, eight nested classes, four load modes and two seeds): item 1 in every
mode on both seeds (scenario D alone through the JUnit glue with its trailing zero-calls test); on each seed with the
agent on the command line items 2, 7 and 8 (the secret scans cover `inference.jsonl`, `journal.jsonl`, the exports,
`triage.*` and the report); on seed A items 4 (the session with the scripted model script, its export compared with
the committed copies and replayed) and 6 (triage over two failed runs the class makes itself); item 7 takes two dry
runs, because A and B are committed with the `approve` label and D with the stand-in label and one job writes one
label; item 5 and the command form of item 4 were written against the CLI's commands and marked disabled until the
CLI wave landed. Item 3 (scenario C) waits for the live planner run.

As built (2026-10-08, review fix wave), §15.2 items 5 and 8 of the note above (item 5 and the command form of item 4
run since the CLI wave landed): item 5's verb through the session entry has an effect and its outcome is asserted: the mutating server enters the code of seed A's auto-sign-on desk assistant
(`lending_enter`; a `signOn` needs a slot with a credential, which `skillroy_resolve` over MCP cannot declare, D94) and
signs off (`lending_sign_off`), each result `PASS`, the desk `IDLE` and then `SIGNED_OFF`, both steps in the server's
journal under its `--out` (`lending_sign_off` alone on a desk signed off already is refused by the desk, which
`SessionEntryVerbTest` shows on the headless desk); item 8's scan runs last, after item 5 and the command form of item
4, over every check's outputs except the credentials files the checks write themselves (left out by name), with the
matcher `SecretScan` (the strings of JSON files, the JSON in labelled blocks and in escaped JSON text read the same
way, the rest split on whitespace, quotes, commas, colons and backslashes; `SecretScanTest`), which `SessionExportTest`
uses too; the launcher subprocesses of the command form of item 4 are destroyed, with their descendants, when they do
not end in time (`Processes`, `ProcessesTest`).

As built (2026-10-08, the live session; D111), `ClaudeGatewayLiveIT`: each test begins with `Live.assumeLive()` (the
property `skillroy.inference.live`, then the key's presence, its value never kept or printed), so a build without the
profile reports both skipped with "the profile inference-live is not active". Item 1's system part is the desk's 22
rules, 2886 characters (about 720 tokens counted as characters / 4), in a `SONNET_CLASS` request at `Effort.LOW`,
`maxTokens` 64, `CacheHint.SHORT`, with the strict `{answer: string}` schema; the first call must have written the
prefix or read it (a prefix an earlier run left warm within its five minutes is read, not written; the test prints
which), and the second, made as soon as the first returns (the gap is printed, not asserted), must read it. Item 2's
questions are answered by rules 6 and 20, so each result is checked by the number its own question names and not the
other's; a batch that has not ended at 20 minutes is cancelled, and so is one whose status read failed. Each call
prints one line: the request id, the model, the stop reason, the tokens, the cost and the elapsed time.

As checked live (2026-10-10, natively on macOS, `claude` 2.1.286, after the Phase 6 fix that moved the planner's
prompts to stdin, SD): item 5 run alone (`-Dit.test=InferenceLiveIT#theAuthorCommandAuthorsTheBriefOfScenarioC+
noSecretInAnythingThisClassWrote`) exited 0 after 49 turns with no repair, compiled and resolved, the secret scan
green, USD 1.11. A second run, `author` through `--claude-command` naming a logging wrapper that renamed the first
document's first verb to an unknown one, forced one repair round: two `-p` invocations with their prompts on stdin
(14625 and 374 bytes; no text of the brief, the instructions or the problems in any argument; each command line ending
with `--max-budget-usd`), the second `-p --resume <session_id> … --max-budget-usd 0.80` (what was left of 1.70) with the
schema's problem as its prompt; the session resumed and answered with the original document, without the unknown verb;
`repairs: 1`, exit 0, compiled and resolved, 43 turns (41 + 2), USD 0.94. A resumed envelope's `total_cost_usd` and
`modelUsage` are the session's running totals, while its `num_turns`, `usage` and `duration_ms` are the invocation's
own; the ledger holds 0.8965614 and 0.0483568, the difference of the totals, which add up to the last total, 0.9449182.
Not checked live: a repair started by a problem that passes the schema and fails only in `PlanCompiler` (the same path
in the code), a failed repair, Windows.

Exit criteria of the roadmap (design §8): (1) is §14.3 with §15.2 item 3; (2) is §15.3 item 4; (3) is §14.4 with §15.2
item 4; all three are also exercised without a key through the recordings.

(Phase 7, built 2026-10-10, VM; P7 §13.3) `VisionLiveIT` in the sample scenarios module (the
items of P7 §13.3) joins the profile; it skips without the profile or the key like the others.

---

## 16. Decisions taken in this contract

| # | Decision | Alternatives rejected | Reason |
|---|---|---|---|
| D70 | `skillroy-inference` reaches a bind through the `ServiceLoader` SPI `ResolverContributor` of `skillroy-scenario`, with string-only `InferenceSettings`; the engine never loads a contributor in `replay` | packs depending on `skillroy-inference` and registering the resolver themselves; `skillroy-scenario` depending on `skillroy-inference` | a pack must stay buildable and testable without a model SDK; P2 D1 forbids the reverse dependency; the `WebPath` precedent (D49) works |
| D71 | The recorded gateway is keyed by a fingerprint of the request's content and the recordings are committed, masked, byte-stable files; a missing recording fails loudly | mocks per test; a cassette keyed by call order; a tolerant match | a prompt change must be visible as a build failure and reviewable as a file diff; order-keyed cassettes break on any refactor; a tolerant match would hide a changed prompt |
| D110 | The live profiles `inference-live` and `inference-record` (§2.3, §4.2, §4.4, §14.2, §14.5) and the live tests of §15.3 are marked specified, not built, and the sample's documents say what a live re-record changes; the profiles, the `*LiveIT` tests and the record route are built in the same change as their first live run and BS13's live part (2026-10-08, review fix wave); done the same day in the live session (D111) | building the profiles and the `*LiveIT` tests now, unrun; leaving the sections as they read | nothing live can run until the key's workspace id exists (`../TODO.md`, Walls): tests built now would be committed unrun (this phase's first image run of a never-run class failed two checks), §15.3 rests on facts BS13's live part has not confirmed, and which recordings a live run replaces needs real answers in hand; meanwhile a profile that does not exist must not read as a pass, nor a document promise a re-record with "no code changes" |
| D111 | How the live profiles work (§2.3, §4.2, §8.3, §14.2, §14.5, §15.3; 2026-10-08, the live session): explicit `-P` profiles that set one system property each for the test JVMs; `*LiveIT` failsafe classes that skip without the property or without the key; `ScenarioDRecordIT` as the record route, which records live and rewrites the committed bindings through the recorded gateway with the label `recorded <date>`; the tier (b) stand-in generator removed; the label read from the committed file; the cache-hit criterion read on a warm prefix (the one-request job run twice); the gateway sends no `effort` to the Haiku class and treats a dated model id as its alias (BS13 live) | a profile activated by the key alone; a profile that selects the tests by include pattern; keeping the stand-in generator beside the live recordings; a label constant; reading the ratio of a cold one-request job | a profile on the key alone would make every `verify` with a key call a model; an include pattern makes a missing profile read as a pass, a skipped assumption reads as what it is; a generator kept beside live recordings is a route that overwrites them; a constant would make every re-record a test change; a cold one-request job reads nothing by construction and measures nothing |
| D112 | The planner keeps the envelopes of its invocations as `<out>/<id>.envelope.json`, masked (§10.2 item 7; 2026-10-08, the live session) | the envelopes discarded with the private temporary directory; the envelopes inside `.authoring.json` | the fixture of §15.1 item 11 is the real envelope of the real run (§14.3), and a reviewer sees what the planner answered in each round; `.authoring.json` is the summary a person edits and commits, which the envelopes would swell |
| D113 | The default confidence threshold of the tier (b) gate is 0.6 (§4.4, §5.4; 2026-10-08, the live session) | keeping 0.8 (ai-inference R13); dropping the gate (the verifier alone, the Q3 veto); raising `effort.resolve` | measured live: Sonnet 5.5 at effort `low` said 0.8, 0.8, 0.8 and 0.7 on four correct statements, so 0.8 refuses a correct answer in about one case of four while the verifier catches a wrong one either way; 0.6 keeps the gate for a model that says it is unsure and leaves routine answers alone; confirmed by the owner on 2026-10-09 (B25) |
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
| D94 | The MCP server's `skillroy_resolve`, the session service's model-side resolve and the CLI's `session resolve` can declare a credential for a slot (`--credential <realm>.<key>` in the REPL; the tools' `credential` argument is open), since a sign-on slot cannot be declared otherwise (2026-10-08, C; the tools' half is open) | no credential from a session | the person-signs-on session of §14.4 is impossible otherwise |
| D95 | The CLI's `session` closes with `recoverOnClose` on: after a mutating call the pack's `recover` runs when the session ends, as after a run (2026-10-08, the matrix fix wave; the MCP server keeps its options' value) | leaving the target as the person left it; an option | the session's purpose is an export that replays, and a replay starts from a recovered target; the §14.4 flow (session, export, replay) is otherwise a trap |
| D96 | A plan run on a client's behalf (`skillroy_scenario_run`, `author --run`) is checked against the server's own projection before anything runs: a base verb's call names a primitive the server offers as a tool (not `data.query`; `trace.mark` for `mark`), a pack verb's or a cleanup call names none it never offers (destructive, `agent.*`, `data.query`, `web.evaluate` and `web.waitFlag` unless opted in), no call's arguments hold `confirm` or `_meta`; a refusal names each step and primitive; every expectation of such a run is evaluated on masked values; the scenario schema's `call` names only what the host offers; the same rule withholds a pack verb's tool whose compiled calls (with probe arguments, at start) reach what the server never offers, logged and listed in `skillroy_catalogue` (2026-10-08, review fix wave) | no check (a scenario stronger than the tools); the positive rule for every call (it refuses a pack's verbs over another module's primitives, which the verb tools run already); stripping `confirm` instead of refusing (the client's scenario would silently not be the one it wrote) | design §3.4 and D76: the server never confirms or runs a never-offered primitive for a client, whose request is often a model's; the planner's server runs under `dontAsk` with no person in the loop; PASS or FAIL on an unmasked credential would rebuild it character by character |
| D97 | Over MCP a model-found (tier (b)) binding is always exported `REQUIRED`: `skillroy_session_export` takes no `approvedBy` and ignores one sent; a person approves with `skillroy resolve`, which reuses the recorded value without a model call (D82). Confirmed by the owner 2026-10-08 (taken 2026-10-08, review fix wave); it narrows Q12 for MCP, where the exporter is a client | the client's label as the approver (Q12 as written); a label given on the `mcp` command line | a client is often a model, and a model's request is never a person's approval (D72, the server's own `REJECT_ALL` for `scenario_run approve`); the CLI's `session export --approved-by` stays, a person typing it |
| D98 | `web.evaluate` and `web.waitFlag` (each runs a caller's script in the target's pages; `web.waitFlag` is a read that evaluates an expression until it is true) are offered by no policy, read-only, mutating or the session's, unless a person starts the server with `skillroy mcp --allow-evaluate` (read-only: `web.waitFlag`; with `--mutating` both); the planner's server never has the flag, and the plan check refuses both (2026-10-08, review fix wave) | offering `web.evaluate` with `--mutating` and `web.waitFlag` as an ordinary read (§9.3's first table); never offering them over MCP | no model output reaches a page's script (design §9 risks; the session already excluded `web.evaluate`), and the planner's model runs under `dontAsk`; a person debugging a page by hand keeps the tools |
| D99 | `data_query`'s guard reads a copy of the allow-list in which every personal column and every column one of the pack's row masks alters is one of the table's `otherColumns`, so that a statement may not name it anywhere; a table keyed by such a column, or with no readable column left, is not readable by `data_query` (2026-10-08, review fix wave) | a guard mode that refuses personal columns everywhere; refusing filters only; trusting rule 4 ("filter, never select") | rule 4 assumes nobody sees the rows, but `data_query` returns them to the client, so a filter, an order or a function of a personal value rebuilds it row by row; the copy needs no new guard code, and the guard's own `COLUMN` rule does the rest |
| D93 | The rationale and the rejections of a tier (b) proposal reach the provenance through `ResolveContext.rationale(text)` and `reject(label, reason)` (defaults keep them as notes; the engine's context fills `Provenance.rationale` and `rejected`), and the bindings file writes `provenance.rationale` last and omits it when null, so that tier (a) files do not change (2026-10-08, I2) | the resolver building `Provenance` itself; notes only | the engine owns `Provenance`; a reviewer of a bindings file needs the rationale beside the statement (Q1 of §17); the file's byte-stability for tier (a) slots is preserved |
| D92 | A verb and a domain primitive that would share a tool name are both offered: the primitive's name takes the suffix `_primitive`, the verb keeps the plain name; every other collision refuses the start (2026-10-08, M) | hiding the primitive behind the verb; refusing the start on any collision | the sample's `deposit` verb and `lending.deposit` read collide, so the planner's mutating server could not start under the first rule, and one name meaning a lookup in read-only mode and an action in mutating mode would mislead a model |
| D91 | The record of a model call travels in `CostLedger.Entry.details`, and the run writer writes `inference.jsonl` from the ledger entries, beside `cost-ledger.jsonl` (2026-10-08, I1) | a sink passed through `BindOptions`; the contributor writing into a directory it is told | the contributor is created per engine and never sees the run's output directory; the ledger already crosses that boundary and is masked by the writer |
| D90 | A tier (b) row becomes a typed candidate through the pack's `ValueLoader` for the kind (the model selects the identity columns, the pack's committed reads build the value), with the flat column-by-name decoding only for kinds without a loader (2026-10-08, S1's finding) | the model aggregating lists and maps in SQL (`GROUP_CONCAT`, `ARRAY_AGG`); the resolver gathering several rows into one value; changing the sample's value types | aggregation functions are outside the guard's set and dialect-specific; gathering rows needs a grouping rule per type; the pack already has the code that loads a value from its key, and reusing it keeps the model to finding *which* row |
| D89 | A `confirm` argument of any call (a plan step, a cleanup call, a session step) is taken out of the parameters and sent as the request's `_meta.confirm` (2026-10-08, E1) | leaving `confirm` as an ordinary parameter | the agent reads the confirmation only from `_meta`, so a scenario's `confirm: true` never reached it before; no primitive in the base or the samples has a parameter of that name; a `MODEL` origin's `confirm` is stripped before this mapping (§12.2) |
| D88 | The Anthropic Java SDK is pinned to its current line at build time (2.69.x on 2026-10-08) rather than the research note's 2.34.0; the client is built explicitly from `ANTHROPIC_API_KEY` with the SDK's retries and logging off, and the gateway retries within its own deadline; MCP comes as `mcp-core` + `mcp-json-jackson2`; the root POM imports `jackson-bom` | staying on 2.34.0 with work-arounds (an untyped fallback parameter, no typed workspace id, unknown 5.5 content blocks); `fromEnv()`; the SDK's own retries; the `mcp` convenience artifact | spike BS13 (F-2, F-7, F-9, F-11): 2.34.0 predates the 5.5 models and the fallback betas; `fromEnv()` reads profiles, auth tokens and custom headers from the operator's shell; the SDK's retries are per attempt without a cap on `retry-after`; `mcp` brings a second Jackson |
| D87 | The session service offers the pack's verbs (and base primitives only by policy), one action at a time, with the model's `confirm` stripped; the overlay bridge rides the existing event stream and answers through a pack-named primitive | a free tool set; parallel actions; a dedicated transport for overlays | design §5 and B15: the verbs are what exports well, a model's request is never a confirmation, no new transport |
| D100 | An SQL allow-list is checked against its source where a data channel exists, not at compile time: `SELECT * FROM <table> WHERE 1 = 0` per table, the readable and other columns compared with the result's names without regard to case, a column a row mask alters refused; by the tier (b) resolvers once per bind before the catalogue (the statements recorded with the catalogue's samples), by the contributor's load for the row masks, by the MCP server at its start; key nullability unchecked (2026-10-08, the review fix wave) | the compile-time check §5.2 described; JDBC metadata; trusting the pack's list | no connection exists at compile time, and the agent's channel exposes no metadata; the guard's rule 4 is sound only while `otherColumns` names every column, and a later release of the application adds columns (L1 F2, L2 F12) |
| D101 | A session's tier (b) resolves are metered on the session's budget through `SessionService.resolve`: one tier (b) estimate reserved on the session meter first (refused `BUDGET` without room), the resolve's scenario budget capped at the session's room by a cap keyed by the session's ledger, the entries it added settled afterwards; every host routes a resolve of a session with a service through it (2026-10-08, the review fix wave) | a meter or budget seam in `BindOptions` or `ResolveContext`; tier (b) resolves outside the session budget | B15 gives a session one money budget and D74 ends its model calls at exhaustion; D70 keeps the scenario module's settings strings only, and both hosts already pass one ledger to the session's binds and to the service |
| D102 | A failed gateway call is a ledger entry: with the usage the provider billed, else with zero usage, cost 0, the provider and the model asked, its details the `inference.jsonl` record with `error` and the masked message; `model calls` counts it (2026-10-08, the review fix wave) | failures in the in-memory log only; a second sink for the run's writer | the run's writer sees only the ledger (D91), and 7.4 promises one record per gateway call; an entry of zero cost changes no budget, and `replay` makes no call to fail |
| D103 | The planner's brief is exempt from the prompt mask; the rest of its prompt, the pack's vocabulary, goes through it, schema values included and property names kept (2026-10-08, the review fix wave) | masking the brief; leaving the planner's prompt unmasked | the brief is the operator's own words, typed at the command line and holding nothing of the target or the environment's secrets, and masking it could change what was asked; the pack's text may carry a secret-looking default or example |
| D104 | The MCP server runs the allow-list check of D100 at its start only when the target offers `data.query`; a list the check refuses refuses the start (`ENVIRONMENT`), and a source the check cannot reach (not served, timed out, refused before it ran) is not read by `data_query`, which says so, while the server starts (2026-10-08, the review fix wave's reconciliation) | refusing the start for an unreachable source; offering `data_query` on an unchecked list; checking only the schema half at the start and leaving row-masked columns to D99's copy | `data_query` is one tool among many, so failing closed for that tool keeps a server usable for the UI while the guard's rule 4 is never trusted on an unchecked list; a list that allow-lists a row-masked column is a pack defect (P2 §4's rule), which the tier (b) binds refuse too |

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
| P2 §6.4 `ResolutionCatalogue` | `ValueLoader` and `Builder.valueLoader` (§5.4a, D90) |
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
