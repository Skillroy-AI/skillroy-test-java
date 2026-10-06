# Driving and observing Swing/AWT from inside the JVM

Topic: how an agent running inside a Java 8+ Swing/AWT target JVM can inject input, find components, observe
state (tree, accessibility, screenshots) and handle modal dialogs, both under Xvfb on Linux and on Windows, and
which existing Swing test libraries could help. Research date: 2026-10-01.

## Summary

In-process event injection is a sound way to drive a Swing application, but the JDK 8 source shows why synthetic
keys are fragile. KeyEvents *posted* to the EventQueue are retargeted by `DefaultKeyboardFocusManager` to the
current focus owner or focused window, and silently dropped if there is none. A non-posted
`Component.dispatchEvent(KeyEvent)` on the EDT keeps its target component; this is exactly Jemmy's QUEUE dispatch
model. Keys sent through XTEST (`xdotool`, `java.awt.Robot`) also depend on X keyboard focus, which under an Xvfb
with no window manager exists only after the application itself requests it. `java.awt.Robot` works under Xvfb
(XTEST is present) but is bound to the screen and to focus, and fails in Windows session 0 or in locked or
disconnected RDP sessions. `Component.printAll` into a `BufferedImage` captures the lightweight component tree,
including a JxBrowser `BrowserView` in OFF_SCREEN mode (a lightweight component), with no native dependency.

Every candidate Swing test library is stale: AssertJ Swing 3.17.1 (September 2020, Java 8 bytecode, Robot-centric,
Apache 2), Jemmy v2 (OpenJDK repo archived; still republished by NetBeans as
`org.netbeans.external:jemmy-2.3.1.1:RELEASE300` in May 2026, Java 6 bytecode, GPLv2 + Classpath exception),
FEST 1.2.1 (2010), UISpec4J (replaces the AWT toolkit, so it cannot attach to a running JVM), Marathon 5.5 (2021),
and SwingLibrary / RemoteSwingLibrary (Jemmy-based agents, 2022 / 2024). QF-Test is the only actively maintained
option and it is commercial and out-of-process. Recommendation: hand-roll `skillroy-agent-swing` on the JDK 8
`java.awt` / `javax.swing` / `javax.accessibility` APIs (zero third-party classes on the application class path,
forward-compatible to JDK 25), modelled on Jemmy's QUEUE dispatch and waiter patterns, with an injection ladder
(semantic input supplied by the domain module, then widget action, then EDT `doClick` / `dispatchEvent`, then
Robot) and a Playwright-style accessibility/tree snapshot as the AI-facing observation primitive. Detect modal
dialogs through `Window.getWindows()` and an `AWTEventListener`; dismiss them with `JOptionPane.setValue` or
`doClick` on the EDT via `invokeLater`, never `invokeAndWait`, for any action that may open a modal.

Ids are stable and intentionally non-contiguous.

Notes on the evidence below. JDK file:line references are into the OpenJDK 8u492 source (`src.zip`). Confidence
values are those of the original research: *verified* (checked against a primary source), *likely*, *uncertain*.
"Medium (not re-verified on a neutral target)" marks a claim whose direct observation was made on a non-public
application and has not yet been repeated on a toy program or the sample lending desk.

## Findings

### F0

`java.awt.Robot` cannot be constructed when `GraphicsEnvironment.isHeadless()` is true, and on X11 it throws
`AWTException` if the XTEST 2.2 extension is missing or disabled. Robot events enter the platform's native input
queue, unlike events posted to the AWT EventQueue.

Evidence: Java 8 `Robot` javadoc: "This exception is always thrown when GraphicsEnvironment.isHeadless() returns
true"; "X-Window systems will throw the exception if the XTEST 2.2 standard extension is not supported (or not
enabled) by the X server"; "Using the class to generate input events differs from posting events to the AWT event
queue ... the events are generated in the platform's native input queue" --
https://docs.oracle.com/javase/8/docs/api/java/awt/Robot.html

Confidence: verified.

### F1

