# Vision input and "clicks like a human": a bolt-on, and the seams the base reserves for it

Research note, 2026-10-02. Topic: what it takes for the base framework to be *able* to support vision-driven
steps later ("the model looks at the screen and clicks like a person"), as a bolt-on module, without weakening
the rule that CI replays committed plans with zero model calls. It covers the Claude computer-use tool and its
alternatives, the ways to inject pointer and keyboard input into a Swing target, the ways to capture what a
person sees, how a vision-driven step is recorded once into a deterministic step, and the names the base should
reserve now. Model facts, prices and API details are as of the research date.

Two uses are in scope:

- **CI**: `replay` only, no inference (design §3.4, §6).
- **Interactive**: a person, or an assistant overlay inside the target application, drives a live target.
  Inference at run time is allowed there.

Base decisions this note informs: B6 (CI and LLM policy), B7 (model access), B9 (embedded-browser scope), B12
(input fidelity), B13 (safety defaults). It does not reopen design §10: Robot, OS tools and computer use stay
rejected as the *primary* driver.

Confidence values: *verified* (read in a primary source on the research date), *likely*, *uncertain*. "Read" is
not "run": nothing in this note was executed against a target. Every behaviour that still needs a run is marked
**not executed** and collected in the last section. Anthropic documentation pages were read through a fetch tool
that returns the page as text; where a section could not be retrieved verbatim the item says so. JDK file:line
references are into the OpenJDK 25.0.1 source (`src.zip`); the JDK 8 line of the same classes was not re-read
for this note. Ids are stable.

## Summary

The Claude computer-use tool is now a GA toolset, `computer_toolset_20260801`, with no beta header on the Claude
API and Google Cloud. It has 17 member tools, takes no display size, and works in the pixel space of the
screenshots the client returns. It is a client-side tool: the model only asks; the framework captures and acts.
That fits the architecture, because the controller can map every member tool onto agent primitives. A screenshot
costs `ceil(w/28) x ceil(h/28)` input tokens (1,036 at 1024x768, 2,691 at 1920x1080), the toolset definition
about 4,500 more, and each action or batch of actions is one model round trip. For a yes/no or "where is X"
question a plain image block with structured output is enough and costs about one cent or less.

For a Swing target there are three ways to "click like a human", and they prove different things:

- `java.awt.Robot` (and OS tools such as xdotool or `SendInput`) inject into the operating system's input
  queue. They prove the whole path a person uses, including focus, stacking order and native surfaces. They
  need an interactive, unlocked, non-headless session, move the real pointer, and cannot run in several places
  where the rest of the framework can.
- A synthetic mouse event handed to the *window* inside the JVM is routed by AWT's own lightweight dispatcher to
  the component under that point. It proves hit-testing and listener wiring at real coordinates and needs no
  display session beyond what the application already has. It proves nothing about what is actually on screen.
- The existing rungs (`ACTION`, `COMPONENT`, `DISPATCH`) address a component, not a pixel.

Capture has the same split: `PAINT` (`printAll` in the JVM) is deterministic and works when obscured or locked
but shows only what Swing paints; `ROBOT` shows true screen pixels including heavyweight and foreign windows.

The recommended architecture keeps the model in the controller and adds three small agent primitives: a richer
capture result, a read-only **hit-test** (pixel to component reference and locator), and a coordinate-addressed
**pointer**. With the hit-test, a vision-driven click made once (interactive or authoring) is turned into an
ordinary step (component locator plus rung), so CI replay needs no model. This is the same "AI proposes once,
deterministic code replays" pattern as the resolver layer (ai-inference, Summary), and shipping tools do it for
web pages (F13). Where a pixel cannot be mapped to a component (canvas-painted or native UI) the options are,
in order: a pack-supplied region resolver, pixel-anchored replay guarded by a deterministic image comparison,
or marking the scenario live-only.

The base should reserve names now (R8) and build nothing: two primitive names, a few capability ids, result
fields on `swing.capture`, a content model for the inference gateway that can carry images, a handful of
scenario keys, and one agent SPI. The loop, the prompts, the toolset adapter, image comparison and everything
Robot-specific beyond the already reserved `ROBOT` values stay out of the base.

## Findings

### A. The Claude side

### F0: The computer-use tool is a GA toolset; the older tool versions are rejected by the 5.5 models

- Current form: one `tools` entry `{"type": "computer_toolset_20260801"}`. No `name`, no `display_width_px` /
  `display_height_px` / `display_number`, no `enable_zoom`; those parameters are rejected. Optional `configs`
  switches members on or off (`{"zoom": {"enabled": false}}`) and may set `defer_loading` per member;
  `cache_control` is allowed on the entry.
- Status: GA with no beta header on the Claude API and Google Cloud; beta on Amazon Bedrock, Claude Platform on
  AWS and Microsoft Foundry.
- Models listed for the toolset: Fable 5.1, Mythos 5.1, Fable 5, Mythos 5, Opus 5.5, Opus 5, Sonnet 5.5,
  Sonnet 5, Opus 4.8.
- "On the Claude API and Google Cloud, Claude 5.5 and later models support computer use only through the
  `computer_toolset_20260801` toolset and return an error for the earlier `computer_20251124` tool version."
  On Amazon Bedrock, Opus 5.5 and Sonnet 5.5 still accept `computer_20251124`.
- Opus 4.7, Opus 4.6, Sonnet 4.6 and Opus 4.5 support computer use only through `computer_20251124`, which
  needs a beta header. Haiku 4.5 is not listed for the toolset.
- The toolset can share a request with other tools (the vendor quick start combines it with the bash and text
  editor tools), so custom tools that address components by reference can sit beside it.
- It is a client-side tool: the application provides the environment and executes the actions.

Evidence: https://platform.claude.com/docs/en/agents-and-tools/tool-use/computer-use-tool (read 2026-10-02);
Anthropic `claude-api` skill for Claude Code (snapshot 2026-09-25), tool-use concepts and migration guides for
Opus 5.5 and Sonnet 5.5, platform availability table.

Confidence: verified. Two details are from the skill snapshot only and were **not re-read on the docs page**
(its "Earlier tool versions" section could not be retrieved verbatim): the beta header string for
`computer_20251124` is `computer-use-2025-11-24`; `computer_20250124` (Sonnet 4.5, Haiku 4.5) is accepted on no
platform by Sonnet 5.5. Whether the Anthropic Java SDK (2.34.0) has typed builders for the toolset entry was
not checked.

### F1: The action set, and how a call looks

Seventeen members. The action is the `name` of the `tool_use` block (not `input.action` as in the older tool),
and every block and every result carries `"toolset_name": "computer"`.

| Member | Input | Note |
|---|---|---|
| `screenshot` | none | the full display as an image |
| `zoom` | `region: [x0, y0, x1, y1]` | that region "at full resolution", scaled to fit the usual screenshot size |
| `left_click`, `right_click`, `middle_click`, `double_click`, `triple_click` | `coordinate?: [x, y]`, `text?`: modifier keys | without a coordinate: click at the cursor |
| `left_click_drag` | `start_coordinate`, `coordinate`, `text?` | press, drag, release |
| `mouse_move` | `coordinate` | hover |
| `left_mouse_down`, `left_mouse_up` | none | at the current position |
| `cursor_position` | none | answered as text |
| `scroll` | `scroll_direction` (`up`, `down`, `left`, `right`), `scroll_amount`, `coordinate?`, `text?` | |
| `type` | `text` | "at keyboard focus": the tool has no notion of a target component |
| `key` | `text` (`"Return"`, `"ctrl+s"`, `"alt+Tab"`), `repeat?` 1-100 | key names are X keysym style |
| `hold_key` | `text`, `duration` (up to 300 s) | |
| `wait` | `duration` (up to 300 s) | |

