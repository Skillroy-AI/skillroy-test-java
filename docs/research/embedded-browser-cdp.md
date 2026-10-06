# Embedded Chromium web views (JxBrowser) and Playwright over CDP

Research date: 2026-10-01. JxBrowser version examined: 7.42.1.
Ids are stable and intentionally non-contiguous.

Scope: driving web content in an embedded web view (a JxBrowser `Browser` hosted in a Swing target application)
from outside the target JVM with Playwright over the Chrome DevTools Protocol (CDP); the in-process JxBrowser
alternative; Playwright Java packaging; and fallbacks.

Spikes. Base spike BS5 (Playwright `connectOverCDP` to an already-running Chromium) proves the generic attach
mechanics against a stock Chromium: the liveness probe, the external deadline, page mapping, no `newContext`. It
does not prove anything about the older Chromium inside JxBrowser or about off-screen rendering. Items marked
"needs a JxBrowser spike" need a JxBrowser licence and a target that embeds it, so they are run by whichever pack
has such a target (see base decision B9), not in this repo.

## Summary

JxBrowser 7.42.1 embeds Chromium 133.0.6943.99. TeamDev documents a remote-debugging port on the Engine
(`EngineOptions` `remoteDebuggingPort(port)` plus `--remote-allow-origins=http://localhost:<port>`) for CDP-based
automation. Playwright `chromium.connectOverCDP("http://127.0.0.1:<port>")` against that port is the right primary
approach. The `--remote-allow-origins` restriction does not affect Playwright: Chromium checks the origin only when
an `Origin` header is present, and Playwright's WebSocket transport sends none. It only blocks Chrome's
chrome://inspect DevTools frontend. The port must be configured before the Engine is created, cannot be enabled
afterwards through the public API, and belongs to an Engine, not to a process.

The real risks are (a) version skew between Playwright and JxBrowser's Chromium (Playwright 1.63 ships Chromium 153;
JxBrowser 7.42.1 has 133), (b) Playwright's "lower fidelity" and curated-args warnings for `connectOverCDP`,
mitigated by `noDefaults=true` (Playwright 1.60+), (c) off-screen-rendering (OSR) viewport sizing of hidden or cached
browsers, which affects actionability checks and screenshots, and (d) `connectOverCDP` hanging if any page target is
unresponsive. The in-process JxBrowser API (DOM, `executeJavaScript`, `@JsAccessible` injection, `browser.dispatch`
input events, `browser.bitmap()`) is the right fallback, and the right tool whenever web-view state must be
correlated with target-application state or calls across the host-to-page bridge must be intercepted. Playwright
Java 1.63.0 supports Java 8+, is Apache-2.0, needs no browser download for `connectOverCDP` (but its driver-bundle
jar is about 194 MB), offers `CDPSession` for raw protocol access, cannot record video on an attached context, and
runs headed under `xvfb-run` in CI.

## Findings

### F0

JxBrowser 7.42.1 embeds Chromium 133.0.6943.99 (released 2025-02-20). The platform jar
`jxbrowser-linux64-7.42.1.jar` is 112 MB (a 110 MB `chromium-linux64.7z` plus `libtoolkit.so`).

Evidence: inspection of the published `jxbrowser-7.42.1.jar` artifact, 2026-10-01: 207 occurrences of
`133.0.6943.99` (in `com/teamdev/jxbrowser/version.info`, `internal/chromium-linux64.info` and others); TeamDev
release notes, https://teamdev.com/jxbrowser/releases/2025/v7-42-1/ ("Chromium 133.0.6943.99", 21 security fixes).

Confidence: verified.

### F2

A target application can create more than one JxBrowser Engine. Each Engine is configured through its own
`EngineOptions`, including its own remote-debugging port and user-data directory, so remote-debugging ports are per
Engine (per user-data directory), not per process. Two Engines cannot use the same user-data directory at the same
time.

