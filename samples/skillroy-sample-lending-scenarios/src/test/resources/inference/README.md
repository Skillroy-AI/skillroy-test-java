# Recorded model answers of the sample

This directory holds the recordings of the recorded gateway (`phase5-contracts.md` §4.2, §14.5): the model's answers
that let the sample's AI-layer scenarios and checks run in the build, in CI and in the Linux image with no model key
and no model call. `inference.example.yaml` in the module directory names it as its `recordings`.

## What is here

### Scenario D's tier (b) requests: recorded live

The tier (b) request of scenario D's supervisor slot (`supervisor-return-llm-sql`, §14.2), on seed A and on seed B:
one recording per seed, of purpose `resolve`.

- **How they were recorded**: on 2026-10-08, by the record route (decision D111): `ScenarioDRecordIT` under the
  profile `-Pinference-record`, with the model key in the environment. It bound scenario D in `approve` without reuse
  on each seed through the live gateway wrapped in a `RecordingGateway`, against the sample's database in the test JVM
  (no display), and wrote both seeds' committed bindings files (`bindings/seed-a/`, `bindings/seed-b/`) from the new
  recordings through the recorded gateway, as `approve` writes them, with `approval.by` `recorded 2026-10-08`.
- **What they hold**: both requests were answered by `claude-sonnet-5-5` at effort `low`. A recording's usage names
  `provider: "claude"` with the provider's request id, the tokens, the cache split and the cost (seed A: 475 tokens in,
  877 out, a 5024-token cache write, USD 0.022280; seed B, a few seconds later: 476 in, 515 out, 5024 read from the
  cache, USD 0.007107), and its `recordedAt` is the time of the call. Seed A's answer carries an `opaque` part before
  the structured answer (a thinking block with empty text and its signature, kept as it came). The structured answer
  has confidence 0.8 (the settings' threshold exactly), a rationale in the model's words, and one `SELECT … ORDER BY
  s.code` over `staff`, `staff_role`, `role_permission` and `role_cap` with four `?` parameters, which the guard bounds
  with `LIMIT 200`. The bindings files carry the label, the model, the request id and `costUsd: "0"` (they are written
  through the recorded gateway), and on each seed the supervisor the committed SQL of scenario A finds.
- **What pins them**: what any live answer must hold, and nothing a model may word differently
  (`ScenarioD.assertLiveRecording` and `ScenarioD.assertLiveAnswer`). `ScenarioDRecordedTest` (the recordings, and
  `approve` through them) and `BindingsUpToDateTest` (the committed bindings files) assert provider `claude` and a
  model of the Sonnet line, a confidence at or above the settings' threshold, a rationale, one guarded statement (a
  `SELECT`, or a `WITH` before one, that reads `staff` and ends with `LIMIT <n>` for an n of at most 200: the guard
  injects 200, or keeps a smaller limit the model wrote), the value loader's statement in the provenance, and above all
  the supervisor scenario A finds on the same seed; the record route checks a new answer the same way before it
  commits it. The label is read from the committed files themselves (`ScenarioD.approvedBy`), here and in the image's
  checks (`InferenceChecks`), so that a later re-record changes the files and not the tests.

### The interactive session's calls: scripted stand-ins

The five model calls of the interactive session exported as `session-basic-loan` (§14.4), made on the sample desk in
the test JVM (`HeadlessDesk`, whose fixed clock makes every request the same on every run); the export's YAML, plan and
bindings file are committed under `scenarios/exported/`.

