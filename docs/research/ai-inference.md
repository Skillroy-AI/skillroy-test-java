# AI inference for environment-dependent test data: resolution, verification, replay

Research note, 2026-10-01. Topic: the AI and runtime-inference layer of a test framework for Java desktop
applications. It covers resolver architecture, the scenario DSL, use of the Claude API and Agent SDK, oracles,
guardrails and the trust model. Product versions, prices and API details are as of the research date.

Ids are stable and intentionally non-contiguous. Items that described one specific proprietary application were
removed; the remaining ids are unchanged. Confidence values come from the original research. "Medium" marks a
claim whose only original evidence was a non-public application. It has not yet been re-verified on a neutral
target, and the item names the spike that will check it.

Base decisions this note informs: B3 (controller platform, planner invocation), B5 (DSL surface), B6 (CI and LLM
policy), B7 (model access), B13 (safety defaults).

## Summary

Practice in 2025-2026 has settled on one lesson. LLMs are reliable at authoring and resolving (plan to code,
intent to binding) and unreliable as the in-loop executor of a regression suite. The auditable pattern is "AI
proposes once, deterministic code verifies and replays". Examples are Playwright's planner/generator/healer,
Maestro's opt-in AI assertions and Healenium's logged deterministic healing. Oracle research points the same way:
LLM-written assertions tend to encode actual rather than expected behaviour.

The note therefore recommends a Resolver layer in the modern-Java controller with three tiers:

- (a) hand-written SQL or Java;
- (b) LLM-authored SQL, validated against a JSqlParser AST allow-list and run under a read-only database role
  with server-side statement timeouts;
- (c) LLM reasoning over primitive outputs from the live target application.

Each tier returns a typed Resolution that carries provenance, strategy, confidence and the result of a
deterministic verifier. Every accepted binding is written to a `bindings.json` fixture keyed by an environment
fingerprint. CI then replays with zero LLM calls ("resolve once, assert many"). Expected outcomes (oracles) are
computed by committed Java oracle functions from the same resolved rows and are never read back from the screen.
For example, waiver rules give the expected waiver, and role limits tell whether an authorisation prompt is
expected.

Everything needed on the Claude side is GA on the Claude API:

- structured outputs;
- adaptive thinking with effort levels;
- prompt caching (512-token minimum on Opus 5.5 and Sonnet 5.5);
- the Batch API at 50% off, for nightly re-resolution.

The defaults are Opus 5.5 for plan compilation and Sonnet 5.5 for resolvers. The Agent SDK (Python and TypeScript
only), with its hooks and `canUseTool`, is the vehicle for authoring-time agents and human approval. From Java,
run `claude -p --bare --json-schema --mcp-config`. The Messages API MCP connector cannot reach local servers, so
expose primitives as a local MCP server that the Agent SDK consumes.

Scenarios are YAML files with `resolve:` and `oracle:` clauses. They compile to a reviewable `plan.json` and run as
JUnit 5 dynamic tests. A fluent Java or Kotlin DSL is the typed layer underneath. Add a Gherkin adapter only if
business authors need one; Cucumber-JVM 8 needs Java 17, which is fine for the controller.

Two constraints shape the design:

- LLM latency is seconds to tens of seconds, so resolution happens before the scenario starts driving the
  application, not mid-transaction.
- Synthetic OS-level key events are unreliable against Swing, so computer use is a last-resort fallback.

## Findings

### F0: Playwright ships first-party AI test agents

Playwright has shipped first-party AI test agents:

| Version | Addition |
|---|---|
| 1.55 | codegen inserts `toBeVisible()` assertions automatically |
| 1.56 | planner, generator and healer agent definitions (`npx playwright init-agents --loop=claude`) |
| 1.59 | an agent CLI (`playwright-cli attach`, `--debug=cli`, `npx playwright trace`) |
| 1.62 | the MCP server is bundled (`npx playwright mcp`) |

The latest release is 1.63 (Java artifact 1.63.0, released 2026-09-16).

Evidence: https://playwright.dev/docs/release-notes ; https://mvnrepository.com/artifact/com.microsoft.playwright/playwright

Confidence: verified

### F1: Playwright's agent workflow is authoring-time and artifact-based

The Playwright test-agents workflow runs at authoring time and produces files:

- The planner takes a seed test (and optionally a PRD) and writes a Markdown plan into `specs/`.
- The generator turns the plan into tests under `tests/` and checks selectors against the live page.
- The healer re-runs failing tests and patches locators. It stops when the test passes or a guardrail ends the
  loop, and it skips the test if the feature looks broken.

The docs define no mandatory human approval gate for healed tests.

Evidence: https://playwright.dev/docs/test-agents

Confidence: verified

### F2: Playwright MCP works from accessibility snapshots and can attach over CDP

Playwright MCP (`@playwright/mcp`) drives the browser through accessibility snapshots, not screenshots, so that
tool application is deterministic. Its relevant options:

| Option | Effect |
|---|---|
| `--cdp-endpoint <endpoint>` | attach to an existing Chromium over CDP |
| `--caps vision` | enable coordinate (vision) mode |
| `--save-session`, `--output-dir`, `--isolated` | persist runs |
| `--allowed-origins`, `--blocked-origins` | limit which origins the browser can reach |

