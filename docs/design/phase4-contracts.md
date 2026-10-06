# Phase 4 contracts: embedded web views, CDP and the in-process web SPI

**Status:** specified 2026-10-06, not yet built. Spike BS8 (JCEF in the Linux test image) was run the same day and
passed with conditions; its results are in `../../spikes/BS8/RESULT.md` and summarised in §2.5, and every statement
here that rests on them says so. Where this file and the code disagree once the code exists, one of the
two has a defect; change the contract first, then the code. **Scope:** Phase 4 of `base-framework-design.md`
("Roadmap": embedded web views, CDP and the in-process SPI; decision B9). This file is the single source of truth
for Phase 4 in the way `phase2-contracts.md` and `phase3-contracts.md` are for their phases: where it and the design
overview (§3.8, §2.4, §2.6) or the research notes disagree on a Phase 4 detail, this file wins.

**What it builds on.** The Phase 1 contract as built (with Phase 1.x), the Phase 2 and Phase 3 contracts as built,
the design overview §3.8 (two paths, one SPI, the module and its two adapters), decision B9 (one SPI wrapping JCEF
and reflective JxBrowser access), the research notes `embedded-browser-spi` (cited as "browser-spi §Fn") and
`embedded-browser-cdp` (cited as "browser §Fn"), and spike BS8. References of the form "P1 §6", "P2 §8.3" and
"P3 §5.1" mean sections of `phase1-contracts.md`, `phase2-contracts.md` and `phase3-contracts.md`.

**Reading guide.** §1 scope and non-goals; §2 modules, dependencies, versions and what BS8 established; §3 the
embedded-browser SPI (`…agent.api.spi.web`); §4 the agent module `skillroy-agent-web`: primitives, events,
capabilities, the two adapters, the script library; §5 `skillroy-cdp`; §6 the controller side: the two paths, the
`web` fact root and snapshot surface, evidence; §7 scenario YAML and `plan.json` version 4; §8 failure taxonomy and
run outputs; §9 the samples: the web host and its scenarios; §10 the adapter contract kit and the neutral fake;
§11 build, image and licences; §12 acceptance; §13 open gaps taken; §14 decisions, with the changes to the earlier
contracts in §14.1; §15 the questions for the owner, answered provisionally.

---

## 1. Scope and non-goals

Phase 4 delivers:

- **the embedded-browser SPI** in `skillroy-agent-api`, package `ai.skillroy.testjava.agent.api.spi.web`: one
  `WebEngineAdapter` per embedded-browser technology, one `WebBrowser` handle per live browser, optional operations
  behind `WebCapability` flags, snapshot frames, JSON-text evaluation, and a technology-neutral
  `BrowserRegistryAdapter` that hands the agent seeds for browsers the Swing tree cannot show (§3);
- **one agent module, `skillroy-agent-web`** (module id `web`): the generic `web.*` primitives written once against
  the SPI, the injected script library, the `web.*` event topics, and the two built-in reflective adapters `jcef`
  (on `CefBrowser.getDevToolsClient()`, no debug port needed) and `jxbrowser` (JxBrowser 7, 8 and 9 through one
  descriptor-driven adapter). No vendor artifact is a dependency of any module in any scope; no vendor type appears
  in a signature or on the wire (§4);
- **`skillroy-cdp`**, controller side: the Playwright `connectOverCDP` wrapper with the liveness probe, the external
  deadline, page mapping by target id, readiness helpers and tracing (§5);
- **two paths for every web verb** with the path recorded per step: CDP where a remote-debugging endpoint exists and
  answers, the in-process SPI otherwise; the `web` base fact root, a `WEB` surface in the UI snapshot and web
  evidence on failure (§6);
- **scenario YAML**: the base verbs `webClick`, `webFill`, `webDispatch`, `webWaitFlag`, `webResize`, the scenario key
  `web` (path policy, default browser, frozen-target recovery) and `plan.json` version 4 (§7);
- **the samples**: a small JCEF host application behind the Maven profile `jcef` (`samples/skillroy-sample-web-host`),
  its agent module (`skillroy-sample-web-host-agent`, a `BrowserRegistryAdapter` for the hidden browser), its domain
  pack and scenarios (`skillroy-sample-web-host-scenarios`): one scenario that passes over CDP and the same scenario
  over the in-process path (§9);
- **the adapter contract kit** as a test artifact (`skillroy-agent-web` `test-jar`), run here against a real JCEF
  browser in the Linux image and against the neutral fake of the `jxbrowser` adapter; a repository that holds a
  JxBrowser licence runs it against the real product (§10);
- **the Linux test image** gains Chromium's run-time libraries (built and used by BS8 on 2026-10-06; §11).

**Non-goals.** Named so that their seams are respected, not built:

| Not in Phase 4 | Seam reserved here | Phase |
|---|---|---|
| Synthetic pointer or key input into a page (`INPUT`), the vision counterparts `web.hitTest`, `web.pointer` | the `WebCapability` names `INPUT` and `RAW_PROTOCOL` exist without methods (B9, B16); the primitive names are reserved in the design catalogue | 7 |
| A raw protocol passthrough (`web.protocol.send`) | `RAW_PROTOCOL`, reserved; an adapter-specific extension, never part of the common contract (browser-spi "What stays outside the SPI") | later, if ever |
| Navigation from a scenario (`web.navigate`) | none: scenarios drive the application, which drives its web views (design §3.8). `web.reload` exists for frozen-target recovery only (§4.2) | none |
| Injecting a remote-debugging port into a target | none: the port is a launch-time precondition the base never sets (design §3.8; browser-spi §C0, §C6) | none |
| Video of an attached browser | none: Playwright cannot record an attached context (browser §F18); evidence is bitmaps and traces | 6 (X11 grab, design §6) |
| A third adapter (JavaFX `WebView`, for example) | a pack registers its own `WebEngineAdapter` through `SpiRegistry`; the kit tests it | later |
| Playwright locators in scenario YAML (`getByRole` strings) | the base verbs take `selector` or `role`+`name` (§7.1); a richer locator language is a later amendment | later |
| Any model call | unchanged from Phases 2 and 3 | 5 |

Phase 4 adds **one new controller-side module** (`skillroy-cdp`), **one new agent-side module**
(`skillroy-agent-web`), **three new sample modules behind a profile** and **one new third-party dependency**
(Playwright Java, Apache-2.0, controller side only; §11). JCEF reaches the build only as a dependency of the
profile-gated sample host; the agent module never depends on it.

---

## 2. Module layout, dependencies, versions and build

### 2.1 Decision

- **The SPI lives in `skillroy-agent-api`**, package `…agent.api.spi.web` (B9; design §2.6, §3.8). It is Java 8 and
  references JDK types only (`java.awt.Component`, `java.awt.Dimension`, `java.awt.image.BufferedImage`,
  `java.util.concurrent.CompletableFuture`). It names no vendor type.
- **The script library lives in `skillroy-protocol`** as the resource `ai/skillroy/testjava/protocol/web/web-lib.js`
  with the accessor class `…protocol.web.WebLib` (D48). Reason: both implementations of a web verb must return
  identical shapes (browser-spi, open question 4), so the walker, the DOM actions and the tap buffer are one source
  read by the agent module (through `evaluate`) and by `skillroy-cdp` (through `page.evaluate` / `addInitScript`).
  `skillroy-protocol` is the module both sides already share (P2 D2 precedent).
- **`skillroy-agent-web`** (`agent/skillroy-agent-web`, `--release 8`, module id `web`, package
  `…agent.web`): depends on `skillroy-agent-api` and `skillroy-protocol` only. Sub-packages: `…agent.web`
  (module, primitives, browser index, events), `…agent.web.jcef`, `…agent.web.jxbrowser`, `…agent.web.reflect`
  (the descriptor-driven reflection plumbing both adapters use). Its `test-jar` is the adapter contract kit (§10).
- **`skillroy-cdp`** (`controller/skillroy-cdp`, `--release 25`, package `…cdp`): depends on `skillroy-scenario`
  (it implements the `WebPath` SPI of §6.1), `skillroy-controller`, `skillroy-protocol` and Playwright Java. It is
  **not** a dependency of `skillroy-scenario`, `skillroy-junit` or `skillroy-cli`: a suite that wants the CDP path
  puts the `skillroy-cdp` jar on its class path (Maven dependency, or `--pack <jar>` for the CLI, which already
  accepts jars without a `DomainPack`, P2 §7.2) and the executor finds the path with `ServiceLoader<WebPath>` (D49).
  Reason: Playwright's driver bundle is about 194 MB (browser §F17); the CLI distribution and the JUnit glue stay
  as they are, and the agent-only path works everywhere.
- **`skillroy-scenario`** gains the `WebPath` SPI (package `…scenario.web`), the built-in `AgentWebPath`, the base
  web verbs, the `web` fact root and the `WEB` snapshot surface. No new third-party dependency.
- **The samples** (`samples/skillroy-sample-web-host`, `…-web-host-agent`, `…-web-host-scenarios`) are listed in the
  root POM inside the profile `jcef`, activated with `-Pjcef`; a build without the profile does not see them (D50).
  The host and its agent module are `--release 8`; the scenarios module is `--release 25`. The host depends on
  `me.friwi:jcefmaven:152.0.6` (compile) and on the platform natives artifact in **test** scope of the scenarios
  module and the host's own tests, chosen by a Maven property (`skillroy.jcef.platform`, default `linux-amd64`;
  §11). Nothing downloads at test time: jcefmaven finds the natives on the class path and extracts them once into
  `target/jcef-bundle` (BS8 measured the extraction, §2.5).

Dependency graph after Phase 4 (arrows = "depends on"; new edges in **bold**):

```
protocol   (gains …protocol.web: WebLib)
agent-api  (gains …agent.api.spi.web)
**agent-web -> agent-api, protocol**              **cdp -> scenario, controller, protocol, playwright**
scenario -> controller, data                      junit -> scenario            cli -> scenario, data, controller, protocol
agent-dist (gains agent-web in modules/)
**web-host -> jcefmaven (+ natives, test)**       **web-host-agent -> agent-api, web-host**
**web-host-scenarios -> scenario, junit, cdp, dist, web-host, web-host-agent (test), agent-web test-jar (test)**
```

No cycle. `skillroy-agent-dist` adds `modules/skillroy-agent-web.jar` to the agent home (P1 §2.1: the home's jar list
and `DistributionContentIT` grow by one).

### 2.2 Versions

- `Plan.VERSION` becomes **4** (§7.3). `BindingsFile.VERSION` stays 1 (nothing in a bindings file changes).
- Wire protocol stays **0.2**: Phase 4 adds primitives, topics and capabilities, which are catalogued, not
  protocol changes (P1 §3).
- New module version strings: `skillroy-agent-web` `0.1.0` (the agent jar's Maven version through the filtered
  properties file, as the Swing module does); the web host pack `0.1.0`.

### 2.3 Build conventions

As P1 §1 and P2 §2.3. The licence header check covers the new `.java`, `.xml`, `.sh` and `Dockerfile` files; the
script library `web-lib.js` carries the header as a `/* … */` comment and `ci/license-headers.sh` learns the `.js`
kind (comment style `java`). New third-party artifacts are audited in `../licence-audit.md` before they enter a
distribution (§11).

### 2.4 Threads

The agent module follows the thread rules of P1 §6 and browser-spi §C1: adapter calls run on agent workers
(`ThreadPolicy.WORKER`) with the bounded wait applied by the primitive to the adapter's future; nothing waits on the
EDT for a browser result; Swing reads (component bounds, `isShowing`) are `EDT_SYNC` reads bounded as in the Swing
module; no blocking vendor call is made on the EDT. The JCEF adapter must respect that CEF's UI thread *is* the EDT
with off-screen rendering (browser-spi §F4), which BS8 confirmed by observing the completion thread of the
DevTools futures (§2.5).

### 2.5 What BS8 established (2026-10-06)

BS8 passed with conditions on 2026-10-06 (`../../spikes/BS8/RESULT.md` has the method, the numbers per configuration and
the diagnosis). What it fixes in this contract:

| BS8 finding | Consequence here |
|---|---|
| JCEF 152 (jcefmaven 152.0.6, Chromium 152) runs under the image's WM-less Xvfb once fifteen Chromium run-time libraries are installed (§11); both rendering modes on a Java 25 target, off-screen on a Java 8 target; the same under x86-64 emulation, slower | the public acceptance runs both modes on the Java 25 target and off-screen on the Java 8 target (§12.2) |
| Chromium 152's first-run "Additional Terms of Service" dialog blocks `CefInitialize` until accepted; accepting writes the empty sentinel files `First Run` and `EULA Accepted` into `root_cache_path`; pre-creating them skips the dialog | the web host creates both in its `root_cache_path` before `CefAppBuilder.build()` (§9.1); the agent never does this (an application concern); `web.cdp.status` and the adapter's probe are unaffected |
| The in-process DevTools channel works as the research read it: `Runtime.evaluate` with `returnByValue` (p50 0.1-0.4 ms in process), `awaitPromise`, the error and rejection shapes, `Page.*` and `Runtime.consoleAPICalled` events, `Page.addScriptToEvaluateOnNewDocument` at document start, `Input.dispatchMouseEvent`, `Page.captureScreenshot` in both modes, `Emulation.setDeviceMetricsOverride` on a 1 x 1 hidden browser | the `jcef` adapter is built on it as §4.5 says; `INIT_SCRIPT_DOCUMENT_START`, `RESIZE`, `BITMAP`, `EVENTS_*` are `OK` on JCEF 152 |
| Futures complete on the EDT in off-screen mode and on a CEF thread in windowed mode | §2.4 and rule 4 of §3 stand; the kit's EDT watchdog (§10.1 item 3) covers both |
| `Target.getTargetInfo` and `Target.getTargets` never answer through this page-level client; the main frame's id in `Page.getFrameTree` equals the page's `id` in `/json/list` | `cdpTargetId()` on JCEF is `Page.getFrameTree().frameTree.frame.id` (D55: a future, one protocol call); no `Target.*` call is made |
| About one evaluation in twenty timed out (30 s), mostly the first after a navigation; every later call answered | every adapter call is bounded by the caller (§4.2) and the generic module retries a **read** once after a `web.load` event on that browser; a mutating call is never retried |
| A browser whose Swing component is displayable but not showing gets no native browser until `createImmediately()`; its viewport is 1 x 1 until overridden; the first `getDevToolsClient()` of a browser created that way can report `Client is closed` | `web.browsers` lists handles without a native browser with `capabilities: []` and `url: ""`; the adapter re-fetches a closed client before every call (rule 3 of §3); `web.resize` is the answer to the 1 x 1 viewport (browser §C6 confirmed) |
| A page whose iframe uses `srcdoc` never finished loading in the image (the renderer idles); plain pages and a `data:`-URL iframe load, and `getFrameIdentifiers()` lists the child frame | the sample pages (§9.1) and the kit's pages (§10.1) use `data:`-URL iframes, never `srcdoc`; the frame model of §3 stands |
| `printAll` throws `IllegalStateException` for the off-screen JOGL canvas and paints the windowed canvas blank | the Swing module's `PAINT` capture guards embedded-browser components (catches the exception and paints a placeholder rectangle); listed in §14.1 as a P1 §6 change; `web.bitmap` is the evidence image (§6.4) |
| Windowed JCEF 152 on a Java 8 target created no native browser (no error logged; cause open) | the Java 8 leg of §12.2 runs off-screen only; windowed JCEF on Java 8 is **unverified** and so marked in the capability strategy ("jcef 152, windowed, Java 8: unverified") until the cause is known (`../TODO.md`) |
| Reflective enumeration (`CefApp.self -> clients_ -> getAllBrowser()`, `settings_`) works on 152.0.6 | the descriptor `jcef.json` names exactly these members (§4.7) |
| Chromium's `root_cache_path` must be on the container's own file system; natives are per platform | the web host defaults `-Dwebhost.cache` to a fresh temporary directory (§9.1); the profile's natives artifact is chosen by `skillroy.jcef.platform` (§11) |

---

## 3. The embedded-browser SPI (`ai.skillroy.testjava.agent.api.spi.web`)

Java 8 interfaces and value types in `skillroy-agent-api`. The shapes follow the "Proposed SPI" of
`research/embedded-browser-spi.md`, adopted by B9; the differences are listed at the end of this section. Every
type here is implemented by an adapter and consumed by the generic module; a domain pack implements only
`BrowserRegistryAdapter` (and, for a third technology, a `WebEngineAdapter`).

```java
/** One per embedded-browser technology. Built in: "jcef", "jxbrowser"; a pack registers another through
 *  SpiRegistry.register(WebEngineAdapter.class, …). Thread-safe; called on agent workers. */
public interface WebEngineAdapter {
    /** Stable id: lower-case letters and digits ("jcef", "jxbrowser"). Also the suffix of the capability web.adapter.<id>. */
    String id();

    /** Are the technology's classes reachable from the host's loader, and which optional operations resolve?
     *  Must not create, initialise or configure an engine, and must not load a class the host has not loaded
     *  (Class.forName(name, false, hostLoader) is the only lookup allowed; a class that is not yet loaded by the host
     *  counts as absent). The strategy of an OK result names the product and line it found ("jcef 152", "jxbrowser 7"). */
    ProbeResult probe(ClassLoader hostLoader);

    /** Turns seeds into handles for every live browser reachable from them. A seed is a Swing component the tree scan
     *  found, or an object a BrowserRegistryAdapter handed in (a browser, a view, an engine or a client, as that
     *  technology knows them). Seeds of another technology are ignored. May add browsers no seed pointed at (JCEF:
     *  the process-wide list, browser-spi §F5). The same live browser yields a handle that is equal() to the one
     *  returned before, so that the module's HandleRegistry keeps one ref per browser. Never throws for a bad seed:
     *  it is skipped and reported through enumerationNotes(). */
    List<WebBrowser> browsers(Collection<?> seeds);

    /** The remote-debugging endpoints configured at engine start, one per engine, empty if none or unknown. A
     *  read-only fact for web.cdp.status; the adapter never sets or probes a port. */
    List<CdpEndpoint> cdpEndpoints(Collection<?> seeds);

    /** How complete the last browsers() call was: OK (every route worked), DEGRADED(reason) (seeds only: the
     *  technology's own list could not be read), UNAVAILABLE(reason) (the technology is present but no browser can be
     *  reached). Drives the capability web.browsers. */
    ProbeResult enumerationState();

    /** Called once when the module is uninstalled: remove every listener and init script the adapter added, clear
     *  viewport overrides, forget cached reflection. Must not close anything that belongs to the application
     *  (browser-spi §C3). Never throws. */
    void detach();
}

/** One live browser. Methods complete futures; the caller (a primitive on an agent worker) applies the bounded
 *  wait. An adapter never waits on the EDT and never makes a blocking vendor call on the EDT (browser-spi §C1). */
public interface WebBrowser {
    String adapterId();
    /** Identity for the weak handle registry (the vendor's browser object); never serialised. */
    Object vendorObject();
    /** The technology's own browser id when it has one (JCEF getIdentifier(), as text), else null. Informational. */
    String nativeId();
    /** What this handle can do, decided when the handle is created from the probe and the browser's state. */
    Set<WebCapability> capabilities();
    boolean isClosed();
    /** The main frame's URL; "" when there is no document yet. */
    String url();
    /** The Swing component that shows this browser, or null (a browser without a view). */
    Component component();
    WebRendering rendering();
    /** The layout viewport in CSS pixels as the adapter knows it, or null if unknown. */
    Dimension viewport();
    /** Snapshot of the frame tree, main frame first; empty when there is no document. Needs FRAMES. */
    List<WebFrame> frames();
    /** Evaluates an expression and completes with its value as JSON text ("null" for undefined and null). A script
     *  error completes exceptionally with WebScriptException (message, stack text); a frame key that no longer exists
     *  with WebFrameGoneException. Needs EVALUATE; awaitPromise needs AWAIT_PROMISE. */
    CompletableFuture<String> evaluate(WebEval request);
    /** Delivers load, navigation, console and closure events until cancelled. Needs EVENTS_LOAD / EVENTS_CONSOLE
     *  for the respective kinds; CLOSED is always delivered when the adapter can observe it. */
    WebSubscription subscribe(WebEventListener listener);
    /** Registers a script that runs in every new document of this browser, before the page's scripts where the
     *  technology allows it (INIT_SCRIPT_DOCUMENT_START), else as early as it can (after the document loaded). The
     *  returned subscription's timing() says which. Cancelling removes it for future documents. */
    WebInitScript addInitScript(String source);
    /** Changes the layout viewport without touching Swing where the technology allows it (RESIZE). */
    CompletableFuture<Void> resize(int width, int height);
    /** Clears a viewport override made by resize(); completes at once when there is none. */
    CompletableFuture<Void> clearResize();
    /** A bitmap of the page as rendered, independent of whether the component is showing (BITMAP). */
    CompletableFuture<BufferedImage> bitmap();
    /** The CDP target id of this browser, or null when unknown (CDP_TARGET_ID). */
    CompletableFuture<String> cdpTargetId();
}

public final class WebEval {                 // immutable value; builder
    public String expression();              // required
    public String frameKey();                // null = main frame
    public boolean awaitPromise();           // default false
    public long timeoutMs();                 // the adapter abandons the call after this (default 10000)
    public static Builder of(String expression);
}
public final class WebFrame {                // snapshot value, not a live object
    public String key();                     // opaque, stable while the frame lives; minted by the adapter
    public String parentKey();               // null for the main frame
    public String name();                    // "" when unnamed
    public String url();
    public boolean main();
}
public final class WebEvent {
    public enum Kind { LOAD_FINISHED, LOAD_FAILED, NAVIGATED, CONSOLE, CLOSED }
    public Kind kind();  public String frameKey();  public String url();
    public String level();  public String text();  public String source();  public int line();   // CONSOLE
    public String error();                                                                        // LOAD_FAILED
}
public interface WebEventListener { void onEvent(WebBrowser browser, WebEvent event); }
public interface WebSubscription { void cancel(); }
public interface WebInitScript extends WebSubscription { Timing timing(); enum Timing { DOCUMENT_START, DOCUMENT_LOADED } }
public enum WebCapability { EVALUATE, AWAIT_PROMISE, FRAMES, EVENTS_LOAD, EVENTS_CONSOLE, INIT_SCRIPT_DOCUMENT_START,
                            RESIZE, BITMAP, CDP_TARGET_ID, INPUT /* reserved */, RAW_PROTOCOL /* reserved */ }
public enum WebRendering { WINDOWED, OFFSCREEN_LIGHTWEIGHT, OFFSCREEN_HEAVYWEIGHT, UNKNOWN }
public final class CdpEndpoint { public String host(); public int port(); public String engineLabel(); /* "jcef", or a JxBrowser engine's user-data directory name */ }
public final class WebScriptException extends Exception { public String stack(); }
public final class WebFrameGoneException extends Exception { public String frameKey(); }

/** Technology-neutral (replaces the vendor-typed sketch of design §2.6): a domain module hands the agent seeds for
 *  browsers the Swing-tree scan cannot see (cached, hidden, or without a view). Called on agent workers; must be
 *  cheap and must not block. */
public interface BrowserRegistryAdapter { Collection<BrowserSeed> seeds(); }
public final class BrowserSeed {
    public static BrowserSeed of(Object object);  public static BrowserSeed of(Object object, String tag);
    public Object object();   public String tag();   // tag: a pack's name for the browser ("hidden-card"), or null
}
```

Rules every adapter obeys (browser-spi §C0-§C5, §C7; design §3.8), all of them tested by the contract kit (§10):

1. **No engine is created, initialised or configured.** JCEF: never `CefApp.getInstance()`, only `getState()` and,
   in state `INITIALIZED`, the existing instance (browser-spi §F3). JxBrowser: never `Engine.newInstance`.
2. **Nothing single-slot of the application is taken.** No `add*Handler` on a `CefClient`, no `Browser.set(callback)`
   (browser-spi §F6, §F25). The one exception is a pack's explicit opt-in to chain `InjectJsCallback` (§4.5), which
   restores the previous occupant on detach.