- **Batches.** One assistant turn may hold several `tool_use` blocks. They are executed in order, not in
  parallel; execution stops at the first failure; every block gets a `tool_result` in the next user message,
  the failed one with `is_error: true`, the skipped ones with `is_error: true` and the text
  `Not executed: an earlier computer action in this turn failed.`
- Only `screenshot` and `zoom` results need an image; `OK` is enough for the rest. If a batch does not end with
  a screenshot, one may be attached as an extra image block on the last result to save a round trip.
- Vendor guidance for accuracy: instruction text before the screenshot in the content array; ask the model to
  end each group of actions with a screenshot and to state what it shows; prefer keyboard shortcuts for
  awkward widgets (dropdowns, scrollbars).

Evidence: https://platform.claude.com/docs/en/agents-and-tools/tool-use/computer-use-tool ; `claude-api` skill,
Opus 5.5 migration guide, breaking change 4.

Confidence: verified (member table and batch rules as returned by the page; the table was not compared
character by character with the page source).

### F2: Coordinates are screenshot pixels; the client owns all scaling; oversized screenshots are rejected

- All coordinates are in the pixel space of the full screenshots the client returns, origin top-left. A `zoom`
  image does not change the frame.
- "Screenshots and zoom images that you return to the computer use toolset must already fit within your
  model's image size limits: the toolset takes no display dimensions and the API doesn't downscale for you, so
  an oversized `tool_result` image is rejected with a validation error."
- Image limits. High-resolution tier ("Claude 4.7 and later models", which includes every model that takes the
  toolset): long edge 2576 px and 4784 visual tokens. Standard tier (all other models, Haiku 4.5 among them):
  1568 px and 1568 visual tokens. Images are padded to a multiple of 28 px on the bottom and right; the origin
  does not move.
- Outside the toolset, an ordinary `image` block that exceeds the limits is silently downscaled, and returned
  coordinates then refer to the downscaled image. `"transformations": {"oversized_image": "error"}` on the
  image block turns that into a 400. The vendor's advice is to resize before upload so that coordinates map one
  to one.
- More than 20 images in one request puts every image under a stricter per-side limit (stay at or below
  2000 px per side, or at or below 20 images).
- Recommended display sizes: "For general desktop tasks, use 1024x768 or 1280x720; for web applications, use
  1280x800 or 1366x768. Avoid resolutions above 1920x1080 to prevent performance issues." The model migration
  notes call 1080p "a good balance of performance and cost" for the high-resolution models.
- On a HiDPI display, screenshot pixels differ from logical coordinates: divide by the display scale factor.

Implication for a Swing target: a `PAINT` capture of one window is in window user-space pixels (no HiDPI
factor), so a click coordinate from the model is directly a window-relative point as long as the capture is
sent unscaled. A 1024x768 window, the CI geometry, needs no scaling on either tier.

Evidence: https://platform.claude.com/docs/en/agents-and-tools/tool-use/computer-use-tool ;
https://platform.claude.com/docs/en/build-with-claude/vision ;
https://platform.claude.com/docs/en/build-with-claude/vision-coordinates ; `claude-api` skill, migration notes
for Opus 4.7.

Confidence: verified. The scaling code sample still shown on the computer-use page uses the standard-tier
constants (1568 px, 1.15 megapixels), which are stricter than the limits the same page states for the toolset
models; the limits in the bullet list above are the ones the vision page gives.

### F3: Token cost

- **Per image:** `ceil(width / 28) x ceil(height / 28)` visual tokens, billed as input.

  | Capture size | Tokens | Fits the standard tier unscaled |
  |---|---|---|
  | 1024x768 | 1,036 | yes |
  | 1280x720 | 1,196 | yes |
  | 1280x800 | 1,334 | yes |
  | 1366x768 | 1,372 | yes |
  | 1280x1024 | 1,702 | no (resized) |
  | 1920x1080 | 2,691 | no (resized to 1456x819, 1,560 tokens) |

  The 1920x1080 rows are in the vendor's own table; the others are computed here from the formula. The
  computer-use page's "roughly 1,000-1,800 input tokens each" (the figure in ai-inference §F10) holds for the
  recommended sizes, not for 1080p on a high-resolution model.
- **Toolset overhead:** "Declaring `computer_toolset_20260801` with its default members adds about 4,500 input
  tokens to a request", which "covers the member tool definitions and the tool use system prompt"; disabling
  `zoom` removes about 410. The older tool versions cost 466-499 system-prompt tokens plus about 735 per tool
  definition.
- **Prices** (USD per million tokens, input / output / cache read): Opus 5.5 4 / 20 / 0.20; Sonnet 5.5
  2 / 10 / 0.20; Haiku 4.5 1 / 5 / 0.10. Cache writes are 1.25x (5 minutes) or 2x (1 hour) of input. The Batch
  API is 50% off.
- **One screenshot as fresh input:** 1,036 tokens is $0.0021 on Sonnet 5.5 and $0.0041 on Opus 5.5.
- **A loop resends its history.** Estimate for 20 actions with one 1024x768 screenshot each, about 150 tokens
  of other content per step and 200 output tokens per step: about 350,000 input tokens in total. Without
  caching that is about $0.70 on Sonnet 5.5 and $1.40 on Opus 5.5; with a cache breakpoint advanced every turn
  about $0.18 and $0.29.

Evidence: https://platform.claude.com/docs/en/about-claude/pricing (model table, tool use pricing, computer
use tool section) ; https://platform.claude.com/docs/en/build-with-claude/vision (resolution and token cost).

Confidence: verified for the formula, the overhead and the prices. The loop figures are **estimates computed
for this note, not measured**; output tokens per step with adaptive thinking were assumed, and thinking can
multiply them.

### F4: Latency and limits

- The vendor publishes no per-action latency. Each action, or batch of actions, is one Messages API round trip.
  The design already assumes "seconds to tens of seconds" per model call (design §5, ai-inference §C1).
- History: "Long agent loops accumulate screenshots quickly". Vendor guidance is one cache breakpoint after
  system prompt and tool definitions and up to three on recent tool results, and pruning old screenshots in
  batches rather than every turn.
- On Fable 5.1, Opus 5.5 and Sonnet 5.5, removing an earlier screenshot on the client invalidates every later
  thinking block. The vendor says to resize screenshots to at most 2000 px per side and to use server-side tool
  result clearing instead.
- Thinking cannot be disabled on Opus 5.5 (effort is the only control, default `medium`). Sonnet 5.5 accepts
  `thinking: {"type": "between_tools"}` at effort `high` or below as its lowest setting. Forced `tool_choice`
  (`any` or a named tool) is a 400 on both, so "always answer with this tool" has to be a structured output or
  a prompt instruction (ai-inference §F12).
- The published effort guidance for computer use (`high` on Opus 4.7, `medium` on Sonnet 4.6 and Opus 4.6,
  never `max`) is for the older tool on older models. For Sonnet 5.5 the migration guide gives `medium` as the
  starting point for multistep tool use and reports more completed computer-use tasks at `high`.
- The API scans tool results, screenshots included, for prompt injection with classifiers; the vendor still
  asks for a dedicated VM or container, no sensitive data in view, and human confirmation for consequential
  actions.
- Computer use is eligible for zero data retention, subject to the model: Fable 5.1 requires 30-day retention
  (design §5 already excludes it for that reason).

Evidence: https://platform.claude.com/docs/en/agents-and-tools/tool-use/computer-use-tool ; `claude-api` skill,
migration guides for Opus 5.5 and Sonnet 5.5.

Confidence: verified for the documented rules. Latency per action on current models: **not measured, no vendor
figure**. The effect of `between_tools` on computer-use accuracy: unknown.

### F5: Visual questions without the computer tool: an image block plus structured output

"Is Y shown?" and "where is X?" do not need the toolset.

