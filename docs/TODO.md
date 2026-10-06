# TODO: base framework

What exists, what was proven and what is open for the base framework (`skillroy-test-java`), as of
2026-10-06. Decisions are in `DECISIONS.md` (B1-B20); the design is in `docs/design/` (Phase 1 and Phase 1.x as
built in `phase1-contracts.md`, Phase 2 as built in `phase2-contracts.md`, Phase 3 as built in
`phase3-contracts.md`); research notes are in `docs/research/`; the
dependency licence audit is `docs/licence-audit.md`.

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

- **Open flake (two occurrences, 2026-10-02 and 2026-10-06; both on a Java 8 test JVM).** In the first agent-side run on
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
  `COMPONENT` rung's window activation (a small amendment, not taken here).
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

| Area | State | Next step |
|---|---|---|
| Repo split | Done 2026-10-01. This repo holds only tenant-neutral material; after the port the neutrality check printed "base repo is clean" | Run it again after the documentation update and before every commit |
| Sample application | Replaced 2026-10-02 (B10): the sample lending desk, modules `skillroy-sample-lending-{app,agent,pack,scenarios}`, domain id `lending`, scenarios `basic-loan` and `basic-loan-ui`. The whole code base and the docs were swept for the first sample's vocabulary | B10's remaining question (a desk that takes deposits) is the owner's |
| Platform | Controller side on Java 25 (`--release 25`, B3); agent side on Java 8 (B4). The Linux test image is JDK 25 (Temurin 25.0.4.1); the previous JDK 21 image is no longer used. Native development JDK is 26 | none |
| Git and publication | The initial commit was made on 2026-10-06 (everything in the tree, one commit, DCO sign-off); nothing pushed. The remote `Skillroy-AI/skillroy-test-java` is private for now. `LICENSE` (Apache-2.0), `NOTICE`, `CONTRIBUTING.md` (DCO sign-off, no CLA) and the header in every source file (`ci/license-headers.sh`, with the holder's name since 2026-10-06) exist; the root POM carries the publication metadata | Push when the owner says; before the first public release, the repeated security review |
| Licence audit | `docs/licence-audit.md`: everything the project distributes, bundles or depends on is compatible with Apache-2.0. The packaging gaps (licence files inside the jars and zips, third-party attribution in the CLI distribution, a `<licenses>` element in the root pom) are closed (Phase 1.x, built 2026-10-02) | none |
| Decisions | B1-B14 answered by the owner 2026-10-02; B15 and B16 follow from the answers; B17-B20, taken without the owner, were confirmed by the owner on 2026-10-06, with two vetoes inside B19 (Q10, Q14) built the same day; **B21** (the Phase 4 contract's fifteen answers) taken without the owner later on 2026-10-06, provisional | The owner's answer to B21 (the interview page) |
| Design docs (`docs/design/`) | `phase1-contracts.md` as built, with the Phase 1.x, Phase 2 and Phase 3 passages marked "(1.x, built 2026-10-02)", "(Phase 2, built 2026-10-02)" and "(Phase 3, built 2026-10-03)". `phase2-contracts.md` and `phase3-contracts.md` as built (2026-10-03: every deviation of the build and every fix of the review marked in place). Overview: roadmap with Phase 1.x, Phases 2 and 3 built, the new Phase 4 and Phase 7. **`phase4-contracts.md` specified 2026-10-06, not built** | Write the §14.1 changes into the Phase 1-3 contracts and the overview when the Phase 4 build starts; as-built pass after it |
| Research notes (`docs/research/`) | Round 1 carried over, finding ids unchanged; `embedded-browser-spi` and `vision-input` new (2026-10-02). Pruned of local-environment detail | None |
| Security review | Run 2026-10-02 on the pre-port tree: 34 confirmed findings (4 medium, 30 low); the fixes H1-H3 are built (Phase 1.x). The Phase 2 code review of 2026-10-03 had a secrets-and-safety lens (below) | Repeat the review before the first release |
| Spikes | BS7 done. **BS8 run 2026-10-06, passed with conditions** (`spikes/BS8/RESULT.md`). BS1 answered for Java 8 and Java 25 targets on Linux, parts open. BS2 answered in part. BS4 answered, parts open. BS3, BS5, BS6 open. BS9-BS12 new, not started | See "Base spikes" |
| CI for this repo | Not chosen. `ci/Dockerfile` and `ci/run-in-linux.sh` are what a pipeline would run; since 2026-10-06 the image holds Chromium's run-time libraries and the runner takes `SKILLROY_PLATFORM` | Needs a JDK 8, a JDK >= 25 and Xvfb on the build agents; the JCEF natives (152 MB per platform) in the Maven cache for the Phase 4 profile |

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

Results so far come from the Phase 1 tests and the end-to-end review; no `spikes/BS<n>/` directories exist yet.
Thresholds marked "proposed" are starting points, not facts from research. Research references such as
`attach F1` mean finding `F1` in `docs/research/attach-instrumentation.md`. Measurements marked "before the switch"
were made with the first sample application and the controller on Java 21; they stay as measurements and are not
re-run. BS8 comes from `research/embedded-browser-spi.md` R4 (B9); BS9-BS12 from `research/vision-input.md` R10
(B16), which numbers them BS8-BS11.

| Id | Proves | Status |
|---|---|---|
| BS1 | The agent loads into stock JVMs in all load modes, across JDK versions | **Answered for Java 8 and Java 25 targets on Linux** (Java 21 targets before the switch). Open: cross-user attach and jattach, Windows |
| BS2 | The control channel survives a soak; the agent is isolated from the host class path | **Answered in part**: 3-minute soak per JDK (before the switch, on the JDK server), bounds and isolation tested; the agent's own listener (H1) is built and its per-call cost measured (1.4-5 ms). Open: the one-hour soak, loader collectability, a soak on the agent's own listener. Finding: about 45 ms per call on the JDK HTTP server on Java 8 and Java 21 targets; absent on a Java 25 target; gone with H1 |
| BS3 | Byte Buddy retransformation with inline Advice and a bootstrap `Spy`; original bytes restored on detach | Open; partly shown in round 1 on toy programs |
| BS4 | Which Swing input rungs work on an Xvfb display with no window manager | **Answered** for Java 8 and 21 (before the switch); the end-to-end check built on it passed on Java 8 and Java 25 targets. Open: the ROBOT rung (not built; see BS9), a large-sample focus statistic, the display-test flake |
| BS5 | Playwright attaches over CDP to an already-running Chromium safely | Open |
| BS6 | `SqlGuard` accepts and rejects correctly across SQL dialects | Open (the agent-side statement guard of `data.query` is a different, simpler check and is built) |
| BS7 | `javac --release 8` still works on current JDKs | Done 2026-10-01 |
| BS8 | A real JCEF browser runs in the Linux test image and the in-process DevTools path works | **Run 2026-10-06, passed with conditions**: both rendering modes on a Java 25 target, off-screen on a Java 8 target; the DevTools channel verified; Chromium 152's first-run terms dialog must be pre-accepted; `Target.getTargetInfo` does not answer (the main frame id is the target id); windowed JCEF on Java 8 creates no browser. `spikes/BS8/RESULT.md` |
| BS9 | Robot input on the test display and on a desktop session | New, not started; comes before Phase 7 |
| BS10 | Routed in-JVM dispatch behaves like Robot | New, not started |
| BS11 | `PAINT` capture is deterministic | New, not started |
| BS12 | Grounding accuracy and cost of a vision loop on the sample | New, not started; needs live model calls (B7) |

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
- **Not yet shown.** Bootstrap `Spy` visibility from a custom loader, the byte comparison on detach, a JDK 25 target.

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

### BS6 `SqlGuard` across dialects

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

**Contract: `docs/design/phase4-contracts.md`** (specified 2026-10-06, not built; B21 holds its provisional answers).
Decision B9: one in-process SPI that wraps both JCEF and reflective JxBrowser access. This replaces the earlier
plan of a JxBrowser-only module that could be tested against a stand-in only: JCEF is open source, so a real
embedded browser can run in the public image.

- [x] BS8 passed with conditions (JCEF in the Linux test image; 2026-10-06; `spikes/BS8/RESULT.md`): windowed and
  off-screen on Java 25, off-screen on Java 8; the sentinel files, the target id and the `srcdoc` finding are
  conditions the contract carries
- [ ] `skillroy-cdp`: connect wrapper, liveness probe, external deadline, page mapping by target id, readiness, tracing
- [ ] The `WebEngineAdapter` / `WebBrowser` SPI in `skillroy-agent-api` (`…agent.api.spi.web`), optional operations
  behind `WebCapability` flags; `BrowserRegistryAdapter` made technology-neutral
- [ ] `skillroy-agent-web`: the `web.*` primitives written once against the SPI, and two reflective adapters, `jcef`
  (on `CefBrowser.getDevToolsClient()`, no debug port needed) and `jxbrowser` (lines 7, 8 and 9); no vendor code
  and no compile-time dependency
- [ ] A small JCEF sample module behind a Maven profile; the adapter contract kit published as a test artifact
- [ ] Every web verb has a CDP implementation and an in-process implementation; the one used is recorded
- [ ] BS5 passed
- [ ] **Exit:** over 50 runs, attach to a running Chromium with a deliberately frozen page target completes or
  aborts within the external deadline; one `web.*` scenario passes over CDP against a stock Chromium; the adapter
  contract kit passes against a real JCEF browser in the Linux image; the `jxbrowser` adapter's reflection plumbing
  passes against a neutral fake. Running the kit against the real JxBrowser needs a licence and happens outside
  this repo. `INPUT` and `RAW_PROTOCOL` stay reserved.

### Phase 5: AI layer

- [ ] `skillroy-inference` (created here, above `skillroy-scenario`); the `InferenceGateway` SPI with typed content
  parts (text, image, tool use, tool result) and the Claude implementation (B7)
- [ ] Tier (b) resolvers: model-authored SQL behind `SqlGuard` (BS6 passed); `MaskPolicy` applied before any prompt
- [ ] Approve flow on first use; budget meter per scenario and per nightly job
- [ ] Nightly batch re-resolve that opens a PR with the bindings diff; `skillroy-cli re-resolve`
- [ ] `skillroy-mcp`: tool projection of the catalogue plus domain tool packs, stdio transport
- [ ] Authoring planner through headless `claude -p`; opt-in failure triage
- [ ] Interactive profile (B15): the executor's single-call entry and the step journal (names `ExecutorSession`,
  `StepJournal` reserved by the Phase 2 contract), export of a journal to scenario steps and bindings, the session
  service, a per-session budget
- [ ] **Exit:** a new sample scenario authored from a one-paragraph brief is reviewed, committed and replays green
  in CI; the nightly job stays under budget with prompt-cache hits above 80%; an interactive session on the sample
  is exported from its journal as a scenario that replays with zero model calls.

### Phase 6: hooks, Byteman, Windows, OTel, hardening

- [ ] `skillroy-agent-spy` and `skillroy-agent-hooks`: hook engine, probes, health, hook-catalogue format, post-detach byte verification (BS3 passed)
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
Phase 1.x closed on 2026-10-02; they stay in the table until the merged tree's verification is recorded.

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
- On an arm64 development machine the image runs under emulation: a whole-reactor run takes about 7 to 8 minutes
  (407 s and 467 s on 2026-10-02).
- The build is run offline (`-o`) once the local Maven repository is filled.
- Downloads still to come, authorised by the owner in general terms ("install what is needed"):
  - BS5: a Chromium binary and Playwright's driver bundle (about 194 MB, extracted on first use).
  - BS8 and Phase 4: **done for BS8 on 2026-10-06**: the JCEF natives for Linux x86-64 and arm64 (152 and 168 MB)
    are in the local Maven cache (the spike's own POM declares them; the Phase 4 sample will do the same behind the
    profile `jcef`), and Chromium's fifteen run-time libraries are in the image (`ci/Dockerfile`; the list is in
    `spikes/BS8/RESULT.md` §3 and `licence-audit.md` §7). Also downloaded for the spike, kept outside the repository:
    a Linux aarch64 Temurin JDK 8, so that an arm64 build of the image (`SKILLROY_PLATFORM=linux/arm64`) can run
    the Java 8 configurations natively on an arm64 development machine.
  - Each needs a licence check when it is adopted (`licence-audit.md` §8); the spike's artifacts are listed there.

