# Attach API and instrumentation on JDK 8 through 25

Scope: feasibility and practice for a Java agent that is dynamically attached to a JDK 8 HotSpot target JVM
(Temurin 8u492), driven by a modern-Java controller (the project's controller baseline is Java 25; the experiments
below used a JDK 21 controller). Covers cross-version attach, Byte Buddy, Byteman, JDI, JFR, JMX, Mockito, jattach
and agent packaging. Research date: 2026-10-01.

Ids are stable and intentionally non-contiguous. Confidence values are carried over unchanged from the original
research.

Related base decisions: B4 (agent baseline and supported target range), B8 (Byteman, JNA and jattach in an
open-source base), B13 (safety defaults). Related base spikes: BS1 (attach matrix), BS2 (control endpoint),
BS3 (Byte Buddy retransformation), BS7 (`javac --release 8`). See `../DECISIONS.md`.

## Summary

Cross-version dynamic attach into a JDK 8 target works, and was verified end to end on Linux x86_64 in a container
(Temurin 21.0.12.1 controller, Temurin 8u492 target, toy target program). A JDK 21 `jdk.attach` client attaches,
reads system properties, and `loadAgent()` runs `agentmain` in the JDK 8 process. Every JDK 10+ client then throws
`AgentLoadException` (`0` or `Failed to load agent library: 0`), because JDK 8 HotSpot replies with a bare `0` where
newer clients expect the `return code: ` prefix introduced by JDK-8165736. The reverse direction (JDK 8 `tools.jar`
client, JDK 21 target) loads the agent and then throws `IOException: Non-numeric value found - int expected`. The
controller must therefore treat that specific exception as success for JDK 8 targets, or use Byte Buddy's emulated
HotSpot attach (JNA), which returned normally, or jattach; in every case it confirms success through the agent's own
control endpoint. On a JDK 8 Linux target the same-OS-user rule is strict in both directions: a non-root client is
rejected client-side, and root cannot attach to a non-root 8u492 JVM with `jdk.attach` or Byte Buddy, while jattach
v2.2 succeeds by switching euid/egid. Re-attaching the same agent jar re-runs `agentmain` on the already-loaded
class, and a newer jar with the same `Agent-Class` is shadowed by the first, so the agent must be a tiny idempotent
bootstrap that loads versioned modules in an isolated class loader (the Arthas / jvm-sandbox pattern).

Byte Buddy 1.18.14 (Java 8 baseline jar since 1.18.7) ran inside 8u492 and changed an already-loaded method via
`AgentBuilder` retransformation with `disableClassFormatChanges()` and `Advice`. Byteman 4.0.27 (Java 6 class
files; only two reflectively loaded classes touch `java.lang.Module`) installed into 8u492 from a JDK 21 attacher,
accepted `bmsubmit` rules on port 9091, fired, checked and unloaded them cleanly, and coexisted with the Byte Buddy
transformer. Mockito 4.11.0 inline (the last Java 8 line) worked in-process, but its static mocks are strictly
thread-local. JDI from JDK 21 talks to a JDK 8 JDWP port (reads fields without suspending; one debugger connection
at a time; reconnect after `dispose()` works), and JDK 21 `jcmd` drove `JFR.start`/`JFR.check` on 8u492, so JDWP
and JFR are usable complementary channels. The agent jar must be compiled with `--release 8`, which hides
`com.sun.tools.attach`, `sun.misc` and `jdk.jfr`; a `com.sun.net.httpserver` control endpoint inside the JDK 8 agent
was verified. JEP 451 warnings and the `-XX:-EnableDynamicAgentLoading` refusal were verified on a JDK 21 target and
matter for targets on Java 21 through 25.

## Findings

### F0

A JDK 21 (21.0.12.1, Linux x86_64) `jdk.attach` client attaches to a Temurin 8u492 HotSpot target in 60-250 ms.
`VirtualMachine.getSystemProperties()` works (`java.version=1.8.0_492`), and `loadAgent()` executes the agent's
`agentmain` inside the JDK 8 process.

Evidence: Experiment, 2026-10-01: Temurin 8u492 target, Temurin 21.0.12.1 controller, Linux x86_64 container, toy
target program. Observed: controller attached in 200 ms; target `java.version=1.8.0_492`; client implementation
`sun.tools.attach.VirtualMachineImpl`; the target log showed `agentmain` running with `retransformSupported=true`
and a Byte Buddy agent installing.

Confidence: verified

### F1

Every JDK 10+ attach client throws `AgentLoadException` after a successful `loadAgent` into a JDK 8 target, because
JDK 8 HotSpot replies with a bare integer (`0`) while newer clients require the prefix `return code: `. jdk11u and
jdk17u clients throw `AgentLoadException` with message `0`; jdk21u and master clients throw
`Failed to load agent library: 0`. Genuine failures surface as the raw code in the message, for example
`Failed to load agent library: 100` (`ATTACH_ERROR_BADJAR`); 101 is `NOTONCP`, 102 is `STARTFAIL`, -4 is
`JNI_ENOMEM`.

Evidence: OpenJDK jdk8u `hotspot/src/share/vm/prims/jvmtiExport.cpp`, where `JvmtiExport::load_agent_library` ends
with `st->print_cr("%d", result)`; OpenJDK master `src/jdk.attach/share/classes/sun/tools/attach/HotSpotVirtualMachine.java`
lines 106-126 (`msgPrefix = "return code: "`, otherwise `throw new AgentLoadException(errorMsg)`) and the error
constants at lines 198-201; jdk11u/jdk17u same file lines 93-108 (`throw new AgentLoadException(result)`); modern
HotSpot prints `return code: %d` in `src/hotspot/share/prims/jvmtiAgent.cpp` (line 564); the format change
originates in https://bugs.openjdk.org/browse/JDK-8165736 (fix version 10). Experiment, 2026-10-01: Temurin 8u492
target, Temurin 21.0.12.1 controller, Linux x86_64 container, toy target program. Observed: `loadAgent` threw
`AgentLoadException: Failed to load agent library: 0` while the target log showed `agentmain` had run; loading a
missing jar produced `Failed to load agent library: 100`.

Confidence: verified

### F2

The reverse direction also misreads the reply. A JDK 8 `tools.jar` client (`sun.tools.attach.LinuxVirtualMachine`)
attaches to a JDK 21 target and loads the agent, but then throws `IOException: Non-numeric value found - int
expected`, because it calls `readInt()` on the `return code: 0` line.

