# Recorded model answers of the sample

This directory holds the recordings of the recorded gateway (`phase5-contracts.md` §4.2, §14.5): the model's answers
that let the sample's AI-layer scenarios and checks run in the build, in CI and in the Linux image with no model key
and no model call. `inference.example.yaml` in the module directory names it as its `recordings`.

## These recordings are scripted stand-ins

No live call with the model key could be made when they were written (2026-10-08). So that the whole tier (b) and
session paths still run for real (the prompt catalogue, the request, the guard, the value loader, the verifier, the
approval, the bindings file, the replay), each recording was made by a `RecordingGateway` around a `ScriptedGateway`
whose answers were written by hand to be correct (`ScriptedAnswers` in the module's tests). A stand-in has the real
format and is keyed by the real request's fingerprint, and says what it is: its usage names `provider: "scripted"`
and the model `scripted-model`, at a cost of `"0"`.

What is here:

- the tier (b) request of scenario D's supervisor slot (`supervisor-return-llm-sql`, §14.2), on seed A and on seed B:
  one `SELECT` over the allow-listed tables that finds the staff members who may authorise the return, selecting the
  identity column `code` that the pack's value loader builds the `StaffRef` from. Scenario D's bindings files
  (`bindings/seed-a/`, `bindings/seed-b/`) are written from these by `approve` against the recorded gateway, with
  `approval.by` `recorded 2026-10-08 (scripted stand-in)`;
- the five model calls of the interactive session exported as `session-basic-loan` (§14.4), run on the sample desk
  in the test JVM (`HeadlessDesk`, whose fixed clock makes every request the same on every run); the export's YAML,
  plan and bindings file are committed under `scenarios/exported/`.

What is not here, and why:

- the triage of failed runs (§11, §14.5): a triage request carries the failed run's own records (durations, event
  times, the desk's business date), so no committed file can answer a run made later; the end-to-end check records
  the triage of its own failed runs with the scripted answers and replays them through the recorded gateway;
- the planner's envelope of scenario C (§14.3): the planner needs a live model, so scenario C is not authored yet.

## Regenerating them

- From the scripted stand-ins: `mvn -o verify -pl samples/skillroy-sample-lending-scenarios -am
  -Dskillroy.inference.fixtures.update=true` (`ScenarioDRecordedTest` and `SessionExportTest` remove the old
  recordings of their purpose, write the new ones from `ScriptedAnswers`, then scenario D's bindings files and the
  session's export from them). This path always records the stand-ins: run after a live re-record, it would replace
  the live recordings.
- Live: not built yet. The Maven profiles `inference-record` and `inference-live` of the contract (§2.3, §14.5, §15.3)
  are specified, not built (decision D110): nothing live can run until the key's workspace id exists (`docs/TODO.md`,
  "Walls"), and the profiles, the live tests and the record route are built in the same change as their first live
  run. The recording route that exists is `record: true` in an `inference.yaml` with `gateway: claude` and
  `recordings:` naming this directory: the live gateway is then wrapped in a `RecordingGateway` (`Gateways.build`), and
  `-Dskillroy.inference.record.overwrite=true` (for the CLI, in `SKILLROY_JAVA_OPTS`) lets it replace an existing file.
  Scenario D bound with `approve` on each seed through such settings records its supervisor request live.
- A live re-record of scenario D's tier (b) requests changes the provider, the model, the statement, the rationale and
  the usage in the recordings, and in the bindings files written from them by `approve` through the recorded gateway
  (as the second half of the update path above does; its first half must not run then), whose `approval.by` label
  changes too. The assertions that pin the stand-ins must be loosened in that change: `ScenarioDRecordedTest.java:253` (the recording's provider `scripted`), `:260`
  (confidence 0.9), `:264` (the stand-in's rationale), `:267` (the stand-in's statement), `:443-444` (every committed
  recording a stand-in made at `RECORDED_AT`); `BindingsUpToDateTest.java:164-169` (the label, the rationale, the
  model `scripted-model`, confidence 0.9); and the label itself, `ScriptedAnswers.APPROVED_BY`, which
  `ScenarioDRecordedTest` and items 2 and 7 of `InferenceChecks` reproduce.
- The session's recordings are not refreshed by a live session: a live session is a new session, with its own answers
  and actions, so the session's stand-ins stay scripted. `SessionExportTest.java:110-111` (the answer and the tools),
  `:116-117` (five calls), `:134` and `:156` (the model `scripted-model` in the journal and the export) and `:184` (the
  recordings are stand-ins) pin them, and the image's session check (`InferenceChecks.java:333`, §15.2 item 4) drives
  the same script by position and compares its export with the committed copies.

## How a recording works

- One file `<fingerprint>.json` per request: the fingerprint is that of the request the code builds (the model class,
  the effort, the system parts, the messages, the tools and the output schema; not the token limit, the cache hint or
  the labels), so the same request finds the same file on any machine.
- A request with no file fails loudly (`NO_RECORDING`, reported as an environment failure naming the slot), so that a
  changed prompt shows up as a failing build and a reviewable file diff, never as a different answer.
- Every file passes the prompt mask on its way to disk: no credential, no secret column, no personal value. The files
  are canonical JSON with sorted keys, byte-stable, and reviewed like any fixture.
- An existing file is overwritten only on purpose (`-Dskillroy.inference.record.overwrite=true`, or the update
  property above). CI never records.