Core tools: `browser_snapshot`, `browser_click`, `browser_type`, `browser_evaluate`, `browser_fill_form`,
`browser_verify_element_visible`, `browser_verify_text_visible`, `browser_verify_value`,
`browser_verify_list_visible`, `browser_console_messages`, `browser_network_requests`.

Evidence: https://github.com/microsoft/playwright-mcp (README)

Confidence: verified

### F3: The JxBrowser vendor documents Playwright MCP over CDP

TeamDev, the JxBrowser vendor, documents how to drive JxBrowser with Playwright MCP over CDP. On the JxBrowser
side the engine is built with
`EngineOptions...addSwitch("--remote-allow-origins=http://localhost:" + port).remoteDebuggingPort(port)`. On the
MCP side the server is started with `--cdp-endpoint http://localhost:9222`. TeamDev states two caveats:

- Remote debugging must be enabled when the Engine starts. It cannot be enabled later.
- Page snapshots can use up even a 200k-token context after a few tool calls.

Evidence: https://teamdev.com/jxbrowser/blog/automating-jxbrowser-with-mcp/ (2025-11-14)

Confidence: verified

### F4: Playwright Java `connectOverCDP` is Chromium-only and lower fidelity

Playwright Java's `BrowserType.connectOverCDP(String endpointURL[, options])`:

- works with Chromium only;
- accepts `http://host:port/` or `ws://.../devtools/browser/...`;
- exposes the browser's existing default context through `Browser.contexts()`.

The documentation calls this connection significantly lower fidelity than the Playwright protocol connection,
because Playwright did not launch the browser with its own arguments. An embedded web view that Playwright did not
launch, for example a JxBrowser engine in off-screen rendering mode, should therefore be expected to lose some
Playwright features, such as tracing fidelity and some emulation. Spike BS5 checks attach, screenshots
and tracing on a stock Chromium that Playwright did not launch; the losses on a particular embedded browser need a
spike against that browser (`embedded-browser-cdp.md` R1).

Evidence: https://playwright.dev/java/docs/api/class-browsertype

Confidence: verified (API facts and the documented fidelity limit); the feature losses on an embedded web view
need a spike (BS5 for the generic part)

### F5: Browser-agent benchmarks overstate production success

Field reports on browser-use agents list six production failure modes that benchmarks do not show:

- DOM selector drift
- screenshot ambiguity
- login state
- modal interruptions
- rate-limit cliffs
- irreversibility

One write-up contrasts a 78% WebArena score with 22% success at booking through a production shopping cart. It
recommends gating CI on recovery rates per failure mode, and stubbing irreversible endpoints behind confirmation
gates. Vendor self-reports (browser-use: 89.1% on WebVoyager) cannot be compared with production results.

Evidence: https://futureagi.com/blog/evaluating-browser-use-agents-2026/ (2026-03-18, updated 2026-05-20,
secondary) ; https://browser-use.com/posts/ai-browser-agent-benchmark (vendor)

Confidence: likely

### F6: Deterministic healing is auditable; runtime LLM reinterpretation is not

Practice on self-healing tests agrees on three points:

- An ordered, deterministic locator fallback is auditable, because you can read the healing log.
- An LLM that reinterprets a step at runtime can choose differently on different runs. That is the opposite of
  what a regression suite needs.
- The most auditable pattern is code generated ahead of time, then reviewed and committed.

Healenium (Java) is the deterministic example. It uses a proxy and a backend, stores reference selectors and
healing reports in PostgreSQL, matches locators by longest common subsequence (LCS), and has no built-in review or
approval gate.

Evidence: https://qaskills.sh/blog/self-healing-test-automation-2026-guide (secondary) ;
https://github.com/healenium/healenium

Confidence: likely

### F7: LLM-generated tests are flakier, often through unordered results

LLM-generated tests are measurably flakier than existing tests. In a study of database management systems, 63%
(72 of 115) of the flaky generated tests assumed an ordering that was not guaranteed (an "unordered collection").
Flakiness also passed from example tests into new tests through the prompt context.

Implication: resolver queries must impose a deterministic `ORDER BY` with tie-breakers, and plan prompts must not
include flaky exemplars.

Evidence: https://arxiv.org/abs/2601.08998

Confidence: verified

### F8: The oracle problem is the central correctness risk

When an LLM generates tests, it tends to write oracles that capture what the program actually does, not what it
should do (arXiv 2410.21136). Other results:

- In a pilot, oracles derived from requirements matched the requirements better than the system under test, but
  varied substantially by bug and by model (arXiv 2607.10277).
- LLM oracles reach about 43% mutation score, against about 45% for human-written oracles (ASE 2025).

Implication: committed code must derive expected outcomes from data and business rules (for example waiver
rules and role limits). Expected outcomes must not be inferred from what the screen shows.

Evidence: https://arxiv.org/abs/2410.21136 ; https://arxiv.org/abs/2607.10277 ;
https://www.lucadigrazia.com/papers/ase2025.pdf

Confidence: verified

### F9: Anthropic separates workflows from agents and asks for ground truth at each step

Anthropic's guidance distinguishes two kinds of system:

