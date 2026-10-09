# Phase 6 contracts: hooks, Byteman, Windows, OTel, hardening (the hook engine and `Spy`, the optional Byteman module, a JDK 25 target through `JAVAAGENT`, the Windows path, OpenTelemetry export, the tenant-pack overlay, the repeat security review and release hardening)

**Status: specified 2026-10-09, revised the same day after the contract review; not built.** The questions of §17
are answered by the orchestrator and recorded as decision **B26** (`../DECISIONS.md`); the decisions the build and its
review take without the owner will be D-numbered in §16 and recorded as **B27**; the sign-off of the repeat security
review is the owner's and will be **B28** (§12.6). Spike **BS3** (the hook go/no-go) reported on 2026-10-09 while this
contract was written: **passed with conditions** (`../../spikes/BS3/RESULT.md`: Java 8 and Java 25 targets,
`JAVAAGENT` and `ATTACH`, 17 configurations, every probe verdict PASS), and its six conditions are rules of §2 and §3;
a statement that rests on the spike cites it as `BS3 RESULT §n` (its §9 is the numbered list of facts). The security
review's first pass (lenses L3-L6 on the committed tree, §12.2) finished the same day: 28 confirmed findings **S35-S62**
(1 high, 6 medium, 21 low), dispositioned in the session notes' `pass1/dispositions.md`, which the fifth work package
builds; four of its fixes change confirmed decisions and are **D156-D159** here (§12.9, §17 Q18-Q21). The contract
itself was then reviewed through three lenses (fit to the code, safety, testability and form), each with a sceptic;
the 65 confirmed findings and the 27 the sceptics added are applied, a recheck of the revision (two readers) found
three of them unresolved and thirteen new ones, applied the same day, and the session notes'
`contract-review/revision-log.md` says where each went. Written in the manner of `phase2-contracts.md` to
`phase5-contracts.md`: the contract is changed first, then the code, and the build marks every passage it builds or
deviates from with an "As built" note; the review's fixes carry "fix wave" notes. The implementer tags of those notes
are two letters (§2.3; D151). Section references `P1 §n` to `P5 §n` are to the five earlier contracts, `design §n` is
the design overview `base-framework-design.md`, `attach §Fn/Rn/Cn` and `control-channel §Fn` are the research notes
`../research/attach-instrumentation.md` and `../research/control-channel.md`, and `map n §m` is one of the seven
reader maps of 2026-10-09 kept in the session notes (not in this repository). Decisions taken here are **D114 to
D159** (§16; D105-D109 were never minted and are not back-filled; D149 and D150 are superseded by D156 and D159);
the build's decisions start at **D160** and are numbered by the orchestrator at integration (§16's preamble);
amendment ids continue the global sequence from **A40**, assigned by the orchestrator in `dispositions.md` for the
security fixes (A40-A60, §12.2) and by the orchestrator at integration, from A61, for any other the build needs (never
by an implementer, §2.3); gaps continue from **50** (§15.2); the repeat review's
findings are S35 onwards; the one new spike is **BS14** (§7.2).

What Phase 6 is for, in one paragraph. Phases 1 to 5 built everything a scenario needs through public seams: the
application's own input seam, its decision code as verifiers, its data through guarded reads, its web views through
their protocol, and a model where B6, B7 and B15 allow one. Phase 6 adds what is left of the design (design §8, row
6): the one place where the agent changes bytecode, the optional hook engine on a shaded Byte Buddy with its
bootstrap `Spy`, probes, health and a byte-identical restore on detach; the optional Byteman module for ad-hoc rules,
in process and without the listener the design row named; the proof that a JDK 25 target loaded through `JAVAAGENT`
behaves as the JDK 8 one; the Windows path, specified in full and built where a Linux image can test it; OpenTelemetry
export from the controller, off by default; the tenant-pack overlay proven on a second variant of the sample lending
desk; and the hardening a first public release needs: the repeat security review in two passes, a written threat
model, `SECURITY.md` and a release checklist. **Nothing of Phase 6 changes the default path**: without the agent
option that names the hooks module no third-party class loads in a target JVM, without `--telemetry` no exporter
exists, and the committed sample scenarios A to D pass unchanged with hooks on and off, while the new scenario E is
`PASS` with hooks and `DEGRADED` (reported as skipped, B14) without.

---

## 1. Scope and non-goals

Phase 6 delivers:

- **`skillroy-agent-spy` and `skillroy-agent-hooks`** (Java 8): the one-class spy jar appended to the bootstrap
  search path in hooks mode only, and the optional hooks module on a shaded, relocated Byte Buddy: probe, install,
  stub, remove and health of catalogued hooks in the modes `OBSERVE`, `CAPTURE` and `STUB`, a touched-class ledger,
  the post-detach byte verification with its foreign-event count, the capabilities `hook.engine` and `hook.<id>`, the
  events `hook.<id>`; a distribution that ships the module without loading it unless the agent's options name it (§2,
  §3, §4);
- **the hook catalogue** as a YAML resource of a domain pack, parsed by the controller (the agent has no YAML
  reader), carried as JSON `HookSpec`s over the `hook.*` primitives, installed once per target and idempotent by
  catalogue hash; the run-level switch `AUTO | ON | OFF`; the capability matrix archived per run; the base fact root
  `hooks`; the new sample scenario E, which needs a hook and ends `PASS` with hooks and `DEGRADED` without (§5, §6);
- **`skillroy-agent-byteman`** (optional, B8): a thin module that loads a user-supplied Byteman jar **in process**
  through the agent's own `Instrumentation` and submits rules through Byteman's manager hook, never through its TCP
  listener; `rule.check`, `rule.submit`, `rule.unload`, `rule.list`; a curated helper in a project jar on the system
  class path; gated by spike BS14 at the start of the build (§7);
- **a JDK 25 target through `JAVAAGENT`**: a check that the target's own log carries no JEP 451 warning, with the
  `ATTACH` leg as the positive control, and the capability matrix compared per load mode against a committed golden
  matrix on the Java 8 and the Java 25 target (§8);
- **the Windows path**, specified in full: the `.bat` launcher mirroring the shell launcher, `PATHEXT` for the
  planner's executable, `cmd.exe /c` where a launcher is written into a configuration, the non-POSIX branch of every
  owner-only file the controller writes, cross-session attach documented as unsupported, `ATTACH` on Windows best
  effort; built and unit-tested where no host is needed, every other item marked "waits for the Windows host" (§9);
- **OpenTelemetry export** (B11): controller only, nothing in the target JVM; a `RunObserver` SPI in
  `skillroy-scenario` with no OpenTelemetry type; the bundled module `skillroy-telemetry` on the OpenTelemetry SDK
  with OTLP/HTTP through the JDK sender; explicit opt-in, no autoconfiguration from the environment, loopback or
  HTTPS only, spans only, attributes from an allow-list, masked and bounded, never call parameters or values; the
  agent's side reconstructed from replies and events (§10);
- **the tenant-pack overlay**: the SPI `DomainPackOverlay` over a base pack (a second jar on the class path), a
  `PackSettings` surface for a pack's constants, the hook catalogue overridable by id; proven on the **branch
  edition** of the sample lending desk, a configuration variant of the same application jar in which a resolver, a
  constant, the allow-list and the hook catalogue are overridden and the scenarios are unchanged (§11);
- **the repeat security review and release hardening**: two passes over eight lenses with one sceptic per lens, the
  four-level scale with its rubric, the record, the dispositions and the owner's sign-off; pass 1's 28 findings fixed
  by the build; the threat model `threat-model.md` beside this file; `SECURITY.md` and `docs/release-checklist.md`;
  four hardening decisions from pass 1 stated once here (§12);
- **acceptance** without a display, end to end in the Linux image, and the Windows items as a written procedure that
  waits for the host (§13); **exit criteria** that say plainly which of the roadmap's can be met this phase (§14).

**Non-goals.** Named so that their seams are respected, not built:

| Not in Phase 6 | Seam reserved here | Phase |
|---|---|---|
| Hook fallbacks of kind `reflect` (design §2.6: `{reflect: {class, field}}`) | parsed by the catalogue reader and refused ("not in this version"); only `seam` fallbacks are honoured (§5.1) | later, if a catalogue needs one |
| `obj.grab(FromHook(…))`, `jvm.classes.find`, `hook.waitFor`, `sync.quiescence`, `trace.bundle`, `agent.modules.install`, `agent.selftest`, `agent.shutdown`, `POST /modules`, `POST /shutdown` (design §2.3, §2.4) | names stay reserved; a captured object travels as a weak handle (§4.3) that a later `obj.*` family may read; `hook.waitFor` is served by `sync.await` on the hook's topic (D126) | later |
| Self-attach in `EMBEDDED` mode (design §2.2 step 1) | `hook.engine` is `UNAVAILABLE("no Instrumentation in EMBEDDED mode")`, exactly as `jvm.instrumentation` is | later, if a target needs it |
| Several catalogues per pack selected by application version (design §2.6: "one YAML file per target application version") | one catalogue per pack or overlay, pinned by the pack's version; the catalogue carries an `application` field for the record (§5.1) | later |
| Byteman over its TCP listener; `boot:` jars, `IMPORT`, system-property rules, `transform.all`; a second `loadAgent` on `ATTACH`; reuse of a Byteman the host's own command line loaded | never (§7; D132): the listener is an unauthenticated port, which the hardening of 2026-10-02 (H1, H2) exists to exclude | none |
| OpenTelemetry metrics and logs; agent-side spans; a gRPC sender; the SDK's autoconfiguration; an OTLP file exporter | the instruments are named (§10.5) and built behind the same observer once spans are proven; `RpcResult.startedAt` is a 6.x amendment if the reconstructed agent span misleads (D142); the `file` exporter writes the project's own format (D143) | later |
| Verification on a Windows host: the ACL branches of S7/S15, the `.bat` launcher run, `JAVAAGENT` as the logged-on user, the rungs and paint capture, cross-session attach | everything is specified (§9) and the Linux-testable part built; every acceptance item that needs the host is marked "waits for the Windows host" (§13.3) and is not an exit criterion this phase can meet (§14) | when the host exists (`../TODO.md`, Walls) |
| A `tasklist` / `/proc/<pid>/cmdline` pid fallback (design §2.2) | dropped from the design (D139): `VirtualMachine.list()` plus the discovery files served five phases | none |
| A Windows transport (dial-out newline JSON-RPC, design §2.3) | unchanged, reserved | later |
| Hooking a target whose loader delegates only `java.*` to its parent | refused at install (§3.3 item 6); the `java.*` placement of `Spy` that would serve it is rejected (D116) and kept as gap 50 | later, if a target needs it |
| The vision bolt-on (B16) | unchanged (P5 §1) | 7 |

Phase 6 adds **five agent-side modules** (`skillroy-agent-spy`, `skillroy-agent-bytebuddy`, `skillroy-agent-hooks`,
`skillroy-agent-byteman`, `skillroy-agent-byteman-sys`), **one controller-side module** (`skillroy-telemetry`) and
**one sample module** (`skillroy-sample-lending-pack-branch`). It brings **one third-party library into a target
JVM**, Byte Buddy with the ASM it bundles, shaded and relocated inside the optional hooks module and loaded only when
the agent's options name that module (B4's carve-out, §2.1, §17 Q1), and **one third-party set into the controller**,
the OpenTelemetry Java SDK (§2.2). Byteman stays the user's jar (B8); the project's own sys jar compiles against it
at `provided` scope and carries nothing of it (§2.1).

---

## 2. Module layout, dependencies, versions, build and distribution

### 2.1 Decision

