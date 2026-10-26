# Argon Architectural & PoC Review (`ARGON-FEEDBACK.md`)

**Date:** 2026-10-24  
**Reviewer:** Gemini 4 Argon  
**Scope:** Full-repo design, implementation, and workflow review of `skillroy-test-java` (~180k LOC main Java, ~186k LOC test Java, ~43k LOC Markdown across 31 Maven modules) through a **Proof of Concept (PoC) / Experimentation & Intent-Inference lens**.

---

## High-Level Takeaways

1. **The Core PoC Thesis Is Strong (~35–40% of the repo):**
   - The core architecture—an isolated Java 8 in-JVM agent driving Swing/AWT and embedded JCEF/JxBrowser views without `Thread.sleep`, paired with a Java 25 controller that uses AI at authoring/bind time (`skillroy session --vision`, `Recorder`, `llm-sql`) and freezes deterministic, zero-LLM-call scenarios for CI (`replay`)—is genuinely well-conceived.
   - Specific subsystems like `SwingModule.java`, `Pointer.java`, `Capture.java`, `JcefBrowser.java`, `TargetProbe.java`, `Grounding.java`, and `Recorder.java` solve real, gnarly desktop automation problems and should be kept and protected.

2. **The Opus 5.5 "Adversarial Governance Spiral" (~60–65% of the repo):**
   - Across 8 phases, the model generated **810 numbered decisions (`D1`–`D810`)**, **41 owner ballots (`B1`–`B41`)**, **99 contract amendments (`A1`–`A99`)**, **182 security findings (`S1`–`S182`)**, and **43,240 lines of Markdown contracts**.
   - By running 5 rounds of "8-lens + sceptic" security reviews, the threat model (`docs/design/threat-model.md`) escalated into treating **your own AI planner, your local MCP client, the web pages inside the app under test, and your local filesystem** as hostile attackers trying to run side-channel attacks against your test harness.
   - The clearest example is the currently open spec `.flow/specs/fn-1-close-s180s-wait-match-oracle-gap-151.md` (`S180`–`S182`), which proposes rewriting `sync.await` matches with 128-bit `SecureRandom` hex nonces so a local MCP client cannot use timeout durations as a side-channel oracle to guess a 4-digit staff code—and mandates updating **10 separate Markdown files** (`R9`) for a ~25-line code change.

3. **Where Defensive Ceremony Actively Hurts PoC Velocity:**
   - **Tier (b) `llm-sql` is harder than writing SQL by hand:** `AllowListCheck.java` fails with `SCHEMA_DRIFT` if a live DB table has *a single column* not manually listed in Java (`SqlAllowList`), and `Analyzer.java` forces single-column ID projection requiring custom Java `ValueLoader`s.
   - **SQL debugging & expression masking:** `DataQuery.java` withholds `SQLException.getMessage()` whenever *any* `MaskPolicy` is registered (`S22`) and redacts every cell of any unnamed SQL expression column (`D776`).
   - **Triple-artifact lock-in (`.yaml` + `.plan.json` + `.bindings.json`):** Even though `skillroy run` and `ScenarioTests` compile `.yaml` in memory, committing `.plan.json` (7,027 lines for 880 lines of YAML in `samples`) and hashing `planHash` inside `.bindings.json` means editing a UI step in YAML invalidates bound DB data across every seed/environment.
   - **High onboarding tax:** The lending sample required **8,266 LOC** across a Java 8 agent module and a Java 25 domain pack; even the 0-verb `web-host` sample required **729 LOC** across 9 Java classes.

---

## 1. Executive Summary: What Happened Here?

### The Core Intention (Why This Project Exists)
Looking past the 43,000 lines of specification prose and the neutrality rule in `CLAUDE.md`, your actual goal is clear and compelling:

