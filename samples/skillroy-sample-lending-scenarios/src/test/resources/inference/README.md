# Recorded model answers of the sample

This directory holds the recordings of the recorded gateway (`phase5-contracts.md` §4.2, §14.5): the model's answers
that let the sample's AI-layer scenarios and checks run in the build, in CI and in the Linux image with no model key
and no model call. `inference.example.yaml` in the module directory names it as its `recordings`.

**Nothing is recorded yet.** Recordings need a live call with the model key, and are made in a later wave of the
Phase 5 build; until then this file is the only one here, and scenario D (`supervisor-return-llm-sql`) has no
bindings file (its bindings are written from these recordings).

What will go here:

- the tier (b) request of scenario D's supervisor slot, on seed A and on seed B (§14.2), from which `approve` writes
  scenario D's bindings files;
- the requests of the interactive session that is exported as `session-basic-loan` (§14.4);
- the triage of the two negative runs of the end-to-end resolution checks that end `FAIL` and `ERROR` (§11, §14.5);
- the planner's envelope for scenario C, `return-refunds-deposit` (§14.3), which the build replays with a substitute
  for the `claude` command.

How a recording works:

- One file `<fingerprint>.json` per request: the fingerprint is that of the request the code builds (the model class,
  the effort, the system parts, the messages, the tools and the output schema; not the token limit, the cache hint or
  the labels), so the same request finds the same file on any machine.
- A request with no file fails loudly (`NO_RECORDING`, reported as an environment failure naming the slot), so that a
  changed prompt shows up as a failing build and a reviewable file diff, never as a different answer.
- Every file passes the prompt mask on its way to disk: no credential, no secret column, no personal value. The files
  are canonical JSON with sorted keys, byte-stable, and reviewed like any fixture.
- They are made once, live, by a maintainer with the key, with the Maven profile `inference-record` in the Linux image
  (`ci/run-in-linux.sh` passes `ANTHROPIC_API_KEY` into the container when it is set, without printing it). An
  existing file is overwritten only on purpose (`-Dskillroy.inference.record.overwrite=true`). CI never records.