- Send one `image` block and a text question; constrain the answer with `output_config.format` (JSON schema).
  Structured outputs are GA on all current models, Haiku 4.5 included. The vision guide points at them for
  coordinates: "To get coordinates as machine-readable JSON instead of prose, define a schema with structured
  outputs, for example an object with an `[x1, y1, x2, y2]` array per detected element."
- Ask for absolute pixel coordinates: "Claude works best with absolute pixel coordinates"; it "does not work
  well when you ask for normalized coordinates".
- Accuracy caveats from the vendor: "Claude's coordinate and localization outputs are approximate"; "Small
  elements lose precision when an image is downscaled: for fine targets, crop the region of interest and send
  the crop"; counts of many small objects can be wrong; "Do not use Claude for tasks requiring perfect
  precision ... without human oversight."
- Schema limits that matter for a verdict type: no numeric `minimum`/`maximum` (a `confidence` in 0..1 cannot be
  enforced by the schema), no recursion, `additionalProperties: false` required. The first use of a schema
  pays a compilation delay; compiled grammars are cached for 24 hours.
- Cost of one assertion on a 1024x768 capture, with about 300 prompt tokens and about 200 output tokens:
  roughly $0.005 on Sonnet 5.5, $0.002 on Haiku 4.5, $0.010 on Opus 5.5; half of that through the Batch API.

A third form sits between the two: send the capture together with the text snapshot (design §2.5), optionally
with component references drawn on the image (`annotateRefs`, already in the primitive catalogue), and let the
model answer with a **component reference** instead of a pixel. Then there is no grounding error to manage and
the answer is already replayable. See F16.

Evidence: https://platform.claude.com/docs/en/build-with-claude/vision-coordinates ;
https://platform.claude.com/docs/en/build-with-claude/vision (limitations) ;
https://platform.claude.com/docs/en/build-with-claude/structured-outputs ; ai-inference §F11.

Confidence: verified for the API facts. The structured-outputs page does not state in so many words that
structured output combines with image input; it is inferred from the vision guide's sentence above and **was
not run**. The cost figures are estimates computed here, without thinking tokens.

### B. Injecting input "like a human"

### F6: `java.awt.Robot`: what it does and where it cannot run

Robot generates events "in the platform's native input queue"; `mouseMove` "will actually move the mouse
cursor". That is its value (the full path a person uses) and its cost.

| Environment | Behaviour | Status |
|---|---|---|
| Headless JVM | the constructor throws `AWTException` | verified (javadoc) |
| X11 | needs the XTEST 2.2 extension, or the constructor throws | verified (javadoc) |
| Xvfb without a window manager | XTEST is present on a stock Xvfb; pointer events are delivered by position; key events go to the X focus window, which exists only once the application has requested focus (swing-driving §F1, §F12, §F13). BS4 measured focus for the in-JVM rungs, not Robot | **not executed** |
| Wayland | screen capture works since JDK 21 through the XDG ScreenCast portal, with a permission that can be stored; input emulation is not implemented for a pure Wayland session and "mostly works for X11 compatibility mode (except when trying to reach outside XWayland and windows are not restacked on emulated mouse click)" | likely (JDK-8280982 fixed in 21; JDK-8280983 closed as duplicate, no fix release found) |
| Windows session 0, locked or disconnected session | no input delivery, black capture (swing-driving §F23) | likely, not executed |
| Windows, integrity levels | OS input injection is subject to UIPI: "Applications are permitted to inject input only into applications that are at an equal or lesser integrity level", and the failure is not reported. This is documented for `SendInput`; that Robot's Windows peer is bound by the same rule is an inference | likely |
| macOS | input needs the Accessibility permission and capture the Screen Recording permission for the process; without them Robot "fail[s] silently from the applications perspective" (JDK-8242790, open) | likely (bug report read, not executed) |
| HiDPI | since JDK 9 `createScreenCapture` takes a user-space rectangle and returns an image of that user-space size, scaled down from the device pixels; `createMultiResolutionScreenCapture` adds the native-resolution variant. That pointer coordinates are user-space too is the expected counterpart but is handled in the platform peers, which were not read. JDK 8 has no HiDPI scaling on Linux or Windows (swing-driving §F21) | capture: verified by source read (`Robot.java:473`, `:525`, `:571-629`); pointer: likely; nothing executed |

Other properties that matter here:

- Robot calls should not be made on the EDT (`waitForIdle` throws there; captures can be lengthy), so a Robot
  rung runs on an agent worker thread.
- It takes the real pointer and keyboard. In interactive use it competes with the person at the machine, and an
  application cannot tell its events from theirs.
- It acts on whatever is at that screen position: another application's window, a screen lock, a notification.
- The JDK 25 class documentation states that desktop environments "may impose restrictions" (ignoring or
  limiting synthesized events, requiring approvals), and that without approval "the API will be degraded".

Evidence: OpenJDK 25.0.1 `java/awt/Robot.java:55-115`, `:473-529`, `:571-629`;
https://docs.oracle.com/javase/8/docs/api/java/awt/Robot.html ; https://bugs.openjdk.org/browse/JDK-8280982 ;
https://bugs.openjdk.org/browse/JDK-8280983 ; https://bugs.openjdk.org/browse/JDK-8242790 ;
https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendinput ; swing-driving §F0, §F1,
§F12, §F13, §F21, §F23.

Confidence: as per the table.

### F7: Inside the JVM, a mouse event handed to the window is routed by AWT itself

A native mouse event reaches AWT as a `MouseEvent` whose source is the heavyweight window, in window
coordinates. `Container.dispatchEventImpl` passes it to the window's `LightweightDispatcher`, which

- picks the target with `getMouseEventTarget`: the deepest visible component containing the point **that
  listens for mouse events** (a mouse, mouse-motion or mouse-wheel listener, or those event masks enabled);
  if a child does not qualify, the search goes on to its siblings and then its container;
- tracks enter and exit, and remembers the component that received `MOUSE_PRESSED`, so that `MOUSE_RELEASED`
  goes to it and `MOUSE_CLICKED` is delivered only when the pointer is still over it;
- builds a retargeted event in the target's coordinates and calls `target.dispatchEvent` (not posted);
- when the native container has a modal component, delivers only to its descendants.

A synthetic `MouseEvent` with the window as source, dispatched non-posted on the EDT, enters the same code.
This is a different thing from the built `DISPATCH` rung, which delivers to a named component at its centre and
therefore bypasses hit-testing: a covered component would still receive the click.

`SwingUtilities.getDeepestComponentAt(parent, x, y)` answers a slightly different question: the deepest
*visible* component containing the point, whether or not it listens for mouse events. A label inside a button
is the deepest component; the button is the mouse target. A visible glass pane is found first by both. A
hit-test primitive should report both.

Neither path checks `isEnabled()`: a disabled Swing component receives the event and its UI delegate ignores
it, as with a real click.

Evidence: OpenJDK 25.0.1 `java/awt/Container.java:2303-2318` (`dispatchEventImpl`), `:2417-2466`
(`getMouseEventTargetImpl`), `:2475-2489` (`MouseEventTargetFilter`), `:4485-4507`
(`LightweightDispatcher.dispatchEvent`), `:4534-4598` (`processMouseEvent`), `:4852-4916`
(`retargetMouseEvent`); `javax/swing/SwingUtilities.java:302-323` (`getDeepestComponentAt`).

Confidence: verified by source read on JDK 25. **Not executed**: that a synthetic window-sourced event behaves
like a native one in practice (popup menus and their mouse grab, tooltips, drag gestures, components that read
`MouseInfo` or modifier state from the toolkit), and that JDK 8 routes identically. `getMouseEventTarget` is
package-private, so an agent-side hit-test has to re-implement the filter with public API (listener arrays);
the enabled-event-mask half of the filter is not readable from outside, so the re-implementation is an
approximation for components that call `enableEvents` without adding a listener.