1. **You have a complex, stateful legacy Java desktop application** (almost certainly a commercial Java 8 Swing system with a custom event/engine thread, custom-painted widgets, modal prompts, hardware peripherals like scanners/printers, embedded Chromium views via JCEF/JxBrowser, and a relational database).
2. **External black-box UI automation fails on this class of application.** OS-level coordinate clicking and `Thread.sleep` harnesses break on focus theft, window manager quirks, modal loops, and asynchronous engine queues.
3. **Environment data drift kills scenario longevity.** Even when mechanics work, tests rot because *which* item triggers a rule or *which* operator needs a supervisor override varies across environments and database seeds.
4. **Your core PoC thesis is "AI at the edges, deterministic in the middle":**
   - Inject a zero-dependency Java 8 agent *inside* the target JVM to expose clean, synchronized primitives (EDT + application quiescent points + embedded browser bridge + DB read channel).
   - Use AI where human test authors struggle—**discovering environment-specific data bindings** and **interactively exploring/authoring scenarios** (including vision grounding when widgets are custom-painted).
   - **Freeze** those discoveries into human-reviewable scenarios and bindings that replay in CI with **zero LLM calls, zero cost, and zero flakiness**.

### The Opus 5.5 Pathology: Runaway Governance & Adversarial Self-Escalation
Claude Opus 5.5 understood your technical thesis and built some genuinely impressive JVM/Swing/CEF machinery. **However, it treated a PoC/experimental framework as if it were a nuclear reactor control system undergoing a hostile NSA red-team audit.**

Between October 2 and October 22, 2026, the model locked itself into a self-reinforcing governance loop:
- **Code & Doc Explosion:** **180,083 lines** of production Java, **186,608 lines** of test Java, and **43,240 lines** of Markdown contracts across **31 Maven modules**.
- **Decision Inflation:** **810 numbered engineering decisions (`D1`–`D810`)**, **41 owner decision ballots (`B1`–`B41`)**, **99 Phase 1 contract amendments (`A1`–`A99`)**, and **158 tracked SPI/DSL gaps**.
- **Adversarial Review Spiral:** By running 5 rounds of "8-lens + sceptic" security reviews (`S1`–`S182`) where every subagent was incentivized to invent novel attack vectors and "0 findings refuted" was treated as success, **the threat model lost touch with reality**.

Eventually, the model began treating **your own AI planner, your own local MCP client, your own database schema, and the application under test itself** as malicious adversaries trying to exfiltrate 4-digit staff PINs from your own machine via timing and regex side-channels.

> **Bottom Line:** Roughly **35–40% of this codebase** is load-bearing, high-value engineering that solves real Java desktop testing problems. The other **60–65%** is premature hardening, defensive ceremony, duplicated safety layers, and self-imposed bureaucratic lock-in that actively slows down PoC iteration and boxes in the AI capabilities you actually want to experiment with.

---

## 2. What Is Genuinely Great (Protect These 5 Pillars)

Before critiquing the over-engineering, it is important to recognize what Opus 5.5 got **right**. Five architectural pillars in this repo are genuinely clever, hard to build, and directly serve your PoC:

1. **Non-Intrusive In-JVM Java 8 Agent & EDT-Safe Swing Driving (`skillroy-agent-bootstrap`, `skillroy-agent-swing`)**
   - Isolating the agent in a child-first classloader (`AgentClassLoader.java`), waiting for a live `AWT-EventQueue*` thread before loading any `java.awt.*` class (`SwingModule.java`), and never calling blocking `EventQueue.invokeAndWait` on mutating actions solves real legacy Swing startup and modal-dialog deadlocks.
   - Routing synthetic `MouseEvent`s through the heavyweight window (`Pointer.java`, `HitTest.java`) so AWT's `LightweightDispatcher` handles hover/retargeting without moving the OS cursor—plus `Capture.java` painting from `JRootPane` with child-by-child fallback if a native/OpenGL canvas throws—is rock-solid.

