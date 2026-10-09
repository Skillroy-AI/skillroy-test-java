# BS14: Byteman in process

Run on 2026-10-09. **Proves** (`docs/design/phase6-contracts.md` §7.2): a user-supplied Byteman jar can be loaded in
process through the agent's own `Instrumentation`, with no listener, its rule base reached through Byteman's
`manager:` option by a project class on the system class path, rules submitted, fired into a curated helper, listed and
removed with a byte-identical restore, and `RuleCheck` run in process; nothing listens, no thread is added, no JEP 451
line. The design it serves is §7 (D132-D134).

**Verdict: passed with conditions.** On Java 8 (Temurin 1.8.0_504) and Java 25 (Temurin 25.0.4.1) targets, through
`-javaagent`, the probe appended the sys jar and Byteman 4.0.27 to the system class path, called
`org.jboss.byteman.agent.Main.premain` reflectively (16 ms on Java 8, 59 ms on Java 25), received the `Retransformer`
in the manager's `initialize`, submitted rules (one retransformation, 7 ms), saw them fire on the host's own thread
with their bindings, removed them (2-3 ms) and found the class's bytes equal to the bytes of the first transform,
byte for byte; the listening sockets of the process were the empty set before the load, after it and after the unload,
and no thread was added; a second module instance in the same JVM reused the parked `Retransformer`. Every check of
the two main configurations PASS. The conditions, each a fact below and an "as built" note in §7:

1. **The option string is `listener:false,manager:<RuleManager>`, with no `prop:`.** The contract's
   `prop:org.jboss.byteman.verbose=false` is harmful twice over: `Main` prints `Setting org.jboss.byteman.verbose=false`
   on the host's stdout unconditionally, and Byteman reads the verbose flag by the property's **presence**
   (`Transformer.computeVerbose`: `System.getProperty(...) != null`), so `=false` turns verbose **on**: 127 (Java 8)
   and 135 (Java 25) lines on the host's console against 24 without it (those 24 are the two deliberate trigger
   failures of item 4, below). The order matters too: a `listener:false` **after** `manager:` clears the manager
   (`jdk25-order-reversed`: `initialize` never called, a plain `Transformer`, no route to the rule base).
2. **Byteman does not match a varargs helper method**: `DO fired($0, $1)` against `fired(Object...)` is a type error
   ("invalid method fired for target class …RuleHelper"), at `RuleCheck` and at the first trigger alike. The helper
   declares fixed-arity overloads, one to eight `Object` parameters; those fire.
