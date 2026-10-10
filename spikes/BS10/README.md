# Spike BS10: routed in-JVM dispatch against Robot

Question (`docs/TODO.md`, "BS9-BS12 Vision and human-like input"; research `docs/research/vision-input.md` F7, F8,
F10, Risk2): does a synthetic `MouseEvent` whose source is the heavyweight window, in window coordinates, dispatched
non-posted on the EDT (so that the window's `LightweightDispatcher` picks the target) behave like a real pointer event
from `java.awt.Robot`, case by case, on Java 8 and Java 25 targets? The result is in `RESULT.md`.

## What is here

- `pom.xml`: a standalone Maven project (not a reactor module). It builds `target/bs10-harness.jar`, Java 8 language and
  API, no dependencies. The jar is both the toy application (`Main-Class`) and the harness (`Premain-Class`), so the
  harness runs inside the target JVM as the agent would.
- `Toy`: one window, Metal look and feel on every platform, absolute layout (the same points on every JDK): a counting
  button with a tool tip, a disabled button, a button under a glass pane, a button another window can cover, a home
  button (the focus is parked there between runs), two text fields, a label with a `JPopupMenu` opened by its own mouse
  listener and one with `setComponentPopupMenu`, a combo box, a table, a list of 200 items in a scroll pane, a slider;
  two glass panes (one with a mouse listener, one without), an owned `JWindow` that covers a button, an application-
  modal dialog.
- `Harness`: 16 cases, each run through three drivers after a reset (counters, selections, scroll position, popups,
  the focus on the home button, the pointer on a neutral point with a motion barrier so that no late native event of
  the reset lands in the next recording):
  - `ROBOT`: `java.awt.Robot` on the harness thread (never the EDT), screen coordinates;
  - `ROUTED`: window-sourced events in window coordinates, each posted with `invokeLater` and dispatched there with
    `window.dispatchEvent`; a move to the point first, press with the button's down mask, release, click with the same
    count, the popup trigger on the press (X11, macOS) or release (Windows), drags with the button-1 down mask and a
    release with click count 0, one wheel event per notch (`Drivers.RoutedDriver`);
  - `DISPATCH`: component-sourced events, exactly as the built rung (`Clicks.mouseClick`: press, release, click at the
    component's centre in one EDT task) where the built rung applies, and the same delivery extended (another button, a
    click count, a drag, the wheel, a hover) where it cannot express the case; the summary marks those as extended.
- Per case and driver it records the outcome, the focus owner afterwards, the component that received the first press,
  the hit-test of the first point (`SwingUtilities.getDeepestComponentAt` and a public-API re-implementation of AWT's
  mouse-target search, `HitTest`), and every mouse event AWT dispatched (an `AWTEventListener`; consecutive moves and
  drags collapsed with a count).

## How to run

Build (offline), then run the legs; the environments are spike BS9's (its derived image with openbox and its
in-image script):

```
mvn -o -q -f spikes/BS10/pom.xml package
SKILLROY_JDK8_HOME=/path/to/linux-arm64-jdk8 spikes/BS10/run.sh            # nowm-j8 nowm-j25 wm-j8 wm-j25
spikes/BS10/run.sh amd64-nowm-j25                                           # the amd64 image, emulated
BS10_MAC_LOCK=/path/to/lock-dir spikes/BS10/run.sh mac-j25                  # this Mac's desktop, see below
```

`spikes/BS9/run.sh` runs the same harness in the same legs together with BS9's probes (one container per leg); the
results in `RESULT.md` come from that run (`spikes/BS9/target/runs/<leg>/bs10/`).

Per leg the harness writes `summary.md` (the table), `results.tsv` (one row per case and driver) and `events.txt` (the
dispatched events per case and driver) under `target/runs/<leg>/bs10/`, and prints one `BS10 case=...` line per run.
A watchdog halts the JVM after the deadline (240 s, 55 s on the Mac).

The Mac leg (`strict=true`): the toy is placed at 160,140; Robot acts only while a window of the toy's JVM is focused
and only at points inside the toy's own windows; the harness sends no keys; the pointer is put back where it was; the
run takes the display lock directory first (waiting up to 30 minutes) and removes it afterwards.
