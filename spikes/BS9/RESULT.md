# BS9 result: Robot input on the test display and on a desktop session (2026-10-10)

**Verdict: passed with conditions.** On the WM-less Xvfb the CI uses and with openbox on the same display, on Java 8
(Temurin 1.8.0_504) and Java 25 (Temurin 25.0.4.1) targets (arm64; Java 25 also on the emulated amd64 image),
**Robot pointer events land** in every case tried (16 toy cases of BS10 and three keypad keys of the sample, which are
focus-gated and debounced) exactly as a person's would: a glass pane with a listener takes the click, another window of
the JVM on top takes it, a window blocked by a modal dialog gets nothing. **Robot and XTEST keys land in the AWT focus
owner if and only if the target JVM has a focused window.** That settles swing-driving F13: under a WM-less X server the
input focus is `PointerRoot` until a Java window asks for it; the Java window then receives the key events from X (the
toolkit's own log shows them) but AWT posts them, and the keyboard focus manager drops a posted key event when there is
no focus owner and no focused window. A window has no focus on a WM-less display when it was shown without asking for
it, and **after a modal dialog is disposed: nothing gives the focus back to the frame** (BS4's observation, now with the
mechanism), so every key typed after a dialog is lost until something activates the frame again; with openbox the window
manager refocuses the frame and the keys land. Keys also go to the focus owner, not to what is under the pointer: with
the focus on a button they are typed into the button and seem ignored. The keymap is not the cause (every key gave the
expected letter). On the **macOS desktop** (this Mac, Java 25.0.1) Robot was constructed without error and **delivered
nothing**, pointer or key (no event reached AWT; no exception), while the window activated itself and `ROBOT` capture
worked. **Capture:** a `ROBOT` capture of the sample and a `PAINT` capture of its **root pane** are pixel-identical on
Linux (0 differing pixels in 23 of 24 comparisons; the 24th differs by the 24 pixels of the blinking caret); a `PAINT`
capture of the **frame** (`frame.printAll`, what the built `swing.capture` does) is not: on XToolkit the frame peer's
`print` draws imitation Motif decorations over the inset band, which on the WM-less display (insets 0) **paints a title
bar over rows 6 to 22 of the content and frame lines along its edges** (12,021 differing pixels). The conditions: a `ROBOT` rung and the V3 tier
need a probe that Robot's events arrive at all (they may be dropped silently), an activation check (and on a WM-less
display an explicit re-activation after a dialog closes) before any key, a check that the point lies in a window of the
target JVM that nothing of the JVM covers, and the target JVM holding the focused window on a person's desktop; and
`swing.capture` should paint the root pane, not the frame.

## What was run