Evidence: JxBrowser Engine guide, https://teamdev.com/jxbrowser/docs/guides/engine/ ("The same user data directory
cannot be used at the same time by multiple Engine instances"; `remoteDebuggingPort` is an `EngineOptions` builder
setting, see F4).

Confidence: verified for the user-data-directory rule; medium for "ports are per Engine" (not re-verified on a
neutral target).

### F4

TeamDev officially positions the Engine's remote-debugging port for CDP-based automation ("to integrate with
software like Selenium that relies on Chrome DevTools Protocol"), with the recipe
`.remoteDebuggingPort(9222).addSwitch("--remote-allow-origins=http://localhost:9222")`, and publishes a
Selenium/ChromeDriver attach tutorial. It also warns "Not all the Chromium switches are supported by JxBrowser".

Evidence: https://teamdev.com/jxbrowser/docs/guides/engine/ ;
https://teamdev.com/jxbrowser/docs/tutorials/integration/selenium/ (the application reads
`--remote-debugging-port` and passes it to `builder::remoteDebuggingPort`; ChromeDriver uses `options.setBinary(app)`
plus `--remote-debugging-port=9222`; the tutorial uses HARDWARE_ACCELERATED rendering and Java 17+ for current
JxBrowser 9.x) ; https://teamdev.com/jxbrowser/docs/tutorials/automated-tests/ (recommends API-driven DOM/JS
automation and QF-Test; mentions attaching Chrome via the remote-debugging port).

Confidence: verified.

### F6

`--remote-allow-origins` does not block Playwright. Chromium 133's DevTools HTTP handler validates the origin only
when an `Origin` request header is present (`*` allows all; a mismatch returns 403 "Rejected an incoming WebSocket
connection from the %s origin..."). Playwright's `WebSocketTransport` passes only caller-supplied headers to `ws`,
and `ws` sets `Origin` only when `options.origin` is given. The switch does block Chrome's chrome://inspect /
DevTools frontend (origin `devtools://devtools`) unless `*` or that origin is allowed. `/json/new` requires PUT.

Evidence:
https://chromium.googlesource.com/chromium/src/+/refs/tags/133.0.6943.99/content/browser/devtools/devtools_http_handler.cc ;
https://raw.githubusercontent.com/microsoft/playwright/main/packages/playwright-core/src/server/transport.ts (ws
options: `maxPayload`, `headers: options.headers`, `followRedirects`, `perMessageDeflate`) ;
https://raw.githubusercontent.com/websockets/ws/master/lib/websocket.js (`if (opts.origin) {... opts.headers.Origin =
opts.origin }`) ; https://github.com/pd4d10/debugtron/issues/27 (rejection of the `devtools://devtools` origin) ;
Chrome 111 introduced the check: https://github.com/apache/cordova-android/issues/1577 .

Confidence: verified.

### F7

Playwright `connectOverCDP` (Java and Node) is Chromium-only, documented as "significantly lower fidelity than the
Playwright protocol connection", and warns that browsers launched without Playwright's curated args may break some
functionality. Existing pages are exposed via `browser.contexts().get(0).pages()`. Options: `headers`, `slowMo`,
`timeout` (30 s default), `isLocal` (1.58), `noDefaults` (1.60: skips Playwright's overrides of download behaviour,
focus emulation and media emulation on the default context), `artifactsDir` (1.61).

Evidence: https://playwright.dev/java/docs/api/class-browsertype ;
https://playwright.dev/docs/api/class-browsertype ; https://playwright.dev/docs/release-notes (1.58 `isLocal`, 1.60
`noDefaults`, 1.61 `artifactsDir`).

Confidence: verified.

### F8

Version skew is the main CDP compatibility risk. Playwright 1.63.0 bundles Chromium 153.0.8010.12, 20 majors newer
than JxBrowser 7.42.1's Chromium 133; Playwright 1.52 bundles Chromium 136. Playwright documents support only for
the "Stable and Beta channels" of branded browsers for the current version and publishes no minimum Chromium version
for `connectOverCDP`. The failure mode with embedded Chromium is protocol errors during attach defaults (for example
with CEF: "Browser.setDownloadBehavior: Browser context management is not supported"), which `noDefaults=true` is
designed to avoid.

