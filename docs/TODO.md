# TODO: base framework

What exists, what was proven and what is open for the base framework (`skillroy-test-java`), as of
2026-10-08. Decisions are in `DECISIONS.md` (B1-B23); the design is in `docs/design/` (Phase 1 and Phase 1.x as
built in `phase1-contracts.md`, Phase 2 as built in `phase2-contracts.md`, Phase 3 as built in
`phase3-contracts.md`, Phase 4 as built in `phase4-contracts.md`, Phase 5 specified in `phase5-contracts.md`);
research notes are in `docs/research/`; the dependency licence audit is `docs/licence-audit.md`.

## Status at a glance

**Phase 1 is built and verified on the two gates of B3 and B4: a Java 8 target and a Java 25 target.** On 2026-10-02 the
controller side moved to Java 25 and the sample application was replaced by the sample lending desk. After both changes
the whole reactor is green natively and in the Linux test image, and the end-to-end acceptance
(`design/phase1-contracts.md` §10; 13 checks per load mode, 52 in all, in these runs; 21 tests per mode, 84 in all,
since Phase 1.x) passes in all four load-mode variants on a Java 25 target and on a Java 8 target. The two whole-reactor
configurations in the image were run once each, the agent-side configuration three times (before the changes, every
configuration twice). One display test failed in one of those three runs; it is recorded below as an open flake. **Phase
1.x** (amendments A17-A33, hardening H1-H3) is built, merged into the main tree and **verified there** (second table
below: every configuration green; the end-to-end acceptance has 21 tests per load mode). Three timing races in tests
surfaced under machine load during that verification and were fixed in the tests (a prompt-view read, an HTTP-budget
test, a child-JVM output read); none was a defect of the code under test. **Phase 2** is **built, merged, reviewed and
closed** (built late on 2026-10-02; closed 2026-10-03). Its integration tree was green natively (1877 tests) and in the
Linux image (Java 25 targets 1897 / 0 / 0 / 4 twice, Java 8 targets 1897 / 0 / 0 / 4 twice, agent side on a Java 8 test
JVM 1000 / 0 / 0 / 4), and scenario A passes in `replay` on seeds A and B with one YAML in every load mode. On
2026-10-03 the merged main tree was run through the Linux matrix (third table below), the three-lens code review against
`design/phase2-contracts.md` was run and its findings adversarially verified and fixed (section "Phase 2 code review"
below), and `design/phase2-contracts.md`, the Phase 1 contract's Phase 2 passages, `DECISIONS.md` (new B19) and
`README.md` were brought to as-built. **Phase 3** was specified, built, reviewed and brought to as-built the same day
(`design/phase3-contracts.md`; B20 holds its answers, confirmed by the owner 2026-10-06): oracle cross-checks with
`ORACLE_SUSPECT`, guarded cross-checks as `DEGRADED`, stored-row aggregates, the `devices` fact root, plan version 3,
and sample scenario B `borrow-2-waive-1-slip` passing in `replay` on both seeds in every load mode on both target Javas,
twice in a row (tables below). **On 2026-10-06** the owner answered every open question (`DECISIONS.md`): B17-B20
confirmed, with two vetoes inside B19 built the same day (a masking threshold per environment, `masking.minLengthInText`
in `credentials.yaml`; no base rule on names in binding values), the holder's name went into every header, and the
initial commit was made (not pushed). After those changes the native reactor was green (2132 tests, 0 failures, 0
errors, 285 skipped, JDK 26, headless). **Later on 2026-10-06** the committed tree (commit `efb48b7`) was run through
the Linux matrix once (first table below): the two whole-reactor legs green at the first attempt (2156 tests each), the
agent-side leg green at the second attempt after one display-test focus failure of the kind recorded as the open flake
(now two occurrences, both on a Java 8 test JVM; see "Open flake" below). This is the Phase 3 tree's second clean run
and the first run with the 2026-10-06 masking change in the image. **Later the same day** spike BS8 was run (passed with
conditions; `spikes/BS8/RESULT.md`) and **the Phase 4 contract was written** (`design/phase4-contracts.md`, specified,
not built; its fifteen provisional answers are decision B21, on the owner interview page).