## Walls

Work that cannot proceed without the project owner, or without something only the owner can provide.

| Wall | Decision | What waits |
|---|---|---|
| The push | B1 | The initial commit exists (2026-10-06); the push to the private remote `Skillroy-AI/skillroy-test-java` waits for the owner's word. History is permanent once pushed |
| Model provider API key | B7 | Anything live in Phase 5, so all of Phase 5 beyond interfaces and replay; after it the live parts of Phase 7 and BS12. The owner (2026-10-06): available when Phase 5 starts |
| A JxBrowser licence | B9 | Real verification of the `jxbrowser` adapter, by the contract kit in a repository that holds a licence, outside this repo. Phase 4 reaches its public exit without it. The owner (2026-10-06): a licence is available, so that verification can follow the Phase 4 build in the private overlay's repository |
| A Windows host | B13, Phase 6 | Verification of the fixes S7 and S15 (not verified on Windows); the Windows path of Phase 6. The owner (2026-10-06): later, a Windows 11 machine, which also runs the tenant layers' testing under Windows and under WSL2 |

Not walls any more: the licence, the holder's name, the first commit and the remote (B1), the final names and the
domain behind the groupId (B2) and the sample domain with its deposits (B10) are decided. B17-B20 are confirmed
(2026-10-06), and the two vetoes inside B19 are built. Open for the owner: B21, the fifteen provisional answers of the
Phase 4 contract (`DECISIONS.md`, the interview page).