Evidence: https://playwright.dev/java/docs/release-notes (1.63: Chromium 153.0.8010.12) ;
https://github.com/microsoft/playwright/releases/tag/v1.52.0 (Chromium 136.0.7103.25) ;
https://playwright.dev/java/docs/browsers ; https://github.com/microsoft/playwright/issues/10927 (CEF, closed
P3-collecting-feedback).

Confidence: likely.

### F9

`connectOverCDP` can hang indefinitely if any pre-existing page target stops answering CDP ("one bad tab poisons the
whole connect"); reported on 1.60 and 1.63-alpha, closed as a duplicate of #41714. This matters for embedded web
views because a target application may keep several live JxBrowser `Browser` instances, some of them cached and
hidden.

Evidence: https://github.com/microsoft/playwright/issues/42730 ; https://github.com/microsoft/playwright/issues/42882 .

Confidence: verified.

### F10

Every `engine.newBrowser()` is a separate CDP page target, so `/json/list` and Playwright's default context show one
`Page` per live JxBrowser `Browser`, whether visible or cached and hidden. A Playwright `Page` can be mapped to a
JxBrowser `Browser` by URL or by target id: in process, `browser.devTools().remoteDebuggingUrl()` embeds the page
target id; from Playwright, `page.context().newCDPSession(page).send("Target.getTargetInfo")`.

Evidence: https://teamdev.com/jxbrowser/docs/guides/browser/ (`devTools().remoteDebuggingUrl()`) ;
https://playwright.dev/java/docs/api/class-cdpsession (`newCDPSession`, `send(method, args)`,
`on(event, Consumer<JsonObject>)`).

Confidence: likely.

### F11

OFF_SCREEN rendering copies Chromium's pixels into a Java-side buffer that the Swing `BrowserView` paints; the
normal input path is OS -> Swing -> `BrowserView` -> Chromium (the experimental `jxbrowser.native.input.enabled`
bypasses it). CDP `Input.dispatchMouseEvent` and `Page.captureScreenshot` enter Chromium directly, bypassing Swing,
so they should behave as in headless Chromium; CEF, which has the same OSR architecture, documents CDP screenshots
working in OSR (with a DPI-scale bug). In OSR the browser's viewport is whatever `browser.resize()` or `BrowserView`
size was last applied. An application that sizes the view only when it displays it leaves never-shown or long-hidden
cached browsers with stale or tiny viewports, which can break Playwright actionability (visible / in-viewport) checks
and full-page screenshots.

Evidence: https://teamdev.com/jxbrowser/docs/guides/rendering/ ; https://teamdev.com/jxbrowser/docs/guides/browser/
(`browser.resize(int,int)` for off-screen; "Chromium will update the DOM layout ... asynchronously") ;
https://bitbucket.org/chromiumembedded/cef/issues/3103/offscreen-capture-screenshot-with-devtools . Not spiked
against JxBrowser itself; needs a JxBrowser spike (see R1).

Confidence: uncertain.

### F12

In-process JxBrowser 7.x automation surface:

- DOM: `Frame.document()`, `findElementsByCssSelector` / `Id` / `TagName`, XPath via `Node.evaluate`,
  `element.click()` / `focus()`, `FormControlElement.value()`, `document.createMouseEvent` plus `element.dispatch`,
  `addEventListener` with `CustomEvent.detail()`, shadow roots via `JsObject`.
- JavaScript: `Frame.executeJavaScript`, synchronous (returns `Object` or null) or asynchronous with a `Consumer`;
  `JsPromise`; `@JsAccessible` objects injected via `InjectJsCallback` / `putProperty`; a Java `CompletableFuture`
  becomes a JS Promise.
