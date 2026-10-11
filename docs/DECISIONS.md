# Decisions: base framework

Decisions for the base framework (`skillroy-test-java`), as of 2026-10-11 (B1-B31). The project owner answered B1-B14
on 2026-10-02; each entry records the answer, what it changed, and what the build has established. B15 and B16 follow
from those answers. B17 and B18 were taken without the owner, on the evidence of the security review and of the
end-to-end review, B19 as the answers to the open questions of the Phase 2 contract and B20 as those of the Phase 3
contract. On 2026-10-06 the owner confirmed all four, with two vetoes inside B19 (Q10 and Q14, both in the form "the
base stays restrictive by default; a private overlay may override it"), and answered what was still open in B1, B2,
B10 and B12. **B21** (the answers to the fifteen questions of the Phase 4 contract) was taken without the owner later
on 2026-10-06 and **confirmed by the owner on 2026-10-07** without a veto. **B22** (the eleven decisions the Phase 4
build and its review took on their own, the contract's D59-D69) was taken without the owner on 2026-10-07 and
**confirmed by the owner the same evening** (`B22 ok`). **B23** (the Phase 5 contract's eighteen answers, `design/phase5-contracts.md` §17, Q11 withdrawn) and **B24** (the eighteen decisions of its build and review, D88-D104 and D110) were taken without the owner on 2026-10-08 and **confirmed by the owner the same day** (`B23 ok`, `B24 ok`). **B25** (the live session's three decisions, D111-D113) was taken without the owner in the afternoon of 2026-10-08 and **confirmed by the owner on 2026-10-09** (`B25 ok`). **B26** (the Phase 6 contract's twenty-two answers, `design/phase6-contracts.md` §17) and **B27** (the 127 decisions of its build, of the code review's fix wave and of the security fix rounds, D160-D286) were taken without the owner on 2026-10-09, the build proceeding on them because the owner asked for progress that day, and **confirmed by the owner on 2026-10-10** (`B26 ok`, `B27 ok`); the owner **signed off B28**, the repeat security review, the same day (`B28 ok`, the eight lows accepted). **B29** (the Phase 7 contract's thirty-nine answers, `design/phase7-contracts.md` §18, which produced D287-D358) was taken without the owner on 2026-10-10 (the build proceeded on it, as it did on B26) and was **confirmed by the owner on 2026-10-11** (`B29 ok`, no veto). **B30** (the 128 decisions of the Phase 7 build, of its code review's fix wave, of the race fix and of the security delta review's fix round, D359-D486, `design/phase7-contracts.md` §17: 72 taken by the build on 2026-10-10 and 56 by the fixers on 2026-10-10 and 2026-10-11) was taken without the owner, while the work ran, and **confirmed by the owner on 2026-10-11** too (`B30 ok`, no veto). The owner **signed off B31**, the Phase 7 security delta review (S101-S148), the same day (`B31 ok`, the two lows S142 and S144 accepted as proposed). Every decision stays changeable
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
| B1 | Licence and publication | Apache-2.0; DCO sign-off, no CLA; pre-publication checklist below | Apache-2.0, DCO, no CLA, as recommended. 2026-10-06: the holder is Mike Mills; the remote is `Skillroy-AI/skillroy-test-java`, private for now; everything in one initial commit, pushed later | `LICENSE`, `NOTICE`, `CONTRIBUTING.md`, the source headers (with the holder's name since 2026-10-06), the dependency audit and the packaged notices exist. The initial commit was made on 2026-10-06; nothing pushed | The push, when the owner says (the repeated security review was signed off by the owner on 2026-10-10, B28, and the Phase 7 delta review on 2026-10-11, B31) |
| B2 | Coordinates and naming | groupId `ai.skillroy`, artifacts `skillroy-*`, package root `ai.skillroy.testjava`, version `0.1.0-SNAPSHOT`, property prefix `skillroy.` | ok: final as proposed; the sample domain id is now `lending`; the domain behind the groupId confirmed 2026-10-06 | In every module | |
| B3 | Controller and build platform | Java 21 bytecode on any JDK >= 21; Maven 3.9; Playwright Java; planner via `claude -p` | Controller on **Java 25** (`--release 25`, build JDK >= 25); the rest as recommended. Gates: a Java 8 target and a Java 25 target | Baseline and Maven built; Playwright Java 1.63.0 in use since Phase 4 (2026-10-07), in the optional `skillroy-cdp`; the planner is design only | |
| B4 | Agent baseline | Java 8 bytecode via `--release 8`; no third-party classes visible to the host; targets 8-25; gates on JDK 8 and JDK 25 | ok | Built. The Java 25 gate can now be run; its result is in `TODO.md` | |
| B5 | DSL surface | YAML + committed `plan.json` + fluent Java; no Gherkin | ok | YAML and committed plans are built, and since Phase 2 (2026-10-02) committed bindings files per environment; the fluent Java API is not | |
| B6 | CI and LLM policy | Replay-only CI with zero LLM calls asserted; nightly batch re-resolve opens a PR; $0.50 per scenario | ok; the owner adds that interactive use may use inference at run time (B15) | Replay-only CI with zero model calls asserted is built (Phase 2, 2026-10-02): the cost ledger check of every `replay` run and the JUnit stream's trailing `skillroy: zero model calls` test. The nightly re-resolve and the budget are not built | |
| B7 | Model access | Claude API; thin `InferenceGateway` SPI now, Claude the only implementation shipped; Opus-class / Sonnet-class / Haiku-class per task | ok | Design only | |
| B8 | Optional third-party pieces | Byteman module optional and its jar never bundled; JNA optional (emulated attach only); jattach located at run time, not redistributed | ok | The Byteman module is built (Phase 6, 2026-10-09, behind BS14; B26 Q2, Q3), its jar never bundled; JNA and jattach are not in the build | |
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
| B21 | Phase 4 contract answers | The fifteen questions of `design/phase4-contracts.md` §15, answered by the orchestrator | Taken without the owner 2026-10-06; **confirmed by the owner 2026-10-07** (all fifteen): the script library in `skillroy-protocol`; `skillroy-cdp` optional; the JCEF web host behind the profile `jcef`; frames in the first cut; six read primitives not mutating; frozen-target recovery by opt-in; BS5 against JCEF's Chromium; `auto` path policy; plan version 4; web evidence always; JxBrowser 7, 8 and 9; every rendering mode BS8 cleared; the configured port read from private state; `data:` pages; the image's new packages | Specified 2026-10-06; built, reviewed and fixed 2026-10-07 (`design/phase4-contracts.md` as built); the build took the decisions D59-D69; the runs in `TODO.md` | – (D59-D69 are B22, confirmed 2026-10-07) |
| B22 | Phase 4 build decisions | D59-D69 of `design/phase4-contracts.md` §14, taken by the orchestrator during the build and the review | Taken without the owner 2026-10-07; **confirmed by the owner 2026-10-07** (`B22 ok`, all eleven): the trace policy; the narrowed re-probe; the probe's loading rule; the JCEF re-send rule; one outstanding drain; the CDP viewport override on its own session; the endpoint ownership check; the `web.browsers` floor; `BrowserExtras`; `Invocation.secretParams()`; the JCEF raw channel | Built and tested 2026-10-07; verified in the Linux matrix (`TODO.md`) | |
| B23 | Phase 5 contract answers | The eighteen questions of `design/phase5-contracts.md` §17, answered by the orchestrator | Taken without the owner 2026-10-08, **confirmed by the owner the same day**: the nightly job writes `APPROVED` and the pull request is the review; `approve` reuses an unchanged verified tier (b) value; confidence and row bounds as gates beside the verifier; the strict `SqlGuard`; committed masked recordings so that CI needs no key; refusal fallbacks on; the MCP server read-only by default, destructive never; the planner as the `claude` CLI headless; triage as a separate command; plan version 5; the `call` verb; `REQUIRED` approval on exports; budget exhaustion as `RESOLUTION_FAILED`; prompts not logged by default; Sonnet/Opus/Haiku by role; verbs as the session's tools; three new controller-side dependencies; `inference.yaml` beside `credentials.yaml` | Specified 2026-10-08 (`design/phase5-contracts.md`); built, reviewed and fixed 2026-10-08; spikes BS6 and BS13 run the same night (BS13's live part next) | **Confirmed by the owner 2026-10-08, all seventeen (eighteen asked, Q11 withdrawn)** |
| B24 | Phase 5 build and review decisions | D88-D104 and D110 of `design/phase5-contracts.md` §16, taken by the orchestrator during the build and the review's fix wave on 2026-10-08 | Taken without the owner 2026-10-08, **confirmed by the owner the same day**: the Anthropic Java SDK pinned to its current line (2.70.0, MIT) with the client built explicitly and the gateway retrying within its own deadline, MCP as `mcp-core` + `mcp-json-jackson2`; a `confirm` argument of any call sent as `_meta.confirm`; a tier (b) row completed by the pack's `ValueLoader`; the inference record in the ledger entry's details; a verb/primitive tool-name collision resolved by the suffix `_primitive`; the rationale and rejections through `ResolveContext` seams into the provenance and the bindings file; a credential declarable for a session's resolve; the CLI session recovering the target when it closes after a mutating call. From the review's fix wave: a plan run on a client's behalf checked against the server's projection and evaluated on masked values, verb tools reaching a never-offered primitive withheld (D96); MCP exports always `REQUIRED` (D97); `web.evaluate` and `web.waitFlag` offered only with `--allow-evaluate`, never to the planner (D98); `data_query` guarded by an allow-list copy with personal and masked columns unreferenceable (D99); the allow-list checked against the source at bind, load and server start (D100); a session's tier (b) resolves metered on the session budget (D101); a failed gateway call as a ledger entry (D102); the planner's brief exempt from the mask, its vocabulary masked (D103); the MCP server running the allow-list check at start only where the target offers `data.query`, an unreachable source not read rather than refusing the start (D104); the live profiles and §15.3 marked not built until a key works (D110) | Built 2026-10-08 (waves 1, 2a, 2b, 2c, the matrix fix, the review's fix wave; `TODO.md`) | **Confirmed by the owner 2026-10-08, all eighteen** |
| B25 | The live session's decisions (D111-D113) | as taken | confirmed 2026-10-09 | built and run live 2026-10-08 | – |
| B26 | Phase 6 contract answers (22 questions: the B4 carve-out's wording, Byteman in process behind BS14, the overlay SPI, the Windows host, telemetry egress, the review's sign-off, SECURITY.md, pass 1's four decision amendments and five accepted lows) | the §17 answers | taken 2026-10-09; **confirmed by the owner 2026-10-10** (`B26 ok`, all twenty-two, no veto) | built 2026-10-09 on the answers while they were provisional (`TODO.md`, "Phase 6"); `NOTICE`, `CONTRIBUTING.md`, the licence audit and, since 2026-10-10, `CLAUDE.md` carry Q1's wording | |
| B27 | Phase 6 build, review and security-fix decisions (127: D160-D286 of `design/phase6-contracts.md` §16, 62 taken by six packages during the build of 2026-10-09 and 65 by the code review's fix wave and the fix rounds of the repeat security review's pass 2) | as taken | taken 2026-10-09; **confirmed by the owner 2026-10-10** (`B27 ok`, all 127, no veto) | built, reviewed, fixed and security-reviewed twice 2026-10-09 (`TODO.md`, "Phase 6"; the final Linux matrix is recorded there) | |
| B28 | The repeat security review's sign-off (S35-S100: 66 confirmed findings, 2 high, 18 medium, 46 low; everything of medium or above fixed; eight lows accepted with a release note each; the "not verified" list; no outside reader yet, not a gate) | the orchestrator recommended yes once the final matrix was green | **signed off by the owner 2026-10-10** (`B28 ok`, the eight lows accepted) | records written 2026-10-09 (`TODO.md`, "Security review (repeat, pass 1)" and "(repeat, pass 2)"); `SECURITY.md` and `docs/release-checklist.md` in the tree | |
| B29 | Phase 7 contract answers (39 questions: the Robot placement and its double opt-in, redaction by default and one rule for pixels and text, root-pane painting, Robot keys left out, the two homes of `region`, `Status.SKIPPED`, the vision module's seams and facade, the merged matrix, baselines by render key and their approval at the step, the gateway's additive changes, the defaults BS12 measured, the sample's number pad, the security delta review, and, added by the contract review: no glass capture for a model, no credential used on a model's or a client's behalf, a Robot press always paired, the controller draws the marks) | the §18 answers | taken 2026-10-10 by the orchestrator without the owner; **confirmed by the owner 2026-10-11** (`B29 ok`, all thirty-nine, no veto) | built 2026-10-10 on them while they were provisional, in four worktree packages, merged into `main`; reviewed (three lenses), fixed and security-reviewed (delta, B31) 2026-10-10/11 (`TODO.md`, "Phase 7") | – (Q25, Q27 and Q34, which rested on the owner's taste or knowledge, are confirmed with the rest; the `jxbrowser` input stays unverified against the real product, B9) |
| B30 | Phase 7 build, review and security-fix decisions (128: D359-D430 of `design/phase7-contracts.md` §17, 72 taken by four packages during the build of 2026-10-10, and D431-D486, 56 taken by the code review's fix wave (FA, FB, FC), the race fix (FX) and the security delta review's four fix packages (SG, SH, SI, SJ), 2026-10-10 and 2026-10-11) | as taken | taken 2026-10-10 and 2026-10-11 by the orchestrator without the owner; **confirmed by the owner 2026-10-11** (`B30 ok`, all 128, no veto) | built, reviewed, fixed and security-reviewed (delta) 2026-10-10/11, merged into `main` with the head `c94aade` (`TODO.md`, "Phase 7"; the final Linux matrix green on `c94aade` (arm64 twice on every leg, amd64 on the Java 25 leg), its amd64 Java 8 legs green on `bc1e250` later on 2026-10-11) | – |
| B31 | The Phase 7 security delta review's sign-off (S101-S148: 48 confirmed findings, 2 high, 13 medium, 33 low; everything of medium or above fixed and 31 lows fixed; S142 and S144 accepted with a release note each; the "not verified" list; the note that the clipboard route S110 predates Phase 7 for the planner and that B28 did not see it; no outside reader yet, not a gate) | the orchestrator recommended yes once the final matrix was green (the amd64 Java 8 legs excepted) and the owner had read the two release notes | **signed off by the owner 2026-10-11** (`B31 ok`, S142 and S144 accepted; the orchestrator did not sign, P6 §12.6) | records written 2026-10-11 (`TODO.md`, "Security review (Phase 7 delta)"); `SECURITY.md` and `docs/release-checklist.md` carry the signature | |
| B32 | Follow-ups after Phase 7 (D487-D491: gaps 94, 99, 92 and 63, taken without the owner on 2026-10-11) | as taken | **confirmed by the owner 2026-10-11** (`B32 ok`, no veto) | built, tested and matrix-verified 2026-10-11 (`TODO.md`, "Phase 7 final matrix") | – |
| B33 | The owner's answers of the evening of 2026-10-11 (gaps 93 and 96, the domain, a JxBrowser licence) | – | **given by the owner 2026-10-11** | recorded 2026-10-11 | – |
| B34 | Follow-ups of the evening of 2026-10-11 (D492-D499: gap 101, the JxBrowser kit run's D493 and D494, gap 95's D495-D499) | as taken | provisional | built and tested 2026-10-11 | – |

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
  with distributing the project under Apache-2.0. The agent contains no third-party code, except (the carve-out of
  `NOTICE`, Phase 6, B26 Q1, confirmed by the owner 2026-10-10) that its optional hooks module carries a relocated copy of Byte Buddy
  (Apache-2.0) and the ASM it bundles (BSD-3-Clause), whose notices travel inside that jar under
  `META-INF/third-party/`, the module being loaded only when the agent option `optionalModules` names it; apart from
  that, the CLI distribution is the place third-party code is redistributed (when this was first written four Jackson
  jars and SnakeYAML, all Apache-2.0; the list now grows with each phase and is in `THIRD-PARTY.txt`); JUnit
  (EPL-2.0) is test-only; HSQLDB is used by the sample and by tests and is not bundled; Playwright (the optional
  `skillroy-cdp`) and JCEF (the web host samples) are in no distribution.

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
| 4 | A cross-cutting security review of the control channel and the safety gates | Run on 2026-10-02 (B13). Its fixes (B17, H1-H3) are built and verified the same day; the review was repeated in two passes on 2026-10-09 and signed off by the owner on 2026-10-10 (B28) |
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
Phase 1: `jdk.attach` alone attached to the targets tested. The Byteman module is built since Phase 6 (2026-10-09, B26 Q2
and Q3; the jar the user supplies is never bundled, licence audit §5); JNA and jattach are not in the build, and the
licence audit lists them under "planned, not yet audited": each needs a licence check when it is adopted.

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
- The review is to be repeated before the first public release, now that H1-H3 are built (done: repeated in two passes
  on 2026-10-09, signed off by the owner on 2026-10-10, B28).

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
**Blocks.** Nothing now (built, H1-H3 included). The production predicate itself is tenant-side. The repeated review
was signed off by the owner on 2026-10-10 (B28).

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

**Blocks.** Nothing: built in Phase 1.x and confirmed. The repeated review was signed off by the owner on 2026-10-10 (B28).

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

## B23 Phase 5 contract answers (2026-10-08; confirmed by the owner 2026-10-08)

**From:** the questions of the Phase 5 contract (`design/phase5-contracts.md` §17), answered by the orchestrator on
2026-10-08 without the owner so that the groundwork (spikes BS6 and BS13) and, on the owner's word, the build can
start; they produced the contract's decisions D70-D87 (§16). **Confirmed by the owner on 2026-10-08**, every row, through the interview page (`B23 ok`).
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
(written in when the build starts). **State of the build.** Built on 2026-10-08 on the then provisional answers, then
reviewed and fixed the same day (`TODO.md`, "Phase 5"). **Blocks.** Nothing; confirmed by the owner on 2026-10-08.

## B24 Phase 5 build decisions (2026-10-08; confirmed by the owner 2026-10-08)

**From:** the decisions taken while building and reviewing Phase 5 on 2026-10-08 without the owner, recorded as D88-D104 and D110 in
`design/phase5-contracts.md` §16 (each with the alternatives rejected and the reason). **Confirmed by the owner on 2026-10-08**, every row (`B24 ok`). **Question.** Seven points the build could have decided
either way.

| D | Question | Answer | Veto form offered |
|---|---|---|---|
| D88 | Which Anthropic Java SDK line, and how is the client built? | The current line at build time (2.70.0, MIT); the client built explicitly from `ANTHROPIC_API_KEY` with the SDK's retries and logging off, the gateway retrying within its own deadline; MCP as `mcp-core` + `mcp-json-jackson2`; `jackson-bom` imported | `B24 D88 veto`: the research note's 2.34.0 with work-arounds, or `fromEnv()` |
| D89 | Does a `confirm` argument of any call reach the agent? | Yes: it is taken out of the parameters and sent as `_meta.confirm` (it never reached the agent before) | `B24 D89 veto`: `confirm` stays an ordinary parameter |
| D90 | How does a model's SQL row become a typed value with a list or a map? | Through the pack's `ValueLoader` for the kind: the model selects the identity columns, the pack's committed reads build the value | `B24 D90 veto`: the model aggregates in SQL, or such value types stay tier (a) |
| D91 | Where does the record of a model call travel? | In `CostLedger.Entry.details`; the run writer writes `inference.jsonl` from the ledger | `B24 D91 veto`: a sink passed through `BindOptions` |
| D92 | A verb and a domain primitive that would share a tool name? | Both offered, the primitive with the suffix `_primitive` | `B24 D92 veto`: refuse the start, or hide the primitive |
| D93 | How do a proposal's rationale and rejections reach the provenance and the file? | `ResolveContext.rationale` and `reject` seams; the bindings file writes `provenance.rationale` last and omits it when null | `B24 D93 veto`: notes only |
| D94 | May a session's resolve declare a credential for a slot? | Yes (`session resolve … --credential <realm>.<key>`; the tools' half open) | `B24 D94 veto`: no credential from a session |
| D95 | Should the CLI's `session` recover the target when it closes after a mutating call (as a run does)? | Yes, always (`recoverOnClose` on; the MCP server keeps its own option) | `B24 D95 veto`: leave the target as the person left it |
| D96 | Is a plan a client runs (`skillroy_scenario_run`, `author --run`) checked against the server's own policy before it runs? | Yes: every call must name a primitive the server would offer (never a destructive one, `agent.*`, `data.query`, a script primitive), no `confirm` or `_meta` anywhere, expectations evaluated on masked values; a verb tool whose compiled calls reach what is never offered is withheld and listed | `B24 D96 veto`: a scenario may run anything its author wrote (the Phase 5 build's behaviour) |
| D97 | May an MCP client name itself the approver of a model-found binding on export? | No: over MCP a tier (b) slot is always exported `REQUIRED` and `approvedBy` is gone; a person approves with `skillroy resolve` (no model call, D82) | `B24 D97 veto`: the client's label as the approver (Q12 as written) |
| D98 | Are `web.evaluate` and `web.waitFlag` (a caller's script in the target's pages) offered over MCP? | Only when a person starts the server with `--allow-evaluate` (read-only: `web.waitFlag`; with `--mutating` both); never to the planner's server; never in a session | `B24 D98 veto`: `web.evaluate` with `--mutating` and `web.waitFlag` as a plain read, as the contract's first table had it |
| D99 | How does `data_query` stop a filter, order or function of a personal column from rebuilding it row by row? | Its guard reads a copy of the allow-list in which personal and row-masked columns are `otherColumns`, so they cannot be named anywhere; a table keyed by one is not readable that way | `B24 D99 veto`: the tier (b) rule as it stands (filter, never select) for `data_query` too |
| D100 | Is an SQL allow-list checked against the database? | Yes, where a connection exists: a zero-row select per table at bind (before the catalogue), the row-mask probe at the contributor's load, both at the MCP server's start; an unknown table, a column set that differs or a masked column refuses; key nullability unchecked | `B24 D100 veto`: trust the pack's list (the build's behaviour) |
| D101 | Do a session's tier (b) resolves count against the session's money budget? | Yes: reserved on the session meter first, the resolve capped at the session's room, settled from the ledger afterwards; every host routes them through `SessionService.resolve` | `B24 D101 veto`: tier (b) resolves on the scenario budget only |
| D102 | Is a failed model call a line of the cost ledger and `inference.jsonl`? | Yes: zero usage and cost unless the provider billed it, with the error; `model calls` counts it | `B24 D102 veto`: failures in the in-memory log only |
| D103 | Is the planner's brief masked before it reaches the model? | No: the brief is the operator's own words; the rest of the planner's prompt (the pack's vocabulary) is masked | `B24 D103 veto`: mask the brief too |
| D104 | What does the MCP server do when the allow-list check cannot reach a source at start? | It runs the check only where the target offers `data.query`; a list the check refuses refuses the start; a source it cannot reach is not read by `data_query` (which says so) and the server starts | `B24 D104 veto`: refuse the start for an unreachable source too |
| D110 | Should the live profiles (`inference-live`, `inference-record`) and the §15.3 live tests be built now, unrun, or marked not built until a key works? | Marked not built: they are built in the same change as their first live run; the documents say what a live re-record changes | `B24 D110 veto`: build them now, skipped without a key |

