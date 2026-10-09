# Phase 4 contracts: embedded web views, CDP and the in-process web SPI

**Status:** as built. Specified 2026-10-06; its fifteen questions (§15, decision B21) were confirmed by the owner on
2026-10-07; built 2026-10-07 by seven implementers in isolated copies (the SPI; the script library; the controller
side; `skillroy-cdp`; the web host and its agent module; `skillroy-agent-web` with the kit, the distribution and the
`PAINT` guard; the web host's pack, scenarios and end-to-end acceptance) and integrated in the main tree; the tree
before the last of them was reviewed the same day through three lenses (contract fit; host safety and secrets; tests
and determinism) with adversarial verification, and corrected by a fix wave of three implementers (the agent's web
module, `skillroy-cdp`, the executor). The passages the build or the review changed carry "(as built 2026-10-07: …)"
or a short "As built:" sentence; the decisions taken during the build and the review are D59-D69 (§14). What was run, on
which JDKs and with what result is in `../TODO.md`. Spike BS8 (JCEF in the Linux test image) was run on 2026-10-06 and
passed with conditions; its results are in `../../spikes/BS8/RESULT.md` and summarised in §2.5, and every statement here
that rests on them says so. Where this file and the code disagree, one of the two has a defect; change the contract
first, then the code. **Scope:** Phase 4 of `base-framework-design.md` ("Roadmap": embedded web views, CDP and the
in-process SPI; decision B9). This file is the single source of truth for Phase 4 in the way `phase2-contracts.md` and
`phase3-contracts.md` are for their phases: where it and the design overview (§3.8, §2.4, §2.6) or the research notes
disagree on a Phase 4 detail, this file wins.
The Phase 4.x amendments A35-A38 (2026-10-08; gaps 38, 45, 40 and 42) are §16, and their passages are marked in place
"(4.x, A3n, built 2026-10-08)".
Passages that Phase 5 changes (`phase5-contracts.md` §16.1) were written in before its code and marked
"(Phase 5, built 2026-10-08; P5 §n)"; the build of 2026-10-08 marked each of them "(Phase 5, built 2026-10-08; P5 §n)"; its answers B23 and its
decisions B24 were confirmed by the owner on 2026-10-08.

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
§11 build, image and licences; §12 acceptance; §13 open gaps taken; §14 decisions (D59-D69 taken during the build),
with the changes to the earlier contracts in §14.1; §15 the questions for the owner, answered provisionally and
confirmed by the owner on 2026-10-07.

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
  (it implements the `WebPath` SPI of §6.1 and uses its `FrozenTargetPolicy`), `skillroy-controller`,
  `skillroy-protocol` and Playwright Java. It is **not** a dependency of `skillroy-scenario`, `skillroy-junit` or
  `skillroy-cli`: a suite that wants the CDP path puts the `skillroy-cdp` jar on its class path (Maven dependency, or
  `--pack <jar>` for the CLI, which already accepts jars without a `DomainPack`, P2 §7.2) and the executor finds the
  path with `ServiceLoader<WebPath>` (D49). Reason: Playwright's driver bundle is about 194 MB (browser §F17); the CLI
  distribution and the JUnit glue stay as they are, and the agent-only path works everywhere. (As built 2026-10-07: a
  Maven dependency brings Playwright with it, while the CLI needs every jar of the set with `--pack`:
  `skillroy-cdp-<version>.jar`, `playwright-1.63.0.jar`, `driver-1.63.0.jar`, `driver-bundle-1.63.0.jar`,
  `gson-2.14.0.jar`, `jspecify-1.0.1.jar` and `opentest4j-1.3.0.jar`; the executor looks the path up through the class
  loader of the run's packs, §6.1.)
- **`skillroy-scenario`** gains the `WebPath` SPI (package `…scenario.web`: `WebPath`, `WebSettings`,
  `WebPathException`, `WebPathOpenException`, `FrozenTargetPolicy` and `WebRunContext`, §6.1), the built-in
  `AgentWebPath` (same package), the base web verbs, the `web` fact root and the `WEB` snapshot surface. No new
  third-party dependency.
- **The samples** (`samples/skillroy-sample-web-host`, `…-web-host-agent`, `…-web-host-scenarios`) are listed in the
  root POM inside the profile `jcef`, activated with `-Pjcef`; a build without the profile does not see them (D50).
  The host and its agent module are `--release 8`; the scenarios module is `--release 25`. The host depends on
  `me.friwi:jcefmaven:152.0.6` (compile) and on the platform natives artifact in **test** scope of the scenarios
  module and the host's own tests, chosen by a Maven property (`skillroy.jcef.platform`, default `linux-amd64`;
  §11). Nothing downloads at test time: jcefmaven finds the natives on the class path and extracts them once into
  `target/jcef-bundle-<platform>` (BS8 measured the extraction, §2.5). (As built 2026-10-07: that is the host's
  default install directory, `-Dwebhost.install`; the host builds CEF with an empty mirror list, so a missing natives
  artifact is an error that names it, never a download. The host's agent module has `skillroy-agent-api` and the host
  at `provided` scope.)

Dependency graph after Phase 4 (arrows = "depends on"; new edges in **bold**):

```
protocol   (gains …protocol.web: WebLib)
agent-api  (gains …agent.api.spi.web)
**agent-web -> agent-api, protocol**              **cdp -> scenario, controller, protocol, playwright**
scenario -> controller, data                      junit -> scenario          cli -> scenario, data, controller, protocol
agent-dist (gains agent-web in modules/)
**web-host -> jcefmaven (+ natives, test)**       **web-host-agent -> agent-api, web-host (both provided)**
**web-host-scenarios -> scenario, junit, cdp, dist, web-host, web-host-agent (test), agent-web test-jar (test)**
```

As built (2026-10-07), the samples' edges differ from the last two lines of the graph. The scenarios module depends on
`skillroy-scenario` (compile) and, in test scope, on `skillroy-cdp` (declared before the host, so that Playwright's
`gson` 2.14.0 wins over jcefmaven's 2.11.0), `skillroy-data`, `skillroy-agent-dist` (the zip), the web host with the
platform's natives (copied into `target/it-target`, the class path of the launched host; the tests themselves use only
the host's option, page and argument constants), the host's agent module (copied into the agent home's `modules/`),
`skillroy-cli` (declared so that the reactor builds the assembled launcher first) and JUnit 5; the zip, the agent module
and the CLI come with every transitive dependency excluded. It depends neither on `skillroy-junit` nor on the kit's
`test-jar`. The web host holds the kit instead: test dependencies on `skillroy-agent-web` and its `test-jar` (the `jcef`
adapter and the kit that is run against it, §10.3), beside the natives and JUnit 5; its compile scope holds jcefmaven
only.

No cycle. `skillroy-agent-dist` adds `modules/skillroy-agent-web.jar` to the agent home (P1 §2.1: the home's jar list
and `DistributionContentIT` grow by one). (As built 2026-10-07: `DistributionContentIT` expects seven jars,
`AgentHomeIT` checks the new one, and the lending acceptance's load-mode checks expect the module `web` in every
agent.)

### 2.2 Versions

- `Plan.VERSION` becomes **4** (§7.3). `BindingsFile.VERSION` stays 1 (nothing in a bindings file changes).
- Wire protocol stays **0.2**: Phase 4 adds primitives, topics and capabilities, which are catalogued, not
  protocol changes (P1 §3). (As built 2026-10-07: the topic names are constants of the protocol's `Topics`,
  `WEB_LOAD`, `WEB_READY`, `WEB_CONSOLE`, `WEB_TAP`, `WEB_CLOSED` and `WEB_BROWSERS_CHANGED`, and the new error code is
  `ErrorCodes.SCRIPT_ERROR`, -32008.)
- New module version strings: `skillroy-agent-web` `0.1.0` (the agent jar's Maven version through the filtered
  properties file, as the Swing module does; as built `web-module.properties`); the web host pack `0.1.0`. (As built:
  the web host's agent module `webhost` is `0.1.0` too.)

### 2.3 Build conventions

As P1 §1 and P2 §2.3. The licence header check covers the new `.java`, `.xml`, `.sh` and `Dockerfile` files; the
script library `web-lib.js` carries the header as a `/* … */` comment and `ci/license-headers.sh` learns the `.js`
kind (comment style `java`). New third-party artifacts are audited in `../licence-audit.md` before they enter a
distribution (§11). (As built 2026-10-07: the descriptors `jcef.json`, `jxbrowser.json` and the fakes' carry no
header, because their strict parser accepts no comments, and neither do the web host's HTML pages, which become
`data:` URLs that a header would only enlarge; the script does not check `.json` or `.html`. The kit's `test-jar`
carries `META-INF/LICENSE` and `NOTICE`.)

**Partial builds** (as built 2026-10-07). This project is never installed into the local Maven repository
(`mvn install` is not used): a module build runs with `-am`, and a module whose tests need the agent home declares
`skillroy-agent-dist` (type `zip`, test scope, every transitive dependency excluded) as a test dependency, so that
`-pl <module> -am` builds the distribution in the same reactor. A partial build of the CLI without that declaration
took the zip from a stale snapshot in the local repository and unpacked jars older than the HMAC handshake into the
test agent home (`AttachIT` then failed with a 401 on `/ping`); `controller/skillroy-cli/pom.xml` declares the zip
as the lending scenarios POM does, and the stale snapshots were deleted.

### 2.4 Threads

The agent module follows the thread rules of P1 §6 and browser-spi §C1: adapter calls run on agent workers
(`ThreadPolicy.WORKER`) with the bounded wait applied by the primitive to the adapter's future; nothing waits on the
EDT for a browser result; Swing reads (component bounds, `isShowing`) are `EDT_SYNC` reads bounded as in the Swing
module; no blocking vendor call is made on the EDT. The JCEF adapter must respect that CEF's UI thread *is* the EDT
with off-screen rendering (browser-spi §F4), which BS8 confirmed by observing the completion thread of the
DevTools futures (§2.5).

As built (2026-10-07). Besides the agent's workers, the module owns two kinds of thread, both daemon threads started
on first use, never at install: the tap poller `skillroy-web-tap`, which runs only while a tap is installed (§4.3),
and each built-in adapter's `AdapterThreads`: at most four threads named `skillroy-web-<adapter>-<n>` with the
host's loader as context loader (idle ones end after 30 s), a serial executor per browser for its events, and a
timer. A technology's callback only hands its result or event over to these threads and runs nothing else on the
technology's thread (one exception: a JCEF init-script removal that must follow an answer arriving after `detach()` is
sent from the thread that delivers that answer, §4.5). `AdapterThreads.close()` is terminal: `detach()` calls it, after
which a late callback is dropped and no thread is created (review 2026-10-07: before it, a callback arriving after
`detach()` started new threads in the host). On the controller, `skillroy-cdp` runs every Playwright call of an attach
on one platform thread, `skillroy-cdp-playwright` (Playwright is not thread-safe, and a call into a frozen page never
returns: a caller waits with its own bound, and while an abandoned operation still runs the thread is busy and later
operations are refused at once, never queued behind it); the probe's targets and the path's poller run on virtual
threads.

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
| A browser whose Swing component is displayable but not showing gets no native browser until `createImmediately()`; its viewport is 1 x 1 until overridden; the first `getDevToolsClient()` of a browser created that way can report `Client is closed` | `web.browsers` lists handles without a native browser with `capabilities: []` and `url: ""`; the adapter re-fetches a closed client before every call (rule 3 of §3) and, as built, sends a call the client refused as closed once more, never a call that reached the page (§4.5, D62); `web.resize` is the answer to the 1 x 1 viewport (browser §C6 confirmed) |
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
    /** Stable id: lower-case letters and digits ("jcef", "jxbrowser"). Also the suffix of the capability
     *  web.adapter.<id>. */
    String id();

    /** Are the technology's classes reachable from the host's loader, and which optional operations resolve?
     *  Must not create, initialise or configure an engine. The technology's gate class (JCEF: org.cef.CefApp;
     *  JxBrowser: its Engine class) must already be loaded by the host, which is asked without loading it; a gate
     *  the host has not loaded counts as absent, and then nothing is loaded. Only then are the technology's other
     *  classes and members looked up (Class.forName(name, false, hostLoader) and reflection), which may load and
     *  link them, and whatever linking needs, but initialises none of them (the gate's own state read,
     *  CefApp.getState(), excepted). The strategy of an OK result names the product and line it found ("jcef 152",
     *  "jxbrowser 7"). (As built 2026-10-07, D61: the specified rule, "must not load a class the host has not
     *  loaded", could not be met by any lookup of members.) */
    ProbeResult probe(ClassLoader hostLoader);

    /** Turns seeds into handles for every live browser reachable from them. A seed is a Swing component the tree scan
     *  found, or an object a BrowserRegistryAdapter handed in (a browser, a view, an engine or a client, as that
     *  technology knows them), unwrapped (BrowserSeed.object(); the tags stay with the module). Seeds of another
     *  technology are ignored. May add browsers no seed pointed at (JCEF: the process-wide list, browser-spi §F5).
     *  The same live browser yields a handle that is equal() to the one returned before, so that the module's
     *  HandleRegistry keeps one ref per browser. Never throws for a bad seed: it is skipped and reported through
     *  enumerationNotes(). */
    List<WebBrowser> browsers(Collection<?> seeds);

    /** The remote-debugging endpoints configured at engine start, one per engine, empty if none or unknown. A
     *  read-only fact for web.cdp.status; the adapter never sets or probes a port. */
    List<CdpEndpoint> cdpEndpoints(Collection<?> seeds);

    /** How complete the last browsers() call was: OK (every route worked), DEGRADED(reason) (seeds only: the
     *  technology's own list could not be read), UNAVAILABLE(reason) (the technology is present but no browser can be
     *  reached). Drives the capability web.browsers. */
    ProbeResult enumerationState();

    /** Notes of the last browsers() call that are not a state change (skipped seeds and why); informational, empty by
     *  default. (Added 2026-10-07 during the build: the comment on browsers() named it, the block had omitted it.) */
    default List<String> enumerationNotes() { return Collections.emptyList(); }

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
     *  returned subscription's timing() says which. Cancelling removes it for future documents. Needs EVALUATE
     *  (as built). */
    WebInitScript addInitScript(String source);
    /** Changes the layout viewport without touching Swing where the technology allows it (RESIZE). */
    CompletableFuture<Void> resize(int width, int height);
    /** Clears a viewport override made by resize(); completes at once when there is none. */
    CompletableFuture<Void> clearResize();
    /** A bitmap of the page as rendered (BITMAP). As built: a technology that does not render a browser whose
     *  component is not showing (JCEF 152 renders neither a hidden card nor a view-less browser) completes
     *  exceptionally with UnsupportedOperationException, which the module reports as CAPABILITY_UNAVAILABLE. */
    CompletableFuture<BufferedImage> bitmap();
    /** The CDP target id of this browser, or null when unknown (CDP_TARGET_ID). */
    CompletableFuture<String> cdpTargetId();
    /** (4.x, A37, built 2026-10-08) Reloads the main frame's document; completes once the reload was requested (the
     *  new document's load is a LOAD_FINISHED through subscribe()). No capability flag. The default evaluates
     *  location.reload() through evaluate() (location.reload(true) with ignoreCache, which only an adapter that
     *  overrides honours), completes when that evaluation answers and fails as it fails; both built-in adapters
     *  override it with their technology's own reload (§4.5). */
    default CompletableFuture<Void> reload(boolean ignoreCache) { … }
    /** (4.x, A37, built 2026-10-08) Where bitmap() comes from, which web.bitmap reports as its source; informational,
     *  never null. The default is VENDOR; the built-in jcef adapter answers PROTOCOL. */
    default BitmapSource bitmapSource() { return BitmapSource.VENDOR; }
}

public final class WebEval {                 // immutable value; builder
    public String expression();              // required; kept as given; null or blank is refused
    public String frameKey();                // null = main frame
    public boolean awaitPromise();           // default false
    public long timeoutMs();                 // the adapter abandons the call after this (default 10000; positive)
    public static Builder of(String expression);   // Builder: frameKey, awaitPromise, timeoutMs, build()
}
public final class WebFrame {                // snapshot value, not a live object
    public WebFrame(String key, String parentKey, String name, String url, boolean main);   // null name, url: ""
    public String key();                     // opaque, stable while the frame lives; minted by the adapter
    public String parentKey();               // null for the main frame
    public String name();                    // "" when unnamed
    public String url();
    public boolean main();
}
public final class WebEvent {                // made by the factories; no timestamp (the agent's bus stamps events)
    public enum Kind { LOAD_FINISHED, LOAD_FAILED, NAVIGATED, CONSOLE, CLOSED }
    public Kind kind();  public String frameKey();  public String url();     // frameKey null: the main frame
    public String level();  public String text();  public String source();  public int line();   // CONSOLE
    public String error();                                                                        // LOAD_FAILED
    public static WebEvent loadFinished(String frameKey, String url);
    public static WebEvent loadFailed(String frameKey, String url, String error);
    public static WebEvent navigated(String frameKey, String url);
    public static WebEvent console(String level, String text, String source, int line);   // line 1-based, 0 unknown
    public static WebEvent closed();
}
public interface WebEventListener { void onEvent(WebBrowser browser, WebEvent event); }
public interface WebSubscription { void cancel(); }
public interface WebInitScript extends WebSubscription {
    Timing timing();  enum Timing { DOCUMENT_START, DOCUMENT_LOADED }
}
public enum WebCapability { EVALUATE, AWAIT_PROMISE, FRAMES, EVENTS_LOAD, EVENTS_CONSOLE, INIT_SCRIPT_DOCUMENT_START,
                            RESIZE, BITMAP, CDP_TARGET_ID, INPUT /* reserved */, RAW_PROTOCOL /* reserved */ }
public enum WebRendering { WINDOWED, OFFSCREEN_LIGHTWEIGHT, OFFSCREEN_HEAVYWEIGHT, UNKNOWN }
public enum BitmapSource { PROTOCOL /* a DevTools-protocol screenshot */,          // (4.x, A37, built 2026-10-08)
                           VENDOR /* the technology's own bitmap, or anything else: the default */ }
public final class CdpEndpoint {
    public CdpEndpoint(String host, int port, String engineLabel);   // port 1..65535: port 0 means none, not reported
    public String host();  public int port();
    public String engineLabel();             // "jcef", or a JxBrowser engine's user-data directory name; may be null
}
public final class WebScriptException extends Exception {
    public WebScriptException(String message, String stack);  public String stack();
}
public final class WebFrameGoneException extends Exception {
    public WebFrameGoneException(String frameKey);  public String frameKey();     // "frame '<key>' no longer exists"
}

/** Technology-neutral, as design §2.6 states it: a domain module hands the agent seeds for browsers the Swing-tree
 *  scan cannot see (cached, hidden, or without a view). Called on agent workers; must be cheap, must not block and
 *  must never create or initialise a browser or an engine. Null or empty means none; null elements are skipped. */
public interface BrowserRegistryAdapter { Collection<BrowserSeed> seeds(); }
public final class BrowserSeed {             // equal by the object's identity and the tag; never calls the object
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
   detach (browser-spi §F9, §C3). (As built 2026-10-07, D62: only a call that the closed client refused before sending
   it is sent again, §4.5. After the kit's run against real JCEF, the adapter sends its calls, and receives answers and
   events, through a DevTools message observer of its own wherever JCEF's package-private raw methods resolve, because
   the shared client loses an answer that arrives before its message id; the client is then only the check that a native
   browser exists, and it remains the channel, under this rule, where they do not, §4.5.)
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
`CdpEndpoint.engineLabel()` (JxBrowser has one port per engine); typed exceptions for script errors and dead frames;
`enumerationNotes()` (added during the build); `reload(ignoreCache)` and `bitmapSource()`, with the enum `BitmapSource`
(4.x, A37, built 2026-10-08: default methods, §16.3).

**As built (2026-10-07): what the SPI's Javadoc fixes beyond the block** (the shapes are pinned by `WebSpiShapeTest`,
which also checks that no signature names a type outside `java.*` and the agent API; the adapters, the module and the
kit follow these rules):

- *Failures.* Without the capability a method names, its future completes exceptionally with
  `UnsupportedOperationException` (`addInitScript` throws it), except `frames()` (empty), `cdpTargetId()` (completes
  with null) and `clearResize()` (completes normally: there is no override). Once the browser has closed, every
  future completes exceptionally with `IllegalStateException`, within the caller's bound (`addInitScript` throws it,
  `frames()` is empty); the module tells closure from other failures with `isClosed()`. An `evaluate` whose
  `WebEval.timeoutMs()` passes first completes with `java.util.concurrent.TimeoutException`. An exception that
  completes a future or leaves a method is a JDK type or one of this package's, never a vendor's (rule 6: JCEF's
  `DevToolsException` is translated).
- *Events.* For load and navigation events a null `frameKey` is the main frame, so that the module publishes
  `web.ready {signal: LOAD}` without `FRAMES`. Console levels are `debug`, `log`, `info`, `warning` and `error` (an
  uncaught exception, `Runtime.exceptionThrown`, is `error`); `line` is 1-based, 0 when unknown. A listener may be
  called on more than one thread, returns at once and never blocks, and the adapter catches what it throws;
  `cancel()` is idempotent and never throws, and once it returns no delivery starts and no new document runs the
  init script.
- *Identity and threads.* Handles are equal by the identity of `vendorObject()`; a later `browsers()` call may
  return an equal handle with other capabilities. Accessors answer from what the adapter knows and bound any wait
  themselves; a future may complete, and a listener be called, on a technology thread (the EDT with JCEF off-screen),
  so the caller runs dependent work on its own threads. `url()`, `rendering()` and `capabilities()` are never null.
- *Values.* `toString()` of `WebEval` and `WebEvent` gives the length of the expression and of the console text,
  never the text (a page may echo a typed secret, §8.3); `equals`, `hashCode` and `toString` of `BrowserSeed` never
  call the seeded object (vendor code that may block or throw). The enum constants are declared in this block's
  order, which fixes the order of `capabilities: […]` in `web.browsers`.
- *After `detach()`* (as built after the review). A built-in adapter is inert once `detach()` has run: its probe
  answers `UNAVAILABLE("the adapter was detached")`, `browsers()` is empty, every call still in progress fails with
  that message and the adapter's threads end for good (§2.4), so that a re-probe racing an uninstall cannot bring it
  back (§4.1).
- *Reload and the bitmap's source* (4.x, A37, built 2026-10-08, §16.3; until then *not in the SPI*: `web.reload` and
  the bitmap's `source` needed more than `WebBrowser` offered, the built-in adapters' handles also implemented the
  module-internal `BrowserExtras` (`reload(ignoreCache)`, `bitmapSource()`), and the module reloaded a handle of a
  third-party adapter with `location.reload()` through `evaluate` and reported its bitmap as `source: "VENDOR"`, D67).
  `reload(ignoreCache)` and `bitmapSource()` are default methods of `WebBrowser`, so an adapter built against the
  Phase 4 SPI links and runs unchanged, with the defaults. `reload` completes once the reload was requested, not when
  the new document has loaded (that is a `LOAD_FINISHED` through `subscribe`), and names no capability flag. Its
  default evaluates `location.reload()` in the main frame through `evaluate`, with `WebEval`'s default timeout
  (`location.reload(true)` when `ignoreCache` is set: a non-standard argument that Chromium ignores, so only an adapter
  that overrides the method honours `ignoreCache`), completes when that evaluation answers and fails as it fails
  (without `EVALUATE` with `UnsupportedOperationException`, on a closed browser with `IllegalStateException`; an
  `evaluate` that throws fails the future instead), and cancelling the returned future cancels the evaluation. A reload
  by the page's own script is a navigation the page starts, which a technology may refuse (Chromium refuses it for a
  `data:` document, §10.1 item 5) and which a frozen page never makes, so an adapter whose technology can reload from
  outside the page overrides the method; both built-in adapters do (§4.5). `bitmapSource()` answers from what the
  adapter knows, never null: `PROTOCOL` for a DevTools-protocol screenshot, `VENDOR` (the default) for the
  technology's own bitmap or anything else.

---

## 4. `skillroy-agent-web` (module id `web`)

### 4.1 Install, readiness and the browser index

`WebModule.install` registers the primitives, declares the capabilities of §4.4 as `UNAVAILABLE("no embedded
browser technology found")` and registers nothing with the host. It touches no AWT class and no vendor class
(P1 §4: `install` runs before the application's `main` in `JAVAAGENT` mode). The two built-in adapters are
constructed but not probed. (As built 2026-10-07: the thirteen primitives of §4.2; `web.hittest` and `web.pointer`
are `UNAVAILABLE("reserved for the vision bolt-on")` from the start; install starts no thread, and a test with a
recording class loader checks that it loads no AWT or vendor class.)

`onTargetReady` runs the probes: each built-in adapter and each `WebEngineAdapter` registered through `SpiRegistry`
is asked `probe(hostClassLoader)`; an adapter whose probe is `UNAVAILABLE` is kept out of the index until a later
probe finds it present (`agent.capabilities {reprobe:true}` runs the probes again, so an application that starts its
browser engine late, or a pack that registers an adapter late, is picked up). The capability `web.adapter.<id>`
takes the probe's result and strategy. (As built: one probe cycle on an agent worker, bounded at 30 s, the built-in
adapters first; an adapter whose id is not lower-case letters and digits, or repeats an earlier one, is skipped with a
log line.)