- workflows, where predefined code paths orchestrate the LLM calls;
- agents, where the LLM directs its own process.

It recommends the simplest kind that works. It names the evaluator-optimizer pattern for tasks with clear
evaluation criteria. It says agents need ground truth from the environment at each step, such as tool-call results
or code execution. This maps directly onto "AI proposes, a deterministic verifier checks".

Evidence: https://www.anthropic.com/research/building-effective-agents

Confidence: verified

### F10: Claude pricing and token overheads

Current Claude pricing, USD per million tokens:

| Model | Input | Output | Cache hit | Note |
|---|---|---|---|---|
| Opus 5.5 | $4 | $20 | $0.20 (0.05x) | |
| Sonnet 5.5 | $2 | $10 | $0.20 | |
| Haiku 4.5 | $1 | $5 | | |
| Fable 5.1 | $10 | $50 | | requires 30-day retention; not eligible for zero data retention (ZDR) |

Pricing rules:

- Cache writes cost 1.25x for the 5-minute TTL and 2x for the 1-hour TTL.
- The Batch API is 50% off input and output, and the discount stacks with caching.
- Tool definitions cost input tokens. The tool-use system prompt is 286 tokens on Opus 5.5 and Sonnet 5.5. The
  computer toolset is about 4,500 tokens. Each screenshot is about 1,000-1,800 tokens.

Evidence: https://platform.claude.com/docs/en/about-claude/pricing ; Anthropic `claude-api` skill for Claude
Code, model table (snapshot 2026-09-25)

Confidence: verified

### F11: Structured outputs are GA

Structured outputs are GA on all current models and all platforms. There are two mechanisms, and they can be used
together and with streaming:

- `output_config.format` with `type: "json_schema"` constrains the response.
- `strict: true` on an individual tool guarantees valid tool arguments.

Schema limits:

- no recursive schemas;
- no numeric min/max;
- no string minLength/maxLength;
- array `minItems` only 0 or 1;
- no external `$ref`.

Compiled grammars are cached for 24 hours. The first request adds latency, compilation times out after 180 s, and
any change to the schema or tool set invalidates the cache.

Java SDK: use `StructuredMessageCreateParams<T> ... .outputConfig(MyClass.class)` with Jackson annotations, or
`OutputConfig.builder().format(JsonOutputFormat.builder().schema(...))`.

Evidence: https://platform.claude.com/docs/en/build-with-claude/structured-outputs ; Anthropic `claude-api` skill,
Java tool-use reference (`java/claude-api/tool-use.md`)

Confidence: verified

### F12: Thinking, effort and tool choice on current models

- Use `thinking: {type: "adaptive"}` on current models.
- On Opus 5.5, thinking cannot be disabled. `output_config.effort` is the only depth control: low, medium, high,
  xhigh or max, defaulting to medium.
- On Opus 5.5, Sonnet 5.5 and Fable 5.1, forcing `tool_choice` to `any` or to a named tool returns HTTP 400.
  Resolver prompts must therefore use `auto` with strict tools and an explicit instruction, or use structured
  outputs.
- The default model in the skill guidance is `claude-opus-5-5`. The Java SDK artifact is
  `com.anthropic:anthropic-java:2.34.0`.

Evidence: Anthropic `claude-api` skill, "Thinking & Effort" and "Tool Use Patterns" (snapshot 2026-09-25) ;
https://platform.claude.com/docs/en/build-with-claude/adaptive-thinking.md ;
https://platform.claude.com/docs/en/build-with-claude/effort.md

Confidence: likely

### F13: Prompt caching rules, and what they mean for resolvers

Prompt caching matches on a prefix, in the render order tools, then system, then messages.

- The minimum cacheable prefix is 512 tokens on Opus 5.5 and Sonnet 5.5, and 4096 on Haiku 4.5.
- A request can have at most 4 breakpoints.
- Check that caching works through `usage.cache_read_input_tokens`.
- A `{"role": "system"}` message added mid-conversation keeps the cached prefix.

For resolvers: put the frozen schema catalog and the resolver tool definitions first (cached, with the 1-hour TTL
for nightly runs), and the per-query facts last.

Evidence: Anthropic `claude-api` skill, prompt-caching reference (`shared/prompt-caching.md`) ;
https://platform.claude.com/docs/en/build-with-claude/prompt-caching

Confidence: likely

### F14: The Messages API MCP connector cannot reach local servers

The Messages API MCP connector (beta header `mcp-client-2025-11-20`) needs an MCP server that is publicly
reachable over HTTP (Streamable HTTP or SSE). It cannot connect directly to local stdio servers.

- `mcp_servers[].type` accepts only `"url"`.
- Each server needs a matching `mcp_toolset` entry with per-tool `configs` / `default_config` (enable or disable,
  `defer_loading`).
- It is in beta on the Claude API, Claude Platform on AWS and Foundry, and unavailable on Bedrock and Vertex.

For local servers the docs point to the client-side helpers in the MCP SDKs (Java:
`io.modelcontextprotocol...StdioClientTransport`).

Evidence: https://platform.claude.com/docs/en/agents-and-tools/mcp-connector (Limitations section; parameter table
"Currently only "url" is supported") ; Anthropic `claude-api` skill, platform availability table
(`shared/platform-availability.md`)

