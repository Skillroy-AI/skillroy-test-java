# BS8: JCEF in the Linux test image

Run on 2026-10-06. **Proves** (TODO.md, "Base spikes"): a real embedded browser runs in the public test image, so that
the in-process web path of Phase 4 can be verified in the open (B9); answers the "not verified" items of
`docs/research/embedded-browser-spi.md` F1, F8-F12, F14 and F15.

**Verdict: passed with conditions.** JCEF 152 (jcefmaven 152.0.6: java-cef `17e805a`, CEF 152.0.6, Chromium
152.0.7977.83) runs under the image's window-manager-less Xvfb in both rendering modes, on a Java 25 and a Java 8
target, and the in-process DevTools channel does what the research note read in the source: evaluation with a
result, promises, script errors, load and console events, document-start scripts, screenshots in both modes,
trusted input, a viewport override on a hidden browser, and the reflective enumeration of browsers. The conditions,
each a finding below: (1) Chromium 152's first-run terms-of-service dialog must be pre-accepted with two sentinel
files, or `CefInitialize` never returns; (2) `Target.getTargetInfo` does not answer through this channel, so the
CDP target id has to be obtained another way; (3) a page whose iframe uses `srcdoc` never finished loading in the
image (the renderer idles), while plain pages and pages with a `data:`-URL iframe load; (4) a browser whose component
is not showing is not created until the application asks for it, and the first evaluation after a navigation timed
out once; (5) `printAll` throws for the off-screen component (a JOGL canvas) and paints nothing for the windowed
one; (6) on the development machine the runs that count were made on an **arm64** build of the same image, because
x86-64 emulation (the way this machine runs the project's `linux/amd64` image) runs about four times slower and hung for the same reason (the dialog) until the sentinel files were in place; with them the x86-64 image works as well (F-I). The numbers are in
§4; the raw results are the `result.json` files the runs leave under `target/results*/`.

## 1. What was run

- `Bs8Probe` (`src/main/java/…/Bs8Probe.java`, Java 8 bytecode): a Swing host that embeds JCEF through jcefmaven's
  `CefAppBuilder` the way an application would, shows one `JFrame` with a `CardLayout` of two browsers (card `visible`
  showing, card `hidden` not showing: a cached browser), loads pages from `data:` URLs (no network, no files) and then,
  through `CefBrowser.getDevToolsClient()` and nothing else, measures: `Runtime.evaluate` (20 round trips, values,
  `awaitPromise`, a thrown error, a rejected promise, `undefined`, objects, JSON text), the thread the futures complete
  on, `Target.getTargetInfo` and `/json/list` with a configured port, `Page.enable` + `Runtime.enable` and the console
  event, `Page.addScriptToEvaluateOnNewDocument` proven by a page whose own inline script looks for the init script,
  CEF frame identifiers against `Page.getFrameTree`, `Emulation.setDeviceMetricsOverride` on the hidden browser,
  `Input.dispatchMouseEvent` on a counting button, `Page.captureScreenshot` on both browsers, `createScreenshot`,
  `printAll` over the UI component and the frame, the reflective walk `CefApp.self -> clients_ -> getAllBrowser()`,
  the private `settings_`, process facts (RSS, helper processes), and `dispose`. Every step is recorded with its
  duration and either a result or the error, so one failing step never hides the others (`result.json`).
- `run.sh`: the four configurations windowed/off-screen x Java 25 (the image's) / Java 8 (mounted at `/opt/jdk8`),
  each in a fresh container through `ci/run-in-linux.sh` (`xvfb-run -a -s "-screen 0 1024x768x24 -nolisten tcp"`,
  no window manager), remote-debugging port 9229 set, 600 s budget. The off-screen runs on Java 25 carry the three
  `--add-exports` flags JOGL needs on JDK 16+ (jcefmaven README).
- The image: `ci/Dockerfile` with the fifteen Chromium run-time libraries added on 2026-10-06 (§3), built as
  `linux/amd64` (the project's CI architecture, run under emulation here) and as `linux/arm64` (native on this
  machine; `ci/run-in-linux.sh` gained `SKILLROY_PLATFORM` for it). The Java 8 target on arm64 was Temurin 8u504
  (Linux aarch64), kept outside the repository.
- jcefmaven's natives come from Maven Central as a dependency of the spike's POM (`jcef-natives-linux-amd64`,
  152,141,475 bytes; `jcef-natives-linux-arm64`, 167,953,639 bytes), never downloaded at run time; jcefmaven extracts
  them once into `target/jcef-bundle-<platform>` (469,062,711 bytes extracted for arm64; 2.4-2.7 s).

## 2. Findings

### F-A The first-run terms-of-service dialog blocks `CefInitialize` (new; not in the research note)

Every first attempt, in every configuration, stopped inside `CefApp.N_Initialize` on the EDT, with `CefInitialize`
never returning; the probe's watchdog ended the runs. The Chromium processes (two zygotes, the GPU process, the
network and storage services) were alive and idle; the CEF UI thread (`CrBrowserMain`) pumped its GLib loop; the X
server was idle. `xwininfo -root -tree` inside the container showed the reason: a 600 x 507 window **"Chromium
Additional Terms of Service"** with the body "This Space Intentionally Blank. In official builds this space will show
the terms of service." and the buttons Cancel and Accept (`target/results-diag/screen-tos-dialog.png`). CEF 152's
Chrome bootstrap shows Chromium's first-run terms dialog and waits for it; `--no-first-run`, `--password-store=basic`,
`--disable-extensions`, `--no-zygote`, `--disable-gpu`, `--in-process-gpu`, `--single-process` and Docker's seccomp
filter switched off made no difference; jcefmaven 146.0.10 (Chromium 146) behaved the same.

Accepting the dialog (an XTEST click through `xdotool`) let `CefInitialize` return and created the profile; the
acceptance wrote two **empty sentinel files into the root cache path**: `First Run` and `EULA Accepted`. **Pre-creating
both before `CefApp` is built skips the dialog** (the probe's `--accept-tos`, default true): every later run
initialised in 80-160 ms after the natives were loaded. Consequences for Phase 4: the web host sample and any CI must
set `CefSettings.root_cache_path` to a directory of their own and create the two files in it before `CefAppBuilder.build()`;
the contract records this (phase4-contracts.md §2.5, §9.1). Not verified: whether an official (branded) build or a
future CEF changes the file names; a policy or switch that suppresses the dialog was not found in the binary
(`strings libcef.so`: `TosHasNotBeenAccepted`, `IDS_TOS_NOTIFICATION_*`, `IDS_FIRST_RUN_DIALOG_WINDOW_TITLE`).

### F-B The in-process DevTools channel works (research F8, F10, F12, F16: verified)

Measured on arm64 natively, off-screen mode, Java 25, the plain page (§4 has the other configurations):

| Item | Result |
|---|---|
| `getDevToolsClient()` | non-null 109-191 ms after the browser's component was shown; one instance per browser (`sameInstance: true`, `isClosed: false`) |
| `Runtime.evaluate` with `returnByValue` | `1+1` -> `{"result":{"type":"number","value":2}}`; 20 round trips p50 **0.11 ms**, min 0.06 ms, max 2.4 ms |
| `awaitPromise` | `new Promise(r => setTimeout(() => r(7), 30))` -> 7 |
| a thrown error | `exceptionDetails` with `text: "Uncaught"` and the `Error: bs8 boom` description and stack |
| a rejected promise | `exceptionDetails.text: "Uncaught (in promise) Error: bs8 rejected"` |
| `undefined`, an object, JSON text | `{"type":"undefined"}`; `{"a":1,"b":[1,2],"c":"x"}`; a string crosses as a string |
| completion thread | the futures complete on **`AWT-EventQueue-0`** in off-screen mode (research F4, §C1 confirmed): an adapter must never wait on the EDT for a result |
| `Page.enable`, `Runtime.enable` | `{}`; then `Runtime.executionContextCreated`, `Page.frameStartedNavigating`, `frameStartedLoading`, `frameNavigated`, `domContentEventFired`, `loadEventFired`, `frameStoppedLoading`, `Runtime.consoleAPICalled` (for `console.log('bs8-console-marker')`) all arrive, on the EDT |
| `Page.addScriptToEvaluateOnNewDocument` | accepted; the next document's inline script saw `typeof window.__bs8Init === "number"` (document-start timing) (`initType: "number"`, `seenAtPageScript: "number"`, `readyStateAtInit: "loading"`; the next document loaded in 42-130 ms), in both modes and on both Javas |
| `Input.dispatchMouseEvent` | `mouseMoved`, `mousePressed`, `mouseReleased` at the button's centre incremented the page's counter to 1 (trusted input works without a port) |
| `Page.captureScreenshot` | an 800 x 600 PNG with more than 64 distinct colours, 62 ms, 7 KB, off-screen mode; in windowed mode the same call gives the same 800 x 600 page image (53 ms): `Page.captureScreenshot` is the mode-independent bitmap, as research F11 hoped |
| `createScreenshot(false)` | off-screen: an 800 x 600 image with the page (38 ms); windowed: `UnsupportedOperationException: Unsupported for windowed rendering` (research F11 confirmed) |
| `Emulation.setDeviceMetricsOverride` | on the hidden browser created with `createImmediately()`: `innerWidth x innerHeight` read `1 x 1` before (research F12's stale viewport, confirmed), `1024 x 768` after the override, and `1 x 1` again after `clearDeviceMetricsOverride`; a `Page.captureScreenshot` of that browser is a 1 x 1 PNG until the override is applied |
| `Target.getTargetInfo` | **no answer within 30 s** (research F14: the expected route does not work through this channel); `Target.getTargets` does not answer either (the client is a page-level session; the `Target` domain is not served). **The CDP target id is the main frame's id**: `Page.getFrameTree().frameTree.frame.id` (`306B0152…`) equals the `id` of the page's entry in `/json/list`, which is how the adapter reports `cdpTargetId`. `/json/version` and `/json/list` on the configured port answer (`Chrome/152.0.7977.83`, protocol 1.3, the browser's `webSocketDebuggerUrl`; one `page` entry per created browser with `id`, `title`, `url`, `webSocketDebuggerUrl`) |
| CEF frame ids against protocol frame ids | different id spaces, as research F7 expected: `getFrameIdentifiers()` gives `8-00D021D5…`, `Page.getFrameTree` gives `C0750364…`; no mapping by value. `getFrameIdentifiers()` returns **null** before the first document (the probe's first version crashed on it) |

### F-C The hidden browser, and the one timeout

A browser in a `CardLayout` card that is not showing gets **no native browser** in either mode (no DevTools client
within 60 s; `getIdentifier()` stays -1): `CefBrowserWr` and `CefBrowserOsr` create it when their component is
displayed, not when it is displayable. `createImmediately()` creates it (DevTools client 50 ms later). For the agent
this means a cached, hidden browser exists only when the application created it, and `web.browsers` must expect
handles without a native browser (`isClosed()`-like: no document, no channel). With `--create-hidden true` (off-screen, Java 25) the created hidden browser's first DevTools call failed with `DevToolsException: Client is closed` and every later call worked (research F9: a client can be closed and re-created; re-fetch it when `isClosed()`); the hidden page evaluated (`document.visibilityState` is `visible` although nothing shows it), accepted the viewport override and answered screenshots, and the reflective walk then listed both browsers.

The first `Runtime.evaluate` after a navigation (`loadURL`) timed out once (30 s) while every later call answered;
the adapter must bound every call and may retry a read once after a navigation.

### F-D Pages: `srcdoc` iframe never loads; plain pages and `data:` iframes do

The probe's first page (an `<iframe srcdoc=…>`, a form, inline scripts, 1.5 KB `data:` URL) **never finished
loading** in the image: `isLoading()` stayed true, the renderer process existed with its main thread idle in a
condition wait, every DevTools call on that browser timed out, the window stayed white; identical with `--no-zygote`,
`--disable-gpu`, `--in-process-gpu`, `--single-process`, seccomp unconfined and jcefmaven 146.0.10. The same probe
with a plain page (title, heading, button, inline script; `--rich-page false`, the default now) loaded in 150-160 ms
and every measurement above succeeded. With `--iframe true` (an `<iframe src="data:text/html,…">`) the page loads, `getFrameIdentifiers()` lists two frames (`""` and `inner`) and `Page.getFrameTree` has the child frame, so the frame model of the research note stands. Cause not established (a sandboxed `about:srcdoc` child frame
of an opaque-origin `data:` document is the suspect); Phase 4's sample pages avoid `srcdoc`, and the frame question is
answered with a `data:`-URL iframe.

### F-E `printAll` (research F11: verified, in the negative)

Off-screen mode: `printAll` on the JOGL `GLCanvas` throws `IllegalStateException: setupPrint() not called`, and so
does `printAll` on the frame that contains it. Windowed mode: the component (`CefBrowserWr$3`, a lightweight `JPanel`
over a heavyweight `java.awt.Canvas`) prints as a blank 800 x 600 image (one colour); the frame prints its own
decorations. So a `PAINT` capture must skip or guard JCEF components (catch the exception, paint a placeholder), and
the evidence image of a web view is `web.bitmap` (research §C7 confirmed).

### F-F Enumeration without a public API (research F5: verified)

`CefApp.self` (static, private) is the built instance; `clients_` holds one `CefClient`; `getAllBrowser()`
(protected) returns the live browsers (`[1, 2]` with both created, `[1]` with the hidden one never created), typed
`CefBrowserWr` or `CefBrowserOsr`; `settings_.remote_debugging_port` (9229) and `windowless_rendering_enabled` are
readable. `setAccessible` was not refused (class path application; no module encapsulation).

### F-G Process, timing and shutdown

Natively on arm64: natives extracted in 2.4-2.7 s (first run only); `CefApp` built in 80-160 ms after that; the frame
shown 30-60 ms after the browsers were created; the DevTools client 110-200 ms later; `document.readyState ===
"complete"` 150-160 ms after the frame. The JVM's RSS 650 MB (off-screen, with JOGL) / 300-450 MB (windowed); seven
`jcef_helper` processes (two zygotes, GPU, network service, storage service, renderers) with 320-770 MB RSS together.
`dispose()` takes 500 ms and leaves `CefApp.getState()` at `SHUTTING_DOWN`; `getDevToolsClient()` is null after
`close(true)`. The runs that reached the end took 90-180 s including the probe's own bounded waits.

### F-H Java 8 target (research F1: verified for off-screen rendering, not for windowed)

JCEF 152's natives (built against JDK 11 headers) run on a Java 8 JVM: on Temurin 8u504 (Linux aarch64) the
off-screen configuration passed every measurement of F-B (evaluate p50 0.13 ms, promises, errors, events, the init
script at document start, the click, both screenshots; one evaluate of twenty-odd timed out once, as on Java 25) with
no `--add-exports` flags. **Windowed rendering on Java 8 did not create the native browsers**: the frame showed, but
no `CefBrowserWr` ever got a DevTools client, `getIdentifier()` stayed -1, the reflective walk listed no browser and
only five helper processes were started (no renderer); no error was logged by JCEF or CEF. The cause was not
established (the JAWT parent-window path of `CefBrowserWr` on Java 8 is the suspect; `CefBrowserOsr` needs no native
parent). For Phase 4 this means: on a Java 8 target the public acceptance runs off-screen mode; a windowed JCEF on
Java 8 is marked unverified until the cause is known.

### F-I x86-64 under emulation

The project's `linux/amd64` image runs under emulation on this machine. With the sentinel files the off-screen
configuration on Java 25 passed the same measurements as on arm64, slower: natives extracted into 375,763,385 bytes;
`CefApp` built in 253 ms; the frame shown 227 ms later; the DevTools client 281 ms after that (and once reported
`Client is closed` on first use, F-C); evaluate p50 0.38 ms, max 82 ms; the init script at document start with the
next document in 623 ms; `Page.captureScreenshot` 196 ms; JVM RSS 580 MB plus 666 MB in seven helper processes.
Before the sentinel files every emulated run hung on the dialog exactly as the native ones did, so nothing here is
specific to emulation; the Java 8 configurations were not repeated under emulation (the mounted Linux x86-64 JDK 8
is the one the matrix uses; time).

## 3. The image

`ldd` of `libcef.so`, `libjcef.so` and `jcef_helper` on the previous image reported nineteen missing libraries, which
map to fifteen Ubuntu 26.04 packages: `libnss3 libnspr4 libatk1.0-0t64 libatk-bridge2.0-0t64 libatspi2.0-0t64
libcups2t64 libdbus-1-3 libglib2.0-0t64 libcairo2 libpango-1.0-0 libasound2t64 libxcomposite1 libxdamage1 libxfixes3
libxkbcommon0` (added to `ci/Dockerfile`; versions in `docs/licence-audit.md` §7). OpenGL for off-screen rendering
(`libgl1`, `libgl1-mesa-dri` with `swrast_dri.so`, `libxxf86vm1`) was already present as a dependency of `xvfb`; the GPU
process runs Mesa's `llvmpipe` threads. Chromium's dbus and screen-saver errors in the logs are noise (no dbus in the
container). The image grew from 212 - 15 to 212 dpkg packages; the natives (152-168 MB per platform) live in the Maven
cache, not in the image.

Two things the probe had to do that an application in the image must do as well: put Chromium's `root_cache_path` on
the container's own file system (not on the directory shared with the host, where Chromium's lock files and sockets
sit in a bind mount) and give each platform its own natives directory.

## 4. The configurations

All four configurations of `run.sh` on the arm64 build of the image (Temurin 25.0.4.1 and Temurin 8u504 for Linux
aarch64), the plain page, remote-debugging port 9229, 2026-10-06 afternoon. "Steps" counts the probe's measurement
steps that ended in an error; the one error common to every passing run is the `Target.*` step (F-B), the one Java 8
windowed error is the missing browser (F-H). Times are milliseconds unless marked.

| Configuration | Run | CefApp built | Frame shown | DevTools client | Document complete | Evaluate p50 / max | Init script next document | Screenshot (protocol) | Steps in error | Helper processes, RSS | JVM RSS |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Java 25, windowed | 93 s | 99 | 36 | 194 | 197 | 0.16 / 3.3 | 42 | 53 | 1 (`Target.*`) | 7, 763 MB | 408 MB |
| Java 25, off-screen | 123 s | 86 | 59 | 111 | 148 | 0.24 / 2.0 | 111 | 71 | 1 (`Target.*`; one evaluate timed out and the step after it passed) | 7, 568 MB | 565 MB |
| Java 8, windowed | 61 s | 118 | 39 | never | never | – | – | – | 1 (no browser was created, F-H) | 5, 506 MB | 320 MB |
| Java 8, off-screen | 123 s | 101 | 45 | 98 | 137 | 0.13 / 3.2 | 90 | 80 | 1 (`Target.*`; one evaluate timed out) | 7, 634 MB | 538 MB |
| Java 25, off-screen, x86-64 under emulation | 125 s | 253 | 227 | 281 | (first call `Client is closed`) | 0.38 / 82 | 623 | 196 | 1 (`Target.*`) | 7, 666 MB | 580 MB |
| Java 25, off-screen, `--iframe true` | 122 s | – | – | 126 | 183 | 0.33 / 1.0 | 109 | 68 | 1 (`Target.*`; one evaluate timed out) | 7 | – |
| Java 25, off-screen, `--create-hidden true` | 93 s | – | – | 131 | 180 | 0.27 / 1.0 | 131 | 75 | 1 (`Target.*`) | 7 | – |

The first-version runs with the rich page and the diagnostic runs that found the dialog are kept under
`target/results-run10/`, `target/results-amd64-emulated/` and `target/results-diag/` on the machine that ran them
(not committed; `target/` is ignored), and are summarised in the session notes.

## 5. What this means for Phase 4

- The in-process path is viable: the `jcef` adapter can be built on `getDevToolsClient()` as the research proposed.
- The web host sample must: pre-create `First Run` and `EULA Accepted` in its `root_cache_path`; use pages without
  `srcdoc` iframes; create its hidden browser with `createImmediately()` and expect browsers without a native
  browser; set the three `--add-exports` on JDK 16+ for off-screen mode.
- The CDP target id cannot come from `Target.getTargetInfo` through this channel; the contract takes a token planted
  in the page (an init script or an evaluation sets `window[Symbol.for("skillroy")].token`) which the controller reads
  through Playwright on each page, and `/json/list` matching by URL and title as the fallback.
- A `PAINT` capture must guard JCEF components; `web.bitmap` through `Page.captureScreenshot` is the evidence image.
- Every adapter call is bounded and a read may be retried once after a navigation.
- The image is good for both rendering modes; the project's x86-64 image is the CI target, and this machine runs it
  under emulation: with the sentinel files it behaves like the native arm64 build, a few times slower.

## 6. How to reproduce

```bash
mvn -f spikes/BS8/pom.xml package                               # linux-amd64 natives (152 MB) into target/lib
mvn -f spikes/BS8/pom.xml package -Dbs8.natives=linux-arm64     # arm64 natives as well, for an arm64 image
docker build -t skillroy-test-java-ci:local ci                  # the image with Chromium's libraries
SKILLROY_JDK8_HOME=/path/to/linux-jdk8 spikes/BS8/run.sh        # four configurations; results in target/results/
SKILLROY_PLATFORM=linux/arm64 SKILLROY_CI_IMAGE=<arm64 tag> spikes/BS8/run.sh   # natively on an arm64 machine
spikes/BS8/run.sh java25-osr -- --rich-page true                # the page that does not load (F-D)
spikes/BS8/run.sh java25-osr -- --iframe true                   # the frame question with a data: iframe
spikes/BS8/run.sh java25-osr -- --create-hidden true            # createImmediately() on the hidden browser
```
