# One in-process SPI for embedded browsers: JCEF and reflective JxBrowser

Research date: 2026-10-02. Versions examined: java-cef `master` at commit `17e805a` (2026-09-09; CEF 152.0.6,
Chromium 152.0.7977.83) and its jcefmaven distribution 152.0.6; the JetBrains fork of java-cef (`dev` branch);
JxBrowser 7.44.2, 8.18.3 and 9.5.2 (public guides and API reference only).
Ids are stable and intentionally non-contiguous: F0-F16 JCEF, F20-F28 JxBrowser, F30 the comparison.

Scope: decision B9 asks for one agent-side SPI that wraps both (a) JCEF, the open-source Java binding of the
Chromium Embedded Framework (`org.cef.*`), and (b) JxBrowser reached purely by reflection (a commercial product; no
vendor code or licence may be in this repository). The generic `web.*` primitives are written once against that
SPI. CDP attach with Playwright from the controller stays the preferred path wherever a remote-debugging port is
available ([`embedded-browser-cdp.md`](embedded-browser-cdp.md), cited below as "browser §"); the SPI is the
in-process path and the fallback.

Method. Everything here was read in primary sources on the research date: source files of java-cef and CEF,
artifact listings on Maven Central, the published `jcef-api` jar (class-file versions and manifest, read in memory),
and the vendor's public guides and API reference for JxBrowser. **Nothing was executed**: no JCEF browser and no
JxBrowser engine was started for this document. "Verified" therefore means "read in a primary source", never "run".
Every claim that could not be confirmed that way is marked "not verified", and the list is repeated at the end.

## Summary

Both products can sit behind one small SPI, but they get there differently.

**JCEF** has a public Java API that is thin for automation (`executeJavaScript` returns nothing, there is no public
list of browsers, and each handler slot of a `CefClient` holds one handler only). What makes it workable is
`CefBrowser.getDevToolsClient()`, present in java-cef since 2024-01-11: an in-process DevTools-protocol channel per
browser that needs **no remote-debugging port**. Through it the agent gets `Runtime.evaluate` with a JSON result
and promise support, load and console events, a document-start script hook, screenshots, viewport override and
synthetic input. JCEF's Java classes are Java 8 bytecode and the jcefmaven distribution states "Java 8 or later",
so a Java 8 target can host it; the JCEF bundled with JetBrains Runtime needs that runtime (Java 17 or newer).
Licences are BSD and Apache-2.0, so a real JCEF browser can be exercised in public CI at the price of a 152 MB
native bundle per platform.

**JxBrowser** has a rich public API with the same shape in 7.44.2, 8.18.3 and 9.5.2 for everything the primitives
need (`Engine.browsers()`, `Browser.url()`, `Frame.executeJavaScript(String, Consumer)`, `Browser.bitmap()`,
`Browser.resize`, events through `on(Class, Observer)`), so one reflective adapter covers all three lines. It has
no in-process DevTools channel, no static registry of engines, and it cannot run without a licence key, so the
adapter's real behaviour can only be tested in a repository that holds a licence. JxBrowser 7 is the only line that
runs on Java 8 and it reached end of life on 2025-10-07.

The proposed SPI is two interfaces: `WebEngineAdapter` (detect the technology, turn seeds into browser handles) and
`WebBrowser` (URL, frames, `evaluate` returning JSON text, events, document-start script, resize, bitmap, CDP
target id), with every optional operation behind a capability flag. Everything else in the `web.*` catalogue
(`dom.click|fill|text`, `ariaSnapshot`, `dispatchEvent`, `tap`) is JavaScript run through `evaluate` and is written
once. The remote-debugging port, Playwright and any CDP WebSocket stay outside the SPI.

## Findings: JCEF

### F0

Distributions. (1) Upstream java-cef publishes source only; its wiki points to two independent projects for
binaries: jcefbuild (builds) and jcefmaven (Maven artifacts plus a bootstrap class, `CefAppBuilder`). (2) jcefmaven
152.0.6 was published on 2026-09-30 and wraps java-cef `17e805a` + CEF 152.0.6 + Chromium 152.0.7977.83; earlier
releases in 2026 were 146.0.10 (May) and 143.0.14 (February). (3) JetBrains maintains a fork that "is distributed as
part of JetBrainsRuntime"; it keeps the `org.cef.*` API, adds an out-of-process mode (`com.jetbrains.cef.remote.*`,
`CefApp.setIsRemoteEnabled`) and is not published as a standalone Maven artifact.

Evidence: https://github.com/chromiumembedded/java-cef/wiki/BranchesAndBuilding (links to jcefbuild and jcefmaven) ;
https://repo1.maven.org/maven2/me/friwi/jcefmaven/ (version directories and dates) ;
https://repo1.maven.org/maven2/me/friwi/jcefmaven/152.0.6/jcefmaven-152.0.6.pom ;
https://github.com/JetBrains/jcef (README; `java/org/cef/CefApp.java`,
`java/com/jetbrains/cef/remote/browser/RemoteBrowser.java` on `dev`).

Status: verified. Not verified: which Chromium version the fork currently bundles, and whether the fork is
obtainable outside that runtime.

### F1

