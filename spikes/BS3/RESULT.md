# BS3: Byte Buddy retransformation, inline Advice, a bootstrap `Spy`, restore on detach

Run on 2026-10-09. **Proves** (`docs/TODO.md`, "BS3 Retransformation, Advice and bootstrap `Spy`"): Byte Buddy's
`AgentBuilder` with `RETRANSFORMATION` and `disableClassFormatChanges()` plus inline Advice changes an already-loaded
class; two-line Advice that calls a `Spy` on the bootstrap search path works from classes in custom loaders; removing
the transformer and retransforming restores the original bytes; retransformation latency. The design it serves is
`docs/design/base-framework-design.md` §2.1 (`skillroy-agent-spy`, `skillroy-agent-hooks`), §2.2 step 6 and §2.6.

**Verdict: passed with conditions.** On Java 8 (Temurin 1.8.0_504) and Java 25 (Temurin 25.0.4.1) targets, in
`JAVAAGENT` and `ATTACH`, a hook on the sample desk's `AccessControl.check` and on a toy class in a custom
`URLClassLoader` installs in 111-197 ms (the first; later ones 6-23 ms), fires on the host's own thread in all three
modes, switches mode without retransforming, and restores byte for byte; Byte Buddy 1.18.14, shaded under the project
prefix, coexists with a host that ships Byte Buddy 1.12.19, ASM 5.0.3 and JNA 4.5.2. 17 configurations, every probe
verdict PASS (the controls show what they are meant to show). The conditions, each a finding below and a fact for the
contract:

1. **`new AgentBuilder.Default()` injects `Nexus` into the system class loader** whatever `InitializationStrategy`
   is set afterwards; on Java 9+ that injection also defines `java.lang.ClassLoader$ByteBuddyAccessor$V1` in the
   bootstrap loader through `sun.misc.Unsafe`, which on Java 25 prints a four-line warning on the host's stderr. The
   engine must set Byte Buddy's (relocated) `nexus.disabled` property while `NexusAccessor` initialises (§6). With
   that, nothing but `Spy` reaches the host's shared loaders.
2. **A bootstrap append costs a VM warning on Java 25**: with class data sharing on (the default since JDK 12) every
   `appendToBootstrapClassLoaderSearch` prints `OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot
   loader classes because bootstrap classpath has been appended` on the host's stderr, once per append, and, as the
   message says, shares only bootstrap classes from then on. Java 8: nothing. It does not depend on where `Spy`
   lives (§5).
3. **The post-detach byte check is weak by construction**: the JVM hands every retransformation the initial bytes it
   cached at the first transform, so "after equals first" holds whatever happened in between unless something else
   is registered or someone redefined the class; on Java 8 a restore even reverts another agent's redefinition and
   still compares equal. The engine needs a ledger of transform events it did not cause beside the byte check (§3).
4. **`Spy` must be visible from the target's loader, or the hook must be refused**: a loader that delegates only
   `java.*` cannot see `Spy` in the project's package. Advice with `suppress = Throwable.class` then costs 4-6 µs per
   call and fires nothing; without `suppress` the host's own call fails with `NoClassDefFoundError`. A `Spy` relocated
   into a `java.*` package works on Java 8 and Java 25 (§5).
5. **Byte Buddy must stay registered for the hook's life and is not free while it is**: every class the host loads
   meanwhile costs 3-8 µs more; every retransformation is a stop-the-world safepoint of 3-12 ms (Java 25, growing with
   the number of loaded classes) or up to 53 ms (Java 8, growing with the class size). Hence the size limit (§4).
6. **Build**: maven-shade-plugin 3.6.2 or newer (3.6.0 cannot read Byte Buddy's Java 24 class files); the 26
   multi-release classes of Byte Buddy's Class-File-API bridge keep their unrelocated path and are unreachable,
   harmless up to Java 27 targets (§7).

## 1. Verdict per item

| Item | Verdict | Evidence (`target/runs/<configuration>/result.properties`) |
|---|---|---|
| Pass: events arrive from both hooks | **Passed** | `check.base.events.bothHooks` (2 enters and 2 exits per hook) and `check.base.events.onHostThread` (`lending-desk`) in every configuration |
| Pass: no `IllegalAccessError` or `NoClassDefFoundError` | **Passed, with the visibility probe** | No such error in any run except the deliberate demonstrations (item 1's unguarded advice, §5's early reference); `check.item1.installRefusedWhenInvisible` |
| Pass: after detach the class bytes equal the bytes captured at first transform | **Passed** (byte for byte, Java 8 and 25), **with condition 3** | `check.remove.limit.check.byteIdentical`, `check.remove.toy.deposit.byteIdentical`, `check.item3.bc0.restoredByteIdentical` |
| Pass: install latency recorded, proposed limit under 1 s for one class | **Passed** | 111-197 ms for the first hook, 6-23 ms for the next; at most 410 ms for a 1.85 MB class (`check.item4.install.underOneSecond`, §4) |
| 1. Strict-delegation loader, `Spy` placement | **Passed with condition 4** | `jdk*-javaagent`: `item1.visibility=NOT_VISIBLE`; `jdk*-javaspy`: `VISIBLE`, `check.item1.eventsFromStrictLoader` |
| 2. Host with older copies of the libraries | **Passed** | `jdk*-hostlibs`: `check.item2.ownCopy`, the host's libraries work before and after (`stdout.log`); the control `jdk*-hostlibs-unrelocated` runs silently on the host's copy |
| 3. Byte check after detach | **Passed with condition 3** | §3 |
| 4. Latency, size limit | **Passed**; limit proposed: 256 KiB and 2,000 methods | §2, §4 |
| 5. A call in flight during remove | **Passed** | `check.item5.a.*`, `check.item5.b.*`: the call finishes on the old version, returns normally, nothing thrown |
| 6. No injection; a second install | **Passed with condition 1** | `check.item6.nothingButSpy`; the controls `jdk*-nexus-default`; `check.item6.second.*`, `item6.second.loaderCollected=true` |
| 7. Load modes | **Passed** | §8: no JEP 451 warning for `JAVAAGENT` on Java 25, the four lines verbatim for `ATTACH` |

## 2. What was run

- **Environment.** Build on macOS (Apple silicon), Maven 3.9.16 on JDK 26.0.2, everything compiled with `--release 8`.
  Runs in the Linux test image `skillroy-test-java-ci:bs8-arm64` (aarch64, native) through `ci/run-in-linux.sh`
  without Xvfb: target JDKs Temurin 25.0.4.1 (`/opt/java/openjdk`) and Temurin 1.8.0_504 (`/opt/jdk8`); the attach
  client is always the image's JDK 25 (as the controller is). An extra run on the Mac's own JDK 25.0.1
  (`target/runs-native/mac-jdk25-javaagent/`) passed as well; it is not part of the verdict (that JDK's default CDS
  archive had been regenerated with `-Xshare:dump` earlier in the session).
- **Targets.** The sample lending desk's `AccessControl.check(String, String, BigDecimal)` (the return-limit check
  behind scenario A) on an in-memory database of seed A, in the system loader; the toy `DepositRule` (tool-library
  deposits) in the host's own `ToolShedLoader` (a `URLClassLoader`, parent-first), in a `StrictDelegationLoader`
  (delegates only `java.*`), and in fresh loaders per byte-check scenario; generated classes for the size sweep.