Evidence: Experiment, 2026-10-01: Temurin 21.0.12.1 target, Temurin 8u492 `tools.jar` controller, Linux x86_64
container, toy target program. Observed: attach succeeded (target `java.version=21.0.12.1`), `loadAgent` threw the
`IOException`, and a following ping to the agent's endpoint was answered from Java 21.0.12.1. OpenJDK jdk8u
`jdk/src/share/classes/sun/tools/attach/HotSpotVirtualMachine.java`: `loadAgentLibrary` lines 55-71 (`readInt`) and
`readInt` lines 273-300.

Confidence: verified

### F3

Byte Buddy's `ByteBuddyAgent.attach(File, pid, args)` from JDK 21 to JDK 8 throws
`IllegalStateException("Error during attachment using: ...AttachmentProvider$Compound")` wrapping the same
`AgentLoadException`, although the agent loaded. Byte Buddy's emulated attach,
`net.bytebuddy.agent.VirtualMachine.ForHotSpot.attach(pid)`, returns normally: its `load()` checks only the
completion-status header and ignores the second reply line, so it also cannot report `Agent_OnAttach` failures. The
emulated path requires JNA; `byte-buddy-parent` 1.18.14 declares `version.jna=5.12.1` with scope `provided`.

Evidence: Experiment, 2026-10-01: Temurin 8u492 target, Temurin 21.0.12.1 controller, Linux x86_64 container, toy
target program. Observed: `ByteBuddyAgent.attach` threw `IllegalStateException` (cause
`InvocationTargetException`) while the target log showed the Byte Buddy agent installed; the emulated attach
(`impl=net.bytebuddy.agent.VirtualMachine$ForHotSpot`) returned normally from `loadAgent` and the agent answered a
ping. Byte Buddy source
https://raw.githubusercontent.com/raphw/byte-buddy/master/byte-buddy-agent/src/main/java/net/bytebuddy/agent/VirtualMachine.java:
`checkHeader()` (switch on `Integer.parseInt` of the first line: 0 is ok, 101 is `Protocol mismatch`) and `load()`.
JNA version from https://repo1.maven.org/maven2/net/bytebuddy/byte-buddy-parent/1.18.14/byte-buddy-parent-1.18.14.pom

Confidence: verified

### F4

Double load on JDK 8. Re-attaching the same agent jar invokes `agentmain` again on the already-loaded class, with
static state preserved (counters `agentmain#1`, `#2`, `#3`). Attaching a different jar whose `Agent-Class` has the
same name runs the old class, and the new jar's `agentmain` never executes, because the first jar was appended to the
system class path and the class is already defined. Agent jars cannot be removed from the class path.

Evidence: Experiment, 2026-10-01: Temurin 8u492 target, Temurin 21.0.12.1 controller, Linux x86_64 container, toy
target program. Observed: target log `AGENT agentmain#1`, `AGENT agentmain#2`, `AGENT agentmain#3 args=port=18080`;
a second jar built to print `AGENT-V2` never printed it. The JDK 21 `VirtualMachine.loadAgent` javadoc states that
the JAR file is added to the system class path of the target VM:
https://docs.oracle.com/en/java/javase/21/docs/api/jdk.attach/com/sun/tools/attach/VirtualMachine.html

Confidence: verified

### F5

The same-OS-user rule on a JDK 8 Linux target is strict in both directions with `jdk.attach`.
(a) A non-root client attaching to a root-owned JVM fails client-side with `well-known file /tmp/.java_pid479 is not
secure: file should be owned by the current user (which is 1001) but is owned by 0`.
(b) A root client attaching to a non-root 8u492 JVM fails (`target process doesn't respond within 5500ms`) with the
JDK 21 client, the JDK 8 `tools.jar` client and Byte Buddy's emulated attach. 8u HotSpot honours an `.attach_pid`
trigger file only when it is owned by its own euid, and rejects socket peers whose uid/gid differ, with no exception
for root; a `Broken pipe` results even when the listener is already up.

Evidence: Experiments, 2026-10-01: Temurin 8u492 target; Temurin 21.0.12.1, Temurin 8u492 `tools.jar` and Byte Buddy
emulated clients; Linux x86_64 container; root and non-root users; toy target program. Outcomes as stated above.
OpenJDK jdk8u `hotspot/src/os/linux/vm/attachListener_linux.cpp` line 534 `if (st.st_uid == geteuid())` (trigger-file
check) and line 355 `if (cred_info.uid != euid || cred_info.gid != egid)` (`SO_PEERCRED` check). Client-side check in
OpenJDK master `src/jdk.attach/linux/native/libattach/VirtualMachineImpl.c`, `checkPermissions`
(`if (sb.st_uid != uid && uid != ROOT_UID)` plus group/other mode bits). The `ByteBuddyAgent` javadoc states that
attach works only to processes run by the same operating-system user. Note: bugs.openjdk.org lists 8u backports for
JDK-8197387 (root may attach; fix version 11), but 8u492 behaviour and the jdk8u HotSpot source show the target-side
checks are still strict.

Confidence: verified

### F6

jattach v2.2 (a single static binary of about 63 KB; no JDK needed) attaches as root to a non-root JDK 8u492 JVM and
loads the agent, because it switches egid/euid to the target's ids and enters the target's net, ipc and mnt
namespaces (NSpid-aware). It also runs jcmd commands against JDK 8 and prints the raw reply
(`JVM response code = 0`).

Evidence: Experiment, 2026-10-01: Temurin 8u492 target running as a non-root user, jattach v2.2 run as root, Linux
x86_64 container, toy target program. Observed: the attach socket `/tmp/.java_pid119` was owned by the non-root
user, and the loaded agent answered a ping. Source
https://raw.githubusercontent.com/jattach/jattach/master/src/posix/jattach.c lines 34-41 (`enter_ns` for `net`,
`ipc`, `mnt`; `setegid`/`seteuid` to the target) and `src/posix/jattach_hotspot.c` lines 38-58
(`/proc/<pid>/cwd/.attach_pid`, `SIGQUIT`); README https://github.com/jattach/jattach (no installed JDK required,
works with a JRE, supports Linux containers); release https://github.com/jattach/jattach/releases/tag/v2.2

Confidence: verified

### F7