### F8: What in-JVM coordinate dispatch cannot see

- **Stacking order.** AWT has no public query for the z-order of top-level windows. `Window.getWindows()` and
  `getOwnedWindows()` give ownership, not stacking. In screen coordinates an agent can only guess which of two
  overlapping, unrelated windows is on top (modal dialog first, then owned windows over their owners, then the
  active window), and it knows nothing of other processes' windows. In *window* coordinates the question does
  not arise.
- **Heavyweight and native surfaces.** A heavyweight child (an AWT `Canvas`, a JOGL `GLCanvas`, a
  hardware-accelerated embedded browser) receives its input from the operating system. A synthetic AWT event
  reaches the AWT component but not the native surface behind it. See F12.
- **Things that are not in the component tree:** window decorations, the macOS screen menu bar, native file
  dialogs (`java.awt.FileDialog`), the system tray, other applications.
- **Native drag and drop.** `java.awt.dnd` drags are driven by the platform; synthetic press, move and release
  events are not expected to start one. Not executed.
- **Focus.** A dispatched click does not make a window the focused window. BS4 showed that
  `requestFocusInWindow()` fails in any window that is not focused; a text field clicked this way in such a
  window does not become the focus owner, so a following "type at keyboard focus" has no reliable target
  (design §3.7). The adapter must carry the last clicked component as the typing target, or activate the
  window first.
- **Popups.** A Swing popup that fits inside its window is a lightweight component of the layered pane; one
  that does not fit is a separate heavyweight window (`PopupFactory`: `LIGHT_WEIGHT_POPUP`,
  `MEDIUM_WEIGHT_POPUP`, `HEAVY_WEIGHT_POPUP`). The second kind is another window to capture and hit-test.

Evidence: OpenJDK 25.0.1 `java/awt/Window.java:1412`, `:1538`; `javax/swing/PopupFactory.java:100-115`,
`:227-263`; design §3.7 (BS4 results); swing-driving §C10.

Confidence: verified for the API facts; the drag-and-drop and focus consequences are likely, not executed.

### F9: OS tools outside the JVM (xdotool, `SendInput`)

- xdotool uses XTEST unless a window is named; with `--window` it uses `XSendEvent`, which many programs
  ignore (swing-driving §F1, §F13). It has the Robot constraints on X11 and none of Robot's integration: it
  cannot run inside the target's session unless started there.
- `SendInput` is subject to UIPI and reports no error when blocked (F6).
- Such tools need nothing from the agent, and can drive windows that are not Java at all (an installer, a
  native dialog, a second application). They cannot say which component is under a pixel; paired with the
  agent's hit-test they could, but then the agent is in play anyway and Robot does the same job in-process.
- The observation that XTEST keys did not reach a Swing application under a WM-less Xvfb (swing-driving §F13,
  ai-inference §C6) is still not explained. BS4 did not exercise XTEST.

Evidence: swing-driving §F1, §F13; https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendinput

Confidence: likely.

### F10: What each input path can and cannot prove

| | Robot / OS tools | In-JVM, routed through the window (F7) | Component rungs (`ACTION`, `COMPONENT`, `DISPATCH`) |
|---|---|---|---|
| Addressed by | screen pixel | window pixel | component |
| Proves the listener wiring of the component | yes | yes | yes |
| Proves the component is the one under that point (not covered by a glass pane or overlay) | yes | yes, within the Swing tree | no |
| Proves the window is visible, on top and focusable for a person | yes | no | no (`COMPONENT` proves focusability inside the focused window) |
| Reaches heavyweight and native surfaces | yes | no | no |
| Works when obscured, locked, in Windows session 0, on a WM-less Xvfb | no / no / no / pointer yes, keys uncertain | yes | yes (`COMPONENT` needs window activation) |
| Leaves the person's pointer alone | no | yes | yes |
| Deterministic on replay | timing and focus dependent | yes, given the same layout | yes |

None of them proves that the pixels a person would see are right. That is capture (F11) plus an assertion.

Confidence: a summary of F6-F9; the same caveats apply.

### C. Capturing what a person sees

### F11: `PAINT` and `ROBOT` capture differ exactly where a person's view and Swing's model differ

`PAINT` is `printAll` into a `BufferedImage` on the EDT (built, `swing.capture`, design §3.7). `ROBOT` is
`Robot.createScreenCapture`.

| Situation | `PAINT` | `ROBOT` |
|---|---|---|
| Window obscured by another window, minimised, off-screen | shows the window as it would paint | shows what covers it, or nothing |
| Session locked, Windows session 0, WM-less Xvfb | works | black or unavailable (F6); Xvfb works |
| Heavyweight child (AWT `Canvas`, `GLCanvas`, accelerated browser surface) | not rendered; typically an empty area | rendered |
| Lightweight off-screen browser view | rendered (swing-driving §F19) | rendered |
| Popup inside the window (lightweight) | rendered with the window | rendered |
| Popup outside the window bounds, tooltip window, second dialog | a separate window: needs its own capture | rendered in place |
| Window decorations, other applications, OS notifications | never | rendered |
| Mouse cursor | never | not included by the JDK either (not re-verified for JDK 25) |
| HiDPI | user-space pixels unless painted with a scale transform | user-space size by default, native variant on request (F6) |
| Determinism | same JDK, look and feel, fonts and size give the same pixels in principle | depends on the compositor, timing and whatever else is on screen |
| Animation, a frame in mid-update | a synchronous repaint of the current model state | the frame that happened to be on screen |
| Personal data | can be masked per component before the image leaves the JVM | can only be masked by rectangle after capture |

A `PAINT` capture can therefore pass while a person sees a dialog hidden behind another window, and a `ROBOT`
capture can fail because a notification covered the button. They answer different questions: "what does the
application draw" and "what is on the glass".

A screen-shaped image without Robot is possible by painting each showing window at its screen position onto
one canvas, but the stacking order is a guess (F8).

Evidence: design §3.7; swing-driving §F19, §R5, Risk7; OpenJDK 25.0.1 `java/awt/Robot.java:440-529`.

Confidence: likely. **Not executed**: pixel-level determinism of `PAINT` across runs and across JDK versions
(font rasterisation is known to differ between JDK lines; not measured here), and `PAINT` with a scale
transform as a sharper "zoom".

### F12: Embedded browsers: which capture and which input reach them depends on the rendering mode

- **JxBrowser.** In off-screen mode the browser view is a lightweight component that paints from a pixel
  buffer, so `PAINT` captures it and AWT events reach it. In hardware-accelerated mode the native surface sits
  above the Java window: `PAINT` misses it and in-JVM events do not reach it (swing-driving §F19).
- **JCEF** (upstream `java-cef`). The windowed-rendering browser embeds an AWT `Canvas` on Windows and its
  `createScreenshot` throws `UnsupportedOperationException("Unsupported for windowed rendering")`. The
  off-screen-rendering browser draws into a JOGL `GLCanvas`, a heavyweight AWT component, and offers
  `createScreenshot(nativeResolution)`. So upstream JCEF content is not captured by `printAll` in either mode;
  in off-screen mode the browser's own screenshot call is the in-process route. Forks with a lightweight
  off-screen component exist; none was examined.
- **CDP**, when the debug port is open (design §3.8), is independent of the rendering mode:
  `Page.captureScreenshot` for pixels, and `Input.dispatchMouseEvent` for pointer input at "X coordinate of the
  event relative to the main frame's viewport in CSS pixels". Playwright's mouse API drives the same command.
  An element under a point is available in the page (`document.elementFromPoint`), which is the web
  counterpart of a hit-test.