- **The matrix** (`run.sh`; one container each): `jdk{8,25}-javaagent`, `jdk{8,25}-attach`, `jdk25-attach-enabled`,
  `jdk25-attach-disabled`, `jdk{8,25}-javaspy`, `jdk{8,25}-hostlibs`, `jdk{8,25}-hostlibs-unrelocated`,
  `jdk{8,25}-nexus-default`, `jdk25-xshare-off`, `jdk{8,25}-system-append`; evidence in `target/runs/` and the
  verdict lines in `target/matrix-1.txt`. The five measurement configurations ran a second time
  (`target/runs-2/`, `target/matrix-2.txt`). Each full probe makes 50 checks; each run took 4-11 s (the refused
  attach waits 15 s for a probe that never comes).
- **Not run.** The amd64 image (only the arm64 build of the same image), Windows, a real application of tens of
  thousands of classes (the pause was measured up to 76,000 loaded classes with generated ones).

### Measurements per JDK and load mode

Two passes where there are two numbers (`runs/` / `runs-2/`). Time in ms unless stated; fire costs in ns per call,
best of five runs of 10⁶ (toy) or 2·10⁵ (sample) calls on the host's own loop.

| | Java 8 `JAVAAGENT` | Java 25 `JAVAAGENT` | Java 8 `ATTACH` | Java 25 `ATTACH` | Java 25 `ATTACH` +EDAL |
|---|---|---|---|---|---|
| Attach client: `attach` + `loadAgent` | – | – | 137.7 + 8.2 / 136.5 + 6.9 | 138.5 + 10.1 / 146.2 + 10.5 | 128.5 + 10.9 / 133.9 + 9.6 |
| Agent entry to probe start | 4.2 / 2.7 | 4.4 / 4.2 | 1.9 / 2.0 | 2.8 / 2.2 | 4.5 / 2.9 |
| `Spy` append (`appendToBootstrapClassLoaderSearch`) | 0.16 / 0.18 | 0.27 / 0.30 | 0.11 / 0.24 | 0.24 / 0.29 | 0.25 / 0.29 |
| First install (builder + `installOn`) | 137 (82 + 54) / 157 | 180 (73 + 107) / 186 | 120 / 143 | 190 / 183 | 182 / 195 |
| Next install, another class | 17 / 19 | 6.4 / 12 | 23 / 23 | 9.3 / 7.9 | 8.2 / 9.1 |
| Install from a second, fresh engine loader | 113 / 115 | 128 / 126 | 122 / 134 | 126 / 134 | 125 / 118 |
| Mode change (ns) | 1959 / 3083 | 1958 / 1791 | 7917 / 2875 | 3000 / 1834 | 2083 / 2167 |
| Handler swap behind `Spy` (ns) | 916 / 917 | 667 / 542 | 625 / 1291 | 708 / 541 | 1209 / 458 |
| Remove with byte check (sample class) | 4.9 / 5.4 | 4.4 / 7.0 | 6.6 / 2.3 | 5.0 / 6.4 | 9.9 / 6.1 |
| Restore with a call in flight (item 5 a, b) | 8.3, 10.3 / 6.2, 9.8 | 6.5, 7.0 / 6.8, 6.0 | 4.5, 4.2 / 5.6, 5.8 | 6.9, 5.3 / 7.1, 7.2 | 7.5, 8.0 / 8.1, 8.3 |
| Sample `check`: unhooked | 91 / 99 | 97 / 91 | 109 / 127 | 76 / 95 | 79 / 92 |
| … hooked, `Spy` without handler | 101 / 94 | 76 / 78 | 120 / 127 | 90 / 112 | 93 / 105 |
| … `OFF` | 102 / 99 | 78 / 88 | 121 / 135 | 87 / 90 | 94 / 96 |
| … `OBSERVE` | 309 / 300 | 218 / 220 | 377 / 412 | 270 / 282 | 263 / 275 |
| … `CAPTURE` | 317 / 300 | 222 / 243 | 384 / 424 | 275 / 276 | 278 / 272 |
| … `STUB` | 183 / 185 | 119 / 127 | 238 / 255 | 165 / 172 | 150 / 167 |
| Toy `depositFor`: unhooked | 1.2 / 0.8 | 1.1 / 1.3 | 0.8 / 0.9 | 1.1 / 1.3 | 1.0 / 1.3 |
| … hooked, `Spy` without handler | 9.8 / 10.4 | 9.8 / 11.5 | 14.3 / 16.6 | 17.7 / 16.6 | 15.8 / 12.3 |
| … `OFF` | 13.3 / 13.4 | 11.7 / 14.6 | 13.4 / 15.0 | 17.4 / 17.9 | 14.2 / 17.4 |
| … `OBSERVE` | 79 / 89 | 71 / 79 | 109 / 142 | 106 / 121 | 123 / 120 |
| … `CAPTURE` | 93 / 97 | 76 / 89 | 129 / 143 | 114 / 125 | 129 / 125 |
| … `STUB` | 79 / 80 | 72 / 78 | 106 / 116 | 105 / 105 | 99 / 104 |
| Toy with an unresolvable `Spy`, suppressed (µs) | 4.2 / 4.2 | 5.2 / 5.4 | 3.9 / 5.0 | 5.5 / 5.5 | 5.6 / 5.7 |
| Class loading while a hook is installed (µs per class, extra) | 4.0 / 5.3 | 7.1 / 2.6 | 6.2 / 4.5 | 7.8 / 4.5 | 2.9 / 5.7 |
| Metaspace and classes added by the engine (first two installs) | +5.1 MB, +1,110 | +6.6 MB, +1,423 | +5.1 MB, +1,110 | +6.6 MB, +1,425 | +6.7 MB, +1,425 |