**What it changed.** Each is built and tested (`TODO.md`, "Phase 5"). **Blocks.** Nothing; confirmed, so the built
behaviour stands as it is.

## B25 The live session's decisions (2026-10-08, afternoon; confirmed by the owner 2026-10-09)

**From:** the decisions taken while closing the live items of Phase 5 on 2026-10-08 without the owner (the owner's
brief: BS13's live part, the live profiles and §15.3 with their first live run, scenario D re-recorded, scenario C
authored, the nightly figures, the matrix), recorded as D111, D112 and D113 in `design/phase5-contracts.md` §16 with the
alternatives rejected and the reason. **Confirmed by the owner on 2026-10-09**, every row (`B25 ok`, no veto).
**Question.** Three points the session could have decided either way.

| D | Question | Answer | Veto form offered |
|---|---|---|---|
| D111 | How do the live profiles work, and what does a live re-record of the sample pin? | `-Pinference-live` and `-Pinference-record` are explicit profiles that set one system property each for the test JVMs; the live tests are failsafe `*LiveIT` classes that skip (never fail) without the property or without the key; `ScenarioDRecordIT` is the record route (records live, then rewrites the committed bindings through the recorded gateway with the label `recorded <date>`); the tier (b) stand-in generator is gone; the tests read the label from the committed file; the gateway sends no `effort` to the Haiku class (Haiku 4.5 refuses it); the cache-hit criterion is read on a warm prefix (the one-request job run twice) | `B25 D111 veto`: name the part (`… profile on the key`, `… keep the generator`, `… label constant`, `… cold ratio`) |
| D112 | Does the planner keep the envelopes the `claude` CLI printed? | Yes, as `<out>/<id>.envelope.json`, masked, beside the YAML, the plan and the authoring record; a person deletes it before committing or keeps it as the fixture of the planner's replay test | `B25 D112 veto`: the envelopes discarded with the planner's temporary directory |
| D113 | What is the default confidence threshold of the tier (b) gate? | 0.6 (was 0.8): the live model reported 0.8, 0.8, 0.8 and 0.7 on four correct answers to the sample's supervisor query, so 0.8 refused a correct statement in about one case of four; the verifier remains the gate that decides, and the threshold stays a setting | `B25 D113 veto`: keep 0.8 (`… 0.8`), or the verifier alone (`… no gate`) |

**What it changed.** Built and run live on 2026-10-08 (`TODO.md`, "Phase 5", the live table). **Blocks.** Nothing;
confirmed, so the built behaviour stands as it is.

## B26 Phase 6 contract answers (2026-10-09; confirmed by the owner 2026-10-10)

**From:** the questions of `design/phase6-contracts.md` §17, answered by the orchestrator on 2026-10-09 without the
owner so that the build can start; they produced the contract's decisions D114-D159 (§16; D149 and D150 as first
written were superseded by D156 and D159 in the contract review of the same day and stand as struck rows). Spike BS3
(the hook go/no-go) reported the same day, passed with conditions, and its facts are in the contract
(`spikes/BS3/RESULT.md`); the repeat security review's first pass (lenses L3-L6) finished the same day with 28
confirmed findings, S35-S62 (1 high, 6 medium, 21 low), every one dispositioned by the Phase 6 build as its fifth
work package: fixed, contract first, or, for five low parts, accepted by the owner with a release note (Q22)
(`notes/2026-10-09/phase6/security/pass1/dispositions.md`). **Confirmed by the owner on 2026-10-10**, every row (`B26 ok`,
no veto); the answers were provisional while the build ran on them.
**Question.** Twenty-two points the contract could have decided either way; nine of them rest on knowledge only the
owner has (the private layers, the Windows host, the telemetry backends, the disclosure channel); four (Q18-Q21) amend
decisions the owner confirmed in B21, B23 and B24, because pass 1 found their premises wrong; one (Q22) accepts five
low findings of pass 1 with a release note each.

| Q | Question | Answer | Veto form offered |
|---|---|---|---|
| Q1 | The carve-out of "no third-party classes inside the target JVM", in words, for `CLAUDE.md`, `NOTICE`, `CONTRIBUTING.md` and the audit: shaded, relocated Byte Buddy and ASM inside the optional hooks module `optional/skillroy-agent-hooks.jar`, loaded only when `optionalModules` names it, only `Spy` on the bootstrap path; may the jar ship in the default home under these words? | Yes, with the wording of §17 Q1 (D114, D115) | `B26 Q1 veto`: a separate download, the home third-party-free; `B26 Q1 veto wording`, with the text to use |
| Q2 | Byteman in Phase 6: build the thin in-process module behind spike BS14, or specify only? | Build it behind BS14 (D132, D134); specified only if the spike fails | `B26 Q2 veto`: specified only |
| Q3 | A second project jar (`skillroy-agent-byteman-sys.jar`, the curated helper and the manager class, compiled against Byteman at `provided` scope) on the target's system class path, inside the B8 exception? | Yes (D132) | `B26 Q3 veto`: no project jar there; `rule.fired` events impossible, Byteman's default helper denied |
| Q4 | How the private layers consume an overlay: a second jar through `DomainPackOverlay`, or a wrapper class of their own registered instead of the base? | The second jar through the SPI (D144) | `B26 Q4 veto`: `DomainPacks` prefers a marked `DomainPack` of the base's id over the base; the SPI is dropped |
| Q5 | Does a private catalogue shape exist that the §5.1 format must carry unchanged? | No: §5.1 is the first catalogue (D121) | `B26 Q5 veto`, naming the fields (`… <field>`) or `… versions` |
| Q6 | Third-party hook targets: off by default, `enabled: true` outside the pack's packages accepted with a warning; does any layer need one in Phase 6? | None in Phase 6; the warning stands (D121, D124) | `B26 Q6 veto`: the reader refuses it |
| Q7 | The Windows host's JDKs (vendor, bitness) and whether tests may run in an interactive logon session there? | Temurin 25 and 8, 64-bit, an interactive session (D137) | `B26 Q7 veto`, naming the JDKs or `… no interactive session` |
| Q8 | `ATTACH` on Windows in scope, or `JAVAAGENT` plus the documented exclusion? | Best effort only (D139) | `B26 Q8 veto`: `ATTACH` joins the Windows exit when the host exists |
| Q9 | Ship `bin/skillroy.bat` unverified from the next commit, marked so? | Yes (D137) | `B26 Q9 veto`: assembled only after the host ran it |
| Q10 | Telemetry backends and what may leave the host: OTLP/HTTP protobuf through the JDK sender, loopback-http or https only, `standard` attributes by default? | Yes (D141, D142) | `B26 Q10 veto grpc` (OkHttp sender, gRPC); `… minimal` (default set); `… loopback` (no off-host export) |
| Q11 | The rule "no third-party classes inside the target JVM" closed for a relocated OpenTelemetry copy? | Closed (D140) | `B26 Q11 veto`: the door stays open for a later agent-side exporter |
| Q12 | Who signs the repeat review (the owner, B28), and is an outside human reader a gate of the first release? | The owner signs; an outside reading recommended, recorded, not a gate (D147) | `B26 Q12 veto`: a gate |
| Q13 | `SECURITY.md`'s disclosure channel and supported versions? | The repository host's private reporting plus the `NOTICE` address; the latest minor only (D148) | `B26 Q13 veto`, naming the channel or the policy |
| Q14 | The one CDS warning line a Java 25 host prints in hooks mode (the bootstrap append; BS3 fact 8): acceptable and documented, or a system-class-path placement option for `Spy`? | Acceptable, documented (D116) | `B26 Q14 veto`: the option `hooks.spyPlacement = bootstrap \| system` |
| Q15 | May the first public release ship with the Windows items marked "not verified"? | Yes (§14) | `B26 Q15 veto`: the release waits for the host |
| Q16 | The repeat review may accept or defer a low finding only, in writing, with a release note, deferred only to a named phase, a documentation-only change counting as an acceptance; nothing medium or above? | Yes (D147) | `B26 Q16 veto none` (nothing accepted at all); `B26 Q16 veto any` (the owner may accept any in writing) |
| Q17 | May read-only mode install `OBSERVE` and `CAPTURE` hooks, with only `STUB` disabled (design §2.2 step 7)? | Yes: `hook.install` is not mutating (D119) | `B26 Q17 veto`: `hook.install` mutating too |
| Q18 | Pass 1's S42: `web.waitFlag` runs a caller's expression, so D51 (B21 Q5) rested on a wrong premise. The descriptor flag `script`, refused in read-only mode by the agent's dispatcher and the CDP path; D98's offer rule unchanged. Confirm the amendment of D51? | Yes (D156) | `B26 Q18 veto`: the flag marks and projects only; S42 accepted (low) with a release note |
| Q19 | Pass 1's S56: a client's `skillroy_scenario_run` spends tier (b) calls outside the session budget. D101 (B24) widened: one `SESSION` meter per MCP server bounds `skillroy_session_ask`, `skillroy_resolve` and a run's tier (b) slots. Confirm? | Yes (D157) | `B26 Q19 veto`: a run stays on the scenario budget; S56 (medium) would then be accepted, which §12.5 forbids, so the owner names another fix |
| Q20 | Pass 1's S57, S59, S62: the MCP server acted under `person("mcp")`, so a verb's own `confirm` was honoured, and D96's verb gate ran on three probes at start. `Origin.Kind.CLIENT` never confirms; the gate runs at call time on the compiled calls for the MCP server and the session service; `author` passes `--client planner`. Confirm the amendment of D96 and of P5 §12.4's host text? | Yes (D158) | `B26 Q20 veto client`: a `PERSON` origin stays and a verb's `confirm` is honoured for every client (accepted in writing); `B26 Q20 veto gate`: probes only, S57 (medium) accepted, which §12.5 forbids as in Q19 |
| Q21 | Pass 1's S58: captures reached the MCP client as unmasked, uncut blobs. A capture's bytes leave the controller to a client only under the inference settings' `images: true` (D84 widened to MCP; D76 amended); otherwise a text resource names the written file; no separate flag. Confirm? | Yes (D159) | `B26 Q21 veto flag`: a separate `skillroy mcp --serve-captures`; `B26 Q21 veto today`: served as today, S58 (medium) accepted, which §12.5 forbids as in Q19 |
| Q22 | Five low parts of pass 1 deferred or fixed by documentation only, each with a release note: S36 part (b), S37 option (i), the pinning part of S40, S48, the `CLAUDE_CONFIG_DIR` part of S60 (the phase each goes to is in §17 Q22). Under §12.5 these are acceptances and need the owner's word. Accept the five? | Yes: accepted as lows, named here and repeated in B28 (D147) | `B26 Q22 veto`, naming the finding to fix now (`… S37`, `… S48`, …): FX builds that part in the same wave |