**On 2026-10-07** the owner confirmed B21 (all fifteen answers, no veto), and **Phase 4 was built, reviewed and fixed
the same day** (`design/phase4-contracts.md`, now as built; the decisions the build took are its D59-D69). The method
was that of Phases 2 and 3. Seven implementers worked in isolated copies of the tree (the web SPI; the script library;
the controller side; `skillroy-cdp`; the web host and its agent module; `skillroy-agent-web` with the kit, the
distribution and the `PAINT` guard; the web host's pack, scenarios and end-to-end acceptance), integrated into the main
tree by file list in that order; the tree before the last of them went through a code review of three lenses with a
sceptic each (section "Phase 4 code review" below); a fix wave of three implementers (the agent's web module,
`skillroy-cdp`, the executor) closed every confirmed finding; then the documents were brought to as-built. Natively
(JDK 26, headless) the integrated tree before the last implementer and the fixes was green with the profile `jcef`
(2513 tests, 0 failures, 0 errors, 290 skipped; 199 s), and so was the final tree after the fix wave (2701 / 0 / 0 /
383; 222 s). The implementer who built the acceptance ran it in the Linux image on its copy, before the fix wave, which
is the evidence before the matrix: with `-Pjcef` and `-pl` the three web host modules `-am` (most of the reactor), the
amd64 image with a Java 25 target 2169 / 0 / 0 / 16 (783 s), an arm64 build of the image natively with a Java 25
target 2169 / 0 / 0 / 16 (547 s) and with a Java 8 target 2169 / 0 / 0 / 44 (487 s), and the web host's modules with
their upstream agent modules on a Java 8 test JVM (arm64) 694 tests, 16 skipped (29 s; the twelve windowed kit tests
among them); the kit against real JCEF passed 12 of 12 off screen and 11 with one skipped windowed, and the 50 attach
attempts against a frozen page all ended with `FROZEN_TARGETS` within the deadline (the slowest about 2.02 s). The
earlier runs of that copy found defects that only a real JCEF shows, fixed before those runs (section "Phase 4 code
review" below), and display-test flakes of the Swing module ("Open flake"). The orchestrator's Linux matrix of the
final tree is the table that follows.

**On the night of 2026-10-07 to 2026-10-08 the Phase 5 contract was written** (`design/phase5-contracts.md`, first
specified, then built the same night, below): the `InferenceGateway` SPI with typed content parts and its Claude, recorded and scripted implementations,
tier (b) SQL behind `SqlGuard` with masking before any prompt, approval and budgets, batch re-resolution, `skillroy-mcp`,
the planner through the `claude` CLI, triage, and the interactive profile (`ExecutorSession`, `StepJournal`, the export,
the session service); CI and replay never need a model key. Its eighteen provisional answers are decision **B23**, on
the owner interview page. Two spikes started the same night as the contract's gates: BS6 (`SqlGuard` across dialects on
JSqlParser) and BS13 (the Claude API facts the contract relies on, checked live with the owner's key). The small Phase 4
follow-ups (gaps 38, 40, 45 and, if cheap, 42) went to implementers as Phase 4.x amendments A35-A38, and the display-test
focus flake to an investigator. **The flake is explained and mitigated** (A39, below: 14 failed runs of 50 before, 0 of 60 after), and the amendments A35-A38 are built.

**Phase 5 was then built the same night on the then provisional B23 answers, confirmed by the owner the next morning** (the method of Phases 2 to 4: eight
implementers in isolated worktrees, integrated by file list in waves, the native reactor green after every wave; the
decisions the build took are D88-D95, B24), **run through the Linux matrix** (first round: three failures in checks that
had never run before, fixed; tables below), **reviewed through the three lenses, each verified by a sceptic** (33 distinct
items, among them five rated high: a model client could run any primitive confirmed through the MCP scenario-run tool, a
key with a stray character could leak through a provider exception, a session check was an oracle on masked values, the
allow-list was never checked against the schema, triage sent row samples unmasked by column; section "Phase 5 code
review" below), **and fixed by four fixers** (decisions D96-D103 and D110; the review's as-built and "fix wave" notes are
in the contract). The final tree's matrix is the first table below. **In the afternoon of 2026-10-08 the live items were closed** with the owner's workspace-scoped key (the method of
the night: contract first, D111-D113 as B25, confirmed by the owner on 2026-10-09; two implementers in worktrees and a third for scenario C; one review lens
with probes; a fix round; the matrix before the commits; "Phase 5, the live items" below): BS13's live part on SDK
2.70.0, scenario D's tier (b) requests re-recorded live on both seeds, the profiles `inference-live` and
`inference-record` with the live acceptance of P5 §15.3 green in the image (five items; the cache-hit criterion met at
91.4 % on both nightly jobs), the planner-authored scenario C reviewed, committed and replayed, and about USD 2.05 spent.
The live table follows this paragraph; the matrix of the final tree is the table after it.

The live runs of 2026-10-08 (afternoon), with the key passed through by name; every figure from the ledger entries the
tests printed:

| What | Where | Result |
|---|---|---|
| BS13's live part (`java -jar spikes/BS13/target/bs13-probe.jar live`, SDK 2.70.0) and the CLI item | natively | every item answered, USD 0.032 plus about 0.008 of raw probes (`spikes/BS13/RESULT.md`) |
| `ScenarioDRecordIT` (`-Pinference-record`): scenario D's requests re-recorded | natively (the JDBC channel, the in-JVM database) | both seeds answered by `claude-sonnet-5-5` at effort `low`, the committed SQL's supervisor found; USD 0.029 (0.0223 + 0.0071, the second reading 5024 cached tokens) |
| `ClaudeGatewayLiveIT` (`-Pinference-live`), §15.3 items 1 and 2 | natively | 2 tests, 508.6 s: a 1187-token cache write then read 2.4 s later, `countTokens` exact (1207); a batch of two ended after 504 s, its second request reading the first's prefix; USD 0.0053 |
| `InferenceLiveIT` (`-Pinference-live`), §15.3 items 3 to 6, first run | the arm64 image | 2 failures, 2 errors (the confidence gate at 0.8 refusing a correct answer at 0.7, the strict tools above the provider's 16-union limit, the planner's `$schema` key, a missing test directory): all fixed |
| `InferenceLiveIT`, second run | the arm64 image, the Linux `claude` 2.1.294 mounted | 5 tests, 0 failures, 485.7 s: scenario D live through the agent channel to the committed supervisor (USD 0.022); the nightly job A+B `UNCHANGED` and D twice `CHANGED` with the same value, USD 0.0054 and 0.0051, **cache hits 91.4 % on both**, batches 154 s and 184 s; `skillroy author` on the brief exit 0 in 119 s (Opus 5.5, 35 turns, USD 0.907); one session turn `DONE` in 20 s after nine actions (USD 0.166), its export compiles; 26 files scanned clean; USD 1.11 in all |
| Scenario C authored (`skillroy author --id return-refunds-deposit --run`) | the arm64 image | Opus 5.5, 43 turns, no repair, USD 0.80, 161 s with the checks; compiled, resolved, ran green on seed A; reviewed and kept; bindings approved on both seeds with no model call |

Verification of the final tree of the live session (the tree committed as `7690322` and `22ae574`; the live classes
skip in it, no key being passed), 2026-10-08 evening: the arm64 image natively twice in a row, then the amd64 image
under emulation once, three legs each:

| Configuration | Command (from the repo root) | Result |
|---|---|---|
| Native, JDK 26, headless | `mvn -o -q verify` | 4087 tests, 0 failures, 0 errors, 378 skipped (the live classes among the skips) |
| arm64 image (JDK 25), targets on Java 25 | `ci/run-in-linux.sh mvn -o -q clean verify -Pjcef -Dskillroy.jcef.platform=linux-arm64 -Dskillroy.headless=false` | run 1: stopped at the sample application module after 342 s on one failure, `LendingUiDisplayTest.typedKeysFillTheEntryFieldAndEnterSubmitsIt` (the class then passed 3 of 3 re-runs: a one-off, "Open flake" below); run 2: 4143 / 0 / 0 / 77, 830 s |
| arm64 image, targets on Java 8 (aarch64 JDK 8u504) | the same plus `-Dskillroy.it.targetJava=/opt/jdk8/bin/java` | run 1: 4143 / 0 / 0 / 104, 737 s; run 2: 4143 / 0 / 0 / 104, 729 s |
| arm64 image, agent side, test JVMs and targets on Java 8 | the Java 8 command plus `-Djvm=/opt/jdk8/bin/java`, `-pl` the twelve agent-side modules | run 1: stopped at the agent core module after 34 s on one error, `LifecycleTest.productionMarkerShutsTheAgentDown` (3 of 3 re-runs green: a one-off, below); run 2: 1380 / 0 / 0 / 21, 182 s |
| amd64 image (JDK 25, emulated), targets on Java 25 | the arm64 command with `linux-amd64` and `-Dskillroy.test.extraArgLine=-Djdk.lang.Process.launchMechanism=FORK` | 4143 / 0 / 0 / 76, 1258 s |
| amd64 image, targets on Java 8 (1.8.0_492) | the same plus `-Dskillroy.it.targetJava=/opt/jdk8/bin/java` | 4143 / 0 / 0 / 104, 1030 s |
| amd64 image, agent side, test JVMs and targets on Java 8 | the Java 8 command plus `-Djvm=/opt/jdk8/bin/java`, `-pl` the twelve agent-side modules | 1380 / 0 / 0 / 21, 254 s |

The whole-reactor legs gained scenario C's replay check (eight cases, four load modes and two seeds) and the planner
replay test; `InferenceEndToEndIT` runs scenario D's items against the live recordings now. Two display-free legs of
the first arm64 round stopped on one-off failures in tests this session did not touch (both re-run three times green
and absent from the second round and the amd64 round):

- `LendingUiDisplayTest.typedKeysFillTheEntryFieldAndEnterSubmitsIt` (the sample application's own UI test, Java 25
  test JVM): the typed keys produced no engine event within 30 s, the focus family of "Open flake" (A39 mitigated the
  agent's display tests, not the sample application's harness); first occurrence of this test.
- `LifecycleTest.productionMarkerShutsTheAgentDown` (agent core, Java 8 test JVM): `AgentHarness` read the token file
  after the agent, shut down by the production marker, had deleted it (`NoSuchFileException`; A23's clean-up): a race
  of the harness on a fast JVM; first occurrence. The harness could tolerate a vanished token when the shutdown is the
  expected outcome.



Verification of the final Phase 5 tree (`e7acd61`, the tree after the review's fix wave), 2026-10-08: the arm64 image
natively twice in a row, then the amd64 image under emulation once, three legs each; the counts are the main tree's own
(the summariser now ignores the agents' worktrees and the spikes):

| Configuration | Command (from the repo root) | Result |
|---|---|---|
| Native, JDK 26, headless (`7a0b4c7`) | `mvn -o -q verify` | 3968 tests, 0 failures, 0 errors, 367 skipped (surefire and failsafe reports together; the image-only classes skip) |
| arm64 image (JDK 25), targets on Java 25 | `ci/run-in-linux.sh mvn -o -q clean verify -Pjcef -Dskillroy.jcef.platform=linux-arm64 -Dskillroy.headless=false` | run 1: 4101 / 0 / 0 / 68, 817 s; run 2: stopped at `skillroy-mcp` after 253 s on one error, `ScenarioRunPolicyTest.aRunOnAClientsBehalfEvaluatesExpectationsOnMaskedValues` (the MCP client's 20 s request timeout: a defect of the SDK's stdio transport under load, found, fixed and explained below under "Status at a glance"); on the fixed tree (`7a0b4c7`, `SerialSends`) the leg ran twice more: 4102 / 0 / 0 / 68, 820 s, and 4102 / 0 / 0 / 69, 835 s |
| arm64 image, targets on Java 8 (aarch64 JDK 8u504) | the same plus `-Dskillroy.it.targetJava=/opt/jdk8/bin/java` | run 1: 4101 / 0 / 0 / 96, 737 s; run 2: 4101 / 0 / 0 / 96, 745 s |
| arm64 image, agent side, test JVMs and targets on Java 8 | the Java 8 command plus `-Djvm=/opt/jdk8/bin/java`, `-pl` the twelve agent-side modules | run 1: 1380 / 0 / 0 / 21, 176 s; run 2: 1380 / 0 / 0 / 21, 187 s |
| amd64 image (JDK 25, emulated), targets on Java 25 | the arm64 command with `linux-amd64` and `-Dskillroy.test.extraArgLine=-Djdk.lang.Process.launchMechanism=FORK` | 4101 / 0 / 0 / 68, 1231 s |
| amd64 image, targets on Java 8 (1.8.0_492) | the same plus `-Dskillroy.it.targetJava=/opt/jdk8/bin/java` | 4101 / 0 / 0 / 96, 1063 s |
| amd64 image, agent side, test JVMs and targets on Java 8 | the Java 8 command plus `-Djvm=/opt/jdk8/bin/java`, `-pl` the twelve agent-side modules | 1380 / 0 / 0 / 21, 261 s |

The whole-reactor legs run the end-to-end acceptance of every phase, Phase 5's `InferenceEndToEndIT` included (its 88
cases on the four load modes and two seeds, among them the MCP server as a subprocess, which since the fix wave sends the
allow-list check's statements through the target's `data.query` at start, the session command's export replayed, and
the secret scan last), and the web host's kit against real JCEF off screen and windowed; the agent-side leg runs the
kit's off-screen half on a Java 8 test JVM (item 12 among them).

Verification of the Phase 5 tree, first round (2026-10-08): the committed build (`7e18e0c`) on the arm64 image natively,
then the tree with that round's three fixes (`a6ed6e9`) on the amd64 image under emulation. The arm64 counts of this
round are not recorded: the summariser also counted the test reports of the review agents' worktrees inside the tree
(corrected before the final round); its failures are exact. The final round, on the tree after the review's fix wave,
is the table that follows this one.

| Configuration | Command (from the repo root) | Result |
|---|---|---|
| Native, JDK 26, headless, the committed build `7e18e0c` | `mvn -o -q verify` | 3627 tests, 0 failures, 0 errors, 122 skipped |
| arm64 image (JDK 25), targets on Java 25, `7e18e0c` | `ci/run-in-linux.sh mvn -o -q clean verify -Pjcef -Dskillroy.jcef.platform=linux-arm64 -Dskillroy.headless=false` | 1 failure, 2 errors, 467 s: `InferenceEndToEndIT` (approve with the reuse rule, the session export's replay), fixed in `a6ed6e9` |
| arm64 image, targets on Java 8 (aarch64 JDK 8u504), `7e18e0c` | the same plus `-Dskillroy.it.targetJava=/opt/jdk8/bin/java` | the same three, 467 s |
| arm64 image, agent side, test JVMs and targets on Java 8, `7e18e0c` | the Java 8 command plus `-Djvm=/opt/jdk8/bin/java`, `-pl` the twelve agent-side modules | 1361 tests, 0 failures, 1 error, 8 skipped, 178 s: kit item 12 against real JCEF (the kit's own marker script), fixed in `a6ed6e9`; after the fix the off-screen kit passes 13 of 13 on a Java 8 and on a Java 25 test JVM (run from a worktree) |
| amd64 image (JDK 25, emulated), targets on Java 25, `a6ed6e9` | `ci/run-in-linux.sh mvn -o -q clean verify -Pjcef -Dskillroy.jcef.platform=linux-amd64 -Dskillroy.headless=false` | 4006 / 0 / 0 / 68, 1212 s |
| amd64 image, targets on Java 8 (1.8.0_492), `a6ed6e9` | the same plus `-Dskillroy.it.targetJava=/opt/jdk8/bin/java` | 4006 / 0 / 1 / 68, 688 s: `InferenceEndToEndIT`'s session check could not spawn the CLI ("Bad code from spawn helper", the JDK's `jspawnhelper` under QEMU; the same check passed in the leg above and on arm64): an emulation flake, not a defect; the matrix now runs the amd64 test JVMs with `-Djdk.lang.Process.launchMechanism=FORK` ("Environment notes") |
| amd64 image, agent side, test JVMs and targets on Java 8, `a6ed6e9` | the Java 8 command plus `-Djvm=/opt/jdk8/bin/java`, `-pl` the twelve agent-side modules | 1380 / 0 / 0 / 21, 250 s |

Verification of the Phase 4 tree (2026-10-07). The final tree is the one after the review's fix wave; each Linux leg
runs twice in a row (the exit criterion's "twice in a row without a flake"), on the amd64 image, which an arm64
development machine runs under emulation:

| Configuration | Command (from the repo root) | Result |
|---|---|---|
| Native, JDK 26, headless, the integrated tree before the last implementer and the fix wave | `mvn -o -q verify -Pjcef` | 2513 tests, 0 failures, 0 errors, 290 skipped; 199 s |
| Native, JDK 26, headless, the final tree | `mvn -o -q verify -Pjcef` | 2701 tests, 0 failures, 0 errors, 383 skipped; 222 s |
| Linux image (JDK 25), targets on Java 25 | `ci/run-in-linux.sh mvn -o -q clean verify -Pjcef -Dskillroy.jcef.platform=linux-amd64 -Dskillroy.headless=false` | run 1: 2725 / 0 / 0 / 17, 1094 s; run 2: 2725 / 0 / 0 / 16, 1057 s |
| Linux image, targets on Java 8 | `ci/run-in-linux.sh mvn -o -q verify` with the same flags, plus `-Dskillroy.it.targetJava=/opt/jdk8/bin/java` | run 1: 2725 / 0 / 0 / 44, 919 s; run 2: 2725 / 0 / 0 / 44, 911 s |
| Linux image, agent side, test JVMs and targets on Java 8 | the Java 8 command plus `-Djvm=/opt/jdk8/bin/java`, `-pl` the twelve agent-side modules: the nine of the earlier tables, `skillroy-agent-web`, `skillroy-sample-web-host` and `skillroy-sample-web-host-agent` | run 1: 1342 / 0 / 0 / 20, 246 s; run 2, first attempt: one failure, the open focus flake (`WindowActivationDisplayTest.activateWindowOnTheFocusedWindowJustClicks`, third occurrence, 30 s wait for the frame to be the focused window; the build stopped at the Swing module after 153 s with 875 tests run); second attempt: 1342 / 0 / 0 / 20, 252 s |

The native skips are the display tests and the end-to-end checks, which need the Linux image. The web acceptance
(`WebHostEndToEndIT`, `CdpAttachSoakIT` and the kit against real JCEF) runs in the first two Linux legs, the kit's
off-screen half on a Java 8 test JVM in the third.

Verification of the committed tree (`efb48b7`, the Phase 3 tree with the 2026-10-06 changes), on 2026-10-06:

| Configuration | Command (from the repo root) | Result |
|---|---|---|
| Linux image (JDK 25.0.4.1), targets on Java 25 | `ci/run-in-linux.sh mvn -o -q clean verify -Dskillroy.headless=false` | 2156 / 0 / 0 / 4, 587 s, run once |
| Linux image, targets on Java 8 (1.8.0_492) | the same plus `-Dskillroy.it.targetJava=/opt/jdk8/bin/java` | 2156 / 0 / 0 / 4, 557 s, run once |
| Linux image, agent-side modules, test JVMs and targets on Java 8 | the Java 8 command plus `-Djvm=/opt/jdk8/bin/java`, `-pl` the nine agent-side modules | first run: one failure, `WindowActivationDisplayTest.activateWindowOnTheFocusedWindowJustClicks` ("timed out after 30000 ms waiting for the test frame is the focused window"; the build stopped at the Swing module after 164 s with 785 tests run); second run: 1038 / 0 / 0 / 4, 188 s |

The two whole-reactor legs gained two tests since the Phase 3 runs (2154 -> 2156: the `masking.minLengthInText` tests of
2026-10-06). The four skips are the usual ones. The agent-side failure is the second occurrence of the display-test focus
flake (details under "Open flake" below); the machine was otherwise idle apart from the emulated container itself.

Verification of the merged main tree after Phase 2, on 2026-10-03 (before and after the review's fixes):

| Configuration | Command (from the repo root) | Result |
|---|---|---|
| Native, JDK 26, headless, before the fixes | `mvn -o -q verify` | 1877 tests, 0 failures, 0 errors, 224 skipped; 171 s |
| Linux image (JDK 25.0.4.1), targets on Java 25, before the fixes | `ci/run-in-linux.sh mvn -o -q clean verify -Dskillroy.headless=false` | 1897 / 0 / 0 / 5 skipped, 549 s (the fifth skip: a Swing display test that aborts when a disposed button is not garbage-collected within 20 s) |
| Linux image, targets on Java 8 (1.8.0_492), before the fixes | the same plus `-Dskillroy.it.targetJava=/opt/jdk8/bin/java` | first run: one failure, `RpcRetryTest` (a controller unit test with a fake agent; it asserted one of two valid "agent lost" wordings, whose choice depends on timing inside the JDK HTTP client; fixed in the test), build stopped at the controller module after 207 s; second run, `clean verify`: 1897 / 0 / 0 / 4, 516 s |
| Linux image, agent-side modules, test JVMs and targets on Java 8, before the fixes | the Java 8 command plus `-Djvm=/opt/jdk8/bin/java`, `-pl` the nine agent-side modules | 1000 / 0 / 0 / 4, 175 s |
| Native, JDK 26, headless, after the fixes | `mvn -o -q verify` | 1938 tests, 0 failures, 0 errors, 236 skipped; 172 s |
| Linux image, targets on Java 25, after the fixes | `ci/run-in-linux.sh mvn -o -q clean verify -Dskillroy.headless=false` | 1958 / 0 / 0 / 4, 538 s, run once |
| Linux image, targets on Java 8, after the fixes | the same plus `-Dskillroy.it.targetJava=/opt/jdk8/bin/java` | 1958 / 0 / 0 / 4, 491 s, run once |
| Linux image, agent-side modules, test JVMs and targets on Java 8, after the fixes | the Java 8 command plus `-Djvm=/opt/jdk8/bin/java`, `-pl` the nine agent-side modules | 1011 / 0 / 0 / 4, 177 s, run once |

The 61 tests the fixes added to the Linux legs are the review's new tests, among them the end-to-end JDBC block
items 4-7, the Q11 and A34 checks of `ResolutionChecks` and the agent-core event tests. The four skips are the usual
ones (two headless-only Swing tests, the Java 9+ process-id branch, the second-OS-user branch). "Twice in a row
without a flake" holds for the Java 25 leg over the two runs of the day (before and after the fixes, 549 s and
538 s) and for the agent-side leg (175 s and 177 s); the Java 8 targets leg passed its two complete runs (516 s before
the fixes, 491 s after) after its first run of the day stopped on the `RpcRetryTest` flake that is fixed since.

Verification after the port and the Java 25 switch (2026-10-02):

| Configuration | Command (from the repo root) | Result |
|---|---|---|
| Native, JDK 26, headless | `mvn -o -q verify` | 1304 tests (1239 surefire + 65 failsafe), 0 failures, 0 errors, 110 skipped (display tests, end-to-end checks) |
| Linux image (JDK 25.0.4.1, WM-less Xvfb), targets on Java 25 | `ci/run-in-linux.sh mvn -o -q clean verify -Dskillroy.headless=false` | 1316 / 0 / 0 / 2 skipped, 407 s, run once |
| Linux image, targets on Java 8 (1.8.0_492) | `ci/run-in-linux.sh mvn -o -q verify -Dskillroy.headless=false -Dskillroy.it.targetJava=/opt/jdk8/bin/java` (no `clean`: on the build output of the run above) | 1316 / 0 / 0 / 2 skipped, 467 s, run once |
| Linux image, agent-side modules, test JVMs and targets on Java 8 | the Java 8 command plus `-Djvm=/opt/jdk8/bin/java`, `-pl` the nine agent-side modules | three runs: the first failed one test and stopped at the Swing module (447 tests run); the next two were green, 804 / 0 / 0 / 2 skipped (151 s, 155 s) |

Verification of the merged tree after Phase 1.x (2026-10-02, later the same day):

| Configuration | Command (from the repo root) | Result |
|---|---|---|
| Native, JDK 26, headless | `mvn -o -q verify` | 1581 tests, 0 failures, 0 errors, 158 skipped; 2 min 39 s |
| Linux image, targets on Java 25 | `ci/run-in-linux.sh mvn -o -q clean verify -Dskillroy.headless=false` | 1593 / 0 / 0 / 4 (463 s); run again after the test fixes: 1601 / 0 / 0 / 4 (435 s) |
| Linux image, targets on Java 8 | the same plus `-Dskillroy.it.targetJava=/opt/jdk8/bin/java` | first run: one error (the prompt-view race in a sample-app unit test, fixed); after the fix: 1601 / 0 / 0 / 4 (451 s) |
| Linux image, agent-side modules, test JVMs and targets on Java 8 | the Java 8 command plus `-Djvm=/opt/jdk8/bin/java`, `-pl` the nine agent-side modules | first run: one error (the HTTP-budget test, fixed); second: one failure (the child-JVM output read, fixed); third: 949 / 0 / 0 / 4 (176 s) |

The 4 skips in the image are the two headless-only Swing tests, a core test that needs Java 9 or newer on the test JVM,
and a discovery-directory test that needs a second OS user. On a Java 8 target the `basic-loan` scenario now takes
about 0.3 s in every load mode (0.24-0.38 s measured), as on Java 25: the per-call stall is gone (H1).

The 2 skips in the image are the two headless-only tests of the Swing module. Java versions used: 26.0.2 (native),
25.0.4.1 (image), 1.8.0_492 (mounted JDK 8). The image runs x86-64 under emulation on the development machine, so
the times are slow. Java 21 targets are no longer run (B3).

- **Open flake (three occurrences on a Java 8 test JVM, 2026-10-02, 2026-10-06 and 2026-10-07; on 2026-10-07 four
  more in other tests, on a Java 25 test JVM in the arm64 image).** In the first agent-side run on
  Java 8 of 2026-10-02,
  `HostSafetyDisplayTest.componentRungClicksOnlyWithFocusAndAlwaysRemovesItsListener` failed with "timed out after
  30000 ms waiting for the test frame is the focused window", and the build stopped at the Swing module. It did not
  repeat in the next two runs. The machine was running other builds at the time. The cause has not been
  investigated, so this is an open flake, not "no flake". **On 2026-10-06** the agent-side leg failed the same way once
  more, in another test: `WindowActivationDisplayTest.activateWindowOnTheFocusedWindowJustClicks` waited 30 s for the test
  frame to be the focused window after `toFront()` + `requestFocus()` on the EDT; the preceding test had shown and
  disposed a second frame ("Focus thief") that held the focus. Its BS4 report in the same JVM shows the same activation
  succeeding in 0-2 ms a dozen times before. The rerun was green. Pattern so far: both failures are on the Java 8 test
  JVM under the WM-less Xvfb, both wait for a frame to become the focused window after another window held or took the
  focus, and the Java 25 test JVMs have never shown it (six whole-reactor legs since 2026-10-02). Hypotheses to check
  when it is investigated: an `XSetInputFocus` ignored by the X server because the Java 8 toolkit passes a stale
  timestamp after the focused window was destroyed, or a `FocusIn` event the toolkit drops; the test-side mitigation
  would be to re-issue `toFront()` + `requestFocus()` once a second while waiting, and the same retry could join the
  `COMPONENT` rung's window activation (a small amendment, not taken here). **On 2026-10-07**, in the Linux runs of the
  Phase 4 acceptance on its implementer's copy, two other display tests of the Swing module failed on focus in 4 of 7
  runs on the arm64 build of the image, which runs natively: `ModalBlockingDisplayTest` and
  `ComponentFallbackDisplayTest`; each passed alone and in the reruns. Those runs had a Java 25 test JVM, so the
  pattern above no longer holds for Java 8 alone: what the failures share is a frame that must become the focused
  window under the WM-less Xvfb; the faster arm64 image may make the race likelier. Not a flake, and recorded here
  because it looked like one: the `AttachIT` failures of the CLI module (a 401 on `/ping`) in partial builds that day
  were the stale-snapshot build trap under "Environment notes", fixed in the build.
  **Third occurrence, 2026-10-07:** `WindowActivationDisplayTest.activateWindowOnTheFocusedWindowJustClicks` failed
  the same way in the second round of the Phase 4 matrix (the agent-side leg on the Java 8 test JVM stopped at the
  Swing module after 153 s with 875 tests run); the rerun is recorded in the Phase 4 table. The Java 25 test JVMs of
  the amd64 image have still never shown it.
  **Investigated and mitigated 2026-10-08 (A39).** Reproduced in both images with the X toolkit's own focus log
  (`sun.awt.X11.focus` and `java.awt.focus` at FINEST): 14 failed runs of 50 of the nine display classes (arm64 Java
  25 4/20, arm64 Java 8 8/20, amd64 Java 8 2/10), **0 of 60 after the change**. Not a stale timestamp and no dropped
  event. Under the WM-less Xvfb an activation is a single `XSetInputFocus` (`toFront()` only raises without `WM_STATE`,
  since no window manager ever sets it), and it was lost in three ways: (1) a frame shown a few milliseconds after
  another loses the focus to that frame's own activation tail (when the focus moves to the frame's first component
  the toolkit issues one more `XSetInputFocus`; the last request wins, the state ends consistent, nothing re-issues);
  (2) on the Java 8 test JVM, an activation issued on the EDT after the focus holder was disposed is not delivered
  until the next X round trip from the EDT (a re-issue one second later finds the focus already there and its
  `FocusIn` arrives with it; why the idle toolkit thread does not flush within its poll cadence is not established,
  the native sources not being at hand); (3) `ModalBlockingDisplayTest`'s "forced key delivered" is (1) seen from the
  key side: the frame's first field never gained the focus, so the field's ENTER action was disabled. Every failure
  but (2) was the first test of its class. Mitigation: `DisplayTestBase.activate` and `awaitFocusedWindow` re-issue
  `toFront()` + `requestFocus()` once a second within the unchanged 30 s wait, every display test uses them, and the
  class set-up waits for the frame to be focused and settles; the `COMPONENT` rung's window activation re-issues the
  same way within `focusTimeoutMs` (`design/phase1-contracts.md` §6, amendment A39; `WindowActivationRetryTest`).
  The two key focus logs and the run summaries are kept with the session notes. Open: the native reason for (2); the
  garbage-collection-dependent skip of `SwingDisplayTest.refBecomesStaleOnceTheComponentIsCollected`, seen in 3 of 60
  runs.
- **A transport defect that looked like a flake (one occurrence in the matrix, 2026-10-08; once more natively during
  the fix wave): found and fixed.** In the third arm64 run of the final Phase 5 tree the whole-reactor leg stopped at
  `skillroy-mcp`: `ScenarioRunPolicyTest.aRunOnAClientsBehalfEvaluatesExpectationsOnMaskedValues` ended with the MCP
  Java SDK client's 20 s request timeout while `skillroy_scenario_run` ran a plan in process. The investigation
  reproduced it under CPU load (3 of 15 runs; 0 of 14 without load) and found the cause in the SDK's stdio transport
  (2.0.1): its outgoing sink refuses a second thread while another is still putting a message in, drops that message
  ("Failed to enqueue message") and, when the dropped message is an answer, ends the transport's loop without a word;
  the server sends from two kinds of thread (the tool call's thread: the answer and one `resources/list_changed` per
  resource a run registers; the SDK's reading thread: the answers to `ping`, `tools/list`, `resources/list`), so under
  load even alternating calls collide. The server-side call itself never took more than 272 ms over 844 calls. Fixed
  by `SerialSends`, a wrapper that serialises every session's sends under one lock (`SkillroyMcpServer.transport`);
  `SerialSendsTest` fails 5 of 5 without the wrapper and passes 20 of 20 under load with it; the two classes then ran
  20 times under load with 0 unanswered requests and 0 drops. Worth reporting upstream; the wrapper goes when the SDK
  fixes it. The leg was run twice more on the fixed tree, green both times (table above).
- **First run on a Java 25 target.** The end-to-end acceptance passed in every load-mode variant on a Java 25
  target and on a Java 8 target. With it the B4 gate and the Phase 1 exit item "target on Java 25" are met, and
  BS1's "Java 25 target" is answered for Linux: `JAVAAGENT`, `JAVAAGENT` through `JAVA_TOOL_OPTIONS`, `ATTACH` and
  `EMBEDDED` all end in a verified handshake.
- **Timing seen in the end-to-end test.** `basic-loan` took about 0.3 s on a Java 25 target and about 2.0 s on a
  Java 8 target (`basic-loan-ui` 0.6 s and 2.9 s). The per-call stall of the JDK's built-in HTTP server (the BS2
  finding) was therefore absent on JDK 25 and present on Java 8. With the agent's own listener (H1, Phase 1.x) a
  call costs about 1.4-5 ms on either target and `basic-loan` about 0.2-0.3 s on Java 8
  (`design/phase1-implementation-notes.md`).
- **Before the changes** (first sample application, controller on Java 21; kept as measurements, not re-run):
  native 1303 / 0 / 0 / 110; in the image on Java 21 targets and on Java 8 targets 1315 / 0 / 0 / 2, twice each;
  agent-side modules on a Java 8 test JVM 803 / 0 / 0 / 2, twice; no flake in those runs.

Modules and what their tests are. Per-module counts were not re-collected after the port; the totals are in the
table above. "Java 8 test JVM" = the module's own tests also run on Java 8 (`-Djvm`).

| Module | Built | Java 8 test JVM | What the tests are |
|---|---|---|---|
| `skillroy-protocol` | yes | yes | codec, wire types, filters; adversarial JSON |
| `skillroy-agent-api` | yes | yes | spec builder, SPI value types |
| `skillroy-agent-bootstrap` | yes | yes | options, enable rule, loader, embedded start |
| `skillroy-agent-core` | yes | yes | server, dispatch, limits, redaction, discovery directory, readiness, SSE |
| `skillroy-agent-swing` | yes | yes | snapshot, locators, rungs, modal blocking, re-shown windows, host safety, focus spike; two headless-only tests |
| `skillroy-agent-data` | yes | yes | statement guard corpus, JDBC and provider forms, value conversion |
| `skillroy-agent-dist` | yes | yes | integration tests with real child JVMs: host safety, embedded start |
| `skillroy-controller` | yes | targets only | client, SSE, discovery, attach, launcher against child JVMs on the target Java of the run |
| `skillroy-scenario` | yes | n/a (Java 25) | loader, compiler, executor, matchers, reporter |
| `skillroy-junit` | yes | n/a | dynamic tests, result mapping |
| `skillroy-cli` | yes | targets only | every command; the launcher; attach of the real agent (unit and integration tests) |
| `skillroy-sample-lending-app` | yes | yes | engine, access control, deposits, waiver rules, slip printer, database, UI |
| `skillroy-sample-lending-agent` | yes | yes | module, primitives, listener slot, widgets, plugin |
| `skillroy-sample-lending-pack` | yes | n/a | verbs, facts, recovery |
| `skillroy-sample-lending-scenarios` | yes | targets only | committed plans up to date (4 plan tests); §10 acceptance, 19 checks per load mode, 21 tests with the JUnit glue's three dynamic tests, 84 end-to-end tests (`LendingEndToEndIT`) |
| `skillroy-agent-web` (Phase 4) | yes | yes | the module (install, capabilities, index, selection, primitives, events and taps) against scripted adapters; both adapters against the fake JCEF and the JxBrowser fake; the contract kit (`test-jar`) in four fake runs; 160 tests after the fix wave, 172 after the Phase 4.x amendments A37 and A38 (2026-10-08) |
| `skillroy-cdp` (Phase 4) | yes | n/a (Java 25) | the probe against a fake DevTools server, the attach and the path with Playwright replaced by fakes; no test starts a driver or a browser; 105 tests |
| `skillroy-sample-web-host`, `-web-host-agent` (Phase 4, profile `jcef`) | yes | yes | options, sentinels, pages, registry, the agent module's seeds; with a display the host itself and the kit against real JCEF, one surefire execution per rendering mode |
| `skillroy-sample-web-host-scenarios` (Phase 4, profile `jcef`) | yes | targets only | committed plans up to date, the pack; with a display the end-to-end acceptance (`WebHostEndToEndIT`, eight launches) and the frozen-target soak (`CdpAttachSoakIT`) |

| Area | State | Next step |
|---|---|---|
| Repo split | Done 2026-10-01. This repo holds only tenant-neutral material; after the port the neutrality check printed "base repo is clean" | Run it again after the documentation update and before every commit |
| Sample application | Replaced 2026-10-02 (B10): the sample lending desk, modules `skillroy-sample-lending-{app,agent,pack,scenarios}`, domain id `lending`, scenarios `basic-loan` and `basic-loan-ui`. The whole code base and the docs were swept for the first sample's vocabulary. Since Phase 4 (2026-10-07) a second sample target, the JCEF web host (`skillroy-sample-web-host`, `-web-host-agent`, `-web-host-scenarios`, behind the Maven profile `jcef`), hosts embedded browsers for the web acceptance | None (the owner kept the desk as it is, 2026-10-06) |
| Platform | Controller side on Java 25 (`--release 25`, B3); agent side on Java 8 (B4). The Linux test image is JDK 25 (Temurin 25.0.4.1); the previous JDK 21 image is no longer used. Native development JDK is 26 | none |
| Git and publication | The initial commit was made on 2026-10-06 (everything in the tree, one commit, DCO sign-off); nothing pushed. The remote `Skillroy-AI/skillroy-test-java` is private for now. `LICENSE` (Apache-2.0), `NOTICE`, `CONTRIBUTING.md` (DCO sign-off, no CLA) and the header in every source file (`ci/license-headers.sh`, with the holder's name since 2026-10-06) exist; the root POM carries the publication metadata | Push when the owner says; before the first public release, the repeated security review |
| Licence audit | `docs/licence-audit.md`: everything the project distributes, bundles or depends on is compatible with Apache-2.0. The packaging gaps (licence files inside the jars and zips, third-party attribution in the CLI distribution, a `<licenses>` element in the root pom) are closed (Phase 1.x, built 2026-10-02). Updated 2026-10-07 for Phase 4: the Playwright and JCEF sets (in no distribution), the new modules, and the agent home's seventh jar, inspected | none |
| Decisions | B1-B14 answered by the owner 2026-10-02; B15 and B16 follow from the answers; B17-B20, taken without the owner, were confirmed by the owner on 2026-10-06, with two vetoes inside B19 (Q10, Q14) built the same day; **B21** (the Phase 4 contract's fifteen answers) taken without the owner later on 2026-10-06 and confirmed by the owner on 2026-10-07; **B22** (D59-D69, the Phase 4 build's decisions) confirmed the same evening. **B23** (the Phase 5 contract's eighteen answers, D70-D87) and **B24** (the build's and the review's decisions, D88-D104, D110) taken without the owner on 2026-10-08 and confirmed by the owner the same day; **B25** (the live session's D111-D113) taken without the owner in the afternoon of 2026-10-08 and confirmed by the owner on 2026-10-09 | None open; the owner's review of the committed scenario C |
| Design docs (`docs/design/`) | `phase1-contracts.md` as built, with the Phase 1.x, Phase 2, Phase 3 and Phase 4 passages marked "(1.x, built 2026-10-02)", "(Phase 2, built 2026-10-02)", "(Phase 3, built 2026-10-03)" and "(Phase 4, built 2026-10-07)". `phase2-contracts.md` and `phase3-contracts.md` as built (2026-10-03: every deviation of the build and every fix of the review marked in place; their Phase 4 passages marked built). **`phase4-contracts.md` as built 2026-10-07** (specified 2026-10-06; every deviation of the build and every fix of the review marked in place, the decisions of the build D59-D69). **`phase5-contracts.md` as built 2026-10-08** (specified, built, reviewed and fixed the same day, its §16.1 marks written into the Phase 1-4 contracts and the overview; the live session's passages marked "the live session", D111-D113). Overview: roadmap with Phases 1.x to 5 built, §3.8 as built | None |
| Research notes (`docs/research/`) | Round 1 carried over, finding ids unchanged; `embedded-browser-spi` and `vision-input` new (2026-10-02). Pruned of local-environment detail | None |
| Security review | Run 2026-10-02 on the pre-port tree: 34 confirmed findings (4 medium, 30 low); the fixes H1-H3 are built (Phase 1.x). The Phase 2 code review of 2026-10-03 had a secrets-and-safety lens (below) | Repeat the review before the first release |
| Spikes | BS7 done. **BS8 run 2026-10-06, passed with conditions** (`spikes/BS8/RESULT.md`). **BS5 met 2026-10-07** by the Phase 4 acceptance against JCEF's Chromium (D53; the stock-Chromium run is the manual procedure `spikes/BS5/README.md`). BS1 answered for Java 8 and Java 25 targets on Linux, parts open. BS2 answered in part. BS4 answered, parts open. BS3 open. **BS6 passed 2026-10-08** and **BS13 passed 2026-10-08** (the offline part in the night, the live part in the afternoon with the owner's key), both as the Phase 5 contract's gates. BS9-BS12 new, not started | See "Base spikes" |
| CI for this repo | Not chosen. `ci/Dockerfile` and `ci/run-in-linux.sh` are what a pipeline would run; since 2026-10-06 the image holds Chromium's run-time libraries and the runner takes `SKILLROY_PLATFORM`; the Phase 4 matrix adds `-Pjcef` to every leg | Needs a JDK 8, a JDK >= 25 and Xvfb on the build agents; for the profile `jcef` the JCEF natives (152 MB per platform) and Playwright's jars (the driver bundle about 204 MB) in the Maven cache |

### What the end-to-end review fixed (2026-10-02)

Found and fixed before the port, on the first sample application; the fixes carried over to the lending desk.
Three defects in main code were fixed, and the end-to-end tests were tightened:

1. The wire codec read some numbers differently on Java 8 (its `Double.parseDouble` reports certain in-range
   numbers as infinite). The agent-side build on Java 8 was red because of it. Fixed in `Json`.
2. A prompt left open in the sample application made every later agent and scenario unusable: an agent that
   attached while the application was asking a question never became ready, so nothing but a person could unblock
   it. The module is now ready with a prompt open (contract §9.2).
3. A race on the sample application's listener list between an agent being disabled and the next one attaching.
   All changes of that list are now serialised JVM-wide. There is no deterministic regression test for the race
   itself (two threads would have to collide within microseconds); it is covered by the end-to-end sequence only.
4. Tests: a retry that could hide a failed attach was removed; the Java 8 attach reply, the target's Java home and
   pid, the COMPONENT rung (now with a positive control) and `agent.disable` (now by thread id) are asserted
   strictly; the agent and the controller are proven to agree on the default discovery directory with the real
   agent.

### Security review (2026-10-02)

The cross-cutting review that B13 and the B1 checklist asked for, run on the pre-port tree: six lenses (the control
channel, token handling, the discovery directory, the safety gates, host safety, the controller's handling of what
a target sends back), each finding verified by an independent sceptic. 35 raw findings: 34 confirmed (4 medium, 30
low; none high or critical), 1 rejected. The confirmed findings are S1-S34; `DECISIONS.md` B13 summarises them and
`design/phase1-contracts.md` carries their dispositions.

- The four medium findings: the controller sends the bearer token to whatever listens on the remembered port (S1);
  unauthenticated requests can pin large strings in the agent's log ring (S2); the request head is read without a
  size limit before the token check on Java 8 (S3); unauthenticated clients can exhaust file descriptors, and heap
  on Java 8, through the JDK HTTP server (S4).
- Dispositions, all Phase 1.x and all built on 2026-10-02: **H1**, the agent's own HTTP listener, which also
  decides review item R1 as option (c); **H2**, HMAC request and reply authentication, wire protocol 0.2; **H3**,
  every other finding fixed per its verified fix (where the build deviates from a verified fix,
  `design/phase1-contract-amendments.md` says so). Nothing was accepted as a known risk.
- The Windows-specific fixes (S7, S15: access control on file systems without POSIX permissions) are built and
  unit-tested on built ACL values, but cannot be verified without a Windows host: not verified on Windows.
- The review is to be repeated before the first release.

## Base spikes

Results so far come from the Phase 1 tests, the end-to-end review and the Phase 4 acceptance; `spikes/BS8/` holds
BS8's probe and write-up and `spikes/BS5/README.md` BS5's manual procedure.
Thresholds marked "proposed" are starting points, not facts from research. Research references such as
`attach F1` mean finding `F1` in `docs/research/attach-instrumentation.md`. Measurements marked "before the switch"
were made with the first sample application and the controller on Java 21; they stay as measurements and are not
re-run. BS8 comes from `research/embedded-browser-spi.md` R4 (B9); BS9-BS12 from `research/vision-input.md` R10
(B16), which numbers them BS8-BS11.

| Id | Proves | Status |
|---|---|---|
| BS1 | The agent loads into stock JVMs in all load modes, across JDK versions | **Answered for Java 8 and Java 25 targets on Linux** (Java 21 targets before the switch). Open: cross-user attach and jattach, Windows |
| BS2 | The control channel survives a soak; the agent is isolated from the host class path | **Answered in part**: 3-minute soak per JDK (before the switch, on the JDK server), bounds and isolation tested; the agent's own listener (H1) is built and its per-call cost measured (1.4-5 ms). Open: the one-hour soak, loader collectability, a soak on the agent's own listener. Finding: about 45 ms per call on the JDK HTTP server on Java 8 and Java 21 targets; absent on a Java 25 target; gone with H1 |
| BS3 | Byte Buddy retransformation with inline Advice and a bootstrap `Spy`; original bytes restored on detach | **Passed with conditions 2026-10-09** (`spikes/BS3/RESULT.md`): on Java 8 and Java 25 targets in the arm64 image, `JAVAAGENT` and `ATTACH`, a hook on the sample's `AccessControl.check` and on a toy class in a custom loader installs in 111-197 ms (later ones 6-23 ms), fires on the host's thread in `OBSERVE`, `CAPTURE` and `STUB`, switches mode without a retransform and restores byte for byte; Byte Buddy 1.18.14 shaded under the project prefix coexists with a host's Byte Buddy 1.12.19, ASM 5.0.3 and JNA 4.5.2; 17 configurations. Conditions: Byte Buddy injects `Nexus` (and on Java 25 a `java.lang` accessor with an `Unsafe` warning) unless its relocated `nexus.disabled` property is set; each bootstrap append prints a class-data-sharing warning on Java 25 (append once per JVM); the byte check holds by construction, so the engine also counts transform events it did not cause; a loader that delegates only `java.*` cannot see `Spy` (probe and refuse, or a `java.*`-package `Spy`); every retransform is a stop-the-world pause (size limit proposed: 256 KiB and 2,000 methods); maven-shade-plugin 3.6.2 or newer |
| BS4 | Which Swing input rungs work on an Xvfb display with no window manager | **Answered** for Java 8 and 21 (before the switch); the end-to-end check built on it passed on Java 8 and Java 25 targets. Open: the ROBOT rung (not built; see BS9), a large-sample focus statistic, the display-test flake |
| BS5 | Playwright attaches over CDP to an already-running Chromium safely | **Met 2026-10-07 by the Phase 4 acceptance** against JCEF's Chromium in the image (D53): 50 of 50 attach attempts against a frozen page ended with `FROZEN_TARGETS` within the deadline, the slowest in about 2.02 s. A stock-Chromium run is a manual procedure, `spikes/BS5/README.md` |
| BS6 | `SqlGuard` accepts and rejects correctly across SQL dialects | **Passed 2026-10-08** (`spikes/BS6/RESULT.md`): a prototype guard on JSqlParser 5.4 accepts all 264 accept statements and refuses all 382 reject statements with the reason named, on HSQLDB, MySQL, MariaDB, PostgreSQL, DB2, Oracle, SQL Server and generic SQL; all 41 HSQLDB rewrites execute on the embedded database; about 0.1 ms per statement; 741 tests. It found holes in the contract's first rules (database links, dropped comments, ambiguous literals, correlated names, parenthesis depth, a tie-breaker rule that gave invalid SQL after `GROUP BY`), now corrected in P5 §5.3. Open: the other dialects' accept corpora on real servers before production use; Oracle 11g unavailable. The agent-side statement guard of `data.query` is a different, simpler check and is built |
| BS7 | `javac --release 8` still works on current JDKs | Done 2026-10-01 |
| BS8 | A real JCEF browser runs in the Linux test image and the in-process DevTools path works | **Run 2026-10-06, passed with conditions**: both rendering modes on a Java 25 target, off-screen on a Java 8 target; the DevTools channel verified; Chromium 152's first-run terms dialog must be pre-accepted; `Target.getTargetInfo` does not answer (the main frame id is the target id); windowed JCEF on Java 8 creates no browser. `spikes/BS8/RESULT.md`. Since Phase 4 (2026-10-07): the timeouts and the unanswered `Target.*` calls were JCEF's client losing an answer that arrives early, which the `jcef` adapter avoids (D69) |
| BS9 | Robot input on the test display and on a desktop session | New, not started; comes before Phase 7 |
| BS10 | Routed in-JVM dispatch behaves like Robot | New, not started |
| BS11 | `PAINT` capture is deterministic | New, not started |
| BS12 | Grounding accuracy and cost of a vision loop on the sample | New, not started; needs live model calls (B7) |
| BS13 | The Claude API facts the Phase 5 contract relies on (structured output, strict tools, effort, caching minimums and TTLs, token counting, the Batch API, the fallback parameter, error mapping, the `claude` CLI's headless envelope), checked live once; the three Phase 5 dependency sets resolved and listed for the licence audit | **Passed: offline part 2026-10-07/08, live part 2026-10-08 (afternoon)** (`spikes/BS13/RESULT.md`): the live part ran through SDK 2.70.0 (the build's line) with the owner's workspace-scoped key, USD 0.032 in all (the resumed CLI call reports the session total, counted once), the `claude` CLI item natively. Settled: structured output as specified (a numeric `minimum` and `minItems` 2 refused, a string `maxLength` accepted; 2.70.0's typed form keeps `effort`); the strict tool called with valid input, a forced tool choice refused; Opus 5.5 returns a thinking block (empty, signed) at effort low, Sonnet 5.5 none; a 1135-token prefix written then read, the one-hour TTL without a beta header; `count_tokens` exact; a batch of two ended after 458 s with its results in reverse order, the second request reading the first's one-hour prefix (not a standard request's entry); `fallbacks` needs the beta header and `usage.iterations` is present whenever it is sent; 404/400 as mapped; the CLI's envelope with `structured_output`, `is_error: false`, `--resume` keeping the session. Found beside the probe: **Haiku 4.5 refuses `output_config.effort`**, so the gateway sends none to the Haiku class (P5 §4.1). The offline findings stand (SDK MIT, `mcp-core` + `mcp-json-jackson2`, per-attempt retries, `fromEnv()`'s reach, the error classes) |

### BS1 Attach matrix on stock JVMs

- **Proven on 2026-10-02** (controller on JDK 25.0.4.1 in the Linux image; targets Java 25.0.4.1 and 1.8.0_492):
  - All four load-mode variants end in a verified handshake on both targets, as part of the end-to-end acceptance
    (52 of 52 checks per target). Check 1 asserts the attach reply that a Java 8 target must show, so the handling
    of the `0` reply (below) held with the JDK 25 attach client.
- **Proven before the switch** (controller on Java 21.0.12, Linux x86-64 under emulation; targets Java 1.8.0_492
  and Java 21.0.12):
  - All four load-mode variants end in a verified handshake with the same primitives: `JAVAAGENT`, `JAVAAGENT`
    through `JAVA_TOOL_OPTIONS` with the main-class guard, `ATTACH`, `EMBEDDED`. The capability matrices differ only
    in `jvm.instrumentation` (`UNAVAILABLE` in `EMBEDDED`).
  - The `0` reply: a Java 8 target makes the JDK 21 client throw `AgentLoadException: Failed to load agent library: 0`
    although `agentmain` ran. It is accepted only for targets of Java 8 or older and the handshake confirms. A Java
    21 target returns normally. Both are assertions in the end-to-end test.
  - Argument cap, measured with a probe agent: `<jar path>=<options>` of up to 1024 characters arrives intact on
    Java 8 **and** Java 21 targets; from 1025 `loadAgent` fails with `IOException` and the agent is never called.
    A refusal, not a truncation. `AttachSession` refuses 1024 bytes or more before attaching.
  - Start-up signal hazard: a raw SIGQUIT kills a JVM spawned moments earlier (Java 8: 18 of 18 when it arrived
    0-80 ms after spawn, survives from 120 ms; Java 21: 8 of 9 at 0-10 ms, survives from 20 ms). Through the JDK
    21 Linux attach client it did not occur in 78 attaches made 0-2000 ms after spawn: that client waits until the
    target catches the signal. `AttachSession`'s 5 s minimum target age is a safety net for clients without that
    check.
  - JEP 451 on a Java 21 target: dynamic loading works by default and prints the four-line warning on the target's
    stderr at every load; with `-XX:+EnableDynamicAgentLoading` there is no warning; with
    `-XX:-EnableDynamicAgentLoading` the load is refused and the CLI reports the JVM's message (exit 3).
    `-javaagent`, `JAVA_TOOL_OPTIONS` and `EMBEDDED` print no warning. A Java 8 target prints nothing.
  - Clear failures for `-XX:+DisableAttachMechanism`, a pid that is not a JVM (refused before any signal is sent)
    and a dead pid. Natively the CLI attach test ran against a Java 26 target (headless child).
- **Already shown in round 1 (toy programs).** The same-user rule in both directions (attach F5); jattach 2.2 as
  root into a non-root JDK 8 JVM (F6).
- **Not shown.** On a Java 25 target: the JEP 451 warning and the refusal under `-XX:-EnableDynamicAgentLoading`,
  the argument cap and the start-up signal hazard (no measurement after the switch). A different-user attach and
  jattach through the framework's own `AttachSession`; a deliberately broken agent jar reported with its code end
  to end (the reply mapping is unit-tested only); `hook.*` capabilities (no hooks module yet); Windows.

### BS2 Control-channel soak and class-loader isolation

- **Proven before the switch.**
  - Soak, 3 minutes per JDK against the first sample application (8 threads calling ten different primitives, one
    thread completing desk transactions with event waits, two SSE clients). Java 8 target: 32,988 calls (183/s), 0
    errors, 280 completed transactions, 9,459 events published and all received in order by the unfiltered stream
    (no gap frame), 560 of 560 by the filtered stream, agent threads 18 -> 34 (bounded pools), heap 48 -> 46 MB.
    Java 21 target: 32,836 calls (182/s), 0 errors, 9,443 of 9,443 events, threads 6 -> 27, heap 13 -> 28 MB.
  - Latency: p50 44 ms, p95 52-53 ms, p99 69-73 ms, max 299-408 ms. The proposed limit (p99 under 100 ms with
    streams open) is met.
  - SSE: resume by `Last-Event-ID` and `since`, gap frames, heartbeat, a slow client caught up from the ring
    (unit tests on both sides; by hand, resume returned the same 39 events).
  - Pool bounds: the thread cap without a queue, extra connections closed without a response or a thread, 8
    streams then 503 (`HttpLimitsTest`, on Java 21 and Java 8 test JVMs).
  - Isolation: the agent loader and module loaders are tested; a bootstrap copy in the host's own loader and a
    module whose host classes are missing both leave the agent serving. After `agent.disable` no agent thread
    remains (checked with `jstack` on both JDKs).
- **Finding.** The 44 ms median is not agent work (the agent spends about 0.03 ms on a call). The JDK HTTP server writes
  response headers and body as two small segments; Nagle's algorithm holds the second until the client's delayed ACK (40
  ms). With `-Dsun.net.httpserver.nodelay=true` on the target a call takes 1-2 ms. A scenario of about 45 calls takes 2
  s instead of roughly 0.2 s. Review item R1 is decided as option (c), the agent's own listener, which writes each
  response in one write with `TCP_NODELAY` (H1; B17, confirmed 2026-10-06; built in Phase 1.x: 1.4-5 ms per call on a
  Java 8 target, a scenario about a tenth of its old time).
- **Seen on 2026-10-02.** In the end-to-end test `basic-loan` took about 0.3 s on a Java 25 target and about 2.0 s
  on a Java 8 target (`basic-loan-ui` 0.6 s and 2.9 s): the stall is absent with the JDK 25 server and present with
  the Java 8 one. Why the JDK 25 server avoids it was not investigated; H1 replaces the server on every line.
- **Not shown.** The one-hour soak at 100 events/s with a client that disconnects and resumes repeatedly; a
  conflicting library version on the host class path (the agent shades nothing yet); that the isolated loader is
  collectable after shutdown (a self-close is shown to release it, S31); any soak on the agent's own listener.

### BS3 Retransformation, Advice and bootstrap `Spy`

- **Proves.** Byte Buddy `AgentBuilder` with `RETRANSFORMATION` and `disableClassFormatChanges` plus inline Advice
  changes an already-loaded class; two-line Advice that calls a `Spy` class appended to the bootstrap search path
  works from classes in a custom class loader; removing the transformer and retransforming restores the original
  bytes; retransform latency.
- **Run.** Hook one method of a sample lending desk class and one method of a toy class loaded through a custom
  `URLClassLoader`; install, fire, remove; target on JDK 8 and on JDK 25. Add the case of a host that already ships
  an older copy of the same library (Byte Buddy, ASM, JNA) on its system class path: relocated packages must sit
  under the agent's own package prefix, and inlined Advice may reference bootstrap types only.
- **Pass.** Events arrive from both hooks; no `IllegalAccessError` or `NoClassDefFoundError`; after detach the
  class bytes equal the bytes captured at first transform; install latency recorded (proposed limit: under 1 s for
  one class).
- **Already shown in round 1 (toy programs).** Byte Buddy 1.18.14 `RETRANSFORMATION` + Advice changed a live toy
  class inside a JDK 8 target (8u492) attached from JDK 21 (attach F14); Byteman 4.0.27 loaded, fired and unloaded
  a rule in the same JVM, and the Byte Buddy advice survived the unload (attach F19).
- **Not yet shown** (before 2026-10-09). Bootstrap `Spy` visibility from a custom loader, the byte comparison on detach, a JDK 25 target.
- **Run 2026-10-09: passed with conditions.** `spikes/BS3/` (a standalone Maven project: spy jar, probe agent, engine on
  shaded Byte Buddy 1.18.14, host drivers); the verdict per item, the measurements, the byte check, the size limit, where
  `Spy` lives, what reaches the shared loaders, the shading and the load modes are in `spikes/BS3/RESULT.md`, whose §9
  lists the facts the Phase 6 contract builds on. Not run: the amd64 image, Windows, Java lines between 8 and 25, a large
  real application.

### BS4 Swing input rungs without a window manager

Identical on Java 8 and Java 21, before the switch (step-by-step logs are written by `FocusSpikeTest` into the Swing
module's `target/`).

- A freshly shown frame is the focused window within about 30 ms; `requestFocusInWindow()` in the focused window
  succeeds at once; a second frame takes the focus when it is shown.
- In a window that is not the focused one `requestFocusInWindow()` returns false and the focus never arrives: the
  `COMPONENT` rung reports `FOCUS_FAILED` and clicks nothing. `toFront()` + `requestFocus()` activates such a
  window (2 to about 400 ms over all runs), after which the click works.
- Posted key events go to the focus owner and are lost for an unfocused window; `DISPATCH` typing delivers them
  directly.
- In the sample application: while its modal prompt is open, `COMPONENT` on the main window is `MODAL_OPEN`; with
  `force` it is `FOCUS_FAILED`. When the prompt closes nothing gives the focus back to the frame: `COMPONENT`
  without activation was `FOCUS_FAILED` in 16 of 16 cases (four load modes, two JDKs, two runs), and with
  `activateWindow` it clicked. The UI scenario does not depend on focus: it uses the `ACTION` rung and `SET` typing.
- The `DISPATCH` rung reports `fired:true` on the sample's debounced, focus-gated key even when the key then ignores
  the click because its window is not focused (`fired` means delivered). `ACTION`, and `COMPONENT` with
  `activateWindow`, are the rungs that act.
- **On 2026-10-02** the end-to-end check that encodes these findings (contract §10 check 9, with its positive
  control) passed in every load mode on a Java 25 target and on a Java 8 target. One observation against the 30 ms
  figure: in one of three agent-side runs on Java 8 a display test's frame did not become the focused window within
  30 s (the open flake under "Status at a glance"); not investigated.
- **B12 decided and built.** The `COMPONENT` rung is built in; the primitive's default stays `ACTION`; a scenario
  opts in with `ui: {clickRung: COMPONENT}`, which uses window activation and falls back to `ACTION` recorded as
  degraded fidelity, or fails with `strict: true` (A28, A29; built in Phase 1.x). The sample's UI scenario clicks at
  full fidelity in every load mode on both targets with `focusTimeoutMs: 5000` on its clicks (B12).
- **Not shown.** The `ROBOT` rung (not built; BS9); the proposed statistic of 1000 attempts.

### BS5 Playwright over CDP to a running Chromium

- **Proves.** `connectOverCDP` with `setNoDefaults(true)` and `setIsLocal(true)` (Playwright Java 1.63.0) to a
  browser Playwright did not launch; the per-target liveness probe (`GET /json/list`, then `Runtime.evaluate("1")`
  on each page target's WebSocket with a 2 s deadline); the external deadline around the connect call, needed
  because one unresponsive pre-existing target can hang it (embedded-browser-cdp F9); pages taken from
  `contexts().get(0).pages()` and mapped by target id; no `newContext` or `newPage`.
- **Run.** Start a stock Chromium with a remote-debugging port and several tabs, one deliberately frozen by a
  busy-loop script; attach from the controller. No embedded browser or vendor licence needed.
- **Pass.** The probe flags the frozen target within its deadline; over 50 runs the attach completes or aborts
  within the external deadline, never hangs; screenshot and tracing work on a healthy page.
- **Does not prove.** Compatibility with the older Chromium inside any particular embedded web view. That pairing
  is checked where such a target exists. A JCEF sample started with a debug port (BS8) could later serve as the
  target and exercise the CDP path and the in-process path against one embedded browser.
- **Result (2026-10-07): met** by the Phase 4 acceptance against JCEF's Chromium 152 in the image (decision D53,
  `design/phase4-contracts.md` §12.2 item 6): the web host sample is that target, and `CdpAttachSoakIT` runs once per
  target Java against a host of its own with a debug port. 50 calls of `CdpAttach.connect` against a page in an
  endless loop each ended with `FROZEN_TARGETS` naming it, within the 30 s deadline: slowest and mean 2017 and 2007 ms
  on the arm64 image with a Java 25 target, 2014 and 2007 ms with a Java 8 target, 2021 and 2010 ms on the amd64 image.
  Connecting without the probe ended with `TIMEOUT` after about 10 s, the driver gone: the external deadline, not the
  probe, prevents the hang. The opt-in `RELOAD` recovered a stalled page (busy in chunks with yields between them;
  the attach succeeded after 8.2 to 9.0 s) but not a page in an endless loop (`FROZEN_TARGETS` after about 18 s).
  Screenshots over CDP are part of the acceptance's failure evidence; tracing was checked outside the build, against
  a headless Chromium 148, by the review and its fix wave. The run against a stock Chromium is the manual procedure
  `spikes/BS5/README.md`.

### BS6 `SqlGuard` across dialects

- **Result (2026-10-08): passed**; `spikes/BS6/` (a standalone Maven project, not a reactor module) holds the prototype
  guard, the corpora (each accept statement with its expected rewrite, each reject statement with its reason code),
  `parser-limits.sql` (41 statements JSqlParser cannot parse or reads wrongly, each with the guard's outcome asserted)
  and `RESULT.md`. Per dialect: accepted 41/34/31/31/33/32/31/31 of as many, rejected 60/53/43/48/45/42/45/46 of as
  many (HSQLDB, MySQL, MariaDB, PostgreSQL, DB2, Oracle, SQL Server, generic), HSQLDB's 41 rewrites executed. Security
  findings fixed in the prototype and written into P5 §5.3: a database link bypassed the table check (`item@link`
  reads as `item`); comments, among them MySQL's executable ones, are dropped by the parser and need a lexical pass;
  backslash escapes and MySQL double quotes mean different things per server setting; an unqualified correlated name
  can bind to a hidden inner column; parse time and stack grow with parenthesis depth. The licence is dual LGPL-2.1 /
  Apache-2.0 (every source header says so); the project takes Apache-2.0. Open: accept corpora on real servers of the
  other seven dialects; the full column list and the default function sets are Phase 5 design (done in P5 §5.2, §5.3).

- **Proves.** The JSqlParser 5.4 allow-list: exactly one SELECT; allow-listed tables and columns with alias
  resolution; no DDL or DML; `CALL` only for allow-listed procedures; row limit injected and capped at 200;
  deterministic `ORDER BY` with a tie-breaker injected.
- **Run.** Unit tests over an accept corpus and a reject corpus per dialect: the sample lending desk's embedded
  database, MariaDB/MySQL, DB2, Oracle, SQL Server. Parsing needs no database; the accepted statements also execute
  against the sample database.
- **Pass.** Every reject-corpus statement is rejected; every accept-corpus statement is accepted and rewritten the
  same way on every run; constructs the parser cannot handle are listed per dialect.

### BS7 `javac --release 8` on current JDKs

- **Result (2026-10-01).** Compiles on JDK 25.0.1 and JDK 26.0.2, with a warning that release 8 is obsolete. The
  Phase 1 reactor is built this way, and its agent-side class files (major version 52) run on a real Java 8 JVM.
  On 2026-10-02 the agent side was again built this way on JDK 26 and in the JDK 25 image.
- **Also known (attach F24).** Under `--release 8`, `com.sun.net.httpserver` is available; `com.sun.tools.attach`,
  `sun.misc.Unsafe` and `jdk.jfr` are not, so the agent must not need them at compile time.
- **Follow-up.** Re-run on each new JDK. If the flag is removed, use the JDK 8 toolchain fallback (B4).
- [ ] Write the result up under `spikes/BS7/`.

### BS8 JCEF in the Linux test image

- **Proves.** A real embedded browser runs in the public test image, so the in-process web path of Phase 4 can be
  verified in the open (B9). Answers the "not verified" items of `embedded-browser-spi` F1, F8-F12, F14 and F15.
- **Run.** A small JCEF sample (Swing, Java 8 bytecode) with one visible and one hidden browser, pages from
  `data:` or `file:` URLs, under the WM-less Xvfb; both rendering modes (windowed, off-screen); Java 8 and Java 25
  targets. Through the in-process DevTools client (`CefBrowser.getDevToolsClient()`): evaluate round trip, target
  id, screenshot in both modes, document-start script; `printAll` over each component type; the reflective
  enumeration of browsers.
- **Record.** The image's package list (Chromium's runtime libraries), the extracted size of the natives, start-up
  time.
- **Result (2026-10-06): passed with conditions**; the write-up is `spikes/BS8/RESULT.md`, the probe and its runner
  `spikes/BS8/` (a standalone Maven project, not a reactor module). In short:
  - **The image hosts JCEF 152** (jcefmaven 152.0.6, Chromium 152.0.7977.83) once fifteen Chromium run-time
    libraries are added (done in `ci/Dockerfile`; `ldd` clean); Mesa's software OpenGL was already there. Both
    rendering modes work under the WM-less Xvfb on a Java 25 target; off-screen works on a Java 8 target;
    **windowed on Java 8 creates no native browser** (no error logged; cause open). The same holds for the x86-64
    image under emulation, a few times slower.
  - **Condition 1: Chromium 152's first-run "Additional Terms of Service" dialog blocks `CefInitialize`** until
    accepted; every stock attempt hung there. Accepting writes two empty sentinel files, `First Run` and
    `EULA Accepted`, into the root cache path; pre-creating them skips the dialog. The Phase 4 web host does this.
  - **The in-process DevTools channel works as the research read it**: `Runtime.evaluate` with a result (p50
    0.1-0.4 ms in process), `awaitPromise`, error and rejection shapes, `Page.*` and `Runtime.consoleAPICalled`
    events, `Page.addScriptToEvaluateOnNewDocument` at document start, `Input.dispatchMouseEvent`,
    `Page.captureScreenshot` in both modes, `Emulation.setDeviceMetricsOverride` on a hidden 1 x 1 browser. Futures
    complete on the EDT in off-screen mode and on a CEF thread in windowed mode. One evaluation of about twenty
    timed out, mostly right after a navigation: every call is bounded and a read may be retried.
  - **Condition 2: `Target.getTargetInfo` and `Target.getTargets` do not answer** through this page-level client;
    the CDP target id is the main frame's id from `Page.getFrameTree`, which equals the `/json/list` entry.
  - **Condition 3: a page with a `srcdoc` iframe never finished loading** (the renderer idles); plain pages and a
    `data:`-URL iframe load. The sample pages avoid `srcdoc`.
  - A browser whose component is not showing is not created until `createImmediately()`; its viewport is 1 x 1
    until overridden; its first DevTools client can report `Client is closed` and must be re-fetched (F9).
  - `printAll` throws `IllegalStateException` for the off-screen JOGL canvas and paints the windowed canvas blank:
    a `PAINT` capture has to guard embedded-browser components; `web.bitmap` is the evidence image.
  - Reflective enumeration (`CefApp.self -> clients_ -> getAllBrowser()`, `settings_`) works on jcefmaven 152.0.6.
  - Weight: natives 152 MB (x86-64) / 168 MB (arm64) as a Maven artifact, 376-469 MB extracted, extracted once in
    about 2.5 s; a host with two browsers takes 0.3-0.6 GB of JVM RSS plus 0.5-0.8 GB in seven helper processes.
  - Chromium's cache root must be on the container's own file system (lock files and sockets), not on a directory
    shared with the host.
- **Since Phase 4 (2026-10-07)**, from the kit and the end-to-end acceptance against the web host
  (`design/phase4-contracts.md` §9.1, §10.3): the "one evaluation in twenty" that timed out and the `Target.*` calls
  that never answered were JCEF 152's `CefDevToolsClient` losing an answer that arrives before the call's message id;
  the `jcef` adapter talks over JCEF's raw DevTools methods with a message observer of its own and has lost no answer
  since (D69). A browser that is not showing is not rendered: windowed its screenshot never answers, off screen it
  holds the background colour only, so its `bitmap()` is refused. Windowed rendering restores a cleared viewport
  asynchronously. A `Page.enable` still in flight when a navigation commits misses that navigation's
  `frameNavigated`. The bare `Exception in thread "AWT-EventQueue-0"` lines a host prints off screen are JCEF's own
  (`CefClient.onGotFocus` re-entering until the stack overflows). A `data:` page ignores a change of its fragment.
  Still open: why windowed JCEF on a Java 8 target creates no browser, and why a `srcdoc` iframe never loads.

### BS9-BS12 Vision and human-like input

Proposed in `research/vision-input.md` R10; they come before any Phase 7 work. Not started.

| Id | Question | Pass criterion |
|---|---|---|
| BS9 | Robot on the WM-less Xvfb and on a desktop session: do pointer and key events land, on Java 8 and Java 25 targets? `ROBOT` versus `PAINT` capture of the sample | a written table like BS4's; settles swing-driving F13 |
| BS10 | Routed in-JVM dispatch: buttons, caret placement, popup menus, combo boxes, tables, wheel, a drag inside a component, a glass pane, a disabled component; Java 8 and Java 25 | each case behaves as with Robot on the same build, or the difference is documented |
| BS11 | `PAINT` determinism: the same window, 100 captures on one JDK; then across JDK lines and font sets | identical hashes within one environment; differences across environments quantified |
| BS12 | Grounding and cost on the sample: V1 (references) versus V2 (pixels) on a Sonnet-class and an Opus-class model; seconds per action; tokens per session | measured accuracy, latency and cost replace the estimates of the research note |

## Phase checklist

Phase numbers are those of the design's roadmap; its Phase 0 is the base spikes above. Exit criteria are proven on
the sample lending desk. Phase 1.x comes before Phase 2; Phases 2-5 each depend on the one before; Phase 6 items are
independent; Phase 7 needs Phase 5.

### Phase 1: thin vertical slice on the sample lending desk

The contract for every item in this phase is `docs/design/phase1-contracts.md`. A ticked item is built and its
tests are green in every configuration of the status table (with the one open flake recorded there).

- [x] `skillroy-protocol`: wire DTOs, JSON codec, JSON-RPC and event envelopes, error codes, schema catalogue format
- [x] `skillroy-agent-api`: `AgentModule`, primitive, event, handle, capability and thread-policy SPIs
- [x] `skillroy-agent-bootstrap`: `agentmain` / `premain` / `EmbeddedAgent.start`, isolated loader, discovery properties
- [x] `skillroy-agent-core`: control server (`/rpc`, `/events`, `/schema`, `/capabilities`, `/ping`), registry, event bus, probes, safety gates, bounded pool, per-user discovery directory, secret redaction
- [x] `skillroy-agent-swing`: snapshot, find, click, type, key, dialogs, capture, settle, window events
- [x] `skillroy-agent-data`: `data.query` over `DataSourceProvider` (one guarded SELECT, row cap, timeout, `MaskPolicy`)
- [x] `skillroy-agent-dist`: packaging of the bootstrap jar, core and modules
- [x] `skillroy-controller`: attach session and handshake, target launcher, discovery, client with SSE resume, capability matrix
- [x] `skillroy-scenario`: minimal YAML to `plan.json`, executor with the step protocol, failure taxonomy, reporter with the `environment` testcase
- [x] `skillroy-junit`: dynamic tests, `junit.xml`
- [x] `skillroy-cli`: `jvms`, `attach`, `info`, `caps`, `schema`, `call`, `snapshot`, `events`, `compile`, `run`
- [x] `skillroy-sample-lending-app`, `skillroy-sample-lending-agent` (`AgentModule` `lending`), minimal `skillroy-sample-lending-pack`, scenarios `basic-loan` and `basic-loan-ui` in `skillroy-sample-lending-scenarios`
- [ ] BS1 and BS2 passed. Not ticked: both are answered for what Phase 1 needs (Java 8 and Java 25 targets on
  Linux), but not as specified. BS1 lacks the cross-user case and Windows; BS2 lacks the one-hour soak and is to be
  repeated on the agent's own listener.
- **Exit**, split into what is proven and what is not:
  - [x] `basic-loan` and `basic-loan-ui` run green against the sample lending desk from a clean build, with the
    target on Java 8 and on Java 25, in every load mode (`JAVAAGENT`, `JAVAAGENT` through `JAVA_TOOL_OPTIONS`,
    `ATTACH`, `EMBEDDED`), with no sleep in the executor. Run once per target after the port.
  - [x] A failed scenario does not require restarting the target: after a failure with a prompt left open the
    pack's `recover` signs off and the next scenario passes; all checks of one load mode share one target process.
  - [x] `run.jsonl`, `bindings.json`, failure evidence (snapshot, capture, events, application state, dialogs)
    and JUnit XML are written and asserted.
  - [x] The target on Java 25 (the gate of B4 and of the design's roadmap): met on 2026-10-02 in the Linux image.
  - [ ] Archiving by a CI system. No CI is chosen for this repo. "Per-step bundles" as the design describes them
    are not built; Phase 1 writes one `run.jsonl` per scenario plus the failure evidence.

Not covered end to end in Phase 1, by design or for lack of a hook: an EDT stall in the real sample application
(covered by the Swing module's own display tests only).

### Phase 1.x: contract amendments and hardening

**Status: built, merged into the main tree and verified there (status table at the top).** The as-built text is in
`design/phase1-contracts.md`, marked "(1.x, built 2026-10-02)"; `design/phase1-contract-amendments.md` lists where
the build differs from the amendments' wording; the integration tree's test counts before the merge are in
`design/phase1-implementation-notes.md`. Sources: review items R1-R4, the SPI gaps below, decision B12 and the
security review. Gap numbers refer to "Open SPI and DSL gaps".

- [x] A17: the secret-key rule shared in `skillroy-protocol` (`SecretKeys`); built 2026-10-02
- [x] A18: capabilities can be withdrawn (gap 7); built 2026-10-02
- [x] A19: `APP` before the application thread exists (gap 8); built 2026-10-02 (the sample does not need it)
- [x] A20: live options, `AgentContext.option(key)` (gap 14); built 2026-10-02
- [x] A21: module-supplied redaction keys, `RedactionKeys` (gap 15); built 2026-10-02, the sample registers one
- [x] A22: non-blocking embedded start, `EmbeddedAgent.startAsync` (gap 16); built 2026-10-02
- [x] A23: stale discovery and token files removed, agent and controller parts (gap 3; R3, B18); built 2026-10-02
- A24: withdrawn (R1 is decided by H1)
- [x] A25: fixture secrets masked in every run output (gap 4; R4, B18); built 2026-10-02
- A26: superseded by the fix of S22 in H3 (gap 5)
- [x] A27: dialogs that are not windows, `DialogLocator` (gap 6); built 2026-10-02
- [x] A28, A29: `COMPONENT` with fall-back to `ACTION`; the scenario key `ui`; a click that did not fire fails the
  step (B12, B14); built 2026-10-02
- [x] A30: `MODAL_OPEN` from a step call is `FAIL` / `ASSERTION` (gap 2; R2, B18); built 2026-10-02
- [x] A31: `AgentConnection.await` throws `AgentRpcException` (gap 30); built 2026-10-02
- [x] A32: `await:` on the base verbs `click`, `type` and `press` (gap 23); built 2026-10-02
- [x] A33: the rule for `INPUT_REJECTED` and `input.rejected` (gap 17; documentation only); written 2026-10-02
- [x] H1: the agent's own HTTP listener in place of the JDK `HttpServer` (gap 1; R1, B17); built 2026-10-02
- [x] H2: HMAC request and reply authentication, wire protocol 0.2, the client's terminal `lost` state (B17); built
  2026-10-02
- [x] H3: every other confirmed finding fixed per its verified fix; built 2026-10-02 (S7 and S15 not verified on
  Windows, see the open items below)
- [x] Licence packaging: `LICENSE` and `NOTICE` in every jar and zip, third-party attribution in the CLI
  distribution (`THIRD-PARTY.txt`), a `<licenses>` element in the root pom (`licence-audit.md` §1); built
  2026-10-02
- [x] **Exit** (design roadmap), met in the integration tree before the merge: the Phase 1 matrix is green on the
  new listener, on a Java 8 and a Java 25 target; `basic-loan-ui` passes with `clickRung: COMPONENT` in every load
  mode with no degraded-fidelity entry; the negative scenario `click-fallback-degraded` ends `DEGRADED` and
  `click-strict-fails` ends `FAIL`; the fixture password appears in no run output; a client without the token can
  neither hold a connection past the head deadline nor grow the host's heap (bounded head, body budget, eviction;
  `HttpLimitsTest`); a call takes a few milliseconds on loopback (1.4-5 ms measured). `phase1-contracts.md` is
  brought to as-built (2026-10-02).

Open after the build (not verified, not blocking):

- [ ] **The Windows ACL path** (S7, S15): built on both sides, unit-tested on built ACL values and on a zip file
  system; never run on a Windows host. Marked "not verified on Windows" in the contract.
- [ ] **The S8 fall-back at start** (a lost race for `${java.io.tmpdir}/skillroy-<user>` after the directory was
  chosen): the selection rule is unit-tested, but the switch at start needs a directory owned by a second OS user,
  which no test environment has; the test is skipped.
- [ ] **The sample registers no `ProductionMarker`**, so under S14 a module that fails to load now blocks its
  readiness unless `allowFailedModules=true` is set. No module fails today; a domain layer should register a marker
  or set the option deliberately.
- [x] Verification of the merged main tree (2026-10-02; the second status table at the top).

### Phase 2: resolution and sample scenario A

**Contract: `docs/design/phase2-contracts.md`** (drafted 2026-10-02, critiqued by three independent reviewers,
revised the same day; third draft with the §17 answers; as built since 2026-10-03). **Built the same evening**, in
isolated working copies by module owner, integrated and verified (numbers at the top), then merged into the main
tree. The implementers worked from the second draft: the third draft's additions (the sample's `lending.dbPassword`,
the A34 details, the end-to-end JDBC block, the `source` of a `db` record, the 8-character advice) reached the tree
twelve minutes after their copies were made, which the code review of 2026-10-03 found and the fixes closed. Section
numbers below are those of that contract.

- [x] New controller-side module `skillroy-data`: the `DataChannel` abstraction with the agent channel and a
  controller-side read-only JDBC channel, `Credentials`, `SecretMask`, `RowMask`; the strict YAML reader moves
  there as `StrictYaml` (§2.1, §7)
- [x] `skillroy-protocol` gains `…protocol.sql`: the statement guard, value conversion and JDBC read path moved from
  the agent's data module, so that both channels share one implementation (§2.2)
- [x] The resolution SPI (`…scenario.resolve`) and the deterministic resolution engine in `skillroy-scenario`;
  tier (a) resolvers only (committed SQL or Java); `skillroy-inference` is not created before Phase 5 (§2.1, §6)
- [x] In-process verifiers for every query slot; oracles as committed Java with a bind-time `must_equal` guard
- [x] `credentials.yaml` (git-ignored) as the only source of secrets for sign-on steps and JDBC; fixtures can no
  longer hold secrets (§4)
- [x] Bindings files with `replay`, `approve` and `live`; environment fingerprint v1; plan version 2; a cost ledger
  that must be empty after a `replay` run (§5, §8, §9)
- [x] `REBIND_NEEDED` and `RESOLUTION_FAILED` raised by the binder (§10)
- [x] Stored-row checks (`db.*`) over either channel; a slot captured from an event (§3.4, §7.4)
- [x] `skillroy-cli`: `resolve`, `diff-bindings`, the `run` command's mode and environment options; the JUnit
  glue's equivalents (§12)
- [x] Sample: the read-only primitives `lending.deposit` and `lending.limits`; scenario A
  `return-over-limit-needs-supervisor` on seeds A and B with one YAML file (§13)
- [x] **Exit** (§14): §14.1 green in the normal build; §14.2 green in every load mode, on both seeds, on a Java 8
  target and a Java 25 target, twice in a row without a flake. In the roadmap's words: scenario A passes in
  `replay` on two differently seeded databases with the same YAML; replay makes zero model calls (asserted); a
  deliberately wrong binding fails as `REBIND_NEEDED`.
- [x] **Code review** (2026-10-03): three lenses against the contract, each finding adversarially verified by an
  independent sceptic; 42 findings, 2 refuted, the rest fixed (section "Phase 2 code review" below)
- [x] **As built** (2026-10-03): `design/phase2-contracts.md`, the Phase 1 contract's Phase 2 passages, the design
  overview, `DECISIONS.md` (B19), `README.md` and `licence-audit.md` state what the code does
- [x] **Linux matrix of the merged main tree** (2026-10-03): the table at the top

Answers to the contract's open questions (§17), given provisionally on 2026-10-02 without the owner. They go into
the contract before the code:

| Question | Answer |
|---|---|
| Q1 plan version | Phase 2 plans are version 2 (1.x stays at version 1) |
| Q2 approved bindings in `replay` | `replay` accepts tier (a) bindings written by `live`; no approval switch yet |
| Q3 a secret under a neutral key on the wire | yes: a request-level `secretParams` list becomes Phase 1.x amendment A34, built with Phase 2 |
| Q4 end-to-end coverage of the JDBC channel | yes: the sample gets an option to serve its database on a loopback port, so that the end-to-end acceptance also runs the stored-row checks over the controller-side channel |
| Q5, Q8 | closed in the contract itself |
| Q6, Q7, Q9, Q13, Q15 | as the contract proposes |
| Q10, Q14 | as the contract proposed on 2026-10-02; vetoed by the owner on 2026-10-06 and rebuilt in the overridable form (`DECISIONS.md` B19: no base rule on names in a pack's value types; `masking.minLengthInText` per environment) |
| Q11 how far "nothing before the start mark" goes | nothing is sent to the target for any failure before the start mark |
| Q12 `interactiveSignOn` without a verifier | the answer of `lending.access.check` gains a field `autoSignOn`, so that the condition is verified |

One more decision: the sample's limit table is renamed `role_cap` at the start of Phase 2 (D36).

### Phase 2 code review (2026-10-03)

Three lenses (contract fit; secrets and safety; tests and determinism), each run by one reviewer and verified by one
sceptic who tried to refute every finding and spot-checked the "conforming" claims. 41 findings plus one the sceptics
added; 2 refuted (a masking rule that is A25's, documented instead; a test-strictness point that the contract's own
byte-identity items already imply); 32 distinct items after merging duplicates; every one fixed the same day, by
three implementers in isolated copies (sample modules; controller modules; agent core), integrated and verified
natively (1938 tests, 0 failures) and in the Linux image (table at the top). The most important:

- **The sample's read-only database account had a committed, fixed password**; the contract's `lending.dbPassword`
  (required with `lending.dbPort`, redacted, generated per launch by the end-to-end test) is built now, and the
  end-to-end JDBC block's items 4-7 and the A34 checks of item 10 run end to end (they were headless stand-ins).
- **A34 on the agent rewrote application events** published during a call that named a secret, in the shared event
  ring, so that a payload value equal to a short secret became `<redacted>` for every reader (sync, capture, waits):
  events are untouched now; the agent redacts only what it writes about a call.
- **A34 on the controller named only joined credential fields**; it now names by provenance, which also covers A25
  fixture secrets of version 1 plans and never declares a legitimate value that happens to equal a secret.
- **Bind-time data statements without a timeout got the whole remaining budget**, not the source's default; the
  budget also covers the JDBC connect, its validation and the agent's source lookup now.
- **Provenance call params were written in a per-JVM map order**, so a bindings file with a recorded call was not
  byte-stable; keys are sorted at every depth now.
- **`TIMESTAMP` where-parameters could not work on HSQLDB** (every ISO form was rejected); they travel as
  `yyyy-MM-dd HH:mm:ss[.fraction]` now, offsets refused.
- Smaller: a free-port race in the JDBC end-to-end tests (port 0, read back through `app.state`), two replay reason
  codes, a late compile error for pack-verb captures, `approve` without a bindings root failing late, `db` records
  without `source`, the mask over sets and arrays, the bindings-file writer's check on escaped text, binder and
  credentials messages that could echo a secret, tests depending on the developer's environment, a locale-dependent
  test, `autoSignOn` for an inactive staff member, the example credentials file's advice, a `.gitattributes`
  line-ending rule, and the `RpcRetryTest` flake seen in the first Linux run (two valid wordings of one failure).
- Left as built and documented: the staff SQL alias `rl`; the mask's double-quote rule for short secrets (A25's);
  `BindingsUpToDateTest` asserting `planHash` and `packVersion` of committed material.

The review's reports, verdicts, briefs and implementer reports are with the session's working files (2026-10-03).

### Phase 3: waiver rules, oracles and sample scenario B

**Contract: `docs/design/phase3-contracts.md`** (specified 2026-10-03; its §14 answers are the orchestrator's, recorded
as B20 and confirmed by the owner on 2026-10-06; as built the same day). **Built 2026-10-03** by three implementers in
isolated copies (the base: scenario, CLI and JUnit modules; the sample's agent module; the sample's pack and scenarios),
integrated into the main tree, reviewed through three lenses with sceptics and corrected (section below). The contract's
§13.1 changes were written into the Phase 1 and Phase 2 contracts before the code and are marked built since.

- [x] The `CrossCheck` SPI, the `crossCheck` key of an oracle entry, binder step 8 in every mode, `ORACLE_SUSPECT`
  (reason `DISAGREED`), guarded cross-checks skipped as `DEGRADED` (gap 26 closed), the `crosscheck` record, the
  bindings file's informational `crossCheck` key, the approval screen and CLI lines (P3 §3.1, §5, §6, §8, §9)
- [x] Stored-row aggregates `sum`, `min`, `max`, computed on the controller over the rows read (P3 §3.2, §7)
- [x] `$env.now` decided against (D39); the base fact root `devices` (P3 §3.3, §3.4); plan version 3 (P3 §4)
- [x] The sample: `lending.waiver.evaluate` and `app.state.today`; pack 0.3.0 with the waiver queries, resolvers,
  verifiers, oracles (`LoanFromTrigger`, `ExpectedWaiver`, `ExpectedTotals`) and the cross-check; the fingerprinter's
  two added tables; scenario B `borrow-2-waive-1-slip` with its plan and bindings on both seeds; the negatives; the
  every-seeded-rule agreement test; the end-to-end checks of P3 §11.2 (P3 §10, §11)
- [x] Slip assertions on the print event (`slip.printed` captured) and the rendered text through the pack fact and
  the `devices` root
- [x] **Exit:** scenario B passes in `replay` on seeds A and B with one YAML in every load mode on a Java 8 and a
  Java 25 target, twice in a row (the table at the top); the oracle equals the dry run for every seeded waiver rule
  of both seeds (`WaiverOracleAgreementTest`, 552 loans); a deliberately wrong oracle is `ORACLE_SUSPECT` headless
  (`PackOnRealSchemaTest`) and end to end (`ResolutionChecks`), never a failure of the application

### Phase 3 code review (2026-10-03)

The same method as Phase 2's: three lenses, each verified by a sceptic. 9 findings (2 medium, 7 low), 1 moved to the
contract (a wording), 1 resolved as a contract clarification (row masks over the agent channel stay the agent's
`MaskPolicy`), the rest fixed the same evening: a `db` check's aggregate matchers were not reference-checked at bind
time (a bad reference passed the bind and failed after the steps, or ended `DEGRADED` under a guard); two end-to-end
assertions read the desk's business date before a run and compared it with the date the cross-check used, which
would fail on a leg crossing the target's midnight; the engine's public cross-check hook was unusable outside its
package; the CLI's `--json` dropped the cross-check records of a failed bind; a cross-check's `actual`, `evidence`
and `expected` could carry a secret in a set, array or record past the mask into `--json`; the A34 and secret scans
of the end-to-end checks did not cover scenario B. The fix wave found and closed one more Phase 2 gap: `resolve
--dry-run` and the approval screen printed values with a text scrub only, so a credential containing a quote or a
backslash could print escaped; the dry run now refuses such a file as `approve`'s writer does, and the screen masks
values before writing them. Determinism was proven in fresh JVMs under other locales, time zones, charsets and
business dates: the committed plans and bindings are byte-identical, and no date reaches a committed file (D47).

Verification of Phase 3 (2026-10-03):

| Configuration | Result |
|---|---|
| Native, JDK 26, headless, before the review's fixes | 2117 tests, 0 failures, 0 errors, 285 skipped; 172 s |
| Linux image, targets on Java 25, before the fixes, twice | 2141 / 0 / 0 / 4 (587 s); 2141 / 0 / 0 / 4 (571 s) |
| Linux image, targets on Java 8, before the fixes, twice | 2141 / 0 / 0 / 4 (514 s); 2141 / 0 / 0 / 4 (520 s) |
| Linux image, agent side on a Java 8 test JVM, before the fixes, twice | 1038 / 0 / 0 / 4 (181 s); 1038 / 0 / 0 / 4 (180 s) |
| Native, JDK 26, headless, after the review's fixes | 2130 tests, 0 failures, 0 errors, 285 skipped; 174 s |
| Linux image, targets on Java 25, after the fixes | 2154 / 0 / 0 / 4, 561 s |
| Linux image, targets on Java 8, after the fixes | 2154 / 0 / 0 / 4, 507 s |
| Linux image, agent side on a Java 8 test JVM, after the fixes | 1038 / 0 / 0 / 4, 177 s |

The four skips are the usual ones. The thirteen tests the fixes added to the Linux legs are the review's. Commands as
in the first table (the Phase 3 end-to-end checks run inside `LendingEndToEndIT`, so no new command). The exit
criterion's "twice in a row" holds on the pre-fix tree for every leg and the fixed tree passed its one run of the
evening; a second run of the fixed tree is the first thing to do next session.

### Phase 4: embedded web views, CDP and the in-process SPI

**Contract: `docs/design/phase4-contracts.md`** (specified 2026-10-06; B21 holds its answers, confirmed by the owner
2026-10-07; as built since 2026-10-07). **Built 2026-10-07** by seven implementers in isolated copies, integrated into
the main tree, reviewed through three lenses with sceptics and corrected by a fix wave (section below). The contract's
§14.1 changes were written into the Phase 1-3 contracts and the design overview before the code and are marked built
since.
Decision B9: one in-process SPI that wraps both JCEF and reflective JxBrowser access. This replaces the earlier
plan of a JxBrowser-only module that could be tested against a stand-in only: JCEF is open source, so a real
embedded browser can run in the public image.

- [x] BS8 passed with conditions (JCEF in the Linux test image; 2026-10-06; `spikes/BS8/RESULT.md`): windowed and
  off-screen on Java 25, off-screen on Java 8; the sentinel files, the target id and the `srcdoc` finding are
  conditions the contract carries
- [x] `skillroy-cdp`: connect wrapper, liveness probe, external deadline, page mapping by target id, readiness, tracing.
  As built (P4 §5): `CdpAttach` runs every Playwright call of an attach on one platform thread under the external
  deadline; `TargetProbe` refuses an endpoint that serves none of the target's browsers (D65); `CdpWebPath` serves the
  web verbs, traces into a private directory (D59) and sets the viewport override on its own CDP session (D64); an
  optional module found through `ServiceLoader<WebPath>` (D49)
- [x] The `WebEngineAdapter` / `WebBrowser` SPI in `skillroy-agent-api` (`…agent.api.spi.web`), optional operations
  behind `WebCapability` flags; `BrowserRegistryAdapter` made technology-neutral. As built (P4 §3): plus
  `enumerationNotes()`, and `Invocation.secretParams()` in the agent API (D68)
- [x] `skillroy-agent-web`: the `web.*` primitives written once against the SPI, and two reflective adapters, `jcef`
  (on `CefBrowser.getDevToolsClient()`, no debug port needed) and `jxbrowser` (lines 7, 8 and 9); no vendor code
  and no compile-time dependency. As built (P4 §4): thirteen primitives; descriptor-driven adapters (D54), the `jcef`
  one on JCEF's raw DevTools channel where it resolves (D69); the jar holds 155 classes and nothing outside
  `ai/skillroy/` and `META-INF/` (`licence-audit.md` §3)
- [x] A small JCEF sample module behind a Maven profile; the adapter contract kit published as a test artifact. As
  built (P4 §9, §10): three modules behind the profile `jcef` (the web host, its agent module, its pack and scenarios);
  the kit is `skillroy-agent-web`'s `test-jar`, passing against the fake JCEF (both channels) and the JxBrowser fake
  (plain and chained), and against real JCEF in the image in the acceptance implementer's runs, before the fix wave:
  12 of 12 off screen and 11 with one skipped windowed on Java 25, 12 of 12 off screen on a Java 8 test JVM; on the
  final tree it runs in the matrix at the top
- [x] Every web verb has a CDP implementation and an in-process implementation; the one used is recorded. As built
  (P4 §5.2, §6.1, §8.2): the CDP path serves every `web.*` primitive but `web.browsers`, `web.cdp.status` and
  `web.reload` (always the agent's) and hands a call with secret parameters to the agent; every web call record
  carries `path`, every fall-back a `web.fallback` record
- [x] BS5 passed: met by the acceptance against JCEF's Chromium, D53; manual procedure in `spikes/BS5/README.md`
- [x] **Exit:** over 50 runs, attach to a running Chromium with a deliberately frozen page target completes or
  aborts within the external deadline; one `web.*` scenario passes over CDP against a stock Chromium; the adapter
  contract kit passes against a real JCEF browser in the Linux image; the `jxbrowser` adapter's reflection plumbing
  passes against a neutral fake. Running the kit against the real JxBrowser needs a licence and happens outside
  this repo. `INPUT` and `RAW_PROTOCOL` stay reserved. As run (P4 §12.2, the criteria made precise there): every item
  passed in the Linux runs of the acceptance's implementer, on the arm64 image with a Java 25 and a Java 8 target and
  on the amd64 image with a Java 25 target; the CDP scenario W1 ran against JCEF's Chromium (D53), over CDP and over
  the agent from one committed plan. "Twice in a row without a flake" on the final tree is the matrix at the top
- [x] **Code review** (2026-10-07): three lenses, each verified by a sceptic; 27 findings and six more from the
  sceptics, every one confirmed, none refuted; each fixed by the fix wave or answered by a contract amendment, two
  with a residual left open (gaps 42 and 47; section below)
- [x] **As built** (2026-10-07): `design/phase4-contracts.md`, the Phase 1-3 contracts' Phase 4 passages, the design
  overview, `DECISIONS.md` (B21), `README.md` and `licence-audit.md` state what the code does

### Phase 4 code review (2026-10-07)

The method of Phases 2 and 3: three lenses, each run by one reviewer on the integrated tree before the acceptance's
implementer landed, and each verified by a sceptic who tried to refute every finding with probes in a copy of the tree
and added what the lens had missed. **Contract fit** (L1): 5 findings (0 high, 1 medium, 4 low) and one added (N1,
low). **Host safety and secrets** (L2): 13 findings (2 high, 3 medium, 8 low; the sceptic raised F3 to high) and four
added (N1 medium, N2-N4 low). **Tests and determinism** (L3): 9 findings (0 high, 5 medium, 4 low) and one added (N1,
low). Every finding was confirmed, many with changes to the proposed fix; none was refuted. Five pairs were one item
seen through two lenses (the re-probe, the probe's class loading, the core's `secretParams` test, the kit's EDT
watchdog, a timer race in the probe's test), which leaves 28 distinct items. The most important:

- **A traced CDP run wrote typed secrets in clear into the run's output directory** (L2 F1, high). Playwright appends
  its live trace (page snapshots with the values of input fields, console text) to files in the directory it is given
  and never deletes them, on passing runs too, and a later failing run's `failure/web-trace.zip` carried an earlier
  run's secrets. Now the CDP path traces into a private, owner-only directory it deletes at close, and the executor
  decides at step 1a, before the path opens, whether a call may carry a secret, and then opens it without tracing
  (D59).
- **The JCEF adapter sent calls again after Chromium's "Inspected target navigated or closed"** (L2 F2, high), which
  it took for a closed client: clicks ran twice, abandoned calls ran in the page after the caller's timeout, and a
  second init script stayed on the application's client. Now only a call the client refused before sending it is sent
  again, and only one of the adapter's own reads; the module retries its reads after a navigation (D62).
- **An abandoned tap drain lost the events it had taken** (L2 F3, raised to high: a wrong `FAIL` whenever a tapped page
  stays busy for a second or two right after firing, reproduced on both paths against a real Chromium). Now one drain
  per page is outstanding and a late one is published; the same rule for `web.waitFlag`'s polls closed L2 F4, abandoned
  57 KB evaluations piling up in the application's DevTools client (D63).
- **Every `window.opened` ran a whole probe cycle with a Swing walk** while any adapter was `UNAVAILABLE` (L1 F1 =
  L2 F5, medium), which in a JCEF application means on every window and dialog; now only the adapters that can have
  changed are probed, cheaply (D60), and a re-probe in flight during `uninstall` can no longer revive a detached adapter
  (L1 N1).
- **From the tests lens**: the adapters' threads restarted after `detach()` when a callback came late (L3 F2;
  `AdapterThreads.close()` is terminal now); kit item 1 could not fail with either fake (L1 F5 = L3 F3; it now checks
  initialisation and package scope against a host that loaded only the gate, D61); the kit's EDT watchdog forgave a
  block in an event listener (L3 F1 = L2 F12); no test proved that the dispatcher fills `Invocation.secretParams()` (L3
  F4 = L2 F11). `TargetProbeTest` had a cold-JVM time bound and two racing timers (L3 F5; L2 N1 = L3 N1).
- Smaller: the shape of the `NOT_FOUND` data (L1 F3); a stale `DevToolsActivePort` file, or a foreign process on the
  port, attaching the run to another browser (L1 F4, D65); a JCEF older than 2024-01, served on neither path (L1 F2, a
  contract amendment); an attach left open by an unexpected failure (L2 F6); `web.bitmap` starting AWT in a target
  without a user interface (L2 F7); a dropped post stopping the re-probe for good (L2 F8); unbounded JxBrowser calls
  (L2 F9); taps and init scripts left behind in pages (L2 F10); `clear: true` pinning the viewport on the CDP path
  (L2 F13, D64); a `web.trace` success record without a trace (L2 N2); fakes too kind to show F2 and F3 (L2 N3); client
  replacement on real JCEF, which the fakes cannot show (L2 N4); the kit's violation checks charged to the next test
  (L3 F7); threads compared by name (L3 F8); the untested Java 8 strategy mark (L3 F9).

**The fix wave**, three implementers in isolated copies, each change with a test, checked by mutation (the test fails
when the old behaviour is put back):

- *The agent's web module and one agent-core test* (agent-web 160 tests, agent-core 186): L1 F1, F3, F4 (the agent's
  half), F5 and N1; L2 F2-F5, F7-F12, N3 and N4; L3 F1-F4 and F6-F9. It merged the acceptance implementer's agent-web
  changes and fixed their residual: `subscribe()` now waits for the page's `Page.enable` and `Runtime.enable` answers.
  Left open: a time-based rate limit on the re-probe (gap 42), bounds on JxBrowser's observer registration and
  `cdpEndpoints` (gap 47). Three checks that need real JCEF (init scripts added once per client, the `subscribe()`
  wait, kit item 5's judged console event) were made by the kit's run in the final tree's matrix (table at the top):
  the first round showed that the reload by script the kit first used never happens on a `data:` page (Chromium
  refuses a renderer-initiated navigation of such a document), so the kit now navigates through the fixture; after
  that change the kit passed in every leg of both rounds.
- *`skillroy-cdp`* (105 tests, 11 new): L2 F1 (the private trace directory), F3 (the drain publishes from inside the
  Playwright operation), F6 and F13 (the override on the path's own session); L1 F4's second half (the endpoint must
  serve the target's browsers); L3 F5 and L2 N1 = L3 N1. Checked against Chrome for Testing 148 headless outside the
  build.
- *The executor* (`skillroy-scenario`, 447 tests, 22 new): L2 F1's executor half (`SecretParamsForecast`: the trace is
  withheld at step 1a when a call may carry a secret, by the provenance rule of the call's `secretParams`) and L2 N2 (a
  `web.trace` record of success only for a non-empty file).
- *The orchestrator*: the contract amendments the verdicts asked for (among them L1 F2's), and the CLI POM's test
  dependency on the distribution zip (the build trap under "Environment notes").

**What the runs against real JCEF found** (the acceptance's implementer, on its copy, before the fix wave; each fixed
or written into the contract): JCEF 152's `CefDevToolsClient` loses an answer that arrives before the call's message
id (kit items 3, 4, 9 and 11 timed out), so the `jcef` adapter talks over JCEF's raw channel (D69); a hidden browser is
not rendered, so its `bitmap()` is refused and kit item 8 accepts that; windowed rendering restores a cleared viewport
asynchronously, so `clearResize` waits for it; a `Page.enable` in flight when a navigation commits misses its
`frameNavigated`, so the kit's fixture waits for the answer; `RELOAD` recovers a stalled page but not an endless loop;
the web host's `#freeze` and `#stall` cannot be reached on `data:` pages, so the soak starts its loops by evaluation;
the bare `Exception in thread "AWT-EventQueue-0"` lines are JCEF's own; `cdpTargetId` is known without a port; with a
port the CDP path serves W2's `web.resize`; and for an expression that throws, `web.waitFlag` keeps polling on the
agent but fails at once over CDP (gap 38).

The review's reports, verdicts, briefs and implementer reports are with the session's working files (2026-10-07).

### Phase 4.x: the small follow-ups as amendments (2026-10-08)

Contract first, then code, native build green; each amendment is a subsection of `design/phase4-contracts.md` §16 and
its passages are marked "(4.x, A3n, built 2026-10-08)" in place. Built by two implementers in isolated worktrees on the
night of 2026-10-07 to 2026-10-08, integrated into the main tree by file list.

- [x] A35: `web.waitFlag` answers an expression that throws the same way on both paths (gap 38): a run-time throw is
  "not ready" and polling goes on, `lastError` on a timed-out wait, a compile error `SCRIPT_ERROR` at once; one guard in
  `WebLib` for both paths; built 2026-10-08 (18 new tests across protocol, agent-web, cdp and scenario; the guard also
  checked outside the build in Node 24, over raw CDP and under Playwright 1.63 against a Chromium headless shell); the
  two new `jcef` negatives wait for the next Linux matrix
- [x] A36: a call the CDP path serves reads the agent's sequence once, not three times (gap 45); built 2026-10-08
- [x] A37: `WebBrowser.reload(boolean)` and `bitmapSource()` as SPI default methods, `BitmapSource`, `BrowserExtras`
  deleted, the built-in adapters override, kit item 12 (gap 40); built 2026-10-08, agent-api 110 and agent-web 172 tests
  green natively. **Kit item 12 against real JCEF ran in the first Phase 5 matrix (arm64 image, 2026-10-08) and failed on
  the kit's own script**: `DOCUMENT_MARK` returned the window (the value of `Object.defineProperty`), which Chromium's
  `Runtime.evaluate` by value refuses ("Object reference chain is too long"); the expression now ends with a string and
  the fake answers it the same way. After the fix the kit's off-screen execution passes in the image, 13 of 13, on a
  Java 8 and on a Java 25 test JVM
- [x] A38: the re-probe after `window.opened` at most once a second, on the worker, nothing dropped (gap 42); built
  2026-10-08 (`WindowOpenedPaceTest`)
- [x] A39: the `COMPONENT` rung's window activation re-issues `toFront()` + `requestFocus()` once a second while it waits,
  within `focusTimeoutMs` (the open display-test focus flake, investigated and mitigated 2026-10-08: 14 failed runs of 50
  before, 0 of 60 after; `design/phase1-contracts.md` §6; `WindowActivationRetryTest`); the display tests wait through
  one helper that re-issues the same way, and their class set-up waits for the frame to be focused

### Phase 5: AI layer

**Contract: `docs/design/phase5-contracts.md`** (specified and built 2026-10-08; its answers B23 and the decisions of its
build and review, B24, confirmed by the owner on 2026-10-08; decisions D70-D104 and D110). The items below are the
roadmap's; the contract makes each precise (its §1 scope, §15 acceptance). The gates before the build were spike BS6 (the
guard's corpora, passed) and spike BS13 (the API facts; its live part waits for the first live session).

**Build, wave 1 (2026-10-08, on the then provisional B23; two implementers in isolated worktrees, integrated by file list):**
the executor side of `skillroy-scenario` (`CallRunner` extracted from the executor, `ExecutorSession`, `StepJournal`
with its file and export, `ScenarioSchema` and `Schemas`, plan version 5 with the sample's plans and bindings
regenerated, `SqlAllowList`/`SqlDialect`, the `DomainPack` and `StepLibrary` additions, `llm-sql` accepted when a pack
declares an allow-list, `confirm` sent as `_meta.confirm`) and the resolution side (`InferenceSettings` with
`inference.yaml`, `ResolverContributor` and `BatchableResolver`, the ladder with contributed resolvers, `REQUIRED`
approval and `replay`'s `NOT_APPROVED`, the `approve` reuse, `BindMode.RE_RESOLVE` with `Binder.bindStepwise`,
`CostLedger.Entry`'s new fields, `Resolution.usage` filled, `RESOLUTION_FAILED` / `BUDGET`); the CLI refuses `--mode
re-resolve` as a usage error. Native reactor after the merge: 2572 tests, 0 failures, 0 errors, 122 skipped; the web
host modules green under `jcef`.

**Build, waves 2a-2c (2026-10-08, six more implementers, each in an isolated worktree, integrated by file list, one
commit per wave; every wave green natively):** `skillroy-inference` (the gateway SPI, `ClaudeGateway` on the Anthropic
Java SDK 2.70.0 built explicitly from the environment with the SDK's retries and logging off, `RecordedGateway` and
`RecordingGateway`, `ScriptedGateway`, `Gateways` with reference-counted leases, `PromptMask`, `SchemaCatalogue`,
`SqlGuard` lifted from BS6 with its corpora as tests, `LlmSqlResolver` and `InferenceContributor`, budgets, prices, the
ledger writer, `ToolProjection`; then `ReResolver`, `Triage`, `Planner`, `SessionService`, `OverlayBridge`); the sample
pack's allow-list, verb schemas and `ValueLoader`s, scenario D with its plan and its bindings on both seeds through
**scripted stand-in recordings** (no live call was possible, see "Walls"), the session export acceptance on a headless
desk, `InferenceEndToEndIT` for the image; `skillroy-mcp` (the server over stdio on the MCP Java SDK 2.0.1); the CLI's
`re-resolve`, `mcp`, `author`, `triage` and `session` commands, the inference options of `run` and `resolve`, the
approval screen's tier (b) lines, `inference.jsonl`, the distribution (48.7 MB, 43 jars, `THIRD-PARTY.txt` with the MIT
texts) and the runner's key pass-through. The build took decisions D88-D95 (`DECISIONS.md` B24, provisional). The
contract carries an "As built" note under every section the build changed. **The first Linux matrix of the committed
tree (arm64 image, 2026-10-08)** found two failures in `InferenceEndToEndIT`, whose checks run only with a display and
so had never run before: a test that expected `approve` with the reuse rule to rewrite an identical bindings file
(approve step 8 leaves it alone; the test now asserts that), and the export's replay signing on to a desk the `session`
command had left signed on (the desk took the staff code for an item); the CLI session now closes with
`recoverOnClose` on (D95). The class then passed in full in the image (88 cases on the four load modes and two seeds,
the two repaired checks green on both seeds). What was run is in the table below and under "Status at a glance".

- [x] `skillroy-inference` (created here, above `skillroy-scenario`); the `InferenceGateway` SPI with typed content
  parts (text, image, tool use, tool result, and an opaque part) and the Claude implementation (B7); built 2026-10-08;
  the live gateway exercised the same afternoon with the owner's key (`ClaudeGatewayLiveIT` under `-Pinference-live`,
  the live subsection below)
- [x] Tier (b) resolvers: model-authored SQL behind `SqlGuard` (BS6 passed first); masking before any prompt (P5 §6);
  built 2026-10-08, proven end to end against stand-in recordings, then live: scenario D's two requests answered by
  Sonnet 5.5 and recorded (`ScenarioDRecordIT`, the live subsection below)
- [x] Approve flow on first use; budget meter per scenario and per nightly job (and per session); built 2026-10-08
- [x] Nightly batch re-resolve that writes the bindings and a report (the pull request is the pipeline's,
  `ci/examples/nightly-re-resolve.yml`); `skillroy re-resolve`; built 2026-10-08, the batch interface exercised against
  the recorded gateway only
- [x] `skillroy-mcp`: tool projection of the catalogue plus the pack's verbs, stdio transport; built 2026-10-08
- [x] Authoring planner through headless `claude -p` (built and tested with a substitute executable; run live in the
  afternoon of 2026-10-08, the live subsection below); opt-in failure triage as a separate command; built 2026-10-08
- [x] Interactive profile (B15): the executor's single-call entry and the step journal (`ExecutorSession`,
  `StepJournal`), export of a journal to scenario steps and bindings, the session service, a per-session budget; built
  2026-10-08; a session on the headless desk is exported and replays with zero model calls
- [x] A recorded gateway and committed recordings, so that the whole layer is tested in CI and the Linux image with no
  model key (P5 §4.2, §14.5); built 2026-10-08; scenario D's committed recordings are live since the afternoon of
  2026-10-08 (the session's stay scripted stand-ins by design, P5 §14.5)
- [x] **Exit:** a new sample scenario authored from a one-paragraph brief is reviewed, committed and replays green
  in CI (**met 2026-10-08**, scenario C: authored live, reviewed, committed with its plan, bindings and authoring
  record, replayed on both seeds in every load mode by the acceptance; the live subsection below); the nightly job
  stays under budget with prompt-cache hits above 80% (**met 2026-10-08**: USD 0.005 of 1.00 and 91.4 % on both live
  jobs of `InferenceLiveIT`, read as P5 §8.3 says); an interactive session on the sample is exported from its journal
  as a scenario that replays with zero model calls (**met headlessly** 2026-10-08, `SessionExportTest`; in the image
  by `InferenceEndToEndIT`; live by `InferenceLiveIT` item 6).

### Phase 5 code review (2026-10-08)

The method of Phases 2 to 4: three lenses, each run by one reviewer on the committed tree (`7e18e0c`, the whole build)
with a shared brief, and each verified by a sceptic who tried to refute every finding with probes in a worktree of the
tree and added what the lens had missed. **Contract fit** (L1): 8 findings (0 high, 5 medium, 3 low); the sceptic
raised F2 to high, split F3 (one medium, two documentation items), lowered F4 to a contract decision and added S1 (high),
S2 and S3 (low). **Secrets, safety and bounds** (L2): 14 findings (3 high, 6 medium, 5 low); the sceptic confirmed 13,
refuted half of F13 (resources are served uncut by §9.4), restated F3, F6, F8 and F14, and added S1 (high) and S2
(medium). **Tests and determinism** (L3): 11 findings (2 high, 5 medium, 4 low); the sceptic confirmed all eleven,
lowered F1 and F2 to medium and added S1 and S2 (low). Six items were seen through two lenses (a model's `_meta.confirm`,
the MCP export's `approvedBy`, the session budget, triage's row samples, the recording gateway's mask, the scenario-run
tool), which leaves about 33 distinct items. The most important:

- **The MCP scenario-run tool ran whatever a client's YAML named** (L2 F2 = L1 sceptic S1, high, live on the sample):
  through `skillroy_scenario_run`, and through `author --run`, a model could `call agent.disable {confirm: true}` (sent
  confirmed, D89 made the route live) or `call data.query` unguarded, with the rows readable through the run resource,
  while the planner's server is always `--mutating` and its model runs under `dontAsk`. Now one plan-policy check,
  shared by the tool and `author --run`, refuses before anything runs a plan whose steps or cleanup name a primitive the
  server's projection would not offer, or carry `confirm` or `_meta` (D96).
- **A key with a character a header cannot carry leaked through OkHttp's exception** (L2 F1, high): the gateway
  validated the key for blank only, OkHttp's `IllegalArgumentException` names the header's value, and the raw message
  reached `run.jsonl`, `junit.xml`, the console, an MCP tool error (that is, a model) and the CLI's stack trace. Now the
  key and the workspace id are refused outside printable ASCII with a message that names the variable, and any other
  runtime exception of an attempt is scrubbed.
- **A model's request could still confirm** (L1 F1 = L2 F5, medium, latent: the only destructive primitive today is
  `agent.disable`): a params-level `_meta` map survived into the call and the controller's client merged it into the
  request's `_meta`; only a MODEL origin's top-level `confirm` was stripped, and the scenario tests' fake agent could not
  show it. Now `CallRunner` drops a params-level `_meta` for every caller, every origin but PERSON is stripped of
  `confirm` and `_meta`, the compiler refuses `_meta`, the client ignores `confirm` inside `params._meta`, and the fake
  honours it the way the client did.
- **A session `check` was an oracle on masked values** (L2 sceptic S1, high): the matcher ran against the unmasked
  value and only the reported `actual` was masked, so a read-only MCP client could rebuild a credential character by
  character with `matches` and `contains`. Now the matcher runs against the masked value.
- **The allow-list was never checked against the schema** (L1 F2, raised to high): §5.2's checks were not implemented,
  and the guard's rule 4 trusted `otherColumns` to be complete, so a column added by a later release let a predicate on
  it run. Now the contributor (and the MCP server at start) reads each allow-listed table's columns through the source
  with a zero-row select and refuses a list that does not name them all, and a column a `RowMask` alters.
- **Triage sent row samples unmasked by column** (L1 F3 = L2 F3, high but latent: the sample's SQL selects no personal
  column) and **the `data_query` tool answered personal-column filters** (L2 F7, medium: a name rebuilt by prefix
  enumeration). Now triage sends no row sample at all, and the MCP server guards `data_query` with an allow-list copy
  in which personal and masked columns are unreferenceable (D99); the personal taint also escaped through `EXISTS`
  and `IN` subqueries in a select list (L2 sceptic S2, medium), closed in the analyser.
- **Decisions the review forced**: the MCP export writes tier (b) slots `REQUIRED` and its `approvedBy` argument is
  gone (L1 F4 = L2 F8, D97); `web_evaluate` is not offered by the mutating projection unless a person opts in, and
  never to the planner (L2 F6, D98); tier (b) resolves in a session count against the session budget (L1 F5 = L2 F9); the
  `inference-live` and `inference-record` profiles and the §15.3 live tests are marked not built until a key works
  (L3 F1: nobody can run them, and the "no code changes after a live re-record" claim was false; D110).
- **From the tests lens**: removing the session service's masks survived every suite (L3 F2; real-builder masking
  tests now cover the session, the summary, the overlay and the planner's catalogue); the export wrote a slot's
  arguments in journaled order, so a live re-record would have rewritten the committed export and failed the same
  build (L3 F3; sorted keys now); the §15.2 secret scan ran before the MCP and CLI-session checks and missed a secret
  inside JSON text (L3 F4); no accept-corpus statement executed on the sample's own schema (L3 F5); the MCP verb check
  passed by construction (L3 F6); the planner's process-tree kill was unproven (L3 F7).
- Smaller: `retry-after` overflow and negative values (L2 F4); `inference.jsonl` missing failures and sessions' tier (b)
  calls (L1 F6); settings accepted and ignored (L1 F7); contract text contradicting the build (L1 F8); the recording
  gateway's second pass being keys-only (L1 F3(2) = L2 F11 = L3 S2, now documented and given the mask where one exists);
  `StdioPipe` unbounded (L2 F13); the planner's files unbounded on disk (L2 F14); a timing bound that included client
  construction (L3 F8); CLI subprocesses leaked on a timeout (L3 F9); `ReResolverTest` hanging on a regression (L3
  sceptic S1); the parameter rule 6 not enforced on inline literals (L1 sceptic S2); the job budget's inline retries (L1
  sceptic S3). Judged and left as a documented limitation: the table-unaware personal rule, which over-masks
  `desk_setting.name` in two recordings and never under-masks (L2 F10).

**The fix wave**, four fixers in isolated worktrees from the committed tree, each change with a test that fails before
it (the fixers' own mutation runs are in their reports), integrated by patch in the order FA, FD, FB, FC with one
documentation conflict resolved by hand, then reconciled (FC had coded against FA's and FB's interfaces by reflection
while they were being written; the reconciler replaced those with direct calls, wired the allow-list check into the MCP
server's start and made FC's fixtures match their fake schemas):

- *The scenario side and the controller's client* (FA): a params-level `_meta` dropped for every caller, every origin
  but PERSON stripped of `confirm` and `_meta`, the compiler refusing `_meta`, the client ignoring `confirm` inside a
  params `_meta` (no `traceparent` travels that way any more; P1 §7 and P2 §11.4 amended), the scenario tests' fake
  agent honouring `_meta.confirm` so the acceptance test can see a bypass, the session check and (behind
  `RunOptions.withExpectationsOnMaskedValues`, which the MCP scenario-run tool and `author --run` set) a run's
  expectations evaluated on masked values, the export's arguments written with sorted keys (the committed export
  regenerated: three lines), `REQUIRED` written with `by: null`, and the contract text of L1 F8.
- *The inference module* (FB): the key and the workspace id refused outside printable ASCII and every other runtime
  exception of an attempt scrubbed; `retry-after` clamped; triage sends no row sample; `Gateways.open(settings, mask)`
  for the recording's second pass; `AllowListCheck` (a zero-row select per table, the row-mask probe) at bind, at the
  contributor's load and, through FC, at the MCP server's start (D100; scenario D's bindings files regenerated, the
  check's statements now in the provenance); the personal taint through `EXISTS`, `IN`, `ANY` and scalar subqueries in
  a select list; the metered `SessionService.resolve` with `BudgetCaps` (D101); failed calls as ledger entries (D102);
  `effort.*`, the model ids, `logPrompts` and `timeouts.plannerMs` threaded through; real-builder masking tests for the
  session, the summary, the overlay and the planner's vocabulary (the brief exempt, D103); the planner's process-tree
  kill proven with a forked child and a stepping clock; the planner's files bounded on disk; `ReResolverTest` bounded;
  the parameter rule's literal check; the job's inline retries reserved; the `NO_RECORDING` message corrected.
- *The MCP server and the CLI* (FC): the plan-policy check before `skillroy_scenario_run` and `author --run` (D96),
  verb tools that reach a never-offered primitive withheld and listed in `skillroy_catalogue`, `_meta` stripped at
  the tool boundary, the planner's scenario schema naming only what the host offers; MCP exports always `REQUIRED`
  (D97); `web.evaluate` and `web.waitFlag` behind `--allow-evaluate` and never for the planner (D98); `data_query`
  guarded by an allow-list copy with personal and row-masked columns unreferenceable (D99); the session's and the
  server's resolves routed through the metered entry; `inference.jsonl` written on close for sessions and the server;
  `triage --log-prompts` and `plannerMs` honoured; `StdioPipe` bounded (an 8 MiB line, a 16 MiB queue).
- *The sample's acceptance and the profile documentation* (FD): the live profiles and §15.3 marked not built with the
  documents telling the truth about a live re-record (D110); the §15.2 secret scan last, with a JSON-aware matcher
  (`SecretScan`); a lending-schema accept corpus of ten statements over all twelve allow-listed tables executed on the
  sample's database; the MCP verb check asserting a real effect (sign-on through the session entry, then sign-off);
  the end-to-end CLI subprocesses killed on a timeout.

The reactor after the wave and the reconciliation, and the final Linux matrix, are the tables under "Status at a
glance".

The review's reports, verdicts, briefs and fixer reports are with the session's working files (2026-10-08).

### Phase 5, the live items (2026-10-08, afternoon)

The owner's workspace-scoped key made the live items possible the same day the phase was confirmed. The method of the
night: contract first (`design/phase5-contracts.md`, the passages marked "the live session"; decisions D111-D113, B25,
confirmed by the owner on 2026-10-09), two implementers in isolated worktrees (the inference module with the root POM and the CLI; the sample's
scenarios module), one review lens with probes over the integrated change, a fix round, the Linux matrix before the
commits. The key lived in the operator's login shell and reached a JVM by name only (`zsh -lc` to export it,
`ci/run-in-linux.sh`'s pass-through into the container); it never touched the repository, the notes or a page.

- **BS13's live part** (`spikes/BS13/RESULT.md`): the probe rebuilt on SDK 2.70.0, every item answered, USD 0.032 (the
  "Base spikes" row above). Two facts changed the gateway: Haiku 4.5 refuses `output_config.effort` (no `effort` for
  the Haiku class now) and answers under the dated id `claude-haiku-4-5-20251001` (a dated id is its alias, priced as
  such, no fallback note); `claude-opus-5` joined the price table; `usage.iterations` is present whenever `fallbacks`
  is sent. The CLI's `--max-budget-usd` bounds an invocation's own spend and a resumed envelope's `total_cost_usd` is
  the session's running total: the planner records cost differences and gives a repair round what is left.
- **The record route** (`ScenarioDRecordIT`, `-Pinference-record`, natively, USD 0.029): both seeds' supervisor
  requests answered by `claude-sonnet-5-5` at effort low on the first attempt, each statement through the guard and the
  desk's access check to the committed SQL's supervisor; seed A 475 in / 877 out / 5024 written to the cache, 9.5 s,
  USD 0.0223; seed B four seconds later 476 / 515 / 5024 read, 4.0 s, USD 0.0071. The stand-in generator is gone; the
  tests pin what a live answer must hold and read the label (`recorded 2026-10-08`) from the committed file.
- **`ClaudeGatewayLiveIT`** (`-Pinference-live`, natively, 508.6 s, USD 0.0053): item 1 wrote 1187 tokens to the cache
  and read them 2.4 s later, `countTokens` 1207 = the reported 1207; item 2's batch of two ended after 504 s with both
  results, the first request writing the 1187-token prefix (5 min TTL inside the batch) and the second reading it.
- **The first image run of `InferenceLiveIT`** found four things, all fixed before the second run: the model answered
  a correct statement with confidence 0.7, under the 0.8 gate, and the slot ended "no candidate" without saying why
  (the default threshold is 0.6 since D113, measured on four live answers 0.8 / 0.8 / 0.8 / 0.7, and the binder's
  message now names the rejections); the API refused the session's strict tools, 20 union-typed parameters against a
  limit of 16 (the gateway drops `strict` above the limit); the claude CLI refused the planner's `--json-schema` for
  its 2020-12 `$schema` key (the planner strips it and reports the CLI's stderr when no envelope comes back); the test
  of item 5 had not created its directory. The review lens found the envelope file masked without the environment's
  credential values and whole objects redacted under secret-looking keys (fixed: masked by value, the key rule on
  strings only), and the record route not atomic (fixed: staged, checked and scanned before it replaces anything).
- **The second image run of `InferenceLiveIT`** (arm64 natively, 485.7 s, five tests green, USD 1.11): item 3 bound
  scenario D live through the agent channel to the committed supervisor (USD 0.0219; 475 in, 835 out, 5024 written to
  the cache); item 4's nightly job: scenarios A and B `UNCHANGED` with no request, then scenario D twice in a row,
  `CHANGED` with the same supervisor each time (a live answer carries a new request id), USD 0.0054 and 0.0051, **cache
  hits 91.4 % on both jobs** (each batch request read the 5024-token prefix; the first had been written by item 3's
  standard request minutes before, so the batch tier did read a standard entry here, against BS13's one observation),
  the batches 154 s and 184 s; item 5 ran `skillroy author` on the brief of §14.3 against the desk: exit 0 in 119 s,
  Opus 5.5, 35 turns, no repair, USD 0.907, the scenario compiled and resolved; item 6's session turn ended `DONE` in
  20 s after nine actions (a snapshot, the catalogue, two resolves, a scan, a deposit the desk refused, the DUE key, the
  deposit, a snapshot), nine calls, USD 0.166, and its export of four steps compiles; the secret scan found nothing in
  26 files.
- **Scenario C** (`return-refunds-deposit`, §14.3): authored for real in the image with `skillroy author
  --id return-refunds-deposit --run` (Opus 5.5, 43 turns, no repair, USD 0.80, 161 s with the checks; the plan
  compiled, every slot resolved, the scenario ran green on seed A), reviewed by the orchestrator on the owner's
  instruction and kept as written (the `review` section of its `.authoring.json` says why: the brief's "needs no
  induction" cannot be stated through `LendableItem`, a DSL gap below, and holds by the resolver's order on both
  seeds; "back to zero" is the net of the two postings the desk makes), its bindings approved on both seeds with
  `skillroy resolve` (tier (a) only, label `approve`), the planner's envelope kept as the fixture of §15.1 item 11
  beside the pre-review YAML; wired into the acceptance by a third implementer (`ScenarioC`, the plan and bindings
  tests, the replay check of §15.2 item 3 in every load mode, the planner replay test). The owner's own review of the
  committed scenario stays open.
- **Spend of the live session**: about USD 2.05 in all (BS13 0.032 and its probes 0.008, the record route 0.029, the
  CLI dry run of D 0.022, the gateway live test 0.005, the first image run 0.04, the second 1.11, scenario C's
  authoring 0.80, the approvals nothing).

### Phase 6: hooks, Byteman, Windows, OTel, hardening

- [ ] `skillroy-agent-spy` and `skillroy-agent-hooks`: hook engine, probes, health, hook-catalogue format, post-detach byte verification (BS3 passed with conditions 2026-10-09)
- [ ] `skillroy-agent-byteman` (optional and unbundled, B8)
- [ ] JDK 25 target through `JAVAAGENT`: no JEP 451 warning; capability matrix equal to the JDK 8 matrix. The
  handshake through `JAVAAGENT` on a Java 25 target was verified on 2026-10-02; no result for the warning or the
  matrix comparison is recorded
- [ ] Windows: `JAVAAGENT` as the logged-on user; `SEMANTIC`, `ACTION`, `COMPONENT` and `DISPATCH` rungs and paint
  capture verified; `ROBOT` excluded; attach from another logon session documented as unsupported; a `.bat`
  launcher for the CLI; the access-control fixes S7 and S15 verified
- [ ] OTel export (B11)
- [ ] Repeat the security review before the first release (the findings of 2026-10-02 are closed by H1-H3 in
  Phase 1.x)
- [ ] Tenant-pack overlay proven on a second variant of the sample lending desk: resolvers, constants and hook
  catalogue overridden, scenarios unchanged
- [ ] **Exit:** a hook is installed and removed on the sample lending desk with byte-identical restore; the sample
  scenarios pass unchanged with hooks enabled and with hooks disabled, and on a JDK 25 target loaded through
  `JAVAAGENT`; the repeated security review is signed off.

The B12 item that stood here (`COMPONENT` with window activation for UI-verification steps) is now Phase 1.x
(A28, A29), as an opt-in per scenario.

### Phase 7: vision bolt-on and the vision loop of interactive sessions

Decision B16. Nothing is built; the seams are reserved now in the design catalogue (`swing.hitTest`,
`swing.pointer` and their `web.*` counterparts, the `swing.capture` result fields, the capability prefix
`inference.`, the plan field `inference`, typed content parts in the gateway, `Strategy.LLM_VISION`, the verbs
`aiAct`, `aiExpect`, `pointer`, `expectImage`, the agent SPI `RegionResolver`). Interactive use with run-time
inference is decision B15; its session service is Phase 5.

- [ ] BS9-BS12 passed
- [ ] Agent: `swing.hitTest`, `swing.pointer`, the extended `swing.capture`, their `web.*` counterparts
- [ ] Controller: `skillroy-vision` (optional) with the V1-V3 loop inside interactive sessions, the recorder,
  `RegionResolver` support, pixel-anchored replay with an image guard, `expectImage`, the capability
  `inference.vision`
- [ ] **Exit:** a vision-driven session on the sample is recorded into ordinary steps that replay with zero model
  calls; a plan compiled with `inference: run` is reported as skipped in `replay`; a guarded visual expectation
  yields `DEGRADED` in `replay`; V3 runs only with its explicit opt-in.

### Not yet assigned to a phase

- [ ] `skillroy-trace-viewer` (static HTML over the run bundles)
- [ ] Second `Transport` implementation (newline-delimited JSON-RPC over TCP or a Unix socket, agent dials out)
- [x] Licence audit of all dependencies: `docs/licence-audit.md` (2026-10-02). Its packaging gaps are Phase 1.x;
  the planned pieces it lists in §8 are audited when they are adopted
- [ ] Confirm control of the domain behind the `ai.skillroy` groupId (B2)
- [ ] A longer soak, and a soak on a target other than the sample lending desk

## Open SPI and DSL gaps

What is still open after Phase 1. Sources: the Phase 1 reviews, and needs raised while a second domain pack and an
adapter module were worked out against this SPI outside this repo (worded generically here). "1.x" means a small
change that fits before Phase 2. "Closed (built)" means the change was built in Phase 1.x
(2026-10-02) and the contract states it as built. "P2 §n" is a section of `design/phase2-contracts.md`, whose
§15 takes gaps 4, 12 (partly), 20 (partly), 25 and 27 and defers 9-11, 13, 18, 19, 21, 22, 24 and 28 past Phase 2
without a new phase; their Phase column still shows where they were first placed. "Closed (built)" marks the gaps
Phase 1.x closed on 2026-10-02; they stay in the table until the merged tree's verification is recorded. Gaps 34-47
were recorded by Phase 4 (`design/phase4-contracts.md` §13, which also says what Phase 4 did for gaps 21, 22, 28 and
31); "P4 §n" is a section of that contract.

Closed by Phase 1, and therefore no longer listed: the provider-executed form of `DataSourceProvider`,
`SyncPolicy.promptTopics`, cleanup calls and `recover`, `DomainPack.preflight()`, guarded expectations with
`DEGRADED`, the `launchProperties` and `artifacts` keys, a host object for an `EMBEDDED` module (`hostContext`),
how `swing.dismiss {close}` closes a dialog, the per-call completion event (`Sync.EVENT`), an idempotent and
reflectively callable `EmbeddedAgent.start`, and redaction of secrets in JVM arguments and system properties.

| # | Gap | Why it matters | Proposed shape | Phase |
|---|---|---|---|---|
| 1 | Every call costs about 45 ms on the JDK HTTP server | Nagle plus delayed ACK on the server's two-segment responses. On 2026-10-02 `basic-loan` took about 2.0 s on a Java 8 target and about 0.3 s on a Java 25 target: the stall shows on Java 8, not on JDK 25 | H1: the agent's own listener, one write per response with `TCP_NODELAY` (R1 option (c); B17, confirmed 2026-10-06) | closed (built): 1.4-5 ms per call |
| 2 | `MODAL_OPEN` from a step call is `ERROR` / `ENVIRONMENT` | A click blocked by an unexpected dialog is an application state, like an unexpected prompt, not an environment problem | A30: `FAIL` / `ASSERTION`, the detail carries the dialog's title and text (R2; B18, confirmed 2026-10-06) | closed (built) |
| 3 | Stale discovery and token files are never deleted | Files of a JVM that was killed stay in the discovery directory for ever | A23: the agent deletes files of its own pid with another start time; the controller's `Discovery` deletes files whose process is gone (R3; B18, confirmed 2026-10-06) | closed (built) |
| 4 | Fixture secrets are written in clear | A scenario's assistant password appears in `run.jsonl` and `bindings.json` | A25: a fixture field whose key looks secret is masked in every run output, by key and by value (R4; B18, confirmed 2026-10-06). Phase 2 makes fixtures unable to hold secrets: `credentials.yaml` and the credential join (P2 §4, §8.4) | closed (built, Phase 2) |
| 5 | Driver messages are returned verbatim by `data.query` | Some JDBC drivers echo parameter values in `SQLException` messages | The fix of S22 (replaces A26): SQL state, error code and exception class with a generic message; the driver's text is neither returned nor logged unless the provider opts in and no `MaskPolicy` is registered | closed (built) |
| 6 | Dialogs that are not windows | `DialogTextExtractor` is consulted, and `swing.dismiss` acts, only for `java.awt.Dialog` windows; an application that draws its message boxes inside another window is invisible to both | A27: a `DialogLocator` SPI returning in-window dialog roots that `swing.dialogs` lists and `swing.dismiss` accepts | closed (built) |
| 7 | Capabilities cannot be withdrawn | A module that goes away, or a seam that disappears, leaves its capabilities declared | A18: `CapabilityRegistry.declare` returns `AutoCloseable`, plus `undeclare`; the core drops a module's capabilities after uninstall | closed (built) |
| 8 | `APP` before the application thread exists | Unspecified: a primitive with thread `APP` called before the provider can supply its thread | A19: `ThreadPolicyProvider.available()`; `WORKER` for read-only primitives, `NOT_READY` for mutating ones | closed (built) |
| 9 | One application lane | A slow `DataSourceProvider.query` on `APP` blocks every other `APP` primitive | Named lanes, or a per-provider thread policy for `data.query` | 2 |
| 10 | A thread policy for the application's own engine thread at a quiescent point | `ThreadPolicyProvider` supplies one application worker and can only forbid the engine thread; a module that must run a bounded task from the application's idle callback builds that itself | A policy `APP_QUIESCENT` backed by a provider method that runs a bounded task at the next idle point | 2 |
| 11 | Query timeout is advisory, no cancellation | `DataSourceProvider.query` receives `timeoutMs` but nothing can stop it | A cancellation signal passed to `query`, and a way for the provider to state whether it enforces the timeout | 2 |
| 12 | Positional sources | `QueryResult` column names are all or nothing; a source that cannot name columns reports `c1..cn` | Phase 2 (built): `db.*` checks match columns by position (P2 §7.4). Reading such a source by name stays open: an optional `columns:[string]` parameter on `data.query`, or names derived from an explicit select list | partly closed (built); the rest later |
| 13 | Session-scoped data reads | A guarded stored-procedure `CALL` followed by a `SELECT` on a session temporary table on the same connection cannot be expressed: `data.query` takes one statement and closes a JDBC connection after each call, and a strictly read-only role may lack temporary-table rights | A provider-declared, allow-listed "prepared read" that the provider runs as a unit; `SqlGuard` gets the matching session form | 2 |
| 14 | Options are a start-time snapshot | A module cannot see an option the host sets after the agent started | A20: `AgentContext.option(key)` with a live fallback to the `skillroy.agent.<key>` system property | closed (built) |
| 15 | Module-supplied redaction keys | The `redact` option is set at launch; a domain module that knows its application's secret property names cannot add them | A21: a `RedactionKeys` SPI that adds key patterns | closed (built) |
| 16 | `EmbeddedAgent.start` blocks the host's calling thread | A host plugin hook that must return quickly waits up to the start timeout | A22: `EmbeddedAgent.startAsync` | closed (built) |
| 17 | `INPUT_REJECTED` and the `input.rejected` topic | The contract did not say whether a primitive that answers `INPUT_REJECTED` should also publish `Topics.INPUT_REJECTED` | A33: for an input sent through a primitive the error alone is the contract; `input.rejected` is for inputs the application rejects on its own | closed (written into the contract) |
| 18 | Semantic-input result contract | A `<domain>.input` result has no standard fields for "queued but not yet consumed" and "value dropped or coerced by the application", and a call either succeeds or raises an error | Standard result fields plus a warning channel in call results that the executor records or fails on | 2 |
| 19 | Conditional or optional planned calls | A step whose next input depends on which prompt the application raises (an optional second prompt; unrelated prompts tolerated before the expected one) cannot be written: plans are linear | Either an "only if the next ready event matches" form, or a stated rule that such logic lives in one agent-side primitive | 2 |
| 20 | Expectations relative to an earlier step | "At least one new element since step N" cannot be asserted: the path grammar reads current facts only | Phase 2 (built): a slot captured from an event (P2 §3.4). Capturing a fact into a named slot at a step and asserting a delta against it stays open | partly closed (built); the rest later |
| 21 | Event expectations | No regex on an event's source, no match inside a string payload value, no "this event must not have occurred since step N"; `sync.await` matches top-level values by text only | An `events` fact root with filters, and a negated wait | 2 |
| 22 | Rung ladders for pack verbs | A pack verb cannot declare ordered alternatives with a capability condition per rung and have the rung used recorded, as `swing.click` does; `DeviceAdapter` actions do not say whether they used the device's physical path or a semantic shortcut | A ladder form in `PlannedCall`, and a `rung` field in device action results | 2 |
| 23 | No event sync for the base UI verbs | `click` and `type` are always `SETTLE`, which covers the event-dispatch thread only; a scenario adds a `waitFor` after each UI step | A32: an optional `await:` on the base `click`, `type` and `press` verbs | closed (built) |
| 24 | Scenario-level preconditions on application configuration | `preflight()` is pack-wide and `launchProperties` checks JVM system properties only; a scenario cannot require an application setting | Decide whether probed capabilities in `requires:` are the intended mechanism, or add a pack-evaluated `preconditions:` key with arguments | 2 |
| 25 | Expressions over slot values | A slot value multiplied or divided by a constant margin cannot be written; references are plain `$ref`s | Decided in the Phase 2 contract (D6): no expression form; computed values are oracles, margins are arguments of the pack's own query (P2 §3.2) | closed (decided, Phase 2) |
| 26 | Oracle cross-check outcomes | When a bind-time cross-check is guarded by an absent capability, or an oracle was calibrated from a first real run, the scenario should not be a plain `PASS` | The Phase 3 contract (§6.2, D38): a guarded cross-check that cannot run makes `DEGRADED` the best outcome; the calibrated-oracle case belongs to Phase 5 | 3 (specified) |
| 27 | Masking of rows read over the controller-side JDBC channel | `MaskPolicy` is agent-side only | `RowMask` (P2 §7.3), applied by the JDBC channel and by the binder to every row sample | closed (built, Phase 2) |
| 28 | Pack-defined artifact kinds | `artifacts:` implements `capture` only; another kind is logged and ignored | An SPI in `DomainPack` that produces a named artifact | 2 |
| 29 | A pack-contributed CLI subcommand | For example an exporter to an application's own test format | Decide; `ServiceLoader` of a small command SPI | later |
| 30 | `AgentConnection.await` cannot report a JSON-RPC error | Anything but `TIMEOUT` surfaces as `IllegalStateException` | A31: `AgentRpcException` in the signature | closed (built) |
| 31 | `ROBOT` type mode, `OPTION_VALUE` dismissal, `swing.events` | In the design's catalogue, not in the Phase 1 contract | Add them to the contract when their phase starts | 6 |
| 32 | Hook catalogue: an `enabled` flag per hook | A pack catalogues a target and leaves it off | In the design (§2.6); build with the hooks module | 6 |
| 33 | Third-party isolation when the host ships an older copy of the same library | The agent loader is child-first only for the agent's own package prefix | Relocate under that prefix; covered by the added BS3 case | 6 |
| 34 | A `PAINT` capture must guard embedded-browser components | BS8: `printAll` throws for an off-screen JCEF canvas and paints a windowed one blank, so a Swing capture of a window with such a browser would fail | The guard: the capture paints part by part, a part that throws becomes a placeholder listed in `skipped` (P1 §6) | closed (built, Phase 4) |
| 35 | A browser with no view and no seed is invisible | JxBrowser has no process-wide list of browsers: an engine whose browser is in no window is found only through a seed (browser-spi §F23) | The pack's `BrowserRegistryAdapter` hands it in (P4 §9.2 is the example); nothing more in the base | later, if a target needs it |
| 36 | `web.dom.click` is a DOM click, not a pointer click | `el.click()` fires no `pointerdown`, `mousedown` or `mouseup`; a page that listens for those does not react | `INPUT` (reserved) and `web.pointer` with the vision bolt-on | 7 |
| 37 | `web.resize` cannot change a windowed browser's Swing bounds | It sets a protocol-level viewport override; a page that measures its component sees the override only | `applied: COMPONENT` is reserved for a variant that resizes the component | later |
| 38 | `web.waitFlag` answers an expression that throws differently on the two paths | The agent polled on and answered `ready: false` with `lastError` (the base verb then ends `SYNC_TIMEOUT`); the CDP path failed at once with `SCRIPT_ERROR` (`ASSERTION`) | A35 (4.x, built 2026-10-08; P4 §16.1): one page-side guard (`WebLib.flagExpression`) on both paths; a run-time throw is "not ready" and polling goes on, `lastError` when the wait ends not ready, a compile error `SCRIPT_ERROR` at once | closed (built); the `jcef` negatives `flag-never-ready` and `flag-does-not-compile` run in the next matrix |
| 39 | A hidden JCEF browser has no bitmap | Chromium renders nothing for a browser that is not showing, so `web.bitmap` on such a browser is refused with `CAPABILITY_UNAVAILABLE` (the SPI as specified promised a bitmap whatever the view's state) | None in the base (a technology limit); the aria snapshot and the page text of such a browser still read | open |
| 40 | The SPI has no reload and no bitmap source | `web.reload` and the bitmap's `source` used the module-internal `BrowserExtras` of the built-in adapters; a third-party adapter was reloaded through `location.reload()` and reported `VENDOR` (D67) | A37 (4.x, built 2026-10-08; P4 §16.3): `WebBrowser.reload(ignoreCache)` and `bitmapSource()` as default methods, `BitmapSource {PROTOCOL, VENDOR}`, the built-in adapters override, `BrowserExtras` deleted, kit item 12 | closed (built); kit item 12 against real JCEF pending the next matrix |
| 41 | Three member names of the JxBrowser descriptor are unverified | `getChromeVersion` or `CHROME_VERSION_MAJOR`, `FrameLoadFailed.error` or `errorCode`, and `Browser.set` matched by its parameter count could not be checked without the product (P4 §4.5) | The contract kit against the real product, in a repository that holds a licence | wall (a JxBrowser licence) |
| 42 | No rate limit on the re-probe after `window.opened` | The re-probe was coalesced and cheap (probes only, no Swing walk), and the review's proposed one-a-second limit was not built (P4 §4.1) | A38 (4.x, built 2026-10-08; P4 §16.4): at most one cycle a second, the rest of the second waited out on the agent worker that runs it, no event dropped, no new thread or timer | closed (built) |
| 43 | A JCEF older than 2024-01 is served on neither path | Without `getDevToolsClient` the adapter is `UNAVAILABLE` and reports no endpoint, so not even a configured port is used (P4 §4.5) | An amendment that reports the port of such a JCEF for the CDP path alone | later, if a target needs it |
| 44 | Windowed JCEF on a Java 8 target is unverified | BS8: no native browser was created, no error logged; the strategy says "windowed, Java 8: unverified" | Find the cause (BS8's open question) | open |
| 45 | A call the CDP path serves reads the agent's sequence three times | The path read `lastSeq` before and after the primitive and the executor read it as well (P4 §5.2) | A36 (4.x, built 2026-10-08; P4 §16.2): the path reads none; the executor's read fills `seq.before` and `seq.after` of the call record | closed (built) |
| 46 | A killed JVM leaves a traced CDP path's trace directory | The private, owner-only `skillroy-cdp-trace-*` directory is deleted at close; a JVM killed meanwhile leaves it in the temporary directory (P4 §6.4) | Delete stale ones at the next open, or accept and document (done) | accepted |
| 48 | Over CDP, `web.waitFlag` on a page whose content security policy forbids `eval` fails at Playwright's second poll | Playwright's `waitForFunction` evaluates its own predicate with `eval` from the page's timer after the first poll, which such a policy refuses before the guard runs; it surfaces as `INTERNAL_ERROR` with an unhelpful message (found by A35, 2026-10-08; the agent path's `Runtime.evaluate` is not subject to the policy) | A clearer message, or a first-poll-only fallback to the agent path for such pages | later, if a target shows it |
| 47 | JxBrowser's observer registration and `cdpEndpoints` call the vendor without a bound | Every other vendor call of the adapter runs on its own threads with a bound; whether `on`, `set` and the endpoint read can block needs the product (P4 §4.5) | Bound them as the accessors are, once a run against the product shows the need | with gap 41 |
| 49 | The sample's `LendableItem` query cannot say "an item that needs no induction" | The planner was briefed to choose such an item for scenario C (P5 §14.3) and could only choose a plain counted item (`perDay: false`); the brief holds by the resolver's order on both seeds, not by the scenario's words (`ItemsTriggering` has an `inductionRequired` argument, `LendableItem` has none) | An `inductionRequired` argument on `LendableItem` (pack 0.5.0; every committed plan carries the pack version) | later, with the next pack change |

Small items for the module owners, none of them a contract matter: `swing.snapshot` shows Swing's own made-up
names such as `null.layeredPane`; module version strings are inconsistent (`0.1.0` and `0.1.0-SNAPSHOT`); in
`EMBEDDED` mode a module jar that is also on the host class path is logged at WARN; a client that disconnects
mid-request leaves a full stack trace in the agent log; after an asynchronous stop a restart from the same thread
can still see the old agent's system properties; a negative `sinceSeq` event stream has no resume point until its
first event; `AttachSession`'s Javadoc still calls the argument limit a truncation; `Node` has no `path` field.

## Environment notes

Kept generic on purpose: nothing here names a machine, a user or a local path.

- The reactor builds with any JDK >= 25 and Maven 3.9 (an enforcer rule refuses an older JDK). It is developed on
  JDK 26; the controller baseline (Java 25 bytecode) is exercised on a real JDK 25 in the Linux test image
  (`ci/Dockerfile`, `eclipse-temurin:25-jdk`, 25.0.4.1 in the runs of 2026-10-02). The previous JDK 21 image is no
  longer used. The CLI's launcher refuses a Java older than 25.
- Display tests and anything that launches the sample lending desk with a window must run in the Linux image
  (`ci/run-in-linux.sh`, WM-less Xvfb 1024x768x24). Never run with `-Dskillroy.headless=false` natively: it opens
  windows on the desktop.
- A Java 8 target needs a Linux x86-64 JDK 8, mounted into the image at `/opt/jdk8` through the environment
  variable `SKILLROY_JDK8_HOME`. Its host path must never be written into a file of this repo.
- Under QEMU emulation (the amd64 image on an arm64 host) the JDK's `jspawnhelper` fails now and then ("Bad code from
  spawn helper (Failed to exec spawn helper), error: 0"; seen once on 2026-10-08 when an end-to-end check spawned the
  CLI). Run the emulated test JVMs with `-Dskillroy.test.extraArgLine=-Djdk.lang.Process.launchMechanism=FORK`; the
  arm64 image, run natively, does not need it.
- On an arm64 development machine the image runs under emulation: a whole-reactor run takes about 7 to 8 minutes
  (407 s and 467 s on 2026-10-02).
- The build is run offline (`-o`) once the local Maven repository is filled. **Never `mvn install` this project.**
  A partial build runs with `-am` (`mvn -o verify -pl <module> -am`), and a module whose tests need the agent home
  declares `skillroy-agent-dist` (type `zip`, test scope, every transitive dependency excluded) as a test dependency,
  so that `-am` builds the distribution in the same reactor. The trap this avoids, found 2026-10-07: a partial build of
  the CLI module, which lacked that declaration, took the zip from a stale snapshot in the local Maven repository and
  unpacked jars older than the HMAC handshake into its test agent home, so that `AttachIT` failed with a 401 on
  `/ping`. The CLI POM declares the zip now, as the lending and the web host scenarios POMs do, and the stale
  snapshots were deleted.
- Downloads, authorised by the owner in general terms ("install what is needed"), all done:
  - Phase 5 (2026-10-08, spike BS13): the Anthropic Java SDK 2.34.0 with its tree (and the 2.69.0 core jar alone, for
    comparison; the build pins the current line and resolves it, P5 §2.2), JSqlParser 5.4, the MCP Java SDK 2.0.1 in
    both JSON variants, in the local Maven cache; their licences are listed in `spikes/BS13/RESULT.md` F-11 in the
    audit's format and go into `licence-audit.md` §5 when the modules declare them.
  - BS8 and Phase 4: the JCEF natives for Linux x86-64 and arm64 (152 and 168 MB) are in the local Maven cache (the
    web host modules declare them behind the profile `jcef`, `-Dskillroy.jcef.platform` choosing one), and
    Chromium's fifteen run-time libraries are in the image (`ci/Dockerfile`; the list is in `spikes/BS8/RESULT.md` §3
    and `licence-audit.md` §7). Also kept outside the repository: a Linux aarch64 Temurin JDK 8, so that an arm64
    build of the image can run the Java 8 configurations natively on an arm64 development machine.
  - Phase 4: Playwright Java 1.63.0 with its driver and driver bundle (the bundle about 204 MB) in the local Maven
    cache. No browser is downloaded: BS5 is met against JCEF's Chromium (D53), and the manual stock-Chromium procedure
    (`spikes/BS5/README.md`) uses a Chromium its user provides.
  - Their licences are audited in `licence-audit.md` §5 (Playwright and JCEF moved there from §8 when Phase 4 built
    them); §8 lists what is still planned.
- **Images.** `skillroy-test-java-ci:local` is the image `ci/Dockerfile` builds for amd64, the project's CI
  architecture; since BS8 it holds Chromium's run-time libraries, so it also runs the profile `jcef`. An arm64
  development machine runs it under emulation. An arm64 build of the same Dockerfile (here tagged
  `skillroy-test-java-ci:bs8-arm64`, chosen with `SKILLROY_CI_IMAGE` and `SKILLROY_PLATFORM=linux/arm64`) runs
  natively and several times faster; it needs `-Dskillroy.jcef.platform=linux-arm64` and, for the Java 8
  configurations, a Linux aarch64 JDK 8 as `SKILLROY_JDK8_HOME`.
- **Playwright's driver** is extracted by the first `Playwright.create` of a JVM, about 140 MB on Linux x86-64 and
  136 MB on arm64, into `PLAYWRIGHT_DRIVER_TMPDIR` or else the Java temporary directory, and is deleted at the JVM's
  exit. The web host acceptance points it at its module's `target/playwright-driver` and extracts it once per test JVM
  (0.7 to 1.7 s in the runs of 2026-10-07).

## Walls

Work that cannot proceed without the project owner, or without something only the owner can provide.

| Wall | Decision | What waits |
|---|---|---|
| The push | B1 | The initial commit exists (2026-10-06); the push to the private remote `Skillroy-AI/skillroy-test-java` waits for the owner's word. History is permanent once pushed |
| Model provider API key | B7 | **Resolved 2026-10-08, and the live items done the same day.** The first key (organisation-level) was refused by the API without an `anthropic-workspace-id` header (BS13, the night of 2026-10-08), so nothing live ran that night and the profiles were marked not built (D110). The owner's workspace-scoped key, exported as `ANTHROPIC_API_KEY` in the operator's own environment only (never a file of this repository, never a property, never a command line; the contract's §2.3), answered the models endpoint with no workspace header; in the afternoon of 2026-10-08 it carried BS13's live part, scenario D's live re-record, the live profiles and §15.3, scenario C's authoring and the nightly job's live figures ("Phase 5, the live items" above), about USD 2.05 in all. Nothing waits on it. |
| A JxBrowser licence | B9 | Real verification of the `jxbrowser` adapter. The adapter exists (built 2026-10-07) and passes the contract kit against the neutral fake, plainly and with the init-script chaining option; the kit (`skillroy-agent-web`'s `test-jar`) runs against the real product only in a repository that holds a licence, outside this repo, through a `WebAdapterFixture` over a real engine. That run also settles three member names of the descriptor and whether some vendor calls can block (gaps 41 and 47). Phase 4 reached its public exit without it. The owner (2026-10-06): a licence is available, so that verification can follow in the private overlay's repository |
| A Windows host | B13, Phase 6 | Verification of the fixes S7 and S15 (not verified on Windows); the Windows path of Phase 6. The owner (2026-10-06): later, a Windows 11 machine, which also runs the tenant layers' testing under Windows and under WSL2 |

Not walls any more: the licence, the holder's name, the first commit and the remote (B1), the final names and the
domain behind the groupId (B2) and the sample domain with its deposits (B10) are decided. B17-B20 are confirmed
(2026-10-06), and the two vetoes inside B19 are built. B21 (the fifteen answers of the Phase 4 contract) was
confirmed by the owner on 2026-10-07, and so was B22 (the eleven decisions the Phase 4 build took without the
owner, D59-D69) the same evening. B23 (the Phase 5 contract's answers) and B24 (the decisions of its build and review) were confirmed by the owner
on 2026-10-08, every row.

The review items of the end-to-end review, now decided (gap numbers refer to the table above):

| Id | Subject | State |
|---|---|---|
| R1 | How to remove the 45 ms per call (gap 1) | Decided as option (c), the agent's own HTTP listener (H1); confirmed 2026-10-06 (B17); built |
| R2 | Classification of `MODAL_OPEN` from a step call (gap 2) | `FAIL` / `ASSERTION` (A30); confirmed 2026-10-06 (B18); built |
| R3 | Clean-up of stale discovery and token files (gap 3) | Removed by agent and controller (A23); confirmed 2026-10-06 (B18); built |
| R4 | Masking of fixture secrets in `run.jsonl` and `bindings.json` (gap 4) | Masked now (A25); confirmed 2026-10-06 (B18); built |
| R5 | A Java 25 target in the Linux image | Done: the image is JDK 25 (B3) and the gate was run on 2026-10-02 |

## Next steps I would take

1. **The push**, when the owner says (the commits of 2026-10-06 and the Phase 4 commit of 2026-10-07 are local);
   the repeated security review before the first public release.
2. **Phase 4 follow-ups**: the JxBrowser kit run in the private repository that holds the
   licence (the wall "A JxBrowser licence"); why windowed JCEF on a Java 8 target creates no browser (unverified since
   BS8, gap 44) and why a `srcdoc` iframe never loads in the image; then the open gaps of the table above (38, 40, 42
   and 45 were closed by the Phase 4.x amendments on 2026-10-08; gap 48 is new). D59-D69 are B22, confirmed by the
   owner on 2026-10-07.
3. **Phase 5 is built, reviewed, fixed and closed live** (`design/phase5-contracts.md`, 2026-10-08; B23 and B24
   confirmed by the owner on 2026-10-08; the live items of the afternoon under "Phase 5, the live items"). B25 (D111-D113:
   the live profiles' mechanics, the envelope file, the confidence threshold 0.6) was confirmed by the owner on
   2026-10-09. Open for the owner: the review of the committed scenario C. Open from the review, not fixed: the table-unaware personal rule (L2 F10, a documented
   over-masking), a row mask whose effect depends on the value (not found by the start probe), a verb that compiles with
   none of the probe argument sets (offered as is), key nullability of allow-listed tables (unchecked); and gap 49 (the
   item query's missing induction argument). Worth a re-measure later: the model's self-reported confidence at other
   efforts (D113 rests on four answers), and the session's strict tools once the provider's union limit moves.
4. **The display-test focus flake is explained and mitigated** (2026-10-08, A39; "Open flake" under "Status at a
   glance"). Still open there: the native reason why the Java 8 toolkit thread does not flush an EDT-issued focus request
   while idle, and the garbage-collection-dependent skip of `SwingDisplayTest.refBecomesStaleOnceTheComponentIsCollected`
   (3 of 60 runs on 2026-10-08, once under emulation on 2026-10-03).