`-XX:+DisableAttachMechanism` on JDK 8 makes attach fail with `AttachNotSupportedException: The VM does not support
the attach mechanism`. `-XX:+StartAttachListener` is accepted by 8u492 and creates `/tmp/.java_pid<pid>` (mode 0600)
at startup. Otherwise the listener starts lazily: the client creates `.attach_pid<pid>` in the target's working
directory (JDK 17+ clients write `/proc/<pid>/cwd` first, then the target's `/tmp`) and sends `SIGQUIT`.

Evidence: Experiment, 2026-10-01: Temurin 8u492 target, Temurin 21.0.12.1 controller, Linux x86_64 container, toy
target program. Observed: the exception above with `-XX:+DisableAttachMechanism`; `srw------- 1 root root 0 ...
/tmp/.java_pid110` at startup with `-XX:+StartAttachListener`. JDK 8 `java` tool documentation
https://docs.oracle.com/javase/8/docs/technotes/tools/unix/java.html (`-XX:+DisableAttachMechanism`); OpenJDK jdk8u
`attachListener_linux.cpp` lines 515-534; OpenJDK master
`src/jdk.attach/linux/classes/sun/tools/attach/VirtualMachineImpl.java`, `createAttachFile` lines 248-301
(`/proc/<pid>/cwd`, `/proc/<pid>/root/tmp`, NSpid via `getNamespacePid`).

Confidence: verified

### F8

Modern attach clients negotiate Attach API v2 by sending a `getversion` command and silently fall back to version 1
when the target (JDK 8) rejects it. `-Djdk.attach.compat=true` forces v1 and gives identical results against JDK 8.
The default client attach timeout is 10000 ms in modern JDKs (5000 ms in JDK 8) and can be overridden with
`-Dsun.tools.attach.attachTimeout`.

Evidence: OpenJDK master `HotSpotVirtualMachine.java`: `getDefaultProps()` (falls back to VERSION_1 when the command
is not supported), `isAPIv2Enabled()` reading `jdk.attach.compat`, `defaultAttachTimeout=10000` (line 608 of the
master copy read on 2026-10-01); OpenJDK jdk8u `HotSpotVirtualMachine.java` line 320 (5000). Experiment, 2026-10-01:
Temurin 8u492 target, Temurin 21.0.12.1 controller, Linux x86_64 container, toy target program. Observed: identical
results with and without `-Djdk.attach.compat=true`.

Confidence: verified

### F9

JDK 9+ clients refuse to attach to their own JVM unless `-Djdk.attach.allowAttachSelf=true` (or empty) is set. A
JDK 8 target has no such restriction, so in-process self-attach (for example Mockito inline via Byte Buddy and
`tools.jar`) works inside a JDK 8 target process.

Evidence: OpenJDK master `HotSpotVirtualMachine.java` lines 61-88 (`ALLOW_ATTACH_SELF`, `Can not attach to current
VM`); jdk8u `HotSpotVirtualMachine.java` has no such check; JDK 9 release notes
https://www.oracle.com/java/technologies/javase/9-notes.html; JEP 451 (https://openjdk.org/jeps/451) notes that JDK 9
and later prevent code from connecting to the current JVM by default. Experiment, 2026-10-01: Temurin 8u492 target,
Temurin 21.0.12.1 controller, Linux x86_64 container, toy target program. Observed: Mockito inline self-attached
inside the 8u492 target.

Confidence: verified

### F10

Container and namespace attach. JDK-8179498 made the client NSpid- and mount-namespace aware (fixed in JDK 10,
backported to 8u471, so 8u492 tooling has it). JDK-8226919 (attach on Linux hangs because access to
`/proc/<pid>/root` is denied; it hits same-user attach to JVMs with elevated capabilities or changed uids) is fixed in
JDK 23 with backports to 21.0.9, 17.0.17, 11.0.31 and 8u501. JDK-8307977 (jcmd/jstack broken for targets with
elevated capabilities) is its duplicate.

Evidence: https://bugs.openjdk.org/browse/JDK-8179498 ; https://bugs.openjdk.org/browse/JDK-8226919 (fix version 23;
backports 21.0.9 JDK-8361358, 17.0.17 JDK-8361362, 11.0.31, 8u501 JDK-8381407);
https://bugs.openjdk.org/browse/JDK-8307977

Confidence: likely

### F11

Windows attach (source analysis, not executed). The client opens the target with `OpenProcess(PROCESS_ALL_ACCESS)`,
falling back to enabling `SeDebugPrivilege` (otherwise `Insufficient memory or insufficient privileges to attach`).
It injects a stub via `CreateRemoteThread` that calls `JVM_EnqueueOperation_v2`, then `JVM_EnqueueOperation` (the
v1 fallback, which the JDK 8 `jvm.dll` exports). The target writes results to a client-created named pipe
`\\.\pipe\javatool<random>`. A 64-bit client cannot attach to a 32-bit JVM (`Unable to attach to 32-bit process
running under WOW64`). Cross-version JDK 21 to JDK 8 attach on Windows should therefore behave like Linux (same
bare-`0` reply symptom), and cross-user or cross-session attach needs an elevated controller.

Evidence: https://raw.githubusercontent.com/openjdk/jdk/master/src/jdk.attach/windows/native/libattach/VirtualMachineImpl.c
(`OpenProcess(PROCESS_ALL_ACCESS...)`, `doPrivilegedOpenProcess` with `SE_DEBUG_NAME`, `GetProcAddress` of the v1 and
v2 functions, `CreateRemoteThread`, the WOW64 message);
https://raw.githubusercontent.com/openjdk/jdk/master/src/jdk.attach/windows/classes/sun/tools/attach/VirtualMachineImpl.java
(pipe prefix `\\.\pipe\javatool`, VERSION_1/VERSION_2 enqueue); HotSpot Serviceability documentation
https://openjdk.org/groups/hotspot/docs/Serviceability.html

Confidence: likely

### F12

JEP 451. A JDK 21+ target prints four WARNING lines on every dynamic agent load (beginning `A Java agent has been
loaded dynamically`, and stating that dynamic loading of agents will be disallowed by default in a future release).
With `-XX:-EnableDynamicAgentLoading` the load is refused (`Dynamic agent loading is not enabled. Use
-XX:+EnableDynamicAgentLoading to launch target VM.`). `-javaagent`, `-agentlib` and `Launcher-Agent-Class` are
unaffected. JDK 8 targets print nothing. JDK 25 still only warns.