Confidence: verified

### F15: The 2026-07-28 MCP specification

MCP specification 2026-07-28 changes four things:

- The core becomes stateless request/response.
- Multi Round-Trip Requests let a tool ask for confirmation in the middle of a call.
- Requests are routed by the `Mcp-Method` / `Mcp-Name` headers.
- `tools/list` results can be cached (`ttlMs`, `cacheScope`).

A primitives MCP server written now should target this version.

Evidence: https://blog.modelcontextprotocol.io/posts/2026-07-28/

Confidence: verified

Cross-reference: `control-channel.md` F18 and C6 record that the MCP Java SDK 2.0.1 implements revisions up to
2025-11-25 only. The design therefore pins `skillroy-mcp` to the 2025-11-25 line; the "should target this version"
advice here and in R11 is superseded until the SDK catches up.

### F16: Claude computer use, and a keyboard caveat for Swing

`computer_toolset_20260801` is GA on the Claude API, with no beta header, for Opus 5.5/5/4.8, Sonnet 5.5/5 and
Fable 5.1/5. Opus 5.5 and Sonnet 5.5 reject the older `computer_20251124`.

- The toolset has 17 member actions (screenshot, zoom, left_click, type, key, scroll, wait, ...).
- Batched actions run in order and stop at the first failure.
- The recommended screen sizes are 1024x768 or 1280x720.
- The documented best practices are a dedicated VM, human confirmation for consequential actions, and a
  screenshot after each step.

Caveat for Swing targets: AWT was observed to ignore synthetic key events sent with xdotool, while mouse events
arrived. Keyboard-driven computer use against a Swing application is therefore likely to fail unless input is
injected inside the JVM. Spike BS4 will check this.

Evidence: https://platform.claude.com/docs/en/agents-and-tools/tool-use/computer-use-tool. The keyboard caveat
comes from an observation on a non-public Swing application under X11; it needs spike BS4.

Confidence: verified (computer-use tool facts); medium for the keyboard caveat (not re-verified on a neutral
target)

### F17: What the Claude Agent SDK provides

The Claude Agent SDK exists for Python (`claude-agent-sdk`) and TypeScript (`@anthropic-ai/claude-agent-sdk`).
There is no Java SDK. It provides:

- **Custom tools:** in-process tools through `@tool` / `tool()` plus `create_sdk_mcp_server` /
  `createSdkMcpServer`, namespaced `mcp__{server}__{tool}` and pre-approved through `allowed_tools`. A
  `readOnlyHint` annotation lets the SDK call tools in parallel.
- **Hooks:**
  - `PreToolUse` returns a `permissionDecision` (allow, deny, ask or defer) and can supply `updatedInput`.
  - `PostToolUse` can return `updatedToolOutput` or `additionalContext`.
  - The other hooks are `PermissionRequest`, `SubagentStop` and `PreCompact`.
- **Permission modes:** default, dontAsk, acceptEdits, bypassPermissions, plan and auto. Deny rules win even in
  bypass mode.
- **`canUseTool` callback:** returns allow (with `updatedInput`) or deny (with a message). "Approve and remember"
  is implemented by writing `updatedPermissions` to `.claude/settings.local.json`.
- **Structured output:** `output_format: {type: json_schema}` (JSON Schema draft-07) yields `structured_output`,
  or `error_max_structured_output_retries`.
- **Sessions:** they can be resumed and forked.
- **Tool search:** on by default; it defers loading the schemas of SDK MCP tools.

Evidence: https://code.claude.com/docs/en/agent-sdk/overview ; https://code.claude.com/docs/en/agent-sdk/custom-tools ;
https://code.claude.com/docs/en/agent-sdk/hooks ; https://code.claude.com/docs/en/agent-sdk/permissions ;
https://code.claude.com/docs/en/agent-sdk/user-input ; https://code.claude.com/docs/en/agent-sdk/structured-outputs

Confidence: verified

### F18: Reaching the Agent SDK loop from Java through headless `claude -p`

A Java controller can reach the Agent SDK loop by running the CLI headless:

```
claude -p --bare --output-format json --json-schema '<schema>' --mcp-config <file> \
  --allowedTools "mcp__<server>__*" --permission-mode dontAsk --permission-prompts none
```

- The exit code is 0 on success and non-zero on failure.
- `--bare` skips host hooks, plugins and `CLAUDE.md`, so CI runs are reproducible.
- The `system/init` message carries `mcp_server_errors`, so CI can fail when the primitives server did not load.
- `--resume <session_id>` continues a session.
- `total_cost_usd` is in the JSON result.

Evidence: https://code.claude.com/docs/en/headless

Confidence: verified

### F19: Cucumber-JVM 8 needs Java 17

Cucumber-JVM v8.0.0 (2026-09-24) set its baseline to Java 17, and 8.0.3 was released on 2026-09-28. The 7.34.x
line, which is Java 8 compatible, still receives releases. A modern-Java controller is not affected, but Cucumber
8 cannot run inside a Java 8 target JVM.

Evidence: https://github.com/cucumber/cucumber-jvm/releases

Confidence: verified

### F20: Gherkin pays off only when non-developers author scenarios