Java versions. The published `jcef-api` jar for 152.0.6 contains 204 classes, all class-file major version 52
(Java 8); its manifest says it was built by JDK 1.8.0_504. java-cef's `build.xml` compiles with `source="1.8"
target="1.8"`. jcefmaven's README states "Requirements: Java 8 or later" and its POM sets compiler source and
target 8; its two runtime dependencies are commons-compress 1.28.0 and gson 2.11.0. On JDK 16 or newer the README
requires `--add-exports java.base/java.lang=ALL-UNNAMED`, `java.desktop/sun.awt` and `java.desktop/sun.java2d` for
off-screen rendering (they are for JOGL), plus three `--add-opens` on macOS. jcefbuild's README lists the JDK used
for the native build per platform: OpenJDK 11 on Linux, JDK 8 on Windows x86-64 and macOS. The JetBrains fork ships
inside a runtime whose current lines are Java 17, 21 and 25, so it is never present in a Java 8 target.

Evidence: in-memory inspection of
`me/friwi/jcef-api/jcef-17e805a+cef-152.0.6+g708dc14+chromium-152.0.7977.83/` on Maven Central, 2026-10-02 ;
https://raw.githubusercontent.com/chromiumembedded/java-cef/master/build.xml ;
https://raw.githubusercontent.com/jcefmaven/jcefmaven/master/README.md ;
https://raw.githubusercontent.com/jcefmaven/jcefbuild/master/README.md ;
https://github.com/JetBrains/JetBrainsRuntime/releases .

Status: verified for the bytecode level and the stated requirement; likely for the runtime's Java lines (release
list and the fork's build notes, which name a Java 17 runtime). **Not verified: an actual run of JCEF 152 on a
Java 8 JVM** (JNI is version-stable, but the Linux natives were built against JDK 11 headers and nobody ran them
here), and the minimum glibc of the Linux natives.

### F2

Sizes and licences (bytes as listed on Maven Central for 152.0.6):

| Artifact | Size | Licence |
|---|---|---|
| `me.friwi:jcefmaven` (bootstrap) | 30,936 | Apache-2.0 |
| `me.friwi:jcef-api` (`org.cef.*`) | 223,438 | BSD (java-cef `LICENSE.txt`, 3-clause) |
| `me.friwi:jcef-natives-linux-amd64` | 152,141,475 (145 MiB) | BSD (CEF) plus Chromium's third-party notices |
| `me.friwi:jcef-natives-linux-arm64` | 167,953,639 | same |
| `me.friwi:jcef-natives-windows-amd64` | 171,200,308 | same |
| `me.friwi:jcef-natives-macosx-arm64` | 142,331,980 | same |
| `jogl-all.jar` + `gluegen-rt.jar` (2.4.0, needed for off-screen rendering) | 3,667,449 + 366,041 | BSD (JogAmp) |

Without a natives artifact on the class path, `CefAppBuilder.build()` downloads the bundle on first run (from the
project's GitHub releases, then Maven Central) and extracts it into an install directory (default `jcef-bundle`)
guarded by an `install.lock` file. The README still says "each bundle is ~100MB"; the listing shows 142-171 MB.

Evidence: directory listings under https://repo1.maven.org/maven2/me/friwi/ (2026-09-30 13:47) ; the two POMs
(licence elements) ; https://raw.githubusercontent.com/chromiumembedded/java-cef/master/LICENSE.txt ;
https://github.com/chromiumembedded/java-cef/tree/master/third_party/jogamp ;
`jcefmaven/src/main/java/me/friwi/jcefmaven/CefAppBuilder.java` and `impl/step/init/CefInitializer.java`.

Status: verified for the compressed sizes and licences. Not verified: the extracted size on disk.

### F3

One CEF per process, configured once. `CefApp` is a process singleton held in `private static CefApp self`;
`getInstance(...)` creates it when absent. Settings can only be passed before the first client exists
(`IllegalStateException: Settings can only be passed to CEF before createClient is called the first time`).
`CefApp.getState()` is a static, side-effect-free read (`NONE, NEW, INITIALIZING, INITIALIZED,
INITIALIZATION_FAILED, SHUTTING_DOWN, TERMINATED`). The native side always disables the Chromium sandbox
(`settings.no_sandbox = true`, "Sandbox is not supported").

Consequence for an agent: it must **never call `CefApp.getInstance()`** to look for browsers, because in state `NONE`
that call creates and later initialises CEF inside the target. It reads `getState()` and, only when the state is
`INITIALIZED`, the existing instance.

Evidence: https://raw.githubusercontent.com/chromiumembedded/java-cef/master/java/org/cef/CefApp.java (lines
130-132, 204-231, 253) ; `native/context.cpp` lines 193-197.

Status: verified.

### F4

Threads. CEF is pre-initialised and initialised on the AWT event dispatch thread (`SwingUtilities.invokeAndWait`).
With `windowless_rendering_enabled` (off-screen rendering), JCEF uses an external message pump: CEF's UI thread *is*
the EDT, pumped there by `invokeLater` and a Swing `Timer` whose delay is capped at 1000/30 ms. With windowed
rendering on Windows and Linux it
sets `multi_threaded_message_loop = true`, so CEF's UI thread is its own native thread; on Linux this requires
`XInitThreads` to be the first Xlib call in the process, which is what the static `CefApp.startup(args)` is for (it
must run before AWT opens its display connection).

Consequence: with off-screen rendering every asynchronous CEF result is delivered on the EDT. An agent thread that
blocks the EDT while waiting for such a result deadlocks; waits happen on agent workers only, and are bounded.

Evidence: `CefApp.java` lines 136-180, 395-447, 493-531, 539-547 ; `native/context.cpp` lines 199-227 (the comment
block on Linux and `XInitThreads`).

Status: verified.

### F5

Browser enumeration has no public API. Clients are in `private HashSet<CefClient> clients_` of `CefApp`; each
client keeps `private HashMap<Integer, CefBrowser> browser_` and exposes it only as
`protected Object[] getAllBrowser()` (synchronised on the map). A browser's Swing component comes from the public
`CefBrowser.getUIComponent()`; the reverse direction (component to browser) has no accessor, and `CefClient`'s own
focus tracking works the same way round, testing a component against a known browser's `getUIComponent()`. So an agent
has three routes: objects handed in by the application's domain module (`BrowserRegistryAdapter`), a reflective
walk `CefApp.self -> clients_ -> getAllBrowser()` with `setAccessible`, or, least robust, the synthetic outer-instance
field of the anonymous component classes found by the Swing-tree scan. `CefBrowser.getIdentifier()` (an `int`) is the
native browser id.

Evidence: https://raw.githubusercontent.com/chromiumembedded/java-cef/master/java/org/cef/CefClient.java (lines
71, 86-124, 154-165) ; `CefApp.java` line 134 ; `java/org/cef/browser/CefBrowser.java`.

Status: verified for upstream. Not verified: that the same private members exist in the JetBrains fork's
out-of-process mode (its `CefApp` also declares `clients_`; its remote client classes were not read).

### F6

Handler slots hold one handler. `CefClient.addLoadHandler`, `addDisplayHandler`, `addLifeSpanHandler` and the other
`add*Handler` methods are written `if (xHandler_ == null) xHandler_ = handler;`. A second registration is silently
ignored. An agent cannot count on `CefLoadHandler` (load events), `CefDisplayHandler.onConsoleMessage` (console) or
`CefLifeSpanHandler.onBeforeClose` (closure), because the application normally occupies those slots, and it must not
replace them. `addMessageRouter` is the exception (a client may have several routers), see F8.

Evidence: `CefClient.java` lines 294-295, 534-535, 627-628, 716-717.

Status: verified.

### F7

URL and frames. `CefBrowser.getURL()` returns the main frame's URL (`""` when the native browser does not exist yet;
the Java wrapper catches `UnsatisfiedLinkError`). Frames: `getMainFrame()`, `getFocusedFrame()`,
`getFrameIdentifiers()` (`Vector<String>`), `getFrameByIdentifier(String)`, `getFrameNames()`, `getFrameCount()`;
`CefFrame` has `getIdentifier()`, `getURL()`, `getName()`, `isMain()`, `isValid()`, `getParent()` and a
fire-and-forget `executeJavaScript(code, url, line)`. Lifecycle state: `isLoading()`, `hasDocument()`.

Evidence: `java/org/cef/browser/CefBrowser.java`, `CefFrame.java`, `CefBrowser_N.java` on `master`.

Status: verified. Not verified: whether a CEF frame identifier can be mapped to the DevTools `frameId` of the same
frame (they are different id spaces).

### F8

JavaScript with a result. `executeJavaScript` is `void` on both `CefBrowser` and `CefFrame`; CEF's own guide says
"ExecuteJavaScript has no return value". Two mechanisms return data to Java:

- **`CefMessageRouter` / `window.cefQuery`.** The page calls `window.cefQuery({request, persistent, onSuccess,
  onFailure})`; a `CefMessageRouterHandler` in the browser process answers. The router configuration (the two
  JavaScript function names) "must be the same in both the browser and renderer processes", and JCEF sends the set
  of configurations to the renderer **when a browser is created** (`extra_info` "router_configs", "Send the message
  router config to CefHelperApp::OnBrowserCreated"). A router that an agent adds later, under its own function
  names, therefore does not exist in browsers that are already open, and one under the default names would share the
  application's channel. It is not a usable general mechanism for an agent that arrives late.
- **The DevTools message API.** `CefBrowser.getDevToolsClient()` returns a `CefDevToolsClient` with
  `CompletableFuture<String> executeDevToolsMethod(String method, String parametersAsJson)` and
  `addEventListener(EventListener)` (`void onEvent(String eventName, String messageAsJson)`). The underlying CEF
  call is documented as not requiring "an active DevTools front-end or remote-debugging session". So
  `Runtime.evaluate` with `returnByValue: true` and `awaitPromise: true` gives the agent a JSON result, an exception
  description and promise handling, in any browser, without a port and without touching the page.

The DevTools client was added to java-cef on 2024-01-11 (commit `566dc4e`) and is present in the published 152.0.6
jar (`org/cef/browser/CefDevToolsClient.class`) and in the JetBrains fork (`CefBrowser.getDevToolsClient()`, also
implemented by its remote browser class). On an older JCEF there is no general way to get a value back.

Evidence: https://chromiumembedded.github.io/cef/general_usage ;
`java/org/cef/browser/CefMessageRouter.java` (class comment) ; `native/CefBrowser_N.cpp` lines 1025-1038 and
1230-1255 ; `native/browser_process_handler.cpp` lines 79-111 ;
https://raw.githubusercontent.com/chromiumembedded/java-cef/master/java/org/cef/browser/CefDevToolsClient.java ;
https://github.com/chromiumembedded/java-cef/commits/master/java/org/cef/browser/CefDevToolsClient.java ;
https://raw.githubusercontent.com/chromiumembedded/cef/master/include/cef_browser.h (lines 621-649).

Status: verified by reading. Not verified: the round trip itself (`Runtime.evaluate` through this channel returning
the expected JSON) was not executed.

### F9

The DevTools client is shared with the application. "Only one instance per browser is available": `CefBrowser_N`
caches one client and creates a new one only after the old one was closed. `getDevToolsClient()` returns `null`
until the native browser exists and once it is closing. `CefDevToolsClient` is `AutoCloseable`, and `close()` clears
all listeners and pending commands. Results are routed by message id inside that one client; events go to every
listener.

Consequences: the agent never calls `close()`; it re-checks `isClosed()` and re-subscribes, because an application
that closes its client silently drops the agent's listeners; and protocol domains the agent enables (`Runtime`,
`Page`) stay enabled, since disabling them could switch off events the application relies on. Result futures
complete on CEF's UI thread (the EDT with off-screen rendering, F4).

Evidence: `java/org/cef/browser/CefBrowser.java` lines 363-371 ; `CefBrowser_N.java` lines 155-182 ;
`CefDevToolsClient.java` (constructor, `close`, `executeDevToolsMethod`).

Status: verified by reading. Not verified: that the application and the agent share one protocol session in CEF
itself (inferred from the single cached client; CEF's DevTools manager source was not read).

### F10

Events and document-start scripts without handler slots. Through the DevTools client: `Page.enable` then
`Page.frameNavigated`, `Page.domContentEventFired`, `Page.loadEventFired`; `Runtime.enable` then
`Runtime.consoleAPICalled` and `Runtime.exceptionThrown`; `Page.addScriptToEvaluateOnNewDocument` registers a script
that runs in every new document before the page's own scripts, which is exactly what `web.tap` needs (design §3.8:
a single-fire event is lost when the tap arrives late). `Page.createIsolatedWorld` gives a script context the page
cannot see. These are standard, long-stable protocol methods; nothing here depends on Playwright.

Evidence: https://chromedevtools.github.io/devtools-protocol/ (Page, Runtime domains) ; F8 for the channel.

Status: likely. Not verified: any of these calls through JCEF's channel.

### F11

Rendering modes and what Swing sees.

- **Windowed** (`CefBrowserWr`): `getUIComponent()` is a `JPanel`; on Windows and Linux it contains a heavyweight
  `java.awt.Canvas`, and Chromium draws into a native child window parented to it. `createScreenshot` throws
  `UnsupportedOperationException("Unsupported for windowed rendering")`.
- **Off-screen** (`CefBrowserOsr`): `getUIComponent()` is a JOGL `com.jogamp.opengl.awt.GLCanvas`, also a heavyweight
  AWT component; `onPaint` hands a BGRA `ByteBuffer` to an OpenGL texture. `createScreenshot(boolean
  nativeResolution)` returns a `CompletableFuture<BufferedImage>` read back from the GL surface on the EDT, and
  `addOnPaintListener(Consumer<CefPaintEvent>)` exposes each painted frame (`getRenderedFrame()`, `getWidth()`,
  `getHeight()`, dirty rectangles). jcefmaven's example sets `windowless_rendering_enabled = true` as its default.
- An application may bring its own off-screen component. One IDE platform built on the JetBrains fork paints the
  `onPaint` buffer in a lightweight `JPanel` subclass, which Swing can print like any other component.

For `swing.capture`: `PAINT` (`printAll`) renders lightweight components only (swing §F19), so with upstream JCEF
neither mode is expected to appear in a `PAINT` capture; `ROBOT` capture shows both, because it reads screen pixels.
For `web.bitmap` the mode-independent source is `Page.captureScreenshot` through the DevTools client (PNG as
base64); `createScreenshot` is an off-screen-only alternative.

Evidence: `java/org/cef/browser/CefBrowserWr.java` (lines 188, 254-255, 423-424) ; `CefBrowserOsr.java` (lines 78,
148, 366, 513) ; `CefPaintEvent.java` ; `CefBrowser.java` lines 381-402 ; jcefmaven README ;
https://github.com/JetBrains/intellij-community/blob/master/platform/ui.jcef/jcef/JBCefOsrComponent.java
(`class JBCefOsrComponent extends JPanel`, `paintComponent`) ; browser §F11 (CEF issue 3103: CDP screenshots work
with off-screen rendering, with a DPI-scale bug).

Status: verified for the component classes. Not verified: what `printAll` actually produces for a `GLCanvas`, and
`Page.captureScreenshot` on a windowed JCEF browser.

### F12

Resize. `CefBrowser` has no public resize. `wasResized(int, int)` is `protected final` in `CefBrowser_N`; the size
CEF asks for comes from the UI component (`getViewRect` returns a rectangle updated in the canvas' reshape callback,
initialised to 1x1 "to work around CEF issue #1437"). An off-screen browser that was created with
`createImmediately()` and never laid out is therefore 1x1, the same stale-viewport hazard as browser §C6. The two
options are a Swing operation (set the component's bounds on the EDT, only meaningful for a displayed component) and
the protocol override `Emulation.setDeviceMetricsOverride` / `clearDeviceMetricsOverride`, which changes the layout
viewport without touching Swing.

Evidence: `CefBrowserOsr.java` lines 83, 234-238, 341-342 ; `CefBrowser_N.java` line 616.

Status: verified for the API facts; the emulation override through JCEF is not verified.

### F13

Remote-debugging port. `CefSettings.remote_debugging_port` (Java field, default 0): "Set to a value between 1024
and 65535 to enable remote debugging on the specified port. Also configurable using the 'remote-debugging-port'
command-line switch. Specifying 0 via the command-line switch will result in the selection of an ephemeral port
... If a cache directory path is provided the port will also be written to the `<cache-dir>/DevToolsActivePort`
file." It is read at `CefInitialize`, so it cannot be set after start (F3), and there is one port per process,
where JxBrowser has one per Engine (browser §F2). The `--remote-allow-origins` behaviour is Chromium's and is as
described in browser §F6. An agent can learn the configured value only from the private `settings_` field of
`CefApp`, from the `DevToolsActivePort` file, or by probing `GET /json/version`.

Evidence: https://raw.githubusercontent.com/chromiumembedded/cef/master/include/internal/cef_types.h (lines
415-427) ; `java/org/cef/CefSettings.java` ; `CefApp.java` lines 217-231.

Status: verified.

### F14

Per-browser CDP target id. JCEF has no accessor for it. The expected route is `Target.getTargetInfo` through the
browser's own DevTools client, whose `targetInfo.targetId` is the id that `/json/list` and Playwright report. Without
that, the fallback is matching `/json/list` entries by URL and title, which is ambiguous (design §3.8).

Evidence: https://chromedevtools.github.io/devtools-protocol/tot/Target/#method-getTargetInfo ; F8.

Status: not verified.

### F15

Linux and Xvfb. CEF on Linux needs an X11 display; upstream's own run scripts start the JVM with
`LD_PRELOAD=libcef.so` and an `LD_LIBRARY_PATH` that lets the helper process find `libcef.so` and lets `libjcef.so`
find `libjawt.so`, while jcefmaven loads `libjawt`, `libjcef.so` and `libcef.so` itself with `System.load` and calls
`CefApp.startup` between them. Windowed mode on Linux needs `CefApp.startup` before the first AWT call (F4).
Off-screen mode needs OpenGL through JOGL, which on an Xvfb display means Mesa's software GLX. The sandbox is off
(F3), so the usual container problem with Chromium's sandbox does not arise.

Evidence: `tools/run.sh`, `tools/run_tests.sh` in java-cef ; jcefmaven `CefInitializer.java` ; F3, F4.

Status: verified for what the scripts and the loader do. **Not verified: that JCEF runs on an Xvfb display with no
window manager, in either rendering mode; which system packages the Linux image needs; whether JOGL gets a usable GL
context there.** No primary source states it and it was not run.

### F16

Synthetic input. `sendMouseEvent`, `sendKeyEvent` and `sendMouseWheelEvent` are `protected final` in `CefBrowser_N`
(used by the off-screen component to forward AWT events). The public route is the protocol:
`Input.dispatchMouseEvent`, `Input.dispatchKeyEvent`, `Input.insertText` through the DevTools client.

Evidence: `CefBrowser_N.java` ; https://chromedevtools.github.io/devtools-protocol/tot/Input/ .

Status: verified for the visibility; the protocol route through JCEF is not verified.

## Findings: JxBrowser

### F20

Lines and Java versions on the research date.

| Line | Latest | Released | Chromium | Supported Java | State |
|---|---|---|---|---|---|
| 7 | 7.44.2 | 2025-10-02 | 141.0.7390.55 | LTS 8, 11, 17, 21 | end of life since 2025-10-07; "will continue working as before" |
| 8 | 8.18.3 | 2026-04-10 | 147.0.7727.56 | LTS 17, 21, 25 | first release October 2024 |
| 9 | 9.5.2 | 2026-09-28 | 154.0.8037.58 | LTS 17, 21, 25 | 9.0.0 on 2026-04-24, "does not introduce breaking changes" |

A Java 8 target can therefore only contain JxBrowser 7. The agent module is Java 8 bytecode in every case, so one
reflective adapter must work against all three lines. All lines need an X server on headless Linux.

Evidence: https://teamdev.com/jxbrowser/docs/7/guides/introduction/requirements/ ;
https://teamdev.com/jxbrowser/docs/8/guides/introduction/requirements/ ;
https://teamdev.com/jxbrowser/docs/guides/introduction/requirements/ ;
https://teamdev.com/jxbrowser/blog/jxbrowser-v7-end-of-life/ ;
https://teamdev.com/jxbrowser/releases/2025/v7-44-2/ , `/2026/v8-18-3/` , `/2026/v9-0-0/` , `/2026/v9-5-2/` .

Status: verified.

### F21

The API the primitives need, checked member by member in the published API reference of 7.44.2, 8.18.3 and 9.5.2.
Names and signatures are identical in all three unless noted.

| Need | Type and member |
|---|---|
| Engine to browsers | `engine.Engine.browsers()` -> `List<Browser>` (default profile, popups included); `Engine.profiles().list()` -> `Profile.browsers()` for other profiles |
| Engine options | `Engine.options()` -> `EngineOptions.remoteDebuggingPort()` (`Optional<Integer>`), `renderingMode()`, `switches()`, `userDataDir()` |
| Browser state | `browser.Browser.url()`, `title()`, `isClosed()`, `engine()`, `profile()`, `size()` |
| Frames | `Browser.mainFrame()` (`Optional<Frame>`), `frames()`, `focusedFrame()`; `frame.Frame.isMain()`, `name()`, `parent()`, `children()`, `browser()` |
| JavaScript | `<T> T Frame.executeJavaScript(String)` (blocking); `void Frame.executeJavaScript(String, java.util.function.Consumer<?>)` |
| Events | `Subscription on(Class<E>, event.Observer<E>)` on `Browser`, `Engine` and `Navigation`; `Subscription.unsubscribe()` |
| Console | `browser.event.ConsoleMessageReceived.consoleMessage()` -> `js.ConsoleMessage.level()`, `message()`, `source()`, `lineNumber()` |
| Load | `Browser.navigation()`; `navigation.event.FrameLoadFinished` (`frame()`, `url()`), `FrameDocumentLoadFinished`, `FrameLoadFailed`, `NavigationFinished` (`url()`, `frame()`, `isInMainFrame()`, `hasCommitted()`, `error()`) |
| Closed | `browser.event.BrowserClosed`; `engine.event.EngineClosed`, `EngineCrashed` |
| Resize | `Browser.resize(int, int)`; "By default Browser size is empty" |
| Bitmap | `Browser.bitmap()` -> `ui.Bitmap.pixels()` (`byte[]`, BGRA, pre-multiplied), `size()` -> `ui.Size.width()`, `height()`; `BitmapTimeoutException` after 10 s |
| Swing view | `view.swing.BrowserView` (a `JComponent`): `static newInstance(Browser)`, `getBrowser()` |
| Target id | `Browser.devTools().remoteDebuggingUrl()` -> `Optional<String>`, "a valid URL only if the Engine was configured with the Remote Debugging Port" |
| Input | `Browser.dispatch(MousePressed | MouseReleased | MouseMoved | MouseWheel | KeyPressed | KeyTyped | KeyReleased)` (8 and 9 add touch events) |
| Single-slot callbacks | `Browser.set(Class<C>, C)`, `get(Class<C>)` -> `Optional<C>`, `remove(Class<C>)`; for example `InjectJsCallback` |

(All types are under `com.teamdev.jxbrowser.`.) The `devtools` package contains only the `DevTools` interface in
all three lines: there is no in-process protocol channel like JCEF's.

Evidence: https://teamdev.com/jxbrowser/api/7.44.2/ (Javadoc pages `engine/Engine`, `browser/Browser`,
`frame/Frame`, `profile/Profile`, `devtools/DevTools`, `ui/Bitmap`, `view/swing/BrowserView`, `event/Observer`,
`event/Subscription`, `js/ConsoleMessage`, `js/JsPromise`, `navigation/event/*`, `engine/EngineOptions`) ;
https://teamdev.com/jxbrowser/api/8.18.3/java/ and https://teamdev.com/jxbrowser/api/9.5.2/java/ (the same types) ;
https://teamdev.com/jxbrowser/docs/7/guides/browser/ , `/javascript/` , `/navigation/` , `/engine/` ,
`/browser-view/` .

Status: verified against the API reference. Not verified: behaviour, and the first 7.x release that has each member
(`Engine.browsers()` carries no "since" tag; `Browser.bitmap()` and `devTools()` say 7.1).

### F22

What differs between the lines and what reflection must cope with.

- **Java level, not API.** 7 runs on Java 8 to 21; 8 and 9 need 17 or newer. The vendor describes 7 -> 8 as "mostly
  compatible with minor API differences"; the migration guide's concrete break is DOM attributes
  (`element.attributeValue("src")` became `element.attributes().get("src")`, likewise put, remove, has, list).
  The proposed adapter does not use the vendor's DOM API at all (it uses JavaScript), so this break does not
  reach it.
- **Additions.** 8.18.3 has, and 7.44.2 lacks, touch dispatch, `downloadUrl`, user-agent data, `BigInt` to
  `BigInteger` conversion and symbol-keyed property access on `JsObject`. 9 adds `Engine.newBrowser(RenderingMode)`
  and `Profile.newBrowser(RenderingMode)`:
  the rendering mode becomes a per-browser property, so `engine.options().renderingMode()` no longer tells the mode
  of a given browser.
- **Native input by default in 9.** From 9.0.0 input events reach Chromium directly from the operating system
  instead of being forwarded from the Java toolkit (`-Djxbrowser.native.input.enabled=false` restores forwarding).
  Synthetic AWT events dispatched to a `BrowserView` are then not expected to reach the page.
- **Reflection mechanics.** Vendor interfaces the adapter must implement (`event.Observer`, and `JsPromiseHandler`
  or `InjectJsCallback` if used) are implemented with `java.lang.reflect.Proxy` in the class loader that loaded the
  vendor classes. `java.util.function.Consumer` and `java.util.Optional` are JDK types and need no proxy. Members
  are looked up by name once per vendor class loader, cached, and a missing optional member turns into a missing
  capability, not an error.

Evidence: https://teamdev.com/jxbrowser/migration/from-v7-to-v8/overview/ ; the release notes and API references
cited in F20 and F21 ; https://teamdev.com/jxbrowser/docs/guides/rendering/ (9.5.2: per-browser mode).

Status: verified for the documented differences. Not verified: that no undocumented signature change exists
between the lines for the members in F21 beyond what the three API references show; the effect of native input on
AWT-dispatched events (an inference).

### F23

Discovery. JxBrowser has no static registry of engines. The agent reaches one from a seed: a `BrowserView` found by
the Swing-tree scan (`getBrowser()` then `engine()`), or an engine or browser handed in by the application's domain
module. From an engine, `browsers()` plus the profiles' `browsers()` list every live browser, including cached and
hidden ones. An engine none of whose browsers is in a window, and for which no hint exists, is invisible.

Evidence: F21.

Status: verified (API); the completeness claim is a deduction.

### F24

JavaScript results. The blocking variant returns converted values (`Double`, `String`, `Boolean`, `null`,
`JsObject`, `JsArray`, `JsPromise`, DOM objects); `JsObject` instances are live references that stay valid until
the page unloads or they are closed. The asynchronous variant delivers the same value to a `Consumer` "without
blocking the current thread". For an automation channel the safe contract is: the agent wraps the script so that it
returns **a JSON string** (or an error envelope), which crosses as `java.lang.String` and leaves no live object
behind. Promises: `JsPromise.then(JsPromiseHandler)` exists in all three lines, but a portable alternative needs no
vendor callback type: the wrapper stores the settled value in a private slot and the agent collects it with a
second evaluation.

Evidence: https://teamdev.com/jxbrowser/docs/7/guides/javascript/ and `/docs/8/guides/javascript/` (type table,
blocking and asynchronous variants) ; API reference `js/JsPromise`.

Status: verified for the API. Not verified: the vendor's threading rules for the blocking variant on the EDT (no
statement was found); the adapter avoids the question by using the `Consumer` variant from an agent worker.

### F25

Events and callbacks. Events are multi-subscriber (`on` returns a `Subscription`), so the agent can observe console
messages, loads and closure without disturbing the application. Callbacks are single-slot (`set` replaces what the
application registered). `InjectJsCallback`, the hook that runs before a document's scripts, is such a callback and
applications use it to install their host bridge. An agent that wants a true document-start hook must chain to the
previous occupant (`get`) and restore it on detach, and loses the hook if the application sets its own callback
again later. The non-intrusive alternative is `FrameDocumentLoadFinished` followed by an evaluation, which is later
than document start, so a single-fire page event can be missed (design §3.8 already provides flag polling for that
case).

Evidence: API reference `browser/Browser` (`set`, `get`, `remove`, `on`) ;
https://teamdev.com/jxbrowser/docs/7/guides/javascript/ (`InjectJsCallback` example).

Status: verified for the API; the chaining behaviour is a design consequence, not tested.

### F26

Rendering modes. `HARDWARE_ACCELERATED`: Chromium renders on a native surface placed above the Java window; the
`BrowserView` is heavyweight and "regular UI components cannot appear over" it. `OFF_SCREEN`: pixels are copied into
a buffer in the Java process and the `BrowserView` is a lightweight component that paints them with `Graphics2D`.
So a `PAINT` capture includes an `OFF_SCREEN` view and not a `HARDWARE_ACCELERATED` one (swing §F19), while
`Browser.bitmap()` works in both.

Evidence: https://teamdev.com/jxbrowser/docs/guides/rendering/ ; browser §F11.

Status: verified (documentation).

### F27

Remote-debugging port. Set with `EngineOptions.newBuilder(mode).remoteDebuggingPort(port)` before the engine is
created; it cannot be added later (browser §C0); one port per Engine (browser §F2). New here: the configured value
is readable in process through `browser.engine().options().remoteDebuggingPort()`, so `web.cdp.status` can report
the port, or its absence, as a fact without guessing. The same options object exposes `licenseKey()`; the agent
must never read or forward it.

Evidence: API reference `engine/EngineOptions` (7.44.2 and 8.18.3) ;
https://teamdev.com/jxbrowser/docs/7/guides/engine/ .

Status: verified.

### F28

Licensing constraints for this repository. JxBrowser "is a commercial library that requires a valid license key in
order to work"; the key is given by the `jxbrowser.license.key` system property or the `licenseKey` engine option;
a 30-day evaluation exists. The published individual licence agreement allows a licensee to "reproduce and
distribute the Software in executable form as part of the programs that you create", forbids making it available
"separately from the programs that you create", and forbids decompiling or reverse engineering it. (Project and
enterprise licences have their own agreements, which were not read.) Consequences:

- No JxBrowser artifact may be a dependency of any module here, not even in `provided` or `test` scope, because the
  build of an open-source repository must resolve without the vendor's repository and without a key. The adapter
  refers to vendor types only by name, as strings.
- Public CI cannot start a JxBrowser engine. The adapter's behaviour against the real product is tested in a
  repository whose owner holds a licence.
- Everything in this document about JxBrowser comes from the public guides and API reference. Reading published
  documentation and calling public methods by reflection is ordinary API use.

Evidence: https://teamdev.com/jxbrowser/docs/guides/introduction/licensing/ ;
https://teamdev.com/jxbrowser/licence-agreement/ (individual licence agreement, sections 3, 4.1, 5.1).

Status: verified for the quoted terms. This is not legal advice; whether stub classes that imitate vendor package
names would be acceptable in a public repository was not assessed, and R6 avoids needing them.

## Comparison

### F30

Operation by operation. "DT" means "through `CefBrowser.getDevToolsClient()`" (F8); it needs no debug port.

| Operation | JCEF (`org.cef.*`) | JxBrowser 7 / 8 / 9 (reflective) | Impossible or caveat |
|---|---|---|---|
| Detect the technology | `org.cef.CefApp` loadable; `CefApp.getState()` | `com.teamdev.jxbrowser.engine.Engine` loadable | Never `CefApp.getInstance()` (F3) |
| Find engines | one per process (`CefApp` singleton) | none; from a `BrowserView` or a hint | JxBrowser: an engine with no view in a window and no hint is invisible |
| List browsers | non-public: `CefApp.self` -> `clients_` -> `getAllBrowser()`; or hints | public: `Engine.browsers()`, `Profile.browsers()` | JCEF: reflection into private members, fork-dependent |
| Swing component to browser | compare with each `getUIComponent()` | `BrowserView.getBrowser()` | JCEF: needs the browser list first |
| URL | `getURL()` | `url()` | |
| Frames | `getMainFrame()`, `getFrameIdentifiers()`; DT `Page.getFrameTree` | `mainFrame()`, `frames()`, `children()`, `parent()` | JxBrowser frames have no id and no URL accessor: the adapter mints keys and asks `location.href` |
| Evaluate with a result | DT `Runtime.evaluate` (`returnByValue`) | `Frame.executeJavaScript(String, Consumer)` | JCEF before 2024-01: none (cefQuery only if the application's own router is reused) |
| Await a promise | `awaitPromise: true` | wrapper plus a second evaluation, or `JsPromise.then` | |
| Evaluate in a sub-frame | DT with the frame's execution context | `executeJavaScript` on that `Frame` | JCEF: context ids only arrive after `Runtime.enable` |
| Script before page scripts | DT `Page.addScriptToEvaluateOnNewDocument` | `InjectJsCallback` (single slot, chain and restore) or after `FrameDocumentLoadFinished` | JxBrowser without the callback: later than document start |
| Load events | DT `Page.*` events; `CefLoadHandler` only if the slot is free | `navigation().on(FrameLoadFinished ...)` | |
| Console | DT `Runtime.consoleAPICalled`; `CefDisplayHandler` only if free | `on(ConsoleMessageReceived)` | |
| Closed | `getDevToolsClient() == null`, list difference; `CefLifeSpanHandler` only if free | `on(BrowserClosed)`, `isClosed()` | JCEF: no free event, so polling |
| Resize | component bounds; DT `Emulation.setDeviceMetricsOverride` | `resize(w, h)` | JCEF: no public resize |
| Bitmap | DT `Page.captureScreenshot`; off-screen only: `createScreenshot` | `bitmap()` | JCEF windowed: `createScreenshot` throws |
| In a Swing `PAINT` capture | upstream: neither mode expected; a lightweight off-screen component: yes | `OFF_SCREEN` yes, `HARDWARE_ACCELERATED` no | otherwise `ROBOT` capture |
| Synthetic input | DT `Input.dispatch*` | `dispatch(...)` | JCEF `send*Event` are protected |
| Raw protocol call in process | yes | **no** | impossible on JxBrowser without a port |
| Is a debug port configured | private `settings_`; `DevToolsActivePort`; probe | `engine().options().remoteDebuggingPort()` | |
| Set the port after start | no | no | impossible on both |
| Port scope | process | Engine | |
| CDP target id | DT `Target.getTargetInfo` (not verified) | parse `devTools().remoteDebuggingUrl()` | JxBrowser: empty without a port |
| Runs on a Java 8 target | upstream and jcefmaven: stated yes; runtime-bundled fork: no | 7 only (end of life) | |
| Licence | BSD, Apache-2.0 | commercial, key at run time | no vendor classes here |
| Runs in public CI | yes (152 MB natives, F2) | no | |

Status: each row inherits the status of the finding it summarises.

## Proposed SPI

Java 8 interfaces in `skillroy-agent-api` (package `...agent.api.spi.web`). The names are proposals.

```java
/** One per embedded-browser technology. Built-in: "jcef", "jxbrowser". A pack may register another. */
public interface WebEngineAdapter {
    String id();