Evidence: Experiment, 2026-10-01: Temurin 21.0.12.1 target, Linux x86_64 container, toy target program. Observed:
the warnings in the target log on every dynamic load, and the refusal when started with
`-XX:-EnableDynamicAgentLoading`. JEP 451 https://openjdk.org/jeps/451 (summary; warning text; tools that load agents
at startup are unaffected; a future release will disallow dynamic loading by default); OpenJDK master
`src/hotspot/share/prims/jvmtiAgent.cpp` (line 492).

Confidence: verified

### F13

The current Byte Buddy release is 1.18.14 (14 September 2026), with a parallel `1.18.14-jdk5` artifact. Since
1.18.7 (1 March 2026) the regular jar has a Java 8 baseline and the `-jdk5` suffix marks the Java 5-compatible jar.
`byte-buddy-1.18.14.jar` class files are major 52 (Java 8), Multi-Release, with `META-INF/versions/9` (module-info)
and `versions/24` entries; the `-jdk5` jar is major 49 with about 3092 `versions/9` entries.
`byte-buddy-agent-1.18.14.jar` manifest: `Agent-Class`/`Premain-Class: net.bytebuddy.agent.Installer`,
`Can-Redefine-Classes`, `Can-Retransform-Classes`, `Can-Set-Native-Method-Prefix`. Byte Buddy generates class files
for any Java version up to 25 and later. Licence: Apache 2.0 (ASM bundled under BSD).

Evidence: release notes https://github.com/raphw/byte-buddy/blob/master/release-notes.md (1.18.14 dated
14 September 2026; 1.18.7 introduced the `-jdk5` suffix for the backwards-compatible jar and the Java 8 baseline for
the regular jar); Maven Central https://repo1.maven.org/maven2/net/bytebuddy/byte-buddy/maven-metadata.xml (latest
`1.18.14-jdk5`, lastUpdated 20260914221459); inspection of the published jars, 2026-10-01 (class-file major versions,
manifests); README https://github.com/raphw/byte-buddy (compatibility table; written in Java 5 but generates classes
for any Java version; licence).

Confidence: verified

### F14

Byte Buddy 1.18.14 works inside the JDK 8u492 target when attached from JDK 21.
`AgentBuilder.Default().disableClassFormatChanges().with(RedefinitionStrategy.RETRANSFORMATION).type(named("Target")).transform(... Advice.to(GreetAdvice.class).on(named("greet")))`
retransformed the already-loaded class and changed the live return value. Later retransforms by Byteman and by
Mockito re-ran the Byte Buddy transformer (transformers chain and are re-applied from the original bytes), and the
advice survived Byteman's rule unload.

Evidence: Experiment, 2026-10-01: Temurin 8u492 target, Temurin 21.0.12.1 controller, Linux x86_64 container, toy
target program. Observed in the target log: `[Byte Buddy] TRANSFORM Target [sun.misc.Launcher$AppClassLoader@18b4aac2,
...]`; `TARGET tick 16 -> hello world#16 [advised by ByteBuddy in java 1.8.0_492]`; repeated `TRANSFORM` lines while
Byteman retransformed `Target`; advice still present after `Uninstalled rule`.

Confidence: verified

### F15

Byte Buddy retransformation constraints. With `disableClassFormatChanges()` Byte Buddy can no longer rebase a method,
so intercepted methods are fully replaced, and it adds no implicit type initializers. The `RedefinitionStrategy`
javadoc explains that most JVMs do not support structural changes to an already-loaded class, so a transformer used
for redefinition or retransformation typically has to be built with `disableClassFormatChanges()`. `Advice` inlines
the advice bytecode, and Byte Buddy does not check the visibility of types referenced inside an inlined advice
method; a type the instrumented class cannot see results in `IllegalAccessError`. Helper or bridge types used by
advice woven into classes defined by custom class loaders (for example an application's own launcher loader or
its plugin loaders) must therefore be reachable, typically via bootstrap injection
(`Instrumentation.appendToBootstrapClassLoaderSearch` or `ClassInjector.UsingInstrumentation`).

Evidence: https://raw.githubusercontent.com/raphw/byte-buddy/master/byte-buddy-dep/src/main/java/net/bytebuddy/agent/builder/AgentBuilder.java,
javadoc of `disableClassFormatChanges` and of `RedefinitionStrategy` (lines 358-371 and 5978-5987 of the master copy
read on 2026-10-01); https://raw.githubusercontent.com/raphw/byte-buddy/master/byte-buddy-dep/src/main/java/net/bytebuddy/asm/Advice.java,
class javadoc (lines 114-151 of the same-date copy); JDK 8 `Instrumentation.appendToBootstrapClassLoaderSearch`
https://docs.oracle.com/javase/8/docs/api/java/lang/instrument/Instrumentation.html

Confidence: verified

### F16

`ByteBuddyAgent.install()` on HotSpot works only when running on a JDK and, on JDK 8, needs `tools.jar`.
`install()` returns an already-present `Instrumentation` if the `Installer` class (possibly shaded, identified
through the `Agent-Class` manifest attribute) is loaded in the system class loader. The `AttachmentProvider` chain
is `ForModularizedVm` (`jdk.attach`), `ForJ9Vm`, `ForStandardToolsJarVm`, `ForUserDefinedToolsJar` (system
property), `ForEmulatedAttachment` (JNA). When `jdk.attach.allowAttachSelf` is false it spawns an external process
and passes the argument via an environment variable, not the command line (a 1.18.14 change).

Evidence: https://raw.githubusercontent.com/raphw/byte-buddy/master/byte-buddy-agent/src/main/java/net/bytebuddy/agent/ByteBuddyAgent.java
(class javadoc; `doGetInstrumentation` lines 777-808; `installExternal` line 628 reading `jdk.attach.allowAttachSelf`);
release-notes.md entry for 1.18.14 (the agent argument is no longer exposed on the command line of the process
spawned for external attachment); Mockito issue https://github.com/mockito/mockito/issues/2577, which quotes the same
javadoc.

Confidence: verified

### F17