- **`agent/skillroy-agent-spy`** (artifact `ai.skillroy:skillroy-agent-spy`, package
  `ai.skillroy.testjava.agent.spy`, Java 8): one public class, `Spy`, with its two nested types `Spy.Handler` and
  `Spy.Stub` (§3.1). No dependency, not even on `skillroy-protocol`: it references `java.*` only, so that it links in
  any loader that delegates to the bootstrap loader. It lands in the agent home as **`skillroy-agent-spy.jar` at the
  home's root**, beside the bootstrap jar and outside `lib/` and `modules/` (D116): a jar under `lib/` would be
  defined a second time by the agent loader, which is child-first for every `ai.skillroy.testjava.*` class except the
  bootstrap package (`AgentClassLoader.isChildFirst`), and a handler set on one `Spy` would be read by the other
  (attach §F25, the `LinkageError` precedent). Two loader rules change so that the spy package exists exactly once in
  a JVM: `AgentClassLoader.isChildFirst` excludes `ai.skillroy.testjava.agent.spy.` as it excludes the bootstrap
  package, and `ModuleClassLoader.PARENT_FIRST` gains `ai.skillroy.testjava.agent.spy.` (with the matching resource
  prefix); and **no jar but `skillroy-agent-spy.jar` may hold an entry under `ai/skillroy/testjava/agent/spy/`**
  (`DistributionContentIT` asserts it: the module loader is parent-first for the package, but before the first append
  the parent chain has no `Spy`, so a copy inside a module jar would be defined there, BS3 RESULT §9 fact 9's trap
  made silent). The jar is appended to the bootstrap search path by the hooks module on the first `hook.install`,
  never earlier, and at most once per JVM (§3.2). BS3 settled the placement (RESULT §5, §9 facts 6 and 7): in this
  package `Spy` is visible from the system loader, from parent-first custom loaders and from anything that delegates
  up to the bootstrap loader, and **not** from a strict-delegation loader (one that delegates only `java.*` to its
  parent); such a target's hook is refused at install as `UNAVAILABLE("the class's loader cannot see Spy")` (§3.3
  item 6) rather than failing at advice time. The alternative that is visible there, a `Spy` in a `java.*` package
  (the placement Arthas and jvm-sandbox use), is accepted by the JVM on Java 8 and 25 and costs nothing at fire time,
  but it squats on the JDK's namespace and can be defined by no loader but the bootstrap one (`SecurityException:
  Prohibited package name` from any other), so it can never sit on a class path, a test's included: rejected (D116),
  kept as gap 50.
- **`agent/skillroy-agent-bytebuddy`** (artifact `skillroy-agent-bytebuddy`, Java 8, packaging `jar`): the shading
  module (D115). It has one dependency, `net.bytebuddy:byte-buddy` (§2.2), and `maven-shade-plugin` (3.6.2 or newer:
  3.6.0 fails on Byte Buddy's Java 24 class files with `Unsupported class file major version 68`, BS3 RESULT §7)
  rewrites it into the module's own artifact (`shadedArtifactAttached=false`, `createDependencyReducedPom=true`) with
  two relocations: `net.bytebuddy` → `ai.skillroy.testjava.agent.hooks.shaded.bytebuddy` (ASM travels inside Byte
  Buddy under `net.bytebuddy.jar.asm` and is relocated with it) and `com.sun.jna` →
  `ai.skillroy.testjava.agent.hooks.shaded.absent.jna`, a package that exists nowhere, because Byte Buddy's optional
  `ClassInjector.UsingJna` references JNA and unrelocated it would bind to a JNA on the host's class path (BS3 RESULT
  §7). The shade rebuilds every class's constant pool, so the dangling `org/objectweb/asm/{Opcodes,ClassReader,
  ClassWriter}` entries Byte Buddy's own repackaging left in 93 of its classes do not survive (the review's probe over
  all 3,048 classes found none in the output); no third relocation is needed. Filters drop `META-INF/MANIFEST.MF`,
  `META-INF/maven/**`, `META-INF/LICENSE`, `META-INF/NOTICE`, `META-INF/licenses/**`, `META-INF/versions/**` and
  `module-info.class` of the dependency: the home is Java 8 and Byte Buddy's class files are major 52 (attach §F13);
  the 25 classes under `META-INF/versions/24/` (and `versions/9/module-info.class`) are a bridge to the JDK's
  Class-File API that Byte Buddy uses only on a VM newer than its ASM (Java 27 and later) and whose **paths** the shade
  plugin does not relocate, so dropping them is what makes the jar whole (BS3 RESULT §7, §9 fact 25); the
  dependency's `META-INF/LICENSE`, `NOTICE` and `licenses/ASM` would shadow or duplicate the project's and the
  module's own notice files. Relocation rewrites string constants with the classes, so Byte Buddy's system properties
  become the agent's own (`ai.skillroy.testjava.agent.hooks.shaded.bytebuddy.{dump,experimental,raw,safe,
  nexus.disabled,validation,…}`) and a host's `-Dnet.bytebuddy.*` does not reach the agent's copy; a name that must
  stay as it is (a host class looked up for diagnostics) is assembled at run time. The module's own resources add
  `META-INF/third-party/byte-buddy/LICENSE` and `META-INF/third-party/byte-buddy/NOTICE` (the dependency's files,
  copied verbatim at adoption) and `META-INF/third-party/asm/LICENSE` (the BSD-3-Clause text of the bundled ASM), the
  one copy of each, so that the project's `META-INF/LICENSE` and `META-INF/NOTICE`, which `copy-legal-files` puts
  into every jar and `DistributionContentIT` compares byte for byte with the root files, are never shadowed. The
  module has no code of its own; its one test is the **failsafe `ShadedContentIT`**, bound to `integration-test` so
  that it runs after `package` (the module declares `maven-failsafe-plugin` in its `<build><plugins>`, the root's
  execution being in `pluginManagement`): it opens `target/skillroy-agent-bytebuddy-<version>.jar` and asserts the
  relocation (no entry under `net/`, every class under `ai/skillroy/testjava/agent/hooks/shaded/`, no constant-pool
  class reference to `net/bytebuddy/`, `org/objectweb/asm/` or `com/sun/jna/` in any class, the three third-party
  notice files present and no other `META-INF/licenses` or `LICENSE`/`NOTICE` entry of the dependency, no
  `META-INF/versions`, no `module-info.class`). **A missing relocation fails silently**: BS3's unrelocated control ran
  on the host's Byte Buddy 1.12.19, loaded a thousand of its classes into the host's system loader and still
  installed and fired (BS3 RESULT §7, §9 fact 10), which is why this test and `HomeConstantPoolIT` (§2.5) exist. Why
  a module of its own rather than shading inside the hooks module: surefire runs before `package`, so a hooks module
  that shaded its own dependency would test unrelocated classes and only an IT in `skillroy-agent-dist` would see what
  ships; with the relocation done one module earlier, the hooks module's **source imports the relocated names**
  (`ai.skillroy.testjava.agent.hooks.shaded.bytebuddy.agent.builder.AgentBuilder` and the rest) and every test of it,
  the Java 8 `-Djvm` leg included, runs on the bytes that ship (map 6 §6.3).
- **`agent/skillroy-agent-hooks`** (artifact `skillroy-agent-hooks`, module id `hooks`, package
  `ai.skillroy.testjava.agent.hooks`, Java 8): the hook engine (§3). Dependencies: `skillroy-agent-api`,
  `skillroy-protocol` **and `skillroy-agent-spy`**, all `provided` (the sample module's pattern; the shade mojo
  resolves at `runtime` scope, so none of them is merged, and the spy package stays in its one jar);
  `skillroy-agent-bytebuddy` at compile scope **with the exclusion `net.bytebuddy:byte-buddy`** (within one reactor
  build Maven resolves the shading module's original dependencies, not its dependency-reduced POM: the review's probe
  reactor merged 3,089 unrelocated `net/bytebuddy/` entries into the consumer's jar without the exclusion); JUnit
  (test). Its jar is built by `maven-shade-plugin` too, without relocation: it **merges** `skillroy-agent-bytebuddy`
  into `skillroy-agent-hooks.jar` with `<artifactSet><includes>ai.skillroy:skillroy-agent-bytebuddy</includes>`
  and nothing else, so that the module is one jar (the module loader takes one jar per module), with the
  dependency-reduced POM; its `maven-enforcer-plugin` execution bans `net.bytebuddy:*` from its resolved tree
  (`bannedDependencies`), so that an IDE-completed `import net.bytebuddy…` fails the build instead of passing the unit
  tests. The module compiles against the **packaged** shading module, so it builds only in a reactor run that reaches
  `package`: `mvn -q verify` (the project's rule) and `-pl … -am verify` work, a root `mvn compile` or `mvn test`
  fails in this module by design (`package …shaded.bytebuddy does not exist`), and `README.md`'s build section says
  so. It lands in the home as **`optional/skillroy-agent-hooks.jar`** (D114, below) and carries one
  `META-INF/services/ai.skillroy.testjava.agent.api.AgentModule` entry naming `HooksModule`. Its version is read from
  a filtered `hooks-module.properties`, as the web module reads its own (no hash of the spy jar: §3.2 step 3 checks
  the jar's content, not a checksum, since the reactor has no checksum step).
- **`agent/skillroy-agent-byteman`** (artifact `skillroy-agent-byteman`, module id `byteman`, package
  `ai.skillroy.testjava.agent.byteman`, Java 8) and **`agent/skillroy-agent-byteman-sys`** (artifact
  `skillroy-agent-byteman-sys`, package `ai.skillroy.testjava.agent.byteman.sys`, Java 8): the optional Byteman
  module and the project jar it appends to the system class path (§7). The module depends on `skillroy-agent-api`,
  `skillroy-protocol` and `skillroy-agent-byteman-sys` (`provided`: its handler implements the sys jar's
  `RuleHelper.Handler`) and, at **test scope only**, on `org.jboss.byteman:byteman` (§2.2); it has no compile-time
  dependency on Byteman, which it reaches by reflection through the system loader. The sys jar depends on
  `org.jboss.byteman:byteman` at **`provided` scope only**: its manager and helper are declared with the parameter
  and constructor types Byteman looks them up by (§7.3), so they must compile against it, and they keep what Byteman
  hands them as `Object` and read it reflectively, so that the jar's constant pool holds no `CONSTANT_Class` outside
  `java/` and `ai/skillroy/` (a type in a method descriptor is a UTF-8 entry); nothing of Byteman is in the jar
  (LGPL §6, "a work that uses the library", recorded in `../licence-audit.md` §5). `skillroy-agent-dist` also
  declares `org.jboss.byteman:byteman` at test scope, because its `BytemanIT` needs the jar's path (§7.6; test scope
  is not transitive). Both modules land in the home under `optional/`; neither is loaded unless named. Both are in
  `<modules>` of the root reactor without a profile (D134): a test- or provided-scoped LGPL artifact is the treatment
  JUnit's EPL already gets (`../licence-audit.md` §5) and bundles nothing. If spike BS14 fails (§7.2) the two modules
  are not created and §7 stays "specified".
- **`controller/skillroy-telemetry`** (artifact `skillroy-telemetry`, package `ai.skillroy.testjava.telemetry`,
  Java 25): the OpenTelemetry implementation of the `RunObserver` SPI (§10). It depends on `skillroy-scenario`,
  `skillroy-controller` and the OpenTelemetry set of §2.2; `skillroy-scenario`, `skillroy-junit` and `skillroy-mcp`
  do not depend on it; `skillroy-cli` does, so that the CLI distribution carries it (D140). It is found through
  `ServiceLoader<RunObserverProvider>` on the thread context class loader, the `WebPath` and `ResolverContributor`
  pattern (P4 D49, P5 D70).
- **`samples/skillroy-sample-lending-pack-branch`** (artifact `skillroy-sample-lending-pack-branch`, package
  `ai.skillroy.testjava.samples.lending.pack.branch`, Java 25): the overlay of the sample pack for the desk's branch
  edition (§11.3). It depends on `skillroy-sample-lending-pack` and `skillroy-scenario`.
- **`skillroy-protocol`** gains the package `ai.skillroy.testjava.protocol.hook` (the wire types of §4.1), the
  `Topics` constants of §4.4, the error codes of §4.5 and, built by the security fixes (D156), `PrimitiveDescriptor.script`.
  **`skillroy-agent-api`** gains the SPI `…agent.api.spi.hook.HookEngine`, the default methods `AgentContext.hooks()`,
  `isSecretKey(String)` and `redactText(String)` (§3.6), the package `…agent.api.instrument` (§3.6), the amended
  thread rule of `MaskPolicy` (§4.3) and, by D156, `PrimitiveSpec.Builder.script()`. **`skillroy-agent-core`** gains
  the third module source `optionalModules`, the parent-first rule for the spy package and the two context methods'
  implementations over its `Redactor`. **`skillroy-agent-bootstrap`** gains the child-first exclusion of the spy
  package. **`skillroy-scenario`** gains the package `…scenario.hooks` (§5.2), `…scenario.observe` (§10.2), the pack
  overlay SPI and `PackSettings` (§11.1), `RunOptions.hooks` and `RunOptions.observer`, the base fact root `hooks`,
  the `capabilities.json` archive and the run records of §6.4. **`skillroy-controller`** gains
  `AgentHome.optionalDir()`, `optionalModule(String)`, `spyJar()`, `CapabilityMatrix.toJson()` and `hash()`,
  `CallOptions.traceparent`, and the public `SecureFiles` members of §9.3. **`skillroy-cli`** gains `--hooks`,
  `--telemetry`, the command `hooks`, the `.bat` launcher and the Windows branches of `author` (§6.2, §9.2, §10.3).
  **`skillroy-junit`** reads `skillroy.hooks` and `skillroy.telemetry`. The **sample application** gains the branch
  edition and the `AccessControl.check` change of §6.3 (§11.3); the **sample agent module** gains **one `app.state`
  key, `edition`** (§11.3), nothing else; the **sample pack** gains its hook catalogue, `settings()` and the public
  surface an overlay needs; the **scenarios module** gains scenario E, the variant's plans and bindings, the golden
  matrices and the new acceptance classes.

**The `optional/` directory and the option `optionalModules` (D114).** The agent home gains a third jar directory,
`optional/`, which `ModuleLoader` never scans. A jar in it is loaded only when the agent option **`optionalModules`**
names its file stem: `optionalModules=skillroy-agent-hooks` loads `<home>/optional/skillroy-agent-hooks.jar` as a
module jar (its own `ModuleClassLoader`, service file required, first id wins), in the position between
`<home>/modules/*.jar` and the paths of the `modules` option; a comma separates several stems
(`optionalModules=skillroy-agent-hooks,skillroy-agent-byteman`; in an agent argument string a `;` separates the
options, P1 §2.4, so `optionalModules=…;byteman.jar=<path>`); a stem that names no jar, or a jar without a service
file, is a `FAILED` record (S14: never fatal, visible in `agent.info`). The default path therefore loads **no
third-party class**: the hooks jar is not even opened. The controller's `AgentHome` gains `optionalDir()` and
`optionalModule(String stem)` (the path, whether or not the file exists), and the sample's launcher passes the option
in the hooks leg (§13.2). A consumer who wants no third-party code in the home at all deletes
`optional/skillroy-agent-hooks.jar`: the default home then equals today's plus the spy jar and the Byteman module
(project code only). The §17 Q1 veto (a separate download) removes the hooks jar from the home and the
`DistributionContentIT` list and leaves the mechanism in place for the Byteman module.

The agent home after Phase 6 (P1 §2.1 amended, §15.1):

```
skillroy-agent/
  LICENSE  NOTICE
  skillroy-agent-bootstrap.jar     # the -javaagent / loadAgent jar
  skillroy-agent-spy.jar           # Phase 6: appended to the bootstrap search path by the hooks module, in hooks mode only
  lib/skillroy-protocol.jar  lib/skillroy-agent-api.jar  lib/skillroy-agent-core.jar
  modules/skillroy-agent-swing.jar  modules/skillroy-agent-data.jar  modules/skillroy-agent-web.jar   # plus domain modules dropped in
  optional/skillroy-agent-hooks.jar          # Phase 6: loaded only with optionalModules=skillroy-agent-hooks; carries the
                                             # relocated Byte Buddy and ASM and their notices under META-INF/third-party/
  optional/skillroy-agent-byteman.jar        # Phase 6 (if BS14 passes): loaded only with optionalModules=skillroy-agent-byteman
  optional/skillroy-agent-byteman-sys.jar    # Phase 6 (if BS14 passes): appended to the system class path by that module
```

Dependency graph after Phase 6 (arrows = "depends on"; new edges in **bold**; `(p)` = provided):

```
protocol                                        agent-api -> protocol
**spy (nothing)**                               **bytebuddy -> byte-buddy (shaded, relocated)**
**hooks -> agent-api (p), protocol (p), spy (p), bytebuddy (compile, byte-buddy excluded; merged)**
**byteman -> agent-api (p), protocol (p), byteman-sys (p); byteman (test)**     **byteman-sys -> byteman (p)**
controller -> protocol                          scenario -> controller, data
**telemetry -> scenario, controller, the OpenTelemetry set of 2.2**
cli -> scenario, data, controller, protocol, inference, mcp, **telemetry**
lending-pack -> scenario, data                  **lending-pack-branch -> lending-pack, scenario**
lending-scenarios -> lending-pack, junit, dist, app, agent (test), inference (test), mcp (test), **pack-branch (test), telemetry (test), byteman (test)**
dist -> every agent jar; **byteman (test)**
```

No cycle. `skillroy-scenario` reaches `skillroy-telemetry` only through `ServiceLoader` at run time, as it reaches
`skillroy-cdp` and `skillroy-inference`; it names no inference type (§10.2).

### 2.2 Versions

| Piece | Version | Where | Licence (audited in `../licence-audit.md` §3 and §5 when built) |
|---|---|---|---|
| Byte Buddy (`net.bytebuddy:byte-buddy`) | **1.18.14** (attach §F13: the regular jar has a Java 8 baseline since 1.18.7, class files major 52, Multi-Release; the latest on Maven Central on 2026-10-09, released 2026-09-14; the jar BS3 shaded: SHA-1 `e26b6f5163f36a5ef91f84eea1befae54e11eb76`, 4,658,001 bytes, 3,074 classes, carrying ASM 9.10.1 as `net.bytebuddy.jar.asm`, BS3 RESULT §7) | `skillroy-agent-bytebuddy` only, shaded and relocated into `optional/skillroy-agent-hooks.jar`; never on any class path unrelocated | Apache-2.0; the bundled ASM BSD-3-Clause (attach §F13). The shaded jar is the audited artifact (`../licence-audit.md` §8's "verify at adoption, including the shaded copy"): its §3 row lists the class count, the relocated prefixes and the three notice files |
| Byteman (`org.jboss.byteman:byteman`) | **4.0.27** (attach §F18, §F19; design §1.3) | `skillroy-agent-byteman`, `skillroy-agent-dist` and `skillroy-sample-lending-scenarios` (the hooks leg's Byteman item, §13.2 item 7), **test scope**; `skillroy-agent-byteman-sys`, **`provided` scope** (compile-time only, nothing copied, §2.1); nothing of it is in any distribution; at run time the user supplies the jar (B8, §7.4) | LGPL-2.1-or-later; `../licence-audit.md` §5 row "test scope in three modules, provided in the sys jar (a work that uses the library, LGPL §6); in no distribution; the run-time jar is the user's"; §8 row moved |
| OpenTelemetry Java (`io.opentelemetry:opentelemetry-api`, `-context`, `-sdk-common`, `-sdk-trace`, `-exporter-otlp`, `-exporter-sender-jdk`; test: `-sdk-testing`) | the current **1.x** line at build time (1.66.0 on the research's date, control-channel §F24; pinned in the root POM as `opentelemetry.version` with the reasoning, D88's rule) | `skillroy-telemetry`, main scope; the CLI distribution (`THIRD-PARTY.txt`, `DistributionNoticesIT`) | Apache-2.0 for every artifact of the set (checked per jar at adoption as OkHttp was, `../licence-audit.md` §4); the exporter's transitives (`-exporter-otlp-common`, `-exporter-common`, `-sdk-extension-autoconfigure-spi`, `-sdk-metrics`, `-sdk-logs`) are kept where the exporter needs them at run time (the audit's snippet decides at adoption) and each jar that lands in `lib/` is listed in `THIRD-PARTY.txt` and in `DistributionNoticesIT.THIRD_PARTY`; `-sdk-extension-autoconfigure` itself and `-exporter-sender-okhttp` are **excluded** (D140, D141); the artefacts and the JDK sender's Java level (11 or newer) are confirmed at adoption, nothing of the set being in the local cache on 2026-10-09 |
| `maven-shade-plugin` | **3.6.2** or newer, pinned as `maven-shade-plugin.version` in `pluginManagement` | `skillroy-agent-bytebuddy`, `skillroy-agent-hooks` | Apache-2.0 (a build plugin, `../licence-audit.md` §6 row) |
| Semantic conventions (`test.*`, `gen_ai.*`, `mcp.*`) | the attribute **names** of the OpenTelemetry semantic conventions at the version named in a comment of `TelemetryAttributes` (D142); the `opentelemetry-semconv` jars are not used | `skillroy-telemetry`, string constants | – |

**Before the build wave starts the orchestrator resolves into the local Maven cache, online and once**: the
OpenTelemetry set of this table at the version it pins as `opentelemetry.version` (the current 1.x on that day), its
transitive tree read with the audit's snippet (B1 checklist item 2), and `org.jboss.byteman:byteman:4.0.27`; the
entry under `../TODO.md` "Environment notes" names the versions and the date (B3's "install what is needed"). The
implementers then build offline (`mvn -o`), as the project does. The root POM gains `byte-buddy.version`,
`byteman.version`, `opentelemetry.version` and `maven-shade-plugin.version`, each with the comment form of P5
(`<!-- Phase 6 (phase6-contracts.md 2.2, decision D1nn): <library>, <licence>; audited in docs/licence-audit.md
section <n>. -->`), and the managed entries.

### 2.3 Build conventions

As P2 §2.3, P4 §2.3 and P5 §2.3: `--release 8` for every module under `agent/` and the sample application and agent
module, `--release 25` for the controller and the two pack modules; the Apache header on every file (`.bat` and
`.cmd` are a new kind of `ci/license-headers.sh`, §9.1); no `mvn install`; the reactor is built with `verify`, which
the hooks module needs (§2.1); the assembly descriptors and the content tests are changed on purpose and together.

- **Adding the five agent modules** follows P4 §11's steps for `skillroy-agent-web`: a `<module>` line each before
  `agent/skillroy-agent-dist` (reactor order: `spy`, `bytebuddy`, `hooks`, `byteman-sys`, `byteman`), a managed
  entry each at `${project.version}`, dependencies in `agent-dist/pom.xml` (the spy jar feeds the `/` set), the
  `agent-home-layout.xml` gains a `dependencySet` for `/` (`skillroy-agent-spy`) and one for `optional/`
  (`skillroy-agent-hooks`, `skillroy-agent-byteman`, `skillroy-agent-byteman-sys`), and `DistributionContentIT.EXPECTED`
  gains the four file names (§2.5). The matrix command's "`-pl` the twelve agent-side modules" (`../TODO.md`) becomes
  seventeen.
- **Implementer tags (D151).** "As built" notes carry two-letter tags that collide with no id series of this
  repository (A, B, BS, D, H, R, S, Q, L, F): `HK` (agent hooks and spy), `CP` (controller, pack, sample, JDK 25),
  `TL` (telemetry), `BW` (Byteman and Windows), `SR` (the security fixers, whose work package is FX, §12.2), and
  `FA`-`FD` for the fixers of the Phase 6 code review's own fix wave. Phase 5's single letters `S1`, `S2` are not
  reused: `S35` onwards are security findings. A security fix's passage in an earlier contract is marked "(Phase 6
  security fix S<n>, A<m>, built …)" (§12.2).
- **Decision and amendment numbers are never minted by an implementer.** A build decision is recorded in the
  implementer's own notes as `D-<tag>-<n>` and numbered **D160 onwards** by the orchestrator at integration, in the
  order the packages land (§16's preamble); the security fixes' A-numbers (A40 onwards, in S order, one per finding
  that changes a contract passage) are assigned by the orchestrator in `dispositions.md` before the wave (§12.2).
- **Spike BS14** (§7.2) is a standalone Maven project under `spikes/BS14/` in the form of `spikes/BS6` and
  `spikes/BS13` (README, RESULT.md with a verdict line and "Facts for the Phase 6 build"), run before the Byteman
  module is created.
- **Golden files** (§8.2) and the variant's committed plans and bindings (§11.4) are compared byte for byte, so
  they follow `.gitattributes`'s LF rule; the `.bat` launcher and the `.cmd` runner are the only text files with CRLF
  (`*.bat text eol=crlf`, `*.cmd text eol=crlf`, **below** the LF rule, §9.1).
- **Tests that need an `Instrumentation`** in process (the hooks and Byteman modules' unit tests) get one from a
  test-only premain jar: the module's POM runs a `maven-jar-plugin` execution in `process-test-classes` over
  `target/test-classes` with the manifest entry `Premain-Class: ai.skillroy.testjava.agent.hooks.TestAgent` (its
  `premain` stores the `Instrumentation` in a static; the Byteman module has its own), and the module's surefire
  configuration **extends** the inherited line, `<argLine>-Djava.awt.headless=${skillroy.headless}
  ${skillroy.test.extraArgLine} -javaagent:${project.build.directory}/${project.build.finalName}-test-agent.jar</argLine>`,
  so that a command-line `skillroy.test.extraArgLine` (the QEMU `launchMechanism=FORK` of the environment notes,
  which replaces a POM property) and the agent both apply; **no module POM sets `skillroy.test.extraArgLine`**.
  Launch-time loading is JEP 451-exempt and works on the `-Djvm` Java 8 test JVM (map 1 §7 Q9). The hooks module's
  surefire further sets `<classpathDependencyExcludes>ai.skillroy:skillroy-agent-spy</classpathDependencyExcludes>`
  (a `Spy` on the test class path would be defined by the application loader before the engine's append: BS3 RESULT
  §9 fact 9's trap in the tests) and passes the reactor's spy jar as the live option
  `-Dskillroy.agent.hooks.spyJar=${ai.skillroy:skillroy-agent-spy:jar}` through `maven-dependency-plugin:properties`,
  so that the engine under test appends the real jar as in production and the tests reach `Spy` only reflectively,
  through the bootstrap loader, after the first install. The in-process `AgentHarness` of the core's tests keeps
  starting agents without an `Instrumentation`; the hooks module's tests start theirs with the test agent's.
- **Test fixtures that a test hooks or names in a rule live outside the protected prefixes** of §3.3 item 2: the
  hooks module's toy classes in `ai.skillroy.testjava.fixture.hooks` (its own `src/test`), `BytemanIT`'s
  `LendingToyMain` in `ai.skillroy.testjava.fixture.byteman` (a new fixture of `skillroy-agent-dist`'s tests, beside
  the `fixture.app` classes, which stay where they are).

### 2.4 Threads and bounds

- The hooks module starts **no thread of its own**. Probes, installs, removals and health run on the agent's worker
  pool under the dispatcher's timeout; advice runs on the application's thread that called the hooked method and
  does nothing but call `Spy`, whose handler encodes and publishes (`EventBus.publish` never blocks) within the bounds
  of §4.3. The restore at `uninstall` runs on `skillroy-uninstall-hooks` within `UNINSTALL_TIMEOUT_MS` (10 s): the
  module disarms its handler first, then retransforms the touched classes in batches under its own budget
  `hooks.detachBudgetMs` (default 8000, §3.5), so that a slow retransform leaves inert advice behind, never a stuck
  uninstall.
- `hook.install` has a default timeout of 60000 ms (`_meta.timeoutMs` caps at 120000 as for every primitive).
  Measured (BS3 RESULT §2, §9 fact 16): 111-197 ms for the first hook of a JVM (Byte Buddy loading and warming),
  6-23 ms for the next, 103-134 ms from a fresh engine after a detach, under 60 ms per class up to the size limit of
  §3.7; `hook.remove` 30000 ms (2-10 ms measured, verification included); `hook.probe`, `hook.health`, `hook.events`,
  `hook.stub` 10000 ms. Every retransformation is a stop-the-world `RedefineClasses` safepoint: a median 4.4-5.0 ms
  and at most 9.3 ms on Java 25, growing with the number of loaded classes (8-12 ms at 64,000-76,000), and 4-51 ms
  on Java 8, growing with the class (11 ms at 273 KiB, 51 ms at 1.8 MiB) (fact 17). Hence three rules of the engine:
  the hooks of one class are installed and removed together, in batches whose class files sum to at most
  `hooks.maxClassBytes` and never more than 8 classes (§3.2); classes are discovered explicitly, never by Byte Buddy's
  scan of every loaded class (7-22 ms against 22-49 ms on Java 25 and up to 615 ms on Java 8 at 63,000 classes,
  fact 19); a class above the limit is never retransformed (fact 18). While the transformer is registered every class
  the host loads costs 3-8 µs more, which the engine's pre-filter bounds (fact 20, §3.2); a hooked call costs 9-17 ns
  with no handler or the hook off, about 70-140 ns in `OBSERVE` and `CAPTURE`, 70-115 ns in `STUB` (fact 21).
- Byteman is loaded in process only under `rule.submit` (§7.4), on the agent worker that serves it, under its
  30000 ms default; a rule's trigger runs on the application's thread and the curated helper publishes without
  blocking (§7.5).
- The telemetry exporter runs the SDK's `BatchSpanProcessor` thread (named through its own thread factory
  `skillroy-telemetry`), the only thread Phase 6 adds to the controller, and `shutdown()` with a 10 s bound flushes it
  at the end of a CLI invocation or a JUnit stream; an unreachable endpoint is reported once to stderr (the module
  owns the `io.opentelemetry` JUL configuration, §10.4) and never fails or slows a run beyond the exporter's own
  timeout (`timeoutMs`, default 10000).

### 2.5 What a target JVM sees

- Without `optionalModules`: what it saw after Phase 5, plus nothing. The spy jar is a file in the home that no
  loader opens; `optional/` is never scanned.
- With `optionalModules=skillroy-agent-hooks`: the hooks module in its own module loader, with the relocated Byte
  Buddy and ASM inside that loader (no `net.bytebuddy` or `org.objectweb.asm` class exists in the JVM, so a host's
  own copies cannot clash, gap 33: BS3's host with Byte Buddy 1.12.19, ASM 5.0.3 and JNA 4.5.2 on its system class
  path ran the relocated engine on its own copy, the host's libraries kept working, no `LinkageError`,
  `IllegalAccessError` or `NoClassDefFoundError`, BS3 RESULT §9 fact 10); and, from the first `hook.install` on, `Spy`
  on the bootstrap search path, permanent for the JVM's life (attach §C4). After install and after remove the host's
  shared loaders hold **nothing of the agent's but `Spy`, `Spy$Handler` and `Spy$Stub` in the bootstrap loader**,
  provided the engine takes the `Nexus` measure of §3.2 (BS3 RESULT §6, §9 facts 2 and 3: without it the system
  loader gains the relocated `Nexus` and, on Java 9 and later, the bootstrap loader gains
  `java.lang.ClassLoader$ByteBuddyAccessor$V1` through `sun.misc.Unsafe`, with a four-line warning on the host's
  stderr on Java 25); Byte Buddy's generated classes live in loaders of its own. The engine adds about 5-7 MB of
  metaspace and 1,100-1,400 classes to the host (fact 24). On Java 25 with class data sharing on (the default) the
  one bootstrap append prints one line on the host's stderr, `OpenJDK 64-Bit Server VM warning: Sharing is only
  supported for boot loader classes because bootstrap classpath has been appended`, after which the JVM shares boot
  classes only (fact 8; none on Java 8, none with `-Xshare:off`, none for a system class path append); the engine
  appends at most once per JVM (§3.2) and the line is a §17 question (Q14).
- With `optionalModules=skillroy-agent-byteman` and a configured Byteman jar, from the first confirmed `rule.submit`
  on: that jar and `skillroy-agent-byteman-sys.jar` on the **system** class path for the JVM's life (B8, design §2.1;
  §17 Q3).
- Three new ITs guard it. `DistributionContentIT` keeps its exact file list (eleven entries after Phase 6, thirteen
  with the two Byteman jars: `skillroy-agent-spy.jar`, `optional/skillroy-agent-hooks.jar`,
  `optional/skillroy-agent-byteman.jar` and `optional/skillroy-agent-byteman-sys.jar` added to today's nine) and its
  rule that every jar entry is under `ai/skillroy/` or `META-INF/`, which the relocated jar satisfies as it is (its
  `META-INF/third-party/` entries included); the comparison of `META-INF/LICENSE` and `NOTICE` with the root files
  holds because the dependency's own copies are filtered out in `skillroy-agent-bytebuddy` (§2.1); the test gains two
  rules, **no jar but `skillroy-agent-spy.jar` holds an entry under `ai/skillroy/testjava/agent/spy/`** and
  `optional/skillroy-agent-hooks.jar` holds no entry under `net/`; its class comment and the root `NOTICE` are
  reworded (§17 Q1). The new **`HomeConstantPoolIT`** (D152) opens every class of every jar of the assembled home and
  fails on a `CONSTANT_Class` reference outside `java/`, `javax/`, `sun/`, `com/sun/`, `jdk/` and `ai/skillroy/`, and
  on any reference to `net/bytebuddy/`, `org/objectweb/asm/` or `com/sun/jna/` anywhere (the review's scan of BS3's
  relocated jar found none, so the rule can be met): the mechanical form of the audit's manual step
  (`../licence-audit.md` §3) and the proof that the relocation missed no class; the sys jar's Byteman types appear in
  method descriptors only (§2.1), which the scan does not read, and the scan says so in its comment. The new
  **`OptionalModulesIT`** and **`SharedLoadersIT`** (§13.1 item 13) prove the default path loads none of it and that
  only `Spy` reaches the shared loaders.

### 2.6 What the CLI distribution carries

The CLI zip gains **`bin/skillroy.bat`** (§9.1; the shell launcher's twin, CRLF, mode 0644), the project's own
**`lib/skillroy-telemetry-<version>.jar`** (`DistributionNoticesIT.PROJECT_MODULES` gains `telemetry`) and the
OpenTelemetry set of §2.2 in `lib/`, each third-party jar attributed in `THIRD-PARTY.txt` in the file's format
(Apache-2.0, so `DistributionNoticesIT`'s allow-list holds) and its artifact id added to
`DistributionNoticesIT.THIRD_PARTY`; the test's set of files outside `lib/` gains `bin/skillroy.bat`. Nothing else:
`skillroy-cdp` stays out (D49), Byteman is in no distribution (B8), the agent home stays a separate artifact.

---

## 3. `skillroy-agent-spy` and `skillroy-agent-hooks`: the hook engine

### 3.1 `Spy` (`ai.skillroy.testjava.agent.spy.Spy`)

The only class the hooks put in front of the host. Advice bodies inlined into a hooked method call nothing but its
two static methods, so every type an advice references is visible from the instrumented class's loader through the
bootstrap loader (attach §F15, §R1). It references `java.*` only, never throws, never blocks, and is a no-op while no
handler is installed.

```java
public final class Spy {
    /** Bumped when the shape of this class changes. A bootstrap append is permanent (attach §C4): a JVM keeps the
     *  first Spy it was given, and a hooks module whose Spy has another VERSION reports hook.engine UNAVAILABLE. */
    public static final int VERSION = 1;

    /** The hook engine's side; implementations live in the hooks module's isolated loader. */
    public interface Handler {
        /** @return null to run the original body, or a Stub (from Spy.stub) to skip it and return the stub's value */
        Object enter(String hookId, Object self, Object[] args);
        /** After the original body ran (never after a skip), with its return value or the throwable it threw. */
        void exit(String hookId, Object self, Object[] args, Object returned, Throwable thrown);
    }
    /** A handler-supplied return value, carried from the enter advice to the exit advice in a local variable. */
    public static final class Stub { public Object value(); }
    public static Stub stub(Object value);

    /** Installs the handler if the slot is free; false while another live handler holds it (D117). */
    public static boolean install(Handler handler);
    /** Clears the slot if, and only if, {@code handler} is the installed one (identity); false otherwise. */
    public static boolean uninstall(Handler handler);
    /** True while a handler holds the slot. The handler object itself is never handed out. */
    public static boolean held();

    /** Called by the enter advice: null, or the Stub the handler returned. Counted; never throws. */
    public static Object enter(String hookId, Object self, Object[] args);
    /** Called by the exit advice. Counted; never throws. */
    public static void exit(String hookId, Object self, Object[] args, Object returned, Throwable thrown);
    /** {enters, unhandled, failures, skips} since the class was loaded. */
    public static long[] counters();
}
```

- **The slot (D117).** One `volatile WeakReference<Handler>`: set-once, so that no code in the JVM can redirect the
  hooks' events by writing the field (the bootstrap path is callable by every class, map 5 §8 Q7); released only by
  the installer (identity); held weakly as a last resort, so that a handler whose module loader vanished without any
  uninstall frees the slot once collected. **The invariant the engine keeps: while any class carries an advice, the
  slot is held by the engine's handler** (§3.5): whoever holds the slot receives the arguments of every hooked method
  and may return a `Stub` for any of them, whatever the hook's mode, so a free slot beside a live advice would be a
  tap any code in the JVM could claim. `install` while a live handler holds the slot returns false, and the engine
  then reports `hook.engine` `UNAVAILABLE` with the reason of §3.2. Reconfiguring (a stub armed or cleared, a hook
  added to the catalogue) changes the engine's own registry behind the one handler and **needs no retransformation
  and no new handler** (design §2.1, `skillroy-agent-hooks`: "runtime registry behind Spy").
- **Fast path.** `enter` and `exit` read the slot, return at once when it is empty (counted in `unhandled`), call the
  handler inside `try { } catch (Throwable)` (counted in `failures`, never rethrown), and `enter` returns the handler's
  result only when it is a `Stub` (counted in `skips`). A `StackOverflowError` or `OutOfMemoryError` thrown by the
  handler is swallowed like any other throwable: the host must never see one from the agent (`CLAUDE.md`).
- **`enter` and `exit` are callable by every class in the JVM**, and the engine does not try to tell an advice's call
  from a direct one (a stack walk would cost more than the hook): in-JVM code can forge a hook event, which the threat
  model records (`threat-model.md` §3) and which changes nothing an implementer builds.
- **Re-entrancy** is the handler's business (§3.2): `Spy` itself keeps no thread-local.
- The jar carries the Apache header and the project's `META-INF/LICENSE` and `NOTICE` like every project jar, one
  class file plus the two nested types, and no service file.

### 3.2 `HooksModule`: install, readiness, the first `hook.install`, the transformer

- **`install(ctx)`** (agent start; the module is loaded only when `optionalModules` names it, §2.1): registers the
  six primitives of §4.2, the `HookEngine` SPI (§3.6) through `ctx.spi().register(HookEngine.class, engine)`, and
  the capability `hook.engine`. When `ctx.instrumentation()` is null the capability is **set** at once to
  `UNAVAILABLE("no Instrumentation in <mode> mode")` (the core's own wording for `jvm.instrumentation`), otherwise it
  is **declared** with the probe below, so that it reads `UNAVAILABLE("not probed yet: the target is not ready")`
  until the target is ready and the four primitives that name it answer `NOT_READY` through the capability gate (P1
  §5 "Dispatch", the pending case). `install` touches `Instrumentation` for nothing but the flag read
  `isRetransformClassesSupported()`; no append, no transformer. A `hooks.*` option that is out of its range or not a
  number fails the module's install as a `FAILED` record naming the key (S14: never fatal).
- **The `hook.engine` probe** (after readiness, and on `agent.capabilities{reprobe}`), in order: `Instrumentation`
  present; `isRetransformClassesSupported()` true (attach §F17: `Can-Retransform-Classes` is in the bootstrap
  manifest; else `UNAVAILABLE("retransformation is not supported by this JVM")`); **while no `Spy` is on the
  bootstrap path** (`Class.forName("ai.skillroy.testjava.agent.spy.Spy", false, null)` fails), the spy jar exists at
  `hooks.spyJar` (§3.7; else `UNAVAILABLE("spy jar not found: <path>")`), a check that is skipped once `Spy` is on the
  path, since nothing needs the file any more; **when a `Spy` is on the path** (a second agent instance in the same
  JVM, after `agent.disable` and a re-attach), its `VERSION` equals the engine's expectation (else
  `UNAVAILABLE("Spy version <n> is already on the bootstrap path; this module needs <m>")`) and its slot is **free or
  held by this engine's own handler** (`Spy.held()` false, or the engine installed the handler that holds it; else
  `UNAVAILABLE("Spy slot held by a disarmed handler of an earlier agent; <n> classes still carry advice")`, the case
  of §3.5's detach that ran out of budget, which lasts until the JVM exits); the result is `OK(strategy "advice")`.
  The probe never appends: a second engine after a detach finds the earlier `Spy` on the bootstrap path (the same
  `Class` object), works with it, and its dropped predecessor's loader is collectable when the predecessor restored
  everything (§3.5); a repeated append is accepted but adds a path entry and, on Java 25, a second CDS line, so the
  engine appends only when the bootstrap loader does not know `Spy` (BS3 RESULT §6, §9 fact 22). The probe also lists
  the other bytecode agents it can see in its answer's `otherAgents`, which `hook.install` repeats and the controller
  records (§5.3; D154): for each `-javaagent:` entry of `RuntimeMXBean.getInputArguments()` other than the agent's
  own, **the jar's file name only** (the text before the first `=`, its directory stripped: an agent's option string
  may carry a licence key, and `jvm.info` already redacts input arguments), and the marker `byteman` when
  `org.jboss.byteman.agent.loaded` is `true`. The engine cannot see what such an agent changed or registered before
  the first retransformation (facts 13 and 14), so the record is the one place that says a later comparison may be
  moot.
- **`onTargetReady`**: nothing (the engine installs nothing on its own: "zero hooks on the default path", design
  §2.6). Hooks come from the controller through `hook.install` (§5.3) or from a module through the SPI (§3.6).
- **The first `hook.install` of an agent instance**, on an agent worker, in this order: (1) the probe's checks again,
  so that a stale matrix cannot pass a broken state; (2) §3.3's items 1 to 5 and 7 for every spec; when none passes,
  the call answers the states and **stops without appending** (nothing permanent happens for a catalogue that fits
  nothing); (3) **append**, if no `Spy` is on the bootstrap path: the engine opens the jar at `hooks.spyJar` and
  refuses it (`UNAVAILABLE("spy jar holds more than Spy: <entry>")`) when it holds any entry but
  `ai/skillroy/testjava/agent/spy/Spy.class`, its two nested classes, the directory entries and
  `META-INF/{MANIFEST.MF,LICENSE,NOTICE}` (a bootstrap append is the most privileged act of the agent: whatever else
  the jar held would be bootstrap-defined code for the JVM's life); and refuses it as well when any class entry is not a `--release 8` class file (major 52) or references, in its
  constant pool, a class outside `java/` (`ClassFileRefs` of §3.6: the jar's code must reach nothing but the JDK);
  there is **no hash comparison** (the reactor has no checksum step, and the jar is the operator's, `threat-model.md`
  §3); then `inst.appendToBootstrapClassLoaderSearch(new JarFile(spyJar))`, `Class.forName(…, true, null)` and
  the version check; the append is logged at INFO with the jar's path and size, once per JVM (on Java 25 it costs the
  one CDS line of §2.5, BS3 RESULT §9 fact 8, which is why it is never repeated for a `Spy` the bootstrap loader
  already knows); (4) `Spy.install(handler)` with the engine's one handler (D117), whose class is **loaded only now**:
  it implements `Spy.Handler`, and a class linked against a `Spy` type before the append keeps failing with
  `NoClassDefFoundError` after it (BS3 RESULT §5, §9 fact 9), so nothing in `HooksModule`, the primitives or the SPI
  implementation names a `Spy` type in a signature or a field, and the handler is instantiated by reflection after
  step 3; (5) §3.3's item 6, the visibility check by identity, for every spec that passed step 2; (6) the transformer
  (below), the `Nexus` measure first, and the retransformation of the specs that passed. Steps 3 and 4 run once per
  agent instance; a later `hook.install` starts at step 2 and skips to 5.
- **The transformer.** One `AgentBuilder` of the relocated Byte Buddy per engine, configured
  `disableClassFormatChanges()` (attach §F17, §C3: it selects `Implementation.Context.Disabled` and
  `TypeStrategy.Default.REDEFINE_FROZEN`, so no member or type initializer is ever added to a hooked class and
  retransformation stays possible; **no later `with(TypeStrategy…)` call**, which would replace the frozen strategy),
  `with(RedefinitionStrategy.DISABLED)` on the builder itself, because the engine retransforms **explicitly** the
  classes its ledger names (it calls `Instrumentation.retransformClasses` itself, the classes of one install or
  remove together in **batches whose class files sum to at most `hooks.maxClassBytes` and never more than 8
  classes**, each batch logged with its classes and its measured duration, so that one safepoint stays near the
  per-class bound BS3 measured, every retransform is attributable in the log and the ledger, and a class gets one
  stop-the-world pause each way; Byte Buddy's own discovery would match every loaded class at install: 22-49 ms on
  Java 25 and up to 615 ms on Java 8 at 63,000 classes against 7-22 ms for the explicit list, BS3 RESULT §9 fact 19),
  `with(InitializationStrategy.NoOp.INSTANCE)`, a `Listener` that records every transformed class and every error
  into the engine's log, and the engine's own type matcher in place of `ignore(none())`: **only the classes the
  installed specs name**, by exact binary name and, when a spec says so, by loader (§4.1 `target.loader`). **The
  `Nexus` measure** (a condition of BS3, RESULT §6, §9 fact 2): `new AgentBuilder.Default()` constructs its default
  `InitializationStrategy.SelfInjection.Split`, whose `NexusAccessor` injects `Nexus` into the system loader when it
  initialises, before `disableClassFormatChanges()` or `with(NoOp)` can matter, and on Java 9 and later defines
  `java.lang.ClassLoader$ByteBuddyAccessor$V1` in the bootstrap loader through `sun.misc.Unsafe` (a four-line warning
  on the host's stderr on Java 25); the engine therefore sets the relocated system property
  `ai.skillroy.testjava.agent.hooks.shaded.bytebuddy.nexus.disabled` to `true`, forces the initialisation of the
  relocated `NexusAccessor`, and clears the property, **before** the first `AgentBuilder` is constructed (the
  property name is relocated with the code, so a host's own Byte Buddy is untouched). The engine does **not** call
  `installOn(inst)`: it takes the transformer with `makeRaw()` and registers it itself as
  `inst.addTransformer(hookTransformer, true)` behind a thin pre-filter of its own (a `ClassFileTransformer` that
  answers null at once for a class name the ledger does not hold, because a registered transformer costs the host
  3-8 µs on every class it loads, fact 20), so that the engine owns the registration order (the recorder of §3.5
  goes ahead of it) and the pre-filter bounds the cost. The transformer is registered once, after every transformer
  that was there before it (attach §Risk5: transformers chain in registration order), stays registered for the life
  of the hooks (any retransformation, another agent's included, rebuilds the class from the initial bytes through the
  registered transformers, fact 20), and is **re-installed never**: a changed spec set changes the matcher and the
  advice table behind it, then the engine retransforms the affected classes (added classes once; classes whose hooks
  were removed once, to drop their advice). What BS3 proved of this (RESULT §9 fact 1): the advice shape of §3.4,
  `disableClassFormatChanges()` and `InitializationStrategy.NoOp`, through `installOn` with `RETRANSFORMATION` and an
  explicit discovery list, on Java 8 and Java 25, `JAVAAGENT` and `ATTACH`, in the system loader and in custom
  loaders, with events on the host's thread carrying arguments, return value and throwable; taking the transformer
  with `makeRaw()` and registering it behind the pre-filter is Phase 6's change (D155), proved by the hooks module's
  tests (§13.1 item 3) and measured for its class-loading effect as gap 54.
- **The ledger.** Per touched class: its binary name, its loader (weakly), the `classfileBuffer` the recorder saw at
  the **first** transform (the bytes the restore is compared with, §3.5), the descriptors hooked, the retransform
  count, the recorder's event count and the last error. The ledger is what `hook.health` and the restore read; it
  holds at most `hooks.maxClasses` entries (§3.7). The engine's per-thread state (the re-entrancy depth, the
  `OBSERVE` start time) is one `ThreadLocal<long[]>` whose values are bootstrap types only, dropped at `uninstall`;
  **a module class is never a thread-local value**, because an application thread's `ThreadLocalMap` would then pin
  the module's loader, and the dropped predecessor's loader of BS3 RESULT §9 fact 22 would never be collectable with
  application threads alive.
- **Re-entrancy.** An `enter` while the same thread is already inside the handler (the handler's own encoding called
  a hooked method, for example an overridden `toString`) returns null at once and counts in `Spy`'s `enters` only.

### 3.3 Probing a spec: `hook.probe` and the install-time check

For each `HookSpec` (§4.1), in this order, the first failure decides the state; every item's finding is in the
answer so that a reviewer sees why a hook is unavailable. `inst.getAllLoadedClasses()` is read **once per
`hook.probe` or `hook.install` call** and indexed by name for every spec of the call (it allocates an array of every
loaded class).

1. **Name rules.** `id` matches `[a-z][a-zA-Z0-9]*(\.[a-z][a-zA-Z0-9]*)*` and is at most 64 characters (it becomes a
   capability id suffix and a topic suffix); `target.class` is a binary name; `target.method` an identifier or
   `<init>` (constructors are refused in Phase 6: `UNAVAILABLE("constructors are not hookable")`); each descriptor is
   a JVM method descriptor. A violation is `INVALID_PARAMS`, not a state.
2. **Deny-list (D124), by name and by loader.** By name: a class under `java.`, `javax.`, `sun.`, `com.sun.`, `jdk.`,
   `org.jboss.byteman.`, `ai.skillroy.testjava.agent.` or `ai.skillroy.testjava.protocol.` is `UNAVAILABLE("class in a
   protected package")`. By loader: a class defined by the bootstrap loader, by the platform loader (Java 9 and
   later, `ClassLoader.getPlatformClassLoader()` reached reflectively) or the extension loader (Java 8: the system
   loader's parent), or by a loader whose own class is under `ai.skillroy.testjava.agent.` (the agent loader and every
   module loader, which the hooks module cannot name but can recognise: it compares `c.getClassLoader()` with the
   loaders of its own loader chain and tests the loader's class name), is `UNAVAILABLE("class is the agent's own or
   the JDK's")`, whatever its package: a domain agent module's classes are refused by loader. **A target application's
   own packages are hookable**: the sample's `ai.skillroy.testjava.samples.lending` is one of them, in the system
   loader. Test fixtures that a test hooks live outside the protected prefixes (§2.3); the "protected package" case
   of §13.1 item 3 uses a class of the hooks module itself.
3. **Loaded?** The class must already be loaded (the indexed `getAllLoadedClasses()`, filtered by name and by the
   loader the spec names, else every loader that defines the name; several matches without a loader filter are
   hooked all, each a ledger entry, the probe reporting `loaders: n`). Not loaded: `UNAVAILABLE("class not loaded")`
   (`STALE` once it was hooked and its loader is gone, §3.6). **The engine never loads the target class itself**:
   loading changes the host. Reading its methods (items 4 and 5 below) loads, without initialising, the types its
   method signatures name, as Byte Buddy's own description of the class does at install; a missing one makes the
   spec `UNAVAILABLE("a method signature of the class names a missing type: <name>")` (the `NoClassDefFoundError` of
   `getDeclaredMethods` is caught and reported, never thrown).
4. **Modifiable and bounded.** `inst.isModifiableClass(c)` true, else `UNAVAILABLE("class is not modifiable")`; the
   class file (the first transform's bytes when the class is in the ledger, else the loader's resource
   `<name>.class`, which is canonically but not byte-wise the same, BS3 RESULT §9 fact 11) at most
   `hooks.maxClassBytes`, and its declared methods (`getDeclaredMethods().length + getDeclaredConstructors().length`)
   at most `hooks.maxMethods` (§3.7), else `UNAVAILABLE("class too large: <n> bytes > <limit>")` or
   `UNAVAILABLE("class too large: <n> methods > <limit>")`.
5. **Descriptors.** Every descriptor of the spec resolves to a declared method of that class with that name and
   descriptor (reflection on `getDeclaredMethods`, descriptors computed from the `Method`); a native or abstract
   method is refused (`UNAVAILABLE("method is native|abstract")`); a descriptor that matches nothing is
   `UNAVAILABLE("no method <method><descriptor>")` with the descriptors that do exist in `data.declared`, so that a
   catalogue author can fix the pin. The design's rule "an overloaded target pins every descriptor that reaches the
   behaviour" is the author's; the engine hooks exactly the descriptors given.
6. **Spy visibility.** `Class.forName("ai.skillroy.testjava.agent.spy.Spy", false, c.getClassLoader())` returns the
   bootstrap `Spy` (identity with the engine's own view of it), else `UNAVAILABLE("the class's loader cannot see
   Spy")`. The check is decided **after the append** (§3.2 step 5): before any `Spy` is on the bootstrap path no
   loader can see it, so `hook.probe` reports the item as `unchecked` in the spec's findings and refuses only a loader
   that would **shadow** `Spy` (its `getResource` of the class finds a copy of its own). BS3 RESULT §5, §9 fact 6: a
   loader that delegates only `java.*` to its parent cannot see `Spy` in the project's package; installed anyway, an
   advice with `suppress = Throwable.class` costs the host 4-6 µs per call (a `NoClassDefFoundError` created and
   caught inside the method) and fires nothing, and without `suppress` the host's own call fails with
   `NoClassDefFoundError` on every call. The refusal is therefore mandatory, and the reason names the loader. (The
   `java.*` placement that would be visible there is rejected, §2.1, D116; gap 50.)
7. **Mode fit.** `STUB` on a method whose return type is a primitive needs a stub value of that type when armed
   (checked at `hook.stub`, §4.2); a `void` method accepts only `STUB` without a value (skip only). `CAPTURE` on a
   method with no parameters and `void` return, with `capture` naming nothing, is `DEGRADED("nothing to capture")`.

A spec that passes is `OK(strategy "advice")`. `hook.install` runs the same check and refuses nothing for a failing
spec: it installs the ones that pass, reports the others with their state, and the controller applies the catalogue's
fallbacks (§5.3).

### 3.4 The three modes and the advice

One advice pair is inlined per hooked method, chosen by the method's return type: `ValueAdvice` (methods with a
return value) and `VoidAdvice` (methods returning `void`), both `final` classes of the engine with
`@Advice.OnMethodEnter` and `@Advice.OnMethodExit` methods whose bodies are the two lines the design asks for:

```java
@Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
static Object enter(@HookId String id, @Advice.This(optional = true) Object self, @Advice.AllArguments Object[] args) {
    return Spy.enter(id, self, args);
}
@Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
static void exit(@HookId String id, @Advice.This(optional = true) Object self, @Advice.AllArguments Object[] args,
                 @Advice.Enter Object stub, @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned,
                 @Advice.Thrown Throwable thrown) {
    if (stub != null) { returned = ((Spy.Stub) stub).value(); } else { Spy.exit(id, self, args, returned, thrown); }
}
```

(`@HookId` is the engine's own `Advice.OffsetMapping` that binds the hook id as a string constant per hooked method;
`VoidAdvice` has no `returned`; both methods carry `suppress = Throwable.class`, so that a throwable out of the advice
itself, a `NoClassDefFoundError` included, never reaches the host.) The same shape serves all three modes, because
the **mode lives in the handler's registry, not in the bytecode**: `OBSERVE` and `CAPTURE` handlers return null from
`enter`; a `STUB` handler returns a `Stub` only while a value is armed (§4.2 `hook.stub`). Changing the mode of an
installed hook, arming or clearing a stub, therefore needs no retransformation: BS3 ran the three modes with this
shape on the sample's `AccessControl.check` and on the toy class, a mode change took effect at the next call in about
2 µs and a handler swap behind `Spy` in under 1.3 µs while a counting transformer saw no retransformation (BS3 RESULT
§9 fact 4; attach §R7, §F23). `STUB` skipped the original body (a throwing one included) and returned the handler's
value, the same object for a reference type and unboxed for a primitive; **a value of the wrong type made the method
return its type's default (0, null, false) silently** (fact 5), which is why the conversion below is mandatory at
`hook.stub` time and is never left to the advice.

| Mode | At `enter` | At `exit` | Event (§4.3) |
|---|---|---|---|
| `OBSERVE` | the handler notes the start time (`System.nanoTime`) in its thread-local frame; returns null | one event with `durationNs` and, if the body threw, `thrown {class, message}`; **no values** | `hook.<id>` at exit |
| `CAPTURE` | as `OBSERVE` | as `OBSERVE`, plus the encoded copies the spec asks for: `args` (the indexes of `capture.args`), `self` (`capture.self`), `returned` (`capture.result`), each masked and bounded (§4.3) | `hook.<id>` at exit |
| `STUB` | if a stub is armed for the hook: returns `Spy.stub(value)` and the body is skipped; else as `OBSERVE` | when the body was skipped: `returned` is set to the armed value and one event with `stubbed: true` is published by the **handler at `enter`** (the exit advice does not call `Spy.exit` after a skip); else as `OBSERVE` | `hook.<id>` with `stubbed` |

The stub value is converted once, when armed: JSON `null`, booleans, numbers and strings become the method's declared
return type (boxed primitives through `Assigner.Typing.DYNAMIC` unboxing; `BigDecimal` from a string; an enum by
name); any other return type accepts only `null`, and a value that cannot be converted is `INVALID_PARAMS`
(`data.param: value`, `data.returnType`). A `null` for a primitive return type is refused the same way.

### 3.5 Remove, detach and the byte verification

**`hook.remove`** (and the module's `uninstall`, which runs the same steps under the detach budget) for the hooks
named, or every installed hook when none is named:

1. The hooks are taken out of the handler's registry (an advice that fires between this step and the retransform
   finds no entry and does nothing; a call in flight inside a hooked method finishes on the old method version, its
   exit advice runs after the restore as an unhandled no-op, it returns normally, nothing is thrown into the host, and
   the next call runs the original code without a `Spy` call: BS3 RESULT §9 fact 15).
2. **Restore and verification, one retransformation (D122).** The engine's **recorder** is a no-op
   retransformation-capable transformer registered at install **ahead of** the hook transformer, so that it receives
   the bytes of every transform event of a touched class: at the first transform the bytes the JVM reconstitutes (the
   ledger's reference), and afterwards every retransformation, the engine's own and anyone else's. For the restore
   the recorder is re-registered **last**, the hook transformer answers null for the classes being restored (or is
   removed with `removeTransformer` when no hook is left), and the engine retransforms them in the batches of §3.2:
   that one retransformation both restores (attach §F17: it re-runs every retransformation-capable transformer from
   the JVM's initial bytes) and verifies, since the recorder now sees what the JVM installs. BS3 RESULT §3, §9 facts
   11-14: the JVM hands the first transform **reconstituted** bytes (the constant pool as in the file, methods and
   code attributes in the VM's order, on Java 8 without `MethodParameters`), canonically equal to the class file but
   not byte-identical to it, so the comparison is **with the bytes captured at the first transform, never with the
   class file**; after the restore the bytes equal those captured bytes byte for byte on Java 8 and Java 25, and a
   further retransformation gives the same bytes again (fact 12). The equality holds almost by construction (a
   retransformation starts from the bytes cached at the first transform), so the verification reports **two
   things**: the byte comparison and the count of **foreign transform events**, the recorder's events the engine did
   not cause (fact 13).
3. **Outcomes**, per class, in `hook.remove`'s `restored` list: `OK` when the bytes are identical and no foreign
   event was seen (BS3's BC0); `DEGRADED("bytes differ: another transformer is present")` when they differ and hold
   no constant-pool reference to `ai/skillroy/testjava/agent/spy/Spy` (`ClassFileRefs`, §3.6; BC1: another
   retransformation-capable transformer registered after the install and still there, the design's accepted case,
   attach §F14, §Risk5); `DEGRADED("foreign redefinition observed: <n> events")` when foreign events were counted,
   whether or not the bytes compare equal (BC3, BC4: someone redefined the class while it was hooked; on Java 25 the
   bytes then differ as well, on Java 8 (8u504) **the restore reverts the redefinition and the bytes compare equal**,
   so the event count is the only witness, fact 13); **`HOOK_VERIFY_FAILED`** (`-32020`) as the call's error, with
   `data {classes: [{class, loader, bytesBefore, bytesAfter, foreignEvents}]}`, when a reference to `Spy` is left,
   and `hook.health` then reports the hook `ERROR("residue after remove")`. A limit the contract records (fact 14):
   the engine's own install retransformation applies another agent's change that was registered but never applied
   (BC2) and drops one whose transformer was removed before the engine came (BC5); neither the bytes nor the ledger
   can see it, which is what the `otherAgents` record of §3.2 is for.
4. The ledger entries of restored (`OK` or `DEGRADED`) classes are dropped; the capabilities `hook.<id>` of removed
   hooks are withdrawn through their declaration handles (A18); one event `hooks.removed {ids, restored: [class…],
   verified: OK|DEGRADED|FAILED}`. **A `FAILED` class keeps its ledger entry**, its hook stays disarmed (no registry
   entry) and the handler stays installed (the invariant of §3.1).
5. **Only when the ledger is empty**: `removeTransformer(engine's transformer)` and `Spy.uninstall(handler)`; the spy
   class stays on the bootstrap path (a JVM rule, attach §C4), inert.

**Detach** (`agent.disable`, `EmbeddedAgent.stop`, `cmd=stop`, a production-marker match; P1 §5): the core
uninstalls modules in reverse install order, so a domain module that installed after the hooks module restores its
seams first; then `HooksModule.uninstall()` runs steps 1-5 under `hooks.detachBudgetMs` (default 8000 ms, inside the
core's 10 s `UNINSTALL_TIMEOUT_MS`): it **disarms** its handler first (the registry is emptied, so every `enter`
returns null and every `exit` is a no-op, which makes whatever is not restored in time inert) and **keeps it installed
in the slot**, then restores class by class until the budget is spent; `Spy.uninstall(handler)` and `removeTransformer`
run only when the ledger is empty. A class left transformed stays in the ledger with the disarmed handler in place
and the transformer registered: **the registered pre-filter holds the handler in a final field, so while any class
carries an advice the handler stays strongly reachable from the JVM's transformer list and the weak slot cannot
clear** (a pinned loader alone would keep the handler's class, not the instance); the class is logged at WARN by name
with the remedy, and a re-attached agent's probe then reports the slot held by a
disarmed handler of an earlier agent (§3.2) until the JVM exits: a refused re-attach rather than a free slot beside
a live advice (D117). Because the core **closes** the module's loader after `uninstall` (`AgentRuntime`, S32), the
disarmed handler's path (the registry lookup, the null return) and the pre-filter load every class they need at
install; a class they had not loaded would fail inside `Spy`'s catch, counted, never thrown. Nothing is thrown
(`AgentModule.uninstall` "must not throw and must not block for long"). The design's "disable flag file" trigger stays
unbuilt (design §2.2 step 6).

### 3.6 Health, the `HookEngine` SPI and the agent-api additions

**Health states (D123).** Computed on `hook.health`, never by a thread of their own:

| State | Meaning |
|---|---|
| `INSTALLED` | the hook is installed (its classes retransformed) and has not fired since it was installed |
| `FIRING` | at least one call reached the handler since install; `count`, `lastFiredMs` (epoch ms), `last` (the last event's payload) are set |
| `STALE` | a hooked class's loader has been collected (the ledger's weak reference is empty); a spec that failed §3.3 at install is not installed and not declared (§4.6), so it has no health entry |
| `ERROR` | the advice's handler threw for this hook (`Spy.counters()[2]` rose and the engine attributed it), a `MaskPolicy` threw while encoding its capture (§4.3), or a retransform or a restore of its class failed (`reason` names the step and the exception's class and message, redacted) |

"Armed" means one thing only: a `STUB` hook holds a value (`HookHealth.armed`, §4.1).

**The SPI** (`ai.skillroy.testjava.agent.api.spi.hook.HookEngine`, D118), for a domain module that wants hooks from
Java rather than from a controller's catalogue; found with `ctx.spi().first(HookEngine.class)`, which is null when
the hooks module is not loaded, or through the default method `AgentContext.hooks()`:

```java
public interface HookEngine {
    HookProbe probe(HookSpec spec);                         // the checks of 3.3; never installs
    List<HookStatus> install(List<HookSpec> specs, String catalogueId, String catalogueHash, boolean replace);
    boolean stub(String id, Object value, boolean clear);   // arms or clears; refused for a hook not in STUB mode;
                                                            // publishes hooks.stubbed {id, armed, by: "spi"} (4.3)
    List<HookStatus> remove(List<String> ids);              // empty list = every hook; the restore of 3.5
    Map<String, HookHealth> health(List<String> ids);
    Map<String, Object> catalogue();                        // {id, hash, installedAt} of the installed catalogue, or empty
}
// AgentContext gains three default methods, so that the four test fakes (and the core's two contexts) that
// implement it compile unchanged:
default HookEngine hooks() { return spi().first(HookEngine.class); }
default boolean isSecretKey(String key) { return SecretKeys.looksSecret(key); }   // the core overrides: its Redactor's
default String redactText(String text) { return text; }                          // rule, the redact option, every
                                                                                  // registered RedactionKeys
```

`isSecretKey` and `redactText` are implemented by the core's `CoreContext` and `ModuleContext` over the runtime's
`Redactor` (the protocol's `SecretKeys` rule, the operator's `redact` option, every registered `RedactionKeys`, the
`key=value` and URL user-info rules of S21), which is package-private and otherwise unreachable from a module's
loader; the defaults serve test fakes only. A domain module looks the engine up in `onTargetReady` (after every
module's `install`), never in `requires()`: an unknown module id there fails the module, and the framework must stay
complete without the hooks module (design §2.6; map 1 §7 Q13). The SPI's install from a module is **not** gated by
the dispatcher (no read-only or confirm check): a module is reviewed code (`threat-model.md` §3), and a stub it arms
is its own behaviour; the engine still refuses `stub` while `ctx.readOnly()` is true, so that read-only mode disables
`STUB` hooks whoever asks (design §2.2 step 7), and records every stub it arms as `hook.stub` does (§4.2).

**`…agent.api.instrument` (D153)**, Java 8, no dependency, shared by the hooks and the Byteman modules:
`ClassFileRefs.classReferences(byte[] classFile)` (the binary names of the `CONSTANT_Class` entries of a class file,
a sixty-line constant-pool reader that refuses a malformed file with `IllegalArgumentException`),
`ByteVerification` (`IDENTICAL`, `FOREIGN_CHANGE`, `RESIDUE`, with `compare(byte[] before, byte[] after,
String residueClassPrefix)`), and `RetransformBatches.of(List<Class<?>>, long maxBytes, int maxClasses)`. The package
is API of the agent only in the sense that modules may use it; the controller never sees it.

### 3.7 Options

Agent options (P1 §2.4's table gains these rows; unknown keys were already kept, so no core change is needed for
the `hooks.*` keys; a `hooks.*` value that is out of range or not a number fails the module's install as a `FAILED`
record naming the key, §3.2):

| Key | Default | Meaning |
|---|---|---|
| `optionalModules` | – | file stems of jars under `<home>/optional/` to load as module jars, comma-separated (D114, §2.1). Read by the core |
| `hooks.spyJar` | `<home>/skillroy-agent-spy.jar` | the jar appended to the bootstrap search path at the first install, after the content check of §3.2 step 3 |
| `hooks.maxClassBytes` | `262144` | a class whose class file (as the JVM hands it to the transformer) is larger is never retransformed (design §2.6: "very large classes are never retransformed; BS3 sets the size limit"). BS3 RESULT §4, §9 fact 18: at 273 KiB and 3,000 methods the install took 53-59 ms on Java 8 and 31-37 ms on Java 25 and stopped the world for about 11 ms on Java 8, below one 60 Hz frame; at 916 KiB the Java 8 stop is 26 ms, at 1.8 MiB 51 ms. For scale, the desk's largest class is 35 KB. Also the byte bound of one batch (§3.2). At most 16 MiB |
| `hooks.maxMethods` | `2000` | the second bound of the same limit: Byte Buddy's cost follows the method count rather than the bytes (1.8 MiB of code in 30 methods: 82-88 ms on Java 8; in 20,000 methods: 346-410 ms; fact 18); a class declaring more methods is never retransformed. At most 65535 |
| `hooks.maxClasses` | `64` | the most classes one engine may hold in its ledger; the 65th spec is `UNAVAILABLE("ledger full")`; also the bound of the classes one Byteman rule may retransform (§7.3) |
| `hooks.detachBudgetMs` | `8000` | the restore budget of `uninstall` (§3.5); 1000 to 9000 so that it fits the core's 10 s |
| `hooks.capture.maxBytes` | `16384` | the budget of one `CAPTURE` event's encoding, counted on the input (§4.3) |
| `byteman.jar` | – | the user-supplied Byteman jar, an absolute path (§7.4); **no default and no environment fallback**: without the option `rule.engine` is `UNAVAILABLE("no byteman.jar configured")` |

---

## 4. The `hook.*` primitives, capabilities, events and errors

### 4.1 Wire types (`ai.skillroy.testjava.protocol.hook`, shared by both sides, never duplicated)

```java
public enum HookMode { OBSERVE, CAPTURE, STUB }
public enum HookState { INSTALLED, FIRING, STALE, ERROR }
/** One hook as the agent receives it: JSON {id, mode, target{class, method, descriptors[], loader?}, emits?,
 *  capture?{args[], self, result}, secretArgs?[], secretResult?, note?}. toJson/fromJson as every protocol type;
 *  fromJson refuses an unknown key, a missing id, mode, class, method or descriptors, an empty descriptor list, a
 *  secretArgs index that is negative, and a capture index that is negative. */
public final class HookSpec { … String id(); HookMode mode(); HookTarget target(); String emits(); HookCapture capture();
                               List<Integer> secretArgs(); boolean secretResult(); String note(); }
public final class HookTarget { String className(); String method(); List<String> descriptors(); String loader(); }
   // loader: null (every loader that defines the class), "system", "host" (the agent's host loader), or the
   // hexadecimal identity the agent reported for a loader in an earlier probe answer
public final class HookCapture { List<Integer> args(); boolean self(); boolean result(); }
/** A probe answer: {id, state: OK|DEGRADED|UNAVAILABLE, reason?, strategy?, loaders?, declared?[], sizeBytes?,
 *  methods?, spyVisibility: VISIBLE|NOT_VISIBLE|UNCHECKED}. */
public final class HookProbe { … }
/** An install or remove answer per hook: {id, state, reason?, strategy?, capability}. */
public final class HookStatus { … }
/** {state: HookState, count, lastFiredMs?, armed (a STUB hook holds a value; never the value), reason?, strategy?,
 *  last?: payload} */
public final class HookHealth { … }
```

`HookSpec.hash()` is the SHA-256 (hex, prefixed `sha256:`) of its canonical JSON (`Json.writeCanonical`); the
catalogue hash of §5.1 is the hash over the catalogue id, its version and every entry's spec hash with its `enabled`
flag.

### 4.2 Primitives (module `hooks`)

`hook.probe`, `hook.install`, `hook.stub` and `hook.events` name the capability `hook.engine`, so each answers
`NOT_READY` until the production gate has passed and the probe ran, and `CAPABILITY_UNAVAILABLE` without
`Instrumentation`. **`hook.remove` and `hook.health` name no capability**: they are served whenever the module is
installed and an `Instrumentation` exists, so that a restore and a diagnosis are always reachable, whatever a
reprobe says; without an `Instrumentation` (`EMBEDDED`) both answer `CAPABILITY_UNAVAILABLE` with `hook.engine`'s
reason, from the primitive itself (the dispatcher's gate never answers for them).

| Primitive | Params | Result | Flags | Timeout | Thread |
|---|---|---|---|---|---|
| `hook.probe` | `{specs: [HookSpec]}` (at most 64) | `{hooks: [HookProbe], spy: {onBootPath, version, slotFree, slotHeldByThisEngine}, otherAgents: [file name or marker…]}` (§3.2, D154) | read-only | 10000 | `WORKER` |
| `hook.install` | `{specs: [HookSpec], catalogue: {id, version?, hash, application?}, replace?: false}` | `{catalogue: {id, hash, installedAt, alreadyInstalled}, hooks: [HookStatus], touched: [{class, loader, bytes, methods, ms}], otherAgents: […]}` | **not mutating** (D119: an `OBSERVE` or `CAPTURE` hook changes bytecode, not behaviour, and the design's rule is that read-only mode disables `STUB` hooks); idempotent by catalogue hash: the same `catalogue.hash` and the same spec set answer `alreadyInstalled: true` and the current states, installing nothing; a different hash or id while a catalogue is installed, **or the same hash with a different spec set** (the controller's hash is then wrong: `data.reason: "spec set differs under the same hash"`), is `HOOK_CONFLICT` unless `replace: true`, which runs `hook.remove` of everything first | 60000 | `WORKER` |
| `hook.stub` | `{id, value?, clear?: false}` | `{id, armed: bool, returnType}` | **mutating and destructive** (`_meta.confirm=true` required: a stub changes the target's behaviour for every user of the JVM, which is what `destructive` means in this agent; D119); refused with `INVALID_PARAMS` for a hook whose mode is not `STUB` or that is not installed; `READ_ONLY` in read-only mode by the dispatcher's gate; publishes `hooks.stubbed {id, armed, by: "call"}` (§4.3) | 10000 | `WORKER` |
| `hook.remove` | `{ids?: [id]}` (absent or empty: every hook) | `{hooks: [HookStatus], restored: [{class, loader, verified: OK|DEGRADED|FAILED, reason?, foreignEvents}]}`; the error `HOOK_VERIFY_FAILED` when any class reports `FAILED` (the restore of the others still happened) | **not mutating, never gated by read-only or confirm**: it restores; no capability | 30000 | `WORKER` |
| `hook.health` | `{ids?: [id]}` | `{hooks: {id: HookHealth}, stubs: [id…] (the STUB hooks that hold a value), spy: {version, counters: {enters, unhandled, failures, skips}}, catalogue?: {id, hash, installedAt}}` | read-only; no capability | 10000 | `WORKER` |
| `hook.events` | `{since: seq, id?, limit?: 100}` | `{events: [Event]}`: the bus's events whose topic is the hook's `emits` (or every `hook.*` topic when `id` is absent) with `seq > since`, oldest first, at most `limit` (cap 1000) | read-only; a thin filter over `EventBus.since` (D126) | 10000 | `WORKER` |

Parameter validation is `INVALID_PARAMS` with `data.param`. `GET /schema` documents the flags, so the controller's
`confirm` plumbing (D89) applies to `hook.stub` for free. **No `hook.*` or `rule.*` primitive takes its arguments
from an MCP client or a model** (P5 §9.3's "never offered: hook installation", kept and widened; `threat-model.md`
§5): the MCP server and the session service offer **`hook.health`, `hook.events` and `rule.list` as reads** and never
`hook.probe`, `hook.install`, `hook.remove`, `hook.stub`, `rule.check`, `rule.submit` or `rule.unload`, whatever the
policy; `ToolProjection` names the seven in an explicit never-offered set (not by namespace or by flag, since
`hook.install` and `hook.remove` are not mutating) and `PlanPolicy` (D96) refuses every `hook.*` and `rule.*`
primitive but the three reads in a client's plan. A `HookSpec` is a pack author's or a person's; the pack's catalogue
is installed by the server itself at its start under `--hooks` (§6.1), the person's choice. Why: a client's own
`CAPTURE` on a password-taking method would be a credential disclosure to a model (High on §12.4's scale), and a
client's `hook.remove` would drop a person's evidence mid-run.

### 4.3 Events, the value encoding and the masking

- **Topic**: the spec's `emits`, default `hook.<id>`; it must start with `hook.` (`INVALID_PARAMS` otherwise), so
  that the SSE filter `hook.*` of design §2.3 and the `hooks` fact root (§6.3) see every hook. **Source**: `hooks`.
- **Payload** (`OBSERVE`): `{hook, class, method, descriptor, thread, durationNs, stubbed: false, thrown?: {class,
  message}}`; `message` is `ctx.redactText(throwable.getMessage())` (§3.6: the core's rule, the `redact` option and
  every `RedactionKeys` pattern) cut at 500 characters. `CAPTURE` adds `args: {"<i>": value}` for the indexes asked,
  `self`, `returned`. `STUB` with the body skipped: `{…, durationNs: 0, stubbed: true}`.
- **Correlation**: a hook fires on an application thread, outside any call scope, so `correlationId` and
  `traceparent` are null unless the application thread is the one serving a call (P1 §5; the core stamps them from
  the thread-local scope, which an advice inherits when the hooked method runs inside a primitive's `APP` call).
- **Encoding of a captured value (D125)**, applied to each argument, `self` and the return value: `null`;
  `Boolean`, `Number`, `Character`, `String` as JSON (a string cut at 2000 UTF-16 code units, 256 inside a collection,
  `truncated: true` on the payload); an `Enum` by `name()`; `BigDecimal`, `BigInteger` as their plain string;
  `java.util.Date` and `java.time.*` as ISO-8601 text; an array, `Collection` or `Map` as a JSON list or object of at
  most 100 elements (keys as strings), each element encoded by this rule one level deep (a nested collection or map
  becomes a handle); everything else as `{"class": "<binary name>", "ref": "<handle>"}` where the handle is
  registered in `ctx.handles()` (weak, as every handle), so that a later `obj.*` family may read it and nothing of the
  object's state leaves the JVM now. **No `toString()` of an application object is ever called**: it could run
  application code on the application's thread, block, or carry personal data.
- **Masking**: an argument whose index is in `secretArgs`, and the return value when `secretResult` is true, is
  replaced by `"<redacted>"` before encoding (the catalogue author's A34 analogue, map 5 §8 Q6; a method that
  *returns* a credential has `secretResult` as its one-line mark); every encoded **scalar** (string, number, boolean,
  character), including those inside lists and maps, then passes every registered `MaskPolicy` as
  `mask("hook.<id>", "arg<i>" | "self" | "return", value)` (P1 §4: the SPI that masks `data.query` rows; the sample's
  `PasswordMask` keeps its rule and may mask by the column `return`), and a map entry whose key `ctx.isSecretKey`
  calls secret is `<redacted>`. **A `MaskPolicy` is called here on the application's thread that called the hooked
  method** (P1 §4's thread rule is amended, §15.1: "on agent worker threads, or, for a `CAPTURE` hook or a Byteman
  rule's bindings, on the application thread that called the hooked method; it must be thread-safe, cheap, and must
  never block or take a lock of the application"; `MaskPolicy`'s javadoc changes with it); a policy that throws
  counts as a handler failure (`Spy.counters()[2]`), the value is replaced by `<redacted>`, and the hook's health
  reports `ERROR("mask policy threw")`. Events keep their payloads otherwise unredacted (A34's rule for events of a
  call), so a catalogue author must mark secret arguments and results: the catalogue reader warns on a `CAPTURE` hook
  whose method name contains `password`, `pin`, `secret`, `token` or `credential` (case-insensitive) with `secretArgs`
  empty, or with `capture.result` true and `secretResult` false, and `skillroy hooks status` prints the warning.
- **Bounds**: the encoder counts the UTF-16 code units and elements it has read from the application's values and
  stops at `hooks.capture.maxBytes` (16 KiB): **the budget is on the input, not only on the output**, so a call never
  encodes more than that on the application's thread; a value it did not finish is `truncated: true`; a payload still
  above the limit after encoding drops `args`, `self` and `returned` in that order. Encoding touches no lock of the
  application.
- **Engine events**: `hooks.installed {catalogue: {id, hash}, hooks: [id], touched: n}`, `hooks.removed` (§3.5) and
  `hooks.stubbed {id, armed: bool, by: "call" | "spi"}` (every arm or clear, by `hook.stub` or by the SPI; never the
  value), source `hooks`.

### 4.4 Topics

`Topics` gains the constants `HOOK_PREFIX = "hook."`, `HOOKS_INSTALLED = "hooks.installed"`,
`HOOKS_REMOVED = "hooks.removed"`, `HOOKS_STUBBED = "hooks.stubbed"`, `RULE_FIRED = "rule.fired"` (§7),
`RULES_CHANGED = "rules.changed"` (§7). The SSE filter and `sync.await` take `hook.*` as any other glob (design §2.3).

### 4.5 Error codes

`ErrorCodes` gains `HOOK_CONFLICT = -32021` (another catalogue is installed and `replace` is false) and
`RULE_REJECTED = -32022` (a Byteman rule the lint or the type check refused, §7.3), each with its `name` mapping;
`HOOK_VERIFY_FAILED = -32020` is raised for the first time (§3.5). `PrimitiveDescriptor` gains the additive field
`script` (D156, built by the security fixes). The protocol version stays **0.2**: nothing of the envelope, the
authentication or the existing shapes changes (P4 added `SCRIPT_ERROR` the same way). The controller maps
`HOOK_VERIFY_FAILED` and `HOOK_CONFLICT` from a run's hook installation to `ERROR` / `ENVIRONMENT` (§5.3) and
`RULE_REJECTED` from a step's `call` to `FAIL` / `ASSERTION` (the rule the scenario wrote is wrong).

### 4.6 Capabilities (D120)

| Id | Declared | States |
|---|---|---|
| `hook.engine` | by `HooksModule.install` (set `UNAVAILABLE` without `Instrumentation`, declared with the probe otherwise; §3.2) | `OK(strategy "advice")`; `UNAVAILABLE` with the probe's reason; never `DEGRADED` |
| `hook.<id>` | by `hook.install` (or the SPI's `install`) for every spec that passed §3.3, with a probe that reads the hook's health; withdrawn through the declaration handle by `hook.remove` and by `uninstall` (A18) | `OK(strategy "advice")` while `INSTALLED` or `FIRING`; `DEGRADED(reason)` for `STALE`; `UNAVAILABLE(reason)` for `ERROR`. A hook that failed §3.3 at install is **not declared**: the id is absent from the matrix, which `requires:` and a guard read as not `OK` (P1 §8.4), and the controller records the fallback (§5.3) |

`hook.<id>` ids are therefore the ids of the pack's catalogue, which is how a scenario requires or guards on a hook
without the base learning the pack's vocabulary (design §1.3: hook-dependent expectations are `requires:`
capabilities). The ids `hook.engine`, `hook.probe`, `hook.install`, `hook.stub`, `hook.remove`, `hook.health` and
`hook.events` are reserved and refused as hook ids (`INVALID_PARAMS`).

---

## 5. The hook catalogue: format, the pack side and the controller side

### 5.1 The catalogue (D121)

A catalogue is one YAML resource of a domain pack or an overlay, read by the controller with the project's strict
reader (`StrictYaml`: unknown keys, wrong types and duplicate ids are errors naming file and line). The agent never
sees YAML: it receives JSON `HookSpec`s (§4.1). The format fixes design §2.6's sketch:

```yaml
catalogue: lending                      # the catalogue id: [a-z][a-z0-9-]*; by convention the pack id
version: 1                              # a positive integer; part of the hash
application: skillroy-sample-lending-app 0.1.0, standard edition      # free text for the record (non-goal: selection by it)
hooks:
  - id: limit.applied                   # the hook id (4.1 name rule); capability hook.limit.applied, topic hook.limit.applied
    mode: CAPTURE                       # OBSERVE | CAPTURE | STUB
    enabled: true                       # default true for a class under the pack's own application packages, false
                                        # otherwise (design 2.6: third-party targets off by default); see "packages"
    target:
      class: ai.skillroy.testjava.samples.lending.AccessControl
      method: returnLimit
      descriptors: ["(Ljava/lang/String;)Ljava/math/BigDecimal;"]
    capture: { args: [0], result: true }                        # CAPTURE only; self: false by default
    secretArgs: []                      # argument indexes whose values are never captured (4.3)
    secretResult: false                 # true: the return value is never captured (4.3)
    emits: hook.limit.applied           # default hook.<id>; must start with hook.
    fallbacks:
      - seam: lending.limits            # always first, always a public seam: a primitive id the matrix must show OK
      # - reflect: { class: …, field: … }   # parsed and refused in Phase 6 ("not in this version"), a non-goal
    note: the limit the desk applies to a return line     # free text, carried to the agent (bounded at 200 chars)
packages: [ai.skillroy.testjava.samples.lending]            # the application's own packages: decides the default of enabled
```

Rules the reader enforces: at least one fallback, and the first is a `seam` (design §2.6: "the first fallback is
always a public seam"); a `seam` names a primitive id (`PrimitiveSpec`'s name pattern); `enabled: true` on a class
outside `packages` is accepted with a warning that `skillroy hooks status` prints (the author overrides the default
knowingly: third-party targets are the author's licence question, design §9; §17 Q6); a `capture` key on a
hook whose mode is not `CAPTURE` is an error (`secretArgs` and `secretResult` are allowed on any mode); `secretArgs`
and `capture.args` indexes are
non-negative; ids are unique; the catalogue is at most 64 hooks (`hooks.maxClasses`'s ceiling); the §4.3 warning on a
`CAPTURE` whose method name suggests a secret with `secretArgs` empty, or `capture.result` true and `secretResult`
false. The reader never resolves a class: whether a target exists is the agent's probe.

**Typed form** (`ai.skillroy.testjava.scenario.hooks`): `HookCatalogue` (`id()`, `version()`, `application()`,
`List<HookEntry> hooks()`, `packages()`, `hash()`, `enabled()` the entries with `enabled: true`, `toSpecs()` the
`HookSpec`s of the enabled entries, `warnings()`, `EMPTY`), `HookEntry` (the fields above plus `fallbacks()` as
`List<Fallback>`, `Fallback.Seam(primitive)`), `HookCatalogues.read(Reader, String source)`,
`HookCatalogues.ofPack(DomainPack pack)` (the resource rule of §5.2), `HookCatalogues.overlay(HookCatalogue base,
Reader, String source)` (§11.2), and `HookCatalogue.builder(base)` with `replace(HookEntry)` (by id;
`IllegalStateException` when absent, the `ResolutionCatalogue` precedent), `add(HookEntry)`, `disable(String id)`,
`enable(String id)`, `build()`. The **hash** is `sha256:` over the canonical JSON of `{id, version, hooks: [{hash,
enabled}…]}`, one element per entry with the entry's `HookSpec.hash()` and its `enabled` flag, sorted by `hash`: a
change of any entry, of the version or of an `enabled` flag changes it, which is what the agent's idempotency is keyed
on (§4.2: the agent receives the hash and the enabled specs, and answers `HOOK_CONFLICT` when the spec set differs
under the same hash).

### 5.2 The pack side

`DomainPack` gains one member with a default:

```java
/** The pack's hook catalogue (phase6-contracts.md 5): by default the resource META-INF/skillroy/hooks/<id()>.yaml
 *  of this class's loader, read once; HookCatalogue.EMPTY when there is none. An overlay answers a catalogue built
 *  from the base's with HookCatalogue.builder(base.hooks()) or HookCatalogues.overlay(base.hooks(), …). */
default HookCatalogue hooks() { return HookCatalogues.ofPack(this); }
```

A pack without a catalogue is unchanged. The sample pack's catalogue is §6.3; the overlay's is §11.3. A catalogue is
read at compile time only to validate it (a compile of any scenario of the pack reports a malformed catalogue as a
compile error naming the resource), and at run time once per run; nothing of it enters `plan.json`, so **plan version
stays 5** (D127): a plan compiled before Phase 6 runs unchanged.

### 5.3 The controller side: `HookInstaller`

`ai.skillroy.testjava.scenario.hooks.HookInstaller` installs a pack's catalogue on a target **once per target,
idempotent by catalogue hash (D129)**, and is called by `PlanExecutor` for every run, by `ExecutorSession.open`, by
the MCP server at its start, and by the CLI command `hooks` (§6.2). In a run it is **step 0b**, between the first
read of the matrix and `checkRequires` (P1 §8.5, P2 §11.2: before the launch-property check, the pre-flight and the
bind), because `requires:` may name `hook.<id>` capabilities that exist only after the install:

1. `mode = options.hooks()` (§6.1), `catalogue = pack.hooks()`. `OFF`, or an empty catalogue under `AUTO`: one record
   `hooks {mode, skipped: "off" | "no catalogue"}`, done. `ON` with an empty catalogue: `PRECONDITION_FAILED`
   ("hooks ON, but pack <id> has no hook catalogue").
2. The matrix: `hook.engine` absent (the module is not loaded) or not `OK`: under `AUTO` the record
   `hooks {mode, skipped: "<reason>"}` and done; under `ON` `PRECONDITION_FAILED` with the reason ("hooks ON, but
   hook.engine is UNAVAILABLE: no Instrumentation in EMBEDDED mode").
3. `hook.health {}`. **An armed stub** (`stubs` not empty) under `replay` is `ERROR` / `ENVIRONMENT` ("a stub is armed
   on the target: <ids>; `skillroy hooks status`"), because CI must never run against stubbed behaviour; under `live`
   and `approve` the ids are recorded and the run proceeds. A catalogue with the same id and hash is installed:
   `hooks {mode, alreadyInstalled: true, catalogue: {id, hash}, armed: [id…]}`, go to 6. The same id with another
   hash (a newer catalogue of the same pack): `hook.install` with `replace: true`. Another id: `ERROR` / `ENVIRONMENT`
   ("catalogue <other> is installed on this target; `skillroy hooks remove` first"), so that two suites never silently
   take turns retransforming one JVM.
4. `hook.install {specs: catalogue.toSpecs(), catalogue: {id, version, hash, application}}` under the executor's
   diagnostic timeout plus 60 s. For every entry whose status is not `OK`: the **fallback** is the first `seam` whose
   primitive the matrix shows `OK`; recorded per hook. Nothing else happens: a scenario that needs the hook names it
   in `requires:` (then `PRECONDITION_FAILED` at step 1 proper) or guards on it (then `DEGRADED`).
5. One record `hooks {mode, catalogue: {id, version, hash}, installed: [id…], unavailable: [{id, state, reason,
   fallback: "seam:<primitive>" | null}], armed: [id…], touched: n, ms, otherAgents: […]}` (`otherAgents` from the
   agent's answer, §3.2: the other bytecode agents of the target, whose changes the post-detach verification cannot
   see); `HOOK_CONFLICT`, `HOOK_VERIFY_FAILED`, a transport error or a timeout from the install is `ERROR` /
   `ENVIRONMENT` with the agent's error as the detail.
6. The matrix is read again (it now holds `hook.<id>`), archived (§6.4), and `checkRequires` runs on it. The executor
   then records the agent's `lastSeq` when the first step begins, `stepsSinceSeq`, which the `hooks` fact root reads
   from (§6.3).

Hooks **stay installed** after the run (D129): the next run of any scenario of the same pack finds them
(`alreadyInstalled`), which is what makes a suite of thirty scenarios cost one retransform per class; they go when a
person runs `skillroy hooks remove`, when the agent is disabled (§3.5) or when the target exits. The JUnit glue
behaves as the CLI: `-Dskillroy.hooks=on|off|auto` (default `auto`) reaches `RunOptions.hooks` through
`ScenarioTests`'s overload without options.

---

## 6. Hooks on and off: the switch, the sample's catalogue, scenario E, the archived matrix

### 6.1 The run-level switch (D127)

`HooksMode { AUTO, ON, OFF }` in `ai.skillroy.testjava.scenario.hooks`. `RunOptions` gains the component `hooks`
(default `AUTO`) and `observer` (§10.2; default `RunObserver.NONE`) as its eleventh and twelfth components; every
earlier constructor stays and fills the defaults; `withHooks(HooksMode)` and `withObserver(RunObserver)` are added.
The CLI option **`--hooks auto|on|off`** is accepted by `run`, `session` and `mcp` (`McpCommand` installs at the
server's start so that sessions over MCP have the pack's hooks; `--hooks on` refuses the start when they cannot be
installed), the system property **`skillroy.hooks`** by the JUnit glue. The switch is a run-level setting and
**not a scenario key**: the exit criterion says the committed scenarios pass *unchanged* with hooks on and off, so
the YAML cannot carry it; the scenario's own words about hooks are `requires: [hook.<id>]` for a hook it cannot do
without and `capability: hook.<id>` on an expectation it can do without (B14, design §1.3).

### 6.2 `skillroy hooks`

`skillroy hooks status|install|remove [--pack <jar>…] [--json] <connection options>`:

- `status`: the agent's `hook.health` (states, counts, **the armed stubs**, the spy's counters, the installed
  catalogue, `otherAgents`), beside the pack's catalogue (id, version, hash, entries, the warnings of §5.1), and
  whether they match; exit 0.
- `install`: `HookInstaller` with mode `ON` and the pack the `--pack` jars (or the one pack) provide; prints the
  record of §5.3 step 5; exit 0 when the install ran (hooks unavailable with fallbacks included), 1 when it was
  refused (`PRECONDITION_FAILED`, `HOOK_CONFLICT`), 3 when the agent cannot be reached.
- `remove [--ids a,b]`: `hook.remove`; prints every restored class with its verification and foreign-event count;
  exit 0 for `OK` and `DEGRADED`, 1 for `HOOK_VERIFY_FAILED`.

The command is listed in `Main` after `session`; `P1 §8.6`'s command list gains it (§15.1).

### 6.3 The base fact root `hooks`, the sample's catalogue and scenario E

**Fact root (D131).** `BaseFacts.ROOTS` gains `hooks`, **a list as `caps` is**, because a hook id holds dots and the
path grammar splits a field at every dot (P1 §8.4; `caps[id=swing.tree].state` is the precedent): the root is
`[{id, state, count, lastFiredMs, armed, last: {…payload…}}]`, sorted by id, read with the filter form
`hooks[id=limit.applied].count`, `hooks[id=limit.applied].last.returned`, `hooks[id=limit.applied].last.args.0`.
**`count` and `last` describe this run's steps**: the root reads `hook.health {}` for the ids and states and, per id,
`hook.events {since: stepsSinceSeq, id, limit: 1000}`, where `stepsSinceSeq` is the agent's sequence the executor
recorded when the first step began (§5.3 step 6); `count` is the number of those events and `last` the newest one's
payload. A bind-time verifier call (the assistant's `lending.access.check` runs before the steps) or an earlier run's
fires therefore never satisfy an expectation; the cumulative figures stay in `hook.health` for `skillroy hooks
status`. When `hook.health` answers `METHOD_NOT_FOUND` (the module is not loaded) or `CAPABILITY_UNAVAILABLE` (no
`Instrumentation`, §4.2) the root is an empty list, so a path under it does not resolve, and the guarded expectation that names it is skipped anyway (its
capability is not `OK`). The root is read fresh at each expectation attempt, like `app`; the base-fact provider seam
gains the run context it needs for `stepsSinceSeq`.

**The sample application changes once for it**: `AccessControl.check` reads the `RETURN` limit through
`limit(code, LIMIT_RETURN)` (public today, with no caller outside the class) instead of `returnLimit(code)`, so that `returnLimit` is called by
**`Engine`'s return path only** (the return line's check and the supervisor's) and never by the verifier's primitive
`lending.access.check`; the hook `limit.applied` then means what scenario E says it means. (`lending.limits` never
called `returnLimit`: it answers `systemMaxReturn()`.)

**The sample pack's catalogue** (`samples/skillroy-sample-lending-pack/src/main/resources/META-INF/skillroy/hooks/lending.yaml`,
pack version **0.5.0**, which also takes gap 49's `inductionRequired` argument of `LendableItem` if the build has
room; every committed plan carries the pack version, so the plans are regenerated once):

| Id | Mode | Target (`ai.skillroy.testjava.samples.lending.`) | Capture | Fallback | Why |
|---|---|---|---|---|---|
| `limit.applied` | `CAPTURE` | `AccessControl.returnLimit(String) BigDecimal` | `args: [0], result: true` | `seam: lending.limits` | the limit the desk applies to every return line, called by the return path only (above); scenario E observes it |
| `access.checked` | `OBSERVE` | `AccessControl.check(String, String, BigDecimal) AccessDecision` | – | `seam: lending.access.check` | the complete decision; the verifier's own primitive calls it, so the hook also shows the verifier at work (and, in the standard edition, nothing else does during E's steps) |
| `waiver.evaluated` | `CAPTURE` | `WaiverEngine.evaluate(List, LocalDate) List` | `result: true` | `seam: lending.waiver.evaluate` | the waivers of a `DUE`; the result is a list of application objects, so the event carries handles (§4.3): the demonstration that no application state leaks |
| `sign.on.checked` | `CAPTURE`, **`enabled: false`** | `AccessControl.passwordMatches(String, String) boolean` | `args: [0], result: true`, `secretArgs: [1]` | `seam: lending.access.check` | catalogued and off (gap 32); when a test enables it, the password index is never captured (§13.1 item 6) |
| `line.amount` | `STUB` | `Deposits.money(BigDecimal) BigDecimal` (static) | – | `seam: lending.deposit` | the acceptance's `STUB` demonstration (§13.2 item 1): armed with `"1.00"` every amount the desk rounds becomes 1.00 (the method is reached by every `LoanLine` and by `AccessControl.check`) and the loan shows it; cleared, the desk computes again; never armed by a committed scenario |

`packages: [ai.skillroy.testjava.samples.lending]`.

**Scenario E** (`samples/skillroy-sample-lending-scenarios/scenarios/return-within-limit-observed.yaml`): a return
within the signed-on assistant's limit, with guarded expectations on the hook. It passes `PASS` with hooks installed
and `DEGRADED` without, on both seeds, and the YAML is identical for both runs and for the branch edition (§11.4):

```yaml
# Scenario E (phase6-contracts.md 6.3): a return within the assistant's return limit. The desk applies the assistant's
# limit to every return line; with the hooks module loaded the pack's CAPTURE hook on that check reports the limit
# the desk applied, and the guarded expectations compare it with the bound assistant's limit. Without hooks they are
# skipped and the run is DEGRADED: the same file passes both ways, and the matrix says which.
scenario: return-within-limit-observed
pack: lending
tags: [returns, hooks]
requires: [lending.input, lending.state, lending.loan, lending.slip]
resolve:
  systemMax: { query: SystemLimit, args: { type: RETURN } }
  item:
    query: LendableItem
    args: { deposit: { between: ["20.00", "200.00"], lte: $systemMax } }
    strategy: [sql]
    verify: lending.deposit
  assistant:
    query: StaffWithPermission
    args: { perform: [SIGN_ON, RETURN], limit: { type: RETURN, gte: $item.deposit }, interactiveSignOn: true }
    verify: lending.access.check
    credential: { realm: staff, key: code }
  keys: { query: ActionKey, args: { actions: [RETURN, DUE] } }
oracle:
  auth:
    fn: ExpectedAuthorisation
    args: { staffLimit: $assistant.limits.RETURN, systemMax: $systemMax, amount: $item.deposit }
    must_equal: NONE                           # a binding that would need a supervisor is rejected, not run
  returnLine: { fn: Money.negate, args: [$item.deposit] }
steps:
  - signOn: { staff: $assistant }
  - expect: { "state.name": IDLE, "state.staff.code": $assistant.code }
  - key: { action: $keys.RETURN }
  - expect: { "state.name": RETURN, "state.returnMode": true }
  - scan: { item: $item }
  - expect:
      "loan.lines[code=$item.code].returned": true
      "loan.lines[code=$item.code].amount": $oracle.returnLine
      "hooks[id=limit.applied].count": { gte: 1, capability: hook.limit.applied }
      "hooks[id=limit.applied].last.args.0": { equals: $assistant.code, capability: hook.limit.applied }
      "hooks[id=limit.applied].last.returned": { equals: $assistant.limits.RETURN, capability: hook.limit.applied }
  - key: { action: $keys.DUE }
  - expect: { "state.name": DEPOSIT, "loan.total": $oracle.returnLine }
  - deposit: {}
  - waitFor: { topic: loan.posted, within: 10s, capture: { slot: loan, fields: { id: loanId, total: total } } }
verify:
  - { "state.name": IDLE }
  - db.loan: { where: { id: $loan.id }, staff_code: $assistant.code, total: $oracle.returnLine }
  - db.loan_line: { where: { loan_id: $loan.id, item_code: $item.code }, returned: true, amount: $oracle.returnLine }
  - { "slip.last.text": { contains: $item.title } }
on_prompt: { unexpected: fail }
timeouts: { step: 30s }
```

Scenarios A to D are **not changed**: they carry no hook expectation and pass `PASS` either way; hooks add evidence
to their runs (the `hooks` record, `hook.*` events in `failure/events.json`, the archived matrix) and nothing else.
The committed plans of A to E are regenerated once for the pack version; E's bindings are committed for both seeds
under `bindings/seed-a/` and `bindings/seed-b/` (the fingerprint does not change with hooks, §6.5, so one file per
seed serves both runs). The tests that enumerate the committed scenarios change with E: `LoadModeChecks` check 14
expects seven ids and E's last status `DEGRADED` (reported as skipped) in the legs without hooks and `PASS` in the
hooks leg; `ScenarioSchemaCommittedTest` expects 18 files; `BindingsUpToDateTest` lists E (§13.1 item 7).

### 6.4 The capability matrix archived per run (D130)

With an output directory, the executor writes **`capabilities.json`** beside `run.jsonl` once the matrix is final
(after step 0b): the wire shape `{"capabilities": {id: {state, reason, strategy}}}` as `GET /capabilities` returns it
(`CapabilityMatrix.toJson()`), ids sorted, and one record `capabilities {hash, ids: n, file: "capabilities.json"}`
in `run.jsonl`, where `hash` is `CapabilityMatrix.hash()`, the `sha256:` of the canonical JSON. `OUTPUT_FILES` gains
the file; `ExecutorSession` writes the same file into its directory at `open`; the MCP server's `--out` likewise.
This is design §2.2 step 5's "archived per run" as promised, and it is what makes a hooks-on and a hooks-off run of
the same scenario distinguishable in evidence, and what §8.2's comparison reads. The `requires` record is unchanged.

### 6.5 Hooks and the fingerprint (D128)

Hooks **never enter the environment fingerprint**: not the catalogue's version, id or hash, not the matrix. Bindings
do not depend on hooks, and a `replay` with hooks off against bindings made with hooks on must not be
`REBIND_NEEDED`. Design §3.4's list of typical components loses "hook-catalogue version" (§15.1); the catalogue's id
and hash are recorded in the `hooks` record and the archived matrix instead.

---

## 7. `skillroy-agent-byteman`: ad-hoc rules in process (optional, B8)

### 7.1 Decision (D132)

The design row for this module (design §2.1: "`rule.*` wraps Byteman's `Submit` client against a 127.0.0.1 listener,
per-session port") is **not built as written**. Byteman's listener is a plain TCP socket with no authentication that
executes arbitrary Java in the target on request (attach §C9, §Risk6: `killJVM` is a built-in); binding it to
loopback is exactly the state of the first build's control channel that findings S1-S4 showed insufficient and that
H1 and H2 replaced. The module therefore loads Byteman **in process** through the agent's own `Instrumentation`,
with `listener:false`, and reaches the rule base through Byteman's documented `manager:` option: a project class on
the system class path receives the `Retransformer` at Byteman's start, hands it to the module once through a
package-private accessor the module reaches by reflection (nothing public on the system class path returns or accepts
it, §7.3), and the module submits, lists and removes rules through it, reflectively. Every rule the agent installs travels over the agent's
HMAC-authenticated channel and passes the dispatcher's read-only and confirm gates; in-JVM code that reflects on
Byteman's or the module's internals is out of scope (`threat-model.md` A12). **No listener is ever opened; no port
exists.** The module is optional (in `optional/`, loaded only when named), the Byteman jar is the user's and never
bundled (B8), and the framework is complete without it (design §9).

What it is for: the design's ad-hoc rule path (attach §R2: rule text is a natural output of a person or a model at
authoring time, type-checked before it loads): tracing a method the catalogue does not name, a fault or a delay
injected for one scenario, a synchronisation point. The typed hooks of §3 cover the catalogued, reviewed case; this
module covers the one-off, under `confirm`.

### 7.2 Spike BS14: "Byteman in process", the gate

Run at the start of the build in `spikes/BS14/`, on JDK 8 and JDK 25 targets in the Linux image, before the two
modules are created. It proves, each with its evidence line in `RESULT.md`:

1. `org.jboss.byteman.agent.Main.premain(options, inst)` can be called reflectively from an agent that holds the
   `Instrumentation`, after `inst.appendToSystemClassLoaderSearch` of the Byteman jar and of a small project jar,
   with the options `listener:false,manager:<project class>` (and `prop:org.jboss.byteman.verbose=false`), and the
   manager class's `initialize` is called with a live `Retransformer` (map 7 §4.2 way 3, §5 option D). The spike
   records **how `Main` resolves the manager's `initialize`** (by name, or by the exact parameter types
   `Retransformer`/`Transformer`, `String`, `Integer`; the one- or three-argument form) and which loader it uses to
   find the manager class, and whether `manager:` needs `listener:true` as well (then the spike shows whether a
   listener that `initialize` never binds can be avoided, or the option is a no-go).
2. The `Retransformer` methods the module needs exist on 4.0.27 with these shapes, or the spike records the real ones:
   `installScript(List<String> texts, List<String> names, PrintWriter out)`, `removeScripts(List<String> texts,
   PrintWriter out)`, `listScripts(PrintWriter out)`.
3. A rule `AT ENTRY` on a toy class with the project helper as `HELPER` fires, the helper's static slot receives the
   call, the rule is removed, and the class's bytes after removal equal the bytes a recorder saw at the first
   transform (the comparison of §3.5, with the recorder registered before `premain`). The spike records **how Byteman
   constructs the helper** (the `(Rule)` constructor, else no-arg) and **how the helper learns the rule's name, trigger
   class and method** from what it is handed. The rule's `DO` calls the helper's `fired` with two bindings
   (`fired($0, $!)`), so that Byteman's resolution of a varargs `fired(Object...)` is proven; if Byteman does not match
   varargs, the helper declares fixed-arity overloads `fired(Object)` to `fired(Object, …, Object)` (eight) and §7.3
   says so as built.
4. `org.jboss.byteman.check.RuleCheck` runs in process against a loaded class and reports a type error for a rule
   with one, and no error for a sound rule; the spike records **whether `RuleCheck` defines classes** (helper
   adapters, compiled rules) in the JVM and in which loader, and **every line it or a failing trigger writes to
   `System.out` or `System.err`**: a run-time trigger failure must reach the agent's log, not the host's console, or
   the limit is recorded in §7.5.
5. Nothing is left listening: the toy's listening sockets, read as `/proc/<pid>/net/tcp` and `tcp6` entries in state
   `0A` whose inode is the target of one of the process's `/proc/<pid>/fd/*` links, are an empty set before the load
   and after the unload (the toy has no agent listener); no Byteman thread but the ones its transformer needs; the
   JVM exits normally with the agent disabled.
6. On a JDK 25 target `premain` through the agent prints no JEP 451 warning (nothing is dynamically loaded: the agent
   itself came through `-javaagent`).

**Pass**: 1-6 on both targets. **Pass with conditions**: a changed signature or option, recorded, is folded into
§7.3-§7.5 as "as built". **Fail** (no manager route, or a listener that cannot be kept closed): the two modules are
**not created**, §7 stays "specified", the TODO item reads "specified only; BS14 failed on …", nothing with a
listener ships (the §17 Q2 veto cannot change that), and the `rule.*` names stay reserved.

### 7.3 Primitives (module `byteman`; `rule.check`, `rule.submit` and `rule.list` name the capability `rule.engine`)

| Primitive | Params | Result | Flags | Timeout |
|---|---|---|---|---|
| `rule.check` | `{rule: <text>}` | `{ok, name, class, method, errors: [], warnings: [], typeChecked: bool}` | read-only: the lint (below), then Byteman's `RuleCheck` **only when Byteman is already loaded** (§7.4) **and the rule's class is already loaded and defined by the system loader** (found through the indexed `getAllLoadedClasses()`, never by loading); otherwise `typeChecked: false` with the warning "type check skipped: Byteman is not loaded (the first `rule.submit` loads it)" or "type check skipped: class not loaded"; `RuleCheck` runs with `setPrintStream` on a buffer whose text goes into `warnings`, so nothing reaches the host's console. **`rule.check` never loads Byteman and never loads a target class** | 30000 |
| `rule.submit` | `{rule: <text>, checkOnly?: false}` | `{name, installed: bool, classes: [{class, status}], messages: []}` | **mutating and destructive** (`_meta.confirm=true`; D133: every rule is a bytecode change and a `DO` clause can change behaviour in ways no lint can bound, so the person confirms every submit); loads Byteman on its first call (§7.4); `RULE_REJECTED` when the lint, the class bound or the type check refuses, with `data {errors}`; `checkOnly: true` is `rule.check` under this primitive's gates, kept because design §2.4 names the parameter | 30000 |
| `rule.unload` | `{name}` | `{removed: bool, restored: [{class, verified: OK|DEGRADED|FAILED, foreignEvents}]}`; `HOOK_VERIFY_FAILED` when a class reports `FAILED` | like `hook.remove`, **neither mutating nor destructive, never gated**: it restores (D119, D133); a rule can only have been installed by a confirmed `rule.submit`, which read-only mode refuses; no capability | 30000 |
| `rule.list` | `{}` | `{rules: [{name, class, method, classes: [{class, status}]}], loaded: bool, reused: bool}` | read-only; the module's own rules only | 10000 |

**Rule names.** The `RULE` line's name must match `[A-Za-z][A-Za-z0-9_. -]{0,79}`; the module stores and submits the
rule under `skillroy:<name>`, so that `rule.unload` can never delete a rule the host installed on its own
(`DELETEALL` is never used). Two submits of one name: the second replaces the first (Byteman's "redefine rule").

**The class bound.** Before `rule.submit` hands a rule to Byteman the module counts the loaded classes it would match
(by name; by supertype or interface for a `^` prefix or an `INTERFACE` rule, over the indexed loaded classes) and
refuses (`RULE_REJECTED`, "rule would retransform <n> classes") above `hooks.maxClasses`, or when any of them is above
`hooks.maxClassBytes` or `hooks.maxMethods` (§3.7), or when any is defined by the bootstrap, platform or extension
loader or by a loader of the agent (the loader rule of §3.3 item 2: `RULE_REJECTED`, "class is the agent's own or the
JDK's"); every retransformation is a stop-the-world pause (BS3 RESULT §9 fact 17), and a rule is bounded as a hook is.

**The lint (`RuleLint`)**, before anything reaches Byteman, errors naming the line: exactly one `RULE … ENDRULE`;
`CLASS` or `INTERFACE` present, its name not under `java.`, `javax.`, `sun.`, `com.sun.`, `jdk.`,
`org.jboss.byteman.`, `ai.skillroy.testjava.agent.` or `ai.skillroy.testjava.protocol.` (the agent protects itself
and the JDK; a target application's own packages, the sample's `ai.skillroy.testjava.samples.` included, are
allowed; the leading `^` of a subclass rule is allowed); `METHOD` present; a `HELPER` line absent (the module adds
`HELPER ai.skillroy.testjava.agent.byteman.sys.RuleHelper`) or exactly that line, **which the lint removes before the
text scan below**; no `IMPORT`, no `COMPILE`/`NOCOMPILE`; the text at most 16 KiB. The text scan runs over the rest
of the text (outside the `RULE`, `CLASS`, `INTERFACE` and `HELPER` lines) **after comments and string literals are
removed, matching entries as whole Java identifiers or as qualified-name prefixes on identifier boundaries**,
case-sensitively (a method `traceId` or `delayDays` is not `trace` or `delay`), and refuses the built-ins and names
that block, kill, write, create processes or reach reflection: `killJVM`, `killThread`, `delay`, `waitFor`,
`rendezvous`, `joinWait`, `signalWake`, `signalThrow`, `traceOpen`, `traceClose`, `trace`, `traceln`, `traceStack`,
`openTrace`, `ProcessBuilder`, `getRuntime`, `Unsafe`, `MethodHandle`, `MethodHandles`, `setAccessible`,
`defineClass`, `getMethod`, `getDeclaredMethod`, `getField`, `getDeclaredField`, `getConstructor`,
`getDeclaredConstructor`, `invoke`, `newInstance`, and the prefixes `System.`, `Runtime.`, `Thread.`, `Class.`,
`ClassLoader`, `java.lang.reflect.`, `java.io.`, `java.nio.`, `java.net.`, `java.sql.`, `javax.`, `sun.`, `jdk.`,
`org.jboss.byteman.`, `ai.skillroy.testjava.agent.`, `ai.skillroy.testjava.protocol.`. `RETURN`, `THROW`,
assignments and calls on bound objects are **allowed**: a fault injection is the point, and `confirm` is the gate.
The deny-list is a `Pattern` constant with its reason per entry, so that the security review reads one place; the
corpus test (§7.6) holds, per entry, one refused rule and one accepted rule whose name or target method contains the
word as a substring.

**The curated helper** (`skillroy-agent-byteman-sys`, on the system class path because Byteman loads a helper through
the trigger class's loader, map 7 §4.5): `RuleHelper` (public, non-final) with the `(Rule)` constructor Byteman
prefers, **compiled against `org.jboss.byteman:byteman` at `provided` scope** and keeping the rule as `Object`: it
reads the rule's name, trigger class and method reflectively, so that its constant pool holds no `CONSTANT_Class`
outside `java/` and `ai/skillroy/` (§2.1), and BS14 item 3 confirms the construction. Its observe-only methods a rule
may call: `fired(Object... bindings)` (publishes `rule.fired`), `count(String key)`, `flag(String key)`,
`clear(String key)`, `flagged(String key)`, `countOf(String key)`, `format(Object)` (a bounded text of a scalar, never
`toString()` of an application object); none of Byteman's `Helper` built-ins is inherited (`RuleHelper` does not
extend `org.jboss.byteman.rule.helper.Helper`). It hands the raw objects to a static slot `RuleHelper.Handler { void
fired(String rule, String className, String method, Object[] bindings); }` with `install`/`uninstall`/`held` of
§3.1's shape (set-once, weak, identity-released), which the module fills at load; the module's handler encodes the
bindings with the rule of §4.3 (handles for application objects, every registered `MaskPolicy` on the application's
thread under the same thread rule and input budget, the secret-key rule, 16 KiB) and publishes `rule.fired {rule,
class, method, bindings: {"<i>": value}}` (source `byteman`). **`RuleManager`** (the `manager:` class) declares
`initialize` with exactly the parameter types BS14 shows `Main` looks it up by (its one public method), stores the
`Retransformer` it is handed in a **private** static as `Object`, and exposes it through two **package-private**
statics only: `take()` (returns it once and clears the field) and `park(Object retransformer)` (gives it back, used by
the module's `uninstall` with no rule left, §7.5). The module invokes both reflectively with `setAccessible(true)`, as
it reaches every Byteman type (the sys jar sits in the unnamed module of the system loader, so no `--add-opens` is
needed), and calls `take()` on the same thread immediately after `premain` returns. **The system class path offers no
public accessor at any time, a parked `Retransformer` included**: a public one would hand any class in the JVM a
`Retransformer` whose `installScript` takes any rule text with Byteman's default `Helper` (`killJVM`, file writes),
past the lint, `confirm` and the channel; code that reaches the package-private statics by reflection is A12
reflecting on internals, out of scope (§7.1, `threat-model.md` §7).

**Events**: `rule.fired` (above) and `rules.changed {installed: [name], removed: [name]}` after a submit or unload.

### 7.4 Loading Byteman

**Only under `rule.submit`**, after the dispatcher's read-only and confirm gates have passed, on an agent worker,
once per JVM (a read never loads an agent into the host; `rule.check` and `rule.list` answer `loaded: false` until
then):

1. The jar: the agent option `byteman.jar` (§3.7) and nothing else, an absolute path to a readable jar whose manifest
   names `Premain-Class: org.jboss.byteman.agent.Main` (`UNAVAILABLE("not a Byteman jar: <path>")`), in a directory
   that no other user may write to and itself not writable by others (the agent's `SecureFiles` directory rule, S8,
   S19, H3; `UNAVAILABLE("byteman.jar is writable by others: <path>")`). No environment fallback: whoever installed a
   Byteman on the machine or set a variable in a shared profile must not decide which jar's `premain` runs inside the
   target (the contract's own stance against `OTEL_*`, D140); the operator names the jar.
2. **Reuse.** If `RuleManager` is loadable from the system loader and holds a parked `Retransformer` (an earlier agent
   instance of this JVM loaded Byteman in process, unloaded its rules and parked it at its `uninstall`), the module
   takes it (reflectively, §7.3): no append, no `premain`, `rule.engine` `OK(strategy "in-process, reused")`; a parked
   `Retransformer` means that no rule of the module is left (§7.5). If `org.jboss.byteman.agent.loaded`
   is `true` and `RuleManager` holds nothing: when `RuleManager` is loadable, an earlier instance of the agent left
   rules installed and its handler in the helper's slot, `UNAVAILABLE("Byteman rules of an earlier agent instance are
   still installed: <names from the log>; restart the target")`; when it is not loadable, the host's own command line
   loaded Byteman and the module has no route to its rule base, `UNAVAILABLE("Byteman was loaded by the host; the
   agent cannot reach its rule base")` (the non-goal of §1).
3. Otherwise: `inst.appendToSystemClassLoaderSearch(sysJar)` (`<home>/optional/skillroy-agent-byteman-sys.jar`), then
   of the Byteman jar; then `Main.premain("listener:false,manager:ai.skillroy.testjava.agent.byteman.sys.RuleManager,
   prop:org.jboss.byteman.verbose=false", inst)` reflectively through the system loader (as BS14 establishes it);
   the `Retransformer` is taken through `RuleManager.take()` (reflectively, on the same thread, at once). Both appends
   are permanent (attach §C4) and logged at INFO with the paths. Nothing in `BytemanModule`, its primitives or its
   probe names a `RuleHelper` or `RuleManager` type in a signature or a field: the module's handler class is loaded and
   instantiated by reflection after the appends, as the hooks engine does for `Spy` (BS3 RESULT §9 fact 9 applies to
   the system class path as to the bootstrap path, since the module loader is child-first and then the host's).
4. The module's first-transform recorder (`addTransformer(recorder, true)`) is registered **before** step 3, so that
   it sees the original bytes of every class Byteman later retransforms; it records only classes that a rule of the
   module names.

Load modes: `JAVAAGENT` and `ATTACH` have the `Instrumentation` and work; `EMBEDDED` is `UNAVAILABLE("no
Instrumentation in EMBEDDED mode")`. Nothing is attached a second time, nothing passes through the 1024-byte attach
argument, and a JDK 25 target sees no second JEP 451 warning: the agent itself was loaded at launch.

### 7.5 Safety, lifecycle and the capability

- **Gates**: `rule.submit` under read-only mode is `READ_ONLY`, without `_meta.confirm` `CONFIRM_REQUIRED`, before
  readiness `NOT_READY`, all by the dispatcher (P1 §5); `rule.check`, `rule.list` and `rule.unload` are served in
  read-only mode; **loading Byteman happens only under `rule.submit`**, so a read-only agent never loads it.
- **Targets**: the lint's deny-list, the class bound and the loader rule (§7.3); `boot:`, `sys:` beyond the two
  appends, `SETSYSPROPS` and `transform.all` are never used or exposed.
- **Restore**: `rule.unload` runs `removeScripts` and then the byte verification of §3.5 for every class the rule
  touched, with the same outcomes and foreign-event count; the module's `uninstall()` unloads **every rule it
  installed**, by name, under the detach budget, then verifies, and **only when no rule of the module is left**
  parks the `Retransformer` (`RuleManager.park`) and releases the helper's handler (`RuleHelper.uninstall`). A rule
  left installed (the budget ran out) is **not inert**: Byteman keeps running its condition and `DO` clause on every
  trigger (a `THROW` or `RETURN` keeps changing the application's behaviour) and only `rule.fired` stops, because the
  handler is disarmed; it is logged at WARN by name with the remedy "restart the target", the `Retransformer` is **not**
  parked, the module's recorder stays registered and holds the disarmed handler strongly in a final field (so the
  helper's weak slot cannot free while the rule exists, as §3.5's pre-filter does for `Spy`), and the next instance's
  `rule.engine` is `UNAVAILABLE("Byteman rules of an earlier agent instance are still installed: <names>; restart the
  target")` (§7.4 step 2). Byteman itself cannot
  be unloaded (attach §C4, map 7 §4.7): its transformer stays registered and returns null without a rule, which the
  capability's reason says after a disable ("Byteman stays loaded; no rule of the agent is installed").
- **Capability `rule.engine`**: declared at `install` with a probe: `UNAVAILABLE("no Instrumentation in <mode>
  mode")`, `UNAVAILABLE("no byteman.jar configured")`, `UNAVAILABLE("not a Byteman jar: …")`,
  `UNAVAILABLE("byteman.jar is writable by others: …")`, `UNAVAILABLE("Byteman was loaded by the host; …")`,
  `UNAVAILABLE("Byteman rules of an earlier agent instance are still installed: …")`, else `OK(strategy
  "in-process")` or `OK(strategy "in-process, reused")`; `agent.info` lists the module as every module. There is no
  per-rule capability: rules are ad hoc by definition, and a scenario that depends on one is wrong by design (design
  §3.2: expectations against primitives and hook events, through the catalogue).
- **Never on the wire**: the rule text appears in `rule.submit`'s params, and so in `run.jsonl`'s `call` record and
  the agent's log line for the call; a rule that names a secret value is the author's mistake, and `_meta.secretParams:
  ["rule"]` is honoured as for any call (A34).

### 7.6 Tests

Unit (the byteman module, Java 8, surefire with its test agent of §2.3): the lint on a corpus of accepted and refused
rules (one refused per deny-list entry with the line reported, and one accepted whose name or target method contains
the entry as a substring; a rule on `ai.skillroy.testjava.samples.lending.AccessControl` with the module's `HELPER`
line accepted; a rule on `ai.skillroy.testjava.agent.core.Dispatcher` refused); the class bound; the helper's and the
manager's slot semantics (`take` once, `park`, both package-private; a reflection scan that no public method of
`RuleManager` returns or accepts the `Retransformer`); the encoding of bindings; the probe's
reasons for each `UNAVAILABLE` case (a fake jar without the manifest entry, no option, a jar in a world-writable
directory, the loaded property set with and without a loadable `RuleManager`); `rule.check` on a fresh agent loads
nothing (no `org.jboss.byteman` class defined, no append). IT (`BytemanIT` in `skillroy-agent-dist`, failsafe, no
display; child JVM on the target Java, the agent through `-javaagent` with the option string
`optionalModules=skillroy-agent-byteman;byteman.jar=<path>`): the jar's path is the code source of
`org.jboss.byteman.agent.Main` on the test class path (the artifact is a test-scoped dependency of
`skillroy-agent-byteman`, **of `skillroy-agent-dist`** and of `skillroy-sample-lending-scenarios` (§13.2), all three
named in the audit's §5 row; resolved once online
into the Maven cache: this is where CI gets it, §17 Q2); the toy target is the new fixture `LendingToyMain` in
`ai.skillroy.testjava.fixture.byteman` with a method `ceiling(String code)`; a rule checked (`ok`, `typeChecked:
false` before the first submit), a rule with a type error refused after the first submit (`RULE_REJECTED`, the
message in the reply and not on the console), a forbidden rule refused by the lint (`killJVM`, a `java.lang.Thread`
target, an agent class, `new ProcessBuilder`), a rule submitted with `confirm`, `rule.fired` arriving on the event
stream with the bound argument, `rule.list` showing it, `rule.unload` restoring with `verified: OK`, `rule.submit`
without `confirm` `CONFIRM_REQUIRED`, in read-only mode `READ_ONLY`; **the child's listening sockets** (the
`/proc/<pid>/net/tcp` and `tcp6` entries in state `0A` whose inode is the target of one of the child's
`/proc/<pid>/fd/*` links) read once before the first `rule.submit` and once after `rule.unload`: the two sets are
equal and hold exactly the agent's endpoint port (`agent.info.port`), skipped with a message on a host without
`/proc`; after `agent.disable` and a re-attach the module **reuses** the parked `Retransformer` (`reused: true`); when the
first instance's budget was cut short with a rule installed (a test seam), the re-attached instance's `rule.engine` is
`UNAVAILABLE` naming that rule, and the rule still fires on the toy (its `DO` runs, no `rule.fired` arrives); after
the final
`agent.disable` no agent thread (P1 §10 check 16's rule). On the sample desk: the hooks leg of the end-to-end
acceptance adds one Byteman check (§13.2 item 7).

---

## 8. A JDK 25 target through `JAVAAGENT`

### 8.1 No JEP 451 warning (D135)

A new check **1b** of `LoadModeChecks` (the end-to-end acceptance's per-mode class, P1 §10; the checks keep their
names in the documents, 1b and 1c beside 1 and 2 to 19, while the class's integer `@Order` values are renumbered in steps of ten, 10, 20, 30
for 1, 1b, 1c and 40 to 210 for 2 to 19, with a comment that maps them, so that `HookChecks`' methods take values
between them: after 1c and before 5, e.g. 65; the `hook.remove` demonstration after 14, e.g. 165; BW's Byteman item
after it, §13.2 item 1), run after the handshake of check 1 in
every load mode on both target Javas: it reads the target's merged log (`LaunchedTarget.logFile()`, the file
`target/it-logs/<mode slug>.log` that `LendingTarget.launch` redirects stdout and stderr into, `javaagent-hooks.log`
for the hooks leg; after `awaitAgent` the lines are in the file, because the log is a redirect, not a pipe) and looks
for the two JEP 451 texts `A Java agent has been loaded dynamically` and `EnableDynamicAgentLoading` (attach §F12).
The four lines a Java 25 target prints on a dynamic load, verbatim (BS3 RESULT §8, `jdk25-attach/stderr.log`; the path
in the first line is the loaded jar's, `skillroy-agent-bootstrap.jar` in the acceptance):

```
WARNING: A Java agent has been loaded dynamically (/…/bs3-agent.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning
WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information
WARNING: Dynamic loading of agents will be disallowed by default in a future release
```

Under `-XX:+EnableDynamicAgentLoading` the target prints none of them, and under `-XX:-EnableDynamicAgentLoading` the
load is refused with `AgentLoadException: Failed to load agent library: Dynamic agent loading is not enabled. Use
-XX:+EnableDynamicAgentLoading to launch target VM.` (BS3 RESULT §8, §9 fact 23); a JDK 25 client attaching to a
Java 8 target gets `Failed to load agent library: 0` after a successful load (attach §F1, the mapping `AttachSession`
already has). The check, per mode (the hooks leg's Java 25 log also holds the CDS line of §2.5, which is not a JEP
451 text):

- `JAVAAGENT`, `JAVA_TOOL_OPTIONS` and `JAVAAGENT_HOOKS`: **no line matches**, on Java 8 and on Java 25 (the
  `JAVA_TOOL_OPTIONS` log legitimately holds `Picked up JAVA_TOOL_OPTIONS: …`, so the check matches the texts, never
  "stderr is empty"); a match fails the check with the lines quoted.
- `ATTACH`, the **positive control**: on a target of Java 21 or newer the texts **must** appear (the desk is launched
  without `-XX:+EnableDynamicAgentLoading`, unlike the controller's own attach fixtures, which silence the warning),
  else the detector is broken and the check fails; on a Java 8 target none appears. Check 15's second attach and
  check 16's re-attach print it again, which the check tolerates (it asserts presence, not count).
- `EMBEDDED`: no line matches.

The findings go into `../TODO.md` under BS1's "Not shown" item (which reads "On a Java 25 target: the JEP 451
warning and the refusal under `-XX:-EnableDynamicAgentLoading`"), now shown; the refusal itself stays with the
controller's `AttachIT`.

### 8.2 The capability matrix equal to the JDK 8 one (D136)

The exit criterion "the capability matrix equals the JDK 8 one" is checked against a **committed golden matrix per
load mode**, in both legs of the Linux matrix (Java 8 targets, Java 25 targets), so that a drift fails in every leg
and not only when the two legs are read side by side:

- Files: `samples/skillroy-sample-lending-scenarios/src/test/resources/capabilities/golden/<mode slug>.json` for
  `embedded`, `javaagent`, `java-tool-options`, `attach` and `javaagent-hooks` (the hooks leg's `Mode`, §13.2); each
  `{"capabilities": {id: {"state", "strategy", "reason": true|false}}}` with ids sorted; and
  `capabilities/golden/allow-differences.json`, a list of `{id, states: [...], reason}` entries, **empty at first**,
  for ids whose state may legitimately differ by JDK (none exists today: no probe reads `java.version`, map 3 §1.4;
  Phase 6's own `hook.*` entries are the first candidates, and the allow-list is where a difference would be
  explained rather than tolerated silently).
- Normalisation: **ids and `state` strict** (a missing or extra id fails), **`strategy` strict** (it names an
  implementation, a constant), **`reason` by presence** (free text that may embed a message of the JDK): the golden
  holds `true`/`false`. `CapabilityMatrix.equals` stays as it is (all four fields); the test's `CapabilityGolden`
  does the normalised comparison and prints a unified diff of the normalised forms.
- When: in `LoadModeChecks` check 1c after `target.ready` (pending entries read `UNAVAILABLE "not probed yet"`, so
  the read must follow readiness), in every mode of both legs, with the hooks leg against its own golden (read
  before any catalogue is installed, so the golden holds `hook.engine` and no `hook.<id>`).
  `-Dskillroy.golden.write=true` rewrites the files from the live matrix (the committed-plans pattern); a build never
  sets it.
- The literal proof of the criterion, both Javas in one assertion, is the Java 8 leg's `PerTargetJavaChecks`: the
  test JVM is Java 25 and the target is Java 8, so one extra target is launched on the test JVM's Java in `JAVAAGENT`
  mode and its normalised matrix compared live with the Java 8 target's; the golden files are the gate in every
  leg, the live diff the proof when both Javas are at hand.

### 8.3 What the hooks leg adds on JDK 25

The hooks leg (§13.2) runs on the Java 25 target as on the Java 8 one, and BS3 ran its engine there (RESULT §2, §8):
the relocated Byte Buddy with its `META-INF/versions` dropped (§2.1), the bootstrap append of the spy jar (accepted
on Java 25 as on 8, with the one CDS line on the host's stderr, §2.5), retransformation (the first install 180-195 ms
on Java 25 against 120-157 ms on Java 8, the next 6-12 ms against 17-23 ms; a `RedefineClasses` safepoint of a median
4.4-5.0 ms and at most 9.3 ms on Java 25, growing with the loaded classes rather than with the class as on Java 8),
and the byte verification (identical bytes on both, with the one difference of §3.5: a redefinition by another agent
shows in the bytes on Java 25 and only in the foreign-event count on Java 8). No `--add-opens` or `--add-exports` is
needed by the engine: it reflects on the target's own classes (`getDeclaredMethods` of the application's classes,
which are in the unnamed module) and never into `java.base`; the `Nexus` measure of §3.2 is what keeps Byte Buddy's
`sun.misc.Unsafe` use, and its four-line warning, off a Java 25 host (BS3 RESULT §6).

---

## 9. The Windows path (specified in full; built and tested where no host is needed)

No Windows host exists in this phase (`../TODO.md`, Walls: the owner provides a Windows 11 machine later). Every item
below is specified; the ones marked **(Linux-testable)** are built in Phase 6 and tested on the Linux image, the ones
marked **(waits for the Windows host)** are built where they are pure code and verified when the host exists; §13.3
lists the verification procedure and §14 says plainly that the Windows exit criterion is not met this phase.

### 9.1 The `.bat` launcher (D137) (Linux-testable)

`controller/skillroy-cli/src/main/bat/skillroy.bat`, assembled as `bin/skillroy.bat` (mode 0644, `<lineEnding>crlf
</lineEnding>`; `.gitattributes` gains `*.bat text eol=crlf` and `*.cmd text eol=crlf` **below** the `* text=auto
eol=lf` line, because a later line overrides an earlier one and above it the `.bat` would be checked out LF in every
clone while passing in a worktree that never re-checks it out; a comment there names `BatLauncherTest` and
`LauncherIT` as the tests that depend on the order), the mirror of `src/main/sh/skillroy` line for line:

| Shell launcher | `.bat` |
|---|---|
| shebang; the usage and environment comments | `@echo off`, `setlocal`; the same text as `rem` lines after the Apache header in `rem` lines |
| follow symbolic links to the script; `home` = the real parent of the script's directory; exit 69 when that fails | `home` from `%~dp0..` normalised with a `for %%i in ("%~dp0..") do set home=%%~fi`; `cmd.exe` cannot follow a link, so a linked `.bat` is unsupported (a `rem` says so) |
| `JAVA_HOME\bin\java` must be executable, else exit 69 with the message; else `java` from the `PATH`, else exit 69 | `if defined JAVA_HOME` check `%JAVA_HOME%\bin\java.exe` exists; else `for %%i in (java.exe) do set java=%%~$PATH:i`; the **same three messages** as the shell launcher, byte for byte except the path, `exit /b 69` |
| the runtime's `release` file two levels above `java` | `%java%\..\..\release` normalised |
| `JAVA_VERSION="…"` parsed; the major is the text before the first dot; refuse `< 25` with the message, exit 69; a non-numeric major is let through | `for /f "tokens=2 delims==" %%v in ('findstr /b JAVA_VERSION= "%release%"')`, quotes stripped with `%%~v`, `for /f "delims=."` for the major, `if %major% LSS 25` with the same message (`1.8.0_x` gives major `1`, as in `sh`); a non-numeric major is let through (`2>nul` on the comparison) |
| `has_attach_module`: `MODULES=` in `release` containing ` jdk.attach ` or `"jdk.attach"`; else `java --list-modules` lists `jdk.attach@` | `findstr /r /c:"^MODULES=.*[\" ]jdk\.attach[\" ]" "%release%"`; else `"%java%" --list-modules 2>nul \| findstr /b /c:"jdk.attach@"` |
| `--add-modules jdk.attach` only for `jvms` and `attach` | `if /i "%~1"=="jvms"` or `"attach"` |
| `exec java $SKILLROY_JAVA_OPTS $modules -Dskillroy.launcher=<home>/bin/skillroy -cp "<home>/lib/*" ai.skillroy.testjava.cli.Main "$@"` | `"%java%" %SKILLROY_JAVA_OPTS% %modules% "-Dskillroy.launcher=%home%\bin\skillroy.bat" -cp "%home%\lib\*" ai.skillroy.testjava.cli.Main %*` then `exit /b %ERRORLEVEL%` (no `exec` in `cmd.exe`: the exit code is relayed) |

- **`ci/license-headers.sh`** gains the kind `rem` for `*.bat|*.cmd` (the header as `rem` lines after `@echo off`,
  which `--apply` keeps first as it keeps `#!`), and the `hash` kind gains `ci/**/*.yml` (the example workflow carries
  a header nobody checked); scenario YAML and plan JSON stay uncovered by path.
- **`BatLauncherTest`** (a unit test of the CLI, runs everywhere): the file's bytes are CRLF throughout and start with
  `@echo off`; every `goto` label is defined; the three exit-69 messages equal the shell launcher's (read from
  `src/main/sh/skillroy` and compared); `ai.skillroy.testjava.cli.Main`, `-cp "%home%\lib\*"`,
  `-Dskillroy.launcher=`, `LSS 25`, `exit /b %ERRORLEVEL%` are present; `--add-modules jdk.attach` appears only after
  the `jvms`/`attach` test; the Apache header is in the first 25 lines.
- **`DistributionNoticesIT`**'s set of files outside `lib/` gains `bin/skillroy.bat`; **`LauncherIT`** compares zip
  and directory with the new file and asserts its CRLF bytes survive the zip (the assembly's `lineEnding` is per
  file).
- **(waits for the Windows host)**: running it (`skillroy --help`, `jvms`, `run` of scenario A against the desk);
  `LauncherIT`'s twin with fake Java homes as `.bat` files.

As built (Phase 6, BW, 2026-10-09; not run on Windows): `controller/skillroy-cli/src/main/bat/skillroy.bat` follows
the table with three refinements that keep `cmd.exe`'s parser out of the way: the script is flat (labels and `goto`,
no parenthesised block, so that `(set JAVA_HOME)` in the third message and a `%version%` set on the way need no
escaping or delayed expansion); a message goes to stderr as `>&2 echo …` (a trailing `1>&2` would leave a space at
the end of the text); `JAVA_VERSION=` is found with `findstr /b /c:"JAVA_VERSION="` (quoted, so that `for /f`'s parser
does not split at `=`), and a non-numeric major is let through by testing it with `findstr /r /x "[0-9][0-9]*"` before
the `LSS 25` comparison, rather than by `2>nul` on a comparison `cmd.exe` would make as text. `ci/license-headers.sh`'s
`rem` kind writes its header lines with CRLF, as the files are, and covers `ci/**/*.yml` and `.yaml`.
`BatLauncherTest` (seven tests) also checks that `.gitattributes` keeps the two CRLF lines below the LF rule;
`LauncherIT`'s new test reads the `.bat` back from the directory and the zip (CRLF, equal to the source, not
executable).

### 9.2 `skillroy author` on Windows (D137) (Linux-testable)

- **`PATHEXT`.** `AuthorCommand.claudeCommand` accepts, besides a file named exactly `claude`, the names `claude` plus
  each extension of the `PATHEXT` environment variable (`.EXE`, `.CMD`, `.BAT`, …, case-insensitive) when
  `os.name` starts with `Windows`; the error text keeps naming the remedy (`--claude-command` or
  `skillroy.claude.command`). Unit-tested on Linux by a fake `PATH` and `PATHEXT` passed through the command's
  environment seam (the lookup takes the environment map as a parameter).
- **`cmd.exe /c`.** `AuthorCommand.mcpConfig` writes, when `skillroy.launcher` ends with `.bat` or `.cmd`
  (case-insensitive), the MCP server command as `"command": "cmd.exe", "args": ["/c", "<launcher>", "mcp", …]`
  instead of the launcher itself: Node-based tools refuse to spawn a `.bat` without a shell since the
  `CVE-2024-27980` fix, and the planner must start the same distribution. `cmd.exe /c` applies its quote rule to the
  whole command line, so with a launcher path **and** an argument that both need quotes a path with a space would
  split; the form that survives (`/d /s /c` with one quoted string) depends on whether the planner's client passes
  arguments verbatim or re-quotes them, which only the host run can show (§13.3). Until then `AuthorCommand` writes
  the plain form above and **refuses, with the remedy** ("move the installation or the pack to a path without spaces
  or special characters"), a launcher path or argument that contains `"`, `%`, `^`, `&`, `|` or a space, so that no
  configuration is written that `cmd.exe` would split. Unit-tested with a fake launcher path (the plain form, and the
  refusal for a path with a space and one with a `%`).
- The planner's work directory and the configuration file go through `SecureFiles` (§9.3).

As built (Phase 6, BW, 2026-10-09; not run on Windows): `AuthorCommand.findOnPath(Map<String,String> env, String
osName)` is the lookup; on Windows it tries the `PATHEXT` names **before** a file named exactly `claude` (npm installs
an extensionless shell shim beside `claude.cmd` that Windows cannot start; D-BW-5), reads `PATH` and `PATHEXT`
without regard to the variable names' case (`Path`), compares extensions as given, lower- and upper-case, and on Windows
asks for a regular file (no executable bit); elsewhere `PATHEXT` is ignored and the file must be executable, as
before. `AuthorCommand.serverCommand(launcher, args)` writes the plain `cmd.exe /c` form and refuses, with exit code 64
and the remedy, a launcher path or argument holding `"`, `%`, `^`, `&`, `|`, `<`, `>`, a space, a tab or a control
character (the contract's list plus the two redirection characters and the other white space; D-BW-6).
`AuthorCommandWindowsTest` (five tests) proves the lookup, the form and the refusals on Linux.

### 9.3 Every owner-only file through `SecureFiles` (D138) (Linux-testable for the POSIX branch; the ACL branch
waits for the host)

The controller's `SecureFiles` has the three branches `POSIX | ACL | NONE` (S7/S15, unit-tested on built ACL values)
and is today a package-private class whose callers are all in `skillroy-controller`; the sites below are in other
modules. **`SecureFiles` becomes `public final class` with exactly these public static members**, everything else
staying package-private (the token readers and `writeOptionsFile` are the launcher's and `AttachSession`'s, confined
by S6/S28): `Path ensureOwnerOnlyDirectory(Path dir)` (exists, widened) and the overload
`ensureOwnerOnlyDirectory(Path dir, String what)` whose refusal names `what` (today's text names "the discovery
directory" and `discoveryDir`, which is wrong for a planner's work directory; the discovery wording stays for
discovery); `Path createOwnerOnlyTempDirectory(Path parent, String prefix)` (new: a random name under `parent`,
created through the branches of the existing private `createOwnerOnlyDirectory`, the replacement for every
`Files.createTempDirectory`); `void writeOwnerOnly(Path file, byte[] content)` (exists, widened; the file must not
exist); `String untrustedDirectoryReason(Path dir)` (exists, widened). Each refuses on a file system with neither view
with the agent's wording and names the directory's role. The sites:

| Site | Today | Phase 6 |
|---|---|---|
| `Planner` work directory (P5 §10.2) | `Files.createTempDirectory` with `PosixFilePermissions` `rwx------` directly | `SecureFiles.createOwnerOnlyTempDirectory(tmp, "skillroy-author-")` |
| `Planner` MCP configuration file (P5 §10.2 step 3) | owner-only through POSIX | `SecureFiles.writeOwnerOnly` |
| `CdpWebPath` trace directory (P4 D59) | `createTempDirectory` with POSIX attributes, else plain | `SecureFiles.createOwnerOnlyTempDirectory`, refused (`ENVIRONMENT`) where neither view exists |
| `SkillroyMcpServer` temporary `--out` | `Files.createTempDirectory("skillroy-mcp-")` | `SecureFiles.createOwnerOnlyTempDirectory` |
| `TargetLauncher` options file | already `SecureFiles.writeOptionsFile` | unchanged |
| `ItEnv.newDiscoveryDir` (the tests) | POSIX permissions when it can, else the checkout's inherited ACL | `SecureFiles.ensureOwnerOnlyDirectory(dir, "the test discovery directory")`, so that the end-to-end tests prove the ACL branch on the host instead of depending on the checkout's ACL |
| `CredentialFiles` (P2 §4) | POSIX refused on any group or other bit; ACL rule; **neither: accepted with a warning** | unchanged in Phase 6; named for the review's lens L7 (map 5 §6 item 8): a token file is refused where a credentials file is accepted |
| the run's output tree (`--out`) | the process umask | unchanged: pass 1's S37 is dispositioned as documentation (`umask 077` on a shared host; the owner-only directory of its option (i) is one of the five deferred parts, §12.2), so no `SecureFiles` site is added here and BW and FX do not meet on it |

The agent side is unchanged (its branches exist since 1.x).

As built (Phase 6, BW, 2026-10-09; the ACL branch not run on Windows): the five members are public and nothing else
(`SecureFilesTest` asserts the set by reflection). `createOwnerOnlyTempDirectory(parent, prefix)` names the directory
`<prefix><16 hex digits>` from the class's `SecureRandom`, creates it exclusively (a name taken is retried, at most
eight times), reads it back with `untrustedDirectoryReason` and deletes and refuses it when that fails (the ACL branch
can inherit entries from the parent); a prefix holding a path separator or `..` is an `IllegalArgumentException`. A
file system with neither view is refused **before anything is created** by every member, in the agent's wording plus
the remedy: "the <role> <path> cannot be used: its file system has neither POSIX permissions nor ACLs, so nothing
written there can be restricted to the current user (use a directory on a file system that has one of them)"; the
discovery wording of `ensureOwnerOnlyDirectory(Path)` is kept for discovery, and the `what` overload drops the
`discoveryDir` remedy. The sites: the planner's MCP configuration is written by `AuthorCommand.mcpConfig` (P5 §10.2
step 3 is the CLI's step), as `skillroy-mcp-<16 hex>.json` under the system's temporary directory through
`writeOwnerOnly` (`AuthorCommand.writeConfig`, the tests' seam); `Planner.privateDirectory(tmp)`,
`CdpWebPath.createTraceDir(parent)` and `SkillroyMcpServer.temporaryOutputDirectory(tmp)` are the seams of the other
three, each tested on the POSIX host and on a zip file system (`PlannerWorkDirectoryTest`, `CdpTraceDirectoryTest`,
`McpOutputDirectoryTest`); the CDP path, which used to run untraced when no private directory could be made, now
fails its `open` (`ENVIRONMENT`, the agent path under `auto`). `SecureFilesRulesTest` does not exist: the zip file
system test is the new `SecureFilesTest`. `ItEnv.newDiscoveryDir` changed in both scenario modules (the lending
desk's and the web host's).

### 9.4 Attach on Windows (D139) (documentation now; the behaviour waits for the host)

- **Cross-session attach is unsupported.** Attaching to a JVM that runs in another logon session (a service in
  session 0, another user's session, an elevated process from a non-elevated controller) is documented as unsupported
  in `README.md`'s Windows paragraph, in P1 §2.5 (a mark, §15.1) and here; the text: "On Windows the controller must
  run as the same user, in the same logon session and at the same integrity level as the target, with matching
  bitness; attaching across logon sessions is not supported, and `JAVAAGENT` is the path for services and for CI
  (control-channel §F28, attach §F11)". The observed failure text is recorded when the host exists; detection and a
  tailored refusal are not built (they need Win32 calls the controller does not make).
- **`ATTACH` is best effort on Windows**: `VirtualMachine.list()` (same user), the discovery files, the 5 s start-up
  margin **kept** (a safety net for a signal Windows does not send, and the JDK's Windows client injects a thread
  into a JVM that may still be starting; nothing is measured yet: measured on the host, then decided), the S18 rule
  for `java.exe` and `javaw.exe` (exists, pure, tested). The `tasklist` / `/proc/<pid>/cmdline` fallback of design
  §2.2 is **dropped** (§1 non-goals; design §2.2 amended, §15.1).
- **`LaunchedTarget.close()`** asks for a normal termination first so that the agent can delete its discovery file;
  `Process.destroy()` is forcible on Windows, so every test launch there leaves a discovery and a token file for
  A23's clean-up (`Discovery.removeStale`, run by `Discovery.list(dir, true)`); documented, no change.

### 9.5 The Windows runner and the verification procedure (waits for the Windows host)

`ci/run-on-windows.cmd` (written now, unverified): `mvn verify -Dskillroy.headless=false
-Dskillroy.it.targetJava=%SKILLROY_JDK8_HOME%\bin\java.exe` (and once without, for the Java 25 targets) from the
repository root in an **interactive logon session** (the tests open real windows; `README.md` says so for every
desktop), with `SKILLROY_JDK8_HOME` and `JAVA_HOME` (a JDK 25) set; it prints the two commands and the environment it
expects when a variable is missing. The matrix table of `../TODO.md` gains a row shape for a Windows leg
(`| windows, Java 25 targets | ci\run-on-windows.cmd | … |`), filled when run. What the host must show, from map 3
§2.6: `LendingEndToEndIT`'s `JavaAgent` and `JavaToolOptions` classes green on a Windows JDK 25 and a Windows JDK 8
(`SEMANTIC` through `lending.input`, `ACTION` and `COMPONENT` through checks 5, 9, 10 and 13, `PAINT` through check
6); **`DISPATCH` through a `swing.click` with rung `DISPATCH` added to check 13** (built now by CP, Linux-testable,
so that the end-to-end run proves every rung in one place; the Swing module's display tests run on the host as well,
as evidence beside it); `ROBOT` excluded (`swing.click.robot` `UNAVAILABLE`, as everywhere); the `ATTACH` and
`EMBEDDED` legs for completeness; one deliberate cross-logon-session attach to record the failure text; the S7/S15
paths with a real ACL (`%TEMP%`, a checkout under the profile, a checkout under the system drive's root, where the
inherited ACL grants `Users` modify rights and `checkAcl` must refuse with the `icacls` remedy, a FAT or exFAT volume
for the "neither" branch); the `.bat` through `LauncherIT`'s twin; the planner started through a launcher path with a
space and a `--pack` path with a space (which decides the `cmd.exe` form of §9.2); the CLI's `AttachIT`; the
owner-only files of §9.3 read back with `icacls`. The owner's questions that decide the host's shape are §17 Q7 and
Q8.

As built (Phase 6, BW, 2026-10-09; not run): `ci/run-on-windows.cmd` (CRLF, the `rem` header) checks that `JAVA_HOME`
and `SKILLROY_JDK8_HOME` name a `bin\java.exe`, changes to the repository root and runs the two `mvn verify` commands
(`call mvn`, the first stopping the script when it fails); a missing variable prints the two commands, the session
rule and the values found, and exits 2. The matrix row shape for `../TODO.md` is in the build report (the orchestrator
writes `TODO.md`).

---

## 10. OpenTelemetry export (B11): `RunObserver` and `skillroy-telemetry`

### 10.1 Decision (D140-D143)

Export is **controller only**: nothing of OpenTelemetry enters a target JVM (`CLAUDE.md`'s rule; control-channel
§C0; B11's "shaded dependency in the agent" door is closed, §17 Q11 records that only the owner could reopen it),
and the agent's side of a call is **reconstructed** from what the reply already carries (`durationMs`, `thread`,
`seq`, the events with their `ts`). The seam is a small SPI in `skillroy-scenario` that names no OpenTelemetry type
and no inference type, `RunObserver`, carried by `RunOptions` (so the executor still reads no system property), fed
from the places that write the matching `run.jsonl` records today (so spans and records cannot disagree); the
implementation is the bundled module `skillroy-telemetry` on the OpenTelemetry SDK with OTLP/HTTP (protobuf) through
the JDK's `HttpClient` sender. It is **off by default**: without `--telemetry` (or the property or the file naming an
exporter) no `io.opentelemetry` class loads and no thread starts. It is **never autoconfigured from the
environment**: the SDK's autoconfigure extension is excluded and no `OTEL_*` variable is read (D88's lesson against
`fromEnv()`, map 5 §8 Q9); every setting is explicit, and a header's value is read from an environment variable the
setting **names**, so that a backend token never appears in a file, an option or an output. Egress is **loopback or
HTTPS only**. Phase 6 exports **spans only**; the metric instruments are named (§10.5) and not built. Attributes come
from an **allow-list** of identifiers, enumerations, counts, durations, hashes and one masked one-line message;
**never** call parameters or values, event payloads, snapshot text, resolved values, SQL, rows, prompts, credentials,
the workspace id or a path under a user's home (the `P2 §11.4` list gains "a span attribute", §15.1).

### 10.2 The SPI (`ai.skillroy.testjava.scenario.observe`)

```java
public interface RunObserver extends AutoCloseable {
    RunObserver NONE = new RunObserver() { … every method a no-op; currentTraceparent() null … };
    void runStarted(RunStart run);                 // one per CLI invocation or JUnit stream (the root span)
    void scenarioStarted(ScenarioStart s);  void scenarioEnded(RunResult result);
    void bindEvent(String type, Map<String, Object> fields);          // what BindListener.event receives (P2 11.3)
    void scenarioRecord(String type, Map<String, Object> fields);     // a run.jsonl record of the scenario that is not a bind event:
                                                                      // requires, launchProperties, preflight, hooks, capabilities, recover, cleanup
    void stepStarted(StepStart step);       void stepEnded(StepResult result);
    void callStarted(CallStart call);       void callEnded(CallEnd call);   // CallEnd: ok, error, RpcResult facts
    void await(AwaitRecord sync);                                     // the sync record's fields
    void modelCall(CostLedger.Entry entry);                           // after the fact, from the ledger
    void sessionStep(StepOutcome outcome);  void sessionTurn(TurnRecord turn);  // the interactive profile
    void toolCall(ToolCallRecord record);                             // the MCP server's tools/call
    String currentTraceparent();                                      // the W3C traceparent of the open call span, or null
    void flush(); @Override void close();                             // bounded; never throw
}
public interface RunObserverProvider {           // ServiceLoader SPI, the WebPath / ResolverContributor pattern
    String exporter();                           // "otlp" | "file"
    RunObserver create(TelemetrySettings settings) throws SettingsException;
}
public record TelemetrySettings(String exporter, String endpoint, Map<String, String> headerVariables,
                                String serviceName, String serviceVersion, String attributes, Path out,
                                long timeoutMs, Path file) { … }
```

The records (`RunStart` with `cliVersion`, `ScenarioStart`, `StepStart`, `CallStart`, `CallEnd`, `AwaitRecord`,
`TurnRecord`, `ToolCallRecord`) are small immutable value types of the package holding only what the span attributes
of §10.4 need (ids, names, counts, durations, statuses, the masked one-line message), built by the caller from what it
already has; none carries call parameters, values or payloads. **`TurnRecord(int index, String outcome, int actions,
long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens, String model, String requestId, long
durationMs)`** is built by `SessionService` in `skillroy-inference` from its `Turn` (`index`, `outcome.name()`,
`actions().size()`, `totalUsage`, `duration`) before the call: `Turn` lives in `skillroy-inference`, which depends on
`skillroy-scenario`, so the SPI cannot name it, and the record never carries the turn's `request`, `answer` or an
action's input or result. Hook points, each beside the `run.jsonl` write it mirrors: `PlanExecutor.executeLogged`
(`run-start`/`run-end`), `runBlock` (steps), `CallRunner.execute` (calls) and `applySync` (`sync`), the `BindListener`
the executor installs (`bindEvent`), `PlanExecutor`'s `RunLog.write` of the seven scenario records `requires`,
`launchProperties`, `preflight`, `hooks`, `capabilities`, `recover` and `cleanup` (`scenarioRecord`, with the fields as
written, so that the span builder takes `skillroy.hooks.mode` and `skillroy.hooks.catalogue.hash` from the `hooks`
record and `skillroy.capabilities.hash` from the `capabilities` record, §10.4; whichever of CP and TL lands second adds
the call beside CP's two writes), `writeLedger` (`modelCall`, with the entry's `details`: `t`, `elapsedMs`,
`requestId`, `stop`, tokens and cost, so the `gen_ai` span is placed at `t - elapsedMs .. t`), `ExecutorSession`
(`sessionStep`), `SessionService` (`sessionTurn`), `SkillroyMcpServer.toolSpecification` (`toolCall`).
`RunOptions.observer` is the twelfth component (§6.1); a `RunObserver` that throws is caught, logged once and replaced
by `NONE` for the rest of the run: telemetry never changes a verdict. `BindListener` records gain `durationMs` on
`resolve`, `oracle` and `fingerprint` (an additive change to P2 §11.3, §15.1), so that the bind span's events show
where model latency and SQL time went.

**The travelling `traceparent`.** `CallOptions` gains a fifth component `traceparent` (the four-argument constructor
stays, as A34 kept the three-argument one), `AgentClient.withMeta` writes it into `_meta.traceparent` (P1 §7's "the
controller sends … and nothing else" is amended: it sends the trace parent when one exists, §15.1), and
`CallRunner.options` fills it from `observer.currentTraceparent()`. The agent needs no change: it already reads
`_meta.traceparent` and stamps it on every event of the call. The MCP server accepts an inbound `_meta.traceparent`
on `tools/call` (`request.meta()`, control-channel §F16) parsed as W3C and dropped when malformed, so that a client's
trace continues into the run.

### 10.3 Settings and the CLI

| Setting | Option (on `run`, `session`, `mcp`, `re-resolve`, `triage`, `author`) | System property (the JUnit glue) | `telemetry.yaml` | Default and rule |
|---|---|---|---|---|
| the switch | `--telemetry none\|otlp\|file` | `skillroy.telemetry` | `exporter:` | `none`: nothing is created, no SDK class loads, no thread starts (the CLI consults `ServiceLoader<RunObserverProvider>` only for another value, and the provider class references no OpenTelemetry type until `create`) |
| where | `--telemetry-endpoint <url>` | `skillroy.telemetry.endpoint` | `endpoint:` | `http://127.0.0.1:4318` (the OTLP/HTTP port); **`http://` is accepted only when the URL's host is a literal IP address for which `InetAddress.isLoopbackAddress()` holds (`127.0.0.0/8`, `::1`, `[::1]`) or the literal `localhost`**; anything else must be `https://`, verified against the JDK's default trust store; the module exposes **no** trust-store, client-certificate or "insecure" setting and never changes the `HttpClient`'s TLS defaults; a violation is a settings error |
| headers | – | – | `headers: { <Name>: env:<VARIABLE> }` | none; the value of each header is read from the named environment variable at `create` and **never** written, logged or printed; a variable that is not set is a settings error naming the variable, never a value; a header given as a literal (not `env:`) is refused ("a header value is read from an environment variable"); **a variable the inference settings read is refused** ("a telemetry header may not carry the model key"): `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_CUSTOM_HEADERS`, `ANTHROPIC_WORKSPACE_ID` and any variable `inference.yaml` names |
| the file exporter's directory | `--out` (the CLI uses `<out>/telemetry/`) | `skillroy.telemetry.out` | `out:` | required for `file`; a path under the run's own output |
| service name and version | – | `skillroy.telemetry.serviceName` | `serviceName:` | `skillroy`; the version is `TelemetrySettings.serviceVersion`, which the CLI fills with its own version (`Main.version()`, private to the CLI, which the telemetry module cannot reference) and the JUnit glue with `skillroy-junit`'s `Implementation-Version`, `(development build)` when absent; `RunStart.cliVersion` carries the same value into `skillroy.cli.version` |
| attribute set | `--telemetry-attributes standard\|minimal` | `skillroy.telemetry.attributes` | `attributes:` | `standard` (§10.4); `minimal` drops **exactly** the list `TelemetryAttributes.MINIMAL_DROPS`: `skillroy.message`, `skillroy.step.message`, the `environment` event's message, `skillroy.pack`, `skillroy.suite`, `skillroy.bindings.file`, `skillroy.slot`, `skillroy.primitive` (replaced by its namespace: a pack primitive becomes `pack`, `swing.*` stays), `gen_ai.tool.name` (replaced by `verb`, `primitive` or `workflow`), `skillroy.step.verb` for a pack verb (replaced by `verb`), the `expect` event's path (replaced by its root), the `resolve <slot>`, `oracle <name>` and `capture` event names (replaced by `resolve`, `oracle`, `capture`); `test.case.name`, `skillroy.failed_step` and `skillroy.step.line`'s step id become `sha256:` hashes of the id; and the span names become `scenario`, `step`, `call`, `chat`, `tools/call` with the id hashed into `skillroy.id.hash`. The §10.6 unit test asserts the list against every attribute, event and span name of a run |
| timeout | – | `skillroy.telemetry.timeoutMs` | `timeoutMs:` | `10000`: the exporter's per-batch timeout and the bound of `flush`/`close` |
| the file | `--telemetry-file <path>` | `skillroy.telemetry.file` | – | `telemetry.yaml` beside the credentials file (the `inference.yaml` lookup), else none; `version: 1`, strict, no secret |

Precedence options > properties > file > defaults, as `InferenceSettings`. A settings error is a usage error (exit
64) found before the agent is contacted. `replay` runs may export (telemetry is not a model call; the zero-call
assertion is untouched). The variables a header names join the "never written" list (P2 §11.4, §15.1). Reporting:
with telemetry on, `run.jsonl`'s `run-start` gains `traceId`, `--json` gains `telemetry: {exporter, traceId}`, the
JUnit XML's `<system-out>` gains one line `traceId=<hex>` (a trace id is not a secret), and the CLI's summary one
line `telemetry: otlp <endpoint host:port>, trace <id>, <n> batches dropped` (the endpoint's host and port only, never
its headers; the dropped count from the module's own failure handler, §10.4).

### 10.4 `skillroy-telemetry`: spans, attributes, status, masking (D142)

The module's `OtlpObserverProvider` (`exporter() = "otlp"`) builds, at `create`: an `SdkTracerProvider` with a
`Resource` that is **`Resource.getDefault()` merged with `service.name` and `service.version` and nothing else** (no
`OTEL_RESOURCE_ATTRIBUTES`, no `opentelemetry-sdk-extension-resources` or `-incubator`: the process resource would
carry the command line), a `BatchSpanProcessor` over an `OtlpHttpSpanExporter` built explicitly from the endpoint
(`<endpoint>/v1/traces`), the headers and the timeout, with the JDK sender (`opentelemetry-exporter-sender-jdk`,
which the exporter finds by `ServiceLoader`; the OkHttp sender is excluded from the module's tree so that exactly one
exists), `AlwaysOn` sampling, and a `W3CTraceContextPropagator` for `currentTraceparent()`. No `GlobalOpenTelemetry`,
no autoconfigure, no `OTEL_*`. The module sets the `io.opentelemetry` JUL loggers to `WARNING` with a handler of its
own that prints the first export failure to stderr and counts the rest into the summary line (§10.3). One trace per
CLI invocation or JUnit stream, scenarios as children (the JUnit XML's one `testsuite`); a MCP `tools/call` continues
the client's trace when it sent one.

| Span | Parent | Attributes (the allow-list; `standard`) | Events |
|---|---|---|---|
| `skillroy run` | root | `skillroy.run.id` (random hex), `skillroy.suite`, `skillroy.bind.mode`, `skillroy.env.id`, `skillroy.cli.version`; the target: `skillroy.target.pid`, `skillroy.target.java.version`, `skillroy.target.load_mode`, `skillroy.agent.version`, `skillroy.agent.protocol`, `skillroy.agent.session` | `environment` (one, with the masked message) |
| `scenario <id>` | run | `test.case.name`, `test.case.result.status` (`pass` for `PASS` and `DEGRADED`, `fail` for `FAIL` and `ERROR`), `skillroy.status`, `skillroy.failure.kind`, `skillroy.failed_step`, `skillroy.pack`, `skillroy.pack.version`, `skillroy.plan.version`, `skillroy.source_hash`, `skillroy.message` (the `RunResult`'s one-line message, masked), `skillroy.skipped_expectations`, `skillroy.degraded_fidelity`, `skillroy.model_calls`, `skillroy.model.cost_usd`, `skillroy.hooks.mode`, `skillroy.hooks.catalogue.hash`, `skillroy.capabilities.hash`; span status `ERROR` for `FAIL` and `ERROR` | `requires`, `launchProperties`, `preflight` (id, ok), `fingerprint` (hash only), `hooks` (installed and unavailable ids, armed ids), `recover`, `cleanup` (primitive, ok) |
| `bind` | scenario | `skillroy.bind.mode`, `skillroy.env.id`, `skillroy.bindings.file` (the file's name, never a directory), `skillroy.bind.skipped` | `resolve <slot>` (strategy, resolver id, candidate count, rejection count, verifier ids and outcomes, confidence, approval state, `reused`, `durationMs`), `oracle <name>` (fn, `durationMs`), `crosscheck`, `guard`, `inference` (mode, gateway, contributor ids) |
| `step <id>` | scenario | `skillroy.step.verb`, `skillroy.step.line`, `skillroy.step.status`, `skillroy.step.message` (masked), `skillroy.step.skipped_expectations` | `expect` (the plan's path text as written, which the `expect` record also carries unbound, never a substituted form; matcher name; held), `db` (table, source, channel, rowCount, ok), `prompt` (expected, decision), `fidelity` (requested, actual), `capture` (slot name) |
| `call <primitive>` | step (or scenario for a cleanup call) | `skillroy.primitive`, `skillroy.call.index`, `skillroy.path` (`agent`/`cdp`), `skillroy.correlation_id`, `skillroy.ok`, `error.type` (the error code's name), `skillroy.seq.before`, `skillroy.seq.after`, `skillroy.events` (count), `skillroy.secret_params` (names only), `skillroy.confirm` | one event per returned agent event: `topic`, `seq`, `source`, at the event's `ts`; **no payload** |
| `agent <primitive>` | call | `skillroy.agent.duration_ms` (`RpcResult.durationMs`), `skillroy.agent.thread`, `skillroy.agent.timing = "reconstructed"`: the span is centred in its parent (`start = t0 + (rtt - durationMs) / 2`), since the agent's start time is not on the wire (`RpcResult.startedAt` is a 6.x amendment if this misleads) | – |
| `await` | call | `skillroy.sync.mode` (`ACK`/`READY`/`SETTLE`/`EVENT`), `skillroy.ok`, `skillroy.event.seq`, `skillroy.topic`, `skillroy.sync.ms`, `skillroy.sync.source` | – |
| `chat <model>` (`gen_ai`) | scenario, or bind when the entry's slot is a bind slot | `gen_ai.operation.name = chat`, `gen_ai.provider.name` (`anthropic` for provider `claude`; `recorded`, `scripted`, `claude-code` as given), `gen_ai.request.model`, `gen_ai.response.model`, `gen_ai.response.id`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `skillroy.cache.read_tokens`, `skillroy.cache.write_tokens`, `gen_ai.response.finish_reasons`, `skillroy.purpose`, `skillroy.slot`, `skillroy.strategy`, `skillroy.batch`, `skillroy.model.cost_usd`, `error.type` for a failed call; start `t - elapsedMs`, end `t` | – |
| `session <id>` / `turn <n>` / `step <id>` | root / session / turn | the session: its id and policy name; `turn <n>`: `skillroy.turn.index`, `skillroy.turn.outcome`, `skillroy.turn.actions`, the usage sums, `gen_ai.request.model`, `gen_ai.response.id`, duration (from `TurnRecord`); the session's `step <id>`: as `StepOutcome` carries them (status, kind, executed, `seqBefore`, `seqAfter`, duration) and the origin kind (`PERSON`, `MODEL`, `SYSTEM` or `CLIENT`, D158); **never the intent text** | journal record types |
| `tools/call <tool>` | the client's `traceparent`, else root | `mcp.method.name`, `gen_ai.tool.name`, `mcp.session.id`, outcome status, `durationMs` | – |

Rules: the `scenario` span's `requires`, `launchProperties`, `preflight`, `hooks`, `recover` and `cleanup` events and its
`skillroy.hooks.mode`, `skillroy.hooks.catalogue.hash` and `skillroy.capabilities.hash` attributes come from
`scenarioRecord` (§10.2: the `hooks` and `capabilities` records as written), `fingerprint` from `bindEvent`; every
string attribute passes the run's `SecretMask` (as `RunLog.write` does) and the `minimal` set drops the
list of §10.3; an attribute or event value is taken from the plan or a record's identifiers, never from a bound or
substituted form, and `TelemetryAttributes` names the source field of each; attribute values are cut at 256
characters; at most 64 attributes and 128 events per span (the default limits of the SDK, set explicitly);
`TelemetryAttributes` holds the names as string constants with the semantic-conventions version in its comment (D142:
the `semconv` jars move between releases and would be two more jars to audit). The agent is not touched: its events
already carry `ts`, `seq`, `topic`, `source`, `correlationId`, `traceparent`.

### 10.5 The `file` exporter and what is not built

`FileObserverProvider` (`exporter() = "file"`) writes `<out>/spans.jsonl` in the **project's own format**
`skillroy-spans/1` (D143): one JSON object per finished span, `{format, traceId, spanId, parentSpanId, name, kind,
startEpochNanos, endEpochNanos, status: {code, description}, attributes: {…}, events: [{name, epochNanos,
attributes}], resource: {…}}`, flushed per span, masked like every run output. It is not OTLP JSON (the OTLP file
exporter is still "Development" and the SDK's JSON exporter writes through `java.util.logging`, control-channel §F24);
it is the test oracle and a CI artifact without a collector, and a converter is a later tool. **Not built**: metrics
(named for a later phase: `skillroy.scenario.count {status, mode}`, the histograms `skillroy.step.duration` and
`skillroy.call.duration {primitive, path, ok}`, `gen_ai.client.token.usage {gen_ai.token.type}`,
`gen_ai.client.operation.duration`, the counter `skillroy.model.cost_usd {purpose, model}`), logs, a gRPC sender,
agent-side spans.

### 10.6 Tests

Unit (`skillroy-telemetry`, with `opentelemetry-sdk-testing`'s `InMemorySpanExporter` behind the same observer code
through a test-only provider; `skillroy-scenario` publishes a **`test-jar`** (a managed entry as `skillroy-agent-web`'s)
that carries `FakeAgentConnection` and the executor tests' fixtures, and the module's secret scan is its own copy of
the P5 `SecretScan` rule, that class living downstream in the sample scenarios' tests): the span tree of a passing, a
failing, a `SYNC_TIMEOUT` and a `REBIND_NEEDED` run against `FakeAgentConnection`; statuses; the `gen_ai` span from a
ledger entry with details; the `agent` span centred inside its call; `traceparent` written into `_meta` and absent
when off; `minimal` versus `standard` (the exact list, span names included); the settings (every rule of §10.3,
including an `http://` endpoint that is not loopback refused, `http://127.0.0.2:4318` accepted, a literal header
refused, a header naming `ANTHROPIC_API_KEY` refused); a **secret scan** of every attribute and event of every span
collected, **and** a check that no attribute or event value equals a public binding value of the run (the recording
observer compares against `bindings.json`). Transport: a loopback OTLP/HTTP stub (`com.sun.net.httpserver` in the
test, `POST /v1/traces`, `Content-Type: application/x-protobuf`) receives one request per flush with the header given
through an environment variable the test sets, and a refused endpoint neither fails nor slows the run beyond the
timeout and is reported once with the dropped count; content is asserted through the in-memory exporter, not by
decoding protobuf (no protobuf library enters the build). CLI (`RunCommand` with `FakeAgent`): default off, proven
by a child JVM with `-Xlog:class+load` whose output names no `io.opentelemetry` class; `--telemetry file` writes
`spans.jsonl` beside the run outputs; `--json` carries the trace id; nothing of a header's value appears in any output
or file (a scan for the variable's value). Distribution: `DistributionNoticesIT` covers the new jars and the project's
telemetry jar, `DistributionContentIT` proves the agent home holds nothing of it. JUnit glue: a stream with
`-Dskillroy.telemetry=file -Dskillroy.telemetry.out=<dir>` writes the file and flushes at JVM exit (a shutdown hook,
bounded).

---

## 11. The tenant-pack overlay and the sample's branch edition

### 11.1 The SPI (D144, D145)

```java
/** An overlay of one deployment variant over a base pack (design 3.6): found by ServiceLoader<DomainPackOverlay>
 *  on the same loader as the packs, applied by DomainPacks.discover to the base whose id it names. */
public interface DomainPackOverlay {
    String baseId();                       // the DomainPack.id() it overlays
    String id();                           // the overlay's own id, [a-z][a-z0-9-]*, for the record and the version
    String version();
    DomainPack overlay(DomainPack base);   // returns the pack to use in place of base; normally a DelegatingDomainPack
}
/** Forwards every DomainPack member to base; an overlay subclasses it and overrides what it changes. */
public abstract class DelegatingDomainPack implements DomainPack {
    protected DelegatingDomainPack(DomainPack base, String overlayId, String overlayVersion) { … }
    public final String id() { return base.id(); }                   // scenarios keep pack: <base>
    public String version() { return base.version() + "+" + overlayId + "@" + overlayVersion; }   // into plan.json
    // every other member forwards; hooks() forwards to base.hooks(); settings() forwards to base.settings()
}
/** A pack's constants, overridable by an overlay: an immutable map of string keys to strings. */
public final class PackSettings { public static final PackSettings EMPTY; String get(String key, String dflt);
    Map<String, String> asMap(); PackSettings with(Map<String, String> overrides); static PackSettings of(Map<String, String>); }
// DomainPack gains:  default PackSettings settings() { return PackSettings.EMPTY; }
// ResolveContext, VerifyContext, FingerprintContext (…scenario.resolve) and the nested PreflightCheck.PreflightContext
//   (…scenario.pack) gain:  default PackSettings settings()
//   (the engine's contexts return the running pack's settings; the defaults return EMPTY for tests' fakes)
```

- **Discovery.** `DomainPacks.discover(loader)` loads the `DomainPack`s as today, then the `DomainPackOverlay`s of
  the same loader, sorts the overlays by `id()`, and for each base pack applies every overlay whose `baseId()` matches,
  in that order (chained: the second overlays the first's result), and lists the result under the base's id. An
  overlay whose base is absent is an error naming both ids (`ScenarioException` at discovery, so `compile` and `run`
  refuse before anything runs). The duplicate-id refusal of the CLI's `Packs` stays a real error: the overlay is not
  a second pack with the same id, it is a second **kind** of service. This is how a private sibling layer that
  depends on this repository ships a variant: the base pack's jar and the overlay's jar side by side on one class
  path (`--pack base.jar --pack overlay.jar`, or two dependencies of a scenarios module); §17 Q4 asks whether the
  private layers wrap the base pack in a class of their own instead, in which case `overlay(base)` is the same seam.
- **Scenarios stay untouched**: `pack: lending` selects the overlaid pack; `plan.json` records `packVersion` as
  `0.5.0+lending-branch@0.1.0`, which the P2 rule "a version that differs at run time is logged, not an error" (Q13)
  keeps harmless; a variant whose `db` SQL differs compiles **its own plans** from the same YAML (P2 §5's rule), §11.4.
- **Constants.** The pack's variant-sensitive literals are read from `settings()` with a default, through the
  contexts, so that an overlay changes one constant with `settings()` alone and replaces a resolver only when its
  logic differs. The keys are the pack's own (`<packId>.<name>`), documented in the pack.

### 11.2 The hook catalogue of an overlay

An overlay's `hooks()` answers `HookCatalogues.overlay(base.hooks(), resource)`, where the resource
`META-INF/skillroy/hooks/<overlayId>.yaml` has the format of §5.1 with two differences: `catalogue:` is the
overlay's id, and its `hooks` entries **replace** the base's entries of the same id (every field of the entry) or
are added when the id is new; `disable: [id…]` turns base entries off. The result's hash is its own (§5.1), so a
target that carries the base catalogue is replaced under §5.3 step 3's "same id" rule only when the overlay keeps the
base's catalogue id; the sample's overlay uses its own id (`lending-branch`), so a desk that still holds the base's
catalogue makes the installer stop with `ENVIRONMENT` and the remedy, which is the right answer for a mixed-up
environment (and why §11.4's acceptance launches a fresh desk for each half).

### 11.3 The branch edition of the sample lending desk (D146)

The second variant is a **configuration variant of the same application jar**: the system property
`lending.edition=branch` (`LendingOptions.PROP_EDITION`, values `standard` (default) and `branch`;
`Edition` enum), reported by `app.state` as **`edition`** (the one key the sample agent module gains, §2.1). Seeds A
and B exist for both editions (`lending.seed` is independent). What differs in the branch edition, each chosen so
that one of the three overrides the roadmap names has a real target:

| Difference (branch edition) | In the application | Overlay override it needs |
|---|---|---|
| the role limit column `role_cap.amount` is named **`role_cap.ceiling`** | `LendingSchema` creates the table with the edition's column; `AccessControl.load` reads it; `SeedData` seeds it | a **resolver**: `BranchStaffResolver` (public, in the overlay module) replaces the base's `StaffWithPermission` resolver (same id) with the SQL naming `ceiling`; the **allow-list**: `BranchAllowList` lists `role_cap.ceiling` in place of `amount` (D100's start check against the database catches a wrong list) |
| the system maximum's setting is named **`return_ceiling`** instead of `max_return` | `AccessControl.systemMaxReturn()` reads the edition's setting name; `SeedData` seeds it | a **constant**: `settings()` of the overlay sets `lending.maxReturnSetting = return_ceiling`; the base's `SystemLimit` resolver reads `ctx.settings().get("lending.maxReturnSetting", "max_return")` |
| the return path computes the applicable limit through **`ReturnCeilings.applicable(String staffCode)`** (a new class of the application, `ai.skillroy.testjava.samples.lending.ReturnCeilings`, used only in the branch edition), not through `AccessControl.returnLimit` | `Engine`'s return-line path (`addReturnLines`, and the supervisor's limit) calls `ReturnCeilings.applicable` when the edition is `branch`; `AccessControl.returnLimit` still exists for the standard edition's return path, and in both editions `AccessControl.check` reads the limit through `limit(code, LIMIT_RETURN)` (public today, §6.3), so nothing on E's path or in the verifier reaches `returnLimit` in the branch edition | the **hook catalogue**: the overlay's `limit.applied` entry targets `ReturnCeilings.applicable(String) BigDecimal`; the base catalogue's target exists but never fires on this edition, so a run of E with the base pack alone against the branch edition ends with the hook never fired (§11.4's negative) |

Everything else is identical: the waiver engine, the slips, the prompts, the verbs, the stored tables, the row masks.
**The fingerprint is unchanged**: its `lending.schema` checksum (a checksum over `ROLE_CAP`'s columns among the twelve
tables) already differs between the editions, so bindings never cross editions, and a new component would change the
hash of the standard edition too and stop every committed bindings file replaying. The sample pack is refactored for
it: the `SystemLimit` resolver reads its setting name from the context's settings; the query records of
`LendingQueries`, the value types, the resolver and verifier ids and the SQL text constants an overlay reuses are
**public** (they are the sample pack's API now, as a private layer's base pack would have to expose the same);
`LendingResolvers`' resolver classes have public constructors taking the SQL they run, so that `BranchStaffResolver`
is a subclass with another statement rather than a copy.

**The overlay module** (`samples/skillroy-sample-lending-pack-branch`): `LendingBranchOverlay implements
DomainPackOverlay` (`baseId "lending"`, `id "lending-branch"`, `version "0.1.0"`) returning a `BranchPack extends
DelegatingDomainPack` that overrides `settings()`, `resolution()` (`ResolutionCatalogue.builder(base.resolution())
.replace(new BranchStaffResolver(...)).build()`), `sqlAllowLists()` and `hooks()` (the resource
`META-INF/skillroy/hooks/lending-branch.yaml`, one entry replacing `limit.applied`); one `META-INF/services/
ai.skillroy.testjava.scenario.pack.DomainPackOverlay` entry. Nothing else: the proof is that the base pack's API and
the scenarios are untouched by the overlay (the application's edition and the agent module's one key are the
variant's, not the overlay's).

### 11.4 Plans, bindings and the variant's acceptance

- The variant's plans are compiled by the overlaid pack from the **same YAML** and committed under
  `samples/skillroy-sample-lending-scenarios/variants/branch/plan/<scenario>.plan.json`; they differ from `plan/`'s
  in `packVersion` **only** (a plan holds query kinds and `db` SQL, never a resolver's SQL, and the `db` checks of A,
  B and E read `loan`, `loan_line`, `loan_waiver` and `loan_authorisation`, none of which the branch edition renames),
  which `BranchPlansUpToDateTest` proves as `PlansUpToDateTest` does for `plan/`. Bindings for the variant's
  environment `branch-a` (seed A, branch edition) are committed under `variants/branch/bindings/branch-a/` for
  scenarios A, B and E, approved with the label `approve`.
- **Acceptance (§13.2 item 8)**: scenarios A, B and E replay green on the branch edition with the overlay (A exercises
  the renamed column and the renamed setting, B the unchanged half, E the overridden catalogue), the hooks leg of the
  variant showing `hook.limit.applied` `OK` with the overlay's target; **the negative**, on a **freshly launched**
  branch-edition desk (or after `skillroy hooks remove`, so that §5.3 step 3's "another id" refusal is not what ends
  it): E with the base pack alone (no overlay on the class path) ends `DEGRADED` when hooks are off (the guard skips)
  and, with hooks on, the base catalogue installs on `AccessControl.returnLimit`, which nothing on E's steps reaches
  in this edition, so the guarded expectations fail at the step timeout as `FAIL` / `ASSERTION` with the actual
  `hooks[id=limit.applied].count = 0` (the run-scoped root of §6.3): the demonstration that the overlay's catalogue is
  needed and not decorative. Scenarios C and D are not run on the variant (D's recordings are keyed by prompt content,
  which the variant's allow-list changes; a variant recording is a live item, §13.4).

---

## 12. The repeat security review, the threat model, `SECURITY.md` and the release checklist

### 12.1 Decision (D147, D148)

The security review of 2026-10-02 (six lenses, 34 findings S1-S34, every one fixed by H1-H3, nothing accepted as a
known risk; `../TODO.md`, B13) is **repeated before the first public release**, in **two passes over eight lenses**,
each lens read by one reviewer and every finding and every "found sound" claim tested by one **sceptic**, on the
**four-level scale** of 2026-10-02 with the rubric of §12.4 (so that the counts of the two reviews compare), with
one record in the shape of 2026-10-02's, dispositions under the rule of §12.5, and the **owner's signature** as the
sign-off (§12.6; the owner's word of 2026-10-09). The review reads against a **written threat model**,
`threat-model.md` beside this file (§12.7), which every lens cites for "who can reach it", and the release carries
`SECURITY.md` and `docs/release-checklist.md` (§12.8). Pass 1 has run; its four fixes that change confirmed decisions
are stated once, here, as D156-D159 (§12.9), and built by the security-fix package alone.

### 12.2 Pass 1: lenses L3-L6 on the committed tree (finished and dispositioned 2026-10-09)

Pass 1 ran on HEAD `e86e8e2` while this contract was written, on the four lenses whose code Phase 6 does not change:
L3 (credentials, data channels, what a run writes), L4 (embedded web views), L5 (the model boundary), L6 (the model as
an actor); one reviewer and one sceptic per lens. The reviewers raised 25 findings; the sceptics confirmed all 25 and
added 3 from "found sound" claims they refuted: **28 confirmed findings, S35-S62: 1 high (S55), 6 medium (S43, S44,
S46, S56, S57, S58), 21 low; 18 counted, 10 operator-only; nothing rejected**. The high: the MCP `data_query` tool
substitutes `$references` into the SQL **after** `SqlGuard` checked the text (S55). The mediums: page-produced data
bounded only by page-side code (S43), the debug-port probe's unbounded `/json` bodies before the ownership check
(S44), the author's regexes on page-controlled text without the S24 budget (S46), `skillroy_scenario_run` spending
outside the session budget (S56), the verb gate decided by probes only under an origin that confirms (S57), captures
served to the MCP client as unmasked, uncut blobs (S58). The record is `pass1/record.json` (the 2026-10-02 shape) and
the fix specification is **`pass1/dispositions.md`** in the session notes: per finding the files and behaviour of the
fix, the test that fails before it, the contract passage it changes and the amendment text, and the fix work package
(`FX-scenario`, `FX-web`, `FX-inference`, `FX-mcp`, `FX-ci`). This contract does not restate the fixes.

**How pass 1 is built.** Every confirmed finding is dispositioned by the **fifth work package, "security fixes from
pass 1" (FX, tag `SR`)**, contract first in the contract each fix touches (P1, P2, P4 or P5), marked "(Phase 6
security fix S<n>, A<m>, built …)", with the test that fails before the fix, and recorded in `../TODO.md` under "Phase
6 security review, pass 1" in the form `dispositions.md` gives. **Before the wave the orchestrator assigns, in
`dispositions.md`, the A-numbers (A40 onwards, in S order, one per finding whose fix changes a contract passage) and
the D-numbers of its four provisional decisions, which are D156-D159 here (§12.9); a fixer mints nothing.** FX builds
over the merged tree of the four other packages and lands before the Phase 6 code review, so that pass 2 reads the
fixes as code. Where a fix and a Phase 6 package touch one file, the FX fix lands last (the work-package list in the
session notes names them).

**Dispositions under §12.5.** The high and the six mediums are fixed in full. Of the lows, `dispositions.md` **defers
a part of five** with a release note each (S36 part (b), a deny-list of side-effect function names in `StatementGuard`;
S37 option (i), owner-only run output directories by default; a part of S40, pinning the CI example's image and actions;
S48, a minimal environment for the Playwright driver; a part of S60, relocating the `claude` CLI's configuration) and
fixes **S37 and S48 by documentation only**, which under §12.5 is an **acceptance, not a fix**, and so needs the
owner's word: the five are put to the owner as **§17 Q22**, named in B26 now and repeated in B28, each with the phase
it goes to where there is one (7, or the phase that builds the seam; S40's part has none) and its release note. Nothing of medium or above is deferred.

### 12.3 Pass 2: all eight lenses on the Phase 6 tree

After the Phase 6 build and its own three-lens code review and fix wave, on the committed tree: L1, L2, L7 and L8 in
full, L3-L6 as **deltas** (what changed since pass 1, plus a re-read of every pass 1 finding's fix). The lenses, from
map 5 §5, with the Phase 6 surfaces added:

| # | Lens | Scope | Threat model (`threat-model.md`) | A finding is |
|---|---|---|---|---|
| L1 | the local trust boundary and the control channel, repeated | `ControlServer`, `HttpHead`, `Dispatcher`, `SecretParams`, `Auth`, `Protocol`, both `SecureFiles` (the controller's now public API, §9.3), `Discovery`, `AttachSession`, `TargetLauncher`, `LaunchedTarget`, `BootLauncher`, `BootOptions`, `AgentClassLoader`, `ModuleLoader` (the new `optionalModules` source), `AgentClient`, SSE, the CLI `Connector`; P1 §2.4-§2.5, §5, §7 | A1, A2, A3, a relaunched target | a request or reply authenticated wrongly or not at all; a bound that applies only after authentication; a file trusted outside the owner-only rule; an enable path the design forbids; a stale credential honoured; **a second listener of any kind** (the Byteman module must have none) |
| L2 | host safety and the instrumentation path | `AgentRuntime`, `ModuleLoader`, `ModuleClassLoader`, `DaemonThreads`, `CoreThreadBroker`, `RingEventBus`, `RingLog`, the swing module's EDT paths; **new**: `skillroy-agent-spy`, `skillroy-agent-hooks` (probe, install, stub, remove, the ledger, the byte verification and the foreign-event count, the size limit, the `Nexus` measure, the pre-filter, the detach invariant on the slot), `skillroy-agent-byteman` and its sys jar (the lint, the class bound, the helper, the manager's `take`/`park`, the loading under `rule.submit`); §3, §4, §7; BS3 RESULT | A4 (the application's own code), a wrong catalogue, another bytecode agent (A12) | anything that throws into, blocks or slows the host; `STUB` reachable in read-only mode or without `confirm`; a hook on a class the catalogue did not name or on the agent's own; `Spy`'s slot free while an advice exists, or settable in a way the engine did not intend; a detach that leaves bytes, threads or loaders behind without the WARN and the refused re-attach; `HOOK_VERIFY_FAILED` not raised when a `Spy` reference remains; a foreign redefinition not reported; a rule the lint should refuse; Byteman loaded by a read; the `Retransformer` reachable from the host |
| L3 | credentials, data channels and what a run writes | `controller/skillroy-data/*`, `protocol/sql/*`, `agent/skillroy-agent-data/*`, `PlanExecutor`, `RunLog`, `BindingsFile`, `JournalExport`, `JunitXmlReporter`, `FixtureSecrets`, `SecretParamsForecast`, `SafeRegex`, the binder's provenance, `Oracle`/`CrossCheck` purity, `ci/run-in-linux.sh`, `ci/examples/nightly-re-resolve.yml`; **new**: `capabilities.json`, the `hooks` record, `CAPTURE` payloads in `failure/events.json`; the pass 1 fixes S35-S41 as deltas | A4, A13 (a reader of what a run leaves behind), a committed bindings file, A1 for the credentials file | a credential or personal value written as a value anywhere P2 §11.4 lists; a `SELECT` with side effects the guard admits; a connection left writable; a mask bypass through a type; a CI step that puts a secret on a command line |
| L4 | embedded web views: the debug port, the page as an adversary, the driver | `controller/skillroy-cdp/*`, `scenario/web/*`, `agent/skillroy-agent-web/*`, `protocol/web/WebLib`, `web-lib.js`, the kit; P4 §3-§6, §8.3, A35-A38; the pass 1 fixes S42-S48 as deltas (the `script` flag, D156, among them) | A3 | an attach to a browser the application did not configure; page-controlled data trusted beyond its bounds on the controller's side; a script primitive served where the policy says read-only; a trace, screenshot or console line carrying a secret past the mask; the driver loaded from a world-writable place |
| L5 | the model boundary: key, prompts, recordings, retention, cost | `ClaudeGateway`, `ClaudeMapping`, `Gateways`, the recorded and recording gateways, `PromptMask`, `SchemaCatalogue`, `LlmSqlResolver`, `InferenceContributor`, `AllowListCheck`, `SqlGuard`, `Analyzer`, `BudgetMeter`, `LedgerWriter`, `ReResolver`, `Triage`, `SessionServices`, `InferenceSettings`; P5 §2.3-§2.5, §4-§8; the pass 1 fixes S49-S54 as deltas | A5 (and what it reads, A4), A14 (the provider or whatever `ANTHROPIC_BASE_URL` names), A11's key | the key or workspace id anywhere but the request header; a prompt part a rule of P5 §6.2 should have masked; a recording holding what a prompt may not; `replay` constructing a gateway; a wait or spend outside its bound |
| L6 | the model as an actor: MCP, session, planner, overlay | `controller/skillroy-mcp/*`, `ToolProjection` (`skillroy-inference`), `WorkflowTools`, `PlanPolicy`, `DataQueryLists`, `ExecutorSession`, `CallRunner`, `PlanCompiler`, `JournalExport`, `AgentClient.withMeta`, `SessionService`, `OverlayBridge`, `Planner`, `AuthorCommand`, `McpCommand`, `SessionCommand`; P5 §9, §10, §12, §13.4; **new**: the never-offered set of `hook.*` and `rule.*` primitives (§4.2), the `CLIENT` origin and the verb gate at call time (D158), captures under `images: true` (D159), the inbound `traceparent`, `cmd.exe /c`; the pass 1 fixes S55-S62 as deltas | A5, A6, A7 | a path from model or client input to a destructive, `agent.*`, `data.query`, `hook.*` (other than `hook.health` and `hook.events`), `rule.*` (other than `rule.list`) or script primitive without a person's flag; a confirmation or approval not typed by a person; a resource or tool result past the mask or the cut; a budget bypass; the planner started with more than its policy allows |
| L7 | Windows and file-system trust | both `SecureFiles`, `CredentialFiles`, `TargetLauncher` (quoting, the options file), `LaunchedTarget`, `Planner` (work directory, configuration), `CdpWebPath` (trace directory), `SkillroyMcpServer` (`--out`), `AttachSession`, `skillroy.bat`, `AuthorCommand`'s Windows branches; §9 | A1 on a shared Windows host, a file system with neither view, localised principal names, a path with spaces | an owner-only guarantee silently absent; a trust check skipped; an attach across logon sessions that works when the document says unsupported; a launcher or a configuration that word-splits a path. **Read on Linux in pass 2; the host-dependent items are re-read when the host exists** (§13.3) |
| L8 | telemetry and evidence at rest | `skillroy-telemetry` (the span builder, the settings, the exporter, the resource, the JUL handler), `RunObserver` call sites, `RunLog`, the `failure/` writers, `SkillroyMcpServer` resources, `Triage` evidence, `cost-ledger.jsonl`, `inference.jsonl`, the planner's envelope files, `capabilities.json`, `spans.jsonl`; §10 | A9 (a backend operator), A13 for files at rest, A6 for served resources | an attribute carrying call arguments, values, bound text or unmasked messages; export on by default or to a non-loopback endpoint without TLS; a header value in any output; a header that names the model key's variable; a capture served without the opt-in; a file written outside the run's directory |

Common rules (the brief of 2026-10-09 and the phase reviews' briefs): the committed tree is read-only; probes run in
a copy or a worktree with `mvn -o` and `-pl … -am`, never `install`, never a display outside the image, never a live
model call, never a search for a key; every finding cites file and line and quotes them, states the actor (from the
threat model), the precondition, the path, what the actor gets, and a fix that respects the contracts and the
confirmed decisions (a fix that breaks one says so and proposes the contract change); every finding and every "sound"
claim gets a sceptic's verdict (`CONFIRMED`, `CONFIRMED, RESTATED`, `REFUTED`, `UNDECIDED`) with its evidence;
neutrality throughout.

### 12.4 The severity scale and the counting rule

Four levels, the scale of 2026-10-02 with the phase reviews' rubric as the examples per level:

- **Critical**: code execution or secret disclosure reachable by a remote party or by any local user without
  preconditions.
- **High**: code execution in the target or the controller, or disclosure of a credential, the model key, a prompt or
  a personal value, reachable by another OS user, a same-user process without the token, a page, the target's content,
  or a model or MCP client; a model's request confirming or reaching a never-offered primitive; `replay` making a model
  call; a wrong `PASS`/`FAIL` from hostile input; a non-deterministic committed artifact that hides one of these.
- **Medium**: an availability attack on the target JVM or the controller by those actors; a gate that fails open in a
  reachable configuration; a documented guarantee not implemented, without disclosure; an unbounded wait or resource.
- **Low**: host hygiene, robustness, a redaction gap behind an unusual precondition.

Re-rating by the sceptic is expected and recorded with its reason. **Counting**: a finding that needs the agent's
token, or the operator's own environment, to reach is recorded as "token-holder only" or "operator-only" and kept but
not counted, as in 2026-10-02, **unless** a model or an MCP client is the token's user (then it counts): the token is
code execution in the target by design (`threat-model.md` §4), and the model is not a trusted party. Pass 1 rated on
exactly this rule (18 counted, 10 operator-only).

### 12.5 The record and the dispositions

- **The record**: one JSON file of the 2026-10-02 shape (`lensSummaries[8]` with each lens's `checked` text and its
  evidence limits, `confirmed[]` with lens, title, severity, file, line, actor, scenario, fix and the sceptic's
  verdict, `rejected[]`, `unverified[]`) and its prose twin, kept in the session notes (not the repository); a
  summary in `../TODO.md` under "Security review (repeat, <date>)" in the form of the 2026-10-02 entry, with counts
  per level and per lens, S-numbers from S35 on.
- **A disposition for every confirmed finding**: **fixed** (contract first, the test that fails before the fix, the
  commit), **accepted** (only a **low** finding, with the owner's explicit acceptance written into `../DECISIONS.md`
  as part of B26 or B28 with the reason, and a release note that names it; **a documentation-only change is an
  acceptance, not a fix**), or **deferred** (low only, **to a named phase**: 7, or the phase that builds the seam the
  fix needs, never "later", with the release note). **Nothing of medium or above may be accepted or deferred**: it is
  fixed before the release, or the release waits (§17 Q16). A finding that is the design (a token holder may end the
  agent with `agent.disable`; a same-user process may attach to a JVM of the same user) is recorded as **design**,
  with the passage of the threat model that says so, and counts for nothing.
- **A re-run of the matrix after the fixes** (the Linux matrix: arm64 twice, amd64 once, Java 8 and Java 25 targets,
  the agent side on a Java 8 test JVM; the live profiles skipped) is part of the record, as for every phase.

### 12.6 The sign-off (B28)

The review is signed off when, and only when: (1) the record of §12.5 exists with every finding dispositioned under
its rule, pass 1's five accepted low parts (Q22) included; (2) the matrix after the fixes is green; (3) the "not
verified" list is explicit where a release reader looks: `README.md`'s status paragraph, `../TODO.md`'s Walls,
`SECURITY.md` and `threat-model.md` §8 (Windows: S7/S15 and the owner-only files of §9.3 until the host exists; the
`jxbrowser` adapter against the real product; the live-only paths; the Byteman module if BS14 was not run on a real
application; a detach that exhausted its budget, and a Byteman rule such a detach left, which keeps acting until the
target restarts); (4) `threat-model.md`, `SECURITY.md` and `docs/release-checklist.md`
are in the tree and the licence audit's §8 rows of every Phase 6 dependency adopted have moved to §3, §4 or §5; (5)
**the owner writes the signature**: a B-row in `../DECISIONS.md` ("B28: the repeat security review of <date> is
signed off", with the record's path, the counts per level and the accepted lows) and the `../TODO.md` exit item ticked
by the owner's word, not by the orchestrator. Whether a human reader from outside the project reads the record before
the first public release is §17 Q12.

### 12.7 The threat model (`threat-model.md`)

Written with this contract (D148), about a hundred and fifty lines, cited by every lens and by `SECURITY.md`: the
actors (A1 another OS user on the host; A2 a same-user process without the token; A3 a browser page or web content
inside the target, an adversary of the controller as much as of the agent; A4 the target application and its content;
A5 the model and what it reads, a holder of the token through the session service and the planner's server; A6 an MCP
client, a holder of the token through `skillroy mcp`; A7 the planner's CLI and what is on `PATH`; A8 a remote network
party; A9 a telemetry backend's operator; A10 the pack author; A11 the operator; A12 other code in the target JVM:
another agent, a plugin; A13 a reader of what a run leaves behind; A14 the model provider, or whatever
`ANTHROPIC_BASE_URL` names), the trust boundaries with the mechanism at each (its §6), what is trusted (its §3: the
pack, the committed scenarios, plans, bindings and recordings as reviewed code; the operator's environment, the
Byteman jar named by `byteman.jar` included; the JDK, the Maven cache and the CI image and actions the operator pins;
the owner-only file systems' own guarantees; hook and rule events as evidence against non-adversarial application code
only), the **token rule** (its §4: the token is code execution in the target; the review counts nothing that needs it
unless a model or client holds it), the **model rule** (its §5: the model proposes and never confirms; nothing it
produces reaches `data.query` unguarded, hooks, rules, scripts or module upload; what it reads from the target is
untrusted; a resolved value is target content), what is out of scope (its §7) and what is not verified (its §8).

### 12.8 `SECURITY.md` and `docs/release-checklist.md`

- **`SECURITY.md`** (repository root, Markdown, no licence header): the disclosure channel (§17 Q13: provisionally
  the repository host's private vulnerability reporting for this repository and the owner's address as given in
  `NOTICE`); the supported versions (the latest minor release only, until the project says otherwise); what the
  project considers in scope (the threat model's actors A1-A9 and A12-A14) and out of scope (its §7); the known
  limitations a user must read before running the agent on a shared host (the `-XX:+DisableAttachMechanism` note of
  B13, the Windows items not verified, the JEP 451 path, the CDS line of a Java 25 host in hooks mode, the `umask 077`
  rule for run outputs of S37); the expected response time (a reply within seven days; no bounty); a pointer to
  `docs/design/threat-model.md` and to the record of the last security review.
- **`docs/release-checklist.md`**: the ordered list a release runs through: the matrix green (the legs named); the
  repeat review's record and sign-off (B28) for the first release, a delta review for later ones; the licence audit
  re-run (its §9 triggers) and `THIRD-PARTY.txt` compared with the built zip (`DistributionNoticesIT`); the agent home's
  content test and `HomeConstantPoolIT`; the "not verified" list current in `README.md`, `SECURITY.md` and
  `threat-model.md` §8; `NOTICE`'s wording checked against the home's content (§17 Q1); the groupId's domain confirmed
  (B2); the DCO trail; the version in every `version()` and `agentVersion`; the committed plans, bindings, goldens
  and recordings up to date (`PlansUpToDateTest` and its siblings green); the `.bat` marked unverified or verified;
  the release note naming every accepted or deferred low finding (Q22's five among them) and whether an outside
  reader read the record (Q12).

### 12.9 Four hardening decisions from pass 1, stated once (D156-D159)

Each is the contract's one specification of a rule that pass 1's `dispositions.md` proposed under a placeholder
(`P6-SEC-1` to `P6-SEC-4`); each changes a confirmed decision and is put to the owner as §17 Q18-Q21; each is built
**by the security-fix package FX alone** (the sub-package named), so that no field, gate or flag is built twice. HK,
CP, TL and BW build nothing of them; CP's own `ToolProjection` and `PlanPolicy` work (the never-offered `hook.*` and
`rule.*` set of §4.2) lands before FX's, which lands on top.

- **D156 (= P6-SEC-1, S42; amends D51, B21 Q5; keeps D98): the descriptor flag `script`, refused in read-only mode on
  both paths.** `PrimitiveDescriptor` gains the boolean `script` (JSON key `script`, optional, default false, read with
  `Wire.optBool`) and `PrimitiveSpec` gains `Builder.script()` and `script()`: "this primitive evaluates a
  caller-supplied expression or script in the target". `web.evaluate` and `web.waitFlag` set it (P4 §4.2; `web.waitFlag`
  keeps "not mutating" for the step protocol: no settle, no recover). **The agent's dispatcher refuses a `script`
  primitive in read-only mode as it refuses a mutating one** (`READ_ONLY`; P1 §5's gate 2 becomes "`readOnly` and spec
  `mutating` or `script`"), and the CDP path refuses it on a read-only target (`CdpWebPath.SCRIPT` beside `MUTATING`,
  `UNSUPPORTED`, then the agent's `READ_ONLY`). `ToolProjection` computes `readOnly = !mutating && !script`
  (`readOnlyHint: false` for `web_wait_flag`), decides its exclusions by `script || SCRIPT_PRIMITIVES.contains(name)`
  (**the named set stays as the fallback for an older agent's descriptors without the flag**, not replaced), and D98's
  offer rule is unchanged (a read-only server offers `web_wait_flag` only with `--allow-evaluate`); `PlanPolicy` refuses
  a flagged primitive as it refuses the named set. D51's recorded rationale ("the one primitive that runs caller script
  is `web.evaluate`") was wrong on its face, which is what B21 Q5 was decided on. Built by **FX-web** (the six steps of
  `dispositions.md` S42: protocol, agent-api, `WebPrimitives`, `Dispatcher`, `CdpWebPath`, `ToolProjection`,
  `McpCommand`'s help). Alternatives rejected: `.mutating()` on `web.waitFlag` (removes it from `readOnly(true)`,
  reversing D98, and makes the step protocol settle and recover after a wait); the controller-only named set (a
  third-party adapter or a later primitive would be missed, map 5 §8 Q11).
- **D157 (= P6-SEC-2, S56; widens D101, B24): one session budget per MCP server bounds every model call made on a
  client's behalf.** The MCP server owns one `SESSION` `BudgetMeter` from `budget.sessionUsd`, shared with the
  `skillroy_session_ask` actor (`ServerOptions.sessionMeter`; `SessionServices.open` takes the meter); `skillroy_resolve`
  and the tier (b) slots of `skillroy_scenario_run` are metered on it as `SessionService.resolve` is: one tier (b)
  estimate per slot whose strategy names a model is reserved first, the run is refused `BUDGET` before any model call
  when the meter has no room or is exhausted (D74), the run executes under a cap of the session's room keyed by the
  server's ledger, the entries it added are settled afterwards and a `budget` record journaled; `--session-budget`'s
  help says it bounds the three together. Built by **FX-mcp**. Alternatives rejected: a run on the scenario budget
  only (the build's behaviour: `scenarioUsd` times the client's patience); a per-run cap without settlement (the
  session would not learn what a run spent).
- **D158 (= P6-SEC-3, S57, S59, S62; amends D96, B24, and P5 §12.4's host text): a client origin that never confirms,
  and the verb gate at call time.** `Origin.Kind` gains `CLIENT` ("an MCP client, often a model"; `Origin.client(String
  by)`, read by `fromJson`), which `ExecutorSession.confirms(origin)` keeps for `PERSON` only, so a `CLIENT` origin's
  `confirm` and `_meta` are stripped from its arguments and from every call a verb compiles to, and a verb's own
  `confirm: true` fails `CONFIRM_REQUIRED`; the MCP server's origin is `client(options.clientLabel())`
  (`ServerOptions.clientLabel`, `McpCommand --client <label>`, default `mcp`; `author` passes `--client planner`);
  `JournalExport.originLabel` writes `client <label>`. A pack verb called through the MCP server **or the session
  service** is checked **at call time** on the calls it compiles to with the client's or the model's real arguments
  (`ExecutorSession.compile(verb, args)`, a seam that compiles without running or journaling; `PlanPolicy.packRefusals`
  over the D96 pack-call and argument rules, shared with `refusals(plan)`; the session service's `CallPolicy` in
  `skillroy-inference`, which `PlanPolicy.never`/`arguments` delegate to), a refusal being a tool error naming each
  primitive before the first call runs; the start-time probes decide only the catalogue's `withheldVerbs`. Built by
  **FX-mcp** (with the `Origin` and `ExecutorSession.compile` seams of FX-scenario, `JournalExport` one line, and
  FX-inference's `CallPolicy` for S62). Alternatives rejected: keeping `person("mcp")` and widening the probes (no finite
  set of probes covers a verb whose calls depend on its arguments); stripping `confirm` only for the planner's server
  (every client is often a model).
- **D159 (= P6-SEC-4, S58; amends D76, B23 Q7, and widens D84, B23 Q15, to MCP): a capture's bytes reach an MCP
  client only under the inference settings' `images: true`.** `SkillroyMcpServer.registerImage` writes
  `<out>/mcp/<n>.png` as today and, while images are off (null settings count as `false`), registers a `text/plain`
  resource `skillroy://mcp/<n>.png.txt` naming the written file and the opt-in ("capture written to <path>; served over
  MCP only with `inference.yaml` `images: true` (a capture is personal data until masked, design §3.10)") and returns
  a `ResourceLink` to it; `registerRun` lists `failure/*.png` only when images are on; `author`'s server runs
  `--inference none`, so it never serves them. **There is no `--serve-captures` flag** (the superseded D150). Built by
  **FX-mcp**. Alternatives rejected: a separate flag (a second switch for the one question D84 already answers);
  serving as today (a capture is personal data until masked, and the client is often a model).

---

## 13. Tests and acceptance

### 13.1 Without a display (unit and module tests; the normal build; `mvn -q verify`)

1. **`skillroy-agent-spy`**: `SpyTest`: no-op without a handler (counters), set-once (`install` false while held),
   identity-released `uninstall`, `held()`, a throwing handler counted and swallowed, the weak slot cleared when the
   handler is collected (a `WeakReference` and `System.gc()` in the test only), `stub` round trip; a class-file scan
   over the module's `target/classes` that every `CONSTANT_Class` reference is under `java/` (the jar itself is
   covered by `HomeConstantPoolIT`).
2. **`skillroy-agent-bytebuddy`**: `ShadedContentIT` (§2.1; an IT, since the shaded jar exists only after `package`).
3. **`skillroy-agent-hooks`** (surefire with the test agent of §2.3, so an `Instrumentation` exists; the spy module
   excluded from the test class path and `hooks.spyJar` passed as the reactor's jar; on the `-Djvm` Java 8 leg too;
   the toy classes in `ai.skillroy.testjava.fixture.hooks`): the `Nexus` measure (after the first `AgentBuilder` the
   system loader defines no class of the relocated prefix and the bootstrap loader no `ByteBuddyAccessor`: a snapshot
   through the test agent's `getInitiatedClasses`); §3.3's probe items one by one on fixture classes (a protected
   package by name, using a class of the hooks module itself; a class defined by the test's own child-first loader
   whose class is under `ai.skillroy.testjava.agent.`, refused by loader; an unloaded class; an unmodifiable one; a
   class above each bound; a missing descriptor with `declared` filled; a native method; a method signature naming a
   missing type; the strict-delegation loader fixture refused with the reason, **in a fresh JVM** of its own, because
   the check is decided after the append); the spy jar's content check (a jar with an extra entry refused, a jar whose class entry references a type outside
   `java/` refused); install, fire and remove on a toy class in the test's own loader and in a child-first `URLClassLoader`
   (two ledger entries for one name when `loader` is absent); the three modes with their events (payload shapes,
   `durationNs`, `thrown`, `stubbed`); `CAPTURE`'s encoding (every scalar kind, a list of 101 elements cut, a map with
   a secret-looking key, a `secretArgs` index, `secretResult`, a `MaskPolicy` applied to a numeric scalar and to a
   string, a `MaskPolicy` that throws counted and the hook `ERROR("mask policy threw")`, an application object as a
   handle, the input budget: a value of a million characters stops the encoder at 16 KiB with `truncated`); re-entrancy
   (a hooked `toString` called from the encoding does not recurse); `hook.stub` conversions and refusals (every
   primitive return type, `null` for a primitive refused, an enum, `BigDecimal`, a `void` method skip-only) and the
   `hooks.stubbed` event by call and by SPI; `hook.health`'s `stubs` and `armed`; idempotency by hash, `HOOK_CONFLICT`,
   `replace`; the restore's outcomes with a second transformer (BC1), a redefinition through the test agent's
   `Instrumentation` (BC3/BC4: on Java 8 the bytes equal and the foreign count is 1) and a planted `Spy` reference
   (the `HOOK_VERIFY_FAILED` path, a transformer the test keeps registered that references `Spy`: the class keeps its
   ledger entry and the handler stays installed); the detach budget (a slow retransform simulated by a transformer
   that sleeps leaves inert advice, the handler in the slot, the WARN naming the class, and no exception; a fresh
   engine's probe then answers `UNAVAILABLE("Spy slot held by a disarmed handler…")`; **this case and the planted-reference
   case each run in a fresh JVM of their own**, since by design they leave the slot held for the JVM's life, D117); after
   a complete `uninstall` and
   a `System.gc()` the module loader is collectable **while application threads that ran hooked methods are alive**;
   `hook.remove` and `hook.health` served while `hook.engine` is `UNAVAILABLE`; a `hooks.*` option out of range makes
   the install a `FAILED` record; health states; the SPI through `AgentContext.hooks()`, `isSecretKey` and
   `redactText` through the core's contexts (in `skillroy-agent-core`'s tests: the `redact` option and a `RedactionKeys`
   pattern honoured); `optionalModules` in `ModuleLoader` (loaded only when named, `FAILED` records for an unknown
   stem and a jar without a service file; in `skillroy-agent-core`'s tests); the loader rules (`AgentClassLoaderTest`,
   `ModuleClassLoaderTest`: the spy package parent-first).
4. **`skillroy-protocol`**: the hook wire types' JSON round trips and refusals (`secretResult`, `armed`, `INSTALLED`);
   `HookSpec.hash()` stable under key order; the new codes and topics in `ErrorCodes.name` and `Topics`.
5. **`skillroy-scenario`**: `HookCatalogues.read` on the sample's catalogue and on a corpus of malformed ones (every
   rule of §5.1 with its line) and its warnings; the hash; `builder(base).replace/add/disable`;
   `HookCatalogues.overlay`; `HookInstaller` against `FakeAgentConnection` for every branch of §5.3 (`OFF`, `AUTO`
   without engine, `ON` refused, `alreadyInstalled`, same id other hash replaced, other id `ENVIRONMENT`, fallbacks
   recorded, an armed stub `ENVIRONMENT` under `replay` and recorded under `live`, `HOOK_VERIFY_FAILED` mapped,
   `otherAgents` recorded); `RunOptions` with the two new components and the old constructors; the `hooks` fact root
   (a list, the filter path, `count` and `last` from `hook.events {since: stepsSinceSeq}` so that a fire before the
   first step is not counted, the empty list on `METHOD_NOT_FOUND`, `CAPABILITY_UNAVAILABLE` and `NOT_READY`);
   `capabilities.json` and its record; `CapabilityMatrix.toJson/hash`; `DomainPackOverlay` discovery (an overlay
   applied, chained by id, a missing base refused), `DelegatingDomainPack` forwarding every member (a reflection test
   that no abstract member is left unforwarded), `PackSettings` through every context; the `RunObserver` seam with a
   recording observer (every hook point called once per record type) and `NONE`'s cost (no allocation per call beyond
   the records); `BindListener` durations; the `test-jar` (§10.6) built.
6. **The sample pack and the branch overlay**: the catalogue resource read and valid; `settings()` defaults; the
   overlay's `settings`, resolver, allow-list and catalogue; the branch edition's `LendingSchema`, `SeedData`,
   `AccessControl` and `Engine` paths in the application's tests (`ReturnCeilings` called by the return path in
   `branch`, `returnLimit` by the return path in `standard`, **and `AccessControl.check` reaching `returnLimit` in
   neither edition**); `app.state.edition` in the agent module's test; `sign.on.checked` enabled in a test: the
   password index is `<redacted>` in the event (§6.3).
7. **The scenarios module (headless)**: `PlansUpToDateTest` and `BranchPlansUpToDateTest`; `BindingsUpToDateTest`
   covering E and `branch-a`; `ScenarioSchemaCommittedTest` expecting 18 files; scenario E on the headless desk
   (`HeadlessDesk`) with a scripted matrix: `PASS` when `hook.limit.applied` is `OK` and the fact root answers from
   the engine's own return-path calls, `DEGRADED` when the capability is absent; the golden normalisation
   (`CapabilityGoldenTest`: strict ids, states and strategy, reason by presence, the allow-list, the diff text).
8. **The CLI**: `skillroy hooks status|install|remove` against `FakeAgent` (the armed stubs and `otherAgents` printed);
   `--hooks` parsing; `BatLauncherTest` (§9.1); `AuthorCommand`'s `PATHEXT` lookup, the plain `cmd.exe /c` form, and
   the refusal of a launcher path or argument with a space or a `%` (§9.2), with a fake `PATH`, `PATHEXT` and launcher
   path; `--telemetry` and `TelemetryArgs` (§10.3's rules, exit 64 texts naming no value).
9. **`SecureFiles`** (§9.3): the public members on the POSIX test host (a unit test per site through the seam the site
   takes), the role in the refusal text, and a `NONE` file system (the zip file system of `SecureFilesRulesTest`)
   refused with the remedy, nothing created.
10. **`skillroy-telemetry`**: §10.6's unit tests, the transport stub, the class-loading probe, the secret scan, the
    binding-value check, the `minimal` list.
11. **`skillroy-agent-byteman`** (if BS14 passed): §7.6's unit tests.
12. **`skillroy-mcp`**: the projection never offers `hook.probe`, `hook.install`, `hook.remove`, `hook.stub`,
    `rule.check`, `rule.submit` or `rule.unload` under any policy, offers `hook.health`, `hook.events` and `rule.list`
    as reads, and `PlanPolicy` refuses the seven; the inbound `traceparent`. The gates of D156-D159 are FX's tests, in
    `dispositions.md`.
13. **The agent home ITs** (`skillroy-agent-dist`, failsafe, no display): `DistributionContentIT` with the new list
    and the two new rules (§2.5); `HomeConstantPoolIT` (§2.5, `com/sun/jna/` included); `OptionalModulesIT`: a child
    JVM on the target Java with `-verbose:class` loads the default home and the log names no class of
    `ai.skillroy.testjava.agent.hooks`, of the relocated prefix or of `ai.skillroy.testjava.agent.spy`, while with
    `optionalModules=skillroy-agent-hooks` the module is `INSTALLED` and `hook.engine` is `OK` in `JAVAAGENT`;
    `SharedLoadersIT`: in that JVM, after an install and a remove through the primitives, a **probe agent of the test's
    own** (`-javaagent:` on the child, as BS3's probe; the engine lists it in `otherAgents`, which this test expects)
    snapshots through `Instrumentation.getInitiatedClasses` the bootstrap loader, the system loader and, on Java 9 and
    later, the platform loader (on Java 8 the extension loader) and finds **nothing of the agent's but `Spy` and its
    two nested types** in the bootstrap loader (BS3 RESULT §9 fact 3 made a test); `BytemanIT` (§7.6).

### 13.2 End to end (the Linux image; `ci/run-in-linux.sh … -Dskillroy.headless=false`, Java 8 and Java 25 targets)

Extends P1 §10, P2 §14.2, P3 §11.2, P4 §12.2 and P5 §15.2 inside `LendingEndToEndIT`, `InferenceEndToEndIT` and new
classes. **The hooks leg** is a new `LendingTarget.Mode`, **`JAVAAGENT_HOOKS("javaagent-hooks", LoadMode.JAVAAGENT)`**:
its launch adds `optionalModules=skillroy-agent-hooks` to the agent options (`skillroy-agent-hooks,skillroy-agent-byteman`
and `byteman.jar=<path>`, the path read as §7.6 reads it from this module's test class path, once the Byteman module
exists: BW adds both after CP's mode has landed), `LendingTarget.attach()` reuses the launch's agent options
(not `discoveryDir` alone), and its slug names the target log (`it-logs/javaagent-hooks.log`), the run directories and
the golden (`javaagent-hooks.json`).

1. **`JavaAgentHooks`**, a nested class of `LendingEndToEndIT` on that mode, runs the checks of `LoadModeChecks` with
   `HookChecks` woven in by `@Order` (the values between the tens, §8.1): the module lists of checks 1 and 15 gain
   `hooks` (and `byteman`) in this mode, `jvm.instrumentation`
   and `hook.engine` are `OK`; **after check 1c and before check 5** (so that the committed scenarios of check 5 and
   the runs of item 4 see the catalogue installed, which under `AUTO` they would otherwise install themselves at step
   0b): `hook.probe` of the pack's catalogue answers every entry `OK` but `sign.on.checked` (absent, disabled) with
   `otherAgents` empty; `hook.install` installs four hooks in under 60 s with `touched` naming `AccessControl`,
   `WaiverEngine` and `Deposits` once each; the matrix shows `hook.limit.applied`, `hook.access.checked`,
   `hook.waiver.evaluated`, `hook.line.amount` `OK(advice)`; a return line makes `hook.limit.applied` fire on the
   desk's engine thread `LendingEngine` (`Engine.THREAD_NAME`, the application's own thread) with `args.0` the staff
   code and `returned` the limit, and `hook.health` shows `FIRING` with `last`; a `DUE` makes `hook.waiver.evaluated`
   carry a list of handles and no application field; `hook.stub {id: line.amount, value: "1.00"}` without `confirm` is
   `CONFIRM_REQUIRED`, with it `hook.health` lists the stub, the next scanned line shows `amount 1.00` in
   `lending.loan`, `clear: true` restores the computation and empties `stubs`; the same install again answers
   `alreadyInstalled`; a second catalogue id is `HOOK_CONFLICT`; **after check 14** the `hook.remove {}` demonstration
   restores every class with `verified: OK` and `foreignEvents 0` and the matrix drops the `hook.<id>` ids; **in checks
   16 and 17** the re-attach carries the same options and installs again without a second append (the target's log
   holds **one** CDS line on Java 25 and none on Java 8; `hook.probe.spy.onBootPath` true before the install); check
   19 stays last. The target's log is scanned for any line from Byte Buddy, `sun.misc.Unsafe` or an exception with
   `skillroy` in its trace: none.
2. **The exit criterion's restore**: item 1's `hook.remove` on `AccessControl.check` and `returnLimit` (the methods
   BS3 and E hook) reports `OK` with `bytesBefore == bytesAfter` and `foreignEvents 0`, on the Java 8 and the Java 25
   target.
3. **The `STUB` demonstration** is item 1's `line.amount` steps; nothing committed arms a stub.
4. **Scenarios with hooks on and off**: `LendingEndToEndIT` gains `ResolutionJavaAgentHooksSeedA` and `…SeedB`
   (`ResolutionChecks` on `Mode.JAVAAGENT_HOOKS`) and `InferenceEndToEndIT` gains `JavaAgentHooksSeedA` and `…SeedB`
   for D; `ResolutionChecks` gains E's replay check. With hooks installed (the hooks legs) A, B, C, D and E are `PASS`
   (E's three guarded expectations evaluated, `skippedExpectations` empty) and each run's `hooks` record says
   `installed` or `alreadyInstalled`; in every other leg (the hooks-off half: `AUTO`, no `hook.engine`) A to D are
   `PASS` and E is `DEGRADED` with three skipped expectations naming `hook.limit.applied`; the same YAML, the same
   committed plans and bindings; `capabilities.json` present in every run's directory and its hash equal across the
   runs of one leg; check 14's seven ids with E's last status as §6.3 says.
5. **On a JDK 25 target through `JAVAAGENT`**: item 4's hooks-on and hooks-off runs on the Java 25 leg.
6. **JEP 451 and the golden matrix**: check 1b (§8.1) in every mode on both legs, the `ATTACH` leg's positive
   control included; check 1c (§8.2) against the committed goldens in every mode on both legs; `PerTargetJavaChecks`'s
   live diff on the Java 8 leg.
7. **Byteman on the desk** (if BS14 passed): in the hooks leg, `rule.check` of a rule on `AccessControl.returnLimit`
   (`AT EXIT`, `DO fired($0, $!)`) is `ok` with `typeChecked: false` before any submit; `rule.submit` with `confirm`
   loads Byteman and installs it; a return line publishes `rule.fired` with two bindings (a handle and the limit);
   `rule.list` shows it; `rule.unload` restores with `verified: OK`; a rule naming `killJVM` is `RULE_REJECTED`; the
   child's listening sockets (the `/proc/<pid>/fd`-mapped set of §7.6) before the first `rule.submit` and after
   `rule.unload` are equal and hold exactly the agent's port.
8. **The variant** (`BranchEndToEndIT`, seed A, branch edition, the overlay jar on the test class path,
   `JAVAAGENT_HOOKS`): §11.4's acceptance, each half on a freshly launched desk (A, B and E `PASS` with the overlay; E
   with the base pack alone `DEGRADED` with hooks off and `FAIL` / `ASSERTION` with hooks on,
   `hooks[id=limit.applied].count = 0` as the actual).
9. **Telemetry end to end** (`TelemetryEndToEndIT` in the scenarios module, TL's, with the JUnit-glue stream test of
   §10.6 beside it): one `skillroy run --telemetry file --out <dir>` of scenario A against the desk in the
   image writes `spans.jsonl` with the `skillroy run`, `scenario`, `bind`, `step`, `call`, `agent` and `await` spans,
   the `traceId` in `run-start` and `--json`, and the secret scan passes over it; one `--telemetry otlp` run against
   the loopback stub started by the test delivers its batches.
10. **The secret and A34 scans** of P2 §14.2 and P5 §15.2 item 8 cover the new files: `capabilities.json`,
    `spans.jsonl`, the `hooks` records, `failure/events.json` with `CAPTURE` payloads (the staff password of
    `sign.on.checked`, when a test enables it, appears nowhere).

### 13.3 Windows (waits for the Windows host; a written procedure, not run in this phase)

`ci/run-on-windows.cmd` and the list of §9.5 (the planner through a launcher path with a space among them), run twice
without a flake once the host exists, with the results in a Windows row of the matrix table and the "not verified on
Windows" marks removed from `README.md`, `SECURITY.md`, `threat-model.md` §8, P1 §2.5 and `../TODO.md`. Until then
the marks stay and §14 says so.

### 13.4 Live and opt-in (not required)

A recording of scenario D for the branch edition (`inference-record` profile, the owner's key) so that D could join
the variant's runs; `--telemetry otlp` against a real OpenTelemetry Collector on the operator's machine, following the
procedure in `README.md`'s telemetry paragraph. Neither is an exit criterion.

---

## 14. Exit criteria

The roadmap's row 6 (design §8), item by item, and what this contract adds:

| Criterion | Met by | This phase |
|---|---|---|
| a hook is installed and removed on the sample lending desk with byte-identical restore | §13.2 items 1 and 2 (the Java 8 and the Java 25 target, `JAVAAGENT`) | **can be met** |
| the sample scenarios pass unchanged with hooks enabled and with hooks disabled | §13.2 item 4: A to D `PASS` both ways from one YAML, with E as the one designed exception: `PASS` with hooks, `DEGRADED` (never `FAIL`; reported as skipped) without, by B14 | **can be met** |
| … and on a JDK 25 target loaded through `JAVAAGENT` | §13.2 item 5 | **can be met** |
| on JDK 25 `JAVAAGENT` loads with no JEP 451 warning and the capability matrix equals the JDK 8 one | §13.2 item 6 (check 1b with the `ATTACH` control; the goldens in both legs and the live diff) | **can be met** |
| on Windows the agent loads through `JAVAAGENT` as the logged-on user, with `SEMANTIC`, `ACTION`, `COMPONENT`, `DISPATCH` and `PAINT` verified and `ROBOT` excluded | §13.3, when the host exists | **cannot be met this phase**: no Windows host (`../TODO.md`, Walls). The path is specified in full (§9), the Linux-testable parts are built and tested, every host item is marked "waits for the Windows host", and the TODO's exit item is split into the part this phase meets and the part that waits |
| the repeated security review is signed off | §12.6: pass 1 fixed by the build (S35-S62), pass 2 after the build, the record, the dispositions, **the owner's signature (B28)** | **the owner's**: the phase delivers everything up to the signature |
| *(added)* Byteman: a rule is checked, submitted, fired and unloaded on the desk on JDK 8 and JDK 25 with byte-identical restore, a forbidden rule refused, no listener opened | §13.2 item 7, §13.1 item 13's `BytemanIT` | **conditional on BS14** (§7.2): met if the spike passes; "specified only" if it fails |
| *(added)* OTel: a run exports its spans to a loopback OTLP stub and to the file format, with nothing but allow-listed attributes and no secret or binding value, and a default run loads no OpenTelemetry class | §13.1 item 10, §13.2 item 9 | **can be met** |
| *(added)* the overlay: scenarios A, B and E pass unchanged on the branch edition with the overlay, and E shows the overlay's catalogue is needed | §13.2 item 8 | **can be met** |
| *(added)* the default path loads no third-party class, and the shared loaders hold nothing of the agent's but `Spy` | §13.1 item 13 (`OptionalModulesIT`, `SharedLoadersIT`, `HomeConstantPoolIT`) | **can be met** |

---

## 15. Changes to the earlier contracts and the design overview

### 15.1 Marks into the earlier contracts and the overview

Written into the files named when the build starts, marked "(Phase 6, specified 2026-10-09, not yet built; P6 §n)",
and "(Phase 6, built …; P6 §n)" once built; the security fixes' passages carry their own mark (§12.2). Each package
writes its notes under its tag and never rewrites another package's passage; the orchestrator merges by tag (the
session notes' work-package list):

| Contract and section | Change |
|---|---|
| P1 §2.1 agent home | `skillroy-agent-spy.jar` at the root, the `optional/` directory and its three jars; the `DistributionContentIT` list and its two new rules (§2.1, §2.5) |
| P1 §2.2 class loaders | `ai.skillroy.testjava.agent.spy.` parent-first in the module loader and excluded from the agent loader's child-first rule; the bootstrap path holds `Spy` in hooks mode; the spy package exists in one jar |
| P1 §2.4 options | `optionalModules`, `hooks.*`, `byteman.jar` (§3.7); the `;` separator rule unchanged and cited |
| P1 §2.5 discovery | the Windows paragraph: same user, same logon session, same integrity level, matching bitness; cross-session attach unsupported (§9.4) |
| P1 §3 `skillroy-protocol` | the package `…protocol.hook`; `ErrorCodes.HOOK_CONFLICT`, `RULE_REJECTED`; the `Topics` constants of §4.4; `PrimitiveDescriptor.script` (D156, FX-web) |
| P1 §4 `skillroy-agent-api` | the SPI table gains `HookEngine` (§3.6); `AgentContext.hooks()`, `isSecretKey`, `redactText`; the package `…api.instrument`; **`MaskPolicy`'s thread rule** (§4.3: also on the application thread for a `CAPTURE` hook or a rule's bindings, never blocking); `PrimitiveSpec.Builder.script()` (D156) |
| P1 §5 topics and dispatch | `hook.*`, `hooks.*`, `rule.fired`, `rules.changed` topics; the `optionalModules` source of module discovery; gate 2 "mutating or `script`" (D156) |
| P1 §7 `AgentClient`, `SecureFiles` | `_meta.traceparent` is sent when the observer supplies one (`CallOptions.traceparent`); "and nothing else" amended (§10.2); `SecureFiles` becomes public with the members of §9.3 |
| P1 §8.3 base fact roots | `hooks`, a list keyed by id and scoped to the run's steps (§6.3) |
| P1 §8.5 run sequence and outputs | step 0b (hooks, the armed-stub rule under `replay`); `stepsSinceSeq`; `capabilities.json`; the records `hooks` (with `armed`, `otherAgents`), `capabilities`, `traceId` on `run-start`; `spans.jsonl` under `--out`'s `telemetry/` |
| P1 §8.6 CLI and JUnit | the command `hooks`; `--hooks`, `--telemetry`, `--telemetry-endpoint`, `--telemetry-attributes`, `--telemetry-file`; `skillroy.hooks`, `skillroy.telemetry.*`; `bin/skillroy.bat` (§6.1, §6.2, §9.1, §10.3) |
| P1 §10 acceptance | checks 1b and 1c with the renumbered `@Order`; check 13's `DISPATCH` click; check 14's seven ids; the hooks leg `JAVAAGENT_HOOKS`, `JavaAgentHooks` and `HookChecks` (§8, §9.5, §13.2) |
| P2 §6.1 `DomainPack` | `hooks()`, `settings()`; `DomainPackOverlay`, `DelegatingDomainPack`, `PackSettings` (§5.2, §11.1) |
| P2 §6.4, §6.6 contexts | `settings()` on `ResolveContext`, `VerifyContext`, `FingerprintContext` and `PreflightCheck.PreflightContext`; `BindListener` records gain `durationMs` (`resolve`, `oracle`, `fingerprint`) |
| P2 §11.3 records | `hooks`, `capabilities`, `traceId` on `run-start` (when telemetry is on) |
| P2 §11.4 never written | a span attribute, event or span name; the environment variables a telemetry header names; a `CAPTURE` value of a `secretArgs` index or of a `secretResult` method |
| P2 §12 CLI and JUnit | as P1 §8.6 above |
| P3 §4, P5 §13.2 `plan.json` | **unchanged**: version 5 stays the written version; nothing of hooks, telemetry or the overlay enters a plan beyond `packVersion`'s composite form (§5.2, §11.1) |
| P4 §4.2 `web.*` primitives | `web.evaluate` and `web.waitFlag` carry `script: true` (D156, FX-web) |
| P4 §6.1 `ExecutorSession.open` | installs the pack's hooks under `RunOptions.hooks` and writes `capabilities.json` (§5.3, §6.4) |
| P4 §8.3 never written | the telemetry rows above |
| P4 D59 trace directory | through `SecureFiles.createOwnerOnlyTempDirectory` (§9.3) |
| P5 §9.3, §9.4 MCP | `hook.health`, `hook.events` and `rule.list` offered as reads; "never offered: hook installation" widened to every other `hook.*` and `rule.*` primitive, named in an explicit set (§4.2); `--hooks`; the inbound `traceparent`; the projection decides script primitives by the flag with the named set as fallback, captures under `images: true`, the `CLIENT` origin and `--client` (D156-D159, FX) |
| P5 §10.2 planner | `PATHEXT`; the plain `cmd.exe /c` form and its refusals; `--client planner` (D158); the work directory and the configuration through `SecureFiles` (§9.2, §9.3) |
| P5 §12.2-§12.4 `ExecutorSession`, the session | the observer's `sessionStep` and `sessionTurn`; hooks at `open`; `Origin.Kind.CLIENT` (D158) |
| P5 §13.4 CLI | the options above; `mcp --client` |
| P5 §14.1 the pack | version 0.5.0, the catalogue, `settings()`, the public surface; the sample application's `AccessControl.check` change and the branch edition; the agent module's `app.state.edition` (§6.3, §11.3) |
| design §1.1 stack | Byte Buddy 1.18.14 shaded in the optional module behind `optionalModules`; Byteman "in process, no listener"; OpenTelemetry pinned at build time, controller only |
| design §2.1 modules | the rows `skillroy-agent-spy`, `skillroy-agent-hooks`, `skillroy-agent-byteman` as specified here (the Byteman row's "listener on 127.0.0.1" struck); `skillroy-agent-bytebuddy`, `skillroy-agent-byteman-sys`; `skillroy-agent-dist`'s `optional/` |
| design §2.2 steps 1, 5, 6 | self-attach still not built (`hook.engine` `UNAVAILABLE` in `EMBEDDED`); the matrix archived per run (built); detach restores and verifies with the foreign-event count and keeps the slot held while an advice exists (built); the disable flag file still not built; the Windows paragraph of §9.4; the pid fallback dropped |
| design §2.3, §2.4 | `hook.stub` and `hook.events` beside the catalogue's names, `hook.waitFor` reserved and served by `sync.await`; `rule.check`, `rule.submit`, `rule.unload`, `rule.list`; the error codes |
| design §2.6 hook policy | the catalogue format of §5.1 as built; `reflect` fallbacks deferred; the size limit "256 KiB or 2,000 methods (BS3)"; the `Nexus` measure and the one append; the deny-list by name and loader |
| design §3.1, §3.4, §3.6 | `skillroy-telemetry` as an optional-at-run-time module in the CLI zip; the fingerprint's list loses "hook-catalogue version"; the overlay SPI as §11.1 |
| design §3.4, §5 | "a model's request is never the confirmation" now holds for the planner through the `CLIENT` origin (D158) |
| design §6 CI | artifacts: `capabilities.json`, `spans.jsonl`; the `--telemetry` paragraph |
| design §7 layout | the new modules |
| design §8 roadmap | row 6's status and the Windows criterion's split (§14) |
| design §9 risks | the row "Licensing of bytecode changes…" gains the shaded-copy facts; a new row for the CDS line and the other-agents limit |
| design §11 | B26, B27, B28 |
| P1, P2, P4, P5, as pass 1's `dispositions.md` names them | the amendments A40 onwards that fix the 28 confirmed findings S35-S62 (§12.2), written in by FX and marked "(Phase 6 security fix S<n>, A<m>, built …)"; the four that change decisions are D156-D159 above |

### 15.2 Open SPI and DSL gaps: what Phase 6 takes

Numbers are those of `../TODO.md`, "Open SPI and DSL gaps". P5 §16.2 recorded five further gaps without numbers (a
verb without an `argsSchema`, `data_query`'s allow-list, the planner's `claude` precondition, tier (c) at bind time,
the session's base primitives off by default); they are not in the table, and if they enter it they take numbers
after 57.

| # | Gap | In Phase 6 |
|---|---|---|
| 31 | `ROBOT` type mode, `OPTION_VALUE` dismissal, `swing.events` | unchanged: nothing of Phase 6 needs them; moved to "later" |
| 32 | Hook catalogue: an `enabled` flag per hook | closed (specified): §5.1, the sample's `sign.on.checked` |
| 33 | Third-party isolation when the host ships an older copy of the same library | closed (specified): §2.1's relocation and BS3 RESULT §9 fact 10; `ShadedContentIT` and `HomeConstantPoolIT` keep it closed |
| 49 | `LendableItem` cannot say "an item that needs no induction" | taken if the build has room: pack 0.5.0 regenerates every plan anyway (§6.3) |

New, for the gaps table, numbered **50-57**: **(50)** a target behind a strict-delegation loader cannot be hooked
(BS3 RESULT §9 facts 6 and 7; the `java.*` placement of `Spy` would serve it and is rejected, D116); **(51)** another
bytecode agent's change registered before the first retransformation, or dropped before it, is invisible to the
byte check and the event ledger (BS3 RESULT §9 fact 14; recorded as `otherAgents`, D154); **(52)** on Java 25 with
class data sharing on, the one bootstrap append prints a VM warning on the host's stderr and ends sharing of
non-boot classes (fact 8; §17 Q14); **(53)** the retransformation pause was measured with a spinning thread and the
safepoint logs, not on a painting event-dispatch thread of a real application (BS3 RESULT §10): to measure on the
desk with a UI; **(54)** the pre-filter's effect on the class-loading cost of a registered transformer (fact 20) and
the `makeRaw()` route itself are specified and not yet measured against `installOn`; **(55)** `RpcResult.startedAt`
for an exact agent span (D142); **(56)** OpenTelemetry metrics and logs (§10.5); **(57)** a Byteman the host loaded
on its own command line cannot be reused by the module (§7.4).

---

## 16. Decisions taken in this contract

D114-D159 were taken by this contract (D149 and D150 as first written are superseded by D156 and D159 and kept as
struck rows, so that the numbering stays stable). **During the build an implementer records a new decision as
`D-<tag>-<n>` in its own notes; the orchestrator numbers them D160 onwards at integration, in landing order, and
writes them into this table** (B27).

| # | Decision | Alternatives rejected | Reason |
|---|---|---|---|
| D114 | The agent home gains `optional/`, never scanned, and the option `optionalModules` names the file stems to load as module jars; the hooks module (and the Byteman modules) ship there and load only when named (§2.1) | the hooks jar in `modules/` with a manifest flag the core reads; a separate download; the `modules=` path option alone | the default path must load no third-party class (B4, design §2.6) while one home serves everyone; a flag in a manifest makes the core open the jar; a separate download is the §17 Q1 veto; a bare path option is what a launcher would have to know by heart |
| D115 | Byte Buddy is relocated in a shading module of its own, `skillroy-agent-bytebuddy` (`net.bytebuddy` → `…agent.hooks.shaded.bytebuddy`, `com.sun.jna` → `…shaded.absent.jna`; `META-INF/versions/**`, `module-info.class`, the dependency's `META-INF/LICENSE`, `NOTICE` and `licenses/**` dropped; the notices kept once under `META-INF/third-party/`), merged unrelocated into the one hooks jar, whose dependency on it excludes `net.bytebuddy:byte-buddy` and whose enforcer bans `net.bytebuddy:*`; maven-shade-plugin 3.6.2 or newer; the relocation checked by `ShadedContentIT` (failsafe) and `HomeConstantPoolIT` (§2.1) | shading inside the hooks module; keeping the Multi-Release entries; keeping the dependency's `META-INF/LICENSE` in place; a third relocation for the dangling `org/objectweb/asm` entries; a surefire test of the shaded jar | surefire runs before `package`, so only a separate module lets every hooks test run on the relocated bytes, and only a failsafe IT can read the shaded jar; the versioned entries keep unrelocated paths and are dead weight below Java 27 (BS3 RESULT §7); `DistributionContentIT` compares `META-INF/LICENSE` with the root file; the shade rebuilds every constant pool, so the dangling entries do not survive; within one reactor Maven resolves the shading module's original dependencies, so the exclusion is what keeps unrelocated classes out of the hooks jar; a missing relocation fails silently (BS3 RESULT §9 fact 10) |
| D116 | `Spy` lives in `skillroy-agent-spy.jar` at the home's root, package `ai.skillroy.testjava.agent.spy`, parent-first in both agent loaders and in no other jar, appended to the bootstrap search path at most once per JVM at the first install after a content check of the jar (its entries, and their class references all under `java/`); a target whose loader cannot see it is refused; the `java.*` placement is rejected (§2.1, §3.2, §3.3) | a jar under `lib/`; a `java.*` package (Arthas's placement); a system class path placement; appending at module install; appending whatever jar `hooks.spyJar` names | `lib/` defines `Spy` twice (attach §F25); a `java.*` class can be defined by no loader but the bootstrap one, never sits on a class path and squats on the JDK's namespace (BS3 RESULT §9 fact 7); a system placement is invisible to loaders whose parent chain skips the system loader; every append is permanent and costs a VM warning on Java 25 (fact 8), so it happens when hooks are wanted and once; a bootstrap append is the agent's most privileged act, so the jar's content is checked |
| D117 | `Spy`'s slot is set-once, weakly held as a last resort, released only by identity, and **never free while any class carries an advice**: a detach that runs out of budget keeps the disarmed handler installed and the transformer registered, and a later agent is refused rather than given a free slot (§3.1, §3.5) | a plain volatile field; a slot keyed by a random token the module holds; disarming by `Spy.uninstall` first (the first draft) | the bootstrap path is callable by every class in the JVM, so a writable field, or a free slot beside a live advice, lets any code read every hooked method's arguments and stub its return (map 5 §8 Q7); the weak slot frees itself only when nothing registered with the JVM still holds the handler, which the registered pre-filter does in a final field while any advice exists (a pinned loader would keep classes, not the handler; BS3 RESULT §9 fact 22's collectable loader is the complete-restore case); a token adds a secret to keep for one compare |
| D118 | The hook wire types live in `skillroy-protocol` (`…protocol.hook`), the `HookEngine` SPI in `skillroy-agent-api` (`…api.spi.hook`), found through `spi().first` or the default `AgentContext.hooks()`; `AgentContext` also gains the defaults `isSecretKey` and `redactText`, which the core's contexts implement over its `Redactor` (§3.6, §4.1) | `HookSpec` and `HookEngine` in `agent-api` with an abstract accessor (design §2.1); the hooks module re-implementing the core's redaction | wire types live in the protocol by rule (`CLAUDE.md`); the SPI registry already gives "if present" and withdrawal on uninstall; an abstract accessor breaks the four test fakes (and the core's two contexts) that implement `AgentContext`, a default one does not (the A20, A34 precedent); the core's `Redactor` is package-private and the one owner of the `redact` option and the `RedactionKeys` rule |
| D119 | `hook.install` is not mutating; `STUB` is armed through the separate primitive `hook.stub`, mutating and destructive; `hook.remove`, like `rule.unload`, is gated by nothing (§4.2, §7.3) | one `hook.install` the module gates itself by mode; a per-call mutating predicate on `PrimitiveSpec`; `rule.unload` mutating (the first draft) | the dispatcher's flags are per spec, so one primitive cannot be "mutating only in STUB mode" (P1 §5); the design's rule is that read-only mode disables `STUB` hooks, not observation; `/schema` then documents the rule and the controller's `confirm` plumbing (D89) applies for free; a stub changes behaviour for every user of the JVM, which is what `destructive` means here; a restore is never gated |
| D120 | Capabilities `hook.engine` (set or declared at module install; named by `hook.probe`, `hook.install`, `hook.stub` and `hook.events`, **not** by `hook.remove` and `hook.health`) and one `hook.<id>` per installed hook, declared at install with a health probe, withdrawn at remove; a spec that fails the probe is not declared (§4.2, §4.6) | one `hook.engine` only; declaring every catalogued id with `UNAVAILABLE`; every primitive behind `hook.engine` | hook-dependent expectations are `requires:` capabilities and guards by id (design §1.3); A18 makes declare and withdraw cheap; an absent id reads as not `OK`, and the controller's fallback record says why; a restore must stay reachable when a reprobe turns the engine `UNAVAILABLE` |
| D121 | The catalogue is a YAML resource of the pack (`META-INF/skillroy/hooks/<id>.yaml`), read by the controller with `StrictYaml`, typed as `HookCatalogue`, exposed through `DomainPack.hooks()`, and sent to the agent as JSON `HookSpec`s; nothing of it enters `plan.json` (§5) | the agent reading YAML; a catalogue resource of the agent module installed in `onTargetReady`; a Java-built catalogue only | the agent has no YAML reader and none may enter the target (P1 §3); catalogues belong to domain and tenant packs and the overlay is a `DomainPack` (design §2.6, §3.6), so the override must be where the overlay is; a YAML file is what a reviewer diffs; a plan compiled before Phase 6 must run unchanged |
| D122 | Restore and verification are one retransformation: the engine's recorder is registered ahead of the hook transformer at install (the first-transform bytes, every later transform event) and re-registered last at restore; the comparison is with the captured bytes, never the class file; the verification reports the byte comparison and the count of foreign transform events; the outcomes are `OK`, `DEGRADED("bytes differ…")`, `DEGRADED("foreign redefinition observed…")` and `HOOK_VERIFY_FAILED` on a `Spy` reference, a `FAILED` class keeping its entry and its disarmed handler (§3.5) | the byte check alone; comparing with the class file; behavioural verification only (`Spy` counters at zero) | BS3 RESULT §3, §9 facts 11-14: the JVM hands reconstituted bytes, "after equals first" holds almost by construction, and on Java 8 a restore reverts another agent's redefinition while the bytes compare equal, so the event count is the only witness there; a counter says nothing about bytes left behind |
| D123 | Health is computed on `hook.health` from the ledger, the registry and `Spy`'s counters; the four states `INSTALLED`, `FIRING`, `STALE`, `ERROR` have the meanings of §3.6; "armed" means a stub value only; no watchdog thread (§3.6) | a health thread; `ARMED` as the installed-not-fired state (the first draft) | the agent adds no thread it does not need (P1 §5); `agent.capabilities{reprobe}` already offers a refresh; one word with two meanings in the wire types misleads |
| D124 | A built-in deny-list **by name** (`java.`, `javax.`, `sun.`, `com.sun.`, `jdk.`, `org.jboss.byteman.`, `ai.skillroy.testjava.agent.`, `ai.skillroy.testjava.protocol.`) **and by loader** (the bootstrap, platform or extension loader, and any loader whose own class is under `ai.skillroy.testjava.agent.`), so that the target application's own packages, the sample's included, are hookable; the size limit **256 KiB or 2,000 methods**, also the byte bound of a retransformation batch of at most 8 classes; a ledger of at most 64 classes; the detach budget `hooks.detachBudgetMs` 8000 inside the core's 10 s; the same bounds on a Byteman rule's matched classes (§3.3, §3.5, §3.7, §7.3) | whatever the catalogue names; the repository prefix `ai.skillroy.testjava.` (the first draft, which refused the sample desk); a bytes-only limit; batches by count only; restoring within the core's bound without a budget | attach §C9's precedent; the agent protects itself by what it is (its loaders) and not by a name the sample shares; BS3 RESULT §4: the install cost follows the method count more than the bytes, and every retransformation is a stop-the-world pause (11 ms on Java 8 at the limit, 51 ms at 1.8 MiB), so a batch of eight classes at the limit must not be eight pauses in one; a stuck uninstall must never reach the host |
| D125 | `CAPTURE` values are encoded as scalars, bounded collections or weak handles, never through `toString()`; masked by the catalogue's `secretArgs` and `secretResult`, every `MaskPolicy` on every encoded scalar (`sourceId "hook.<id>"`, on the application's thread, a throwing policy counted and the value redacted) and the secret-key rule; the encoding budget of 16 KiB is counted on the input (§4.3) | publishing values as is (A34's rule for events of a call); a new masking SPI; rendering objects as text; strings-only masking and an output-only bound (the first draft) | a hooked sign-on method's argument is exactly what A34 protects on the wire and the agent cannot know it by name (map 5 §8 Q6); `MaskPolicy` is built and the sample's rule exists, and its row path masks every type; `toString()` runs application code on the application's thread and may leak personal data; an output-only bound lets a call encode a hundred kilobytes on the EDT before it is cut |
| D126 | `hook.waitFor` is not built: `sync.await` on the hook's topic with a payload match serves it; `hook.events` is a thin filter over the bus (§4.2) | a `hook.waitFor` primitive deriving its timeout from its arguments; no `hook.events` | `Dispatcher.DispatchTimeout` is package-private in the core, so a module's wait could only use `_meta.timeoutMs`, which `sync.await` already does better; the design names `hook.events` and it costs a filter, and the run-scoped fact root needs it |
| D127 | Hooks are switched per run: `RunOptions.hooks` (`HooksMode AUTO | ON | OFF`), `--hooks`, `skillroy.hooks`; never a scenario key; `plan.json` stays version 5 (§6.1) | a scenario key `hooks: {mode}` with plan version 6; the module's presence in `modules/` as the switch; the catalogue's `enabled` flags as the switch | the exit criterion says the scenarios pass *unchanged* with hooks on and off, so the YAML cannot carry the switch; `requires:` and guards already express "the same scenario on a target without hooks" (B14, design §1.3); a plan version for a value nothing in the plan uses is churn |
| D128 | Hooks never enter the environment fingerprint; design §3.4's list loses "hook-catalogue version" (§6.5) | the catalogue version as a fingerprint component (design §3.4); only when installed | bindings do not depend on hooks, and a `replay` with hooks off against bindings made with hooks on must not be `REBIND_NEEDED`; the catalogue is recorded in `run.jsonl` and the archived matrix instead |
| D129 | The controller installs a pack's catalogue once per target, idempotent by catalogue hash, at step 0b of every run and at `ExecutorSession.open`; `replace` only for the same catalogue id, another id is `ENVIRONMENT`; **an armed stub is `ENVIRONMENT` under `replay`** and recorded under `live` and `approve`; hooks stay installed until `skillroy hooks remove`, detach or exit (§5.3, §6.2) | per scenario install and remove; the agent module installing in `onTargetReady`; replacing any installed catalogue silently; running `replay` against an armed stub | a retransformation costs a stop-the-world pause per class each way (BS3 RESULT §9 fact 17) and per-scenario churn multiplies the restore-and-verify step; a module-side install cannot be overridden by a controller-side overlay (D121); two suites taking turns on one JVM must be visible, not silent; CI must never run against stubbed behaviour the record cannot explain |
| D130 | The capability matrix is archived per run as `capabilities.json` beside `run.jsonl` with a `capabilities {hash, ids, file}` record, also by `ExecutorSession` and the MCP server (§6.4) | a record in `run.jsonl` only; `caps --json` by hand | design §2.2 step 5 promised it; a hooks-on and a hooks-off run must be distinguishable in evidence; the file is what §8.2's golden comparison and a triage read, and the hash is what a record can carry |
| D131 | Scenario E `return-within-limit-observed` carries three guarded expectations on the base fact root `hooks`, **a list keyed by id and scoped to the run's steps**, and no `requires` on a hook, so it is `PASS` with hooks and `DEGRADED` without; the committed scenarios A to D must be `PASS` either way; the sample's `AccessControl.check` reads the limit through `limit(code, LIMIT_RETURN)` (public today) so that `returnLimit` is the return path's alone; the sample pack is 0.5.0 with the catalogue of §6.3 (§6.3) | guarded hook expectations added to A to D; E with `requires: [hook.limit.applied]`; a map root keyed by dotted ids read as `hooks.limit.applied.count`; cumulative `count` and `last` | `DEGRADED` reads as not green in a CI gate, so A to D must be `PASS` either way and one new scenario shows the two outcomes the matrix mechanism promises (design §1.3; B14: `DEGRADED` is the designed outcome of a guarded expectation on an absent capability, reported as skipped); a `requires` would make E `PRECONDITION_FAILED` without hooks; the path grammar splits a field at every dot, so a dotted id is reachable only through the filter form `caps` already uses; a cumulative count would be satisfied by the bind's verifier or an earlier run, and the verifier's own path to `returnLimit` would make the hook mean nothing about the return line |
| D132 | Byteman is loaded in process through the agent's `Instrumentation` with `listener:false` and a project `manager:` class that hands the `Retransformer` to the module once (package-private `take()` and `park()`, reached by reflection; no public accessor); no listener is ever opened; a curated helper jar goes on the system class path; spike BS14 gates the build and a failure leaves §7 specified only; a re-attached instance reuses a `Retransformer` its predecessor parked with no rule left, and rules left by a cut-short detach make `rule.engine` `UNAVAILABLE` until the target restarts (§7.1-§7.5) | the design row's `Submit` client against a loopback listener with a per-session port; the module speaking the listener's wire protocol itself; a second `loadAgent` on `ATTACH`; a `RuleManager` with a public accessor; treating a loaded Byteman as the host's | an unauthenticated loopback socket that executes arbitrary Java is the S1-S4 class of finding that H1 and H2 removed (attach §C9, §Risk6; map 5 §8 Q5); a wire client still needs the port open; a second dynamic load warns on Java 21+ and meets the 1024-byte attach limit, while the agent already holds the `Instrumentation`; a public accessor on the system class path would hand any class a `Retransformer` past the lint, `confirm` and the channel; the module's own load sets Byteman's `loaded` property, so a re-attach must tell its own earlier instance from the host's |
| D133 | `rule.*` is split into `rule.check` (read-only, never loads Byteman or a class), `rule.submit` (mutating and destructive; loads Byteman on first use; bounded in the classes a rule may retransform), `rule.unload` (ungated: a restore) and `rule.list` (read-only), an additive change to design §2.4's `rule.submit(bytemanRule, checkOnly) / unload(id)`; every submit needs `confirm`; the lint's deny-list of §7.3, matched as identifiers and qualified-name prefixes after comments and strings are removed, with the agent's and the JDK's prefixes and not the repository's; rules carry the `skillroy:` name prefix (§7.3) | one destructive `rule.submit` with `checkOnly`; a lint verdict "observe-only" that lifts `confirm`; the repository prefix `ai.skillroy.` in the deny-list (the first draft, which refused every rule on the sample and the module's own `HELPER` line); substring matching; `rule.unload` mutating; loading Byteman under `rule.check` | the flags are per spec and a primitive cannot read `_meta.confirm` (P1 §5), so a check under a destructive primitive would itself need `confirm`; no lint can make a `DO` clause with calls on bound objects safe (map 7 §7); a prefix keeps the module's `unload` away from the host's own rules; a read must never append jars to the system class path and run a third party's `premain`; substrings refuse an application's `traceId` |
| D134 | The Byteman modules are in the reactor without a profile; `org.jboss.byteman:byteman` 4.0.27 is a test-scoped dependency of the module, of `skillroy-agent-dist` and of the scenarios module (the ITs take the jar's path from the test class path, which is how CI gets it) and a `provided` dependency of the sys jar, which keeps what Byteman hands it as `Object`; at run time the jar comes from the agent option `byteman.jar` only, an absolute path in a directory no other user may write to, never from the home or an environment variable (§2.1, §7.4, §7.6) | a Maven profile `byteman`; a mounted path through an environment variable (the `SKILLROY_JDK8_HOME` pattern); a drop-in directory of the home; `$BYTEMAN_HOME/lib/byteman.jar` as a fallback (the first draft); a sys jar that "depends on nothing" | a test- or provided-scoped LGPL artifact is the treatment JUnit's EPL already has and bundles nothing (`../licence-audit.md` §5); the Maven cache is already how every artifact reaches the offline image; the home's explicit includes and `DistributionContentIT` must never carry Byteman (B8); a jar the agent runs inside the host, named by a variable set machine-wide for another tool, is the class of egress D140 refuses for `OTEL_*`; the manager's `initialize` and the helper's constructor must be typed as Byteman looks them up |
| D135 | The JEP 451 check is check 1b of `LoadModeChecks`, reading the target's merged log for the two texts, with the `ATTACH` leg on a Java 21+ target as the positive control (§8.1) | a `PerTargetJava` check launching one extra target per mode; an `AgentHomeIT` test on a parked child | the log file, the Java version and the mode are in hand in `LoadModeChecks`, and the two legs of the matrix already give the Java 8 and Java 25 readings; the desk's `ATTACH` leg prints the warning for free (the controller's own attach fixtures silence it) |
| D136 | A committed golden matrix per load mode (and one for the hooks leg), compared in both legs after `target.ready` with ids, states and strategy strict, reason by presence and an allow-list of ids that may differ, empty at first; `-Dskillroy.golden.write=true` regenerates; the Java 8 leg also diffs live against a target on the test JVM's Java (§8.2) | a live diff only; files for the orchestrator to compare; `CapabilityMatrix.equals` (all four fields) | the gate must catch drift in every leg, not only when both Javas are at hand (the committed-plans pattern); a free-text reason may embed a JDK's message; a difference must be explained in the allow-list, never tolerated silently |
| D137 | The `.bat` launcher ships from the Phase 6 build, unverified on Windows, CRLF (`.gitattributes` lines **below** the LF rule, `cli-layout.xml`), with a `rem` header kind in `ci/license-headers.sh`, checked statically by `BatLauncherTest`; `AuthorCommand` accepts `claude` plus every `PATHEXT` extension on Windows and writes the plain `cmd.exe /c <launcher>` form as the MCP command when the launcher is a `.bat` or `.cmd`, refusing a launcher path or argument with a space or a quoting character until the host run settles the quoted form (§9.1, §9.2) | shipping the `.bat` only after the host ran it (§17 Q9's veto); `AuthorCommand` falling back to `java -cp` on Windows; a `.cmd` the planner spawns directly; the `/d /s /c "…"` form written blind | the planner must start the same distribution with `SKILLROY_JAVA_OPTS` and the attach-module logic; Node-based tools refuse to spawn a `.bat` without a shell since `CVE-2024-27980`; a static test proves what a host would prove about the text; a later line wins in `.gitattributes`, so above the LF rule the `.bat` would be checked out LF; whether the client re-quotes arguments decides which `cmd.exe` form survives, and only the host shows it |
| D138 | Every owner-only file or directory the controller writes goes through the now public `SecureFiles` (`ensureOwnerOnlyDirectory` with a role, `createOwnerOnlyTempDirectory`, `writeOwnerOnly`, `untrustedDirectoryReason`; the token readers and `writeOptionsFile` stay confined): the planner's work directory and configuration, the CDP trace directory, the MCP server's temporary `--out`, the tests' discovery directories; `CredentialFiles`'s "neither view: accepted with a warning" stays and is named for L7; the run output tree stays on the umask (S37's documentation fix) (§9.3) | leaving each site's own `PosixFilePermissions` call; tightening `CredentialFiles` now; owner-only run outputs by default | Windows gets the ACL branch everywhere or nowhere, and four of the sites are in modules that cannot call a package-private class; the credentials asymmetry is P2 §4's deliberate choice and a review question, not a build decision; owner-only outputs break the documented CI example and are one of pass 1's deferred parts (Q22) |
| D139 | Cross-session attach is documented as unsupported (README, P1 §2.5, this contract) with the wording of §9.4; `ATTACH` on Windows is best effort; the 5 s start-up margin is kept; the `tasklist` / `/proc/<pid>/cmdline` pid fallback is dropped from the design (§9.4) | detecting the session and refusing with a tailored message; skipping the margin on Windows; building the fallback with `ProcessHandle` | detection needs Win32 calls the controller does not make; nothing is measured on Windows yet; nothing needed the fallback in five phases, and `jvms` already lists agents the attach API cannot see |
| D140 | OpenTelemetry export is controller-only through the `RunObserver` SPI of `skillroy-scenario` (no OpenTelemetry type there and no inference type: `TurnRecord` carries a turn's facts), implemented by `skillroy-telemetry`, which the CLI distribution bundles and `ServiceLoader` finds; explicit opt-in (`--telemetry`, `skillroy.telemetry`, `telemetry.yaml`), off by default with no class loaded and no thread started; no autoconfiguration, no `OTEL_*` variable read, a `Resource` of the SDK's default plus the service name and version only (§10.1-§10.4) | a relocated OpenTelemetry SDK in the agent (B11's open door, control-channel §R10); on when `OTEL_EXPORTER_OTLP_ENDPOINT` is set; the SDK's autoconfigure; an optional module outside the CLI zip; the SPI naming `Turn` (the first draft, a dependency cycle) | nothing but the JDK may be visible to a target JVM (`CLAUDE.md`, control-channel §C0) and the replies already carry what a span needs; export is network egress of run facts, a different class from a key's presence, and `OTEL_*` may be set machine-wide for other tools; D88 took the same choice against `fromEnv()`; a bundled module makes `--telemetry` work from the zip as shipped; `Turn` lives in `skillroy-inference`, which depends on the scenario module |
| D141 | OTLP/HTTP (protobuf) through `opentelemetry-exporter-sender-jdk`, `http://` only to a literal loopback address or `localhost`, `https://` otherwise against the JDK's default trust store with no insecure setting; headers given as `env:<VARIABLE>` and read at `create`, never written, and never a variable the inference settings read; a settings error is exit 64 before the agent is contacted (§10.3) | the OkHttp sender (gRPC capable); literal header values; any `http://` endpoint; a header naming any variable | no dependency outside OpenTelemetry and the controller already uses `java.net.http`; a backend token in a file or option is a secret in a place P2 §11.4 forbids; plain HTTP off the host would carry run facts in clear (§17 Q10 flips the sender if a backend needs gRPC); a header naming `ANTHROPIC_API_KEY` would send the model key to the backend, L5's criterion verbatim |
| D142 | Spans only; the attribute allow-list of §10.4 with `standard` and the exact `minimal` list of §10.3 (span names included); every string masked and cut, taken from the plan or a record's identifiers and never from a bound form; the agent span reconstructed and centred with `skillroy.agent.timing = reconstructed` (`RpcResult.startedAt` a 6.x amendment if needed); the outbound `traceparent` restored through `CallOptions`; the inbound MCP `traceparent` accepted; the semantic-convention names as project constants; the service version from `TelemetrySettings.serviceVersion` (§10.2, §10.4) | metrics in Phase 6; copying records into attributes; a protocol change for the agent's start time now; the `semconv` jars; `minimal` described by example; `Main.version()` from the telemetry module | a CLI process is short-lived and metrics need flush plumbing; an attribute is a run output under P2 §11.4; an additive wire field costs a matrix run on two targets and the approximation may suffice; `test.*` and `gen_ai.*` are incubating and move between releases; an implementer cannot build `minimal` from "every name a tenant may hold confidential"; the CLI depends on the telemetry module, not the reverse |
| D143 | The `file` exporter writes the project's own `skillroy-spans/1` JSON lines, masked, one span per line (§10.5) | the experimental `otlp/stdout` exporter redirected; the `logging-otlp` exporter through `java.util.logging`; no file exporter | the OTLP file format is still "Development" (control-channel §F24) and the SDK's JSON exporters write through logging; a stable project format is the test oracle and a CI artifact without a collector |
| D144 | `DomainPackOverlay` (`baseId`, `id`, `version`, `overlay(base)`) found by `ServiceLoader` beside `DomainPack`, applied in `DomainPacks.discover` to the base it names, chained by id order, with `DelegatingDomainPack` in main code and `packVersion` recorded as `<base>+<overlay>@<version>` (§11.1) | an overlay as a full `DomainPack` with the base's id that `Packs` prefers; an overlay with its own id that scenarios name | scenarios keep `pack: lending` (the exit criterion); the duplicate-id refusal stays a real error; the base jar and the overlay jar can both be on the class path, which is how a private sibling repository ships (§17 Q4) |
| D145 | `PackSettings` (`DomainPack.settings()`, default empty) reaches resolvers, verifiers, the fingerprinter and pre-flight through their contexts (`PreflightCheck.PreflightContext` included), so that an overlay changes a constant without replacing the code that reads it (§11.1) | constants as pack-private literals replaced by whole-resolver overrides; a settings map on `ResolveContext` only | "constants overridden" is the roadmap's own word for the overlay test (design §3.6); a context default keeps every test fake compiling |
| D146 | The second variant is the desk's **branch edition** (`lending.edition=branch`): `role_cap.ceiling` for `amount`, `return_ceiling` for `max_return`, the return path through `ReturnCeilings.applicable`, `app.state.edition` (the agent module's one new key); the fingerprint is unchanged; the overlay module `skillroy-sample-lending-pack-branch` overrides a resolver, a constant, the allow-list and the catalogue; A, B and E prove it on fresh desks, E's negative shows the catalogue override is needed (§11.3, §11.4) | seed B (a data variant: proves nothing the Phase 2 YAML did not); a second application module built from the same sources with renamed classes; a `lending.edition` fingerprint component; the agent module untouched | one flag on the existing launcher exercises each of the three overrides the roadmap names in one jar; a second module doubles the build for what one flag shows; a new fingerprint component changes the standard edition's hash and stops every committed bindings file replaying, while `lending.schema` already differs between the editions; the edition is a fact `app.state` must report, which only the agent module can add |
| D147 | The repeat review runs in two passes over eight lenses (pass 1: L3-L6, finished; pass 2: all eight after the build, L3-L6 as deltas), one sceptic per lens, the four-level scale with §12.4's rubric and counting rule, one record of the 2026-10-02 shape, dispositions under §12.5's rule (nothing medium or above accepted or deferred; a low accepted only by the owner's word, deferred only to a named phase; documentation is an acceptance) (§12) | one pass after the build; six lenses; the phase reviews' three-level scale; accepting any finding in writing; accepting nothing at all; deferring "later" | L3-L6's code is untouched by Phase 6 and is where the Phase 5 reviews found the highs, so their findings can be fixed inside the build; Windows and telemetry are where no lens has looked; comparability with the first review's counts; a release will meet findings that are the design, and a rule keeps the record honest while the owner keeps the signature (§17 Q16, Q22) |
| D148 | `threat-model.md` is written with this contract and cited by every lens; `SECURITY.md` and `docs/release-checklist.md` enter the tree with the build (§12.7, §12.8) | keeping the threat model implicit in B13 and the lens briefs; `README.md` only | the 2026-10-02 sceptics re-rated eleven findings on "who can reach it", a judgement every reviewer makes alone today; a first public release without a disclosure channel pushes reports into public issues |
| D149 | ~~`PrimitiveDescriptor.script` marks a primitive that evaluates a caller's script; the MCP projection and `PlanPolicy` decide by the flag; the agent's read-only treatment is unchanged~~ **Superseded by D156** (the revision of 2026-10-09): the flag is also refused by the agent's dispatcher and the CDP path in read-only mode, the named set stays as a fallback, and FX-web builds it | – | the contract review (C2-M1, C3-M1, C1-M5) found two specifications of one field with opposite read-only semantics, built by three packages; pass 1's S42 is the one that fixes a finding |
| D150 | ~~`failure/*.png` resources are served over MCP only with `skillroy mcp --serve-captures`~~ **Superseded by D159** (the revision of 2026-10-09): captures reach a client only under the inference settings' `images: true`, and there is no `--serve-captures` | – | the same review: pass 1's S58 gates the same methods on D84's existing opt-in and names a separate flag as the alternative it rejects |
| D151 | "As built" tags are two letters: `HK`, `CP`, `TL`, `BW`, `SR` (the security fixers, package FX), `FA`-`FD` (the code review's fix wave) (§2.3) | single letters per wave as Phase 5 (`I1`, `S1`); `FA`-`FD` for the security fixers too | Phase 6 mints security findings `S35`+ and may extend `H`; `S1` would then mean two things in one document, and one tag must not mean two waves |
| D152 | `HomeConstantPoolIT` scans every class of every jar of the assembled home for `CONSTANT_Class` references outside `java/`, `javax/`, `sun/`, `com/sun/`, `jdk/`, `ai/skillroy/` and for any `net/bytebuddy/`, `org/objectweb/asm/` or `com/sun/jna/` reference (§2.5) | an enforcer `bannedDependencies` rule alone; the audit's manual constant-pool reading; the allowed `com/sun/` covering JNA | the artifact that ships is what must be checked, and a shade that missed a class is only visible there (BS3 RESULT §9 fact 10); an enforcer cannot see what a shade produced; `com/sun/jna/` would pass the `com/sun/` allowance unnamed |
| D153 | `…agent.api.instrument` (`ClassFileRefs`, `ByteVerification`, `RetransformBatches`) in `skillroy-agent-api`, shared by the hooks and Byteman modules (§3.6) | a copy in each module; a public utility in `agent-core`; a new `lib/` jar | two optional modules verify bytes the same way; core's internals must not become API by accident; one more jar for sixty lines is not worth a loader |
| D154 | Other bytecode agents are **reported**, as jar file names and the Byteman marker, never as option strings (`hook.probe`/`hook.install` `otherAgents`, the `hooks` record), and never refused or `DEGRADED` (§3.2, §5.3) | the capability `DEGRADED` when another agent is present; refusing to install beside one; repeating the `-javaagent:` arguments | BS3 RESULT §9 facts 13 and 14: what another agent changed before the first retransformation is invisible to the engine, so the record is the honest form; a `DEGRADED` reason would change the matrix in every environment with a profiler, and the golden comparison reads reasons by presence; an agent's option string may carry its licence key, which `jvm.info` already redacts |
| D155 | The engine takes Byte Buddy's transformer with `makeRaw()` and registers it itself behind a name pre-filter, after taking the `Nexus` measure (the relocated `nexus.disabled` property set while `NexusAccessor` initialises); it keeps `disableClassFormatChanges()`'s frozen type strategy, discovers classes explicitly, retransforms the classes of one install or remove together in batches of at most 8 classes and `hooks.maxClassBytes`, and never calls `installOn` (§3.2) | `installOn(inst)` with Byte Buddy's own discovery and initialisation defaults (what BS3 ran); a later `with(TypeStrategy.Default.REDEFINE)` (the first draft) | BS3 RESULT §9 facts 2, 17, 19 and 20: the defaults inject `Nexus` into the system loader (and an accessor into `java.lang` on Java 9+ with a `sun.misc.Unsafe` warning), scan every loaded class (615 ms on Java 8 at 63,000 classes) and cost 3-8 µs per class the host loads; owning the registration order is what puts the recorder ahead of the hook (D122); an unfrozen `REDEFINE` would let a hooked class gain a bridge method and fail its retransformation. The `makeRaw()` route is unproven by the spike and proved by the module's tests (gap 54) |
| D156 | The descriptor flag `script`, refused in read-only mode by the agent's dispatcher and the CDP path; `web.evaluate` and `web.waitFlag` carry it; the MCP projection annotates `readOnlyHint: false` and keeps the named set as a fallback; D98's offer rule unchanged; built by FX-web (= pass 1's P6-SEC-1, S42; §12.9) | `.mutating()` on `web.waitFlag`; the controller-only named set; the flag for projections only with the agent unchanged (the superseded D149) | a `web.waitFlag` expression is a caller's script run in the target (D51's rationale was wrong on its face); `.mutating()` would reverse D98 and make the step protocol settle after a wait; a named set misses a third-party adapter's primitive; the dispatcher is the agent's one read-only gate. Amends D51 (B21 Q5); §17 Q18 |
| D157 | One `SESSION` budget meter per MCP server bounds `skillroy_session_ask`, `skillroy_resolve` and the tier (b) slots of `skillroy_scenario_run`, a run being refused `BUDGET` before any model call when there is no room or the session is exhausted; built by FX-mcp (= P6-SEC-2, S56; §12.9) | a run on the scenario budget only (the build's behaviour); a per-run cap without settlement | B15 gives a session one money budget and D74 ends its model calls at exhaustion; `scenarioUsd` times a client's patience is no bound. Widens D101 (B24); §17 Q19 |
| D158 | `Origin.Kind.CLIENT`, which never confirms, is the MCP server's origin (`--client <label>`, `planner` for `author`); a pack verb called through the MCP server or the session service is checked at call time on its compiled calls with the real arguments, the start-time probes deciding only the catalogue; the journal and export say `client <label>`; built by FX-mcp with FX-scenario's seams and FX-inference's `CallPolicy` (= P6-SEC-3, S57, S59, S62; §12.9) | keeping `person("mcp")` and widening the probes; stripping `confirm` only for the planner's server | no finite set of probes covers a verb whose calls depend on its arguments; a client's request is often a model's and is never a confirmation (design §3.4). Amends D96 (B24) and P5 §12.4's host text; §17 Q20 |
| D159 | A capture's bytes leave the controller to an MCP client only under the inference settings' `images: true` (D84's opt-in, widened to MCP); otherwise the file is written and a text resource names its path; no `--serve-captures`; built by FX-mcp (= P6-SEC-4, S58; §12.9) | a separate `--serve-captures` flag (the superseded D150); serving as today | one question, one switch (D84 already answers "may an image leave the controller"); a capture is personal data until masked (design §3.10) and the client is often a model. Amends D76 (B23 Q7) and widens D84 (B23 Q15); §17 Q21 |

### 16.1 Where the §17 answers went

Every provisional answer of §17 is written into the section its row names; a veto changes that section and the
decision row it names, and the build then follows the changed text.

---

## 17. Questions for the owner: answered provisionally 2026-10-09 (B26)

Answered by the orchestrator in the manner of P2 §17, P3 §14, P4 §15 and P5 §17, so that the build can start; written
into the sections named. A veto removes or changes what the section says; its form is stated. Items the owner must
decide for reasons outside this repository (the private layers, the Windows host, the backends, the disclosure
channel) are marked **(owner's knowledge)**: nine of the twenty-two.

| # | Question | Provisional answer | Written into | A veto means |
|---|---|---|---|---|
| Q1 | **The carve-out of the hard rule, in words.** `CLAUDE.md` says "No third-party classes inside the target JVM"; `NOTICE` says the agent "contains no third-party code"; the design admits shaded Byte Buddy in the optional hooks module (B4). Proposed wording for `CLAUDE.md`'s rule: "No third-party classes inside the target JVM, with one carve-out (B4, Phase 6): Byte Buddy and the ASM it bundles, shaded and relocated under the project's prefix inside the optional hooks module `optional/skillroy-agent-hooks.jar`, which loads only when the agent option `optionalModules` names it; only `Spy`, project code, ever reaches the bootstrap search path. Nothing else, in no module." For `NOTICE`: "The agent that runs inside a target JVM contains no third-party code, except that its optional hooks module carries a relocated copy of Byte Buddy (Apache-2.0) and the ASM it bundles (BSD-3-Clause), whose notices travel inside that jar under META-INF/third-party/; the module is loaded only when named." `CONTRIBUTING.md`, `DistributionContentIT`'s comment and `../licence-audit.md` §1 get the same sentence. May the hooks jar ship in the default home under these words? | Yes (D114, D115) | §2.1, §2.5; `CLAUDE.md`, `NOTICE`, `CONTRIBUTING.md`, the audit (edited by the orchestrator with the owner's wording) | `B26 Q1 veto`: the hooks jar is a separate download (`skillroy-agent-hooks.zip`), the home stays third-party-free, `NOTICE` unchanged, the `optional/` mechanism stays for Byteman; `B26 Q1 veto wording`, with the text to use instead |
| Q2 | **Byteman in Phase 6.** Build the thin in-process module behind spike BS14 (CI's Byteman jar from the Maven cache as a test-scoped artifact, the sys jar compiled against it at `provided` scope, never in a distribution), or specify it only? **(owner's knowledge: do the private layers want AI-authored tracing, fault injection or synchronisation rules on their targets soon?)** | Build it behind BS14 (D132, D134); if the spike fails, specified only | §7 | `B26 Q2 veto`: §7 stays specified, the two modules are not created, `rule.*` stays reserved |
| Q3 | **A second project jar on the system class path.** B4 says only the bootstrap jar goes on the target's class path, with Byteman as the one exception (B8). The curated helper and the manager class need a project jar (`skillroy-agent-byteman-sys.jar`, Apache-2.0, two classes, compiled against Byteman at `provided` scope) on the **system** class path for the JVM's life, because Byteman loads a helper through the trigger class's loader. Acceptable inside the B8 exception? | Yes (D132): without it `rule.fired` events cannot reach the agent's bus and Byteman's default `Helper` with its `killJVM` and blocking built-ins would be the only helper | §7.3, §7.4 | `B26 Q3 veto`: no project jar on the system class path; the module keeps `rule.check/list/unload`, `rule.submit` accepts rules whose `DO` is empty or `traceln`-free assignments only, no `rule.fired` events, and Byteman's default helper is denied by the lint entirely |
| Q4 | **How the private layers consume an overlay.** As a second jar beside the base pack's jar on one class path (`--pack base.jar --pack overlay.jar`, or two dependencies), through `DomainPackOverlay`, or do they already wrap the base pack in a class of their own and register that instead? **(owner's knowledge)** | The second jar through `DomainPackOverlay` (D144); a layer that wraps today moves its wrapper into `overlay(base)` | §11.1 | `B26 Q4 veto`: `DomainPacks` prefers a `DomainPack` with the base's id that implements a marker interface `Overlays` over the base, and the `ServiceLoader<DomainPackOverlay>` SPI is dropped |
| Q5 | **An existing private catalogue shape.** Does a hook catalogue already exist in a private layer whose shape (ids, descriptors, fallbacks, versioning per application release) the base format must carry unchanged? **(owner's knowledge)** | The §5.1 format is the first catalogue anyone writes (D121) | §5.1 | `B26 Q5 veto`, naming the fields the format must add or rename (`… <field>`), or `… versions` for several catalogues per pack selected by application version (today a non-goal) |
| Q6 | **Third-party hook targets.** The catalogue defaults `enabled` to false for a class outside the pack's `packages` and accepts `enabled: true` there with a warning; the sample demonstrates `enabled: false` only. Does any tenant layer need a hook on a third-party class in Phase 6 **(owner's knowledge)**, and is a warning the right strength? | No third-party target in Phase 6; the warning stands (D121, D124) | §5.1 | `B26 Q6 veto`: the reader refuses `enabled: true` on a class outside `packages` (an error, not a warning) |
| Q7 | **The Windows host.** Which JDK 8 and JDK 25 builds will it carry (vendor, 64-bit only?), and may tests run in an interactive logon session there (the Windows items need real windows)? **(owner's knowledge)** | Assumed: Temurin 25 and Temurin 8, 64-bit only, an interactive session of the logged-on user; `ci/run-on-windows.cmd` is written for that (D137) | §9.5, §13.3 | `B26 Q7 veto`, naming the JDKs or the session constraint (`… no interactive session`: the display items are then not verifiable and the criterion is split again) |
| Q8 | **`ATTACH` on Windows.** In scope for Phase 6 (then the host procedure's `ATTACH` leg is part of the exit), or only `JAVAAGENT` plus the documented exclusion? | Best effort only (D139): the TODO item reads as the latter | §9.4, §9.5 | `B26 Q8 veto`: `ATTACH` on Windows joins the exit criterion when the host exists; the 5 s margin and the session rule are then measured and decided there |
| Q9 | **The `.bat` shipped unverified.** Carry `bin/skillroy.bat` in the distribution from the next commit, marked unverified in `README.md`, or only after the host has run it? | Ship it (D137): a static test proves the text, the host proves the run | §9.1, §2.6 | `B26 Q9 veto`: the assembly leaves it out until the host run; `BatLauncherTest` and the source file stay |
| Q10 | **OTel backends and what may leave the host.** Which backends will receive the spans (an OpenTelemetry Collector on the CI host, a hosted vendor, both)? If one needs gRPC, the sender flips to OkHttp. May a tenant's run export the `standard` set (scenario ids, pack names, primitive names, masked one-line messages) to a backend run by someone else, or must `minimal` be the default? **(owner's knowledge)** | OTLP/HTTP protobuf through the JDK sender, loopback-http or https only; `standard` by default (D141, D142) | §10.3, §10.4 | `B26 Q10 veto grpc`: the OkHttp sender and `grpc` as a protocol setting; `B26 Q10 veto minimal`: `minimal` the default; `B26 Q10 veto loopback`: `https://` endpoints refused, loopback only |
| Q11 | **The rule "no third-party classes inside the target JVM" for a relocated OpenTelemetry copy.** B11 left the door open for agent-side spans; the contract closes it (nothing in Phase 6 needs it). Closed for good? | Closed (D140) | §10.1 | `B26 Q11 veto`: the door stays open for a later phase's agent-side exporter under the same shading rules as Byte Buddy; nothing changes in Phase 6 |
| Q12 | **Who signs the repeat review, and an outside reader.** The sign-off is the owner's (B28). Should a human reviewer from outside the project read the record before the first public release (every review so far was run by model agents with model sceptics)? **(owner's knowledge)** | The owner signs; an outside reading is recommended and recorded in the release checklist as "done" or "waived by the owner", not a gate (D147) | §12.6, §12.8 | `B26 Q12 veto`: an outside reading is a gate of the first release |
| Q13 | **`SECURITY.md`'s disclosure channel and supported versions.** An address, or the repository host's private vulnerability reporting; fixes for the latest version only? **(owner's knowledge)** | The repository host's private reporting plus the address in `NOTICE`; the latest minor only (D148) | §12.8 | `B26 Q13 veto`, naming the channel (`… address <a>`) or the policy (`… last two minors`) |
| Q14 | **The CDS line on Java 25 in hooks mode.** Every start in hooks mode on a Java 25 target with class data sharing on prints `OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended` on the host's stderr once and ends sharing of non-boot classes (BS3 RESULT §9 fact 8). Acceptable, documented in README and `SECURITY.md`'s limitations, or should the engine offer a system-class-path placement of `Spy` (`hooks.spyPlacement=system`: no line, but invisible to loaders whose parent chain skips the system loader)? | Acceptable and documented (D116) | §2.5, §3.2 | `B26 Q14 veto`: the option `hooks.spyPlacement = bootstrap | system` (default `bootstrap`) and a second visibility probe for the system placement |
| Q15 | **Releasing with Windows unverified.** May the first public release ship with the Windows items marked "not verified" (S7/S15, the owner-only files, the `.bat`, the rungs) in `README.md`, `SECURITY.md` and the threat model, or does the release wait for the host? **(owner's knowledge)** | It may ship so marked (§14) | §12.6, §12.8, §14 | `B26 Q15 veto`: the first release waits for the Windows host and §13.3 joins its gate |
| Q16 | **Accepting known risks.** The repeat review may accept or defer a **low** finding only, in writing, with a release note (deferred only to a named phase; a documentation-only change counts as an acceptance); nothing medium or above. Right rule? | Yes (D147) | §12.5 | `B26 Q16 veto none`: nothing accepted or deferred at all (2026-10-02's precedent); `B26 Q16 veto any`: the owner may accept any finding in writing |
| Q17 | **Read-only mode and observation.** May read-only mode install `OBSERVE` and `CAPTURE` hooks (a bytecode change without a behaviour change), with only `STUB` disabled, as design §2.2 step 7 reads ("read-only mode disables mutating primitives and `STUB` hooks")? | Yes (D119): `hook.install` is not mutating | §4.2 | `B26 Q17 veto`: `hook.install` is mutating too, so read-only mode installs nothing and `hooks.mode AUTO` skips the install on a read-only agent |
| Q18 | **The `script` flag refused in read-only mode (D156).** Pass 1's S42: `web.waitFlag` runs a caller's expression, so B21 Q5's answer (D51, "the six reads are not mutating") rested on a wrong premise. The agent's dispatcher and the CDP path refuse a `script` primitive in read-only mode; `web.waitFlag` stays "not mutating" for the step protocol; D98's offer rule (`--allow-evaluate`) is unchanged. Confirm the amendment of D51? | Yes (D156) | §12.9; P1 §2.4, §3, §5; P4 §4.2, D51 | `B26 Q18 veto`: the flag marks and projects only (the superseded D149); read-only mode keeps serving `web.waitFlag`, and S42 is recorded as accepted (low) with a release note |
| Q19 | **One session budget over `skillroy_scenario_run` (D157).** Pass 1's S56: a client's runs spend tier (b) calls outside the session budget. D101 (B24) is widened: the server's one `SESSION` meter bounds `skillroy_session_ask`, `skillroy_resolve` and a run's tier (b) slots, a run being refused `BUDGET` before any model call. Confirm? | Yes (D157) | §12.9; P5 §9.3, §12.4 | `B26 Q19 veto`: a run stays on the scenario budget only, and S56 is recorded as accepted (medium), which §12.5 forbids: the veto therefore also means the release waits on another fix the owner names |
| Q20 | **A client origin that never confirms, and the verb gate at call time (D158).** Pass 1's S57, S59, S62: the MCP server's origin was `person("mcp")`, so a verb's own `confirm` was honoured, and D96's verb gate ran on three probes at start, not on a call's real arguments. `Origin.Kind.CLIENT` never confirms; the gate runs at call time on the compiled calls, for the MCP server and the session service; the journal says `client <label>`; `author` passes `--client planner`. Confirm the amendment of D96 and of P5 §12.4's "the MCP client is the person's side"? | Yes (D158) | §12.9; P5 §9.2, §9.3, §12.3, §12.4 | `B26 Q20 veto client`: the server keeps a `PERSON` origin (then a verb's `confirm` is honoured for every client, which the owner accepts in writing); `B26 Q20 veto gate`: the probe-only gate stays and S57 is accepted (medium), which §12.5 forbids as in Q19 |
| Q21 | **Captures over MCP only under `images: true` (D159).** Pass 1's S58: captures reached the MCP client as unmasked, uncut blobs. A capture's bytes now leave the controller to a client only under the inference settings' `images: true` (D84, widened to MCP; D76's "captures as resource links" amended); otherwise a text resource names the written file; there is no separate flag. Confirm? | Yes (D159) | §12.9; P5 §4.1, §9.3, §9.4 | `B26 Q21 veto flag`: a separate `skillroy mcp --serve-captures` (the superseded D150); `B26 Q21 veto today`: served as today, S58 accepted (medium), which §12.5 forbids as in Q19 |
| Q22 | **Five low parts of pass 1 deferred or fixed by documentation only.** `dispositions.md` defers, each with a release note: S36 part (b) a deny-list of side-effect function names in `StatementGuard` (the read-only role is the boundary); S37 option (i) owner-only run output directories by default (it would break the CI example; documented: `umask 077` on a shared host); the pinning of the CI example's image and actions in S40 (a pinned example rots; the comment tells a copy to pin); S48 a minimal environment for the Playwright driver (it would break proxies and the `PLAYWRIGHT_*` variables; documented); the `CLAUDE_CONFIG_DIR` relocation of the planner's CLI configuration in S60 (unverified without a CLI run). Under §12.5 a documentation-only change and a deferral are acceptances that need the owner's word, each with the phase it goes to (7, or the phase that builds the seam: the data channel for S36 and S37, the CDP path for S48, the planner for S60, none for S40). Accept the five? | Yes: accepted as lows with their release notes, named in B26 and repeated in B28 (D147) | §12.2, §12.5, §12.8 | `B26 Q22 veto`, naming the finding to fix now (`… S37`, `… S48`, …): FX builds that part in the same wave |
