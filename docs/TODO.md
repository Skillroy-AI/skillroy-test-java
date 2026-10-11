# TODO: base framework

What exists, what was proven and what is open for the base framework (`skillroy-test-java`), as of
2026-10-11. Decisions are in `DECISIONS.md` (B1-B31); the design is in `docs/design/` (Phase 1 and Phase 1.x as
built in `phase1-contracts.md`, Phase 2 as built in `phase2-contracts.md`, Phase 3 as built in
`phase3-contracts.md`, Phase 4 as built in `phase4-contracts.md`, Phase 5 as built in `phase5-contracts.md`, Phase 6
as built in `phase6-contracts.md`, Phase 7 as built in `phase7-contracts.md`, code-reviewed, security-reviewed (delta)
and fixed on 2026-10-10/11; B29 and B30 confirmed and B31 signed off by the owner on 2026-10-11);
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
The live table follows the Phase 6 paragraph below; the matrix of the final Phase 5 tree is the table after it.

**On 2026-10-09 the Phase 6 contract was written and Phase 6 was built the same day** (`design/phase6-contracts.md`, now
as built; its twenty-two answers are decision **B26** and the 62 decisions the build took, D160-D221, are **B27** (with
the 65 of the review's fixes, D222-D286, below: 127); both were taken without the owner on 2026-10-09 and were provisional while the build ran on
them (the owner asked for progress), and were confirmed by the owner on 2026-10-10). The method was that of Phases 2 to 5: spike BS3 (the hook go/no-go) passed
with conditions the same morning, seven readers' maps, a contract reviewed through three lenses with a sceptic each and
revised, then six packages built in isolated worktrees and merged into `main` in this order, the native reactor green
after every landing: telemetry (TL), hooks and spy (HK), the controller, pack and sample (CP), Byteman and Windows (BW,
behind spike BS14, passed with conditions) and, over the merged four, the fixes of the repeat security review's first
pass in two packages (FX-2, FX-1; S35-S62, one high, six medium, 21 low). What exists: the optional hook engine on a
shaded, relocated Byte Buddy with its bootstrap `Spy` and a byte-identical restore on detach (installed and removed on
the sample desk on a Java 8 and a Java 25 target); Byteman loaded in process with no listener; a Java 25 target through
`JAVAAGENT` with no JEP 451 line and a capability matrix equal to the Java 8 one; OpenTelemetry export from the
controller, off by default; the tenant-pack overlay proven on the desk's branch edition; the Windows path built where
Linux can test it (not verified on a Windows host). **Phase 6 was then reviewed, fixed and security-reviewed twice the
same day**, with the same method, the fixes integrated on a branch (`p6/int`) so that the sceptics of the security review
read a stable `main`: a code review of three lenses with a sceptic each (19 raw findings and 10 added by the sceptics, none
refuted: one high, eight medium, 20 low; section "Phase 6 code review (2026-10-09)") and its fix wave in four packages (FA the
hooks engine, FB the Byteman modules, FC the controller and telemetry, FD the sample's typed-keys display flake); then pass 2
of the repeat security review over all eight lenses (section "Security review (repeat, pass 2, 2026-10-09)": 38 confirmed
findings, S63-S100, one high, 12 medium, 25 low, and one refuted) and its six fix rounds (SA the control channel, SB the web
views, SC the model boundary and the model as an actor, SD Windows and SQL Server, SE hooks and Byteman, SF telemetry and run
outputs). The 65 decisions of these fixers are D222-D286 and join B27. Every finding of medium or above of both passes is
fixed; eight lows are accepted, each with a release note in `SECURITY.md`, and the repeat review is signed off by the owner
(B28, 2026-10-10). The native
reactor was green after every landing. The native reactor on `main` at `4f9962f` and the Linux matrix of the final tree
(arm64 twice, amd64 once): green on every leg: native 4692 tests, 0 failures, 0 errors, 493 skipped; arm64 rounds 2 and 3 and amd64 round 1 (the FORK launch mechanism under emulation), each with the whole reactor on Java 25 targets 4853/0/0/105, on Java 8 targets 4853/0/0/132 and the agent side on a Java 8 test JVM 1668/0/0/26 (reports read: hooks 70 and Byteman 43 tests on `java.version` 1.8.0_*); `-fae`, so no leg stopped early (the table "Phase 6 final matrix" below). **On 2026-10-10 the owner confirmed B26 and B27 and signed off the repeat
security review (B28), no veto.** **Still to do:** the push (on the owner's word, B1); the Windows host; Phase 7 (built 2026-10-10, code-reviewed, security-reviewed and fixed 2026-10-10/11; the paragraphs after this one). **On 2026-10-10** the planner's stdin route was checked live (P5 section 15.3 item 5 natively on macOS, and a forced `--resume` repair round; both green, USD 2.05; the last row of the live table below), and the four Phase 7 spikes ran: BS9 (Robot input) and BS10 (routed dispatch) passed with conditions, BS11 (`PAINT` capture determinism) passed with conditions, BS12 (grounding accuracy and cost of a vision loop) passed live, USD 5.64; the live spend of those two days, with the three live runs of Phase 7's acceptance, is USD 8.12 (below). The tables that follow
are those of Phases 1 to 5.

**Later on 2026-10-10 the Phase 7 contract was written, reviewed and built** (`design/phase7-contracts.md`, committed as `2eebeed`; with `design/threat-model.md` extended): the vision bolt-on and the vision loop of interactive sessions, written from seven readers' maps and the four spikes of the same day, reviewed through three lenses with a sceptic each (94 findings, 91 applied, 3 refuted) and rechecked twice (36 more applied). Its decisions are D287-D358, the amendments A61-A66, and its thirty-nine answers are decision **B29**, taken without the owner and provisional while the build proceeded on them, as it did on B26; the owner confirmed every answer on 2026-10-11 (`B29 ok`, no veto). The build ran in four worktree packages, VS (the agent's Swing side, the sample's desk and pack), VW (the web side), VC (the scenario, controller, JUnit and CLI side) and VM (the gateway, the session seam, `skillroy-vision`, the MCP and session options), and landed on `main` the same evening in the order VC (`71efa8e`), VS with the regeneration step 2b (`482288d`, which carried VM's step 1b), VW (`e7a4cb3`), VM (`7af44eb`), then one closing commit of VM for D425 (`e11ae08`, the head of this section). The packages took 72 decisions of their own, **D359-D430**, which are decision **B30**, provisional in the same way and confirmed by the owner on 2026-10-11 with the decisions of the paragraph that follows. The native reactor was green on the head (surefire 4,454 tests, 0 failures, 118 skipped; failsafe 512, 0 failures, 458 skipped); the packages ran their image legs on the arm64 build of the Linux image on both Java lines (section "Phase 7" below); what followed is in the next paragraph.

**Then, on 2026-10-10 and 2026-10-11, Phase 7 was code-reviewed and fixed, then security-reviewed and fixed** (section "Phase 7 code review (2026-10-10)" and "Security review (Phase 7 delta, 2026-10-10/11)"). The code review (three lenses with a sceptic each, on `7af44eb`) found 30 distinct problems (2 high, 9 medium, 19 low) and the first live run of `VisionLiveIT` one more (LV-1, the session never told the model which slots the person had bound); three fixers closed them (FA the agent's Swing side `9f44718`, FC the vision module and the session `791e0ae`, FB the scenario, controller and CLI side `c690bef`), the first Linux matrix of the merged tree found a race in Phase 6's hook engine that had failed once in Phase 6 (a `hook.<id>` declared after readiness read back pending; fixed agent-side, FX `a134159`, D449), and the second live run passed 7 of 7 (USD 0.155). The security delta review (eight lenses with a sceptic each over everything Phase 7 changed since `af29e59`) found **48 standing findings, S101-S148: 2 high, 13 medium, 33 low**; four fix packages (SG the agent's Swing side `5d6fdd8`, SH credentials, slots and the client's plan form `edba708`, SJ the image baselines, the bindings files and the run output `73e406a`, SI the vision module, the session service, the web reads and the CDP path `056a007`, and SG's follow-up `c94aade`) fixed every medium and high and 31 of the 33 lows; S142 and S144 are accepted by the owner (B31, 2026-10-11). The decisions of the fix wave, the race fix and the security fixes are **D431-D486** and join B30. On 2026-10-11 the owner confirmed B30 (128 decisions, D359-D486; `B30 ok`, no veto), so the built behaviour stands, and signed off the delta review, **B31**, the same day. The third live run, on the final code `c94aade`, passed 7 of 7 (USD 0.148). The Linux matrix of `c94aade` is green on `c94aade` (arm64 twice on every leg, amd64 on the Java 25 leg); the amd64 Java 8 legs, which waited for the x86-64 JDK 8's volume, ran later on 2026-10-11 and are green on `bc1e250` (the same code plus two test fixes and gaps 94 and 99), `keypad-image`'s Java 8 key holding on amd64 ("Phase 7 final matrix"). **Still to do:** the push (on the owner's word, B1); the Windows host; the two amd64 Java 8 legs of that matrix.

The live runs of 2026-10-08 (afternoon) and, in the last four rows, of 2026-10-10 and 2026-10-11, with the key passed through by name; every
figure from the ledger entries the tests printed:

| What | Where | Result |
|---|---|---|
| BS13's live part (`java -jar spikes/BS13/target/bs13-probe.jar live`, SDK 2.70.0) and the CLI item | natively | every item answered, USD 0.032 plus about 0.008 of raw probes (`spikes/BS13/RESULT.md`) |
| `ScenarioDRecordIT` (`-Pinference-record`): scenario D's requests re-recorded | natively (the JDBC channel, the in-JVM database) | both seeds answered by `claude-sonnet-5-5` at effort `low`, the committed SQL's supervisor found; USD 0.029 (0.0223 + 0.0071, the second reading 5024 cached tokens) |
| `ClaudeGatewayLiveIT` (`-Pinference-live`), §15.3 items 1 and 2 | natively | 2 tests, 508.6 s: a 1187-token cache write then read 2.4 s later, `countTokens` exact (1207); a batch of two ended after 504 s, its second request reading the first's prefix; USD 0.0053 |
| `InferenceLiveIT` (`-Pinference-live`), §15.3 items 3 to 6, first run | the arm64 image | 2 failures, 2 errors (the confidence gate at 0.8 refusing a correct answer at 0.7, the strict tools above the provider's 16-union limit, the planner's `$schema` key, a missing test directory): all fixed |
| `InferenceLiveIT`, second run | the arm64 image, the Linux `claude` 2.1.294 mounted | 5 tests, 0 failures, 485.7 s: scenario D live through the agent channel to the committed supervisor (USD 0.022); the nightly job A+B `UNCHANGED` and D twice `CHANGED` with the same value, USD 0.0054 and 0.0051, **cache hits 91.4 % on both**, batches 154 s and 184 s; `skillroy author` on the brief exit 0 in 119 s (Opus 5.5, 35 turns, USD 0.907); one session turn `DONE` in 20 s after nine actions (USD 0.166), its export compiles; 26 files scanned clean; USD 1.11 in all |
| Scenario C authored (`skillroy author --id return-refunds-deposit --run`) | the arm64 image | Opus 5.5, 43 turns, no repair, USD 0.80, 161 s with the checks; compiled, resolved, ran green on seed A; reviewed and kept; bindings approved on both seeds with no model call |
| The planner's stdin route live (P5 section 15.3 item 5; the fix of S93, P2-L7-F3), 2026-10-10 | natively on macOS (Java 25.0.1), `claude` 2.1.286 | Run A, `InferenceLiveIT` item 5 and its secret scan alone (`-Dit.test=InferenceLiveIT#theAuthorCommandAuthorsTheBriefOfScenarioC+noSecretInAnythingThisClassWrote`): exit 0 in 159 s, 49 turns, no repair, compiled and resolved, USD 1.108. Run B, `skillroy author --claude-command <a logging wrapper> --budget-usd 1.70` with the first envelope's first verb renamed to an unknown one: two `-p` invocations, each with its prompt on stdin (14625 and 374 bytes; no text of the brief, the instructions or the problems in any argument; each command line ending with `--max-budget-usd`), the second `-p --resume <session> … --max-budget-usd 0.80` with the schema's problem as its prompt; the session resumed and answered with the original document; `repairs: 1`, 43 turns (41 + 2), exit 0, compiled and resolved; ledger 0.8965614 + 0.0483568 = 0.9449182, the last running total; both envelopes in the envelope file; USD 0.945. **New fact:** a resumed envelope's `total_cost_usd` and `modelUsage` are the session's running totals, while its `num_turns`, `usage` and `duration_ms` are its own, which is how the planner reads them. No key in any of 105 files scanned. Not verified live: a repair started by a compile problem alone (the same code path), a failed repair, Windows. USD 2.05 in all |
| `VisionLiveIT` (`-Pinference-live`, P7 §13.3), **run 1**, 2026-10-10 on `e11ae08` (the build) | the arm64 image, Java 25 desk, `ci/run-in-linux.sh mvn -o verify -pl samples/skillroy-sample-lending-scenarios -am -Pinference-live -Dskillroy.headless=false -Dit.test=VisionLiveIT` | items 3 (the fallback header coexists with the toolset: `END_TURN`, USD 0.0107), 5 (`skillroy_look` `DONE`, USD 0.022; the player `PASS` on `loan-table-visual-check` with `YES` and on the false statement with `expect: NO`, USD 0.0045 each) and the secret scan (19 files) passed; items 1, 2 and 4 (Sonnet 5.5 V1 USD 0.029, the toolset V2 USD 0.057, Haiku 5.5 V1 USD 0.0028) ended `QUESTION`: the model read the person's resolved `$item` as an unfilled placeholder because the session never lists the bound slots (**finding LV-1**, fixed by FC); item 6 skipped. USD 0.131 |
| `VisionLiveIT`, **run 2**, 2026-10-10 on `791e0ae` (FA and FC merged) | the same | **7 of 7**: items 1, 2 and 4 each `DONE` in 3 UI actions (Sonnet V1 USD 0.052, 7.2 s; the toolset V2 with the legend USD 0.057, 5.3 s; Haiku V1 USD 0.0028, 6.1 s), item 3 as in run 1, item 5 `skillroy_look` `DONE` (USD 0.023) and the player `PASS` with `verify` on both statements (USD 0.0047 each), item 6 the export of item 1 replays with zero model calls and an empty ledger (exit criterion 1 met live), secret scan 26 files. USD 0.155 |
| `VisionLiveIT`, **run 3**, 2026-10-11 on `c94aade` (the final code: build, fix wave, race fix and security fixes) | the same | **7 of 7**: item 1 (V1, Sonnet 5.5 at `low`) `DONE` in 6.3 s and 3 UI actions (`lending_enter`, `lending_key`, `lending_deposit`), USD 0.045; item 2 (V2 toolset with the legend) `DONE` in 5.8 s and 3 UI actions, USD 0.057; item 4 (V1, Haiku 5.5) `DONE` in 6.4 s and 3 UI actions, USD 0.0029; item 3 the fallback header coexists with the toolset (`END_TURN`, USD 0.0107); item 5 `skillroy_look` `DONE` (USD 0.023) and the player `PASS` with `verify` on both statements (USD 0.0045 and 0.0047); item 6 the export of item 1 replays with zero model calls; the secret scan over 26 files. USD 0.148 |

The live spend of 2026-10-10 and 2026-10-11 is USD 8.12: the planner check 2.05 (run A 1.108, run B 0.945; each run counted as
the last envelope's `total_cost_usd`, equal to the sum of the ledger entries), BS12 5.64 (the sum over every call of usage
times price; "BS12" under "Base spikes") and the three `VisionLiveIT` runs 0.131, 0.155 and 0.148 (each the class's own sum of
its ledger entries). The items are rounded: 2.05 + 5.64 + 0.131 + 0.155 + 0.148 = 8.124; with the unrounded 7.697 of the first two
the total is 8.131.

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
- **`LendingUiDisplayTest.typedKeysFillTheEntryFieldAndEnterSubmitsIt` (matrix 2026-10-08 and 2026-10-09, arm64, Java 25
  test JVM): explained and fixed in the test (Phase 6 review fix wave, FD).** Not the A39 focus loss: `JTextField`'s Enter
  binding submits the text field that most recently had the focus, not the event's target, and the test dispatched its Enter
  before the freshly shown frame had given the entry field the focus (a round trip to the X server, under 1 ms or about
  21-32 ms after the frame shows), so the Enter was lost and no engine event followed. The test now waits for the field's
  focus-gained event (30 s, re-requesting once a second, as A39), and `enterDoesNotSubmitAnEntryFieldThatNeverHadTheFocus`
  pins the lost Enter. In the arm64 image, one fresh JVM per run: 5 failures in 240 runs before (Java 8 2/100 warm, 1/40
  fresh container; Java 25 0/60 warm, 2/40 fresh container), 0 in 380 after (D234, D235). The same property holds for
  `swing.type` DISPATCH with `enter` (gap 60).
- **Scenario E's capability-matrix hash ("one matrix for every run of this target"; once in Phase 6 on 2026-10-09, seen by
  security fixer SD, seed A, and once in the first arm64 round of the Phase 7 matrix on `c690bef`, seed B, both in the hooks leg
  on Java 25 targets): explained and fixed agent-side (Phase 7, FX, D449; the open entry of Phase 6 is closed).** The installing
  run (scenario A's `replay`, which installs the pack's catalogue with `hook.install`) read the matrix right after the
  install, and the core holds a capability declared after the target is ready pending (`UNAVAILABLE("not probed yet: the target
  is not ready")`) until a posted worker probe has run; under CPU contention the read won, so that run archived some
  `hook.<id>` pending and every later run (`alreadyInstalled`) saw them `OK` (advice): two hashes for one target. The matrix
  failure's first hash `sha256:9dc6810f…` is exactly `hook.line.amount` and `hook.waiver.evaluated` pending and nothing else
  different. Only the hooks leg declares anything after readiness, which is why only it ever failed; the Phase 6 occurrence,
  whose run directories were lost, is attributed to the same race (the same check in the same leg, and nothing of SD's fix
  enters a capability probe). The engine now sets each `hook.<id>` to its probe's first
  answer when it declares it, so `hook.install` answers only after every declared `hook.<id>` holds a state of P6 §4.6
  (`design/phase6-contracts.md` §4.6, "As built (Phase 7, FX)"). Arm64 image, two hooks targets per iteration, under 14 busy
  loops: **22 of 30 targets failed before** (Java 25; 15 of 15 iterations), 4 of 6 on Java 8 targets; **after, 0 of 30 loaded
  and 0 of 20 unloaded** (Java 25), 0 of 10 loaded and 0 of 6 unloaded (Java 8): 0 of 66. The check (`ResolutionChecks`) stays
  (P6 §13.2 item 4, D130) and now also compares the records' `agentHash` and names every differing entry on a failure. The same
  race was latent in `HookChecks` item 1, the session's and the MCP server's install, and a guard on a hook in the installing
  run. Gap 92 records what is left (a late declaration by an agent module outside this repository).
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
| `skillroy-vision` (Phase 7, optional) | yes | n/a (Java 25) | the loop of the tiers V1-V3 against a scripted gateway and a fake session (the parts, the limits, the recording), the scenario player; 59 tests after the security fixes (41 after the code review's fix wave, 32 after `e11ae08`); the module is exercised end to end by `InferenceEndToEndIT` item 8 and `VisionLiveIT` (profile `inference-live`; run live three times, the last on `c94aade`: 7 of 7, see the Phase 7 checklist) |
| `skillroy-sample-web-host`, `-web-host-agent` (Phase 4, profile `jcef`) | yes | yes | options, sentinels, pages, registry, the agent module's seeds; with a display the host itself and the kit against real JCEF, one surefire execution per rendering mode |
| `skillroy-sample-web-host-scenarios` (Phase 4, profile `jcef`) | yes | targets only | committed plans up to date, the pack; with a display the end-to-end acceptance (`WebHostEndToEndIT`, eight launches) and the frozen-target soak (`CdpAttachSoakIT`) |

| Area | State | Next step |
|---|---|---|
| Repo split | Done 2026-10-01. This repo holds only tenant-neutral material; after the port the neutrality check printed "base repo is clean" | Run it again after the documentation update and before every commit |
| Sample application | Replaced 2026-10-02 (B10): the sample lending desk, modules `skillroy-sample-lending-{app,agent,pack,scenarios}`, domain id `lending`, scenarios `basic-loan` and `basic-loan-ui`. The whole code base and the docs were swept for the first sample's vocabulary. Since Phase 4 (2026-10-07) a second sample target, the JCEF web host (`skillroy-sample-web-host`, `-web-host-agent`, `-web-host-scenarios`, behind the Maven profile `jcef`), hosts embedded browsers for the web acceptance | None (the owner kept the desk as it is, 2026-10-06) |
| Platform | Controller side on Java 25 (`--release 25`, B3); agent side on Java 8 (B4). The Linux test image is JDK 25 (Temurin 25.0.4.1); the previous JDK 21 image is no longer used. Native development JDK is 26 | none |
| Git and publication | The initial commit was made on 2026-10-06 (everything in the tree, one commit, DCO sign-off); nothing pushed. The remote `Skillroy-AI/skillroy-test-java` is private for now. `LICENSE` (Apache-2.0), `NOTICE`, `CONTRIBUTING.md` (DCO sign-off, no CLA) and the header in every source file (`ci/license-headers.sh`, with the holder's name since 2026-10-06) exist; the root POM carries the publication metadata | Push when the owner says (B1); the repeated security review is signed off (B28, 2026-10-10); the Phase 7 delta review is signed off (B31, 2026-10-11) |
| Licence audit | `docs/licence-audit.md`: everything the project distributes, bundles or depends on is compatible with Apache-2.0. The packaging gaps (licence files inside the jars and zips, third-party attribution in the CLI distribution, a `<licenses>` element in the root pom) are closed (Phase 1.x, built 2026-10-02). Updated 2026-10-07 for Phase 4: the Playwright and JCEF sets (in no distribution), the new modules, and the agent home's seventh jar, inspected | none |
| Decisions | B1-B14 answered by the owner 2026-10-02; B15 and B16 follow from the answers; B17-B20, taken without the owner, were confirmed by the owner on 2026-10-06, with two vetoes inside B19 (Q10, Q14) built the same day; **B21** (the Phase 4 contract's fifteen answers) taken without the owner later on 2026-10-06 and confirmed by the owner on 2026-10-07; **B22** (D59-D69, the Phase 4 build's decisions) confirmed the same evening. **B23** (the Phase 5 contract's eighteen answers, D70-D87) and **B24** (the build's and the review's decisions, D88-D104, D110) taken without the owner on 2026-10-08 and confirmed by the owner the same day; **B25** (the live session's D111-D113) taken without the owner in the afternoon of 2026-10-08 and confirmed by the owner on 2026-10-09. **B26** (the Phase 6 contract's twenty-two answers, D114-D159) and **B27** (the Phase 6 build's 62 decisions, D160-D221, and the 65 of its code review's fix wave and the security fix rounds, D222-D286) taken without the owner on 2026-10-09 and confirmed by the owner on 2026-10-10; **B28** (the repeat security review's sign-off) signed off by the owner on 2026-10-10. **B29** (the Phase 7 contract's thirty-nine answers, D287-D358) taken without the owner on 2026-10-10, provisional while the build proceeded on it, and confirmed by the owner on 2026-10-11 (`B29 ok`, no veto; Q25, Q27 and Q34, which rested on the owner's taste or knowledge, among them); **B30** (the Phase 7 build's 72 decisions, D359-D430, and the 56 of its code review's fix wave, the race fix and the security fixes, D431-D486: 128 in all) taken without the owner on 2026-10-10 and 2026-10-11, provisional in the same way, and confirmed by the owner on 2026-10-11 (`B30 ok`, no veto); **B31** (the Phase 7 security delta review's sign-off) signed off by the owner on 2026-10-11, S142 and S144 accepted | No decision open; still to do: the owner's review of the committed scenario C |
| Design docs (`docs/design/`) | `phase1-contracts.md` as built, with the Phase 1.x, Phase 2, Phase 3 and Phase 4 passages marked "(1.x, built 2026-10-02)", "(Phase 2, built 2026-10-02)", "(Phase 3, built 2026-10-03)" and "(Phase 4, built 2026-10-07)". `phase2-contracts.md` and `phase3-contracts.md` as built (2026-10-03: every deviation of the build and every fix of the review marked in place; their Phase 4 passages marked built). **`phase4-contracts.md` as built 2026-10-07** (specified 2026-10-06; every deviation of the build and every fix of the review marked in place, the decisions of the build D59-D69). **`phase5-contracts.md` as built 2026-10-08** (specified, built, reviewed and fixed the same day, its §16.1 marks written into the Phase 1-4 contracts and the overview; the live session's passages marked "the live session", D111-D113). **`phase6-contracts.md` as built 2026-10-09** (specified, reviewed, built by six packages the same day; an `As built (Phase 6, <TAG>, 2026-10-09)` note under every section a package built or deviated from, the build's decisions D160-D221 and the decisions of its review's fixes D222-D286 in its §16, `threat-model.md` beside it, marks in the Phase 1 to 5 contracts and the overview; reviewed, fixed and security-reviewed twice the same day). Overview: roadmap with Phases 1.x to 6 built, reviewed and fixed, §3.8 as built. **`phase7-contracts.md` specified, reviewed and built 2026-10-10, code-reviewed, security-reviewed and fixed 2026-10-10/11** (decisions D287-D358 of the contract, D359-D430 of the build and D431-D486 of the fix wave, the race fix and the security fixes in its §17, amendments A61-A66, gaps 77-102; an "As built (Phase 7, <TAG>)" note under every section a package built or deviated from, a "Fix wave (Phase 7 review, <TAG>, 2026-10-10)" or "(Phase 7 security fix, S<nnn>, built 2026-10-11, <TAG>)" note under every passage a fix changed, the §16.1 marks written into the Phase 1-6 contracts and the overview by the packages; `threat-model.md` extended 2026-10-10 and completed 2026-10-11) | None |
| Research notes (`docs/research/`) | Round 1 carried over, finding ids unchanged; `embedded-browser-spi` and `vision-input` new (2026-10-02). Pruned of local-environment detail | None |
| Security review | Run 2026-10-02 on the pre-port tree: 34 confirmed findings (4 medium, 30 low); the fixes H1-H3 are built (Phase 1.x). The Phase 2 code review of 2026-10-03 had a secrets-and-safety lens (below). **Repeat, pass 1 (2026-10-09):** 28 confirmed findings (1 high, 6 medium, 21 low), fixed by the Phase 6 build or accepted as lows with release notes (B26 Q22, confirmed by the owner 2026-10-10). **Repeat, pass 2 (2026-10-09):** 38 confirmed findings (1 high, 12 medium, 25 low) on all eight lenses, fixed by six fix rounds except three lows accepted with release notes (B28). Together S35-S100: 66 findings, 2 high, 18 medium, 46 low; `SECURITY.md` and `docs/release-checklist.md` are in the tree. **Signed off by the owner on 2026-10-10** (B28), the eight lows accepted. **Phase 7 delta review (2026-10-10/11):** 48 confirmed findings (2 high, 13 medium, 33 low; S101-S148) on all eight lenses, every medium and high and 31 lows fixed by four fix packages (SG, SH, SI, SJ), S142 and S144 accepted with a release note each (B31); with B28, S35-S148: 114 findings, 4 high, 31 medium, 79 low. **Signed off by the owner on 2026-10-11** (B31), the two lows accepted | None before the first release; an outside reader is recommended, not a gate (B26 Q12: "done" or "waived by the owner" in the release checklist) |
| Spikes | BS7 done. **BS8 run 2026-10-06, passed with conditions** (`spikes/BS8/RESULT.md`). **BS5 met 2026-10-07** by the Phase 4 acceptance against JCEF's Chromium (D53; the stock-Chromium run is the manual procedure `spikes/BS5/README.md`). BS1 answered for Java 8 and Java 25 targets on Linux, parts open. BS2 answered in part. BS4 answered, parts open. **BS3 passed with conditions 2026-10-09** (`spikes/BS3/RESULT.md`) and **BS14 passed with conditions 2026-10-09** (`spikes/BS14/RESULT.md`), the hook go/no-go and the gate of the Byteman module. **BS6 passed 2026-10-08** and **BS13 passed 2026-10-08** (the offline part in the night, the live part in the afternoon with the owner's key), both as the Phase 5 contract's gates. **BS9, BS10 and BS11 passed with conditions 2026-10-10** (`spikes/BS9/RESULT.md`, `spikes/BS10/RESULT.md`, `spikes/BS11/RESULT.md`) and **BS12 passed 2026-10-10, live** (`spikes/BS12/RESULT.md`, USD 5.64): all four Phase 7 spikes have run, swing-driving F13 is settled by BS9, and the capture defect they found is gap 76 | See "Base spikes" |
| Phase 7 (vision bolt-on and the vision loop) | **Contract specified and reviewed 2026-10-10** (`design/phase7-contracts.md`, `2eebeed`; three lenses with sceptics, 94 findings, 91 applied; two rechecks, 36 more applied; D287-D358; B29 confirmed by the owner 2026-10-11). **Built the same day** in four worktree packages, VS (the agent's Swing side, the sample's number pad, the sample's agent module and pack 0.6.0), VW (the web side), VC (the scenario, controller, JUnit and CLI side), VM (the gateway, the session seam, `skillroy-vision`, the MCP and session options), merged into `main` as VC `71efa8e`, VS with step 2b `482288d`, VW `e7a4cb3`, VM `7af44eb` and `e11ae08` (D425); 72 decisions of the build, D359-D430. **Code-reviewed and fixed 2026-10-10:** three lenses with sceptics on `7af44eb`, 30 distinct findings (2 high, 9 medium, 19 low) and the live finding LV-1, fixed by FA `9f44718`, FC `791e0ae` and FB `c690bef`; a hook-engine race found by the first matrix round fixed by FX `a134159`; D431-D449. **Security-reviewed (delta) and fixed 2026-10-10/11:** S101-S148 (2 high, 13 medium, 33 low), fixed by SG `5d6fdd8`, SH `edba708`, SJ `73e406a`, SI `056a007` and SG's follow-up `c94aade`, except two lows accepted by the owner (S142, S144; B31, 2026-10-11); D450-D486 (B30 holds D359-D486, confirmed by the owner 2026-10-11). **Live:** `VisionLiveIT` run three times, 7 of 7 on the last two (the last on `c94aade`). Native reactor green on `c94aade` (5,099 tests, 0 failures, 592 skipped in SG's run); the Linux matrix of the final tree is green on `c94aade` (arm64 twice on every leg, amd64 on the Java 25 leg), its amd64 Java 8 legs green on `bc1e250` later on 2026-10-11; nothing pushed | Nothing in B29-B31 (all given 2026-10-11); B32 (D487-D491, the follow-ups of 2026-10-11) confirmed the same day; gaps 91-102 (93 and 96 are the owner's optional choices). See "Phase 7" in the checklist |
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

### Security review (repeat, pass 1, 2026-10-09)

The first pass of the repeat review B13 asks for before the first release, run on the committed tree `e86e8e2` with
the four lenses whose code Phase 6 does not change (L3 credentials, data channels and run outputs; L4 embedded web
views; L5 the model boundary; L6 the model as an actor), one reviewer and one independent sceptic each, probes in
scratch copies, no live model call. 28 raw findings (25 from the reviewers, 3 raised by the sceptics from refuted
"sound" claims): 28 confirmed (1 high, 6 medium, 21 low; 18 counted, 10 operator-only), 0 rejected. The confirmed
findings are S35-S62; the record is in the session notes (`2026-10-09/phase6/security/pass1/record.json` and `dispositions.md`).
- The high finding: `data_query` substitutes `$slot.field` references into the guarded SQL after `SqlGuard` ran, so a
  stored value or a model's reference rebuilds the statement past every guard rule (S55, FX-mcp).
- The six medium findings: page-produced data bounded only by page-side code (S43), the debug-port probe's unbounded
  `/json` bodies (S44), unbounded author regexes on page text written by an MCP client (S46), all FX-web;
  `skillroy_scenario_run` spending outside the session budget (S56), the verb gate decided by probes only under an
  origin that confirms (S57), captures served to the MCP client unmasked (S58), all FX-mcp.
- Dispositions: every finding fixed inside Phase 6's build in five packages (FX-scenario, FX-web, FX-inference,
  FX-mcp, FX-ci), contract first, but five low parts accepted (B26 Q22, confirmed by the owner 2026-10-10) with a release note each (S37 and
  S48 by documentation only, an acceptance under P6 §12.5); four fixes carry a decision of the Phase 6 contract (D156
  the `script` flag against D51, D158 the client origin against D96, D157 D101 widened, D159 captures over MCP against
  D76/D84).
- Not verified here: Windows, the drivers' read-only semantics, GitHub's multi-line masking, the real `claude` CLI;
  pass 2 runs all eight lenses on the Phase 6 tree with L3-L6 as deltas, and the sign-off is the owner's (given 2026-10-10, B28).
- State on 2026-10-09: **built** in two packages (tag `SR`; the five work packages FX-web, FX-scenario, FX-ci,
  FX-inference and FX-mcp went to two implementers), contract first (the amendments A40-A60 written into P1, P2, P4 and
  P5, marked `(Phase 6 security fix S<n>, A<m>, built 2026-10-09)`), every fix with a test that fails before it (S39 and
  S40 by hand: a `docker` stub and a parsed example; S37 and S48 documentation only); landed on `main` as `755844c`
  (FX-2) and `822dc58` (FX-1), native reactor green. Dispositions as built:
  - **Fixed in full: the high and the six mediums, and all the lows but the parts below.** FX-2 (S49-S62): S55 (the
    high: `data_query` refuses a reference in its SQL or parameters and sends the guarded text byte for byte,
    `PlannedCall.literalArgs`, D208); S56 (D157: one `SESSION` meter per MCP server for `skillroy_session_ask`,
    `skillroy_resolve` and a run's tier (b) slots, D211); S57 and S59 (D158: `Origin.Kind.CLIENT`, `mcp --client`,
    `author --client planner`, the verb gate at call time, D212); S58 (D159: a capture's bytes are served only under
    `images: true`); S60 (the planner's work and the server's output under one owner-only directory, `--keep-work`);
    S61 (export never replaces a file); S62 (`CallPolicy` in the session service, D213); S49 (the vocabulary masked in
    the session's tools, catalogue and recordings, D210); S50 (report and triage Markdown rendered as code); S51
    (`ANTHROPIC_BASE_URL` https or loopback, no user information); S52 (below); S53 (mask, then cut, D209); S54 (scrub,
    then cut). FX-1 (S35-S48): S35 and S41 (a value typed where a key belongs is never echoed, duplicate keys withheld
    with their line); S36 (the T-SQL words and lock-share sequences refused by `StatementGuard`); S38 (a credentials
    location is a path, never content); S39 (`MAVEN_OPTS` by name only in `ci/run-in-linux.sh`); S40 (the CI example:
    `persist-credentials: false`, `gh auth setup-git`, the secret renamed); S42 (D156 in full: the descriptor flag
    `script`, refused in read-only mode by the dispatcher and the CDP path, and by `CallPolicy` for a client); S43
    (page-supplied data bounded on the receiving side, agent and CDP, D219); S44 (the debug port's `/json` bodies
    bounded, a listed WebSocket probed at the endpoint's own authority); S45 (a page the agent does not report is never
    acted on); S46 (a regex budget, `BoundedRegex`, D221); S47 (library errors mapped as on the agent path).
  - **Accepted as lows under B26 Q22 (proposed 2026-10-09, confirmed by the owner 2026-10-10; B28 repeats it), each with
    its release note** (also in `SECURITY.md` "Known limitations", accepted by the owner, B28, 2026-10-10): S36 part (b) ("`StatementGuard` is a safety net,
    not the boundary: give the data account a read-only role; PostgreSQL sources should set `readOnlyMode: always`");
    S37 option (i) ("run outputs take the process umask; on a shared host run the controller under `umask 077`
    (`skillroy run --out`)"; documentation only); S40, the pinning part ("pin the image and the actions in your copy of
    `ci/examples/nightly-re-resolve.yml`"); S48 ("the Playwright driver inherits the controller's environment;
    `DEBUG=pw:*`, `NODE_OPTIONS`, `PLAYWRIGHT_NODEJS_PATH` and `PLAYWRIGHT_DRIVER_DIR` change what it runs or logs";
    documentation only); S60, the `CLAUDE_CONFIG_DIR` part ("the planner's `claude` session transcript stays in the
    CLI's own data directory; on a shared host run `author` with a key and your own `CLAUDE_CONFIG_DIR`"). S46's
    optional item 4 (the Swing locator on `BoundedRegex`, no behaviour change) is not done.
  - **Two behaviour changes the owner should know.** S52 (P5 §7.2, A52): a configured model id that is not in the price
    table is now refused when the gateway opens (`INVALID_REQUEST`, `model <id> is not in the price table; name it under
    prices: with its price`), a configuration that worked before; the alternative is a warning at open; the refusal stands
    (B27 confirmed by the owner, 2026-10-10). D210: the key
    rule now also masks the tool descriptions a session and the MCP server offer, so the sample's `signOn` and
    `authorise` descriptions read `credential: <redacted> staff, key: code}` there, as they already did in the planner's prompt (a false
    positive of the rule on pack prose; the five scripted session recordings were regenerated, no live call); the owner
    confirmed D210 without the wording veto on 2026-10-10 (B27), so the sample's wording stays as it is.

### Security review (repeat, pass 2, 2026-10-09)

The second pass of the repeat review B13 asks for before the first release, run on the Phase 6 tree with all eight lenses of
P6 §12.3 (L1 the control channel, L2 host safety and the instrumentation path, L3 credentials and data channels, L4 embedded web
views, L5 the model boundary, L6 the model as an actor, L7 Windows and file-system trust, L8 telemetry and evidence at rest;
L3-L6 as deltas on pass 1's fixes), **one reviewer and one independent sceptic each**, probes in scratch copies, no live model
call. **L1 and L3-L7 ran on `f546363`** (the tree before the code review's fix wave was integrated into `main`, so that the
sceptics read a stable tree) and **L2 and L8 on `ce1f41f`** (after it). 39 raw findings (32 from the reviewers, 7 raised by the
sceptics from claims they broke): **38 confirmed (1 high, 12 medium, 25 low; 18 counted, 16 operator-only, 4 token-holder
only), 1 refuted (P2-L1-F2, no number), 0 unverified.** Per lens: L1 4, L2 8, L3 2, L4 4, L5 5, L6 5, L7 4, L8 6. The sceptics
re-rated six (S64 low to medium; S73 token-holder only to counted; S78 low to medium; S92 and S95 high to medium; S97 low to
medium). The confirmed findings are S63-S100 (the table below maps each to the id the contract's marks keep); the record is in
the session notes (`2026-10-09/phase6/security/pass2/record.json` and `dispositions.md`). With pass 1 the repeat review stands
at S35-S100: 66 findings, 2 high, 18 medium, 46 low.

- **The high finding:** the planner handed model-controlled text to an implicit `cmd.exe` on Windows (S93, P2-L7-F3): npm's
  `claude.cmd` wins the `PATH` search, the repair prompt carries the model's own values raw as the last argument, and the JDK's
  legacy quoting lets an embedded quote close the wrap, so a model could run a command as the controller's account and expand
  `%ANTHROPIC_API_KEY%` into the prompt. Fixed: the prompt is the planner's stdin, a `.cmd` or `.bat` planner is refused, the
  schema argument is pre-quoted by the C runtime's rules (SD, D250-D254); read, not run on Windows, where no host exists. The stdin route
  itself, a forced `--resume` repair round included, was run live on macOS on 2026-10-10 (the last row of the live table at the top).
- **The twelve medium findings**, one line each: S63 a token-less process pinned the unauthenticated body budget so a controller
  call on a new connection failed with a 503 (the body hash now travels in a signed header); S64 the agent read its options
  file with no owner, permission or link check (counted as operator-only, lifted from low); S67 a confirmed Byteman rule on an
  unloaded JDK class broke it for the JVM's life; S74 a hook removed from a class whose retransformation threw was no residue, so
  a stub armed for another copy stubbed the untargeted class; S78 an oversized drain was asked for again every 100 ms; S80 a
  page's console output reached the JVM whole, at the page's rate; S86 a client's `skillroy_scenario_run` ignored the server's
  `--hooks` switch; S87 a capture's bytes reached the MCP client through `run.jsonl`; S91 the "neither POSIX nor ACL" refusal was
  decided per provider, never per volume; S92 `skillroy.bat` ran `findstr` by bare name; S95 `telemetry.yaml` was read with no
  trust check and alone turned export on; S97 the run-output writers followed planted links.
- **Dispositions:** every finding of medium or above is **fixed** (contract first, a test that fails on the old code, the review
  id in the contract's mark), by six fix rounds merged into `main` on 2026-10-09: SC `2ab0f72` (L5, L6: S81-S90), SD `49becf3`
  (L7 and L3's SQL Server items: S75, S76, S91-S94), SA `3be43b9` (L1: S63-S66; merged with SD by a resolver, the volume rule
  inside `JarTrust` and the single-channel write in `SecureFiles`), SB `84eeab6` (L4: S77-S80), SE `378b5e0` (L2: S67-S74, and
  the bootstrap's options file on FAT/exFAT that the SA x SD merge left open: D279), SF `4f9962f` (L8: S95-S100). Their 43
  decisions are **D244-D286** (SC D244-D249, SD D250-D261, SA D262-D265, SB D266-D270, SE D271-D279, SF D280-D286), after the
  code review's fix wave (D222-D243). 35 findings are fixed in full; S77 is fixed with a residual; S84 and S94 are left as
  they are. Nothing is deferred.
- **Accepted by the owner on 2026-10-10 (B28; proposed 2026-10-09), each with a release note in `SECURITY.md` "Known limitations":**
  S77's residual (Playwright's driver follows a redirect of the attach's WebSocket handshake while the agent reports no CDP
  target id; D269), S84 (a loopback `http` model base URL sends the key in clear to whatever holds the port) and S94 (on a
  Windows with localised built-in account names every owner-only path fails closed). With pass 1's five (S36 part (b), S37
  option (i), the pinning part of S40, S48, the `CLAUDE_CONFIG_DIR` part of S60) that is eight in B28. Read S84 beside S96 (a
  telemetry header over loopback `http`, fixed by SF, D281): the key may use loopback `http`, a header may not, by SF's choice;
  the owner accepted the two as they are (B28, 2026-10-10).
- **Sign-off:** the owner signed off the repeat security review (pass 1 and pass 2) on 2026-10-10 (B28), the eight lows
  accepted as proposed; no outside reader was made a gate (B26 Q12).
- **Behaviour changes to read first:** a request with a body must carry the signed header `X-Skillroy-Body-Sha256` (a controller
  older than the fix cannot call a newer agent; nothing was released on protocol 0.2); a `.cmd` or `.bat` planner is refused; a
  telemetry header needs an `https://` endpoint, the default included; `telemetry.yaml` and `inference.yaml` must be the current
  user's and not writable by others; a run refuses an output directory another user owns or others may write to and never
  writes through a link or into a file already in place; an options file on a FAT or exFAT volume is refused; a Byteman rule may
  no longer name a JDK class.
- **Not verified here:** everything Windows (the ACL branches of `SecureFiles`, `OutputFiles`, `SettingsFiles`, `JarTrust`,
  `JarCheck` and the bootstrap's `OptionsFileCheck` on a real volume; `skillroy.bat` with a decoy `findstr.cmd`; the planner
  through `claude.exe` on stdin and `claude.cmd` refused; the pre-quoted schema argument; localised principals), where no host
  exists; a second OS user (the cross-user windows of S64, S95, S97 are tested with one user, built ACL values and the `Access`
  seam); SQL Server (the T-SQL consequences of S75 and S76 rest on vendor documentation; the read path's order is proved on
  HSQLDB and a recording proxy); whether the planner can read MCP resources under
  `--tools ""`; the JxBrowser adapter's console budget (the fake only; the kit items 13 and 14 ran against real JCEF 152);
  Linux `vfat` (not mounted: the image is not privileged); the real agent's `hook.install {replace: true}` against a real
  residue (the fake agent only); an outside human reader (B26 Q12: recommended, not a gate; none has read the record yet). The real `claude` CLI and the
  planner's stdin route, verified offline against a local listener with claude 2.1.286 when this pass was written, were
  run live on 2026-10-10 with a forced `--resume` repair round (the last row of the live table at the top).

The mapping, S-number to the id the contract's marks keep (`P2-L<lens>-<F|S><n>`; F is the reviewer's, S the sceptic's new
finding; P2-L1-F2 was refuted and has no number):

| S | Pass-2 id | Severity, reach | Finding | Fixer (decisions) | Disposition |
|---|---|---|---|---|---|
| S63 | P2-L1-F1 | medium, counted | a token-less process pins the body budget (503 on new connections) | SA (D262) | fixed |
| S64 | P2-L1-F3 | medium, operator-only | options file read with no owner, permission or link check | SA (D263) | fixed |
| S65 | P2-L1-F4 | low, operator-only | no floor on a supplied token's strength | SA (D265) | fixed |
| S66 | P2-L1-F5 | low, operator-only | spy jar and `modules` paths lack a directory and owner rule | SA (D264) | fixed |
| S67 | P2-L2-F1 | medium, token-holder-only | a Byteman rule on an unloaded JDK class breaks it for the JVM's life | SE (D276) | fixed |
| S68 | P2-L2-F2 | low, counted | Byteman engine left half-loaded when the handler cannot arm | SE (D277) | fixed |
| S69 | P2-L2-F3 | low, counted | `replace: true` loses the implicit remove's `HOOK_VERIFY_FAILED` | SE (D273) | fixed |
| S70 | P2-L2-F4 | low, counted | partial retransformation at `hook.remove` answered OK; own events over-counted | SE (D272) | fixed |
| S71 | P2-L2-F5 | low, token-holder-only | Byteman recorders unbounded | SE (D278) | fixed |
| S72 | P2-L2-F6 | low, token-holder-only | lock-less detach without WARN and count; the install completes into the detached engine | SE (D274) | fixed |
| S73 | P2-L2-F7 | low, counted | captured strings not passed through `redactText` | SE (D275) | fixed |
| S74 | P2-L2-S1 | medium, token-holder-only | removed hook with a failed partial retransformation is no residue (stale stub) | SE (D271) | fixed |
| S75 | P2-L3-F1 | low, operator-only | `SET IMPLICIT_TRANSACTIONS` keeps a lock-holding transaction open (SQL Server) | SD (D260) | fixed |
| S76 | P2-L3-S1 | low, operator-only | open transaction handed back to a pooled provider connection | SD (D260) | fixed |
| S77 | P2-L4-F1 | low, counted | the attach re-reads `/json/version` and dials what it names | SB (D269) | fixed; residual accepted (B28) |
| S78 | P2-L4-F2 | medium, counted | an oversized drain asked for again every 100 ms | SB (D268, D270) | fixed |
| S79 | P2-L4-F3 | low, counted | CDP: a bare-string library value escapes the 1 MiB bound | SB (D268) | fixed |
| S80 | P2-L4-S1 | medium, counted | a page's console output reaches the JVM whole, at the page's rate | SB (D266, D267) | fixed |
| S81 | P2-L5-F1 | low, operator-only | `PromptMask.schema` secrecy not carried into nested schemas | SC (-) | fixed |
| S82 | P2-L5-F2 | low, operator-only | `triage.md` renders provider strings as Markdown | SC (-) | fixed |
| S83 | P2-L5-F3 | low, operator-only | provider ids unscrubbed and unbounded | SC (D247) | fixed |
| S84 | P2-L5-F4 | low, operator-only | a loopback `http` base URL hands the key to a port squatter | SC (-) | accepted (B28) |
| S85 | P2-L5-S1 | low, operator-only | data keywords of a non-secret property keep secret-named keys | SC (-) | fixed |
| S86 | P2-L6-F1 | medium, counted | a client's `scenario_run` ignores the server's `--hooks` switch | SC (D249) | fixed |
| S87 | P2-L6-F2 | medium, counted | capture bytes reach the client through `run.jsonl` | SC (D244, D245, D246) | fixed |
| S88 | P2-L6-F3 | low, counted | only a step's value scanned for images, to depth 8 | SC (D244) | fixed |
| S89 | P2-L6-F4 | low, operator-only | `author` has no `--hooks` | SC (D248) | fixed |
| S90 | P2-L6-S1 | low, operator-only | triage sends a failed step's capture bytes to the model | SC (D244) | fixed |
| S91 | P2-L7-F1 | medium, operator-only | the 'neither' refusal decided per provider (FAT/exFAT pass as POSIX) | SD (D255, D256, D257, D258, D259, D261, D279) | fixed |
| S92 | P2-L7-F2 | medium, counted | `skillroy.bat` runs `findstr` by bare name | SD (-) | fixed |
| S93 | P2-L7-F3 | high, counted | the planner passes model text to an implicit `cmd.exe` | SD (D250, D251, D252, D253, D254) | fixed |
| S94 | P2-L7-F4 | low, operator-only | built-in principals by English name (fails closed) | SD (-) | accepted (B28) |
| S95 | P2-L8-F1 | medium, counted | `telemetry.yaml` read with no trust check | SF (D280, D284) | fixed |
| S96 | P2-L8-F2 | low, operator-only | a telemetry header over loopback `http` | SF (D281, D286) | fixed |
| S97 | P2-L8-F3 | medium, counted | run-output writers follow planted links | SF (D282, D283, D284) | fixed |
| S98 | P2-L8-F4 | low, counted | the environment event carries the discovery directory | SF (D285) | fixed |
| S99 | P2-L8-S1 | low, counted | `umask 077` does not hold against a pre-created output | SF (D282) | fixed |
| S100 | P2-L8-S2 | low, operator-only | the planner's MCP child exports under `telemetry.yaml` | SF (-) | fixed |

### Security review (Phase 7 delta, 2026-10-10/11)

The delta review that B13 and the release checklist ask of a phase that opens a new boundary (pixels leave the JVM, screen
content steers a model, Robot acts outside the application), specified in `design/phase7-contracts.md` §15 (D338) and run on
everything Phase 7 changed since `af29e59`, the tree B28 covered, with all eight lenses of P6 §12.3 (L1 controller correctness
and bounds, L2 host safety, L3 the data channels and, found there, the credential rule, L4 the web boundary, L5 the model
boundary, L6 the model as an actor, L7 Windows and file-system trust, L8 evidence at rest), **one reviewer and one independent
sceptic each**, probes in scratch copies, no display, no image run, no
live model call. **L1, L3, L4, L7 and L8 ran on `e96a20b`** (the build with its documents, before the code review's fix wave was
integrated) and **L2, L5 and L6 on `c690bef`** (after it); every `e96a20b` finding was re-checked against `c690bef`, and the
fix wave had closed none. 52 raw findings (29 from the reviewers, 23 raised by the sceptics from claims they broke): 1 refuted
(L8-F4, whose premise, a dry-run `approve`, does not exist), 3 pairs merged into one finding each (S110 = L5-F1 = L6-F1,
S115 = L7-F1 = L8-S1, S117 = L1-F3 = L8-F5): **48 standing findings, S101-S148: 0 critical, 2 high, 13 medium, 33 low; 42
counted (2 high, 13 medium, 27 low), 6 operator-only (S117, S119, S126, S136, S143, S147), none token-holder-only.** Per lens:
L1 6, L2 9, L3 5, L4 7, L5 5, L6 6, L7 6, L8 4; 22 were raised by the sceptics. The sceptics re-rated S129 (medium to low: the
CDP leg was refuted) and S141 (low, like its POSIX twin S99, not medium) and restated the reach of S103, S110, S145 and S147.
The record is in the session notes (`2026-10-10/phase7/security/`: `record.json` with the dispositions as built,
`dispositions.md`, the lens files, `fix-SG-report.md`, `fix-SH-report.md`, `fix-SI-report.md`, `fix-SJ-report.md`). With B28 the
security reviews stand at S35-S148: 114 findings, 4 high, 31 medium, 79 low.

- **The two high findings** (both SH): **S101**, the credential rule of D342 required a field after the slot name, so
  `x$staff` or `$staff` for a credential-joined slot resolved in a model's or a client's call to the slot with its joined
  password past every refusal on every route (the pixel route: the typed password rendered in an unmasked field and the next
  capture carried it to the provider); **S102**, the plan form keyed a pack verb's written arguments by source line, so two steps
  on one line (flow-style YAML, a one-line JSON scenario) let `$staff.password` through a pass-through verb.
- **The thirteen medium findings**, one line each: S103 the image comparison's focus mask had no bound (a focus owner covering
  the raster made an `expectImage` hold and an anchored step's guard hold whatever the pixels show; now at most a quarter of
  the raster); S104 a Robot press into a minimised frame or a window it owns passed every pre-check and landed on the desktop;
  S105 a Robot press at a point on no screen was clamped by the platform to the screen edge and landed there; S106 a masked
  label's or button's accessible name is its text, so a mask withheld the text and leaked it as a name, a locator candidate
  and an oracle; S107 `author --run` used the one-argument plan policy, so a planner's pass-through verb ran with
  `$staff.password`; S108 the composite painted a page over a modal dialog, an owned window or a popup above the browser and
  over the part its viewport clips, while a click there landed on the hidden control; S109 a page under a modal dialog was
  clicked as `webClick`, which has no modal check; S110 the vision tools let a model paste the person's clipboard or the X11
  selection into the target (ctrl+V, shift+Insert, the PASTE key, the middle button), which the next capture carried to the
  provider; the route **predates Phase 7** for the planner (its server is always `mcp --mutating` and offered `swing_key` with
  modifiers since Phase 5) and B28 did not see it; S111 a browser a pack's mask names still sent its page to a model and a
  client as text in every snapshot and as pixels, text and tree through the raw `web_*` reads; S112 V2's raw pointer halves
  clicked a masked component; S113 in a played scenario the model of an `aiAct` could rebind a slot the person bound, and the
  person's later steps and the scenario's `verify` used its value; S114 the stale-capture rule compared two live hit-tests, so
  a window that opened over the point without moving the composite was named by both and the click landed on a dialog the
  model never saw; S115 an images-only plan in `approve` wrote the bindings file at bind time with no approver asked, so a
  client's `skillroy_scenario_run {mode: approve}` could create or replace a file in the operator's bindings root.
- **Dispositions:** every finding of medium or above and 31 of the 33 lows are **fixed** (contract first, a test that fails on
  the old code, the review id in the contract's mark "(Phase 7 security fix, S<nnn>, built 2026-10-11, <TAG>)"), in four
  packages from `a134159` with disjoint files, merged into `main` on 2026-10-11: SG `5d6fdd8` (the agent's Swing side: S104,
  S105, S106, S121-S126, S129 and the agent halves of S108 and S146; native 5027 tests, 13 of its new tests failed on `a134159`),
  SH `edba708` (credentials, slots and the client's plan form: S101, S102, S107, S113, S127, S128, S139; native 5016), SJ
  `73e406a` (the image baselines, the bindings files and the run output: S103, S115-S120, S140, S141, S145, S147, S148 and the
  controller halves of S143 and S146; native 5027), SI `056a007` (the vision module, the session service, the web reads and the
  CDP path: S109-S112, S114, S130-S138 and the controller halves of S108, S124 and S143; 36 new tests failed with their fix
  neutralised in place; native 5065), then SG's follow-up `c94aade` (the browser's whole `bounds` on every `WEB` surface, which
  S108's composite needed for a browser a viewport clips; native 5099 on the merged tree). Their 37 decisions are
  **D450-D486** (SG D450-D462, SH D463-D469, SI D470-D480, SJ D481-D486), after the code review's fix wave and the race fix
  (D431-D449); all are B30. No fixer found a finding wrong, already fixed or out of proportion. Image runs of the fixers
  (arm64 image, Java 25 and Java 8 targets and test JVMs, counts from their reports): `agent-swing` 248 run, 0 failures on both
  test JVMs; the lending end-to-end 65 run, 0 failures, 3 skipped by design, on both Javas; `WebHostEndToEndIT` 80 run, 0
  failures on both; `InferenceEndToEndIT` 130 run, 0 failures on both; the resolution classes 19 run, 0 failures each.
- **Accepted by the owner on 2026-10-11 (B31; proposed 2026-10-11), each with a release note in `SECURITY.md` "Known limitations":** **S142**, a Windows directory junction in place of a run's `failure/`, `captures/` or `candidates/` directory
  (or a bindings environment's `<id>.images/`) is taken for an ordinary directory (a code remedy comparing the real path is
  named in the finding for a Windows host); **S144**, on Windows the run's pixel files inherit the access list of the directory
  they are created in (the `umask 077` remedy has no counterpart; S37's option (i) for ACL volumes is not built). Both are
  documentation-only acceptances under P6 §12.5, not verified on a Windows host.
- **Sign-off:** the owner signed off the Phase 7 delta review on 2026-10-11 (B31, `B31 ok`), the two lows accepted as proposed;
  no outside reader was made a gate (B26 Q12), and none has read the record yet.
- **Behaviour changes to read first:** `DECISIONS.md` B30 (e)-(n): the Robot opt-in read in any case and by reference; a
  minimised or off-screen Robot point and `modifiers` refused; no clipboard chord or middle button for a model or a client; a
  masked browser masked on every route; no credential-joined slot in any spelling for a model or a client, their steps
  resolved against public values, nothing mutating in flight, a person's slot not rebound; `approve` refused on a client's
  behalf without an approver and nothing written at bind time; the focus mask bounded (baselines approved with the focus on
  the compared component may need re-approval); a session without `--out` in an owner-only temporary directory; the player
  evaluates `verify`. The owner confirmed them with B30 on 2026-10-11, so they stand as built.
- **Not verified here:** everything on Windows (the ACL judgement of S141 on built values, S142 and S144, a minimised frame's
  parking position, `SetCursorPos` clamping); macOS (an off-display button event's routing, the first glass capture's
  screen-recording consent dialog); the minimised-frame refusal off the WM-less display (it ran once under openbox); a window
  on another virtual desktop or workspace (AWT cannot see it); a real masked browser, a real JCEF frame under a `JOptionPane`,
  the paste and the middle click on an X11 desktop with a headed JVM, S133's late click on real Chromium; the paths that need a
  live model call (a disabled toolset member, the planner choosing `approve`, the 5.5 history rule under the automatic cache
  hint); a second OS user, an APFS case-insensitive volume, a real multi-screen desktop, HiDPI, the amd64 image for any fix
  round. Owner's note, no finding: under `fallbacks: true` (the default) a refused vision request is re-run on a fallback model
  of the same provider, redacted captures included (pass 2's open S52 question, now with pixels; `SECURITY.md` says so).

The mapping, S-number to the id the contract's marks keep (`L<n>-F<m>` the reviewer's, `L<n>-S<m>` the sceptic's new finding,
in the security lens numbering; the code review's ids have the same shape and are other findings):

| S | Delta-review id | Severity, reach | Finding | Fixer (decisions) | Disposition |
|---|---|---|---|---|---|
| S101 | L3-F1 | high, counted | the credential rule missed the credential-joined slot as a whole (`x$staff` reached a model's or a client's call with the password) | SH (D463, D464) | fixed |
| S102 | L3-S1 | high, counted | the plan form keyed a pack verb's written arguments by source line (flow-style YAML or one-line JSON let `$staff.password` through) | SH (D465) | fixed |
| S103 | L1-F1 | medium, counted | the image comparison's focus mask was unbounded (a focus owner covering the raster made a check hold whatever the pixels show) | SJ (D481) | fixed |
| S104 | L2-F1 | medium, counted | a Robot press into a minimised frame, or a window it owns, landed on the desktop | SG (D450) | fixed |
| S105 | L2-F2 | medium, counted | a Robot press at a point on no screen was clamped to the screen edge and landed there | SG (D451) | fixed |
| S106 | L2-S1 | medium, counted | a masked label's or button's name (its text) leaked as a name, a locator candidate and an oracle | SG (D452) | fixed |
| S107 | L3-S2 | medium, counted | `author --run` used the one-argument plan policy (a pass-through verb ran with `$staff.password`) | SH (D466) | fixed |
| S108 | L4-S1 | medium, counted | the composite painted a page over a modal dialog above the browser and past its viewport | SG + SI (D455, D462, D475) | fixed |
| S109 | L4-S2 | medium, counted | a page under a modal dialog was clicked as `webClick` (no modal check) | SI (D472) | fixed |
| S110 | L5-F1, L6-F1 | medium, counted | a model could paste the person's clipboard or X11 selection into the target (chords, middle button) | SI (D470) | fixed |
| S111 | L5-S1 | medium, counted | a browser a pack's mask names still sent its page (text, pixels, tree) to a model and a client | SI (D471) | fixed |
| S112 | L6-S1 | medium, counted | V2's raw pointer halves clicked a masked component | SI (-) | fixed |
| S113 | L6-S2 | medium, counted | a model's `skillroy_resolve` rebound a slot the person bound | SH (D468) | fixed |
| S114 | L6-S3 | medium, counted | the stale rule compared two live hit-tests (a dialog opening over the point was clicked) | SI (D473) | fixed |
| S115 | L7-F1, L8-S1 | medium, counted | an images-only `approve` wrote the bindings file at bind time with no approver | SJ (D482) | fixed |
| S116 | L1-F2 | low, counted | a writing bind replaced a bindings file it could not read | SJ (D486) | fixed |
| S117 | L1-F3, L8-F5 | low, operator-only | `approve` without `--out` asked the person to approve a candidate it could not show | SJ (-) | fixed |
| S118 | L1-S1 | low, counted | image labels differing only in case share one PNG on a case-insensitive volume | SJ (-) | fixed |
| S119 | L1-S2 | low, operator-only | the plan reader was looser than the compiler (a cleanup guard, the mask count) | SJ (-) | fixed |
| S120 | L1-S3 | low, counted | the export never wrote a re-recorded anchor's entry beside the old ones | SJ (D483) | fixed |
| S121 | L2-F3 | low, counted | the delivery probe added its listener before it had the Robot port | SG (D458) | fixed |
| S122 | L2-F4 | low, counted | a `regions`-only mask blanked the pixels and left the covered text | SG (D454) | fixed |
| S123 | L2-S2 | low, counted | two masks combined unsafely (a `regions` mask cancelled another's whole blanking) | SG (D453) | fixed |
| S124 | L2-S3 | low, counted | the Robot path ignored `modifiers` | SG + SI (D459) | fixed |
| S125 | L2-S4 | low, counted | the heap bound counted the raster, not the encoding | SG (D456) | fixed |
| S126 | L2-S5 | low, operator-only | a glass capture at a UI scale above 1 allocated (s^2 + 1) times its bound | SG (D457) | fixed |
| S127 | L3-F2 | low, counted | while a credential was in flight only typing was refused for a model or a client | SH (D467) | fixed |
| S128 | L3-S3 | low, counted | the plan form stopped at depth 64 and failed open | SH (D469) | fixed |
| S129 | L4-F1 | low, counted | a browser inside a hidden card was listed as a `WEB` surface | SG (D455, D461) | fixed |
| S130 | L4-F2 | low, counted | a hit in a child frame was recorded as a main-frame `webClick` | SI (D477) | fixed |
| S131 | L4-F3 | low, counted | the inner-size read was a raw evaluate outside the library guard | SI (D480) | fixed |
| S132 | L4-S3 | low, counted | a recorded `webClick` carried no browser | SI (D477) | fixed |
| S133 | L4-S4 | low, counted | the CDP `web.pointer` kept running after its caller timed out | SI (D478) | fixed |
| S134 | L5-F2 | low, counted | a step's message text reached the model outside a `<target-state>` block | SI (-) | fixed |
| S135 | L5-F3 | low, counted | the token count was sent to the provider before the budget refused | SI (D476) | fixed |
| S136 | L5-F4 | low, operator-only | `visionCall` did not mask a tool set's tools, output schema and provider tools | SI (-) | fixed |
| S137 | L6-F2 | low, counted | the vision module acted a tool call it had not offered | SI (-) | fixed |
| S138 | L6-F3 | low, counted | a tool set's reads were exempt from every per-turn limit | SI (D474) | fixed |
| S139 | L6-F4 | low, counted | a model's resolve of a person-bound credential slot rebound it | SH (D468) | fixed |
| S140 | L7-F2 | low, counted | the bindings directory's writers followed a symbolic link | SJ (-) | fixed |
| S141 | L7-F3 | low, counted | the Windows ACL branch judged the owner only | SJ (-) | fixed |
| S142 | L7-F4 | low, counted | a Windows directory junction is a directory to Java, not a link | (owner, B31) | accepted (B31) |
| S143 | L7-S1 | low, operator-only | the PNG codec wrote pixel data into `java.io.tmpdir` through ImageIO's file cache | SJ + SI (-) | fixed |
| S144 | L7-S2 | low, counted | Windows run output inherits its parent's access list | (owner, B31) | accepted (B31) |
| S145 | L8-F1 | low, counted | `failure/image-*-actual.png` was written with the step's masks unblanked | SJ (-) | fixed |
| S146 | L8-F2 | low, counted | a baseline's focus owner recorded the focused component's `{text}` locator | SG + SJ + SI (D460, D484, D479) | fixed |
| S147 | L8-F3 | low, operator-only | an anchor label was unchecked when the export built a PNG path | SJ (-) | fixed |
| S148 | L8-S2 | low, counted | `skillroy session` without `--out` wrote `./run-output/<id>`, not nothing as documented | SJ (D485) | fixed |

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
| BS1 | The agent loads into stock JVMs in all load modes, across JDK versions | **Answered for Java 8 and Java 25 targets on Linux** (Java 21 targets before the switch). Since 2026-10-09 (Phase 6, check 1b) the JEP 451 warning is shown for `ATTACH` on a Java 25 target and absent for `JAVAAGENT`, `JAVA_TOOL_OPTIONS` and `EMBEDDED`. Open: cross-user attach and jattach, the refusal under `-XX:-EnableDynamicAgentLoading`, Windows |
| BS2 | The control channel survives a soak; the agent is isolated from the host class path | **Answered in part**: 3-minute soak per JDK (before the switch, on the JDK server), bounds and isolation tested; the agent's own listener (H1) is built and its per-call cost measured (1.4-5 ms). Open: the one-hour soak, loader collectability, a soak on the agent's own listener. Finding: about 45 ms per call on the JDK HTTP server on Java 8 and Java 21 targets; absent on a Java 25 target; gone with H1 |
| BS3 | Byte Buddy retransformation with inline Advice and a bootstrap `Spy`; original bytes restored on detach | **Passed with conditions 2026-10-09** (`spikes/BS3/RESULT.md`): on Java 8 and Java 25 targets in the arm64 image, `JAVAAGENT` and `ATTACH`, a hook on the sample's `AccessControl.check` and on a toy class in a custom loader installs in 111-197 ms (later ones 6-23 ms), fires on the host's thread in `OBSERVE`, `CAPTURE` and `STUB`, switches mode without a retransform and restores byte for byte; Byte Buddy 1.18.14 shaded under the project prefix coexists with a host's Byte Buddy 1.12.19, ASM 5.0.3 and JNA 4.5.2; 17 configurations. Conditions: Byte Buddy injects `Nexus` (and on Java 25 a `java.lang` accessor with an `Unsafe` warning) unless its relocated `nexus.disabled` property is set; each bootstrap append prints a class-data-sharing warning on Java 25 (append once per JVM); the byte check holds by construction, so the engine also counts transform events it did not cause; a loader that delegates only `java.*` cannot see `Spy` (probe and refuse, or a `java.*`-package `Spy`); every retransform is a stop-the-world pause (size limit proposed: 256 KiB and 2,000 methods); maven-shade-plugin 3.6.2 or newer |
| BS4 | Which Swing input rungs work on an Xvfb display with no window manager | **Answered** for Java 8 and 21 (before the switch); the end-to-end check built on it passed on Java 8 and Java 25 targets. The ROBOT rung is not built; its facts, and the mechanism behind "nothing gives the focus back" after a dialog closes, are BS9's (2026-10-10). Open: a large-sample focus statistic, the display-test flake |
| BS5 | Playwright attaches over CDP to an already-running Chromium safely | **Met 2026-10-07 by the Phase 4 acceptance** against JCEF's Chromium in the image (D53): 50 of 50 attach attempts against a frozen page ended with `FROZEN_TARGETS` within the deadline, the slowest in about 2.02 s. A stock-Chromium run is a manual procedure, `spikes/BS5/README.md` |
| BS6 | `SqlGuard` accepts and rejects correctly across SQL dialects | **Passed 2026-10-08** (`spikes/BS6/RESULT.md`): a prototype guard on JSqlParser 5.4 accepts all 264 accept statements and refuses all 382 reject statements with the reason named, on HSQLDB, MySQL, MariaDB, PostgreSQL, DB2, Oracle, SQL Server and generic SQL; all 41 HSQLDB rewrites execute on the embedded database; about 0.1 ms per statement; 741 tests. It found holes in the contract's first rules (database links, dropped comments, ambiguous literals, correlated names, parenthesis depth, a tie-breaker rule that gave invalid SQL after `GROUP BY`), now corrected in P5 §5.3. Open: the other dialects' accept corpora on real servers before production use; Oracle 11g unavailable. The agent-side statement guard of `data.query` is a different, simpler check and is built |
| BS7 | `javac --release 8` still works on current JDKs | Done 2026-10-01 |
| BS8 | A real JCEF browser runs in the Linux test image and the in-process DevTools path works | **Run 2026-10-06, passed with conditions**: both rendering modes on a Java 25 target, off-screen on a Java 8 target; the DevTools channel verified; Chromium 152's first-run terms dialog must be pre-accepted; `Target.getTargetInfo` does not answer (the main frame id is the target id); windowed JCEF on Java 8 creates no browser. `spikes/BS8/RESULT.md`. Since Phase 4 (2026-10-07): the timeouts and the unanswered `Target.*` calls were JCEF's client losing an answer that arrives early, which the `jcef` adapter avoids (D69) |
| BS9 | Robot input on the test display and on a desktop session | **Passed with conditions 2026-10-10** (`spikes/BS9/RESULT.md`): Java 8 (1.8.0_504) and Java 25 (25.0.4.1) targets, the WM-less Xvfb (the CI image) and openbox on the same display (arm64; Java 25 also amd64), and this Mac (macOS 26.6.2, Java 25.0.1, strict mode). Robot pointer events land as a person's in every case tried (16 toy cases, three focus-gated keypad keys of the sample); Robot and XTEST keys land in the AWT focus owner if and only if the JVM has a focused window. swing-driving F13 settled: WM-less, the X focus is `PointerRoot` until a Java window asks; the window receives the keys and AWT drops posted key events when no window is focused; a window shown without asking, and every frame after a modal dialog is disposed, has no focus (BS4's mechanism); openbox refocuses after a dialog. On the Mac Robot delivered nothing, silently; self-activation and `ROBOT` capture worked. `ROBOT` capture equals a `PAINT` of the root pane pixel for pixel on Linux; `frame.printAll`, which the built `swing.capture` does, carries imitation Motif decorations from `XFramePeer.print`, on the content when the insets are 0 (gap 76). Conditions: a Robot delivery probe, an activation check before keys (re-activation after a dialog closes, WM-less), the point in an uncovered window of the JVM, the JVM holding the focus on a person's desktop, root-pane painting for capture. Not run: Windows, Wayland, macOS with Accessibility granted |
| BS10 | Routed in-JVM dispatch behaves like Robot | **Passed with conditions 2026-10-10** (`spikes/BS10/RESULT.md`): the same legs. Routed window-sourced dispatch matched Robot's outcome in 14 of 16 cases (button with rollover, tool tip, caret at a pixel, selection drag, double-click, two kinds of popup menu, combo, table, wheel, slider drag, a glass pane with and without a listener, disabled button) and AWT's dispatched events in 13 of those 14 (X11 drops the click of a popup-opening press; routed delivers it); Java 8 = Java 25; on the Mac the same 16 routed outcomes. By construction it differs for a modal-blocked window (routed input reaches it) and a window on top (routing cannot see it). The built `DISPATCH` rung clicks the centre, bypasses glass panes and sends no enter or move. Conditions: the modal check and a covering-window check in `swing.pointer`, the native sequence imitated (move first, popup trigger per platform, release after a drag with click count 0), the actual press target reported; the hit-test's public-API mouse-target search needs the `componentPopupMenu` and `autoscrolls` rules |
| BS11 | `PAINT` capture is deterministic | **Passed with conditions 2026-10-10** (`spikes/BS11/RESULT.md`): the built `swing.capture` on the sample desk through the real launcher and client, Java 25 (25.0.4.1) and Java 8 (1.8.0_504) targets in the arm64 image, 15 environments in all. Within one environment 100 of 100 captures of an unfocused window hash identically on both targets (ARGB raster and PNG bytes), and five launches, type-and-clear, a dialog opened and closed, a covering window and a move reproduce the same hashes; the one exception is the blinking caret of a focused field (1 x 24 px, max 204: two variants), as large as a one-character change (28-50 px). Across environments: architecture, Xvfb depth, `swing.aatext`, AA off and the JDK 8 update change nothing; the JDK line 0.30 % of the main window (shapes, table header), text AA on/lcd 0.14-0.82 %, two font packages 3.3-4.7 %, macOS 7.9-14.7 %; no tolerance separates that noise from a one-character change. Conditions: carets masked at the source, focus state recorded, baselines bound to a fingerprint of rendering inputs and compared exactly, raster hashes and not PNG bytes. Not run: Windows, HiDPI, a 25.x update |
| BS12 | Grounding accuracy and cost of a vision loop on the sample | **Passed 2026-10-10, live** (`spikes/BS12/RESULT.md`): 27 targets on five desk screens, Sonnet 5.5, Opus 5.5 and Haiku 5.5 at effort `low` (and a default-effort sample), 1,175 grounding attempts and 29 short sessions in the arm64 image on a Java 8 desk, USD 5.64. V1 with marks and V2 through the computer toolset were right every time on Sonnet and Opus (135 of 135 attempts each); V1 by text 96 % (the text snapshot hides the table header and rows); V2 with a custom click tool 93 % on Sonnet and 100 % on Opus, every pixel miss another whole component chosen by meaning, none by a few pixels. Sessions: 25 of 25 done on Sonnet and Opus, V1 4-5 actions, V2 6-14 (icon-only keys; 4-5 with a pack legend), USD 0.02-0.06 each, 1.0-1.5 s per Sonnet call, about 2 s per Opus call. Found: a frame's `PAINT` prints a substitute title bar on the X toolkit (capture the root pane); an error `tool_result` may carry only text; `PriceTable` lacks `claude-haiku-5-5` |
| BS13 | The Claude API facts the Phase 5 contract relies on (structured output, strict tools, effort, caching minimums and TTLs, token counting, the Batch API, the fallback parameter, error mapping, the `claude` CLI's headless envelope), checked live once; the three Phase 5 dependency sets resolved and listed for the licence audit | **Passed: offline part 2026-10-07/08, live part 2026-10-08 (afternoon)** (`spikes/BS13/RESULT.md`): the live part ran through SDK 2.70.0 (the build's line) with the owner's workspace-scoped key, USD 0.032 in all (the resumed CLI call reports the session total, counted once), the `claude` CLI item natively. Settled: structured output as specified (a numeric `minimum` and `minItems` 2 refused, a string `maxLength` accepted; 2.70.0's typed form keeps `effort`); the strict tool called with valid input, a forced tool choice refused; Opus 5.5 returns a thinking block (empty, signed) at effort low, Sonnet 5.5 none; a 1135-token prefix written then read, the one-hour TTL without a beta header; `count_tokens` exact; a batch of two ended after 458 s with its results in reverse order, the second request reading the first's one-hour prefix (not a standard request's entry); `fallbacks` needs the beta header and `usage.iterations` is present whenever it is sent; 404/400 as mapped; the CLI's envelope with `structured_output`, `is_error: false`, `--resume` keeping the session. Found beside the probe: **Haiku 4.5 refuses `output_config.effort`**, so the gateway sends none to the Haiku class (P5 §4.1). The offline findings stand (SDK MIT, `mcp-core` + `mcp-json-jackson2`, per-attempt retries, `fromEnv()`'s reach, the error classes) |
| BS14 | Byteman loaded in process through the agent's own `Instrumentation`, with no listener: the rule base reached through Byteman's `manager:` option by a project class on the system class path, rules fired into a curated helper, listed and removed with a byte-identical restore, `RuleCheck` in process, nothing listening, no JEP 451 line (the gate of Phase 6's Byteman module, P6 §7.2) | **Passed with conditions 2026-10-09** (`spikes/BS14/RESULT.md`): Java 8 (1.8.0_504) and Java 25 (25.0.4.1) targets in the arm64 image, `-javaagent`, five configurations. Items 1 to 6 pass on both targets and a second module instance reuses a parked `Retransformer`. The five conditions: the option string is `listener:false,manager:<RuleManager>` in that order and has no `prop:` (`prop:org.jboss.byteman.verbose=false` prints on the host's stdout and, Byteman reading the property by presence, turns verbose on; a later `listener:false` clears the manager); Byteman does not match a varargs helper method, so the helper has fixed-arity `fired` overloads; a trigger-time failure prints on the host's stderr through the helper's trace map unless the module redirects it, and a transform-time failure prints nowhere (only `listScripts` shows it); a sys-jar method with a Byteman-typed parameter must be branch-free, or its StackMapTable adds a `CONSTANT_Class` for Byteman; the byte check needs two recorders, one ahead of and one behind Byteman's transformer. Not run: the amd64 image, Windows, `ATTACH` with the real attach client |

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
- **Shown on 2026-10-09 (Phase 6, CP; `design/phase6-contracts.md` §8.1, §8.2, check 1b and 1c).** On a Java 25 target
  in the Linux image (arm64) `ATTACH` prints the JEP 451 warning (the positive control of check 1b) and `JAVAAGENT`,
  `JAVAAGENT` through `JAVA_TOOL_OPTIONS` and `EMBEDDED` print none (`jep451Lines=0` in every non-`ATTACH` leg). The
  capability matrix of a Java 8 target equals a Java 25 target's, normalised, in all four modes (committed goldens,
  reasons by presence, and a live diff between the target's Java and the test JVM's). In the hooks leg a Java 25 target's
  log holds one class-data-sharing line (the one bootstrap append) and, after checks 16 and 17, one JEP 451 warning per
  re-attach (`ATTACH` of `skillroy-agent-bootstrap.jar`), none of the engine's own.
- **Not shown.** On a Java 25 target: the refusal under `-XX:-EnableDynamicAgentLoading`, the argument cap and the
  start-up signal hazard (no measurement after the switch). A different-user attach and jattach through the framework's
  own `AttachSession`; a deliberately broken agent jar reported with its code end to end (the reply mapping is
  unit-tested only); Windows. (`hook.*` capabilities: shown in Phase 6, the hooks leg.)

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
- **Explained 2026-10-10 (BS9, below).** Under a window-manager-less display nothing gives the frame its focus back after
  a modal dialog is disposed: the X focus reverts to `PointerRoot`, the Java window still receives the keys from X, and
  AWT drops a posted key event when the JVM has no focused window (swing-driving F13 settled). With a window manager the
  frame is refocused.
- **Not shown.** The `ROBOT` rung (not built; its facts are BS9's); the proposed statistic of 1000 attempts.

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

Proposed in `research/vision-input.md` R10; they come before any Phase 7 work. All four ran on 2026-10-10 (BS9, BS10 and BS11
passed with conditions, BS12 passed; rows in the table above, sections below).

| Id | Question | Pass criterion |
|---|---|---|
| BS9 | Robot on the WM-less Xvfb and on a desktop session: do pointer and key events land, on Java 8 and Java 25 targets? `ROBOT` versus `PAINT` capture of the sample | a written table like BS4's; settles swing-driving F13 |
| BS10 | Routed in-JVM dispatch: buttons, caret placement, popup menus, combo boxes, tables, wheel, a drag inside a component, a glass pane, a disabled component; Java 8 and Java 25 | each case behaves as with Robot on the same build, or the difference is documented |
| BS11 | `PAINT` determinism: the same window, 100 captures on one JDK; then across JDK lines and font sets | identical hashes within one environment; differences across environments quantified |
| BS12 | Grounding and cost on the sample: V1 (references) versus V2 (pixels) on a Sonnet-class and an Opus-class model; seconds per action; tokens per session | measured accuracy, latency and cost replace the estimates of the research note |

### BS9 and BS10 Robot input and routed dispatch

- **Proves.** BS9: whether `java.awt.Robot` pointer and key events land in a Swing application on the window-manager-less
  Xvfb and on desktop sessions, on Java 8 and Java 25 targets; `ROBOT` against `PAINT` capture of the sample; why XTEST
  keys did not reach a Swing application under a window-manager-less Xvfb (swing-driving F13). BS10: whether a mouse
  event whose source is the heavyweight window, in window coordinates, dispatched non-posted on the EDT, behaves like
  Robot's (research vision-input F7, Risk2).
- **Run.** `spikes/BS9/` (a key probe and a sample probe, a derived image with openbox, xdotool and x11-utils from
  Ubuntu 26.04's repositories, `run.sh`) and `spikes/BS10/` (a toy window with 16 cases and a harness loaded with
  `-javaagent` that drives each case by Robot, by routed dispatch and by the delivery of the built `DISPATCH` rung,
  recording the outcome, the focus, the press target, the hit-test and every mouse event AWT dispatched), both Java 8
  language and API, standalone Maven projects built offline. Legs, one container each through `ci/run-in-linux.sh`
  (Xvfb 1024x768x24): WM-less and openbox on Temurin 1.8.0_504 and 25.0.4.1 (arm64), WM-less Java 25 on the emulated
  amd64 image, and this Mac (macOS 26.6.2, Oracle OpenJDK 25.0.1) once, in strict mode under the display lock; 70 target
  JVMs, all exit 0.
- **Pass.** BS9: a written table like BS4's, and F13 explained. BS10: each case behaves as with Robot on the same build,
  or the difference is documented.
- **Facts (result 2026-10-10: both passed with conditions).**
  - Robot pointer events land as a person's on Linux in every case tried, on both Javas, with and without a window
    manager: clicks, hover, pixel-accurate clicks (caret, table cell, list item), drags, the wheel, popups, a combo box,
    a double-click; a glass pane with a listener takes the click, another window of the JVM on top takes it, a window
    blocked by a modal dialog gets nothing. Three focus-gated, debounced keypad keys of the sample fired 3 of 3.
  - Robot and XTEST keys land in the AWT focus owner if and only if the JVM has a focused window; keys never follow the
    pointer (with the focus on a button the letters are typed into the button, which looks like "keys are ignored").
    **F13 explained:** a window-manager-less X server starts with the focus at `PointerRoot`; Java sets the X focus only
    when a frame is shown with `autoRequestFocus`, on `toFront()` plus `requestFocus()`, or when a component of an
    unfocused window asks for it; the Java window receives the key events from X, but `DefaultKeyboardFocusManager`
    drops a posted key event when there is no focus owner and no focused window. A window shown with
    `setAutoRequestFocus(false)` has none, and **after a modal dialog is disposed nothing gives the focus back to the
    frame** (not within 2 s; BS4's observation, now with its mechanism). Openbox refocuses the frame within 1 ms. Not
    the keymap (every key gave its letter). Self-activation took 266-424 ms WM-less, 25-30 ms with openbox, 32 ms on
    the Mac. With xdotool on Java 8 the click that activates a never-activated window lost a race in 1 of 6 clicks (2
    of 7 with the smoke run); Robot 0 of 12.
  - On the Mac (no Accessibility permission for the process) Robot was constructed, threw nothing and delivered nothing,
    pointer or key; the window activated itself (frame focused 48-168 ms after show) and `ROBOT` capture returned the
    window's pixels with macOS's title bar.
  - Capture: `ROBOT` and a `PAINT` of the root pane are identical on Linux (0 differing pixels in 23 of 24
    comparisons; 24 px, the caret, in the 24th); `ROBOT` against `PAINT` of the frame, which is what the built
    `swing.capture` does: 12,021 px WM-less, 30,364 with openbox (27,084 in the decoration band), 37,390 on the Mac
    (28,672 of them the 28 px title band). The cause is `XFramePeer.print` (gap 76). On a Retina Mac `ROBOT` and the root pane differ by
    8,718 px of antialiasing (a 2x capture scaled to user space against 1x painting). The cursor is absent from X11
    `ROBOT` captures.
  - BS10: routed dispatch matched Robot's outcome in 14 of 16 cases on every Linux leg and Java line (a button with
    rollover, tool tip, caret at a pixel, selection drag, double-click, popup menus of both kinds, combo, table, wheel,
    slider drag, glass pane with and without a listener, disabled button) and AWT's dispatched events in 13 of those 14
    (the X11 toolkit drops the `MOUSE_CLICKED` of the press that opens a combo box's popup; routed delivers it, with no
    effect). Java 8 = Java 25 throughout; on the Mac the same 16 routed outcomes. The two differences are by
    construction: a modal-blocked window receives routed events, and routing cannot see a window on top. The built
    `DISPATCH` rung differs from Robot wherever the position inside the component matters (it clicks the centre),
    bypasses a glass pane, and sends no enter or move. The native release after a drag has click count 0 and no click
    follows; a retargeted button-3 event carries `META` in its extended modifiers. A public-API search for the mouse
    target named the component that received the press in 13 of 14 cases; it missed a label with
    `setComponentPopupMenu` (an enabled event mask, no listener).
- **Conditions.**
  1. A `ROBOT` rung and the V3 tier need a delivery probe (Robot's move must arrive within 1 s, else
     `ROBOT_UNAVAILABLE` with the reason), because on a Mac without permission Robot delivers nothing and says nothing.
  2. Keys only when the intended window is focused and the intended component owns the focus; otherwise activate first,
     and on a window-manager-less display activate again after every dialog closes.
  3. The point must lie in a window of the target JVM that nothing covers; on a person's desktop act only while the
     target JVM holds the focused window, and restore the pointer.
  4. `swing.capture` paints the root pane, not the frame (gap 76).
  5. `swing.pointer` keeps the modal check and checks for a covering window of the JVM (routing sees neither), imitates
     the native sequence (a move first, the popup trigger per platform, the release after a drag with click count 0) and
     reports the component that actually received the press; the hit-test needs the `componentPopupMenu` and
     `autoscrolls` rules and says its mouse target is inferred.
- **What the Phase 7 contract takes from it.** `swing.capture` paints the root pane of a window and reports `origin`
  (the root pane's offset) with the decorations outside the image; `ROBOT` mode captures the window's screen bounds in
  user-space size and says that decorations are included. `swing.hitTest` reports the window, the other showing windows
  of the JVM that cover the point, `deepest`, `mouseTarget` (inferred), the wheel target, `enabled`, the modal blocker and
  the local point. `swing.pointer` `ROUTED` follows BS10's sequence (one `invokeLater` task per event, one wheel event
  per notch with amount 3 on X11, refusal on `MODAL_OPEN` or a covered point unless `force`) and reports the actual press
  target, the focus owner afterwards and any window activation the press caused. The `ROBOT` rung reports
  `NOT_DELIVERED` when no press arrives within its deadline. The test environment keeps the window-manager-less display
  (routed input and `PAINT` capture need no window manager); a Robot-keyboard test runs with openbox (the recipe is in
  `spikes/BS9/docker/`) or activates explicitly.
- **Not run.** Windows (UIPI, session 0, the popup trigger on release), Wayland, a locked session, macOS with
  Accessibility granted, heavyweight popups, native drag and drop, components that read `MouseInfo`, HiDPI on Linux, JDK
  lines between 8 and 25, more than one final run per leg (the smoke runs agreed).

### BS11 PAINT capture determinism

- **Proves.** The built `swing.capture` (`printAll` into an image on the EDT) gives the same pixels for the same window
  state within one environment, how much and where the pixels change across environments, and whether a tolerance can
  make a baseline portable.
- **Run.** `spikes/BS11/` (a standalone Maven project, not a reactor module: a Java 8 toy window and a Java 25 harness,
  `run.sh`, `scripts/run-env.sh`, `scripts/run-mac.sh`, derived images under `images/`; built from `main` at `11fce4e`
  with the reactor's artifacts of `4f9962f`): the sample desk (seed A) with the built agent home plus the sample's
  agent module through `TargetLauncher`, `swing.capture` over the control channel through `AgentClient`, each capture
  decoded and hashed (SHA-256 of the ARGB raster; of the PNG bytes). State changes through the agent's own `swing.type`
  and `swing.dismiss`, waits on the desk's events and `swing.settle`. Fifteen environments: the arm64 image on Temurin
  25.0.4.1 and 1.8.0_504 (100 captures per series, 5 launches), the unmodified image as a control, the emulated amd64
  image (25.0.4.1, 1.8.0_492), `awt.useSystemAAFontSettings` on, off and lcd, `swing.aatext`, Xvfb depth 16, two derived
  font images (`fonts-liberation2` and `fonts-noto-core`; a `local.conf` preferring Liberation), and macOS 26.6.2
  natively (OpenJDK 25.0.1, under the display lock).
- **Pass.** Identical hashes within one environment; differences across environments quantified.
- **Facts (result 2026-10-10: passed with conditions).**
  - Within one environment 100 of 100 captures of the main window with no focus owner hash identically on the Java 25
    and the Java 8 target (raster and PNG bytes). Five fresh launches, a field typed and cleared, a dialog opened and
    closed, the window covered by another process's window and moved (`xdotool`), two dialogs and the toy reproduce the
    same hashes. The one exception is the blinking caret of a focused field: a 1 x 24 px column (24 of 786,432 pixels,
    per-channel difference 204) gives two alternating variants (50/50 of 100 on both targets in the recorded runs),
    the same size as a real one-character change (28-50 px).
  - Across environments, unchanged: the CPU architecture (same JDK build, freetype 2.14.2, fontconfig 2.17.1), the
    Xvfb depth, `swing.aatext`, `useSystemAAFontSettings=off`, the JDK 8 update. Changed: the JDK line 8 against 25
    (0.30 % of the main window, max 187: antialiased shapes of the icon keys and the table header; the dialogs 0.08-0.16
    %, max 28; the toy nothing), text antialiasing on or lcd (0.14-0.82 %, max 111-180: glyphs), two font packages
    (3.3-4.7 %, max 238: the default sans face moves from DejaVu to Noto), a `local.conf` preferring Liberation (1.0 %
    of the main window), macOS (7.9-14.7 %: Aqua, fonts, a 28 px title bar inside the frame).
  - Real changes within one run: 28 px (one character of a label), 50 px (one character in the entry field), 1,725 px
    (a button disabled), 3,264 px (a colour; at max 6 for a subtle tint). No per-channel tolerance with a pixel budget
    makes the antialiasing, font or macOS pairs equal and still sees a one-character change (the noise inside that one
    label is up to 1,056 px); for Java 8 against 25 the dialogs compare equal at tolerance 32 but the main window needs
    187, which hides the label change. Inside one fingerprint the noise is zero, so the comparison there is exact.
  - A 1024 x 768 capture takes 18-33 ms natively, PNG encoding and HTTP included.
- **Conditions.**
  1. Carets, and any other time-dependent painting, are masked on both sides of a comparison.
  2. The focus owner and whether its window is active are part of the captured state (on Aqua losing the focus repaints
     the field's focus ring, 9,625 px; without a window manager nothing returns the focus after another window closes).
  3. A baseline or pixel anchor is bound to a fingerprint of the rendering inputs (JDK line and update, operating
     system, look and feel, the effective text antialiasing, the logical fonts' resolution, the screen scale, the
     window's size and insets), and compared exactly inside it, after the masks.
  4. The hash is over the decoded raster, never the PNG bytes (the same raster encodes to 2,419 bytes on Java 8 and
     3,536 on Java 25).
- **What the Phase 7 contract takes from it.** `swing.capture` gains `carets: [{ref, rect}]` (every showing focused
  text component with a visible caret, computed in the same EDT read as `printAll`; the host's caret is never hidden)
  and `focus: {owner, windowActive}`. The image guard and `expectImage` compare the decoded raster exactly after masking
  the union of both sides' caret rectangles and the pack's masks, then take a pixel difference for the report only
  (default tolerance 0 and budget 0; an expectation may set a small tolerance); a focus-state mismatch masks the focus
  owner's bounds and says so. The environment fingerprint of a scenario with pixel anchors or image baselines carries the
  rendering inputs above (architecture and X depth for information only) and a probe-render hash as a tripwire (a fixed
  off-screen panel with labels, a field, a table header, a button and an antialiased shape, painted in the target); a
  mismatch makes an anchor `REBIND_NEEDED` and an `expectImage` "not applicable" (reported, not failed), and baselines are
  kept per fingerprint. The research note `vision-input` can cite BS11 at F11 and F15; its Risk3 holds, with the caret
  and the focus state added. BS9 found afterwards that the built capture of a frame on Linux includes `XFramePeer.print`'s
  imitation decorations (gap 76); they are deterministic, so BS11's findings stand, but a baseline taken before the
  capture paints the root pane is invalid after it.
- **Not run.** Windows, a HiDPI scale, a JDK update within the 25 line, a Linux desktop with a window manager or a
  compositor, embedded web views, captures under load, a window larger than 4096 px.

### BS12 Grounding and cost of a vision loop on the sample

- **Proves.** How well, how fast and how cheaply current Claude models find an element of a Swing desk, by reference
  (V1: the text snapshot or numbered marks on the capture) and by pixel (V2: a custom click tool or the computer
  toolset), and what a short loop (capture, model, act, wait for the desk, capture) costs per task.
- **Run.** `spikes/BS12/` (a standalone Maven project, live only, `README.md`) in the arm64 image under the WM-less
  Xvfb through `ci/run-in-linux.sh`, the key passed by name; the harness on Temurin 25, the desk on Java 8 with the
  agent; the Anthropic Java SDK 2.70.0; observations from the built primitives (`swing.windows`, `swing.snapshot`,
  `app.state`, `swing.capture` `PAINT` of each window's root pane, composited in the frame's space), the hit-test in the
  harness from the snapshot's bounds, actions through the existing rungs (never Robot). 27 targets on five screens, four
  variants, three models, effort `low` (three repetitions, two for Opus) and a default-effort sample; two tasks (lend a
  spade; lend a ladder through the induction prompt) as loops with the toolset and with ref tools, one cache breakpoint
  after the tools and system text and one advancing on the latest tool result. Spend capped at USD 10.00.
- **Pass.** Measured accuracy, latency and cost replace the estimates of the research note (F3, F4, F14, R6).
- **Facts (result 2026-10-10: passed, USD 5.64; 1,587 API calls).**
  - Accuracy at effort `low` (correct of attempts): V1-marks Sonnet 81/81, Opus 54/54, Haiku 77/81; V2-toolset 81/81,
    54/54, 75/81; V1-text 78/81, 52/54, 78/81; V2-custom 75/81, 54/54, 65/81 (12 of Haiku's calls written as text).
    Default effort (nine targets once): all right except one Haiku call. Effort changed neither accuracy nor output
    tokens on single-element questions.
  - No pixel answer of any model missed its target by a few pixels: every miss hit another whole component chosen by
    meaning (a power icon for "the key that leaves return mode", the entry field for "the prompt line"), the same
    wrong answer on every repetition (313 of 324 groups gave the same answer each time).
  - A 1024x768 capture is 1,039 tokens on all three models; the computer toolset's declaration 4,522 (4,111 with
    `zoom` off, which no model ever called in 828 toolset calls). A grounding answer costs USD 0.004-0.006 on Sonnet,
    0.008-0.010 on Opus, 0.0002-0.0003 on Haiku. One model call 1.0-1.5 s median on Sonnet and Haiku, about 2 s on Opus
    (p90 under 3.5 s); the desk's side of a step 0.2-0.3 s, plus 3.1 s when the desk drops an input silently.
  - Sessions: 25 of 25 done on Sonnet and Opus. V1 used 4-5 UI actions, V2 6-14 (the keypad keys are icons without
    text; Sonnet read "or DUE" in the prompt line as "type DUE"), 4-5 with a one-paragraph legend. USD 0.02-0.06 per
    session on Sonnet and Opus (Haiku USD 0.0013-0.0022; its V2 sessions stopped one action short, 0 of 2), 61-89 % of
    the tokens read from the cache.
  - Haiku 5.5 is on the high-resolution tier and takes the toolset; its weakness is format (22 calls written as text)
    and judging completion. Downscaling hurts icons first (at 0.5 Sonnet confused three keys; at 0.75 it kept its
    accuracy).
- **Conditions.** None on the verdict. Findings that change built code or the contract: a window's `PAINT` on the X
  toolkit with zero insets prints a substitute title bar (title and two boxes) over the content's top 20 px, so a window
  is captured as its root pane (BS9 found the cause, `XFramePeer.print`'s imitation Motif decorations, gap 76); the text snapshot (`UiSnapshotText`) cuts a table's value at 80 characters and drops
  unnamed container-role nodes, so it hides table rows and the table header (V1-text 0 of 8 on "the column headings");
  the stale-capture rule of design §3.10 step 1 is too strict for a batch (a click after a dialog-closing click was
  refused; re-hit-testing and acting when the same component is under the point let 16 through and refused none);
  `PriceTable` has no `claude-haiku-5-5`; an error `tool_result` may carry only text (400 otherwise).
- **What the Phase 7 contract takes from it.** The default tier is V1 with marks (the reserved `marks` result field: a
  number, ref, row and bounds per interactive component, named icon-like custom component, label with text, table
  header and visible table row) on Sonnet 5.5 at effort `low`; V1 with the text snapshot is the fallback; Opus 5.5 only
  for a step that failed twice. V2 through `computer_toolset_20260801` is the opt-in "clicks like a human", with
  `zoom` disabled up to about 1366 px and a short pack-supplied legend for icon-only controls (a new optional pack
  hint, named by the contract). Haiku 5.5 only for V1, preferably with structured output, never for V2. The capture is
  the composite of the target's showing windows in the frame's space (`windows`, `origin`, `space`), each window as its
  root pane, at its own size, scaled down only above about 1920 px on the long edge and never below 0.75. The text
  snapshot for a model keeps table rows (bounded) and unnamed interactive components. Loop rules: settle after every
  batch and before every screenshot; a model's `wait` is a settle, never a sleep; the capture goes on the last result
  unless it is an error result or the batch ended with its own screenshot; the post-action wait is bounded short, or the
  pack names an "input dropped" event. Budgets: USD 1.00 and 30 UI actions per session as before, plus a 20-turn limit
  (a 30-turn session extrapolates to about USD 0.30 on Sonnet and 0.5-0.7 on Opus). `claude-haiku-5-5` joins
  `PriceTable` (USD 0.10 / 0.50 per million tokens, cache read 0.01).
- **Not run.** The amd64 image, Windows, macOS, Java 25 or other look and feels and fonts on the desk, screens larger
  than 1024x768 or HiDPI, V3 (Robot; BS9's ground), embedded web views, combo boxes and menus (the desk has none), tasks
  longer than 14 actions, an application that changes on its own between capture and action. Two to six sessions per
  cell and 27 targets on one application.

### BS14 Byteman in process

- **Proves.** A user-supplied Byteman jar can be loaded in process through the agent's own `Instrumentation`, with no
  listener: the project's sys jar and Byteman appended to the system class path, `org.jboss.byteman.agent.Main.premain`
  called reflectively with `listener:false,manager:<RuleManager>`, the `Retransformer` received once in the manager's
  `initialize`, rules submitted, fired into a curated helper, listed and removed with a byte-identical restore, and
  `RuleCheck` run in process; nothing listens, no thread is added, no JEP 451 line.
- **Run.** `spikes/BS14/` (a standalone Maven project, not a reactor module: the aggregator of four modules `sys`,
  `probe`, `agent` and `toy`, `run.sh` and `scripts/run-config.sh`) in the arm64 image on Temurin 1.8.0_504 and
  25.0.4.1: `jdk25-javaagent` and `jdk8-javaagent`, the same two with the contract's own option string
  (`jdk25-contract-options`, `jdk8-contract-options`) and `jdk25-order-reversed` (`listener:false` after `manager:`) as
  the negative control.
- **Pass.** Every item of P6 §7.2 on both targets: `premain` called after the two appends, the `Retransformer` signatures
  as written, a rule with the project helper fires and its removal restores the bytes, `RuleCheck` in process, nothing
  listening before, after the load and after the unload, no JEP 451 line, and a second module instance reuses the
  parked `Retransformer` (`premain` refuses a second call).
- **Result 2026-10-09: passed with conditions** (`spikes/BS14/RESULT.md`; committed as `7640125`). Four configurations
  PASS, the control without the manager is as expected (no `Retransformer`); `premain` took 16 ms on Java 8 and 59 ms on
  Java 25, one retransformation 7 ms, a removal 2 to 3 ms. The five conditions, all folded into P6 §7 as built (1, 2,
  3 and 5 are D194, D195, D197 and D196; 4 is in the note on `RuleManager`, with D200):
  1. the option string is `listener:false,manager:<RuleManager>` in that order and has no `prop:`; the contract's
     `prop:org.jboss.byteman.verbose=false` printed 127 (Java 8) and 135 (Java 25) console lines against 24;
  2. the helper has fixed-arity `fired` overloads of one to eight arguments, because Byteman does not match varargs;
  3. a trigger-time failure prints on the host's stderr through `Helper.traceMap` unless the module redirects it, and a
     transform-time failure prints nowhere (the module reads `listScripts` after a submit);
  4. a sys-jar method whose parameter is a Byteman type must be branch-free (else a `CONSTANT_Class` for Byteman lands
     in its constant pool);
  5. the byte check needs two recorders, one ahead of Byteman's transformer and one behind it.
- **Not run.** The amd64 image, Windows, Java lines between 8 and 25, `ATTACH` with the real attach client (the module's
  reuse path is simulated through `agentmain` in `BytemanIT`), a large real application.

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
- **The planner's stdin route, checked live on 2026-10-10** (after Phase 6's fix of S93, P2-L7-F3, which moved the
  prompts to stdin; natively on macOS, `claude` 2.1.286, USD 2.05 in all): item 5 alone, exit 0 in 159 s, 49 turns, no
  repair, USD 1.11; then a forced `--resume` repair round through a logging wrapper (the first document's first verb
  renamed to an unknown one), exit 0, `repairs: 1`, 43 turns, USD 0.94. The prompts were the CLI's stdin each time and
  no brief, instruction or problem text was an argument. One new fact: a resumed envelope's `total_cost_usd` and
  `modelUsage` are running totals while its `num_turns`, `usage` and `duration_ms` are its own, which the planner
  already reads so. Not verified live: a repair started by a compile problem alone, a failed repair, Windows. The
  row of the live table at the top has the figures.

### Phase 6: hooks, Byteman, Windows, OTel, hardening

**Specified 2026-10-09** (`design/phase6-contracts.md`, with `design/threat-model.md`): written from seven readers' maps
and spike BS3 (passed with conditions), reviewed by three lenses with a sceptic each (72 raw findings, 65 confirmed,
27 more found by the sceptics, all applied) and rechecked twice; decisions D114-D159; its twenty-two questions are
**B26**, provisional while the build proceeded on them (the owner asked for progress on 2026-10-09) and confirmed by
the owner on 2026-10-10. The build ran in five work packages:
HK (hooks and spy), CP (controller, pack, the sample's branch edition, scenario E, JDK 25), TL (telemetry), BW (Byteman
behind spike BS14, and the Windows path), FX (the security fixes of the repeat review's pass 1, S35-S62).

**Built 2026-10-09**, the day it was specified, in six packages (FX went to two implementers), each in an isolated
worktree (`.claude/worktrees/p6-<tag>`, branch `p6/<tag>`) made by hand from the contract commit `065746d`: HK, CP, TL
and BW first, four at a time, FX over the merged four. Each implementer committed on its own branch, signed off, and
touched no `TODO.md`, `DECISIONS.md`, `CLAUDE.md`, `NOTICE` or `CONTRIBUTING.md` (its text for them went into its
report); the orchestrator merged the branches into `main` in the order below with a native reactor between
(`mvn -o -q verify`), then brought the documents together (the numbering of the build's decisions, B27, this section).
Nothing is pushed. The build took 62 decisions, **D160-D221** (`DECISIONS.md` B27, confirmed by the owner 2026-10-10), numbered in landing
order; the contract carries an `As built (Phase 6, <TAG>, 2026-10-09)` note under every section a package built or
deviated from, and marks in the Phase 1 to 5 contracts and in the overview.

| Package (landing order) | What it built | On `main` | The orchestrator's native reactor on `main` after the landing |
|---|---|---|---|
| TL (telemetry) | the `RunObserver` SPI in `skillroy-scenario` with its hook points, `CallOptions.traceparent` and the inbound MCP `traceparent`, `durationMs` on the bind records, the module `skillroy-telemetry` (OpenTelemetry 1.66.0: `otlp` over OTLP/HTTP and the project's `skillroy-spans/1` `file`), the CLI options `--telemetry*` on six commands, the JUnit glue's `skillroy.telemetry.*` with a JVM-exit flush, `THIRD-PARTY.txt` and `DistributionNoticesIT` for the eleven OpenTelemetry jars; D160-D168 | `8fc2384` (fast-forward) | exit 0; 4,074 tests, 0 failures, 0 errors, 385 skipped (surefire and failsafe together); 5 min 7 s |
| HK (hooks and spy) | the wire types `…protocol.hook`, the `HookEngine` SPI and `…api.instrument` in `skillroy-agent-api`, `optionalModules` and the spy package's loader rules in the core and the bootstrap, `skillroy-agent-spy` (`Spy`), `skillroy-agent-bytebuddy` (Byte Buddy 1.18.14 relocated) and `skillroy-agent-hooks` (the engine, the six `hook.*` primitives, the recorder and the restore that is its own verification), the home's `skillroy-agent-spy.jar` and `optional/`, `DistributionContentIT`'s list and rules, `HomeConstantPoolIT`, `OptionalModulesIT`, `SharedLoadersIT`; D169-D183 | `9044c9a` (part 2; part 1 `c42f040`, merged by CP and BW) merged as `720eb6b` | exit 0; 4 min 46 s |
| CP (controller, pack, sample, JDK 25) | the hook catalogue reader, overlay and installer (step 0b), the `hooks` fact root, `capabilities.json`, `--hooks` and `skillroy hooks status/install/remove`, the never-offered `hook.*`/`rule.*` set, the pack overlay SPI (`DomainPackOverlay`, `DelegatingDomainPack`, `PackSettings`), the sample's branch edition, pack 0.5.0 with its catalogue, the overlay module `skillroy-sample-lending-pack-branch`, scenario E `return-within-limit-observed`, checks 1b and 1c with the committed goldens, the hooks leg (`HookChecks`), `BranchEndToEndIT`, the live matrix diff; D184-D193 | `cf79b52` (fast-forward; the package `91cd1a1`, merges of TL, HK and `main`, `14bd710`, `cf79b52`) | exit 0; 4 min 38 s |
| BW (Byteman and Windows) | spike BS14; `skillroy-agent-byteman-sys` and `skillroy-agent-byteman` (`rule.check/submit/unload/list`, the lint, `BytemanIT`, the hooks leg's checks 166-168); the Windows half: `bin/skillroy.bat`, `PATHEXT`, plain `cmd.exe /c`, `SecureFiles` public and its sites, `ci/run-on-windows.cmd`; D194-D207 | `b22013e` (fast-forward; spike `7640125`, Windows `26d3c22`, Byteman `3bf51e5`, `8ba12c9`, `9b87ba4`, documents `cd38182`, integration `5457ccb`, `b22013e`) | exit 0; 4 min 47 s |
| FX-2 (security fixes, tag SR: FX-inference and FX-mcp) | S49-S62 and their contract text (A50-A60), D157-D159 in code; D208-D215 | `755844c` (fast-forward; `6d6ce08`, `ee020ad`, `755844c`) | exit 0; 4 min 45 s |
| FX-1 (security fixes, tag SR: FX-web, FX-scenario, FX-ci) | S35-S48 and their contract text (A40-A49), D156 in full; D216-D221 | `822dc58` (fast-forward; `8333680` and the merge of FX-2) | not run again on `main`; FX-1's own run of the merged tree is below |

The packages' own native runs (the whole reactor, JDK 26, macOS arm64, headless; read from the XML reports by each
package, on bases that differ, surefire alone or surefire and failsafe, so they do not add up across rows): TL 4 min 28
s, green; HK 3,778 tests, 0 failures, 99 skipped (4 min 27 s); CP green, per module among others scenario 645, inference
961, cli 178 and 17 ITs, mcp 61, lending-app 109 (9 skipped), lending-scenarios 125 unit tests; BW 3,991 tests, 0
failures, 103 skipped (4 min 39 s) on the merged reactor; FX-2 surefire 3,952, 0 failures, 91 skipped, and failsafe 439,
388 skipped (4 min 36 s); FX-1 on the merged tree surefire 4,023, 0 failures, 91 skipped, and failsafe 443, 388 skipped
(4 min 47 s). The skips are the display and live tests.

The image runs of each package (arm64 image `skillroy-test-java-ci:bs8-arm64`, Temurin 25.0.4.1 and the aarch64
Temurin 8u504; `ci/run-in-linux.sh`, `-Dskillroy.headless=false`; counts read from the reports):

- **TL:** `TelemetryEndToEndIT` 2 of 2 and `TelemetryStreamTest` 1 of 1 on a Java 25 and on a Java 8 target (the file
  run wrote 59 spans; the OTLP run delivered its batches to the loopback stub with 0 dropped).
- **HK:** leg A (Java 25 targets, `-pl agent/skillroy-agent-dist -am`): 1,260 tests over the agent modules and the
  home's ITs, 0 failures, 8 skipped, 2 min 50 s; leg B (Java 8 targets): 1,260 / 0 / 8, 2 min 46 s, the child JVMs of
  `OptionalModulesIT` and `SharedLoadersIT` on Java 8; leg C (the agent modules' own tests on a Java 8 test JVM, 8u504):
  protocol 426, agent-api 125, spy 6, bytebuddy 2 (IT), hooks 55, core 190 (2 skipped), bootstrap 40, all 0 failures.
- **CP:** every CP leg verifying on a Java 25 target: 210 run, 0 failures, 20 skipped; on a Java 8 target 218 / 0 / 20
  (the skips: scenario E without hooks, which is `DEGRADED` by design, and the checks "once per seed" of the inference
  legs); the hooks leg's 34 checks ran on both Javas with no skip (E `PASS` with hooks on), the install in 174 ms on
  Java 25 and 136 ms on Java 8, `hook.remove` restoring every class `verified: OK` with 0 foreign events; the branch
  variant both halves; the live matrix diff `none`; `jep451Lines=0` in every non-`ATTACH` leg.
- **BW:** the Byteman modules' unit tests on the Java 8 test JVM, 32 run, 0 failed (every report 1.8.0_504);
  `BytemanIT` 2, `DistributionContentIT` 6, `HomeConstantPoolIT` 2, `OptionalModulesIT` 2, `SharedLoadersIT` 1 on a Java 8
  and a Java 25 target, 0 failed (the socket checks ran); `LendingEndToEndIT$JavaAgentHooks` 37 tests, 0 failures, on a
  Java 25 and a Java 8 desk, checks 166-168 among them (`rule.fired` with a handle and 20.00, unload `verified: OK`, 0
  foreign events, the same listening sockets); the other hooks-mode classes on Java 25 (36 + 2 + 22 run, 0 failures, 16
  skipped by design).
- **FX-2:** `InferenceEndToEndIT$JavaAgentSeedA` 11 tests, 0 failures, 0 skipped, 5 min 5 s (the MCP server as a
  subprocess under the `CLIENT` origin, the session script and its export, triage and the dry run), and the reactor's
  unit tests in the image: 3,952 / 0 / 0 / 8.
- **FX-1:** the web legs with `-Pjcef` (`-pl samples/skillroy-sample-web-host,samples/skillroy-sample-web-host-scenarios -am`),
  before and after the merge with FX-2: exit 0, 10 min 38 s and 10 min 31 s (on the merged tree the web host 50, its
  agent 6, its scenarios 14 and 68 ITs, cdp 128, mcp 74, inference 975, scenario 649, cli 191 and 18 ITs; the JCEF kit
  13 of 13 in both modes, `WebHostEndToEndIT` 64 / 0, `CdpAttachSoakIT` 4 / 0); the agent modules on a Java 8 test JVM
  (protocol 469, agent-api 126, agent-core 192 with 2 skipped, agent-web 184 with 4 skipped, 0 failures); on the merged
  tree `LendingEndToEndIT$JavaAgent` 29 / 0 (1 skipped: scenario E without hooks) and `InferenceEndToEndIT$JavaAgentSeedA`
  11 / 0.

**Surefire traps the build found** (both in "Environment notes" too): (1) surefire 3.6.0 with `reuseForks=false` runs
no test at all, reports `Tests run: 0` and exits 0, when `-Djvm` names a Java 8 JVM (the fork starts and is never
handed its class): HK's first Java 8 leg ran **zero** hooks tests and was caught from the reports' `java.version` and
counts; the hooks module now runs each of its six fresh-JVM test classes in a surefire execution of its own (D175), and
the Byteman module uses three executions of its own as well. (2) A command-line `-Dtest` **merges** the Byteman
module's separate executions into one JVM, where `ProbeTest`, `CheckLoadsNothingTest` and `InProcessRouteTest` failed
for the wrong reason (Byteman already loaded; a copy of the toy left by another test's loader matched a rule); in a
merged JVM they now skip with a message, and the module's counts are to be read from a run without `-Dtest`. A matrix
run of the Java 8 leg must read the reports' counts and `java.version`, not the exit code.

**What was not run (at the packages' reports; the matrix of the final tree is in the checklist below):** the full Linux matrix of the integrated tree (arm64 twice, amd64 once; the packages ran the
targeted legs above only, and the first arm64 round of the integrated tree was started on 2026-10-09 and is not
recorded here); any amd64 image leg of the hooks module and the home's ITs; anything on a Windows host (BW's
`ci\run-on-windows.cmd` and every item of `design/phase6-contracts.md` §9.5, §13.3); a real OpenTelemetry Collector
(§13.4, opt-in); `ATTACH` with the real attach client for the Byteman reuse path (simulated through `agentmain`); the
full `InferenceEndToEndIT` and `ScenarioDRecordIT` legs that CP did not change; the Java 8 target legs of the web host
and of the lending desk, and the lending desk's other load modes, after the FX-1 changes; the real JxBrowser product
(its channel bound is tested on the fake); any live model call (none was made or needed).

#### Phase 6 code review (2026-10-09)

The method of Phases 2 to 5: three lenses, each run by one reviewer on the committed tree (`f13657d`, the build with its
documents) with a shared brief, and each verified by a sceptic who tried to refute every finding with probes in a copy of the
tree and added what the lens had missed. **Contract fit** (L1): 5 findings (0 high, 1 medium, 4 low); the sceptic restated F1
and F5 (F5 raised from low to medium) and added S1, S2 and S3 (all medium). **Host safety, secrets and bounds** (L2): 11 findings
(0 high, 1 medium, 10 low); the sceptic added S3 (high), S1 and S4 (medium) and S2 (low). **Tests, determinism and the matrix**
(L3): 3 findings (low); the sceptic restated all three and added S1, S2 and S3 (low). In all **19 raw findings and 10 added by
the sceptics, none refuted: 1 high, 8 medium, 20 low** (the reports are in the session notes, `2026-10-09/phase6/review/`).
The most important:

- **A hook whose classes retransform only partly left live advice in a class the ledger forgot** (L2-S3, high, found by the
  sceptic): an install refused at step 6 while some classes had already taken its advice answered the refusal and released the
  slot, so a stale site key could still fire. Now a refused hook is taken out class by class before the call answers, a class
  that may keep its advice keeps its ledger entry, and the id of such a residue is refused until a remove restores the class
  (D236).
- **`minimal` telemetry still carried the scenario id, the step id, the pack version and the pack's vocabulary** (L1-F1 = L2-F9
  and L1-S1, medium): the exact list of dropped names did not name the correlation id or the sibling attributes. Now `minimal`
  is an allow-list with hash by default (D230).
- **Byteman's uninstall was not bounded by the detach budget** (L1-F5 = L2-S4, medium): 3016 ms against a 1000 ms budget, and
  module code ran in a worker after the module's loader was cut. Now a bounded lock and a detaching state (D222).
- **`HookInstaller` skipped the armed-stub refusal** whenever step 1 or 2 ended the install (L1-S2, medium): a replay could run
  past an armed stub. Now step 0 reads `hook.health` first (D231).
- **The `hooks` fact root's `last` was stale after 1000 fires** (L1-S3, medium): it took the oldest-first page's last entry as
  the newest, and `count` stopped at 1000. Now the root pages (D232).
- **Both encoders iterated JDK collections and views whose iteration takes a lock or runs the application's `compareTo`**
  (L2-F1 = L2-S1, medium): a `Vector`, a blocking queue, a `subSet` view of a `TreeSet`. Now exact JDK allow-lists in the hooks
  module and in the Byteman module, and a bit-length guard on numbers (D224, D237).
- The lows: the Byteman lint's reasons against its list, `getMessage()` of an application exception, a constructor entry failing
  every compile, a failure's text not redacted, `hooks.maxClasses` ranges, `JarCheck` following a link and skipping the owner,
  an unbounded latch in a test, a skip-always guard that turned a non-fresh JVM into a green skip, check 18's self-check, order
  67's inability to see `secretArgs`.

The fix wave: four fixers in worktrees from `edbc325`, integrated on a branch (`p6/int`) while pass 2's group a read the tree;
each with a report, the tests that fail on the old code and the contract text first (decisions D222-D243, B27):

| Fixer | What it fixed | Fixer's commit | Merge |
|---|---|---|---|
| FB the Byteman modules (D222-D229) | all findings of the Byteman half: the detach budget (L1-F5, L2-S4), the encoder's allow-list and number guard (L2-F1, L2-S1, L2-F3), `maxClasses` one range (L1-F4, L2-F4), the class bound before the load (L2-F5), `JarCheck`'s owner rule and real path (L2-F6, L2-S2), the lint's limits documented (L2-F8), `FreshJvm` and `failIfNoTests` (L3-S2, L3-F1). Native 4038 surefire + 439 failsafe, 0 failures; image: Java 8 test JVM Byteman 37 + 10, `BytemanIT`, the hooks leg 37 on both Javas | `5ed6780` | `f9c8033` |
| FC controller and telemetry (D230-D233) | `minimal` as an allow-list (L1-F1, L2-F9, L1-S1), the armed-stub check as step 0 (L1-S2), the `hooks` fact root paging (L1-S3), order 67 strict plus the headless `SignOnCheckedHeadlessIT` (L3-F3), check 18's kinds (L3-S3). Native 4029 + 440, 0 failures; image: hooks legs on both Javas | `9cd9183` | `f0e8477` |
| FD the sample's display test (D234-D235) | the typed-keys display flake (below), fixed in the test; the Enter semantics recorded in P1's sample UI bullet | `797b5ae` | `ffa83cd` |
| FA the hooks engine and the agent home's tests (D236-D243) | L2-S3 (high), L2-F7 (the slot and transformers released after an install of nothing), L2-F2/S1 (hooks half: `Capture`'s allow-list), L1-F2 (failure texts redacted), L1-F3 (`<init>` a per-hook state), L3-F1/F2/S1 (`failIfNoTests`, `assumeFreshJvm`, a bounded latch), the `BigDecimal` bit-length guard, `HookChecks` order 65 (the desk's unmodifiable list is one handle), the matrix's `OptionalModulesIT` on Java 8; L2-F10 and L2-F11 recorded in the contract. Native 4473 / 0 / 0 / 479; image: Java 8 test JVM 1356 / 0 / 0 / 8, hooks end-to-end 73 / 0 on both Javas | `55fe538` | `ce1f41f` |

After the four, the native reactor on `p6/int` (= `main` at `ce1f41f`) was green: 4496 tests, 0 failures, 0 errors, 480 skipped.

**The two findings of the first matrix round, fixed in the fix wave.** (1) `OptionalModulesIT.theDefaultHomeLoadsNoClassOfTheHooks`
failed on the Java 8 test JVM with a `NumberFormatException` in `ChildJvm.pid`: an auto-flushing `PrintStream` on Java 8 writes
`pid=3403` and its newline as two writes, and the JVM's whole `-verbose:class` line lands between them; `ChildJvm.pid` now takes
the leading digits (FA, D243; `ChildJvmIT` holds the matrix's exact string). (2) `LendingUiDisplayTest.typedKeysFillTheEntryFieldAndEnterSubmitsIt`,
twice in two days: a race in the test (the Enter was sent before the freshly shown frame had given the entry field the focus,
and `JTextField`'s Enter acts on the field that last had it), not the A39 focus loss and not an application defect; the test
waits for the focus and a new test pins the lost Enter (FD, D234, D235): **5 failures in 240 runs before, 0 in 380 after** (arm64
image, one fresh JVM per run, Java 8 and Java 25, warm and fresh containers). The same property of `swing.type` DISPATCH with
`enter` is gap 60.

**Phase 6 final matrix** (2026-10-09/10, on `4f9962f`, every fix merged; the commits after it change documents and two
comment lines only; `-fae`; counts read from the reports, the Java 8 test-JVM leg's reports checked for `java.version`
1.8.0_*):

| Run | Whole reactor, Java 25 targets | Same, Java 8 targets | Agent side on a Java 8 test JVM |
|---|---|---|---|
| native (headless, JDK 26) | 4692 / 0 / 0 / 493 | – | – |
| arm64 round 1 (on `822dc58`, before the review) | 4675 / 0 / 0 / 97 | 4675 / 1 / 0 / 97, then re-run with `-fae` 4675 / 0 / 0 / 124 (the typed-keys flake, fixed by FD) | 1347 / 0 / 1 / 8 (`OptionalModulesIT` on Java 8, fixed by FA) |
| arm64 round 2 | 4853 / 0 / 0 / 105 (939 s) | 4853 / 0 / 0 / 132 (855 s) | 1668 / 0 / 0 / 26 (243 s) |
| arm64 round 3 | 4853 / 0 / 0 / 105 (910 s) | 4853 / 0 / 0 / 132 (873 s) | 1668 / 0 / 0 / 26 (238 s) |
| amd64 round 1 (emulated, FORK) | 4853 / 0 / 0 / 105 (1423 s) | 4853 / 0 / 0 / 132 (1256 s) | 1668 / 0 / 0 / 26 (356 s) |

The scenario E capability-hash failure seen once by fixer SD did not recur in the final rounds; it recurred once in the first
arm64 round of the Phase 7 matrix and was then explained and fixed (FX, D449; "Open flake" above).

**Status of the checklist.** The security review item: pass 1 and pass 2 run, every medium and high fixed, signed off by the
owner on 2026-10-10 (B28); ticked below with the code review item and the matrix item.

**Checklist.**

- [x] `skillroy-agent-spy` and `skillroy-agent-hooks`: hook engine, probes, health, hook-catalogue format, post-detach
  byte verification (BS3 passed with conditions 2026-10-09); built and tested 2026-10-09 (HK, CP): installed and removed
  on the sample desk on a Java 8 and a Java 25 target with `verified: OK` and no foreign event (`HookChecks`: CP's 34
  checks, 37 tests with BW's three);
  the home's eleven jars and the default path loading no third-party class held by `DistributionContentIT`,
  `HomeConstantPoolIT`, `OptionalModulesIT` and `SharedLoadersIT`
- [x] `skillroy-agent-byteman` (optional and unbundled, B8): built 2026-10-09 (BW) behind BS14 (passed with
  conditions): `rule.check`, `rule.submit` (every submit needs `confirm`), `rule.unload`, `rule.list`, no listener ever;
  `BytemanIT` and the hooks leg's checks 166-168 green on Java 8 and Java 25 targets, the unit tests green on a Java 8
  test JVM
- [x] JDK 25 target through `JAVAAGENT`: no JEP 451 warning; capability matrix equal to the JDK 8 matrix: built and
  tested 2026-10-09 (CP: checks 1b and 1c in the four load modes on both Javas, the committed goldens, the live diff)
- [ ] Windows: `JAVAAGENT` as the logged-on user; `SEMANTIC`, `ACTION`, `COMPONENT` and `DISPATCH` rungs and paint
  capture verified; `ROBOT` excluded; attach from another logon session documented as unsupported; a `.bat` launcher
  for the CLI; the access-control fixes S7 and S15 verified. **Split 2026-10-09 (contract §14):** [x] the part Linux can
  test is built and tested (BW: `bin/skillroy.bat` with `BatLauncherTest` and `LauncherIT`, `PATHEXT`, plain
  `cmd.exe /c` with its refusals, `SecureFiles` public and its sites, the documentation of cross-session attach as
  unsupported); [ ] **verification on a Windows host waits for the host** (Walls): the `.bat` run, the planner through a
  launcher path and a `--pack` path with a space, the ACL branches, `JAVAAGENT` as the logged-on user with the rungs and
  paint capture, S7 and S15
- [x] OTel export (B11): built 2026-10-09 (TL), controller only, off by default, no class loaded without it
  (`ClassLoadingProbeTest`); `TelemetryEndToEndIT` green in the image on both Javas; [ ] the live, opt-in run against a
  real OpenTelemetry Collector (§13.4)
- [x] Repeat the security review before the first release (the findings of 2026-10-02 are closed by H1-H3 in
  Phase 1.x): **pass 1 and pass 2 run, every medium and high fixed; signed off by the owner on 2026-10-10 (B28)** (S35-S62
  and S63-S100, "Security review (repeat, pass 1)" and "(repeat, pass 2)" under "Status at a glance"; eight lows accepted
  with release notes; the matrix after the fixes is the checklist line below)
- [x] Tenant-pack overlay proven on a second variant of the sample lending desk (the branch edition): resolvers,
  constants, the allow-list and the hook catalogue overridden, scenarios A, B and E unchanged; built and tested
  2026-10-09 (CP: `BranchEndToEndIT`, both halves, E's negative showing the overlay's catalogue is needed)
- [ ] **Exit:** a hook is installed and removed on the sample lending desk with byte-identical restore (**met** in the
  packages' image runs, Java 8 and Java 25 targets); the sample scenarios pass unchanged with hooks enabled and with hooks
  disabled, and on a JDK 25 target loaded through `JAVAAGENT` (**met** in the same legs, E being the designed exception:
  `PASS` with hooks, `DEGRADED` without); on Windows the agent loads through `JAVAAGENT` as the logged-on user (**cannot
  be met without the Windows host**, contract §14); the repeated security review is signed off (**met** 2026-10-10, B28). Not
  ticked: one criterion waits (the Windows host); the Linux matrix of the final tree is green (the item after the code review)
- [x] The Phase 6 code review (three lenses, a sceptic each) on the integrated tree and its fix wave (tags `FA`-`FD`):
  done 2026-10-09 ("Phase 6 code review (2026-10-09)" above; D222-D243)
- [x] The Linux matrix of the final tree (arm64 twice, amd64 once; Java 8 and Java 25 targets; the agent side on a
  Java 8 test JVM, the matrix command's `-pl` list now seventeen modules, see "Environment notes"): green on every leg,
  2026-10-09/10, on `4f9962f` (the table "Phase 6 final matrix" above)
- [x] `SECURITY.md` and `docs/release-checklist.md` (D148, §12.8): written 2026-10-09 (`edbc325`: no package had written
  them); the release notes of the accepted lows (pass 1's five, pass 2's three) are in `SECURITY.md`
  "Known limitations", accepted by the owner (B28, 2026-10-10)
- [ ] The amd64 image legs of the hooks module and the home's ITs (HK: only arm64 run)
- [ ] (later, gap 55) `RpcResult.startedAt` for an exact agent span; (later, gap 56) OpenTelemetry metrics and logs
- [x] (code review) `minimal` keeps the scenario id inside `skillroy.correlation_id`: fixed by FC, D230 (an allow-list,
  hash by default; D166's clause superseded)
- [ ] (code review) the BSP worker thread's name (D163); whether `re-resolve` and `triage` should export more than the
  root span (gap 59, D168)
- [x] (owner) S52 refuses a configuration that worked before (a configured model id outside the price table; the
  alternative is a warning at open); D210: the key rule rewrites the sample's `signOn` and `authorise` descriptions in the
  tools and the planner's prompt, and whether the sample's wording should change: decided 2026-10-10 (B27 confirmed, no
  veto): the refusal stands and the sample's wording stays
- [ ] (optional) S46 item 4: the Swing locator on `BoundedRegex`, no behaviour change
- [ ] Gap 49 (an `inductionRequired` argument on `LendableItem`) was not taken (D193); it waits for the next pack change

The B12 item that stood here (`COMPONENT` with window activation for UI-verification steps) is now Phase 1.x
(A28, A29), as an opt-in per scenario.

### Phase 7: vision bolt-on and the vision loop of interactive sessions

Decision B16 (the reserved names), B15 (interactive use with run-time inference, Phase 5's session service), for the
contract's answers **B29** and for the build's decisions **B30**.

**Specified and reviewed 2026-10-10** (`design/phase7-contracts.md`, committed as `2eebeed`, with `design/threat-model.md`
extended): written from seven readers' maps and the four spikes BS9-BS12 (BS9, BS10 and BS11 passed with conditions, BS12
live), reviewed through three lenses with a sceptic each (fit to the code and feasibility; safety, security and the threat
model; coherence, buildability and testability): 94 findings, 91 applied (54 confirmed or restated and 37 added by the
sceptics), 3 refuted; then two rechecks (V1 and V2), whose 36 further findings were all applied. Decisions **D287-D358**
(D341-D358 are the review's), amendments **A61-A66**, plan version 6, bindings file version 2, the script library's version
2, the protocol stays 0.2 (D299). Its thirty-nine questions are **B29**, taken without the owner and provisional while the
build proceeded on them, as it did on B26; the owner confirmed every answer on 2026-10-11 (`B29 ok`, no veto; `DECISIONS.md`). B30 holds the build's
decisions, D359-D430 (numbered at integration), and the decisions of the code review's fix wave, the race fix and the security
fixes, D431-D486 (numbered at the closing documentation pass of 2026-10-11); the owner confirmed B30 the same day (`B30 ok`, no veto). B31 is the security delta review's sign-off, given by the
owner on 2026-10-11.

**Built 2026-10-10**, the day the contract was written, in four packages, each in an isolated worktree
(`.claude/worktrees/p7-<tag>`, branch `p7/<tag>`) made from the contract commit `2eebeed` (VW's over VS's first commit `e5133b6`; a package writes "As built (Phase 7,
<TAG>)" notes under its tag into the sections it builds, writes the marks of `phase7-contracts.md` §16.1 into the earlier
contracts and the overview, regenerates only the artifacts it owns, and reports to the session notes). **VS**: the agent's Swing
side (the three Swing SPIs and `LocatorResolver.describe` first, then `swing.capture` painting the root pane, `swing.hitTest`,
`swing.pointer` routed and through Robot behind the agent option `robot`, `swing.rendering`), the sample's number pad, agent
module and pack 0.6.0, the Swing end-to-end checks and the key-free regeneration of the committed session recordings. **VW**:
`web.hitTest` and `web.pointer` on both paths, the script library's version 2, the CDP path's served set, the web host's counter
and checks. **VC**: plan version 6, `Status.SKIPPED`, the four verbs, the merged capability matrix, image comparison and
bindings file version 2, `ExecutorSession`'s additions, the opt-ins and the credential rule, JUnit and the CLI's `run`, `call` and
`caps`. **VM**: the gateway changes, the session seam and `skillroy-vision`, the MCP and session options, the vision scenario
and the live class. Each implementer committed on its own branch, signed off, and touched no `TODO.md`, `DECISIONS.md`,
`README.md`, `SECURITY.md`, `CLAUDE.md`, `NOTICE` or `CONTRIBUTING.md` (its text for them went into its report); the documents
were brought together afterwards (the numbering of the build's decisions, B30, this section, the Phase 7 sentences of
`SECURITY.md`, the licence audit, `README.md`). Nothing is pushed. The build took 72 decisions, **D359-D430** (`DECISIONS.md`
B30, provisional while the build ran on them, confirmed by the owner 2026-10-11), numbered VC D359-D382, VS D383-D403, VW D404-D419, VM D420-D430; the contract's §17 holds them with their
alternatives and reasons, and every id of the form tag-and-number in the packages' notes, Javadoc and comments was replaced by its
number (a grep for the old form finds none).

**Integration order as it happened** (all on 2026-10-10; each of VS, VW and VM merged `main` into its branch before `main` was
fast-forwarded to it, except VC, which was merged into `main`):

| Package (landing order) | What it built | On `main` | The package's native reactor on its last run before landing |
|---|---|---|---|
| VC (scenario, controller, JUnit, CLI) | plan version 6 (`images`, `Plan.Call.guard`, `inferenceOf`) with every committed plan regenerated (both scenario modules, the branch variant, the session export, the web host); `Status.SKIPPED` (JUnit `<skipped>`, exit 0); the verbs `aiAct`, `aiExpect`, `pointer`, `expectImage`; `CapabilityContributor`, `CapabilityMerge` and the merged matrix with `agentHash` and `host`; `ImageCompare`, `ImageSteps`, `ImageBaselines`, bindings file version 2 with its bind lifecycle (`Approver.reviewImage`); `ExecutorSession.read`, the four-argument `execute`, the robot gates and the credential rule; `DomainPack.screenLegend()`; `--allow-robot` on `run` and `call`, `caps` with `SOURCE`, `skillroy.robot` and its warning; `InferenceContributor` as a contributor; the sample's four scenarios; D359-D382 | `71efa8e` (merge; the package: `8d59900` the early API commit that VM and VS merged, `96c347f`, `80e2f9d`, `06091a4`) | BUILD SUCCESS, 28 projects, 5 min 43 s: scenario 709 tests, inference 996, cli 201, sample surefire 127, 0 failures (the sample's ITs skip natively, 406 of 407) |
| VS (the agent's Swing side, the sample's desk and pack, step 1b and step 2b) | `RegionResolver`, `CaptureMask`, `SurfaceSource`, `LocatorResolver.describe` (`e5133b6`, VS's first commit); the extended `swing.capture` (root-pane painting, scopes, scale, heap bound, redaction by default, marks, carets, focus, surfaces, raster hash, `captureId`, a person's glass capture); `swing.hitTest`; `swing.pointer` routed and via Robot; `swing.rendering`; `swing.type` without a target; the five capability ids; the agent option `robot`; the desk's `NumberPad`, `LendingRegions`, `LendingCaptureMask`, `describe`, module 0.2.0, pack 0.6.0 (`screenLegend()`, gap 49's `inductionRequired`); `VisionSwingChecks`, `RobotChecks`, the host-safety additions; the five session recordings and `exported/session-basic-loan.*` regenerated key-free. **Step 2b** (with VC's scripts, after `71efa8e`): every plan's `packVersion` 0.6.0 and every committed bindings file rehashed, the five capability goldens (exactly the five Swing ids), `keypad-image` approved on both seeds and both Javas, `Phase7Pending` deleted, `RobotChecks`' controller half, `keypad-frame`'s three masks; VM's step 1b (`fecd468`, the session vocabulary's `$reference` sentence, merged into VS's branch as `a236ccd`) reached `main` with it; D383-D403 | `482288d` (fast-forward; `e5133b6`, merges `a236ccd`, `9fcfb52` and `7f1319a`, `8b2bc36`, `12493b4`, `6819a95`, step 2b `190a6d1`, `ec8fb84`, `482288d`) | BUILD SUCCESS: 4,808 tests, 0 failures, 0 errors, 547 skipped |
| VW (the web side) | `WebPointerEvent` and `WebBrowser.dispatchPointer` (A66, the SPI); `web.hitTest` and `web.pointer` on the agent and the CDP path (`protocol.web.WebInput` shared); `web.hittest` and `web.pointer` capabilities; script library version 2 with `hit` and the install-into-an-older-slot rule; JCEF `INPUT`; JxBrowser `INPUT` roles (unverified, wall gap 41); the web module as a `SurfaceSource`; the web host's and the kit's pointer counters, kit items 15-18, `WebHostChecks` item 1 flipped and item 10; `web.browsers.blockedBy` (an additive field of P4 §4.2); gap 36 closed; D404-D419 | `e7a4cb3` (fast-forward; `ddcb1f6`, merge `da1b667` of `main` at `482288d`, `e7a4cb3`) | BUILD SUCCESS, 5 min 40 s: 4,865 tests, 0 failures, 0 errors, 549 skipped |
| VM (the gateway, the session seam, `skillroy-vision`, the hosts' options, the vision scenario) | `ProviderTool`, `ModelRequest.providerTools`, `toolsetName`, image parts in a tool result, `CacheHint.AUTO`, the Haiku 5.5 price row, the `vision` purpose and `effort.vision`, image tokens in the estimate; the `SessionTools` seam with `SessionActions` as the facade, `SessionExtensions`, `visionCall`, the service-enforced limits; the module `skillroy-vision` (tiers V1-V3, `Marks`, `Captures`, `Grounding`, `VisualQuestion`, the two tool adapters, the recorder, the scenario player, `VisionCapabilities`); `session --vision/--vision-model/--vision-tools/--allow-robot`, `session --play [--export]`, `mcp --vision/--allow-robot`, the captures resources; `CallPolicy.arguments` and `PlanPolicy.refusals` for the new routes; the committed `exported/session-vision-loan.*` (arm64 image, Java 25 controller and target; held byte for byte with a Java 8 target); `VisionSessionScript`, `VisionLiveIT` (written, never run); D420-D430 | `7af44eb` (fast-forward; the package commits `fecd468` ... `b374317`, merges of `main` at `71efa8e`, `482288d` and `e7a4cb3`) | BUILD SUCCESS: surefire 4,449 run, 0 failed, 118 skipped; failsafe 502 run, 0 failed, 448 skipped |
| VM, closing commit (D425) | `ExecutorSession.fixture(slot, value, credential, origin)` (VC's file, by permission), the origin rule `FIXTURE_CREDENTIAL_REFUSED`, the `fixture` journal record and its export; the player's `FixtureBinder.SESSION`; the player half of §13.2 item 8 (`vision-live-only.yaml` and `loan-table-visual-check.yaml` played with a scripted model); a focus flake of item 8 fixed (below) | `e11ae08` | BUILD SUCCESS: surefire 4,454 run, 0 failed, 118 skipped; failsafe 512 run, 0 failed, 458 skipped |

The counts are those of each package's own run after it had merged `main` (macOS arm64, JDK 26, headless), read from its report;
VC's reactor was a smaller one (28 projects), so the rows do not add up. The skips are the display and live tests.

**Image runs** of the packages (arm64 build of the image `skillroy-test-java-ci:bs8-arm64`, Temurin 25.0.4.1 and the aarch64
Temurin 8u504; `ci/run-in-linux.sh`, `-Dskillroy.headless=false`, `-Dskillroy.jcef.platform=linux-arm64` where the web host is
involved; counts read from the reports):

- **VC** (its own tree, before step 2b): `LendingEndToEndIT`, Java 25 target 371 run, 0 failures, 25 skipped; Java 8
  target (`-Dskillroy.it.targetJava=/opt/jdk8/bin/java`) 371 / 0 / 24; items 7 (the seed-A part) and 9 pass in all five load modes;
  `InferenceEndToEndIT`, `BranchEndToEndIT` and `TelemetryEndToEndIT` on Java 25 114 / 0 / 68.
- **VS** (the Swing modules, the desk and `HostSafetyIT`, Java 25 test JVM): agent-swing 210 run, 0 failures, 2 skipped,
  `LendingUiDisplayTest` 11 of 11, `HostSafetyIT` 5 of 5. After step 2b, on `482288d`, the whole `LendingEndToEndIT` with no update
  flag: Java 25 target BUILD SUCCESS, the four load modes 39 tests each (0 failed, 3 skipped: the two vision scenarios, which need
  `inference.vision`, and scenario E, which the hooks leg runs), check 1c included, the ten resolution classes 19 of 19 each (item 7,
  `keypad-image`, included), `PerTargetJava` 8 (1 skipped), the hooks leg 43 (2 skipped) and its two resolution classes 19 of 19, the
  Robot leg 6 of 6 (`RobotChecks` with the controller half); the Java 8 target the same counts, `PerTargetJava` 8 of 8, nothing
  skipped there. The Robot leg ran on both Javas (both capability ids `OK("robot")`, a glass capture equal to the root-pane paint,
  `glassVsPaintDiffering=0`).
- **VW** (the `jcef` profile): kit off screen 19 of 19, windowed 19 (1 skipped, the pre-existing item 10 case), items 15-18 pass against
  real JCEF in both modes; `WebHostEndToEndIT` (all eight launches, Java 25 targets) after VS's merge 80 run, 0 failures, 7 skipped,
  the Swing half of item 10 run in all eight; Java 8 targets 80 / 0 / 43 (the windowed launches skip on Java 8 by design, the four
  off-screen launches pass item 10); the agent-side modules on a Java 8 test JVM (protocol 499, agent-api 141, agent-web 235) green.
- **VM** (on `50d83bb`): `LendingEndToEndIT` Java 25 target 403 run, 0 failed, 15 skipped; Java 8 target 403 / 0 / 14;
  `InferenceEndToEndIT` 120 / 0 / 77 on both; item 8 passes on seed A (the committed export held byte for byte, the journal read
  back, the replay with zero model calls); item 7's skip line reads `capability inference.vision is UNAVAILABLE (replay)` (10 runs)
  and item 9's `contributed` is `[inference.gateway, inference.sql, inference.vision]` (352 records), both in every load mode. On
  `e11ae08`: the whole `InferenceEndToEndIT` Java 25 130 run, 0 failed, 86 skipped; Java 8 130 / 0 / 86; item 8's two checks 2 of 2
  on each Java. `LendingEndToEndIT` was not rerun after the closing commit (the other paths of `ExecutorSession` did not change).

**What the build found** (also in "Environment notes"):

- **A focus flake of item 8** (found in one full `InferenceEndToEndIT` run on Java 25, fixed in `e11ae08`): the model's `type` was
  refused with "nothing has the focus" because a WM-less display gives no window the focus and the desk's entry field held it only
  by timing; the failed session left the desk signed on, which failed the MCP and CLI checks after it and, through the missing
  outputs, the secret scan. `VisionSessionScript` (and `VisionLiveIT`'s sessions) now bring the desk to the front before the model's
  turn, outside the session (not journaled, so the committed export is unchanged), and the vision session recovers the desk when
  it closes.
- **`keypad-frame` and the glue** (D403): the baselines are approved on a fresh desk (`ResolutionChecks`, per seed) but the JUnit glue
  replays the scenario after the earlier ones, where the totals line shows the last loan and the printer's scroll pane holds slips
  (11,724 differing pixels); `keypad-frame` masks the entry field, the totals line and the printer's scroll pane, and was approved
  afresh.
- **The WM-less display after a modal dialog** (BS9 K7, observed again): no window of the JVM holds the focus
  (`focusedAfterPrompt=null` on Java 25 and in the last Java 8 run); on Java 8 the frame kept a focused window in an earlier run, so
  the Robot check's `requireFocus` refusal is conditional on the observed focus.
- **Render keys** on the arm64 image: Java 25 `d0bd5ce68e2f111c`, Java 8 `6ccffcf8f0c8db46`, the same in every load mode of a Java;
  the committed `keypad-image` baselines are per seed and per key, and the Java 25 `keypad` PNG is byte-identical on both seeds. The
  amd64 comparison of §13.2 item 5 belongs to the matrix. An image entry's `rendering` keeps the reviewer-only `os.version`, which
  inside the container is the host kernel's release (an OrbStack build string): it is not part of the key (D350) but it is committed
  text, for the code review's lens on evidence at rest.
- **The hidden JCEF browser** (kit item 17, gap 86): a pointer click at the counting button of a hidden browser is answered (off screen
  every event within 25 ms; windowed on Java 25 the first `mouseMoved` after about 5,010 ms, the press and release at once) and the
  page records no event.
- **Smaller facts**: the routed wheel check on the slip panel needs six loans first so that the slips overflow the view
  (`slipWheel=-428->-203`, text 975 px in a 483 px view, Java 25); VC's first full reactor attempt failed two sample tests (a plan
  stale after `sign-on-by-number-pad` gained its `signOff` step, and a verb set in `ScenarioSchemaCommittedTest`), fixed before the
  merge; the web host's `PlansUpToDateTest` pin (plan version 5 to 6) was edited by VC (D378).

**What was not run** (at the packages' reports, on 2026-10-10, before the code review; since then the fix wave and the security
fixes ran the whole `LendingEndToEndIT` classes they touch, the `jcef` profile end to end and `VisionLiveIT` three times, and the
matrix of the final tree is green on `c94aade` (arm64 twice on every leg, amd64 on the Java 25 leg), its amd64 Java 8 legs on `bc1e250` later on 2026-10-11): the amd64 image leg of
anything in Phase 7 (the render key's arm64 and amd64 equality of §13.2 item 5, the committed `keypad-image` keys on amd64, item 8
on amd64); the whole `LendingEndToEndIT` after `e11ae08` and after VM's merges on the final head; the `jcef` profile end to end on the
final head (VW ran it on `e7a4cb3`, VC ran the web host's plan test in the profile); anything on macOS with the Accessibility
permission, Windows, Wayland, a locked session or at a scale other than 1 (`phase7-contracts.md` §13.4, `SECURITY.md`); JxBrowser
input against the real library; `VisionLiveIT` (then not run: it needs the owner's key); the MCP server's export of an anchored
step (gap 89); any live model call (none had been made or needed).

#### Phase 7 code review (2026-10-10)

The method of Phases 2 to 6: three lenses on the committed tree (`7af44eb`, read from a frozen export; the build's documents
came after it), each run by one reviewer and each verified by a sceptic who
tried to refute every finding with probes in a copy of the tree and added what the lens had missed. **Contract fit** (L1):
7 findings (0 high, 1 medium, 6 low); the sceptic raised F2 to medium, refuted F7 and added S1 (low): 2 medium, 5 low stand.
**Host safety, secrets, pixels and bounds** (L2): 8 findings (1 high, 3 medium, 4 low), all confirmed (four restated); the
sceptic added S1 (high), S3 (medium) and S2 (low): 2 high, 4 medium, 5 low. **Tests, determinism and the matrix** (L3): 10
findings (3 medium, 7 low), all confirmed; the sceptic added S1 (medium, already fixed on `main` at `e11ae08`), S2 and S3 (low):
4 medium, 9 low. In all **25 findings from the reviewers and 7 added by the sceptics, 1 refuted (L1-F7), one pair merged
(L1-F1 = L2-F3): 30 distinct findings, 2 high, 9 medium, 19 low** (the reports are in the session notes,
`2026-10-10/phase7/review/`). Found while fixing: **FB-N1** (high by the review brief's rule: a contract guarantee not implemented)
and FB-N2 (low); found by the first live run: **LV-1**. The most important:

- **A capture whose paint root lay inside a masked component was not blanked** (L2-F1, high, restated by the sceptic): a
  `COMPONENT` or `REGION` capture of a child of a masked panel returned the pixels as rendered, and so did a mask that gives
  `regions` only or names a window; a model reached it through the raw primitive branch. Now the ancestors up to the window
  are walked and blanked (D431).
- **A person's glass capture blanked the masks of the target window only** (L2-S1, high, found by the sceptic), though it keeps
  every window of the JVM that meets the captured rectangle. Now every such window's masks apply (D432). Its heap bound and
  out-of-memory catch were missing (L2-S3, medium; D437).
- **The controller's Robot and glass gates compared `ROBOT` by exact case** while the agent upper-cases the trimmed value
  (L1-F1 = L2-F3, medium), at seven controller sites; so a raw `call swing.pointer {via: robot}` passed without `--allow-robot`.
  One predicate now (D440). The fixer found that `CallPolicy` stripped only Unicode white space, so a model's raw `via` of
  `"\u0001ROBOT"` passed every model and client route and reached Robot where the agent option was set (FB-N1), and that a
  `$slot` reference resolving to `robot` passed every gate as written (FB-N2; D441).
- **The delivery probe moved the real pointer before any validity check** (L2-F4, medium): the contract orders the checks
  first. One read now serves the probe and the press, and the probe is skipped under a modal dialog (D433).
- **The player evaluated no `verify`** (L1-F2, medium) and **the recorder acted on a masked browser page** because the `WEB`
  branch ran before the masked test (L2-F2, medium): fixed (D443; FC).
- **The "one shared raster-hash vector" was two different vectors** (L3-F1, medium) and **the Robot leg's `requireFocus` refusal
  ran only when the frame happened to lose the focus** (L3-F2, medium), **no test made the focus owners differ** (L3-F3,
  medium): fixed in the tests (L3-F1 and L3-F2 by FA, D438 and D439; L3-F3 by FB).
- Among the lows: a Robot button marked down after the press returned, the pointer not restored after a passing probe, a failed
  post midway through a routed sequence leaving no balance, a refused probe post leaving stale capability reasons, a drag's
  end point not checked, an anchored drag recorded at its start point, the anchor's window insets always zero, the role read
  from the child under a label, `wait` settling for a fixed 5 s, the dropped `aiExpect` steps missing from a played export, a
  player test accepting either verdict, two stale guards, the missing secret scan over the committed files, baseline entries
  recording the desk's transient focus.
- **LV-1** (the first live run of `VisionLiveIT`, run 1 below): when the person resolved a slot before the turn, the session
  never told the model which `$references` were bound, so the model of each of the three live items (V1 on Sonnet 5.5, the
  toolset's V2, V1 on Haiku 5.5) read `$item` as an unfilled placeholder and asked; the turn's first message now lists the bound slots by name, never a value or a secret (D444).

The fix wave: three fixers in worktrees from `e96a20b`, each with a report, the tests that fail on the
old code and the contract text first (marked "Fix wave (Phase 7 review, <TAG>, 2026-10-10)"); decisions D431-D448 (B30):

| Fixer | What it fixed | Fixer's commit | On `main` |
|---|---|---|---|
| FA the agent's Swing side and its tests (D431-D439) | all 15: L2-F1, L2-S1 (high), L2-S3, L2-F4, L3-F1, L3-F2 (medium), L2-F5, F6, F7, F8, L2-S2, L3-F4, F5, F7, S3 (low). 15 new or tightened tests failed on the old code. Native 4982 run, 0 failures, 585 skipped; `agent-swing` 225 run, 0 failures on the Java 25 and the Java 8 test JVM; the end-to-end items green on both target Javas. Also: `HostSafetyIT`'s full-home Robot case had run on Java 25 targets only and failed one of its five cases in four runs out of four on a Java 8 target (a `-verbose:class` fragment between a value and its newline); `ChildJvm.awaitLine` drops it, 5 of 5 on both | `9f44718` | fast-forward to `9f44718` |
| FC the vision module and the session (D443-D448) | L1-F2 (the player evaluates `verify`), L2-F2 (masked pages refused before the web branch), LV-1 (the bound slots listed), L1-F3 (anchored drag end point), L1-F4 (anchor insets), L1-F5 (`targetNode` on the hit-test's target form), L1-F6 (`wait` settles for the step timeout), L1-S1 (the played export lists the dropped `aiExpect`), L3-F10 (the player test asserts `DEGRADED`): nine items. The five Phase 5 session recordings were regenerated key-free (their fingerprints changed with LV-1; the exports are byte-identical). Native reactor green; `InferenceEndToEndIT` 130 run, 0 failures on both Javas | `73187c0` | `791e0ae` |
| FB the scenario, controller and CLI side (D440-D442) | L1-F1 = L2-F3 (one Robot predicate), L3-F3 (the focus rule tested), L3-F6, L3-F8, L3-F9 (seed B's `keypad-frame` baselines re-approved with the desk activated), L3-S2: six items, plus FB-N1 and FB-N2. Native 4977 run, 0 failures, 576 skipped; image lending checks 77 run, 0 failures on both Javas; web host item 10 green | `c700772` (three commits: `a897e17`, `c3a65f1`, `c700772`) | `c690bef` |

Not fixed: L1-F7 (refuted) and L3-S1 (already fixed at `e11ae08`). **The race fix (FX).** The first arm64 round of the Phase 7
matrix on `c690bef` failed one test in the Java 25 whole-reactor leg, `LendingEndToEndIT`'s scenario E check ("one matrix for
every run of this target", two hashes), the Phase 6 one-off flake again; the other two legs were green. FX reproduced it under
load (22 of 30 hooks targets) and found a race in Phase 6's hook engine, not a Phase 7 cause; fixed agent-side by
`HookEngineImpl.declare` setting each `hook.<id>` to its probe's first answer (D449), merged as `a134159`; native 5005 run, 0
failures, image `LendingEndToEndIT` 404 run, 0 failures on Java 25 and the hooks legs 81 run, 0 failures on Java 8 (the entry
under "Open flake" is closed).

#### Phase 7, the live items (2026-10-10/11)

`VisionLiveIT` (profile `inference-live`, P7 §13.3) ran **three times** with the owner's key, in the arm64 image, on the Java 25
desk, from a detached worktree, the key passed by name through the login shell; the rows are in the live table at the top. Run 1
on `e11ae08` (USD 0.131) found LV-1: items 1, 2 and 4 ended `QUESTION`, item 6 was skipped, items 3 and 5 and the secret scan
passed (item 3 answers the contract's question: the fallback header coexists with the toolset). Run 2 on `791e0ae` (FA and FC
merged; USD 0.155): **7 of 7**, the three models each `DONE` in 3 UI actions, the player `PASS` with `verify` on both
statements, and the export of item 1 replayed with zero model calls (exit criterion 1 met live). Run 3 on `c94aade`, the final
code (USD 0.148): **7 of 7** again (V1 Sonnet 5.5 USD 0.045, V2 toolset USD 0.057, Haiku 5.5 USD 0.0029, each in 3 UI actions;
the secret scan over 26 files). The day's live spend, planner check and BS12 included, is **USD 8.12** (table note above). Not
verified live: the fold under the provider's history rule beyond one turn (gap 85), a longer session than BS12's longest (14
actions), the 5.5 models' history rule under the automatic cache hint, a disabled toolset member, V3 (Robot) against a live model.

#### Phase 7 final matrix (2026-10-11, on `c94aade`)

Tests / failures / errors / skipped per leg, read from the reports (`summarize.py`), on the tree `c94aade` (the
later commits change documentation and comments only):

| Round | Java 25 targets (whole reactor, `-Pjcef`, `clean verify`) | Java 8 targets (`-Pjcef`) | Java 8 test JVM (the agent side) |
|---|---|---|---|
| arm64 r2 | 5,360 / 0 / 0 / 141 (994 s) | 5,360 / 0 / 0 / 176 (935 s) | 1,849 / 0 / 0 / 30 (264 s) |
| arm64 r3 | 5,360 / 0 / 0 / 140 (986 s) | 5,360 / 0 / 0 / 175 (942 s) | 1,849 / 0 / 0 / 30 (280 s) |
| amd64 r1 (emulated, `FORK` launch) | 5,360 / 0 / 0 / 140 (1,532 s) | not run | not run |
| amd64, Java 8 legs, first run (on `dcc5050`, documentation only past `c94aade`) | – | stopped in the agent modules: 1 failure, 1 error, 4,321 run (706 s; `-fae` skipped every module downstream of them) | 1,849 / 0 / 0 / 30 (429 s) |
| amd64, Java 8 legs, second run (on `bc1e250`: `aadd5f4`, `d6720ea`, gaps 94 and 99) | – | 5,362 / 0 / 0 / 176 (1,494 s) | 1,849 / 0 / 0 / 30 (423 s) |
| arm64, the follow-ups' final code (on `117da59`: the two test fixes and gaps 94, 99, 92 and 63) | 5,362 / 0 / 0 / 141 (1,005 s) | 5,362 / 0 / 0 / 176 (937 s) | 1,849 / 0 / 0 / 30 (264 s) |

Every leg that ran is green. The `jcef` profile ran in every leg; the Robot leg (`JAVAAGENT_ROBOT`) ran inside
`LendingEndToEndIT` on both target Javas of both arm64 rounds; scenario E's matrix-hash failure of the first round (arm64 r1
on `c690bef`) did not recur (fixed by FX, D449). The amd64 Java 25 leg settles §13.2 item 8 on amd64 and `keypad-image`'s
committed Java 25 render key there (the key is the same across the two architectures for Java 25). **The amd64 Java 8 legs**
could not run on `c94aade`: the x86-64 JDK 8 used for them since Phase 5 lives on an external volume that was not mounted at
the time (docker refused the mount: exit 126 after 1 s). The owner remounted it later on 2026-10-11 and the legs ran in the next
session. Their first run (on `dcc5050`) failed two tests that had passed on every earlier leg, both test-side, and its Java 8
targets leg skipped everything downstream of the two agent modules: `ProductionGateTest.requireProductionMarkerRefusesWhenNoneIsRegistered`
(`NoSuchFileException` on the token file: the gate's refusal closes the runtime asynchronously and close retracts the token, so
`AgentHarness`'s read raced it; `aadd5f4` reads a null token when the file is gone and the runtime is closing) and
`RobotPathDisplayTest.requireFocusRefusesAJvmWithoutAFocusedWindow` (30 s waiting for the JVM to hold no focused window after the
thief frame was disposed: the test frame was focused again, most likely a late activation of its own under emulation, A39's first
way; `d6720ea` repeats the thief cycle up to three times). The second run, on `bc1e250` (those two fixes and gaps 94 and 99), is
green on both legs; the Java 8 targets leg counts the two tests the gaps added. **`keypad-image`'s committed Java 8 key holds on
amd64:** in that leg every load mode on both seeds (ten replays) held `keypad` and `keypad-frame` against `6ccffcf8f0c8db46`, the
Java 8 key (Temurin 1.8.0_504) recorded on arm64, so no `approve` ran and the bindings are unchanged; the render key is the same
across the two architectures for both Java lines (§13.2 item 5).

**Status of the checklist.** The code review and the fix wave, the race fix, the security delta review and its four fix packages
are done and ticked below; the live acceptance is done (three runs). The matrix line and the Exit item are ticked since the amd64 Java 8 legs ran green
(2026-10-11, on `bc1e250`; `keypad-image`'s Java 8 key held on amd64). Not ticked: the two follow-up lines (gaps 93 and 96, the
owner's optional choices; gap 95; gap 94 is done). The owner's B29, B30 and B31 are given (2026-10-11).

- [x] BS9-BS12 passed on 2026-10-10 (BS9, BS10 and BS11 with conditions, BS12 outright; the conditions are inputs of the
  Phase 7 contract, "Base spikes" below)
- [x] The contract specified, reviewed and rechecked 2026-10-10 (`2eebeed`; D287-D358; A61-A66; gaps 77-87 opened below)
- [x] B29 (thirty-nine answers) and B30 (128 decisions, D359-D486: the build's 72 and the fixers' 56), provisional while the
  build ran on them, **confirmed by the owner on 2026-10-11** (`B29 ok`, `B30 ok`, no veto); Q25 (the sample's number pad), Q27
  (JxBrowser input, a licence holder's run; still unverified) and Q34 (how V3 sessions on a person's desktop will be run), which
  rested on the owner's taste or knowledge, are confirmed with the rest
- [x] **VC** (P7 §8, §9.2, §9.3 run/call/caps/junit, §12.3 `screenLegend`; §13.1 items 5 and 6; §13.2 items 7 and 9): built, merged as
  `71efa8e`; plan version 6 regenerated in both scenario modules, the branch variant, the session export and the web host; bindings
  `planHash` rehashed; `InferenceContributor` contributes `inference.gateway` and `inference.sql`
- [x] **VM, step 1b**: the vocabulary sentence in `SessionService` and its pin (`fecd468`), on the head before VS regenerated any
  recording
- [x] **VS** (P7 §3, §12.1-§12.3; §13.1 items 1, 2 (Swing part) and 9; §13.2 items 1-6 and 11): built, merged as `482288d` with
  step 2b; `swing.capture` paints the root pane and reports its origin (gap 76 closed, A61); `swing.hitTest`; `swing.pointer` routed
  and through Robot (the double opt-in, a press always paired with its release); `swing.rendering`; one rule for pixels and text
  (`CaptureMask`); the number pad, `LendingRegions`, `LendingCaptureMask`, module 0.2.0 and pack 0.6.0 (gap 49 closed); the
  Swing end-to-end checks and the Robot leg; the session recordings regenerated once
- [x] **Step 2b**: every plan's `packVersion`, the five capability goldens and `keypad-image`'s bindings file and baselines (both
  Javas, both seeds) regenerated and committed; `Phase7Pending` dropped; `RobotChecks`' controller half
- [x] **VW** (P7 §4; §13.1 items 2 (web part) and 3; §13.2 item 10): built, merged as `e7a4cb3`; `web.hitTest` and `web.pointer` on
  the agent path and the CDP path, `INPUT` for JCEF and described for JxBrowser (unverified), the script library's version 2, the
  host's pointer counter and the kit's items 15-18, `WebHostChecks` item 1 flipped and item 10 including its Swing half (gap 36
  closed; gap 86 recorded)
- [x] **VM** (P7 §6, §7, §9.1, §9.3 session/mcp, §9.4-§9.6, §10; §13.1 items 7 and 8; §13.2 item 8; §13.3 written): built, merged as
  `7af44eb` and `e11ae08`; the gateway's additive changes, the `SessionTools` seam and the facade, `skillroy-vision`, `session` and
  `mcp` options, the credential rule's plan form in `PlanPolicy`, the committed `exported/session-vision-loan.*`, `VisionLiveIT`
- [x] D425 closed: `ExecutorSession.fixture(slot, value, credential, origin)` and the player half of §13.2 item 8 (both vision
  scenarios played with a scripted model; green on both Javas in the arm64 image)
- [x] `LoadModeChecks` items 7 and 9 tightened by themselves once `VisionCapabilities` was on the class path: `UNAVAILABLE (replay)`
  and the third contributed id `inference.vision`, in every load mode
- [x] Integration by the orchestrator (the documentation pass of 2026-10-10): the build's decisions numbered D359-D430 (B30), every
  tag-and-number id resolved to its number, the packages' marks of §16.1 and "As built" notes merged, `SECURITY.md`'s Phase 7 sentences
  (§11 (a)-(h)) and the not-verified items of §13.4, the licence audit's module counts, `README.md` and this file brought to
  as-built
- [x] `ImageCompareTest` asserts the shared raster vector of `CaptureTest` (`sha256:6be118c3…c4ba`, 3 x 2 opaque pixels): the two
  tests hold the same six pixels and the same hash literal, and `VisionSwingChecks` item 1 asserts the agent's `hash` equal to
  `ImageCompare.rasterHash` of the decoded desk raster (fix wave, FA, L3-F1, D438)
- [x] The Linux matrix of the final tree (`c94aade`: arm64 twice, amd64 once, the `jcef` profile once, the Robot leg once per
  Java; the first arm64 round, on `c690bef` before the race fix, failed one test in its Java 25 leg, scenario E's matrix hash,
  fixed by FX), recorded under "Phase 7 final matrix" above: green on `c94aade` (arm64 twice on every leg, amd64 on the Java 25 leg), and **the amd64 Java 8 legs green on `bc1e250`
  (2026-10-11, the second run; the first, on `dcc5050`, failed two tests, both test-side, fixed by `aadd5f4` and `d6720ea`)**; among it the amd64 leg of item 8 (VM ran arm64
  only), the render key's arm64/amd64 equality (§13.2 item 5) and `keypad-image`'s committed keys on amd64 (a key that differs
  there is a finding, settled by an `approve` run on that leg); the Robot leg's `focusedAfterPrompt` line is to be read with
  the refusal test's status (skipped when the frame kept the focus)
- [x] The Phase 7 code review (three lenses with a sceptic each) and its fixes: done 2026-10-10 ("Phase 7 code review" above:
  30 distinct findings, 2 high, 9 medium, 19 low, plus LV-1, FB-N1 and FB-N2; the fix wave FA `9f44718`, FC `791e0ae`, FB
  `c690bef`; D431-D448); the committed `os.version` text of the baselines is gap 91, the reading of `MODAL_OPENED` on a release
  gap 88
- [x] The hook engine's race (scenario E's matrix hash) explained and fixed: FX `a134159`, D449 (the open flake of Phase 6 is
  closed)
- [x] The live acceptance of `phase7-contracts.md` §13.3 (`VisionLiveIT`, profile `inference-live`, the owner's key, at most
  USD 1.00 per session and USD 3.00 for the class; `ci/run-in-linux.sh mvn -o verify -pl
  samples/skillroy-sample-lending-scenarios -am -Pinference-live -Dskillroy.headless=false -Dit.test=VisionLiveIT
  -Dfailsafe.failIfNoSpecifiedTests=false`): **three runs, 2026-10-10 and 2026-10-11, USD 0.434 in all** ("Phase 7, the live
  items" above; the live table at the top). Run 1 on `e11ae08` (USD 0.131): item 3 the fallback header coexists with the toolset
  (`END_TURN`); item 5 `skillroy_look` answered and the player passed `loan-table-visual-check` (`YES`) and its false variant
  (`expect: NO`); the secret scan passed (19 files); items 1, 2 and 4 ended `QUESTION` (finding LV-1: the model read the person's
  resolved `$item` as an unfilled placeholder); item 6 skipped. Run 2 on `791e0ae` (USD 0.155): 7 of 7. Run 3 on `c94aade`, the
  final code (USD 0.148): 7 of 7, items 1, 2 and 4 each in 3 UI actions (USD 0.045, 0.057, 0.0029), item 6 replaying with zero
  model calls, the secret scan over 26 files
- [x] The security delta review (`phase7-contracts.md` §15: eight lenses with sceptics over the diff since `af29e59`, S101
  onwards; every finding of medium or above fixed, nothing of medium or above accepted; the record in the session notes and
  its summary here): **run and fixed 2026-10-10/11** ("Security review (Phase 7 delta, 2026-10-10/11)" under "Status at a
  glance": S101-S148, 2 high, 13 medium, 33 low; every medium and high and 31 lows fixed by SG `5d6fdd8`, SH `edba708`, SJ
  `73e406a`, SI `056a007` and SG's follow-up `c94aade`; D450-D486; S142 and S144 accepted by the owner, B31, 2026-10-11)
- [x] **B31, the owner's sign-off of the security delta review: given on 2026-10-11** (`B31 ok`; S142 and S144 accepted as
  proposed, their two release notes in `SECURITY.md` "Known limitations")
- [x] The security fix round's packages: SG (S104, S105, S106, S121-S126, S129, S108 and S146 agent halves), SH (S101, S102,
  S107, S113, S127, S128, S139), SI (S109-S112, S114, S130-S138, S108, S124 and S143 controller halves, SG's recorder item), SJ
  (S103, S115-S120, S140, S141, S143, S145-S148); contract first, each marked "(Phase 7 security fix, S<nnn>, built 2026-10-11,
  <TAG>)"
- [x] The closing documentation pass (2026-10-11): D431-D486 numbered (B30 extended), B31 drafted, the record's statuses set,
  `SECURITY.md`, `threat-model.md`, `README.md`, `docs/release-checklist.md` and this file brought to as-built
- [x] (owner, optional) Replay refusing `NOT_APPROVED` for a recorded `REQUIRED` approval whatever the strategy (gap 93); a
  modal check in `web.dom.click` and `web.dom.fill` for a model or a client (gap 96): **answered 2026-10-11, neither is
  refused** (B33; both gaps closed, the behaviour stays as built)
- [x] `Recorder.namesSecret` on the public tokenizer and `SessionService.REFERENCE_SENTENCE` naming the whole-slot rule (gap 94):
  **done 2026-10-11** (D487, D488; the five Phase 5 session recordings regenerated key-free with `SessionExportTest`)
- [ ] A web-host check with a mask on the front browser's panel (gap 95)
- [x] **Exit** (`phase7-contracts.md` §14, the roadmap's row 7; **met 2026-10-11** with the amd64 Java 8 legs), item by item (each "can be met" in CI by the checks that ran in
  the packages' image legs and in the fixers' reruns; the matrix of the final tree, green on `c94aade` on every leg that ran (arm64 twice on every leg, amd64 on the Java 25 leg), ticks them; the security sign-off, the one item that waited for the owner, was given on 2026-10-11 and is **met 2026-10-11**. The item as a whole stays unticked: §14 reads the `expectImage` criterion as holding "on every leg" (§13.2 item 7: both Javas and the amd64 leg), and the amd64 hold of `keypad-image`'s Java 8 key is open until the amd64 Java 8 legs of the matrix line above have run. It is ticked with that line, as the Phase 6 Exit item stayed unticked while its Windows criterion waited):
  - [x] a vision-driven session on the sample is recorded into ordinary steps that replay with zero model calls
    (§13.2 item 8 with the scripted gateway on both Javas and the committed `exported/session-vision-loan.*`, green in the arm64
    image on both; once live, §13.3 items 1 and 6)
  - [x] a plan compiled with `inference: run` is reported as skipped in `replay` (`vision-live-only.yaml`: `Status.SKIPPED`,
    JUnit `<skipped>`, exit 0, no agent call; §13.2 item 7; green in the image)
  - [x] a guarded visual expectation yields `DEGRADED` in `replay` (`loan-table-visual-check.yaml`: `inference.vision`
    `UNAVAILABLE (replay)`, the check skipped; §13.2 item 7; green in the image)
  - [x] V3 runs only with its explicit opt-in (§13.2 item 6; §13.1 items 7 and 8: never a per-call choice of a model or a
    client, never a glass capture for a model); met on Linux in the packages' runs, the person's-desktop half is specified and not
    verified
  - [x] *(added)* the pixels a model sees are redacted `PAINT` captures of the target's own windows, no credential reaches
    a model, and every vision call is in the ledger (§13.2 items 1 and 8; §13.1 items 5, 7 and 8)
  - [x] *(added)* `web.hitTest` and `web.pointer` on both paths against real JCEF (§13.2 item 10, profile `jcef`; green in the image
    on both Javas, the Swing half included)
  - [x] *(added)* an `expectImage` baseline holds on the target it was made on and is `REBIND_NEEDED` elsewhere (§13.2
    item 7, `keypad-image`, held on both Javas and seeds on arm64; §13.1 item 5; on amd64 the Java 25 key held in the final
    matrix, and the Java 8 key in the amd64 Java 8 targets leg on `bc1e250`, 2026-10-11, ten replays held)
  - [x] *(added)* the security delta review is signed off (B31: run and fixed 2026-10-10/11, signed off by the owner on
    2026-10-11)

### Not yet assigned to a phase

- [ ] `skillroy-trace-viewer` (static HTML over the run bundles)
- [ ] Second `Transport` implementation (newline-delimited JSON-RPC over TCP or a Unix socket, agent dials out)
- [x] Licence audit of all dependencies: `docs/licence-audit.md` (2026-10-02). Its packaging gaps are Phase 1.x;
  the planned pieces it lists in §8 are audited when they are adopted
- [x] Confirm control of the domain behind the `ai.skillroy` groupId (B2): **confirmed by the owner 2026-10-11** (B33)
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
31); "P4 §n" is a section of that contract. Gaps 50-57 were numbered by the Phase 6 contract (§15.2, "P6 §n"), 58-59 by
its build and 60-75 by the Phase 6 code review's fix wave and the security pass 2 fix rounds (their sources are named in
the rows); gap 76 by the Phase 7 spikes (2026-10-10), gaps 77-87 by the Phase 7 contract (2026-10-10, P7 §16.2), gaps 88-90 by its build and gaps 91-102 by its code review's fix wave, the race fix and the security fix round (2026-10-11, P7 §16.2); the Phase 6 contract's §15.2 also says what Phase 6 did for gaps 31, 32, 33 and 49.

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
| 31 | `ROBOT` type mode, `OPTION_VALUE` dismissal, `swing.events` | In the design's catalogue, not in the Phase 1 contract | Add them to the contract when their phase starts | later (nothing of Phase 6 needed them; moved from 6, P6 §15.2; Phase 7 did not take them either: Robot keys stay out, gap 77, `swing.events` is not needed, P7 §16.2) |
| 32 | Hook catalogue: an `enabled` flag per hook | A pack catalogues a target and leaves it off | In the design (§2.6); build with the hooks module | closed (built 2026-10-09, Phase 6, CP: P6 §5.1; the sample's `sign.on.checked` is off) |
| 33 | Third-party isolation when the host ships an older copy of the same library | The agent loader is child-first only for the agent's own package prefix | Relocate under that prefix; covered by the added BS3 case | closed (built 2026-10-09, Phase 6, HK: Byte Buddy relocated; `ShadedContentIT`, `HomeConstantPoolIT`) |
| 34 | A `PAINT` capture must guard embedded-browser components | BS8: `printAll` throws for an off-screen JCEF canvas and paints a windowed one blank, so a Swing capture of a window with such a browser would fail | The guard: the capture paints part by part, a part that throws becomes a placeholder listed in `skipped` (P1 §6) | closed (built, Phase 4) |
| 35 | A browser with no view and no seed is invisible | JxBrowser has no process-wide list of browsers: an engine whose browser is in no window is found only through a seed (browser-spi §F23) | The pack's `BrowserRegistryAdapter` hands it in (P4 §9.2 is the example); nothing more in the base | later, if a target needs it |
| 36 | `web.dom.click` is a DOM click, not a pointer click | `el.click()` fires no `pointerdown`, `mousedown` or `mouseup`; a page that listens for those does not react | `INPUT` (reserved) and `web.pointer` with the vision bolt-on | closed (built 2026-10-10, Phase 7, VW: `web.pointer` and `INPUT`, P7 §4 and §16.2; JCEF verified in the image on both Javas, JxBrowser's roles unverified, gap 41) |
| 37 | `web.resize` cannot change a windowed browser's Swing bounds | It sets a protocol-level viewport override; a page that measures its component sees the override only | `applied: COMPONENT` is reserved for a variant that resizes the component | later |
| 38 | `web.waitFlag` answers an expression that throws differently on the two paths | The agent polled on and answered `ready: false` with `lastError` (the base verb then ends `SYNC_TIMEOUT`); the CDP path failed at once with `SCRIPT_ERROR` (`ASSERTION`) | A35 (4.x, built 2026-10-08; P4 §16.1): one page-side guard (`WebLib.flagExpression`) on both paths; a run-time throw is "not ready" and polling goes on, `lastError` when the wait ends not ready, a compile error `SCRIPT_ERROR` at once | closed (built); the `jcef` negatives `flag-never-ready` and `flag-does-not-compile` run in the next matrix |
| 39 | A hidden JCEF browser has no bitmap | Chromium renders nothing for a browser that is not showing, so `web.bitmap` on such a browser is refused with `CAPABILITY_UNAVAILABLE` (the SPI as specified promised a bitmap whatever the view's state) | None in the base (a technology limit); the aria snapshot and the page text of such a browser still read | open |
| 40 | The SPI has no reload and no bitmap source | `web.reload` and the bitmap's `source` used the module-internal `BrowserExtras` of the built-in adapters; a third-party adapter was reloaded through `location.reload()` and reported `VENDOR` (D67) | A37 (4.x, built 2026-10-08; P4 §16.3): `WebBrowser.reload(ignoreCache)` and `bitmapSource()` as default methods, `BitmapSource {PROTOCOL, VENDOR}`, the built-in adapters override, `BrowserExtras` deleted, kit item 12 | closed (built); kit item 12 against real JCEF pending the next matrix |
| 41 | Three member names of the JxBrowser descriptor are unverified | `getChromeVersion` or `CHROME_VERSION_MAJOR`, `FrameLoadFailed.error` or `errorCode`, and `Browser.set` matched by its parameter count could not be checked without the product (P4 §4.5) | The contract kit against the real product, in a repository that holds a licence | **closed 2026-10-11** (the JxBrowser kit run against 8.18.3, P4 §4.5 *Against the product*): `FrameLoadFailed.error()` is the product's member, `Browser.set` resolves by its parameter count, every role of `jxbrowser.json` resolves; `getChromeVersion` / `CHROME_VERSION_MAJOR` are roles of `jcef.json`, not of the JxBrowser descriptor. The run found `Browser.dispatch` overloaded per event type (so `INPUT` was absent) and fixed it |
| 42 | No rate limit on the re-probe after `window.opened` | The re-probe was coalesced and cheap (probes only, no Swing walk), and the review's proposed one-a-second limit was not built (P4 §4.1) | A38 (4.x, built 2026-10-08; P4 §16.4): at most one cycle a second, the rest of the second waited out on the agent worker that runs it, no event dropped, no new thread or timer | closed (built) |
| 43 | A JCEF older than 2024-01 is served on neither path | Without `getDevToolsClient` the adapter is `UNAVAILABLE` and reports no endpoint, so not even a configured port is used (P4 §4.5) | An amendment that reports the port of such a JCEF for the CDP path alone | later, if a target needs it |
| 44 | Windowed JCEF on a Java 8 target is unverified | BS8: no native browser was created, no error logged; the strategy says "windowed, Java 8: unverified" | Find the cause (BS8's open question) | open |
| 45 | A call the CDP path serves reads the agent's sequence three times | The path read `lastSeq` before and after the primitive and the executor read it as well (P4 §5.2) | A36 (4.x, built 2026-10-08; P4 §16.2): the path reads none; the executor's read fills `seq.before` and `seq.after` of the call record | closed (built) |
| 46 | A killed JVM leaves a traced CDP path's trace directory | The private, owner-only `skillroy-cdp-trace-*` directory is deleted at close; a JVM killed meanwhile leaves it in the temporary directory (P4 §6.4) | Delete stale ones at the next open, or accept and document (done) | accepted |
| 48 | Over CDP, `web.waitFlag` on a page whose content security policy forbids `eval` fails at Playwright's second poll | Playwright's `waitForFunction` evaluates its own predicate with `eval` from the page's timer after the first poll, which such a policy refuses before the guard runs; it surfaces as `INTERNAL_ERROR` with an unhelpful message (found by A35, 2026-10-08; the agent path's `Runtime.evaluate` is not subject to the policy) | A clearer message, or a first-poll-only fallback to the agent path for such pages | later, if a target shows it |
| 47 | JxBrowser's observer registration and `cdpEndpoints` call the vendor without a bound | Every other vendor call of the adapter runs on its own threads with a bound; whether `on`, `set` and the endpoint read can block needs the product (P4 §4.5) | Bound them as the accessors are, once a run against the product shows the need | **closed 2026-10-11** (built; P4 §4.5 *Against the product*): measured against 8.18.3, every call answered within 2 ms with the renderer busy; with the Chromium main process stopped `on(...)` waited (and `url()`, `mainFrame()`, `size()`, `devTools().remoteDebuggingUrl()`, which the adapter already bounds), while `set`, `get`, `remove` and the engine options' reads did not. `subscribe()` and `addInitScript()` now register the observers on the adapter's threads within 2 s (measured 2007 ms and 2011 ms with the process stopped); a late registration is unsubscribed after `detach()` |
| 49 | The sample's `LendableItem` query cannot say "an item that needs no induction" | The planner was briefed to choose such an item for scenario C (P5 §14.3) and could only choose a plain counted item (`perDay: false`); the brief holds by the resolver's order on both seeds, not by the scenario's words (`ItemsTriggering` has an `inductionRequired` argument, `LendableItem` has none) | An `inductionRequired` argument on `LendableItem` (every committed plan carries the pack version) | not taken in Phase 6 (D193: no room, pack 0.5.0 went without it); **closed by Phase 7** (built 2026-10-10, VS: the argument comes with pack 0.6.0, P7 §16.2; null means either kind and the two-argument constructor stays, D394) |
| 50 | A target behind a strict-delegation loader cannot be hooked | BS3 RESULT §9 facts 6 and 7: a loader that delegates only `java.*` to its parent cannot see `Spy` on the bootstrap path, so the hook is refused at install (P6 §3.3 item 6) | A `java.*`-package `Spy` would serve it and is rejected (D116) | later, if a target needs it |
| 51 | Another bytecode agent's change registered before the first retransformation, or dropped before it, is invisible to the byte check and the event ledger | BS3 fact 14; the engine reports the other agents as `otherAgents` (D154, D180) and never refuses or degrades for them | none: documented | open (documented limit) |
| 52 | On Java 25 with class data sharing on, the one bootstrap append prints a VM warning on the host's stderr and ends sharing of non-boot classes | BS3 fact 8; shown in the hooks leg (one CDS line); accepted and documented under B26 Q14 (confirmed by the owner 2026-10-10) | The option `hooks.spyPlacement` (bootstrap or system) was the veto form of B26 Q14; not taken (Q14 confirmed by the owner without a veto, 2026-10-10) | open (documented limit) |
| 53 | The retransformation pause was measured with a spinning thread and the safepoint logs, not on a painting event-dispatch thread of a real application | BS3 RESULT §10 (D124): 11 ms on Java 8 at the size limit and 51 ms at 1.8 MiB, measured with a spinning thread; the effect on a UI is unmeasured | Measure on the desk with a UI | open |
| 54 | The pre-filter's effect on the class-loading cost of a registered transformer, and the `makeRaw()` route against `installOn`, are not measured | BS3 fact 20; D155. HK: both are proven to work (the module's tests, both legs), not measured | Measure | open |
| 55 | `RpcResult.startedAt` for an exact agent span | The agent span is reconstructed and centred in its call (`skillroy.agent.timing = reconstructed`, D142) | An additive wire field (a 6.x amendment) if the approximation misleads | later |
| 56 | OpenTelemetry metrics and logs | Phase 6 exports spans only (P6 §10.5); the instruments are named | Build behind the same observer once spans are proven | later |
| 57 | A Byteman the host loaded on its own command line cannot be reused by the module | P6 §7.4; the module reuses only a `Retransformer` its own earlier instance parked | none: documented | open (documented limit) |
| 58 | After another agent's `redefineClasses`, HotSpot reconstitutes a class with the constant pool it merged across redefinitions | The hooks module verifies a restore relative to the first bytes because of it (D172); whether a Byteman restore meets the same on a real redefinition, and whether a Java 8 JVM behaves alike, is not measured (new, HK) | Measure on both Javas with a second agent that redefines | open |
| 59 | `re-resolve` and `triage` export their root span only, and the planner's `skillroy mcp` child exports nothing | Their model calls are in their own ledgers and reports; spans beyond the root and a child started with telemetry are a further egress decision (D168; new, TL) | Pass the observer through the batch job and triage; options to the child | later |
| 60 | `swing.type` with mode `DISPATCH` and `enter: true` acts on the text field that last had the focus, not necessarily the target | `JTextField`'s Enter binding submits the text field that most recently had the focus (a `JPasswordField` is a `JTextField` too), so a dispatched Enter can submit another field or none; P1 "Type modes" and `Typing`'s Javadoc do not say so, and `SwingDisplayTest.typeDispatchSendsKeyEventsWithoutFocus` passes only because its field had the focus (FD's report) | Document it in P1 "Type modes" now; later have DISPATCH on a `JTextField` that has not had the focus fall back to `postActionEvent()` and report it. `SET` mode (what the sample scenarios use) is not affected | documentation now, a fix later (Phase 7 documents it in A62's passage, P7 §16.2; the fix stays later) |
| 61 | `Page` events whose URL a page chooses (`Page.navigatedWithinDocument` through `history.pushState`) are queued within the hand-off bound but not rate-budgeted | D266's budget covers console events only (SB's report, "for a later pass") | A bucket for page events too, if a page shows the need | later |
| 62 | `web.evaluate`'s wrapper text and a thrown error's description remain page-sized first copies | One per call, script primitives only; the library guard (D268) bounds every library call but not these (SB) | Bound the wrapper at its source as the guard does | later |
| 63 | `hook.events` accepts an `id` that is not a hook id | A comma in the `id` widens the topic filter to every topic on the bus; no actor gains (P2-L1-F2 was refuted), but P6 §4.2 reads "the hook's emits, or every `hook.*` topic" (SA's report) | Refuse an `id` that fails `HookSpec.ID_REGEX` (`INVALID_PARAMS`) and keep only `hook.*` topics | **closed 2026-10-11**: both, as proposed (D491; `ModesTest`) |
| 64 | One container predicate for the hooks module's `Capture` and the Byteman `BindingEncoder` | The two allow-lists are identical copies (D224, D237) and can drift (FB, FA) | Move the predicate into `agent-api` | later |
| 65 | A `FAILED` ledger entry keeps `restoring = true` | A later install of another id on that class is refused "the class was not retransformed" until `hook.remove {}` restores it (older engine behaviour found by FA; its other observation, `own` counted for every class of a batch that threw, was fixed by SE, D272) | Clear the flag when a restore is retried or the entry is dropped | later |
| 66 | A `Code`-attribute scan for instructions that reference a `Spy` member | Would make the residue check exact in D172's corner, reference bytes that already name `Spy` (D241; FA) | About 150 lines of class-file decoding in `agent-api`, shared with Byteman's verification | later |
| 67 | A nested-schema variant of `SessionDesk.withSecretSchema` | SC left it out so that the committed session recordings do not change; `PromptMaskSchemaTest` covers the nested shapes | Add the variant and regenerate the recordings | later (SC) |
| 68 | Byteman: a copy of an accepted application class name defined after a `rule.submit` is not counted against `hooks.maxClassBytes` / `hooks.maxMethods`, and a name only a sibling module's jar holds is not looked up by the class-bound gate | A load-time transform is not the stop-the-world pause the bound exists for (SE; recorded in P6 §7.3) | Count late copies if a target shows the need | open (documented limit) |
| 69 | A capture cut that splits a user-info password before its `@` may leave its first characters | `redactText` runs after the cut (D275; SE); a wider window before the cut would read beyond the capture's input budget on the application's thread | none: documented in P6 §4.3 | open (documented limit) |
| 70 | `OutputFiles`: a directory shared with a group can have an entry renamed by a member between the check and the open; a link in a directory above the one checked is followed; `Triage` reads a run's `failure/` files following links (a read) | Written into P2 §11.3 (SF) | none: a group-shared directory is the operator's choice | open (documented limit) |
| 71 | A volume mounted with ignore-ownership whose type keeps permissions (APFS or HFS+ "Ignore ownership on this volume") is not detected; an unset `%SystemRoot%` would make the `.bat`'s `findstr` resolve under the current drive's root | The JDK does not expose mount options; Windows always sets `%SystemRoot%` (SD) | none: documented | open (documented limit) |
| 72 | P6 §10.4's `hooks` event row says "installed and unavailable ids" while the code carries a count of the unavailable ones | A documentation difference found by the code review's L1 sceptic and left by FC | Reword the row, or send the ids | Closed 2026-10-09: the row now names the mode, the installed ids, the count of unavailable ones and the armed ids, as the code sends |
| 73 | The sample's two older focus tests (`iconKeyNeedsFocusButPerformAlwaysWorks`, `aMouseClickOnAKeyDeliversItsActionAndHandsTheFocusBackToTheEntryField`) request the focus once and print "not checked" if it does not come in 5 s | `awaitFocus` could replace both waits (FD) | Use `awaitFocus` | optional |
| 74 | The JxBrowser adapter's console budget (D266) runs against the neutral fake only | No product here (SB) | The kit against the real product, with gap 41 | **closed 2026-10-11**: kit item 14 passes against JxBrowser 8.18.3 in all three variants of the run (P4 §4.5) |
| 75 | P4 §4.3 (`drain(900000)` in the tap poller sentence) and §4.6 (`DRAIN_CHARS` 900000) still carry the figure before pass 2 | The pass-2 paragraphs beside them state 500000 (D268); SB did not rewrite the older sentences | Reword the older sentences | Closed 2026-10-09: both sentences say 500000 with the earlier figure in brackets |
| 76 | `swing.capture` in `PAINT` mode, given a frame on Linux, includes imitation window decorations | On the X toolkit `XFramePeer.print` paints the frame's children and then draws a Motif title bar (17 px from y = 6, with the title and two buttons), cross-hair lines 5 px in and outer lines at fixed positions whatever the insets are; with the insets of 0 that a window-manager-less display gives, they lie on the content (the desk's prompt line half hidden; 12,021 pixels differ from a `ROBOT` capture). Found 2026-10-10 by BS9 (`spikes/BS9/RESULT.md`, the image `results/nowm-j25-paint-frame-initial.png`) and met by BS12; the same on Java 8 and Java 25; every `PAINT` capture of a frame taken on the CI display so far carries it, a dialog does not (`XDialogPeer` has no `print`); a painted root pane equals a `ROBOT` capture pixel for pixel there | Paint the root pane (`RootPaneContainer.getRootPane()`) at its offset and report its origin in the result, decorations outside the image (the extended `swing.capture` of Phase 7; P1 §6 and P4 §2.5 to be amended with it); baselines and recordings taken before the change are not comparable after it (BS11) | closed (built 2026-10-10, Phase 7, VS: A61 paints a window from its root pane and reports its origin, P7 §3.1, D288, D385; a glass capture equals the root-pane paint with 0 differing pixels in the Robot leg) |
| 77 | Robot key input (a V3 keyboard through the OS) is not built | BS9: keys land in the AWT focus owner if and only if the target JVM holds a focused window, and on a window-manager-less display nothing restores a frame's focus after a modal dialog is disposed; V3 types through the routed primitives (`swing.type {mode: SET}` by locator, `swing.key`) after a focus check and stops the turn when the target JVM holds no focused window (P7 §3.4, D345) | `swing.type {mode: ROBOT}` and `swing.key {via: ROBOT}` with X-keysym translation and a re-activation after a dialog, if a target needs the OS key path proven (the veto form of B29 Q6, not taken: B29 confirmed by the owner 2026-10-11) | open (a later tier) |
| 78 | HiDPI | The Swing side reports user-space pixels and the web side computes an empirical factor with `scaleAssumed`; nothing runs at another scale than 1 here (BS9, leg `mac-j25`: a `ROBOT` capture on a Retina display differs from a `PAINT` by antialiasing) | A run at another scale, then scale-aware capture results | open |
| 79 | `swing.hitTest`'s mouse target is inferred with public API plus two rules (`componentPopupMenu`, `autoscrolls`) | A component that calls `enableEvents` otherwise is invisible to the hit-test (BS10 condition 3); `swing.pointer`'s `received` reports the component that really received the press | none: `received` is the evidence | open (documented limit) |
| 80 | The covering-window rule of the routed path ranks only a modal dialog, an owned window and the active window | Two unrelated frames of the JVM are reported, not ranked (P7 §3.2, §3.3); the Robot path refuses every covering window instead (§3.4); a window of another process is invisible to both | none: stacking against other processes is outside the JVM's knowledge | open (documented limit) |
| 81 | `web.pointer` has no key input | `Input.dispatchKeyEvent` and `Input.insertText` are the later keys of `INPUT` (P7 §4) | The key actions, when a page under test needs them | open (a later step of `INPUT`) |
| 82 | The computer toolset's `hold_key`, a horizontal `scroll`, and in V3 `left_mouse_down`/`left_mouse_up` are refused as text | P7 §6.3; the last two because a Robot press is always paired with its release (D343) | none yet | open (documented limit) |
| 83 | A baseline exists per rendering the project runs in | A new JDK line or font set needs an `approve` run per environment to add its key (P7 §8.7, D330) | none: an `approve` run per environment | open (documented limit) |
| 84 | Redaction blanks a component's rectangle after it was painted | The layout and a value's width stay visible, and pixels carry no value mask, so a component no mask names reaches a model as rendered (P7 §3.6, §11) | A pack names its components in a `CaptureMask` | open (documented limit) |
| 85 | The session's fold and the vendor's history rule on the 5.5 models are not exercised live beyond one turn | P7 §7.4 | A longer live session | open |
| 86 | `web.pointer` into a hidden JCEF browser is served, but what Chromium does with it is recorded, not asserted | P7 §4.9; kit item 17 (VW, 2026-10-10): off screen every event is answered within 25 ms, windowed on Java 25 the first `mouseMoved` after about 5,010 ms and the press and release at once, and the page records no event in either | none: a technology limit like gap 39 | open (documented limit; measured) |
| 87 | The window between the recorder's hit-test and the recorded step's act is closed by the stale-capture check and the verb's exact-match locator, not atomically | A locator that matches exactly one other component after a window change acts on it (P7 §6.4, D305); `expectTarget` closes the window for the live-only raw calls only | none yet | open |
| 88 | The routed `MODAL_OPENED` rule reports `fired: false` also when the dialog is opened by a release and only the trailing `MOUSE_CLICKED` is withheld, though the button's action did fire | P7 §3.3 as written (VS, 2026-10-10): the executor's A29 then fails such a `pointer` step; nothing on the sample desk opens a dialog inside a mouse handler | A contract note, or `fired: true` with a `reason` when the release acted; for the code review | open (a candidate of the build) |
| 89 | The MCP server's `skillroy_session_export` writes no anchor PNGs | Only `session` (the REPL's `export` and `--play --export`) writes `ExportResult.images()` beside the bindings file (P7 §9.3, D429); the contract asks it of `session` only, so a client's export of an anchored step has no baseline file to review | Write them under the server's output directory and list them under `images: true`, if a client's anchored exports are wanted | open (not in the contract) |
| 90 | `web.pointer`'s `hit` is the main frame's: a frame element when the point lies in an iframe, never descended | Only `web.hitTest` descends into frame elements (P7 §4.2, D408, D410); a recorder that reads `web.pointer`'s answer sees the iframe, not what is in it | Call `web.hitTest` first; descending in `web.pointer` is a later step | open (documented limit) |
| 91 | The committed image entries carry `rendering.os.version` and `jdk.version`, the container host's kernel release among them | Reviewer-only text that is not part of the render key (D350) and that every `approve` on another host rewrites; the code review's L1-F7 (as a churn problem) was refuted and its L3 sceptic did not endorse dropping the fields (FB) | Drop `os.version` from the entry, or keep it and accept the rewrite; a contract change | open (not a defect) |
| 92 | A capability declared after the target is ready is held `UNAVAILABLE("not probed yet: the target is not ready")` until a posted worker probe has run | Only the hooks module declares late and D449 closes its window, but an agent module outside this repository that declares after readiness would show the same pending state, and the reason text is misleading for a late declaration (FX) | A synchronous first probe in the core, or `declare(id, probe, initial)` in `agent-api` (both rejected for D449: a blocking declare, a public Java 8 SPI change); at least a reason that says "declared late" | **closed 2026-10-11** for the reason: a late declaration reads `UNAVAILABLE("not probed yet: declared after the target was ready, its probe is queued")` (D490); the state and timing unchanged, the two shapes stay rejected (D449) |
| 93 | Replay refuses an unapproved value for a model's strategy only (D72) | The export marks a model's or a client's tier (a) binding `REQUIRED` (D468), which is the reviewer's mark and no replay gate (SH) | `Binder`: refuse `NOT_APPROVED` for a recorded `REQUIRED` approval whatever the strategy, one line in `Binder` | **closed 2026-10-11: not refused, the owner's answer** (B33); the behaviour stays as built |
| 94 | `Recorder.namesSecret` still reads the credential rule's reference with the field-only expression, and `SessionService.REFERENCE_SENTENCE` does not say "nor a credential slot as a whole" | S101 item 3 is cosmetic (the session's gate refuses first); the sentence is part of every session's system prompt, so changing it changes the five Phase 5 session recordings (SH proposed it, SI left it for the integration pass: no package holds it) | Read the text with `References.references` in the recorder; reword the sentence and regenerate the recordings on the key-free route (`-Dskillroy.inference.fixtures.update=true`, `SessionExportTest`) | **closed 2026-10-11**: the recorder reads the text with `References.references` and asks the facade's new `namesSecret` (rule 1 over the session's joins, D487); the sentence reworded (D488) and the five recordings regenerated key-free; the old sentence had reached the model as "secret: <redacted> person" (the mask's key rule) |
| 95 | S111's agent half, the composite's clip and S133's late click ran against fakes | The web host registers no `CaptureMask`; no run combined a real JCEF frame and a `JOptionPane`; Chromium's handling of a synthetic middle click is stated from its behaviour (SI) | A `WebHostChecks` item with a mask on the front browser's panel, one with a dialog over the page; the kit against real JCEF | open |
| 96 | A page under a modal dialog can still be clicked by `web.dom.click` and `web.dom.fill` for a model or a client | S109's recorder path is closed (D472); the broader alternative is a modal check in the DOM primitives for MODEL and CLIENT origins, a P4 amendment (SI) | The check, if wanted | **closed 2026-10-11: not refused, the owner's answer** (B33); the behaviour stays as built |
| 97 | An in-window change between a V2 or V3 capture and the act (a glass-pane dialog that opens no window) is outside the stale rule's trigger; an application's own Paste item or context-menu command is an ordinary click | S114's rule compares the capture's own view and a window event; the clipboard rule (D470) sees chords and the middle button, not an application's own commands (SI) | Mask the application's Paste control (rule 6 then refuses every input at it); a tap for in-window changes is not built | open (documented limit) |
| 98 | A window of the target JVM on another virtual desktop or workspace is invisible to AWT; the iconified refusal ran on the WM-less display and once under openbox | `requireFocus` is the only guard there; macOS's and Windows' minimised windows and Windows' parking of a minimised frame at about (-32000, -32000) were not run (SG, D450) | A run on macOS and Windows hosts | open (documented limit; waits for the hosts) |
| 99 | The bindings JSON reader still follows a link at the bindings file's own position | The writers refuse a link in the bindings directory (S140) and replace a link at the file's position by the atomic rename; the PNG reader's no-link rule is the one the writers adopted (SJ) | The JSON reader adopting the PNG reader's rule | **closed 2026-10-11**: `BindingsFile.read(bindingsRoot, envId, scenarioId)` under the walk the writers and the PNG reader share, opened without following a link, for the binder, the session and the executor; a link at the file's position, dangling or not, is unreadable (D489); `diff-bindings` reads the files a person names |
| 100 | The Windows ACL judgement of `OutputFiles` resolves CREATOR OWNER by its English name | On a Windows with localised built-in names it is not exempt: a false refusal, the limitation S94 accepted for SYSTEM and Administrators, extended (SJ, S141) | None yet; settled on a Windows host with the localised-names case of S94 | open (waits for the host) |
| 101 | The Robot covering rule refuses every point of a dialog that lies over its own owner | `HitTest.covering` lists the owner frame below the dialog, and `rankedAbove` answers false for an owner, so V3 cannot click a prompt dialog of the desk without `force` (the security review's lens L2, a functional note on the safe side) | Leave out of the Robot rule only windows that own the target, never other unranked windows | open |
| 102 | The `<target-state>` block of the bound slots is capped at 40 slots and 30 fields a slot (counted beyond) and was exercised with a few slots only | FC's LV-1 fix (D444) | A test with more slots than the cap | open (low) |
| 103 | On macOS JxBrowser's bitmap holds the display's colours, not the page's | Chromium renders in the output display's colour profile and `Browser.bitmap()` holds those pixels: the page's sRGB (32, 96, 160) read back as (0, 87, 146) on an external display (the JxBrowser kit run against 8.18.3, 2026-10-11, P4 §4.5; kit item 8's colour check failed until the engine ran with `--force-color-profile=srgb`) | The application's engine switch; none in the adapter (a technology limit, as gap 39) | open (documented limit; measured) |

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

- **Phase 6 dependencies** (fetched online once on 2026-10-09 into the local Maven repository, B3's "install what
  is needed"; the build is offline): OpenTelemetry Java **1.66.0** (released 2026-09-11; 1.67.0 was released on
  2026-10-09 itself and is not pinned) with its tree: `opentelemetry-api`, `-context`, `-common`, `-sdk-common`,
  `-sdk-trace`, `-exporter-otlp`, `-exporter-otlp-common`, `-sdk-metrics`, `-sdk-logs`, `-exporter-sender-jdk`,
  `-exporter-common`; for tests `-sdk-testing` and `-sdk`; `-exporter-sender-okhttp` excluded. Byteman **4.0.27**
  (test and provided scope only, B8). Byte Buddy 1.18.14 and maven-shade-plugin 3.6.2 were fetched by spike BS3.
- **Phase 6 build notes (2026-10-09; HK, BW, TL, CP, FX).**
  - The hooks module compiles against the packaged, relocated `skillroy-agent-bytebuddy` (a shading module): build it with
    `mvn -o verify -pl agent/skillroy-agent-hooks -am`; a root `mvn compile` or `mvn test` fails there by design
    (`package …shaded.bytebuddy does not exist`). The same needs `verify`, never `install`.
  - The matrix command's `-pl` list of the agent-side modules is now **seventeen**: the twelve of the Phase 4 and 5
    tables plus `agent/skillroy-agent-spy`, `agent/skillroy-agent-bytebuddy`, `agent/skillroy-agent-hooks`,
    `agent/skillroy-agent-byteman-sys` and `agent/skillroy-agent-byteman`.
  - Surefire 3.6.0 with `reuseForks=false` runs **no test** and exits 0 when `-Djvm` names a Java 8 JVM (`Tests run: 0`);
    the hooks module isolates its six fresh-JVM classes in executions of their own (D175) and the Byteman module uses
    three. A command-line `-Dtest` merges those executions into one JVM, where the Byteman module's `ProbeTest`,
    `CheckLoadsNothingTest` and `InProcessRouteTest` skip with a message. A Java 8 leg's counts and the reports'
    `java.version` are to be read, not only the exit code; the Byteman module's counts come from a run without `-Dtest`.
  - On a JDK 25 or newer test JVM the hooks module's test output carries `OpenJDK 64-Bit Server VM warning: Sharing is
    only supported for boot loader classes because bootstrap classpath has been appended` once per test JVM that appends
    the spy jar: expected (P6 §2.5, B26 Q14). In the hooks leg a Java 25 target's log holds that one line and, after
    checks 16 and 17, one JEP 451 warning per re-attach (`ATTACH` of `skillroy-agent-bootstrap.jar`), none of the
    engine's own.
  - Byteman 4.0.27 (`org.jboss.byteman:byteman`, LGPL) is in the local Maven cache and in the image's mounted
    `/root/.m2`, at test scope (the Byteman module, `skillroy-agent-dist`, the scenarios module) and `provided` scope
    (the sys jar); never in a distribution. `BytemanIT` and the hooks leg's checks 166-168 skip their listening-socket
    checks on a host without `/proc` (macOS) and say so on stdout.
  - OpenTelemetry Java 1.66.0 is in the local cache with its tree (no BOM, no OkHttp sender, no autoconfigure artifact);
    the CLI's `lib/` holds 55 jars (8 project, 47 third-party) and the zip 62 entries.
  - `ci/run-in-linux.sh` no longer sets an empty `MAVEN_OPTS` in the container, and passes a `MAVEN_OPTS` the host has by
    name only (S39). The web host's legs need `-Pjcef` together with `-pl samples/skillroy-sample-web-host…`: those
    modules are in that profile only.
  - The neutrality check scans agents' worktrees under `.claude/worktrees` as well; run it on a `git archive` export of
    the tree, not on a tree with worktrees inside.

- **Phase 6 fix rounds (2026-10-09; FA-FD, SA-SF): test traps and their guards.**
  - Surefire traps and their guards: `failIfNoTests` of the hooks module comes from `skillroy.failIfNoTests` (default true)
    with the profile `single-test`, activated by `-Dtest`; both Byteman POMs take it from the same property with the profile
    `merged-test-run` (D228, D240); `Harness.assumeFreshJvm` and the Byteman `FreshJvm` skip a dedicated-JVM test only under
    `-Dtest` and fail otherwise (D229, D240). An execution that runs no test therefore fails the build by itself. Still read
    **counts, not exit codes** on the Java 8 test-JVM leg: after the pass-2 fixes the hooks module has **70 tests in 17 report
    files** (eight fresh-JVM classes), the Byteman module **43 in 12** and `-byteman-sys` 10 in 3, 0 skipped, every report's
    `java.version` `1.8.0_*` (the earlier figures in this file, 64 in 15 and 37 in 10, are those before SE). `-Dtest` runs of the
    in-process Byteman tests need `package` or `verify` (at `test` the sys jar is still `target/classes`).
  - The matrix scripts run `mvn -fae` (fail at end): without it the first arm64 round's java8-targets leg stopped at the sample
    application module after the typed-keys failure and its lending ITs on Java 8 targets did not run until the leg was re-run.
  - `SignOnCheckedHeadlessIT` (failsafe, no display, about 2 s, in the normal `mvn verify`) needs the agent home unpacked at
    `pre-integration-test` and copies it to `target/it-hooks-home` without the lending module; `ChildJvmIT` replaces the
    `ChildJvmTest` that never ran (the `pom` packaging of `skillroy-agent-dist` binds no surefire).
  - The real-volume tests (`PermissionlessVolumeTest` in the controller and the agent core, `JarTrustTest`, `OptionsFileVolumeTest`)
    attach FAT and exFAT disk images with `hdiutil` into the test's temporary directory (about 3 s per class) and are skipped
    where `hdiutil` is absent, so they run natively on macOS and not in the Linux image (D261).
  - The CI example: if it ever adds `telemetry.yaml` or `inference.yaml` beside `$RUNNER_TEMP/credentials.yaml`, the container
    runs as root and the file is the runner user's, so the owner rule of D280 refuses it; write it with `--telemetry-file` from
    inside the container or `chown` it.
  - An operator's options file that holds a `token` must be owner-only; one without may be readable by others; a discovery
    directory below a group- or world-writable parent without the sticky bit is refused (choose another `--discovery-dir`); a
    Byteman rule may not name a JDK class (`RULE_REJECTED("<name>: class is the JDK's")`).

- **Phase 7 build (2026-10-10; VC, VS, VW, VM): commands and traps.**
  - Selecting tests under `-am`: `-Dtest=…` with `-am` needs `-Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false`,
    and `-Dit.test=NONE -Dfailsafe.failIfNoSpecifiedTests=false` keeps `verify` from running the ITs. In the sample scenarios
    module `-Dtest='ai.skillroy.testjava.samples.lending.scenarios.*Test'` selects the module's unit tests (a slash pattern
    selected none); a nested end-to-end check runs alone with `-Dit.test='InferenceEndToEndIT$JavaAgentSeedA#<method>'`.
  - The regeneration scripts of plan version 6 (VC's, rerun by step 2b after a pack bump): `mvn -o verify -pl
    samples/skillroy-sample-lending-scenarios -am -Dtest='PlansUpToDateTest,BranchPlansUpToDateTest' -Dskillroy.plans.update=true`
    (plans, the branch variant's and, since Phase 7, `scenarios/exported/plan`), then `-Dtest=BindingsUpToDateTest
    -Dskillroy.bindings.rehash=true` (every committed bindings file's `planHash` and `packVersion`, nothing else); the web host's
    plans with `-Pjcef` and the same flag. The key-free recordings: `-Dtest=SessionExportTest
    -Dskillroy.inference.fixtures.update=true`. New `keypad-image` baselines: the resolution classes in the image with
    `-Dskillroy.bindings.update=true`, once per Java line, both seeds per run; the capability goldens: `-Dskillroy.golden.write=true`
    in `LendingEndToEndIT`; the committed `exported/session-vision-loan.*`: the item 8 check with
    `-Dskillroy.inference.fixtures.update=true` in the image.
  - The Robot leg (`LendingTarget.Mode.JAVAAGENT_ROBOT`, `RobotChecks`) runs on the WM-less display of the image with the agent
    option `robot=true`: XTEST is there and both Robot capability ids are `OK("robot")`; after a modal dialog closes no window of the
    JVM holds the focus (BS9 K7), which is how `requireFocus` is exercised. A WM-less display also gives no window the focus at
    start: an end-to-end check that types into the desk must bring it to the front first (outside the journal), as
    `VisionSwingChecks.activate` and `VisionSessionScript` do.
  - A compare of a baseline is exact on decoded rasters after masks; a baseline approved on a fresh desk is not comparable with a
    frame taken after other scenarios ran unless the changing components are masked (D403).
  - The CLI distribution's `lib/` holds `skillroy-vision` since Phase 7: 9 project jars and 47 third-party ones, 56 jars (`DistributionNoticesIT`
    lists the nine); no third-party jar was added.
  - A committed image entry's `rendering` carries the reviewer-only `os.version`; inside the image that is the host kernel's
    release, so a baseline approved in a container names the container host's kernel (it is not part of the render key, D350).

- **Phase 7 code review and security fixes (2026-10-10/11; FA, FB, FC, FX, SG, SH, SI, SJ): commands and traps.**
  - The web host scenarios build only with `-Pjcef`: a native `mvn -o verify` without it does not compile `WebHostChecks`; the image
    run with `-Pjcef -Dskillroy.jcef.platform=linux-arm64` does (FB).
  - The WM-less Xvfb keeps a frame's `ICONIFIED` state after `setExtendedState(ICONIFIED)` while the frame stays mapped, so a
    display test can iconify there; BS9's derived image `skillroy-spike-bs9:wm-arm64` runs a display test under openbox
    (`openbox --sm-disable &`, wait for `_NET_SUPPORTING_WM_CHECK`, then `mvn …`) (SG, S104).
  - The Robot leg's `requireFocus` refusal is its own test and reports itself skipped when the frame kept the focus after the
    prompt; read the leg's `focusedAfterPrompt` line together with that test's status (FA, D439).
  - A Java 8 `-verbose:class` line can land between a printed value and its newline (`[Loaded … from …]`):
    `ChildJvm.awaitLine` drops the fragment (FA); `HostSafetyIT`'s full-home Robot case had never run on a Java 8 target before.
  - Restoring a source file with its old modification time (`cp -p`, `shutil.copy2`) leaves Maven's incremental compiler on the
    newer class file; touch the file, or build `clean`, before trusting a run (SI).
  - The Phase 5 session recordings carry the session's first message in their fingerprints: LV-1 changed it (FC regenerated the
    five, key-free, `-Dskillroy.inference.fixtures.update=true` with `SessionExportTest`), and any later change of that message
    (gap 94) or of `REFERENCE_SENTENCE` changes them again (it did on 2026-10-11, gap 94: regenerated the same way). The committed `session-basic-loan` bindings changed once more for S113
    (the model-resolved `item` is `REQUIRED`; SH).
  - The capability-hash check of scenario E compares the records' `agentHash` too and names every differing entry per run on a
    failure, so a recurrence is diagnosable without the run directories (FX).
  - An accidental `mvn install` (FC, about five seconds) wrote `ai.skillroy` snapshots into `~/.m2/repository/ai`; they were
    moved out a minute later and the directory is absent; a partial build without `-am` then fails to resolve them, which is how
    it was confirmed. Never `mvn install` this project (the trap above).
  - Native test counts of different runs do not add up: the fixers' own full-reactor runs (5005 on `a134159`, 5016, 5027 and
    5027 for SH, SG and SJ, 5065 for SI, 5099 on the final tree) and the orchestrator's summaries of the merged trees differ by
    several dozen tests for the same tree, and the skipped counts differ with the headless display tests. Compare counts only
    within one method of counting, and read counts, not exit codes.

## Walls

Work that cannot proceed without the project owner, or without something only the owner can provide.

| Wall | Decision | What waits |
|---|---|---|
| The push | B1 | The initial commit exists (2026-10-06); the push to the private remote `Skillroy-AI/skillroy-test-java` waits for the owner's word. History is permanent once pushed |
| Model provider API key | B7 | **Resolved 2026-10-08, and the live items done the same day.** The first key (organisation-level) was refused by the API without an `anthropic-workspace-id` header (BS13, the night of 2026-10-08), so nothing live ran that night and the profiles were marked not built (D110). The owner's workspace-scoped key, exported as `ANTHROPIC_API_KEY` in the operator's own environment only (never a file of this repository, never a property, never a command line; the contract's §2.3), answered the models endpoint with no workspace header; in the afternoon of 2026-10-08 it carried BS13's live part, scenario D's live re-record, the live profiles and §15.3, scenario C's authoring and the nightly job's live figures ("Phase 5, the live items" above), about USD 2.05 in all. On 2026-10-10 it carried the planner's stdin check (USD 2.05) and BS12 (USD 5.64), and on 2026-10-10 and 2026-10-11 the three runs of `VisionLiveIT` (USD 0.131, 0.155, 0.148): USD 8.12 in all. Nothing waits on it. |
| A JxBrowser licence | B9 | Real verification of the `jxbrowser` adapter. The adapter exists (built 2026-10-07) and passes the contract kit against the neutral fake, plainly and with the init-script chaining option; the kit (`skillroy-agent-web`'s `test-jar`) runs against the real product only in a repository that holds a licence, outside this repo, through a `WebAdapterFixture` over a real engine. That run also settles three member names of the descriptor and whether some vendor calls can block (gaps 41 and 47), and, since Phase 7 (2026-10-10), the `INPUT` roles of the descriptor (named from the public 7.x API as best known, D413; modifier keys are refused) and whether `Point` is CSS or device pixels on a HiDPI display (the strategy says "input unverified"). Phase 4 reached its public exit without it. The owner (2026-10-06): a licence is available, so that verification can follow in the private overlay's repository **2026-10-11: a 30-day evaluation key is available** (B33, in the owner's login shell as `JXBROWSER_LICENSE_KEY`, never in a repository); the kit run follows in the private sibling repository. **2026-10-11: run.** The kit passed 19 of 19 against JxBrowser 8.18.3 on macOS (arm64, Java 25, the external display and the Retina display) in three variants (hardware-accelerated, the same with init-script chaining, off screen), after two fixes in the base: `INPUT` (one `dispatch` role per event type; the wheel's deltas negated; `Point` is in CSS pixels on a HiDPI view, so the strategy's note is gone on line 8) and observer registration bounded (gap 47); gaps 41, 47 and 74 are closed and gap 103 records the colour-profile limit (P4 §4.5 *Against the product*; P7 §4.5) |
| A Windows host | B13, Phase 6 | Verification of the fixes S7 and S15 (not verified on Windows); the Windows path of Phase 6, built 2026-10-09 where Linux can test it and **waiting for the host** for: `ci\run-on-windows.cmd` run twice without a flake and the list of `design/phase6-contracts.md` §9.5 and §13.3 (`bin\skillroy.bat` run, the planner started through a launcher path and a `--pack` path with a space, which decides the quoted `cmd.exe` form, the ACL branches checked with `icacls`, `JAVAAGENT` as the logged-on user with the `SEMANTIC`, `ACTION`, `COMPONENT`, `DISPATCH` rungs and paint capture, one cross-session attach for its failure text, the 5 s start-up margin; and, added by the pass-2 fix rounds, `bin\skillroy.bat` run from a directory holding a decoy `findstr.cmd` and the runner from a checkout holding a decoy `mvn.cmd`, the planner through the native `claude.exe` with the prompt on stdin and npm's `claude.cmd` refused with the remedy, the schema's pre-quoted argument read back intact, a command line near 32767 characters, a FAT32 and an exFAT volume refused by every owner-only member, the new ACL branches of `OutputFiles`, `SettingsFiles`, `JarTrust`, the ancestor rule and the bootstrap's `OptionsFileCheck`, and the localised-Windows case of S94), and the exit criterion that names Windows; when it has run, the "not verified on Windows" marks come off `README.md`, `SECURITY.md`, `design/threat-model.md` §8, P1 §2.5 and this file. The matrix table rows for it are `\| windows, Java 25 targets \| ci\run-on-windows.cmd \| … \|` and `\| windows, Java 8 targets \| ci\run-on-windows.cmd (SKILLROY_JDK8_HOME) \| … \|`. The owner (2026-10-06): later, a Windows 11 machine, which also runs the tenant layers' testing under Windows and under WSL2 |
| The owner's word on Phase 6 | B26, B27, B28 | **Resolved 2026-10-10** (`B26 ok, B27 ok, B28 ok`, no veto): every answer of B26, Q1's wording of the hard rule now in `CLAUDE.md` among them, every decision of B27, and the sign-off of the repeat security review with the eight lows accepted (B28). The build had proceeded on the then provisional answers; the built behaviour stands as it is. Nothing waits on it. |
| The owner's word on Phase 7 | B29, B30, B31 | **Resolved 2026-10-11** (`B29 ok, B30 ok, B31 ok`, no veto): every answer of B29 (Q25, Q27 and Q34, which rested on the owner's taste or knowledge, among them), every decision of B30, the behaviour changes listed in its entry included, and the sign-off of the Phase 7 security delta review with the two lows S142 and S144 accepted (B31). The build had proceeded on the then provisional answers; the built behaviour stands as it is. Two optional choices rode along (gaps 93 and 96) and stay open. Nothing waits on it. |
| An x86-64 JDK 8 for the amd64 image | – | **Resolved 2026-10-11: the volume remounted and both legs run green.** The amd64 legs with Java 8 (Java 8 targets, the Java 8 test JVM) use an x86-64 JDK 8 on an external volume of the owner's machine, which was not mounted for Phase 7's final matrix and which the owner remounted on 2026-10-11, so no download was needed (`ci/run-in-linux.sh` mounts any JDK 8 directory named by `SKILLROY_JDK8_HOME`). Both legs ran green the same day on `bc1e250`, and `keypad-image`'s Java 8 key held on amd64 ("Phase 7 final matrix"). Nothing waits on the owner. |

Not walls any more: the licence, the holder's name, the first commit and the remote (B1), the final names and the
domain behind the groupId (B2) and the sample domain with its deposits (B10) are decided. B17-B20 are confirmed
(2026-10-06), and the two vetoes inside B19 are built. B21 (the fifteen answers of the Phase 4 contract) was
confirmed by the owner on 2026-10-07, and so was B22 (the eleven decisions the Phase 4 build took without the
owner, D59-D69) the same evening. B23 (the Phase 5 contract's answers) and B24 (the decisions of its build and review) were confirmed by the owner
on 2026-10-08, every row, and B25 (D111-D113) on 2026-10-09. B26 (the Phase 6 contract's answers) and B27 (the decisions
of its build, review and security fix rounds) were confirmed by the owner on 2026-10-10, every row, and the repeat
security review was signed off the same day (B28). B29 (the Phase 7 contract's answers) and B30 (the decisions of its build, review
and fix rounds) were confirmed by the owner on 2026-10-11, every row, and the Phase 7 delta review was signed off the same day (B31).

The review items of the end-to-end review, now decided (gap numbers refer to the table above):

| Id | Subject | State |
|---|---|---|
| R1 | How to remove the 45 ms per call (gap 1) | Decided as option (c), the agent's own HTTP listener (H1); confirmed 2026-10-06 (B17); built |
| R2 | Classification of `MODAL_OPEN` from a step call (gap 2) | `FAIL` / `ASSERTION` (A30); confirmed 2026-10-06 (B18); built |
| R3 | Clean-up of stale discovery and token files (gap 3) | Removed by agent and controller (A23); confirmed 2026-10-06 (B18); built |
| R4 | Masking of fixture secrets in `run.jsonl` and `bindings.json` (gap 4) | Masked now (A25); confirmed 2026-10-06 (B18); built |
| R5 | A Java 25 target in the Linux image | Done: the image is JDK 25 (B3) and the gate was run on 2026-10-02 |

## Next steps I would take

1. **The push**, when the owner says (every commit since the initial commit of 2026-10-06 is local). The
   DCO trail (release checklist item 10, checked 2026-10-11): 140 of the 143 commits carry a `Signed-off-by`; the three that
   do not are Phase 6 merge commits of 2026-10-09, `ee020ad` and `f33488f` (clean merges) and `6a342e7` (Merge `main` into
   `p6/bw`, with its own conflict resolution), **accepted by the owner on 2026-10-11** ("DCO accept": no history rewrite). The neutrality check of the private sibling passed the same day. The repeated
   security review is signed off by the owner (2026-10-10, B28; item 5); the Phase 7 delta review is signed off by the owner (2026-10-11, B31; item 6).
2. **Phase 4 follow-ups**: the JxBrowser kit run in the private repository that holds the licence (the wall "A JxBrowser
   licence"; **run 2026-10-11 against 8.18.3, green after two fixes**, P4 §4.5); why windowed JCEF on a Java 8 target
   creates no browser (unverified since BS8, gap 44) and why a `srcdoc` iframe never loads in the image; then the open
   gaps of the table above (38, 40, 42 and 45 were closed by the Phase 4.x amendments on 2026-10-08; gap 48 is new).
   D59-D69 are B22, confirmed by the owner on 2026-10-07.
3. **Phase 5 is built, reviewed, fixed and closed live** (`design/phase5-contracts.md`, 2026-10-08; B23 and B24
   confirmed by the owner on 2026-10-08; the live items of the afternoon under "Phase 5, the live items"). B25 (D111-D113:
   the live profiles' mechanics, the envelope file, the confidence threshold 0.6) was confirmed by the owner on
   2026-10-09. Open for the owner: the review of the committed scenario C. Open from the review, not fixed: the table-unaware personal rule (L2 F10, a documented
   over-masking), a row mask whose effect depends on the value (not found by the start probe), a verb that compiles with
   none of the probe argument sets (offered as is), key nullability of allow-listed tables (unchecked); and gap 49 (the
   item query's missing induction argument; not taken in Phase 6, D193). Worth a re-measure later: the model's self-reported confidence at other
   efforts (D113 rests on four answers), and the session's strict tools once the provider's union limit moves.
4. **The display-test focus flake is explained and mitigated** (2026-10-08, A39; "Open flake" under "Status at a
   glance"). Still open there: the native reason why the Java 8 toolkit thread does not flush an EDT-issued focus request
   while idle, and the garbage-collection-dependent skip of `SwingDisplayTest.refBecomesStaleOnceTheComponentIsCollected`
   (3 of 60 runs on 2026-10-08, once under emulation on 2026-10-03).
5. **Phase 6 is built, reviewed, fixed and security-reviewed twice** (2026-10-09; six packages, four review fixers, six
   security fixers; `design/phase6-contracts.md` as built; B26 and B27 confirmed and B28 signed off by the owner on
   2026-10-10). In this order: (a) B26, B27 and B28 for the owner: **done 2026-10-10** (`B26 ok, B27 ok, B28 ok`, no veto;
   S52, D210 and the five behaviour changes stand, the eight lows are accepted, S84 beside S96 stands as it is); (b) **the push**, on the owner's word; (c) the Windows host, when it exists: the §9.5 list, the exit criterion that
   names Windows and the host items the fix rounds added (`skillroy.bat` with a decoy `findstr.cmd`, the planner through
   `claude.exe` on stdin, localised principals, FAT32 and exFAT); (d) **the live check of the planner's stdin route** (P5
   §15.3) with the key, a repair round through `--resume` included: **done 2026-10-10** (natively on macOS, `claude`
   2.1.286, USD 2.05; the last row of the live table at the top); (e) Phase 7 (item 6).
   Open from the packages and the fix rounds: the amd64 image legs of the hooks module and the home's ITs; a real
   OpenTelemetry Collector (§13.4); gaps 53, 54, 58 and 59 (measurements and spans beyond the root) and 60-75 (gaps 76-102 belong to Phase 7); the
   code-review questions of D163 and D168; S46 item 4.
6. **Phase 7 is specified, reviewed, built, code-reviewed, security-reviewed (delta) and fixed** (2026-10-10 and 2026-10-11,
   `design/phase7-contracts.md` as built; B29 and B30 confirmed and B31 signed off by the owner on 2026-10-11) in four packages (VS, VW, VC, VM), a fix wave
   of three (FA, FB, FC), a race fix (FX) and a security fix round of four (SG, SH, SI, SJ), merged into `main` up to `c94aade`;
   nothing pushed. In this order: (a) the Linux matrix of the final tree: green on `c94aade` (arm64 twice on every leg, amd64 on the Java 25 leg, the `jcef`
   profile once, the Robot leg once per Java), whose amd64 Java 25 leg settled the render key's equality across architectures;
   **the two amd64 Java 8 legs ran green on `bc1e250`** later on 2026-10-11 (after two test-side fixes, `aadd5f4` and `d6720ea`),
   `keypad-image`'s Java 8 key holding on amd64, and the Exit item is ticked (**Phase 7 is complete**); (b) for the owner: **done 2026-10-11** (`B29 ok, B30 ok, B31
   ok`, no veto; the built behaviour and the behaviour changes of B30 stand, S142 and S144 are accepted), the two optional
   choices of gaps 93 and 96 excepted; (c) **the push**, on the owner's word; (d) the Windows and macOS hosts, when they
   exist: the §9.5 list of Phase 6, the exit criterion that names Windows and the Phase 7 items (the minimised-frame and
   off-screen refusals, the ACL judgement of the output directory, S142, S144, Robot with the Accessibility permission).
   Open from the build and the fixes: gaps 88-102 (among them 95, the web-host checks that would exercise a masked browser on real
   JCEF, and 101, the Robot covering rule over a dialog's owner; 92, 94 and 99 were closed on 2026-10-11, B32), and from the
   contract gaps 77-87 and the "not verified" list of §13.4 and of the delta
   review (Windows, Wayland, HiDPI, a person's desktop with another process's window over the target, JxBrowser input (verified
   against 8.18.3 on macOS, Retina included, 2026-10-11), windowed
   JCEF on a Java 8 target, a real masked browser, a second OS user).