JVM/JVMTI limits on retransform and redefine on JDK 8 (unchanged through 25): a retransform or redefine may not add,
remove or rename fields or methods, change method signatures, or change inheritance. `retransformClasses` starts from
the initial class-file bytes and re-invokes every transformer registered with `canRetransform=true`, so removing a
transformer and retransforming restores the original behaviour. `isRetransformClassesSupported` requires
`Can-Retransform-Classes: true` in the agent manifest. Primitive and array classes are never modifiable. Native
methods need `Can-Set-Native-Method-Prefix` plus `setNativeMethodPrefix`. Verified on 8u492:
`retransformSupported=true`, `redefineSupported=true`, `nativePrefix=false` (the manifest lacked the prefix
attribute), `isModifiableClass(Target)=true`.

Evidence: https://docs.oracle.com/javase/8/docs/api/java/lang/instrument/Instrumentation.html (the restriction and
the retransform steps). Experiment, 2026-10-01: Temurin 8u492 target, Temurin 21.0.12.1 controller, Linux x86_64
container, toy target program. Observed in the target log: `retransformSupported=true redefineSupported=true
nativePrefix=false`, `modifiable(Target)=true`. Arthas `reset` precedent (enhanced classes are restored on reset and on
server stop): https://arthas.aliyun.com/en/doc/reset.html

Confidence: verified

### F18

The current Byteman release is 4.0.27 (Maven Central lastUpdated 2026-06-15); the latest 3.x is 3.0.22.
`byteman-4.0.27.jar` class files are major 50 (Java 6; root pom compiler source/target 1.6; Build-Jdk 11.0.28), with
manifest `Premain-Class`/`Agent-Class: org.jboss.byteman.agent.Main`, `Can-Redefine-Classes: true`,
`Can-Retransform-Classes: true`. Only `org.jboss.byteman.agent.JigsawAccessEnablerGenerator` and
`JigsawAccessManager` reference `java.lang.Module`, and they are loaded reflectively. The Byteman downloads page says
3.x releases do not always work correctly on JDK 9+ runtimes, 4.x releases work on all JDK 9+ runtimes, and 3.x is
maintained so Byteman can still be used on JDK 8 and earlier. 4.0.27 demonstrably runs on 8u492 (F19), so 3.x is not
required.

Evidence: https://repo1.maven.org/maven2/org/jboss/byteman/byteman/maven-metadata.xml; jar, manifest and `javap`
inspection of `byteman-4.0.27.jar` and `byteman-3.0.22.jar`, 2026-10-01;
https://repo1.maven.org/maven2/org/jboss/byteman/byteman-root/4.0.27/byteman-root-4.0.27.pom (source/target 1.6);
https://byteman.jboss.org/downloads.html

Confidence: verified

### F19

Byteman 4.0.27 installed from a JDK 21 attacher into the 8u492 target with
`org.jboss.byteman.agent.install.Install -b -p 9091 <pid>`. `Install` printed the JDK 8
`AgentLoadException: Failed to load agent library: 0` symptom, but the agent came up
(`TransformListener() : accepting requests on localhost:9091`). `bmsubmit -l` loaded a `RULE` on `Target.greet AT
ENTRY` that fired (`[byteman 1.8.0_492] greet called with world#26`); `-c` listed it; `-u` unloaded it and
retransformed the class back. This coexisted with the Byte Buddy transformer in the same JVM. `Install` resolves
`byteman.jar` via `-Dorg.jboss.byteman.home` or `BYTEMAN_HOME`; `bminstall.sh` adds `tools.jar` only for Java 8 and
older attachers.

Evidence: Experiment, 2026-10-01: Temurin 8u492 target, Temurin 21.0.12.1 attacher, Linux x86_64 container, toy
target program. Observed in the target log: `install rule trace greet`, `RuleTriggerMethodAdapter.injectTriggerPoint
: inserting trigger into Target.greet`, `uninstall RULE trace greet`, `Uninstalled rule using default helper`. Source
https://raw.githubusercontent.com/bytemanproject/byteman/main/install/src/main/java/org/jboss/byteman/agent/install/Install.java
lines 347-354 and 524; https://raw.githubusercontent.com/bytemanproject/byteman/main/bin/bminstall.sh

Confidence: verified

### F20

Byteman rule language and runtime features (Programmer's Guide, 4.0.27):

- Grammar: `RULE` / `CLASS` or `INTERFACE` (with `^` for overriding implementations) / `METHOD` / `HELPER` /
  `AT` or `AFTER` {`ENTRY`, `EXIT`, `LINE n`, `READ`/`WRITE` field, `INVOKE` method, `NEW`, `SYNCHRONIZE`, `THROW`,
  `EXCEPTION EXIT`} / `BIND` / `IF` / `DO` / `ENDRULE`.
- Built-in `Helper`: `traceln`/`traceOpen`, `flag`/`clear`/`flagged`, `countDown`, `rendezvous`,
  `waitFor`/`signalWake`, `delay`, `createTimer`, `killThread`/`killJVM`, `link`/`linked`,
  `callerMatches`/`callerEquals`.
- Agent listener default port 9091; `bmsubmit -l/-u/-c/-b/-s/-y/-p`. Rules are type-checked and compiled on first
  trigger, and already-loaded classes are retransformed.
- `java.*` classes are transformed only with `org.jboss.byteman.transform.all`; `org.jboss.byteman` classes are never
  transformed; constructor rules inject after the `super()` call.
- Rules are interpreted by default; `org.jboss.byteman.compile.to.bytecode` or `COMPILE` compiles hot paths.

Evidence: https://downloads.jboss.org/byteman/latest/byteman-programmers-guide.html (sections on rule grammar, agent
install, listener, `bmsubmit`, Helper built-ins, restrictions)

Confidence: verified

### F21

JDI from JDK 21 (`jdk.jdi`, `com.sun.jdi.SocketAttach`) connects to the JDK 8 JDWP port in about 170 ms (the target
reports `Java Debug Wire Protocol ... version 1.8`, `canRedefineClasses=true`, `canGetInstanceInfo=true`), reads a
static field without suspending anything, allows only one debugger connection at a time (a second attach gets
`Connection refused`) and accepts a new connection after `dispose()`. JDI method invocation requires the thread to
have been suspended by an event that occurred in that thread, and carries deadlock caveats. JDWP is therefore
suitable as a read-only inspection and fallback channel, not as the primary way to drive a Swing target application.