    /** Are the technology's classes reachable from this loader, and which optional operations resolve?
     *  Must not create, initialise or configure an engine, and must not load a class the host has not loaded. */
    ProbeResult probe(ClassLoader hostLoader);

    /** Turns seeds into handles for every live browser reachable from them. A seed is a Swing component from the
     *  tree scan or an object from a BrowserRegistryAdapter: a browser, a view, an engine or a client. Seeds of
     *  another technology are ignored. May add browsers no seed pointed at (JCEF: the process-wide list). */
    List<WebBrowser> browsers(Collection<?> seeds);

    /** The remote-debugging endpoint configured at engine start, per engine; empty if none. Read-only fact for
     *  web.cdp.status. The adapter never sets it. */
    List<CdpEndpoint> cdpEndpoints(Collection<?> seeds);
}

public interface WebBrowser {
    String adapterId();
    Object vendorObject();                 // identity for the weak handle registry; never sent over the wire
    Set<WebCapability> capabilities();     // what this handle can do, decided at probe time
    boolean isClosed();
    String url();                          // "" when no document
    Component component();                 // the Swing component showing it, or null
    WebRendering rendering();              // WINDOWED, OFFSCREEN_LIGHTWEIGHT, OFFSCREEN_HEAVYWEIGHT, UNKNOWN
    Dimension viewport();                  // null if unknown
    List<WebFrame> frames();               // main frame first; empty when no document