The review items of the end-to-end review, now decided (gap numbers refer to the table above):

| Id | Subject | State |
|---|---|---|
| R1 | How to remove the 45 ms per call (gap 1) | Decided as option (c), the agent's own HTTP listener (H1); confirmed 2026-10-06 (B17); built |
| R2 | Classification of `MODAL_OPEN` from a step call (gap 2) | `FAIL` / `ASSERTION` (A30); confirmed 2026-10-06 (B18); built |
| R3 | Clean-up of stale discovery and token files (gap 3) | Removed by agent and controller (A23); confirmed 2026-10-06 (B18); built |
| R4 | Masking of fixture secrets in `run.jsonl` and `bindings.json` (gap 4) | Masked now (A25); confirmed 2026-10-06 (B18); built |
| R5 | A Java 25 target in the Linux image | Done: the image is JDK 25 (B3) and the gate was run on 2026-10-02 |

## Next steps I would take

1. **The push**, when the owner says (the commit of 2026-10-06 is local); the repeated security review before the
   first public release.
2. **Phase 4, contract first**: BS8 is done (2026-10-06, passed with conditions) and the contract is written
   (`design/phase4-contracts.md`, specified, not built; its fifteen provisional answers are B21, on the interview
   page). Next: the owner's answers to B21, then the build in the manner of Phases 2 and 3 (implementers in isolated
   copies by module, review with sceptics, matrix, as-built docs). Before the build: write the §14.1 changes into the
   Phase 1-3 contracts and the design overview. Open from BS8: why windowed JCEF 152 on a Java 8 target creates no
   browser, and why a `srcdoc` iframe never loads in the image.
3. **Phase 5 groundwork that needs no model key**: the `InferenceGateway` SPI shape and the `inference.` seams are
   reserved; everything live waits for B7 (a wall).
4. **Investigate the display-test flake** (`HostSafetyDisplayTest` on 2026-10-02 and `WindowActivationDisplayTest` on
   2026-10-06, both on the Java 8 test JVM; the hypotheses and the retry mitigation are under "Open flake" in "Status at
   a glance"; `HostSafetyDisplayTest`, one failure in three agent-side runs on
   Java 8 on 2026-10-02; not seen in the four agent-side runs since), on an otherwise idle machine; and the
   garbage-collection-dependent abort of `SwingDisplayTest.refBecomesStaleOnceTheComponentIsCollected` seen once
   under emulation on 2026-10-03.