Practitioners agree that Gherkin earns its keep only when non-developers write or run the scenarios. Otherwise it
adds a third artifact that the IDE cannot refactor, and reports show "the Gherkin, not what ran". Kotest
`BehaviorSpec`, or JUnit with good test names, gives Given/When/Then structure in code that can be refactored
safely.

Evidence: https://dev.to/paul-brooks/bdd-in-kotlin-the-options-and-how-to-choose-3mha ;
https://kotest.io/docs/framework/testing-styles.html ; https://stackshare.io/stackups/cucumber-vs-kotest

Confidence: likely

### F21: Maestro is a shipping precedent for AI inside a YAML test DSL

Maestro is a shipping example of AI inside a YAML UI-test DSL:

- `assertWithAI` sends a screenshot and a natural-language assertion to an LLM and returns a boolean. It is
  `optional: true` by default, so an AI assertion does not fail the flow unless you opt in. It writes HTML and
  JSON reports.
- `extractTextWithAI` writes an extracted value into a variable (`aiOutput`) for later deterministic use.

This is the "resolve into a variable, then assert deterministically" shape.

Evidence: https://docs.maestro.dev/api-reference/commands/assertwithai ;
https://docs.maestro.dev/reference/commands-available/extracttextwithai

Confidence: likely

### F22: JUnit 5 dynamic tests can turn scenario files into first-class tests

A JUnit 5 `@TestFactory` method returns a Stream or Collection of `DynamicTest`s. They are generated at runtime and
executed lazily. YAML or JSON scenario files can therefore become first-class JUnit tests, each reported
separately in Surefire and Jenkins.

Note: `@BeforeEach` and `@AfterEach` run once per factory, not once per dynamic test. Per-scenario setup must go
inside the executable.

Evidence: https://docs.junit.org/current/writing-tests/dynamic-tests.html

Confidence: likely

### F23: Record-once/replay-many has mature precedents

Two mature tools record once and replay many times, and either can be copied for binding fixtures:

- Playwright's `page.routeFromHAR(path, update=true)` records traffic, then replays it. Matching is strict on URL
  and method (and POST body), and unmatched requests are aborted.
- WireMock record/snapshot produces stub mappings, and playback needs no explicit action.

Both store the recording as a file next to the test, where it can be reviewed and diffed.

Evidence: https://playwright.dev/java/docs/mock ; https://wiremock.org/docs/record-playback/

Confidence: verified

### F24: The text-to-SQL guardrail pattern

Production-style projects guard text-to-SQL in the same way. The model proposes SQL. Deterministic code then:

- validates the SQL syntax tree: exactly one SELECT; only allow-listed tables and columns, with aliases resolved;
  no DDL or DML;
- injects a LIMIT if there is none, and caps it;
- optionally checks the EXPLAIN cost against a limit;
- sets a statement timeout;
- runs the query under a read-only database role;
- logs every query.

Java tooling:

- JSqlParser 5.4.96 (JDK 11+) validates the syntax tree.
- MariaDB's `MAX_STATEMENT_TIME` can be granted per user and applies to read-only SELECTs.
- The DB2 JDBC driver supports `queryTimeout` / `Statement.setQueryTimeout`.

Evidence: https://github.com/AbuBaker91-hub/safesql ; https://github.com/darrshangovender/sql-guardrails
(secondary) ; https://github.com/JSQLParser/JSqlParser/releases ;
https://mariadb.com/docs/server/reference/sql-statements/account-management-sql-statements/grant ;
https://www.ibm.com/support/pages/timeout-properties-ibm-data-server-driver-jdbc-and-sqlj

Confidence: likely

## Recommendations

### R0: Two-plane architecture

The framework has two planes:

- A deterministic modern-Java controller (JDK 25) owns the agent loop, every side effect on the target
  application and its database, and the test report.
- Claude is used only at three explicit, logged touchpoints:
  - (A) plan compilation at authoring time;
  - (B) binding resolution at the start of a run;
  - (C) opt-in proposals for diagnosis or repair.

Claude never drives the target application step by step in CI.

### R1: Typed queries and typed resolutions

Define the Resolver layer as typed queries and typed resolutions. Query types and their resolver chains come from
the domain pack. Examples for the sample lending desk:
`StaffWithPermission(permission, limitPredicate, site)`, `ItemsTriggering(waiverKind, qty, site, date)`,
`LendableItem(depositPredicate, available)`.

```java
sealed interface Query<T> { ... }
record Resolution<T>(T value, Strategy strategy, String provenance, double confidence,
                     VerifierResult verified, EnvFingerprint env) {}
interface Resolver<Q, T> { Optional<Resolution<T>> resolve(Q q, ResolveContext ctx); }
```

The design does not seal `Query`: a sealed interface has to list its permitted subtypes, so the base would have to
know its packs' query types.

Each query type has an ordered chain of resolvers:

1. tier (a): hand-written SQL or Java;
2. tier (b): LLM-authored SQL;
3. tier (c): LLM reasoning over primitive outputs.

The fixture and the report record which strategy was actually used.

### R2: A deterministic verifier gates every binding

Attach a deterministic Verifier to every query type. The verifier must pass before a binding is accepted, whatever
tier produced it. Examples:

