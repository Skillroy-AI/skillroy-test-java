# Spike BS9: Robot input on the test display and on a desktop session

Question (`docs/TODO.md`, "BS9-BS12 Vision and human-like input"; research `docs/research/vision-input.md` F6, F9-F11,
R10 and `docs/research/swing-driving.md` F1, F12, F13): do `java.awt.Robot` pointer and key events land in a Swing
application on the WM-less Xvfb the CI uses and on desktop sessions, on Java 8 and Java 25 targets; how does a `ROBOT`
screen capture of the sample compare with a `PAINT` capture; and why did XTEST keys not reach a Swing application under
a WM-less Xvfb (swing-driving F13)? The result is in `RESULT.md`.

## What is here

- `pom.xml`: a standalone Maven project (not a reactor module). It builds `target/bs9-probe.jar`, Java 8 language and
  API, no dependencies, with two probes that run inside the target JVM:
  - `KeysProbe` (the jar's main class): a small frame of its own, "BS9 keys" (a button first in the focus order, then a
    text field), and letter keys under different focus and activation states, by Robot and, where it is installed, by
    `xdotool key` without `--window` (XTEST). Scenarios: `activated` (the frame asks for the focus when shown, as by
    default), `unactivated-hover` (shown with `setAutoRequestFocus(false)`, the pointer over the field, no click; then
    the application activates itself), `unactivated-click` and `unactivated-xclick` (the same window, one Robot or one
    xdotool click into the field, then keys), `after-dialog` (a modal dialog of the frame shown and disposed, then keys
    with no focus action). Before every case it reads the AWT focus (focused window, focus owner) and, with xdotool,
    the X server's focus window, its parent and `_NET_ACTIVE_WINDOW`; after it, which component received the typed
    characters. On Linux the XToolkit's own log of every X key event the window receives is switched on
    (`scripts/awt-key-logging.properties`).
  - `SampleProbe` (the jar's `Premain-Class`): loaded with `-javaagent` into the sample lending desk launched from its
    built jar (`samples/skillroy-sample-lending-app/target/...jar` and HSQLDB on the class path, as the scenario tests
    launch it). It compares a `ROBOT` capture (`Robot.createScreenCapture` of the frame's screen bounds) with two `PAINT`
    captures (`frame.printAll` as the built `swing.capture` does, and the root pane alone at its place), three times, and
    attributes every differing pixel to the decorations or to the deepest component under it; then Robot keys into the
    focused entry field; Robot clicks on the keypad keys DUE, CANCEL and RETURN (no-ops in the signed-off state; each
    counted by an action listener the probe adds); the same keys by routed window-sourced events and by the built
    DISPATCH rung's delivery; and the captures again with text in the field.
- `docker/Dockerfile`: the derived image `skillroy-spike-bs9:wm-arm64`, the arm64 test image plus `openbox`, `xdotool`
  and `x11-utils` from Ubuntu's own repositories (why openbox: see the file). The base image is not modified.
- `scripts/in-image.sh`: runs inside a container started by `ci/run-in-linux.sh` (so under `xvfb-run`, 1024x768x24):
  starts openbox first when asked, then the parts asked for (`bs10`: spike BS10's harness; `keys`; `keysrepeat`: the two
  click-to-activate scenarios five times each; `sample`), each JVM bounded by `timeout`.
- `run.sh`: the legs (below). `results/`: the capture images kept as evidence.

## How to run

```
mvn -o -q -f spikes/BS9/pom.xml package && mvn -o -q -f spikes/BS10/pom.xml package
SKILLROY_JDK8_HOME=/path/to/linux-arm64-jdk8 spikes/BS9/run.sh      # nowm-j8 nowm-j25 nowmx-j8 nowmx-j25 wm-j8 wm-j25
spikes/BS9/run.sh amd64-nowm-j25                                     # the amd64 image (emulated, slow)
BS9_MAC_LOCK=/path/to/lock-dir BS9_RUNS=/tmp/somewhere spikes/BS9/run.sh mac-j25
```

| Leg | Image | Window manager | Parts |
|---|---|---|---|
| `nowm-j8`, `nowm-j25` | `skillroy-test-java-ci:bs8-arm64`, as the CI runs it | none | bs10, keys, sample |
| `nowmx-j8`, `nowmx-j25` | `skillroy-spike-bs9:wm-arm64` (xdotool, xprop) | none | keys, keysrepeat |
| `wm-j8`, `wm-j25` | `skillroy-spike-bs9:wm-arm64` | openbox, started on the Xvfb display before the target | bs10, keys, sample |
| `amd64-nowm-j25` | `skillroy-test-java-ci:local` (emulated) | none | bs10, keys |
| `mac-j25` | this Mac, Java 25 natively | the macOS desktop | bs10, keys, sample, all in strict mode |

Evidence per leg: `target/runs/<leg>/env.txt` (the environment and the exit code of every JVM), `<part>.out` (each
JVM's console), `bs10/` (BS10's `summary.md`, `results.tsv`, `events.txt`), `keys/` (`keys-<scenario>.tsv` and `.log`),
`sample/` (`sample.tsv`, `sample.log`, the PNGs).

**Strict mode (the Mac leg), because it runs on a person's desktop:** the probe positions its own window; a pointer
action happens only at a point inside one of the probe JVM's showing windows and only while that JVM holds the focused
window (so its windows are in front); a key press only after the probe's own frame is checked to be the focused window,
both by a `WindowFocusListener` flag and by a read on the EDT, before every single press, and only letter keys (no
Enter, Tab, modifiers or command keys); a lost focus aborts the key part; no click is ever sent to a window of an
inactive application (Java cannot tell whether another application's window covers it); the pointer is put back where
it was; every JVM halts itself after 55 s (50 s for the key probe); the leg takes the display lock directory first
(`mkdir`, atomic; waiting up to 30 minutes) and removes it at the end; nothing changes a system or privacy setting.
The ROBOT captures show whatever is on that desktop, so `BS9_RUNS` should point outside the repository and the images
must be looked at before any is kept.