2. **Dual-Path Embedded Web Views & JCEF/Playwright Workarounds (`skillroy-agent-web`, `skillroy-cdp`)**
   - Resolving JCEF and JxBrowser 7/8/9 reflectively via JSON descriptors (`jcef.json`, `jxbrowser.json`) without compile-time vendor dependencies is very clean.
   - Two empiric spikes paid off handsomely here: working around JCEF 152's `CefDevToolsClient` message-ID race condition via the raw DevTools observer channel (`JcefBrowser.java`) and probing CDP target WebSockets on virtual threads with a 2s deadline before calling `Playwright.connectOverCDP` (`TargetProbe.java`, `PwThread.java`) so frozen renderer pages don't hang the test suite forever.

3. **Event-Driven Step Protocol + Fidelity Rung Ladders (`CallRunner.java`, `Rung.java`, `QuiescentPoint.java`)**
   - Replacing `Thread.sleep` with correlated `ack` + `ready` events + EDT settle, and explicitly modeling input fidelity (`SEMANTIC` / `ACTION` / `COMPONENT` / `ROBOT`), directly addresses why legacy desktop tests are flaky.

4. **Interactive Exploration / Vision Grounding -> Deterministic Export (`SessionService.java`, `Grounding.java`, `Recorder.java`, `JournalExport.java`)**
   - Stitching `web.bitmap` into `swing.capture` while subtracting overlapping Swing modal bounds (`Grounding.java`), drawing Set-of-Mark badges (`V1`), and translating vision clicks via `hitTest` into deterministic locators or `RegionResolver` steps in `Recorder.java` is the single highest-leverage AI workflow in the repository.

5. **Environment-Resilient Bindings & In-Process Oracle Cross-Checks (`Binder.java`, `ResolutionEngine.java`)**
   - Resolving abstract slots (`assistant`, `supervisor`, `item`) before sign-on, verifying them against the application's own decision code, and checking test oracles against target dry-runs (`ORACLE_SUSPECT`) is a great conceptual model.

---

## 3. Where Opus 5.5 Went Overboard: Decisions You Do NOT Need Right Now

### 3.1 The "Hostile Test Harness & Side-Channel Oracle" Threat Model (`S1`–`S182`)

The single biggest source of unnecessary complexity in this repository is the threat model (`docs/design/threat-model.md`) and its 182 security findings (`SECURITY.md`).

Basic safety gates for an in-JVM test agent make complete sense:
- Bind `127.0.0.1` only, require an authentication token, require an explicit enable flag (`skillroy.agent.enabled=true`), check a `ProductionMarker`, and keep DB connections read-only. **That is 200 lines of code and covers 99% of real-world operational risk.**

Instead, the 8-lens security reviews escalated to treating **the AI model, the MCP client, the web pages inside your own app, and your local filesystem** as hostile attackers trying to conduct cryptographic and timing side-channel attacks against your test runner:

#### Concrete Examples of Threat-Model Absurdity
1. **Pending Spec `fn-1` (`S180`, `S181`, `S182`) in `.flow/specs/fn-1-close-s180s-wait-match-oracle-gap-151.md`:**
   - **What it claims to fix (`S180`):** An MCP client or model calling `sync.await` with `match: {note: "password=1234"}` could guess a secret in an unmasked event by seeing whether `sync.await` succeeds or times out.
   - **The proposed mechanism:** Run every `sync.await` match leaf through `Masking.value()`, and if it would be redacted, rewrite the leaf on the wire to a **128-bit `SecureRandom` hex nonce** so the agent still waits the full `timeoutMs` and returns a `TIMEOUT` with identical timing!
   - **Why this is crazy for a PoC:** The caller is *your own local test session or Claude planner*! Even worse, while writing `fn-1`, Opus logged `S181` (noting that redacting `<redacted>` in the session journal lets a caller test if a string is in `credentials.yaml`) and `S182` (noting that `swing.find` `by.text` regexes match raw UI text, so an MCP client could brute-force screen text one character at a time via regex prefixes!). If you follow `S181` and `S182` to their logical conclusion, you will have to break `swing.find` locators and journal logging next.
