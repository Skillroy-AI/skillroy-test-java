# BS12: grounding accuracy and cost of a vision loop on the sample lending desk

**Live only.** Every command except `probe`, `summary` and `spend` calls the Claude API and costs money. Nothing here
runs in CI, no test needs the key, and the spike is not a module of the root reactor. The findings are in `RESULT.md`;
the per-call records are in `results/`.

## What it measures

- **Single-shot grounding**: 27 targets over five desk screens (signed off; a loan with five lines; the safety-induction
  prompt over the loan; the deposit step; return mode with the supervisor prompt), each a natural-language description
  of one element with a ground truth read from the snapshot (a component ref, and for a table row the row). Variants:
  `v1-text` (text snapshot + capture, answer `select_component(ref, row?)`), `v1-marks` (capture with numbered marks +
  the list of marks, answer `select_mark(n)`), `v2-custom` (capture only, answer `click(x, y)`), `v2-toolset` (the
  computer toolset `computer_toolset_20260801`: intent first, the model asks for a `screenshot`, then clicks). Extras:
  `v1-text-noimage` (the text snapshot alone) and `v2-custom-s75` / `v2-custom-s50` (the capture scaled to 0.75 / 0.5,
  the point scaled back before the hit-test).
- **Short sessions**: two tasks end to end as a loop (capture -> model -> act -> wait for the desk -> capture), with the
  toolset (V2: the harness hit-tests the model's point and acts on the component through the existing rungs, never
  Robot) or with custom tools that name refs (V1).

## Layout

| Path | What it is |
|---|---|
| `pom.xml` | the harness: Java 25; compiles against the controller jars of the built CLI distribution (system scope, by path) and `anthropic-java` 2.70.0 (the version the distribution carries) |
| `src/main/java/.../bs12/Bs12.java` | commands, output writing (every file is checked against the key first), the ledger |
| `.../Desk.java` | the desk (seed A) with the agent on the command line, launched with the real `TargetLauncher` / `AgentClient` |
| `.../Screen.java` | the composite capture (each window's `JRootPane` through `swing.capture` `PAINT`, drawn at its offset in the frame), the snapshot in composite coordinates, the hit-test, the marks |
| `.../Targets.java` | the 27 targets and how their ground truth is found |
| `.../Model.java` | the Messages API through the SDK (JSON bodies as `MessageCreateParams.Body`), prices, the budget guard |
| `.../Grounding.java` | the screens, the variants, the runner, the free token counts, the probe |
| `.../Session.java` | the loop |
| `.../Summary.java` | the tables of `RESULT.md` (`target/summary.md`, `results/summary.json`) |
| `run.sh` | runs a command in the arm64 Linux image under the WM-less Xvfb |
| `results/*.jsonl` | one record per grounding attempt, per session turn and per session; `results/summary.json` |

## Run

```bash
mvn -o -f spikes/BS12/pom.xml package       # never mvn install
spikes/BS12/run.sh prepare                    # copies the CLI's lib/, the agent home and the desk's jars into target/
export SKILLROY_JDK8_HOME=/path/to/linux-arm64-jdk8   # optional: the desk then runs on Java 8 (/opt/jdk8)
spikes/BS12/run.sh probe                      # no model call: target/probe/<screen>.png, -marks.png, .txt
zsh -lc 'spikes/BS12/run.sh count'            # free: count_tokens of a capture, the toolset, the variants
zsh -lc 'spikes/BS12/run.sh ground --models sonnet,opus,haiku --haiku-variants v1-text,v1-marks,v2-custom,v2-toolset \
          --effort low --reps 1 --rep-start 1 --threads 6 --tag low-rep1 --max-usd 4.00'
zsh -lc 'spikes/BS12/run.sh ground --models sonnet,opus,haiku --effort default --targets sample --tag default-rep1'
zsh -lc 'spikes/BS12/run.sh session --task lend|ladder --variant v2-toolset|v1-text --model sonnet|opus|haiku \
          [--effort low|default] [--hint legend] [--feedback none] --tag <name>'
spikes/BS12/run.sh summary                    # host-side, no network
spikes/BS12/run.sh spend
```

`run.sh` defaults to `SKILLROY_PLATFORM=linux/arm64` and `SKILLROY_CI_IMAGE=skillroy-test-java-ci:bs8-arm64`.

**The key.** `ANTHROPIC_API_KEY` comes from the owner's login shell (`zsh -lc`) and reaches the container by name only
through `ci/run-in-linux.sh`; the SDK reads it from the environment (`AnthropicOkHttpClient.builder().fromEnv()`). It is
never on a command line, never printed and never written: every file the harness writes is compared with the key's
value first (`Bs12.write`). An `ANTHROPIC_WORKSPACE_ID`, when set, is sent as the `anthropic-workspace-id` header and
checked the same way.

**The budget.** `Bs12.HARD_CAP` is USD 10.00 for everything recorded in `results/*.jsonl`. Before each call the
harness reserves the worst case (an estimate of the input at the cache-write price plus `max_tokens` of output) and
refuses the call if the recorded total plus the reservations in flight could cross the cap, or the run's `--max-usd`.
A session also stops at its own `--session-usd` (default 1.00), 30 UI actions and 40 model turns. Costs are usage x
price per call: Sonnet 5.5 and Opus 5.5 from the project's `PriceTable`, Haiku 5.5 (absent from that table) at
USD 0.10 / 0.50 per million, cache reads 0.1x, five-minute cache writes 1.25x.

**The model's own text** (what it writes beside its tool calls) is not kept in `results/`; it goes to the git-ignored
`target/model-text.jsonl`, and the records keep its length.