- OS-level input simulation: `browser.dispatch(KeyPressed / KeyTyped / KeyReleased / MousePressed / MouseReleased /
  TouchStarted ...)`, which "generates a proper sequence of DOM events".
- Screenshots `browser.bitmap()` (saveable to PNG), `browser.resize()`, `browser.devTools().show()` /
  `remoteDebuggingUrl()`, console capture via `ConsoleMessageReceived`.

Caveats: Java called from JavaScript blocks the render thread; returning a JS Promise from Java is unsupported (use
`CompletableFuture`).

Evidence: https://teamdev.com/jxbrowser/docs/guides/dom/ ; https://teamdev.com/jxbrowser/docs/guides/javascript/ ;
https://teamdev.com/jxbrowser/docs/guides/browser/ .

Confidence: verified.

### F17

Playwright Java's current release is 1.63.0 (`com.microsoft.playwright:playwright:1.63.0`). It requires "Java 8 or
higher" (Windows 11+ / Server 2019+ / WSL, macOS 14+, Debian 12/13, Ubuntu 22.04/24.04/26.04); licence Apache-2.0.
Browser binaries are downloaded on first run to `~/.cache/ms-playwright` (override with `PLAYWRIGHT_BROWSERS_PATH`)
unless `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` is set; stale browser versions are garbage-collected unless
`PLAYWRIGHT_SKIP_BROWSER_GC=1`. The `driver-bundle-1.63.0.jar` on Maven Central is 203,821,698 bytes (about 194 MB;
it contains the Node-based driver for all platforms; browsers are separate). A driver-only (about 1 MB) artifact for
`connectOverCDP` users is an open feature request with no maintainer response.

Evidence: https://playwright.dev/java/docs/intro ; https://playwright.dev/java/docs/browsers ;
https://github.com/microsoft/playwright-java (Apache-2.0) ;
https://repo1.maven.org/maven2/com/microsoft/playwright/driver-bundle/1.63.0/ ;
https://github.com/microsoft/playwright-java/issues/1845 ; https://github.com/microsoft/playwright-java/issues/635
(the driver honours `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD`).

Confidence: verified.

### F18

Artifacts over a CDP-attached browser: screenshots use `Page.captureScreenshot` (expected to work). Tracing can be
started on the attached default context, and 1.61 added `artifactsDir` for it, but actions driven over a raw
`CDPSession` are not in the action timeline. Video recording is impossible on the pre-existing context
(`recordVideo` is a `newContext` option), and creating a new context on JxBrowser would yield a target with no
`BrowserView`.

Evidence: https://github.com/microsoft/playwright/issues/29065 (video over `connectOverCDP`, open feature) ;
https://github.com/microsoft/playwright/issues/42776 (action timeline for raw CDP) ;
https://github.com/microsoft/playwright/issues/27409 (1.37 auto-trace bug, historical) ;
https://playwright.dev/docs/release-notes (1.61 `artifactsDir`).

Confidence: verified.

### F19

Headed CI: Playwright documents `xvfb-run mvn test` for Linux agents and the
`mcr.microsoft.com/playwright/java:v1.63.0-noble` image with Xvfb preinstalled. `connectOverCDP` launches no
browser, so Xvfb is needed only for the target application (the process that hosts JxBrowser).

Evidence: https://playwright.dev/java/docs/ci .

Confidence: verified.

### F20

Google Chrome 136+ ignores `--remote-debugging-port` for the default profile directory, and Chrome M144 added a
chrome://inspect on-demand remote-debugging mode that Playwright initially could not attach to. These are
branded-Chrome behaviours; JxBrowser always uses its own user-data directory, so JxBrowser 7.42 (Chromium 133) is
unaffected. They are a forward-compatibility watch item for when JxBrowser moves to a newer Chromium.