`OBSERVE` and `CAPTURE` render two events per call (arguments and result as text, safe types only) into a ring;
that is most of their cost. With the `java.*` `Spy` the fire costs are the same (`jdk25-javaspy`: `OBSERVE` 87, no
handler 10.7 ns on the toy). The relocated engine's first install in the other configurations: 111-197 ms (the
unrelocated controls, which run on the host's already loaded Byte Buddy: 68-95 ms).

## 3. The byte check

### What the JVM hands a transformer at the first transform

Reconstituted bytes, not the class file (`item3.bc0.fileVsFirst`, `remove.*.fileVsFirst`; the files are in
`classes/`):

- **Constant pool**: the same entries in the same order as the file, on both JDKs and in both load modes (106 of
  106 for the toy, 343 of 343 for the sample's `AccessControl`). Constant-pool order never differed.
- **Methods** in the VM's internal order, not the file's: in the image `<init>, applyAsLong, applyAsLong, depositFor,
  waives, rateName, holdReturn` against the file's `<init>, depositFor, waives, rateName, holdReturn, applyAsLong,
  applyAsLong`, the same on Java 8 and 25, `JAVAAGENT` and `ATTACH`; on the Mac's JDK 25 another order again. The
  order is not the file's and not stable across platforms.
- **Code attributes** reordered: `StackMapTable` before `LocalVariableTable`.
- **Java 8 drops `MethodParameters`** (javac writes it for the bridge `applyAsLong(Object, Object)`): 2,315 bytes
  against the file's 2,330. Java 25 keeps it (2,330 bytes, different bytes).
- **Canonically equal** to the file (members sorted, debug attributes and frames left out, operands resolved):
  `item3.bc0.fileVsFirstCanonical=true`, `remove.*.fileVsFirstCanonical=true`.

### After restore

On both JDKs the bytes the class has after `removeTransformer` and one `retransformClasses` (seen by a recording
transformer moved to the end of the chain) equal the bytes captured at the first transform byte for byte, and one
more retransformation with a fresh recorder gives the same bytes again (`remove.*.onceMore`): 9,810 bytes for
`AccessControl`, 2,330 (Java 25) and 2,315 (Java 8) for the toy. The remove itself needs one retransformation: the
recorder that was registered ahead of the hook is re-registered last, so the restore and the verification are the
same retransformation.

### What makes it differ, and what the byte check can see

Each scenario hooks `DepositRule.rateName()` (constant `"rate:standard"`) in a fresh loader; "another agent" is a
transformer that rewrites that constant (`ForeignPatch`).

| Scenario | Java 25 | Java 8 | Seen by the byte check | Seen by the event ledger |
|---|---|---|---|---|
| BC0 nothing else | identical, behaviour unchanged | identical | – | 0 foreign events |
| BC1 another retransformation-capable transformer registered after our install, still there at restore | after ≠ first; the class ends `rate:STANDARD` | the same | **yes** (bytes and canonical differ) | 0 (it ran only in our restore) |
| BC2 such a transformer registered before our install, never applied (nobody had retransformed) | identical, but our install applied it: before `rate:standard`, after `rate:STANDARD` | the same | no | no |
| BC3 someone redefines the class with its own class file while hooked | after = the class file (≠ first in bytes, canonically equal) | after = first (bytes identical) | Java 25 bytes only | **yes, 1 foreign event** |
| BC4 someone redefines the class with changed bytes while hooked | after = the redefined class (`rate:redefine`) | **after = first: our restore reverted the redefinition** (`rate:standard`), bytes identical | Java 25 only | **yes, 1 foreign event** |
| BC5 a retransformation-capable transformer changed the class at load and was removed before we came | identical, but our install re-based the class on the JVM's initial bytes: before `rate:STANDARD`, after `rate:standard` | the same | no | no |
| BC6 a transformer without retransformation changed it at load | identical, the load-time change kept | the same | – | – |

Why (the observations agree with the JVMTI specification's "initial class file bytes"): a retransformation starts from
the bytes the JVM cached when a retransformation-capable transformer first changed the class (or from reconstitution if
none did) and runs every retransformation-capable transformer registered
at that moment. So after equals first by construction unless a transformer in the chain at restore changes it (BC1)
or a redefinition replaced the cached bytes, which Java 25 does (BC3, BC4) and Java 8 (8u504) does not, so that there a
restore puts the class back to the bytes cached before the redefinition. BC2 and BC5 are invisible to both checks:
JVMTI shows no "current bytes", and our first retransformation is itself what applies or drops the other agent's
change. The ledger is the recorder that the engine registers ahead of the hook at install: it sees every transform
event of the touched class; the engine counts its own retransformations, and the difference is the foreign events
(`item3.bc3.foreignEvents=1`, `item3.bc4.foreignEvents=1`, 0 everywhere else).

## 4. Size limit

The sweep (`item4.size.*`; `Latency.java`): per generated class, the JVM alone (a retransformation with only a
counting transformer registered, median of three), the hook's install (Byte Buddy plus the retransformation) and its
remove, with the longest gap a spinning host thread saw (a lower bound: the thread sometimes misses the pause; the
safepoint logs below are the reference). `JAVAAGENT`, two passes.

| Class | Bytes | Constant pool | Java 8: JVM only | Java 8: install | Java 8: pause | Java 25: JVM only | Java 25: install | Java 25: pause |
|---|---|---|---|---|---|---|---|---|
| 1 small method | 314 | 15 | 4.4 / 4.5 | 11.0 / 11.3 | 4.3 / 4.4 | 3.8 / 3.4 | 9.5 / 5.2 | 3.7 / 3.3 |
| 10 | 1,098 | 42 | 4.4 / 4.6 | 11.5 / 8.9 | 4.3 / 4.6 | 3.8 / 3.5 | 13.4 / 5.8 | 3.7 / 3.4 |
| 100 | 9,128 | 312 | 5.1 / 5.0 | 15.5 / 19.4 | 4.9 / 4.9 | 3.6 / 4.1 | 12.4 / 12.0 | 3.4 / 4.0 |
| 1,000 | 91,929 | 3,012 | 8.5 / 8.1 | 35.4 / 27.3 | 7.3 / miss | 4.2 / 4.8 | 18.2 / 25.0 | 3.5 / 4.1 |
| 3,000 | 279,929 | 9,012 | 15.6 / 16.4 | 52.7 / 58.9 | 11.0 / miss | 6.3 / 6.8 | 36.9 / 31.4 | 4.3 / 4.6 |
| 10,000 | 937,930 | 30,012 | 45.6 / 46.9 | 154.0 / 165.5 | 26.2 / 26.2 | 20.1 / 23.0 | 123.5 / 117.4 | 5.8 / 6.9 |
| 20,000 | 1,897,930 | 60,012 | 113.5 / 116.5 | 345.6 / 366.2 | 50.9 / 51.2 | 65.3 / 64.8 | 338.5 / 323.6 | 6.9 / 7.0 |
| 1 method of 60 KB code | 60,248 | 13 | 7.9 / 8.5 | 12.4 / 15.6 | 6.8 / 7.5 | 5.1 / 8.4 | 9.0 / 11.9 | 4.7 / 7.9 |
| 4 such | 240,365 | 16 | 15.1 / 15.3 | 36.6 / 27.6 | 11.0 / 10.5 | 5.6 / 6.4 | 13.1 / 9.3 | 4.4 / 5.1 |
| 16 such | 960,840 | 28 | 42.8 / 41.9 | 51.4 / 50.2 | 25.3 / 24.6 | 13.0 / 12.2 | 37.7 / 20.8 | 6.4 / 6.6 |
| 30 such | 1,801,400 | 42 | 72.2 / 72.7 | 87.5 / 81.6 | 40.5 / 40.3 | 15.6 / 18.9 | 29.6 / 75.3 | 6.3 / 7.9 |

("miss": that pass's reading was below 1.4 ms while the JVM alone took 8-16 ms; the spinning thread was not
scheduled across the pause.) `ATTACH` gives the same picture (Java 8 at 20,000 methods: install 356 / 410 ms, pause
40 / 45 ms). The safepoint logs agree: on Java 25 every
`RedefineClasses` safepoint of a whole run (132 per run) spent a median 4.4-5.0 ms and at most 9.3 ms at the
safepoint (`safepoint.log`, three runs); on Java 8 the longest stop of a run was 51.5 / 53.0 ms
(`Total time for which application threads were stopped`, `stdout.log`).

What the stop depends on (`item4.pause.loaded.*`): on Java 25 it grows with the number of classes loaded in the JVM,
not with the class (a 10-method class, medians over the `JAVAAGENT` and `ATTACH` runs: 4.7-6.3 ms at about 16,000
loaded classes, 5.6-6.7 at 26,000, 6.7-7.2 at 46,000, 8.3-11.6 at 64,000-76,000); on Java 8 it grows with the class
(above) and little with the loaded classes (6.6-7.9 ms at 13,000-33,000, 9.1-9.5 at 63,000). Byte Buddy's default
discovery (match every loaded class) instead of an explicit list costs 22-49 ms on Java 25 and 34-615 ms on Java 8 for
the same install (`item4.pause.loaded.*`, last column), the explicit one 7-22 ms.

**Proposed limit: never retransform a class whose class file (as the JVM hands it to the transformer) is larger than
256 KiB or declares more than 2,000 methods.** At the 3,000-method, 273 KiB point the install took 53-59 ms (Java 8)
and 31-37 ms (Java 25) and stopped the world for about 11 ms on Java 8, below one 60 Hz frame (16.7 ms); at 916 KiB
the Java 8 stop is 26 ms and the install 154-166 ms; at 1.8 MiB 51 ms and 346-410 ms. Byte Buddy's cost follows the
method count rather than the bytes (1.8 MiB of code in 30 methods: 82-88 ms on Java 8; 1.8 MiB in 20,000 methods:
346-366 ms), hence the second bound. For scale: the sample desk's largest class is 35 KB, HSQLDB's largest 81 KB,
Byte Buddy's own largest 73 KB. Independent of the limit, every retransformation stops the world for 3-12 ms on Java 25
in a JVM of 16,000-76,000 classes: hooks on one class should be installed and removed together, in one
retransformation each way.

## 5. Where `Spy` lives

- **The project's package on the bootstrap path** (`bs3-spy.jar`): visible from the system loader, the host's
  `ToolShedLoader`, generated-class loaders and anything that delegates up to the bootstrap loader. **Not visible** from
  the `StrictDelegationLoader` (`item1.visibility=NOT_VISIBLE`): the instrumented class's reference goes to its own
  loader, which knows only `java.*` and its own jar.
  - The engine's visibility probe (`Class.forName(Spy, false, targetLoader) == bootstrap Spy`) refuses such a target:
    `item1.unforcedInstall=UNAVAILABLE(NOT_VISIBLE)`.
  - Forced, with `suppress = Throwable.class`: the host gets the right result, no event fires, and every call creates
    and catches a `NoClassDefFoundError` inside the method: 3.9-5.7 µs per call against about 1 ns
    (`item1.invisibleSpyNsPerCall`).
  - Forced, without `suppress`: the host's own call fails with `java.lang.NoClassDefFoundError:
    ai/skillroy/testjava/spikes/bs3/spy/Spy` on the first and every later call (`item1.unguarded.*`); after the remove
    the class works again.
- **A `java.*` package on the bootstrap path** (`bs3-spy-java.jar`, `java.ai.skillroy.testjava.spikes.bs3.spy.Spy`,
  the placement Arthas and jvm-sandbox use): accepted by `appendToBootstrapClassLoaderSearch` on Java 8 and Java 25,
  loaded by the bootstrap loader, visible from the strict loader; the hook fires there (`check.item1.eventsFromStrictLoader`,
  `jdk{8,25}-javaspy`). What it costs: no fire-time difference; such a class can only ever be defined by the bootstrap
  or platform loader (`java.lang.SecurityException: Prohibited package name: java.ai.skillroy.testjava.spikes.bs3.spy`
  from an ordinary loader, `item1.javaPackageInOrdinaryLoader`), so it can never sit on a class path, in a test's class
  path included; javac compiles a `java.*` package in the unnamed module with `--release 8`, `11` and `25` without a
  complaint; it squats on the JDK's namespace (nothing stops a future JDK from refusing that).
- **Any bootstrap append** (either placement) prints the CDS line of condition 2 on Java 25, once per append, between
  the stderr markers the probe writes around it (`jdk25-system-append/stderr.log`); none with `-Xshare:off`
  (`jdk25-xshare-off`), none on Java 8, and none for a system class path append (`appendToSystemClassLoaderSearch`, the
  comparison at the end of `jdk{8,25}-system-append`). A system class path placement would avoid the line but would be
  invisible to loaders whose parent chain skips the system loader (a `null` parent, the platform loader) as well as to
  strict ones; it is not built.
- **Before the append**: a class linked against `Spy` before the append keeps failing after it
  (`spy.earlyReferenceBeforeAppend` and `spy.earlyReferenceAfterAppend` are both `NoClassDefFoundError`; a fresh class
  resolves it). The first smoke run failed this way one level up: the probe's own class passed the registry where a
  `Spy.Handler` was expected, so the verifier loaded `Spy$Handler` when the probe was linked, before the append;
  `SpyAccess.handler(Object)` fixes it.

## 6. What reaches the host's shared loaders

Snapshots of the bootstrap, platform (or extension) and system loaders before the append, after the detach and after
the second engine (`item6.*`). With the measure, in every configuration: the bootstrap loader gained `Spy`,
`Spy$Handler`, `Spy$Stub` and nothing else; the system and platform loaders nothing of the agent's. Byte Buddy's own
generated classes live in loaders of its own: one class in a `JavaDispatcher$DynamicClassLoader` on both JDKs, and on
Java 25 one more (the module-aware transformer subclass) in a `ByteArrayClassLoader` (`item6.addedSinceStart`).

Without it (`jdk{8,25}-nexus-default`, Byte Buddy as it comes): the system loader gains
`ai.skillroy.testjava.spikes.bs3.shaded.bytebuddy.dynamic.Nexus`; on Java 25 the bootstrap loader also gains
`java.lang.ClassLoader$ByteBuddyAccessor$V1`, and stderr shows:

```
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::objectFieldOffset has been called by ai.skillroy.testjava.spikes.bs3.shaded.bytebuddy.dynamic.loading.ClassInjector$UsingUnsafe$Dispatcher$CreationAction (file:…/bs3-engine.jar)
WARNING: Please consider reporting this to the maintainers of class ai.skillroy.testjava.spikes.bs3.shaded.bytebuddy.dynamic.loading.ClassInjector$UsingUnsafe$Dispatcher$CreationAction
WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release
```

The cause is in Byte Buddy's source: `AgentBuilder.Default()` constructs its default `InitializationStrategy
.SelfInjection.Split`, whose `NexusAccessor` injects `Nexus` into the system loader when it initialises, before
`.disableClassFormatChanges()` (which sets `InitializationStrategy.NoOp`) or `.with(...NoOp.INSTANCE)` can matter.
The measure (`HookEngine.disableNexus`): set the property named by `Nexus.PROPERTY` to `true`, force
`NexusAccessor`'s initialisation, clear the property. Shading relocates the constant, so the property set is
`ai.skillroy.testjava.spikes.bs3.shaded.bytebuddy.nexus.disabled` and the host's own Byte Buddy is not affected.

**A second install after a remove** (`SecondInstall` from a fresh `EngineLoader` over the same jar, after the first
engine cleared `Spy.handler`): `Spy` is already on the bootstrap path and is the same `Class` object; appending the jar
again is accepted (0.04-0.3 ms) but adds a second path entry and, on Java 25, a second CDS line, so the engine appends
only when the bootstrap loader does not know `Spy`. The fresh engine (its own Byte Buddy copy) installs in 103-134 ms,
its events arrive, its remove restores byte for byte, and its dropped loader is collected (`item6.second.loaderCollected
=true`, a weak reference cleared after `System.gc()`). Appends are permanent: a later `Spy` of another shape could only
be added under another name.

## 7. Shading

- **Byte Buddy 1.18.14** (`net.bytebuddy:byte-buddy`, the latest on Maven Central on 2026-10-09, released 2026-09-14;
  jar SHA-1 `e26b6f5163f36a5ef91f84eea1befae54e11eb76`, 4,658,001 bytes, 3,074 classes; the regular artifact, Java 8
  class files, Multi-Release). It carries **ASM 9.10.1** repackaged as `net.bytebuddy.jar.asm` (its `Opcodes` go up to
  `V27`).
- **Relocated packages**: `net.bytebuddy` → `ai.skillroy.testjava.spikes.bs3.shaded.bytebuddy` (3,048 classes, the
  ASM copy included); `com.sun.jna` → `ai.skillroy.testjava.spikes.bs3.shaded.absent.jna`, a package that exists
  nowhere: Byte Buddy's optional `ClassInjector.UsingJna` references JNA, and unrelocated it would bind to a JNA on the
  host's class path. String constants are relocated with the classes: Byte Buddy's system properties become
  `ai.skillroy.testjava.spikes.bs3.shaded.bytebuddy.{dump,experimental,raw,safe,nexus.disabled,validation,processor,
  depth,naming,reproducible}`, so a host's `-Dnet.bytebuddy.*` does not reach the agent's copy (`jdk25-hostlibs` sets
  `-Dnet.bytebuddy.experimental=true` for its own 1.12.19); constants folded into longer literals (error messages,
  the `META-INF/net.bytebuddy/build.plugins` resource name) keep the old text. The engine's own constants are
  relocated too, so a name that must stay as it is (the host's `net.bytebuddy.ByteBuddy`, looked up for item 2) has to
  be put together at run time.
- **Excluded**: `META-INF/versions/9/module-info.class` (it would declare module `net.bytebuddy` in a jar whose
  packages are all renamed) and `META-INF/maven/**`. **Kept**: `META-INF/LICENSE` (Apache-2.0, preceded by the note
  "This product bundles ASM 9.10.1, which is available under a 3-clause BSD license"), `META-INF/NOTICE` (Byte Buddy's
  Apache-2.0 notice) and `META-INF/licenses/ASM` (the BSD-3-Clause text of ASM).
- **Multi-release entries**: Byte Buddy ships 26 classes under `META-INF/versions/24/net/bytebuddy/jar/asmjdkbridge/`
  (a bridge to the JDK's Class-File API). The shade plugin relocates their contents (`this_class` becomes
  `ai/skillroy/…/shaded/bytebuddy/jar/asmjdkbridge/JdkClassReader`) **but not their paths**, so no lookup can find them.
  Byte Buddy uses the bridge only when the running VM is newer than its ASM (`ASM_FIRST`: VM version above
  `ClassFileVersion.latest()`, Java 27), so it is dead weight up to Java 27 targets. The engine jar's manifest keeps
  `Multi-Release: true`. For the real module: drop the entries, or rename their paths after shading.
- **maven-shade-plugin 3.6.2**, not 3.6.0: 3.6.0 (ASM 9.7) fails the build with `Unsupported class file major version
  68` on those entries; 3.6.2 bundles ASM 9.9.1.
- **Sizes**: `bs3-engine.jar` 5,108,918 bytes (3,199 entries, of which 66 the engine's), `bs3-engine-unrelocated.jar`
  4,769,755 (the longer names cost 0.34 MB), `bs3-spy.jar` 5,346, `bs3-agent.jar` 9,096.
- **The control** (`bs3-engine-unrelocated.jar`, the same child-first loader): with no Byte Buddy on the host it works
  from its own copy; in a host that ships Byte Buddy 1.12.19 the engine, compiled against 1.18.14, **silently runs on
  the host's copy** (`item2.engine.agentBuilder=net.bytebuddy.agent.builder.AgentBuilder from system …
  byte-buddy-1.12.19.jar`), loads 979-1,030 of its classes into the host's system loader and still installs and fires.
  Relocation is what isolates; its absence fails silently, so the build must check that no `net/bytebuddy/` class
  entry remains in the agent's jars.

## 8. Load modes (item 7)

| Target, mode | Outcome | stderr of the target |
|---|---|---|
| Java 25, `JAVAAGENT` | loaded, `premain` | no JEP 451 line; the CDS line of the append (condition 2) |
| Java 8, `JAVAAGENT` | loaded | nothing |
| Java 25, `ATTACH`, no flag | loaded, `agentmain`; `loadAgent` returned normally | the four JEP 451 lines below, then the CDS line |
| Java 25, `ATTACH`, `-XX:+EnableDynamicAgentLoading` | loaded | the CDS line only |
| Java 25, `ATTACH`, `-XX:-EnableDynamicAgentLoading` | refused: `AgentLoadException: Failed to load agent library: Dynamic agent loading is not enabled. Use -XX:+EnableDynamicAgentLoading to launch target VM.` | nothing |
| Java 8, `ATTACH` from the JDK 25 client | loaded; the client throws `AgentLoadException: Failed to load agent library: 0` (attach F1) | nothing |

The JEP 451 warning, verbatim (`target/runs/jdk25-attach/stderr.log`):

```
WARNING: A Java agent has been loaded dynamically (/Users/…/spikes/BS3/target/bs3/bs3-agent.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning
WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information
WARNING: Dynamic loading of agents will be disallowed by default in a future release
```

The host's own libraries print their own lines on Java 25 (`jdk25-hostlibs`: JNA 4.5.2's `System::loadLibrary`
restricted-method warning); they are not the agent's.

## 9. Facts for the Phase 6 contract

1. Byte Buddy 1.18.14's `AgentBuilder` with `RETRANSFORMATION`, an explicit discovery list,
   `disableClassFormatChanges()`, `InitializationStrategy.NoOp` and inline Advice hooks an already-loaded method on Java
   8 and Java 25 targets, `JAVAAGENT` and `ATTACH`, in the system loader and in custom loaders; events arrive on the
   host's thread with arguments, return value and throwable. *Evidence:* `check.base.*=PASS` in
   `runs/jdk{8,25}-{javaagent,attach}`, `runs/jdk25-attach-enabled`.
2. `new AgentBuilder.Default()` injects `Nexus` into the system loader (and on Java 9+
   `java.lang.ClassLoader$ByteBuddyAccessor$V1` into the bootstrap loader, with a four-line `sun.misc.Unsafe` warning on
   Java 25) regardless of the initialisation strategy set afterwards; setting the relocated `nexus.disabled` property
   while `NexusAccessor` initialises prevents it. *Evidence:* `runs/jdk{8,25}-nexus-default`: `item6.systemAdded`,
   `item6.bootstrapAdded`, `stderr.log`; every other run: `check.item6.nothingButSpy=PASS`.
3. With fact 2's measure, the hooks leave nothing in the host's shared loaders but `Spy` (and its two nested types) in
   the bootstrap loader, after install and after remove. *Evidence:* `item6.bootstrapAdded`, `item6.systemAdded=[]`,
   `item6.platformAdded=[]`.
4. One advice shape (enter with `skipOn = OnNonDefaultValue`, exit assigning the return value with `DYNAMIC` typing,
   both `suppress = Throwable.class`) serves `OBSERVE`, `CAPTURE` and `STUB`; mode changes and a swap of the handler
   behind `Spy` take effect at the next call without any retransformation, in about 2 µs and under 1.3 µs. *Evidence:*
   `check.reconfigure.noRetransform` (a counting transformer saw 0), `reconfigure.modeChangeNs`,
   `reconfigure.handlerSwapNs`.
5. `STUB` skips the original body (also a throwing one) and returns the handler's value, the same object for a
   reference type and unboxed for a primitive; a value of the wrong type makes the method return its type's default
   (0, null, false) silently, so stub values must be checked against the return type when they are configured.
   *Evidence:* `check.reconfigure.stub.sample`, `check.reconfigure.stub.toy`, `reconfigure.stub.mistypedValueReturns=0`.
6. A target whose loader cannot resolve `Spy` to the bootstrap copy must be refused at install
   (`Class.forName(Spy, false, loader)`): installed anyway, the guarded advice costs 4-6 µs per call and fires nothing,
   the unguarded advice throws `NoClassDefFoundError` into every host call. *Evidence:* `item1.unforcedInstall`,
   `item1.invisibleSpyNsPerCall`, `item1.unguarded.firstCall`.
7. A `Spy` in a `java.*` package on the bootstrap path is accepted on Java 8 and Java 25, is visible to loaders that
   delegate only `java.*`, costs nothing at fire time, and can be defined by no other loader. *Evidence:*
   `runs/jdk{8,25}-javaspy`: `spy.bootstrapLoaded=true`, `check.item1.eventsFromStrictLoader`,
   `item1.javaPackageInOrdinaryLoader=…SecurityException: Prohibited package name…`.
8. On Java 25 with class data sharing on, each bootstrap append prints one `OpenJDK 64-Bit Server VM warning: Sharing is
   only supported for boot loader classes because bootstrap classpath has been appended` line on the host's stderr;
   not with `-Xshare:off`, not on Java 8, not for a system class path append. The engine must append at most once per
   JVM. *Evidence:* `runs/jdk25-system-append/stderr.log` (the line between the probe's markers),
   `runs/jdk25-xshare-off/stderr.log`, `runs/jdk8-*/stderr.log`.
9. No class that refers to a `Spy` type may be linked before the append: a failed resolution sticks to the referring
   class, and passing an engine object where a `Spy` type is expected makes the verifier load that type when the caller
   is linked. *Evidence:* `spy.earlyReferenceAfterAppend=…NoClassDefFoundError`; §5 (the first smoke run).
10. Relocating Byte Buddy (and the JNA references) under the agent's prefix, loaded child-first for that prefix, keeps
    the agent on its own copy beside a host's Byte Buddy 1.12.19, ASM 5.0.3 and JNA 4.5.2; the host's libraries keep
    working; no linkage error. Without relocation the agent silently runs on the host's copy. *Evidence:*
    `runs/jdk{8,25}-hostlibs`: `check.item2.ownCopy`, `[host] host libraries after: …` in `stdout.log`;
    `runs/jdk{8,25}-hostlibs-unrelocated`: `check.item2.control.engineSilentlyRunsOnHostCopy`.
11. The first transform receives reconstituted bytes: the constant pool as in the class file, methods and code
    attributes reordered, and on Java 8 no `MethodParameters`; they are canonically equal to the file. The byte check
    therefore compares with the bytes captured at the first transform (or canonically), never with the class file.
    *Evidence:* `item3.bc0.fileVsFirst`, `item3.bc0.fileVsFirstCanonical=true`.
12. After `removeTransformer` and one `retransformClasses` with a recorder registered last, the class bytes equal the
    bytes captured at the first transform byte for byte, on Java 8 and Java 25; the verification needs no extra
    retransformation. *Evidence:* `check.remove.*.byteIdentical`, `remove.*.onceMore`.
13. The byte check sees a retransformation-capable transformer still registered at restore (BC1) and, on Java 25 only,
    a redefinition by someone else (BC3, BC4). On Java 8 (8u504) a restore after someone else's redefinition puts back
    the bytes cached before it and the bytes compare equal. A recorder registered ahead of the hook sees every transform
    event of the class; those the engine did not cause are the foreign events, and they reveal a redefinition on both
    JDKs. The post-detach verification must report both. *Evidence:* `item3.bc{1,3,4}.*`, `item3.bc{3,4}.foreignEvents=1`.
14. The engine's own install retransformation runs every retransformation-capable transformer registered at that
    moment from the JVM's initial bytes: it applies another agent's change that was registered but never applied (BC2)
    and drops one whose transformer has since been removed (BC5). Neither check can see it. *Evidence:* `item3.bc2.behaviour`,
    `item3.bc5.behaviour`.
15. A call inside the hooked method during the restore finishes on the old method version: its exit advice runs after
    the restore (an event when the handler is kept, an unhandled no-op when `Spy.handler` was cleared), it returns
    normally, nothing is thrown into the host, and the next call runs the original code without a `Spy` call.
    *Evidence:* `item5.{a,b}.*`.
16. Install of one hook: 111-197 ms for the first in a JVM (Byte Buddy loading and warming), 6-23 ms after,
    103-134 ms from a fresh engine loader; remove with verification 2-10 ms. *Evidence:* §2 table.
17. Every retransformation is a stop-the-world `RedefineClasses` safepoint: on Java 25 a median 4.4-5.0 ms and at most
    9.3 ms at the safepoint (at most 19.5 ms with reaching it), growing with the loaded classes (the spinning thread saw
    8-12 ms at 64,000-76,000); on Java 8 growing with the class (4 ms small, 11 ms at 273 KiB, 51 ms at 1.8 MiB). *Evidence:* `safepoint.log`, `item4.size.*`,
    `item4.pause.loaded.*`.
18. Size limit: classes over 256 KiB of class file or over 2,000 methods are never retransformed; up to that bound the
    install stayed under 60 ms and the Java 8 stop near 11 ms. *Evidence:* §4.
19. An explicit discovery list costs 7-22 ms per install; Byte Buddy's default discovery (every loaded class) 22-49 ms
    on Java 25 and up to 615 ms on Java 8 at 63,000 classes. *Evidence:* `item4.pause.loaded.*`.
20. While a hook's transformer is registered every class the host loads costs 3-8 µs more; the transformer must stay
    registered for the hook's life (any retransformation, another agent's included, rebuilds the class from the initial
    bytes through the registered transformers). *Evidence:* `item4.load.*`; fact 14.
21. Fire cost: `Spy` without a handler or a hook set to `OFF` adds 9-17 ns per call; `OBSERVE` with two rendered events
    about 70-140 ns; `STUB` about 70-115 ns. *Evidence:* `item4.fire.*`.
22. A second engine after a detach finds `Spy` already on the bootstrap path (the same class), works with it, and its
    dropped loader is collectable; a repeated append is accepted but adds a path entry and (Java 25) a CDS line.
    *Evidence:* `item6.second.*`.
23. Load modes: `JAVAAGENT` on Java 25 prints no JEP 451 warning; `ATTACH` on Java 25 prints the four lines of §8
    unless the target runs with `-XX:+EnableDynamicAgentLoading`, and is refused under `-XX:-EnableDynamicAgentLoading`;
    a JDK 25 client attaching to Java 8 gets `Failed to load agent library: 0` after a successful load. *Evidence:* §8.
24. The engine with Byte Buddy adds about 5-7 MB of metaspace and 1,100-1,400 classes to the host. *Evidence:*
    `memory.atStart`, `memory.afterFirstInstalls`.
25. Shading needs maven-shade-plugin 3.6.2 or newer; it relocates string constants (Byte Buddy's property names become
    the agent's own); the Java 24 multi-release entries keep an unrelocated path and are unreachable, which is harmless
    up to Java 27 targets; `module-info` must be excluded; the shaded jar carries Byte Buddy's Apache-2.0 `LICENSE` and
    `NOTICE` and ASM's BSD-3-Clause text. *Evidence:* §7.

## 10. What stays open

- **Other bytecode agents at arrival** (facts 13 and 14): the engine cannot see what another agent changed or
  registered before the first retransformation. Whether the capability probe should look for other agents (JVM
  arguments, known agent classes) and mark hooks `DEGRADED` is a contract decision.
- **The CDS line on Java 25** (fact 8) is host-visible on every start in hooks mode; whether that is acceptable, or a
  system class path placement (no line, less reach) is offered as well, is for the owner. (Answered: acceptable and
  documented, B26 Q14, confirmed by the owner on 2026-10-10; the system class path placement stays gap 52 in
  `docs/TODO.md`.)
- **The class-loading cost** of a registered transformer (fact 20): an engine-wide pre-filter that returns at once for
  classes the engine does not hook was not built or measured.
- **The Java 8 redefinition behaviour** (fact 13) was observed on 8u504 only; other 8u builds were not checked.
- **Pause on a Swing host**: measured with a spinning thread and the safepoint logs, not on an event-dispatch thread
  with a painting UI; a real application class path (tens of thousands of classes with real code) was approximated
  with generated classes.
- **Not run**: the amd64 image, Windows, targets between 8 and 25, hooks on JDK or named-module classes (out of scope
  by §2.6), a second `Spy` of a different shape after the first.
- **Licence audit**: `docs/licence-audit.md` has Byte Buddy as "not audited; verify at adoption, including the shaded
  copy"; §7 has the facts for that entry (Apache-2.0, the bundled ASM 9.10.1 under BSD-3-Clause, both texts in the
  jar). The entry is owed when the hooks module adopts it.