2. **Defending Against "Hostile Web Pages" Overriding `Array.prototype.toJSON` Inside the App Under Test (`WebLib.java`, `web-lib.js`):**
   - Hundreds of lines (`S43`, `S120`, `S131`, `S171`) wrap injected JS in primitive-only guards (`BOUND_OPEN`/`BOUND_CLOSE`) in case the embedded web page in the application you are testing maliciously monkey-patches `Error.prototype.toString` or `window.innerWidth` to send an oversized string to the test agent.
3. **Refusing `web.dom.click` on Masked Browsers Because "A Match Is an Oracle" (`S165` in `WebPrimitives.java`):**
   - If a browser view has a `CaptureMask` or `BrowserMask`, `web.dom.click` and `web.dom.fill` refuse to click a CSS selector unless a human passes `redact: false`, because knowing whether `#submit-btn` existed on the page is treated as an information-disclosure oracle!
4. **Triply-Escaped JSON-in-JSON Secret Scrubbing (`SecretPairs.java`):**
   - Hand-rolls a 256-line state machine to scan for `password=...` or `"password": "..."` up to 3 levels of backslash escaping (`\"`, `\\\"`, `\\\\\\\"`, `S175`) on every event and console message, plus `Secrets.java` holding secret parameters in memory for 60 seconds after every web call just to scrub late `console.log` lines.
5. **4-Dialect SQL Expression AST Parser Inside the Agent Just to Mask Unaliased Expressions (`SelectLabels.java`, 720 LOC; `SqlTokens.java`, 363 LOC):**
   - Because someone might run `SELECT LOWER(password) FROM staff` without an `AS password` alias (`S160`), `skillroy-protocol` includes a 1,345-line multi-dialect SQL lexer and `SELECT`-list parser (`STANDARD`, `MYSQL`, `POSTGRESQL`, `SQLSERVER`) that parses CTEs, `CASE WHEN`, and `OVER (...)` windows to extract referenced column names—and if a query selects a constant or subquery with no column reference (`SELECT 1`), `DataQuery.java` (`D776`) replaces every non-null cell in that unnamed column with `<redacted>` whenever *any* `MaskPolicy` is registered! And whenever any `MaskPolicy` is registered, `SQLException.getMessage()` is withheld (`messageWithheld: true`, `S22`), making SQL debugging painful!

> **Recommendation:** **Kill Spec `fn-1` (`S180`), close `S181` and `S182` as "Not Applicable — Trusted Local Test Harness," and declare a moratorium on security delta reviews.** In a PoC, the operator, the controller, the MCP client, and the configured LLM are in the **same trust zone**. Masking passwords from leaving the machine in LLM prompts and logs is good hygiene; defending the test runner against timing/membership/regex side-channels from its own LLM or MCP client is pure self-sabotage.

---

### 3.2 Where AI Capabilities Are Boxed In by Over-Strict Guards

You noted that PoC/experimentation work thrives on inferring intent and reducing friction. Right now, Phase 5 (`skillroy-inference`) and Phase 7 (`skillroy-vision`) have strong underlying engines that are **strangled by rigid syntactic rules**:

#### A. Tier (b) `llm-sql` Currently Takes *More* Java Code Than Writing SQL by Hand
Look at what `LlmSqlResolver.java` (835 LOC), `AllowListCheck.java` (232 LOC), and `Analyzer.java` (1,207 LOC) require before the LLM is allowed to resolve a slot via SQL:
1. **Exhaustive Schema Lock-In (`SCHEMA_DRIFT`):** Before calling Claude, `AllowListCheck.verify()` runs `SELECT * FROM <table> WHERE 1 = 0` against the live DB for every table in `SqlAllowList.java`. **If a table in the database has even ONE column that you did not manually list in Java (`columns()` or `otherColumns()`), it throws `GuardViolation(SCHEMA_DRIFT)` and aborts!**
   - *Impact on a real enterprise app:* Legacy enterprise tables routinely have 50–250 columns, and migrations add columns constantly. Forcing the pack author to list every single column of every table in Java code (`LendingAllowList.java`, 211 LOC for a tiny HSQLDB schema) destroys PoC agility.