`spikes/BS9/` (`README.md`; probes `KeysProbe` and `SampleProbe`, derived image `docker/Dockerfile`, `scripts/in-image.sh`,
`run.sh`) and spike BS10's harness in the same containers. `spikes/BS9/run.sh nowm-j8 nowm-j25 nowmx-j8 nowmx-j25 wm-j8
wm-j25`, then `amd64-nowm-j25`, then `mac-j25` once with the display lock held; every JVM ended with exit 0 (70 in all).
Evidence: `spikes/BS9/target/runs/<leg>/` (not committed; `env.txt`, the consoles `*.out`, `bs10/`, `keys/`,
`keysrepeat-*/`, `sample/`); the images kept are in `spikes/BS9/results/`.

| Leg | Image or host | Window manager | Java | Parts |
|---|---|---|---|---|
| `nowm-j8`, `nowm-j25` | `skillroy-test-java-ci:bs8-arm64` (Temurin 25 on Ubuntu 26.04.1, Xvfb 21.1.22) via `ci/run-in-linux.sh` | none | 1.8.0_504, 25.0.4.1 | BS10, keys, sample |
| `nowmx-j8`, `nowmx-j25` | `skillroy-spike-bs9:wm-arm64` (+ openbox 3.6.1, xdotool 3.20160805, x11-utils 7.7) | none | the same | keys (with xdotool and the X focus), keysrepeat |
| `wm-j8`, `wm-j25` | `skillroy-spike-bs9:wm-arm64` | openbox, started on the display before the target | the same | BS10, keys, sample |
| `amd64-nowm-j25` | `skillroy-test-java-ci:local` (emulated) | none | 25.0.4.1 | BS10, keys |
| `mac-j25` | this Mac, macOS 26.6.2, 1920x1080 points | the macOS desktop | 25.0.1 (Oracle OpenJDK) | BS10, keys, sample, strict mode |

openbox was chosen because it is a small stacking window manager implementing EWMH (`_NET_ACTIVE_WINDOW`,
`_NET_WM_USER_TIME`, transient-for stacking) with focus-new-windows and click-to-focus by default, and needs no session or
desktop environment (xfwm4 and metacity bring much of Xfce or GNOME). All packages came from Ubuntu's repositories.

## The table (like BS4's)

"Lands" means the outcome a person's input would have; for keys, all three letters typed into the intended field. Toy
pointer rows are BS10's Robot column (one run per leg); key rows are the key probe; the Mac ran in strict mode (README).

| Case | WM-less Xvfb, Java 8 | WM-less Xvfb, Java 25 | openbox, Java 8 | openbox, Java 25 | macOS, Java 25 |
|---|---|---|---|---|---|
| **Pointer** | | | | | |
| Click a button (count, enter, rollover) | lands | lands | lands | lands | does not land (no event) |
| Hover: tool tip after 150 ms | lands | lands | lands | lands | does not land |
| Click at a pixel: caret, table cell, list item | lands (caret 12, row 5, item 2) | lands | lands | lands | does not land |
| Drag: slider to 77, text selection 4-15 | lands | lands | lands | lands | does not land |
| Wheel, 3 notches | lands (first visible 9) | lands | lands | lands | does not land |
| Right-click popup menu, choose an item (own listener; `setComponentPopupMenu`) | lands | lands | lands | lands | does not land |
| Combo box: open, choose | lands | lands | lands | lands | does not land |
| Glass pane with a listener over a button | the glass pane gets it | the same | the same | the same | does not land |
| Disabled button | press delivered, no action | the same | the same | the same | does not land |
| Button covered by another window of the JVM | the covering window gets it | the same | the same | the same | does not land |
| Window blocked by a modal dialog | nothing delivered (as for a person) | the same | the same | the same | nothing (nothing ever arrived) |
| Sample: keypad keys DUE, CANCEL, RETURN (focus-gated, 60 ms debounce) | 3 of 3 fired | 3 of 3 | 3 of 3 | 3 of 3 | 0 of 3 |
| **Keys** (Robot `keyPress`/`keyRelease`, letters) | | | | | |
| K1 frame shown as by default, the application asked for nothing, pointer over the field | land in the focus owner, the button (not the field) | the same | the same | the same | frame focused, nothing delivered |
| K2 a Robot click into the field, then keys | lands | lands | lands | lands | nothing delivered |
| K3 the application asks the field for the focus, then keys | lands | lands | lands | lands | nothing delivered |
| K4 window shown with `setAutoRequestFocus(false)`, never activated, pointer over the field | **does not land**: X delivers to the Java window (`PointerRoot`), AWT drops | the same | **does not land**: X delivers to openbox's own focus window | the same | not sent (strict mode: the frame is not focused) |
| K5 then the application activates itself (`toFront` + `requestFocus`), keys | lands (activation 276 ms) | lands (387 ms) | lands (25 ms) | lands (30 ms) | activation works (32 ms), nothing delivered |
| K6 never-activated window, one Robot click into the field, keys | lands (the click activates the window) | lands | lands (click to focus) | lands | not run (strict mode, see README) |
| K7 a modal dialog of the frame shown and disposed, then keys with no focus action | **does not land** (frame not refocused within 2 s; X focus `PointerRoot`) | **does not land** | lands (refocused within 1 ms) | lands | frame refocused within 1 ms, nothing delivered |
| K8 then one Robot click into the field, keys | lands | lands | lands | lands | nothing delivered |
| Sample: keys into the focused entry field | lands ("abc") | lands | lands | lands | does not land |

The amd64 leg (Java 25, WM-less) gave the arm64 results in every pointer and key row (activation 424 ms). xdotool
without `--window` (XTEST, from outside the JVM, `nowmx-*` and `wm-*`) behaved like Robot in every key row, with one
exception on Java 8: the click that activates a never-activated window (K6) left the focus on the button in 1 of 6
xdotool clicks (the probe's run plus five repeats; 0 of 6 on Java 25; Robot 0 of 12). The toolkit source names the race:
a focus request from a component of a window whose `WINDOW_GAINED_FOCUS` is still in the event queue
(`XComponentPeer.requestFocus`, 8u504 `:306-313`); xdotool's click has no gap between press and release, the probe's
Robot click 25 ms.

## The capture comparison

`SampleProbe` took, three times each at the start and after the input, a `ROBOT` capture
(`Robot.createScreenCapture` of the frame's screen bounds, 15-39 ms) and two `PAINT` captures on the EDT: `frame.printAll`
(the built `swing.capture`) and the root pane alone printed at its offset in an image of the frame's size, compared
inside the root pane's bounds. Every differing pixel was attributed to the decorations (the inset band) or to the deepest
component under it.

| Leg | ROBOT vs PAINT(frame) | where | ROBOT vs PAINT(root pane), inside it |
|---|---|---|---|
| WM-less, Java 8 and 25 | 12,021 differing (6,116 by more than 32 in a channel) | across the top band and the borders of the content: imitation title bar and frame lines drawn over `promptLine`, `statusLine`, the table, the scroll panes | **0** in 11 of 12 comparisons; 24 pixels (1x24, the entry field's caret, blinking) in one |
| openbox, Java 8 and 25 | 30,364 (25,878) | 27,084 in the inset band (openbox's real title bar against the imitation), the rest where the imitation's lines cross the content | **0** in 12 of 12 |
| macOS, Java 25 | 37,390 (2,364) | 28,672 = the whole 28-pixel title band (the real title bar against background), the rest antialiasing | 8,718 (1,118): antialiasing on text and curves and the rounded window corners: the screen is a 2x Retina display, the `ROBOT` image is the 2x capture scaled to user space, `PAINT` renders at 1x |

- **Cause of the frame difference (Linux):** `XFramePeer.print` (8u504 `:440-665`) paints the frame's children and then
  renders "the Motif look ourselves": outer border lines, cross-hair lines 5 pixels in and a 17-pixel title bar from
  y = 6 with the title and the menu, minimise and maximise buttons, at fixed positions whatever the insets are, so with
  insets 0 they lie on the content. Dialogs do not do it (`XDialogPeer` has no `print`). The same counts on Java 8 and
  Java 25.
- **Decorations** are never in a `PAINT` capture; the window manager's are in a `ROBOT` capture.
- **Cursor:** not in the `ROBOT` capture on X11: during the after-input captures the X pointer was over the RETURN key
  (the last Robot click) and the root-pane comparison was 0. On the Mac the pointer was outside the window, so nothing
  is shown about the cursor there.
- **Text antialiasing:** identical on Linux (same rasteriser, 1x); different only through the Retina scaling on the Mac.
- **Personal data:** the images show the desk's signed-off screen only (fictional seed, no names); the Mac's `ROBOT`
  images show only the desk's window (with macOS's title bar) and were looked at before being kept.
- Kept in `results/`: `nowm-j25-{robot,paint-frame,paint-root,diff-frame}-initial.png` (`paint-root` and `robot` are
  the same file byte for byte), `wm-j25-{robot,paint-frame,diff-frame}-initial.png`,
  `mac-j25-{robot,diff-root}-initial.png`; in the diff images red is a difference above 32 in a channel, orange a
  smaller one, grey equal, dark grey not compared.

## F13: the verdict

Explained, for Java 8 and Java 25 on XToolkit:

1. **X focus.** A WM-less X server starts with the input focus at `PointerRoot` (`xdotool getwindowfocus` before the
   frame is shown). Java sets the X focus itself (`XSetInputFocus` on the frame's focus proxy, a child of the frame's
   shell window: observed `xFocus = <child of the frame window>`) when a frame is shown with `autoRequestFocus` (the
   default), on `toFront()` + `requestFocus()`, and when a component of an unfocused window asks for the focus (which
   Swing's press handlers do: the click that activates, K6) (`XComponentPeer.requestFocus` -> `XWindowPeer
   .requestWindowFocus` -> `XDecoratedPeer.requestXFocus` -> `XBaseWindow.xRequestFocus`, 8u504 `:280-330`,
   `:1939-1964`, `:1021-1037`, `:595-612`). Nothing else does: there is no window manager.
2. **Delivery.** XTEST key events (Robot, or xdotool without `--window`) go to the X focus; with `PointerRoot` to the
   window under the pointer. In K4 and K7 the Java window received every one of them (the XToolkit log,
   `sun.awt.X11.event.XWindow` at FINE, shows the three `KeyPress` events of each such case, Robot's and xdotool's, on
   the frame's content window), and **no** `KEY_TYPED` reached any component.
3. **The drop.** AWT posts a native key event to the event queue; `DefaultKeyboardFocusManager.preDispatchKeyEvent`
   retargets a posted key event to the focus owner, else to the focused window, and drops it when both are null
   (8u504 `:1052-1059`, research swing-driving F2). With no focused window in the JVM, the keys vanish.
4. **When there is no focused window** under a WM-less Xvfb: a window shown with `setAutoRequestFocus(false)`; after
   the focused window (for example a modal dialog) is disposed, when the X server reverts the focus to `PointerRoot`
   and nothing refocuses the frame (K7, 2 s observed; BS4's "nothing gives the focus back"); and an activation that loses
   a race (amendment A39, the `COMPONENT` rung's re-issue).
5. **Keys go to the focus owner,** never by pointer position: with the focus on a button the letters are typed into
   the button (K1), which looks exactly like "keys are ignored". swing-driving F13's cause (b) is real as well.
6. **Not the keymap:** every key produced the expected letter on both JDKs, Robot and xdotool (F13 (c) excluded).
7. **With a window manager** (openbox) a dialog's disposal refocuses the frame, so K7 lands; a never-activated
   window's keys go to the window manager's own focus window and never reach Java (the toolkit logged only the keys typed
   after activation).

What remains open: the original observation came from another application whose circumstances were not recorded; the
mechanism above reproduces it (keys after a dialog, or with the focus elsewhere) and nothing else was needed. The Java 8
click race (1 of 6) is a small sample.

## What a `ROBOT` rung and the V3 tier need

- **A session whose input path the OS honours, checked, not assumed.** X11 with XTEST (present on stock Xvfb; works with
  and without a window manager); on macOS the Accessibility permission for the responsible process. Without it Robot is
  constructed, throws nothing and delivers nothing (this Mac). So the rung needs a **delivery probe**: move to a point
  inside the target window and wait, bounded (1 s), for the native `MOUSE_MOVED` there (BS10's motion barrier); none
  means `ROBOT_UNAVAILABLE` with the reason, not a click that silently did nothing.
- **Capture permission:** on this Mac `createScreenCapture` returned the window's real pixels (with decorations), so the
  Screen Recording condition (research F6) did not bite here; whether that holds for another responsible process was
  not tried, and nothing was changed to find out.
- **Focus before keys:** type only when the target JVM's focused window is the intended one and the focus owner is the
  intended component (read on the EDT); otherwise activate first (`toFront` + `requestFocus`, re-issued once a second as
  A39 does: 266-424 ms WM-less, 25-30 ms with openbox, 32 ms on the Mac) or click into the field, and check again. On a
  WM-less display, **re-activate after every dialog closes**; nothing else will.
- **Self-activation** worked everywhere tried (WM-less, openbox, macOS 26 for an application started from a shell);
  macOS restricting it was not observed.
- **The point must lie in a window of the target JVM, on top:** Robot clicks whatever is at that screen point (the
  covered case: the covering window got it). Within the JVM the hit-test can name covering windows (BS10); other
  processes cannot be seen, so on a person's desktop V3 acts only while the target JVM holds the focused window (its
  windows in front) and stops when it loses it. On X11 a cheap check is available: `ROBOT` and root-pane `PAINT` of the
  target region are pixel-identical when nothing covers it.
- **Modal dialogs** need no extra check for Robot (input to a blocked window is dropped, as for a person), but the rung
  must notice that nothing arrived (an `AWTEventListener` for the call's duration: no `MOUSE_PRESSED` within the
  deadline is `NOT_DELIVERED`).
- **Exclusive use of the pointer, and restore it;** Robot on a worker thread, never the EDT; strict-mode rules (README)
  on any desktop with a person.

## Not verified

Windows (UIPI, session 0, the popup trigger on release), Wayland and XWayland, a locked or screen-saver session, Robot
with the Accessibility permission granted on macOS (not changed, by rule), HiDPI on Linux, a JDK line between 8 and 25,
the large-sample focus statistic of BS4, more than one run per leg (the smoke runs before the final ones gave the same
results; the Java 8 click race was seen in 2 of 7 xdotool clicks over both).