A capture meant for a model therefore has to be assembled per surface, and a pixel in it has to be attributed
to a surface (Swing component, or browser plus viewport point) before anything is clicked. This belongs behind
the embedded-browser SPI that B9 asks for, with both JxBrowser and JCEF behind it.

Evidence: swing-driving §F19; `java-cef` master, `java/org/cef/browser/CefBrowserWr.java` (lines 251-256, 423-424)
and `CefBrowserOsr.java` (lines 15, 80, 148, 513), read 2026-10-02,
https://github.com/chromiumembedded/java-cef ; Chrome DevTools Protocol, `Input.pdl`, `dispatchMouseEvent`,
https://github.com/ChromeDevTools/devtools-protocol

Confidence: verified by source read for JCEF and CDP; likely for JxBrowser (vendor documentation, not
re-read). Nothing executed against an embedded browser.

### D. Precedents and evidence

### F13: "Resolve with a model once, replay without one" is shipping practice for web UIs

- **Stagehand.** `observe()` returns an action preview, "a JSON-ified version of a Playwright action:
  {description, method, selector, arguments}"; passing it to `act()` involves no model call; the object can be
  stored and replayed; on failure an optional self-heal step sends the original instruction to the model again.
- **Midscene.** Caches the plan per instruction and, for element location, the element's XPath; "verifies
  whether the XPath is still valid on the next execution" and falls back to the model when it is not. Query and
  assertion results (`aiAssert`, `aiQuery`, `aiBoolean`) "will never be cached". Its location cache is
  web-only: "Canvas elements, cross-origin iframes, closed Shadow DOM" cannot be cached and fall back to the
  model. Its own example drops from 51 s to 28 s with the cache.
- **Maestro** keeps AI assertions optional by default and extracts text into a variable for deterministic use
  (ai-inference §F21). **Playwright**'s agents generate committed test code (ai-inference §F0, §F1).

Three lessons transfer. The replayable artefact is a structural locator, not a pixel. What has no structural
locator (canvas) has no replay. Verdicts are not cached: an assertion made by a model stays a model call unless
it is converted into a deterministic check. A fourth point is where these tools differ from this framework:
their fall-back to the model on a cache miss happens at run time, which design §3.4 forbids in `replay`
(`REBIND_NEEDED`, never a silent re-resolution).

Evidence: https://docs.stagehand.dev/v2/best-practices/caching ; https://midscenejs.com/caching ;
ai-inference §F0, §F1, §F21.

Confidence: likely (vendor documentation read through a summarising fetch; not run).

### F14: Accuracy, latency and step-count evidence for vision-driven GUI agents

- **OSWorld** (369 tasks on real desktops): "humans can accomplish over 72.36%" of the tasks. At release in
  2024 the best agent reached 12.24%. Third-party leaderboards for OSWorld-Verified listed the leading models
  at about 85% in August 2026.
- **OSWorld-Human**: "these systems are practically unusable due to extremely high end-to-end latency (e.g.,
  tens of minutes) for tasks that typically take humans just a few minutes"; "large model calls for planning,
  reflection, and judging account for most of the overall latency, and as an agent uses more steps to complete
  a task, each successive step can take 3x longer"; of 16 agents "even the best agents take 2.7-4.3x more steps
  than necessary" (the first version of the paper said 1.4-2.7x).
- **ScreenSpot-Pro** (grounding in high-resolution professional applications, 23 applications): "the best model
  achieving only 18.9%" at publication, 48.1% with a search method that crops and zooms. Small targets on large
  screens are the hard case; that is what `zoom` and cropping address.
- **Anthropic**, at the first release in October 2024: "Claude's computer use remains slow and often
  error-prone"; the "flipbook" of screenshots "can miss short-lived actions or notifications". For Sonnet 5.5
  the vendor now reports substantially more completed computer-use tasks than its predecessor with under a
  third of the tokens, and for Opus 5.5 that at its default effort it matches the success rate its predecessor
  reached only at a much higher effort, "in fewer steps and with roughly 40% fewer tokens". No absolute numbers
  are given.
- **Field reports** on browser agents: a large gap between benchmark and production success, with screenshot
  ambiguity and modal interruptions among the causes (ai-inference §F5).
- **Run-to-run variation** is the property that disqualifies a model as the executor of a regression suite
  (ai-inference §F6).

Evidence: https://arxiv.org/abs/2404.07972 ; https://arxiv.org/abs/2506.16042 ;
https://arxiv.org/abs/2504.07981 ; https://www.anthropic.com/news/developing-computer-use ; `claude-api` skill,
migration guides for Opus 5.5 and Sonnet 5.5 ; ai-inference §F5, §F6.

Confidence: verified for the three papers (abstracts read) and the 2024 Anthropic statement; the 2026
leaderboard figure is from a search summary of third-party pages and is **not verified**; the vendor's relative
claims are unquantified. Grounding accuracy of current Claude models on Swing user interfaces: no data found.

### F15: Image comparison is deterministic but environment-bound; UI flakiness is mostly about waiting

- Playwright's visual comparison documentation: "Browser rendering can vary based on the host OS, version,
  settings, hardware, power source (battery vs. power adapter), headless mode, and other factors. For
  consistent screenshots, run tests in the same environment where the baseline screenshots were generated."
  Baseline file names carry the platform, and tolerances (`maxDiffPixels`) are part of the API.
- An industrial study of visual GUI testing (image recognition against the bitmap layer) identified 58
  challenges, problems and limitations in 26 types, and still found the technique "valuable, flexible and
  considered cost-effective"; a follow-up found maintenance cheaper when frequent than when deferred.
- A study of 235 flaky UI tests in 62 projects attributes about 45% to asynchronous waiting. The framework's
  event-based step protocol (design §2.5) addresses that class, and it applies unchanged to a vision-driven
  step if the step is executed through the same protocol.

Implication: a pixel-anchored step or an image baseline is replayable only inside an environment fingerprint
that includes whatever changes rendering (JDK line, look and feel, fonts, display geometry and scale), and
`PAINT` captures remove several of Playwright's variation sources but not fonts.

Evidence: https://playwright.dev/docs/test-snapshots ; https://research.chalmers.se/en/publication/218525 ;
https://research.chalmers.se/en/publication/235742 ; https://arxiv.org/abs/2103.02669 (the 45% figure is from a
search summary of the paper, not read in the paper itself).

Confidence: likely.

### F16: Marks and references are an established alternative to raw pixel grounding

Set-of-Mark prompting overlays marks (numbers, boxes) on image regions and lets the model answer with a mark;
the paper reports large grounding gains for a vision model that was weak at raw coordinates. Playwright MCP
works from accessibility snapshots with element references by default and makes coordinate mode an opt-in
capability (ai-inference §F2). For Swing the framework already has stable component references, exact bounds
and a text snapshot, so marks cost one Java2D pass in the agent and no segmentation model.

Evidence: https://arxiv.org/abs/2310.11441 ; ai-inference §F2; design §2.4 (`annotateRefs`), §2.5.

Confidence: verified that the technique and the precedent exist; **not measured** whether marks help current
Claude models on Swing captures, or how many marks an image tolerates.

## Recommendations

### R0: Three ways of seeing, and only one of them needs pixels

Treat "vision" as three tiers and use the lowest that does the job:

| Tier | The model gets | The model answers with | Input path | Replayable |
|---|---|---|---|---|
| V1 "sees, acts by reference" | text snapshot plus a `PAINT` capture, optionally annotated with refs | a component ref (custom tools) | existing rungs | directly: the ref resolves to a locator |
| V2 "acts by pixel inside the JVM" | a window capture | a pixel (computer toolset or a custom pointer tool) | `swing.pointer` routed through the window (F7), after `swing.hitTest` | when the hit-test finds a locatable component; else R4 |
| V3 "acts like a person at the glass" | a `ROBOT` screen capture | a screen pixel | `swing.pointer` via `ROBOT` | timing and focus dependent; opt-in |

