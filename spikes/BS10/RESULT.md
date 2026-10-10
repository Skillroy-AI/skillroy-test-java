# BS10 result: routed in-JVM dispatch against Robot (2026-10-10)

**Verdict: passed with conditions.** On Java 8 (Temurin 1.8.0_504) and Java 25 (Temurin 25.0.4.1) targets, on the
WM-less Xvfb the CI uses and with openbox on the same display (arm64 image), and on Java 25 in the emulated amd64 image,
a mouse event whose source is the heavyweight window, in window coordinates, dispatched non-posted on the EDT
(`ROUTED`) produced **the same outcome as `java.awt.Robot` in 14 of 16 cases**: a button (with enter and rollover), a
tool tip on hover, caret placement at a pixel, a text-selection drag, a double-click, a popup menu opened by its own
listener and one set with `setComponentPopupMenu` (item chosen), a combo box (opened, item chosen), a table cell, the
mouse wheel, a slider drag, a glass pane with a mouse listener (it takes the click) and one without (the button under it
takes it), a disabled button (the event is delivered, nothing fires). AWT dispatched the same mouse events to the same
components in 13 of those 14 (moves and drags counted apart: the event queue coalesces them); in the combo case the X11
toolkit drops the `MOUSE_CLICKED` of the press that opens the popup and the routed path delivers it, with no effect on
the outcome. The two cases that differ, differ by construction and are the conditions: **(1) a window that a modal
dialog blocks receives routed events** (Robot's click on it delivers nothing; routed and DISPATCH click the button), so
`swing.pointer` must keep the modal check of the other input primitives; **(2) routing knows nothing of other windows
on top**: a point covered by another window (here an owned `JWindow` of the same JVM) is clicked through by routed
dispatch, while Robot hits the covering window, so `swing.hitTest` must report a showing window of the JVM that covers
the point, and V2 must say that it proves nothing about stacking against other processes. Further conditions: **(3)** a
public-API re-implementation of AWT's mouse-target search misses components that receive mouse events only through an
enabled event mask (`setComponentPopupMenu` calls `enableEvents(MOUSE_EVENT_MASK)` and adds no listener), so the hit-test
needs that rule and the pointer result must report the component that actually received the press; **(4)** the routed
sequence must imitate the native one (below), otherwise enter/exit, rollover, the release after a drag and the popup
trigger differ. On the macOS desktop (Java 25.0.1, this Mac) `ROUTED` gave the same 16 outcomes as on Linux, while Robot
delivered no event at all (no Accessibility permission for the process; nothing is reported, see BS9). The built
`DISPATCH` rung differs from Robot wherever the position inside the component matters (caret, list item, table row,
combo item: it clicks the centre), bypasses a glass pane (it clicks the covered button and moves the focus there), sends
no enter or move (no rollover), and, like routed, ignores modality and stacking.

## What was run

`spikes/BS10/` (harness and toy, `README.md`), run by `spikes/BS9/run.sh` in one container per leg together with BS9's
probes; the evidence is in `spikes/BS9/target/runs/<leg>/bs10/` (`summary.md`, `results.tsv`, `events.txt`) and
`<leg>/bs10.out`. Every leg ran all 16 cases on the three drivers, 48 runs, in 27-30 s (43 s on the Mac); every JVM
ended with exit 0; the motion barrier (the reset waits until the native move to the neutral point has been dispatched)
never missed on Linux and missed twice on the Mac, where no native event arrived at all.

| Leg | Target | Display | Frame on screen, insets | Focused after show |
|---|---|---|---|---|
| `nowm-j8` | 1.8.0_504, arm64 | Xvfb 1024x768, no window manager (the CI's) | 40,40, insets 0 | yes, 3 ms |
| `nowm-j25` | 25.0.4.1, arm64 | the same | 40,40, insets 0 | yes, 36 ms |
| `wm-j8` | 1.8.0_504, arm64 | the same Xvfb with openbox 3.6.1 (derived image) | 40,40, insets 20,1,5,1 | yes, 14 ms |
| `wm-j25` | 25.0.4.1, arm64 | the same | 40,40, insets 20,1,5,1 | yes, 32 ms |
| `amd64-nowm-j25` | 25.0.4.1, amd64 (emulated) | Xvfb, no window manager | 40,40, insets 0 | yes, 130 ms |
| `mac-j25` | 25.0.1 (Oracle OpenJDK), arm64 | macOS 26.6.2 desktop, 1920x1080 points, strict mode | 160,140, insets 28,0,0,0 | yes, 48 ms |

The amd64 leg gave exactly the arm64 outcomes; the architecture does not matter here. Java 8 and Java 25 gave the same
outcome, focus owner, press target and event sequence in every case of every Linux leg.

## Per case

R = Robot, Ro = routed, D = DISPATCH. The outcomes are the same on all five Linux legs; the Mac column gives the routed
outcome there (Robot delivered nothing on the Mac). "Press target" is the component that received the first
`MOUSE_PRESSED` (from an `AWTEventListener`).

| Case | Robot | Routed | DISPATCH | Routed = Robot | DISPATCH = Robot | Mac, routed | Difference and cause |
|---|---|---|---|---|---|---|---|
| button | count 1, entered 1, rollover | the same | count 1, entered 0, no rollover | yes | **no** | the same as Linux | DISPATCH sends press, release, click only: no enter, no rollover |
| tooltip (hover 150 ms) | shown | shown | shown (extended: enter + move to the button) | yes | yes | shown | none |
| caret (left third of character 12) | caret 12 | 12 | 36 (Mac 39) | yes | **no** | 12 | DISPATCH clicks the field's centre |
| select-drag (character 4 to 15, 6 steps) | selection 4-15 | 4-15 | 4-15 (extended) | yes | yes | 4-15 | none; the native release after a drag has click count 0 (routed imitates it) |
| double-click (list item 2) | 1 double-click on item 2 | the same | on item 4 (extended, the visible centre) | yes | **no** | the same | DISPATCH: centre |
| popup, own listener (right-click, choose "Beta") | Beta, closed | Beta, closed | Beta (extended right-click) | yes | yes | Beta | popup trigger on the press on X11 and macOS; the popup lies under the pointer, so neither path delivers the click after the release; the item fires on release, no click follows |
| popup, `setComponentPopupMenu` ("Two") | Two | Two | Two (extended) | yes | yes | Two | none; the hit-test re-implementation finds no mouse target here (condition 3) |
| combo (open, choose item 2 "Saw") | Saw, closed | Saw, closed | "Ladder" (the list's centre) | yes | **no** | Saw | X11 drops the click after the press that opens the popup, routed delivers it (no effect); DISPATCH: centre |
| table (row 5, column 1) | row 5, col 1 | the same | row 6, col 1 | yes | **no** | the same | DISPATCH: centre |
| wheel (3 notches down) | first visible 9, view y 153 | the same | the same (extended) | yes | yes | the same | none; native and routed: 3 events, rotation 1, unit scroll, amount 3, retargeted to the scroll pane |
| slider-drag (thumb to 3/4, 8 steps) | value 77 | 77 | 77 (extended) | yes | yes | 77 | none |
| glass-listening (glass pane with a listener over a button) | glass 1, button 0 | glass 1, button 0 | glass 0, **button 1** | yes | **no** | glass 1 | DISPATCH bypasses the glass pane and moves the focus to the button |
| glass-transparent (glass pane without listeners) | button 1 | button 1 | button 1 | yes | yes | button 1 | none: AWT routes past a visible component that does not listen |
| disabled button | 0 actions, 1 press delivered | the same | the same (raw; the built rung refuses a disabled component) | yes | yes | the same | none: neither path checks `isEnabled`, the UI delegate ignores the press |
| covered (an owned `JWindow` over the button) | **cover 1**, button 0 | button 1 | button 1 | **no** | **no** | button 1 | routing is per window: it cannot see a window on top (condition 2) |
| modal (an application-modal dialog open) | **count 0**, no event at all | count 1 | count 1 | **no** | **no** | count 1 | AWT's modal blocking acts on native input (the blocked window's events are dropped) and on the event queue's filter; a `dispatchEvent` from inside an `invokeLater` task passes neither (condition 1) |

Focus after the click was the same for Robot and routed in every case (the clicked button, field, list, table, combo or
slider; unchanged after a hover, a wheel, a popup item, a click on the listening glass pane or on the disabled button);
DISPATCH differed only for the glass pane (the button under it) and the covered button.

## What the event logs show

`events.txt` holds, per case and driver, every mouse event AWT dispatched to a component. Comparing Robot with routed:

- Same sequence in 13 of 14 equal-outcome cases: `ENTERED` and `MOVED` on the target before the press, `PRESSED` with
  the button's down mask and click count 1, `RELEASED` and `CLICKED` with modifiers empty; the second click of a
  double-click with click count 2; the right press flagged `popupTrigger`; enter and exit generated by the window's
  lightweight dispatcher as the lightweight popup appears under the pointer (`EXITED popupArea`, `ENTERED popup`).
- A retargeted button-3 release carries `META_DOWN_MASK` in its extended modifiers, natively and routed alike: AWT's
  retargeting passes `getModifiersEx() | getModifiers()` and the old `BUTTON3_MASK` is the `META_MASK` bit. A primitive
  comparing modifiers must expect it.
- The native release after a drag has click count 0 and no `CLICKED` follows: XToolkit posts a click after a release
  only while the button is still "click allowed", and any motion beyond the multi-click smudge, or any motion after the
  multi-click time, clears that state and the click count (`XWindow.handleButtonPressRelease` and
  `handleMotionNotify`, 8u504 `:718-731`, `:783-799`).
- Native moves and drags are coalesced by the event queue (7 or 8 drags for 8 Robot moves); the count is not an
  outcome.
- The combo case: natively `PRESSED combo, RELEASED combo, EXITED combo`; routed adds `CLICKED combo` after the release.
  The toolkit posts a click after every release without a drag (`:718-731`); that the lightweight dispatcher then does
  not deliver it is most likely the pointer grab Swing's popup takes when it opens (`BasicPopupMenuUI`'s grabber through
  `SunToolkit.grab`), not confirmed in the source. No outcome depended on it.
- DISPATCH: no enter, exit or move; for the right-click, the release and click go to the component even with the popup
  under the point (no lightweight dispatcher in the path).

## Java 8 against Java 25, and the platforms

No difference between Java 8 and Java 25 in any case, focus, press target or event sequence (Linux, both window
managers). openbox changed nothing for routed dispatch (the frame's coordinates include the insets; the points were
computed in them). On the Mac the routed outcomes were those of Linux; the popup trigger is on the press there too.
DISPATCH's centre lands on another character on the Mac (39 against 36: other fonts), which is the point of the caret
case.

## What `swing.hitTest` must report

- **The window** the point is in, and **every other showing window of the JVM that covers the point** (owned windows,
  popups, dialogs; in screen space by their bounds), flagged as covering: routed dispatch would go through them (the
  covered case). Other processes' windows cannot be seen (`stackingAssumed` as in research R2).
- **`deepest`** (`SwingUtilities.getDeepestComponentAt`) and **`mouseTarget`** (what AWT delivers to): they differ for a
  glass pane without listeners (deepest: the glass pane; target: the button under it), and AWT's own target is the
  deepest *listening* component. The public-API re-implementation (`HitTest`, the filter read through the listener
  arrays) named the component that received the routed press in 13 of the 14 cases with a press; it missed the label
  with `setComponentPopupMenu` (no listener, only an enabled event mask: `JComponent.setComponentPopupMenu`, 8u504
  `:530-533`; `Container.MouseEventTargetFilter`, `:2454-2467`). Add the public rules: a `JComponent` with
  `getComponentPopupMenu() != null` or `getAutoscrolls()` (which also enables mouse events) counts as listening.
  Anything else that calls `enableEvents` stays invisible, so the result says `mouseTarget` is inferred, and
  `swing.pointer` reports the real one.
- **The wheel target** apart: a wheel event goes to the mouse target and from there to its nearest ancestor with a
  wheel listener (the list's scroll pane here, natively and routed alike).
- **Enabled state** of the target: a disabled component receives the press and does nothing; the hit-test reports
  `enabled: false` so that a recorder does not record a click that had no effect.
- **The modal blocker** of the window (`blockedBy`), since routed input would reach a blocked window.
- **The local point and its fraction** inside the target (caret, list, table and slider outcomes depend on it).

## What `swing.pointer` ROUTED must do

- **Address:** the heavyweight window as the event source, window coordinates (the window's origin, decorations
  included), screen coordinates filled in (`xAbs`, `yAbs`); for a point in a heavyweight popup or another window, that
  window as the source.
- **Sequence:** a `MOUSE_MOVED` to the point first when the last routed pointer position was elsewhere (it drives enter,
  exit, rollover and tool tips through the lightweight dispatcher); `MOUSE_PRESSED` with the button's down mask and the
  click count; `MOUSE_RELEASED` with the remaining buttons' mask; `MOUSE_CLICKED` with the same count unless the pointer
  moved while pressed (X11 also drops it after a motion later than the multi-click time); each event in its own
  `invokeLater` task, letting the EDT run what the previous event queued (a popup shown by the press) before the next
  one.
- **Click count:** 1, then 2 for a second click on the same point within the double-click interval, as the caller asks.
- **Popup trigger:** on the press on X11 and macOS, on the release on Windows (Windows not run); button 3.
- **Drag:** press, `MOUSE_DRAGGED` with the button's down mask per step, release with click count 0, no click.
- **Wheel:** one `MouseWheelEvent` per notch, rotation 1 or -1, unit scroll, scroll amount 3 (X11's value; the Mac's
  native amount was not observed, Robot delivering nothing there).
- **Modifiers:** the extended masks; expect AWT's `META` bit on a retargeted button-3 event.
- **Before acting:** refuse when a modal dialog blocks the window (`MODAL_OPEN`, `force` as elsewhere) and when another
  showing window of the JVM covers the point (or report it), since neither is visible to the routing.
- **After acting:** report the component that received the press (an `AWTEventListener` for the duration of the call,
  or the dispatcher's retargeted event), the focus owner afterwards, and `fired`; a press that nothing received (the
  point lies in no listening component) is reported as such.
- **Focus:** routed clicks change the focus exactly as Robot's did here because the window was focused; in a window
  that is not focused, Swing's press handlers ask for the focus and the toolkit activates the window (BS9 K6), which is
  a side effect the result must report; the keyboard primitives should target the focus owner or the last clicked
  component (research F8).
- **The real pointer is not moved**: components that read `MouseInfo` (none in these cases) see the person's pointer.

## Not verified

Windows (popup trigger on the release, UIPI), a heavyweight popup (every popup here fit into the window and was
lightweight), native drag and drop, components that read `MouseInfo` or toolkit state, the Mac's native event sequence
(Robot delivered nothing there; the Mac column compares routed with Linux's Robot), a JDK line between 8 and 25,
repetitions beyond one run per leg (the outcomes were identical in the smoke runs before the final ones).