**What it changes.** The Phase 6 contract and, through its §15.1, the Phase 1-5 contracts and the design overview
(written in when the build starts); `CLAUDE.md`, `NOTICE`, `CONTRIBUTING.md` and the licence audit with Q1's wording;
D51, D76, D84, D96 and D101 as Q18-Q21 say. **State of the build.** The contract was specified and reviewed (72 raw
findings, 65 confirmed, 7 refuted, 27 added by the sceptics, every confirmed one applied) and BS3 passed with
conditions; pass 1 of the security review was dispositioned. **Built the same day** on the answers by six
packages (B27 records the decisions the build took, 62 of its 127): spike BS14 passed with conditions, so Byteman is built (Q2, Q3);
the hooks jar ships in the default home's `optional/` (Q1); Windows is built where Linux can test it and not verified on
a Windows host (Q7-Q9, Q15); pass 1's 28 findings are fixed or, for the five low parts of Q22, accepted with a release
note. `NOTICE` and `CONTRIBUTING.md` carry Q1's wording since 2026-10-09 (`NOTICE` was untrue once the hooks jar
was in the home); `CLAUDE.md` carries it since 2026-10-10, on the owner's confirmation, with one added bracket that names
B8's user-supplied Byteman jar; the same day `CONTRIBUTING.md` was given Q1's closing sentence ("Nothing else, in no
module.") and the same bracket, so that it reads as `CLAUDE.md` does. **Blocks.** Nothing; confirmed, so the built behaviour stands as it is.

## B27 Phase 6 build, review and security-fix decisions (2026-10-09; confirmed by the owner 2026-10-10)

**From:** the decisions taken while building Phase 6 on 2026-10-09 without the owner, by six packages in worktrees
(HK hooks and spy, TL telemetry, CP controller, pack and sample, BW Byteman and Windows, and the two security-fix
packages of FX, S35-S62), recorded as D160-D221 in `design/phase6-contracts.md` §16 (each with the alternatives rejected
and the reason), numbered in the order the packages landed on `main`: TL D160-D168, HK D169-D183, CP D184-D193, BW
D194-D207, FX-2 D208-D215, FX-1 D216-D221; and the decisions taken afterwards, the same day and again without the owner,
by the fixers of the Phase 6 code review (FA the hooks engine, FB the Byteman modules, FC the controller and telemetry, FD the
sample's display test) and by the fix rounds of the repeat security review's pass 2 (SA the control channel, SB the web
views, SC the model boundary and the model as an actor, SD Windows and SQL Server, SE hooks and Byteman, SF telemetry and
run outputs), recorded as D222-D286 in the same place and numbered in the order the fixers landed on `main`: FB D222-D229,
FC D230-D233, FD D234-D235, FA D236-D243, SC D244-D249, SD D250-D261, SA D262-D265, SB D266-D270, SE D271-D279, SF D280-D286 (65
decisions, each fixer's ids from 1 without a gap). **Confirmed by the owner on 2026-10-10**, every row
(`B27 ok`, no veto), with B26; the build had proceeded on both because the owner asked for progress on 2026-10-09. **Question.** One hundred
and twenty-seven points the build, the review and the security fixes could have decided either way (62 + 65).

| D | Question | Answer | Veto form offered |
|---|---|---|---|
| D160 | Where does the telemetry settings reader live? | `TelemetrySettings.load` in `skillroy-scenario`'s `observe` package (plain JDK and StrictYaml); the module re-checks the endpoint and reads the header values | `B27 D160 veto`: the reader in `skillroy-telemetry` (the JUnit glue then depends on the module) |
| D161 | May the `RunObserver` SPI grow past the contract's list? | Yes: default no-op `sessionStarted`, `sessionStepStarted`, `sessionRecord`, `sessionEnded`, `turnStarted`, `toolCallStarted`, `traceId`; `StepStart.origin`; `CallEnd.rttMs` | `B27 D161 veto`: after-the-fact spans only (no session step under its turn, no inbound trace parent) |
| D162 | How does a session's observer travel? | `SessionOptions.observer`; `ExecutorSession.observer()` serves the session service and the MCP server | `B27 D162 veto`: a setter on `ExecutorSession`, or a component on `ServerOptions` |
| D163 | Threads and retries of the exporter? | The SDK's batch worker keeps its name; the sender runs on daemon threads named `skillroy-telemetry`; no retry; no-op meter providers | `B27 D163 veto`: a hand-written batch processor with a named thread, or the SDK's retries |
| D164 | How do the scenario and step records reach the observer? | Through a tap in `RunLog`, by record type, as written | `B27 D164 veto`: an observer call beside each write site |
| D165 | Which header variables and endpoints are refused? | Any `ANTHROPIC_*` variable (not only the four of D141); an endpoint with user information, a query or a fragment | `B27 D165 veto`: the four named variables only, or any URL with an allowed scheme and host |
| D166 | What does `minimal` do with the agent span's name and the correlation id? | Names `agent` without its primitive; `session <id>` and `turn <n>` keep theirs; the correlation id keeps the scenario id (a question left for the code review; **answered by D230**: it is hashed whole, so `B27 D166 veto id` is in effect taken) | `B27 D166 veto`: the five names of §10.3 only; `B27 D166 veto id`: hash the correlation id too |
| D167 | What is the root span called? | `skillroy <command>`, and `skillroy junit` for a JUnit stream | `B27 D167 veto`: `skillroy run` for every command |
| D168 | What do `re-resolve`, `triage` and `author` export? | `re-resolve` and `triage` their root span only; `author` its root, the planner's ledger entries, its bind and its run; the planner's MCP child none | `B27 D168 veto`: plumb the observer through the batch job, triage and the planner's child |
| D169 | How does a caller learn a loader's id for `target.loader`? | `HookProbe.loaderIds` beside the count `loaders` | `B27 D169 veto`: read `loaders` as the list (no id to name) |
| D170 | How does a batch bounded by bytes learn the classes' sizes? | `RetransformBatches.Sizes`; an unknown size counts as the byte bound | `B27 D170 veto`: the three-argument form only |
| D171 | What does the inlined advice bind? | A site key `<id> <class> <method><descriptor>`, resolved by the handler's registry | `B27 D171 veto`: a wider `Spy` (several constants), which changes the spy jar |
| D172 | When is a restore's residue a `HOOK_VERIFY_FAILED`? | Only a `Spy` reference the restored bytes hold and the first bytes did not (HotSpot merges constant pools across another agent's redefinitions) | `B27 D172 veto`: the scan of the restored bytes alone (such a restore then fails and holds the slot) |
| D173 | Which recorder captures at a restore? | A second recorder, registered last for one restore; the first stays ahead of the hook | `B27 D173 veto`: re-register the one recorder last |
| D174 | When does the hook transformer go? | When the ledger is empty | `B27 D174 veto`: as soon as no hook is left |
| D175 | How do the hooks module's fresh-JVM tests get their JVMs? | Six surefire executions of their own; the other classes share one | `B27 D175 veto`: `reuseForks=false` (it runs no test on a Java 8 `-Djvm` under surefire 3.6.0) |
| D176 | What are the ranges of the hook size options? | `maxClassBytes` 1024 to 16 MiB, `maxMethods` 1 to 65535, `maxClasses` 1 to 1024, `capture.maxBytes` 1024 to 1 MiB | `B27 D176 veto`: unbounded, or the ranges you name |
| D177 | How does a detach that leaves classes tell the next probe? | The system property `skillroy.hooks.adviceLeft` holds their count | `B27 D177 veto`: a constant text without the count |
| D178 | A spec over several loaders? | Installed on all its classes or on none | `B27 D178 veto`: hook the classes that pass |
| D179 | A method hooked twice; `capture` on a spec that is not `CAPTURE`? | `UNAVAILABLE`; `INVALID_PARAMS` (as are duplicate ids) | `B27 D179 veto`: two advices on one method |
| D180 | Does `hook.health` answer `otherAgents` and `spy.onBootPath`? | Yes, so that `skillroy hooks status` calls nothing else | `B27 D180 veto`: the probe and install answers only |
| D181 | How is a capture's output bounded? | By the input budget (`hooks.capture.maxBytes`); a full budget drops `args`, then `self`, then `returned` | `B27 D181 veto`: a larger output bound |
| D182 | How are numbers, collections and map keys of a capture encoded? | By the readings of §4.3 (JDK types only; no lock taken; NaN and infinities as text; a policy's non-scalar result redacted) | `B27 D182 veto`: encode any `Number` or `Collection` as such |
| D183 | Is a catalogue of which nothing installs recorded as installed? | No | `B27 D183 veto`: record it |
| D184 | The catalogue reader's details? | Errors with source and line; a malformed resource rethrown on every call; `packages` absent means off unless `enabled: true`; `reflect` an error; `emits` kept as written | `B27 D184 veto`: normalise `emits` (the hash is then not the agent's) |
| D185 | Where does a session's hooks switch live? | `SessionOptions.hooks`, the eleventh component | `B27 D185 veto`: a parameter of `ExecutorSession.open` |
| D186 | Which fallback does step 4 take? | The first `seam` whose primitive the target serves, read from `GET /schema` | `B27 D186 veto`: the seam id read as a capability id |
| D187 | Are `hook.health`, `hook.events` and `rule.list` offered by every MCP and session policy? | Yes, the read-only session policy included | `B27 D187 veto`: only under `--allow-base` |
| D188 | A catalogue with no enabled entry? | "No catalogue": `AUTO` skips, `ON` is `PRECONDITION_FAILED` | `B27 D188 veto`: install it as a catalogue of nothing |
| D189 | Where does the branch overlay's jar sit in the variant's tests? | Off the class path, loaded by `BranchVariant` in its own loader | `B27 D189 veto`: a separate module for the variant's tests |
| D190 | Pack 0.5.0 changed the session prompt's vocabulary line; what happens to the recordings? | The five scripted session stand-ins regenerated; scenario D's two live recordings untouched | `B27 D190 veto`: re-record scenario D live |
| D191 | May the sample application carry call counters for its tests? | Yes, package-private on `AccessControl` and `ReturnCeilings` | `B27 D191 veto`: none (item 6 then cannot say which path calls them) |
| D192 | How does scenario E's headless test run without the hook engine? | `HeadlessDesk` scripts a hooks module | `B27 D192 veto`: E is tested in the image only |
| D193 | Is gap 49 (`inductionRequired` on `LendableItem`) taken now? | No | `B27 D193 veto`: take it now (a new pack version, every plan regenerated) |
| D194 | How is Byteman started? | `listener:false,manager:<the sys jar's RuleManager>`, in that order, nothing else | `B27 D194 veto`: the contract's string with `prop:` (it prints on the host's stdout) |
| D195 | The helper's `fired`? | Fixed-arity overloads of one to eight `Object`s | `B27 D195 veto`: `fired(Object...)` (Byteman's type checker does not match it) |
| D196 | How many byte recorders does the Byteman module need? | Two, ahead of and behind Byteman's transformer; a priming retransformation when a parked `Retransformer` is reused | `B27 D196 veto`: the single recorder (it never sees the reference) |
| D197 | Byteman's trace-map output? | Redirected into the agent's log (WARN, bounded) and removed at uninstall | `B27 D197 veto`: leave it on the host's stderr and record the limit |
| D198 | On Windows, which `claude` is tried first? | `claude` plus each `PATHEXT` extension, before the bare name | `B27 D198 veto`: the bare name first |
| D199 | Which characters does a `cmd.exe /c` configuration refuse? | The double quote, `%`, `^`, `&`, the pipe, `<`, `>`, white space and control characters | `B27 D199 veto`: the contract's shorter list |
| D200 | May a class replace the `Retransformer` that Byteman handed over? | No: `RuleManager.initialize` keeps the first and ignores later calls | `B27 D200 veto`: a plain store |
| D201 | Where do the names of rules a cut-short uninstall left wait? | In `RuleManager`'s slot | `B27 D201 veto`: a system property, or the agent's log |
| D202 | Are `RuleCheck`'s type warnings errors? | Yes, in `rule.check` and `rule.submit` | `B27 D202 veto`: warnings |
| D203 | A rule whose install `threw`? | Removed, `RULE_REJECTED` | `B27 D203 veto`: `installed: true` with the status |
| D204 | What does the rule lint require? | A fully qualified `CLASS` or `INTERFACE`; outside `RULE … ENDRULE` only blank lines and `#` comments | `B27 D204 veto`: unqualified names, or script-level lines |
| D205 | Which containers does a rule's binding encoding read? | Only the JDK's | `B27 D205 veto`: any `Collection` or `Map` (it runs application code on the application's thread) |
| D206 | What is the cut-short test seam? | The system property `skillroy.agent.byteman.test.keepRulesAtUninstall` | `B27 D206 veto`: an agent option |
| D207 | How does `author` write the planner's MCP configuration? | One owner-only file `skillroy-mcp-<16 hex>.json` in the temporary directory | `B27 D207 veto`: an owner-only directory holding the file |
| D208 | Does the MCP `data_query` tool refuse a `$` reference in its SQL or parameters? | Yes, by syntax, whether or not a slot of that name exists; `'a$$b'` and `'US$ 5'` run byte for byte | `B27 D208 veto`: refuse only a reference to a slot the session holds |
| D209 | The overlay bridge and the mask? | No cut before the mask: a request is kept whole to 100000 characters, longer is refused without a model call, then mask and cut at 10000 | `B27 D209 veto`: cut with a margin |
| D210 | The projected tools masked how? | After `strict(...)` by a wrapper; the key rule therefore rewrites the sample's `signOn` and `authorise` descriptions; the five session stand-ins regenerated | `B27 D210 veto`: mask inside `primitive(...)` and `verb(...)`; `B27 D210 veto wording`: reword the sample's descriptions so the rule leaves them |
| D211 | Where does a scope's exhausted state live, and is an actor-less resolve metered? | On `BudgetMeter`; a run's refused reservation exhausts the session (D74); yes, metered | `B27 D211 veto`: a refusal that leaves the session open, or the actor-less path unmetered |
| D212 | The session's verb check? | `ExecutorSession.compile(verb, args, origin)` beside the two-argument form | `B27 D212 veto`: the two-argument form only |
| D213 | A verb withheld at call time? | Listed by `skillroy_catalogue`, not logged | `B27 D213 veto`: log it |
| D214 | S49's test property? | `password`, which the key rule knows | `B27 D214 veto`: `pin` (the rule does not know it) |
| D215 | S58's failure-capture test? | `registerRun` called directly with a planted `failure/` directory | `B27 D215 veto`: a desk run (not deterministic) |
| D216 | Does `PrimitiveDescriptor` always write `script`? | No, only `"script": true` when set | `B27 D216 veto`: always write the key |
| D217 | `ToolProjection.readOnly` and the script flag? | The flag or a name of `SCRIPT_PRIMITIVES` (an older agent's descriptors) | `B27 D217 veto`: the flag alone |
| D218 | What is "the policy excludes script primitives"? | The derived `Policy.excludesScripts()`, applied by the projection and by `CallPolicy.scriptRefusal` | `B27 D218 veto`: a boolean record component |
| D219 | The raw channel bound of page-supplied text? | 6 x `MAX_JSON_CHARS` + 64 KiB, screenshots exempt, the parsed value held to 1 MiB | `B27 D219 veto`: `MAX_JSON_CHARS` + 64 KiB (it refuses genuine values) |
| D220 | How does S38 reach the credentials location? | `Credentials.find`, `Location`, `loadFound`; `locate` unchecked | `B27 D220 veto`: a checked exception from `locate` |
| D221 | A browser selection subject over 1 Mi characters? | `INVALID_PARAMS {reason: "text too long"}` on both paths | `B27 D221 veto`: skip that browser |
| D222 | How is the Byteman uninstall bounded by the detach budget? | A lock taken with a bounded `tryLock`, a `detaching` state, a worker that only removes, the rest on the uninstall's own thread | `B27 D222 veto`: the reviewers' abandoned flag with an outer catch, or a self-healing re-park |
| D223 | Does the cut-short uninstall test need a new seam? | No: a held lock and a slow foreign transformer; D206's property stays the only seam | `B27 D223 veto`: a second seam |
| D224 | Where does the Byteman encoder's container allow-list live? | In the Byteman module, identical to the hooks module's list (D237), with a bit-length guard on numbers | `B27 D224 veto`: one shared predicate in `agent-api` now |
| D225 | What does `RuleHelper.format` print for an oversize number? | Its class name | `B27 D225 veto`: the digits, cut |
| D226 | Is the rule lint a sandbox? | No: a documented tripwire, the deny list unchanged | `B27 D226 veto`: extend the list, or refuse every qualified `java.` reference |
| D227 | How does `JarCheck` judge a link and an ACL owner? | The real path; the owner is the current user, SYSTEM, Administrators or TrustedInstaller, resolved as principals | `B27 D227 veto`: `SecureFiles`' owner rule |
| D228 | How do the Byteman POMs fail an empty execution? | `failIfNoTests` from a property, switched off under `-Dtest` by the profile `merged-test-run` | `B27 D228 veto`: a literal true, or the old default |
| D229 | What does `FreshJvm` do when the JVM is not fresh? | Skips under `-Dtest`, fails otherwise | `B27 D229 veto`: skip always |
| D230 | Does `minimal` stay an exact list, with the correlation id kept in clear? | An allow-list with hash by default; the correlation id and the pack version hashed whole (D166's clause superseded; the veto `B27 D166 veto id` taken) | `B27 D230 veto`: only the named attributes hashed; `B27 D230 veto cut`: the version cut at `+` |
| D231 | When is `hook.health` read for an armed stub? | Whenever the matrix shows `hook.engine` `OK`, before any skip; an armed stub or a failed read ends a replay `ERROR` / `ENVIRONMENT` | `B27 D231 veto`: read in any engine state, or make a failed read in a live skip an error |
| D232 | How does the `hooks` fact root read a long event ring? | Up to ten pages of 1000, then `truncated` and one tail read for `last` | `B27 D232 veto`: page by the ring's size, or a `newest` parameter on `hook.events` |
| D233 | How do the `sign.on.checked` tests capture the password index? | A variant catalogue; the committed `lending.yaml` unchanged | `B27 D233 veto`: change `lending.yaml` to capture `[0, 1]` |
| D234 | The typed-keys display flake: the test or the application? | The test waits for the entry field's focus before its Enter | `B27 D234 veto`: bind Enter in `EntryField` to submit itself |
| D235 | Is the lost-Enter mechanism pinned? | A deterministic display test and a note in P1's sample UI bullet | `B27 D235 veto`: no test |
| D236 | A hook refused after some classes took its advice? | Taken out class by class before the answer; a residue keeps its entry and its id is refused until a remove restores the class | `B27 D236 veto`: treat the refusal as a `hook.remove`, or put the loader into the site key |
| D237 | Which containers does a capture iterate? | An exact JDK allow-list; views, locking collections, blocking queues and the sorted sets are handles | `B27 D237 veto`: a name deny-list with `BlockingQueue` |
| D238 | Is `getMessage()` of an application exception captured? | Yes, redacted and cut | `B27 D238 veto`: JDK exceptions only |
| D239 | `<init>` and `<clinit>` in a spec? | `<init>` is a per-hook `UNAVAILABLE`, `<clinit>` a violation | `B27 D239 veto`: `<init>` a violation too |
| D240 | How does the hooks module fail an empty execution? | `failIfNoTests` from a property with the profile `single-test`; `assumeFreshJvm` skips only under `-Dtest` | `B27 D240 veto`: a literal true |
| D241 | The corner of D172 where the first bytes already name `Spy`? | Accepted and recorded; the `Code`-attribute scan stays open (gap 66) | `B27 D241 veto`: build the scan now |
| D242 | Where is a failure's text redacted? | Where it leaves the engine, and cut at 500 characters | `B27 D242 veto`: a `Redactor` seam in `Transformers` |
| D243 | How does `ChildJvm.pid` read the child's line? | The leading digits | `B27 D243 veto`: a separate class-loading log |
| D244 | May a capture's bytes reach a client on a text route? | Never; under `images: true` only as an image; elided at the write and when served | `B27 D244 veto`: elide only when served |
| D245 | What is an image to the elision? | A map's `base64`, a base64 run with the PNG signature, a JSON `"base64"` member | `B27 D245 veto`: name the shapes you want |
| D246 | Where is the elision applied? | At the write and at the two mask boundaries | `B27 D246 veto`: at the producer only |
| D247 | What happens to a provider's ids and model that do not look genuine? | Scrubbed of secrets and bounded; no `requestedModel` substitution | `B27 D247 veto`: substitute |
| D248 | Does `author` take `--hooks`? | Yes, always written into the planner's server configuration (`auto` by default) | `B27 D248 veto`: no switch |
| D249 | Which hooks switch does a client's scenario run take? | The server's | `B27 D249 veto`: always `OFF` |
| D250 | How does the planner get its prompt? | On stdin, from a file in the private work directory | `B27 D250 veto`: an argument escaped for `cmd.exe`, or a pipe |
| D251 | Is `allowAmbiguousCommands=false` pinned on the launchers? | No | `B27 D251 veto`: pin it |
| D252 | May a `.cmd` or `.bat` file be the planner? | Never: passed over for another candidate, refused with the remedy when alone | `B27 D252 veto`: refuse on Windows only |
| D253 | Which session ids does `--resume` take? | `[A-Za-z0-9_-]{1,128}` | `B27 D253 veto`: hex only |
| D254 | How is the schema argument passed on Windows? | Pre-quoted by the C runtime's rules, the command line bounded at 32767 characters | `B27 D254 veto`: a schema file |
| D255 | Where does the per-volume rule live? | `Volumes` in `skillroy-protocol` | `B27 D255 veto`: copies |
| D256 | Which volume types keep no permissions? | `msdos`, `vfat`, `fat`, `fat12`, `fat16`, `fat32`, `exfat`, `umsdos` | `B27 D256 veto`: add `fuseblk`, or the names you give |
| D257 | What is read back after an owner-only creation? | The exact mode, on both sides; the object is deleted and refused otherwise | `B27 D257 veto`: the provider's views |
| D258 | A credentials file on a FAT or exFAT volume? | Refused | `B27 D258 veto`: accepted with a warning |
| D259 | Which owner comparison does the agent use? | The provider's view, as the controller's `Discovery` does | `B27 D259 veto`: per volume |
| D260 | How is a leftover transaction on SQL Server ended? | Every statement runs in a transaction the read path rolls back; two more guard words | `B27 D260 veto`: auto-commit off in `JdbcDataChannel` only, or more guard words |
| D261 | Do the real-volume tests run in the normal build? | On macOS, with `hdiutil` | `B27 D261 veto`: opt-in by a property |
| D262 | How does the control channel verify a body? | A signed `X-Skillroy-Body-Sha256` header, the MAC checked on the head; the protocol stays `0.2` | `B27 D262 veto`: an optional header, or version `0.3` |
| D263 | What does the agent require of its options file? | The current user's, no group or other write bit, owner-only when it holds a token; the controller writes it through one channel and refuses an ancestor others may rename | `B27 D263 veto`: refuse every group or other bit, or judge the ancestors' owners |
| D264 | One rule for the jars an operator names? | `JarTrust` for a spy jar, the `modules` paths and `byteman.jar`; the home's jars trusted | `B27 D264 veto`: the home's jars too |
| D265 | How short may a supplied token be? | At least 16 characters | `B27 D265 veto`: 32 hex characters, or a warning |
| D266 | What bounds a page's console output? | A token bucket, `Runtime.disable` or unsubscribing, resumed at a later main-frame navigation | `B27 D266 veto`: never resume, or enable `Runtime` only while a run waits |
| D267 | What bounds the hand-off queue? | 2 x `MAX_CHANNEL_CHARS` of queued characters per browser, dropping past it | `B27 D267 veto`: a count bound |
| D268 | How is a library answer bounded at its source? | A guard returning a primitive string; `DRAIN_CHARS` 500000 | `B27 D268 veto`: the marker object, or the guard on `drain` only |
| D269 | The CDP attach after the probe? | Dials the probed browser WebSocket re-pointed to the endpoint; the handshake-redirect residual is accepted by the owner (B28, 2026-10-10) | `B27 D269 veto`: refuse a foreign URL, or build the deferred attach or a relay |
| D270 | When does a tap end? | On an oversized drain only | `B27 D270 veto`: on any refusal |
| D271 | A removed hook whose class's retransformation failed? | A residue, its id refused | `B27 D271 veto`: only when the batch threw |
| D272 | How are the engine's own transform events counted? | Per thread | `B27 D272 veto`: decrement per failed batch |
| D273 | Does `replace: true` report a failed implicit remove? | `replaced: {restored, verified}`; `FAILED` is `ERROR` / `ENVIRONMENT` | `B27 D273 veto`: refuse the install |
| D274 | What does a detach that cannot take the lock do? | Logs and counts like the locked path; the install declares nothing after | `B27 D274 veto`: the WARN and the count only |
| D275 | Which captured strings go through `redactText`? | Every string scalar and map-key text, after the cut | `B27 D275 veto`: a larger window before the cut |
| D276 | How are Byteman rules on the JDK's classes refused? | By name, when the JDK's or the agent's loaders find the class file | `B27 D276 veto`: refuse an empty match, or a prefix list |
| D277 | A half-loaded Byteman engine? | Arm first, undo everything on failure | `B27 D277 veto`: a refused state |
| D278 | What bounds the Byteman recorders? | `hooks.maxClasses` records, no copy above `hooks.maxClassBytes`, unwatch at unload | `B27 D278 veto`: no bound |
| D279 | The bootstrap's options file on a FAT or exFAT volume? | Refused, by a private copy of D256's list | `B27 D279 veto`: accepted |
| D280 | Whose rule trusts `telemetry.yaml` and `inference.yaml`? | The file's: the current user's (or root's), no other-write bit, a user-private group allowed | `B27 D280 veto`: every group bit refused, or a directory rule |
| D281 | May a telemetry header travel over `http://`? | No: `https://` only, the default endpoint included (decided together with B28's S84) | `B27 D281 veto`: allowed to loopback |
| D282 | Which output directories does a run accept? | The current user's (or root's), not writable by others without the sticky bit | `B27 D282 veto`: `untrustedDirectoryReason`, or exclusive creation alone |
| D283 | A refused output directory? | Ends the run `ERROR` / `ENVIRONMENT` before the agent is contacted | `B27 D283 veto`: run without a log |
| D284 | Where do `OutputFiles` and `SettingsFiles` live? | `skillroy-controller` and `skillroy-data` | `B27 D284 veto`: one class in `skillroy-scenario` |
| D285 | What does a telemetry message carry of a path? | Nothing: the whole path becomes `<path>` | `B27 D285 veto`: cut to the last segment |
| D286 | Do the CLI's tests share the scenario module's `TlsStub`? | No, their own copy | `B27 D286 veto`: the test-jar |

**What it changed.** Each is built and tested (`TODO.md`, "Phase 6"), and the contract carries an "As built" note at
every section a decision touches (the code review's fix wave and the pass-2 fix rounds mark their passages "fix wave" and
"Phase 6 security fix, pass 2"). D230 supersedes D166's correlation-id clause (the veto `B27 D166 veto id` is in effect taken);
D236 and D271 apply one rule (a refused or half-removed hook is a residue whose id is refused) to the install and the remove
path; D252 refines D198 (npm's `claude.cmd` no longer wins), D264 refines D227, D281 amends D141. **Behaviour changes the owner should know** (the first two as of the build, the next five from the fix rounds): D210
(a false positive of the key rule on pack prose: the sample's `signOn` and `authorise` descriptions now read
`credential: <redacted> staff, key: code}` in the session's tools and the MCP tool list, as they already did in the
planner's prompt, and the five scripted session recordings were regenerated) and S52 (P5 §7.2, A52: a configured model id that is not in the
price table is now refused at the gateway's open with a message naming the `prices:` key, a configuration that worked before;
the alternative is a warning). Also from the fix rounds: (a) a request with a body must carry the signed header `X-Skillroy-Body-Sha256` (D262), so a
controller older than the fix cannot call a newer agent (nothing was released on protocol 0.2); (b) the planner's prompt is
its stdin and a `.cmd` or `.bat` planner is refused (D250, D252); (c) a telemetry header is sent to an `https://` endpoint
only, the default `http://127.0.0.1:4318` included (D281, to be read with B28's S84); (d) `telemetry.yaml` and
`inference.yaml` are read only when they are the current user's and no other user may write them (D280); (e) a run refuses an
output directory another user owns or others may write to, and never writes through a link or into a file already in place
(D282, D283). **State of the build.** Built, reviewed, fixed and security-reviewed twice 2026-10-09: the native reactor is
green after each landing; the image runs of the fixers and the final Linux matrix are recorded in `TODO.md`. **Blocks.**
Nothing; confirmed, so the built behaviour stands as it is.

## B28 The repeat security review's sign-off (2026-10-09; signed off by the owner 2026-10-10)

**From:** the repeat of the security review that B13 asks for before the first public release, run in two passes on
2026-10-09 and specified in `design/phase6-contracts.md` §12 (the sign-off rule is §12.6: the owner writes it, the
orchestrator does not). Its records are in the session notes, outside the repository:
`notes/2026-10-09/phase6/security/pass1/` and `pass2/` (each with `record.json` in the 2026-10-02 shape and
`dispositions.md`, with the lens reports and the sceptics' verdicts beside them); `TODO.md` summarises them under
"Security review (repeat, pass 1, 2026-10-09)" and "(repeat, pass 2, 2026-10-09)".

- **Pass 1** (lenses L3-L6 on `e86e8e2`, one reviewer and one sceptic each): S35-S62, 28 confirmed, **1 high** (S55,
  `data_query` substituted `$references` into the SQL after the guard ran), **6 medium**, 21 low; 18 counted, 10
  operator-only.
- **Pass 2** (all eight lenses on the Phase 6 tree: L1 and L3-L7 on `f546363`, L2 and L8 on `ce1f41f`): S63-S100, 38
  confirmed, **1 high** (S93, the planner passed model-controlled text as an argument of `claude.cmd`, which Windows
  starts through an implicit `cmd.exe`), **12 medium**, 25 low; 18 counted, 16 operator-only, 4 token-holder only; one
  reviewer finding refuted (P2-L1-F2, no number). Seven findings were raised by the sceptics from claims they broke.
- **Together** S35-S100: 66 confirmed findings, 2 high, 18 medium, 46 low.
- **Dispositions:** every finding of medium or above is **fixed** (contract first, a test that fails on the old code,
  marked in the contract with the review id; pass 1 by the fifth work package, pass 2 by six fix rounds SA-SF with
  decisions D244-D286). Of the lows, all are fixed in code or documentation except the eight below, which the
  owner **accepted on 2026-10-10** (a documentation-only change counts as an acceptance, §12.5): pass 1's five
  (B26 Q22) and pass 2's three. Nothing of medium or above is deferred; each of pass 1's five carries the phase it
  goes to where there is one (7, or the phase that builds the seam: the data channel for S36 and S37, the CDP path for
  S48, the planner for S60, none for S40; B26 Q22, P6 §12.2 and §17 Q22).
- **Not verified** (also in `README.md`, `SECURITY.md` and `design/threat-model.md` §8): everything on Windows (the ACL
  branches of every owner-only file, the `.bat` launcher, the planner on a Windows host, `JAVAAGENT` as the logged-on
  user, the input rungs), where no host exists; the `jxbrowser` adapter against the real product; the paths that need a
  live model key (the planner's stdin route was verified offline only); a hook detach that ran out of its budget and a
  Byteman rule left by it on a real application; SQL Server's behaviour (the guard's T-SQL consequences rest on vendor
  documentation); a second OS user (cross-user windows are argued and tested with one); Linux `vfat` mounted;
  the amd64 image legs of the hooks module; and the other items of `threat-model.md` §8.
- **Outside reader:** none has read the record yet (B26 Q12: recommended, recorded, not a gate).
- **Matrix:** the Linux matrix after the fixes is recorded in `TODO.md`.

**Question.** Does the owner sign the review off, with the eight lows below accepted? The orchestrator recommended yes,
once the final matrix was green and the owner had read the eight release notes. **Signed off by the owner on 2026-10-10**
(`B28 ok`, no veto): the eight lows are accepted as proposed, each with its release note; no outside reader was made a
gate (B26 Q12 as answered).

| Item | Low finding accepted by the owner on 2026-10-10, with its release note (also in `SECURITY.md` "Known limitations") | Veto form offered |
|---|---|---|
| S36 (b) | `StatementGuard` is a safety net, not the boundary: give the data account a read-only role; PostgreSQL sources should set `readOnlyMode: always` (a deny-list of side-effect function names not built; pass 1) | `B28 S36 fix` |
| S37 (i) | Run outputs take the process umask; on a shared host run the controller under `umask 077` and give `--out` a directory you own (documentation only; pass 1, its advice completed by pass 2's S97 and S99, which refuse an output directory another user owns) | `B28 S37 fix` |
| S40 (pinning) | Pin the image and the actions in your copy of `ci/examples/nightly-re-resolve.yml` (pass 1) | `B28 S40 fix` |
| S48 | The Playwright driver inherits the controller's environment; `DEBUG=pw:*`, `NODE_OPTIONS`, `PLAYWRIGHT_NODEJS_PATH` and `PLAYWRIGHT_DRIVER_DIR` change what it runs or logs (documentation only; pass 1) | `B28 S48 fix` |
| S60 (`CLAUDE_CONFIG_DIR`) | The planner's `claude` session transcript stays in the CLI's own data directory; on a shared host run `author` with a key and your own `CLAUDE_CONFIG_DIR` (pass 1) | `B28 S60 fix` |
| S77 (residual) | When the CDP path attaches while the agent reports no CDP target id, a process that holds the configured debug port before the application's engine binds it can answer the attach's WebSocket handshake with a redirect, which Playwright's driver follows to another host or to a Unix socket the operator can open; it gains an outbound connection with Playwright's headers and commands, never the run's scripts. Bind the port in the application first, or use `web.path: agent` (the finding itself, P2-L4-F1, is fixed; the closing designs are a deferred attach or a controller-side WebSocket relay) | `B28 S77 fix` |
| S84 (P2-L5-F4) | A loopback `http` model base URL sends the key in clear to whatever holds that port: use it only for a proxy you run, never on a shared host; prefer `https` on loopback | `B28 S84 fix` (plain `http` refused, the tests' fakes then need TLS) |
| S94 (P2-L7-F4) | On a Windows whose built-in account names are not English the owner-only checks fail closed and the framework refuses to run; remedy with `icacls` (nothing is exposed; a redesign of the ACL branches needs the host) | `B28 S94 fix` (waits for the Windows host) |

Read S84 and the fixed S96 together: the model key may travel over loopback `http` (S84, accepted), but a telemetry
header may not (S96, fixed by SF as D281), so the two rules disagree by choice; the owner accepted that as it stands. A `B28 S84 fix`
would have made both strict, and naming S96 would have relaxed the header rule instead (one method,
`TelemetrySettings.checkHeaders`, and its two callers); neither was asked for.

**The veto forms that were offered** (the owner signed off with `B28 ok` on 2026-10-10 instead): `B28 S<n> fix` would
have refused one proposed acceptance by name, the fix then built and the sign-off waiting for it; `B28 outside reader
first` would have made an outside human reader of the record a gate of the sign-off (in effect a veto of B26 Q12, which was not given). **What it
changed.** With `B28 ok` (given 2026-10-10), the "Reviews" paragraph of `SECURITY.md` carries the signature,
`docs/release-checklist.md` item 2 records it, and in `TODO.md` the Phase 6 item "Repeat the security review" is ticked and
the Exit item's criterion "the repeated security review is signed off" is marked met (the Exit item stays unticked while
the Windows criterion waits). The eight release notes go into the release note (checklist item 11). **State
of the build.** Everything is built and merged into `main` (head `4f9962f` for the last fix; nothing pushed). **Blocks.**
Nothing since 2026-10-10; the push still waits for the owner's word (B1).

## B29 Phase 7 contract answers (2026-10-10; confirmed by the owner 2026-10-11)

**From:** the questions of `design/phase7-contracts.md` §18, answered by the orchestrator on 2026-10-10 without the
owner so that the build can start; they produced the contract's decisions D287-D358 (§17; D341-D358 and the questions
Q35-Q39 were added by the contract review's revision the same day, which also changed the answers of Q2, Q6, Q11, Q14,
Q16, Q20, Q22, Q32 and Q34; the recheck's last round changed Q2, Q36 and Q39 once more). The contract was reviewed through
three lenses with a sceptic each (94 findings, 91 applied, 3 refuted) and rechecked twice (36 more findings applied). The
four spikes of design §8 reported the same day, before the contract was written: BS9 passed with conditions
(`spikes/BS9/RESULT.md`: Robot pointer input lands as a person's on Linux, Java 8 and 25, WM-less and with openbox; keys
land in the focus owner only while the target JVM has a focused window, which settles swing-driving F13 and explains BS4;
macOS without the Accessibility permission drops everything silently; a frame's `PAINT` carries imitation Motif
decorations on the X toolkit, a root-pane `PAINT` equals a `ROBOT` capture), BS10 passed with conditions
(`spikes/BS10/RESULT.md`: routed window-sourced dispatch matched Robot in 14 of 16 cases; the two differences, a
modal-blocked window and a covering window, are by construction), BS11 passed with conditions (`spikes/BS11/RESULT.md`:
`PAINT` is pixel-deterministic within one environment but for the blinking caret; across environments no tolerance
separates noise from a one-character change), BS12 passed live (`spikes/BS12/RESULT.md`: V1 with marks and V2 through the
computer toolset answered 100 % on Sonnet 5.5 and Opus 5.5, sessions USD 0.02-0.06, USD 5.64 spent). B30 holds the build's decisions (D359-D430) and those of the code review's fix wave, the race fix and the security fixes (D431-D486), B31 is the security delta review's sign-off. **Confirmed by the owner on 2026-10-11**, every row (`B29 ok`, no veto); the answers were provisional while the build ran on them.
**Question.** Thirty-nine points the contract could have decided either way. Q27 and Q34 rest on knowledge only the owner
has (§18 marks them: whether a JxBrowser licence holder's run is near, and how V3 sessions on a person's desktop, which
§18 calls the overlay sessions, will be run); Q25, the choice of the sample's component, is a matter of taste the owner
may hold; the owner confirmed all three with the rest. Q35-Q39 were added by the review: four of them (Q35-Q38) are safety rules, each with a veto form.

| Q | Question | Answer | Veto form offered |
|---|---|---|---|
| Q1 | Where the Robot code lives: inside `skillroy-agent-swing` (JDK API, lazily on a worker, behind the option), or an optional agent module behind a new SPI? | Inside the Swing module (D290) | `B29 Q1 veto`: `optional/skillroy-agent-robot.jar` behind a `RobotInput` SPI in `agent-api`, named by `optionalModules`; a sixth golden state per leg |
| Q2 | The double opt-in for V3: an agent option (`robot=true`, the host's consent) and a person's controller flag (`--allow-robot`, per run, session or raw `call`), both required, and never a per-call choice of a model or a client even with both? | Yes (D290, D332, D347) | `B29 Q2 veto option`: the agent option alone; `B29 Q2 veto flag`: the controller flag alone (the agent then exposes Robot to any token holder); `B29 Q2 veto percall`: a model or a client may pass `via: ROBOT` when both flags are set |
| Q3 | `redact` defaults to true, so failure evidence, artifacts and MCP images are redacted too (a password field, a pack-named component)? | Yes (D288, D295) | `B29 Q3 veto`: default false; the vision module always passes true; evidence images unmasked as today |
| Q4 | The capture mask's shape: a separate `CaptureMask` SPI, keeping `MaskPolicy` AWT-free, and one rule for pixels and text? | Yes (D296, D349) | `B29 Q4 veto`: a default method `MaskPolicy.masksPixels(Component)`; `B29 Q4 veto text`: pixels only, the text walker unchanged |
| Q5 | Root-pane painting as an amendment to the built `PAINT`: the pixels of every frame capture on Linux change (the imitation decorations go; the image is the content's size)? | Yes (D288) | `B29 Q5 veto`: the frame stays as built and `scope: CONTENT` is added for the root pane; the vision module uses `CONTENT` |
| Q6 | Robot keys not built: V3 types through the routed primitives after a focus check and stops the turn when the target JVM holds no focused window; BS9's key conditions are the reason. Right for Phase 7? | Yes (D345, D344) | `B29 Q6 veto`: `swing.type {mode: ROBOT}` and `swing.key {via: ROBOT}` under `swing.pointer.robot`, with the focus check, the re-activation after a dialog and X-keysym translation in the agent |
| Q7 | `swing.click {rung: ROBOT}` stays the sentinel? | Yes (D291) | `B29 Q7 veto`: the rung maps to `swing.pointer {target, via: ROBOT}` |
| Q8 | `region`'s two homes: on the wire a rectangle or an id, in the DSL always an id with `clip` for a rectangle? | Yes (D297) | `B29 Q8 veto`: the wire parameter is renamed `clip` for the rectangle and `region` takes an id only |
| Q9 | Lower-case capability ids (`swing.hittest`) kept as reserved? | Yes (D298) | `B29 Q9 veto`: camel case, with `web.hittest` renamed in five goldens and the web host's acceptance |
| Q10 | `LocatorResolver.describe` as the reverse direction for domain locators? | Yes (A64) | `B29 Q10 veto`: no reverse method; the hit-test proposes built-in strategies only |
| Q11 | The covering-window rule: on the routed path refuse for a ranked window (modal, owned, active) and report otherwise; on the Robot path refuse for every covering window; `force` for a person only? | Yes (D294, D344) | `B29 Q11 veto refuse`: refuse for every covering window on both paths; `B29 Q11 veto report`: never refuse, always report, on both |
| Q12 | `Status.SKIPPED` as the fifth status for a `run` plan in `replay`? | Yes (D328) | `B29 Q12 veto degraded`: `DEGRADED` with a skipped line; `B29 Q12 veto error`: `ERROR / PRECONDITION_FAILED` |
| Q13 | Who runs `aiAct` and `aiExpect`: only the vision module's player over a session, never `PlanExecutor` (which skips or refuses a `run` plan)? | Yes (D311) | `B29 Q13 veto`: a `ServiceLoader` step seam in `PlanExecutor` for model steps in `live` |
| Q14 | `inference.vision` is `OK` only in a vision session, and a visual check under `PlanExecutor` is skipped unconditionally? | Yes (D312, D327, D354) | `B29 Q14 veto`: `OK` under `PlanExecutor` in `live` with an evaluator seam (Q13's veto implied) |
| Q15 | Controller-side capabilities merged into the matrix through `CapabilityContributor`, `UNAVAILABLE("replay")` in replay, `agentHash` kept for the goldens, the host feature through `SessionOptions.host`? | Yes (D326, D348) | `B29 Q15 veto`: keep the agent-only matrix; `inference.*` guards stay on the "missing" path and the design's §3.10 sentence is withdrawn |
| Q16 | Baselines keyed by a rendering key in the bindings file (version 2), the fingerprint unchanged, the key hashing the Java line, the look and feel, antialiasing, the screen scale and a probe render (not the OS, the full version, the architecture or font names)? | Yes (D329, D330, D350) | `B29 Q16 veto`: the fingerprint carries `skillroy.render.*` for a plan with images (one bindings file per target Java for such scenarios); the `images` section stays; `B29 Q16 veto vendor`: the vendor and the full version join the key (the amd64 leg then needs its own baselines) |
| Q17 | Exact comparison with `maxDiffPixels` 0 by default? | Yes (D314) | `B29 Q17 veto <n>`: a default margin of n pixels |
| Q18 | The image comparison in `skillroy-scenario`, not in `skillroy-vision`? | Yes (D313) | `B29 Q18 veto`: in `skillroy-vision`, which CI then needs for anchored steps and `expectImage` |
| Q19 | The `SessionTools` seam (one loop, the service's facade) rather than a second loop in the vision module? | Yes (D302, D353) | `B29 Q19 veto`: a second loop with its own actor in the CLI and the MCP server |
| Q20 | Provenance as an optional object on the journal step, handed in by the acting call, cost staying in the ledger by request id? | Yes (D331, D351) | `B29 Q20 veto`: tokens and cost copied into the step record (P5 §12.3 amended) |
| Q21 | The gateway's additive changes: `providerTools` with `PROVIDER_TOOLS`, `toolsetName` on both parts, image parts in a tool result (text-only when `error`), `CacheHint.AUTO`, `IMAGES` on the recorded and the scripted gateway, the Haiku 5.5 row with the class default unchanged, `effort.vision` `low`? | Yes (D318-D324) | `B29 Q21 veto <item>`: naming the item (`providerTools`, `toolsetName`, `imageResult`, `auto`, `recordedImages`, `haiku`, `effort`) and the alternative of §17's row |
| Q22 | The defaults and limits: V1 with marks, Sonnet 5.5 at `low`, the class `haiku` for V1 only, the toolset for V2/V3 with `zoom` off, captures unscaled up to 1920 and never below 0.75, USD 1.00 / 30 actions / 20 turns per session enforced by the service? | Yes (D304, D353, §10) | `B29 Q22 veto <item>`: the item and its value |
| Q23 | `LLM_VISION` and `UiTarget` stay reserved? | Yes (D316) | `B29 Q23 veto`: the binding-shaped recording with a step-time verifier is built |
| Q24 | Plan version 6 with one regeneration of every committed plan and bindings file, and the session recordings regenerated on the scripted route (the pack version and D342's vocabulary sentence in their system text)? | Yes (D325, D337) | `B29 Q24 veto`: no regeneration; then no pack bump (Q25's veto) and the vision export alone |
| Q25 | The sample's number pad on the desk (with the mask on the field and the pad, the legend, module 0.2.0, pack 0.6.0, gap 49's argument)? | Yes (D337, D358) | `B29 Q25 veto shelf`: a shelf map instead; `B29 Q25 veto toy`: a region component in tests only (the sample then shows V1/V2 without regions) |
| Q26 | `aiExpect`'s `UNSURE` skips the check (`DEGRADED` at best); `NO` fails? | Yes (D317) | `B29 Q26 veto`: `UNSURE` fails |
| Q27 | JxBrowser input behind descriptor roles, unverified, with the kit against the fake **(owner's knowledge: is a licence holder's run near?)** | Yes (D336) | `B29 Q27 veto`: JCEF only; the `jxbrowser` adapter never reports `INPUT` until verified |
| Q28 | `web.pointer` honours a blocking modal dialog (an AWT-only check in the web module)? | Yes (D335) | `B29 Q28 veto`: no check |
| Q29 | The protocol stays 0.2? | Yes (D299) | `B29 Q29 veto`: `0.3` (`ProtocolConstantsTest` and the discovery file's `protocol` change) |
| Q30 | The security delta review (eight lenses with sceptics over the diff since `af29e59`, S101 onwards, nothing medium or above accepted, the owner signs B31)? | Yes (D338) | `B29 Q30 veto full`: a full review of the tree; `B29 Q30 veto none`: no review before the next release (against the checklist) |
| Q31 | No separate `act` command: `ask` with `--vision` is the interactive form; `--play` runs a scenario? | Yes (D333, D339) | `B29 Q31 veto`: `act <intent>` and `skillroy_session_act` beside `ask` |
| Q32 | The loop's reads unjournaled through `ExecutorSession.read` (which throws the agent's error and never masks an image's `base64`), their hashes in the provenance? | Yes (D308) | `B29 Q32 veto`: every capture and hit-test a raw read step in the journal, left out of the export by `reads` |
| Q33 | The CI image stays WM-less; Robot checks run WM-less with the option (and `requireFocus: false`); no openbox leg? | Yes (D340) | `B29 Q33 veto`: an openbox leg from `spikes/BS9/docker/` in the matrix |
| Q34 | V3 on a person's desktop acts only while the target holds the focus, checked by the agent at the press (`requireFocus`, default true), and the loop stops the turn when the target loses it instead of clicking to win it back; V1 and V2 never move the pointer. Right default? **(owner's knowledge: how the overlay sessions will be run)** | Yes (D344) | `B29 Q34 veto`: V3 keeps acting after a focus loss (the loop passes `requireFocus: false`), reporting `NOT_DELIVERED` per action |
| Q35 | A capture of the glass never reaches a model or a client: V3 observes through a redacted `PAINT` composite ("the model sees `PAINT`, Robot acts"), and `swing.capture {mode: ROBOT}` is a person's evidence with everything outside the JVM's windows blanked? | Yes (D341) | `B29 Q35 veto`: a glass check built in the agent (a `ROBOT` raster compared with a root-pane `PAINT` of each window, the differing regions blanked, `covered: [rects]` reported) and `capture {mode: ROBOT}` offered to the model in V3 |
| Q36 | No credential is ever used on a model's or a client's behalf: a `$slot.secret` reference and a verb given a credential-joined slot refused for every origin but a person's, no `type` while a credential is in flight, a `type` holding a known secret value refused, a client scenario's base verb that names a credential field refused by `PlanPolicy`, sign-on by the person or the player before the loop? | Yes (D342) | `B29 Q36 veto`: the reference refusal for raw calls only, the recorder allowed to pass a secret reference into a masked field (the pixel path then rests on the pack's mask alone); `B29 Q36 veto verb`: a model or a client may run a pack verb with a credential-joined slot (a model's `signOn`), the verb's own join consuming the in-flight flag |
| Q37 | A Robot press is always paired with its release: `PRESS` and `RELEASE` refused through Robot, the toolset's `left_mouse_down` and `left_mouse_up` answered as text in V3, the release in a `finally` before the pointer is restored? | Yes (D343) | `B29 Q37 veto`: the halves allowed through Robot with the release on the next call or at the turn's end (the pointer not restored while a button is down) |
| Q38 | The controller draws the marks on its own copy; the agent returns geometry only, one raster and one hash? | Yes (D346) | `B29 Q38 veto`: the agent draws and returns both rasters (`base64` and `markedBase64`), the 4096-pixel cap bounding each |
| Q39 | Image baselines are approved at their step (`Approver.reviewImage`, the candidate shown first, `--yes` approving) and the bindings file is rewritten after the steps, `images` carried forward on every rebind; anchors never approved but re-recorded by a session? | Yes (D355) | `B29 Q39 veto batch`: one batched image approval at the run's end; `B29 Q39 veto bind`: baselines approved at bind time from the previous run's candidates |

**What it changes.** The Phase 7 contract and, through its §16.1, the Phase 1-6 contracts and the design overview
(written in when the build starts; design §3.10's V3 row (D341) and the two sentences that D314 and D330 overturn among
them, with the matching lines of design §8 and §9); `design/threat-model.md` (extended on 2026-10-10, D334); the
amendments file's index (D301, by the build). **State of the build.** Specified and reviewed on 2026-10-10 (the contract is committed as `2eebeed`); the build proceeded on the answers, which were provisional, in four worktree packages, VS (the agent's Swing side, the sample's desk and pack), VW (the web side), VC (the scenario, controller, JUnit and CLI side) and VM (the gateway, the session seam, `skillroy-vision` and the MCP and session options), and was built and merged into `main` the same day in the order VC, VS (with VM's step 1b and the regeneration step 2b), VW, VM; its own decisions are B30 below, 128 with the fixers' (`TODO.md`, "Phase 7"); the code review, the fixes and the security delta review (B31) ran on 2026-10-10/11 and are in B30 and B31. **Blocks.** Nothing; confirmed, so the built behaviour stands as it is.


## B30 Phase 7 build, review and security-fix decisions (2026-10-10/11; confirmed by the owner 2026-10-11)

**From:** the decisions taken while building Phase 7 on 2026-10-10 without the owner, by four packages in worktrees (VS the
agent's Swing side, the sample's desk and pack; VW the web side; VC the scenario, controller, JUnit and CLI side; VM the gateway,
the session seam, `skillroy-vision` and the MCP and session options), recorded as D359-D430 in `design/phase7-contracts.md` §17
(each with the alternatives rejected and the reason) and numbered by the orchestrator at integration in the order VC D359-D382,
VS D383-D403, VW D404-D419, VM D420-D430 (72 decisions, each package's ids from 1 without a gap; D401-D403 are VS's three
decisions of the regeneration step 2b, and D425 is VM's decision about `ExecutorSession.fixture`, built at the orchestrator's
request in the closing commit `e11ae08`); and the decisions taken afterwards, on 2026-10-10 and 2026-10-11 and again without the
owner, by the fixers of the Phase 7 code review (FA the agent's Swing side, FB the scenario, controller and CLI side, FC the
vision module and the session), by the fixer of a race in the hooks engine that the first Linux matrix of the integrated tree
found (FX) and by the four packages of the security delta review's fix round (SG the agent's Swing side, SH credentials,
slots and the client's plan form, SI the vision module, the session service, the web reads and the CDP path, SJ the image
baselines, the bindings files and the run output), recorded as D431-D486 in the same place and numbered by the orchestrator at
the closing documentation pass in the order of the tags, FA D431-D439, FB D440-D442, FC D443-D448, FX D449, SG D450-D462, SH
D463-D469, SI D470-D480, SJ D481-D486 (56 decisions, each tag's ids from 1 without a gap; SG's thirteenth, the `bounds` of a
browser surface, was added by a follow-up after SI had landed). The order is the tags', not the order the fixers landed on `main` (FA,
FC, FB, FX, SG, SH, SJ, SI), as the fixers' decisions joined B27. Taken without the owner, the work having
proceeded on them as it did on B26 and B27, so that they were provisional while it ran; **confirmed by the owner on 2026-10-11**, every row (`B30 ok`, no veto).
**Question.** One hundred and twenty-eight points the build, the review's fixes and the security fixes could have decided
either way (72 + 56). None changes a decision of B1-B28 or an answer of B29; in a few places the build departs from the
contract's own sentence, and the contract carries an "As built" note there (see "What it changes").

| D | Question | Answer | Veto form offered |
|---|---|---|---|
| D359 | How does a session get the baselines an `expectImage` compares with? | The caller hands them in: a three-argument `expectImage` and a static `baselines(root, env, scenarioId)`; the two-argument form holds none | `B30 D359 veto`: a baselines setter on the session, or the session reading the bindings file itself |
| D360 | What does the journal hold for an `expectImage` or `pointer` step? | The verb's arguments, reconstructed from the compiled call and check | `B30 D360 veto`: journal no arguments |
| D361 | Is the guard of an anchored step compared at the moment the recorder acts? | No: the step acts, the anchor goes to the export, and the comparison starts with the export's replay | `B30 D361 veto`: compare against the just-taken crop, or refuse the step |
| D362 | Does a plan whose bindings hold only images ask the approver at bind time? | No; its baselines are approved at their steps (D355) | `B30 D362 veto`: ask the approver with empty resolutions |
| D363 | Which `scope` does the capture call compiled from `expectImage` carry? | `REGION` when `region` or `clip` is given, otherwise none (the agent's default) | `B30 D363 veto`: decide `WINDOW` or `COMPONENT` at compile |
| D364 | Does a compiled `pointer` call write its default action, and is `strict` sent? | `action` is always written, default `CLICK`; `strict` is checked and never sent | `B30 D364 veto`: omit the default action, or send `strict` |
| D365 | Which results does the `image` record of `run.jsonl` have? | The four designed (`held`, `failed`, `missing`, `skipped`) plus `approved` and `rejected`; a guard's record carries `guard: true` | `B30 D365 veto`: reuse `held` and `missing` for approve's outcome |
| D366 | How does `run --json` say that a result was skipped? | A `skippedReason` per result | `B30 D366 veto`: `skipped: true` (P3 §9's list then needs another name) |
| D367 | What happens when a `CapabilityContributor` throws? | The run is refused `ERROR` / `ENVIRONMENT`, naming the contributor | `B30 D367 veto`: skip the contributor and go on |
| D368 | What form may a render key in a bindings file take? | Letters and digits, 8 to 64 of them; the agent's own is 16 hex digits | `B30 D368 veto`: exactly 16 lower-case hex digits |
| D369 | What counts as typing while a credential is in flight? | `swing.type`, and `swing.key` outside a fixed non-printable set, in the compiled calls of any verb, a pack's included | `B30 D369 veto`: judge base verbs only, or allow a whitelist of printable keys |
| D370 | When do `keypad-image` and `sign-on-by-number-pad` join the committed set? | The plan at once; the baselines and the replay after step 2b; the sign-on scenario ends with `signOff` | `B30 D370 veto`: keep `keypad-image` out of `PlansUpToDateTest` until its baselines exist |
| D371 | What does the committed visual check ask? | Whether the table holds one line for the scanned item with a quantity of 2, after the scans and before `DUE` | `B30 D371 veto`: the contract's wording (two lines, after the deposit), which is false on the desk |
| D372 | Which matrix does `skillroy caps` print? | The merged matrix of a `live` run without host features, when a pack or an inference option is given; the table's `SOURCE` column and `--json` carry the source | `B30 D372 veto`: merge in `replay`, or only when both are given |
| D373 | How are the committed plans and bindings files regenerated for plan version 6? | Two flags of the module's own tests: `skillroy.plans.update` (now also the export's plan) and `skillroy.bindings.rehash` | `B30 D373 veto`: a standalone script |
| D374 | Which new fields does the journal's `session-start.options` always carry? | None: `robot` only when true, `host` and `contributed` only when not empty | `B30 D374 veto`: always write the three fields |
| D375 | What does an `expectImage` whose `{by}` mask matches no single component end as? | `FAIL` / `ASSERTION` | `B30 D375 veto`: `ERROR` / `ENVIRONMENT` |
| D376 | How does an image check report an unusable capture or baseline file? | As an outcome of kind `ERROR`, mapped once in `ImageRuns` for both hosts | `B30 D376 veto`: throw an exception |
| D377 | Where does a session report an image check it skipped? | In the outcome's `degradedFidelity` | `B30 D377 veto`: a new field of the outcome |
| D378 | May VC edit the web host's plan-version pin? | Yes, one line | `B30 D378 veto`: leave the pin red under `-Pjcef` for the web package |
| D379 | Is an approved baseline written when a later step of the run fails? | Yes: it was approved at its step | `B30 D379 veto`: write the file only after a passing run |
| D380 | How does a caller get the agent's own part of a merged matrix? | `CapabilityMatrix.agentOnly()` | `B30 D380 veto`: no accessor |
| D381 | Where do the bindings file's image records live in the code? | Nested in `BindingsFile`; `window` optional, a baseline's taken from the capture | `B30 D381 veto`: top-level types |
| D382 | How does the export hand out its anchors and report what was vision-derived? | As components of the result: `ExportResult.images`, and `visionDerived` and `liveOnly` in the report | `B30 D382 veto`: side channels beside the result |
| D383 | Is a component inside a masked component masked? | Yes: no text, value or mark, and `masked` in the hit-test | `B30 D383 veto`: only the named component |
| D384 | Does the heap bound refuse a capture or lower its scale? | It refuses; only the 4096-pixel cap lowers the scale | `B30 D384 veto`: lower the scale under the heap bound too |
| D385 | Which painted components does root-pane painting replace? | A `RootPaneContainer` that is a window; an internal frame keeps its title bar | `B30 D385 veto`: every `RootPaneContainer` |
| D386 | How is a routed double click delivered? | As two press, release and click triples with counts 1 and 2 | `B30 D386 veto`: one triple of count 2 |
| D387 | What do `dispatched.sent` and `balanced` say when the EDT is blocked? | `sent` counts dispatches that returned; `balanced: true` is kept by the next task the EDT runs | `B30 D387 veto`: count at dispatch start, and report the balance only when done |
| D388 | What does the hit-test answer when asked about a named component? | That component as `target`, `targetKind: "TARGET"` | `B30 D388 veto`: the mouse target or the deepest component at its centre |
| D389 | Which carets does a capture report? | Every focused text component's without a selection, whatever the blink phase, widened by 2 px | `B30 D389 veto`: only a caret that is drawn at the moment |
| D390 | Which marks does `annotateRefs` produce? | The controls a person would use, as listed; no marks inside combo boxes and scroll bars; labels `role 'name': text` | `B30 D390 veto`: a mark for every button, arrow buttons included |
| D391 | What colour is a `SCREEN` capture's canvas outside the windows? | RGB 64, 64, 64 | `B30 D391 veto`: the mask grey, or white |
| D392 | How long does a Robot press wait to be observed? | At most 3 s, within the call's remaining time | `B30 D392 veto`: the whole remaining time |
| D393 | How does the delivery probe move when the pointer is already at the point? | One pixel aside first; `MOUSE_ENTERED` counts as delivery; the pointer is restored on failure | `B30 D393 veto`: probe without moving aside |
| D394 | What does an absent `inductionRequired` mean on `LendableItem` (gap 49)? | Either kind of item; the induction SQL is used only when the argument is named | `B30 D394 veto`: null means false, or one SQL with a nullable parameter |
| D395 | What is the geometry holder behind `captureId` called? | `RecentCaptures` | `B30 D395 veto`: another name |
| D396 | How does a person's glass capture treat `scale` and size? | It ignores `scale`, refuses a rectangle over 4096 a side, says `glassNote` | `B30 D396 veto`: honour `scale` on the glass capture too |
| D397 | How is the number pad laid out beside the keypad row? | The keypad row keeps 64 px, the pad about 160 px | `B30 D397 veto`: let the keys stretch |
| D398 | How do the vision Swing checks get their targets? | They extend `LoadModeChecks` (orders 52, 54, 56, 58); the Robot checks launch a target with the agent option on | `B30 D398 veto`: a class per mode that launches its own target |
| D399 | What does `swing.capture` do for a component that is in no window? | It captures, with `origin` relative to the topmost ancestor and no `window` | `B30 D399 veto`: refuse the capture |
| D400 | How does a routed pointer call recognise its own events as received? | By a unique `when`; the window counts only when it listens | `B30 D400 veto`: by the identity of the event objects |
| D401 | Does the bindings rehash also bring the files' `packVersion` to the pack's? | Yes; an `approve` then writes the same bytes | `B30 D401 veto`: leave `packVersion` to a later `approve` of every file |
| D402 | What does the resolution classes' pass helper require of an images-only scenario? | An `image` record counts as a check; no wait is required | `B30 D402 veto`: require an `expect` record and a wait of every scenario |
| D403 | What does `keypad-frame` mask? | The entry field, the totals line and the printer's scroll pane, by locator | `B30 D403 veto`: the single mask of §12.4, a `maxDiffPixels` budget, or a recovery that clears the slips first |
| D404 | How does the CDP path honour a blocking modal dialog for `web.pointer`? | It re-reads `web.browsers` before the call and hands a blocked browser to the agent, which answers `MODAL_OPEN`; `web.browsers` gains the optional `blockedBy` | `B30 D404 veto`: no modal check on the CDP path, or `web.pointer` never served on it |
| D405 | How does library version 2 install into a page that holds version 1? | The slot is kept; the new functions are installed once; install answers `upgraded` | `B30 D405 veto`: replace the slot, or add a second version marker |
| D406 | Which events does `web.pointer` send for an action? | A `mouseMoved` first; for a click of count n, n press/release pairs with counts 1 to n | `B30 D406 veto`: no move before `PRESS` and `RELEASE`, or one pair with count n |
| D407 | What does `web.pointer` do when a call fails after a press? | It sends one balancing release (at most 1 s) and then reports the failure | `B30 D407 veto`: leave the button held |
| D408 | In which space are a hit-test's rectangles? | The viewport of the frame the call started in | `B30 D408 veto`: each frame's own viewport |
| D409 | Where does the code shared by the in-process and the CDP path of `web.pointer` and `web.hitTest` live? | In `ai.skillroy.testjava.protocol.web.WebInput` | `B30 D409 veto`: a copy in each path |
| D410 | How does `web.hitTest` find the frame behind a frame element? | By the element's name, else URL, else the only child, from the frame tree on both paths | `B30 D410 veto`: descend in the page, or stop at every iframe |
| D411 | Which locators does `web.hitTest` propose? | Only ones that `find` resolves to exactly the element, in the forms listed | `B30 D411 veto`: report non-unique candidates too |
| D412 | What does `web.pointer` do when its hit read fails? | `NOT_FOUND` refuses; any other failure omits `hit` and the pointer proceeds | `B30 D412 veto`: fail the pointer on any hit failure |
| D413 | How are the JxBrowser adapter's input roles named, and are modifiers mapped? | From the public 7.x API as best known; modifiers refused; the "input unverified" note only when the roles resolve | `B30 D413 veto`: map modifiers by a guessed type, or always print the note |
| D414 | Which dialog blocks a web view when several are open? | The Swing module's rule; the active blocker, else the last created | `B30 D414 veto`: the literal phrase of the contract, or the first created |
| D415 | When does the web module offer its browsers to the Swing capture? | From install, answered from the index; no AWT class is loaded at install | `B30 D415 veto`: install it at readiness, or ask each adapter for its component |
| D416 | When does `web.browsers` read `blockedBy`? | For each open browser with a component, in one bounded EDT read, while the call has time | `B30 D416 veto`: always, or never |
| D417 | What does a `COMPONENT` point of `web.hitTest` need? | The `EVALUATE` capability and a component with a size, and no `web.resize` override | `B30 D417 veto`: read the adapter's `viewport()` |
| D418 | Does the web host get capability goldens? | No | `B30 D418 veto`: add goldens for it |
| D419 | What bounds does the `WebPointerEvent` SPI value enforce? | The listed ranges, failing at construction | `B30 D419 veto`: unbounded integers |
| D420 | What does the vision module's facade over the session offer beyond the contract's list? | The output directory, the pack's legend (masked) and a journal note | `B30 D420 veto`: let the module reach the session, or drop the look's note and the captures |
| D421 | How does a tool set name the model class of the loop? | A default method `modelClass(ctx)` answering Sonnet | `B30 D421 veto`: a service option |
| D422 | What does V1's `<target-ui>` block hold? | The marks list and the image's size | `B30 D422 veto`: the marks list and the text snapshot |
| D423 | What does the recorder do with a hit on a heavyweight or an unclaimed surface? | An anchored step for `HEAVYWEIGHT`; a text answer for `NONE` | `B30 D423 veto`: refuse both |
| D424 | May a scenario step lower the session's vision tier? | Only between V2 and V3; to or from V1 is a usage error | `B30 D424 veto`: allow any narrowing |
| D425 | How does the scenario player bind a scenario's fixture slots? | Through `ExecutorSession.fixture`, as the person; a credential slot only for a person's origin | `B30 D425 veto`: a binder the player's host must supply, or fixture slots rewritten as queries |
| D426 | What does `vision.maxActions` count? | Each facade `execute`; an act that ran two steps counts two | `B30 D426 veto`: one per tool call |
| D427 | To what does `PlanPolicy` apply the credential rule's plan form? | To a pack verb's arguments as the client wrote them, not to the verb's compiled references | `B30 D427 veto`: judge compiled calls only, or every reference |
| D428 | How does the player get the scenario's baselines? | `withBaselines(root, env)`, read once per play | `B30 D428 veto`: a further constructor parameter |
| D429 | Does the session's export write the anchors' PNGs? | Yes, beside the bindings file, created exclusively | `B30 D429 veto`: leave the PNGs to the person |
| D430 | How does the live class treat the fallback header's possible refusal, and what does it count? | As a recorded finding; item 3 second; the bounds count the turn's `vision` calls; key scans after each item | `B30 D430 veto`: fail on a 400, and count every ledger entry |
| D431 | Is a capture of a component inside a masked container blanked? | Yes: the ancestors up to the window are walked, their `regions` included; a masked ancestor blanks its rectangle united with the paint root's | `B30 D431 veto`: refuse a capture inside a masked component |
| D432 | Which windows' masks does a person's glass capture apply? | Every window of the JVM whose rectangle meets the captured one, wherever it stands | `B30 D432 veto`: only the windows above the target, or a second mask-only walk |
| D433 | What does a Robot call check before the delivery probe moves the pointer? | The same read as before the press, once: bounds, the drag's end point, `requireFocus`, covering, `expectTarget`; the probe is skipped under a modal dialog | `B30 D433 veto`: a lighter read for the probe |
| D434 | Is the person's pointer restored after a probe that passed? | Yes, in every outcome; one extra move per probe | `B30 D434 veto`: restore on failure only |
| D435 | How is a press balanced when a post fails midway through a routed sequence? | `Pointer.postAll` cancels the sequence and posts one balancing task, once | `B30 D435 veto`: `cancel` alone |
| D436 | What do the Robot capabilities say between AWT being confirmed and the probe's answer? | `UNAVAILABLE` with the reason `robot: probe not run`; a refused post leaves them so | `B30 D436 veto`: retry the post from the watcher |
| D437 | Does the glass capture keep the heap bound? | Yes, per allocation, with an out-of-memory catch; the outside is filled by rectangles | `B30 D437 veto`: one up-front check of twice the raster |
| D438 | Which vector do the agent's and the controller's raster-hash tests share? | `CaptureTest`'s, and the desk check compares the two implementations on a real raster | `B30 D438 veto`: the controller's vector |
| D439 | How does the Robot leg test the `requireFocus` refusal? | In its own test that assumes the unfocused state and reports itself skipped when the frame kept the focus | `B30 D439 veto`: a fixture action that takes the focus away |
| D440 | How does the controller recognise a Robot request, given that the agent reads `via` and `mode` leniently? | One predicate, a superset of the agent's reading, at the seven sites and in `CallPolicy`; `redact` strict | `B30 D440 veto`: normalise `via` and `mode` at compile, or make the agent strict |
| D441 | May `via` or `mode` be a `$slot` reference? | Only if it does not resolve to Robot: the call refuses such a reference | `B30 D441 veto`: re-run the gates after substitution |
| D442 | Is the desk's transient focus recorded in a baseline entry? | No: the approve activates the desk first, and seed B's two `keypad-frame` entries were regenerated | `B30 D442 veto`: hand-edit the entry, or leave it |
| D443 | Does the scenario player evaluate a plan's `verify`? | Yes, once after the last step, all of it, through the journaled checks | `B30 D443 veto`: poll as the executor does, or stop at the first failure |
| D444 | How does a model learn which `$references` the person bound before the turn? | A `<target-state>` block in the turn's first message, from the journal, names only; none when no slot is bound | `B30 D444 veto`: a system part or a new label |
| D445 | Where do an anchor's window insets come from? | From a `WINDOW` capture's origin, read once per window per session | `B30 D445 veto`: an additive `content` rectangle in `windows[]` |
| D446 | Whose role does the recorder read for a click by reference? | The named component's own (`targetNode` on the hit-test's target form) | `B30 D446 veto`: controller only |
| D447 | How long does the vision `wait` tool settle? | For the session's step timeout, between 1 and 110 s | `B30 D447 veto`: a fixed 5 s |
| D448 | How does an export of a played session say what it dropped? | A line per dropped `aiExpect` under 'Live-only'; `verify` as trailing expectations | `B30 D448 veto`: a new report section, or a `verify:` block |
| D449 | A `hook.<id>` declared after the target is ready: pending until a worker probes it, or set at declaration? | Set at declaration to its probe's first answer, under the engine's lock; `hook.install` answers after that | `B30 D449 veto`: the core probes a late declaration synchronously, or the controller polls |
| D450 | Does a Robot press land in a minimised frame? | No: a point in an iconified frame or a window it owns is `NOT_FOUND iconified`, and the window leaves the composite | `B30 D450 veto`: the Robot check alone |
| D451 | Is a Robot press at a point no screen shows refused? | Yes, `NOT_FOUND off-screen`; a press received elsewhere forces a fresh probe | `B30 D451 veto`: the union rectangle of the screens |
| D452 | Is a masked label's or button's name withheld with its text? | Yes, when the name is the text; an accessible name set apart from the text stays and must not itself be a secret | `B30 D452 veto`: withhold every name of a masked component |
| D453 | How do two registered masks combine? | By union | `B30 D453 veto`: the first mask decides |
| D454 | Does a mask's `regions` form hide the text under the regions as well as the pixels? | Yes: a component a region holds whole or a text-bearing one it meets is masked; a table's cells are judged one by one | `B30 D454 veto`: a disclosure sentence that says it does not |
| D455 | How does the agent say what lies above a browser in a capture? | A `covered` list on the surface, by paint order; the surface is its visible part | `B30 D455 veto`: name the layered pane's layers |
| D456 | Does the heap bound count the encoded reply? | Yes, after the encoding, with an out-of-memory catch | `B30 D456 veto`: stream the base64 |
| D457 | What does the glass capture's bound count at a UI scale above 1? | The device pixels at the screen's scale | `B30 D457 veto`: (s^2 + 1) at every scale |
| D458 | When is a second screen's Robot constructed? | On demand, on a worker, and cached | `B30 D458 veto`: one per device at the capability probe |
| D459 | What does a Robot pointer call do with `modifiers`? | Refuses them (`INVALID_PARAMS`); the recorder says why in words | `B30 D459 veto`: ignore them |
| D460 | May the focus owner's locator be the field's text? | No, never; the hit-test's candidate list is unchanged | `B30 D460 veto`: change the hit-test's locators |
| D461 | How is the hidden-browser case tested on real JCEF? | Item 10 asserts the back browser absent and `covered` empty; the wrapped case is a headless test | `B30 D461 veto`: wrap the back browser in the host |
| D462 | How does the controller place a browser's page when a viewport clips it? | The agent sends the whole rectangle as `bounds` on every `WEB` surface | `B30 D462 veto`: clip `bounds` to the image |
| D463 | May a model's or a client's argument reach the credential-joined slot as a whole? | No, in any spelling; their steps resolve against public values only | `B30 D463 veto`: rule 1 alone, or a public view of the bindings during the step |
| D464 | How does the plan policy judge a client's reference to a credential slot? | By the executor's tokenizer, in three places: compiled calls, a pack verb's written arguments, a pack verb's compiled calls | `B30 D464 veto`: refuse every whole-slot reference, or judge compiled calls only |
| D465 | How are a pack verb's written arguments paired with the plan's steps? | By step identity, not by source line | `B30 D465 veto`: `Plan.Step` carrying its written arguments |
| D466 | May the plan policy be asked without the scenario as written? | No: the one-argument form is gone, and `author --run` passes the file it read back | `B30 D466 veto`: keep a one-argument form that fails closed |
| D467 | What may a model or a client do while a credential is in flight? | Reads only; every call that may change the target is refused | `B30 D467 veto`: a value check on every string argument |
| D468 | May a model or a client rebind a slot the person bound? | No; the export marks what they bound `REQUIRED` | `B30 D468 veto`: the credential-only rule |
| D469 | What does the plan form do with a value nested deeper than 64 levels? | Refuses it in a plan with a credential slot | `B30 D469 veto`: fail open past the bound |
| D470 | May a model or a client paste, copy or cut through the person's clipboard? | No, on every route; no chord and no middle button is ever sent for them | `B30 D470 veto`: allow and document |
| D471 | Is a browser a pack's mask names masked for the raw `web_*` reads and the snapshot too? | Yes, on every route; the CDP path costs one more agent `web.browsers` per read | `B30 D471 veto`: withhold only the pixels |
| D472 | May the recorder click a page whose window a modal dialog blocks? | No; refused before `web.hitTest` | `B30 D472 veto`: a modal check in the web DOM primitives for models and clients |
| D473 | What does the stale-capture rule compare the hit with? | With what the capture showed (the mark's ref, the window at the pixel), not with a second live hit-test | `B30 D473 veto`: compare two live hit-tests, as built |
| D474 | Is a tool set's reading bounded per turn? | Yes: 20 reads or twice the action limit, and three step timeouts of waiting | `B30 D474 veto`: a new record component, or a sentence in the instructions |
| D475 | Where does the composite draw a browser's page? | At `bounds`, clipped to `rect` minus `covered`; not at all if `bounds` is missing and `rect` is not the bitmap's shape | `B30 D475 veto`: scale the whole page into the visible part |
| D476 | Is the provider's token count sent when the session's budget cannot pay for the call? | No: the local estimate is checked first | `B30 D476 veto`: send the count anyway |
| D477 | Which web hits are recorded as ordinary `webClick` steps? | Those in the starting frame of the browser the step resolves to; the rest are live-only | `B30 D477 veto`: record every hit |
| D478 | What does the CDP pointer do once its caller timed out? | Dispatches nothing more; a press already sent is released | `B30 D478 veto`: a timer in the page |
| D479 | May a recorded locator name a text field by its content? | No, never | `B30 D479 veto`: change the hit-test's candidates |
| D480 | How are the inner-size and title reads bounded against a hostile page? | Built from `typeof`, two numbers and a primitive `length`, and parsed with the channel check | `B30 D480 veto`: the `web.evaluate` wrapper |
| D481 | How much of a compared raster may the focus rule blank? | At most a quarter; above it the rectangle is compared and the message says so | `B30 D481 veto`: bound each rectangle separately, or drop the focus rule |
| D482 | May an `approve` write the bindings file without a person's yes? | Not for anything but a baseline approved at its step; a change of the header or the resolutions needs the approver's yes, and a client's run under `REJECT_ALL` cannot approve | `B30 D482 veto`: ask at bind time, or let a header change ride on an approved baseline |
| D483 | How does the export of a re-recorded anchor reach an existing bindings file? | The export writes its own file and the report names each entry to merge | `B30 D483 veto`: the CLI merges into the existing file |
| D484 | May a baseline's focus owner be identified by the field's text? | No: the `text` key is dropped, the role stays | `B30 D484 veto`: a hash of the text |
| D485 | Where does a session without `--out` write? | Into a new owner-only temporary directory whose path it prints | `B30 D485 veto`: correct the documentation to `./run-output/<id>`, or a `--no-out` |
| D486 | What does a writing bind do with a bindings file it cannot read? | Ends `ERROR / ENVIRONMENT` naming the file and the reason | `B30 D486 veto`: fail every bind that finds one |

**What it changes.** Each is built and tested (`TODO.md`, "Phase 7"), and the contract carries an "As built (Phase 7, <TAG>)"
note at every section a decision touches. The decisions that differ from the contract's own sentence are D371 (the visual
check's question: the one that is true on the desk), D383 and D384 (a mask on a container hides everything inside it, and a
capture the heap cannot hold is refused, never silently scaled down), D403 (`keypad-image`'s frame check masks three
components instead of one), D404 (an additive optional field `blockedBy` on P4 §4.2's `web.browsers`), D420 and D421 (the
session facade and the tool-set interface gain a few methods) and D425 (`ExecutorSession.fixture`, a method added to a file
that another package owned, and the origin rule that a credential slot is bound only for a person's origin; it refines D342).
D359 and D428 are the two ends of one mechanism (the caller hands the baselines in), D404 and D416 belong together (the CDP
path hands a blocked browser to the agent), and D413 is the one decision that rests on a guess about a library the project
cannot run (JxBrowser's input roles, wall gap 41). **Behaviour changes the owner should know:** (a) `skillroy session --play`
now binds a scenario's fixture slots as the person, a credential slot included (D425); (b) a mask on a container hides the text,
value and mark of everything inside it (D383); (c) a `web.browsers` entry may carry `blockedBy` (D404); (d) the committed
`keypad-image` baselines carry three masks and the reviewer-only text of the container's `os.version` (D403, D350).

The fixers' decisions are marked in the contract where they differ from or add to a sentence ("Fix wave (Phase 7 review, <TAG>,
2026-10-10)", "(Phase 7 security fix, S<nnn>, built 2026-10-11, <TAG>)", and "As built (Phase 7, FX, 2026-10-11)" in P6 §4.6).
D452 and D454 widen D349 and D383 (a masked label's name and the text under a mask's `regions` are withheld), D463, D467 and
D468 widen D342 and D425 (the credential rule: the joined slot as a whole, mutating calls in flight, a person's slot not
rebound), D464, D465, D466 and D469 amend D427 (the plan form), D482 amends D362 (no bind-time write), D470 adds rule 11 to
§11 (the person's clipboard), D440 and D441 close the spelling and the reference route round the Robot opt-in, and D449 is the
one decision that changes a Phase 6 behaviour (a `hook.<id>` holds its state when `hook.install` answers). D462 completes
D475 (the agent now sends the browser's whole `bounds`). **Behaviour changes the owner should know**, from the fix wave and the
security fixes: (e) the controller's Robot and glass gates (the executor, the session, the CLI's `call`, the plan compiler
and the export) read `via` and `mode` as the agent does, in any case and with white space and control characters trimmed, and
a `$slot` reference that resolves to Robot is refused at the call (D440, D441); (f) a Robot press refuses a point in a minimised
frame or a window it owns, a point no screen shows, and `modifiers`, and a press received by another component makes the
next action probe again (D450, D451, D459); (g) a model or an MCP client can no longer paste, copy or cut through the
person's clipboard or the X11 selection, or press the middle button, through any tool or raw primitive (D470); an application's
own Paste control stays an ordinary click, to be masked; (h) a browser a pack's `CaptureMask` names is masked on every route:
no pixels, text or tree for a model or a client (D471); (i) no credential-joined slot is ever a model's or a client's
argument in any spelling, their steps resolve against the slots' public values, nothing that changes the target runs for them
while a credential is in flight, and a slot the person bound is not rebound by them (their bindings are marked `REQUIRED` in
the export; the committed `session-basic-loan` bindings were regenerated key-free) (D463, D464, D467, D468); (j)
`skillroy_scenario_run` in `approve` is refused without an approver and never writes into the bindings root, an images-only
`approve` writes nothing at bind time, and an `approve` with `REJECT_ALL` after an environment change now ends
`RESOLUTION_FAILED / NOT_APPROVED` (D482); (k) the image comparison blanks a focus owner's rectangle only up to a quarter of
the compared raster, so a baseline approved while the compared component held the focus may need re-approval if the focus
differs at replay (D481); (l) `skillroy session` without `--out` writes into a new owner-only temporary directory and prints
its path (D485); (m) the scenario player evaluates the plan's `verify` after the last step, so a played scenario can now end
`FAIL` where it ended `PASS` (D443); (n) the turn's first message lists the slots the person bound, and the five Phase 5
session recordings were regenerated key-free for it: any later change of that message regenerates them again (D444).
**State of the build.** Built, reviewed, fixed and security-reviewed (delta) 2026-10-10/11: the native reactor on the head
`c94aade` is green (5,099 tests, 0 failures, 0 errors, 592 skipped, surefire and failsafe together), the fixers' image runs on
both Java lines are recorded in `TODO.md`, and the Linux matrix of the final tree is green on `c94aade` (arm64 twice on every leg, amd64 on the Java 25 leg), its amd64 Java 8 legs green on `bc1e250` later on 2026-10-11. **Blocks.** Nothing; confirmed, so the built behaviour stands as it is, the behaviour changes (a) to (n) above included.

## B31 The Phase 7 security delta review's sign-off (2026-10-11; signed off by the owner 2026-10-11)

**From:** the delta review that B13 and the release checklist's item 2 ask of a phase that opens a new boundary, specified in
`design/phase7-contracts.md` §15 (D338; the sign-off rule is P6 §12.6: the owner writes it, the orchestrator does not) and run
on 2026-10-10/11 over everything Phase 7 changed since `af29e59`, the tree B28 covered. Its records are in the session notes,
outside the repository: `notes/2026-10-10/phase7/security/` (`record.json` in the 2026-10-02 shape, with the dispositions as
built, `dispositions.md`, the eight lenses' reports with their sceptics' verdicts, and the four fix reports); `TODO.md`
summarises them under "Security review (Phase 7 delta, 2026-10-10/11)".

- **The review:** eight lenses (L1 controller correctness and bounds, L2 host safety, L3 the data channels and, found there,
  the credential rule, L4 the web boundary, L5 the model boundary, L6 the model as an actor, L7 Windows and file-system trust,
  L8 evidence at rest), one reviewer and one sceptic each; L1, L3, L4, L7 and L8 on `e96a20b`, L2, L5 and L6 on `c690bef`.
  52 findings raised (29 by the reviewers, 23 by the sceptics), 1 refuted (L8-F4, no number), 3 duplicates merged:
  **48 standing, S101-S148: 0 critical, 2 high, 13 medium, 33 low; 42 counted (2 high, 13 medium, 27 low), 6 operator-only
  (S117, S119, S126, S136, S143, S147), none token-holder-only**. 22 were raised by the sceptics; the sceptics re-rated S129
  and S141 (to low) and restated the reach of S103, S110, S145 and S147. Per lens: L1 6, L2 9, L3 5, L4 7, L5 5, L6 6, L7 6, L8 4.
  The code review's fix wave closed none of them.
- **The two high findings:** S101, the credential rule of D342 missed the credential-joined slot as a whole (`x$staff` resolved
  to the slot with its joined password in a model's or a client's argument, past every refusal on every route), and S102, the
  plan form keyed a pack verb's written arguments by source line (flow-style YAML or a one-line JSON scenario let
  `$staff.password` through a pass-through verb). Both were reachable by a model or an MCP client.
- **Dispositions:** every finding of medium or above is **fixed**, and so are 31 of the 33 lows (contract first, a test that
  fails on the old code, marked in the contract with the review id), by four packages with the primary assignment SG 12, SH 7,
  SI 14 and SJ 13 findings (S108, S124, S143 and S146 shared by two packages), merged into `main` as SG `5d6fdd8`, SH `edba708`,
  SJ `73e406a`, SI `056a007` and SG's follow-up for S108 `c94aade`; their 37 decisions are D450-D486 (B30). No fixer found a
  finding wrong or already fixed. Nothing of medium or above is accepted or deferred.
- **Accepted by the owner on 2026-10-11, as proposed (P6 §12.5: a documentation-only change is an acceptance), each with its
  release note (also in `SECURITY.md` "Known limitations"):** the two lows below.
- **Not verified** (also in `README.md`, `SECURITY.md` and `design/threat-model.md` §8): everything on Windows (the ACL
  judgement of S141 on built values only, S142's junction, S144's inheritance, a minimised frame's parking position,
  `SetCursorPos` clamping), where no host exists; macOS (the routing of a button event at an off-display point, the first glass
  capture's screen-recording consent dialog) and the minimised-frame refusal off the WM-less display (it ran once under
  openbox); a window of the target JVM on another virtual desktop or workspace, which AWT cannot see; a real masked browser
  (S111 ran against fakes and over real Swing components), one desktop with a real JCEF frame and a `JOptionPane` (S108), the
  paste of S110 and the middle click on an X11 desktop with a headed JVM, S133's late click on real Chromium; every path that
  needs a live model call (a provider's handling of a toolset member whose `configs` disable it, the planner's model choosing
  `approve`, the 5.5 models' history rule under the automatic cache hint); a second OS user for the writers' link rule (S140),
  an APFS case-insensitive volume (S118), a real multi-screen desktop, HiDPI (the glass bound at scale 3 through a seam only),
  the amd64 image; and the other items of `threat-model.md` §8. The lenses themselves ran with no display and no image run.
- **Two notes for the owner, not findings.** (1) The clipboard route of S110 (a model pasting the person's clipboard or the X11
  selection into the target, and so to the provider through the next capture) **predates Phase 7**: the planner's server is
  always `mcp --mutating` and has offered `swing_key` with modifiers since Phase 5, so the route existed for the planner and for
  a client of a `--mutating` server when B28 signed off S35-S100, and B28 did not see it; D470 closes it for every route and
  Phase 7 added the second default route (every vision session). (2) Under the default `fallbacks: true` a refused vision
  request is re-run by the provider on a fallback model of the same provider, redacted captures included; the fallback targets'
  data-handling regime is the owner's to confirm (the open S52 question of pass 2, now with pixels; `SECURITY.md` says so).
- **Outside reader:** none has read the record yet (B26 Q12: recommended, recorded, not a gate).
- **Matrix:** the final Linux matrix is green on `c94aade` (arm64 twice on every leg, amd64 on the Java 25 leg), its amd64 Java 8 legs green on `bc1e250` later on 2026-10-11; the live acceptance of `VisionLiveIT` ran three times (the last on
  the final code: 7 of 7, USD 0.148; `TODO.md`).

**Question.** Does the owner sign the delta review off, with the two lows below accepted? The orchestrator recommended yes,
once the final matrix was green (the amd64 Java 8 legs excepted) and the owner had read the two release notes. **Signed off by
the owner on 2026-10-11** (`B31 ok`, no veto): the two lows are accepted as proposed, each with its release note; no outside
reader was made a gate (B26 Q12 as answered). The signature is the owner's; the orchestrator did not sign.

| Item | Low finding accepted by the owner on 2026-10-11, with its release note (also in `SECURITY.md` "Known limitations") | Veto form offered |
|---|---|---|
| S142 (L7-F4) | On Windows a directory junction (`mklink /J`, which needs no privilege) in place of a run's `failure/`, `captures/` or `candidates/` directory, of the per-scenario output directory, or of a bindings environment's `<id>.images/` directory is taken for an ordinary directory and judged by its target's owner: the run then writes its PNG files into, and deletes files of its own names from, the directory the junction points at, if the operator owns it; a symbolic link in the same place is refused unless the operator owns it. Give `--out` and the working copy a directory under your profile whose ACL others cannot write (`icacls <dir> /inheritance:r /grant:r "%USERNAME%":(OI)(CI)F`). Not verified on a Windows host (a code remedy, comparing the real path, is named in the finding for a later Windows host) | `B31 S142 fix` (waits for the Windows host) |
| S144 (L7-S2) | On Windows, run output, a session's `captures/` (a screenshot of every screen the model acted on), `candidates/`, `failure/image-*.png` and an uncommitted `<id>.images/` directory inherit the access list of the directory they are created in; the `umask 077` remedy of the POSIX hosts has no counterpart there, and a folder under `C:\` gives every local user read access by default. On a shared Windows host give `--out` and the working copy a directory under your profile, or remove inheritance from the directory you choose. Not verified on a Windows host (the code alternative, S37's option (i) for ACL volumes, is not built) | `B31 S144 fix` (waits for the Windows host) |

**The veto forms that were offered** (the owner signed off with `B31 ok` on 2026-10-11 instead): `B31 S142 fix` and `B31 S144 fix`
would have refused one proposed acceptance by name, the fix then built and the sign-off waiting for it; `B31 outside reader
first` would have made an outside human reader of the record a gate of the sign-off (in effect a veto of B26 Q12, which was not
given). Two optional choices the review left to the owner were not part of the sign-off and were not answered by it: whether replay should refuse `NOT_APPROVED` for a recorded
`REQUIRED` approval whatever the strategy (one line in `Binder`; gap 93), and whether `web.dom.click` and `web.dom.fill` should
refuse a page under a modal dialog for a model or a client (gap 96). **What it changed.** With `B31 ok` (given 2026-10-11) the "Reviews" paragraph
of `SECURITY.md` carries the signature and drops "provisional" from the two release notes, `docs/release-checklist.md` item 2
records it, and in `TODO.md` the Phase 7 item "B31, the owner's sign-off of the security delta review" is ticked, the Exit item's
criterion "the security delta review is signed off" is marked met (2026-10-11); the Exit item stayed unticked until the amd64 Java 8 legs of the final matrix had run, because its `expectImage` criterion names every leg (`phase7-contracts.md` §14); they ran green later on 2026-10-11 (`bc1e250`) and the Exit item is ticked. The two release notes join the release note (checklist item 11), ten with B28's
eight. **State of the build.** Everything is built and merged into `main` (head `c94aade`; nothing pushed). **Blocks.** Nothing
for the build; the signature is given, and the first public release waits for the owner's word to push (B1).

## B32 Follow-ups after Phase 7 (2026-10-11; confirmed by the owner 2026-10-11)

**From:** the session of 2026-10-11 after Phase 7 closed, without the owner: gap 94 of `TODO.md` (S101 item 3 and the
vocabulary's sentence that SH proposed and no package held) gap 99 (the bindings JSON reader's link rule), gap 92 (a late declaration's pending reason) and gap 63 (`hook.events`'s
`id`). Each is
built and tested and the contract carries it as "(Phase 7 follow-up, gap <n>, built 2026-10-11)" in
`design/phase7-contracts.md` §6.5, §8.7, §9.1, §9.2 rule 1 and the gaps of §16 (gap 92 in `design/phase6-contracts.md` too).
**Confirmed by the owner on 2026-10-11** (`B32 ok`, no veto): the built behaviour stands.

| D | Question | Default taken | Veto form |
|---|---|---|---|
| D487 | How does the vision recorder learn that a `type`'s text names a credential slot as a whole (`$staff`, `x$staff.`), which only the session's joins can tell? | The session facade gains `default boolean namesSecret(String text)` (false by default, so another facade leaves the refusal to the session), answered by a public `ExecutorSession.namesSecret(value)`, rule 1's own predicate; the recorder reads the text with `References.references` and refuses before the session, so no refused step is journaled | `B32 D487 veto`: leave the whole-slot refusal to the session (its text, a journaled refused step), the recorder on the public tokenizer only |
| D488 | Should the vocabulary's sentence name the whole-slot rule? | Yes, SH's wording: "… names a non-secret field of a resolved slot, never a credential's secret nor a credential slot as a whole: the person signs on"; the five Phase 5 session recordings regenerated key-free. The old wording had reached the model as "secret: <redacted> person" (the mask's `key: value` rule); the new one passes the mask whole | `B32 D488 veto`: keep the Phase 7 sentence (and restore its recordings) |
| D489 | Gap 99: which reads of a bindings file follow the link rule that the writers and the PNG reader follow (S140)? | Every read of a scenario's bindings file by the binder, the session and the executor (`BindingsFile.read(bindingsRoot, envId, scenarioId)`); a link at the file's position, dangling or not, is a file that cannot be read, so a writing bind refuses it (S116) instead of replacing the link; `diff-bindings` reads the files a person names, as named | `B32 D489 veto`: let a writing bind replace a dangling link, or refuse a link in `diff-bindings` too |
| D490 | Gap 92: what does a capability declared after the target is ready read until its posted probe has run? | `UNAVAILABLE("not probed yet: declared after the target was ready, its probe is queued")`, the prefix callers read kept; the state and the timing unchanged (no synchronous probe, no SPI change, D449) | `B32 D490 veto`: keep "not probed yet: the target is not ready" for every pending entry |
| D491 | Gap 63: what does `hook.events` do with an `id` that is not a hook id (a comma or a wildcard would widen its topic filter)? | Refuses it (`INVALID_PARAMS`, `param: id`) and answers `hook.*` topics only, as P6 §4.2 reads | `B32 D491 veto`: accept any `id` as before |

## B33 The owner's answers of the evening of 2026-10-11

**From:** the owner's message after B32: "Regarding Gaps 93 & 96: Do not refuse either. I confirm I own the skillroy.ai
domain name (and so "ai.skillroy" is fine for the namespace.) I've placed a JxBrowser 30 day evaluation license key in
env var JXBROWSER_LICENSE_KEY."

| Subject | Answer | What it changes |
|---|---|---|
| Gap 93: should replay refuse `NOT_APPROVED` for a recorded `REQUIRED` approval whatever the strategy? | No | Nothing: replay refuses an unapproved value for a model's strategy only, as built (D72, D468); the gap is closed |
| Gap 96: should `web.dom.click` and `web.dom.fill` refuse a page under a modal dialog for a model or a client? | No | Nothing: the recorder's path stays closed by D472, the DOM primitives unchanged; the gap is closed |
| B2: is the domain behind the `ai.skillroy` groupId under the project's control? | Yes (the owner holds `skillroy.ai`) | The release checklist's item 9 is met |
| B9: a JxBrowser licence for the adapter's real verification | A 30-day evaluation key from 2026-10-11, in the owner's login shell as `JXBROWSER_LICENSE_KEY` | The kit run against the real product can proceed outside this repository (the wall "A JxBrowser licence"); the key never enters a repository, the notes or a page |

## B34 Follow-ups of the evening of 2026-10-11 (provisional)

**From:** the work after B33, without the owner (the owner: "do whatever remaining work you can"). Each is built and tested
and the contract carries it as "(Phase 7 follow-up, gap <n>, built 2026-10-11)". Answer in one line: `B34 ok`, or a veto by
name.

| D | Question | Default taken | Veto form |
|---|---|---|---|
| D492 | Gap 101: which covering windows may the Robot press past without `force`? | None but one: a window that owns the target's window, while the target's window is the active window (an owner lies below the windows it owns; the active window was raised when it was activated, and without a window manager nothing else keeps an owned window above its owner), for the point and a drag's end point; the hit-test still reports the owner under `covering` | `B34 D492 veto`: leave out every owner whatever the active window, or keep refusing every covering window |
| D493 | The JxBrowser kit run (8.18.3): how does the adapter dispatch pointer input, which `Browser.dispatch` offers as one overload per event type? | One descriptor role per event type (`browser.dispatchMouse{Pressed,Released,Moved,Wheel}`, matched by parameter type), the wheel's deltas negated (JxBrowser's sign is the page's opposite), and no "input unverified" note on JxBrowser 8; modifier keys stay refused (a `KeyModifiers` type exists, for later) | `B34 D493 veto`: keep input unavailable on JxBrowser until a later line |
| D494 | Gap 47: may the adapter's observer registration (`on(...)`) wait for the Chromium process? | No: it runs on the adapter's threads, the caller waits at most 2 s (not at all on the EDT), a registration that answers after `detach()` is unsubscribed at once; `set`, `get`, `remove` and the endpoint reads, which never waited in the run, stay on the calling worker | `B34 D494 veto`: keep the registration on the calling worker |
| D495 | Gap 95: how does the web host's check get a masked browser without a launch of its own? | The web host's agent module registers a `CaptureMask` on the front card only with the agent option `webhost.mask=front`, read once at install; the check re-attaches a fresh agent with it (as item 8 re-attaches), so items 1-10 run unmasked | `B34 D495 veto`: a launch of its own for the masked case |
| D496 | How does that mask name the front browser without a JCEF type in the agent module? | By component names: the component named `front` whose parent is named `deck` (the host's card layout) | `B34 D496 veto`: match the browser's view class reflectively |
| D497 | Where does the check's modal dialog come from? | The web host gains a Notice button that shows a modal `JOptionPane` over the page (the sample's one modal dialog) | `B34 D497 veto`: open the dialog from the test through the agent |
| D498 | How do the checks reach the recorder's refusals? | The vision module's real `Grounding` and `Recorder` over a pass-through facade to the attached agent, not a full session service | `B34 D498 veto`: run them through `SessionService` with a scripted gateway |
| D499 | May the web host scenarios module depend on the vision module? | Yes, in test scope only | `B34 D499 veto`: copy the needed checks instead |

---

## Questions for the owner

**Open as of the evening of 2026-10-11: B34** (above, provisional; answer `B34 ok` or a veto by name). Before it, none was open: the owner answered **B33** (gaps 93 and 96 not refused, the domain confirmed,
a JxBrowser evaluation licence) and confirmed **B32** (D487-D491, the decisions of gaps 94, 99, 92 and 63)
on 2026-10-11 without a veto (`B32 ok`), and accepted the three Phase 6 merge commits without a `Signed-off-by`
(`ee020ad`, `6a342e7`, `f33488f`; "DCO accept": the release checklist's item 10 for the first release). Before it, none was open as of 2026-10-11: the owner confirmed **B29** (the Phase 7 contract's thirty-nine answers, `design/phase7-contracts.md` §18, decisions D287-D358) and **B30** (the 128 decisions the Phase 7 build, its code review's fix wave, the race fix and the security fixes took, D359-D486) on 2026-10-11, every row, without a veto (`B29 ok`, `B30 ok`); both had been taken without the owner on 2026-10-10 (B30 also on 2026-10-11) and were provisional while the build ran on them. Among the confirmed points are the three that rested on the owner's knowledge or taste (Q27, JxBrowser input behind descriptor roles, unverified; Q34, V3 on a person's desktop acting only while the target holds the focus; Q25, the number pad as the sample's component) and the decisions worth a look (D403, D413, D425, D470, D482), so the built behaviour stands as it is, the behaviour changes listed in B30 included. The owner signed off **B31** (the Phase 7 security delta review, S101-S148) the same day (`B31 ok`), with the two lows proposed for acceptance accepted as proposed: S142 (a Windows directory junction in a run's output tree) and S144 (Windows run output inheriting its parent's access list), each with its release note in `SECURITY.md`. Outside the decisions, the push still waits for the owner's word (B1) and the Windows host is still missing (`TODO.md`, "Walls"). Two optional choices that the delta review left to the owner rode along as gaps 93 and 96 of `TODO.md`; the owner answered both on 2026-10-11, not to refuse (B33).
Before B29, B30 and B31 none was open: the owner confirmed **B26** (the twenty-two answers of the Phase 6 contract,
`design/phase6-contracts.md` §17, D114-D159) and **B27** (the 127 decisions the Phase 6 build, its code review's fix wave
and the security fix rounds took, D160-D286) on 2026-10-10, every row, without a veto, and signed off **B28** (the repeat
security review, S35-S100) the same day with the eight lows accepted as proposed: "I confirm all your default decisions,
and I sign-off on the security review", read as `B26 ok, B27 ok, B28 ok`. B26 and B27 were provisional while the build ran on
them on 2026-10-09. Outside the decisions, the push still waits for the owner's word (B1) and the Windows host is still
missing (`TODO.md`, "Walls").
Before it, the owner confirmed **B25** (the three decisions of the live session of 2026-10-08, D111 to D113) on
2026-10-09, every row; none was then open until B26 and B27 were taken later that day. **B23** (the Phase 5 contract's eighteen provisional answers, one withdrawn) and **B24** (the eighteen decisions the Phase 5
build and the review's fix wave took, D88-D104 and D110) were confirmed by the owner on 2026-10-08, every row.
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