- `StaffWithPermission`: call the application's own access-control check in-process through an agent primitive,
  or re-run the canonical SQL.
- `ItemsTriggering`: run the application's own waiver-rule check for (item, date) and confirm that the
  matching waiver rule is of the requested kind.

The LLM's confidence is advisory. The verifier is the gate.

### R3: Oracles come from resolved data through committed code

Derive oracles from resolved data with committed Java oracle functions, not from observation. Examples:

- `ExpectedWaiver.compute(waiverRuleRows, loan) -> expectedWaiver`
- `ExpectedAuthorisation.compute(roleLimits, amount) -> SupervisorPromptExpected`

Claude may draft an oracle function once, at authoring time, with the schema and the application's waiver-rule
model classes in context. The function is then committed as source and goes through code review and unit tests.
Assertions in scenarios reference `$oracle.*` values only.

### R4: Layered guardrails for tier (b) text-to-SQL

Implement tier (b) text-to-SQL with these guardrails:

- **Catalog:** introspect the schema catalog once per environment: DDL, plus sampled distinct values for small
  enum-like columns, with PII columns masked. Place it in the cached prompt prefix.
- **Model output:** Claude returns a structured object `{sql, rationale, tablesUsed, orderBy}` through
  `output_config.format`.
- **Validation:** JSqlParser checks for:
  - exactly one SELECT;
  - only allow-listed tables and columns;
  - no DML, DDL or CALL, except stored procedures that the domain pack allow-lists;
  - a deterministic `ORDER BY` with a tie-breaker (mandatory);
  - a LIMIT, injected if missing and capped at 200 or less.

  An EXPLAIN cost check is optional.
- **Execution:** run under a dedicated read-only database user, with `MAX_STATEMENT_TIME` (MariaDB) or
  `setQueryTimeout` (DB2, Oracle). Log every statement with its request id.
- **Channel:** use JDBC directly. When the data lives in the target JVM's own cache, use an in-process query
  primitive (the `DataSourceProvider` SPI) instead.

### R5: Make "resolve once, assert many" concrete

- Write a `bindings.json` beside each scenario. It holds every Resolution: query, value, strategy, provenance (the
  SQL or the tool trace), verifier result, model id, request id, and token and cost usage.
- Key the file by an EnvFingerprint: schema checksum, target application version, site or organisational unit,
  device identity and configuration, embedded web view application version, embedded browser (for example
  JxBrowser) version, and data snapshot id. The domain pack's fingerprinter supplies the domain-specific fields.

Three modes use the file:

| Mode | Where | Behaviour |
|---|---|---|
| `replay` | CI | zero LLM calls; fail fast if the fingerprint does not match |
| `re-resolve` | nightly job | regenerates fixtures through the Batch API (50% discount) and opens a diff for review |
| `live` | local | resolves on demand |

### R6: Resolve before the session starts

Do all resolution before sign-on, and never inside a modal prompt or a transaction. In-loop LLM latency is seconds
to tens of seconds with adaptive thinking. That can exceed a target application's inactivity timeout, and it holds
the application in a modal state while it waits. If a resolution in the middle of a scenario cannot be avoided,
pre-compute the candidates in the plan and let the controller choose between them deterministically at runtime.

### R7: Default models, SDK settings and costs

Default models:

| Model | Settings | Use |
|---|---|---|
| `claude-opus-5-5` | effort high, adaptive thinking | plan compilation, drafting oracle functions |
| `claude-sonnet-5-5` | effort medium or low | tier (b) and (c) resolvers |
| `claude-haiku-4-5` | | trivial classification only, such as failure-triage labels |

Use the Java SDK `com.anthropic:anthropic-java:2.34.0` with:

- structured outputs (`.outputConfig(Class)`);
- `strict: true` tools;
- `tool_choice: auto` (forced tool choice is a 400 on the 5.5 models);
- prompt caching, with the schema catalog as the first breakpoint (at least 512 tokens; 1-hour TTL for nightly
  runs);
- `fallbacks: "default"`, as the skill guidance recommends.

Rough costs:

- With a 30k-token cached catalog, a resolver call costs about $0.02 on Sonnet 5.5 and about $0.05 on Opus 5.5.
- Plan compilation costs about $0.20-0.50 per scenario on Opus 5.5. This is an estimate; check it with
  `count_tokens`.

### R8: Scenario DSL shape

The recommended primary format is YAML with `resolve:`, `oracle:` and `steps:` sections. Example for the sample
lending desk (the step names come from the domain pack; `$item` and `$supervisor` are resolved the same way as
`$assistant`):

```yaml
resolve:
  assistant: {query: StaffWithPermission, args: {perform: [RETURN], limit: {type: RETURN, lt: 50.00}}, strategy: [sql, llm-sql, app]}
oracle:
  supervisorPrompt: {fn: ExpectedAuthorisation, args: {staff: $assistant, amount: $item.deposit}}
steps:
  - signOn: {staff: $assistant}
  - returnItem: {item: $item}
  - expect: {prompt: $oracle.supervisorPrompt}
  - authorise: {staff: $supervisor}
  - settleDeposit: {media: CASH}
  - expect: {loan.deposits[0].amount: $oracle.expectedReturn}
```