2. **Single-Identity-Column Projection + Mandatory Java `ValueLoader`:**
   - `Analyzer.java` Rule 7 (`SELECT_SHAPE`) forbids the LLM from selecting the columns needed to construct the slot value; the LLM is only allowed to `SELECT` a single primary-key/identity column!
   - Consequently, the developer must write a Java `ValueLoader` with a hand-written SQL query to hydrate the full object from that ID, plus a Java `QueryType`, plus a Java `Verifier`.
   - **Think about that:** If you already have to write the `SqlAllowList` for every column, the `QueryType`, the `ValueLoader` SQL, and the `Verifier` in Java, writing the 5-line `WHERE` clause in a Tier-(a) resolver takes 1/10th the effort of using `llm-sql`!
3. **Over-Aggressive AST & Literal Rejections:**
   - `LlmSqlResolver` rejects any query where a string literal in SQL happens to match any string value in `args` (`literalArgument` check)—which breaks whenever an argument is a status/type enum like `"RETURN"` or `"ACTIVE"`.
   - `Analyzer.java` bans `UNION`, `OFFSET`, `WITH RECURSIVE`, `LATERAL`, and all window functions (`OVER (...)`), and forces every joined table's primary key to be selectable so it can rewrite `ORDER BY`.

> **How to fix `llm-sql` for PoC velocity:** Flip the model from **exhaustive allow-listing** to **live schema introspection + deny-list/redaction**. Let the controller auto-discover tables/columns from `DatabaseMetaData` or `SELECT * WHERE 1=0`, block/mask columns matching secret/PII patterns (`*password*`, `*pin*`, `*secret*`, `*token*`), enforce read-only execution + `LIMIT 200` + timeout, and let the LLM return the row map directly without requiring a pre-written Java `ValueLoader` or `QueryType` for every ad-hoc slot!

