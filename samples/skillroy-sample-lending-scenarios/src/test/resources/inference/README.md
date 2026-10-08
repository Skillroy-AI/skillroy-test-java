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
  recordings of their purpose, write the new ones, then scenario D's bindings files and the session's export from
  them).
- Live, once the key works: the Maven profile `inference-record` in the Linux image (`ci/run-in-linux.sh` passes
  `ANTHROPIC_API_KEY` into the container when it is set, without printing it), with
  `-Dskillroy.inference.record.overwrite=true`. The files then name the provider and the model that answered; no code
  changes. Scenario D's bindings files and the session's export are then written again from the live recordings (the
  statement, its rationale and the model usage in them change), reviewed, and committed with them.

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