V1 is the default for anything in the Swing tree and probably covers most of what is wanted from "the AI
looks at the screen". V2 is the answer to "clicks like a human" that still works in CI images, locked
sessions and beside a person using the machine. V3 is for the cases in F10 only Robot can prove, and for
native surfaces. This keeps design §10 intact: none of the three is the primary driver, and V3 never becomes a
default (B12).

### R1: The loop runs in the controller, in a separate module

- A new controller module (working name `skillroy-vision`), outside the base's required set, holds: the
  screenshot/act loop, the adapter between a tool vocabulary and agent primitives, the grounding and visual
  question calls, the recorder (R3), and image comparison (R4, R5). It depends on `skillroy-inference` for
  model access and on `skillroy-scenario` for step execution.
- The agent gets no model code and no new third-party classes. Its additions are the primitives in R2, written
  on JDK 8 API only.
- The adapter is an interface with two implementations: the Claude computer toolset (F0, F1), and a
  provider-neutral set of custom strict tools (`capture`, `click_ref`, `click_at`, `type`, `key`, `scroll`).
  The second keeps B7's neutrality, avoids the 4,500-token toolset overhead for V1, and is the only form that
  can offer `click_ref`. Both can be declared in one request.
- Every action the loop takes goes through the executor's step protocol (act, ack, ready, settle, snapshot;
  design §2.5), never around it. The settle and the application's ready signal, not a `wait` action or a
  sleep, decide when the next capture is taken. The model's `wait` member maps to a bounded `sync.await` or
  `swing.settle`.
- Mapping of the toolset members: `screenshot` and `zoom` to `swing.capture`; clicks, `mouse_move`, `scroll`,
  drag and button down/up to `swing.pointer`; `type` and `key` to `swing.type` / `swing.key` aimed at the
  focus owner or, failing that, the last clicked component (F8); `cursor_position` answered from the adapter's
  own state. Key names arrive in X keysym style and are translated in the controller.
- The loop is allowed in `live` and interactive use and at authoring time. It is not allowed in `replay`; the
  cost ledger assertion (design §6) already enforces that.

### R2: Agent primitives the bolt-on needs

Three additions, all small, none of them built now.

**Capture, extended.** `swing.capture` exists (`PAINT`, one window). The bolt-on needs the result to say where
the image sits and what is in it, so that a pixel can be turned back into a point:

```
swing.capture(target?, mode?: PAINT|ROBOT, scope?: WINDOW|SCREEN|REGION, region?, scale?, annotateRefs?, redact?)
  -> { format, width, height, base64,
       captureId, seq,                       // identity of this capture, and the event sequence it reflects
       space: WINDOW|SCREEN, origin:{x,y},   // where pixel (0,0) lies in that space
       scale,                                // image pixels per user-space unit
       windows:[{ref, rect}],                // which windows are in the image, in image coordinates
       surfaces?:[{kind: WEB|HEAVYWEIGHT, rect, browserId?}],   // areas that are not Swing-painted
       marks?:[{mark, ref, rect}] }          // with annotateRefs
```

`scope: SCREEN` with `mode: PAINT` is the composite of F11 and reports that its stacking order is assumed.

**Hit-test (read-only).**

```
swing.hitTest(point:{x,y}, space?: {captureId} | {window: ref} | SCREEN)
  -> { window: ref,
       deepest: Node,                 // what is drawn there (SwingUtilities.getDeepestComponentAt)
       mouseTarget?: Node,            // what AWT would deliver a mouse event to (F7), when it differs
       locators: [Locator],           // candidates in authoring preference order, each checked to match exactly one
       local:{x,y}, relative:{fx,fy}, // the point inside mouseTarget, in pixels and as fractions of its bounds
       region?: {id, rect, provider}, // from a RegionResolver (R4), for custom-painted components
       surface: SWING|WEB|HEAVYWEIGHT|NONE, browserId?, viewportPoint?,
       blockedBy?: ref,               // a modal dialog that would block input to this window
       stackingAssumed?: bool }       // SCREEN space only: which window is on top was guessed (F8)
```

**Pointer at coordinates (mutating).**

```
swing.pointer(action: MOVE|PRESS|RELEASE|CLICK|DOUBLE_CLICK|DRAG|WHEEL, point, space?, to?, button?, modifiers?,
              clickCount?, wheel?, via?: ROUTED|ROBOT (ROUTED), force?)
  -> { via, window: ref, target?: ref, fired: bool, reason? }
```

`ROUTED` builds window-sourced events and dispatches them non-posted on the EDT through `invokeLater`, so AWT
does the routing (F7); it checks for a blocking modal dialog like the other input primitives. `ROBOT` converts
to screen coordinates and uses the Robot rung on a worker thread. The result names the component that
received the press, so the recorder can cross-check it against the hit-test.

For embedded browsers the same two operations are needed on the web side: `web.hitTest(browserId, viewportPoint)`
and `web.pointer(...)`, each with a CDP implementation (`Input.dispatchMouseEvent`) and an in-process one
behind the B9 browser SPI (F12).

Keyboard needs no new primitive: `swing.key` already defaults to the focus owner. `swing.type` would accept an
omitted target with the same default.

### R3: Recording a vision-driven step into a deterministic step

The procedure, for one model action "click at (x, y)" on capture C:

1. `swing.hitTest` at (x, y) in C, **before** acting. Refuse to act if the capture is stale (events after C's
   `seq` changed the window set) and take a new capture instead.
2. If the hit-test yields a component with at least one locator that matches exactly that component, choose the
   locator by the authoring preference (domain strategy, then `id`, `name`, `text`, `class` with index, `path`
   last; design §3.7) and choose the rung: `ACTION` for a button-like target, `DISPATCH` otherwise, or
   `swing.pointer` with the fractional point when the position inside the component matters (a slider, a
   table cell).
3. **Act through the step that will be replayed**, not at the pixel. The consequence observed while recording
   is then the consequence of the recorded step, and a difference between "clicked the pixel" and "fired the
   component" shows up at recording time, in front of a person, instead of in CI.
4. Journal the step with its provenance: the intent text, capture hash, point, hit-test result, model and
   request id, tokens and cost.
5. Export the journal as ordinary scenario steps (`click: {by: {...}, rung: ...}`) for review. The compiled
   `plan.json` contains no vision call, so the scenario is replayable like any other.

The same procedure serves the interactive overlay (a person or assistant acts, the journal is the by-product)
and the authoring planner of design §5 (A).

A second, optional shape keeps the intent in the scenario and the locator in the bindings: a typed query
(`UiTarget(intent)`) whose `Resolution` holds the locator, with strategy `LLM_VISION`, provenance as in step 4,
and a deterministic verifier (the locator matches exactly one showing component of the recorded role). It
reuses design §3.3 and §3.4 as they are, with one difference that needs a decision when it is built: a UI
target can only be verified when the application is in the state where the target exists, so its verifier
runs at the step, not at run start. In `replay` a failed verifier is `REBIND_NEEDED`. That is also the
weakness: a control that disappeared because of a product defect would be reported as a binding problem. The
plain-step shape fails as `ASSERTION` or `NOT_FOUND` like any step and should be the default; the binding shape
is for targets whose locator legitimately differs between environments.

### R4: When a step cannot be made deterministic

In order of preference:

1. **A region resolver from the pack.** A custom-painted component usually has a model behind it (cells, nodes,
   keys of an on-screen keypad). An agent SPI, in the family of `NameEnricher` and `ActionFirer`, maps a point
   in such a component to a stable region id and back: `regionAt(component, point) -> {id, rect}` and
   `rectOf(component, id)`. The hit-test reports the region; the recorded step addresses it
   (`{by: ..., region: "..."}`); replay asks for the rectangle and clicks its centre. No pixels are stored.
