# BS5: Playwright over CDP to a running Chromium (manual procedure)

**Status.** The criterion of BS5 (`docs/TODO.md`, "Base spikes") is met by the Phase 4 acceptance against JCEF's
Chromium in the Linux image (decision D53 of `docs/design/phase4-contracts.md`): `CdpAttachSoakIT` in
`samples/skillroy-sample-web-host-scenarios` attaches 50 times to the web host with a deliberately frozen page target
and every attempt ends within the external deadline, on 2026-10-07 in every Linux run that reached it (the numbers
are in `docs/TODO.md`). A run against a *stock* Chromium is not a CI gate; it is the manual procedure below, for
whoever needs to show that the attach mechanics do not depend on who launched the browser.

## Procedure

1. Build the tree once (`mvn -o -q -DskipTests verify -Pjcef`), so that `controller/skillroy-cdp/target/` holds the
   module jar and the Playwright jars are in the local Maven cache (`controller/skillroy-cdp` depends on them).
2. Start a stock Chromium (or Chrome for Testing) with a remote-debugging port and a few tabs, for example
   `chromium --remote-debugging-port=9222 --user-data-dir=<fresh dir> about:blank`, then open two or three pages.
3. Freeze one page target: in that tab's own DevTools console run `for (;;) {}` (the tab stops answering; the
   browser process still lists it in `http://127.0.0.1:9222/json/list`).
4. From a JShell or a small `main` on the class path of `skillroy-cdp` (its jar plus `skillroy-scenario`,
   `skillroy-controller`, `skillroy-protocol`, `skillroy-data` and the Playwright artifacts; set
   `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`):
   - `TargetProbe probe = TargetProbe.run(CdpEndpoint.local(9222), CdpOptions.defaults());` and check that
     `probe.unresponsive()` names the frozen target within about the per-target deadline (2 s) while the others are
     listed as responsive;
   - 50 times: `CdpAttach.connect(CdpEndpoint.local(9222), CdpOptions.defaults(), TargetProbe.run(…))` must throw
     `CdpException` of kind `FROZEN_TARGETS` naming that target, each attempt within `connectDeadline` (30 s);
   - once with the probe disabled: `CdpAttach.connect(endpoint, CdpOptions.defaults().withConnectDeadline(
     Duration.ofSeconds(10)), null)` must end with kind `TIMEOUT` within about 10 s and leave no `node` driver
     process behind (`ProcessHandle.current().descendants()`);
   - on a healthy page, after `probe` with the frozen tab closed: `CdpAttach.connect(...)` succeeds,
     `attach.pageFor(<target id>)` answers, `page.screenshot()` returns a PNG and `attach.tracing().start("bs5")` /
     `stop(<path>)` writes a `trace.zip`.
5. Close the browser. Record the Chromium version (`/json/version`) and the slowest attempt with the result.

What this does not prove: compatibility with the older Chromium inside a particular embedded web view (that is
checked where such a target exists; the JCEF web host of Phase 4 is the one in this repository).