**The re-probe after `window.opened`** (as built 2026-10-07 after the review, D60). The specified rule, a re-probe
"on the first `web.*` call after a `window.opened` event", cannot work: while no adapter is present the dispatcher
refuses every `web.*` call before the module sees it. After each `window.opened` event the module therefore
re-probes, on an agent worker and coalesced, only: every built-in adapter whose last probe was `UNAVAILABLE` because
its technology is present but not yet loaded by the application ("present but not loaded by the application yet"),
every adapter registered through `SpiRegistry` whose last probe was `UNAVAILABLE`, and every adapter registered since
the last cycle. That probe is cheap (no index rebuild, no Swing walk); when one of their results changed (state or
reason), the module runs a full cycle: every probe, the index rebuilt, the capabilities set. An adapter whose
technology is not present at all ("not present": its gate class cannot be found from the host's loader) is probed
again only on reprobe. Coalesced means one re-probe queued or running at a time; a queued one older than 30 s (a post
that the core's broker dropped when its pool was full) no longer holds the next one back. No rate limit beyond that is
built (the review proposed one a second; §13). (4.x, A38, built 2026-10-08, §16.4: at most once a second, and no event
dropped. Every `window.opened` marks a re-probe pending and posts a worker unless one is queued or running. The worker
loops while a re-probe is pending: when some adapter is a candidate (the three kinds above, or the set of adapters
changed) and the previous re-probe cycle started less than 1 s ago, it first waits out the rest of that second on its
own agent worker (at most 1 s, on a monitor that uninstall wakes), then clears the mark and runs the cycle. An event
that arrives during the wait is covered by that cycle; one that arrives while the cycle runs marks it pending again,
and the worker runs one further cycle, a second after the start of the one before. Without a candidate the worker
neither waits nor probes. The worker renews its queued time every round, so that a long burst is not taken for a lost
post. The readiness cycle and a reprobe are not paced.)

**Uninstall** (as built) never throws: the event bridge and the probe cycle are closed first, so that a re-probe still
running can neither subscribe a browser nor bring a detached adapter back (review 2026-10-07: the bridge cancels every
subscription it made and refuses new ones; closing the cycle waits for a running one, after which a cycle probes and
rebuilds nothing); then the taps are removed (bounded at 3 s), every subscription is cancelled, every adapter detached
(listeners, init scripts and viewport overrides removed; nothing of the application closed; a built-in adapter is
inert afterwards, §3), the index cleared, the capabilities withdrawn and the primitives unregistered.

**The browser index** (`BrowserIndex`) is rebuilt on every `web.browsers` call and on every call that names a browser
whose ref is unknown or stale, bounded at 2 s of agent-worker time (as built also by every probe cycle, and by a `by`
or default selection that finds the index older than 2 s or nothing in it that matches):

1. seeds from every `BrowserRegistryAdapter` (each adapter is called once; one that throws is logged, once per class,
   and skipped; null or empty means none, and null elements are skipped);
2. seeds from the Swing tree: every showing or displayable component of every window (the Swing module's walk,
   `EDT_SYNC`, bounded as `swing.snapshot`), handed to every adapter as seeds (an adapter recognises its own view
   components and ignores the rest); as built one bounded `EDT_SYNC` read of at most 50000 displayable components,
   made only while an adapter is present and an `AWT-EventQueue` thread is alive;
3. each adapter's `browsers(seeds)`, given the seeds' objects unwrapped; the handles are given refs of kind `browser`
   through the `HandleRegistry` (`handleFor(handle.vendorObject(), "browser")`, so the ref is stable for the life of
   the vendor object), their components refs of kind `component`; an adapter the budget does not reach keeps its
   previous browsers;
4. the `web.browsers` capability becomes the worst `enumerationState()` of the adapters that found anything, `OK`
   when every adapter that is present reports `OK`; as built it is never worse than `DEGRADED` while an adapter is
   present (a present adapter's `UNAVAILABLE` enumeration counts as `DEGRADED` with its reason), because the
   dispatcher refuses a call whose capability is `UNAVAILABLE` and `web.browsers` is the call that rebuilds the index
   (D66).

A browser that disappeared from the index answers `STALE_HANDLE` to a call that names it (P1 §4 `HandleRegistry`);
as built its ref is released, its subscription cancelled and `web.browsers.changed {added, removed}` published.

### 4.2 Primitives

Browser selection: every primitive except `web.browsers` and `web.cdp.status` takes `browser: <ref>` **or**
`by: {url: <regex>} | {tag: <string>} | {title: <regex>} | {nativeId: <string>}`, resolved against the index: zero
matches is `NOT_FOUND` with `data.browsers`, several is `AMBIGUOUS` with the candidates. With neither, the default is
the single browser whose component is showing; several or none is `AMBIGUOUS` / `NOT_FOUND` with the same data.
`frame?: <frameKey>` selects a frame where a primitive says so; the default is the main frame. (As built 2026-10-07:
a `by` object may hold several of the keys, which must all match; a regular expression is Java's and is found
anywhere in the text, as JavaScript's `RegExp.test` finds it. `data.browsers` lists every open browser of the index
as `{ref, adapter, nativeId?, url, title?, closed, visibleInSwing, component?, capabilities, tag?}`, `title` only when
the selection read it and `closed` always false here (kept so that `NOT_FOUND` and `AMBIGUOUS`, whose candidates have
the same shape, share one summary); closed browsers are omitted, as `web.browsers` omits them by default, and no page
is read again on the error path (review 2026-10-07: the specified "the index as `web.browsers` lists it" would spend up
to 2 s on page reads for a diagnostic). A ref the index does not know rebuilds the index once; a released ref, or a
browser that has closed, is `STALE_HANDLE`; a ref of something other than a browser is `INVALID_PARAMS`.)

| Primitive | Params | Result `value` | Thread, timeout, capability, flags |
|---|---|---|---|
| `web.browsers` | `includeClosed?: bool (false)` | `{browsers:[{ref, adapter, nativeId?, url, title?, closed, visibleInSwing, component?: <ref>, rendering, viewport?: {w,h}, capabilities:[…], cdpTargetId?, tag?, frames:[{key,parentKey?,name,url,main}]}]}`; `title` and `frames` need `EVALUATE`/`FRAMES` and are omitted otherwise; `cdpTargetId` is read with a 2 s bound and omitted when unknown (as built: kept per browser once a read succeeded and reported when a later read times out, so that a frozen page still has it; a later read replaces it; on JCEF the handle also keeps the main frame's id) | `WORKER`, 10000, `web.browsers` |
| `web.evaluate` | browser selection, `expression: string`, `frame?`, `awaitPromise?: bool (false)` | `{json: <text>, typeofResult: string, truncated?}` where `json` is the value as JSON text (`"null"` for undefined). A script error is `SCRIPT_ERROR` (new code `-32008`, §8) with `data {browser, message, stack}` | `WORKER`, 10000, `web.evaluate`, **mutating** (browser-spi §C4) |
| `web.ariaSnapshot` | browser selection, `frame?`, `maxNodes?: int (2000, cap 20000)`, `interestingOnly?: bool (true)` | `{nodes:[{role, name, value?, checked?, disabled?, focused?, level?, children?}], truncated}`: the library's walker over the accessibility semantics of the DOM (role from ARIA role or element kind, name by the accessible-name computation the library implements, as Playwright's `ariaSnapshot` does for the same elements); as built the roles, names, states and text rules of §4.6 | `WORKER`, 10000, `web.evaluate`; not mutating (D51) |
| `web.dom.text` | browser selection, `frame?`, `selector?: string` (CSS; default `body`), `maxChars?: int (10000, cap 100000)` | `{text, truncated}` (the element's `innerText`; `textContent` where there is none); a selector that matches nothing is `NOT_FOUND` | `WORKER`, 10000, `web.evaluate`; not mutating |
| `web.dom.click` (mutating) | browser selection, `frame?`, **one of** `selector: string`, or `role: string` + `name: string\|{regex}` + `index?: int`; `expectVisible?: bool (true)` | `{matched: int, clicked: {role, name, tag, id?}, fidelity: "DOM"}`; zero matches `NOT_FOUND` with `data.selector/role/name`, several without `index` `AMBIGUOUS` with `data.candidates`; a hidden element with `expectVisible` is `INVALID_PARAMS` (`data.reason: "not visible"`). As built: `index` applies to a `selector` as well; `AMBIGUOUS` also carries `count` (at most 20 candidates are listed); an `index` out of range is `NOT_FOUND` with `index` and `count`; a disabled control is clicked as the DOM clicks it (a no-op) | `WORKER`, 10000, `web.evaluate` |
| `web.dom.fill` (mutating) | as click, plus `text: string`, `clear?: bool (true)`, `enter?: bool (false)` | `{matched, filled: {tag, type, id?}, typed: int}`; the library sets the value through the native setter so that framework bindings see it, then dispatches `input` and `change`, and `Enter` as a `keydown`/`keyup` pair when asked. As built further `INVALID_PARAMS` reasons: `not fillable` (with `tag` and `type`: checkbox, radio, file, buttons, an element that takes no text), `not editable` (disabled or read-only), `value rejected` (the element turned a non-empty value into an empty one, as a number field does with letters; the previous value is restored and no event fired), `no such option` (a `select`, filled by option value, else by label); `typed` counts UTF-16 code units; the text never appears in error data | `WORKER`, 10000, `web.evaluate` |
| `web.dispatchEvent` (mutating) | browser selection, `frame?`, `name: string`, `detail?: any`, `target?: selector (default window)` | `{dispatched: true}`: `target.dispatchEvent(new CustomEvent(name, {detail, bubbles: true}))` (as built `target` may also be `document`) | `WORKER`, 10000, `web.evaluate` |
| `web.tap` (mutating) | browser selection, `patterns: [string]` (event names or globs `app.*`), `target?: "window"\|"document"` | `{installed: int, timing: DOCUMENT_START\|DOCUMENT_LOADED}`: an init script that listens for the named events on every new document and publishes each as `web.tap` (§4.3); re-installing replaces the patterns. As built the same installer also runs in the current document; `installed` counts distinct patterns; `patterns: []` drains what is buffered, removes the tap and cancels the init script | `WORKER`, 10000, `web.events` |
| `web.waitFlag` | browser selection, `frame?`, `expression: string` (truthy means ready), `timeoutMs?: int (0 = the call's budget, at most 600000)`, `pollMs?: int (50, min 20)` | `{ready: bool, ms, polls}`: evaluated on the agent worker until truthy or the budget ends; a step with `sync: EVENT` on `web.ready` pairs with it (§6.3). Answers `ready:false` before the dispatcher's `TIMEOUT`. As built: `lastError` (the last poll's error) when not ready (4.x, A35, built 2026-10-08: every poll is the shared guard `WebLib.flagExpression` of §4.6, on both paths; a poll whose expression throws is not ready and the wait goes on; `lastError` is the first line of the last poll's error, at most 2000 characters, present only when that poll threw; an expression that does not compile is `SCRIPT_ERROR` at once, `data {browser, message, stack: ""}`; a blank one `INVALID_PARAMS`; §16.1); at most one poll is outstanding (D63): a poll is sent with the rest of the budget as its bound and awaited in slices of max(2 s, 4 x `pollMs`), a poll the page has not answered is awaited again, not abandoned and replaced, and one still pending when the wait ends is cancelled; `polls` counts the polls sent; a load of the browser wakes the wait early; a flag that turns ready publishes `web.ready {signal: FLAG}`. The dispatcher caps every call at 120 s, so the base verb accepts at most 119000 (§7.1) | `WORKER`, 10000, `web.evaluate`; not mutating |
| `web.resize` (mutating) | browser selection, `width: int`, `height: int` (1..16384), or `clear: true` | `{applied: "OVERRIDE"\|"COMPONENT", viewport: {w,h}}`: the adapter's `resize` (a protocol-level override on JCEF, `Browser.resize` on JxBrowser); `COMPONENT` is reserved for a later Swing-bounds variant and is not built. As built `clear: true` removes the override; the CDP path sets the same protocol override on its own session (§5.2, D64) | `WORKER`, 10000, `web.resize` |
| `web.bitmap` | browser selection, `maxWidth?: int (4096)` | `{format: "png", width, height, base64, source: "PROTOCOL"\|"VENDOR"}`; scaled down keeping the aspect ratio above `maxWidth`. As built the scaling is a box filter in plain Java, one row of pixels at a time and without `Graphics2D`, so it starts no toolkit, AppKit or Java2D thread (review 2026-10-07: the first build's `Graphics2D` scaling started them in a target without a user interface); `source` is `PROTOCOL` for the built-in JCEF adapter and `VENDOR` for JxBrowser and for a third-party adapter (D67; 4.x, A37, built 2026-10-08: the handle's `bitmapSource()`, §3, whose default is `VENDOR`; `VENDOR` also when an adapter's own answer is null or throws) | `WORKER`, 20000, `web.bitmap`; not mutating |
| `web.reload` (mutating) | browser selection, `ignoreCache?: bool (false)` | `{reloaded: true}`: `CefBrowser.reload()` / `Navigation.reload()` through the adapter (as built through the module-internal `BrowserExtras` of the built-in adapters; a third-party browser is reloaded with `location.reload()` through `evaluate`, D67; 4.x, A37, built 2026-10-08: through the SPI's `WebBrowser.reload(ignoreCache)` for every browser, §3, which the built-in adapters override with their technology's reload and whose default, for an adapter that does not, is that same `location.reload()` through `evaluate`; the call answers once the reload was requested, and a failure maps as every adapter failure does, below the table). Exists for frozen-target recovery (§5.3) and for a pack's own recover; no base verb emits it | `WORKER`, 10000, `web.browsers` |
| `web.cdp.status` | – | `{endpoints:[{host, port, engineLabel, adapter}], browsers:[{ref, cdpTargetId?}]}`: the configured endpoints the adapters report (JCEF: from the private `settings_` of `CefApp`, else from `<cache dir>/DevToolsActivePort` when the cache path is known, else none; JxBrowser: `engine().options().remoteDebuggingPort()`); nothing is probed over the network by the agent. As built the `DevToolsActivePort` file counts only when it is not older than the JVM's start, less 2 s for coarse file times (an older file is a previous run's; review 2026-10-07, D65), and `browsers` carries the kept target ids | `WORKER`, 5000, `web.browsers`; not mutating |

Every `web.*` call runs on `WORKER`; the adapter's futures are awaited with the call's remaining budget less 100 ms; a
timed-out future is abandoned (`cancel(false)`), never retried on another thread (browser-spi §C1), and the call answers
`TIMEOUT` (as built before the dispatcher's own, and like every error the module makes itself with `data.browser` and
`data.primitive`). A browser that closed between selection and call answers `STALE_HANDLE`. A **read** (a primitive that
is not mutating, D51) is retried once after a `web.load` event on that browser, also when its first attempt failed
because the page navigated, within the call's budget; a mutating call never is. (As built 2026-10-07: a read still
pending when the load arrives is sent again at once; a read that starts within 2 s after a load gets 2 s before it is
sent again; a read whose attempt failed, other than with a script error, a gone frame, a missing operation or an error
of the module, while the browser is open, is sent once more when a load of that browser follows within 2 s. Review:
Chromium answers a call in flight across a navigation with an error that arrives before the load event, and the retry of
reads moved from the JCEF adapter into the module, D62.) The adapters' failures map to the protocol's errors:
`WebScriptException` to `SCRIPT_ERROR {browser, message, stack}`, `WebFrameGoneException` to `NOT_FOUND {browser,
frame}`, `UnsupportedOperationException` to `CAPABILITY_UNAVAILABLE`, an `IllegalStateException` of a browser that has
closed to `STALE_HANDLE`, anything else to `INTERNAL_ERROR`. In read-only mode the mutating primitives are refused and
the reads served (D51). No primitive logs its arguments, and `web.dom.fill` answers only the count it typed.

`web.evaluate` wraps the caller's expression so that the result crosses as JSON text: `JSON.stringify` of the value
with a replacer that turns `undefined` into `null`, functions and symbols into their `String()`, DOM nodes into
`{nodeName, id, className}` and cyclic structures into `"[cycle]"`, capped at 1 MiB (`truncated: true` in the
result when cut). (As built 2026-10-07: a `BigInt` and a `Window` become their `String()` as well, because
`JSON.stringify` throws on the first and the walk of the second throws on an opaque origin; a cycle is a value that is
its own ancestor, so a value shared without a cycle is written twice; 1 MiB is 1,048,576 UTF-16 code units of the
inner JSON text, cut without splitting a surrogate pair; the wrapper is `WebLib.wrap(expression, awaitPromise)`, the
expression embedded once and verbatim, with an `async` wrapper for `awaitPromise`, §4.6.)

**`web.waitFlag` on the two paths** (as built 2026-10-07 an open gap, §13; one behaviour since (4.x, A35, built
2026-10-08), §16.1). Both paths poll the same page-side guard, `WebLib.flagExpression(expression)` (§4.6), which
compiles the expression on its own before it runs it. A poll whose expression throws at run time counts as not ready,
and polling goes on until the flag is truthy or the wait's time is up; a wait that ends not ready answers
`ready: false`, with the last poll's error in `lastError` when that poll threw, so that the base `webWaitFlag` ends
`FAIL` / `SYNC_TIMEOUT` (§8.1) on either path. An expression that does not compile can never become ready: it is
`SCRIPT_ERROR` at once on both paths, which a step reports as `FAIL` / `ASSERTION`. A flag that throws until the page
is ready (a property read of an element that does not exist yet) therefore waits on either path. (As built
2026-10-07 the CDP path ended the wait at the first poll that threw, with `SCRIPT_ERROR`, and the agent polled on
through a compile error until its time was up.)

### 4.3 Events

Published on the agent's `EventBus` with source `web`; payload `browser` is the browser's ref. (As built 2026-10-07:
the topic names are constants of the protocol's `Topics`, `WEB_LOAD` to `WEB_BROWSERS_CHANGED`, shared by the agent
and the controller.)

| Topic | Payload | When |
|---|---|---|
| `web.load` | `{browser, url, frameKey?, kind: LOAD_FINISHED \| LOAD_FAILED \| NAVIGATED, error?}` | the adapter's load events (JCEF, as built: `Page.frameNavigated` (`LOAD_FAILED` when it carries `unreachableUrl`), `Page.loadEventFired`, `Page.frameStoppedLoading` (a sub-frame's load) and `Page.navigatedWithinDocument` through the shared client (after the kit's run against real JCEF, through the adapter's own message observer wherever the raw channel of §4.5 resolves); the protocol has no `Page.loadingFailed`, which the specification named; JxBrowser: `FrameLoadFinished`, `FrameLoadFailed`, `NavigationFinished`) |
| `web.ready` | `{browser, url, signal: LOAD \| TAP \| FLAG, name?}` | the main frame finished loading (`LOAD`); a tapped pattern fired (`TAP`, `name` = the event name); `web.waitFlag` turned ready (`FLAG`). The step protocol's "web.ready" (design §2.5) is this topic. As built a main-frame `LOAD_FINISHED` publishes `web.load` and then `web.ready {signal: LOAD}`, and every `web.tap` is followed by `web.ready {signal: TAP, name}` |
| `web.console` | `{browser, level, text, source?, line?, truncated?}` | console output; `text` capped at 2000 characters (`truncated: true` when cut) and masked: A34 names apply to the call that caused it, by key only otherwise (as built the module masks the text and the source itself, below: the core's secret redaction, which the specification named, does not reach event payloads) |
| `web.tap` | `{browser, name, detail?, truncated?}` | a tapped page event; `detail` is the event's `detail` as JSON (capped at 64 KiB, `truncated: true` when cut) |
| `web.closed` | `{browser}` | the adapter observed the browser closing (JCEF: `getDevToolsClient()` turned null or the browser left the process-wide list at the next index rebuild, as built also an `Inspector.detached` after which no client can be fetched; JxBrowser: `BrowserClosed`); as built at most once per browser |
| `web.browsers.changed` | `{added: [ref], removed: [ref]}` | an index rebuild found a difference |

Load and console subscriptions are made once per browser when it first enters the index and cancelled on `detach`
(as built also when the browser leaves the index). On JCEF they require `Page.enable` and `Runtime.enable` on the
shared DevTools client, which stay enabled on detach (browser-spi §C3; BS8 §2.5 shows what the application then also
sees); as built they go through the adapter's own channel wherever the raw roles resolve (§4.5), and `subscribe()`
returns once the page has answered both (at most 2 s on the calling worker, at once on the EDT), so that a navigation
started next is reported (§10.3).

**The tap poller** (as built 2026-10-07). While at least one tap is installed, a daemon thread `skillroy-web-tap`
wakes every 100 ms and hands one drain round to an agent worker (bounded at 5 s; a round that finds the pool busy is
skipped). A round calls the library's `drain(900000)` in each tapped browser, at most eight times while it answers
`more: true`, and publishes each event as `web.tap` followed by `web.ready {signal: TAP}`; events the page dropped
(its buffer holds 1000) are logged. The thread ends when the last tap is removed, when the tapped browsers have left
the index, or on uninstall. The agent's poller is the only consumer of the in-process buffer (one drainer per
document). **One outstanding drain** (after the review, D63): `drain()` empties the page's buffer, so a drain that
outlives the poller's 2 s bound is not discarded. The agent sends each drain with an adapter bound of 60 s and waits for
it at most 2 s without cancelling it; one still unanswered stays outstanding, its events are published when it answers
(on an agent worker when the answer arrives on the EDT; after uninstall they are dropped), and no further drain is sent
to that page meanwhile (at most one outstanding drain per browser, on both paths: §5.2). The same rule holds for the
polls of `web.waitFlag` (§4.2). When a tapped browser leaves the index while it is still open, its tap is also cleared
in its current document (the installer with no patterns, on an agent worker, bounded at 1 s, best effort), so that no
listener or `dispatchEvent` wrapper of the agent stays behind (review 2026-10-07).

**Masking of `web.console`** (as built 2026-10-07, D68). The core does not redact event payloads, and console output
arrives on a browser's thread, outside the call that caused it, so the module masks the text (and the source URL)
itself, with two rules. *By value:* the values of the parameters a `web.*` call named in `_meta.secretParams` (read
through `Invocation.secretParams()`, a read-only accessor of the agent API, default empty, that the dispatcher fills;
an extension of A34) are remembered from the call's start until 60 s after its budget ends, at most 256 of them; a
remembered value of eight or more characters is replaced wherever it occurs, a shorter one where it stands as a token
of its own (the core's rule), and a text equal to a value becomes `<redacted>`. *By key:* in `key=value`,
`key: value` and `"key": "value"` pairs whose key looks secret (`SecretKeys.looksSecret`, the `redact` option's
pattern, every registered `RedactionKeys`) the value is replaced. Limits: a secret logged more than about a minute
after its call is masked by key only (and by the controller's mask in `run.jsonl`); a secret split across messages or
transformed by the page is not recognised.

### 4.4 Capabilities

| Id | `OK` when | Otherwise |
|---|---|---|
| `web.adapter.<id>` | the adapter's probe is `OK`; strategy = product and line ("jcef 152", "jxbrowser 7"); as built ", windowed, Java 8: unverified" is appended for windowed JCEF on a Java 8 target (§2.5) | the probe's `DEGRADED` or `UNAVAILABLE` with its reason; one entry per built-in and registered adapter; as built an absent technology answers `UNAVAILABLE("not present")` (its gate class cannot be found) or `UNAVAILABLE("present but not loaded by the application yet")` |
| `web.browsers` | the last index rebuild reached every route of every present adapter | `DEGRADED("seeds only: …")` when a present adapter could not read the technology's own list (JCEF: the private members were not found); `UNAVAILABLE` when no adapter is present; as built never worse than `DEGRADED` while an adapter is present (§4.1, D66) |
| `web.evaluate` | at least one indexed browser has `EVALUATE` | `UNAVAILABLE` with the first adapter's reason (JCEF without `getDevToolsClient()`: "JCEF older than 2024-01: no in-process result channel", browser-spi §F8); as built "no browser found" when adapters are present and the index is empty, "no indexed browser has EVALUATE" when no browser has the flag, and with no adapter present the first informative adapter reason (not "not present" or "not loaded"), else "no embedded browser technology found" |
| `web.events` | at least one indexed browser has `EVENTS_LOAD` | as above |
| `web.initScript` | at least one indexed browser has `INIT_SCRIPT_DOCUMENT_START`; strategy `DOCUMENT_START` | `DEGRADED("after document load")` when scripts can only be added late (JxBrowser without the opt-in of §4.5); as built `UNAVAILABLE` as above when no browser runs scripts at all |
| `web.resize` | at least one indexed browser has `RESIZE` | `UNAVAILABLE` |
| `web.bitmap` | at least one indexed browser has `BITMAP` | `UNAVAILABLE` |
| `web.cdp` | at least one endpoint is configured (the agent's view; the controller decides whether it answers, §6.1); as built strategy `host:port` of the first endpoint | `UNAVAILABLE("no remote-debugging endpoint configured")` |
| `web.hittest`, `web.pointer` | never (reserved, B16) | `UNAVAILABLE("reserved for the vision bolt-on")` |

Capabilities are probed after `onTargetReady` and on reprobe, as every other module's (P1 §5); the per-browser
flags above are evaluated at probe time over the index of that moment. (As built 2026-10-07: one computation, a
*cycle*, serves every capability of a probe round, and a repeated id or a cycle older than 30 s starts a new one; an
index rebuild outside a cycle (`web.browsers`, a selection) may improve a per-browser capability, never worsen it, and
`web.browsers` follows the rebuild's enumeration states; the re-probe after `window.opened` sets what its cycle
computed, §4.1.)

### 4.5 The two built-in adapters

**`jcef`** (`…agent.web.jcef.JcefAdapter`), on `CefBrowser.getDevToolsClient()` and nothing else for operations
(browser-spi §R1; as built, on the browser's own DevTools methods wherever they resolve, the raw channel below),
everything by reflection through the host's loader:

- *Probe.* `org.cef.CefApp`, the gate, already loaded by the host (as built 2026-10-07, D61: the specification said
  "loadable from the host loader without initialisation"; the host is asked through `LoadedClasses`, §4.7, without
  loading anything, and the probe answers "not present" when the class file cannot even be found, "present but not
  loaded by the application yet" when it can); `CefApp.getState()` read by reflection, never `getInstance()`;
  `CefBrowser.getDevToolsClient` present (else `UNAVAILABLE("JCEF older than 2024-01: no in-process result
  channel")`, and Phase 4 serves such a JCEF on neither path: an `UNAVAILABLE` adapter indexes no browser and reports
  no endpoint; the specified "with CDP over a port still possible" was delivered by nothing, review 2026-10-07);
  `CefDevToolsClient.executeDevToolsMethod(String, String)` and `addEventListener` present. Strategy: "jcef <Chromium
  major>" from `CefApp.getVersion()` when the state is `INITIALIZED`, else "jcef"; as built ", windowed, Java 8:
  unverified" is appended for windowed rendering on a Java 8 target (§2.5).
- *Enumeration* (browser-spi §R2): seeds first (a `CefBrowser`, a `CefClient` (its `getAllBrowser()`), a
  `CefApp` (its clients), or a component equal to some browser's `getUIComponent()` or a descendant of it); then,
  only in state `INITIALIZED`, the reflective walk `CefApp.self -> clients_ -> getAllBrowser()`; when the private
  members are missing or `setAccessible` is refused, `enumerationState()` is `DEGRADED("seeds only: <reason>")`.
  BS8 verified the walk against jcefmaven 152.0.6 (§2.5); the member names are a descriptor (§4.7) so that a fork
  can be served by another descriptor without code changes. (As built: JCEF has no component-to-browser accessor, so
  a component seed keeps a browser the adapter already knows listed but finds no new one, and counts only in
  seeds-only mode; outside `INITIALIZED` the state is `DEGRADED("seeds only: CefApp is <state>")`; a known browser that
  left the process-wide list is marked closed once.)
- *Operations.* `evaluate` = `Runtime.evaluate {expression, returnByValue: true, awaitPromise}` (with
  `contextId` for a sub-frame, taken from `Runtime.executionContextCreated` events); `frames` = `Page.getFrameTree`,
  keys are the protocol's frame ids; events = `Page.*` and `Runtime.consoleAPICalled` / `Runtime.exceptionThrown`;
  `addInitScript` = `Page.addScriptToEvaluateOnNewDocument` (timing `DOCUMENT_START`), removed with
  `Page.removeScriptToEvaluateOnNewDocument`; `resize` = `Emulation.setDeviceMetricsOverride` and
  `clearDeviceMetricsOverride`; `bitmap` = `Page.captureScreenshot` (source `PROTOCOL`, both rendering modes;
  `createScreenshot` is not used); `cdpTargetId` = the main frame's `id` in `Page.getFrameTree` (BS8: the `Target`
  domain is not served by this client, and that id equals the `/json/list` entry); `cdpEndpoints` = `settings_.
  remote_debugging_port` when it holds a port, else the port in a `DevToolsActivePort` file under `root_cache_path`,
  then `cache_path`, that is not older than the JVM's start less 2 s (as built 2026-10-07 after the review, D65: an
  older file is a previous run's, and coarse file times need the tolerance).
  `rendering()` = `WINDOWED` for `CefBrowserWr`, `OFFSCREEN_HEAVYWEIGHT` for `CefBrowserOsr` (a JOGL canvas),
  `OFFSCREEN_LIGHTWEIGHT` for an off-screen browser whose component is lightweight (an application's own
  component), `UNKNOWN` otherwise. (As built 2026-10-07: the events are those of §4.3 plus `Inspector.detached`; the
  `contextId` of a frame is its default context (`auxData.frameId`), forgotten on `executionContextsCleared`, on a
  new client, on `executionContextDestroyed` and on `frameDetached`; for a frame not yet known the adapter waits for
  `Runtime.enable` to replay the live contexts, then asks `Page.getFrameTree` (a gone frame is
  `WebFrameGoneException`); `web.reload` uses `reload()` / `reloadIgnoreCache()` through `BrowserExtras` (D67; 4.x, A37,
  built 2026-10-08: through the handle's override of the SPI's `reload`, which sends `Page.reload {ignoreCache}` on its
  channel where the descriptor's `reload` roles do not resolve, and `bitmapSource()` answers `PROTOCOL`); the
  target id, once read, is kept with the main frame's id, so that a frozen page still reports it. After the kit's run
  against real JCEF (§10.3): `bitmap()` of a browser whose component is missing or not showing is refused at once with
  `UnsupportedOperationException` (`CAPABILITY_UNAVAILABLE`, §13), because Chromium renders nothing for it (windowed the
  screenshot was never answered, off screen it held the page's background colour only); and `clearResize` waits, at most
  2 s, until the page leaves the cleared size, which windowed rendering restores asynchronously, about 100 ms later.)
- *Rules.* The client is never closed; a client whose `isClosed()` is true is re-fetched and re-subscribed before
  every call (BS8 saw a freshly created browser's first client closed); the message router is not used; no handler
  slot is taken (browser-spi §R1, §C2, §C3). Results complete on CEF's UI thread, the EDT with off-screen rendering
  and a CEF thread with windowed rendering (BS8, §2.5); the adapter hands them over to the caller's future without
  running anything else on that thread. A browser without a native browser (not yet shown, §2.5) is a handle with
  no capabilities until the application creates it. (As built 2026-10-07 after the review, D62: on a fresh client the
  listener proxy, the domains, the viewport override and the init scripts are put back, each init script once per
  client; a client that is closed as soon as it is fetched (BS8) is fetched once more before the call is sent. On the
  shared client a call is sent again at most once, and only when all of these hold: it is one of the handle's own
  protocol reads (`Page.getFrameTree`, `Page.captureScreenshot`), its caller still waits (a timeout or a cancellation
  completes the caller's future), and the client refused it as closed (JCEF's "Client is closed" with the client's own
  `isClosed()` true: it never left the agent). An evaluation is never sent again by the adapter, which cannot tell a
  read from a mutating script; the module retries the reads it makes by evaluation (§4.2). A call that reached the
  page is never sent again, whatever its answer: Chromium answers a call in flight across a navigation with -32000
  "Inspected target navigated or closed", which the first build took for a closed client, re-running clicks and
  abandoned calls in the page. `evaluate` sends nothing for a caller that has already given up, and cancels its
  protocol call when the caller's future completes. `detach()` removes the init scripts from every client they were
  added to, also when the page answers an addition only after the detach (a frozen renderer): the removal then follows
  the late answer, sent on that client from the thread that delivers the answer, CEF's, as a non-blocking send whose
  answer is not awaited, because the adapter's own threads have ended by then (the one thing a handle runs on a
  technology thread); calls still in progress at `detach()` fail at once ("the adapter was detached"). `subscribe()`
  returns once the channel's `Page.enable` and `Runtime.enable` are answered (at most 2 s on the calling worker, at
  once on the EDT). Results and events are handed to the adapter's own threads, events through a serial executor per
  browser (§2.4); `detach()` is bounded at 2 s.)
- *The raw channel* (as built 2026-10-07, after the kit ran against real JCEF, §10.3). JCEF 152's `CefDevToolsClient`
  loses an answer that CEF's UI thread delivers before the call's message id has reached the client: the client
  completes and drops the future it queued for that answer, and the call then waits for ever (about one call in a
  hundred in the image, more often windowed; kit items 3, 4, 9 and 11 timed out on it, and it explains BS8's evaluations
  that timed out and its `Target.*` calls that never answered, §2.5). Where the descriptor's four `raw.*` roles resolve
  (the package-private `CefBrowser_N.executeDevToolsMethod(String, String)` and `addDevToolsMessageObserver`, the
  observer interface and `CefRegistration.dispose`), each handle therefore sends its calls through the browser's own
  `executeDevToolsMethod`, whose future yields the message id, and receives answers and events through a
  `CefDevToolsMessageObserver` of its own, registered on first use: it pairs answers with calls itself and keeps an
  answer that arrives before its id (at most 32, for at most 10 s). Registering enables `Page` and `Runtime` and puts
  the listed init scripts (each once to that session) and the viewport override back; the raw channel sends no call
  again; when the browser closes, pending calls fail at once, and `detach()` disposes of the registration, or, while
  the page has not answered a script addition, keeps it until that answer arrives and the script is removed (above).
  The shared client is then only the existence check (`getDevToolsClient()`
  null: no native browser yet, or closed) and carries no call or listener of the agent's; without the `raw` roles it is
  the channel, as the rules above describe, and D62's re-send applies to it alone. No answer has been lost in any run
  since. The fake JCEF covers both channels (§10.2).

**`jxbrowser`** (`…agent.web.jxbrowser.JxBrowserAdapter`), on the public members that lines 7, 8 and 9 share
(browser-spi §F21, §R3), resolved per vendor class loader from the descriptor `jxbrowser.json` (§4.7):

- *Probe.* `com.teamdev.jxbrowser.engine.Engine`, the gate, already loaded by the host, as for JCEF (as built
  2026-10-07, D61; the specification said "loadable from the host loader (no initialisation)"); the F21 members
  resolve (the descriptor's `core` roles); strategy "jxbrowser <line>" from the `Browser`/`Engine` class's package
  version or the vendor's `version.info` resource when readable, else "jxbrowser" (as built a version text in which
  no version number is found gives "jxbrowser (<text>)", which is how the neutral fake reads "jxbrowser (fake)").
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
  readable (browser-spi §F22, §Risk7). (As built 2026-10-07: the expression runs in a sibling function and its result
  comes back as a tagged string; `awaitPromise` is the library's `defer`, then `collect` polled every 5 to 50 ms;
  frame keys are `f<n>`; `web.jxbrowser.chainInjectJs` is read live and is on only for "true" (trimmed, in any case);
  on detach the previous occupant is restored, or the chained callback removed when there was none; an off-screen
  view that is not yet displayable counts as lightweight. The vendor calls run on the adapter's own threads within
  bounds (review 2026-10-07): `evaluate` checks closure and finds its frame inside its own task; the accessors
  `isClosed()`, `url()`, `viewport()`, `rendering()` and `frames()` wait at most 2 s, with at most one vendor call per
  accessor in flight (a hung call is waited for, not repeated); enumeration is bounded at 2 s, one at a time, and on a
  timeout keeps the browsers found before and reports `DEGRADED`; `detach()`'s vendor calls run within its own bound.
  Observer and callback registration (`on`, `set`) and `cdpEndpoints` still call the vendor on the calling worker
  without a bound of their own: whether those members can block needs the product (§13).) (4.x, A37, built 2026-10-08:
  `reload` = `navigation().reload()` / `reloadIgnoringCache()` (the descriptor's `reload` roles) on the adapter's own
  threads, and where those roles do not resolve the SPI's default, `location.reload()` through the page, where the
  handle refused with `UnsupportedOperationException` before; `bitmapSource()` = `VENDOR`.)
- *Rules.* `licenseKey()` is never read; no `set(...)` callback by default; `dispatch(...)` (input) is not used
  (reserved `INPUT`); no DOM API of the vendor is used (browser-spi §F22). (As built: the descriptor has no
  `licenseKey` role at all. Three member names could not be confirmed without the product: `getChromeVersion`
  (alternative `CHROME_VERSION_MAJOR`), `FrameLoadFailed.error` (alternative `errorCode`) and `Browser.set`, matched by
  its parameter count; a licence holder verifies them, the `../TODO.md` wall "A JxBrowser licence".)

### 4.6 The script library (`skillroy-protocol`, `…protocol.web.WebLib`)

One JavaScript source, `web-lib.js`, installed on first use through `evaluate` (in process) or `addInitScript`
(CDP), idempotent, under the non-enumerable slot `window[Symbol.for("skillroy")]`. (As built 2026-10-07: every
expression `WebLib` builds installs the library when it is absent, so either path may evaluate any of them in any
document; the slot is non-enumerable and non-writable but configurable; a library already present, of any version, is
kept, never replaced, so that a tap keeps its state; all state lives in the library's closure; `L.version` is "1".)

The functions as built (each a non-writable property of the library object `L`; the specified table listed
`click`, `fill`, `text` and `dispatch` on elements, a `drain()` without arguments and a library function `wrap`):

| Function | Returns | Used by |
|---|---|---|
| `stringify(value)` | the JSON text of `{json, typeofResult, truncated}`, `json` written with the replacer of §4.2 | the `web.evaluate` wrapper on both paths |
| `aria(root, maxNodes, interestingOnly)` | the `web.ariaSnapshot` value; `root` null (the document's body, not emitted itself), a selector or an element | `web.ariaSnapshot`, the `WEB` surface, the evidence |
| `find(selectorOrRole, name, index)` | the matched element, or `{count, candidates}` (at most 20 candidates, in document order) when none or several match without an index, or the index is out of range; `name` null means a CSS selector | the DOM functions; the CDP path's actionability wait |
| `domClick({selector?, role?, name?, index?, expectVisible})`, `domFill({…, text, clear, enter})`, `domText({selector, maxChars})`, `dispatch({target?, name, detail?})` | the `web.dom.click`, `web.dom.fill`, `web.dom.text` and `web.dispatchEvent` values of §4.2, or an error object; `dispatch(target, name, detail)` is accepted too | those primitives on both paths; `domText` also the `web` fact root |
| `click(el)`, `fill(el, text, clear, enter)`, `text(el, maxChars)` | `{role, name, tag, id?}`, `{tag, type, id?, typed}`, `{text, truncated}` | the same actions on an element in hand (a Playwright element handle) |
| `tap(patterns, target)` | `{installed}`: replaces the tap on `window` (default) or `document`; `tap([])` removes it | `web.tap` |
| `drain(maxChars?)` | `{events: [{name, detail?, truncated?}], dropped}`, removing what it returns from the buffer; with `maxChars`, events up to about that much JSON (at least one) and `more: true` when some remain | the tap pollers of both paths |
| `defer(value)`, `collect(id)` | an id; then the settled state (pending, fulfilled with the wrapper's text, or rejected with `message` and `stack`); at most 100 pending per document | `awaitPromise` over a string-only channel (JxBrowser, §4.5) |

A failure the caller can act on is returned as an error object, `{__error: "NOT_FOUND" | "AMBIGUOUS" | "INVALID_PARAMS",
data}` (the data of §4.2; further `INVALID_PARAMS` reasons name bad arguments, such as `invalid selector`, `invalid
regex` or `a selector or a role is required`); both paths turn it into the RPC error of that code with that data through
`WebLib.errorOf`, and anything thrown is `SCRIPT_ERROR`. `click` is `el.click()` after a visibility check
(`pointerdown`/`mousedown`/`mouseup` are not synthesised: that is `INPUT`); `fill` focuses the element, sets the value
through the setter of the element's own realm, then dispatches `input` (an `InputEvent`) and `change`, both bubbling,
and `Enter` as a `keydown`/`keyup` pair when asked; a `contenteditable` element takes the text as its content. A tap
listens for an exact name with a capturing listener on the target and serves a glob (`app.*`) through an own-property
wrapper of the target's `dispatchEvent` (never the prototype), which removing the tap undoes exactly (a wrapper the page
has chained over stays in its chain, inactive); its buffer holds 1000 events (the oldest dropped and counted), each
`detail` cut at 64 KiB.

**Roles, names and visibility** (as built 2026-10-07; the subset both paths share):

- *Role.* An explicit `role` attribute counts by its first token that is an ARIA 1.2 role (`image` read as `img`;
  unknown and abstract tokens skipped); `none` and `presentation` are ignored on a focusable element or one with
  `aria-label` or `aria-labelledby`. Otherwise the element's implicit role: `a` and `area` with `href` link; `button`
  button; `h1`-`h6` heading (`aria-level` wins; `role=heading` defaults to level 2); `input` by type (`search`
  searchbox, `text`, `email`, `tel`, `url` textbox, either one combobox with a `datalist`; `button`, `submit`,
  `reset`, `image`, `file` button; `checkbox`; `radio`; `range` slider; `number` spinbutton; `hidden` skipped; every
  other type, `password` and `date` included, textbox); `select` combobox, listbox when `multiple` or `size` > 1;
  `textarea` textbox; `img` img (none for `alt=""` without a label); `form` form and `section` region only with an
  author name; `header` banner and `footer` contentinfo unless inside `article`, `aside`, `main`, `nav` or `section`;
  `td` cell (gridcell in a grid), `th` columnheader or rowheader (by `scope`, then by position); `iframe` a leaf named
  by its title; the further implicit roles as `web-lib.js` maps them (lists, tables, landmarks, `dialog`, `option`,
  `progress`, …); every other element, inline formatting included, generic.
- *Name.* `aria-labelledby` (the referenced content, hidden content included when the referenced element is hidden;
  an embedded control contributes its value), then `aria-label`, then the native label (`label` elements; an input
  button's `value`, else "Submit" or "Reset"; `alt`; `legend`; `caption`; `figcaption`; an SVG `title`; an option's
  label), then the content for the roles named from content (button, cell, checkbox, columnheader, gridcell, heading,
  link, menuitem and its checkbox and radio forms, option, radio, row, rowheader, switch, tab, tooltip, treeitem),
  then `title`, then `placeholder`. Names are trimmed, whitespace collapsed, zero-width spaces and soft hyphens
  removed; CSS generated content is not included. `find` matches a name exactly after collapsing both sides, or by
  `new RegExp(regex, ignoreCase ? "i" : "")` against the collapsed name.
- *States.* `value` (when not empty) for textbox, searchbox, spinbutton, combobox, slider, progressbar, meter and
  scrollbar, **never for a password field**; `checked` (true, false or "mixed") for checkbox, radio, switch and the
  checkable menu items; `disabled: true`; `focused: true` on the deep active element; `level` for headings.
- *Text.* Text accumulates across inline content and splits at elements and block boundaries; each non-empty run is
  `{role: "text", name}`, and a text child equal to its parent's non-empty name is dropped. `interestingOnly` (the
  default) drops generic, none and presentation nodes and splices their children in their place; `maxNodes` counts
  every node, text included, and a walk also stops after 100 x `maxNodes` visited elements (`truncated: true` either
  way).
- *Hidden and visible.* Skipped with their subtree: `script`, `style`, `template`, `head` and the like,
  `input type=hidden`, `aria-hidden="true"`, `display: none`, and the content of a closed `details` element except its
  summary; `visibility: hidden` hides an element and its own text but not a child that sets `visible`. Open shadow
  roots are walked, slots replaced by their assigned nodes. `find` by CSS uses `querySelectorAll` (document order,
  light DOM; a hidden match then answers "not visible"); by role it walks the composed tree in pre-order and never
  matches a hidden element. `click` and `fill` need a client rect wider and higher than 0 and a computed visibility
  that is not `hidden` or `collapse`.

The Java side, `WebLib`: `source()`, `hash()`, `SLOT`, `installExpression()`, `callExpression(function, args…)`,
`callJsonExpression(function, args…)` (the value as `stringify` text, for string-only channels),
`wrap(expression, awaitPromise)` (the `web.evaluate` wrapper, in Java: the library itself runs no `eval`),
`tapInstaller(patterns, target)`, `deferExpression(expression)`, `collectExpression(id)`, `errorOf(value)`, and the
constants `FUNCTIONS`, `ERROR_KEY`, `MAX_JSON_CHARS` (1 MiB), `MAX_DETAIL_CHARS` (64 KiB) and `MAX_BUFFERED_EVENTS`
(1000).

(4.x, A35, built 2026-10-08: also `flagExpression(expression)`, the guard of `web.waitFlag` that both paths poll
(§4.2, §5.2, §16.1), `errorLine(text)` and `MAX_ERROR_CHARS` (2000). The guard is one function expression invoked in
place with the expression as its argument, a JSON string and never code of the guard's own, so the guard always
compiles and never throws. It compiles the expression alone, as the body `return (<expression>\n)` of a
`new Function` (trailing semicolons and whitespace dropped as `wrap` drops them; the newline ends a trailing line
comment), calls the result with the window as `this`, and evaluates to `{ready: true}`, `{ready: false}`,
`{ready: false, error: <line>}` when the call threw, or `{ready: false, compileError: <line>}` when compiling threw (a
`SyntaxError`; or the `EvalError` of a page whose content security policy forbids compiling text, where the channel
does not lift that rule for its own evaluations, §16.1). `<line>` is `"<name>: <message>"` for an error object (the
name `Error` when it has none) and `String(value)` for any other thrown value, up to its first CR, LF, U+2028 or
U+2029, cut at 2000 UTF-16 code units without splitting a surrogate pair; `errorLine` applies the same cut in Java to
an error text that does not come from the guard. The guard needs no library and installs none: compiling text is the
guard's, never the library's, whose text calls no `eval` and no `Function`, as the textual checks below require.)

`WebLib.source()` returns the text; `WebLib.hash()` its SHA-256, which both paths record in `run.jsonl` (§8.2) so
that a difference between the two paths can be traced to a library version. Tests (as built 2026-10-07): the JDK has
no JavaScript engine and none may be added, so no test of the normal build runs the library's code, neither in
`skillroy-agent-web` (whose fakes evaluate a fixed table of expressions) nor in `skillroy-cdp` (whose tests start no
browser), where the specification placed library tests. `WebLibTest` in `skillroy-protocol` checks the text: the
resource, a stable hash, the header, one strict function expression invoked in place, no write to a global (a
textual check with a self-test of its own), the constants equal to the library's, every expression builder and
`errorOf`. The behaviour was checked outside the build against jsdom and a Chromium 148 headless shell over CDP, with
byte-identical aria snapshots of the same page; inside the build only the kit against real JCEF and the end-to-end
acceptance (§10.3, §12.2), both under the profile `jcef` with a display, run the library in a browser. (4.x, A35,
built 2026-10-08: `WebLibTest` checks the text of the guard of `web.waitFlag` and `errorLine` as well; the guard
itself ran outside the build in Node 24, in a Chromium headless shell over CDP and under Playwright 1.63, and inside
it only in the end-to-end acceptance's polls, §16.1.)

### 4.7 Descriptors and the reflection plumbing (`…agent.web.reflect`)

Both adapters take their class and member names from a small JSON descriptor resource (`jcef.json`,
`jxbrowser.json`): roles (`browser.url`, `frame.executeJavaScriptAsync`, …) mapped to class name, member name and
parameter types, with an optional list of alternatives per role. `ReflectiveApi.resolve(descriptor, loader)` looks
every role up once per vendor class loader, caches the `Method`/`Field` objects weakly per loader, implements vendor
interfaces with `Proxy` in that loader, and turns a missing optional role into a missing capability (browser-spi
§F22, §R3). The neutral fake of §10.2 is the same code driven by a third descriptor whose classes live under the
project's own package, so that the plumbing is proven in the open without a class in a vendor's namespace
(browser-spi §F28).

As built (2026-10-07). Descriptor format 1 is `{format, adapter, gate, versionResource?, roles}`, where `gate` is the
class whose being loaded by the host means "present" (§3, `probe`). A role has `class`; `method` or `field`, or
neither (the class itself); `params`, or `arity` for a generic signature; `static`; `access` (`public`, or
`private` through `setAccessible`); `interface` (a vendor interface implemented with `Proxy`); `match` (`type`, or
`name`: compared by class name and never loaded by the lookup itself, although linking another looked-up class may
load it); `for`, its group; and `alternatives`, tried in order. A group is `core` (missing: the probe answers
`UNAVAILABLE`), the name of a `WebCapability` (missing: that flag is absent) or a name the adapter interprets (`walk`,
`endpoints`, …); a missing optional role is never an error. `jcef.json` has 32 roles (four of them, the `raw.*` roles of
§4.5, added after the kit's run against real JCEF), `jxbrowser.json` 70 and no role for `licenseKey`; the JSON resources
carry no licence header (§2.3). A vendor failure arrives as a
`ReflectiveException` naming the role, the vendor class and its message. `LoadedClasses` answers "has the host
already loaded this class?" without loading it: through `Instrumentation.getInitiatedClasses` where an
`Instrumentation` exists, else the loader's `findLoadedClass` called reflectively (on Java 8, or when `java.lang` is
open), else, on Java 9 or newer, whether the loader has defined the class's package (a heuristic); `isReachable`
tells "not present" from "not loaded yet". `AdapterThreads`: §2.4.

---

## 5. `skillroy-cdp` (package `ai.skillroy.testjava.cdp`, Java 25)

Playwright Java `1.63.0` (`com.microsoft.playwright:playwright`; Apache-2.0; its `driver-bundle` is about 194 MB,
browser §F17). Browser downloads are never needed: the module sets `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` for the
driver it starts and attaches only (design §6 "Playwright provisioning"). The driver extracts to
`PLAYWRIGHT_BROWSERS_PATH`/`PLAYWRIGHT_DRIVER_TMPDIR` when set, else to the Java temporary directory. (As built
2026-10-07: the driver's environment holds `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` and, when this process has them,
`PLAYWRIGHT_BROWSERS_PATH` and `PLAYWRIGHT_DRIVER_TMPDIR`; Playwright Java 1.63.0 itself reads only the system
property `playwright.driver.tmpdir`, which the module sets from that variable when it is unset. The first
`Playwright.create` in a JVM extracts the driver for the running platform, about 140 MB on Linux x86-64 and 136 MB on
arm64, into a `playwright-java-*` directory there, deleted at the JVM's exit; the extraction counts against the first
attach's deadline, and `PLAYWRIGHT_DRIVER_DIR` names an extracted driver to use instead. The module's one factory is
the only `Playwright.create` call, which a test checks on the class files; with the system property
`skillroy.cdp.noDriver=true`, which the module's own tests set, the factory refuses, so no unit test starts a driver
or a browser.)

### 5.1 API

```java
public record CdpEndpoint(String host, int port) {     // host 127.0.0.1 unless a suite says otherwise (null, blank)
    public URI httpUri();  public static CdpEndpoint local(int port);  public String authority();   // port 1..65535
}
/** connectDeadline: the external deadline of the attach, the driver's start included (CdpWebPath passes the
 *  scenario's WebSettings.attachDeadline(), §6.1); artifactsDir: where Playwright records a trace (CdpWebPath passes a
 *  private temporary directory of its own when the scenario traces, never a run's output directory, §6.4; null lets
 *  Playwright use one of its own, deleted when it disconnects). As built: deadlines of at least 1 ms, the probe's at
 *  most 60 s and the connect's at most 10 min. */
public record CdpOptions(Duration probeDeadlinePerTarget /*2 s*/, Duration connectDeadline /*30 s*/,
                         boolean noDefaults /*true*/, boolean isLocal /*true*/, Path artifactsDir /*null*/,
                         FrozenTargetPolicy frozenTargets /*FAIL*/) {
    public static CdpOptions defaults();      /* with…() */
}
// FrozenTargetPolicy { FAIL, RELOAD, CLOSE }: what to do with a page target that does not answer the probe. As built
// it lives in …scenario.web beside WebSettings (§6.1), and this module imports it.

/** GET /json/version and /json/list, then Runtime.evaluate("1") over each page target's WebSocket
 *  (java.net.http.WebSocket), each under probeDeadlinePerTarget, all targets in parallel on virtual threads. Never
 *  hangs: a target that does not answer is reported, not awaited (browser §F9, §C5). As built the whole probe ends
 *  within three deadlines (the two requests, then the targets) and 500 ms; a /json request's own timeout is 250 ms
 *  longer than its caller's wait, and either way it reads "no answer within <n> ms"; given the target ids the agent
 *  reports (CdpWebPath.open, §6.1), a /json/list that names none of them ends the probe before any page is probed. */
public final class TargetProbe {
    public static TargetProbe run(CdpEndpoint endpoint, CdpOptions options) throws CdpException;
                                   // CdpException(UNREACHABLE) when /json/version fails (refused, timed out, not 200,
                                   // not a JSON object); a /json/list that is not 200 or not an array is PROTOCOL
    public record Target(String id, String type, String url, String title, String webSocketDebuggerUrl,
                         boolean responsive, long millis, String error) { boolean isPage(); Map summary(); }
    public String browserVersion();  public String protocolVersion();  public List<Target> targets();
    public List<Target> unresponsive();  public List<Target> pages();  public CdpEndpoint endpoint();
    public long millis();
}

/** connectOverCDP under an external deadline on a worker thread; the Playwright instance is closed when the deadline
 *  passes, which ends the driver and with it the hung connect (browser §F9). Pages come from
 *  contexts().get(0).pages(); never newContext() or newPage() (browser §C4). As built the worker is the attach's one
 *  platform thread, skillroy-cdp-playwright, which runs every Playwright call of the attach (§2.4); a probe that found
 *  a frozen page target is refused with FROZEN_TARGETS before anything starts, whatever the policy (recovery needs
 *  the agent, §5.3); Playwright's TimeoutError is TIMEOUT, a refused, reset, unknown or unreachable host is
 *  UNREACHABLE, anything else (no context included) PROTOCOL. */
public final class CdpAttach implements AutoCloseable {
    public static CdpAttach connect(CdpEndpoint endpoint, CdpOptions options, TargetProbe probe) throws CdpException;
    public TargetProbe probe();  public CdpOptions options();
    public Browser browser();  public BrowserContext context();
    /** The page whose Target.getTargetInfo (through a CDPSession; as built Page.getFrameTree when that fails) reports
     *  targetId; null when none. Mapping is cached per page and refreshed when the page list changes. */
    public Page pageFor(String targetId);
    public Map<String, Page> pagesByTargetId();
    public CdpTracing tracing();      // start(name), stop(path), isActive(): context tracing into artifactsDir
                                      // (browser §F18)
    public long connectMillis();      // Playwright's start plus connectOverCDP
    public boolean isClosed();
    @Override public void close();    // idempotent; never throws; closes Playwright, never the browser
}
public final class CdpException extends Exception {
    public enum Kind { UNREACHABLE, TIMEOUT, FROZEN_TARGETS, PROTOCOL }
    public Kind kind();  public List<TargetProbe.Target> targets();
}
```

### 5.2 `CdpWebPath`: the CDP implementation of the web verbs

`skillroy-cdp` registers `…cdp.CdpWebPath` through `META-INF/services/ai.skillroy.testjava.scenario.web.WebPath`.
It serves every `web.*` primitive of §4.2 except `web.cdp.status`, `web.reload` and `web.browsers` (those are always
the agent's: enumeration and endpoints are facts of the target), with the same parameters and result shapes:

| Primitive | Playwright implementation |
|---|---|
| `web.evaluate` | the text of `WebLib.wrap(expression, awaitPromise)` on the page, or on the frame a frame key maps to (below), evaluated through an indirect `eval` inside a page-side `try`/`catch`, so that a thrown error and a syntax error in the expression are both `SCRIPT_ERROR`, caught in the page rather than parsed from Playwright's messages (as built 2026-10-07; the specification wrote `page.evaluate(WebLib.wrap(expression))`, with frames mapped through `Page.getFrameTree` ids = Playwright's frame ids) |
| `web.ariaSnapshot`, `web.dom.text`, `web.dom.click`, `web.dom.fill`, `web.dispatchEvent` | the same library functions through `page.evaluate` / `frame.evaluate`, so that both paths return byte-identical shapes for the same page state. `web.dom.click` and `web.dom.fill` additionally wait for Playwright's actionability of the matched element (`locator.waitFor(VISIBLE)`, bounded by the call's budget) before the library acts, and record `fidelity: "DOM"` as the agent does. As built the locator is `frame.locator("css=" + selector)` or `getByRole(role, {name, exact})` (a `Pattern` for a regular-expression name), `.nth(index)`, then `waitFor(VISIBLE)`, or `ATTACHED` with `expectVisible: false`, bounded by the budget less a reserve of at most 1 s; a wait that times out, a strict-mode violation or a selector Playwright refuses is not an error: the library then answers in its own terms |
| `web.tap` | the selected browser's page only, as the agent taps one browser (as built 2026-10-07; the specification tapped the whole context with `context.addInitScript`): `page.addInitScript(WebLib.tapInstaller(patterns, target))` plus the same tap in the page's current document (`timing: DOCUMENT_START`); `patterns: []` removes both. No `page.exposeBinding`: the path's poller drains the tapped pages every 100 ms from `open` to `close` and publishes nothing to the agent's bus; instead the executor's event wait of §6.3 reads the path's own event list. §4.3's rule of one outstanding drain holds (review 2026-10-07, D63): the drain operation itself appends the events it took (`web.tap`, then `web.ready {signal: TAP}`) to the path's events, also after its 1 s bound, and no further drain, nor any other operation, is sent to the attach meanwhile |
| `web.waitFlag` | `frame.waitForFunction(<the expression in a page-side guard>, null, new WaitForFunctionOptions().setPollingInterval(pollMs).setTimeout(…))` on the selected frame, the timeout being `timeoutMs` or the budget, at most what is left less 250 ms (as built; the specification wrote `page.waitForFunction(expression, …)`); `polls` is `1 + ms / pollMs` (Playwright does not count them); a throwing expression was `SCRIPT_ERROR` until A35; a flag that turns ready appends `web.ready {signal: FLAG}` to the path's events. (4.x, A35, built 2026-10-08: the page-side guard is the agent's, `WebLib.flagExpression` of §4.6, in a wrapper (`Scripts.flagWait`) that returns the guard's object when the flag is ready or the expression did not compile, both of which end the wait, and `false` otherwise, so that Playwright polls on through a poll that threw; a `compileError` is `SCRIPT_ERROR` at once with `data {browser, message, stack: ""}`; after a wait that timed out, the path makes one more poll, the last, with the guard itself through `waitForFunction` (its first evaluation ends the call; `evaluate` has no bound of its own), bounded by 1000 ms and by what is left of the call less 100 ms: its `error` is the result's `lastError`, a `ready: true` makes the flag ready after all, and a last poll that does not answer in time, or fails otherwise, leaves `lastError` out (a page that closed is `STALE_HANDLE`); one operation is outstanding at a time, D63; a blank expression is `INVALID_PARAMS`; §16.1) |
| `web.resize` | `Emulation.setDeviceMetricsOverride {width, height, deviceScaleFactor: 0, mobile: false}` on the path's own `CDPSession` of the page, the in-process adapter's protocol call; `clear: true` sends `Emulation.clearDeviceMetricsOverride` on that same session and reads the size back (`viewport` omitted when unreadable); `close()` removes every override the path left in place (as built 2026-10-07 after the review, D64: the specified `page.setViewportSize(width, height)` cannot be removed, so `clear: true` pinned the original size, Chromium ignores a removal sent on another session, and an override outlives the session and the connection that set it) |
| `web.bitmap` | `page.screenshot()` (`source: "PROTOCOL"`); as built scaled above `maxWidth` from the PNG itself, without a `Graphics2D` |

Browser selection by `browser: <ref>` is mapped through `web.browsers` of the agent (`cdpTargetId` per ref) to a
Playwright page; `by: {url|title}` is matched on the pages' own `url()`/`title()`; a ref without a target id (the
adapter has no `CDP_TARGET_ID`) makes the CDP path answer `UNSUPPORTED` for that call and the executor falls back to
the agent path for it, recorded (§6.1). (As built 2026-10-07: refs and target ids come from the agent's
`web.browsers`, kept per ref and read again for an unknown ref; a regular expression is found anywhere in the text, as
on the agent; `by: {tag|nativeId}` uses the agent's list; with neither, the one browser `visibleInSwing`; whatever
cannot pick exactly one page (none, several, a target without a page, a bad pattern) is `UNSUPPORTED`, so that the
agent answers with its own `NOT_FOUND` or `AMBIGUOUS` data. A frame key is looked up in the page's
`Page.getFrameTree`: the root is the main frame, and a child maps to the one Playwright frame with the same URL and
name; an unknown or ambiguous key is `UNSUPPORTED`.)

**Calls the path hands to the agent** (as built 2026-10-07; each an `UNSUPPORTED` that the executor records as a
`web.fallback`, §6.1): a call that names secret parameters (`CallOptions.secretParams()`: the agent redacts them in its
records and can mask a console echo only of a call it saw, while a Playwright trace or a driver debug log would carry
the value; D68); a mutating primitive (D51) while the target is in read-only mode; any call while an earlier operation
of the path has not returned (a frozen page: the run goes on with the agent instead of queueing behind it); any call
before `open` or after `close`.

**Errors, sequence numbers, events** (as built 2026-10-07). A call's failures are `AgentRpcException`s with the
agent's codes: a library error object as its code with its data, a thrown script as `SCRIPT_ERROR {browser, message,
stack}` (`message` the first line of the error's description, at most 2000 characters, `stack` at most 8000; (4.x, A35,
built 2026-10-08) a poll of `web.waitFlag` that throws is no error but a poll that is not ready, and only an expression
that does not compile is its `SCRIPT_ERROR`, above), the call's budget passing as `TIMEOUT`, a page that closed as
`STALE_HANDLE` ("the browser closed during the step"), any other Playwright failure as `INTERNAL_ERROR`; parameters are
checked before the agent is asked anything (`INVALID_PARAMS` with `data.param`). The budget is
`CallOptions.timeoutMs()`, else the default of §4.2, at most 120 s (600 s for `web.waitFlag`). A read whose execution
context a navigation destroyed is evaluated once more; a mutating call never is. Argument values are never logged. A
result's `seqBefore` and `seqAfter` were the agent's `lastSeq`, read with `evt.query` just before and after the
primitive, and its `events` list is empty; the path's own events (`web.tap`, `web.ready` with `TAP`, `FLAG` or `LOAD`,
and `web.load`, which the path publishes from Playwright's page `load` and main-frame `framenavigated` events) are
numbered by a sequence of their own and read with `eventsSince` (§6.3). The executor reads the agent's sequence itself
as well (§6.3), so a call the path served made three `evt.query` calls, the path's two a redundancy. (4.x, A36, built
2026-10-08: the path reads no agent sequence; `seqBefore` and `seqAfter` are 0 (`CdpWebPath.NOT_READ`), and the
executor writes the sequence it read before the call in place of both, §6.3, §16.2; a served call makes one
`evt.query`, the executor's, and an agent whose `evt.query` fails no longer makes the path refuse a call.)

### 5.3 Frozen targets

A target that does not answer the probe within `probeDeadlinePerTarget` is **frozen**. `FrozenTargetPolicy.FAIL`
(default) ends the attach with `CdpException(FROZEN_TARGETS)` naming each frozen target (id, url, title); the executor
reports `ERROR` / `ENVIRONMENT` with that list in the detail and does not try the agent path for the scenario (the
scenario asked for CDP, §7.2). (As built 2026-10-07: `open()` throws it as `WebPathOpenException(FROZEN_TARGETS)`
with `detail.frozenTargets`; that is the outcome under `web.path: cdp`, while under `auto` the run takes the agent
path and records the reason, §6.1.) `RELOAD` and `CLOSE` ask the agent, through `web.reload {browser}` or
`web.close`-less equivalent (`CLOSE` is not built in Phase 4: `web.close` would end a browser the application owns;
the policy value is parsed and refused with a message, D52), to recover the target before a second probe, once; the
run records a `web.recover` line (§8.2). Only a scenario can turn the policy on (`web: {frozenTargets: reload}`,
§7.2); nothing recovers by default, because a reload loses the page's state and the application did not ask for it.

As built (2026-10-07). `CLOSE` is refused by `open()` before it asks the agent anything, by the scenario loader and by
the plan reader (§7.2, §7.3). `CdpAttach.connect` refuses a probe with a frozen target whatever the policy: recovery
needs the agent, so it is the path's. `RELOAD` reads the agent's `web.browsers` and takes, for each frozen target, the
browser whose (kept, §4.2) `cdpTargetId` is the target's; failing that, the one open browser without a known target id
whose URL is the target's; failing that, for a single frozen target, the only open browser without a known target id (a
frozen page cannot answer `Page.getFrameTree`, so its target id may be unknown exactly when it is needed). Then
`web.reload {browser}` (10 s), a `web.recover` record `{browser, action: "reload", targetId, matchedBy, ok, reason?}`
through the run's context, and a bounded wait (at most 10 s, never past the attach deadline) for the agent's `web.ready
{browser, signal: LOAD}` after the reload; after all frozen targets, one more probe, and a target still frozen is
`FROZEN_TARGETS`. When no browser matches, the record has `browser: null`, `ok: false` and the reason, and nothing is
reloaded.

What `RELOAD` recovers (as run 2026-10-07, §12.2 item 6). A page that stalls, busy in chunks with zero-delay yields
between them, is recovered: Chromium takes the reload at the next yield, and `open` succeeded after one `web.recover` in
8 to 9 s. A page in an endless loop is not: the agent's `web.reload` is sent, but Chromium does not commit it into a
renderer whose main thread never yields, so no load follows, the second probe still finds the target frozen, and `open`
ends with `FROZEN_TARGETS` after about 18 s. A busy renderer serves no new DevTools session either (a probe, Playwright,
or a `Runtime.terminateExecution` sent over the port), so nothing short of ending the application thaws such a page. The
acceptance starts both conditions by evaluation in the page, because the web host's `#freeze` and `#stall` fragments
cannot be reached on its `data:` pages (§9.1).

### 5.4 Readiness and attach timing

`CdpAttach.connect` runs on a platform worker thread with `connectDeadline`; `connectMillis()` is recorded in
`run.jsonl` (`cdp` record, §8.2). The exit criterion of the roadmap ("over 50 runs, attach to a running Chromium with
a deliberately frozen page target completes or aborts within the external deadline") is the test `CdpAttachSoakIT`
of §12.2 item 6. (As built 2026-10-07: `connectDeadline` covers the driver's start and, in a fresh JVM, its
extraction (§5); `connectMillis()`, Playwright's start plus `connectOverCDP`, is the `cdp.connectMs` of the
`web.path` record; `CdpAttachSoakIT` is not in `skillroy-cdp`, whose tests start no browser: it needs the web host and
belongs with its end-to-end acceptance, §12.2.)

---

## 6. The controller side: two paths, facts, surface, evidence

### 6.1 `WebPath` (package `…scenario.web`) and path selection

```java
/** One implementation of the web verbs: the agent's (built in) or a controller-side one found by ServiceLoader. */
public interface WebPath {
    String id();                                                   // "agent" | "cdp"
    /** Prepares the path for a run against this target: the CDP path probes and attaches here (once per run; the
     *  attach is kept for the run and closed in close()). Returns null when the path is usable, else the reason it is
     *  not (recorded; not an error unless the scenario pinned the path). Throws WebPathOpenException when the attach
     *  itself failed (unreachable, timed out, a protocol error, frozen targets). */
    String open(AgentConnection agent, WebSettings settings, WebRunContext ctx) throws Exception;
    /** What the web.path record says of a successful open (CDP: endpoint, browserVersion, connectMs, targets,
     *  unresponsive); null by default (the agent path). */
    default Map<String, Object> openInfo() { return null; }
    boolean serves(String primitive);
    /** The call's result in the shape the agent would give (value, events[], seq, durationMs, thread="controller"), or
     *  throws WebPathException(UNSUPPORTED) when this call cannot be served (the executor then uses the agent path and
     *  records it) or AgentRpcException with the agent's error codes for the failures of §8.1. */
    RpcResult call(String primitive, Map<String, Object> args, CallOptions options) throws Exception;
    /** Events the path saw since seq (web.ready, web.tap, web.load of the CDP path, which never reach the agent's
     *  bus). */
    List<Event> eventsSince(long seq, String topics);
    /** Writes the path's trace to the file when there is one, else nothing (§6.4); the default does nothing. */
    default void saveTrace(java.nio.file.Path target) throws Exception { }
    void close();                                                  // idempotent; never throws
}
public record WebSettings(Path path /*AUTO|CDP|AGENT*/, Map<String,Object> defaultBrowser /*the by: object, or null*/,
                          FrozenTargetPolicy frozenTargets /*FAIL*/, Duration attachDeadline /*30 s*/,
                          boolean trace /*false*/) {
    public static final WebSettings DEFAULT;  public enum Path { AUTO, CDP, AGENT }
}
public enum FrozenTargetPolicy { FAIL, RELOAD, CLOSE }             // §5.3; CLOSE is refused in Phase 4 (D52)
public final class WebPathException extends Exception {           // one call this path cannot serve
    public enum Kind { UNSUPPORTED }  public Kind kind();  public static WebPathException unsupported(String reason);
}
public final class WebPathOpenException extends Exception {       // open(): the attach itself failed
    public enum Kind { UNREACHABLE, TIMEOUT, PROTOCOL, FROZEN_TARGETS }
    public Kind kind();  public Map<String, Object> detail();      // FROZEN_TARGETS: frozenTargets [{id, url, title}]
}
/** What a path may know about the run it is opened for; implemented by the executor. */
public interface WebRunContext {
    /** The run's output directory, or null. A path writes there only what the executor asks for (the file of
     *  saveTrace), never a recording of the pages as the run goes (§6.4). */
    java.nio.file.Path artifactsDir();
    void record(Map<String, Object> record);                       // appends one record to run.jsonl (web.recover)
}
```

(As built 2026-10-07: the specification's `RunContext` is `WebRunContext`; `openInfo()`, `saveTrace(Path)`,
`WebSettings.trace` and `WebPathOpenException` were added during the build, and `FrozenTargetPolicy` lives here, not
in `skillroy-cdp`. `AgentWebPath`, id `agent`, is public in the same package: it forwards every `web.*` call to the
agent, has no events of its own, and its `open` returns null.) (4.x, A36, built 2026-10-08: a non-agent path need not
read the agent's sequence for the `seq` of its result; the CDP path leaves both numbers 0, and the executor writes the
sequence it read before the call in place of both, §6.3, §16.2. `WebPath.call` keeps its signature.)

Selection per run (step 1a of the run sequence, P1 §8.5, after the capability check and before pre-flight), only
when the plan has at least one `web.*` call:

| `web.path` | the executor |
|---|---|
| `AUTO` (default) | uses the CDP path when a `WebPath` with id `cdp` is on the class path **and** the target's `web.cdp` capability is `OK` **and** `open()` returns null; otherwise the agent path. The choice and its reason are recorded once (`web.path` record). As built `DEGRADED` does not count as `OK`, and a reason from `open()`, a `WebPathOpenException` and any other exception it throws are all reasons. `open()` refuses an endpoint that serves none of the target's browsers: when the agent's `web.cdp.status` names CDP target ids and the endpoint's `/json/list` names none of them, it returns "the endpoint at <host:port> serves none of the target's browsers" before any page is probed (no id reported: no check; review 2026-10-07, D65) |
| `CDP` | as `AUTO`, but a CDP path that is absent (as built also one that cannot be loaded), a `web.cdp` capability that is not `OK`, or an `open()` that returns a reason is `ERROR` / `PRECONDITION_FAILED` naming the reason; no step runs. As built 2026-10-07: an `open()` that throws `WebPathOpenException` (the attach itself failed) is `ERROR` / `ENVIRONMENT`, with its detail merged into the run's detail (so `frozenTargets` appears) and `attach: <KIND>`, and any other exception from `open()` is `ERROR` / `ENVIRONMENT` as well; a `web.cdp` that is `UNAVAILABLE` fails the `requires` check first, because the compiler added it to `requires` (§7.2) |
| `AGENT` | the agent path, whatever the target offers |

Per call: the chosen path's `call` for the primitives it `serves`; `UNSUPPORTED` falls back to the agent path for that
call and is recorded (`web.fallback` record). Every `web.*` call's `run.jsonl` `call` record carries `path: "cdp" |
"agent"` (§8.2). A scenario can pin a browser for every web verb with `web.browser` (the `by:` object, §7.2); a verb's
own `browser:`/`by:` wins.

As built (2026-10-07). *Finding the path:* `ServiceLoader<WebPath>` through the class loader that loads the run's domain
packs, given by the constructor `PlanExecutor(pack, agent, options, ClassLoader)` (the CLI passes its packs' loader, the
JUnit glue the loader it finds packs with); the three-argument constructor uses the thread's context class loader when
the run starts, else `WebPath`'s own loader. Each lookup makes fresh instances (a path's constructor must hold nothing),
only id `cdp` is considered, and a provider that cannot be loaded is a reason, not a crash. *Step 1a* runs after the
`requires` and launch-property checks and before pre-flight, only for a plan with a `web.*` call, and never in
`resolve`; a path that was opened but not chosen is closed at once. The open path lives in the run's `WebRun`, closed
after cleanup and recover (a failing close is a `web.close` record, never thrown). *Each call:* an `AgentRpcException`
from the path is mapped exactly as the agent's would be; any other failure of a non-agent path is `ERROR` /
`ENVIRONMENT`; cleanup calls go through the path too, the removal of a tap included. *Facts:* every fact provider, the
base `web` root and a pack's alike, reads through a connection of the run that sends `web.*` calls to the run's path
(with the fall-back rules) and everything else to the agent; pre-flight, the binder, prompt policies and `recover` use
the plain agent connection.

(Phase 5, built 2026-10-08; P5 §12.2) `ExecutorSession.open` opens the web path as a run does, with
this section's selection: `SessionOptions.web` carries the settings that the scenario's `web` key carries, and
`SessionOptions.webPathLoader` is the class loader that finds the `WebPath` providers. `close` closes the path.

(Phase 6, built 2026-10-09, CP; P6 §5.3, §6.4) Before the path, `ExecutorSession.open` installs the pack's hooks under
`SessionOptions.hooks` (an eleventh component, default `AUTO`; the ten-argument constructor kept) as a run's step 0b
does (a session is live, so an armed stub is recorded, not refused; a refusal is a `SessionOpenException` with the
installer's kind), reads the matrix again when something was installed or found installed, and writes
`capabilities.json` into its output directory; the `hooks` record goes into the journal's start record under
`options.hooks` and is `ExecutorSession.hooks()`. As built: the contract named `RunOptions.hooks` for the session, which
has no `RunOptions`; the switch is `SessionOptions.hooks` (D-CP-2).

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
surface. (As built 2026-10-07: one 10 s budget covers the whole read; a `text` read that fails, or no longer fits the
budget, is left out, logged once per browser and reason and written as a `fact` record (§8.2); a failing
`web.browsers` fails the read; the root reads through the run's path, §6.1.)

`UiSnapshotText` renders a `WEB` surface after the Swing windows: one block per visible browser (`[web <ref>]
<title> <url> (<adapter>, <rendering>, <path>)` followed by the aria nodes of `web.ariaSnapshot {maxNodes: 200,
interestingOnly: true}` indented one level per depth, `role "name" [value]` per line), within the existing 4k-token
cap (design §2.5). Hidden browsers appear as one line each (`[web <ref> hidden] <title> <url>`). (As built
2026-10-07: the cap is the snapshot text's existing budget of 4000 characters; `UiSnapshotText.render(snapshot,
appState, List<WebView>, maxChars)` with the record `UiSnapshotText.WebView(browser, aria, path)`; a level is two
spaces; titles and URLs are cut at 80 characters (the web host's `data:` URLs are about 8.2 KB); the aria snapshot is
read through the run's path, only when `web.browsers` is present, not `UNAVAILABLE`, and lists a browser.)

### 6.3 Step protocol

Unchanged in shape (design §2.5; P1 §8.5). `web.ready` is already named among the ready topics there; from Phase 4
it is published (§4.3). For the base web verbs:

- `webClick`, `webFill`, `webDispatch` compile to `SETTLE` calls by default (the agent's `swing.settle` still runs,
  because a page action may open a Swing dialog), with the optional `await:` of A32 (an event on `web.ready`,
  `web.tap`, `web.load` or any topic, with the A32 match rules) for pages that signal completion. (As built
  2026-10-07, exactly as A32: the primitive goes with sync `EVENT` and any `capture`, then a `swing.settle
  {timeoutMs: min(step, 118000)}` call with sync `NONE` and a call timeout 2000 ms longer, whose `settled: false` is
  `SYNC_TIMEOUT`; `webResize` compiles to `SETTLE` and takes `await:` too; `webTap` compiles to `NONE`.)
- `webWaitFlag` compiles to a `web.waitFlag` call with `sync: NONE` (the primitive itself waits, on the agent or
  on the CDP path). (As built: with `timeoutMs` the call's timeout is one second longer, without it the primitive waits
  for the step's timeout; a result that is not `ready: true` is `FAIL` / `SYNC_TIMEOUT`, §8.1.) (4.x, A35, built
  2026-10-08: alike on both paths, a flag whose expression throws on every poll included: the failure's detail carries
  the result as `waitFlag`, `lastError` included, and the message ends with the last error; an expression that does
  not compile is `SCRIPT_ERROR`, `FAIL` / `ASSERTION`; §16.1.)
- on the CDP path, waits on `web.*` topics are served by `WebPath.eventsSince` (the path's own events) **and** the
  agent's bus; the first matching event from either source ends the wait, so a scenario does not know which path ran.

As built (2026-10-07). While a non-agent path serves the run, a wait whose topics can match one of the six `web.*`
topics (`EVENT`, `ACK` and `READY` waits, and `waitFor`) reads the path's `eventsSince` and `agent.await` in turn, in
100 ms slices of the agent's wait (no sleeper; bounded by the clock), and matches by text as the agent does. Before a
call the path serves, the executor reads the agent's `lastSeq` (`evt.query`) and the highest sequence number of the
path's events, and a wait after the call starts from both; the sequence numbers in a non-agent path's result are
never used, and only the agent's are noted as seen. (4.x, A36, built 2026-10-08: that read is the only one, the path
reading none (§5.2); its number replaces both sequence numbers of the path's result before the result is used or
recorded, so that the `call` record's `seq` is `{before: n, after: n}` in the agent's numbering; a read the executor
makes on its own through the path (a fact, the surface, evidence) reads no sequence and takes the highest one the run
has seen, as before; §16.2.) The `sync` record gains `pathSince` and `source` (`cdp` or
`agent`), and its `eventSeq` is in the path's numbering when `source` is the path; a `waitFor` that a path event
ended is a `call` record with `eventSource` and thread `controller`. On the agent path waits are unchanged.

### 6.4 Evidence on failure

Beside the Phase 1 evidence (`snapshot`, `capture`, `events`, `app-state`, `dialogs`), a failed scenario whose target
has at least one indexed browser gets, under `failure/`: `web-browsers.json` (the `web.browsers` value),
`web-<ref>-aria.json` and `web-<ref>.png` (`web.ariaSnapshot {maxNodes: 2000}` and `web.bitmap`) for each visible
browser, at most four, in ref order; a browser whose read fails gets a `web-<ref>-error.txt` with the error code. The
CDP path's `trace.zip` goes to `failure/web-trace.zip` when `web.trace: true` was set (§7.2; off by default, because
tracing costs several seconds per run). `RunOptions.captureOnFailure` governs the PNGs as it does the Swing capture.
(As built 2026-10-07: "visible" is visible in Swing, and ref order is numeric, `h2` before `h10`; refs are made safe
for file names; the error file has a line `<primitive>: <CODE> (<code>): <message>` per failed read; the reads go
through the run's path with its fall-back; the web evidence is written after the Phase 1 evidence, none on success
and none for a target without browsers; earlier `failure/web-*` files are removed when a run starts; each web
`evidence` record carries `path`; `captureOnFailure` governs all the evidence, web and Swing.)

**The trace** (as built 2026-10-07 after the review, D59). A trace is saved only when the scenario failed with
`web.trace: true`, steps had started, `captureOnFailure` is on, an output directory is set and a path was opened. The
CDP path records its trace into a private temporary directory of its own (`skillroy-cdp-trace-*`, owner-only; **Phase
6, built 2026-10-09, BW (P6 §9.3, D138):** made through the controller's public `SecureFiles.createOwnerOnlyTempDirectory`,
POSIX permissions or an owner-only ACL and read back, and where neither can be had `open` fails, which the executor
reports as `ENVIRONMENT` (the agent path under `auto`), where it used to fall back to an untraced run or to a plain
temporary directory), never
into the run's output directory, and deletes it when the path closes, and when `open` fails after creating it;
`failure/web-trace.zip` is the only trace file a run keeps. When the scenario sets `web.trace`, the executor decides at
step 1a, before the path opens, from the plan (and the caller's bindings), whether a step or cleanup call may announce
a secret parameter (A34). The rule is that of the call's `secretParams`, judged on what is known before the bind: a
value that only the bind produces counts as secret wherever a reference reaches it. If so, the executor opens the path
with `trace: false` and, once a non-agent path is open, records `web.trace {skipped: true, reason: "secrets"}` (with
`error` when the decision itself failed, which withholds the trace as well). At failure time a trace is still not kept
when a call announced a secret all the same (`{skipped: true, reason: "secrets"}` then). `failure/web-trace.zip` is
recorded as saved, `{ok: true, file}`, only when the path wrote a non-empty file; otherwise the record is
`{ok: false, reason}` (the path wrote no trace) or `{ok: false, error}`. Reason: Playwright appends the live trace
(page snapshots with the values of input fields, console text) to files in the directory it is given, never deletes
them and never passes them through the run's secret mask (§8.3).

---

## 7. Scenario YAML and `plan.json` version 4

### 7.1 Base verbs

| Verb | Arguments | Compiles to |
|---|---|---|
| `webClick` | `selector: <css>` **or** `role: <aria role>` + `name: <text or {regex}>`; `index?`, `expectVisible?` (as built), `browser?: <by object>`, `frame?`, `await?` (A32) | `web.dom.click` (`SETTLE`, or `EVENT` with `await`) |
| `webFill` | as `webClick` plus `text: <string or $ref>`, `clear?: bool`, `enter?: bool` | `web.dom.fill` |
| `webDispatch` | `name: <event>`, `detail?: <any, $refs allowed>`, `target?: <css>`, `browser?`, `frame?`, `await?` | `web.dispatchEvent` |
| `webWaitFlag` | `expression: <js>`, `timeoutMs?` (as built at most 119000), `pollMs?` (as built, at least 20), `browser?`, `frame?` | `web.waitFlag` (`NONE`) |
| `webResize` | `width`, `height` (1 to 16384) **or** `clear: true`, `browser?`, `await?` (as built) | `web.resize` (`SETTLE`) |
| `webTap` | `patterns: [<event name or glob>]`, `target?` (`window` or `document`, as built), `browser?`; usually in `setup` | `web.tap` (`NONE`); `ctx.addCleanup` removes it (`web.tap {patterns: []}`, on the same browser and target) |

A `text` of `webFill` that comes from a joined credential field carries `_meta.secretParams: ["text"]` (A34) as
`type` does. `role` values are the ARIA role names (`button`, `textbox`, `link`, `checkbox`, `combobox`, `heading`,
`cell`, …); `name` matching follows the library's accessible-name computation, exact by default, `{regex}` for a
pattern, case-insensitive with `{regex, ignoreCase: true}`. The verbs are base verbs in the sense of P1 §8.1: a pack
may override them.

As built (2026-10-07): `frame` is accepted on `webClick`, `webFill`, `webDispatch` and `webWaitFlag` only; the
further arguments (`expectVisible`, `index` with a `selector`, `pollMs`, `target` on `webTap`) map one to one onto the
parameters of §4.2; `index`, `width`, `height`, `pollMs`, `timeoutMs` and the flags take literal values only; a number
given as `webFill`'s `text` is sent as its text, as `type` does; `name` patterns are not checked at compile time (they
run in the page), while the URL and title patterns of a `browser` object are checked as Java regular expressions
unless they hold a `$reference`. `webWaitFlag.timeoutMs` is at most 119000 because the agent caps every call at
120 s: a larger value is a compile error ("webWaitFlag.timeoutMs must be at most 119000 ms (the agent caps a call at
120 s), found <n> ms"), not a wait cut into several calls. A step's `browser:` (exactly one of `{url}`, `{tag}`,
`{title}`, `{nativeId}`) becomes the primitive's `by`; without one, the scenario's `web.browser` is used. A step whose
verb is a base web verb (by name, also when a pack overrides it) adds `web.browsers` and `web.evaluate` to
`plan.requires` (§7.2).

### 7.2 The scenario key `web`

```yaml
web:
  path: auto | cdp | agent          # default auto (§6.1)
  browser: {url: "^app://"}          # default browser for every web verb; a verb's own browser/by wins
  frozenTargets: fail | reload       # default fail (§5.3); "close" is refused with a message in Phase 4
  trace: false                       # CDP tracing into failure/web-trace.zip on failure; never kept when a call may
                                     # carry a secret (§6.4)
```

A scenario that drives web views over CDP lists `web.cdp` under `requires:` (design §3.8); with `path: cdp` the
compiler adds it when missing (a compile-time note, not an error). Every scenario with a web verb gets `web.evaluate`
added to `plan.requires` by the compiler (the agent path needs it; the CDP path still needs `web.browsers` for the
mapping, so `web.browsers` is added too). (As built 2026-10-07: the specification said "the executor adds it"; the
compiler does, also for a scenario without a web verb, with the note "web.path is cdp: the capability web.cdp was
added to requires (list it there to say so in the scenario)". `web` is the fourteenth top-level key; its values are
accepted in any case; `browser` holds exactly one of `url`, `tag`, `title` and `nativeId`; `frozenTargets: close` is
refused with a message that names Phase 4 and D52. `PlanCompiler.compileWithNotes` returns the plan and its notes,
`skillroy compile` prints them on standard error (and as `notes` with `--json`), and `CompileContext.web()` gives a
pack the scenario's settings, `WebSettings.DEFAULT` by default.)

### 7.3 `plan.json` version 4

New: the `web` object of §7.2 (omitted when absent in the YAML, as `ui` is), the calls of the new verbs. The reader
accepts versions 1 to 4; a version 3 plan reads as version 4 without `web`. The compiler always writes 4 (P2 D18:
one writer, one format). Byte-identical recompilation and the `planHash` rules are unchanged; `resolveHash` is
unchanged (nothing in `web` resolves). Java shapes: `Plan` gains a last component `WebSettings web`, the Phase 3
constructors stay (the A29 precedent for `ui`); `Scenario` likewise. (As built 2026-10-07: `Plan.web()` and
`Scenario.web()` are null when the YAML has no `web` key, as `ui` is, and `effectiveWeb()` answers
`WebSettings.DEFAULT` then, where the specification made `DEFAULT` the component's default; `PlanExecutor.Resolved`
gains `web()`. The object is written after `ui` and before `bindTimeoutMs`, as `{path, browser?, frozenTargets,
trace}` in lower case. The reader refuses `web` in a plan below version 4 ("invalid plan: 'web' needs a version 4
plan"), checks a hand-edited `web` as the loader checks the YAML, and refuses any other version ("planVersion N is not
supported (expected 1, 2, 3 or 4)"). The `planHash` covers the version, so the lending sample's four committed plans
were rewritten (only `planVersion` changed, to 4; `packVersion` stays `0.3.0`) and its four bindings files carry the new
`planHash`; the scenario module's test plan `toy-loan.plan.json` is version 4 as well.)

---

## 8. Failure taxonomy, status mapping and run outputs

### 8.1 Error codes and kinds

- New JSON-RPC error code `SCRIPT_ERROR` (`ErrorCodes.SCRIPT_ERROR`, `-32008`, agent and controller): a script the
  agent ran in a page threw; `data {browser, message, stack}`. From a step call it is `FAIL` / `ASSERTION` (the page
  did not do what the scenario expected), as `INPUT_REJECTED` is.
- `NOT_FOUND`, `AMBIGUOUS` from a web verb: `FAIL` / `ASSERTION`, as for Swing (P1 §8.5).
- `STALE_HANDLE` from a web verb (the browser closed during the step): `FAIL` / `ASSERTION` with the message "the
  browser closed during the step" (as built "<primitive> failed: the browser closed during the step (STALE_HANDLE:
  <message>)"; from any other primitive it stays `ERROR` / `ENVIRONMENT`).
- `CAPABILITY_UNAVAILABLE` from a web verb: `ERROR` / `ENVIRONMENT`, unchanged rule; as built `INVALID_PARAMS` (for
  example "not visible") stays `ERROR` / `ENVIRONMENT` too.
- The CDP path's `CdpException`: `UNREACHABLE`, `TIMEOUT`, `PROTOCOL` are `ERROR` / `ENVIRONMENT`;
  `FROZEN_TARGETS` is `ERROR` / `ENVIRONMENT` with `detail.frozenTargets` (id, url, title each). With
  `web.path: cdp` an unusable path is `ERROR` / `PRECONDITION_FAILED` (§6.1). (As built 2026-10-07: these reach the
  executor from `open()` as a `WebPathOpenException` of the same kind, with `detail.endpoint`; they are the outcome
  under `web.path: cdp`, with `attach: <KIND>` in the detail, while under `auto` they are a recorded reason and the run
  takes the agent path, §6.1.)
- As built: the failures of a call the CDP path served arrive with the agent's codes and map exactly as the agent's
  do. A base `webWaitFlag` whose result is not `ready: true` is `FAIL` / `SYNC_TIMEOUT` ("webWaitFlag: the flag did
  not turn true within <ms> ms: <expression>"); a raw `call` of `web.waitFlag` is not checked. The trailing settle of
  an awaited web verb is checked as in A32. (4.x, A35, built 2026-10-08: the same on both paths, a flag whose
  expression throws on every poll included; with a `lastError` the message ends " (last error: <lastError>)", the
  expression and the last error each cut at 200 characters in the message, and the detail carries the whole result as
  `waitFlag`; an expression that does not compile is `SCRIPT_ERROR`, `FAIL` / `ASSERTION`, on both paths; §16.1.)
- No new `FailureKind` and no new `Status`.

### 8.2 `run.jsonl`

New records: `{type: "web.path", path, reason?, cdp?: {endpoint, browserVersion, connectMs, targets: n, unresponsive:
n}, libHash, policy}` once per run that has web calls; `{type: "web.fallback", step, primitive, reason, from}` per
`UNSUPPORTED` fall-back; `{type: "web.recover", step?, browser, action: "reload", targetId, matchedBy, ok, reason?}`
per frozen-target recovery. Every `call` record of a `web.*` primitive carries `path`. The `web.*` events a run waited
on are in the `events` records as every other topic is; `web.console` text passes `SecretMask` like every recorded
string (A25/P2 §11.4).

As built (2026-10-07): `web.path.policy` is the scenario's `auto`, `cdp` or `agent`, and a pinned path that could not
be used writes `path: null` with the reason; `web.fallback.from` is the id of the path that refused the call, and a
read the executor makes on its own (a fact, the surface, evidence) records its fall-back once per primitive and
reason; `web.recover` carries the target and how its browser was matched (§5.3). Further records: `web.trace`
(`{ok: true, file: "failure/web-trace.zip"}`, `{ok: false, reason}` when the path wrote no trace, `{ok: false,
error}`, or `{skipped: true, reason: "secrets", error?}`, §6.4); `web.close` (`{path, ok: false, error}`, only when
closing the path failed); `fact` (`{root: "web", browser, field: "text", ok: false, reason}`, a `text` the root left
out, §6.2). In a `call` record `path` follows `primitive`, cleanup calls included; a `sync` record of a wait that read
a path's events carries `pathSince` and `source`, and a `waitFor` that a path event ended is a `call` record with
`eventSource` (§6.3); a web `evidence` record carries `path`. (4.x, A36, built 2026-10-08: the `seq` of a `call`
record of a call a non-agent path served is the agent's sequence the executor read before the call, as both `before`
and `after`; the record's shape is unchanged; §6.3, §16.2.)

**`run --json`** (as built 2026-10-07): each result carries `web`, `{path, reason?, cdp?, libHash, policy, calls:
[{step, index, primitive, path, ok}], fallbacks: [...]}`, masked, or null for a plan without web calls.

### 8.3 What is never written

Unchanged (P2 §11.4): no secret, by key or by value. The page's `text` in the `web` fact root and in evidence passes
the mask like every other fact; a `webFill` of a credential records `<redacted>`. A JxBrowser licence key is never
read, so it cannot be written. (As built 2026-10-07: no trace is kept of a run whose calls may carry a secret, and the
CDP path's live trace never lies in the run's output directory (§6.4, D59); a call that names secret parameters is
served by the agent, never by the CDP path (§5.2); the agent masks the console text it publishes (§4.3);
`toString()` of `WebEval` and `WebEvent` never shows the expression or the console text (§3); the library never
echoes a filled text into error data, `web.dom.fill` answers only the count it typed, and neither path logs a call's
arguments.)

(Phase 6, built 2026-10-09, TL; P6 §10.4.) The rows of P2 §11.4 that Phase 6 adds hold here too: a web call's span
names its primitive, path and correlation id, never its expression, selector, filled text or console text, and the
`web.*` records are not among those the observer receives.

---

## 9. The samples: the web host, its agent module, pack and scenarios (profile `jcef`)

The lending desk stays free of any embedded browser (design §3.8: "the lending desk stays free of dependencies").
Phase 4 adds a second, deliberately small sample target whose only purpose is to host embedded browsers the way a
desktop application does.

### 9.1 `skillroy-sample-web-host` (`samples/skillroy-sample-web-host`, package `…samples.webhost`, Java 8)

A Swing application ("the web host") that embeds JCEF through jcefmaven:

- `WebHostApp.main`: reads `-Dwebhost.rendering=windowed|osr` (default `windowed`; both modes run in the image on
  Java 25, off-screen only on Java 8, §2.5), `-Dwebhost.debugPort=<n>` (default 0: none; the only way a port exists,
  design §3.8), `-Dwebhost.install=<dir>` (the natives' install directory, default `target/jcef-bundle-<platform>`
  relative to the working directory), `-Dwebhost.cache=<dir>` (Chromium's root cache path, default a fresh temporary
  directory on the local file system). **Before `CefAppBuilder.build()` the host creates the empty files `First Run`
  and `EULA Accepted` in that cache path** (BS8: Chromium 152's first-run terms dialog otherwise blocks
  `CefInitialize`), and records in its log that it did. Windowed mode on Linux needs `CefApp.startup` before the first
  AWT call, so `main` builds the `CefApp` before any Swing class is touched (browser-spi §F4, §F15), then shows one
  `JFrame` ("Web host") with a `CardLayout` of **two browsers**: card `front` (visible) and card `back` (a cached,
  hidden browser the Swing-tree scan sees as displayable but not showing; the host calls `createImmediately()` on it,
  because BS8 showed that a browser whose component is not showing is otherwise never created), and a `JButton`
  "Swap" that shows the other card. A third browser, `detached`, is created with `createImmediately()` and never
  added to any window (a browser without a view; off-screen mode only, where the native browser exists without a
  window). (As built 2026-10-07: `WebHostOptions` parses the properties (`rendering` in any case, `debugPort`
  0..65535); `FirstRunSentinels.prepare(dir)` creates whichever sentinel is missing and never changes an existing file;
  `WebHostApp.launch(WebHostOptions)` refuses a headless JVM, its only AWT call before CEF exists, builds CEF with an
  empty mirror list (it never downloads), gives its one client the host's own load handler (the host is the
  application, so the slot is its own), waits at most 60 s in all for the pages' main-frame loads (a miss is a
  warning), registers the browsers and logs "ready"; every log line starts with "web host: ". One host per JVM: a
  second `launch` returns the running host when rendering and port match and throws otherwise, and after a stop or a
  failure `launch` throws. The window is 900 x 700, with the button `swap`, a label "Showing: front" or "Showing: back",
  and the deck's components named `front` and `back`. `shutdown()`, also run when the window closes, calls
  `setCloseAllowed()` on the three browsers before `CefApp.dispose()` (JCEF cancels every browser close otherwise, and
  CEF never reaches `TERMINATED`), disposes the frame, waits at most 10 s for `TERMINATED` and deletes a cache
  directory the host created. Off-screen rendering on Java 16 or newer needs `WebHostApp.OFF_SCREEN_JVM_ARGS`, §11.)
- **Pages** are the sample's own, served from the class path through a CEF scheme handler is **not** used (a handler
  slot the agent must never take would then be the application's; fine, but it adds surface). Instead the pages are
  `data:` URLs built from resources `webhost/front.html`, `webhost/back.html`, `webhost/detached.html` (no network,
  no file system): a title, a heading, a form (`#item` textbox, `#qty` number, `#borrow` button), an `iframe` whose
  `src` is a `data:` URL (never `srcdoc`, which did not load in BS8), a status line the page updates, and a small
  script that (a) dispatches `window` `CustomEvent`s `webhost.ready` after load and `webhost.borrowed {item, qty}` on
  the button, (b) listens for the `CustomEvent` `webhost.setItem {item}` from the host, (c) runs a busy loop for ever
  when the page's URL fragment changes to `#freeze` (the frozen target of §12.2 item 6), and (d) sets
  `window.webhostReady = true` 300 ms after load (the flag of `webWaitFlag`). (As built 2026-10-07: each page is
  about 4.8 KB, its URL about 8.2 KB (`WebHostPages.url(page)`), and carries no licence header, which would only
  enlarge the URL. The title and heading are "Front page", "Back page" or "Detached page", and `<body
  id="webhost-<page>">` puts a marker into the URL, so that a scenario selects a page by `url~"webhost-front"`, by
  tag or by title (a bare "back" also matches the CSS word `background`). The form is `#loan`, with labels "Item" and
  "Quantity" and the button "Borrow"; the iframe is `#terms`, titled "Loan terms"; the status line `#status` (role
  `status`) reads `borrowed <item> x <qty>` after the button. One script, identical in the three pages: `webhost.ready`
  carries `{page}`; `qty` is 0 when the field is empty; the event `webhost.reset` clears the fields and the status. A
  fragment acts only on a `hashchange` into it, never at load, so that a reload clears it (the specification's "when
  the fragment is `#freeze`" would freeze the page again on a reload): `#freeze` sets `window.webhostFrozen = true`,
  logs "webhost <page> frozen" and loops for ever; `#stall` logs "webhost <page> stalling" and keeps the page busy in
  30 s chunks with zero-delay yields between them, in case a reload cannot recover a renderer in an endless loop
  (§5.3). The page logs "webhost <page> item: <value>" whenever `#item`'s value changes (the console echo of §12.2
  item 7). `window.webhostDiagnostics()` answers `{listeners, dispatchWrapped}`: the listeners on `window`, counted by
  wrapping `EventTarget.prototype.addEventListener`/`removeEventListener` (the page's own baseline is 2), and whether
  `window` has a `dispatchEvent` of its own, as a glob tap's wrapper is (§12.2 item 8).)
- `WebHostApp` keeps the three browsers in a static registry `WebHostBrowsers` (`front()`, `back()`, `detached()`,
  `cefApp()`), so that the agent module of §9.2 can hand them out as seeds without the agent knowing JCEF. (As built:
  also `client()`, `isRegistered()`, `register(…)`, `clear()` and an event-driven `await(timeout, unit)`; `detached()`
  is null without one; loading the class loads nothing of CEF or AWT; the registry fills only after the pages have
  loaded, so a registered browser is usable. `WebHostApp` itself offers `running()`, `awaitLoaded(page, timeout,
  unit)`, `swap()`, `shutdown()` and the three browsers.)
- The host exposes nothing to the framework but that registry; it has no plugin and no `EMBEDDED` start (the
  end-to-end acceptance runs `JAVAAGENT` and `ATTACH`, §12.2).
- **What the runs showed** (as run 2026-10-07, the kit and the end-to-end acceptance). The `#freeze` and `#stall`
  fragments cannot be reached: Chromium leaves the fragment of a `data:` URL document as it is
  (`location.hash = '#freeze'` has no effect, and no `hashchange` fires), so the acceptance starts both loops by
  evaluating them in the page (§5.3, §12.2 item 6), and no test uses the fragments. Off screen, the hidden `back` card's
  page reports `document.visibilityState` "visible", as in BS8. Every windowed shutdown of a host started in a test JVM
  logs the warning "CEF did not report TERMINATED within 10 s". The bare `Exception in thread "AWT-EventQueue-0"` lines
  without a stack trace (off screen only, 5 to 11 per launch, during the run and at exit) are JCEF's own:
  `CefClient.onGotFocus` calls `setFocus(true)`, which makes CEF call `onGotFocus` again until the event dispatch
  thread's stack overflows inside a native callback; no uncaught-exception handler sees them, and neither the host nor
  the agent causes them.

### 9.2 `skillroy-sample-web-host-agent` (module id `webhost`, package `…samples.webhost.agent`, Java 8)

An `AgentModule` with one SPI implementation: a `BrowserRegistryAdapter` that returns `BrowserSeed.of(back(),
"back")` and `BrowserSeed.of(detached(), "detached")` (the detached browser reaches the agent through a seed only,
browser-spi §F23 precedent), and a `ReadinessSource` that is ready once the registry holds the front browser. It
registers no primitive. It depends on the host module for the registry class and on nothing else. (As built
2026-10-07: the adapter returns `BrowserSeed.of(front(), "front")` first, because tags come only from seeds and W1
selects `{tag: front}`; it returns the seeds that exist, in the order front, back, detached. The readiness source,
`webhost.browsers`, is ready once the registry holds the front browser, which it does once the pages have loaded
(§9.1). The module registers no capability and starts no thread, touches no AWT class at install, and its `uninstall`
never throws; it depends on `skillroy-agent-api` and the host module, both at `provided` scope; version `0.1.0`,
`requires()` empty.)

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

As built (2026-10-07):

- **The pack** (`…samples.webhost.pack`: `WebHostPack`, `WebHostFacts` and `WebHostRecovery`, found through
  `META-INF/services`) has no verbs of its own, the sync policy above and no pre-flight. The fact root `webhost` reads
  `web.browsers` once and resolves `front`, `back` and `detached` (off screen only) by tag, else by the URL marker
  `webhost-<page>` when exactly one open browser carries it, each as
  `{ref, tag?, page, title?, visibleInSwing, rendering, status?}`: `page` is the name the marker gives (the URL itself,
  about 8 KB, is left out), and `status` the trimmed text of `#status`
  (`web.dom.text {selector: "#status", maxChars: 200}`, for a browser with `EVALUATE`), left out when that read fails;
  one read is bounded at 10 s, and a failing `web.browsers` fails it, as for the base root (§6.2). `recover` dispatches
  `webhost.reset` with `by: {tag: front}`, again with `by: {url: "webhost-front"}` on `NOT_FOUND`, then calls
  `swing.settle {timeoutMs: 5000}`.
- **W1** (`scenarios/borrow-in-page.yaml`): `requires: [web.browsers, web.evaluate, web.events]`,
  `web: {browser: {tag: front}}` and a step timeout of 30 s. The scenario format has no `setup` key, so
  `webTap {patterns: [webhost.*]}` is the first step, and the verb's cleanup removes the tap (§7.1). Then `webFill` of
  `#item` with "hammer" and of `#qty` with "2", and `webClick {role: button, name: Borrow}` whose `await` waits for
  `web.tap` matching `{name: webhost.borrowed}` and captures
  `{slot: borrowed, fields: {item: detail.item, qty: detail.qty}}`; the expectations that `web.browsers[tag=front].text`
  contains "borrowed hammer x 2" and that `webhost.front.status` is "borrowed $borrowed.item x $borrowed.qty" (the
  captured detail; the acceptance checks the capture itself, §12.2 item 3); then
  `webDispatch webhost.setItem {item: saw}` and `webWaitFlag "document.getElementById('item').value === 'saw'"`.
  `verify` checks the status and `webhost.front.visibleInSwing: true`.
- **W2** (`scenarios/hidden-browser-reachable.yaml`): `requires: [web.browsers, web.evaluate, web.resize]` and
  `web: {browser: {tag: back}}`; `webResize {width: 1024, height: 768}` and
  `webWaitFlag "innerWidth === 1024 && innerHeight === 768"`, then the expectations `visibleInSwing: false` through both
  roots (`web.browsers[tag=back]` and `webhost.back`) and the back page's text containing "Back page" (the specified
  `web.dom.text` is the base root's read, §6.2), then `webDispatch` and `webWaitFlag` as in W1; `verify` checks the
  title "Back page" and `webhost.back.page: back`. Both committed plans are version 4 with
  `web: {path: auto, browser: {tag}, frozenTargets: fail, trace: false}` and need no bindings.
- **The negative scenarios** (`src/test/resources/negative/`): `no-such-button`
  (`webClick {role: button, name: Return}`: `NOT_FOUND`); `two-inputs` (`webClick {selector: "#loan input"}`, the form's
  two inputs, because the page has a single button: `AMBIGUOUS`); `script-error` (a raw `call` of `web.evaluate` whose
  expression throws: `SCRIPT_ERROR`; no base verb would do, since `webWaitFlag`, the only one that evaluates an
  expression of the scenario's, keeps polling one that throws on the agent path, §4.2); these three end `FAIL` /
  `ASSERTION`. `cdp-without-port` (`web: {path: cdp}`, run against a launch without a port) ends `ERROR` /
  `PRECONDITION_FAILED` naming `web.cdp`, no step run; and, for §12.2 item 7, `fill-a-secret` fills a credential's
  password into `#item` with `webFill`, then expects what cannot hold, so that the evidence is written. (4.x, A35,
  built 2026-10-08: `webWaitFlag` keeps polling an expression that throws on both paths now, and two negatives show
  it, each in every launch, so over CDP with a port and through the agent without one: `flag-never-ready`
  (`webWaitFlag` of `document.getElementById('due-date').value === '2026-10-31'`, a field the front page does not
  have, with `timeoutMs: 1s`) ends `FAIL` / `SYNC_TIMEOUT` with the `TypeError` of its last poll as `lastError`, after
  more than one poll; `flag-does-not-compile` (`webWaitFlag` of `document.title ===` with `timeoutMs: 60s`) ends
  `FAIL` / `ASSERTION` with `SCRIPT_ERROR` and a `SyntaxError`, its call answered within 10 s; §16.1.)

---

## 10. The adapter contract kit and the neutral fake

### 10.1 The kit (`skillroy-agent-web` `test-jar`, package `…agent.web.kit`)

`WebEngineAdapterContract` is an abstract JUnit 5 class (Java 8) with one abstract hook,
`WebAdapterFixture fixture()`, which a technology's test module implements: it supplies the `WebEngineAdapter`, the
seeds for a visible, a hidden and (optionally) a view-less browser, a way to load a given page into a browser
(`load(browser, html)`), the EDT (or the thread that must never be blocked), and the expected `WebRendering` per
browser. The kit's pages are its own resources (same shapes as the web host's). (As built 2026-10-07: the fixture
also supplies a pristine and a recording host loader, `newDisposableBrowser` and `close` for the closure items, the
application's own listener for item 10, `postToGuardedThread`, the expected enumeration state, `debugPort` and
`checksBitmapColours`, and, since the review, three optional hooks that answer "unknown" by default and then skip
their check: `initialisedClasses(loader)` and `technologyPackages()` for item 1, `vendorListenerCount(browser)` for
item 10. The kit calls its protected `afterDetach()` right after `detach()`, where a technology's tests check what
their fake recorded (rule violations), so that a violation is charged to the test that made it. The pages are
`main.html`, with a `data:`-URL iframe of `inner.html`, and `second.html`, beside `KitScripts`, `EdtWatchdog`,
`RecordingClassLoader` and `ClassInitialisations`; the items are the test methods `item01…` to `item11…`, item 10 as
two tests; 4.x, A37, built 2026-10-08: to `item12…`, thirteen tests.) Its tests, one per rule and operation
(browser-spi "Testing in public CI" item 1):

1. `probe` against a host that did not load the gate class is `UNAVAILABLE` and loads nothing (checked with a
   recording class loader); against a host that loaded and initialised the gate as the application does, and nothing
   else of the technology, it is `OK`, every class it makes that host define belongs to the technology's own packages
   (`technologyPackages()`), and it initialises no class of the technology (`initialisedClasses`, where the fixture can
   see initialisation: the fakes' classes record their static initialisers). (As built 2026-10-07 after the review,
   D61: the specified "`probe` is `OK` and loads no class the host had not loaded" could not be met, and its check, on
   a host that had loaded the whole fake package, could not fail.)
2. enumeration from seeds finds the visible, the hidden and the view-less browser; the same browser twice gives
   `equals()` handles; an unrelated seed (a `JButton`, a `String`) is ignored; `enumerationState()` as the fixture
   predicts;
3. `evaluate`: a number, a string, an object, `undefined` (as `"null"`), a thrown error (`WebScriptException` with
   the message), a promise with `awaitPromise` (or `AWAIT_PROMISE` absent), a 1 MiB string (truncation marker), 100
   evaluations in a row within a bound; nothing blocks the EDT (an EDT watchdog fails the test if a marker posted to
   the EDT every 50 ms is late by more than 500 ms during any operation; as built the watchdog first lets the guarded
   thread run what is queued before it excuses the fixture's own work, so that a block still in progress is judged,
   review 2026-10-07);
4. frames: the main frame first, the `data:`-URL iframe present with the parent key (the specification said `srcdoc`
   by mistake: a `srcdoc` iframe does not load in the image, §2.5), `evaluate` in the iframe answers its own
   `document.title`, a key of a frame that navigated away raises `WebFrameGoneException`;
5. events: `LOAD_FINISHED` after `load`, `NAVIGATED`, `CONSOLE` for `console.log`, `CLOSED` when the fixture closes
   a browser, no event after `cancel()` (as built also a console message the kit triggers by evaluation, so that
   item 3's watchdog judges a listener that blocks the EDT on such an event; a reload by script cannot serve, because
   Chromium refuses a renderer-initiated navigation of a `data:` document, which the kit's pages are, so the second
   navigation goes through the fixture's load, excused);
6. init script: a script added before `load` is visible to the page's inline script when `timing()` is
   `DOCUMENT_START`, and after the load event otherwise; cancelled scripts do not run in the next document;
7. `resize` changes `innerWidth`/`innerHeight` as read back; `clearResize` restores them; `viewport()` follows;
8. `bitmap` of the visible and of the hidden browser decodes, has the page's background colour in its corner and more
   than one colour (as built after the run against real JCEF: the hidden browser's may instead be refused at once, with
   an `UnsupportedOperationException` that says the browser is hidden, §10.3);
9. `cdpTargetId` is non-null when the fixture configured a port and the id appears in `/json/list` (skipped when the
   fixture has no port);
10. after `isClosed()` every operation completes exceptionally with a clear message within the bound; `detach()`
    removes the kit's listeners and init scripts (the next load shows no init-script marker) and leaves the
    application's own listener (the fixture installs one) in place (as built, review 2026-10-07: where the fixture can
    count the listeners registered with the technology itself, `vendorListenerCount(browser)`, the count after
    `detach()` is the count before the kit subscribed plus the application's listener, so what the adapter registered
    is gone on the technology's side too; the fake JCEF counts its client's listeners and the raw observers, the
    JxBrowser fake the browser's observers; 4.x, A37, built 2026-10-08: the closed browser's `reload(false)` fails the
    same way);
11. no enumerable global is left on `window` after any operation (`Object.keys(window)` before and after are equal);
12. (4.x, A37, built 2026-10-08) `reload` and the bitmap's source: `bitmapSource()` of the visible browser is
    `PROTOCOL` or `VENDOR`; then, with `EVENTS_LOAD` (else the rest is skipped), `reload(false)` and then
    `reload(true)`, each on a document of the main page that the kit has marked (a non-enumerable property set by
    evaluation), complete within the bound and are each followed, within the bound, by a `LOAD_FINISHED` of that
    browser's main frame, after which the marker is gone and the title is the page's (a new document of the same page).

### 10.2 The neutral fake (`skillroy-agent-web` tests, package `…agent.web.fake`)

The `jxbrowser` adapter cannot be run against the product in this repository (browser-spi §F28). Its reflection
plumbing is run against `FakeEngine`, `FakeBrowser`, `FakeFrame`, `FakeView` (a `JComponent`), `FakeNavigation`,
`FakeOptions`: classes under the project's own package with the same member shapes as the F21 table (`browsers()`,
`url()`, `mainFrame()` returning `Optional`, `executeJavaScript(String, Consumer)`, `on(Class, Observer)` with a
fake `Observer` interface, `bitmap()`, `resize`, `devTools().remoteDebuggingUrl()`, `options().remoteDebuggingPort()`
and a `licenseKey()` that fails the test if called). The fake evaluates a small fixed set of expressions (the ones the
library and the kit use) by table, not a JavaScript engine. The adapter is driven by the descriptor
`fake-jxbrowser.json`, identical to `jxbrowser.json` except for the class names. The kit runs against the fake (every
item except 8's colour check and 9), and three more tests cover: a descriptor role missing in the fake (the
capability is absent, the primitive answers `CAPABILITY_UNAVAILABLE`), two fake class loaders (the cache is per
loader), and `set(...)` never called without the opt-in. This proves the mechanics, **not** compatibility with
JxBrowser, and says so in the test names and in the capability matrix's strategy ("jxbrowser (fake)").

As built (2026-10-07). The JxBrowser fake also has profiles, documents, the event classes and an `InjectJsCallback`;
`licenseKey()` and `dispatch()` record a violation that fails the test; results and events arrive on the EDT; a test
proves that `fake-jxbrowser.json` differs from `jxbrowser.json` only in class names. The kit runs against it twice,
plainly (`JxBrowserFakeKitTest`) and with the init-script chaining option (`JxBrowserFakeChainedKitTest`);
`JxBrowserAdapterFakeTest` holds the further tests. **A fake JCEF** (package `…agent.web.fake.cef`, not in the
specification) does the same for the `jcef` adapter: an app with its state, a client, browsers of both rendering
kinds, a DevTools client and a GL canvas, under the project's own package and driven by `fake-jcef.json`;
`getInstance()` fails the test. It reproduces what BS8 saw (answers on the EDT, the first client of a browser created
with `createImmediately()` closed, a 1 x 1 viewport until overridden, a GL canvas that throws when printed) and, since
the review, Chromium's error answer to a call pending at a navigation (§4.5), a frozen page that queues calls and runs
them on thaw as a busy renderer does, and a DevTools client that keeps its listeners in a set as JCEF 152's does (the
fake classes also record their static initialisers, for kit item 1); the descriptor variants
`fake-jcef-old.json` (no `getDevToolsClient`) and `fake-jcef-nowalk.json` (no `clients_`) cover the probe's and the
walk's degraded answers. The kit runs against it (`JcefFakeKitTest`, every item except 9), beside `JcefAdapterFakeTest`.
After the kit's run against real JCEF (§4.5), the fake has a descriptor for each channel: `fake-jcef.json`, without the
`raw.*` roles, through which `JcefFakeKitTest` drives the client path, and `fake-jcef-raw.json`, with them, whose
browsers answer every second call before its message id: the kit runs over that raw channel as well
(`JcefFakeRawKitTest`, which also checks that no call reaches the client), beside `JcefRawChannelFakeTest`. (4.x, A37,
built 2026-10-08: the four runs of the kit against the fakes include item 12; the fake JCEF's `reload()` and the
JxBrowser fake's `Navigation.reload()` load the fake page again, with its load events.)

### 10.3 Running the kit against JCEF

`skillroy-sample-web-host`'s test scope (profile `jcef`; display tests skip when headless, `DisplayTestBase`
pattern) runs the kit in process against the real JCEF browsers of the web host with the `JcefAdapter`, in both
rendering modes on the image's Java 25 and off-screen on a Java 8 test JVM (§2.5). A licence holder runs
the same kit against JxBrowser in a private repository by implementing `WebAdapterFixture` over a real engine
(browser-spi §R6); the `TODO.md` wall "A JxBrowser licence" records that this verification happens outside this repo.
(As built 2026-10-07: the web host allows one CEF per JVM, so every test class that starts CEF is named
`*DisplayTest` and runs in a surefire execution of its own, as the host's `display` execution does; surefire 3.6.0
with `reuseForks=false` and `-Djvm` runs no tests at all. The off-screen exports reach those JVMs through
`JDK_JAVA_OPTIONS`, §11.)

**The fixture and the test classes** (as built 2026-10-07). `JcefKitFixture` (the host module's tests) starts
`WebHostApp` once per JVM with a free debug port it picked, so that item 9 runs, and gives the kit a
`new JcefAdapter()`, the host's loader, and the host's `front`, `back` and (off screen) `detached` browsers as the
visible, hidden and view-less seeds and objects. It loads a page as an application would: `Page.enable` through the
browser's shared client with its answer awaited (5 s, at most three attempts), then `CefBrowser.loadURL` of the page's
`data:` URL, then a bounded wait (30 s) for the main frame's `Page.frameNavigated` and the `Page.loadEventFired` after
it, heard through a DevTools listener of its own; the application's own listener of item 10 is such a listener too. A
life-span handler the fixture adds to the host's client (the host has none of its own; the handler's `doClose` defers to
the browser's) tells creation from closure: `newDisposableBrowser` is, off screen,
`client.createBrowser(<a blank data: URL>, true, false)` with `createImmediately()`, and null windowed (a windowed
browser needs a window); `close` is `setCloseAllowed()` with `close(true)`. The expected renderings are
`OFFSCREEN_HEAVYWEIGHT` and `WINDOWED`, the enumeration state `OK`; the bitmap colours are checked; the pristine loader
records `org.cef.`, and there is no recording host loader (JCEF's natives load once per JVM). Two classes run the kit,
`JcefKitOffScreenDisplayTest` and `JcefKitWindowedDisplayTest`, one surefire execution per rendering mode
(`kit-off-screen`, `kit-windowed`, each setting `skillroy.kit.execution`); each skips when headless or outside its own
execution, and the windowed class skips on a Java 8 test JVM (BS8, §2.5). Item 10's closed-browser check is skipped
windowed, for want of a disposable windowed browser.

**What the run against real JCEF showed** (2026-10-07), each handled since:

- JCEF's DevTools client loses an answer that arrives before its message id (kit items 3, 4, 9 and 11 timed out): the
  adapter's raw channel (§4.5, §3 rule 3).
- A hidden browser is not rendered: windowed its screenshot was never answered, off screen it held the page's background
  colour only, whatever the viewport override. The adapter refuses a hidden browser's `bitmap()` at once (§4.5), and kit
  item 8 accepts that refusal, an `UnsupportedOperationException` that says the browser is hidden, for the hidden
  browser only.
- Windowed rendering restores the size that `clearResize` cleared asynchronously (item 7 read the override's 640 x 480
  back): `clearResize` now waits for the restored size (§4.5).
- A `Page.enable` still in flight when a navigation commits in a new renderer misses that navigation's main-frame
  `Page.frameNavigated` (the amd64 image, windowed, the first load after the host started: item 4's page "did not
  load"). A DevTools consumer that wants to observe a navigation therefore has its `Page.enable` answered before the
  navigation starts, as the fixture now does. Since the fix wave the adapter's `subscribe()` waits for its channel's
  `Page.enable` and `Runtime.enable` answers as well (at most 2 s, never on the EDT, §4.5), so a navigation started
  after a subscription is reported; one the application commits during that round trip could still miss its `NAVIGATED`
  event, which no run showed.

As run, the kit passed off screen 12 of 12 and windowed 11 with one skipped, on the arm64 and the amd64 image with Java
25, and off screen 12 of 12 on a Java 8 test JVM (arm64); the runs are recorded in `../TODO.md`. They were made on the
copy of the implementer who built the acceptance, before the review's fix wave. Three of the fix wave's changes can
only be shown against real JCEF, so the kit's run in the final tree's Linux matrix (`../TODO.md`) is their check: the
`subscribe()` wait above, init scripts added once per client (the review's N4) and kit item 5's judged console event
(the reload by script the kit first used never happened on a `data:` page, which the first matrix round showed:
Chromium refuses a renderer-initiated navigation of such a document; the kit now navigates through the fixture).
(4.x, A37, built 2026-10-08: item 12 makes the kit thirteen tests. Against real JCEF it runs only in the Linux image
under the profile `jcef`, in the web host's two kit executions, and it had not been run when A37 was built: the
orchestrator's matrix runs it. `CefBrowser.reload()` of the web host's `data:` pages is what frozen-target recovery
already used, §5.3. Run 2026-10-08 in the first Phase 5 matrix: item 12 failed on the kit's `DOCUMENT_MARK` script, whose
value was the window (`Object.defineProperty` returns its object) and which `Runtime.evaluate` by value refuses ("Object
reference chain is too long"); the expression now ends with a string, and the off-screen kit passes 13 of 13 on a Java 8
and on a Java 25 test JVM.)

---

## 11. Build, image and licences

- **Profile `jcef`** in the root POM lists the three sample modules. Property `skillroy.jcef.platform` (default
  `linux-amd64`) selects the natives artifact `me.friwi:jcef-natives-<platform>:<tag>` in test scope; a developer on
  another platform sets it (`macosx-arm64`, …) or runs through `ci/run-in-linux.sh`. Nothing downloads at test time
  beyond Maven's own resolution (`-o` works once the cache holds the natives; Risk5 of browser-spi). (As built
  2026-10-07: the profile holds the properties `skillroy.jcef.platform` (the command line wins), `jcefmaven.version`
  152.0.6 and `jcef.tag` `jcef-17e805a+cef-152.0.6+g708dc14+chromium-152.0.7977.83` (`jcef-api` and the natives are
  versioned by that release tag, not by the jcefmaven number), the sample modules, and managed entries for them, for
  jcefmaven and for the natives; nothing outside the profile changed.)
- **Off-screen rendering on Java 16 or newer** (as built 2026-10-07) needs `--add-exports java.base/java.lang`,
  `java.desktop/sun.awt` and `java.desktop/sun.java2d` to `ALL-UNNAMED`, which Java 8 refuses. The host module's test
  JVMs get them through the environment variable `JDK_JAVA_OPTIONS` (surefire's `environmentVariables`), which a Java
  8 test JVM ignores; a `jdk [16,)` profile would follow Maven's own JVM and hand them to a Java 8 test JVM run with
  `-Djvm`. A host started as a child process gets `WebHostApp.OFF_SCREEN_JVM_ARGS` on Java 9 or newer, never on Java 8.
  Every test class that starts CEF runs in a surefire execution of its own (the host's `display` execution,
  `forkedProcessTimeoutInSeconds` 900; §10.3).
- **The image** (`ci/Dockerfile`) gains Chromium's run-time libraries: exactly the fifteen packages `ldd` reported
  missing for `libcef.so`, `libjcef.so` and `jcef_helper` on the base image (`libnss3 libnspr4 libatk1.0-0t64
  libatk-bridge2.0-0t64 libatspi2.0-0t64 libcups2t64 libdbus-1-3 libglib2.0-0t64 libcairo2 libpango-1.0-0
  libasound2t64 libxcomposite1 libxdamage1 libxfixes3 libxkbcommon0`); OpenGL for off-screen rendering (`libgl1`,
  `libgl1-mesa-dri`, `libxxf86vm1`) was already present as a dependency of `xvfb`. Built and used by BS8 on 2026-10-06
  (§2.5). `../licence-audit.md` §7 lists the new packages.
- **New third-party artifacts** and their audit (`../licence-audit.md` §5, where they moved from §8 when they were
  built on 2026-10-07): Playwright Java 1.63.0 (Apache-2.0; redistributed only if `skillroy-cdp` ever enters a
  distribution, which Phase 4 does not do: D49), with `driver`, `driver-bundle` (Node.js binaries for five platforms,
  each with Node's notices), `gson` 2.14.0 (and through it `error_prone_annotations` 2.48.0), `jspecify` 1.0.1 and
  `opentest4j` 1.3.0; jcefmaven 152.0.6 (Apache-2.0) with `jcef-api` (BSD-3-Clause), JOGL and GlueGen 2.4.0 (their
  POMs name several licences, one per component), the natives (CEF's BSD-3-Clause text and the JOGL and GlueGen
  notices; the audit found no Chromium notices file in them) and Apache-2.0 helpers (`commons-compress`,
  `commons-codec`, `commons-io`, `commons-lang3`, `gson` 2.11.0, `error_prone_annotations` 2.27.0): sample and test
  only, never in a distribution.
- **Agent home**: `modules/skillroy-agent-web.jar` joins the two module jars; `DistributionContentIT` expects seven
  jars and checks, as for the others, that it holds only `ai/skillroy/` and `META-INF/` entries (the script library
  is in `skillroy-protocol`, under `ai/skillroy/`).
- `--release 8` holds for `skillroy-agent-web`, the kit and the two Java 8 samples; the agent-side Java 8 test JVM leg
  of the matrix gains `skillroy-agent-web` (its unit tests and the fake) and, under the profile, the web host and
  its agent module.

As built (2026-10-07): the profile lists the scenarios module after the host and the host's agent module. The host's and
its agent module's tests ran on a Java 8 test JVM (`-Djvm=/opt/jdk8/bin/java`, the arm64 image, with
`skillroy-protocol`, `skillroy-agent-api` and `skillroy-agent-web` through `-am`): the off-screen kit passed 12 of 12
and the windowed kit class was skipped (§10.3); the run is recorded in `../TODO.md`.

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
   them), the frozen-target policy parsing (`close` refused). (As built: the parallel probe is shown by the peak of
   sockets open at the same time, and its wall-clock bounds are the design envelope of §5.1, three deadlines and
   500 ms; no unit test starts a driver or a browser, §5.)
6. `skillroy-cli`, `skillroy-junit`: `caps` lists the `web.*` capabilities; `run --json` carries `path` per call and
   the `web.path` record; the JUnit glue's degraded message is unchanged (nothing new degrades).
7. `skillroy-sample-web-host-scenarios` (headless): the pack compiles W1 and W2 to the committed plans
   (`PlansUpToDateTest` pattern); the committed plans are version 4.

As built (2026-10-07), item 7: `PlansUpToDateTest` (6 tests) checks that the two committed plans are byte for byte what
the pack compiles from the two committed scenarios (regenerated with `-Dskillroy.plans.update=true`) and the only files
in `plan/`; that both are version 4, leave `web.path` at `auto`, name the pack and its version, need no bindings and do
not require `web.cdp`; W1's calls, the tap's cleanup call and the awaited, captured click; that every web call of W2
selects `{tag: back}` and that both roots appear in its expectations; that every call waits or is waited on; and that
the five negative scenarios compile (`cdp-without-port` with `web.cdp` added to `requires`, `fill-a-secret` with the
secret left a `$reference`; (4.x, A35, built 2026-10-08) seven with `flag-never-ready` and `flag-does-not-compile`,
whose `web.waitFlag` calls carry the front page's `by`, the expression and their own `timeoutMs`, with sync `NONE`).
`WebHostPackTest` (6 tests): the pack is found through `ServiceLoader` and has no verbs;
its sync policy; the `webhost` root by tag with the status lines, by URL marker without tags (a failing status read left
out), and a failing `web.browsers` failing the read; `recover` dispatches `webhost.reset` and settles.

As built (2026-10-07), the tests of items 1-6: in `skillroy-agent-api` `WebEvalTest`, `WebFrameTest`,
`BrowserSeedTest`, `WebEventTest`, `CdpEndpointTest`, `WebExceptionsTest` and `WebSpiShapeTest` (every public member,
and no signature naming a type outside `java.*` and the agent API), with `DefaultMethodsTest` for the default of
`Invocation.secretParams()` and, in agent-core, a case of `SecretParamsTest` (beside the A34 tests, where the review
placed it) proving that the dispatcher fills it from `_meta.secretParams`; in `skillroy-protocol` `WebLibTest` (§4.6)
and `ErrorCodesTest`; in `skillroy-agent-web` `WebModuleInstallTest`, `WebCapabilitiesTest`, `BrowserIndexTest`,
`SelectionTest`, `EvaluateAndLibraryTest` (read-only mode included), `TimeoutAndRetryTest`, `EventsAndTapTest`,
`ViewPrimitivesTest`, `ImagesTest` (in a child JVM: scaling and encoding start no AWT thread), `ReflectiveApiTest`,
`LoadedClassesTest`, `AdapterThreadsTest`, `JcefAdapterRulesTest`, `EdtWatchdogTest`, the kit against the fakes
(`JxBrowserFakeKitTest`, `JxBrowserFakeChainedKitTest`, `JcefFakeKitTest` and `JcefFakeRawKitTest`, every item but 9),
`JxBrowserAdapterFakeTest`, `JcefAdapterFakeTest` (a whole fake JCEF, §10.2, not only the shape of the walk) and
`JcefRawChannelFakeTest` (the raw channel, §4.5), and in `skillroy-agent-swing` `CaptureGuardTest` for the
`PAINT` guard (with `CaptureGuardDisplayTest` on a display); in `skillroy-scenario` `WebCompileTest`, `WebPathTest`,
`WebEvidenceTest`, `SecretParamsForecastTest` (the trace decision of §6.4, compared call by call with the `secretParams`
of real runs) and `UiSnapshotTextTest`; in `skillroy-cdp` `TargetProbeTest` (against `FakeDevTools`, an HTTP server with
a WebSocket server of its own), `CdpOptionsTest`, `ShapesTest`, `SelectionTest`, `PwThreadTest`, `CdpAttachTest`,
`CdpWebPathOpenTest`, `CdpWebPathCallTest` and `CdpWebPathServesTest` (Playwright replaced by `Proxy` fakes) and the
guard `NoPlaywrightInUnitTestsTest`; in `skillroy-cli` `WebCommandsTest` (`caps`, the `web` object of `run --json`, the
notes of `compile`) and in `skillroy-junit` `WebGlueTest`. The Java 8 test JVM legs are recorded in `../TODO.md`.
(4.x, A37, built 2026-10-08: `WebBrowserDefaultsTest` in `skillroy-agent-api` for the two default methods of §3, and kit
item 12; 4.x, A38, built 2026-10-08: `WindowOpenedPaceTest` in `skillroy-agent-web` for the pace of §4.1; §16.3,
§16.4.)
(4.x, A35, built 2026-10-08: and `WaitFlagGuardTest` in `skillroy-agent-web` and `CdpWaitFlagTest` in `skillroy-cdp`,
§16.1.) (4.x, A36, built 2026-10-08: A36's tests are cases of `CdpWebPathCallTest` and `WebPathTest`, §16.2.)

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
   component), `back` (false; found by the Swing-tree scan **and** as a seed, one ref), `detached` (false, no component;
   seed only; off-screen mode) with their URLs, titles, frames (the `data:`-URL iframe named `terms`; the specification
   said `srcdoc` by mistake, §2.5), `rendering` as §4.5 says, and `cdpTargetId` for every browser when the port is set
   (equal to the ids in `/json/list`, read by the test) and absent otherwise.

As run (2026-10-07), item 2: in every launch `web.browsers` lists `front`, `back` and, off screen, `detached` once each
with one ref (`back` too, which the Swing-tree scan and its seed both find), the tag, adapter `jcef`, a URL holding the
page's marker, the title, `closed: false`, the rendering, `visibleInSwing` true for `front` only, a component for
`front` and `back`, the capabilities with `CDP_TARGET_ID`, and the main frame first with the iframe `terms` as its
child; the test compares the set of tags and the number of browsers, not an order, which the contract does not fix.
`cdpTargetId` is present in every launch, without a port too, because the adapter reads it in process (D55); with a port
each one is the id of a page target in `/json/list`.

3. **Scenario W1 passes without sleeping** in the launch without the port (path `agent`, recorded) and in the launch
   with the port (path `cdp`, recorded; the `web.path` record names the endpoint and the connect time): no call of the
   executor's sleeper; every wait ended by an event; `run.jsonl` has `path` on every web call; the tapped
   `webhost.borrowed` event was the one that ended the click's wait; the captured `qty` is `2`.
4. **Scenario W2 passes** in both launches (the `back` browser is driven in process in both: `web.resize` and the
   hidden-page text; with the port, `cdp` serves the `web.dom.text` through Playwright on the hidden page's target). As
   run (2026-10-07): with the port, `cdp` served W2's `web.resize` too, in every launch, through the override on its own
   session (§5.2, D64), not the agent as written above; without one the agent served it; the value was
   `{applied: OVERRIDE, viewport: {w: 1024, h: 768}}` either way.
5. **Negative scenarios** (§9.3) end as stated; evidence of §6.4 is written for the failed ones: the aria JSON
   decodes and names the `Borrow` button, the PNG decodes with at least 8 colours; `web.path: cdp` without a port is
   `PRECONDITION_FAILED` naming `web.cdp`. (4.x, A35, built 2026-10-08: also `flag-never-ready`, `SYNC_TIMEOUT` with
   "(last error: TypeError: " in the message, `ready: false`, a `TypeError` as `lastError` and at least two polls in
   the detail's `waitFlag`, and the web evidence; and `flag-does-not-compile`, `ASSERTION` with `SCRIPT_ERROR` and
   `SyntaxError` in the message and a `web.waitFlag` call record under 10 s; each on the launch's path, `cdp` with a
   port and `agent` without one; written natively, where the acceptance cannot run, and left to the Linux image.)
6. **Frozen target (the BS5 criterion, D53).** With the port: the test navigates the `back` browser to its
   `#freeze` fragment through `web.evaluate` (`location.hash = '#freeze'`), waits until the probe reports it
   unresponsive, then runs `CdpAttach.connect` **50 times** with the default policy: every attempt ends within
   `connectDeadline` with `FROZEN_TARGETS` naming the back target and never hangs (measured: the slowest attempt is
   recorded); then once with `frozenTargets: reload`: `web.reload` runs, the second probe is clean, the attach
   succeeds and W1 passes. (Playwright's connect itself against a frozen target is exercised once more with the probe
   disabled, under the external deadline, to show that the deadline, not the probe, is what prevents the hang: it must
   end with `TIMEOUT` and the driver gone.)

As built and run (2026-10-07), item 6: `CdpAttachSoakIT`, once per target Java against a host of its own (`JAVAAGENT`,
off screen, a port), runs four checks in this order. Each starts its loop by evaluating it in the `back` page through
the agent's `web.evaluate` (posted with a zero-delay timer, so that the evaluation answers first), because the page's
`#freeze` and `#stall` cannot be reached on a `data:` page (§9.1), then repeats the probe until it reports the back
target unresponsive. (1) A stalled page (busy chunks of 5 s with zero-delay yields between them; the page's own `#stall`
chunks of 30 s would outlast the policy's 10 s wait for the load) with `RELOAD`: `CdpWebPath.open` succeeds after
exactly one `web.recover` (`ok: true`, the back browser, matched by its `cdpTargetId`), in 8 to 9 s; then W1 with
`web: {frozenTargets: reload}`, against a back page stalled again, passes over CDP with one `web.recover` record. (2) 50
calls of `CdpAttach.connect` with the default policy against an endless loop: every one ends with `FROZEN_TARGETS`
naming the back target, within `connectDeadline` (30 s); the slowest took about 2.02 s. (3) `RELOAD` against the endless
loop: one `web.recover`, no load, and `FROZEN_TARGETS` after about 18 s (§5.3). (4) `CdpAttach.connect` without the
probe, with a `connectDeadline` of 10 s: `TIMEOUT` after about 10 s (the test allows 15 s), and Playwright's driver
(`node`) is gone. The frozen checks run last, because nothing short of ending the host stops the loop.

7. **Secrets.** `webFill` of a value named in `secretParams` is `<redacted>` in `run.jsonl`, in `agent.log` and in
   `web.console` payloads that echo it (the page logs the filled value on purpose), and appears in no evidence file.

As run (2026-10-07), item 7: the negative scenario `fill-a-secret` (§9.3) runs in every launch with a credentials file
that the test writes, owner-only, with a fresh 16-character value and deletes once loaded. The `web.dom.fill` call
record has `text: "<redacted>"`, `secretParams: ["text"]` and `path: agent` in every launch, because the CDP path hands
every call that names secret parameters to the agent (§5.2), so that with a port a `web.fallback` record for it comes
first. The page's echo arrives as a `web.console` event with the value masked ("webhost front item: <redacted>"), and no
`web.console` text holds it; neither `agent.log` (its last 2000 lines) nor any file of the run's output directory, the
failure evidence included, holds it.

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

As built and run (2026-10-07). `WebHostEndToEndIT` holds eight `@Nested` launches, {`JAVAAGENT`, `ATTACH`} x {windowed,
off screen} x {no port, port}, each running the checks of `WebHostChecks`: items 1, 2, 3, 4, 5 and 7, the launcher
checks, then item 8, which disables the agent. A launch starts the host through `TargetLauncher` (and attaches through
`AttachSession` for `ATTACH`) on the target Java with `-Djava.awt.headless=false`, the rendering, the port (a free
loopback port from `new ServerSocket(0)`, closed just before the launch), the platform's natives directory, shared by
every launch, a fresh owner-only cache directory and, off screen on Java 9 or newer, `WebHostApp.OFF_SCREEN_JVM_ARGS`;
the target is ready when the agent reports `target.ready` (within 240 s), and `-Dskillroy.it.launches=<labels>` selects
launches. On a Java 8 target the four windowed launches are skipped (§2.5). Playwright's driver is extracted once per
JVM, under the module's `target/playwright-driver`. Item 6 is `CdpAttachSoakIT` and item 9 the kit in the web host
module (§10.3).

Items 1 to 9 passed in the final tree's Linux runs: the arm64 and the amd64 image with a Java 25 target, the arm64 image
with a Java 8 target, and the host modules on a Java 8 test JVM (§11); not run: a Java 8 target on the amd64 image, and
windowed rendering on Java 8. Item 1 as specified, `web.adapter.jcef`'s strategy beginning "jcef 152". Item 3: W1 passed
without the sleeper, every wait ended by an event or a settle; the `web.path` record names `agent` with its reason, or
`cdp` with the endpoint and `connectMs`; every web call names its path (`web.browsers`, `web.cdp.status` and
`web.reload` always `agent`); no `web.fallback`; the click's wait ended on `web.tap {name: webhost.borrowed}`, with a
port on the CDP path's own event (`source: cdp`); the capture was `{item: hammer, qty: 2}`. Item 5: the negatives ended
as §9.3 says, with `web-browsers.json`, one aria JSON naming Borrow and one PNG of at least 8 colours for each of the
three that fail; `cdp-without-port` runs in the launches without a port. Item 8: before the tap `webhostDiagnostics()`
answers `{listeners: 2, dispatchWrapped: false}`, after a tap of `[webhost.*, webhost.borrowed]` through the first agent
`{listeners: 3, dispatchWrapped: true}`, and after `agent.disable` and a fresh attach
`{listeners: 2, dispatchWrapped: false}` again; the fresh agent's refs are textually equal to the first ones (`h2` to
`h4` off screen), so the test compares the browsers by tag, native id and URL rather than expecting new refs.

The CLI checks ran once per target Java, in the `JAVAAGENT` off-screen launch with a port:
`skillroy run <W1> --mode replay --json --out <dir> --pid <pid> --discovery-dir <dir>` with a `--pack` for each of the
seven jars of the CDP set (§2.1) and one for the pack jar exits 0 with `results[0].web.path` `cdp`, and with the pack
jar alone it exits 0 with `agent`.

The exit criteria of Phase 4 were met in these runs, on the copy of the implementer who built the acceptance: §12.1
and §12.2 as above, the 50 attach attempts of item 6, W1 over both paths from one committed plan (item 3), the kit
against real JCEF (item 9) and against the fakes (§12.1 item 3), `INPUT` and `RAW_PROTOCOL` still reserved; BS5 is
passed by item 6 (D53). The final tree, after the review's fix wave, is run through the orchestrator's Linux matrix:
its three legs (targets on Java 25, targets on Java 8, the agent side on a Java 8 test JVM, each with `-Pjcef`), twice
in a row for the criterion's "twice in a row without a flake"; `../TODO.md` records those runs and the ones above, with
their counts and times. Run on 2026-10-07 (the amd64 image): the two whole-reactor legs were green twice (2725 tests,
0 failures, each round), the soak 50 of 50 within about 2.02 s each time; the agent-side leg was green in the first
round and at the second attempt of the second round, after the open focus flake of the Swing module's display tests
(`WindowActivationDisplayTest`, which predates Phase 4; `../TODO.md`, "Open flake"). The run against a stock Chromium is
a manual procedure, `../../spikes/BS5/README.md`.

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
for a JOGL canvas; as built 2026-10-07 the guard is in place, P1 §6, §14.1); **a browser with no view and no seed is
invisible** (JxBrowser; browser-spi §F23); **`web.dom.click` is a DOM click, not a pointer click** (`INPUT`, Phase
7); **`web.resize` cannot change a windowed JCEF browser's Swing bounds** (`applied: COMPONENT` is reserved).

Recorded during the build (2026-10-07), for the gaps table: **the SPI has no reload and no bitmap source** (the
built-in adapters use the module-internal `BrowserExtras`, D67; an SPI default method is a later amendment; closed by
A37, 4.x, built 2026-10-08: two default methods, §16.3); **a JCEF
older than 2024-01 is served on neither path** (no `getDevToolsClient`: the adapter is `UNAVAILABLE` and reports no
endpoint, §4.5; serving it over CDP alone would need a further amendment); **three member names of the JxBrowser
descriptor wait for a licence holder** (§4.5; the wall "A JxBrowser licence"); **windowed JCEF on a Java 8 target is
still unverified** (§2.5); **a call the CDP path serves reads the agent's sequence three times** (§5.2; two of the
reads are redundant; (4.x, A36, built 2026-10-08) closed: one read, the executor's, §16.2); **a JVM killed while a
traced CDP path is open leaves its owner-only trace directory** in the
temporary directory (§6.4).

Recorded when the kit and the end-to-end acceptance ran against real JCEF (2026-10-07), for the gaps table:
**`web.waitFlag` answers an expression that throws differently on the two paths** (the agent polls on and answers
`ready: false` with `lastError`, the CDP path fails at once with `SCRIPT_ERROR`; one behaviour is to be chosen, §4.2;
(4.x, A35, built 2026-10-08) closed: the agent's on both paths, an expression that does not compile `SCRIPT_ERROR` at
once on both, §16.1);
**a hidden JCEF browser has no bitmap** (Chromium renders nothing for it, so the adapter refuses at once, as the SPI's
Javadoc now allows and kit item 8 accepts, §3, §4.5, §10.3).

Left open by the review's fix wave (2026-10-07), for the gaps table: **the re-probe after `window.opened` has no rate
limit beyond its coalescing** (§4.1; closed by A38, 4.x, built 2026-10-08: at most once a second, §16.4);
**JxBrowser's observer and callback registration and `cdpEndpoints` call the
vendor without a bound of their own** (§4.5; whether they can block needs the product). Choices of the fix wave that a
later review may revisit: the 60 s bound of an outstanding drain (what a frozen page retains against the events a late
answer still delivers, §4.3), the always-false `closed` key in `NOT_FOUND` entries (§4.2), and a late init-script
removal sent from CEF's thread rather than leaving the script registered (§4.5). The gaps table numbers the gaps of this
section 34 to 47.

---

## 14. Decisions taken in this contract

D59-D69 were taken during the build and the review on 2026-10-07 without the owner and confirmed by the owner the same
evening (`DECISIONS.md` B22).

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
| D59 | The trace (2026-10-07, review): the CDP path records it into a private temporary directory of its own (owner-only), deleted when the path closes and when `open` fails, never into the run's output directory; the executor decides at step 1a, from the plan and before the path opens, whether a step or cleanup call may announce a secret parameter, and then opens the path without tracing; `failure/web-trace.zip` is the only trace file a run keeps, recorded as saved only when the path wrote it (§6.4) | the run's output directory as Playwright's artifacts directory (the first build); stopping the trace inside the path at the first call that names `secretParams`; keeping the trace and relying on the secret mask | Playwright appends the live trace (page snapshots with the values of input fields, console text) to files in the directory it is given and never deletes them: a passing run left a typed password in clear in its output directory, and a later failing run's zip carried an earlier run's secrets; a stop inside the path never sees a non-web call whose value the application mirrors into a page; the mask cannot read a trace |
| D60 | The re-probe after `window.opened` (2026-10-07, review): only the adapters whose technology is present but not yet loaded by the application, the registered adapters whose last probe was `UNAVAILABLE` and the adapters registered since the last cycle are probed again, cheaply; a full cycle runs only when one of their results changed; an absent technology waits for reprobe; one re-probe is queued or running at a time, and one queued for more than 30 s no longer holds the next back; no time-based rate limit (§4.1, §13). **Revised by A38 (4.x, built 2026-10-08, §16.4):** the re-probe runs at most once a second: within a second of the start of the last re-probe cycle the worker waits out the rest of that second on its agent worker before it probes, and an event that arrives while a cycle runs gets one further cycle, so no event is dropped and a burst costs at most one further cycle | a re-probe on the first `web.*` call after a `window.opened` (the specification); a full cycle, with the Swing walk, on every `window.opened` while any adapter is `UNAVAILABLE` (the first build) | the dispatcher refuses a `web.*` call whose capability is `UNAVAILABLE` before the module sees it, so the specified rule cannot work where it matters; the first build walked the Swing tree and probed technologies that cannot appear on every window and dialog that opened, unasked, while the executor timed and settled steps; a technology whose gate class the host's loader cannot even find does not appear on its own |
| D61 | The probe's loading rule (2026-10-07, review): the technology's gate class must already be loaded by the host (asked without loading it); only then may the probe load and link the technology's other classes, and it initialises none of them (the gate's state read excepted); kit item 1 checks initialisation and that the probe stays within the technology's own packages (the fixture's optional `initialisedClasses` and `technologyPackages()`), and that nothing is loaded while the gate is absent (§3, §10.1) | "no class the host has not loaded" (the specification); a check that the classes the probe defines are the descriptor's own | looking members up links a class and loads further classes (superclasses, signature types, the classes its verified methods name), and a host has typically not loaded `CefDevToolsClient` before the agent's first call, so the specified rule would answer "JCEF older than 2024-01" for most applications; what the host needs is that no static initialiser of the technology runs and that a target without the technology is not touched; the specified check could not fail with either fake |
| D62 | The JCEF re-send rule (2026-10-07, review): on the shared client a call is sent again at most once, only when it is one of the handle's own protocol reads (`Page.getFrameTree`, `Page.captureScreenshot`), its caller still waits, and the client refused it as closed (JCEF's "Client is closed" with the client's own `isClosed()` true: it was never sent); an evaluation is never sent again by the adapter, and a call that reached the page never; the raw channel (D69) sends nothing again; a client closed as soon as it is fetched (BS8) is fetched once more before the call is sent; reads made by evaluation are retried by the module after a navigation; init scripts are added once per client and removed on detach from every client they were added to, a removal that must follow a late answer sent from CEF's thread (§4.2, §4.5) | sending every call again whose failure message contains "closed" (the first build); a re-send of every read, judged by the adapter (the review's first wording); no re-send at all | Chromium answers a call in flight across a navigation with -32000 "Inspected target navigated or closed": the first build ran clicks twice, ran abandoned calls in the page after the caller's timeout and left a second init script on the application's client; the adapter cannot tell a read from a mutating script, so only the module, which knows its reads (D51), may retry one; without any re-send, the first call to a browser created as BS8 saw (its first client closed) fails |
| D63 | One outstanding drain (2026-10-07, review): at most one tap drain per browser (agent) or page (CDP path) is outstanding; a drain that outlives the poller's bound is awaited and its events published when it answers, never discarded; the same holds for the polls of `web.waitFlag` (§4.3, §5.2) | abandoning a drain at the bound and sending another (the first build); a two-phase drain in the library (read, then acknowledge) | `drain()` empties the page's buffer, so an abandoned drain lost the events it took: a page that stayed busy for more than the bound right after firing made an `await` end in a wrong `SYNC_TIMEOUT` (reproduced against a real Chromium on both paths), and each abandoned 57 KB evaluation stayed queued in the application's DevTools client; a two-phase drain would change the shared library and both paths for no further gain |
| D64 | The CDP path's viewport override (2026-10-07, review): `web.resize` is `Emulation.setDeviceMetricsOverride` on the path's own CDP session of the page, removed by `clear: true` and at close with `Emulation.clearDeviceMetricsOverride` on that session (§5.2) | Playwright's `setViewportSize` (the specification); a removal sent on another session | Playwright cannot remove its override, so `clear: true` pinned the original size, and an override outlives the session and the DevTools connection that set it, so the application's page kept the scenario's size after the run; Chromium ignores a removal from a session that did not set the override; the in-process adapter's protocol call gives both paths one behaviour |
| D65 | The endpoint's ownership (2026-10-07, review): `open()` refuses an endpoint whose `/json/list` names none of the CDP target ids the agent reports in `web.cdp.status`, before any page is probed (no id reported: no check); the JCEF adapter counts a `DevToolsActivePort` file only when it is not older than the JVM's start, less 2 s (§4.5, §6.1) | trusting the configured endpoint; the target ids from a second agent call, `web.browsers`; no age check on the file | a stale `DevToolsActivePort` file in a persistent cache, or a configured port that another process held when CEF started, would attach the run to another browser and evaluate in its pages; the check costs nothing the probe does not already fetch; coarse file times (FAT 2 s, HFS+ 1 s) need the tolerance, and the JVM's start, not the agent's, because in `ATTACH` mode CEF starts first |
| D66 | The floor of `web.browsers` (2026-10-07): while an adapter is present it is never worse than `DEGRADED` (a present adapter's `UNAVAILABLE` enumeration counts as `DEGRADED` with its reason) (§4.1, §4.4) | the worst enumeration state of the present adapters (the specification) | the dispatcher refuses a call whose capability is `UNAVAILABLE`, and `web.browsers` is the call that rebuilds the index: a browser that appeared after readiness would stay invisible until a reprobe |
| D67 | `BrowserExtras` (2026-10-07): `web.reload` and the bitmap's `source` use a module-internal interface the built-in adapters' handles implement; a third-party browser is reloaded with `location.reload()` through `evaluate` and reports `source: "VENDOR"` (§3, §4.2). **Revised by A37 (4.x, built 2026-10-08, §16.3):** `WebBrowser` has both as default methods, `reload(ignoreCache)` (default: that `location.reload()` through `evaluate`) and `bitmapSource()` (default `VENDOR`, enum `BitmapSource`); the built-in adapters override them, the module calls them for every browser and `BrowserExtras` is deleted | `WebBrowser.reload(boolean)` and a bitmap-source accessor in the SPI now | the SPI had been built and pinned as §3 specified it before the need showed; a default method added later is source- and binary-compatible for every adapter; a reload through the technology works where the page cannot run a script, which frozen-target recovery needs from the built-in adapters |
| D68 | `Invocation.secretParams()` (2026-10-07): a read-only accessor of the agent API, empty by default, that the dispatcher fills from `_meta.secretParams` (an extension of A34); the web module masks `web.console` by value until 60 s after a call's budget and by key always; the CDP path hands every call that names secret parameters to the agent (§4.3, §5.2) | masking by key only; the core redacting every event payload; serving such calls on the CDP path | a page's console echo of a filled secret is published outside the call, after the dispatcher's redaction of that call has ended, and only the call's own parameters say which value to look for; the core does not redact event payloads, and doing so for every event would cost every event; the agent can mask an echo only of a call it saw, while a Playwright trace or a driver debug log would carry the value |
| D69 | The `jcef` adapter talks to DevTools through JCEF's raw channel (`CefBrowser_N.executeDevToolsMethod` and `addDevToolsMessageObserver`, resolved by the descriptor's `raw.*` roles) and correlates answers itself; the public `CefDevToolsClient` is only the existence check and the fallback when the raw roles do not resolve (2026-10-07, found by the acceptance) | the public client only (the research note's route) | JCEF 152's client loses an answer that arrives before its message id, which the acceptance hit on every run (kit items 3, 4, 9 and 11 timed out) and which explains BS8's one lost evaluation in twenty; the raw channel is the same package-private API the client itself uses, so nothing of the application's is taken |

### 14.1 Changes to the Phase 1, 2 and 3 contracts that Phase 4 needs

Written into `phase1-contracts.md`, `phase2-contracts.md`, `phase3-contracts.md` and the design overview on
2026-10-07, each passage marked "(Phase 4, specified 2026-10-06, not yet built; P4 §n)" at the time and "(Phase 4,
built 2026-10-07)" since the build. The two rows on P1 §8.5's outputs were missing from this table then; they were
added on 2026-10-07, after the build, and go into P1 marked "(Phase 4, built 2026-10-07)":

| Contract and section | Change |
|---|---|
| P1 §2.1 agent home | `modules/skillroy-agent-web.jar`; seven jars in `DistributionContentIT` |
| P1 §3 (`ErrorCodes`) | `SCRIPT_ERROR` (`-32008`); as built also the `web.*` topic names as constants of `Topics` |
| P1 §4 SPI table (design §2.6 as well) | `WebEngineAdapter`, `BrowserRegistryAdapter` as §3 states them (design §2.6 already had the neutral `BrowserRegistryAdapter`); as built also `Invocation.secretParams()` (D68) |
| P1 §5 topics | the `web.*` topics of §4.3 |
| P1 §6 `swing.capture` | `PAINT` guards embedded-browser components: an exception from a component's `printAll` is caught and a placeholder rectangle painted, the component listed in the result's `skipped` (BS8: a JOGL canvas throws). As built: the capture repaints part by part, a part whose `printAll` throws becomes a crossed placeholder, heavyweight descendants are printed on their own, and `skipped: [{ref, className, error}]` appears when not empty |
| P1 §8.1 scenario YAML | the base verbs of §7.1 and the key `web` (the fourteenth) |
| P1 §8.3 base fact roots | `web` |
| P1 §8.5 run sequence, status mapping | step 1a (path selection), the mapping of §8.1, `path` on call records |
| P1 §8.5 outputs, `failure/` (added 2026-10-07) | the web evidence of §6.4: `web-browsers.json`, `web-<ref>-aria.json`, `web-<ref>.png`, `web-<ref>-error.txt`, `web-trace.zip` |
| P1 §8.5 outputs, `RunResult.detail.snapshot` (added 2026-10-07) | the `WEB` surface of §6.2 after the Swing windows |
| P1 §10 | unchanged; the web acceptance is this file's §12.2 |
| P2 §5 version rules, `Plan.VERSION` | 4 |
| P2 §11.3 records | `web.path`, `web.fallback`, `web.recover`, `path`; as built also `web.trace`, `web.close`, `fact` and the `sync` fields `pathSince` and `source` (§8.2) |
| P3 §4 | plan version 4 supersedes 3 as the written version |
| design §2.4 catalogue | `web.waitFlag`, `web.reload`, `web.frames` folded into `web.browsers`; the signatures of §4.2 |
| design §3.1 | `skillroy-cdp` as an optional module (D49) |

---

## 15. Questions for the owner: answered provisionally 2026-10-06, confirmed 2026-10-07

Answered by the orchestrator on 2026-10-06 in the manner of P2 §17 and P3 §14, so that the build can start; written
into the sections named; **confirmed by the owner on 2026-10-07** without a veto (the interview page: `B21 ok`). A veto
that removes built behaviour is stated with each one.

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

## 16. Phase 4.x amendments

Amendments built on 2026-10-08, after the Phase 4 build and its review, for the small follow-ups of §13 (gaps 38,
45, 40 and 42): contract first, then code, the native reactor green. Each amendment marks the passages it changes
in place "(4.x, A3n, built 2026-10-08)"; A35 and A36 were built by one implementer, A37 and A38 by another, each in an
isolated copy of the tree, and integrated by file list.

### 16.1 A35 `web.waitFlag` answers an expression that throws the same way on both paths (4.x, A35, built 2026-10-08)

- *Gap.* 38 of `../TODO.md` (§13; §4.2 as built 2026-10-07): the agent counted a poll whose expression throws as not
  ready, polled on and answered `ready: false` with `lastError`, so that the base `webWaitFlag` ended `FAIL` /
  `SYNC_TIMEOUT`; the CDP path ended the wait at the first poll that threw, with `SCRIPT_ERROR`, which a step reports
  as `FAIL` / `ASSERTION`. The same scenario failed at once over CDP and waited in process.
- *Decision.* One behaviour on both paths, the agent's. A poll whose expression throws at run time counts as **not
  ready**, and polling goes on until the flag is truthy or the wait's time is up (`timeoutMs`, else the call's
  budget). A wait that ends not ready answers `{ready: false, ms, polls, lastError?}`: `lastError` is the last poll's
  error, its first line, at most 2000 UTF-16 code units, never an argument value, present only when that poll threw.
  The base verb's outcome stays `FAIL` / `SYNC_TIMEOUT` on both paths; the failure's detail carries the result as
  `waitFlag`, `lastError` included, and the message ends with " (last error: <lastError>)" (§8.1). One refinement, on
  both paths: an expression that does not **compile** (a `SyntaxError` from compiling the expression itself, as
  distinct from one thrown while it runs, such as the `SyntaxError` of `JSON.parse("{")`) is `SCRIPT_ERROR` at once,
  after one poll, with `data {browser, message, stack: ""}`, `message` the compile error's first line; a step reports
  it as `FAIL` / `ASSERTION`.
- *The guard.* Both paths poll one page-side guard, `WebLib.flagExpression(expression)` (§4.6), so that they read
  byte-identical shapes. The expression travels as a JSON string, is compiled alone as the body
  `return (<expression>\n)` of a `new Function` (trailing semicolons and whitespace dropped, as `wrap` drops them), and
  the compiled function is called with the window as `this`. The guard evaluates to `{ready: true}`, `{ready: false}`,
  `{ready: false, error: <line>}` (the call threw) or `{ready: false, compileError: <line>}` (compiling threw); it never
  throws itself, needs no library and installs none. *The agent path* (§4.2) sends the guard as every poll, under
  D63's rule as before (one outstanding poll, a poll the page has not answered awaited and not replaced, a load waking
  the wait): a `compileError` is `SCRIPT_ERROR` at once; an `error` becomes `lastError`, which a poll that does not
  throw clears; a channel that still reports a script error for the guard itself counts that poll as not ready, its
  message cut by `WebLib.errorLine`. Its polls no longer carry the library (the former probe, `wrap`, embedded the
  57 KB library in every poll; the guard is a few hundred characters and the expression). *The CDP path* (§5.2) has
  `frame.waitForFunction` poll the guard in a wrapper that returns the guard's object when the flag is ready or the
  expression did not compile, both of which end the wait, and `false` otherwise, so that Playwright polls on through
  a poll that threw. After a wait that timed out, the path makes one more poll, the last, with the guard itself,
  through `waitForFunction` again (the guard always answers an object, so its first evaluation ends the call, and
  `evaluate` has no bound of its own), bounded by 1000 ms and by what is left of the call less 100 ms: its `error` is
  `lastError`, a `ready: true` makes the flag ready after all (with `web.ready {signal: FLAG}`), and a last poll that
  does not answer in time, or fails otherwise, leaves `lastError` out (it only explains a wait that is over; a page
  that closed meanwhile is `STALE_HANDLE`). One operation is outstanding at a time (D63): the wait, then the last
  poll. `polls` stays `1 + ms / pollMs`. A blank expression is `INVALID_PARAMS` (`data.param: expression`) on both
  paths.
- *Alternatives rejected.* Failing at once on any throw (the CDP path's former behaviour): a flag that reads a
  property of an element that does not exist yet is the common case, and every such run would fail spuriously.
  Treating a compile error as not ready: a typo would cost the whole step timeout before it is reported. For the CDP
  path, recording the run-time error in the page for the path to read after the wait: the last poll gives the same
  line without keeping state in the page; polling from the path with `frame.evaluate` every `pollMs`: `evaluate` has
  no bound, so a page that stopped answering would turn `ready: false` into `TIMEOUT`, while Playwright already polls
  in the page.
- *Reason.* A predicate that throws before the page is ready is "not yet", and a step's `timeoutMs` already bounds
  the wait; an expression that does not compile can never become ready, and compiling is pure.
- *Limit.* Compiling text is subject to a page's content security policy (no `unsafe-eval`) except while DevTools
  evaluates. Checked outside the build on such a page with a Chromium headless shell: `Runtime.evaluate` of the guard
  compiles, while a compile from the page's own timer is refused with an `EvalError`. The `jcef` adapter evaluates
  through `Runtime.evaluate`, so its polls compile on any page. JxBrowser's `executeJavaScript` on such a page is
  unverified (gap 41); where a channel refuses, the guard reports the page's `EvalError` as a `compileError`:
  `SCRIPT_ERROR` at once, with a message that names the cause. Over CDP, Playwright's `waitForFunction` evaluates its
  own predicate with `eval` from the page's timer after the first poll, which such a policy refuses before the guard
  runs (checked with Playwright 1.63): a flag that is not ready at the first poll ends in `INTERNAL_ERROR` there, as it
  did before A35, which this amendment does not change.
- *Sections amended.* §4.2 (the `web.waitFlag` row; "`web.waitFlag` on the two paths"), §4.6 (the Java side and its
  tests), §5.2 (the `web.waitFlag` row; "Errors, sequence numbers, events"), §6.3, §8.1, §9.3 (the negative
  scenarios), §12.1 (item 7 and the list of tests), §12.2 item 5, §13.
- *Tests.* `skillroy-protocol` `WebLibTest`: the guard's text (the expression once, as a JSON string; compiled alone;
  the four results; the first line and the cut), trailing semicolons and a blank expression, and `errorLine`.
  `skillroy-agent-web` `WaitFlagGuardTest`, against a scripted browser whose fake page evaluates the guard: a flag that
  throws for its first polls and then turns true is `ready: true`; a flag that throws until the end is `ready: false`
  with its `TypeError` as `lastError`, after polling until `timeoutMs`; `lastError` is the last poll's only; a
  channel's script error for the guard itself is a poll that is not ready; an expression that does not compile is
  `SCRIPT_ERROR` at once, after one poll (and `EventsAndTapTest`'s throwing flag is one that throws at run time).
  `skillroy-cdp` `CdpWaitFlagTest`, through the path's fakes (no browser): the same three cases, the last poll (the
  agent's guard byte for byte, bounded whatever the call's budget), a last poll that finds the flag ready, one that
  fails (no `lastError`; `STALE_HANDLE` when the page closed), a blank expression; `ShapesTest` (the shape with
  `lastError`) and `CdpWebPathCallTest` (a last poll that does not answer).
  `skillroy-scenario` `WebEvidenceTest`: both outcomes alike on the agent path and through the fake `cdp` path. End to
  end (§12.2 item 5, profile `jcef`): the negatives `flag-never-ready` and `flag-does-not-compile` (§9.3) in every
  launch, so over CDP with a port and through the agent without one. Outside the build (no JavaScript engine runs in
  it, §4.6), the guard was evaluated in Node 24 and over CDP in a Chromium headless shell, and the CDP path's wait and
  last poll were run with Playwright 1.63 against that shell: a run-time throw, a `SyntaxError` thrown while the
  expression runs, compile errors (`SyntaxError`, and an `EvalError` where code generation is refused), thrown values
  that are not errors, the first line, the cut beside a surrogate pair, a trailing line comment, a flag that throws and
  then turns true, and one that throws to the end.

### 16.2 A36 A call the CDP path serves reads the agent's sequence once (4.x, A36, built 2026-10-08)

- *Gap.* 45 of `../TODO.md` (§13; §5.2 as built 2026-10-07): a result of the CDP path carried the agent's `lastSeq`
  in `seqBefore` and `seqAfter`, read with `evt.query` just before and just after the primitive, while the executor
  read the agent's sequence itself before the call (§6.3) and never used the result's numbers: three `evt.query` calls
  for one served call, two of them redundant.
- *Decision.* The path reads no agent sequence; the executor's read is the only one. `CdpWebPath.call` returns
  `seqBefore` and `seqAfter` as 0 (`CdpWebPath.NOT_READ`), and the executor (`WebRun.call`) writes the agent sequence
  it read before the call in place of both before the result is used or recorded. So a call the path serves makes one
  `evt.query` (none for a read the executor makes on its own, a fact, the surface or evidence, which takes the highest
  agent sequence the run has seen, as before), and its `call` record's `seq` is `{before: n, after: n}` in the agent's
  numbering, `n` the executor's read; the `seq` of a call the agent served is unchanged. No type changes: `RpcResult`
  keeps its two `long` fields and `WebPath.call` its signature (its Javadoc says that a non-agent path need not read
  the agent's sequence).
- *Alternatives rejected.* Passing the executor's number into the path (a new `WebPath.call` parameter or a
  `CallOptions` component): an API change for a number the path does not use; leaving the path's zeros in the record:
  a `seq` of 0 would read as an empty bus; keeping one read in the path and dropping the executor's: the executor needs
  the number before the call to start its waits from it, whichever path serves the call.
- *Reason.* The executor waits from its own read and never used the path's two; they cost two round trips to the
  agent per served call, and a failing `evt.query` made the path refuse a call (`UNSUPPORTED`) that it could serve.
- *What changed in behaviour.* An agent whose `evt.query` fails no longer makes the CDP path refuse calls; the executor
  then takes the highest sequence the run has seen, as it did. `seq.after` of a served call is no longer the agent's
  sequence after the call; nothing used it (§6.3).
- *Sections amended.* §5.2 ("Errors, sequence numbers, events"), §6.1 (the note after `WebPath`), §6.3, §8.2,
  §12.1 (the list of tests), §13.
- *Tests.* `skillroy-cdp` `CdpWebPathCallTest`: a served call of each kind (`web.evaluate`, `web.dom.text`,
  `web.ariaSnapshot`, `web.dispatchEvent`, `web.waitFlag`, `web.resize`) makes zero `evt.query` calls, is served while
  the agent's `evt.query` fails, and answers `NOT_READ` in both numbers. `skillroy-scenario` `WebPathTest`: two step
  calls the fake `cdp` path serves make one sequence read each and none for the fact root's text reads through the
  path, and each call record's `seq` is the executor's read as `before` and `after`, not the path's numbers.

### 16.3 A37: the SPI gains `reload` and the bitmap's source as default methods (gap 40)

**Gap.** Gap 40 of `../TODO.md` ("Open SPI and DSL gaps"), recorded in §13 during the build: the SPI had no reload and
no bitmap source. `web.reload` and the `source` of `web.bitmap` went through `BrowserExtras`, an interface internal to
`skillroy-agent-web` that the built-in adapters' handles implemented; the module reloaded a third-party adapter's
browser with `location.reload()` through `evaluate` and reported its bitmap as `VENDOR`, and such an adapter could offer
neither its technology's reload nor its bitmap's source (D67).

**Decision** (4.x, A37, built 2026-10-08).

- `WebBrowser` (`ai.skillroy.testjava.agent.api.spi.web`) gains two default methods, and the package the enum
  `BitmapSource { PROTOCOL, VENDOR }`:
  - `CompletableFuture<Void> reload(boolean ignoreCache)`, shaped as the interface's other asynchronous operations
    (`bitmap()`, `resize`): a future, no timeout parameter, the caller's bounded wait, the failures of §3 (closed:
    `IllegalStateException`). It completes once the reload was requested; the new document's load is a `LOAD_FINISHED`
    through `subscribe`. Its default is the module's former fallback: `location.reload()` evaluated in the main frame
    through `evaluate`, with `WebEval`'s default timeout (`location.reload(true)` when `ignoreCache` is set, a
    non-standard argument that Chromium ignores: `ignoreCache` is honoured only by an adapter that overrides), completed
    when that evaluation answers and failed as it fails (without `EVALUATE` with `UnsupportedOperationException`; an
    `evaluate` that throws fails the future); cancelling the future cancels the evaluation, so that the module's
    abandoned wait abandons it as before. A reload by the page's own script is a navigation the page starts, which a
    technology may refuse (Chromium refuses it for a `data:` document) and which a frozen page never makes; an adapter
    whose technology can reload from outside the page overrides the method.
  - `BitmapSource bitmapSource()`: where `bitmap()` comes from; informational, answered from what the adapter knows,
    never null; the default is `VENDOR`.
- The built-in adapters override both. `jcef`: `CefBrowser.reload()` / `reloadIgnoreCache()` through the descriptor's
  `reload` roles (`Page.reload {ignoreCache}` on the handle's channel where they do not resolve), and `PROTOCOL`.
  `jxbrowser`: `Navigation.reload()` / `reloadIgnoringCache()` through the descriptor's `reload` roles, and `VENDOR`;
  where those roles do not resolve it falls back to the SPI's default (before A37 it refused with
  `UnsupportedOperationException`), so that reload is total for every browser that can evaluate.
- `BrowserExtras` is deleted. `web.reload` calls `reload(ignoreCache)` for every browser, and `web.bitmap` reports
  `bitmapSource()` as its `source` (`VENDOR` when an adapter's own answer is null or throws). The primitives'
  parameters, results, capabilities and error mapping are unchanged; for a third-party browser without `EVALUATE`
  `web.reload` is still `CAPABILITY_UNAVAILABLE`, now with the adapter's own reason.
- No new `WebCapability` flag: reload is total through its default, and the source is informational.
- Compatibility, as D67 foresaw: an adapter built against the Phase 4 SPI links and runs unchanged and gets the
  defaults; the SPI's signatures still name only `java.*` and the agent API.

**Alternatives rejected.** A `WebCapability.RELOAD` flag: a flag for an operation every browser can perform through its
page is noise, which every adapter would have to report and the module to check. Keeping `BrowserExtras` beside the
SPI methods: two ways to do one thing, one of them closed to a third-party adapter.

**Sections amended.** §3 (the `WebBrowser` block, the enum `BitmapSource`, the list of differences, the bullet that
was "*Not in the SPI*"), §4.2 (`web.bitmap`, `web.reload`), §4.5 (both adapters), §10.1 (items 10 and 12), §10.2,
§10.3, §12.1, §13, §14 (D67).

**Tests** (as built 2026-10-08).

- `skillroy-agent-api` (Java 8): `WebSpiShapeTest` pins both methods as default methods of `WebBrowser`, the enum's
  constants in order, and the new type's signatures; `WebBrowserDefaultsTest` (new) runs the defaults on a browser that
  implements only the abstract methods: `reload(false)` sends `location.reload()` and `reload(true)`
  `location.reload(true)` to the main frame, without `awaitPromise`; the future is pending until the evaluation answers
  and then completes with null; it fails with the evaluation's own exception (`WebScriptException`,
  `UnsupportedOperationException`, `IllegalStateException`), also when it reached the adapter's future through a
  dependent stage; an `evaluate` that throws, or answers no future, fails the future instead of throwing; cancelling
  the future cancels the evaluation; `bitmapSource()` is `VENDOR`; a browser that declares neither method gets both.
- `skillroy-agent-web`: `ViewPrimitivesTest` (`web.reload` goes through a handle's override, and through the default's
  `location.reload()` and `location.reload(true)` for a handle without one; a browser without `EVALUATE`, whose
  evaluation is refused, answers `CAPABILITY_UNAVAILABLE` with the adapter's reason; a reload whose evaluation never
  answers answers `TIMEOUT` and the evaluation is cancelled; `web.bitmap`'s `source` is `VENDOR` by default, the
  override's `PROTOCOL`, and `VENDOR` for an override that answers null or throws); `JxBrowserAdapterFakeTest` (a line
  with the reload roles reloads through the fake's `Navigation.reload()`, a line without them through the page, the
  SPI's default, without calling it; a load follows and the page is a new document either way); the kit's item 12 and
  item 10's `reload`, in the four runs against the fakes (`JcefFakeKitTest`, `JcefFakeRawKitTest`,
  `JxBrowserFakeKitTest`, `JxBrowserFakeChainedKitTest`).
- Against real JCEF, item 12 runs with the kit in the web host's test JVM, in the Linux image under the profile `jcef`
  only (§10.3); it had not been run when A37 was built, on a machine without that image: the orchestrator's matrix
  runs it.

### 16.4 A38: the re-probe after `window.opened` runs at most once a second (gap 42)

**Gap.** Gap 42 of `../TODO.md`, left open by the review's fix wave (§13): the re-probe after `window.opened` (§4.1,
D60) was coalesced, one queued or running at a time, and cheap, but had no time-based limit: in a target that opens
windows in quick succession (dialogs, pop-ups), each window could start a re-probe as soon as the previous one ended.
The review had proposed one a second. Besides, an event that arrived while a re-probe was running was coalesced into it
although that re-probe might already have probed, so the event was in effect dropped until the next window or a
reprobe.

**Decision** (4.x, A38, built 2026-10-08). The re-probe runs at most once a second and no event is dropped, inside the
module's existing plumbing (the queued mark and the agent worker that runs the re-probe), with no new thread, timer or
scheduler:

- Every `window.opened` marks a re-probe pending and, unless a re-probe worker is queued or running (and not lost:
  queued less than 30 s ago, as before), posts one to an agent worker. The publishing thread, often the EDT, only sets
  the mark and posts.
- The worker runs while a re-probe is pending. When some adapter is a candidate (one of the three kinds of §4.1, or the
  set of adapters changed) and the previous re-probe cycle started less than 1 s ago, it first waits out the rest of
  that second on its own agent worker thread: a bounded wait of at most 1 s, on a monitor that uninstall wakes, never
  on the EDT. Then it clears the mark and runs the cycle (the cheap probes of §4.1 and, on a change, the full cycle).
  An event that arrives during the wait is covered by that cycle; one that arrives while the cycle runs marks it
  pending again, and the same worker runs one further cycle, a second after the start of the one before. So every
  event is followed by a cycle that starts after it, and a burst costs at most one further cycle.
- Without a candidate the worker neither waits nor probes, and that round is no cycle (a target without the technology
  still pays one short task per event, coalesced). The readiness cycle and `agent.capabilities {reprobe: true}` are not
  paced.
- The worker renews its queued time every round, so that a long burst is not taken for a lost post; an event that
  arrives just as the worker finishes makes it, or the event itself, post a new one.
- Uninstall wakes a waiting worker, which then probes nothing.

**Alternatives rejected.** A re-probe scheduled one second later on a timer or a scheduler: a thread the module would
own (§2.4), which the gap's condition ("cheap") excluded. Dropping an event that arrives within the second: a window
that brought the technology would wait for the next window or a reprobe. Waiting on the publishing thread: often the
EDT.

**Sections amended.** §4.1 ("The re-probe after `window.opened`"), §12.1, §13, §14 (D60).

**Tests** (as built 2026-10-08). `skillroy-agent-web` `WindowOpenedPaceTest` (new), with an injected clock whose wait
advances it at once and records each wait with its thread, and an adapter whose probe can be held: of 100 events, the
first starts a cycle and the other 99 arrive while it probes, and they cause exactly two cycles, the second one second
after the first, after one wait of the rest of the second on an agent worker; an event one second after the last cycle
starts one at once, without a wait; an event 300 ms after it, published on the EDT as Swing publishes it, waits 700 ms
on an agent worker, never on the EDT; events without a candidate neither wait nor probe; uninstall wakes a waiting
worker, which probes nothing; and, with the real clock, the second of two cycles starts at least 900 ms after the
first. The re-probe tests of `WebCapabilitiesTest` run with the injected clock, unchanged.