3. **The JCEF DevTools client is shared with the application** and is never closed by the agent; a client found
   closed is re-fetched and the agent's listeners re-subscribed; protocol domains the agent enabled stay enabled on
   detach (browser-spi §F9, §C3).
4. **No wait on the EDT, no blocking vendor call on the EDT** (browser-spi §C1).
5. **The script library leaves no enumerable globals behind**: one non-enumerable slot, `window[Symbol.for(
   "skillroy")]`, holds the library and the tap buffer (§4.6).
6. **No vendor type in a signature or on the wire; no vendor artifact in any scope; no licence key read, logged or
   forwarded** (`EngineOptions.licenseKey()` is off limits; browser-spi §C5, §F27, §F28).
7. **A missing optional member is a missing capability, not an error**: the handle's `capabilities()` omits it and
   the primitive that needs it answers `CAPABILITY_UNAVAILABLE` with the reason (browser-spi §F22, §R3).

Differences from the research note's proposal, all additions: `nativeId()`, `enumerationState()` and `detach()`
(the module needs them for the capability matrix and for `uninstall`); `clearResize()` (C3 asks for overrides to be
cleared on detach, so the operation has to exist); `cdpTargetId()` is a future (JCEF answers it through a protocol
call); `WebInitScript.timing()` instead of an untyped subscription (the primitive records which timing it got);
`CdpEndpoint.engineLabel()` (JxBrowser has one port per engine); typed exceptions for script errors and dead frames.

---

## 4. `skillroy-agent-web` (module id `web`)

### 4.1 Install, readiness and the browser index