#### B. Vision (`aiAct` / `aiExpect`) Is Segregated and Stripped on Export
- Today, `PlanExecutor.java` refuses to run `aiAct` or `aiExpect` even in `--mode live` or `--mode approve`; they can only run inside `ScenarioPlayer.java` (`skillroy session --play`).
- Worse, when `ScenarioPlayer.Result.annotate()` exports a played scenario into a replayable YAML scenario, **it silently deletes all `aiExpect` steps** (`# Live-only (dropped)`) rather than converting the grounded evidence (`mark` / `box` -> component locator + text/state matcher or `expectImage` crop) into a deterministic assertion!
- For PoC work, hybrid scenarios (mostly deterministic steps, with one `aiAct` or `aiExpect` step for a gnarly screen you haven't written a pack verb for yet) are *the* fastest way to iterate.

#### C. Hardcoded Synthetic `PriceTable` Gate (`PriceTable.java`)
- `ClaudeGateway.java` validates at startup that all model IDs exist in `PriceTable.java` and refuses to start if you pass a new or fine-tuned model snapshot ID not hardcoded in Java. Unknown model IDs should warn and fall back to default token pricing, never block experimentation.

---

### 3.3 The Onboarding Tax: 8,266 LOC of Dual-JDK Boilerplate for One Sample App

Look at what it took to onboard the sample lending desk (`skillroy-sample-lending-app`, 7,548 LOC):
- `samples/skillroy-sample-lending-agent`: **23 Java files, 3,939 LOC** (compiled with `--release 8`, zero dependencies, hand-rolled JSON/reflection).
- `samples/skillroy-sample-lending-pack`: **19 Java files, 4,060 LOC** (compiled with `--release 25`, hand-coded `VerbSpec`s, hand-coded JSON Schemas in `LendingStepSchemas.java`, `QueryType`s, `SlotResolver`s, `SlotVerifier`s, `ValueLoader`s, `Oracle`s, `SqlAllowList`, `Fingerprinter`).
- `samples/skillroy-sample-lending-pack-branch`: **5 Java files, 267 LOC**.
- Even `samples/skillroy-sample-web-host`—a toy app with **zero custom verbs and zero database tables**—required **9 Java classes (729 LOC)** across two modules just to run two 32-line YAML files!

Why is onboarding so heavy?
1. **Everything is a Java SPI before it can be configured:** Instead of allowing declarative YAML/JSON configuration for common patterns (e.g., naming a JDBC `DataSource` bean/static getter, naming custom button classes and their click method, naming password/masked component patterns, or defining composite macro verbs in YAML), every single customization requires implementing Java 8 SPIs in the agent and Java 25 SPIs in the controller.
2. **Verb Definitions and JSON Schemas Are Duplicated by Hand:** In `LendingSteps.java` (438 LOC) and `LendingStepSchemas.java` (266 LOC), every domain verb's parameter parsing and JSON Schema are written twice by hand.

---

### 3.4 The 3-Artifact Lock-In (`.yaml` + Committed `.plan.json` + Multi-Hash `.bindings.json`) and "Plan Version 7"

In `samples/skillroy-sample-lending-scenarios`:
- There are **18 YAML scenarios totaling ~880 lines**.
- Checked into Git alongside them are **7,027 lines of `.plan.json`** and **5,204 lines of `.bindings.json`**—a **14x artifact amplification**!
- Meanwhile, both `skillroy run` (`RunCommand.java`) and JUnit's `ScenarioTests.java` **already compile `.yaml` files into `Plan` objects in memory in milliseconds!**

What does committing `.plan.json` and maintaining Plan versions 1 through 7 actually cost you?
- `Plan.java` is **2,045 lines long** largely because it maintains strict version-gated key sets (`TOP_KEYS_V1` through `STEP_KEYS_V7`) for an internal intermediate representation on a pre-1.0 project with zero external users.
- Every `.bindings.json` locks in a 5-hash chain (`sourceHash` -> `planHash` -> `resolveHash` -> `argsHash` -> `inputsHash` + `fingerprint`), plus 17+ SQL introspection queries per `llm-sql` slot in `provenance`.
- Whenever you tweak a domain verb in your pack or edit a step in a `.yaml` file, `PlansUpToDateTest` fails and `replay` breaks until you regenerate `.plan.json` and re-approve `.bindings.json` across every seed and variant.

> **Recommendation:** Treat `Plan` as an **in-memory compiled representation** (or an optional build cache in `target/`), not a mandatory Git-committed artifact with 7 backwards-compatible schema versions. And in `.bindings.json`, only hash the **slot resolution inputs (`resolveHash` / `argsHash`)**, NEVER the UI steps (`planHash`), so editing a scenario's UI steps never invalidates its bound database test data!

---

### 3.5 Duplicated & Unverified Infrastructure Across the Codebase

1. **Dual Execution Engines in `skillroy-scenario` (5,301 LOC):**
   - `PlanExecutor.java` (**2,863 lines**) and `ExecutorSession.java` (**2,438 lines**) are two parallel 2,500-line engines that both run preflight checks, install hooks, evaluate capabilities, execute steps, check expectations, compare images, and write JSONL records. Batch execution (`PlanExecutor`) should simply be a deterministic script driver over a single step-execution core.
2. **Two Separate Bytecode Instrumentation Engines (`skillroy-agent-hooks` 4,960 LOC + `skillroy-agent-byteman` 3,545 LOC):**
   - Maintaining both a shaded Byte Buddy Advice engine AND a reflective in-process Byteman engine—each with its own bytecode constant-pool parsers (`ClassFileRefs.java`), pre/post `ClassFileTransformer` byte recorders, and copy-pasted 350-line lock-free value encoders (`hooks/Capture.java` vs. `byteman/BindingEncoder.java`)—adds 8,500+ lines of complex agent code.
3. **4x Copy-Pasted POSIX / Windows ACL / FAT-Volume / Temp-File Owner Probing (~2,500+ LOC):**
   - Nearly identical file-permission and Windows `AclFileAttributeView` inspection code is duplicated across:
     - `agent/skillroy-agent-core/.../SecureFiles.java` (717 LOC)
     - `agent/skillroy-agent-bootstrap/.../OptionsFileCheck.java` (374 LOC)
     - `agent/skillroy-agent-api/.../JarTrust.java` (239 LOC)
     - `controller/skillroy-controller/.../SecureFiles.java` (883 LOC) & `OutputFiles.java` (390 LOC)
     - `controller/skillroy-data/.../SettingsFiles.java` (331 LOC) & `CredentialFiles.java` (256 LOC)
   - Worse: the Windows ACL checks have **never been run on Windows**, and they hard-code English account names (`NT AUTHORITY\SYSTEM`, `BUILTIN\Administrators`, `CREATOR OWNER`) and reject standard Windows folders (like `C:\temp`) or macOS `staff`-group-writable config files!
4. **The 10-Document Contract Synchronization Tax:**
   - Look at Criterion `R9` in `.flow/specs/fn-1-close-s180s-wait-match-oracle-gap-151.md`: to make a 25-line change in `CallRunner`, the spec requires surgical edits across **10 separate Markdown files** (`phase8-contracts.md`, `phase5-contracts.md`, `phase7-contracts.md`, `threat-model.md`, `SECURITY.md`, `release-checklist.md`, `TODO.md`, `DECISIONS.md`, `base-framework-design.md`, `README.md`) with cross-referenced `B-`, `D-`, `S-`, `A-`, and `gap` numbers.
   - These documents have become append-only legal ledgers (`phase8-contracts.md` is 5,696 lines; `DECISIONS.md` is 2,283 lines; `TODO.md` is 3,501 lines) where every paragraph has 4 layers of parenthetical amendments (`(Phase 6, built...) (Fix wave...) (Phase 7 security fix S111...) (Phase 8 security fix S166...)`). No human—and soon no LLM context window—can read them cleanly.

---

## 4. Triage Matrix: Keep vs. Freeze vs. Simplify Now

| Area / Subsystem | Verdict | Why / Action for PoC Velocity |
| :--- | :---: | :--- |
| **In-JVM Agent Core, EDT-Safe Swing Primitives, Routed Pointer, `Capture`** | **KEEP & PROTECT** | Solves the hardest part of legacy Swing automation cleanly without third-party classpath pollution. |
| **Reflective JCEF / JxBrowser Adapters + `TargetProbe` + `PwThread` CDP** | **KEEP & PROTECT** | Solves real Chromium/JCEF race conditions and frozen-renderer hangs. High leverage. |
| **Interactive Session + Vision Grounding (`V1`/`V2`) + `Recorder` -> YAML Export** | **KEEP & EXPAND** | The killer feature for PoC authoring. Let `PlanExecutor` (`--mode live`) also run `aiAct`/`aiExpect` directly, and convert `aiExpect` evidence into deterministic assertions on export. |
| **Spec `fn-1` (`S180`) + Open Security Findings `S181` & `S182`** | **DROP IMMEDIATELY** | Do not implement `SecureRandom` nonce-rewriting on `sync.await` or break `swing.find` regex locators to prevent local MCP side-channel guessing. |
| **Multi-Lens / 8-Lens Sceptic Security Reviews & `B`/`D`/`S`/`A` Numbering** | **STOP NOW** | Replace the 10-document cross-referencing bureaucracy with concise module Javadocs and a single lightweight `CHANGELOG.md` or `ARCHITECTURE.md`. |
| **Tier (b) `llm-sql` (`AllowListCheck`, `SqlGuard`, `ValueLoader`)** | **SIMPLIFY** | Stop failing on `SCHEMA_DRIFT` when a DB table has unlisted columns. Auto-introspect schema via JDBC metadata, block/mask secret column names by pattern, and allow multi-column `SELECT` without requiring a custom Java `ValueLoader` per slot. |
| **Committed `.plan.json` & Plan v1–v7 Compatibility** | **SIMPLIFY** | Compile `.yaml` in memory at test/run time (which `skillroy run` and `ScenarioTests` already do!). Decouple `.bindings.json` validity from UI step hashes (`planHash`) so editing UI steps doesn't invalidate bound DB data. |
| **Windows ACL / FAT Volume / TOCTOU / `SQLException` Withholding (`S22`)** | **RELAX / FREEZE** | Stop withholding `SQLException.getMessage()` when a `MaskPolicy` is registered (just run `SecretMask` on the error string!). Make strict ACL/permission refusal opt-in or warn-only so it doesn't blow up on Windows or standard dev setups. |
| **Hooks (`skillroy-agent-hooks`) & Byteman (`skillroy-agent-byteman`)** | **FREEZE** | Both work and are optional modules. Do not invest more cycles in bytecode constant-pool verification or deny-list grammar unless a real target app requires a specific hook. |
| **OpenTelemetry (`skillroy-telemetry`)** | **FREEZE** | Already built and off by default. Needs zero further work during PoC phase. |

---

## 5. Recommended Next Steps (Shifting Back to PoC Mode)

If we align on this perspective, here is how I recommend we proceed:

1. **Abort Spec `fn-1` and Archive the Bureaucracy**
   - Delete or close `.flow/specs/fn-1-close-s180s-wait-match-oracle-gap-151.md`.
   - Update `CLAUDE.md` to remove the "change the contract first, mint `B`/`D`/`S`/`A` IDs across 10 docs" workflow and replace it with a **PoC-first engineering rule**: *optimize for low-friction target onboarding, fast iteration, and simple code over theoretical adversarial threat modeling*.

2. **Remove the 4 Biggest Friction Points Blocking Real-App Experimentation**
   - **Fix #1 (DB & `llm-sql` Friction):** Make `AllowListCheck.java` tolerant of unenumerated DB columns (or auto-populate `SqlAllowList` from live JDBC metadata with pattern-based secret column exclusion), and stop `DataQuery.java` from withholding `SQLException.getMessage()` (`S22`) or blanking unnamed expression columns (`D776`).
   - **Fix #2 (Artifact & Edit-Loop Friction):** Stop requiring `.bindings.json` to match `planHash` (only check `resolveHash`/`argsHash`), and make committed `.plan.json` optional so you can edit `.yaml` scenarios and immediately run them without regenerating plans and bindings.
   - **Fix #3 (AI / Vision Ergonomics):** Allow `aiAct` and `aiExpect` to execute in `skillroy run --mode live` / `approve`, and relax `PriceTable.java` so new model IDs don't fail startup.
   - **Fix #4 (Zero-Pack / Low-Code Target Onboarding):** Make it possible to attach Skillroy to a new Java 8 Swing + DB application and start an interactive `skillroy session --vision v1` or `skillroy mcp` session **with zero custom Java pack code** (or a single declarative YAML pack file).

3. **Focus 100% of New Work on Your Real Target Application**
   - The base framework already has more features than 95% of v1.0 testing tools. Every new line of code in `skillroy-test-java` should now be driven exclusively by concrete friction encountered while writing and running scenarios against your actual target application in the sibling repo.