2. **Pixel-anchored replay with an image guard.** Record the component locator (or the window, when there is
   none), the point as fractions of its bounds, a `PAINT` crop around the point with its hash, and a
   tolerance. At replay: capture the same crop, compare it deterministically (exact hash first, then a bounded
   pixel difference), and click only on a match; a mismatch is `REBIND_NEEDED` and carries both images. The
   environment fingerprint for such a scenario has to include what changes rendering (F15). `ROBOT` captures
   are not suitable anchors (F11).
3. **Live-only.** The compiler marks a plan that still contains a model-driven step (`inference: run`).
   `replay` does not execute such a plan and reports it as skipped with that reason; it is not a failure of
   the build and not a pass. The scenario remains useful for interactive runs and for exploratory nightly jobs
   that are allowed a model, and the zero-LLM assertion in CI stays intact.

A heavyweight or native surface (F8, F12) falls under 2 with `ROBOT` input, or under 3.

### R5: Visual assertions

Keep design §3.2 and §3.3 as the rule: expectations are evaluated against primitives, and a model never
evaluates an oracle at run time in CI. Within that:

- **Authoring and bind time.** A model may look at a capture and answer a question. Wherever possible the
  answer is converted into a deterministic expectation at once: the model points at its evidence (a ref or a
  box), the hit-test finds the component, and the recorded expectation is a tree or fact matcher
  (`ui...text`, a domain fact). The model's verdict is provenance, not the oracle. Model-written assertions
  tend to encode what is shown rather than what should be shown (ai-inference §F8), so these need the same
  review as oracles.
- **Bind time, purely visual properties** (a colour, an icon, a layout). The model and a person approve a
  baseline image once; replay compares a `PAINT` capture with the baseline deterministically, with a mask and
  a tolerance, inside the fingerprint (F15). This is classical visual regression and needs no model in CI.
- **Run time, live and interactive only.** A visual question answered by a model during the run is advisory
  evidence in the report. In a scenario it is written as a guarded expectation on a controller-side capability
  (`inference.vision`), so the existing rule applies unchanged: where the capability is not `OK` (always in
  `replay`) the expectation is skipped and the best outcome is `DEGRADED` (B14). It never turns a failing
  scenario green.
- Verdict type: `{verdict: YES|NO|UNSURE, evidence: [{ref? | box?}], explanation}` through structured output.
  `UNSURE` is a result, not an error. A numeric confidence cannot be range-checked by the schema (F5).

### R6: Models and budgets for interactive use

- Default model for the act loop and for grounding: Sonnet 5.5 (high-resolution image tier, lowest latency of
  the models that take the toolset). Opus 5.5 where a step fails twice. Haiku 4.5 is not listed for the
  toolset and is on the standard image tier; it is an option for yes/no questions on captures up to about
  1366x768 only.
- Fable 5.1 stays excluded (30-day retention; design §5).
- Captures at the window's own size, at most 1920x1080, sent unscaled; `PAINT` unless the question is about
  the glass.
- One cache breakpoint after the tool definitions and system text, one advancing on the latest tool result;
  no client-side pruning of screenshots on the 5.5 models (F4).
- A per-session budget next to the per-scenario one (B6). With the estimates in F3 a 20-action session costs
  about $0.2 to $0.3 with caching; agents commonly take two to four times the necessary steps (F14), so a
  ceiling of about $1 per session is a starting value to be replaced by measurement.
- An action that the pack marks destructive needs confirmation as it does for any caller (`confirm: true`,
  design §2.2); in interactive use the person confirms, and a model's request alone is never the confirmation.

### R7: Safety and data

- **Masking moves from rows to pixels.** `MaskPolicy` covers rows (design §2.6). A capture shows personal data
  as rendered text. `PAINT` can mask per component before the image leaves the JVM (F11); a capture intended
  for a model should be redacted by default, with the policy supplied by the pack. Password fields already
  paint echo characters.
- **Screen content is untrusted input.** Text in a capture can carry instructions (F4). The loop treats what
  the model reads on screen as data: the tool set offered to the model is limited to the target's own
  primitives, read-only mode (B13) applies, and nothing the model produces can reach `data.query`, hooks or
  module upload.
- **Robot acts outside the application.** With `via: ROBOT` a wrong coordinate lands on whatever is there. V3
  therefore needs its own capability and an explicit opt-in per run, and should refuse when the hit-test says
  the point is outside every window of the target.

### R8: The seams to reserve now

Nothing here is built now. Reserving means: the name is written into the design catalogue and is not used for
anything else; additive changes to the frozen Phase 1 contract go through its amendment process when the
bolt-on is built.

| Area | Reserve | Why now |
|---|---|---|
| Agent primitives | `swing.hitTest`, `swing.pointer`; `web.hitTest`, `web.pointer` | names in the catalogue (design §2.4) next to the already listed `ROBOT` values |
| `swing.capture` | result fields `captureId`, `seq`, `space`, `origin`, `scale`, `windows`, `surfaces`, `marks`; parameters `scope`, `region`, `scale`, `redact` (with `mode: ROBOT` and `annotateRefs` already named) | the present result (`format, width, height, base64`) cannot be mapped back to a point; adding optional fields later is compatible only if nobody has given these names another meaning |
| `swing.type` | an omitted target means the focus owner, as for `swing.key` | "type at keyboard focus" (F1) |
| Capability ids | `swing.hittest`, `swing.pointer.routed`, `swing.pointer.robot`, `swing.capture.robot`, `swing.capture.screen`, `web.hittest`, `web.pointer` (beside the existing `swing.click.robot`) | `requires:` and guarded expectations are keyed by id |
| Controller-side capabilities | the prefix `inference.` for capabilities the controller contributes to the matrix, first `inference.vision`; `UNAVAILABLE(reason="replay")` in `replay` | lets a scenario guard or require a model without a new mechanism (R5) |
| Inference gateway (B7) | message content is a list of typed parts (text, image, tool use, tool result with image), and a request may carry provider-defined tool entries the gateway passes through | a text-only gateway signature would have to break to carry a screenshot |
| Cost ledger | image input counted like any input; the zero-LLM assertion covers the bolt-on's calls | CI guarantee (design §6) |
| Resolution model | `Strategy` value `LLM_VISION`; a query and value kind `UiTarget` (a locator, or an anchor: locator, fractional point, image hash, tolerance); provenance may reference capture artefacts by hash | `Strategy` is a closed list in design §3.3 |
| Plan | a compiled field `inference` with the values `none`, `bind` (a model produced bindings; replayable) and `run` (a model is needed while steps run); a plan with `run` is never executed in `replay` and is reported as skipped | R4 option 3, without a new failure kind |
| Scenario verbs | `aiAct` (an intent for the vision loop), `aiExpect` (a visual question), `pointer` (deterministic coordinate step), `expectImage` (deterministic comparison with a baseline) | base verbs are a closed set and unknown keys are errors |
| Step locators | an optional `region` beside `by` | R4 option 1 |
| Agent SPI | `RegionResolver` (point to region id and back, for custom-painted components); a component-level method on the masking policy for captures | same family as `NameEnricher`, `ActionFirer`, `MaskPolicy` |
| Executor | a public single-call entry ("execute this call with the step protocol, return events and snapshot") and a step journal that exports scenario steps | the loop must not bypass the step protocol (R1); the MCP surface and the authoring planner need the same entry |
| Fingerprint | the pack's fingerprinter may add rendering inputs (JDK line, look and feel, fonts, geometry, scale) | pixel anchors and image baselines (F15) |

The smallest subset, if only a few things are to be decided today: the two primitive names with the capture
result fields, the `inference.` capability prefix with the plan's `inference` field, and the typed content
parts in the gateway. Everything else can be added without breaking a contract.

### R9: What stays out of the base

