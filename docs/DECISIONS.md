# Decisions: base framework

Decisions for the base framework (`skillroy-test-java`), as of 2026-10-08 (B1-B23). The project owner answered B1-B14
on 2026-10-02; each entry records the answer, what it changed, and what the build has established. B15 and B16 follow
from those answers. B17 and B18 were taken without the owner, on the evidence of the security review and of the
end-to-end review, B19 as the answers to the open questions of the Phase 2 contract and B20 as those of the Phase 3
contract. On 2026-10-06 the owner confirmed all four, with two vetoes inside B19 (Q10 and Q14, both in the form "the
base stays restrictive by default; a private overlay may override it"), and answered what was still open in B1, B2,
B10 and B12. **B21** (the answers to the fifteen questions of the Phase 4 contract) was taken without the owner later
on 2026-10-06 and **confirmed by the owner on 2026-10-07** without a veto. **B22** (the eleven decisions the Phase 4
build and its review took on their own, the contract's D59-D69) was taken without the owner on 2026-10-07 and
**confirmed by the owner the same evening** (`B22 ok`). **B23** (the eighteen provisional answers of the Phase 5
contract, `design/phase5-contracts.md` §17) was taken without the owner on 2026-10-08 and is **provisional** until the
owner answers it (the interview page). Every decision stays changeable
until the first public release, at a cost that grows with the code written on it. Decisions that concern one tenant
application live in that tenant's private repo, not here.

**How to answer.** One message is enough, for example: `B17 ok, B18 R4 wait for credentials.yaml, B19 ok`.
Any id not mentioned keeps what is recorded here. What genuinely needs the owner is collected in the last section,
"Questions for the owner".

**Decided is not the same as built.** Each entry says which parts exist in code. The contract amendments A17-A33 and
the hardening items H1-H3 are Phase 1.x, built and verified on 2026-10-02 and marked "(1.x, built 2026-10-02)" in
`design/phase1-contracts.md`, which states them as built. Phase 2 (resolution and sample scenario A) was built and
verified on 2026-10-02 and is stated as built in `design/phase2-contracts.md`; its changes to the Phase 1 contract
are marked "(Phase 2, built 2026-10-02)" in `design/phase1-contracts.md`. The results of the verification runs made
on 2026-10-02, after the move to Java 25 and to the new sample application, are recorded in `TODO.md`, not here. Test
counts quoted below are older measurements and say so.

Research references such as `attach F1` mean finding `F1` in `docs/research/attach-instrumentation.md`
(`control-channel`, `swing-driving`, `embedded-browser-cdp`, `embedded-browser-spi`, `vision-input`, `ai-inference`
likewise). `BSn` are the base spikes in `TODO.md`. `An` are contract amendments, `Hn` the hardening items, `Rn` the
review items of the end-to-end review, and `Sn` the confirmed findings of the security review of 2026-10-02.

## Answer sheet

| Id | Decision (short) | Recommended default | Decision (2026-10-02) | State of the build | Still open |
|---|---|---|---|---|---|
| B1 | Licence and publication | Apache-2.0; DCO sign-off, no CLA; pre-publication checklist below | Apache-2.0, DCO, no CLA, as recommended. 2026-10-06: the holder is Mike Mills; the remote is `Skillroy-AI/skillroy-test-java`, private for now; everything in one initial commit, pushed later | `LICENSE`, `NOTICE`, `CONTRIBUTING.md`, the source headers (with the holder's name since 2026-10-06), the dependency audit and the packaged notices exist. The initial commit was made on 2026-10-06; nothing pushed | The push, when the owner says; the repeated security review before the first public release |
| B2 | Coordinates and naming | groupId `ai.skillroy`, artifacts `skillroy-*`, package root `ai.skillroy.testjava`, version `0.1.0-SNAPSHOT`, property prefix `skillroy.` | ok: final as proposed; the sample domain id is now `lending`; the domain behind the groupId confirmed 2026-10-06 | In every module | |
| B3 | Controller and build platform | Java 21 bytecode on any JDK >= 21; Maven 3.9; Playwright Java; planner via `claude -p` | Controller on **Java 25** (`--release 25`, build JDK >= 25); the rest as recommended. Gates: a Java 8 target and a Java 25 target | Baseline and Maven built; Playwright Java 1.63.0 in use since Phase 4 (2026-10-07), in the optional `skillroy-cdp`; the planner is design only | |
| B4 | Agent baseline | Java 8 bytecode via `--release 8`; no third-party classes visible to the host; targets 8-25; gates on JDK 8 and JDK 25 | ok | Built. The Java 25 gate can now be run; its result is in `TODO.md` | |
| B5 | DSL surface | YAML + committed `plan.json` + fluent Java; no Gherkin | ok | YAML and committed plans are built, and since Phase 2 (2026-10-02) committed bindings files per environment; the fluent Java API is not | |
| B6 | CI and LLM policy | Replay-only CI with zero LLM calls asserted; nightly batch re-resolve opens a PR; $0.50 per scenario | ok; the owner adds that interactive use may use inference at run time (B15) | Replay-only CI with zero model calls asserted is built (Phase 2, 2026-10-02): the cost ledger check of every `replay` run and the JUnit stream's trailing `skillroy: zero model calls` test. The nightly re-resolve and the budget are not built | |
| B7 | Model access | Claude API; thin `InferenceGateway` SPI now, Claude the only implementation shipped; Opus-class / Sonnet-class / Haiku-class per task | ok | Design only | |
| B8 | Optional third-party pieces | Byteman module optional and its jar never bundled; JNA optional (emulated attach only); jattach located at run time, not redistributed | ok | None of the three is in the build | |
| B9 | Embedded-browser scope | Keep a reflective `skillroy-agent-jxbrowser` module in the base | Not the default: **one in-process SPI that wraps both JCEF and reflective JxBrowser access**, in one module `skillroy-agent-web` | Built 2026-10-07 (Phase 4): the web SPI, `skillroy-agent-web` with the `jcef` and `jxbrowser` adapters, the contract kit, and the JCEF web host behind the profile `jcef`; spike BS8 passed with conditions 2026-10-06 | The `jxbrowser` adapter against the real product, in a repository that holds a licence (`TODO.md`, "Walls") |
| B10 | Sample application | Keep the domain of the first sample application | Not the default: the sample must come from a non-commercial domain. It is now the **lending desk** (domain id `lending`) | Built: four modules; the whole repository was swept. Since Phase 2 the desk can also serve its database on a loopback port when asked (`lending.dbPort`, B19); its behaviour is unchanged. 2026-10-06: the owner finds the desk far enough from commerce; the deposits stay | |
| B11 | Artifacts and observability | Files only now; OTel export in Phase 6 | ok | `run.jsonl`, `bindings.json`, failure evidence and JUnit XML are built; Phase 2 (2026-10-02) added `cost-ledger.jsonl` per run and the committed bindings files (`bindings/<env>/<scenario>.bindings.json`), and extended `run.jsonl` and `bindings.json` | |
| B12 | Default input fidelity | Semantic rung for data entry; action rung as the default click; component rung, with window activation, for UI-verification steps; Robot opt-in | The `COMPONENT` rung is built in and stays. Push-back on the default only: `ACTION` stays the primitive default, `COMPONENT` is opt-in per scenario | Built: the rung, the scenario setting `ui` and the fall-back (A28, A29, 2026-10-02). The owner accepted the push-back 2026-10-06 | |
| B13 | Safety defaults | Explicit enable flag; `ProductionMarker` SPI; loopback + token only; per-user discovery directory; bounded pool; secret redaction | ok. The security review it asked for was run: 34 confirmed findings (4 medium, 30 low) | Defaults built; the fixes H1-H3 built and verified (2026-10-02; the Windows ACL path not verified on Windows); amendment A34, the agent's redaction of secret parameters named in `_meta.secretParams`, built in Phase 2 (2026-10-02, B19) | |
| B14 | "Degraded" scenario outcome | Fourth outcome `DEGRADED` for a run whose guarded expectations were skipped; reported to JUnit as *skipped* with the reasons | ok | Built; A29 adds degraded click fidelity (built 2026-10-02) | |
| B15 | Interactive use and run-time inference | Two profiles: CI replay with zero model calls; interactive use with inference at run start and between steps | Decided by the owner's note on B6 | The `live` and `approve` binding modes are built (Phase 2, 2026-10-02), tier (a) only and without a model; the session service, the single-call entry, the journal and model calls remain Phase 5, the vision loop Phase 7 | |
| B16 | Vision and human-like input | A bolt-on for later; reserve the seams now, build nothing | Direction decided: the owner wants the base to be able to support it later | Nothing built; the names are reserved in the design | |
| B17 | Control-channel hardening | The agent's own HTTP listener (H1); HMAC authentication, protocol 0.2 (H2) | Taken without the owner 2026-10-02; **confirmed by the owner 2026-10-06** | Built and verified 2026-10-02 | |
| B18 | Review items R2-R4 | `MODAL_OPEN` is `FAIL` / `ASSERTION`; stale discovery files are removed; fixture secrets are masked | Taken without the owner 2026-10-02; **confirmed by the owner 2026-10-06** | Built and verified 2026-10-02 (A30, A23, A25) | |
| B19 | Phase 2 contract answers and additions | The answers to Q1-Q15 of the Phase 2 contract (§17): among them A34 (`secretParams`), the sample's loopback database listener and the `autoSignOn` field; and the sample's limit table renamed `role_cap` | Taken without the owner 2026-10-02; **confirmed by the owner 2026-10-06 with two vetoes**: Q10 (names in binding values are a pack's own choice; the sample keeps them out) and Q14 (the masking threshold is a setting per environment, `masking.minLengthInText`, default 8) | Built and verified 2026-10-02 (Phase 2); the two vetoes built 2026-10-06 | |
| B20 | Phase 3 contract answers | The eight questions of `design/phase3-contracts.md` §14, answered by the orchestrator | Taken without the owner 2026-10-03, **confirmed by the owner 2026-10-06**: no `$env.now`; controller-side aggregates; `ORACLE_SUSPECT` is `ERROR` in every mode; the cross-check is recorded in the bindings file; plan version 3; the desk's date stays out of the fingerprint; scenario A's bindings are regenerated once; the `devices` fact root | Built and verified 2026-10-03 (the Phase 3 matrix in `TODO.md`) | |
| B21 | Phase 4 contract answers | The fifteen questions of `design/phase4-contracts.md` §15, answered by the orchestrator | Taken without the owner 2026-10-06; **confirmed by the owner 2026-10-07** (all fifteen): the script library in `skillroy-protocol`; `skillroy-cdp` optional; the JCEF web host behind the profile `jcef`; frames in the first cut; six read primitives not mutating; frozen-target recovery by opt-in; BS5 against JCEF's Chromium; `auto` path policy; plan version 4; web evidence always; JxBrowser 7, 8 and 9; every rendering mode BS8 cleared; the configured port read from private state; `data:` pages; the image's new packages | Specified 2026-10-06; built, reviewed and fixed 2026-10-07 (`design/phase4-contracts.md` as built); the build took the decisions D59-D69; the runs in `TODO.md` | D59-D69, taken without the owner, stand unless the owner objects (a possible B22) |
| B22 | Phase 4 build decisions | D59-D69 of `design/phase4-contracts.md` §14, taken by the orchestrator during the build and the review | Taken without the owner 2026-10-07; **confirmed by the owner 2026-10-07** (`B22 ok`, all eleven): the trace policy; the narrowed re-probe; the probe's loading rule; the JCEF re-send rule; one outstanding drain; the CDP viewport override on its own session; the endpoint ownership check; the `web.browsers` floor; `BrowserExtras`; `Invocation.secretParams()`; the JCEF raw channel | Built and tested 2026-10-07; verified in the Linux matrix (`TODO.md`) | |
| B23 | Phase 5 contract answers | The eighteen questions of `design/phase5-contracts.md` §17, answered by the orchestrator | Taken without the owner 2026-10-08, **provisional**: the nightly job writes `APPROVED` and the pull request is the review; `approve` reuses an unchanged verified tier (b) value; confidence and row bounds as gates beside the verifier; the strict `SqlGuard`; committed masked recordings so that CI needs no key; refusal fallbacks on; the MCP server read-only by default, destructive never; the planner as the `claude` CLI headless; triage as a separate command; plan version 5; the `call` verb; `REQUIRED` approval on exports; budget exhaustion as `RESOLUTION_FAILED`; prompts not logged by default; Sonnet/Opus/Haiku by role; verbs as the session's tools; three new controller-side dependencies; `inference.yaml` beside `credentials.yaml` | Specified 2026-10-08 (`design/phase5-contracts.md`, not built); spikes BS6 and BS13 started the same night | All eighteen, until the owner answers (`B23 ok` or `B23 Qn veto`) |

Phase 1 details are fixed in `design/phase1-contracts.md`; the same file carries the Phase 1.x specification
(A17-A33, H1-H3). Phase 2 details are fixed in `design/phase2-contracts.md`, whose open questions B19 answers. What
was run and proven is in `TODO.md`. The review items R1-R5 that `TODO.md` listed without a B id are now decided: R1
in B17, R2-R4 in B18, and R5 (a Java 25 target in the Linux image) is done with B3.

---

## B1 Licence and publication

**From:** new.
**Question.** Which licence, when does the repo go public, and how are outside contributions accepted (DCO or CLA)?
**Options.** (a) Apache-2.0. (b) MIT. (c) Keep it private and unlicensed for now.
**Recommendation.** Apache-2.0, with DCO sign-off and no CLA. It carries a patent grant and is compatible with the
libraries the design depends on most: Byte Buddy (Apache-2.0), JNA (dual-licensed, Apache-2.0 or
LGPL-2.1-or-later; take Apache-2.0) and jattach (Apache-2.0). Byteman is LGPL-2.1-or-later and stays optional and
unbundled (B8). What Phase 1 actually depends on: Jackson (Apache-2.0) in the controller, JUnit 5 (EPL-2.0) for
tests, HSQLDB (BSD-style) in the sample application only, and nothing but the JDK inside the target JVM.

**Decided (2026-10-02).** (a): Apache-2.0, DCO sign-off, no CLA. The repository stays unpublished until the owner
says when and where to push.

**Decided (2026-10-06).** The copyright holder is Mike Mills (mike.mills@skillroy.ai): the headers carry the name,
`NOTICE` the name and the address. The remote is `Skillroy-AI/skillroy-test-java` on GitHub, private for now. The
first commit holds everything in the tree as it stood that day, as one initial commit with a DCO sign-off, made on
2026-10-06 after the neutrality check printed "base repo is clean"; the push waits for the owner's word. The packaging
gaps were to be closed before the first commit; they had been closed on 2026-10-02 (below). The domain behind the
groupId is confirmed (B2).

**What exists now.**
- `LICENSE` (the Apache-2.0 text) and `NOTICE` in the repository root.
- `CONTRIBUTING.md`: contributions under Apache-2.0, a `Signed-off-by` line under the Developer Certificate of
  Origin 1.1 on every commit, no contributor licence agreement, and the ground rules of this repository.
- The Apache-2.0 header in every source file (456 files when it was added; since 2026-10-06 it names the holder,
  `Copyright 2026 Mike Mills`). `ci/license-headers.sh` lists every
  file without the header and exits non-zero; with `--apply` it adds the header. It covers `*.java`, `*.xml`,
  `*.sh`, `*.properties`, the `Dockerfile` and the CLI launcher; it does not cover documentation, scenario YAML,
  plan JSON or service files.
- `docs/licence-audit.md`: the dependency licence audit of all 21 reactor modules and the root POM (18 modules in
  every build; the three web host samples only with the Maven profile `jcef`), including test dependencies, build
  plugins and the test image. Its result: everything the project distributes, bundles or depends on is compatible
  with distributing the project under Apache-2.0. The agent contains no third-party code; the CLI distribution is the
  only place third-party code is redistributed (four Jackson jars and SnakeYAML, all Apache-2.0); JUnit (EPL-2.0) is
  test-only; HSQLDB is used by the sample and by tests and is not bundled; Playwright (the optional `skillroy-cdp`)
  and JCEF (the web host samples) are in no distribution.

**Closed inside B1 (2026-10-06).**
1. **The copyright holder's legal name:** Mike Mills. The one line was replaced in `NOTICE` (which also carries the
   contact address), in `ci/license-headers.sh` and in every header.
2. **The first commit:** made on 2026-10-06, everything as one initial commit, after the neutrality check printed
   "base repo is clean" and the header check passed. **The first push:** when the owner says; the remote is private.
3. **The remote:** `Skillroy-AI/skillroy-test-java`, private for now, as the owner confirmed. The root POM names it
   in `<scm>` and `<url>`, and the holder in `<developers>`.
4. **The packaging gaps of the audit** (`docs/licence-audit.md` §1, items 1-4) were closed on 2026-10-02: `LICENSE`
   and `NOTICE` are in `META-INF/` of every jar and at the top level of both zips; the CLI distribution carries
   `THIRD-PARTY.txt` with the Jackson and SnakeYAML attributions and a copy of Apache-2.0; the root POM declares
   `<licenses>`. Two integration tests keep that so. The owner's answer of 2026-10-06, "close them before the
   first commit", was therefore already met.

**Pre-publication checklist.** The six items of the first version of this entry, with their state:

| # | Item | State on 2026-10-02 |
|---|---|---|
| 1 | LICENSE and NOTICE files and a licence header in every source file | Done (the holder's name since 2026-10-06; the packaging gaps closed 2026-10-02) |
| 2 | The dependency licence audit, including test and build-plugin dependencies | Done (`docs/licence-audit.md`). Pieces that are planned but not in the build are listed there as "not yet audited" |
| 3 | No local-environment notes in the docs; nothing tenant-specific; the neutrality check prints "base repo is clean" | Re-run before the first commit on 2026-10-06: clean. To be re-run before every push |
| 4 | A cross-cutting security review of the control channel and the safety gates | Run on 2026-10-02 (B13). Its fixes (B17, H1-H3) are built and verified the same day; the review is to be repeated before the first release |
| 5 | Decide what the first commit contains | Decided 2026-10-06: everything, one initial commit (made) |
| 6 | Confirm whether the remote is public or private, and who can read it | Decided 2026-10-06: private, `Skillroy-AI/skillroy-test-java`; its readers are the owner's to set |

**If you choose otherwise.** MIT: no patent grant, no NOTICE file, otherwise the same work; the headers would be
re-applied with another text. Private: nothing changes technically. A CLA instead of DCO needs a CLA text and a
signing bot.
**Blocks.** The push and any public release wait for the owner; the first commit exists. Does not block local
build work.

## B2 Coordinates and naming

**From:** new.
**Question.** Are these the names to build under?

| Thing | Proposed | Decided |
|---|---|---|
| Maven groupId | `ai.skillroy` | as proposed |
| Artifact prefix | `skillroy-` | as proposed |
| Java package root | `ai.skillroy.testjava` | as proposed |
| Version | `0.1.0-SNAPSHOT` | as proposed |
| System-property and file prefix | `skillroy.` | as proposed |
| Sample domain id | (the first sample's id) | `lending` (B10) |

**Options.** Keep as proposed, or supply other names (for example a different groupId).
**Recommendation.** Keep.
**Decided (2026-10-02).** ok: the names are final as proposed. The sample domain id follows B10 and is `lending`:
it is the primitive namespace, the pack id, the agent module id and the data source id of the sample, and the
sample modules are `skillroy-sample-lending-{app,agent,pack,scenarios}` under the package
`ai.skillroy.testjava.samples.lending`.
**Closed (2026-10-06).** Publishing under `ai.skillroy` on a public Maven repository normally requires proving control
of the matching domain; the owner confirmed that the domain is under the project's control.
**If you choose otherwise.** A mechanical rename of poms, packages, the agent jar's manifest `Agent-Class` /
`Premain-Class` entries, the property prefix and the discovery-file prefix. Cheap now; after the first release the
bootstrap agent class name, the protocol and the property prefix are compatibility surface.
**Blocks.** Nothing.

## B3 Controller and build platform

**From:** formerly D10 (round 1).
**Question.** Four linked choices for the controller side.

| Choice | Options | Recommended | Decided (2026-10-02) |
|---|---|---|---|
| Bytecode baseline | Java 21 / Java 25 | Java 21 (`--release 21`), built on any JDK >= 21 | **Java 25** (`--release 25`), built on any JDK >= 25 |
| Build tool | Maven / Gradle | Maven 3.9 | Maven 3.9 |
| Embedded web views | Playwright Java in the controller JVM / a thin Node sidecar | Playwright Java | Playwright Java |
| Authoring planner | headless `claude -p` called from Java / a TypeScript Agent SDK sidecar | `claude -p` | `claude -p` |

**Why the recommendations.** 21 was the LTS line the design was written against (records, sealed interfaces; the
MCP Java SDK needs 17+, control-channel F18), and a Java 21 baseline would have let the controller run on 21 and
on 25. Maven with `--release` needs no toolchain set-up for the normal build. Playwright Java and `claude -p` keep
everything in one JVM and one language.

**Decided (2026-10-02).** The controller side moves to Java 25. The owner's reasoning: the requirement that
matters on the target side is Java 8 applications running on Java 8; Java 21 is not needed as a gate of its own.
The owner also authorised installing what the gates need. Maven 3.9, Playwright Java and `claude -p` stay as
recommended. Consequences:
- Controller-side modules (`controller/*`, the sample pack, the sample scenarios) compile with `--release 25`; the
  build requires a JDK 25 or later (an enforcer rule). The agent side is unaffected: Java 8 (B4).
- The controller and the CLI no longer run on a Java 21 runtime. The CLI's POSIX launcher refuses a Java older
  than 25 and says so.
- The Linux test image is `eclipse-temurin:25-jdk` (JDK 25.0.4 at the time of writing), with a JDK 8 mounted for
  legacy targets. Development is on JDK 26.
- The gates are a Java 8 target and a Java 25 target. A Java 21 target is no longer run. Results measured on
  Java 21 before the switch stay recorded as measurements; they are not re-run.

**What the build established.** The reactor is 15 Maven modules on Maven 3.9. Controller-side dependencies in use:
Jackson 2.22 (`jackson-databind`, `jackson-dataformat-yaml`) and JUnit 5.14; nothing else. Measured before the
switch, on the Java 21 baseline: the reactor built and passed natively on JDK 26 (1303 tests, headless) and on a
real JDK 21 in the Linux test image (1315 tests, with a display). The runs on the Java 25 baseline are recorded in
`TODO.md`. The `claude -p` planner is not used yet, so that choice is still free of code. Since Phase 4 (2026-10-07)
Playwright Java 1.63.0 is in use, in the optional `skillroy-cdp` (Phase 4 contract, D49), and the reactor has 18
modules plus the three web host samples behind the Maven profile `jcef`.
**If you choose otherwise.** Gradle: rewrite the build files, same module layout. Back to a Java 21 baseline: the
release properties in the POMs, the launcher's version check and the image, a mechanical change as long as no
controller code has come to need a newer API; the controller would run on JDK 21 again.
Node sidecar: adds a Node runtime and an IPC seam inside `skillroy-cdp`. Agent SDK sidecar: adds a TypeScript
component in Phase 5.
**Blocks.** Baseline, build tool and Playwright: nothing now (in use). Planner choice: Phase 5.

## B4 Agent baseline

**From:** new.
**Question.** What does the in-JVM agent promise about Java level, host isolation and supported target JVMs?
**Options.** (a) Java 8 bytecode, targets 8-25. (b) Raise the agent baseline to 11 or 17 and drop Java 8 targets.
**Recommendation.** (a).
- Agent modules and the sample application compile with `--release 8`. Verified on JDK 25.0.1 and 26.0.2 (BS7): it
  compiles, with a warning that release 8 is obsolete.
- When javac drops `--release 8`: compile `agent/*` and the sample application with a real JDK 8 through Maven
  toolchains (the fallback), or pin the agent build to the last JDK that accepts the flag. The build rule that bans
  post-8 APIs runs in both set-ups.
- No third-party classes visible to the host: only the small bootstrap jar goes on the target's class path;
  everything else loads in an isolated child-first loader; JSON is hand-rolled; Byte Buddy is shaded and relocated.
  The one exception is the optional Byteman module (B8).
- Supported target range 8-25. Required test gates: JDK 8 and JDK 25. Every line in between, Java 21 included
  since B3, is best effort until CI has it.

**Decided (2026-10-02).** (a), as recommended. With B3 the two gates are the only target lines that are run.

**What the build established.**
- All nine agent-side modules (the seven under `agent/`, the sample application and the sample agent module)
  compile with `--release 8`; their class files are major version 52. No toolchain is configured; the fallback has
  not been needed.
- The agent-side tests also run on a real Java 8 JVM. Measured before the switch of B3: 803 tests on 1.8.0_492,
  green twice. That run found a real defect the newer JVMs hide: Java 8 parses certain numbers differently, so the
  wire codec rejected valid JSON on Java 8 only. It is fixed, and it is the reason to keep "test JVM on Java 8" as
  a gate and not only "target on Java 8".
- Measured before the switch: the end-to-end acceptance passed in every load mode on a Java 8 target and on a
  Java 21 target, twice each, in the Linux test image. On a Java 21 target dynamic attach works and prints the
  JEP 451 warning; `-javaagent`, `JAVA_TOOL_OPTIONS` and embedding print none.
- **The Java 25 gate.** It had not been run when Phase 1 was first verified, because the image had no JDK 25. The
  image is now built on JDK 25 (B3; review item R5 is done), so the gate can be run there. Its result is recorded
  in `TODO.md`.
- "No third-party classes visible to the host" holds: the agent's six jars depend only on the JDK and on each
  other (confirmed again by the licence audit, which read the constant pools of all their classes), and tests with
  real child JVMs check that nothing but the bootstrap jar is visible to the host.

**If you choose otherwise.** A higher baseline simplifies the agent (newer JDK APIs) but makes every target still
on Java 8 unsupported, which removes the main reason for the three load modes and the attach quirk handling.
**Blocks.** Every agent module (in use).

## B5 DSL surface

**From:** formerly D9 (round 1), generic half.
**Question.** What do scenario authors write, and what does CI execute?
**Options.** (a) YAML source, compiled deterministically to a committed `plan.json`, plus a fluent Java API that
produces the same plan. (b) Java only. (c) Add a Gherkin front end now.
**Recommendation.** (a), Gherkin deferred. The plan is committed beside the YAML and executed verbatim, so a
review sees exactly what will run and replay is deterministic. The authoring model emits JSON that matches the
scenario schema, which maps directly to YAML. Gherkin pays off only when non-developers write scenarios
(ai-inference F20); none are identified.
**Decided (2026-10-02).** (a), as recommended: YAML, a committed plan, a fluent Java API later, no Gherkin.
**If you choose otherwise.** Java only: drop the YAML loader; model-authored scenarios become generated Java, which
is harder to validate against a schema. Gherkin: one more front end onto `PlanCompiler`; nothing else changes.
**What the build established.** YAML scenarios compile deterministically to committed `plan.json` files (a test
compares the committed plans byte for byte), and the executor runs a plan verbatim. The YAML reader is stricter
than plain YAML so that a code such as `0100` cannot silently change value. The fluent Java API is not built.
**Additions to the surface since.** A29 adds an optional top-level scenario key `ui` (B12) and A32 an optional
`await:` on the base verbs `click`, `type` and `press` (both 1.x, built 2026-10-02). B16
reserves four verb names and a plan field for later; of those only the plan field `inference` exists, which Phase 2
writes as `none`. Phase 2 (built 2026-10-02) added the scenario key `oracle`, query slots with a `credential` join,
`db.<table>` checks, `capture` on waits and `timeouts.bind`; plans are version 2, and committed bindings files sit
beside them, one per environment.
**Blocks.** Nothing now (in use).

## B6 CI and LLM policy

**From:** formerly D4 (round 1), generic half.
**Question.** May CI call a model, and how do bindings get refreshed?
**Options.** (a) CI runs `replay` only; a cost ledger asserts zero LLM calls; a nightly job re-resolves through the
batch interface and opens a PR with the bindings diff. (b) Allow live resolution in CI. (c) Replay only, no nightly job.
**Recommendation.** (a). In replay a changed environment fingerprint or a failed verifier yields `REBIND_NEEDED`,
never a silent re-resolution. No API key is present on PR build agents. Default budget: $0.50 per scenario
(round-1 estimate: about $0.02 per resolver call with a cached catalogue, $0.20-0.50 per plan compile). No nightly
figure was proposed; the default is "must be configured, no built-in value".
**Decided (2026-10-02).** (a), as recommended: replay-only CI, zero model calls asserted, a nightly re-resolve
that opens a PR, $0.50 per scenario. The owner adds that **interactive use may use inference at run time**. That
is a second way of running, not a change to CI, and it is recorded as B15. One sentence of the earlier design
changes with it: "no model call mid-scenario" now reads "no model call mid-scenario in CI".
**If you choose otherwise.** (b) puts an API key and run-to-run variation into CI and makes a pass mean different
things on different days. (c) removes the nightly job; bindings are refreshed by hand in `approve` mode.
**What the build established (Phase 2, 2026-10-02).** `replay` makes no model call and the run asserts it: the cost
ledger is checked after every `replay` run (a non-empty one is `ERROR` / `ENVIRONMENT`), `cost-ledger.jsonl` is
written per run, the CLI prints `model calls: N`, and a JUnit stream that runs a plan needing bindings ends with the
test `skillroy: zero model calls`. A changed fingerprint or a failed verifier in `replay` is `REBIND_NEEDED`. The
nightly re-resolve and the budget are not built.
**Blocks.** Nothing now for the binding modes (built in Phase 2); the nightly job waits for Phase 5. Reviewers and
budget values per deployment are a tenant-side decision.

## B7 Model access

**From:** formerly D5 (round 1), generic half.
**Question.** Which model provider does the base assume, and does it need a provider-neutral seam?
**Options.** (a) Call the Claude API directly from the resolvers. (b) Add a thin `InferenceGateway` SPI now, with
the Claude implementation as the only one shipped. (c) Ship several provider implementations.
**Recommendation.** (b). An open-source audience will ask for other providers and for Claude through Bedrock or
Vertex; an SPI of a few methods (structured completion, batch submit/collect, usage/cost report) costs little now
and avoids reworking `skillroy-inference` later. Batch and prompt caching are exposed as optional capabilities.
Model defaults, all configuration: plan compile on the top Opus-class model, resolvers on a Sonnet-class model,
triage on a Haiku-class model (round-1 ids: `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-4-5`).
**Decided (2026-10-02).** (b), as recommended: a thin `InferenceGateway` SPI, Claude the only implementation
shipped. One shape of the SPI is fixed early because of B16: message content is a list of typed parts (text,
image, tool use, tool result), so that a capture can be carried later without breaking the signature.
**Notes.** Round 1 recorded that Bedrock and Vertex lack the batch interface and the MCP connector; re-verify
before choosing them. The authoring planner (`claude -p`) is Claude-specific either way; the MCP server is not.
**If you choose otherwise.** (a) is slightly less code now and a refactor when a second provider appears.
(c) is more code and test surface with no user for it yet.
**Blocks.** Phase 5 only. No live model call is made before then.

## B8 Optional third-party pieces

**From:** formerly D2 (round 1), generic half.
**Question.** How should an open-source base carry three pieces that are not plain Apache-2.0 Java libraries?

| Piece | Why it is special | Recommended |
|---|---|---|
| Byteman 4.0.27 (`skillroy-agent-byteman`, `rule.*` primitives) | LGPL-2.1-or-later. Runs as a second agent jar on the target's system class path, so it is visible to the host, cannot be loader-isolated and cannot be unloaded (only its rules can) | Keep the module optional; never bundle the Byteman jar; the user supplies it |
| JNA (through byte-buddy-agent's emulated attach) | Native code; dual-licensed (Apache-2.0 or LGPL-2.1-or-later) | Optional controller dependency used only by the emulated attach strategy; `jdk.attach` stays primary |
| jattach binary | Native executable per platform; needed to attach as root to a JVM owned by another user or in a container | Do not redistribute; find it on `PATH` or at a configured path |

**Options.** Per piece: bundle it, keep it optional and user-supplied, or drop it.
**Decided (2026-10-02).** As recommended for all three: optional and unbundled.
**If you choose otherwise.** Bundling Byteman puts an LGPL jar in the distribution and its redistribution terms
with it. Dropping Byteman removes ad-hoc `rule.*`; the Byte Buddy hooks module still covers typed hooks. Dropping
JNA or jattach removes attach fallbacks (emulated attach; root or cross-user attach) but not the primary path.
**Blocks.** What `skillroy-agent-dist` contains; Byteman work in Phase 6. Attach fallbacks were not needed for
Phase 1: `jdk.attach` alone attached to the targets tested. None of the three pieces is in the build today, and
the licence audit lists each as "planned, not yet audited": each needs a licence check when it is adopted.

## B9 Embedded-browser scope

**From:** new. Research: `research/embedded-browser-spi.md` (written 2026-10-02) and
`research/embedded-browser-cdp.md`.
**Question.** How does the base reach web views that a Swing application embeds, from inside the target JVM, and
for which browser technologies?
**Options.** (a) Keep a module `skillroy-agent-jxbrowser` in the base as a purely reflective module for
JxBrowser-hosted web views. (b) Move that module to a separate repo; the base keeps only the
`BrowserRegistryAdapter` SPI and the `web.*` primitive contract. (c) One SPI in the base that wraps both JCEF and
reflective JxBrowser access, with the `web.*` primitives written once against it.
`skillroy-cdp` (Playwright over CDP) stays in the base in every case.
**Recommendation before the answer.** (a). Its reasoning still holds for the JxBrowser half of (c): the module
calls the vendor API by reflection, so the base ships no vendor code and no licence, and builds without the
vendor's repository. The cost was that JxBrowser needs a commercial licence at run time, so the module could be
tested in public CI against a stand-in only.

**Decided (2026-10-02).** (c): the owner wants one SPI that wraps both JCEF and reflective JxBrowser access. The
design adopts the "Proposed SPI" and the recommendations R0-R7 of `research/embedded-browser-spi.md`:
- **The SPI** lives in `skillroy-agent-api`, package `…agent.api.spi.web`. `WebEngineAdapter`, one per
  technology, detects whether the technology is present, turns seeds into browser handles and reports the CDP
  endpoints configured at engine start. `WebBrowser` is one browser: URL, frames, `evaluate` returning JSON text,
  events, an init script, resize, a bitmap and the CDP target id. Operations that not every technology offers
  sit behind `WebCapability` flags. `BrowserRegistryAdapter` becomes technology-neutral
  (`Collection<BrowserSeed> seeds()`), so a domain module no longer needs to know a vendor type.
- **One agent module, `skillroy-agent-web`,** replaces the planned `skillroy-agent-jxbrowser`. It holds the
  generic `web.*` primitives, written once against the SPI, and two built-in reflective adapters: `jcef`, built
  on `CefBrowser.getDevToolsClient()` (no debug port needed), and `jxbrowser`, which covers JxBrowser 7, 8 and 9
  through one reflective adapter. Neither adapter has a compile-time dependency; no vendor code and no licence
  ships.
- **CDP stays the preferred controller-side path** where a debug port exists (Playwright, B3). The SPI is the
  in-process path. The debug port remains a launch-time precondition that the base never injects.
- **Testing changes for the better.** JCEF is open source, so a real embedded browser can run in the public Linux
  image: a separate small JCEF sample module (about 152 MB of natives per platform, behind a Maven profile so
  that a build without them still works). An adapter contract kit, published as a test artifact, runs against
  JCEF publicly and against JxBrowser in a repository that holds a licence. The JxBrowser adapter's reflection
  plumbing is tested publicly against a neutral fake. This replaces the earlier open point "stand-in only".
- **A new spike, BS8** "JCEF in the Linux test image" (research R4), comes before the build.
- The capabilities `INPUT` and `RAW_PROTOCOL` are reserved names and are not built in Phase 4. `INPUT` is the
  hook for the later pointer work of B16.
- For Phase 4 planning: an application outside this repository that already hosts JCEF can inform the adapter;
  nothing from it is copied here.

**State (2026-10-07).** Built as Phase 4 (`design/phase4-contracts.md` as built; B21 holds that contract's answers):
the SPI in `skillroy-agent-api`; `skillroy-agent-web` with the two reflective adapters, the `jcef` one on JCEF's raw
DevTools channel (the contract's D69); the contract kit as that module's `test-jar`; and the JCEF web host behind the
profile `jcef`. The kit passed against real JCEF in the Linux image and against the neutral fakes; the `jxbrowser`
adapter waits for a run against the real product in a repository that holds a licence. BS8 passed with conditions on
2026-10-06.
**If you choose otherwise.** (a) returns to a JxBrowser-only module and leaves JCEF to the CDP path, which needs
a debug port that not every host opens. (b) moves the in-process path out of the base altogether; the base loses
its in-process fallback for web views and Phase 4 shrinks to `skillroy-cdp` plus the SPI.
**Blocks.** Phase 4. Its exit criteria gain "the adapter contract kit passes against a real JCEF browser in the
Linux image".

## B10 Sample application

**From:** new.
**Question.** Which domain should the bundled sample application, the public proof target of the base, come from?
**Options.** (a) Keep the domain of the first sample application, a commercial one. (b) A neutral domain with
equivalent shapes (for example a lending desk or a ticket booth).
**Recommendation before the answer.** (a), for one reason: each feature of the sample exercises one base
mechanism, and the first domain supplied all of them without contrivance. Its schema and class names were
invented for the sample.

**Decided (2026-10-02).** (b). The owner: the sample must have nothing to do with commerce, because the base will
be open source. The sample is now **the sample lending desk**: the issue desk of a small community tool library,
where staff lend items to borrowers against a deposit that is handed back when the items return. Domain id
`lending`; modules `samples/skillroy-sample-lending-{app,agent,pack,scenarios}`.

It is structurally the same application as the first sample (the same engine, state machine, prompts, widgets,
database shape and two seeds, with every number kept), so every framework mechanism is still exercised and every
test keeps its meaning. One simplification was made: the deposit step takes an amount and nothing else.

| Sample feature | Base mechanism it exercises |
|---|---|
| An engine thread that blocks on an input queue and silently drops an input its current state does not accept | `ReadinessSource`, `ThreadPolicyProvider`, `SyncPolicy`, the `SEMANTIC` rung, `INPUT_REJECTED` |
| Image-only keypad buttons with empty accessible names, which act only when they own the focus | `NameEnricher`, `ActionFirer`, the name rule of the snapshot, `FOCUS_FAILED` |
| Application-level modal prompts (the induction check, "Supervisor required"); an optional idle lock | `prompt.waiting`, `DialogTextExtractor`, `PromptPolicy`, the verbs `expectPrompt` and `answerPrompt` |
| Staff with roles, permissions and per-role monetary limits (the limit on a return) | Resolvers with verifiers (`lending.access.check`); the authorisation oracle with a bind-time guard |
| Items with deposits, per-day items, and a quantity waiver rule (`BORROW_N_WAIVE_M`) | Waiver-rule resolvers; the expected-waiver oracle with a dry-run cross-check (`lending.waiver.evaluate`) |
| An embedded SQL database | `DataSourceProvider`, `data.query`, stored-row checks (`db.loan`, `db.loan_line`, `db.loan_waiver`, `db.loan_authorisation`) |
| The printed loan slip | Device output: the `DeviceAdapter` `printer`, `lending.slip`, the `slip.printed` event |
| Two seeds that differ in staff, role limits, items and the active waiver rule | The same YAML passing on both; `RESOLUTION_FAILED` and `REBIND_NEEDED` |

Sample scenario A is `return-over-limit-needs-supervisor` (a return above the signed-on assistant's role limit
needs approval by a second staff member). Sample scenario B is `borrow-2-waive-1-slip` (the quantity waiver rule
applies and the printed slip shows the waiver). Both are Phase 2-3 targets; the Phase 1 scenarios are
`basic-loan` and `basic-loan-ui`.

**The whole repository was swept,** not only `samples/`: framework tests, fixtures, Javadoc examples and comments
that borrowed the first sample's vocabulary now use the lending vocabulary or a plainly generic one, and the
documentation follows the same rule. Nothing in the repository names the first sample's domain any more.

**What the build established.** The sample is four modules (application, agent module, domain pack, scenarios)
and carries the whole Phase 1 acceptance: 13 end-to-end checks per load-mode variant, in four variants. Its
awkward properties earned their keep: the focus-gated image buttons, the modal prompt that blocks the engine
thread and the non-thread-safe listener list each exposed a real problem in the framework or in the module
pattern. The results of the runs after the port are recorded in `TODO.md`. Phase 2 (2026-10-02) added the
read-only primitives `lending.deposit` and `lending.limits`, the `autoSignOn` field of `lending.access.check`, an
optional loopback listener for the desk's database (`lending.dbPort`), the limit table's new name `role_cap`, and
scenario A on seeds A and B with one YAML file (B19); what the desk does is unchanged.
**Closed (2026-10-06).** The owner finds a tool library that takes deposits far enough from commerce: the deposits
stay, and scenarios A and B stand as built.
**If you choose otherwise.** Removing the deposits as well means replacing the monetary amounts with a
non-monetary quantity (for example a count of items or of loan days per role), and restating the role limit, the
waiver rule, the deposit step and scenarios A and B on it. The framework modules are unaffected; the four sample
modules and their tests are rework again.
**Blocks.** Phases 2-3 use the sample as their exit-criteria target (in use).

## B11 Artifacts and observability

**From:** formerly D16 (round 1), generic half.
**Question.** What does a run leave behind, and is OpenTelemetry needed now?
**Options.** (a) Files only: `junit.xml`, `run.jsonl`, `agent-events.jsonl`, per-step bundles (snapshot text,
PNG, events), Playwright `trace.zip`. (b) Files plus OTel export from the start.
**Recommendation.** (a), with OTel export in Phase 6. Every call already carries a `correlationId` and a W3C
`traceparent`, so export can be added later without changing the event model. Deferring keeps third-party classes
out of the agent. Default location: under the build output directory of the module that runs the scenarios;
retention is left to the CI system.
**Decided (2026-10-02).** (a), as recommended: files now, OTel export in Phase 6.
**If you choose otherwise.** OTel now adds an exporter to the controller and, if agent-side spans are wanted, a
shaded dependency in the agent (the OTel Java API and SDK support Java 8, control-channel F24).
**What the build established.** Per scenario: `run.jsonl`, `bindings.json`, on failure `failure/` (UI snapshot,
PNG capture, the last 200 events, application state, dialogs) and optionally `artifacts/capture.png`; per run a
JUnit XML file. `agent-events.jsonl` and per-step bundles are not built. Two things to know. The agent redacts
secret-looking values before they leave the target. On the controller side a fixture password is still written to
`run.jsonl` and `bindings.json` in clear until A25 (B18), which masks it, was built on 2026-10-02. Phase 2
(2026-10-02) added `cost-ledger.jsonl` per scenario, new record types in `run.jsonl`, the fuller `bindings.json` of
`design/phase2-contracts.md` §11.3, and the committed bindings files `bindings/<env>/<scenario>.bindings.json`;
passwords now come from `credentials.yaml`, and a fixture may not hold one.
**Blocks.** Nothing before Phase 6. The file layout is used from Phase 1.

## B12 Default input fidelity

**From:** formerly D11 (round 1), generic half.
**Question.** At which level does the framework inject input by default?

| Rung | What it does | Default use |
|---|---|---|
| `SEMANTIC` | The domain module's own input primitive: the input reaches the application the way its UI would hand it over. Needs no focus | Data entry |
| `ACTION` | Fires the widget's action without focus (a pack's `ActionFirer`, else `doClick()` posted to the EDT) | Default rung of `swing.click` (built) |
| `COMPONENT` | Focuses the real component, waits for `hasFocus()`, then `doClick()`. Reports `FOCUS_FAILED` and does not click if focus never arrives. With `activateWindow` it first brings the component's window to the front | Built in. The click default of a scenario that sets `ui: {clickRung: COMPONENT}` (A29, built 2026-10-02) |
| `DISPATCH` | Non-posted mouse or key events delivered straight to the component | On request: components with no action to fire; typing "like a user" |
| `ROBOT` | OS-level events through `java.awt.Robot` | Opt-in only; not in Phase 1 (see B16) |

**Options.** (a) As in the table. (b) Always component level or above (needs a window manager in CI).
(c) Always semantic.
**Recommendation.** (a). Every action reports the rung that fired.

**Decided (2026-10-02).** The owner: "the component rung should be built in", and open to push-back. Two parts:
- **Built in: yes.** The `COMPONENT` rung, with `activateWindow`, is part of the base `swing.click` and stays. It
  is not an optional module and not a pack concern.
- **Push-back, on the default only.** The default rung of the *primitive* stays `ACTION`. Reason: `COMPONENT`
  needs window activation to work at all (see BS4 below), and activation brings a window to the front. That is
  right for a suite whose purpose is UI verification and wrong as a default for interactive use beside a person
  (B15), where it would take the screen away from them. So the choice is made per scenario.
- **What a scenario writes** (A28 agent side, A29 controller side; both built 2026-10-02).
  A top-level key `ui: { clickRung: ACTION | COMPONENT, strict: bool }`, default `ACTION` and `false`. With
  `clickRung: COMPONENT`, a `click` step that names no rung compiles to `swing.click` with `rung: COMPONENT`,
  `activateWindow: true` and `fallback: ACTION`. If the focus still does not arrive, the click is fired through
  the `ACTION` rung, the result says `fidelity: DEGRADED` with the reason, and the best possible outcome of the
  run is `DEGRADED` (B14). With `strict: true` there is no fall-back and the step fails as `FAIL` / `ASSERTION`.
  A step's own `rung`, `activateWindow` and `fallback` win over the scenario setting, and the compiled plan shows
  the resulting arguments.
- A29 also closed a hole: a `click`, `type` or `press` whose result says `fired: false` fails the step. Before it
  such a result was ignored by the executor.
- **Per-step `focusTimeoutMs`, as the sample needed it.** The primitive's default of 500 ms bounds window activation
  and component focus together. On the WM-less test display under emulation that is tight for bringing the desk
  window to the front, and a slow activation would fall back to the ACTION rung, recorded as degraded fidelity, so
  the UI scenario would wrongly end `DEGRADED`. The sample's three clicks therefore carry `focusTimeoutMs: 5000`
  (contract §9.3); the primitive default stays 500 ms, because a click that cannot focus should fail fast where no
  activation is involved. With that margin the scenario's clicks fired on the COMPONENT rung at full fidelity in
  every load mode on both targets, after a prompt had taken the focus away.

To overrule the push-back: `B12 COMPONENT by default` makes `COMPONENT` with activation the default of the
primitive and of every scenario, with `ACTION` as the opt-out.

**Decided (2026-10-06).** The owner accepted the push-back: `ACTION` stays the primitive's default, and a scenario
chooses `COMPONENT`.

**What the build established (BS4, on an Xvfb without a window manager, identical on Java 8 and Java 21).**
- A freshly shown frame is the focused window within about 30 ms, and `requestFocusInWindow()` in the focused
  window succeeds at once.
- In any other window it returns false and the focus never arrives. When a modal dialog closes, nothing gives the
  focus back to the frame behind it. In the sample application, after its prompt closed, the `COMPONENT` rung
  without activation reported `FOCUS_FAILED` in 16 of 16 cases.
- `toFront()` + `requestFocus()` activates such a window in 2 to about 400 ms; the click then works. This is the
  `activateWindow` parameter.
- So **`COMPONENT` cannot be a default rung without window activation**. With activation it changes which window
  is in front, which a scenario may or may not want.
- Posted key events are lost for an unfocused window; the non-posted `DISPATCH` typing reaches it.
- Built: the scenario names the rung, or sets `ui: {clickRung: COMPONENT}`, and gets `FOCUS_FAILED`, the
  automatic fall-back recorded as degraded fidelity, or a failed step under `strict` (A28 and A29, 2026-10-02).

**If you choose otherwise.** (b): CI images need a window manager and runs are slower and more focus-sensitive.
(c): faster, but a broken button wiring would not be detected.
**Blocks.** Nothing now. The built defaults are `ACTION` for clicks and `SET` for typing.

## B13 Safety defaults

**From:** formerly D12 (round 1), generic half.
**Question.** What must be true before the agent starts, and who may talk to it?
**Options.** (a) The proposed defaults below. (b) Stricter: also refuse to start when no `ProductionMarker` is
registered. (c) Looser: allow a non-loopback bind.
**Recommendation.** (a).
- The agent does nothing unless enabled: system property `skillroy.agent.enabled=true`, environment variable
  `SKILLROY_AGENT_ENABLED`, or `enabled=true` in the `EMBEDDED` properties. The `-javaagent` argument string cannot
  enable it (`JAVA_TOOL_OPTIONS` may hand it to JVMs that never asked). In `ATTACH` mode `enabled=true` in the
  options file is accepted, because attaching is a deliberate act by a process of the same OS user.
- A domain pack supplies a `ProductionMarker`; when it matches, the agent shuts down before any module is wired.
  With none registered the agent starts and logs a warning.
- The control channel binds to loopback only, on an ephemeral port. A token (32 random bytes, file mode 0600) is
  required on every request, with Host and Origin checks. Remote use is by SSH tunnel only.
- Read-only mode disables mutating primitives; destructive primitives need `confirm: true`; every wait is bounded.

**Decided (2026-10-02).** (a), as recommended. The defaults are built, and so is the hardening that the security review
produced (H1-H3, built and verified 2026-10-02); how the control channel is hardened is decision B17 (confirmed
2026-10-06). Phase 2 added amendment A34 (B19, built 2026-10-02): a request may name secret parameters in
`_meta.secretParams`, and the agent keeps their values out of its log, the events of the call and its error data.

**The security review was run (2026-10-02).** It was the cross-cutting review this entry and the B1 checklist
asked for: the control channel, token handling, the discovery directory, the safety gates, host safety and the
controller's handling of what a target sends back.
- **Result: 34 confirmed findings, S1-S34: 4 medium, 30 low, none high or critical.** Each was traced through the
  code by a second pass, which also corrected or rejected what did not hold.
- The four medium findings: the controller sends the token to whatever listens on the remembered port, before
  and without verifying the peer (S1); a process without the token can pin large strings in the agent's log
  ring (S2); the JDK HTTP server reads the request line and headers without a size limit before the token check
  on Java 8 runtimes (S3); a process without the token can exhaust the host JVM's file descriptors, and on
  Java 8 its heap, through the JDK HTTP server (S4). The review reported S3 for older Java 8 updates; its
  verification found no size limit in the current Java 8 update line either. All four need a local process on
  the same machine; none gives code execution in the default configuration.
- The low findings are of the same families: stalled or unlimited connections, start-up side effects on the
  host, the trust check on file systems without POSIX permissions, redaction gaps, unbounded reads and regular
  expressions on the controller side, and lifecycle races in the bootstrap.
- **Where the fixes are:** in `design/phase1-contracts.md` as hardening H1-H3 (1.x, built and verified
  2026-10-02). H1 is the agent's own HTTP listener and H2 is HMAC authentication (both are B17). H3 lists the
  disposition of every remaining finding; each is "fix", and each is built. Nothing was accepted as a known risk.
  Where the build deviates from the verified fix (for example a deferred listener start that was not needed once the
  JDK server was gone), `phase1-contract-amendments.md` records it.
- One path cannot be verified here: the access-control fix for file systems without POSIX permissions (S7, S15)
  needs a Windows host, and none is available. It is built and unit-tested on built ACL values; the contract and
  `TODO.md` mark it "not verified on Windows". The owner will provide a Windows 11 host later (2026-10-06), which
  also serves the tenant layers' own testing under Windows and under WSL2.
- The review is to be repeated before the first public release, now that H1-H3 are built.

**As built (contract §2.4, §2.5, §5).** This describes the defaults as the Phase 1 build left them, and where the
review found the behaviour wanting, the bullet says so and names the 1.x fix; every fix named is built.
- *Enable rule per load mode.* `JAVAAGENT`: only the system property or the environment variable; neither the
  agent argument string nor the options file it names can enable the agent. `EMBEDDED`: also `enabled=true` in the
  `Properties` the host passes. `ATTACH`: also `enabled=true` in the attach options or the options file; the
  controller's `AttachSession` always writes it. The consequence to be aware of: any process of the same OS user
  that can attach to a JVM can start the agent in it. That is the attach API's own trust boundary, and
  `-XX:+DisableAttachMechanism` closes it.
- *Two gates, in order.* The enable flag is checked before anything starts (no thread, no port). The production
  marker can only be evaluated once the application is initialised: until every marker has passed, the agent
  serves read-only primitives only, and a marker that matches or throws shuts the agent down. With no marker
  registered the agent serves and logs a warning (seen in every run on the sample application, which registers
  none). Review finding S14: when the module that carries the marker fails to install, the gate saw "no marker"
  and served. H3 made that case fail closed: with no marker and a `FAILED` module the target does not become ready
  unless `allowFailedModules=true`, and `requireProductionMarker=true` refuses outright. The sample registers no
  marker, so a module that fails to load now blocks its readiness (none does today).
- *Per-user discovery directory.* Token and discovery files go to `${java.io.tmpdir}/skillroy-<user>`, created
  owner-only. The agent refuses a directory that another user owns or that others may write to; the controller
  ignores one. Agent and controller compute the same directory, proven with the real agent. Review findings S8
  and S19: another local user could block that directory by creating it first. H3 added one deterministic fall-back
  directory, `<user.home>/.skillroy/run`, chosen by the same rule on both sides (the lost-race case at start is
  unit-tested on the rule only: proving it needs a second OS user).
- *Bounded pool.* At most `httpThreads` threads (default 32), no queue, connections beyond that closed at once,
  at most 8 event streams: a local process without the token cannot make the target create threads without limit.
  The bound was on threads only: open connections, file descriptors, the size of a request head and the time a
  silent connection may hold a thread were not bounded (S3, S4, S10, S11). H1 bounds all four.
- *Secret redaction.* Values of keys that look like secrets are replaced by `<redacted>` in `jvm.info`,
  `jvm.properties` and `agent.info`; the option `redact` adds keys. No token appeared in any CLI output, evidence
  file, agent log, event or target log during the Phase 1 review. The security review added: credentials in the
  user-info part of a URL were not caught (S21), and a JDBC driver's error text could echo values (S22). Both are
  fixed in H3; A21 lets a module add its own secret key names.
- *The agent never harms its host.* It never initialises AWT before the application does, owns only daemon threads
  named `skillroy-*`, and after `agent.disable` none of them remains. The review found one start-up side effect of
  the same kind: creating the JDK HTTP server in `premain` initialised `java.util.logging` before the application's
  `main` on Java 8 (S12, S16). H1 removed it.
- *Observed over raw HTTP.* No token, a wrong token and a token in the query string are 401; an `Origin` header or
  a wrong `Host` is 403; a wrong token with a wrong `Host` is 401, so nothing leaks to an unauthenticated caller.
  Under H2 the token no longer travels at all, and the old `Bearer` header is itself a 401 (proven end to end on
  raw sockets against the real agent).

**If you choose otherwise.** (b) makes the base unusable on a target with no domain pack (ad-hoc exploration).
(c) needs TLS and a real authentication story; not designed.
**Blocks.** Nothing now (built, H1-H3 included). The production predicate itself is tenant-side. The first public
release waits on the repeated review.

## B14 "Degraded" scenario outcome

**From:** new (surfaced while reconciling a tenant layer's needs with the base).
**Question.** Some evidence is not available on every target (a capability that exists only in some load modes or
builds). When a scenario's *guarded* expectation (`capability: <id>` on the matcher) cannot be evaluated, what is
the scenario's outcome and how does CI show it?
**Options.** (a) A fourth outcome `DEGRADED`, mapped to JUnit `<skipped>` with the skipped expectations listed.
(b) Map it to a failure. (c) Map it to a pass with a note. (d) No guarded expectations at all: everything a
scenario checks must be in `requires:`, and a target that lacks it is `PRECONDITION_FAILED`.
**Recommendation.** (a). "Passed, but one check could not be made here" must not look green, and it is not a
failure of the application either; *skipped* is the one JUnit state every CI server renders as neither.
`RunOptions.degradedAs` (`SKIPPED` default, `FAILURE`, `PASS`) lets a pipeline choose. The rule that keeps this
honest stays: anything the scenario cannot do without goes in `requires:`; guards are only for evidence whose
availability genuinely differs.
**Decided (2026-10-02).** (a), as recommended and as built.
**What changes otherwise.** (b)/(c) are one setting. (d) removes the `capability:` guard from the DSL and the
`DEGRADED` status from `RunResult`, and makes scenarios that probe optional evidence fail fast instead.
**As built.** `RunResult.Status` is `PASS`, `DEGRADED`, `FAIL` or `ERROR`. A matcher may carry `capability: <id>`;
when that capability is not `OK` the expectation is skipped and recorded with the capability's state and reason,
and the run's best outcome is `DEGRADED`. The JUnit XML shows it as `<skipped>` listing the skipped expectations;
`RunOptions.degradedAs` and the CLI's `--degraded-as skipped|failure|pass` change that, and the CLI's exit code is 0
for a `DEGRADED` run unless it is mapped to a failure. The JUnit glue aborts the dynamic test (shown as skipped).
Proven end to end in every load mode with a guard on a capability that is `UNAVAILABLE` in Phase 1.
**Two later uses of the same outcome.** A29 (1.x, built 2026-10-02) adds degraded click
fidelity: a click that fell back from `COMPONENT` to `ACTION` is listed in `RunResult.degradedFidelity` and makes
the best outcome `DEGRADED`, with the same mapping (B12). B16 reserves the same rule for a run-time visual
question: a guarded expectation on the controller-side capability `inference.vision`, skipped in `replay`.
**Blocks.** Nothing; the default is implemented in Phase 1 (`phase1-contracts.md` §8.4-§8.5).

## B15 Interactive use and run-time inference

**From:** the owner's note on B6 (2026-10-02): interactive use may use inference at run time.
**Question.** B6 keeps models out of CI. May a run that is not CI call a model while it runs, and under which
rules?
**Options.** (a) No: models are used at authoring and bind time only, everywhere. (b) Two profiles: CI stays
replay-only with zero model calls; interactive use may call a model at run start and between steps. (c) Allow
run-time inference in CI as well.
**Decided (2026-10-02).** (b).
- **CI profile.** `replay` only; zero model calls, asserted by the cost ledger. A plan that needs a model while
  its steps run is not executed in `replay`; it is reported as skipped with that reason (the plan field
  `inference`, B16).
- **Interactive profile.** A person, an authoring tool, or an in-application assistant overlay supplied by a
  tenant layer drives a live target with a model in the loop. Inference is allowed at run start (the `live` and
  `approve` binding modes) and between steps: reasoning over primitive outputs, the vision loop of B16, choosing
  the next action, live triage.
- **The earlier rule changes its scope, not its reason.** "No model call mid-scenario" was justified by timing:
  a model call takes seconds, while the target may hold a modal prompt open or time a session out. In CI that
  stays a prohibition. In interactive use it becomes a constraint the loop must respect: bounded waits, and the
  pack's `PromptPolicy` still applies.
- **One way in.** Everything an interactive session does goes through the executor's step protocol, by a public
  single-call entry of the executor ("execute this call with the step protocol, return events and snapshot"),
  and is journaled. The journal exports ordinary scenario steps and bindings for review. That is how interactive
  work becomes a CI scenario.
- **An in-application overlay never brings a model SDK into the target JVM,** which may be Java 8. Its agent
  module publishes events (for example `assist.requested`) and offers primitives. The controller-side session
  service (the MCP server, or an embedding API of `skillroy-scenario`) subscribes, calls the model through
  `skillroy-inference`, and acts back through primitives. No new transport.
- **Budget and safety.** A per-session budget beside the per-scenario one of B6. Read-only mode, `confirm: true`
  for destructive primitives and the `ProductionMarker` are unchanged; a model's request is never the
  confirmation.

**State.** The `live` and `approve` binding modes were built in Phase 2 (2026-10-02), without a model (tier (a)
resolvers only). The rest is designed, not built: the session service and the model calls arrive in Phase 5, the
vision loop in Phase 7.
**If you choose otherwise.** (a) restores the earlier rule everywhere: no assistant overlay, no vision loop, no
live triage; the executor's single-call entry is still wanted by the MCP surface and the authoring planner.
(c) contradicts B6: a pass would mean different things on different days.
**Blocks.** Nothing now. The executor's single-call entry and the journal are reserved seams (B16); Phase 2 reserved
their names (`ExecutorSession`, `StepJournal`) and built neither.

## B16 Vision and human-like input as a bolt-on

**From:** new (the owner, 2026-10-02: the base must be *able* to support this later). Research:
`research/vision-input.md` (written 2026-10-02).
**Question.** Should the base be able to carry a mode in which a model looks at the screen and acts on it like a
person, and what has to be fixed now so that it can be added later without breaking a contract?
**Options.** (a) Never in the base. (b) A bolt-on for later: reserve the seams now, build nothing. (c) Build it
now.
**Decided (2026-10-02).** (b). The design adopts recommendations R0-R10 of the research note as its direction.
- **Three tiers, the lowest that does the job.** V1 "sees, acts by reference": the model gets the snapshot and a
  `PAINT` capture and answers with a component reference; the existing rungs act. V2 "acts by pixel inside the
  JVM": `swing.hitTest`, then `swing.pointer` routed through the window. V3 "acts like a person at the glass":
  the `ROBOT` rung, opt-in only. None of the three becomes the primary driver; the design's rejection of Robot
  and computer use *as the primary driver* (design §10) stands.
- **Where it lives.** The loop is in the controller, in a separate optional module `skillroy-vision` that depends
  on `skillroy-inference` and `skillroy-scenario`. The agent gets three small primitives (an extended
  `swing.capture`, `swing.hitTest`, `swing.pointer`) and no model code.
- **Record once, replay without a model.** A vision-driven click is hit-tested to a component, acted through the
  step that will be replayed, journaled, and exported as an ordinary step. Where a pixel cannot be mapped to a
  component: a pack `RegionResolver` first, then pixel-anchored replay with a deterministic image guard, then
  live-only.
- **Visual assertions.** At authoring and bind time a model's answer is converted into a deterministic
  expectation or an approved baseline image. A run-time visual verdict exists only in live and interactive use
  (B15), as a guarded expectation on the controller-side capability `inference.vision`: skipped in `replay`,
  best outcome `DEGRADED` (B14). It never gates CI.
- **Seams reserved now.** The names are written into the design catalogue and are not used for anything else;
  nothing is built. The full list is the table in R8 of the research note. The subset that later contracts must
  respect:

  | Area | Reserved |
  |---|---|
  | Agent primitives | `swing.hitTest`, `swing.pointer` (and `web.hitTest`, `web.pointer`) |
  | `swing.capture` | the result fields and parameters of R2 of the research note |
  | Capability ids | the prefix `inference.` for capabilities the controller contributes to the matrix |
  | Plan | the field `inference: none \| bind \| run`; a plan with `run` is never executed in `replay` and is reported as skipped |
  | `InferenceGateway` (B7) | message content as typed parts: text, image, tool use, tool result |
  | Resolution model | `Strategy.LLM_VISION` |
  | Scenario verbs | `aiAct`, `aiExpect`, `pointer`, `expectImage` |
  | Agent SPI | `RegionResolver` |

- **Roadmap.** A new Phase 7 "Vision bolt-on and interactive sessions", after Phase 5 because it needs the
  inference gateway. Four spikes come first, BS9-BS12 (the research note numbers them BS8-BS11; BS8 is taken by
  the JCEF spike of B9): Robot on the test display; routed dispatch against Robot; `PAINT` determinism; grounding
  accuracy and cost.

**State.** Nothing is built. No sub-question blocks anything; the owner need not answer further for now.
**If you choose otherwise.** (a) releases the reserved names and Phase 7; a later change of mind then costs
contract changes (a text-only gateway signature, a capture result that cannot be mapped back to a point).
(c) pulls Phase 7 ahead of Phase 5, which it depends on.
**Blocks.** Nothing now. Contracts written from here on must not give the reserved names another meaning.

## B17 Control-channel hardening (confirmed 2026-10-06)

**From:** the security review of 2026-10-02 (B13) and review item R1 of the end-to-end review. Taken on the review's
evidence, without the owner; **confirmed by the owner on 2026-10-06**.
**Question.** Two linked questions. How is the agent's HTTP listener bounded against a local process without the
token, and how is the 45 ms per call removed (R1)? And how does the controller authenticate to the agent without
handing the token to whoever answers on the port?

**Options, listener.**
- (a) Keep the JDK `HttpServer`; the launcher adds `-Dsun.net.httpserver.nodelay=true` to targets it launches.
  Visible, but does nothing for `ATTACH` and `EMBEDDED`, and fixes none of the findings.
- (b) Keep it; the agent sets that system property around creating its server. Works in every load mode, but
  switches the option for the host's own JDK HTTP servers too, for the life of the JVM: a host-wide side effect
  that B13 forbids. It fixes none of the findings either.
- (c) The agent's own minimal HTTP listener.

**Options, authentication.**
- (d) Keep the bearer token and only teach the client to stop after a loss (the review's interim hardening).
- (e) The token never travels: HMAC request and response authentication.

**Chosen.** (c) and (e), as hardening H1 and H2 (1.x, built and verified 2026-10-02; the as-built text is in
`design/phase1-contracts.md`, and `design/phase1-contract-amendments.md` lists where the build differs from the
wording below). Provisional until the owner confirmed it on 2026-10-06.

- **H1 replaces the JDK `HttpServer`** that earlier text names (the recommendation of B13, spike BS2, the
  design's control-channel section). `agent-core` stops using `com.sun.net.httpserver` and serves HTTP/1.1 from a
  loopback `ServerSocket`: Java 8 API only, no third-party classes, no `java.util.logging`, no reflection into
  JDK internals. Why the JDK server cannot stay:
  - on Java 8 runtimes it reads the request line and headers without a size limit, before the handler and so
    before the token check can run (S3);
  - it has no cap on open connections and no deadline for a silent one, so a process without the token can use
    up the host's file descriptors or hold every pool thread (S4, S10, S11);
  - creating it in `premain` initialises `java.util.logging` before the application's `main` on Java 8, which
    overrides a host that selects its log manager at start-up (S12, S16);
  - it writes a response as two segments, which costs about 45 ms per call on Linux (R1);
  - its limits are JVM-wide system properties, read once and partly absent on Java 8. Setting them would change
    the host, which B13 forbids.
- **What H1 bounds.** Open connections (at most `httpThreads`, default 32; when full, the oldest connection that
  has not authenticated is closed in preference to the new one); the request head (16 KB, at most 64 header
  fields, an absolute deadline of 5 s); the body (a `Content-Length` of at most 8 MB, read under a 30 s
  deadline, and a shared 16 MB budget for the bodies of connections that have not authenticated yet); idle
  keep-alive connections (`httpIdleTimeoutMs`, default 120 s). The `Authorization` header is checked on the head
  alone; a request with a body is verified once the body is in, because the MAC covers it. Each response is one
  write with `TCP_NODELAY`, which removed the stall: measured after the build, 1.4 to 5 ms per call on a Java 8
  target instead of about 45 ms. This decides R1 as its option (c), and amendment A24 (a no-delay option and a
  launcher flag) is withdrawn.
- **H2: the token stays the shared secret and is never put on the wire.** Every request carries an HMAC-SHA256
  over the request, keyed by the token and bound to a session id that the agent generates at every start. Every
  reply carries a MAC that the client verifies before it parses the body. The helpers are in `skillroy-protocol`
  and use only `javax.crypto` from the Java 8 JDK, so the rule "no third-party classes in the target JVM" holds.
  The client also has a terminal *lost* state: after a refused connection, a 401, a failed MAC, a changed session
  or an `agent.stopping` event it sends nothing more. This closes S1 and narrows S5, S6 and S28.
- **The wire protocol version becomes 0.2.** Nothing was ever released on 0.1.

**What it costs.** The agent now owns a small HTTP parser, which is more code to test in `agent-core`, on Java 8
as well. A plain `curl` with a bearer token no longer works; `skillroy call` remains the way to call a primitive
by hand. Every test fake that speaks the protocol has to use the shared helpers.

**The veto forms that were offered** (the owner confirmed on 2026-10-06 instead).
- `B17 no`: keep the JDK `HttpServer` and the bearer token. Then say which of (a) and (b) removes the 45 ms, and
  note that S1, S3 and S4, three of the four medium findings, stay open or need another fix.
- `B17 H1 only`: the agent's own listener with the bearer token kept; S1 then gets option (d) only.
- `B17 H2 only`: HMAC on the JDK `HttpServer`; the listener findings and R1 stay open.

**Blocks.** Nothing: built in Phase 1.x and confirmed. The first public release waits on the repeated review.

## B18 Review items R2-R4 (confirmed 2026-10-06)

**From:** the review items of the end-to-end review that had no B id (`TODO.md`). Taken without the owner on 2026-10-02
(the recommended options) and **confirmed by the owner on 2026-10-06**. R1 is decided in B17. R5 (a Java 25 target in
the Linux image) is not provisional: it is done, following B3 and the owner's authorisation to install what the gates
need. **Question.** Three small contract questions that the review left open.

| Item | Question | Options | Chosen (confirmed 2026-10-06) | Why | Veto form offered |
|---|---|---|---|---|---|
| R2 | How is `MODAL_OPEN` from a step call classified? | `ERROR` / `ENVIRONMENT` (as built); or `FAIL` / `ASSERTION` | `FAIL` / `ASSERTION` (A30). The detail carries the dialog's title and text | A click blocked by a dialog the scenario did not expect is a state of the application, like `INPUT_REJECTED`, not a fault of the environment | `B18 R2 keep ERROR` |
| R3 | What happens to discovery and token files of a JVM that is gone? | Leave them (as built); or remove them | Removed (A23). The agent, at start, deletes files that carry its own pid and another start time. The controller's `Discovery` deletes files whose process is gone when it lists a directory that passed the ownership check; a flag switches that off | Files of a killed JVM otherwise stay in the discovery directory for ever. The agent never touches another pid's files, because it cannot tell portably on Java 8 whether that process is alive | `B18 R3 leave the files` |
| R4 | Are fixture secrets masked in run outputs? | Leave it until `credentials.yaml` exists (Phase 2); or mask now by key name | Masked now (A25, with the shared key rule of A17). A fixture field whose key looks like a secret is used for binding and calls as before, and is replaced by `<redacted>` wherever the run writes to disk or returns evidence, by key and by value | Today a scenario's assistant password is written in clear to `run.jsonl` and `bindings.json` (B11). `credentials.yaml` in Phase 2 remains the proper home for secrets; this closes the gap until then | `B18 R4 wait for credentials.yaml` |

**State.** A23, A25 and A30 are built and verified (2026-10-02), each proven in the end-to-end acceptance
(`phase1-contracts.md` §10 checks 12, 18 and 19). The other Phase 1.x amendments (A18-A22, A27, A31-A33; A26 is
superseded by the fix of S22) close SPI gaps from `TODO.md`, carry no decision of their own and are built too.
Two details decided in the build: the controller's staleness rule for A23 is the listing's 60 s tolerance, not 2 s;
A25 also masks a short secret where it stands in double quotes (`phase1-contract-amendments.md`).
**If you choose otherwise.** Each veto removes one amendment and leaves the built behaviour. None of the three
affects another decision.
**Blocks.** Nothing: built in Phase 1.x and confirmed.

## B19 Phase 2 contract answers and additions (confirmed 2026-10-06, two vetoes)

**From:** the open questions Q1-Q15 of the Phase 2 contract (`design/phase2-contracts.md` §17) and two additions
that came with the answers. The project lead took them on 2026-10-02, without the owner, so that the
Phase 2 build could start; they are written into the contract as decisions D33-D36 and into §17. **On 2026-10-06 the
owner confirmed them, except Q10 and Q14, vetoed as the table records.**
**Question.** The fifteen questions of the contract's second draft (two of them, Q5 and Q8, closed in that draft
already), plus: may the sample's limit table be renamed, and is the design overview corrected together with the
code? The answers and the rename produced four decisions of the contract: D33, A34 `secretParams`; D34, the sample's
loopback database listener; D35, the `autoSignOn` field; D36, the limit table renamed `role_cap`.

| Q | Question | Answer | Veto form offered |
|---|---|---|---|
| Q1 | Which plan version does Phase 2 write? | Version 2: Phase 1.x left `Plan.VERSION` at 1 | – (follows from the code) |
| Q2 | Should `replay` insist on approved bindings? | No: `replay` accepts tier (a) bindings marked `NOT_REQUIRED`; no approval switch | `B19 Q2 veto`: `replay` refuses them |
| Q3 | A secret under a neutral key on the wire (the sign-on password is the `value` of an input) | Yes: amendment A34, `_meta.secretParams` (D33) | `B19 Q3 veto` |
| Q4 | End-to-end coverage of the controller-side JDBC channel | Yes: the sample serves its database on a loopback port when `lending.dbPort` is set (D34), and the acceptance runs scenario A over JDBC once per target Java | `B19 Q4 veto` |
| Q5 | The order of resolver tiers | Closed in the second draft (D25): the next resolver is asked when no candidate of the previous one passes | – |
| Q6 | Who is `approval.by`? | A free-text label, default the mode name | `B19 Q6 veto`: a person's name by default |
| Q7 | One file for secrets and JDBC sources? | Yes, `credentials.yaml` | `B19 Q7 veto`: an environment file beside it |
| Q8 | How is the schema checksummed? | Closed in the second draft: the columns of the tables the pack reads, one statement per table | – |
| Q9 | Is a model call in `replay` a failure kind of its own? | No: `ERROR` / `ENVIRONMENT` | `B19 Q9 veto`: a new kind |
| Q10 | Staff names in binding values? | **Vetoed by the owner 2026-10-06.** The base does not restrict what a pack puts into a value type; the sample keeps names out, and the contract recommends that for packs whose test accounts are people. A private overlay whose test staff are not real people may carry their names (P2 §11.3) | taken |
| Q11 | How far does "nothing is sent before the start mark" go? | To every failure before it: `requires`, launch properties, pre-flight, bind, served sources | `B19 Q11 veto`: `recover` again after a failed `requires`, launch-property or pre-flight check |
| Q12 | `interactiveSignOn` is not verified by the application | `lending.access.check` answers `autoSignOn`, and the staff verifier checks it (D35) | `B19 Q12 veto` |
| Q13 | The pack version of the plan differs from the running pack's | Logged, not an error | `B19 Q13 veto`: `ENVIRONMENT` ("recompile") |
| Q14 | Secrets shorter than 8 characters | **Vetoed by the owner 2026-10-06.** The length from which a secret is also masked inside longer text is a setting per environment: `masking.minLengthInText` in `credentials.yaml`, default 8, from 1 to 1000 (P2 §4, §7.3). The base never refuses a credential for its length; an environment with short test credentials lowers the threshold and accepts false redactions. The agent's own rule (A34) keeps 8 | taken |
| Q15 | The bindings file's provenance depends on the credentials file | Recorded, as the contract says | `B19 Q15 veto`: leave the dropped candidates out |

The two additions: the sample's limit table is renamed `role_cap` (D36), with its columns, in the application, its
seeds, the agent module, the pack, every test and every document; and the design overview's module table, resolver
signature and portability claim of scenario A were corrected together with the contract.

**What it changed.** The Phase 2 contract (its third draft) and, through its §16.1, the Phase 1 contract: A34 in the
protocol, the agent core and the controller's `CallOptions`, with the executor naming the secret arguments; the sample's
listener and its read-only account; the `autoSignOn` field and the staff verifier's fifth condition; the rule that
nothing is sent to the target for a failure before the start mark; and the table rename everywhere. The vetoes of
2026-10-06 changed §4, §7.3, §11.3, §11.4 and §17 of the Phase 2 contract and A25 in the Phase 1 contract. The owner's
reason, in neutral terms: a private overlay must be able to use the existing test accounts of its environments, whose
credentials may be short and whose staff records name no real person; the base may stay restrictive as long as the
overlay can override it, and both vetoes take that form. **State of the build.** Built and verified 2026-10-02 with
Phase 2 (`TODO.md` has the runs), stated as built in `design/phase2-contracts.md` and, where Phase 1 changed, in
`design/phase1-contracts.md`. Where the build differs from an answer as worded, the contract says so; for example, the
database that the listener serves has an administrator with a random name and password and no `SA`, because the build
found that HSQLDB lets any connected user change another user's password. The two vetoes of 2026-10-06 are built and
tested (native reactor, the same day): `SecretMask` and `Credentials` carry the threshold `masking.minLengthInText`, and
the contract's sentence on personal data in value types is now the sample's practice and a recommendation (§11.3). **The
veto forms that were offered** (Q10 and Q14 were taken on 2026-10-06; the others confirmed).
- `B19 Q3 veto`: removes A34 from the agent core, the controller (`CallOptions.secretParams`) and the executor; the
  agent's redaction goes back to key names alone, and the end-to-end check of the agent's log goes with it.
- `B19 Q4 veto`: removes the listener (`lending.dbPort`, the servable database and its read-only account) and the
  JDBC block of the end-to-end acceptance; the controller-side channel stays covered by headless tests only.
- `B19 Q12 veto`: removes the `autoSignOn` field of `lending.access.check` and the verifier's condition (5); the "is
  asked for a password" condition then rests on the resolver's SQL alone.
- The other forms are in the table. A veto of the rename is not offered: the pack's fingerprinter needs the table
  name as an upper-case literal, and the project's neutrality check rejects the upper-case form of the old name.
**Blocks.** Nothing.

---

## B20 Phase 3 contract answers (confirmed 2026-10-06)

**From:** the questions of the Phase 3 contract (`design/phase3-contracts.md` §14), answered by the orchestrator on
2026-10-03 without the owner so that the Phase 3 build can start; they produced the contract's decisions D37-D46.
**Confirmed by the owner on 2026-10-06**, every one of the eight.
**Question.** Eight points the contract could have decided either way.

| Q | Question | Answer | Veto form offered |
|---|---|---|---|
| Q1 | Should `$env.now` exist as a bind-time constant excluded from the hash? | No: a resolver reads the target's business date and records the read; the verifier re-checks the date window in every mode (D39) | `B20 Q1 veto`: `$env.now` as a hash-excluded constant |
| Q2 | Database-side or controller-side aggregates in `db.*` checks? | Controller-side over the rows read, bounded by the 200-row cap (D40) | `B20 Q2 veto`: SQL aggregates generated into the statement |
| Q3 | `ORACLE_SUSPECT` in `replay`: `ERROR` or `REBIND_NEEDED`? | `ERROR` / `ORACLE_SUSPECT` in every mode: a disagreement is a problem with the test | `B20 Q3 veto`: `REBIND_NEEDED` in `replay` |
| Q4 | Does a cross-check belong in the bindings file? | Yes, as an informational key; `replay` recomputes (D41) | `B20 Q4 veto`: leave it out |
| Q5 | `planVersion` 3, or keep 2? | 3 (D41) | `B20 Q5 veto`: keep 2 and tolerate the new keys |
| Q6 | Should the sample's business date join the fingerprint? | No: it would stale every bindings file daily (D44) | `B20 Q6 veto` |
| Q7 | Regenerate scenario A's bindings for the two added fingerprint tables, or add a component? | Regenerate once (D45) | `B20 Q7 veto`: a separate component |
| Q8 | A `devices` base fact root now, or the pack fact only? | Now (D43) | `B20 Q8 veto`: pack fact only |

**What it changed.** The Phase 3 contract and, through its §13.1, the Phase 1 and Phase 2 contracts; the code of Phase 3
follows them (built the same day). A further decision taken during the build, D47, keeps the business date out of every
recorded text so that a bindings file is the same from one day to the next. **State of the build.** Built and verified
2026-10-03 (`design/phase3-contracts.md` as built; the matrix in `TODO.md`). **Blocks.** Nothing; confirmed. The vetoes
that were offered would have removed built behaviour: `B20 Q1 veto` adds `$env.now` and makes every bindings file of a
date-dependent scenario change daily; `B20 Q2 veto` moves aggregates into the generated SQL per dialect; `B20 Q8 veto`
removes the `devices` root and scenario B's two assertions through it.

---

## B21 Phase 4 contract answers (confirmed 2026-10-07)

**From:** the questions of the Phase 4 contract (`design/phase4-contracts.md` §15), answered by the orchestrator on
2026-10-06 without the owner so that the Phase 4 build can start; they produced the contract's decisions D48-D58.
**Confirmed by the owner on 2026-10-07** through the interview page (`B21 ok`, no veto). The owner's accompanying notes
restate the principle behind the B19 vetoes (a base default may be restrictive as long as a private overlay can
override it: short test credentials, names of fictitious staff in scenarios) and the later Windows 11 host, also
used under WSL2 (`TODO.md`, "Walls").
**Question.** Fifteen points the contract could have decided either way.

| Q | Question | Answer | Veto form offered |
|---|---|---|---|
| Q1 | Where does the shared JavaScript library (aria walker, DOM actions, tap buffer) live? | In `skillroy-protocol` as `WebLib`, read by the agent module and by `skillroy-cdp`, so that both paths return identical shapes (D48) | `B21 Q1 veto`: the agent module owns it and `skillroy-cdp` keeps a tested copy |
| Q2 | Is `skillroy-cdp` (Playwright, a 194 MB driver bundle) optional? | Yes: found by `ServiceLoader<WebPath>`; the CLI distribution and the JUnit glue ship without Playwright (D49) | `B21 Q2 veto`: a hard dependency of the executor |
| Q3 | A second sample target (the JCEF web host, three modules) in `samples/` behind the profile `jcef`? | Yes, the natives as a test-scope Maven dependency selected by platform (D50) | `B21 Q3 veto`: a repository of its own |
| Q4 | Frames in the first cut? | Yes: `web.browsers` lists frames and every verb takes a `frame` key; one frame model (D56) | `B21 Q4 veto`: main frame only |
| Q5 | Which `web.*` primitives are mutating? | The reads are not (`ariaSnapshot`, `dom.text`, `waitFlag`, `bitmap`, `browsers`, `cdp.status`); the rest are (D51) | `B21 Q5 veto`: everything that runs script is mutating |
| Q6 | May the CDP attach reload a page target that does not answer the liveness probe? | By scenario opt-in only (`web: {frozenTargets: reload}`); the default fails and names the target; `close` is refused (D52) | `B21 Q6 veto`: never, or by default |
| Q7 | BS5's target: JCEF's Chromium in the image, or a stock Chromium download? | JCEF's Chromium; a stock-Chromium run is a documented manual procedure (D53) | `B21 Q7 veto`: Playwright's Chromium in the image as well |
| Q8 | Path policy `auto` by default, `cdp` and `agent` as pins, an unusable pinned path `PRECONDITION_FAILED`? | Yes to all three | `B21 Q8 veto`: `agent` by default, `cdp` by opt-in |
| Q9 | Plan version 4? | Yes; the reader accepts 1-4 (D58) | `B21 Q9 veto`: keep 3 |
| Q10 | Web evidence on failure always when the target has a browser? | Yes: a Swing failure may have a web cause | `B21 Q10 veto`: only for plans with a web verb |
| Q11 | JxBrowser lines covered by the one adapter? | 7, 8 and 9 (B9); the descriptor keeps them apart | `B21 Q11 veto`: drop 7 |
| Q12 | Rendering modes in the public acceptance? | Every mode BS8 cleared | `B21 Q12 veto`: windowed only |
| Q13 | May the `jcef` adapter read private state to report the configured debug port? | Yes, as a fact; `UNKNOWN` when refused; nothing is set | `B21 Q13 veto`: only a port a pack names in the options |
| Q14 | The web host's pages as `data:` URLs? | Yes; a loopback server can come later | `B21 Q14 veto`: a loopback server now |
| Q15 | The image's fifteen new packages and the natives in the Maven cache: acceptable CI weight? | Yes | `B21 Q15 veto`: the web acceptance leaves the public image |

**What it changed.** The Phase 4 contract (`design/phase4-contracts.md`) and, through its §14.1, the Phase 1, 2 and 3
contracts and the design overview (written in before the build, marked built since). **State of the build.** Specified
2026-10-06; built, reviewed and fixed on 2026-10-07 on these answers: seven implementers in isolated copies, a code
review through three lenses with a sceptic each (contract fit 5 findings: 1 medium, 4 low; host safety and secrets 13:
2 high, 3 medium, 8 low, one medium raised to high by its sceptic; tests and determinism 9: 5 medium, 4 low; and six
more from the sceptics; every one confirmed, none refuted), and a fix wave of three implementers. The contract is as
built, and `TODO.md` has the runs. The build and the review took eleven decisions of their own, recorded in the
contract's §14: D59 the trace (kept out of the run's output directory, withheld when a call may carry a secret), D60
the re-probe after `window.opened`, D61 the probe's loading rule, D62 the JCEF re-send rule, D63 one outstanding
drain, D64 the CDP path's viewport override, D65 the endpoint's ownership, D66 the floor of `web.browsers`, D67
`BrowserExtras` for reload and the bitmap's source, D68 `Invocation.secretParams()` and console masking, D69 the JCEF
raw DevTools channel. BS8 was run on 2026-10-06 (`TODO.md`, `spikes/BS8/RESULT.md`); BS5 is met by the acceptance
against JCEF's Chromium (Q7, D53). **Blocks.** Nothing.

---

## B22 Phase 4 build decisions (confirmed 2026-10-07)

**From:** the decisions taken while building and reviewing Phase 4 on 2026-10-07 without the owner, recorded as
D59-D69 in `design/phase4-contracts.md` §14 (each with the alternatives rejected and the reason). **Confirmed by the
owner on 2026-10-07** through the interview page (`B22 ok`, all eleven, after first vetoing D59 and then withdrawing
the veto). **Question.** Eleven points the build could have decided either way.

| D | Question | Answer | Veto form offered |
|---|---|---|---|
| D59 | Where does a Playwright trace go, and when is one taken? | A private directory of the CDP path, deleted when the path closes; never started when the plan may carry a secret; `failure/web-trace.zip` is the only trace a run keeps | `B22 D59 veto`: a trace whenever a scenario asks, secrets the author's care |
| D60 | When does the agent re-probe for a browser engine that starts late? | After `window.opened`, only the adapters whose technology is present but not loaded yet, cheaply; a full cycle on a change; an absent technology only by an explicit reprobe | `B22 D60 veto`: no re-probe after `window.opened` |
| D61 | How much may an adapter's probe load from the application's loader? | The gate class must already be loaded; resolution may then load, never initialise, the technology's other classes, within its packages; the kit proves no static initialiser runs | `B22 D61 veto`: UNAVAILABLE until every class the descriptor names is loaded |
| D62 | May the JCEF adapter re-send a protocol call? | Only its own reads that the client refused before sending, once, while the caller waits; never an evaluation or a mutating call | `B22 D62 veto`: nothing is ever re-sent |
| D63 | How are tapped page events drained when a page is busy? | At most one outstanding drain per page (and one `web.waitFlag` poll); a late answer is published, never dropped | `B22 D63 veto`: a longer bound and dropped events |
| D64 | How does the CDP path resize a page? | `Emulation.setDeviceMetricsOverride` on the path's own session, removed by `clear` and at the end of the run (Playwright's `setViewportSize` cannot be undone) | `B22 D64 veto`: `web.resize` by the agent path only |
| D65 | May the CDP path attach to whatever answers at the port? | No: `open()` refuses an endpoint whose `/json/list` names none of the target's browsers; a `DevToolsActivePort` file older than the JVM is ignored | `B22 D65 veto`: attach to whatever answers |
| D66 | What does `web.browsers` report before any browser exists? | Never worse than DEGRADED while an adapter is present, so the call that rebuilds the index is never refused | `B22 D66 veto`: UNAVAILABLE until a browser exists |
| D67 | Does the SPI gain `reload` and a bitmap-source accessor? | Not in Phase 4: a module-internal interface for the built-in adapters; a third-party browser reloads through `location.reload()` and reports `VENDOR` | `B22 D67 veto`: SPI default methods now |
| D68 | May a module learn which parameters of a call are secret? | Yes, `Invocation.secretParams()` filled by the dispatcher from `_meta.secretParams` (an A34 extension), so the web module masks by value | `B22 D68 veto`: masking by key only |
| D69 | Which DevTools channel does the JCEF adapter use? | JCEF's raw channel, correlating answers itself; the public client is the existence check and the fallback (JCEF 152's client loses an answer that arrives before its id) | `B22 D69 veto`: the public client only, lost answers accepted |

**What it changed.** Each is built and tested (the review's fix wave; `TODO.md`, "Phase 4 code review"). **State of
the build.** Built 2026-10-07. **Blocks.** Nothing.

---

## B23 Phase 5 contract answers (provisional, 2026-10-08)

**From:** the questions of the Phase 5 contract (`design/phase5-contracts.md` §17), answered by the orchestrator on
2026-10-08 without the owner so that the groundwork (spikes BS6 and BS13) and, on the owner's word, the build can
start; they produced the contract's decisions D70-D87 (§16). **Provisional** until the owner answers, through the
interview page (`B23 ok`, or `B23 Qn veto` per row; named veto forms as the contract states them).
**Question.** Eighteen points the contract could have decided either way (Q11 withdrawn the same day: nothing to decide).

| Q | Question | Provisional answer | Veto form offered |
|---|---|---|---|
| Q1 | Who approves the nightly job's bindings? | The job writes `APPROVED by re-resolve:<jobId>`; the pull request review is the gate (D75) | `B23 Q1 veto`: files written `REQUIRED`, approved by hand afterwards |
| Q2 | Does `approve` reuse an unchanged, verified tier (b) value without a model call? | Yes, by default (`--reuse-bindings`, D82) | `B23 Q2 veto`: always re-resolve |
| Q3 | Tier (b) gates beyond the verifier: confidence ≥ 0.8 and the model's row bounds? | Both, the threshold a setting | `B23 Q3 veto`: the verifier alone |
| Q4 | `SqlGuard` strictness: no set operations, a function allow-list, values as parameters that must match the query's arguments, `ORDER BY` completed with the key? | Yes to all (D73) | `B23 Q4 veto`, naming the rule (`… functions`, `… union`, `… params`) |
| Q5 | Committed, masked recordings of model exchanges so that CI needs no key? | Yes (D71) | `B23 Q5 veto`: recordings outside the repository |
| Q6 | Refusal fallbacks on by default, both model names in the ledger? | On (D84) | `B23 Q6 veto`: off |
| Q7 | The MCP server read-only by default, mutating by flag, destructive never? | Yes (D76) | `B23 Q7 veto`, naming the change |
| Q8 | The planner as the `claude` CLI headless (B3), tested with a substitute executable? | Yes (D77) | `B23 Q8 veto`: a planner loop on the gateway |
| Q9 | Triage as a separate command, never inside `run`? | Yes (D78) | `B23 Q9 veto`: `run --triage` |
| Q10 | Plan version 5, the sample's bindings regenerated once? | Yes (D81) | `B23 Q10 veto`: keep 4 |
| Q11 | ~~The base verb `call`?~~ Withdrawn 2026-10-08: the verb has existed since Phase 1 (P1 §8.3); the export uses it as it is | – | – |
| Q12 | An exported tier (b) slot is `REQUIRED` unless `--approved-by` is given? | Yes | `B23 Q12 veto`: always `REQUIRED` |
| Q13 | Budget exhaustion as `RESOLUTION_FAILED` / `BUDGET`? | Yes (D74) | `B23 Q13 veto`: `ENVIRONMENT` |
| Q14 | Prompts not logged by default (`inference.jsonl` holds metadata and masked structured answers)? | Yes (D83) | `B23 Q14 veto`: masked prompts always logged |
| Q15 | Model defaults: Sonnet for resolvers, sessions and summaries; Opus for the planner; Haiku for labels; Fable never; images off until Phase 7? | Yes (D84) | `B23 Q15 veto`, naming the class to change |
| Q16 | The session's default tools are the pack's verbs, base primitives opt-in? | Yes (D87) | `B23 Q16 veto`: base primitives by default |
| Q17 | Three new controller-side dependencies (Anthropic Java SDK, JSqlParser, MCP Java SDK) in the CLI distribution? | Yes | `B23 Q17 veto`, naming the piece to keep out |
| Q18 | `inference.yaml` beside `credentials.yaml`, committed, no secret; the key only in the environment? | Yes | `B23 Q18 veto`: options and properties only |

**What it changes.** The Phase 5 contract and, through its §16.1, the Phase 1-4 contracts and the design overview
(written in when the build starts). **State of the build.** Nothing is built; the contract is specified. **Blocks.**
The Phase 5 build waits for the owner's word on B23 as the Phase 4 build waited for B21, unless the owner has asked
for progress on the provisional answers.

## Questions for the owner

**B23** is open as of 2026-10-08: the eighteen provisional answers of the Phase 5 contract, on the interview page.
Before it, none was open as of the evening of 2026-10-07: the owner confirmed **B21** (the fifteen answers of the Phase 4 contract)
and **B22** (the eleven decisions of the Phase 4 build, D59-D69) on 2026-10-07 through the interview page, both
without a veto. The owner answered every earlier
question on 2026-10-06: the holder's name, the remote and the first commit (B1); the domain behind the groupId (B2);
the lending desk stays as it is (B10); the push-back on the click rung stands (B12); B17, B18 and B20 confirmed; B19
confirmed with Q10 and Q14 vetoed in the form "the base stays restrictive by default; a private overlay may override
it". Three resources the owner will provide are recorded under "Walls" in `TODO.md`: a model provider API key when
Phase 5 starts, a JxBrowser licence, and later a Windows 11 host (also usable under WSL2).

The eleven decisions the Phase 4 build took without the owner are B22 above (`design/phase4-contracts.md` §14,
D59-D69), confirmed.