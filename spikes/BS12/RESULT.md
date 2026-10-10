# BS12 result: grounding accuracy and cost of a vision loop on the sample lending desk (2026-10-10, live)

**Verdict: passed.** Measured accuracy, latency and cost now replace the research note's estimates (F3, F4, F14, R6).
On the desk, grounding is a solved problem for Sonnet 5.5 and Opus 5.5 at effort `low`: by reference with marks
(V1-marks) both answered all 135 attempts correctly, by pixel through the computer toolset (V2-toolset) all 135, and
**no pixel answer of any model missed its target by a few pixels**: every wrong click landed squarely on another
component, the wrong one by meaning (an icon read as another icon, "the prompt line" read as the entry field). The
errors that remain are semantic and systematic (the same wrong answer on every repetition), not geometric noise. In
short loops all 25 Sonnet and Opus sessions finished their task. V1 needed 4 to 5 UI actions (the minimum is 3 to 4);
V2 needed 6 to 14, because the desk's keypad keys are icons without text and the model has to find out which is DUE.
A one-paragraph legend in the system text (what a domain pack could supply) brought V2 down to 4 to 5 actions. A
session cost USD 0.022-0.063 on Sonnet and Opus (Haiku 5.5: USD 0.0013-0.0022), and one model call took 1.0-1.5 s
(median) on Sonnet and Haiku, about 2 s on Opus. **Proposal: V1 with marks on a `PAINT` capture at the window's own
size, Sonnet 5.5 at effort `low`, by default; V2 through the toolset as the opt-in "clicks like a human" form;
Haiku 5.5 only for V1; a per-session ceiling of USD 1.00 kept, with 30 actions as the binding limit.** Two findings
change built code: a window's `PAINT` capture on the X toolkit prints a substitute title bar over the content, and the
project's text snapshot drops table rows and unnamed components.

Total spend **USD 5.64** of the USD 10.00 cap (method and split at the end).

## How it was run

- `spikes/BS12/` (a standalone Maven project; `README.md`), the harness on Temurin 25 and the desk (seed A, the agent on
  the command line, launched with the real `TargetLauncher` and `AgentClient`) on Temurin **1.8.0_504** (`/opt/jdk8`), both
  in `skillroy-test-java-ci:bs8-arm64` under the WM-less Xvfb (1024x768x24), through `ci/run-in-linux.sh`, the key passed
  by name. Built artifacts of `main` (4f9962f) by path; Maven offline; nothing downloaded.