The compiler emits `plan.json`: ordered primitive calls, with resolved argument slots and assertion predicates.
`plan.json` is committed and diffed. A Kotlin or Java fluent DSL
(`scenario { resolve(...) ; signOn(assistant) ; expect(...) }`) is the typed implementation layer that the YAML maps
onto, so power users can skip the YAML.

### R9: Natural language only at authoring time

Allow steps to be written in natural language only at authoring time:

1. An Agent SDK "planner" agent explores the live target application and emits the YAML and `plan.json` through
   `output_format: json_schema`.
   - It is written in Python or TypeScript and runs with `permission_mode: plan` or `dontAsk`.
   - Its tools are the primitives MCP server, a read-only SQL tool and the schema catalog.
2. A human reviews the diff before commit.

This mirrors Playwright's planner/generator split. Never compile natural language at CI time.

### R10: Run scenarios as JUnit 5 dynamic tests

In CI, run scenarios as JUnit 5 `@TestFactory` dynamic tests: one `DynamicTest` per YAML file, with the
environment-fingerprint check and the fixture load inside the executable. Jenkins and Surefire reporting, tagging
and parallelism then come for free.

Add a Gherkin adapter (Cucumber-JVM 8, Java 17+) only if business authors must own scenarios. It would generate
the same `plan.json`.

### R11: Expose the primitives as a local MCP server

Expose the framework primitives as a local MCP server that targets MCP 2026-07-28 (superseded for the version:
the design pins the 2025-11-25 line, see the cross-reference under F15):

- **Transports:** stdio for the Agent SDK and `claude -p --mcp-config`; Streamable HTTP for in-house tools.
- **Annotations:** `readOnlyHint` on observe and query primitives, so Claude can call them in parallel;
  `destructiveHint` on state-changing primitives (in the sample lending desk: lend, take a deposit, cancel a
  loan).
- **No Messages API connector:** do not plan on the Messages API MCP connector for CI. It needs a publicly
  reachable URL and is unavailable on Bedrock and Vertex.

From the Java controller, start authoring agents with:

```
claude -p --bare --output-format json --json-schema ... --mcp-config primitives.json \
  --allowedTools "mcp__<server>__*" --permission-prompts none
```

### R12: Embedded web views and computer use

For embedded web views:

- Attach Playwright Java (1.63.0) with `connectOverCDP("http://localhost:<remote-debugging-port>")`, where the
  port is the one the embedded browser engine was started with. Use the pages of `Browser.contexts().get(0)`.
- For exploration at authoring time, let Playwright MCP attach with `--cdp-endpoint` and `--save-session`.
- Keep snapshots small: call `browser_snapshot` on a scoped locator, and use `--blocked-origins` for analytics.
  Embedded-browser pages can use up the context quickly (F3).
- Record the embedded web view's network traffic with `routeFromHAR(update=true)` where the backend simulators are
  not deterministic.

Treat Anthropic computer use (`computer_toolset_20260801`, 1024x768) strictly as an exploratory or diagnostic
fallback for Swing. Inject input inside the JVM through primitives, not as OS-level keyboard events.

### R13: Human approval and trust thresholds

The first run of a new or changed scenario executes in `approve` mode:

- Every Resolution is presented (value, SQL, verifier result, confidence) and must be approved before
  `bindings.json` is written.
- Implement this with the Agent SDK `canUseTool` callback at authoring time, or a controller CLI prompt at run
  start.
- Later runs replay.

Acceptance thresholds:

| Tier | Accepted when |
|---|---|
| (a) | the verifier passes |
| (b) | the verifier passes, confidence is 0.8 or higher, and the row count is within the expected bounds |
| (c) | the verifier passes and the binding is explicitly approved on first use |

Any healer or repair proposal is emitted as a patch to `plan.json` or the YAML, with a rationale. It is never
applied automatically in CI.

### R14: Report and audit trail

Every test report embeds:

- the EnvFingerprint snapshot;
- all Resolutions with their provenance;
- all LLM calls: model, request id, input, cache and output tokens, cost and effort;
- the trace of primitive calls;
- screenshots and excerpts from the application's logs and audit records;
- Playwright traces for embedded-web-view steps.

Failures are first classified deterministically: assertion failure, missing resolution, or environment drift.
Only after that may Claude Haiku or Sonnet summarise them for triage.

### R15: PII and data policy

- Mask person and user columns (names, passwords, codes) before any row reaches a prompt.
- Prefer sending schema, aggregates and surrogate ids.
- Keep an allow-list of the tables Claude may see.
- Log prompts and keep them under the 30-day retention regime. Opus 5.5 and Sonnet 5.5 are eligible for ZDR if
  that is required; Fable 5.1 is not.

### R16: Pilot order

1. Tier (a), verifiers and fixtures for `StaffWithPermission` and `LendableItem`, with the YAML DSL and the
   JUnit runner. No LLM.
2. Tier (b) text-to-SQL for `ItemsTriggering`, with the JSqlParser guardrails and the waiver oracle.
3. The Agent SDK planner for authoring.
4. Embedded-web-view steps through Playwright over CDP.
5. Only then, evaluate tier (c) and healer proposals.

## Constraints

### C0: Java 8 in the target, modern Java in the controller