Evidence: Experiment, 2026-10-01: Temurin 8u492 target with JDWP enabled, Temurin 21.0.12.1 JDI client, Linux x86_64
container, toy target program. Observed: `JDI SECOND concurrent connection: FAILED java.net.ConnectException:
Connection refused`; `JDI RECONNECT after dispose: SUCCEEDED`. `ObjectReference.invokeMethod` javadoc
https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jdi/com/sun/jdi/ObjectReference.html (event-suspended thread
requirement, `INVOKE_SINGLE_THREADED` deadlock warning, `IncompatibleThreadStateException`); JPDA connection options
https://docs.oracle.com/en/java/javase/21/docs/specs/jpda/conninv.html

Confidence: verified

### F22

JFR is present on Temurin 8u492 (`jre/lib/jfr.jar`, `jre/lib/jfr/default.jfc` and `profile.jfc`) following the
OpenJDK 8u262 backport (JDK-8223147), and JDK 21's `jcmd` drives it cross-version: `jcmd <pid8> VM.version`,
`JFR.start name=probe duration=5s filename=...` and `JFR.check` all worked and wrote a 186 KB `.jfr` file. The
`jdk.jfr` programmatic API (`Recording`, custom `Event` subclasses) is available in 8u262+, but `javac --release 8`
cannot resolve `jdk.jfr`, so in-agent use needs reflection or compilation against a JDK 8 toolchain.

Evidence: inspection of the Temurin 8u492 JDK used as the target: `jre/lib/jfr.jar` and `jre/lib/jfr/*.jfc` present;
release file `FULL_VERSION=1.8.0_492-b09` (Eclipse Adoptium). Experiment, 2026-10-01: Temurin 8u492 target, Temurin
21.0.12.1 `jcmd`, Linux x86_64 container, toy target program. Observed: the commands above succeeded and produced
`probe.jfr`. https://bugs.openjdk.org/browse/JDK-8223147;
https://developers.redhat.com/blog/2020/08/25/get-started-with-jdk-flight-recorder-in-openjdk-8u. Experiment,
2026-10-01: a source importing `jdk.jfr` compiled with `--release 8` failed with `package jdk.jfr does not exist`.

Confidence: verified

### F23

Mockito 4.11.0 is the last line supporting Java 8 (Mockito 5 requires Java 11; `mockito-core` latest is 5.24.0 on
2026-09-23; the separate `mockito-inline` artifact ended at 5.2.0 because inline became the 5.x default). Mockito
4.11.0 inline (`mock-maker-inline` resource; depends on byte-buddy 1.12.19 and objenesis 3.3) works in-process inside
an 8u492 target when loaded via attach (it self-attaches through `tools.jar` of the JDK 8 installation). However,
`mockStatic()` is thread-local: the mocking thread saw `MOCKED-BY-MOCKITO` while the target's main loop kept the
real (Byte Buddy-advised) result. `Mockito.spy()` does not delegate to the passed real instance but creates a copy of
it, so Mockito cannot stub a live singleton in place.

Evidence: https://repo1.maven.org/maven2/org/mockito/mockito-core/maven-metadata.xml and
https://repo1.maven.org/maven2/org/mockito/mockito-inline/maven-metadata.xml; `mockito-core-4.11.0.pom` on Maven
Central (byte-buddy 1.12.19, objenesis 3.3); Mockito 4.11.0 javadoc
https://javadoc.io/static/org.mockito/mockito-core/4.11.0/org/mockito/Mockito.html (sections 13, 39 and 48: static
mocks apply within the current thread and a user-defined scope; inline mocking requires a JVM that is part of a JDK);
https://github.com/mockito/mockito/issues/2577. Experiment, 2026-10-01: Temurin 8u492 target, Temurin 21.0.12.1
controller, Linux x86_64 container, toy target program. Observed: `MOCKAGENT in mocking thread:
Target.greet(x)=MOCKED-BY-MOCKITO` while the main loop printed `TARGET tick 24 -> hello world#24 [advised by
ByteBuddy ...]`.

Confidence: verified

### F24

A Java 8-compatible control endpoint can live inside the agent. `com.sun.net.httpserver.HttpServer` (JDK API since
1.6) compiles under `javac --release 8` and, started from `agentmain` inside the 8u492 target, answered HTTP
requests (`pong from agent in java 1.8.0_492 counter=14`). `java.lang.management` and the JMX platform MBean server
also compile under `--release 8`. `javac --release 8` (JDK 25) does not expose `com.sun.tools.attach`,
`sun.misc.Unsafe` or `jdk.jfr` (`package ... does not exist`); `--add-modules` is `not allowed with target 8`; and
`-source`/`-target 8` without `--release` links against the JDK 25 class library (javac warns that this may lead to
class files that cannot run on JDK 8).

Evidence: Experiment, 2026-10-01: four small test sources compiled with OpenJDK 25.0.1 `javac` on macOS, results as
stated. JEP 247 https://openjdk.org/jeps/247 (for N < 9 the documented API is the public API on javac's default
bootclasspath for JDK N; `--release` cannot be combined with `-source`, `-target` or `-bootclasspath`);
`HttpServer` documentation
https://docs.oracle.com/javase/8/docs/jre/api/net/httpserver/spec/com/sun/net/httpserver/HttpServer.html (since 1.6).
Experiment, 2026-10-01: Temurin 8u492 target, Temurin 21.0.12.1 controller, Linux x86_64 container, toy target
program. Observed: the ping line above.

Confidence: verified

### F25

Precedents for the recommended agent architecture. Arthas uses a thin `arthas-agent` loaded via attach that creates
an isolated `ArthasClassLoader` for `arthas-core`, injects a Spy API into the bootstrap loader, and restores all
enhanced classes on `reset`/`stop`. Alibaba jvm-sandbox is an `Instrumentation`-based non-invasive AOP container with
hot-swappable modules (refresh / force refresh), per-jar `ModuleJarClassLoader` isolation, and module unload that
releases intercepted class events.

Evidence: https://github.com/alibaba/arthas ; https://arthas.aliyun.com/en/doc/reset.html (enhanced classes are also
reset when the Arthas server stops); https://github.com/alibaba/jvm-sandbox and
https://github.com/alibaba/jvm-sandbox/blob/master/doc/JVM-SANDBOX-USER-GUIDE-English.md

Confidence: likely

### F26