- SDK `com.anthropic:anthropic-java` **2.70.0** (the CLI distribution's). Request bodies built as JSON and handed to the
  SDK as `MessageCreateParams.Body` (read by the SDK's own mapper); responses kept as the SDK's JSON of the `Message`,
  so assistant turns, signed thinking blocks and `toolset_name` replay unchanged. 2.70.0 **does** have typed classes for
  the toolset (`ComputerToolset20260801`, `ToolResultBlockParam.toolsetName`, one `Computer*ToolUseBlock` per member):
  no raw-JSON workaround is needed in the module.
- Models `claude-sonnet-5-5`, `claude-opus-5-5`, `claude-haiku-5-5` (all three accepted, `count_tokens` too). Adaptive
  thinking (the default; never disabled). Main effort `low`; a sample at each model's default (no `output_config`).
- **Observations** come from the built primitives: `swing.windows`, `swing.snapshot`, `app.state`, `swing.capture`
  (`PAINT`). The composite capture is the frame's space: each showing window captured through its `JRootPane` and drawn
  at its offset (the frame is at 0,0 with zero insets under the WM-less Xvfb; the prompt dialogs are separate,
  undecorated windows at 288,287, 448x194). Snapshot bounds are window-relative, so a node's composite position is its
  window's offset plus its bounds; the hit-test is Swing's own rule (the deepest showing child, lower index on top,
  topmost window first), table rows at the Metal default of 16 px (checked against the capture). The probe checked that
  the centre of every target hit-tests to its own ground truth (27 of 27).
- **Grounding**: 27 targets on five screens (`Targets.java`): 10 icon-only keypad keys, 4 painted prompt buttons, 3 text
  fields, 4 table rows, 2 table parts (the empty table, the column header), 4 labels; 5 of them deliberately ambiguous
  or dense ("the second line in the loan table", "the line of the item with the highest deposit", "the key that takes
  the deposit" next to the DUE key, "the column headings", "the Cancel button" with a CANCEL key on screen). The desk has
  no combo box and no menu, so neither was measured. Repetitions at `low`: three for Sonnet and Haiku, two for Opus;
  the default-effort sample: nine targets, once. Up to six requests in flight.
- **Sessions**: `lend` (one Garden spade, exact deposit) and `ladder` (one Extension ladder: the safety-induction
  prompt, then the exact deposit), from a signed-on desk (the harness signs on as the person's verb did in Phase 5; the
  model never sees a password). Success is read from the desk's own facts (`lending.loan`, `lending.state`).
- Every call is a record in `results/*.jsonl` (model, effort, variant, target, answer, correct, latency, tokens by
  kind, cost, request id); `results/summary.json` aggregates them; `target/summary.md` holds every table.

## Accuracy (effort `low`, correct / attempts)

| Variant | Sonnet 5.5 | Opus 5.5 | Haiku 5.5 |
|---|---|---|---|
| V1-text: text snapshot + capture, answer a ref | 78/81 (96 %) | 52/54 (96 %) | 78/81 (96 %) |
| V1-marks: capture with marks + list, answer a mark | **81/81 (100 %)** | **54/54 (100 %)** | 77/81 (95 %) |
| V2-custom: capture, `click(x, y)` | 75/81 (93 %) | **54/54 (100 %)** | 65/81 (80 %); 12 without a tool call, 65/69 of the answered |
| V2-toolset: the computer toolset | **81/81 (100 %)** | **54/54 (100 %)** | 75/81 (93 %) |
| *extra* V1 text snapshot alone (no image) | 23/27 (85 %) | 23/27 (85 %) | 22/27 (81 %) |
| *extra* V2-custom, capture scaled to 0.75 (768x576) | 25/27 (93 %) | - | 22/27 (81 %); 4 without a tool call |
| *extra* V2-custom, capture scaled to 0.5 (512x384) | 23/27 (85 %) | - | 22/27 (81 %); 3 without a tool call |

**At each model's default effort** (Sonnet `high`, Opus and Haiku `medium`; 9 targets once): every variant 9/9 on
Sonnet and Opus; Haiku 9/9 except V2-custom 8/9 (one call written as text). Output tokens hardly moved (per attempt, every Sonnet and Opus
variant within 25 % of its `low` figure, for example Sonnet V2-custom 68 against 81): on single-element questions
adaptive thinking barely engages, so effort changes neither accuracy nor cost here.

By kind (`low`): icon-only keys 159/160 by reference (V1, all three models) and 153/160 by pixel (V2); for Sonnet
and Opus together, painted buttons 80/80, text fields 60/60 and table rows 80/80 (16 px rows, no row confused with its
neighbour) in every variant; the misses are icon keys and labels by pixel (V2-custom) and the column header in V1-text
(see "Failures").

**Repeatability.** Of 324 (model, variant, target) groups with two or three repetitions, 313 gave the identical answer
every time and 10 differed in correctness (9 of them Haiku). Sonnet's and Opus's custom-tool points moved by at most
2 px (L1) between repetitions; toolset points by up to 60 px, always inside the same component.

## Latency (effort `low`, wall clock of one API call from the container)

| Variant | Sonnet 5.5 median / p90 | Opus 5.5 median / p90 | Haiku 5.5 median / p90 |
|---|---|---|---|
| V1-text | 1.19 s / 1.42 s | 1.94 s / 2.46 s | 1.09 s / 1.64 s |
| V1-marks | 1.43 s / 1.81 s | 1.83 s / 2.48 s | 1.02 s / 1.41 s |
| V2-custom | 1.24 s / 1.39 s | 2.01 s / 3.05 s | 1.15 s / 1.65 s |
| V2-toolset, per call | 1.49 s / 1.77 s | 1.96 s / 3.41 s | 0.99 s / 1.36 s |
| V2-toolset, per grounding (screenshot + click: two calls) | 2.97 s / 3.73 s | 4.27 s / 6.38 s | 2.00 s / 2.93 s |

The slowest single call: Opus 11.9 s (one outlier among 216); Sonnet 4.1 s; Haiku 3.3 s. In the sessions, one model
turn took 1.18 s (V1) and 1.48 s (V2) median on Sonnet, 1.97 s and 2.27 s on Opus, 0.95 s and 1.38 s on Haiku. The
desk side of a step: the composite capture with snapshot and `app.state` about 60-110 ms (the first of a desk about
180 ms), an action through the rungs 3-20 ms (up to 120 ms for a batch), the wait 105-125 ms after an action the desk
answers (its own `input.requested` or `prompt.waiting`, then `swing.settle`), and **3.1 s** after an input the desk
drops silently (a key that is not valid in the state publishes nothing, so the bounded wait runs out).

## Cost and tokens

| | Sonnet 5.5 | Opus 5.5 | Haiku 5.5 |
|---|---|---|---|
| One 1024x768 capture (`count_tokens`: 1,039 tokens, all three models) as fresh input | USD 0.0021 | USD 0.0042 | USD 0.0001 |
| The same from the cache | USD 0.0002 | USD 0.0002 | USD 0.00001 |
| V1-text, per attempt (2,157 input + ~53 output) | USD 0.0048 | USD 0.0097 | USD 0.0003 |
| V1-marks (1,892 input + ~50) | USD 0.0043 | USD 0.0086 | USD 0.0002 |
| V2-custom (1,576 input + ~70) | USD 0.0038 | USD 0.0078 | USD 0.0002 |
| V2-toolset, two calls (1,312 fresh + 8,994 from the cache + ~155) | USD 0.0061 | USD 0.0104 | USD 0.0003 |

Fixed parts (`count_tokens`): the toolset's declaration 4,522 tokens (4,111 with `zoom` disabled), the text snapshot of
a desk screen 500-640 tokens (most of it the `app.state` JSON line), the marks list 160-440 tokens. The toolset's
declaration plus the system text (4,525 tokens) is cached after the first call and read at USD 0.0009 (Sonnet) per call.

## Sessions

| Variant | Model | Effort | Note | Runs | Done | UI actions mean (min-max) | Turns | Tokens per session (mean) | Wall per task (mean) | s per UI action | USD per session mean (max) | Cache hit ratio |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| V1-text | Sonnet 5.5 | low | | 6 | 6 | 4.5 (4-5) | 5.3 | 32,900 | 7.6 s | 1.7 | 0.032 (0.036) | 0.71 |
| V1-text | Opus 5.5 | low | | 4 | 4 | 4.5 (4-5) | 4.0 | 20,300 | 9.2 s | 2.1 | 0.046 (0.047) | 0.63 |
| V1-text | Haiku 5.5 | low | | 2 | 2 | 3.5 (3-4) | 4.5 | 24,900 | 5.5 s | 1.6 | 0.0014 (0.0015) | 0.66 |
| V2-toolset | Sonnet 5.5 | low | | 6 | 6 | 10.3 (6-14) | 7.7 | 78,600 | 14.1 s | 1.3 | 0.048 (0.063) | 0.86 |
| V2-toolset | Sonnet 5.5 | low | tool results without the hit component | 2 | 2 | 10.5 (10-11) | 8.0 | 82,100 | 14.6 s | 1.4 | 0.047 (0.051) | 0.88 |
| V2-toolset | Sonnet 5.5 | low | with the keypad legend | 2 | 2 | 4.5 (4-5) | 3.5 | 26,900 | 5.8 s | 1.3 | 0.025 (0.027) | 0.72 |
| V2-toolset | Sonnet 5.5 | default (`high`) | | 1 | 1 | 9 | 7 | 67,800 | 11.0 s | 1.2 | 0.040 | 0.89 |
| V2-toolset | Opus 5.5 | low | | 4 | 4 | 6.5 (6-7) | 4.5 | 34,200 | 11.1 s | 1.7 | 0.045 (0.063) | 0.82 |
| V2-toolset | Haiku 5.5 | low | | 2 | **0** | 9.5 (9-10) | 5.5 | 49,400 | 10.6 s | 1.1 | 0.0022 | 0.80 |

"Tokens per session" counts input, output, cache reads and writes; "wall per task" is model, actions, waits and
captures together. Two of the four Opus V2 runs carry a harness defect (a batch ending in `wait` got no screenshot, so
the model spent one turn asking for one; fixed, then both tasks run again, `-r2`); the table includes all four. Every
run is in `target/summary.md` ("Sessions"). Caching as R6 says: one breakpoint on the system text after the tools, one
advancing on the latest tool result; the ratio is lower in V1 because each turn adds a fresh capture and snapshot
(about 1,700 tokens) that is written once and read once.

What the models did:

- **Batching.** In V2 Sonnet put 3 to 6 calls in a turn (click the field, type the code with a trailing newline, `wait`,
  `screenshot`) and ended almost every batch with its own `screenshot`, although the system text said one is attached;
  Opus relied on the attached capture after its first session. V1 used one or two calls a turn. No model ever called
  `zoom` (0 of 828 toolset calls: 350 `left_click`, 333 `screenshot`, 97 `wait`, 44 `type`, 4 `key`), `mouse_move`, `scroll` or a drag; `wait` was called with 1-2 s and is
  answered by the loop's settle, never a sleep.
- **Icon-only keys.** Without the legend, Sonnet (at `low` and at `high`) first typed "DUE" into the entry field
  because the prompt line reads "Scan or enter item, or DUE"; the desk answered "Unknown item", the model dismissed it,
  tried the banknote key (DEPOSIT, dropped in that state), then the sigma key. Opus pressed the sigma key first time.
  Haiku pressed DUE and then stopped, taking "Deposit received" for done (both runs failed one action short). Whether the
  tool result named the component that was hit made no measurable difference (10.5 against 10.3 actions).
- **Stale captures.** Never shown by construction (every capture follows the settle). Within a batch, a click after a
  click that closes a dialog meets a changed set of windows: the strict rule of design §3.10 step 1 ("events changed
  the set of windows: take it again") refused such a click in the first run; the refined rule (take a fresh capture,
  act only if the same component is under the point) let 16 such clicks through and refused none.
- **Refusals and stop reasons.** No refusal in 1,587 calls. `stop_reason` was `tool_use`, or `end_turn` for a
  session's closing sentence and for Haiku's calls written as text.
- **An API rule the loop must follow:** a `tool_result` with `is_error: true` may carry only text (400 "all content must
  be type `text` if `is_error` is true"); after a failed action the fresh capture goes after the results as its own
  blocks.

## Failures, with examples

- **Semantic misreads (all pixel misses).** "The key that leaves return mode" -> SIGN_OFF (power icon) instead of
  RETURN (circular arrow): Sonnet V2-custom 3 of 3, Haiku in 5 of its 12 attempts (V1-marks once, V2 four times);
V2-toolset Sonnet and Opus right every time. "The
  prompt line that tells the staff member what to do next" (deposit screen) -> the entry field: Sonnet V2-custom 3 of 3,
  Haiku in three variants. V1-text never made either mistake (the snapshot names the keys and gives the prompt line's
  text). No wrong click was within 10 px of its target.
- **The project's text snapshot hides things.** `UiSnapshotText` cuts a node's value at 80 characters, so the loan table
  appears as one line without its rows, and it drops nodes of a container role with no name or text, so the table
  header (`JTableHeader`, role `unknown`) is not in it: V1-text answered the table for "the column headings" in 8 of 8
  attempts, and without the image V1 could not find rows by content (the table with row 1 for "the cordless drill", "the
  item with the highest deposit" and "the extension ladder" in 7 attempts, 2 more without a tool call). Marks solved both (a mark per row and one for the header).
- **Haiku writes calls as text.** With the custom `click(x, y)` tool Haiku 5.5 answered in text ("click(567, 443)", the
  right point) instead of a tool call in 20 of 144 calls (13 of 90 at full size, 7 of 54 on scaled captures), and in 2
  of 27 text-only V1 calls; never with the toolset or with an image and `select_component`/`select_mark`.
- **Downscaling hurts icons first.** At half size Sonnet confused the QTY and DEPOSIT keys with DUE; at 0.75 it kept its
  full-size accuracy (25/27 against 25/27 for the same targets) and saved about 450 input tokens a call.
- **Not in the desk:** a combo box, a menu, a small icon smaller than the 160x64 keys. "Small" here meant 16 px table
  rows and a 19 px header, which were found reliably.

## Findings that change code or contracts

1. **`PAINT` of a window prints a substitute title bar.** On the X toolkit (Java 8 here), `printAll` of a frame with
   zero insets (no window manager) paints a fake title bar with the title and two boxes over the top ~20 px of the
   content (`target/probe/signed-off-frame-as-is.png`): the prompt line and the status line were half covered.
   Capturing the frame's `JRootPane` (at its offset) gives the true content. Phase 7 should capture a window as its root
   pane (or document the decoration), and the composite of all showing windows belongs in the capture result (the
   reserved `windows`, `origin`, `space` fields).
2. **The text snapshot for a model needs table rows and unnamed interactive components** (or V1 uses marks, which carry
   both).
3. **`PriceTable` has no `claude-haiku-5-5`** (only `claude-haiku-4-5` at USD 1/5): this spike priced it by hand.
4. **The stale-capture rule of design §3.10 step 1 is too strict for batches**: re-hit-test and act when the same
   component is under the point.

## Proposal for the Phase 7 contract

- **Default tier and model: V1 with marks, Sonnet 5.5, effort `low`.** V1-marks was 100 % on Sonnet and Opus, is the
  cheapest V1 form (1,892 input tokens on this desk), answers with a ref (replayable without a pixel), and covers what
  the text snapshot misses. V1 with the text snapshot is the fallback where marks cannot be drawn. Opus 5.5 adds no
  accuracy on this sample at twice the price and about 0.7 s more per call: keep it for a step that failed twice (R6).
- **V2 through the toolset** is accurate (100 % Sonnet and Opus, no geometric miss) and is the opt-in for "clicks like a
  human"; it needs the pack to describe icon-only controls (a short screen legend in the system text), otherwise it
  takes about twice the actions. Disable `zoom` (`configs: {zoom: {enabled: false}}`, 411 tokens a request) for captures
  up to about 1366 px; it was never used.
- **Haiku 5.5** only for V1, preferably with structured output instead of a tool, and never for V2 sessions.
- **Effort `low`**; the default effort changed neither accuracy nor output tokens on single-element questions and did
  not fix V2's icon confusion. Raise it only on a failed step.
- **Capture**: `PAINT` of the window's root pane at the window's own size, unscaled (1024x768 = 1,039 tokens); scale
  down only when the long edge exceeds about 1920 px, never below 0.75; composite the showing dialogs in the frame's
  space.
- **Marks**: draw them (the reserved `marks` result field: number, ref, row, bounds per mark), one per interactive
  component, icon-like custom component with a name, label with text, table header and visible table row.
- **Per-session budget and limits**: keep USD 1.00 per session and 30 UI actions, add a 20-turn limit. Measured:
  USD 0.02-0.06 for a 4-14-action session; extrapolated with the measured per-turn cost and its growth (each turn re-reads
  the history from the cache and writes the new capture), a 30-turn session costs about USD 0.30 on Sonnet in either
  tier and USD 0.5 (V2) to 0.7 (V1) on Opus, so the action limit binds before the budget.
- **Loop rules**: settle after the batch and before any screenshot, never pause for `wait`; attach the capture to the
  last result unless it is an error result or the batch ended with its own screenshot; wait for the desk's ready event
  with a short bound (an input the application drops publishes nothing: 3 s lost per drop here), or let the pack name
  an event for "input dropped".

What replaces the research note's estimates:

- **F3**: one 1024x768 capture is 1,039 tokens (measured; the formula gave 1,036); the toolset declaration 4,522 (4,111
  without zoom), as published. A grounding answer costs USD 0.004-0.006 on Sonnet, 0.008-0.010 on Opus, 0.0002-0.0003 on
  Haiku 5.5. A short session costs USD 0.02-0.06 (Sonnet or Opus) with 20,000-115,000 tokens, 61-89 % of them cache
  reads; the note's 20-action estimate (USD 0.18 Sonnet with caching) agrees with the per-turn growth measured here.
- **F4**: one model call 1.0-1.5 s median on Sonnet and Haiku, about 2 s on Opus, p90 under 3.5 s; one loop step about
  1.3-2.5 s, of which the desk's side (capture, action, settle) is 0.2-0.3 s.
- **F14**: grounding on a Swing desk at 1024x768: 100 % for Sonnet and Opus with marks or the toolset, 93-100 % by raw
  pixel, 95-96 % by text reference (all misses semantic); short tasks completed 25 of 25 (Sonnet, Opus), with about 2x
  the necessary actions in V2 without a legend and near the minimum in V1.
- **R6**: Sonnet 5.5 stays the default (confirmed), effort `low`; Haiku 5.5 (now on the high-resolution tier and the
  toolset, unlike Haiku 4.5) is usable for V1 only; the per-session ceiling of about USD 1 stands.

## Not verified

The amd64 image, Windows, macOS; Java 25 or other look-and-feels and fonts on the desk; screens larger than
1024x768 or HiDPI; V3 (Robot); embedded web views; combo boxes, menus, scroll bars and small icons; tasks longer than
14 actions; sessions in which the application changes on its own between capture and action. Session counts are small
(2 to 6 per cell) and the targets few (27 on one application); requests ran up to six at a time, which may have raised
the grounding latencies slightly.

## Spend

USD **5.6438**, the sum over every record of usage x price per call (input, output, cache reads, five-minute cache
writes at 1.25x input), Sonnet 5.5 and Opus 5.5 from the project's `PriceTable`, Haiku 5.5 at USD 0.10 / 0.50 per
million (cache reads 0.01): grounding at `low` (four variants, three repetitions of Sonnet and Haiku, two of Opus)
3.6027; the default-effort sample 0.5444; the extras (no image, scaled captures) 0.3833; the smoke run 0.0503;
sessions 1.0631 (Sonnet 0.6910 with an aborted first attempt of 0.0322, Opus 0.3650 with the two defective runs of
0.1048, Haiku 0.0071); token counts 0.