    /** Evaluates an expression and completes with its value as JSON text ("null" for undefined). */
    CompletableFuture<String> evaluate(WebEval request);

    WebSubscription subscribe(WebEventListener listener);     // LOAD_FINISHED, LOAD_FAILED, NAVIGATED, CONSOLE, CLOSED
    WebSubscription addInitScript(String source);             // DOCUMENT_START where supported, else DOCUMENT_LOADED
    CompletableFuture<Void> resize(int width, int height);    // RESIZE
    CompletableFuture<BufferedImage> bitmap();                // BITMAP
    String cdpTargetId();                                     // null when unknown
}

public final class WebEval {      // value object
    String expression; String frameKey /* null = main frame */; boolean awaitPromise; long timeoutMs;
}
public final class WebFrame {     // snapshot value, not a live object
    String key; String parentKey; String name; String url; boolean main;
}
public interface WebEventListener { void onEvent(WebEvent event); }   // kind, frameKey, url, level, text, source, line
public interface WebSubscription  { void cancel(); }
public enum WebCapability { EVALUATE, AWAIT_PROMISE, FRAMES, EVENTS_LOAD, EVENTS_CONSOLE,
                            INIT_SCRIPT_DOCUMENT_START, RESIZE, BITMAP, CDP_TARGET_ID, INPUT, RAW_PROTOCOL }