`WebModule.install` registers the primitives, declares the capabilities of §4.4 as `UNAVAILABLE("no embedded
browser technology found")` and registers nothing with the host. It touches no AWT class and no vendor class
(P1 §4: `install` runs before the application's `main` in `JAVAAGENT` mode). The two built-in adapters are
constructed but not probed.

`onTargetReady` runs the probes: each built-in adapter and each `WebEngineAdapter` registered through `SpiRegistry`
is asked `probe(hostClassLoader)`; an adapter whose probe is `UNAVAILABLE` is kept out of the index until the next
reprobe (`agent.capabilities {reprobe:true}` runs the probes again, so an application that starts its browser engine
late, or a pack that registers an adapter late, is picked up; the module also re-probes an `UNAVAILABLE` adapter on
the first `web.*` call after a `window.opened` event). The capability `web.adapter.<id>` takes the probe's result and
strategy.

**The browser index** (`BrowserIndex`) is rebuilt on every `web.browsers` call and on every call that names a browser
whose ref is unknown or stale, bounded at 2 s of agent-worker time:

1. seeds from every `BrowserRegistryAdapter` (each adapter is called once; one that throws is logged and skipped);
2. seeds from the Swing tree: every showing or displayable component of every window (the Swing module's walk,
   `EDT_SYNC`, bounded as `swing.snapshot`), handed to every adapter as seeds (an adapter recognises its own view
   components and ignores the rest);
3. each adapter's `browsers(seeds)`; the handles are given refs of kind `browser` through the `HandleRegistry`
   (`handleFor(handle.vendorObject(), "browser")`, so the ref is stable for the life of the vendor object);
4. the `web.browsers` capability becomes the worst `enumerationState()` of the adapters that found anything, `OK`
   when every adapter that is present reports `OK`.

A browser that disappeared from the index answers `STALE_HANDLE` to a call that names it (P1 §4 `HandleRegistry`).

### 4.2 Primitives

Browser selection: every primitive except `web.browsers` and `web.cdp.status` takes `browser: <ref>` **or**
`by: {url: <regex>} | {tag: <string>} | {title: <regex>} | {nativeId: <string>}`, resolved against the index: zero
matches is `NOT_FOUND` with `data.browsers` (the index as `web.browsers` lists it), several is `AMBIGUOUS` with the
candidates. With neither, the default is the single browser whose component is showing; several or none is
`AMBIGUOUS` / `NOT_FOUND` with the same data. `frame?: <frameKey>` selects a frame where a primitive says so; the
default is the main frame.

| Primitive | Params | Result `value` | Thread, timeout, capability, flags |
|---|---|---|---|
| `web.browsers` | `includeClosed?: bool (false)` | `{browsers:[{ref, adapter, nativeId?, url, title?, closed, visibleInSwing, component?: <ref>, rendering, viewport?: {w,h}, capabilities:[…], cdpTargetId?, tag?, frames:[{key,parentKey?,name,url,main}]}]}`; `title` and `frames` need `EVALUATE`/`FRAMES` and are omitted otherwise; `cdpTargetId` is read with a 2 s bound and omitted when unknown | `WORKER`, 10000, `web.browsers` |
| `web.evaluate` | browser selection, `expression: string`, `frame?`, `awaitPromise?: bool (false)` | `{json: <text>, typeofResult: string}` where `json` is the value as JSON text (`"null"` for undefined). A script error is `SCRIPT_ERROR` (new code, §8) with `data {message, stack}` | `WORKER`, 10000, `web.evaluate`, **mutating** (browser-spi §C4) |
| `web.ariaSnapshot` | browser selection, `frame?`, `maxNodes?: int (2000, cap 20000)`, `interestingOnly?: bool (true)` | `{nodes:[{role, name, value?, checked?, disabled?, focused?, level?, children?}], truncated}`: the library's walker over the accessibility semantics of the DOM (role from ARIA role or element kind, name by the accessible-name computation the library implements, as Playwright's `ariaSnapshot` does for the same elements) | `WORKER`, 10000, `web.evaluate`; not mutating (D51) |
| `web.dom.text` | browser selection, `frame?`, `selector?: string` (CSS; default `body`), `maxChars?: int (10000, cap 100000)` | `{text, truncated}` (the element's `innerText`) | `WORKER`, 10000, `web.evaluate`; not mutating |
| `web.dom.click` (mutating) | browser selection, `frame?`, **one of** `selector: string`, or `role: string` + `name: string|{regex}` + `index?: int`; `expectVisible?: bool (true)` | `{matched: int, clicked: {role, name, tag, id?}, fidelity: "DOM"}`; zero matches `NOT_FOUND` with `data.selector/role/name`, several without `index` `AMBIGUOUS` with `data.candidates`; a hidden element with `expectVisible` is `INVALID_PARAMS` (`data.reason: "not visible"`) | `WORKER`, 10000, `web.evaluate` |
| `web.dom.fill` (mutating) | as click, plus `text: string`, `clear?: bool (true)`, `enter?: bool (false)` | `{matched, filled: {tag, type, id?}, typed: int}`; the library sets the value through the native setter so that framework bindings see it, then dispatches `input` and `change`, and `Enter` as a `keydown`/`keyup` pair when asked | `WORKER`, 10000, `web.evaluate` |
| `web.dispatchEvent` (mutating) | browser selection, `frame?`, `name: string`, `detail?: any`, `target?: selector (default window)` | `{dispatched: true}`: `target.dispatchEvent(new CustomEvent(name, {detail, bubbles: true}))` | `WORKER`, 10000, `web.evaluate` |
| `web.tap` (mutating) | browser selection, `patterns: [string]` (event names or globs `app.*`), `target?: "window"|"document"` | `{installed: int, timing: DOCUMENT_START|DOCUMENT_LOADED}`: an init script that listens for the named events on every new document and publishes each as `web.tap` (§4.3); re-installing replaces the patterns | `WORKER`, 10000, `web.events` |
| `web.waitFlag` | browser selection, `frame?`, `expression: string` (truthy means ready), `timeoutMs?: int (0 = the call's budget, at most 600000)`, `pollMs?: int (50, min 20)` | `{ready: bool, ms, polls}`: evaluated on the agent worker until truthy or the budget ends; a step with `sync: EVENT` on `web.ready` pairs with it (§6.3). Answers `ready:false` before the dispatcher's `TIMEOUT` | `WORKER`, 10000, `web.evaluate`; not mutating |
| `web.resize` (mutating) | browser selection, `width: int`, `height: int` (1..16384), or `clear: true` | `{applied: "OVERRIDE"|"COMPONENT", viewport: {w,h}}`: the adapter's `resize` (a protocol-level override on JCEF, `Browser.resize` on JxBrowser); `COMPONENT` is reserved for a later Swing-bounds variant and is not built | `WORKER`, 10000, `web.resize` |
| `web.bitmap` | browser selection, `maxWidth?: int (4096)` | `{format: "png", width, height, base64, source: "PROTOCOL"|"VENDOR"}`; scaled down keeping the aspect ratio above `maxWidth` | `WORKER`, 20000, `web.bitmap`; not mutating |
| `web.reload` (mutating) | browser selection, `ignoreCache?: bool (false)` | `{reloaded: true}`: `CefBrowser.reload()` / `Navigation.reload()` through the adapter. Exists for frozen-target recovery (§5.3) and for a pack's own recover; no base verb emits it | `WORKER`, 10000, `web.browsers` |
| `web.cdp.status` | – | `{endpoints:[{host, port, engineLabel, adapter}], browsers:[{ref, cdpTargetId?}]}`: the configured endpoints the adapters report (JCEF: from the private `settings_` of `CefApp`, else from `<cache dir>/DevToolsActivePort` when the cache path is known, else none; JxBrowser: `engine().options().remoteDebuggingPort()`); nothing is probed over the network by the agent | `WORKER`, 5000, `web.browsers`; not mutating |

Every `web.*` call runs on `WORKER`; the adapter's futures are awaited with the call's remaining budget less 100 ms;
a timed-out future is abandoned (`cancel(false)`), never retried on another thread (browser-spi §C1), and the call
answers `TIMEOUT`. A browser that closed between selection and call answers `STALE_HANDLE`.

`web.evaluate` wraps the caller's expression so that the result crosses as JSON text: `JSON.stringify` of the value
with a replacer that turns `undefined` into `null`, functions and symbols into their `String()`, DOM nodes into
`{nodeName, id, className}` and cyclic structures into `"[cycle]"`, capped at 1 MiB (`data.truncated: true` in the
result when cut).

### 4.3 Events

Published on the agent's `EventBus` with source `web`; payload `browser` is the browser's ref.

| Topic | Payload | When |
|---|---|---|
| `web.load` | `{browser, url, frameKey?, kind: LOAD_FINISHED \| LOAD_FAILED \| NAVIGATED, error?}` | the adapter's load events (JCEF: `Page.frameNavigated`, `Page.loadEventFired`, `Page.loadingFailed` through the shared client; JxBrowser: `FrameLoadFinished`, `FrameLoadFailed`, `NavigationFinished`) |
| `web.ready` | `{browser, url, signal: LOAD \| TAP \| FLAG, name?}` | the main frame finished loading (`LOAD`); a tapped pattern fired (`TAP`, `name` = the event name); `web.waitFlag` turned ready (`FLAG`). The step protocol's "web.ready" (design §2.5) is this topic |
| `web.console` | `{browser, level, text, source?, line?}` | console output; `text` capped at 2000 characters and passed through the core's secret redaction (A34 names apply to the call that caused it, by key only otherwise) |
| `web.tap` | `{browser, name, detail?}` | a tapped page event; `detail` is the event's `detail` as JSON (capped at 64 KiB, `truncated: true` when cut) |
| `web.closed` | `{browser}` | the adapter observed the browser closing (JCEF: `getDevToolsClient()` turned null or the browser left the process-wide list at the next index rebuild; JxBrowser: `BrowserClosed`) |
| `web.browsers.changed` | `{added: [ref], removed: [ref]}` | an index rebuild found a difference |

Load and console subscriptions are made once per browser when it first enters the index and cancelled on `detach`.
On JCEF they require `Page.enable` and `Runtime.enable` on the shared DevTools client, which stay enabled on detach
(browser-spi §C3; BS8 §2.5 shows what the application then also sees).

### 4.4 Capabilities

| Id | `OK` when | Otherwise |
|---|---|---|
| `web.adapter.<id>` | the adapter's probe is `OK`; strategy = product and line ("jcef 152", "jxbrowser 7") | the probe's `DEGRADED` or `UNAVAILABLE` with its reason; one entry per built-in and registered adapter |
| `web.browsers` | the last index rebuild reached every route of every present adapter | `DEGRADED("seeds only: …")` when a present adapter could not read the technology's own list (JCEF: the private members were not found); `UNAVAILABLE` when no adapter is present |
| `web.evaluate` | at least one indexed browser has `EVALUATE` | `UNAVAILABLE` with the first adapter's reason (JCEF without `getDevToolsClient()`: "JCEF older than 2024-01: no in-process result channel", browser-spi §F8) |
| `web.events` | at least one indexed browser has `EVENTS_LOAD` | as above |
| `web.initScript` | at least one indexed browser has `INIT_SCRIPT_DOCUMENT_START`; strategy `DOCUMENT_START` | `DEGRADED("after document load")` when scripts can only be added late (JxBrowser without the opt-in of §4.5) |
| `web.resize` | at least one indexed browser has `RESIZE` | `UNAVAILABLE` |
| `web.bitmap` | at least one indexed browser has `BITMAP` | `UNAVAILABLE` |
| `web.cdp` | at least one endpoint is configured (the agent's view; the controller decides whether it answers, §6.1) | `UNAVAILABLE("no remote-debugging endpoint configured")` |
| `web.hittest`, `web.pointer` | never (reserved, B16) | `UNAVAILABLE("reserved for the vision bolt-on")` |

Capabilities are probed after `onTargetReady` and on reprobe, as every other module's (P1 §5); the per-browser
flags above are evaluated at probe time over the index of that moment.

### 4.5 The two built-in adapters

**`jcef`** (`…agent.web.jcef.JcefAdapter`), on `CefBrowser.getDevToolsClient()` and nothing else for operations
(browser-spi §R1), everything by reflection through the host's loader:

- *Probe.* `org.cef.CefApp` loadable from the host loader without initialisation; `CefApp.getState()` read by
  reflection; `CefBrowser.getDevToolsClient` present (else `UNAVAILABLE` for the in-process path, with CDP over a
  port still possible); `CefDevToolsClient.executeDevToolsMethod(String, String)` and `addEventListener` present.
  Strategy: "jcef <Chromium major>" from `CefApp.getVersion()` when the state is `INITIALIZED`, else "jcef".
- *Enumeration* (browser-spi §R2): seeds first (a `CefBrowser`, a `CefClient` (its `getAllBrowser()`), a
  `CefApp` (its clients), or a component equal to some browser's `getUIComponent()` or a descendant of it); then,
  only in state `INITIALIZED`, the reflective walk `CefApp.self -> clients_ -> getAllBrowser()`; when the private
  members are missing or `setAccessible` is refused, `enumerationState()` is `DEGRADED("seeds only: <reason>")`.
  BS8 verified the walk against jcefmaven 152.0.6 (§2.5); the member names are a descriptor (§4.7) so that a fork
  can be served by another descriptor without code changes.
- *Operations.* `evaluate` = `Runtime.evaluate {expression, returnByValue: true, awaitPromise}` (with
  `contextId` for a sub-frame, taken from `Runtime.executionContextCreated` events); `frames` = `Page.getFrameTree`,
  keys are the protocol's frame ids; events = `Page.*` and `Runtime.consoleAPICalled` / `Runtime.exceptionThrown`;
  `addInitScript` = `Page.addScriptToEvaluateOnNewDocument` (timing `DOCUMENT_START`), removed with
  `Page.removeScriptToEvaluateOnNewDocument`; `resize` = `Emulation.setDeviceMetricsOverride` and
  `clearDeviceMetricsOverride`; `bitmap` = `Page.captureScreenshot` (source `PROTOCOL`, both rendering modes;
  `createScreenshot` is not used); `cdpTargetId` = the main frame's `id` in `Page.getFrameTree` (BS8: the `Target`
  domain is not served by this client, and that id equals the `/json/list` entry); `cdpEndpoints` = `settings_.
  remote_debugging_port` when readable, else the `DevToolsActivePort` file under `root_cache_path`/`cache_path`.
  `rendering()` = `WINDOWED` for `CefBrowserWr`, `OFFSCREEN_HEAVYWEIGHT` for `CefBrowserOsr` (a JOGL canvas),
  `OFFSCREEN_LIGHTWEIGHT` for an off-screen browser whose component is lightweight (an application's own
  component), `UNKNOWN` otherwise.
- *Rules.* The client is never closed; a client whose `isClosed()` is true is re-fetched and re-subscribed before
  every call (BS8 saw a freshly created browser's first client closed); the message router is not used; no handler
  slot is taken (browser-spi §R1, §C2, §C3). Results complete on CEF's UI thread, the EDT with off-screen rendering
  and a CEF thread with windowed rendering (BS8, §2.5); the adapter hands them over to the caller's future without
  running anything else on that thread. A browser without a native browser (not yet shown, §2.5) is a handle with
  no capabilities until the application creates it.

**`jxbrowser`** (`…agent.web.jxbrowser.JxBrowserAdapter`), on the public members that lines 7, 8 and 9 share
(browser-spi §F21, §R3), resolved per vendor class loader from the descriptor `jxbrowser.json` (§4.7):

- *Probe.* `com.teamdev.jxbrowser.engine.Engine` loadable from the host loader (no initialisation); the F21 members
  resolve; strategy "jxbrowser <line>" from the `Browser`/`Engine` class's package version or the vendor's
  `version.info` resource when readable, else "jxbrowser".
- *Enumeration.* Seeds only (browser-spi §F23): a `BrowserView` (`getBrowser()`), a `Browser`, an `Engine`
  (`browsers()` plus every profile's `browsers()`), a `Profile`; the Swing-tree seeds are matched by the view class.
  `enumerationState()` is `OK` when at least one seed resolved, `DEGRADED("no seed: an engine without a view in a
  window is invisible")` when none did while the technology is present.
- *Operations.* `evaluate` = `Frame.executeJavaScript(String, Consumer)` from an agent worker with the wrapper of
  §4.2 (the result crosses as a `String`); `awaitPromise` = the wrapper stores the settled value in the private slot
  and a second evaluation collects it (browser-spi §F24; `AWAIT_PROMISE` is therefore present); `frames` =
  `mainFrame()`, `frames()`, `children()`, keys minted per `Frame` identity; events through `on(Class, Observer)`
  with `java.lang.reflect.Proxy` observers (`FrameLoadFinished`, `FrameLoadFailed`, `NavigationFinished`,
  `ConsoleMessageReceived`, `BrowserClosed`); `addInitScript` = after `FrameDocumentLoadFinished` (timing
  `DOCUMENT_LOADED`) unless the pack opted in to chaining `InjectJsCallback` (`web.jxbrowser.chainInjectJs=true` in
  the agent options; then `DOCUMENT_START`, the previous occupant is called first and restored on detach);
  `resize` = `Browser.resize(int, int)`; `clearResize` restores the size read before the first resize; `bitmap` =
  `Browser.bitmap()` (BGRA pre-multiplied to `TYPE_INT_ARGB`); `cdpTargetId` = the last path segment of
  `devTools().remoteDebuggingUrl()` when present, else null; `cdpEndpoints` = `engine().options().
  remoteDebuggingPort()` per distinct engine, `engineLabel` the user-data directory's file name.
  `rendering()` = `OFFSCREEN_LIGHTWEIGHT` when the view component is lightweight and the engine's mode reads
  `OFF_SCREEN`, `WINDOWED` for `HARDWARE_ACCELERATED`, `UNKNOWN` on line 9 where the mode is per browser and not
  readable (browser-spi §F22, §Risk7).
- *Rules.* `licenseKey()` is never read; no `set(...)` callback by default; `dispatch(...)` (input) is not used
  (reserved `INPUT`); no DOM API of the vendor is used (browser-spi §F22).

### 4.6 The script library (`skillroy-protocol`, `…protocol.web.WebLib`)

One JavaScript source, `web-lib.js`, installed on first use through `evaluate` (in process) or `addInitScript`
(CDP), idempotent, under the non-enumerable slot `window[Symbol.for("skillroy")]`:

| Function | Returns | Used by |
|---|---|---|
| `aria(root, maxNodes, interestingOnly)` | the `web.ariaSnapshot` node list | `web.ariaSnapshot`, the `WEB` surface, `cdp` |
| `find(selectorOrRole, name, index)` | the matched element, or `{count, candidates}` | `web.dom.click`, `web.dom.fill`, `web.dom.text` |
| `click(el)` | `{role, name, tag, id}` | `web.dom.click` (`el.click()` after a visibility check; `pointerdown`/`mousedown`/`mouseup` are not synthesised: that is `INPUT`) |
| `fill(el, text, clear, enter)` | `{tag, type, id, typed}` | `web.dom.fill` |
| `text(el, maxChars)` | `{text, truncated}` | `web.dom.text`, the `web` fact root |
| `dispatch(target, name, detail)` | `true` | `web.dispatchEvent` |
| `tap(patterns, target)` and `drain()` | installs listeners; returns and clears the buffered events | `web.tap`, the agent's tap poller (every 100 ms on an agent worker, which publishes `web.tap`) |
| `wrap(expression)` | the JSON-text wrapper of §4.2 | `web.evaluate` on both paths |

`WebLib.source()` returns the text; `WebLib.hash()` its SHA-256, which both paths record in `run.jsonl` (§8.2) so
that a difference between the two paths can be traced to a library version. The library has unit tests on both
sides: in `skillroy-protocol` (the text is a resource and the hash is stable), in `skillroy-agent-web` against the
contract kit's pages, and in `skillroy-cdp` through Playwright.

### 4.7 Descriptors and the reflection plumbing (`…agent.web.reflect`)

Both adapters take their class and member names from a small JSON descriptor resource (`jcef.json`,
`jxbrowser.json`): roles (`browser.url`, `frame.executeJavaScriptAsync`, …) mapped to class name, member name and
parameter types, with an optional list of alternatives per role. `ReflectiveApi.resolve(descriptor, loader)` looks
every role up once per vendor class loader, caches the `Method`/`Field` objects weakly per loader, implements vendor
interfaces with `Proxy` in that loader, and turns a missing optional role into a missing capability (browser-spi
§F22, §R3). The neutral fake of §10.2 is the same code driven by a third descriptor whose classes live under the
project's own package, so that the plumbing is proven in the open without a class in a vendor's namespace
(browser-spi §F28).

---

## 5. `skillroy-cdp` (package `ai.skillroy.testjava.cdp`, Java 25)

Playwright Java `1.63.0` (`com.microsoft.playwright:playwright`; Apache-2.0; its `driver-bundle` is about 194 MB,
browser §F17). Browser downloads are never needed: the module sets `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` for the
driver it starts and attaches only (design §6 "Playwright provisioning"). The driver extracts to
`PLAYWRIGHT_BROWSERS_PATH`/`PLAYWRIGHT_DRIVER_TMPDIR` when set, else to the Java temporary directory.

### 5.1 API

```java
public record CdpEndpoint(String host, int port) { public URI httpUri(); }            // host is 127.0.0.1 unless a suite says otherwise
public record CdpOptions(Duration probeDeadlinePerTarget /*2 s*/, Duration connectDeadline /*30 s*/,
                         boolean noDefaults /*true*/, boolean isLocal /*true*/, Path artifactsDir /*null*/,
                         FrozenTargetPolicy frozenTargets /*FAIL*/) { public static CdpOptions defaults(); /* with…() */ }
public enum FrozenTargetPolicy { FAIL, RELOAD, CLOSE }        // what to do with a page target that does not answer the probe

/** GET /json/version and /json/list, then Runtime.evaluate("1") over each page target's WebSocket (java.net.http.WebSocket),
 *  each under probeDeadlinePerTarget, all targets in parallel on virtual threads. Never hangs: a target that does not
 *  answer is reported, not awaited (browser §F9, §C5). */
public final class TargetProbe {
    public static TargetProbe run(CdpEndpoint endpoint, CdpOptions options) throws CdpException;   // CdpException(UNREACHABLE) when /json/version fails
    public record Target(String id, String type, String url, String title, String webSocketDebuggerUrl,
                         boolean responsive, long millis, String error) { }
    public String browserVersion();  public String protocolVersion();  public List<Target> targets();
    public List<Target> unresponsive();
}

/** connectOverCDP under an external deadline on a worker thread; the Playwright instance is closed when the deadline
 *  passes, which ends the driver and with it the hung connect (browser §F9). Pages come from contexts().get(0).pages();
 *  never newContext() or newPage() (browser §C4). */
public final class CdpAttach implements AutoCloseable {
    public static CdpAttach connect(CdpEndpoint endpoint, CdpOptions options, TargetProbe probe) throws CdpException;
    public TargetProbe probe();  public Browser browser();  public BrowserContext context();
    /** The page whose Target.getTargetInfo (through a CDPSession) reports targetId; null when none. Mapping is cached per
     *  page and refreshed when the page list changes. */
    public Page pageFor(String targetId);
    public Map<String, Page> pagesByTargetId();
    public CdpTracing tracing();                      // start(name) / stop(path): context tracing into artifactsDir (browser §F18)
    public long connectMillis();
    @Override public void close();                    // idempotent; never throws; closes Playwright, never the browser
}
public final class CdpException extends Exception { public enum Kind { UNREACHABLE, TIMEOUT, FROZEN_TARGETS, PROTOCOL } public Kind kind(); public List<TargetProbe.Target> targets(); }
```

### 5.2 `CdpWebPath`: the CDP implementation of the web verbs

`skillroy-cdp` registers `…cdp.CdpWebPath` through `META-INF/services/ai.skillroy.testjava.scenario.web.WebPath`.
It serves every `web.*` primitive of §4.2 except `web.cdp.status`, `web.reload` and `web.browsers` (those are always
the agent's: enumeration and endpoints are facts of the target), with the same parameters and result shapes:

| Primitive | Playwright implementation |
|---|---|
| `web.evaluate` | `page.evaluate(WebLib.wrap(expression))` on the page (or `frame.evaluate` for a frame key, which the path maps through `Page.getFrameTree` ids = Playwright's frame ids) |
| `web.ariaSnapshot`, `web.dom.text`, `web.dom.click`, `web.dom.fill`, `web.dispatchEvent` | the same library functions through `page.evaluate` / `frame.evaluate`, so that both paths return byte-identical shapes for the same page state. `web.dom.click` and `web.dom.fill` additionally wait for Playwright's actionability of the matched element (`locator.waitFor(VISIBLE)`, bounded by the call's budget) before the library acts, and record `fidelity: "DOM"` as the agent does |
| `web.tap` | `context.addInitScript(WebLib.tapInstaller(patterns))` plus a `page.exposeBinding`-free drain: the path polls `drain()` every 100 ms while the step runs and publishes nothing to the agent's bus; instead the executor's event wait of §6.3 reads the path's own event list |
| `web.waitFlag` | `page.waitForFunction(expression, null, new WaitForFunctionOptions().setPollingInterval(pollMs).setTimeout(budget))` |
| `web.resize` | `page.setViewportSize(width, height)` (an emulation override, as the in-process adapter's) |
| `web.bitmap` | `page.screenshot()` (`source: "PROTOCOL"`) |

Browser selection by `browser: <ref>` is mapped through `web.browsers` of the agent (`cdpTargetId` per ref) to a
Playwright page; `by: {url|title}` is matched on the pages' own `url()`/`title()`; a ref without a target id (the
adapter has no `CDP_TARGET_ID`) makes the CDP path answer `UNSUPPORTED` for that call and the executor falls back to
the agent path for it, recorded (§6.1).

### 5.3 Frozen targets

A target that does not answer the probe within `probeDeadlinePerTarget` is **frozen**. `FrozenTargetPolicy.FAIL`
(default) ends the attach with `CdpException(FROZEN_TARGETS)` naming each frozen target (id, url, title); the executor
reports `ERROR` / `ENVIRONMENT` with that list in the detail and does not try the agent path for the scenario (the
scenario asked for CDP, §7.2). `RELOAD` and `CLOSE` ask the agent, through `web.reload {browser}` or
`web.close`-less equivalent (`CLOSE` is not built in Phase 4: `web.close` would end a browser the application owns;
the policy value is parsed and refused with a message, D52), to recover the target before a second probe, once; the
run records a `web.recover` line (§8.2). Only a scenario can turn the policy on (`web: {frozenTargets: reload}`,
§7.2); nothing recovers by default, because a reload loses the page's state and the application did not ask for it.

### 5.4 Readiness and attach timing

`CdpAttach.connect` runs on a platform worker thread with `connectDeadline`; `connectMillis()` is recorded in
`run.jsonl` (`cdp` record, §8.2). The exit criterion of the roadmap ("over 50 runs, attach to a running Chromium with
a deliberately frozen page target completes or aborts within the external deadline") is the test `CdpAttachSoakIT`
of §12.2 item 6.

---

## 6. The controller side: two paths, facts, surface, evidence

### 6.1 `WebPath` (package `…scenario.web`) and path selection

```java
/** One implementation of the web verbs: the agent's (built in) or a controller-side one found by ServiceLoader. */
public interface WebPath {
    String id();                                                   // "agent" | "cdp"
    /** Prepares the path for a run against this target: the CDP path probes and attaches here (once per run; the
     *  attach is kept for the run and closed in close()). Returns null when the path is usable, else the reason it is
     *  not (recorded; not an error unless the scenario pinned the path). */
    String open(AgentConnection agent, WebSettings settings, RunContext ctx) throws Exception;
    boolean serves(String primitive);
    /** The call's result in the shape the agent would give (value, events[], seq, durationMs, thread="controller"), or
     *  throws WebPathException(UNSUPPORTED) when this call cannot be served (the executor then uses the agent path and
     *  records it) or AgentRpcException with the agent's error codes for the failures of §8.1. */
    RpcResult call(String primitive, Map<String, Object> args, CallOptions options) throws Exception;
    /** Events the path saw since seq (web.ready, web.tap, web.load of the CDP path, which never reach the agent's bus). */
    List<Event> eventsSince(long seq, String topics);
    void close();
}
public record WebSettings(Path path /*AUTO|CDP|AGENT*/, Map<String,Object> defaultBrowser /*the by: object, or null*/,
                          FrozenTargetPolicy frozenTargets /*FAIL*/, Duration attachDeadline /*30 s*/) { public static final WebSettings DEFAULT; public enum Path { AUTO, CDP, AGENT } }
```

Selection per run (step 1a of the run sequence, P1 §8.5, after the capability check and before pre-flight), only
when the plan has at least one `web.*` call:

| `web.path` | the executor |
|---|---|
| `AUTO` (default) | uses the CDP path when a `WebPath` with id `cdp` is on the class path **and** the target's `web.cdp` capability is `OK` **and** `open()` returns null; otherwise the agent path. The choice and its reason are recorded once (`web.path` record) |
| `CDP` | as `AUTO`, but a CDP path that is absent, a `web.cdp` capability that is not `OK`, or an `open()` that fails is `ERROR` / `PRECONDITION_FAILED` naming the reason; no step runs |
| `AGENT` | the agent path, whatever the target offers |

Per call: the chosen path's `call` for the primitives it `serves`; `UNSUPPORTED` falls back to the agent path for that
call and is recorded (`web.fallback` record). Every `web.*` call's `run.jsonl` `call` record carries `path: "cdp" |
"agent"` (§8.2). A scenario can pin a browser for every web verb with `web.browser` (the `by:` object, §7.2); a verb's
own `browser:`/`by:` wins.

### 6.2 The `web` base fact root and the `WEB` snapshot surface

`BaseFacts.ROOTS` gains `web` (sixth root, after `devices`; a pack root named `web` hides it, P1 §8.3 rule):

```
web.browsers[]: {ref, adapter, url, title?, visibleInSwing, component?, rendering, viewport?, cdpTargetId?, tag?,
                 frames[], text?}
```

built from `web.browsers` and, for each browser with `EVALUATE`, one `web.dom.text {maxChars: 4000}` (`text`); the
read is bounded like the other roots (10 s) and omits `text` for a browser whose text read fails (the failure is
logged, the fact still resolves). Through it a scenario asserts `web.browsers[url~pattern].text: {contains: …}` with
the matchers of P1 §8.4 unchanged. The aria snapshot is not a fact (too large for a retried read); it is evidence and
surface.

`UiSnapshotText` renders a `WEB` surface after the Swing windows: one block per visible browser (`[web <ref>]
<title> <url> (<adapter>, <rendering>, <path>)` followed by the aria nodes of `web.ariaSnapshot {maxNodes: 200,
interestingOnly: true}` indented one level per depth, `role "name" [value]` per line), within the existing 4k-token
cap (design §2.5). Hidden browsers appear as one line each (`[web <ref> hidden] <title> <url>`).

### 6.3 Step protocol

Unchanged in shape (design §2.5; P1 §8.5). `web.ready` is already named among the ready topics there; from Phase 4
it is published (§4.3). For the base web verbs:

- `webClick`, `webFill`, `webDispatch` compile to `SETTLE` calls by default (the agent's `swing.settle` still runs,
  because a page action may open a Swing dialog), with the optional `await:` of A32 (an event on `web.ready`,
  `web.tap`, `web.load` or any topic, with the A32 match rules) for pages that signal completion;
- `webWaitFlag` compiles to a `web.waitFlag` call with `sync: NONE` (the primitive itself waits, on the agent or
  on the CDP path);
- on the CDP path, waits on `web.*` topics are served by `WebPath.eventsSince` (the path's own events) **and** the
  agent's bus; the first matching event from either source ends the wait, so a scenario does not know which path ran.

### 6.4 Evidence on failure

Beside the Phase 1 evidence (`snapshot`, `capture`, `events`, `app-state`, `dialogs`), a failed scenario whose target
has at least one indexed browser gets, under `failure/`: `web-browsers.json` (the `web.browsers` value),
`web-<ref>-aria.json` and `web-<ref>.png` (`web.ariaSnapshot {maxNodes: 2000}` and `web.bitmap`) for each visible
browser, at most four, in ref order; a browser whose read fails gets a `web-<ref>-error.txt` with the error code. The
CDP path's `trace.zip` goes to `failure/web-trace.zip` when `web.trace: true` was set (§7.2; off by default, because
tracing costs several seconds per run). `RunOptions.captureOnFailure` governs the PNGs as it does the Swing capture.

---

## 7. Scenario YAML and `plan.json` version 4

### 7.1 Base verbs

| Verb | Arguments | Compiles to |
|---|---|---|
| `webClick` | `selector: <css>` **or** `role: <aria role>` + `name: <text or {regex}>`; `index?`, `browser?: <by object>`, `frame?`, `await?` (A32) | `web.dom.click` (`SETTLE`, or `EVENT` with `await`) |
| `webFill` | as `webClick` plus `text: <string or $ref>`, `clear?: bool`, `enter?: bool` | `web.dom.fill` |
| `webDispatch` | `name: <event>`, `detail?: <any, $refs allowed>`, `target?: <css>`, `browser?`, `frame?`, `await?` | `web.dispatchEvent` |
| `webWaitFlag` | `expression: <js>`, `timeoutMs?`, `browser?`, `frame?` | `web.waitFlag` (`NONE`) |
| `webResize` | `width`, `height` **or** `clear: true`, `browser?` | `web.resize` (`SETTLE`) |
| `webTap` | `patterns: [<event name or glob>]`, `browser?`; usually in `setup` | `web.tap` (`NONE`); `ctx.addCleanup` removes it (`web.tap {patterns: []}`) |

A `text` of `webFill` that comes from a joined credential field carries `_meta.secretParams: ["text"]` (A34) as
`type` does. `role` values are the ARIA role names (`button`, `textbox`, `link`, `checkbox`, `combobox`, `heading`,
`cell`, …); `name` matching follows the library's accessible-name computation, exact by default, `{regex}` for a
pattern, case-insensitive with `{regex, ignoreCase: true}`. The verbs are base verbs in the sense of P1 §8.1: a pack
may override them.

### 7.2 The scenario key `web`

```yaml
web:
  path: auto | cdp | agent          # default auto (§6.1)
  browser: {url: "^app://"}          # default browser for every web verb; a verb's own browser/by wins
  frozenTargets: fail | reload       # default fail (§5.3); "close" is refused with a message in Phase 4
  trace: false                       # CDP tracing into failure/web-trace.zip on failure
```

A scenario that drives web views over CDP lists `web.cdp` under `requires:` (design §3.8); with `path: cdp` the
executor adds it when missing (a compile-time note, not an error). Every scenario with a web verb gets `web.evaluate`
added to `plan.requires` by the compiler (the agent path needs it; the CDP path still needs `web.browsers` for the
mapping, so `web.browsers` is added too).

### 7.3 `plan.json` version 4

New: the `web` object of §7.2 (omitted when absent in the YAML, as `ui` is), the calls of the new verbs. The reader
accepts versions 1 to 4; a version 3 plan reads as version 4 without `web`. The compiler always writes 4 (P2 D18:
one writer, one format). Byte-identical recompilation and the `planHash` rules are unchanged; `resolveHash` is
unchanged (nothing in `web` resolves). Java shapes: `Plan` gains a last component `WebSettings web` (default
`WebSettings.DEFAULT`), the Phase 3 constructors stay (the A29 precedent for `ui`); `Scenario` likewise.

---

## 8. Failure taxonomy, status mapping and run outputs

### 8.1 Error codes and kinds

- New JSON-RPC error code `SCRIPT_ERROR` (`ErrorCodes`, agent and controller): a script the agent ran in a page
  threw; `data {browser, message, stack}`. From a step call it is `FAIL` / `ASSERTION` (the page did not do what the
  scenario expected), as `INPUT_REJECTED` is.
- `NOT_FOUND`, `AMBIGUOUS` from a web verb: `FAIL` / `ASSERTION`, as for Swing (P1 §8.5).
- `STALE_HANDLE` from a web verb (the browser closed during the step): `FAIL` / `ASSERTION` with the message "the
  browser closed during the step".
- `CAPABILITY_UNAVAILABLE` from a web verb: `ERROR` / `ENVIRONMENT`, unchanged rule.
- The CDP path's `CdpException`: `UNREACHABLE`, `TIMEOUT`, `PROTOCOL` are `ERROR` / `ENVIRONMENT`;
  `FROZEN_TARGETS` is `ERROR` / `ENVIRONMENT` with `detail.frozenTargets` (id, url, title each). With
  `web.path: cdp` an unusable path is `ERROR` / `PRECONDITION_FAILED` (§6.1).
- No new `FailureKind` and no new `Status`.

### 8.2 `run.jsonl`

New records: `{type: "web.path", path, reason?, cdp?: {endpoint, browserVersion, connectMs, targets: n, unresponsive:
n}, libHash}` once per run that has web calls; `{type: "web.fallback", step, primitive, reason}` per `UNSUPPORTED`
fall-back; `{type: "web.recover", step?, browser, action: "reload", ok}` per frozen-target recovery. Every `call`
record of a `web.*` primitive carries `path`. The `web.*` events a run waited on are in the `events` records as every
other topic is; `web.console` text passes `SecretMask` like every recorded string (A25/P2 §11.4).

### 8.3 What is never written

Unchanged (P2 §11.4): no secret, by key or by value. The page's `text` in the `web` fact root and in evidence passes
the mask like every other fact; a `webFill` of a credential records `<redacted>`. A JxBrowser licence key is never
read, so it cannot be written.

---

## 9. The samples: the web host, its agent module, pack and scenarios (profile `jcef`)

The lending desk stays free of any embedded browser (design §3.8: "the lending desk stays free of dependencies").
Phase 4 adds a second, deliberately small sample target whose only purpose is to host embedded browsers the way a
desktop application does.

### 9.1 `skillroy-sample-web-host` (`samples/skillroy-sample-web-host`, package `…samples.webhost`, Java 8)

A Swing application ("the web host") that embeds JCEF through jcefmaven:

- `WebHostApp.main`: reads `-Dwebhost.rendering=windowed|osr` (default `windowed`; both modes run in the image on
  Java 25, off-screen only on Java 8, §2.5), `-Dwebhost.debugPort=<n>` (default 0: none; the only way a port exists, design §3.8),
  `-Dwebhost.install=<dir>` (the natives' install directory, default `target/jcef-bundle-<platform>` relative to the
  working directory), `-Dwebhost.cache=<dir>` (Chromium's root cache path, default a fresh temporary directory on the
  local file system). **Before `CefAppBuilder.build()` the host creates the empty files `First Run` and
  `EULA Accepted` in that cache path** (BS8: Chromium 152's first-run terms dialog otherwise blocks `CefInitialize`),
  and records in its log that it did. Windowed mode on Linux needs `CefApp.startup` before the first AWT call, so
  `main` builds the `CefApp` before any Swing class is touched (browser-spi §F4, §F15), then shows one `JFrame` ("Web host") with a `CardLayout` of **two browsers**: card `front` (visible) and card `back` (a cached, hidden browser the Swing-tree scan sees as displayable but not
  showing; the host calls `createImmediately()` on it, because BS8 showed that a browser whose component is not
  showing is otherwise never created), and a `JButton` "Swap" that shows the other card. A third browser,
  `detached`, is created with `createImmediately()` and never added to any window (a browser without a view;
  off-screen mode only, where the native browser exists without a window).
- **Pages** are the sample's own, served from the class path through a CEF scheme handler is **not** used (a handler
  slot the agent must never take would then be the application's; fine, but it adds surface). Instead the pages are
  `data:` URLs built from resources `webhost/front.html`, `webhost/back.html`, `webhost/detached.html` (no network,
  no file system): a title, a heading, a form (`#item` textbox, `#qty` number, `#borrow` button), an `iframe` whose
  `src` is a `data:` URL (never `srcdoc`, which did not load in BS8), a status line the page updates, and a small script that (a) dispatches `window` `CustomEvent`s
  `webhost.ready` after load and `webhost.borrowed {item, qty}` on the button, (b) listens for the `CustomEvent`
  `webhost.setItem {item}` from the host, (c) runs a busy loop for ever when the page's URL fragment is `#freeze`
  (the frozen target of §12.2 item 6), and (d) sets `window.webhostReady = true` 300 ms after load (the flag of
  `webWaitFlag`).
- `WebHostApp` keeps the three browsers in a static registry `WebHostBrowsers` (`front()`, `back()`, `detached()`,
  `cefApp()`), so that the agent module of §9.2 can hand them out as seeds without the agent knowing JCEF.
- The host exposes nothing to the framework but that registry; it has no plugin and no `EMBEDDED` start (the end-to-end
  acceptance runs `JAVAAGENT` and `ATTACH`, §12.2).

### 9.2 `skillroy-sample-web-host-agent` (module id `webhost`, package `…samples.webhost.agent`, Java 8)

An `AgentModule` with one SPI implementation: a `BrowserRegistryAdapter` that returns `BrowserSeed.of(back(),
"back")` and `BrowserSeed.of(detached(), "detached")` (the detached browser reaches the agent through a seed only,
browser-spi §F23 precedent), and a `ReadinessSource` that is ready once the registry holds the front browser. It
registers no primitive. It depends on the host module for the registry class and on nothing else.

### 9.3 `skillroy-sample-web-host-scenarios` (Java 25): pack, scenarios, acceptance

- A `DomainPack` `webhost` (version `0.1.0`) with no verbs of its own (the base web verbs suffice), the sync policy
  `ackTopic: null`, `readyTopics: [web.ready]`, `promptTopics: []`, one fact provider `webhost` over
  `web.browsers` (`front`, `back`, `detached` resolved by tag and by `url~`), no pre-flight, and `recover` that
  dispatches `webhost.reset` to the front page and settles.
- **Scenario W1 `borrow-in-page`**: `webTap {patterns: [webhost.*]}` in `setup`; `webFill #item "hammer"`, `webFill
  #qty "2"`, `webClick role: button name: Borrow await: {topics: web.tap, match: {name: webhost.borrowed}}`; expect
  `web.browsers[tag=front].text: {contains: "borrowed hammer x 2"}` and the tapped event's `detail.qty == 2`
  (captured into a slot, P2 §3.4); `webDispatch webhost.setItem {item: saw}` then `webWaitFlag "document.getElementById('item').value === 'saw'"`.
  It is committed once and runs in both paths: `web.path` is left at `auto`, and the acceptance launches the host
  with and without a debug port (§12.2), so the same plan runs over CDP and over the agent.
- **Scenario W2 `hidden-browser-reachable`**: on the hidden `back` browser (`browser: {tag: back}`): `webResize 1024
  768`, `web.dom.text` through the fact root, `webDispatch`; expects the back page's text, and that
  `web.browsers[tag=back].visibleInSwing == false`. Proves the in-process path on a browser CDP sees but Swing does
  not show, and `web.resize` on a never-shown viewport (browser §F11, §C6).
- **Negative scenarios** (test-time, not committed under `scenarios/`, P3 D46 precedent): a `webClick` on a role
  nothing matches is `FAIL` / `ASSERTION` with `NOT_FOUND`; a `webClick` whose `name` matches two buttons is
  `AMBIGUOUS`; an expression that throws is `SCRIPT_ERROR`; `web.path: cdp` against a host without a debug port is
  `PRECONDITION_FAILED`.

---

## 10. The adapter contract kit and the neutral fake

### 10.1 The kit (`skillroy-agent-web` `test-jar`, package `…agent.web.kit`)

`WebEngineAdapterContract` is an abstract JUnit 5 class (Java 8) with one abstract hook,
`WebAdapterFixture fixture()`, which a technology's test module implements: it supplies the `WebEngineAdapter`, the
seeds for a visible, a hidden and (optionally) a view-less browser, a way to load a given page into a browser
(`load(browser, html)`), the EDT (or the thread that must never be blocked), and the expected `WebRendering` per
browser. The kit's pages are its own resources (same shapes as the web host's). Its tests, one per rule and
operation (browser-spi "Testing in public CI" item 1):

1. `probe` is `OK` and loads no class the host had not loaded (checked with a recording class loader);
2. enumeration from seeds finds the visible, the hidden and the view-less browser; the same browser twice gives
   `equals()` handles; an unrelated seed (a `JButton`, a `String`) is ignored; `enumerationState()` as the fixture
   predicts;
3. `evaluate`: a number, a string, an object, `undefined` (as `"null"`), a thrown error (`WebScriptException` with
   the message), a promise with `awaitPromise` (or `AWAIT_PROMISE` absent), a 1 MiB string (truncation marker), 100
   evaluations in a row within a bound; nothing blocks the EDT (an EDT watchdog fails the test if a marker posted to
   the EDT every 50 ms is late by more than 500 ms during any operation);
4. frames: the main frame first, the `srcdoc` iframe present with the parent key, `evaluate` in the iframe answers
   its own `document.title`, a key of a frame that navigated away raises `WebFrameGoneException`;
5. events: `LOAD_FINISHED` after `load`, `NAVIGATED`, `CONSOLE` for `console.log`, `CLOSED` when the fixture closes
   a browser, no event after `cancel()`;
6. init script: a script added before `load` is visible to the page's inline script when `timing()` is
   `DOCUMENT_START`, and after the load event otherwise; cancelled scripts do not run in the next document;
7. `resize` changes `innerWidth`/`innerHeight` as read back; `clearResize` restores them; `viewport()` follows;
8. `bitmap` of the visible and of the hidden browser decodes, has the page's background colour in its corner and
   more than one colour;
9. `cdpTargetId` is non-null when the fixture configured a port and the id appears in `/json/list` (skipped when the
   fixture has no port);
10. after `isClosed()` every operation completes exceptionally with a clear message within the bound; `detach()`
    removes the kit's listeners and init scripts (the next load shows no init-script marker) and leaves the
    application's own listener (the fixture installs one) in place;
11. no enumerable global is left on `window` after any operation (`Object.keys(window)` before and after are equal).

### 10.2 The neutral fake (`skillroy-agent-web` tests, package `…agent.web.fake`)

The `jxbrowser` adapter cannot be run against the product in this repository (browser-spi §F28). Its reflection
plumbing is run against `FakeEngine`, `FakeBrowser`, `FakeFrame`, `FakeView` (a `JComponent`), `FakeNavigation`,
`FakeOptions`: classes under the project's own package with the same member shapes as the F21 table (`browsers()`,
`url()`, `mainFrame()` returning `Optional`, `executeJavaScript(String, Consumer)`, `on(Class, Observer)` with a
fake `Observer` interface, `bitmap()`, `resize`, `devTools().remoteDebuggingUrl()`, `options().remoteDebuggingPort()`
and a `licenseKey()` that fails the test if called). The fake evaluates a small fixed set of expressions (the ones the
library and the kit use) by table, not a JavaScript engine. The adapter is driven by the descriptor `fake-jxbrowser.json`,
identical to `jxbrowser.json` except for the class names. The kit runs against the fake (every item except 8's
colour check and 9), and three more tests cover: a descriptor role missing in the fake (the capability is absent, the
primitive answers `CAPABILITY_UNAVAILABLE`), two fake class loaders (the cache is per loader), and `set(...)` never
called without the opt-in. This proves the mechanics, **not** compatibility with JxBrowser, and says so in the test
names and in the capability matrix's strategy ("jxbrowser (fake)").

### 10.3 Running the kit against JCEF

`skillroy-sample-web-host`'s test scope (profile `jcef`; display tests skip when headless, `DisplayTestBase`
pattern) runs the kit in process against the real JCEF browsers of the web host with the `JcefAdapter`, in both
rendering modes on the image's Java 25 and off-screen on a Java 8 test JVM (§2.5). A licence holder runs
the same kit against JxBrowser in a private repository by implementing `WebAdapterFixture` over a real engine
(browser-spi §R6); the `TODO.md` wall "A JxBrowser licence" records that this verification happens outside this repo.

---

## 11. Build, image and licences

- **Profile `jcef`** in the root POM lists the three sample modules. Property `skillroy.jcef.platform` (default
  `linux-amd64`) selects the natives artifact `me.friwi:jcef-natives-<platform>:<tag>` in test scope; a developer on
  another platform sets it (`macosx-arm64`, …) or runs through `ci/run-in-linux.sh`. Nothing downloads at test time
  beyond Maven's own resolution (`-o` works once the cache holds the natives; Risk5 of browser-spi).
- **The image** (`ci/Dockerfile`) gains Chromium's run-time libraries: exactly the fifteen packages `ldd` reported
  missing for `libcef.so`, `libjcef.so` and `jcef_helper` on the base image (`libnss3 libnspr4 libatk1.0-0t64
  libatk-bridge2.0-0t64 libatspi2.0-0t64 libcups2t64 libdbus-1-3 libglib2.0-0t64 libcairo2 libpango-1.0-0
  libasound2t64 libxcomposite1 libxdamage1 libxfixes3 libxkbcommon0`); OpenGL for off-screen rendering (`libgl1`,
  `libgl1-mesa-dri`, `libxxf86vm1`) was already present as a dependency of `xvfb`. Built and used by BS8 on 2026-10-06
  (§2.5). `../licence-audit.md` §7 lists the new packages.
- **New third-party artifacts** and their audit (`../licence-audit.md` §8 moves them to the audited tables when
  built): Playwright Java 1.63.0 (Apache-2.0; redistributed only if `skillroy-cdp` ever enters a distribution, which
  Phase 4 does not do: D49); jcefmaven 152.0.6 (Apache-2.0), `jcef-api` (BSD-3-Clause), the natives (BSD plus
  Chromium's notices), JOGL and GlueGen 2.4.0 (BSD): sample and test only, never in a distribution.
- **Agent home**: `modules/skillroy-agent-web.jar` joins the two module jars; `DistributionContentIT` expects seven
  jars and checks, as for the others, that it holds only `ai/skillroy/` and `META-INF/` entries (the script library
  is in `skillroy-protocol`, under `ai/skillroy/`).
- `--release 8` holds for `skillroy-agent-web`, the kit and the two Java 8 samples; the agent-side Java 8 test JVM leg
  of the matrix gains `skillroy-agent-web` (its unit tests and the fake) and, under the profile, the web host and
  its agent module.

---

## 12. Acceptance

### 12.1 Without a display (unit and module tests; the normal build)

1. `skillroy-agent-api`: the value types (`WebEval` builder validation, `BrowserSeed`, `WebFrame` equality).
2. `skillroy-protocol`: `WebLib.source()` is the resource, `hash()` is stable across runs, the resource carries the
   licence header; `ErrorCodes.SCRIPT_ERROR` round-trips through the codec.
3. `skillroy-agent-web` (also on a Java 8 test JVM): the module installs headless with every capability
   `UNAVAILABLE` and the right reasons, touches no AWT or vendor class at install (recording loader), re-probes on
   reprobe; the browser index over a scripted adapter (seeds, Swing seeds through a fake walk, stable refs,
   `STALE_HANDLE` after removal, `web.browsers.changed`); every primitive of §4.2 against a scripted
   `WebEngineAdapter` (selection rules, `NOT_FOUND`/`AMBIGUOUS` data, default browser, frame keys, the JSON wrapper
   with every value kind of §4.2, `SCRIPT_ERROR` data, `TIMEOUT` abandons the future, read-only mode rejects the
   mutating ones and serves the reads of D51, A34 redaction of `web.console` and of a `webFill` text named in
   `secretParams`); the event bridge (topics and payloads of §4.3, the tap poller, `web.ready` for the three signals);
   the descriptor plumbing and the `jxbrowser` adapter against the fake (§10.2), the kit against the fake; the `jcef`
   adapter's descriptor against a fake `CefApp`/`CefClient`/`CefBrowser` shape for the enumeration walk and the
   state rule (never `getInstance`).
4. `skillroy-scenario`: the verbs of §7.1 compile to the calls of §4.2 with the stated sync levels and `requires`
   additions; the `web` key's grammar and defaults; plan version 4 written, 1-4 read; byte-identical recompilation;
   `WebPath` selection for every `path` value against a fake agent and a fake `cdp` path (present/absent, `web.cdp`
   OK/not, `open()` failing), the `web.path` record, `UNSUPPORTED` fall-back with its record, `path` on every web call
   record; the `web` fact root (built from `web.browsers` and `web.dom.text`, bounded, a failing text read omits the
   field, a pack root `web` wins); the `WEB` surface rendering within the cap; the mapping of §8.1 for every error
   code; evidence files of §6.4 written on failure, none on success; `webFill` from a credential carries
   `secretParams`.
5. `skillroy-cdp` (no browser): `TargetProbe` against a fake `/json` server with a target whose WebSocket never
   answers (reported unresponsive within the per-target deadline, the others probed in parallel), `CdpException`
   kinds, `CdpOptions` validation; `CdpWebPath.serves`, the shape adapters (a Playwright `Page` is not needed for
   them), the frozen-target policy parsing (`close` refused).
6. `skillroy-cli`, `skillroy-junit`: `caps` lists the `web.*` capabilities; `run --json` carries `path` per call and
   the `web.path` record; the JUnit glue's degraded message is unchanged (nothing new degrades).
7. `skillroy-sample-web-host-scenarios` (headless): the pack compiles W1 and W2 to the committed plans
   (`PlansUpToDateTest` pattern); the committed plans are version 4.

### 12.2 End to end (needs a display and the profile: `ci/run-in-linux.sh … -Pjcef`)

`WebHostEndToEndIT` launches the web host as a child process on the Java named by `-Dskillroy.it.targetJava`
(a Java 8 target and the image's Java 25, as the lending acceptance), with the agent in **`JAVAAGENT`** and
**`ATTACH`** (the host has no embedded start), in both rendering modes on the Java 25 target and off-screen only on
the Java 8 target (§2.5), **twice per mode: once with `-Dwebhost.debugPort=0` (no port) and once with a port** (a free port the test picks and passes, because CEF
cannot pick one for the test to read back without a cache directory, browser-spi §F13):

1. **Capabilities.** `web.adapter.jcef` is `OK` with strategy `jcef 152`; `web.adapter.jxbrowser` is `UNAVAILABLE`
   ("not present"); `web.browsers`, `web.evaluate`, `web.events`, `web.initScript` (`DOCUMENT_START`), `web.resize`,
   `web.bitmap` are `OK`; `web.cdp` is `OK` with the port and `UNAVAILABLE` without; `web.hittest` and `web.pointer`
   are `UNAVAILABLE` (reserved).
2. **Enumeration.** `web.browsers` lists three browsers: `front` (visibleInSwing true, component ref of the card's
   component), `back` (false; found by the Swing-tree scan **and** as a seed, one ref), `detached` (false, no
   component; seed only; off-screen mode) with their URLs, titles, frames (the `srcdoc` iframe), `rendering` as §4.5
   says, and `cdpTargetId` for every browser when the port is set (equal to the ids in `/json/list`, read by the
   test) and absent otherwise.
3. **Scenario W1 passes without sleeping** in the launch without the port (path `agent`, recorded) and in the launch
   with the port (path `cdp`, recorded; the `web.path` record names the endpoint and the connect time): no call of the
   executor's sleeper; every wait ended by an event; `run.jsonl` has `path` on every web call; the tapped
   `webhost.borrowed` event was the one that ended the click's wait; the captured `qty` is `2`.
4. **Scenario W2 passes** in both launches (the `back` browser is driven in process in both: `web.resize` and the
   hidden-page text; with the port, `cdp` serves the `web.dom.text` through Playwright on the hidden page's target).
5. **Negative scenarios** (§9.3) end as stated; evidence of §6.4 is written for the failed ones: the aria JSON
   decodes and names the `Borrow` button, the PNG decodes with at least 8 colours; `web.path: cdp` without a port is
   `PRECONDITION_FAILED` naming `web.cdp`.
6. **Frozen target (the BS5 criterion, D53).** With the port: the test navigates the `back` browser to its
   `#freeze` fragment through `web.evaluate` (`location.hash = '#freeze'`), waits until the probe reports it
   unresponsive, then runs `CdpAttach.connect` **50 times** with the default policy: every attempt ends within
   `connectDeadline` with `FROZEN_TARGETS` naming the back target and never hangs (measured: the slowest attempt is
   recorded); then once with `frozenTargets: reload`: `web.reload` runs, the second probe is clean, the attach
   succeeds and W1 passes. Then the test closes the frozen page's loop through `web.reload` so that the next checks
   run. (Playwright's connect itself against a frozen target is exercised once more with the probe disabled, under
   the external deadline, to show that the deadline, not the probe, is what prevents the hang: it must end with
   `TIMEOUT` and the driver gone.)
7. **Secrets.** `webFill` of a value named in `secretParams` is `<redacted>` in `run.jsonl`, in `agent.log` and in
   `web.console` payloads that echo it (the page logs the filled value on purpose), and appears in no evidence file.
8. **Second attach.** After `agent.disable`, a fresh attach finds the same three browsers with new refs; no
   listener of the old agent fires (the page counts `webhost.*` listeners through a host-side hook and the count is
   back to the application's own).
9. **The contract kit against JCEF** (§10.3) is green in process, in the web host's own test JVM, under the profile
   (not part of the child-process matrix, but of the same Linux run).

Once per target Java, through the assembled CLI launcher with `--pack` naming the `skillroy-cdp` jar and the web host
pack jar: `skillroy run --mode replay` of W1 against the launch with the port exits 0 and its `--json` shows
`path: cdp`; without the `skillroy-cdp` jar the same command exits 0 with `path: agent`.

**Exit criteria of Phase 4** (the roadmap's, made precise): §12.1 green in the normal build and on the Java 8 test
JVM; §12.2 green on a Java 8 target and a Java 25 target, in `JAVAAGENT` and `ATTACH`, with and without a port, twice
in a row without a flake; over 50 attach attempts against a frozen target every one ends within the external deadline
(item 6); W1 passes over CDP and over the in-process path from one committed plan (item 3); the kit passes against a
real JCEF browser in the image (item 9) and against the neutral fake (§12.1 item 3); `INPUT` and `RAW_PROTOCOL`
stay reserved. **BS5 is passed by item 6** against JCEF's Chromium (D53); a run against a stock Chromium is
documented in `spikes/BS5/` as a manual procedure, not a CI gate.

---

## 13. Open SPI and DSL gaps: what Phase 4 takes

Numbers are those of `../TODO.md`, "Open SPI and DSL gaps".

| # | Gap | In Phase 4 |
|---|---|---|
| 21 | Event expectations (`sync.await` matches top-level values by text only) | partly: `await:` on the web verbs matches `name` and top-level `detail` fields by text (A32 rules); the `events` fact root and negated waits stay open |
| 22 | Rung ladders for pack verbs | unchanged; the web verbs record `path` and `fidelity: DOM`, which is the same need expressed per verb; a general ladder stays open |
| 28 | Pack-defined artifact kinds | unchanged; the web evidence is base evidence, not a pack artifact |
| 31 | `swing.events` and other catalogued primitives not yet in a contract | unchanged |

New, recorded for the gaps table: **a `PAINT` capture must guard embedded-browser components** (BS8: `printAll` throws
for a JOGL canvas); **a browser with no view and no seed is invisible** (JxBrowser; browser-spi §F23);
**`web.dom.click` is a DOM click, not a pointer click** (`INPUT`, Phase 7); **`web.resize` cannot change a windowed
JCEF browser's Swing bounds** (`applied: COMPONENT` is reserved).

---

## 14. Decisions taken in this contract

| # | Decision | Alternatives rejected | Reason |
|---|---|---|---|
| D48 | The script library is one resource in `skillroy-protocol` (`WebLib`), used by both paths | a copy per path; the agent module as the only owner with `skillroy-cdp` reading its jar | both implementations of a verb must return identical shapes (browser-spi open question 4); `skillroy-protocol` is the module both sides already share, and a Java 25 module must not depend on an agent module jar |
| D49 | `skillroy-cdp` is an optional controller module found through `ServiceLoader<WebPath>`; `skillroy-scenario`, `skillroy-junit` and `skillroy-cli` do not depend on it, and no distribution ships Playwright | Playwright as a dependency of `skillroy-scenario`; a `--cdp` CLI flag that loads it from a path | 194 MB of driver for every user of the executor, most of whom have no web view; the `--pack <jar>` mechanism already exists; the agent path works everywhere |
| D50 | The web host sample and its two companions live in `samples/` behind the Maven profile `jcef`, with the natives as a test-scope Maven dependency selected by `skillroy.jcef.platform` | a download at test time (jcefmaven's default); a separate repository | offline builds (`-o`) and a cached CI repository; a contributor without the 152 MB bundle builds without the profile |
| D51 | `web.ariaSnapshot`, `web.dom.text`, `web.waitFlag`, `web.bitmap`, `web.browsers` and `web.cdp.status` are not mutating; `web.evaluate`, `web.dom.click`, `web.dom.fill`, `web.dispatchEvent`, `web.tap`, `web.resize` and `web.reload` are | every verb that runs script is mutating (browser-spi §C4 read literally) | the reads run the library's own side-effect-free functions, not caller script, and read-only mode must still be able to observe a page; the one primitive that runs caller script (`web.evaluate`) stays mutating, and `web.waitFlag`'s expression is the scenario's own predicate, evaluated and discarded |
| D52 | Frozen-target recovery is `fail` by default and `reload` by opt-in; `close` is refused in Phase 4 | recover by default; `close` as an option | a reload loses page state the application did not ask to lose; closing a browser the application owns can break it; the scenario author decides |
| D53 | The BS5 criterion is met against JCEF's Chromium in the image (the web host with a debug port and a `#freeze` page); a stock Chromium run is a documented manual procedure | a Playwright Chromium download (about 170 MB) in the image; Ubuntu's Chromium package | the image already holds a real Chromium 152 for BS8; the criterion is about the attach mechanics, which do not depend on who launched the browser; one less download |
| D54 | The two built-in adapters take their class and member names from descriptors (`jcef.json`, `jxbrowser.json`) and the neutral fake is a third descriptor | member names hard-coded in the adapters; stub classes in a vendor's package namespace | a fork or a new line is served by a descriptor change; the plumbing is proven in the open without a class in a vendor's namespace (browser-spi §F28, §R3) |
| D55 | `WebBrowser.cdpTargetId()` is a future and `web.browsers` reads it with a 2 s bound per browser; on JCEF it is the main frame's id from `Page.getFrameTree` | a synchronous accessor; `Target.getTargetInfo` | JCEF answers it with a protocol call, and BS8 showed the `Target` domain is not served by the page-level client while the main frame id equals the `/json/list` id; a synchronous accessor would have to block on the EDT or guess |
| D56 | One frame model for both technologies: snapshot values with adapter-minted keys; JCEF keys are the protocol's frame ids, JxBrowser keys are minted per `Frame` identity | expose each technology's own ids | the two id spaces have nothing in common (browser-spi §F7; BS8 §2.5); the primitives need "which frame" only for the length of a call |
| D57 | `web.ready` is one topic with a `signal` field (`LOAD`, `TAP`, `FLAG`), published by the agent module | three topics | the step protocol already names `web.ready` as a ready topic (design §2.5); a pack's readiness list stays one entry |
| D58 | Plan version 4; the reader accepts 1-4 | keep 3 and tolerate `web` | P2 D18 and P3 D41: one writer, and an older executor would run a plan with web verbs it cannot serve |

### 14.1 Changes to the Phase 1, 2 and 3 contracts that Phase 4 needs

To be written into the earlier contracts **before the code**, each passage marked "(Phase 4, specified 2026-10-06,
not yet built)" and "(Phase 4, built …)" after the build:

| Contract and section | Change |
|---|---|
| P1 §2.1 agent home | `modules/skillroy-agent-web.jar`; seven jars in `DistributionContentIT` |
| P1 §3 (`ErrorCodes`) | `SCRIPT_ERROR` |
| P1 §4 SPI table (design §2.6 as well) | `WebEngineAdapter`, `BrowserRegistryAdapter` as §3 states them; the design's vendor-typed sketch is superseded |
| P1 §5 topics | the `web.*` topics of §4.3 |
| P1 §6 `swing.capture` | `PAINT` guards embedded-browser components: an exception from a component's `printAll` is caught and a placeholder rectangle painted, the component listed in the result's `skipped` (BS8: a JOGL canvas throws) |
| P1 §8.1 scenario YAML | the base verbs of §7.1 and the key `web` |
| P1 §8.3 base fact roots | `web` |
| P1 §8.5 run sequence, status mapping | step 1a (path selection), the mapping of §8.1, `path` on call records |
| P1 §10 | unchanged; the web acceptance is this file's §12.2 |
| P2 §5 version rules, `Plan.VERSION` | 4 |
| P2 §11.3 records | `web.path`, `web.fallback`, `web.recover`, `path` |
| P3 §4 | plan version 4 supersedes 3 as the written version |
| design §2.4 catalogue | `web.waitFlag`, `web.reload`, `web.frames` folded into `web.browsers`; the signatures of §4.2 |
| design §3.1 | `skillroy-cdp` as an optional module (D49) |

---

## 15. Questions for the owner: answered provisionally 2026-10-06

Answered by the orchestrator on 2026-10-06 in the manner of P2 §17 and P3 §14, so that the build can start; written
into the sections named; **not yet confirmed by the owner** (they are on the owner interview page; an id not mentioned
in the owner's reply keeps the answer recorded here). A veto that removes built behaviour is stated with each one.

| # | Question | Provisional answer | Written into | A veto means |
|---|---|---|---|---|
| Q1 | **Where does the script library live?** Both paths must return identical shapes. In `skillroy-protocol` (shared by both sides), or in the agent module only, with `skillroy-cdp` reading the resource out of the agent jar? | `skillroy-protocol`, as `WebLib` (D48) | §2.1, §4.6 | the library moves into `skillroy-agent-web` and `skillroy-cdp` gets a copy that a test keeps byte-identical |
| Q2 | **Is `skillroy-cdp` optional?** Playwright's driver bundle is about 194 MB. Make `skillroy-cdp` an optional module found by `ServiceLoader`, so that the CLI distribution and the JUnit glue stay as they are, or a hard dependency of the executor so that the CDP path is always there? | Optional (D49); the CLI distribution ships without Playwright | §2.1, §6.1, §11 | `skillroy-scenario` depends on `skillroy-cdp`; the CLI zip grows by the driver bundle |
| Q3 | **A second sample target in `samples/`** (the web host, three modules) behind the profile `jcef` with a 152 MB natives download for whoever activates it, or a separate repository for it? | In `samples/` behind the profile (D50) | §9, §11 | the three modules move to a repository of their own and the public exit of Phase 4 rests on the kit against the fake only |
| Q4 | **Frames in the first cut.** Enumerate frames and let `evaluate` and the DOM verbs take a `frame` key now, or main frame only with the key reserved? | Now, with one frame model (D56): `web.browsers` lists frames, every verb takes `frame?` | §3, §4.2, §7.1 | `frames` is empty and `frame?` is refused with "Phase 4 serves the main frame only" |
| Q5 | **Which `web.*` primitives are mutating?** Every primitive that runs script (browser-spi §C4 literally), or only the ones that run the caller's script or change the page? | The reads are not mutating (D51): `ariaSnapshot`, `dom.text`, `waitFlag`, `bitmap`, `browsers`, `cdp.status`; the rest are | §4.2, D51 | the six reads become mutating; read-only mode cannot observe a page |
| Q6 | **Frozen-target recovery.** May the CDP attach reload a page target that does not answer the liveness probe? Never, by scenario opt-in, or by default? | By scenario opt-in only, `web: {frozenTargets: reload}`; the default fails and names the target; `close` is refused (D52) | §5.3, §7.2 | "never" removes `web.reload` and the option; "by default" makes `reload` the default |
| Q7 | **BS5's target.** The roadmap says "a stock Chromium". Meet the criterion against JCEF's Chromium in the image (the web host with a debug port and a `#freeze` page), or download Playwright's Chromium (about 170 MB) into the image for it? | JCEF's Chromium (D53); a stock-Chromium run is a documented manual procedure | §12.2 item 6, D53 | the image gains Playwright's Chromium and `CdpAttachSoakIT` runs against it as well |
| Q8 | **Path policy.** Is `auto` (CDP when the port exists and answers, else the agent) the right default, with `cdp` and `agent` as pins, and is a pinned path that is unusable `PRECONDITION_FAILED`? | Yes to all three | §6.1, §7.2 | the alternative is `agent` by default and `cdp` by opt-in, which hides CDP from a suite that did not ask |
| Q9 | **Plan version 4** for the web verbs and the `web` key, or keep 3 and tolerate the keys? | 4 (D58) | §7.3 | keep 3 |
| Q10 | **Web evidence on failure**: aria snapshot and bitmap of every visible browser (at most four), always, or only when a web verb was in the plan? | Always when the target has an indexed browser: a Swing failure may have a web cause | §6.4 | only for plans with web verbs |
| Q11 | **JxBrowser lines.** One adapter for 7, 8 and 9 (7 is end of life, the only line on a Java 8 target), or 8 and 9 only? | All three, as B9 says; the descriptor keeps them apart | §4.5 | 7 is dropped from the descriptor |
| Q12 | **Rendering modes in the public image.** BS8 cleared both modes on Java 25 and off-screen on Java 8 (§2.5). Run the acceptance in every mode that works, or in windowed mode only to halve the matrix? | Every mode BS8 cleared: both on Java 25, off-screen on Java 8 | §12.2 | windowed only |
| Q13 | **`web.cdp.status` from private state.** May the `jcef` adapter read `CefApp.settings_` (private) and the `DevToolsActivePort` file to report the configured port? | Yes: a read of a fact, with `UNKNOWN` when refused; nothing is set | §4.2, §4.5 | the port is reported only when a pack supplies it in the options (`web.cdp.port`) |
| Q14 | **The web host's pages as `data:` URLs** (no network, no file, no scheme handler), accepting that their origin is opaque (no cookies, no `localStorage`)? | Yes; a later sample may add a loopback HTTP server if a scenario needs an origin | §9.1 | a tiny loopback server in the host |
| Q15 | **The image's new packages** (fifteen Chromium run-time libraries, §11) and the 152 MB natives in the Maven cache: acceptable CI weight for this repository? | Yes | §11 | the web acceptance leaves the public image and runs where someone installs the libraries |

---