Byteman 4.0.27 `Install` from a JDK 21 attacher surfaces the cross-version `AgentLoadException` but does not abort
rule submission. `Install.java` simply calls `vm.loadAgent(agentJar, agentOptions)` inside `try`/`finally
vm.detach()`, and its javadoc notes that `AgentInitializationException` nearly always means the agent is already
loaded into the JVM.

Evidence: Experiment, 2026-10-01: Temurin 8u492 target, Temurin 21.0.12.1 attacher, Linux x86_64 container, toy
target program. Observed: `com.sun.tools.attach.AgentLoadException: Failed to load agent library: 0` followed by a
successful `install rule trace greet`. Source
https://raw.githubusercontent.com/bytemanproject/byteman/main/install/src/main/java/org/jboss/byteman/agent/install/Install.java
lines 81-83 and 524-527.

Confidence: verified

## Recommendations

### R0

Controller stack: Java 25 LTS (the experiments in this note used JDK 21) using the built-in `jdk.attach` module as
the primary attach path, plus `net.bytebuddy:byte-buddy-agent:1.18.14` with `net.java.dev.jna:jna` (and
`jna-platform`) 5.12.1 to offer the emulated `VirtualMachine.ForHotSpot` path, and the jattach v2.2 binary as an
operational fallback for root, other-user and container cases. Implement an `AttachSession` abstraction that:

1. reads the target's `java.specification.version` via `getSystemProperties()`;
2. for targets at 1.8 or below, treats an `AgentLoadException` whose message is `0` (JDK 11/17 clients) or ends with
   `: 0` (JDK 21+ clients) as success, and maps raw codes 100/101/102/-4 to BADJAR/NOTONCP/STARTFAIL/ENOMEM;
3. never trusts the attach reply alone, and instead completes a handshake with the agent's control endpoint (for
   example `GET /ping` with a nonce passed in the agent options).

### R1

Agent stack (runs inside the JDK 8 target JVM): compile with maven-compiler `<release>8</release>`, never
`-source`/`-target 8` against a newer class library. Keep two tiers:

- A tiny, stable bootstrap agent jar (`Agent-Class` + `Premain-Class` + `Launcher-Agent-Class`;
  `Can-Retransform-Classes`, `Can-Redefine-Classes`, `Can-Set-Native-Method-Prefix: true`). Its `agentmain` is
  idempotent (guard on a system property or a registered MBean, because `agentmain` runs again on every re-attach and
  a renamed jar is shadowed by the first class). It stores `Instrumentation` and loads the versioned framework core
  from a separate jar in an isolated `URLClassLoader`, so modules can be unloaded and hot-swapped (the Arthas /
  jvm-sandbox pattern).
- A small bridge/spy API published into the bootstrap loader via `appendToBootstrapClassLoaderSearch`, so advice in
  classes defined by custom class loaders (an application's own launcher loader, plugin loaders, classes that
  integrate embedded web views) can see it.

### R2

Bytecode tooling. Use Byte Buddy 1.18.14 (the regular Java 8-baseline artifact) for compiled, typed hooks via
`AgentBuilder.Default().disableClassFormatChanges().with(RedefinitionStrategy.RETRANSFORMATION)` and `Advice`
(`OnMethodEnter`/`OnMethodExit` with `skipOn` and return-value rewriting), for primitives such as observing an
application method call, intercepting an input-queue method, or stubbing a permission check for a given user. Shade
Byte Buddy into the framework namespace with maven-shade relocation (keep the Multi-Release manifest or drop
`META-INF/versions`; the experiment stripped `META-INF` and ran fine on JDK 8), so it cannot conflict with other Byte
Buddy copies in the target (Mockito 4.11 bundles 1.12.19).

Use Byteman 4.0.27 as a second, independently loadable agent for AI-authored ad-hoc rules (tracing, fault
injection, `rendezvous`/`waitFor`/`signalWake` synchronisation, `countDown`, `killThread`) submitted at runtime over
its TCP listener: bind 127.0.0.1, choose a non-default port per session, and wrap `bmsubmit`'s `Submit` class in the
controller. Rule text is a natural LLM output and can be type-checked with `-c` before loading. The framework owns
both and exposes "rule" primitives alongside "hook" primitives.

### R3

Control plane. Expose the in-agent API with `com.sun.net.httpserver` bound to 127.0.0.1 on an ephemeral port,
advertised back through a file or system property, plus a per-session token. JSON over HTTP keeps the Java 8 agent
dependency-free and is easy to call from the JDK 25 controller, an AI tool runner, or Playwright glue code.
Register the same facade as a platform MBean for jconsole/jcmd-style access. Where the target already runs with
JDWP enabled, keep it as a secondary read-only inspection channel via JDI (field reads without suspension), and
remember it supports only one client at a time; do not drive the Swing EDT through JDI `invokeMethod`.

### R4

Disable/unload strategy. Every module records the classes it touched. "Disable" means:
`Instrumentation.removeTransformer`, then `retransformClasses(touched)` (which restores the original bytes per the
JDK spec, as the Byteman unload showed), close the HTTP server, clear static registries, then drop the module class
loader. Accept that the bootstrap jar itself stays on the system class path for the life of the JVM; that is why it
must be tiny and versioned by protocol, not by class name.

### R5

Deployment modes. For CI runs, add `-javaagent:<bootstrap>.jar` to the target's JVM arguments (the `JAVAAGENT` load
mode). This avoids attach entirely, works the same on JDK 8 and Java 25 targets without JEP 451 warnings, and still
allows modules to be loaded later over the control endpoint. Use dynamic attach (the `ATTACH` load mode) only for
live or ad-hoc targets. For Java 21+ targets, run the target JVM with `-XX:+EnableDynamicAgentLoading` when attach is
used.

### R6

Operational rules for attach:

- Run the attach step as the same OS user as the target JVM (`sudo -u <targetuser>` if the controller runs as
  someone else).
- If root must attach to a non-root JDK 8 target, shell out to jattach (verified) rather than `jdk.attach` or Byte
  Buddy.
- On Windows, run the controller in the same user session (or elevated) and match bitness.
- Raise `-Dsun.tools.attach.attachTimeout` for slow target hosts.
- Confirm that `-XX:+DisableAttachMechanism` and `-Xrs` are not in the target JVM arguments.

### R7