- The screenshot/act loop, prompts, the computer-toolset adapter and its key-name translation, model choice.
- Any model call at replay time, including "self-healing" a locator that no longer resolves (F13, design §10).
- Run-time visual verdicts that gate CI.
- Image comparison beyond what `expectImage` needs, perceptual metrics, OCR, video.
- OS tools outside the JVM (xdotool, `SendInput`), a window manager in the CI image, anything that drives
  windows of other processes.
- Robot as a default anywhere. The Robot implementation itself can live in an optional agent module; only the
  `ROBOT` enum values and capability ids are base.
- The overlay's own presentation (a drawn pointer, highlights, narration).
- Vendor-specific embedded-browser code (B9): the base holds the SPI and the CDP path.

### R10: Spikes before any of this is built

Proposed as BS8-BS11, to be listed in `../TODO.md` when the work is scheduled.

| Spike | Question | Pass criterion |
|---|---|---|
| BS8 | Robot on the WM-less Xvfb and on a desktop session: do pointer and key events land, on Java 8 and 25 targets? `ROBOT` versus `PAINT` capture of the sample application | a written table like BS4's; settles swing-driving §F13 |
| BS9 | Routed dispatch (F7): buttons, text caret placement, popup menus, combo boxes, tables, wheel, drag inside a component, a glass pane, a disabled component; Java 8 and 25 | each case behaves as with Robot on the same build, or the difference is documented |
| BS10 | `PAINT` determinism: the same window, 100 captures, one JDK; then across JDK lines and font sets | identical hashes within one environment; differences across environments quantified |
| BS11 | Grounding and cost on the sample application: V1 (refs) versus V2 (pixels) on Sonnet 5.5 and Opus 5.5; latency per action; tokens per session | measured accuracy, seconds per action and cost replace the estimates in F3, F4 and R6 |

## Constraints

### C0: No model call in `replay`

Unchanged (design §3.4, §6; ai-inference §C7). The bolt-on adds no exception. Whatever cannot be reduced to a
deterministic step or comparison is live-only.

### C1: A model call is seconds long and the target may not wait

A screenshot/act loop puts a model call between every two actions. An application that locks after idle time,
or holds a modal prompt, is exposed to that for the whole session (ai-inference §C1). Interactive use accepts
it; the pack's `PromptPolicy` and session handling still apply between actions.

### C2: Robot needs an interactive, unlocked, non-headless session and exclusive use of the pointer

F6. On Wayland input emulation is not available outside XWayland; on macOS two permissions are needed per
process; on Windows session 0 and locked sessions are out (design Phase 6 already excludes `ROBOT` there).

### C3: In-JVM dispatch stops at the edge of the Swing tree

F8, F12: heavyweight and native surfaces, decorations, native dialogs and other processes are unreachable, and
stacking order between top-level windows is not observable.

### C4: The client owns coordinate scaling, and oversized screenshots are rejected

F2. The adapter must know the model's image tier, send captures within it, and map coordinates back through
the capture's `origin` and `scale`.

### C5: The agent stays Java 8 and dependency-free

Capture, hit-test, pointer, marks and masking are Java2D and AWT only. Image comparison and everything
model-related run in the controller.

### C6: Screenshots are personal data until masked

R7. A capture leaves the JVM and, in live use, the organisation. The retention rules of the chosen model apply
(F4).

## Risks

### Risk0: Recorded and replayed paths differ

A click recorded at a pixel and replayed through `ACTION` may not be the same event sequence (press, release,
click, focus). Mitigation: act through the step that is recorded (R3 step 3), and keep `swing.pointer` with a
fractional point as the replay form where position matters.

### Risk1: A UI regression is reported as a binding problem

The binding-shaped recording (R3) reports a vanished control as `REBIND_NEEDED`. Mitigation: plain steps by
default; for bindings, the report shows the recorded and the current capture side by side.

### Risk2: Routed dispatch is not what a native event does

F7 is a source reading. Popup grabs, drag gestures, hover-dependent widgets and toolkit-level state
(`MouseInfo`, lock keys) may differ. Mitigation: BS9 before the primitive is specified; the result's
`target` lets a mismatch with the hit-test be detected at run time.

### Risk3: Pixel anchors and baselines rot

Fonts, look and feel, a JDK update or a changed window size invalidate them wholesale (F15). Mitigation: prefer
a `RegionResolver`; keep anchors small; put rendering inputs into the fingerprint so that the failure is one
`REBIND_NEEDED` per scenario and not a wave of false product failures.

### Risk4: `PAINT` shows something no person saw

F11. A scenario that asserts on a `PAINT` capture passes while the window is covered or off-screen.
Mitigation: say in the report which capture mode produced each image; use `ROBOT` where the claim is about the
glass.

### Risk5: Cost and latency creep in interactive sessions

Agents take several times the necessary steps and later steps get slower (F14). Mitigation: V1 first (fewer,
cheaper turns), batches ending with a capture, budgets per session (R6), measured values from BS11.

### Risk6: Grounding errors on small or dense targets

F5, F14. Mitigation: hit-test before acting and show the model what it is about to hit (role, name, text) when
the target is not what the intent names; `zoom` or a cropped capture for small targets; refs instead of pixels
wherever a ref exists.

### Risk7: Prompt injection through the screen

F4, R7. Mitigation: the tool set is the target's own primitives; read-only mode and `confirm` stay in force;
no path from model output to SQL, hooks or module upload.

### Risk8: Vendor surface drift

The toolset replaced a tool version within a year, and partner platforms differ in which form they accept
(F0). Mitigation: the adapter interface (R1); the provider-neutral custom-tool adapter as the fallback;
re-check the compatibility section before promising a platform.

### Risk9: Reserved names constrain a design nobody has built

R8 fixes names before BS9 and BS11 have run. Mitigation: reserve names and meanings only, no signatures in the
frozen contract; the signatures in R2 are a proposal.

## Not verified

Collected from the items above. None of these was executed or measured for this note.

1. Robot input and capture on the WM-less Xvfb, on any desktop session, on Wayland, macOS or Windows (F6);
   whether XTEST key events reach a Swing application there (F9, swing-driving §F13).
2. That Robot's Windows implementation is blocked by UIPI in the way `SendInput` is documented to be (F6).
3. That a synthetic, window-sourced `MouseEvent` is routed and handled like a native one in practice, on Java 8
   and Java 25 (F7); the JDK 8 source of the dispatcher was not re-read.
4. Native drag and drop and focus behaviour under routed dispatch (F8).
5. Pixel determinism of `PAINT` captures within and across JDK lines; `PAINT` at a scale factor as a zoom (F11).
6. Anything about a real embedded browser: JxBrowser modes are from vendor documentation cited in
   swing-driving; JCEF is from a source read of upstream only; no fork was examined (F12).
7. Latency per action and per session on current models; there is no vendor figure (F4).
8. All cost figures for loops and assertions: computed from the published formula and prices with assumed
   output tokens, not measured (F3, F5, R6).
9. That structured output combines with image input (inferred from the vision guide, F5); grounding accuracy
   of current Claude models on Swing captures; whether marks help (F5, F16).
10. The beta header string for `computer_20251124` and the status of `computer_20250124`: from the `claude-api`
    skill snapshot, not re-read on the documentation page; typed support for the toolset in the Anthropic Java
    SDK was not checked (F0).
11. The August 2026 OSWorld-Verified leaderboard figures (search summary of third-party pages), and the 45%
    asynchronous-wait share of flaky UI tests (search summary) (F14, F15).
12. The Stagehand and Midscene behaviour is from their documentation, read through a summarising fetch; the
    tools were not run (F13).
13. The Anthropic documentation pages were read as text returned by a fetch tool. Quoted sentences are as
    returned; the member table in F1 and the parameter list in F0 were not compared with the page source.