`java.awt.Robot` works under Xvfb: XTEST is present on a stock Xvfb (it is one of the testing extensions that the
X server's `-tst` option disables, so it is on unless turned off). `xdotool` also uses XTEST for key and mouse
events when no `--window` is given.

Evidence: xdotool.pod: "for key and mouse events, we only use XSendEvent when a specific window is targeted.
Otherwise, we use XTEST" -- https://github.com/jordansissel/xdotool/blob/master/xdotool.pod ; Xserver(1) lists
XTEST among the testing extensions disabled by `-tst` --
https://manpages.debian.org/testing/xserver-common/Xserver.1.en.html

Confidence: medium (not re-verified on a neutral target). Needs spike BS4.

### F2

KeyEvents posted via `EventQueue.postEvent` are flagged `isPosted` and then retargeted by
`DefaultKeyboardFocusManager` to the current focus owner (or the focused window); if neither exists the event is
silently dropped. A KeyEvent delivered with `Component.dispatchEvent(e)` (not posted) keeps its original component
as target and is redispatched to it as long as that component is showing, `canBeFocusOwner()` and enabled. It
does not need to own focus.

Evidence: JDK 8u492 `java/awt/EventQueue.java:275` and `:754` (`theEvent.isPosted = true`);
`java/awt/DefaultKeyboardFocusManager.java:1052-1059` `preDispatchKeyEvent`: "if (((AWTEvent) ke).isPosted) {
Component focusOwner = getFocusOwner(); ke.setSource(((focusOwner != null) ? focusOwner : getFocusedWindow())); }
if (ke.getSource() == null) { return true; }"; `:827-836` `dispatchKeyEvent`: "Component focusOwner =
(((AWTEvent)e).isPosted) ? getFocusOwner() : e.getComponent(); if (focusOwner != null && focusOwner.isShowing() &&
focusOwner.canBeFocusOwner()) { ... redispatchEvent(comp, e); }"; `java/awt/Component.java:4710-4712` (`public
final void dispatchEvent` -> `dispatchEventImpl`) and `:4748-4760` (KFM pre-dispatch hook).

Confidence: verified.

### F3

Jemmy's QUEUE dispatching model is precisely "`component.dispatchEvent(event)` on the EDT", with an alternative
ROBOT model using `java.awt.Robot`, plus `waitQueueEmpty()` for synchronisation. Direct dispatch is therefore an
established, long-used pattern for in-process Swing driving.

Evidence: `org/netbeans/jemmy/EventDispatcher.java` javadoc: "One dispatch model uses the managed component's event
queue to dispatch events. The other dispatch model uses java.awt.Robot to generate native events";
`dispatchEvent(AWTEvent)`: "If (getDispatchingModel & JemmyProperties.QUEUE_MODEL_MASK) == 0 dispatched event
directly, otherwise uses javax.swing.SwingUtilities.invokeAndWait(Runnable)" calling `component.dispatchEvent(event)`
-- https://raw.githubusercontent.com/openjdk/jemmy-v2/master/src/org/netbeans/jemmy/EventDispatcher.java

Confidence: verified.

### F7

`AbstractButton.doClick()` is `doClick(68)`: it arms and presses the model, calls `paintImmediately`, runs
`Thread.sleep(68)` on the calling thread, then releases, which fires the ActionEvent. It must be called on the EDT
and therefore blocks the EDT for about 68 ms per click. `AccessibleAbstractButton.doAccessibleAction(0)` is simply
`doClick()`, so the Accessibility API is a uniform "activate" path for any `AbstractButton`.

Evidence: JDK 8u492 `javax/swing/AbstractButton.java:355-357` (`public void doClick() { doClick(68); }`),
`:367-378` (`model.setArmed`/`setPressed`, `paintImmediately`, `Thread.currentThread().sleep(pressTime)`,
`setPressed(false)`, `setArmed(false)`), `:2550-2557` (`doAccessibleAction(int i) { if (i == 0) { doClick(); return
true; } ... }`).

Confidence: verified.

### F8

EDT threading rules relevant to an agent thread: `EventQueue.invokeAndWait` throws `java.lang.Error` if called on
the EDT. A modal `Dialog.show()` runs a secondary event loop on the EDT (`EventQueue.createSecondaryLoop` with a
modal filter), so `invokeAndWait` runnables still execute while a modal dialog is open; but an `invokeAndWait`
whose runnable opens a modal dialog does not return until that dialog closes, so the agent thread is blocked.
`Robot.waitForIdle` throws `IllegalThreadStateException` on the EDT.

Evidence: JDK 8u492 `java/awt/EventQueue.java:1330-1331` ("throw new Error(\"Cannot call invokeAndWait from the
event dispatcher thread\")"); `java/awt/Dialog.java:1070-1085` (`EventDispatchThread edt =
eventQueue.getDispatchThread(); modalityPushed(); secondaryLoop = eventQueue.createSecondaryLoop(cond, modalFilter,
0); secondaryLoop.enter()`); `Robot` javadoc for `waitForIdle`: "IllegalThreadStateException - if called on the AWT
event dispatching thread" -- https://docs.oracle.com/javase/8/docs/api/java/awt/Robot.html

Confidence: verified.

### F9

A dialog that `JOptionPane` builds itself can be closed programmatically with `JOptionPane.setValue(option)`:
`initDialog` (used by `createDialog`) installs a property-change listener that calls `dialog.setVisible(false)`
when `VALUE_PROPERTY` changes to a non-null, initialised value, and `setValue` fires `VALUE_PROPERTY`. This is
JOptionPane's own close path.

Evidence: JDK 8u492 `javax/swing/JOptionPane.java:995-998` (listener on `VALUE_PROPERTY` ->
`dialog.setVisible(false)`) and `:1964-1968` (`setValue` fires `VALUE_PROPERTY`).

Confidence: verified.

### F12

Under a bare Xvfb with no window manager, JDK 8 detects NO_WM (`XWM.isNoWM`, via the `WM_S0` selection /
SubstructureRedirect probe) and must set X input focus itself (`XBaseWindow.xRequestFocus` -> `XSetInputFocus`)
when the application requests focus. Nothing focuses the window at startup, and AWT discards X-server-originated
focus events in general.

Evidence: JDK 8u492 `sun/awt/X11/XWM.java:247-313` (`isNoWM`), `sun/awt/X11/XBaseWindow.java:595-611`
(`XlibWrapper.XSetInputFocus2`), `sun/awt/X11/XDecoratedPeer.java:1113-1165` (`requestWindowFocus` ->
`toFocus.requestXFocus`), `sun/awt/X11/XFocusProxyWindow.java:80-85` (key events on the focus proxy forwarded to
the owner); Oracle AWT troubleshooting guide: "The X server and some window managers may send focus events to a
window. However, these events are discarded by AWT" -- https://docs.oracle.com/en/java/javase/17/troubleshoot/awt.html

Confidence: verified.

### F13

Why XTEST key events (for example from `xdotool`) may fail to reach a Swing application under a WM-less Xvfb while
pointer clicks do is not conclusively established. Three candidate causes, in order of likelihood:

- (a) XTEST key events go to the X focus window. Under a WM-less Xvfb the Java frame holds X focus only after the
  application itself requested it, and even when AWT receives the key it is posted and retargeted to the KFM focus
  owner, and dropped if there is none (F2).
- (b) The application has no binding for the keys sent: its key handling may be attached only to particular
  focused fields, or its actions may be bound to on-screen buttons rather than keys.
- (c) XTEST keymap or layout mismatches. Less likely, because the JDK 8 XToolkit refreshes the keyboard mapping on
  `MappingNotify`.

Pointer clicks land because X delivers pointer events by position, not by keyboard focus.

Evidence: JDK 8u492 `DefaultKeyboardFocusManager.java:1052-1059`; `sun/awt/X11/XToolkit.java:494-500`
(`MappingNotify` -> `XRefreshKeyboardMapping`); xdotool.pod SENDEVENT NOTES (XTEST vs XSendEvent; "many programs
observe this flag and reject these events" applies only to `--window` mode) --
https://github.com/jordansissel/xdotool/blob/master/xdotool.pod ; keymap issues
https://bugs.debian.org/cgi-bin/bugreport.cgi?bug=740811 and https://github.com/jordansissel/xdotool/issues/150

Confidence: uncertain. Needs spike BS4.

### F14

A posted MouseEvent or KeyEvent aimed at a window that is blocked by an AWT-modal dialog is filtered by the modal
secondary loop's event filter, so synthetic input aimed at the main frame while an application modal prompt is up
will not be delivered. Direct method calls on the EDT (`doClick`, `setValue`, `dispose`) are unaffected.

Evidence: JDK 8u492 `java/awt/Dialog.java:1083` (`eventQueue.createSecondaryLoop(cond, modalFilter, 0)`) with
`modalFilter` = `ModalEventFilter` for the dialog; inference from the filter semantics (not exercised).

Confidence: likely. Needs spike BS4.

### F15

AssertJ Swing: last release 3.17.1 on 2020-09-19 (3.17.0 on 2020-09-13); last commit on main 2020-09-23; repository
not archived, 130 open issues. The jar is built with JDK 1.8.0_242, class-file major 52 (Java 8 bytecode); it
depends on assertj-core 3.17.2 and jarjar'd fest-reflect / fest-util. The architecture is Robot-based
(`BasicRobot`), enforces EDT access via `FailOnThreadViolationRepaintManager` / `GuiActionRunner`, serialises tests
with a keyboard/mouse semaphore and allows one Robot per JVM. It is viable on Java 8 but Robot-centric (native
focus and screen constraints) and brings assertj-core onto the target application's class path.

Evidence: GitHub API `repos/assertj/assertj-swing` (archived=false, open_issues=130) and `/releases` (3.17.1
published 2020-09-19T04:19:48Z); `/commits` (latest 2020-09-23); Maven Central search (3.17.1 timestamp
1600488962000); inspection of
https://repo1.maven.org/maven2/org/assertj/assertj-swing/3.17.1/assertj-swing-3.17.1.jar -> MANIFEST `Build-Jdk:
1.8.0_242`, `BasicRobot.class` major version 52; POM dependencies assertj-core 3.17.2, jsr305 3.0.2; getting-started
guide: "FailOnThreadViolationRepaintManager ... forces a test to fail if access to GUI components is not performed
on the EDT", "forces sequential test execution" --
https://joel-costigliola.github.io/assertj/assertj-swing-getting-started.html ; https://github.com/assertj/assertj-swing

Confidence: verified.

### F16

Jemmy v2: the OpenJDK repository `openjdk/jemmy-v2` is archived (last push 2024-08-14; GPL-2.0, with the Classpath
exception per OpenJDK), and `jemmy-v3` is archived too (2023-04-20). NetBeans nevertheless keeps republishing
Jemmy v2 on Maven Central as `org.netbeans.external:jemmy-2.3.1.1` with each NetBeans release (RELEASE270 Aug 2025,
RELEASE280 Nov 2025, RELEASE290 Feb 2026, RELEASE300 May 2026), class-file major 50 (Java 6 bytecode), jar about
3.1 MB. Jemmy offers operators with name/text choosers, waiters with timeouts, and both QUEUE and ROBOT dispatch
models, so it is the most in-process-friendly design, but it is effectively frozen and GPL-licensed.

Evidence: GitHub API `repos/openjdk/jemmy-v2` (archived: true, pushed_at: 2024-08-14, license: GPL-2.0); GitHub API
`repos/openjdk/jemmy-v3` (archived: true, pushed_at: 2023-04-20); Maven Central search `g:org.netbeans.external
a:jemmy-2.3.1.1` -> RELEASE300 (timestamp 1778503377000 = 2026-05-11), RELEASE290/280/270; inspection of
https://repo1.maven.org/maven2/org/netbeans/external/jemmy-2.3.1.1/RELEASE300/jemmy-2.3.1.1-RELEASE300.jar ->
`JemmyProperties.class` major 50; dispatch models
https://raw.githubusercontent.com/openjdk/jemmy-v2/master/src/org/netbeans/jemmy/EventDispatcher.java ;
https://github.com/openjdk/jemmy-v2 ; Classpath-exception statement from a search summary of the OpenJDK project
page (not independently re-read).

Confidence: verified.

### F17

Other candidates are not viable for an attach-time, in-process agent on Java 8:

- FEST-Swing 1.2.1 (2010, repository dormant since 2015) is AssertJ Swing's ancestor.
- UISpec4J (2.5-RC1 on Central in 2015; master claims JDK 7/8 with 11/17 profiles; last push 2024-03) works by
  replacing the AWT Toolkit, which must happen before AWT initialises, so it cannot be applied to a running JVM.
- Marathon v5 (last release 5.5.0.0 on 2021-01-13, last push 2022-04), Robot Framework SwingLibrary 2.3.0
  (2022-10) and RemoteSwingLibrary 2.3.3 (2024-07) are design precedents (a Java agent injected into the system
  under test, exposing a remote driver, with Jemmy underneath) but stale.
- QF-Test is actively maintained (11.0.0 in July 2026, SUT Java 8-27, Swing supported) but commercial and
  out-of-process.

Evidence: Maven Central: `org.easytesting:fest-swing` 1.2.1 (timestamp 1277181079000); `org.uispec4j:uispec4j`
2.5-RC1 (timestamp 1428197325000); GitHub API `repos/alexruiz/fest-swing-1.x` pushed_at 2015-04-13;
https://github.com/UISpec4J/UISpec4J ("intercepts Swing windows through internal toolkit modifications", "mostly
abandoned since JDK8"); https://sourceforge.net/projects/marathonman/files/marathonv5/ (5.5.0.0, 2021-01-13); GitHub
API `repos/jalian-systems/marathonv5` pushed_at 2022-04-19 and contents `marathon-java/{marathon-java-agent,
marathon-java-driver,...}`; GitHub API `repos/robotframework/SwingLibrary` releases (2.3.0, 2022-10-24);
`repos/robotframework/remoteswinglibrary` releases (2.3.3, 2024-07-01); QF-Test release notes
https://www.qftest.com/doc/manual/en/history.html and requirements
https://www.qftest.com/doc/manual/en/user_installation.html (via search summary: SUT JDK 8-27, QF-Test 11.0.0 on
2026-07-29).

Confidence: likely.

### F18

A Playwright-style accessibility snapshot is available on Java 8 with no dependencies. Every `Component` has
`getAccessibleContext()`; `AccessibleContext` yields accessible name, description, role, state set,
parent/children, and optional `AccessibleAction` (`doAccessibleAction`), `AccessibleText`, `AccessibleValue`,
`AccessibleComponent`, `AccessibleSelection`, `AccessibleTable` and `AccessibleEditableText`. Swing components
implement these (`JButton` -> `AccessibleAbstractButton`, with action 0 = click). Caveat: buttons that show only an
image may have empty accessible names, so the snapshot must merge in `Component.getName()` and any
application-specific name registry.

Evidence: https://docs.oracle.com/javase/8/docs/api/javax/accessibility/AccessibleContext.html ("AccessibleContext
represents the minimum information all accessible objects return..."); `getAccessibleContext` in
https://docs.oracle.com/javase/8/docs/api/java/awt/Component.html ; JDK 8u492
`javax/swing/AbstractButton.java:2550-2557`; the JDK tools jaccesswalker / jaccessinspector and
google/access-bridge-explorer are Windows-only Access Bridge consumers -- https://github.com/google/access-bridge-explorer

Confidence: verified (the image-only-button caveat: medium, not re-verified on a neutral target).

### F19

Screenshots without Robot: `Component.printAll(Graphics)` / `print(Graphics)` (`JComponent.printAll` paints the
component, its border and its children without double buffering) render the lightweight tree into a
`BufferedImage` on the EDT regardless of X focus, window stacking or obscuring windows. This does not render
heavyweight children. JxBrowser in OFF_SCREEN mode exposes `BrowserView` as a lightweight component that paints its
pixel buffer with `Graphics2D`, so embedded web view content is captured by `printAll`; in HARDWARE_ACCELERATED
mode the native surface is placed above the Java window and would not be.

Evidence: https://docs.oracle.com/javase/8/docs/api/java/awt/Component.html#printAll-java.awt.Graphics- ("Prints
this component and all of its subcomponents"); JDK 8u492 `javax/swing/JComponent.java:1137-1145` (`printAll` ->
`print` / `printComponent` / `printBorder` / `printChildren`); TeamDev JxBrowser rendering guide: an OFF_SCREEN
`BrowserView` is "a lightweight component that reads pixels from the buffer and displays them using standard UI
toolkit graphics, such as Graphics2D in Swing"; the HARDWARE_ACCELERATED surface is "always placed above the Java
window" -- https://teamdev.com/jxbrowser/docs/guides/rendering/

Confidence: verified.

### F20

An agent loaded into a running target JVM needs no DISPLAY handling of its own: it executes inside the target JVM
and shares its already-initialised Toolkit / X connection. Only the external attacher process should avoid AWT
(run it with `-Djava.awt.headless=true`). Robot and Swing are available in-process provided the target JVM itself
was not started with `java.awt.headless=true`.

Evidence: Java 8 `Robot` javadoc on the headless exception -- https://docs.oracle.com/javase/8/docs/api/java/awt/Robot.html ;
inference from the JVM process model (one Toolkit per JVM).

Confidence: likely.

### F21

Xvfb specifics. The default screen is 1280x1024x24 and any WxHxD may be requested with `-screen`. `-dpi` only
informs the server, and JDK 8 performs no HiDPI scaling on Linux (HiDPI for Windows and Linux arrived with JEP 263
in JDK 9), so Swing geometry is purely pixel-based. A 16-bit depth (WxHx16) degrades pixel-exact image
comparisons. JDK 8 on Linux requires fontconfig and at least one sans/serif/monospace font family (for example
DejaVu), or font initialisation fails with `NoClassDefFoundError: sun.awt.X11FontManager`.

Evidence: https://manpages.debian.org/testing/xvfb/Xvfb.1.en.html ("-screen screennum WxHxD", default
1280x1024x24); https://manpages.debian.org/testing/xserver-common/Xserver.1.en.html (`-dpi`);
https://openjdk.org/jeps/263 ("Java2D and AWT use the XLib library, which does not support HiDPI ... Java
applications can look 2 to 3 times too small on HiDPI displays on Linux"); fontconfig/DejaVu requirement
https://knowledge.broadcom.com/external/article/212419/reporter-reports-generation-fails-with-o.html and
https://www.ibm.com/support/pages/node/4812873

Confidence: verified.

### F22

The Jenkins Xvfb plugin supports per-executor display numbers with an offset and a "parallel build" mode, so
several Xvfb instances can coexist on one node; the screen is configured as WxHxD (default 1024x768x24). Xvnc
(TigerVNC) is a drop-in alternative to Xvfb + x11vnc that bundles the VNC endpoint. Neither provides a window
manager, which matters only for native (Robot / xdotool) keyboard focus, not for in-process injection.

Evidence: https://raw.githubusercontent.com/jenkins-infra/plugins-wiki-docs/master/xvfb/README.md (display name
offset, parallel build mode, screen WxHxD default 1024x768x24);
https://www.jvmhost.com/articles/java-gui-headless-server-xvnc-xvfb-x11vnc/ (Xvnc vs Xvfb + x11vnc).

Confidence: likely.

### F23

Windows: services run in session 0 and "cannot directly interact with a user as of Windows Vista"
(`SERVICE_INTERACTIVE_PROCESS` is blocked by `NoInteractiveServices=1` on current Windows). `java.awt.Robot` screen
capture returns black, and native input is not delivered, when the session is locked or the RDP session is
disconnected. Consequently a target application launched as a service, or in a locked or disconnected session,
cannot be driven by Robot or UI automation, whereas in-process `EventQueue` / `dispatchEvent` / `doClick` and
`printAll`-based capture do not depend on the interactive desktop (expected to work; untested).

Evidence: https://learn.microsoft.com/en-us/windows/win32/services/interactive-services ("Services cannot directly
interact with a user as of Windows Vista"; "All services run in Terminal Services session 0";
`NoInteractiveServices` default 1); locked-screen black capture
https://coderanch.com/t/538843/java/screenshot-java-robot-class-woring ; RDP-disconnect screenshot failure
https://forum.uipath.com/t/take-screenshot-fails-in-unattended-mode-when-rdp-disconnects/342665 ; the in-process
claim is inference from the Robot javadoc distinction between the native input queue and the AWT EventQueue.

Confidence: likely.

### F25

Agent-side diagnostics that settle whether injected keys land, without guessing: install a
`Toolkit.addAWTEventListener` with `KEY_EVENT_MASK | FOCUS_EVENT_MASK | WINDOW_FOCUS_EVENT_MASK` (Oracle recommends
exactly this for focus debugging) and dump `KeyboardFocusManager.getFocusOwner()` / `getFocusedWindow()` /
`getActiveWindow()` while sending keys with `xdotool`; externally, `xdotool getwindowfocus` shows the X focus
window. This event tap should be a first-class primitive (it is also the basis for recording).

Evidence: https://docs.oracle.com/en/java/javase/17/troubleshoot/awt.html (`AWTEventListener` snippet with
`FOCUS_EVENT_MASK | WINDOW_FOCUS_EVENT_MASK | WINDOW_EVENT_MASK`);
https://github.com/jordansissel/xdotool/blob/master/xdotool.pod (`getwindowfocus`); JDK 8u492
`DefaultKeyboardFocusManager.java:1052-1059`.

Confidence: likely.

## Recommendations

### R0

Build `skillroy-agent-swing` by hand on the JDK 8 `java.awt` / `javax.swing` / `javax.accessibility` APIs only
(compile with `--release 8`; load no third-party classes into the application, or shade anything added under a
private package; decision B4). Reason: every Swing test library is frozen (AssertJ Swing 2020, Jemmy archived,
FEST 2010, Marathon 2021), they are Robot-centric or toolkit-replacing, and they would put assertj-core, FEST or
Jemmy onto the target application's class path next to the application's own libraries. Borrow Jemmy's ideas
(operators, QUEUE dispatch, waiters with timeouts) rather than its jar. Keep AssertJ Swing 3.17.1 (Apache 2) as an
optional, separately loaded "Robot fidelity" adapter only if a Robot-level acceptance tier is wanted.

### R1

Expose an explicit injection ladder as primitives, each returning which rung actually fired:

| Rung | Name in the design | How | Notes |
|---|---|---|---|
| 1 | `SEMANTIC` | The domain module feeds input to the application below the UI (for example into the application's own input queue). | Fastest, UI-independent. |
| 2 | `ACTION` | Locate the application's widget by name and invoke its own action handler (an `ActionFirer`). | Exercises the application's widget logic without needing focus. |
| 3 | `COMPONENT` | On the EDT, `requestFocusInWindow()` on the component, wait until `hasFocus()` (bounded, for example 500 ms), then `doClick()`; then wait for the application's next state change (the domain's `SyncPolicy` ready signal) rather than a fixed delay. | Real Swing event path. |
| 4 | `ROBOT` | `java.awt.Robot` mouse and keys at screen coordinates. | Xvfb only with XTEST; fails in Windows session 0. |

Default the DSL to rung 1 for data-entry steps and rung 3 for steps whose purpose is UI verification
(decision B12). The design adds one more `swing.click` rung, `DISPATCH`: non-posted mouse events delivered straight
to the component, for components that have no action to fire (the mouse counterpart of R2's key dispatch).

### R2

Keyboard text entry primitive. For `JTextComponent` targets prefer the semantic path: `setText` or a `Document`
insert on the EDT, then dispatch a `KEY_PRESSED` `VK_ENTER` KeyEvent directly with `component.dispatchEvent` on
the EDT, so that key listeners attached to the field (such as an Enter handler) fire. For "type like a user" use
non-posted `Component.dispatchEvent(KeyEvent)` triples (PRESSED / TYPED / RELEASED) targeted at the component; this
bypasses `DefaultKeyboardFocusManager` retargeting, unlike `EventQueue.postEvent`. Fall back to `Robot.keyPress`
only when native fidelity is explicitly requested. Never use `EventQueue.postEvent` for keys unless you have first
verified that `KeyboardFocusManager.getFocusOwner()` is the intended target.

### R3

EDT discipline for all primitives. Queries run via `invokeAndWait` with a timeout wrapper (`FutureTask` +
`invokeLater` + `get(timeout)`). Actions run via `invokeLater` + `FutureTask`, and the caller waits on a separate
"settled" condition (an EDT marker runnable has executed AND no new windows AND, optionally, an application state
change reported through the domain's `SyncPolicy`), never `invokeAndWait`, because an action can open an AWT-modal
dialog and would block the agent thread until it closes. Guard against agent code running on the EDT
(`EventQueue.isDispatchThread`) and never sleep on the EDT except through `doClick`'s 68 ms.

### R4

Observation primitives (JSON):

- `windows()`: `Window.getWindows()` with class, name, title, bounds, showing, focused/active (via
  `KeyboardFocusManager`), modal (`Dialog.isModal`), owner.
- `tree(windowId, depth, filter)`: recursive dump with class, name, text/label/tooltip,
  enabled/visible/showing/focusable/hasFocus, bounds (local, plus screen via `getLocationOnScreen` when showing),
  index path, plus domain enrichment from a `NameEnricher` (application name registries, custom widget state) and
  JxBrowser `BrowserView` presence.
- `a11y(windowId)`: `AccessibleContext` walk (role, name, description, states, actions, value, text, selection)
  merged with `getName()` so image-only buttons are still addressable.
- `find(selector)` with `name=`, `class=`, `text~=`, `role=`, and a weak-reference handle registry.
- `focusInfo()`, `dialogs()`.

Make the a11y/tree snapshot the canonical AI-facing representation (Playwright-style) and keep screenshots as
secondary evidence.

### R5

Screenshot primitives:

- `capture(window|component, mode=paint)` renders via `printAll` into a `TYPE_INT_ARGB` `BufferedImage` on the
  EDT. It works without Robot, with obscured windows and in Windows session 0, and it captures JxBrowser
  OFF_SCREEN content.
- `capture(screen, mode=robot)` uses `Robot.createScreenCapture` off the EDT: true pixels including heavyweight and
  other windows, but it needs XTEST and a visible session.

Return PNG bytes plus the component bounds used, and optionally an annotated variant overlaying `find()` handles
for vision-model consumption.

### R6

Modal dialog primitives. `dialogs()` lists showing Dialogs with modality, title, message text (walk for
`JOptionPane` -> `getMessage()`, or a domain `DialogTextExtractor` for custom dialogs) and option buttons.
`dismiss(handle, by=option|buttonText|dispose)` uses `JOptionPane.setValue(option)` on the EDT (which triggers
JOptionPane's own close listener and is the safest path), else `doClick` on the matching button via `invokeLater`,
else `dispose()`. Add a window watcher (`Toolkit.addAWTEventListener` with `WINDOW_EVENT_MASK`) that records
`WINDOW_OPENED` / `WINDOW_CLOSED` so the scenario runner can react to unexpected prompts (for example an inactivity
lock or a device warning).

### R7

Event tap primitive: `Toolkit.addAWTEventListener` with KEY, MOUSE, FOCUS, WINDOW and WINDOW_FOCUS masks into a
bounded ring buffer with timestamps, exposed as `events(since)`. Use it to diagnose injection, to record user
sessions into DSL steps, and to assert that an injected input actually reached its target. Pair it with
`edtState()`: EDT alive, queue depth (`peekEvent`), in-modal-loop (any showing modal Dialog), last dispatched event
time.

### R8

Headless CI recipe:

- Run Xvfb (or Xvnc) at the screen geometry the target application expects, at 24-bit depth (16-bit depth hurts
  image diffs).
- Install fontconfig and DejaVu fonts in the image.
- Do not add a window manager unless a Robot-keyboard tier is adopted; if it is, run a minimal WM such as openbox so
  focus is deterministic.
- Run the attacher with `-Djava.awt.headless=true`.
- On Windows, run the target application in an interactive, unlocked console session, or use only rungs 1-3 and
  paint-mode capture, which do not need the interactive desktop.

### R9

Before freezing the DSL's input rungs for a target, run the one-hour diagnostic that the evidence could not
replace: with the target application under Xvfb, load a throwaway agent that installs the event tap (R7) and dumps
the `KeyboardFocusManager` focus owner and focused window, then issue `xdotool` clicks and keys and compare. This
turns "keys are ignored" reports into a documented mechanism (F13) and shows whether the component rung needs extra
focus handling by default. Needs spike BS4.

## Constraints

### C0

Agent bytecode and all helper classes must be Java 8 (class major 52) and must not rely on JPMS. Design the APIs so
the same module runs unchanged on JDK 25: no `sun.awt.*` usage, only public `java.awt` / `javax.swing` /
`javax.accessibility`.

### C1

Nothing may block the EDT: queries use bounded `invokeAndWait`; actions use `invokeLater` plus a separate
settle/wait. `AbstractButton.doClick()` sleeps the EDT for 68 ms by design.

### C3

Posted KeyEvents (`EventQueue.postEvent`) are retargeted to the KFM focus owner and dropped if there is none; only a
non-posted `Component.dispatchEvent` keeps the target.

### C4

Synthetic events aimed at windows blocked by an AWT-modal dialog are filtered by the modal secondary loop; direct
EDT method calls are not.

### C5

`java.awt.Robot` needs a non-headless JVM, an X server with XTEST (Xvfb has it), and on Windows an interactive,
unlocked session (never session 0). Keep Robot an optional rung.

### C6

Under a bare Xvfb with no window manager, X keyboard focus exists only after the application requests it.

### C7

JDK 8 has no HiDPI scaling on Linux; Swing geometry is in raw pixels of the (virtual) display.

### C8

Keep third-party jars off the target application's class path; shade or avoid Jemmy and AssertJ Swing. Jemmy is
GPLv2 (+ Classpath exception), which may be a licensing constraint for a reusable framework.

### C10

Windows that are obscured by other windows are reachable in-process (`Window.getWindows()`) but not by
screen-coordinate automation.

## Risks

### Risk0

Focus-dependent input under a WM-less Xvfb: input whose delivery depends on focus (posted key events, and custom
widgets that check `hasFocus()` before firing) may silently do nothing, so tests pass or fail
non-deterministically. Mitigate with an explicit focus wait, state-change confirmation through the domain's
`SyncPolicy`, and the event tap (R7).

### Risk1

EDT deadlock or hang: `invokeAndWait` around an action that opens a modal dialog blocks the agent until the dialog
closes; a crashed EDT or a `Thread.sleep` in application code stalls every primitive. All waits must be bounded and
report diagnostics (`edtState`).

### Risk2

Library rot: AssertJ Swing (2020), Jemmy (archived) and Marathon (2021) will not get JDK 25 fixes (for example new
focus or accessibility behaviour). Depending on them in the base framework creates an upgrade cliff for targets
moving to Java 25.

### Risk3

Class-path and loader conflicts: agent code must use the component's own ClassLoader to touch application types
(the application may run its own class loader), and must not load a second copy of a library the application
already carries. Reflective access to application internals couples a domain module to one application release.

### Risk4

Robot on Windows: locked or disconnected RDP sessions produce black captures and dropped native input, and session
0 services cannot be driven at all. Any dependence on rung 4 makes Windows CI fragile.

### Risk5

Accessible names on image-only buttons may be empty, which degrades the a11y snapshot for an AI unless it is merged
with `Component.getName()` and the domain's name registries. Custom grid widgets may need bespoke extraction.

### Risk6

Modal event filtering: posted mouse and key events are dropped while any AWT-modal dialog is up, so a scenario that
races a dialog will lose inputs. Detect dialogs before every UI-level action.

### Risk7

A 16-bit Xvfb depth and the absence of a window manager mean Robot screenshots taken in CI differ from those taken
on developer desktops. Pixel-diff assertions must use paint-mode captures or tolerant comparison.

### Risk8

Jemmy's GPLv2 (+ Classpath exception) licence and archived status may be unacceptable for a reusable framework;
verify licensing before any code reuse beyond ideas.

### Risk9

The diagnosis of why XTEST keys fail to land (F13) is unconfirmed. If the real cause is something else (for example
XIM or the keymap), native-key tiers may still misbehave even with a window manager. Needs spike BS4.