When the target JVM runs Java 8, everything that runs inside it must be Java 8 bytecode: the agent, the
primitives and the in-application verifiers. The following all live in the modern-Java controller process:

- the controller and the Resolver layer;
- Cucumber-JVM 8 (Java 17 baseline);
- Playwright Java 1.63;
- the Anthropic Java SDK 2.34.0;
- JSqlParser 5.4 (JDK 11+).

### C1: No LLM calls inside a running transaction

LLM calls take seconds to tens of seconds. A target application may lock itself after a short idle period, or
block its engine thread while a modal prompt is open. LLM calls therefore cannot sit inside a running
transaction. Resolution happens before sign-on or between scenarios.

### C3: CDP access to an embedded browser has to be enabled at creation

JxBrowser remote debugging must be enabled when the Engine is created; it cannot be turned on later (F3).
Playwright `connectOverCDP` works with Chromium only and has lower fidelity than a browser that Playwright
launched (F4).

### C4: Where the MCP connector and Agent SDK are available

The Messages API MCP connector cannot reach local or stdio servers, and it is unavailable on Bedrock and Vertex.
The Agent SDK exists only for Python and TypeScript, so Java must run `claude -p` as a subprocess.

### C5: Model and API limits

- On Opus 5.5, Sonnet 5.5 and Fable 5.1, forcing `tool_choice` to `any` or to a named tool returns 400.
- On Opus 5.5, thinking cannot be disabled.
- Structured-output schemas cannot use recursion, numeric min/max or string length constraints.
- The minimum cacheable prefix is 512 tokens.
- Fable 5.1 requires 30-day retention.

### C6: Keyboard input into Swing must come from inside the JVM

AWT ignores synthetic OS-level key events, as observed with xdotool. Keyboard input from screenshot-driven
computer use against Swing is therefore unreliable, and input must be injected inside the JVM through agent
primitives. Medium confidence: this has not been re-verified on a neutral target and needs spike BS4.

### C7: Determinism requirements for CI

- Replay mode makes no LLM calls.
- All resolver SQL has a deterministic `ORDER BY` with tie-breakers.
- A fixture whose EnvFingerprint does not match is invalidated. It is not silently re-resolved.

### C8: Data governance

Tables that hold people and user accounts contain PII and credentials. Prompts may see only masked, allow-listed
columns. Every LLM request must be logged with its request id and cost.

## Risks

### Risk0: Nondeterminism leaking into CI

If any tier (b) or (c) resolution runs at CI time, two executions of the same run can diverge. Field reports and
the self-healing literature both warn about this. Replay-only CI and fixture diffs mitigate it, but they need
discipline. They also need a fingerprint that is neither too strict (fixtures constantly invalidated) nor too
loose (stale bindings).

### Risk1: Oracle bias

An LLM-drafted oracle function may encode the target application's current, possibly buggy, behaviour instead of
the business rule. Committing oracles as reviewed code, with unit tests against hand-computed cases, mitigates
this, but the review effort is real.

### Risk2: Text-to-SQL across several dialects

Text-to-SQL across several SQL dialects (for example MariaDB, DB2 and Oracle), plus stored procedures, makes
validation more complex. JSqlParser may not parse every DB2 or Oracle construct. That would force a fallback to
tier (a), or to a restricted subset of each dialect. Needs spike BS6.

### Risk3: Cost and latency creep

Opus 5.5 at high effort, with large schema prefixes and Playwright snapshots, can cost tens of cents per scenario
and take tens of seconds per call. Without caching discipline (watching `cache_read_input_tokens`) and Batch
usage, nightly re-resolution across a large scenario suite becomes expensive.

### Risk4: Playwright fidelity over CDP on an embedded browser

CDP attach to an embedded Chromium that Playwright did not launch may break some Playwright features: tracing,
emulation, and auto-waiting on signals that exist only when the browser is headed. Examples of such a browser are
a JxBrowser engine in off-screen rendering mode or with web security disabled. Run spike BS5 (stock Chromium) and
a spike against the embedded browser itself (`embedded-browser-cdp.md` R1) before committing to Playwright Java
for embedded-web-view steps.

### Risk5: Context exhaustion in authoring agents

Accessibility snapshots of embedded web views, and schema catalogs, can overflow the context (TeamDev's own
caveat, F3). The countermeasures are scoped snapshots, deferred tool loading through tool search, and compaction.

### Risk6: Auto-repair can hide regressions

Healer-style auto-repair could silently mask product regressions; Playwright's healer may skip tests it judges
broken. Keep repair as proposals that require review.

### Risk7: Customer variants diverge from the base product

A target application that ships in customised variants (a customer fork with its own schema changes and
access-control code) can break resolvers written against the base product. The Resolver abstraction must allow
per-tenant resolver overrides: a tenant pack layered over the domain pack.

### Risk8: Privacy and compliance

Sending even masked personal or operator data to a hosted model may need security sign-off. The Bedrock and Vertex
alternatives lack the MCP connector and Message Batches.

### Risk9: Vendor API drift

The beta surfaces used here change often: the MCP connector header, the Agent SDK hooks, and the computer toolset
on partner clouds. Pin SDK versions, and keep the LLM boundary behind a small internal interface.