```

`BrowserRegistryAdapter` (design §2.6) keeps its role and becomes technology-neutral: a domain module returns
seeds, optionally tagged, for browsers the tree scan cannot see. It no longer needs to know any vendor type.

```java
public interface BrowserRegistryAdapter { Collection<BrowserSeed> seeds(); }
public final class BrowserSeed { Object object; String tag; }   // a browser, a view, an engine or a client
```

Reasoning.

- **Two interfaces because there are two things that differ**: how browsers are found (F5 against F23) and how one
  browser is operated. Engines do not need a type of their own: nothing in `web.*` addresses an engine, JCEF has
  exactly one, and JxBrowser's is reachable from every browser.
- **`evaluate` returns JSON text.** It is the one result type both sides produce without live objects (F8, F24),
  it is what the wire carries anyway, and it removes the vendor's value types (`JsObject`, `Double` for every
  number) from the contract. A thrown script error completes the future exceptionally with message and stack.
- **`CompletableFuture`, not callbacks or blocking calls.** JCEF already returns one; JxBrowser's `Consumer` adapts
  in one line; and the caller (a primitive on an agent worker) applies the bounded wait. Adapters must complete
  futures without ever waiting on the EDT, and must not call a blocking vendor method on the EDT (F4, F24).
- **A frame is a snapshot with an opaque key**, because JCEF's two id spaces and JxBrowser's id-less `Frame`
  objects have nothing in common except "main frame, parent, name, URL". The adapter owns the key-to-object map.
- **Capabilities instead of a lowest common denominator.** Document-start scripts, promise support, resize and
  target ids are available on one side, conditionally on the other; the capability matrix already has
  `OK | DEGRADED(reason) | UNAVAILABLE(reason)` for exactly this, and `addInitScript` reports which timing it got.
- **Written once on top of it**: `web.browsers`, `web.evaluate`, `web.ariaSnapshot` (a JavaScript walker),
  `web.dom.click|fill|text`, `web.dispatchEvent`, `web.tap` (init script plus a buffer the agent drains),
  `web.resize`, `web.bitmap`, and the `web.load` / `web.console` event topics. `visibleInSwing` is
  `component() != null && component().isShowing()`, computed by the generic code.
- **Seeds are `Object`s** so that the SPI module has no vendor types and a pack for a third technology (JavaFX
  `WebView`, for example) can plug in without changing it.

What stays outside the SPI.

- **CDP as a transport.** The remote-debugging port, its WebSocket, Playwright, target lists, tracing: all
  controller-side (`cdp.attach`, `cdp.pageFor`, design §3.8). The SPI only reports two facts for it:
  `cdpEndpoints()` and `cdpTargetId()`. That the JCEF adapter speaks the DevTools protocol in process (F8) is an
  implementation detail; it involves no port and no Playwright.
- **Engine creation and configuration.** No adapter creates an engine, sets a port, a switch or a licence key.
- **Navigation.** Scenarios drive the application, which drives its web views. A `navigate` operation is not in
  the catalogue and is not proposed.
- **Raw protocol passthrough and synthetic input.** `RAW_PROTOCOL` and `INPUT` are reserved capability names, with
  no methods yet. Input is the natural hook for the later "clicks like a human" work: a vision model picks a point
  on `bitmap()`, and the adapter delivers a trusted pointer event at that point (`Browser.dispatch` on JxBrowser,
  `Input.dispatchMouseEvent` on JCEF) without using the OS pointer. A passthrough (`web.protocol.send`) is cheap on
  JCEF and impossible on JxBrowser, so it would be an adapter-specific extension, not part of the common contract.
- **The vendor DOM APIs.** Not used; JavaScript replaces them and sidesteps the one documented 7 -> 8 break (F22).

Module layout. The row `skillroy-agent-jxbrowser` in design §2.1 would become one module, for example
`skillroy-agent-web`: the generic primitives, the injected script library and both built-in adapters. Both adapters
are reflective and have no compile-time dependency, so separate jars would add packaging without isolating
anything. JCEF could legally be a `provided` dependency (BSD), but reflection is still the better choice there:
the classes must be resolved through the loader that loaded the application's JCEF (a plugin host may keep it out
of the agent's parent chain), and the upstream and runtime-bundled variants differ in their non-public parts.

## Testing in public CI

**JCEF: a real embedded browser can run in the Linux test image.** What it takes, stated plainly:

- A small sample target in `samples/` (Java 8 bytecode, Swing) that embeds JCEF through jcefmaven and shows two
  browsers, one visible and one cached and hidden, loading pages from `data:` or `file:` URLs (no network at test
  time). This puts JCEF's classes into that sample's JVM as the application's own dependency; the rule "no
  third-party classes inside the target JVM" is about what the agent brings, and the adapter stays reflective. The
  sample would be a separate module so that the existing sample application stays dependency-free.
- Downloads: `jcef-natives-linux-amd64` is a 152 MB jar per platform (F2), resolved once into the local Maven
  repository, plus about 4.3 MB of API, JOGL and bootstrap jars. Declaring the natives as a test-scope dependency is
  preferable to jcefmaven's first-run download, because the build then works offline from the Maven cache. On first
  use the bundle is extracted to an install directory whose size was not measured. Developers on other platforms
  need that platform's natives jar (142-171 MB) or run the tests through `ci/run-in-linux.sh` only.
- The image (`ci/Dockerfile`, currently a JDK 25 base with Xvfb, a few X libraries and fonts) will need Chromium's
  runtime libraries (typically NSS, ATK, CUPS, DRM/GBM, XKB, X composite/damage/fixes/randr, Pango/Cairo, ALSA) and,
  for off-screen mode, Mesa's software GL. The exact package list is not verified; finding it is part of the spike.
- Matrix: rendering mode (windowed, off-screen) by target JVM (Java 8, Java 25). Java 16 and newer need the three
  `--add-exports` flags for off-screen mode (F1). Windowed mode needs `CefApp.startup` before the first AWT call
  (F4), which the sample's `main` controls.
- Tests skip when headless, like the other display tests, and should be a separate Maven profile so that a
  contributor without the 152 MB bundle can still build.

**JxBrowser: not runnable in public CI** (F28). Three layers cover it as far as honesty allows:

1. An adapter contract kit, an abstract test class in a `test-jar`: the same assertions for every
   `WebEngineAdapter` (enumerate, evaluate values, errors and promises, frames, events, init-script timing, resize,
   bitmap, closure, behaviour after the browser is closed, no call blocks the EDT). Public CI runs it against JCEF.
2. A repository that holds a JxBrowser licence runs the same kit against the real product, for each line it uses.
3. In public CI the JxBrowser adapter's reflection plumbing (name lookup, proxies, capability degradation when a
   member is missing, weak caching per class loader) is exercised against a neutral fake with the same shapes. For
   that the adapter takes its class and member names from a small descriptor instead of hard-coding them in logic,
   so the fake needs no class in the vendor's package namespace. This proves the mechanics, **not** compatibility
   with JxBrowser.

The CDP half is unchanged: base spike BS5 against a stock Chromium, about 194 MB for Playwright's driver bundle
(browser §F17). A JCEF sample started with `remote_debugging_port` set could later replace the stock Chromium as
the BS5 target and exercise both paths against one embedded browser.

## Recommendations

### R0

Adopt the two-interface SPI above, with JSON-text `evaluate`, snapshot frames and capability flags, and write every
`web.*` primitive against it. Change the design document's module row and `BrowserRegistryAdapter` contract first
(the contract precedes the code).

### R1

Build the JCEF adapter on `CefBrowser.getDevToolsClient()` and nothing else for operations: evaluate, events, init
script, bitmap, viewport override, target id. Treat a JCEF without that method as `UNAVAILABLE` for the in-process
path (CDP over a port remains). Do not use `CefMessageRouter` and do not occupy handler slots.

### R2

Enumerate JCEF browsers in this order: seeds from `BrowserRegistryAdapter`; then the reflective walk from
`CefApp` (only in state `INITIALIZED`, never through `getInstance()`); then report `DEGRADED` with the reason when
the non-public members are not found. Never fail the module because enumeration failed.

### R3

Build the JxBrowser adapter on the F21 members only, resolve them per vendor class loader, and use the `Consumer`
variant of `executeJavaScript` from an agent worker. Subscribe with `on`; do not `set` any callback by default. Make
the `InjectJsCallback` chain an opt-in option of the pack, restored on detach.

### R4

Run one spike before building (name suggestion: BS8, "JCEF in the Linux test image"): the JCEF sample under the
WM-less Xvfb, both rendering modes, Java 8 and Java 25 targets. Record the package list, the extracted size, start-up
time, and the results of: `Runtime.evaluate` round trip, `Target.getTargetInfo`, `Page.captureScreenshot` in both
modes, `Page.addScriptToEvaluateOnNewDocument`, `printAll` over each component type, and the reflective
enumeration. Every "not verified" item of F1, F8-F12, F14 and F15 is answered by it.

### R5

Keep rule and wording from design §3.8: the debug port is a launch-time precondition and the base never injects
it. Add the two facts the adapters can now report (`cdpEndpoints`, `cdpTargetId`) to `web.cdp.status` and
`web.browsers`.

### R6

Publish the contract kit as a test artifact so that a private repository with a licence can run it against
JxBrowser, and record in the capability matrix which adapter and which product line a run used.

### R7

Reserve `INPUT` and `RAW_PROTOCOL` now, implement neither in Phase 4. Revisit `INPUT` together with the vision
work, and note for it that JxBrowser 9's native input changes what AWT-level events can do (F22).

## Constraints

### C0

Adapters never create, initialise or configure an engine. For JCEF that includes never calling
`CefApp.getInstance()` (F3); for JxBrowser never calling `Engine.newInstance`.

### C1

No wait on the EDT for a browser result, and no blocking vendor call on the EDT. With JCEF off-screen rendering
results arrive on the EDT itself (F4); JxBrowser's blocking calls are IPC to another process and can hang with it.
Every future gets a bounded wait on an agent worker; a timed-out call is abandoned, not retried on another thread.

### C2

Nothing single-slot is taken: no `add*Handler` on a `CefClient` (it would be ignored or, in a free slot, block the
application's later registration), no `Browser.set(callback)` unless a pack opts in and the previous occupant is
chained and restored (F6, F25).

### C3

The JCEF DevTools client belongs to the application as much as to the agent: never `close()` it, re-subscribe when
it was closed, do not disable protocol domains on detach (F9). On detach the agent removes its listeners and its
init scripts and clears any viewport override.

### C4

`evaluate` runs arbitrary JavaScript in the application's pages. It is a mutating primitive (not served in
read-only mode), and the injected helper library must not leave enumerable globals behind: one private slot, or an
isolated world where the adapter offers one.

### C5

No vendor type appears in the SPI module, in any signature or on the wire. No JxBrowser artifact is a dependency in
any scope, and no licence key is read, logged or forwarded (`EngineOptions.licenseKey()` is off limits) (F27, F28).

### C6

A remote-debugging port cannot be added after engine start on either product, is per process on JCEF and per Engine
on JxBrowser, and without one JxBrowser has no target id and no protocol access at all (F13, F27, F30).

### C7

`PAINT` capture shows a web view only when its component is lightweight: JxBrowser `OFF_SCREEN`, or an
application-supplied lightweight JCEF off-screen component. For every other combination the evidence image comes
from `web.bitmap` or from `ROBOT` capture (F11, F26).

### C8

A Java 8 target can contain upstream JCEF or JxBrowser 7 only. JxBrowser 8 and 9 and the runtime-bundled JCEF fork
imply a Java 17+ target; the agent module is the same Java 8 bytecode in all cases (F1, F20).

## Risks

### Risk0

The JCEF path rests on one channel (`getDevToolsClient()`) that was read, not run. If `Runtime.evaluate` or
`Target.getTargetInfo` behaves differently through it, the JCEF adapter loses its result path or its page mapping.
Mitigation: R4 before any build.

### Risk1

Reflective enumeration of JCEF browsers depends on private members (`self`, `clients_`, `getAllBrowser()`), which
can change with any release and differ in the out-of-process variant of the runtime-bundled fork. Mitigation:
seeds first, reflection second, `DEGRADED` third (R2); a strongly encapsulated module layout in the target could
also refuse `setAccessible`.

### Risk2

Deadlock or freeze of the target through the EDT (JCEF off-screen) or through a blocking JxBrowser call into a
hung Chromium process. Mitigation: C1, and a contract-kit test that fails when any adapter call runs on, or waits
for, the EDT.

### Risk3

Sharing the JCEF DevTools session with the application: the application sees events the agent enabled, and an
application that closes its client drops the agent's listeners. Mitigation: C3; the residual effect (domains stay
enabled) is accepted and documented.

### Risk4

JCEF under a WM-less Xvfb may not work in one or both rendering modes (JOGL's GL context, focus, the `XInitThreads`
ordering). Mitigation: R4; if only one mode works, public CI covers that mode and says so.

### Risk5

CI weight: 152 MB of natives per platform, an unmeasured extraction, extra image packages, a second sample target.
Mitigation: a separate Maven profile and a cached Maven repository; never download at test time.

### Risk6

The JxBrowser adapter is unverifiable in the open. A regression there is only seen by a licence holder.
Mitigation: the contract kit (R6) and the descriptor-driven fake; the capability matrix names the adapter and line.

### Risk7

JxBrowser 7 is end of life and is the only line for Java 8 targets; 9 changes input delivery and makes the
rendering mode per browser. Mitigation: the adapter uses only members present in all three lines and treats the
rendering mode as `UNKNOWN` when it cannot be read per browser.

### Risk8

An init script or helper library that the page can observe may change application behaviour (name clashes,
content-security rules). Mitigation: C4; whether in-process evaluation is subject to a page's content security
policy on either product is not verified.

## Not verified (collected)

- JCEF 152 running on a Java 8 JVM; minimum glibc of its Linux natives; extracted bundle size (F1, F2).
- JCEF on an Xvfb display without a window manager, in either rendering mode; the Linux package list; JOGL's GL
  context there (F15).
- `Runtime.evaluate`, `Page.*` events, `Page.addScriptToEvaluateOnNewDocument`, `Page.captureScreenshot` (windowed
  mode in particular), `Emulation.setDeviceMetricsOverride`, `Input.dispatch*` and `Target.getTargetInfo` through
  `CefDevToolsClient` (F8, F10-F12, F14, F16).
- Whether the application and the agent share one protocol session per browser in CEF (F9).
- What `printAll` renders for a JOGL `GLCanvas` (F11).
- Mapping between CEF frame identifiers and DevTools frame ids (F7).
- The private members used for enumeration in the runtime-bundled JCEF fork's out-of-process mode; that fork's
  current Chromium version (F0, F5).
- JxBrowser: any behaviour at all (API reference only); the first 7.x release with each member; threading rules of
  the blocking `executeJavaScript`; a per-browser rendering-mode accessor in 9; the effect of 9's native input on
  AWT-dispatched events; parsing the target id out of `remoteDebuggingUrl()` (browser §F10 rates it "likely")
  (F21-F24).
- Whether in-process script evaluation bypasses a page's content security policy on either product (Risk8).
- The legal acceptability of vendor-named stub classes in a public repository (F28); the proposal avoids them.

## Open questions

1. Should the in-process JCEF protocol channel be exposed to the controller as an adapter-specific primitive
   (`RAW_PROTOCOL`)? It would give accessibility trees and trusted input without a debug port, but only for JCEF.
2. Is a JCEF sample target acceptable in `samples/` given its download size, or should it live behind an opt-in
   profile only?
3. Does the frame notion need to be in the first cut, or is main-frame-only enough for Phase 4 with `frameKey`
   reserved? The SPI above carries it; the primitives could ignore it at first.
4. Which component should own the generic JavaScript library (aria walker, DOM actions, tap buffer): the agent web
   module only, or a shared resource that the controller's Playwright path also injects, so that both
   implementations of a `web.*` verb return identical shapes?