These are scripted stand-ins: each was made by a `RecordingGateway` around a `ScriptedGateway` whose answers were
written by hand to be correct (`ScriptedAnswers.sessionScript()` in the module's tests). A stand-in has the real format
and is keyed by the real request's fingerprint, and says what it is: its usage names `provider: "scripted"` and the
model `scripted-model`, at a cost of `"0"`. They stay stand-ins whatever is recorded live: a live session is a new
session, with its own answers and actions, not a refresh of the recorded one. `SessionExportTest` pins them, and the
image's session check (`InferenceChecks`, §15.2 item 4) drives the same script by position and compares its export
with the committed copies.

## What is not here, and why

- The triage of failed runs (§11, §14.5): a triage request carries the failed run's own records (durations, event
  times, the desk's business date), so no committed file can answer a run made later; the end-to-end check records the
  triage of its own failed runs with the scripted answers and replays them through the recorded gateway.
- The planner's answers (§10.2, §14.3): the planner runs the Claude Code CLI, not a model gateway, so what it answered
  is never a recording of this directory; the envelope of scenario C's authoring is the planner's own fixture (§14.3).

## Recording and regenerating them

- **Live: the record route.** `mvn -o verify -pl samples/skillroy-sample-lending-scenarios -am -Pinference-record`
  with `ANTHROPIC_API_KEY` in the environment runs `ScenarioDRecordIT` natively (no display: it binds through the
  controller-side JDBC channel). The profile sets `skillroy.inference.record=true` and
  `skillroy.inference.record.overwrite=true` for the test JVMs; without it, or without the key, the class reports itself
  skipped ("the profile inference-record is not active", "no model key"), never failed, and calls no model. It records
  both seeds into a temporary directory, writes both bindings files there from the new recordings with the label
  `recorded <date>` (the UTC date of the run), checks every answer and file as the unit tests pin them and scans them
  for secrets (both seeds' staff passwords, the desk's PIN, the read-only account's password); only when all of that
  passed does it replace the module's recordings of purpose `resolve` and both committed bindings files, so that a
  failure on either seed leaves them as they were. A re-record changes the statement, the rationale, the usage, the
  request ids and the label, so review the diff before committing. It spends money: one request per seed on the Sonnet
  class (USD 0.029 on 2026-10-08). The same
  route works by hand without the profile: `record: true` and `recordings:` naming this directory in an
  `inference.yaml` of `gateway: claude` wrap the live gateway in a `RecordingGateway`, and
  `-Dskillroy.inference.record.overwrite=true` (for the CLI, in `SKILLROY_JAVA_OPTS`) lets it replace an existing file.
  Re-record after any change of scenario D's request (its prompt, its catalogue, its schema): the recorded gateway then
  answers "no recording".
- **The fixture update path.** `mvn -o verify -pl samples/skillroy-sample-lending-scenarios -am
  -Dskillroy.inference.fixtures.update=true` regenerates only what stand-ins still make: the session's recordings and
  its export (`SessionExportTest`, from `ScriptedAnswers`); and it rewrites scenario D's bindings files from the
  recordings that exist, through the recorded gateway, with the label each file holds (`ScenarioDRecordedTest`). It
  records no tier (b) request (the generator of the earlier stand-ins is gone), so it can never replace a live
  recording with a scripted one: use it after a change of the bindings format or of the session's script.

## How a recording works

- One file `<fingerprint>.json` per request: the fingerprint is that of the request the code builds (the model class,
  the effort, the system parts, the messages, the tools and the output schema; not the token limit, the cache hint or
  the labels), so the same request finds the same file on any machine.
- A request with no file fails loudly (`NO_RECORDING`, reported as an environment failure naming the slot), so that a
  changed prompt shows up as a failing build and a reviewable file diff, never as a different answer.
- The recorded gateway replays the recorded answer verbatim and marks the recorded usage `provider: "recorded"` at a
  cost of `"0"`: a run against recordings calls no model and costs nothing, while the recording itself keeps what the
  live call reported.
- Every file passes the prompt mask on its way to disk: no credential, no secret column, no personal value. The files
  are canonical JSON with sorted keys, byte-stable, and reviewed like any fixture.
- An existing file is overwritten only on purpose (`-Dskillroy.inference.record.overwrite=true`, which the profile
  `inference-record` sets). CI never records.