Evidence: https://github.com/microsoft/playwright/issues/40027 ; search summary on the Chrome 136 default-profile
restriction (https://www.browserless.io/blog/chrome-remote-debugging).

Confidence: likely; medium for "JxBrowser always uses its own user-data directory" (not re-verified on a neutral
target).

### F22

Selenium/ChromeDriver attach (`debuggerAddress`) is an officially documented alternative, but it requires a
ChromeDriver matching Chromium 133 and offers no auto-wait or trace tooling. Raw CDP from Java is available through
Playwright's `CDPSession` without an extra client library.

Evidence: https://teamdev.com/jxbrowser/docs/tutorials/integration/selenium/ ;
https://playwright.dev/java/docs/api/class-cdpsession .

Confidence: likely.

## Recommendations

### R0

Primary: drive embedded web views with Playwright Java from the controller process:

```java
playwright.chromium().connectOverCDP("http://127.0.0.1:<port>",
    new ConnectOverCDPOptions().setNoDefaults(true).setIsLocal(true).setTimeout(...));
```

then select the `Page` from `browser.contexts().get(0).pages()` by URL (optionally confirm with
`newCDPSession(page).send("Target.getTargetInfo")`). Expose this as base-framework primitives: `cdp.attach(port)`,
`cdp.pageFor(urlPattern)`, `page.waitForFunction(<page-readiness predicate supplied by the domain pack>)`,
`page.evaluate("window.dispatchEvent(new CustomEvent(...))")`, locator actions, `page.screenshot`,
`context.tracing`.

### R1

Run a one-day spike before committing to Playwright for a JxBrowser target (needs a JxBrowser spike): the target
application under Xvfb with the debug port configured and an embedded web view in use; Playwright Java 1.52 and
1.63 against JxBrowser's Chromium 133. Cover: attach, enumerate pages, locate and click in a visible web view and
in a cached hidden one, `page.screenshot`, `context.tracing`, `page.keyboard.type`, and attach time with several
live targets. Pin the Playwright version that passes; prefer the newest that works, to keep `noDefaults` and
`artifactsDir`. The generic half (attach to a stock Chromium, frozen target, deadline) is base spike BS5.

### R2

Pair CDP with the in-process agent rather than choosing one. Use the agent (JxBrowser DOM/JS, `browser.dispatch`,
`browser.bitmap`) to correlate web-view state with target-application state, to intercept or replay calls the page
makes into host-injected `@JsAccessible` objects, for simulator-style scenarios that publish events into the page,
and as the fallback when no debug port is available. Use Playwright for locators, auto-wait, screenshots and traces,
console and network capture, and reuse of existing Playwright page objects for the web content.

### R3

Treat the debug port as a launch-time precondition of the target application's test configuration: configure a port
for every Engine the tests must reach (a distinct port per Engine), and do not attempt post-hoc enablement. If the
framework ever needs to enable it, the only hook is intercepting `EngineOptions.Builder.build()` before the
application creates its Engine, which is fragile.

### R5

Simulate host-to-page and page-to-host traffic by dispatching window events from the test
(`window.dispatchEvent(new CustomEvent('<name>', {detail: {...}}))`) when the page and the host communicate that
way. The domain pack owns the event names and payload shapes; the base supplies only the dispatch and tap
primitives.

### R6

Packaging: ship Playwright Java in the controller (Java 25, the project's controller baseline, even though 8+ is
supported; the target JVM can stay on Java 8), set `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` for attach-only profiles,
accept the ~194 MB driver bundle, and allow a Docker/socat topology (`host.docker.internal`, or
`socat TCP-LISTEN:<x>,fork TCP:127.0.0.1:9222`). Video: use an X11 screen recorder (ffmpeg x11grab) or periodic
`browser.bitmap()` / `page.screenshot`, not Playwright `recordVideo`.

### R7

Fallback B (web content only, fastest): run Playwright's own Chromium against the web application served standalone
(a local web server or its dev server), inject a stub for the host-injected bridge object via `page.addInitScript`
whose method names mirror the host's `@JsAccessible` API, and record the calls. This needs no JxBrowser licence.

## Constraints

### C0

Remote debugging must be configured before the JxBrowser Engine is created. Engine options are immutable afterwards,
and there is no public API to enable the port post-hoc. Whether the application creates its Engine at start-up or
lazily on first use, the options are fixed at that moment.

### C1

Each Engine needs its own port and its own user-data directory (`UserDataDirectoryAlreadyInUseException`
otherwise); a target application may have more than one Engine.

### C2

If the application sets `--remote-allow-origins=http://localhost:<port>`, Playwright and other Node clients (no
`Origin` header) connect fine, but Chrome's chrome://inspect / DevTools frontend gets 403 unless the application's
Engine options are changed to allow `devtools://devtools` or `*`.

### C3

JxBrowser 7.42.1's Chromium is 133.0.6943.99. Keep the Playwright version close enough to avoid CDP skew;
1.63 / Chromium 153 against it needs a JxBrowser spike (R1). Use `noDefaults=true` (Playwright 1.60+) so Playwright does not override the
application's own download handling, focus or media emulation.

### C4

Playwright over CDP is officially "lower fidelity", and the embedding application launches Chromium with its own
rendering mode and switches, not Playwright's curated args. Do not call `browser.newContext()` or
`browser.newPage()` on an attached JxBrowser: it creates targets without a `BrowserView`.

### C5

`connectOverCDP` awaits every existing page target; any hung or unresponsive JxBrowser `Browser` blocks the whole
attach. Use timeouts and keep cached browsers healthy.

### C6

The OSR viewport equals the last `BrowserView` size; cached or hidden browsers may have stale or tiny viewports,
which affects Playwright actionability checks and screenshots. This may need an in-process `browser.resize()` or
JS-dispatched clicks.

### C7

When attached over CDP there is no video recording, and the action timeline is incomplete for raw-CDP actions;
screenshots and tracing of Playwright-driven actions do work.

### C8

Playwright Java needs its Node-based driver (the ~194 MB driver-bundle jar) extracted to a writable directory on a
supported OS; `connectOverCDP` needs no browser download (`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`). Java 8+ is
supported for the controller, and it runs out of process from the target JVM, which may be Java 8.

### C9

Any JxBrowser Engine requires a licence key (`jxbrowser.license.key`); the standalone fallback (R7) does not.

### C11

Chromium's `/json/new` requires PUT; `/json/version` and `/json/list` are GET (Playwright handles this).

## Risks

### Risk0

Playwright 1.63 may fail or misbehave against Chromium 133 (20-major skew) despite `noDefaults`. Mitigation: spike
(R1) and pin; keep an older Playwright release closer to Chromium 133 (for example 1.52, which bundles Chromium 136)
as the fallback candidate.

### Risk2

OSR-specific behaviour of CDP screenshots and input, and viewport sizing of hidden cached browsers, are unverified
for JxBrowser (inferred from Chromium and CEF only). This could force a hybrid where clicks are JS-dispatched or the
in-process agent resizes browsers.

### Risk3

Hidden or unresponsive page targets (for example a crashed renderer) can hang `connectOverCDP`. This needs timeouts
and a recovery primitive (close or reload the browser in process).

### Risk4

A target application with more than one Engine doubles the port and user-data-directory configuration, and can
confuse target enumeration if several Engines have debug ports.

### Risk5

Playwright's driver-bundle size (~194 MB) and Node runtime add CI provisioning cost; the driver-only artifact is only
a feature request.

### Risk6

Future JxBrowser upgrades (Chromium 136+ / 144+) may change remote-debugging semantics (default-profile restriction,
new on-demand mode). Keep the attach step isolated and version-pinned.

### Risk7

There is no video over CDP; relying on X11 capture ties evidence collection to the Xvfb host.

### Risk8

Enabling the debug port changes the target application's launch configuration, and ports must be unique per target
application instance on shared CI hosts.