Stubbing live singletons. Do not rely on Mockito for in-place stubbing: `spy()` copies the object, and
`mockStatic`/`mockConstruction` are thread-local, while the target application's own threads are not the thread that
installs the mock. Implement "stub" primitives with Byte Buddy `Advice` (`skipOn` + `@Advice.Return`, optionally
conditioned on `@Advice.This` identity) or Byteman `RETURN` rules. If Mockito is wanted for test-module authoring,
pin `org.mockito:mockito-core:4.11.0` + `mockito-inline:4.11.0` and apply mocks from the thread that executes the code
under test.

### R8

Observability. Use JFR on 8u492 (`jcmd JFR.start`/`JFR.dump` from the controller; custom `jdk.jfr` events from the
agent via reflection or a JDK 8-compiled module) together with the agent's own event stream. Prefer the target
application's own public listener and test APIs as the primary, non-bytecode primitives, and reserve
retransformation for what they cannot reach.

### R9

Validation spikes before committing the design:

| Item | Status |
|---|---|
| (a) Repeat the attach matrix on a production-like Linux host image and on Windows (same user vs service account). | Needs spike BS1 (stock JVMs); Windows is untested (C8, Risk2). |
| (b) Attach to a realistic target and retransform a class defined by a custom class loader, to confirm advice visibility and that no schema-changing instrumentation is needed. | Needs spike BS3. |
| (c) Measure retransform cost on a large application class path. | Needs spike; BS3 covers the mechanism on a small sample only. |
| (d) Confirm that Byteman (LGPL) and JNA licensing are acceptable for distribution. | Decision B8. |

## Constraints

### C0

Supported targets span Java 8 (verified on Temurin 8u492, HotSpot) to Java 25. All in-process agent code must be
compiled with `--release 8` and avoid `com.sun.tools.attach`, `sun.misc`, `jdk.jfr` and other APIs outside `ct.sym`
(use reflection), while remaining loadable on 21+ (multi-release jar entries are ignored by JDK 8).

### C1

JDK 10+ attach clients always throw `AgentLoadException` (`0` / `Failed to load agent library: 0`) on a successful
`loadAgent` into a JDK 8 target; JDK 8 clients throw `IOException` on JDK 10+ targets. The controller must
special-case these replies or use an attach path that does not parse the second line, and must verify agent liveness
out-of-band.

### C2

On Linux with a JDK 8 target, the attaching process must have the same euid/egid as the target JVM. Root is not
sufficient with `jdk.attach` or Byte Buddy (only jattach's euid/egid switch works). The socket file
`/tmp/.java_pid<pid>` is mode 0600, and the `.attach_pid` trigger must be owned by the target's euid.

### C3

Retransformation cannot add, remove or rename fields or methods, or change signatures or inheritance. Byte Buddy must
run with `disableClassFormatChanges()`. Advice-referenced types must be visible to the instrumented class's loader
(bootstrap injection otherwise). Primitive and array classes are never modifiable. Native methods need
`Can-Set-Native-Method-Prefix`.

### C4

Agent jars appended to the system class path cannot be removed or replaced; `agentmain` re-runs on every re-attach,
and a same-named `Agent-Class` in a newer jar is shadowed by the first loaded class. The bootstrap agent must be
idempotent and version-agnostic.

### C5

JDWP accepts a single debugger connection at a time, and JDI `invokeMethod` requires an event-suspended thread.

### C6

Mockito inline on Java 8 requires Mockito 4.11.0 (5.x needs Java 11); `mockStatic`/`mockConstruction` are
thread-local; `spy()` copies the instance.

### C7

JEP 451: Java 21+ targets print warnings on dynamic agent loading, `-XX:-EnableDynamicAgentLoading` refuses it, and a
future JDK will disallow it by default. `-javaagent` and `Launcher-Agent-Class` are unaffected.

### C8

Windows: attach requires `OpenProcess` rights (same user, or `SeDebugPrivilege`/elevation), matching bitness (a
64-bit client cannot attach to a 32-bit JVM), and the client-created named pipe. Untested in this research.

### C9

The Byteman 4.0.27 listener is a plain TCP socket (default 9091) with no authentication: bind it to localhost and
choose per-session ports. Byteman never transforms `org.jboss.byteman` classes, and transforms `java.*` only with
`transform.all`.

## Risks

### Risk0

Treating the JDK 8 `0` reply as success relies on message-string matching that differs by client JDK (jdk11u/17u:
`0`; jdk21u+/25: `Failed to load agent library: 0`). A future client change could alter it. Mitigation: the mandatory
control-endpoint handshake, and pinning the controller JDK.

### Risk1

Byte Buddy's emulated attach ignores the agent's return code entirely, so an `Agent_OnAttach` failure would be silent
without the handshake. It also needs JNA native libraries in the controller.

### Risk2

Windows behaviour (service-account targets, session isolation, 32-bit JVMs) was only analysed from source, not
executed. A target JVM running as a Windows service would need an elevated controller.

### Risk3

Large targets with custom class loaders and other weaving agents on the class path: advice visibility, class-loader
matching and retransform latency on live application threads or the Swing EDT were not measured, and could cause
`IllegalAccessError`, `NoClassDefFoundError` or UI stalls.

### Risk4

The container experiments ran as root on the same host. PID-namespace/cgroup attach (JDK-8179498, JDK-8226919) and
the 8u501 backport status of JDK-8226919 were not exercised on 8u492.

### Risk5

Multiple instrumentation agents (the framework's Byte Buddy, Byteman, Mockito's own Byte Buddy 1.12.19, and any
weaver the target already carries, such as AspectJ's) retransform the same classes. Ordering is by registration, and
every retransform re-runs all transformers, so a buggy transformer in one tool can break another's output. Versions
conflict if Byte Buddy is not shaded.

### Risk6

Byteman's TCP rule listener is unauthenticated, and AI-authored rules execute arbitrary Java expressions in-process
(including `killJVM`). The framework must sandbox rule submission (localhost bind, allow-listed helpers, dry-run `-c`
checks).

### Risk7

bugs.openjdk.org metadata (for example the JDK-8197387 8u backports) did not match observed 8u492 behaviour for root
attach. Relying on tracker claims without re-testing on the target's actual JDK build is risky.

### Risk8

JEP 451 trajectory: for targets on Java 25 and later, dynamic attach may be disabled by default in a later JDK, so
the design must not depend solely on attach (keep the `-javaagent` and `Launcher-Agent-Class` paths).