3. **A trigger-time failure reaches the host's stderr** (`Rule.ensureTypeCheckedCompiled : error type checking rule
   …` and its stack trace, 12 lines) through Byteman's `Helper` trace stream `"err"`, **unless** the module puts its
   own `PrintStream`s into `org.jboss.byteman.rule.helper.Helper.traceMap` (a private static
   `ConcurrentMap<Object, PrintStream>`, reached by reflection) under the keys `out`, `err`, `vrb`, `dbg` and `nzy`:
   then the same failure is in the module's buffer and nothing reaches the console. A failure at transform time (a
   parse error such as an unknown identifier) prints nothing at all; it is visible only in `listScripts`' text for
   the rule ("threw … TypeException …"), so the module reads the listing after a submit for each class's status.
4. **A sys-jar method whose parameter is a Byteman type must be branch-free**: the first build of `RuleManager`
   carried a `CONSTANT_Class org/jboss/byteman/agent/Retransformer`, put there by the `StackMapTable` frames of an
   `initialize` with a branch (a frame names every local's type, the parameter's included). Delegating at once to a
   method with `Object` parameters removes it; the sys jar's constant pool is then clean.
5. **The byte check needs two recorders**: one registered ahead of Byteman's transformer (before `premain`), which
   sees the JVM's bytes at the first transform (the reference), and one registered behind it (after `premain`), which
   sees what the JVM installs; after `removeScripts` the second's last bytes equal the first's first bytes. A single
   recorder re-registered last for the restore (§3.5's way) would, for every later submit, see Byteman's output instead
   of the reference.

## 1. Verdict per item

| Item | Verdict | Evidence (`target/runs/<configuration>/result.properties`) |
|---|---|---|
| 1. Reflective `premain` after the two appends; `manager:` resolution; no `listener:true` needed | **Passed, with conditions 1** | `check.item1.initializeCalledWithRetransformer`, `item1.initializeEvidence` (`thread=bs14-worker;hostname=null;port=null;argClass=org.jboss.byteman.agent.Retransformer;argLoader=<the system loader>;callers=… Main.premain`), `check.item1.takeOnce`, `check.item1.managerOnePublicMethod`; the control `jdk25-order-reversed` (`item1.initializeEvidence=0|`, `item1.retransformer=null`) |
| 2. `Retransformer` signatures on 4.0.27 | **Passed** (as the contract wrote them) | `item2.installScript`, `item2.removeScripts`, `item2.listScripts` |
| 3. A rule with the project helper fires, the helper's slot receives it, removal restores byte for byte | **Passed, with conditions 2 and 5** | `check.item3.entryFixedFired`, `check.item3.exitFixedFired`, `item3.varargsFired=false`, `check.item3.helperRuleConstructor`, `check.item3.byteIdentical` (959 bytes before and after; 1,660 installed), `check.item3.noBytemanRefsAfterRemove`, `check.item3.noForeignEvents`, `check.item3.noEventsAfterRemove` |
| 4. `RuleCheck` in process; what it defines; what reaches the console | **Passed, with condition 3** | `check.item4.sound.noError`, `check.item4.typeError.reported`, `item4.*.classesDefined`, `item4.*.printStream`; `console.txt` holds only the two deliberate trigger failures (`skillroy:entry.varargs`, `skillroy:bad.plain`); `check.item4.redirectCaughtTriggerFailure` |
| 5. Nothing listens; no Byteman thread; a normal exit | **Passed** | `item5.socketsBefore=[]`, `item5.socketsAfterLoad=[]`, `item5.socketsAfterUnload=[]`, `item5.newThreadsAfterLoad=[]`, `item5.newThreadsAfterUnload=[]`, `exit.txt` `host=0` |
| 6. No JEP 451 warning on Java 25 | **Passed** | `exit.txt` `jep451=0` in every configuration; `jdk25-javaagent/console.txt` holds nothing but item 4's deliberate lines |
| (7.4 step 2) A second module instance reuses the parked `Retransformer`; `premain` cannot run twice | **Passed** | `check.reuse.tookParked`, `check.reuse.slotFreeForSecondHandler`, `check.reuse.firedThroughSecondHandler`, `reuse.secondPremain=java.lang.Exception: Main : attempting to load Byteman agent more than once` |
| The sys jar's constant pool | **Passed, with condition 4** | `check.sysjar.constantPoolClean`, `sysjar.refsOutsideJavaAndProject=[]`, class files major 52 |

## 2. What was run

- **Environment.** Build on macOS (Apple silicon), Maven 3.9 on JDK 25, everything compiled with `--release 8`. Runs in
  the Linux image `skillroy-test-java-ci:bs8-arm64` (aarch64, native) through `ci/run-in-linux.sh` without Xvfb:
  Temurin 25.0.4.1 (`/opt/java/openjdk`) and Temurin 1.8.0_504 (`/opt/jdk8`). Byteman 4.0.27 from the local Maven
  repository (`Premain-Class: org.jboss.byteman.agent.Main`, class files major 50, built on JDK 11). A native run on the
  Mac's JDK 25.0.1 passed every item but the socket one (no `/proc`); it is not part of the verdict.
- **The configurations** (`run.sh`, one container each, about two seconds each):

| Configuration | Byteman options | Verdict line | Console lines | JEP 451 |
|---|---|---|---|---|
| `jdk25-javaagent` | `listener:false,manager:…RuleManager` | PASS | 24 (item 4's two deliberate trigger failures) | 0 |
| `jdk8-javaagent` | the same | PASS | 24 (the same) | 0 |
| `jdk25-contract-options` | `listener:false,manager:…RuleManager,prop:org.jboss.byteman.verbose=false` | PASS | 135 (verbose on) | 0 |
| `jdk8-contract-options` | the same | PASS | 127 (verbose on) | 0 |
| `jdk25-order-reversed` | `manager:…RuleManager,listener:false` | FAIL `check.item1.initializeCalledWithRetransformer`, by design (the control) | 0 | 0 |

- **The probe** runs in a child-first loader of its own over `bs14-probe.jar` with the system loader as parent (a
  module loader's shape), on the agent's worker thread, after the host has loaded and used `LendingToy`. It names no
  Byteman, `RuleManager` or `RuleHelper` type: everything is reached by reflection after the appends, and the handler
  class that implements `RuleHelper.Handler` is loaded by name after them (BS3 RESULT §9 fact 9).
- **The rules**: `skillroy:entry.varargs` (`AT ENTRY`, `fired($0, $1)`), `skillroy:entry.fixed` (`AT ENTRY`,
  `observe($0, $1)`), `skillroy:exit.fixed` (`AT EXIT`, `observe($0, $!)`), submitted in one `installScript` call;
  `RuleCheck` on a sound rule, a type error (`$!.noSuchMethod()`), `$!` at `ENTRY` and the varargs call; two
  trigger-time failures (`noSuchHelperMethod($0, $1)`, plain and redirected) and one transform-time failure (an
  unknown identifier) on `deposit`; the second instance's `skillroy:reuse.exit` (`observe($1, $!)`).
- **Not run.** The amd64 image (only the arm64 build of the same image); `ATTACH` (the module's load is the same call
  in both modes, and the agent already holds its `Instrumentation`); a host with its own Byteman on its command line
  (§7.4 step 2's "loaded by the host" case is decided by the property and the manager's loadability, which the module's
  unit tests cover).

## 3. Item 1: how `Main` finds the manager

Read from `Main.premain`'s byte code and confirmed by the run:

- The options are split on `,` and handled in order. `manager:<class>` sets the manager class name; `listener:true`
  sets it to Byteman's own `TransformListener`, `listener:false` sets it to **null**. The default is null (no
  listener). So `manager:` needs no `listener:` at all, and `listener:false` before it is harmless, after it fatal to
  the route (the control). `prop:<name>=<value>` prints `Setting <name>=<value>` on `System.out` and sets the system
  property; it accepts only names under `org.jboss.byteman.`.
- The manager class is loaded with `ClassLoader.getSystemClassLoader().loadClass(name)`, so it must be on the system
  class path (the sys jar's append). `Retransformer` is created (rather than the plain `Transformer`) only when a
  manager is named and `Instrumentation.isRedefineClassesSupported()` is true (`Can-Redefine-Classes: true` in the
  agent's manifest; the project's bootstrap manifest has it). `Main` then calls `inst.addTransformer(transformer,
  true)` itself.
- `initialize` is resolved with `Class.getMethod("initialize", <Retransformer class>, String.class, Integer.class)`
  (public, static: invoked with a null receiver), and on `NoSuchMethodException` with `getMethod("initialize",
  <Retransformer class>)`. The run: one call, on the calling thread (the agent's worker), `hostname=null`, `port=null`,
  the argument a `org.jboss.byteman.agent.Retransformer` defined by the system loader, called from `Main.premain`.
- `premain` sets `org.jboss.byteman.agent.loaded=true` and refuses a second call in the same JVM with
  `java.lang.Exception: Main : attempting to load Byteman agent more than once` (static `firstTime`), before it reads
  any option.
- `Main` creates one unconnected `java.net.Socket` object and never uses it (dead code after the script loading); it
  binds and listens nothing (item 5).

## 4. Item 3: the helper

- Byteman builds a subclass of the helper per rule (`<helper>_HelperAdapter_Interpreted_<n>`, defined in a fresh
  `org.jboss.byteman.modules.ClassbyteClassLoader` whose parent is the trigger class's loader) whose constructor calls
  the helper's `(Rule)` constructor when the helper **declares** one (`getDeclaredConstructor`), else the no-arg one,
  and `Rule.execute` instantiates the adapter through `getConstructor(Rule.class)` **once per firing**: two firings,
  two `(Rule)` constructions, no no-arg construction.
- The helper reads the rule reflectively from the `Object` it kept: `getName()` is the rule's name as written
  (`skillroy:entry.fixed`, not Byteman's internal key `skillroy:entry.fixed_5:0`), `getTriggerClass()` the binary name
  with dots, `getTriggerMethod()` the method's name (`ceiling`).
- Bindings arrive as the objects themselves: `$0` the `LendingToy` instance (reported as a handle by the handler),
  `$1` the `String` argument, `$!` the `BigDecimal` result; the handler ran on the host's thread `lending-toy`.
- `listScripts` writes, per rule, its text (with a `NOCOMPILE` line Byteman inserts), the loader and trigger method of
  each transform, and `compiled successfully`, `failed to compile` with the type error, or `threw <exception>` for a
  transform-time failure; after the last removal it writes `no rules installed`.
- `removeScripts(texts, out)` finds rules by their `RULE <name>` lines (any text with that line does), writes
  `uninstall RULE <name>` per rule, and retransforms the affected classes in one call; `removeScripts(null, out)`
  removes every rule (the module never uses it). `installScript` writes `install rule <name>` per rule and
  retransforms every matching loaded class in **one** `retransformClasses` call (no batching of its own).

## 5. Item 4: `RuleCheck`

- Constructed with no argument (it builds its own `HelperManager` and a `Transformer` with no `Instrumentation`;
  `AccessManager.init(null)`), `setPrintStream(buffer)`, `addRule(fileName, text)`, `checkRules()`, `getResult()` with
  error, type error, parse error, warning and type-warning counts and lists. Its whole output goes to the buffer: in the
  non-verbose configurations nothing appears on the console between its markers.
- It loads the rule's class through **its own loader** (`getClass().getClassLoader().loadClass(name)`, the system
  loader once Byteman is appended), so it must run only for a class the system loader already defined (the contract's
  rule), and it reads the class file through `getResourceAsStream`.
- **It defines classes**: the first check of a sound rule 181-182 (most of them JDK and Byteman classes that load once:
  regex, ASM inside Byteman, the adapters), among them the helper adapter
  `…RuleHelper_HelperAdapter_Interpreted_1` in a new `ClassbyteClassLoader`; later checks 6-10. A type error is
  reported as one type error with the text `ERROR : Failed to type check rule "<name>" … TypeException: …`.
- `$!` in an `AT ENTRY` rule is **not** an error for `RuleCheck`: two type warnings, the second "failed to find any
  matching trigger method in class …". Such a rule would install and never fire.
- At run time a type error is detected at the **first trigger** (`Rule.ensureTypeCheckedCompiled`), printed through
  `Helper.err` (12 lines with the stack trace) and the rule disabled; with the trace-map redirect of condition 3 the
  lines go to the module's stream instead.

## 6. Item 5: sockets and threads

`/proc/self/net/tcp` and `tcp6` entries in state `0A` whose inode is the target of one of `/proc/self/fd/*`: the empty
set before the appends, after `premain` and the first submit, and after the last removal, on Java 8 and Java 25. The
thread set (by name, the probe's own worker excluded) is unchanged after the load and after the unload: Byteman starts
no thread without its listener (`TransformListener` is the only class of the jar that extends `Thread` or opens a
`ServerSocket`). The host exits 0 after the probe.

## 7. Facts for the Phase 6 build

1. **The option string** is `listener:false,manager:ai.skillroy.testjava.agent.byteman.sys.RuleManager`, in that order,
   and nothing else: no `prop:` (it prints on the host's stdout and turns verbose on), no `boot:`, `sys:`, `script:`,
   `policy:`, `modules:` (§7.5's list). §7.4 step 3 is corrected as built.
2. **`RuleManager.initialize`** is `public static void initialize(org.jboss.byteman.agent.Retransformer, String,
   Integer)`, branch-free, delegating to an `Object`-typed private method; Byteman calls it once, from `premain`, on
   the module's own worker thread, with `hostname` and `port` null. The module calls `take()` right after `premain`
   returns, on the same thread. Since the class's public method is callable by anyone, `initialize` stores only the
   **first** `Retransformer` of the JVM (later calls are ignored), so that no class can replace or clear it.
3. **The helper** declares a public `(org.jboss.byteman.rule.Rule)` constructor (branch-free: it stores the rule as
   `Object`) and no other; Byteman constructs one helper per firing. The name, trigger class and method come from the
   rule's public `getName()`, `getTriggerClass()`, `getTriggerMethod()`, read by reflection.
4. **No varargs**: `fired(Object)` to `fired(Object, …, Object)` (one to eight) are fixed-arity overloads; a rule with
   more than eight bindings is a type error, which `rule.check` reports.
5. **Two recorders** for the byte verification (condition 5): ahead (registered before `premain`; first-transform
   bytes, recording only classes a rule of the module names) and behind (registered right after `premain`; installed
   bytes). The restore's comparison is behind-last with ahead-first; the residue prefix is `org/jboss/byteman/`
   (installed bytes reference `org/jboss/byteman/rule/Rule`, `…/exception/EarlyReturnException`,
   `ThrowException`, `ExecuteException`). Transform events the module did not cause (outside its own
   `installScript`/`removeScripts` calls on its own thread) are foreign.
6. **Byteman's console output** goes through `Helper.traceMap`; the module installs its own streams there (the five
   keys) right after `premain`, writing to the agent's log at WARN, and removes them by identity at `uninstall`
   (`ConcurrentMap.remove(key, stream)`). Lines printed by `Main` itself before that point exist only with `prop:` or
   an invalid option, which the module never passes. A host that sets `org.jboss.byteman.verbose` or
   `org.jboss.byteman.debug` itself gets its verbose lines in the agent's log, not on its console.
7. **Statuses come from `listScripts`**: after a submit the module lists and parses, per rule and per class, `compiled
   successfully` (OK), `failed to compile` (type error at the first trigger) or `threw …` (transform-time failure; the
   rule is not injected). A rule whose status is a failure is reported in `classes[].status` and `messages`.
8. **`rule.check`'s type check** treats `RuleCheck`'s type warnings as errors (a rule Byteman would not inject, such
   as `$!` at `ENTRY`, is refused) and runs only when the class is defined by the system loader; `RuleCheck` defines
   the helper adapter in a throwaway loader and loads Byteman's own classes, nothing of the target.
9. **Reuse**: a parked `Retransformer` is taken by the next module instance with no append and no `premain`;
   `Main.premain` refuses a second call, so the property `org.jboss.byteman.agent.loaded=true` with nothing parked
   means rules are still installed by an earlier instance (or the host loaded Byteman), never "load again".
10. **Costs**: `premain` 16 ms (Java 8) and 59 ms (Java 25); a submit of three rules on one class 7 ms (one
    retransformation); a removal 2-3 ms; the first `RuleCheck` 30-36 ms, later ones 1-4 ms.

## 8. What stays open

- `ATTACH` and the amd64 image were not run; the module's `BytemanIT` runs on the image's targets.
- A host whose own command line loaded Byteman: the module refuses it by the rule of §7.4 step 2 (gap 57); not run.
- `org.jboss.byteman.dump.generated.classes` and the other `org.jboss.byteman.*` properties a host may set make
  Byteman write files or change its compilation; the module neither sets nor clears them (the host's own
  configuration).
