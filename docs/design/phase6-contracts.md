# Phase 6 contracts: hooks, Byteman, Windows, OTel, hardening (the hook engine and `Spy`, the optional Byteman module, a JDK 25 target through `JAVAAGENT`, the Windows path, OpenTelemetry export, the tenant-pack overlay, the repeat security review and release hardening)

**Status: specified 2026-10-09, revised the same day after the contract review; built the same day by six packages
(TL, HK, CP, BW and the two security-fix packages of FX, FX-2 and FX-1, in the order they landed); the build was
reviewed by three lenses with a sceptic each and fixed (fix waves FA-FD); the repeat security review's two passes were
run and their findings fixed (fix rounds SA-SF), all on 2026-10-09; the final tree verified in the Linux matrix (arm64
twice, amd64 once, every leg green; `../TODO.md`, "Phase 6 final matrix"); built on the then provisional B26; B26 and B27 confirmed by the
owner on 2026-10-10, every row, and B28, the sign-off of the repeat security review, signed off by the owner the same
day.** The questions of §17 are answered by the orchestrator and recorded as decision **B26** (`../DECISIONS.md`),
confirmed by the owner; the 62 decisions the build took without the owner are D160-D221 in §16 and, with the 65 decisions
of the code review's fix wave and of pass 2's fix rounds (D222-D286), recorded as **B27**, confirmed by the owner; the
repeat security review (S35-S100) is signed off by the owner as **B28** (§12.6), with the eight lows put to the owner
accepted. What the build did to each section is its "As built"
notes; the review's fixes carry "fix wave" notes and pass 2's fixes the marks "(Phase 6 security fix, pass 2, P2-L<n>-<F|S><m>,
built 2026-10-09, <fixer>)"; the Linux matrix of the final tree is recorded in `../TODO.md`. Spike **BS3** (the hook go/no-go) reported on 2026-10-09 while this
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
the build's decisions are **D160 to D221**, numbered by the orchestrator at integration in landing order (§16's
preamble), and those of the code review's fix wave and of pass 2's fix rounds **D222 to D286**, numbered the same way;
amendment ids continue the global sequence from **A40**, assigned by the orchestrator in `dispositions.md`
for the security fixes (A40-A60, §12.2) and by the orchestrator at integration, from A61, for any other the build
needs (never by an implementer, §2.3); gaps continue from **50** (§15.2), the build's own from **58**; the repeat
review's findings are S35-S62 (pass 1) and S63-S100 (pass 2); the one new spike is **BS14** (§7.2).

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
(project code only). The §17 Q1 veto (a separate download) would have removed the hooks jar from the home and the
`DistributionContentIT` list and left the mechanism in place for the Byteman module; Q1 was confirmed without it on
2026-10-10.

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

**As built (Phase 6, HK, 2026-10-09):** the three modules are as specified. `skillroy-agent-spy` turns off Maven's
descriptor (`addMavenDescriptor=false`), so its jar holds exactly `Spy`, its two nested classes, the manifest and the
project's licence files, which the content check of §3.2 step 3 requires; `Spy.Stub`'s constructor is package-private
so that javac adds no synthetic accessor class. Both shading executions write their dependency-reduced POM under
`target/`. The hooks module's own classes are 50; its jar carries 3,098 class files and the three third-party notice
files. `skillroy-agent-dist` declares the hooks module with every transitive dependency excluded (`*:*`) and lists it
in a `dependencySet` of its own with `useTransitiveDependencies=false`.

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

*As built (Phase 6, TL, 2026-10-09):* OpenTelemetry is pinned at **1.66.0** (released 2026-09-11; 1.67.0 came out on
the day of the build and was not adopted), per artifact (no BOM import: the BOM is not in the offline cache). The
resolved runtime tree of `skillroy-telemetry` is exactly eleven jars: `opentelemetry-api`, `-context`, `-common`,
`-sdk-common`, `-sdk-trace`, `-sdk-metrics`, `-sdk-logs`, `-exporter-otlp`, `-exporter-otlp-common`,
`-exporter-common` and `-exporter-sender-jdk`; `-exporter-sender-okhttp` is excluded in the root POM's managed entry
of `-exporter-otlp`, and **no `-sdk-extension-autoconfigure-spi` (nor `-autoconfigure`) artifact is in the tree**
(the exporter declares the SPI compile-only), so the table's guess is void. Tests add `opentelemetry-sdk-testing`
with `opentelemetry-sdk`. `THIRD-PARTY.txt`, `DistributionNoticesIT.THIRD_PARTY` and `../licence-audit.md` §4 list
the eleven; the jars carry no `pom.properties` and no licence file, so the test identifies them by file name and the
attribution and the top-level `LICENSE` are their evidence.

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
  order the packages land (§16's preamble; done on 2026-10-09, D160-D221, and again for the fix waves, D222-D286, and every `D-<tag>-<n>` of the notes below
  replaced by its number); the security fixes' A-numbers (A40 onwards, in S order, one per finding
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

**As built (Phase 6, HK, 2026-10-09):** the test agent is a `maven-jar-plugin` execution with the classifier
`test-agent` at `process-test-classes` (manifest `Premain-Class`, `Can-Retransform-Classes`, `Can-Redefine-Classes`);
the surefire line is as specified, the spy module excluded, `skillroy.agent.hooks.spyJar` passed from
`maven-dependency-plugin:properties`. Beyond the contract each `*FreshJvmTest` class of the hooks module runs in a
surefire execution, and so a JVM, of its own (six of them: the first install, the strict loader, the detach budget,
the planted reference, the `Nexus` measure, the collectable loader), and the other classes share the default
execution's JVM (D175). `reuseForks=false` would give every class a JVM, but surefire 3.6.0 runs no test at all that
way when `-Djvm` names a Java 8 JVM (found in the Java 8 leg: the fork starts and is never handed its class). The
shared classes are made independent of each other: the spec helper of the tests pins a fixture class to
`loader: system`, so a copy of the same class that another test left in a child loader never widens a spec. Two more
properties reach the tests, `skillroy.hooks.classes` and `skillroy.hooks.bytebuddyJar`, for the tests that load the
module in a child-first loader of its own as the agent does (the `Nexus` measure and the collectable loader).

**Fix wave (Phase 6 review, FA, 2026-10-09):** two guards for that layout (L3-F1, L3-F2). The module's surefire
configuration sets `failIfNoTests` from the property `skillroy.failIfNoTests`, `true` by default, so an execution
that ran no test fails the build ("No tests were executed!") instead of exiting 0: D175's Java 8 trap made a build
failure rather than a reminder. A module profile activated by a command-line `-Dtest` sets it `false`, because
`-Dtest` filters every execution of the module (and of every module `-am` builds) and leaves most of them empty; a
literal `true` would break `-pl … -am -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false`. Each of the six
`*FreshJvmTest` classes starts with `Harness.assumeFreshJvm()` (`@BeforeAll`): when a `Spy` is already on the
bootstrap path the class is skipped with the reason "needs a fresh JVM (run without -Dtest …)" if the `test` property
is set (a command-line `-Dtest` merges the executions into one JVM, and surefire hands the property to the fork), and
fails otherwise, so a dedicated JVM that is not fresh is never a green skip (D240). The matrix's Java 8 leg still
reads the reports: 15 `TEST-*.xml` files in the hooks module, `skipped="0"`, `java.version` `1.8.0_*`.
(Phase 6 security fix, pass 2, SE: two more fresh-JVM classes, each in an execution of its own,
`fresh-jvm-detach-lock` (`DetachLockFreshJvmTest`, P2-L2-F6) and `fresh-jvm-replace-residue`
(`ReplaceResidueFreshJvmTest`, P2-L2-F3), both of which leave the slot held by design: eight fresh-JVM classes, 17
`TEST-*.xml` files.)

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
  appends at most once per JVM (§3.2) and the line is accepted and documented (§17 Q14, confirmed by the owner on
  2026-10-10; a system-class-path placement of `Spy` stays gap 52).
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

**As built (Phase 6, HK, 2026-10-09):** as specified. `DistributionContentIT` has eleven entries and the two
rules (the spy jar must hold exactly the three classes of the package); its comment carries the carve-out sentence
of §17 Q1 (the root `NOTICE` is the orchestrator's). `HomeConstantPoolIT` scans 3,000-odd classes and finds no class
reference outside the allowed prefixes, the relocated Byte Buddy included; a second test pins that `com/sun/jna/` is
refused although `com/sun/` is allowed. `OptionalModulesIT` and `SharedLoadersIT` launch the toy target
`ai.skillroy.testjava.fixture.hooks.LoanDeskMain` (new fixtures of the module's tests, beside `fixture.app`), which
reads commands from its standard input (`call`, `snapshot <label>`); `SharedLoadersIT`'s probe agent is
`ai.skillroy.testjava.fixture.hooks.probe.ProbeAgent` in a jar of its own, and the snapshot compares, per shared
loader, the classes of the agent's the loader **defines** (`getInitiatedClasses` filtered by the defining loader),
before the install and after the remove; the bootstrap loader's gain is a subset of `Spy`, `Spy$Handler`,
`Spy$Stub` containing `Spy` (`Spy$Stub` loads only when a stub is armed).

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

**As built (Phase 6, HK, 2026-10-09):** as specified; `install(handler)` also answers true for the handler that
already holds the slot (an idempotent re-install), and the slot's set and release run under a private lock (never on
the advice's path, which reads the volatile slot only).

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
  §3). **Phase 6 security fix, pass 2, P2-L1-F5 (built 2026-10-09, SA):** a jar that `hooks.spyJar` **names** (an
  operator's path; the home's own `<home>/skillroy-agent-spy.jar` is trusted as the home is) must first pass the
  shared jar rule of §3.6 (`JarTrust`, the rule `byteman.jar` has had since §7.4: the real jar, its real directory
  and, for a symbolic link, the link's directory writable by nobody but their owner, who is the current user or the
  administrator), else `UNAVAILABLE("spy jar is writable by others: <path>")`; the content check alone bounds the
  jar's shape, not its behaviour, and the check and the append open the path twice, a window the directory rule
  closes against other users. (`SpyBoot.checkJar(jar, named)`; the engine passes `HookOptions.spyJarNamed`, one line of
  `HookEngineImpl`.) Then `inst.appendToBootstrapClassLoaderSearch(new JarFile(spyJar))`, `Class.forName(…, true, null)` and
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

**As built (Phase 6, HK, 2026-10-09):**
- **The advice binds a site key, not the bare hook id** (D171): §4.3's payload names the class, the method and the
  descriptor, and `Spy`'s three-argument shape hands the handler nothing but the bound string, the receiver and the
  arguments, so the `@HookId` constant of each hooked method is `<id> <class> <method><descriptor>` (a space cannot
  occur in any of the parts), which the handler's registry resolves to the hook and the event's names with one map
  read. A hook with several descriptors, or a class defined by several loaders, has several sites.
- **The restore's recorder** is a second, short-lived instance (`Transformers.After`) registered last for the
  restore's retransformation and removed after it, while the recorder that keeps the first bytes and counts the
  events stays registered ahead of the hook transformer for the hooks' life (D173): §3.5's "re-registered last" moved
  the one recorder behind the hook transformer for good, so that a later install would have captured advised bytes as
  a new class's reference.
- **The hook transformer is never removed while the ledger holds a class** (§3.5 allows "answers null … or is removed
  with `removeTransformer` when no hook is left"): it answers null for a class being restored and is removed, with the
  recorder, only when the ledger is empty, which keeps D117's invariant without a second case.
- Steps 1 to 6 are as specified. A catalogue of which nothing installs is **not recorded** as installed (D183), so
  that the next run's install probes again (a class loaded later can then be hooked) rather than answering
  `alreadyInstalled` for nothing. A spy jar refused at step 3 makes `hook.engine` `UNAVAILABLE` with the refusal until
  the next install, which checks the jar again (the operator may have replaced it).
- **Fix wave (Phase 6 review, FA, 2026-10-09; L2-F7):** §3.5's step 5 (the transformers removed and the slot
  released when the ledger is empty) also ends every install. Steps 3 and 4 append and fill the slot before steps 5
  and 6 can refuse every spec; an install that leaves no class carrying an advice (every spec refused at step 5 or 6,
  each refused hook's classes restored and verified first, §3.3's note) gives both back at once rather than at the
  next `hook.remove {}` or the detach. Step 6 is skipped, and nothing is registered, when step 5 left no spec.
- **`<n>` of "Spy slot held by a disarmed handler of an earlier agent; `<n>` classes still carry advice"** comes from
  the system property `skillroy.hooks.adviceLeft`, which a detach that ran out of budget sets to the number of classes
  it left (D177): the earlier engine's loader is unreachable from the new one, and the property is the one place both
  can see; without it the text says "an unknown number of".
- The `Nexus` measure, `makeRaw()`, the pre-filter (which also refuses, at transform time, a class whose class file the
  JVM hands larger than `hooks.maxClassBytes`) and the explicit batches are as specified; the advice of a site is
  built once at install, while the module's loader is open, and cached, so that a later retransformation by another
  agent needs nothing from the loader. Measured on the test JVM (JDK 26, macOS arm64): the first install of a JVM,
  one fire and the remove took 0.23 s together, Byte Buddy's warm-up included.

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

**As built (Phase 6, HK, 2026-10-09):**
- **Several loaders define the class:** the spec's state is decided by the first class that fails (D178), so a
  spec is installed on all of them or on none; `loaders` and `loaderIds` report each.
- **Fix wave (Phase 6 review, FA, 2026-10-09; L2-S3):** "on all of them or on none" holds after step 6 as well.
  The build refused a hook whose classes retransformed only partly (another copy's batch threw, the pre-filter
  refused it, Byte Buddy failed) and dropped its sites, while the classes that had taken its advice kept it: the
  ledger forgot them, `hook.remove` then freed the slot beside a live advice (D117), and a later `STUB` of the same id
  pinned to the other loader reached them too (a site key names no loader, D171). Now, before the call answers, a
  hook refused at step 6 is taken out of every class of the call that took its advice: a class whose batch threw kept
  its earlier bytes (`Instrumentation.retransformClasses`: "If this method throws an exception, no classes have been
  retransformed") and needs nothing; a class that keeps other hooks' sites is retransformed once without the refused
  ones, as `hook.remove` does; a class with no site left that took the advice is restored and verified as §3.5
  restores (`OK` or `DEGRADED` drops its entry, `FAILED` keeps it and so the slot); an entry the call created for a
  class that never took the advice is dropped. Only then does §3.2's note release the slot. When a class may still
  carry the refused hook's advice (its restore was `FAILED`, or the retransformation without it threw), the hook's
  reason ends "…(hook.remove {} retries it)" and `hook.health` lists it as `ERROR("residue after a refused
  install")`. The id of a hook in either residue state (this one or §3.5's `ERROR("residue after remove")`) is
  refused by a later `hook.install` with `UNAVAILABLE("a residue of hook <id> is left: hook.remove {} retries its
  classes")` until a remove restores the class, since a new site of the id, on any loader's copy, would answer the
  advice left behind (D236).
- **Fix wave (Phase 6 review, FA, 2026-10-09; L1-F3):** a constructor (`<init>`) is the per-hook state
  `UNAVAILABLE("constructors are not hookable")` as item 1 says, decided first in the probe, so that `hook.install`
  installs the other specs and the catalogue's fallback applies (§5.3). HK's build had made it a name-rule violation
  (`INVALID_PARAMS` for the whole call, a read error for the whole catalogue, even for a disabled entry).
  `HookSpec.violation()` accepts `<init>` (`HookSpec.CONSTRUCTOR`) and refuses `<clinit>` ("a static initialiser is
  not hookable"), which is neither an identifier nor a constructor (D239).
- **A method already hooked** by an installed hook or an earlier spec of the call is `UNAVAILABLE("method already
  hooked: <class>.<method><descriptor>")` (D179); two advices on one method would fire twice. `capture` on a hook
  whose mode is not `CAPTURE` is `INVALID_PARAMS` with `data.param specs[n].capture` (the catalogue reader's rule,
  enforced by the agent as well); duplicate ids in one call likewise.
- **"Ledger full"** counts the distinct new classes of the call against `hooks.maxClasses`.
- Item 6 before the append answers `UNCHECKED`, or `NOT_VISIBLE` with "the class's loader shadows Spy with a copy of its
  own: <loader class>" when the loader's `getResource` finds a `Spy` class file; after the append the reasons name the
  loader's class ("the class's loader cannot see Spy: <loader class>").
- Item 7: `DEGRADED("nothing to capture")` when, for every pinned method, the capture names no in-range argument, no
  result of a non-void method and no receiver of an instance method (the contract's case included); such a hook is
  installed and its `hook.<id>` is `DEGRADED` with that reason.

**(Phase 8, built 2026-10-19, EH; P8 §6.4.)** Between item 2 and item 3 a new **item 2b, the class deny**: the agent
option `hooks.deny` first, then the catalogue deny-list the engine holds, then every other registered `ClassDeny` in
registration order; the first refusal wins and the spec is `UNAVAILABLE` with `denied by the agent option hooks.deny:
<p>`, `denied by the catalogue <id>: <p>` or `denied by a module: <text>` (a throwing `ClassDeny` refuses: `denied by
a module: the deny policy <class> threw <exception>`, logged once). Before "loaded?" on purpose: a denied class answers
the same whether or not it is loaded, and nothing of it is read. Item 3 is amended by **the code-source filter**: every
copy's code-source name (`CodeSources.of`) is reported in `codeSources`, aligned with `loaderIds`, qualifier or not;
under `target.codeSource` a copy whose code source cannot be read refuses the spec (`the class's code source cannot be
read: SecurityException`), the qualifier is matched as a whole (`^(?:…)$`) through `BoundedRegex` under one
`BUDGET_MS` per spec (`codeSource regex too slow` refuses), and only the matching copies are kept, a filter like
`loader`; none kept is `class from another code source: <names>` (sorted, at most four, then "and n more"). D178's
"all or none" applies to the kept copies.

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

**As built (Phase 6, HK, 2026-10-09):** stub conversion as specified, and: a boolean also from the strings
`"true"`/`"false"`, a `char` from a string of one character, a number from a JSON number or a numeric string of at
most 64 characters (`BigDecimal` parsing, exact for the integral types, range-checked), a boxed return type as its
primitive with `null` allowed; a value that is a JSON object or array is `INVALID_PARAMS`. A hook with several
descriptors converts the value once per site, and `hook.stub`'s `returnType` lists the distinct types.

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

**As built (Phase 6, HK, 2026-10-09):**
- **A residue is a reference to `Spy` the restored bytes hold and the first bytes did not** (D172; `ByteVerification`
  of §3.6 changed with it). Found by the build: HotSpot merges constant pools across redefinitions, and after another
  agent's `redefineClasses` the JVM keeps no cached initial bytes and reconstitutes a class with the merged pool, so a
  class hooked, fully restored, redefined by someone else and hooked again hands the next install "first" bytes that
  already name `Spy` in unused constant-pool entries (the running code calls nothing: `Spy`'s counters stay still).
  Restoring exactly those bytes is no residue; failing it would leave the class `FAILED` and the slot held for the
  JVM's life for no reason. A reference the first bytes did not hold is still `HOOK_VERIFY_FAILED`.
- **Fix wave (Phase 6 review, FA, 2026-10-09; L2-F10): an accepted corner of D172.** In a class whose reference
  bytes already name `Spy` (D172's case), a genuine residue cannot be told apart from the merged pool's unused entries
  by a `CONSTANT_Class` comparison: it reads `FOREIGN_CHANGE`, so `DEGRADED("bytes differ: another transformer is
  present")`, the entry is dropped and, with the ledger empty, the slot is released beside it. It needs an undetected
  residue (advised code a restore left behind, which no path of the engine is known to produce) on top of another
  agent's redefinition of a class that was hooked, restored and hooked again. Failing on "the bytes differ and both
  name `Spy`" would bring back D172's false `FAILED`, and the slot held for the JVM's life, whenever another
  transformer is present. The precise discriminator, a scan of the `Code` attributes for an instruction that
  references a `Spy` member, is left open (D241).
- `hook.remove` restores **in the batches of §3.2** (one `After` recorder per batch); the detach restores **class by
  class** and checks its budget before each class. A class that keeps other hooks is retransformed once without the
  removed ones and is not verified (it still carries advice); an id that is not installed is answered
  `UNAVAILABLE("not installed")`; a class already unloaded is reported `OK` with "class unloaded: nothing to restore".
  `HOOK_VERIFY_FAILED`'s `data` also carries `restored`, the lines of every class. A residue hook stays in
  `hook.health` as `ERROR("residue after remove")` until a later remove restores its class (which `hook.remove {}`
  retries); **(Fix wave, FA: until then a `hook.install` of the same id is refused, §3.3's note, D236.)** The
  catalogue is cleared when no hook remains.
- The detach waits for the engine's lock at most its budget: an install or remove still running leaves every class
  disarmed and returns; the capabilities are withdrawn by the core's rollback (A18).
- **(Phase 6 security fix, pass 2, P2-L2-S1 and P2-L2-F4, built 2026-10-09, SE) A retransformation that throws
  keeps the class's bytes; the removed hook is then a residue.** The bullet above retransforms a class that keeps
  other hooks once without the removed ones. When that does not go through (its batch threw, so the JVM kept the
  class's bytes and with them the removed hook's advice; or the hook transformer failed on the class, or did not
  advise it), every removed hook with a site in that class is a residue of this section: `hook.remove` answers it
  `UNAVAILABLE("residue after remove: the retransformation without it failed: <class> (<loader>): <error> (hook.remove
  {} retries it)")` instead of `OK "removed"`, `hook.health` lists it as `ERROR` with that reason, and **its id is
  refused by a later `hook.install` as D236 says** (§3.3's note), since a site key names no loader (D171) and a new
  site of the id on any loader's copy would answer the advice left behind: a confirmed `STUB` of the id pinned to
  another copy would also stub this class. (The build answered `OK`, kept no residue, and the sceptic's probe stubbed
  the system copy through a hook pinned to a child-first copy.) The class keeps its ledger entry (it holds other
  hooks), so the slot stays held (D117); the residue clears when a remove restores the class (`hook.remove {}`
  retries it). No `HOOK_VERIFY_FAILED` is raised for it: no class was restored. When the hook transformer failed
  rather than the batch, the class's remaining hooks lost their advice too and report `ERROR` with the error.
  **The engine's own transform events are counted where they happen** (step 3's "foreign transform events are the
  recorder's events the engine did not cause"): the recorder counts an event as the engine's own when it happens on
  the thread that is calling `retransformClasses` for the engine (an install's, a remove's or a restore's), not one
  per class of a batch, since a batch that throws stops at its first failing class and the classes after it never
  reach a transformer; the build's per-batch count hid one foreign event per failed batch, which on Java 8, where the
  restore reverts a foreign redefinition and the bytes compare equal, is the only witness (BS3 fact 13). D122, D171,
  D178 and D236 are unchanged; this applies D236 to one more path.
- **(Phase 6 security fix, pass 2, P2-L2-F6, built 2026-10-09, SE) A detach that cannot take the lock still reports
  what it leaves.** On that path the detach now logs every class of the ledger (a lock-free snapshot, which already
  holds the classes of a running install's retransformation, and may over-report one the call then takes out: the
  safe direction) at WARN by name with the remedy and sets D177's `skillroy.hooks.adviceLeft` to their number, as the
  locked path does; the install or remove that held the lock reports the classes again when it ends, so that the WARN
  lines and the count reflect what it finished with. An install that sees the detach begun before its step 6
  retransforms nothing (`CAPABILITY_UNAVAILABLE("the hooks module is uninstalling")`, the slot released when no class
  carries an advice), and one that sees it after step 6 declares no `hook.<id>` capability and publishes no
  `hooks.installed` into the detached module (the build declared them after the core's rollback, into an agent that
  was shutting down; a moot half, but a wrong record).

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

**Phase 6 security fix, pass 2, P2-L1-F5 (built 2026-10-09, SA): `JarTrust`**, the one provenance rule of every jar
the agent loads into the target from a path the operator names **outside the home**: each `modules` path (P1 §2.4,
the core's `ModuleLoader`; a refused one is a `FAILED` record "module jar is writable by others: <path>" and is not
loaded), `hooks.spyJar` (§3.2 step 3) and `byteman.jar` (§7.4 step 1, whose `JarCheck` now delegates its ownership
and ACL judgement here, unchanged). `JarTrust.problem(Path)` answers null, `NOT_A_FILE` or `WRITABLE_BY_OTHERS`: the
real jar and its real directory, and for a symbolic link also the directory holding the link, must not be writable by
group or others and must be owned by the current user (by name, or as the owner of a file the process creates) or
root (POSIX); on ACL file systems the owner is the current user, the system account, the administrators group or the
installer service, resolved as principals, and no ALLOW entry gives anyone else a write right (`aclProblem`, tested
on built values; not verified on a Windows host); a file system with neither view is refused, and the view is the
volume's (`Volumes`, P1 §2.5; P2-L7-F1, carried into `JarTrust` when the two pass 2 fixes were merged), so a jar or
directory on a FAT or exFAT volume is "writable by others" for all three. The directories above are not judged (the
path is the operator's). The home's own jars (`lib/`, `modules/`, `optional/`, the home's spy jar) stay trusted as the
home is (`threat-model.md` §3). The core uses the package for this one class (D153 named the hooks and Byteman
modules; recorded as D264, §16).

**As built (Phase 6, HK, 2026-10-09):** `ClassFileRefs.classReferences` returns the names in internal form
(`java/lang/String`, the form the JVMS calls binary names in internal form), in constant-pool order without
duplicates, an array class entry as its element class (`[Ljava/lang/String;` as `java/lang/String`) and an array of a
primitive type not at all; the class adds `majorVersion(byte[])` (the spy jar's `--release 8` check of §3.2 step 3)
and `thisClass(byte[])`. `ByteVerification.compare` answers `RESIDUE` when `after` references a class under the
prefix (dots read as slashes), whatever the byte comparison says, else `IDENTICAL` or `FOREIGN_CHANGE`. **A batch needs
the classes' sizes, which the signature above cannot know** (D170): `RetransformBatches` has the nested interface
`Sizes { long bytes(Class<?>) }`, the four-argument `of(List<Class<?>>, Sizes, long maxBytes, int maxClasses)`, and the
three-argument form above using `CLASS_FILE_RESOURCE` (the size of the class's `<name>.class` resource from its
defining loader, the bootstrap's through the system loader); an unknown size counts as `maxBytes`, a class larger than
`maxBytes` gets a batch of its own (the size limit that refuses it is the caller's), and the input order is kept. The
hooks engine passes the sizes of the bytes its recorder captured.

**As built (Phase 6, HK, 2026-10-09):** `ERROR` also covers a class whose retransformation failed (the
pre-filter or Byte Buddy threw, or the class grew past the size bound), until its next successful retransformation.
`isSecretKey` and `redactText` are implemented by `CoreContext` over the runtime's `Redactor` (`isSecret`, `text`)
and forwarded by `ModuleContext`; the default `isSecretKey` applies the protocol's `SecretKeys` rule. `HookEngine`'s
methods throw `RpcException` with the primitives' codes, as its Javadoc says.

**Fix wave (Phase 6 review, FA, 2026-10-09; L1-F2):** the `ERROR` row's "redacted" is built where a failure's text
leaves the engine, whichever step produced it: the reasons of `hook.install`'s statuses, the `reason` of
`hook.remove`'s `restored` lines (and so of `HOOK_VERIFY_FAILED`'s `data.restored`), `hook.health`'s `ERROR` reason
and the reason of the capability `hook.<id>` pass through `ctx.redactText` and are cut at 500 characters, as a thrown
message is (§4.3). The build passed the exception's class and message through unredacted (D242). (The instrument
layer hands a failed retransformation's `ClassFormatError` or `UnsupportedOperationException` without the JVM's
message; Byte Buddy's and the pre-filter's messages name types.)

**(Phase 8, built 2026-10-19, EH; P8 §6.5, §6.6.)** `HookEngine` gains `default List<String> deny()`, the catalogue
patterns the engine holds (empty by default; the hooks engine answers its held list, sorted); the four-argument
`install` carries no deny (D575). `HooksModule.install` registers the engine a second time, as the catalogue's
`ClassDeny` in the shared registry, so that the Byteman module asks the held list and tells it apart from a domain
module's by `instanceof HookEngine`. `hook.health` gains `classes` (the ledger's classes with their code sources and
hook ids, `deniedNow` with the reason when the class is denied now) and `deny`; a hook installed before its class was
denied keeps firing and its health carries `deniedNow: true` (D568).

### 3.7 Options

Agent options (P1 §2.4's table gains these rows; unknown keys were already kept, so no core change is needed for
the `hooks.*` keys; a `hooks.*` value that is out of range or not a number fails the module's install as a `FAILED`
record naming the key, §3.2):

| Key | Default | Meaning |
|---|---|---|
| `optionalModules` | – | file stems of jars under `<home>/optional/` to load as module jars, comma-separated (D114, §2.1). Read by the core |
| `hooks.spyJar` | `<home>/skillroy-agent-spy.jar` | the jar appended to the bootstrap search path at the first install, after the content check of §3.2 step 3; a jar this option names must also pass the shared jar rule (`JarTrust`, §3.6; Phase 6 security fix, pass 2, P2-L1-F5, built 2026-10-09, SA) |
| `hooks.maxClassBytes` | `262144` | a class whose class file (as the JVM hands it to the transformer) is larger is never retransformed (design §2.6: "very large classes are never retransformed; BS3 sets the size limit"). BS3 RESULT §4, §9 fact 18: at 273 KiB and 3,000 methods the install took 53-59 ms on Java 8 and 31-37 ms on Java 25 and stopped the world for about 11 ms on Java 8, below one 60 Hz frame; at 916 KiB the Java 8 stop is 26 ms, at 1.8 MiB 51 ms. For scale, the desk's largest class is 35 KB. Also the byte bound of one batch (§3.2). At most 16 MiB |
| `hooks.maxMethods` | `2000` | the second bound of the same limit: Byte Buddy's cost follows the method count rather than the bytes (1.8 MiB of code in 30 methods: 82-88 ms on Java 8; in 20,000 methods: 346-410 ms; fact 18); a class declaring more methods is never retransformed. At most 65535 |
| `hooks.maxClasses` | `64` | the most classes one engine may hold in its ledger; the 65th spec is `UNAVAILABLE("ledger full")`; also the bound of the classes one Byteman rule may retransform (§7.3) |
| `hooks.detachBudgetMs` | `8000` | the restore budget of `uninstall` (§3.5); 1000 to 9000 so that it fits the core's 10 s |
| `hooks.capture.maxBytes` | `16384` | the budget of one `CAPTURE` event's encoding, counted on the input (§4.3) |
| `byteman.jar` | – | the user-supplied Byteman jar, an absolute path (§7.4); **no default and no environment fallback**: without the option `rule.engine` is `UNAVAILABLE("no byteman.jar configured")` |

**As built (Phase 6, HK, 2026-10-09):** the ranges the contract left open (D176): `hooks.maxClassBytes` 1024 to
16 MiB, `hooks.maxMethods` 1 to 65535, `hooks.maxClasses` 1 to 1024, `hooks.detachBudgetMs` 1000 to 9000,
`hooks.capture.maxBytes` 1024 to 1 MiB; a value out of range or not a number fails the install with
`IllegalArgumentException("option <key> …")`, which the core records as the module's `FAILED` message.
`hooks.spyJar` is read live through `AgentContext.option`.

Fix wave (Phase 6 review, FB, 2026-10-09): the Byteman module, which reads the same five keys as the bounds of one
rule (§7.3), accepted `hooks.maxClasses` from 1 to 4096; it now has the ranges above for all five (`Settings`,
`SettingsTest`), so one key has one range and the bound a rule may reach is the one D176 fixed (findings L1-F4, L2-F4).

**(Phase 8, built 2026-10-19, EH; P8 §6.5.)** A new key, `hooks.deny` (A81): comma-separated class patterns
(`ClassPattern`), trimmed, empty items dropped, at most 64, read at install by `HookOptions.read` and by the Byteman
`Settings.read` alike, so one key rules whichever module is loaded; an invalid pattern or more than 64 is
`IllegalArgumentException("option hooks.deny: pattern <i> <violation>")` (or "more than 64 patterns"), the module's
`FAILED` record naming the key.

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

**As built (Phase 6, HK, 2026-10-09):** the types are as above, with public constructors taking every field and these
additions, so that the controller's catalogue reader and the agent apply one rule: `HookSpec.violation()` returns the
first name-rule violation of §3.3 item 1, §4.3 and §4.6 as a `HookSpec.Violation {field, message}` (the field relative
to the spec: `id`, `target.class`, `target.method`, `target.descriptors[i]`, `emits`, `note`), or null; the agent
answers it as `INVALID_PARAMS` with `data.param` `specs[n].<field>`, the reader reports it with the file's line.
**(Fix wave, FA, 2026-10-09: `<init>`, `HookSpec.CONSTRUCTOR`, is no violation but the agent's per-hook state of
§3.3 item 1; `<clinit>` is one; §3.3's note, D239.)**
`HookSpec` also carries `ID_REGEX`, `MAX_ID_LENGTH` (64), `MAX_NOTE_LENGTH` (200), `TOPIC_PREFIX` (`hook.`),
`RESERVED_IDS` (`engine`, `probe`, `install`, `stub`, `remove`, `health`, `events`: the hook ids whose `hook.<id>` is
the engine's capability or a primitive's name), `topic()` (`emits` or `hook.<id>`) and `capabilityId()`
(`hook.<id>`). `toJson()` writes an optional key only when it says something (`emits`, `capture`, `note` when set,
`secretArgs` when not empty, `secretResult` when true), so the hash of a spec read from JSON with the defaults spelled
out equals that of one without them. `HookSpec`, `HookTarget` and `HookCapture` refuse unknown keys; `HookProbe`,
`HookStatus` and `HookHealth` are answers and are read leniently, as `PrimitiveDescriptor` is. **`HookProbe` gains
`loaderIds?: [string]`** beside the count `loaders` (D169): §4.1 lets `target.loader` name "the hexadecimal identity
the agent reported for a loader in an earlier probe answer", and the shape had no field that reports one; each entry
is `system` for the system loader and otherwise the loader's `System.identityHashCode` in hexadecimal. `HookProbe.state`
is a `CapabilityState`, and `spyVisibility` the nested enum `HookProbe.SpyVisibility`. **`HookStatus.state`** is the
`CapabilityState` of the hook's capability: from `hook.install`, `OK` (strategy `advice`) for an installed hook and the
probe's `DEGRADED` or `UNAVAILABLE` with its reason otherwise, `capability` `hook.<id>` when declared and else null;
from `hook.remove`, `OK` for a hook removed and restored, `DEGRADED` with the verification's reason, `UNAVAILABLE`
with `"not installed"` for an id that was not installed or `"residue after remove"` for a class left `FAILED`, and
`capability` the withdrawn id.

**(Phase 8, built 2026-10-19, EH; P8 §6.3.)** `HookTarget` gains `codeSource?` (a Java regular expression matched
against the whole code-source name; the five-argument constructor beside the kept four-argument one, `withCodeSource`,
`codeSourceViolation`, `MAX_CODE_SOURCE_LENGTH` 200); `toJson` writes the key only when set, so every spec without it
keeps its hash, and `fromJson` stays strict on every other key (a 0.1.0 agent refuses a qualified spec, D560).
`HookSpec.violation()` gains the field `target.codeSource` (longer than 200 characters, or not compilable).
`HookProbe` gains `codeSources?` (at most 16, aligned with `loaderIds`; the eleven-argument constructor beside the kept
ten-argument one; read leniently). The new `ClassPattern` is the deny grammar both sides apply (P8 §6.3).

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
| `hook.install` | `{specs: [HookSpec], catalogue: {id, version?, hash, application?}, replace?: false}` | `{catalogue: {id, hash, installedAt, alreadyInstalled}, hooks: [HookStatus], touched: [{class, loader, bytes, methods, ms}], otherAgents: […], replaced?: {restored: [{class, loader, verified, reason?, foreignEvents}], verified: OK\|DEGRADED\|FAILED}}` (`replaced` only when `replace: true` ran a remove; P2-L2-F3 below) | **not mutating** (D119: an `OBSERVE` or `CAPTURE` hook changes bytecode, not behaviour, and the design's rule is that read-only mode disables `STUB` hooks); idempotent by catalogue hash: the same `catalogue.hash` and the same spec set answer `alreadyInstalled: true` and the current states, installing nothing; a different hash or id while a catalogue is installed, **or the same hash with a different spec set** (the controller's hash is then wrong: `data.reason: "spec set differs under the same hash"`), is `HOOK_CONFLICT` unless `replace: true`, which runs `hook.remove` of everything first | 60000 | `WORKER` |
| `hook.stub` | `{id, value?, clear?: false}` | `{id, armed: bool, returnType}` | **mutating and destructive** (`_meta.confirm=true` required: a stub changes the target's behaviour for every user of the JVM, which is what `destructive` means in this agent; D119); refused with `INVALID_PARAMS` for a hook whose mode is not `STUB` or that is not installed; `READ_ONLY` in read-only mode by the dispatcher's gate; publishes `hooks.stubbed {id, armed, by: "call"}` (§4.3) | 10000 | `WORKER` |
| `hook.remove` | `{ids?: [id]}` (absent or empty: every hook) | `{hooks: [HookStatus], restored: [{class, loader, verified: OK\|DEGRADED\|FAILED, reason?, foreignEvents}]}`; the error `HOOK_VERIFY_FAILED` when any class reports `FAILED` (the restore of the others still happened) | **not mutating, never gated by read-only or confirm**: it restores; no capability | 30000 | `WORKER` |
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

**As built (Phase 6, HK, 2026-10-09):** the six primitives, flags, timeouts and threads are as in the table.
`hook.probe` and `hook.install` take at most 64 specs. `hook.health` also answers `otherAgents` and `spy.onBootPath`
(D180: `skillroy hooks status` prints `otherAgents`, §6.2, and reads them from `hook.health`); without a `Spy` on the
bootstrap path `spy.version` is null and the counters are zero. `hook.events` refuses a `limit` outside 1 to 1000 with
`INVALID_PARAMS`, and (gap 63, 2026-10-11) an `id` that is not a hook id (`HookSpec.ID_REGEX`: no comma or wildcard widens
its filter), and answers `hook.*` topics only; for an id that is not installed it filters on `hook.<id>`. `hook.stub` refuses a JSON object or array
as `value`.

As built (Phase 6, CP): `ToolProjection.NEVER_OFFERED` and `ToolProjection.HOOK_READS`; the three reads are offered by
every policy, the session service's included, whose policy otherwise offers no primitive read (P5 §12.4) (D187);
`PlanPolicy` refuses a base verb's and a pack verb's call of any other `hook.*` or `rule.*` primitive.

**(Phase 6 security fix, pass 2, P2-L2-F3, built 2026-10-09, SE) `replace: true` reports its remove.** The remove of
everything that `replace: true` runs first is a `hook.remove {}` whose `HOOK_VERIFY_FAILED` the build discarded: a
class that kept a reference to `Spy` (another transformer re-planting advised bytes, A12) was then installed over and
answered as a plain success, and the controller's record said `replaced: true` with no error (when the new version of
the catalogue dropped the residue's id; when it kept the id, the hook was merely `UNAVAILABLE`). The install's answer
now carries that remove's outcome as `replaced: {restored, verified}` (`restored` as `hook.remove`'s, `verified` the
worst of its classes), and the new version is installed as before (its own refusals apply: a residue's id is refused,
D236). The controller's `HookInstaller` maps `replaced.verified: FAILED` to ERROR / ENVIRONMENT as it maps the
remove's error (§5.3 step 5). The SPI's `HookEngine.install(…, replace)` answers the statuses only, as before; a
residue it leaves is in `health`. D119 and D236 are unchanged.

**(Phase 8, built 2026-10-19, EH; P8 §6.4, §6.6.)** `hook.install`'s `catalogue` gains `deny?: [pattern…]`: checked
before the lock (a list of at most 64 class patterns; `INVALID_PARAMS` with `data.param` `catalogue.deny[i]` or
`catalogue.deny`), merged under the lock **before the conflict check** into the engine's held list (add-only for the
engine instance's life, kept across `hook.remove {}` and `replace: true`, at most 256: beyond, `INVALID_PARAMS` "the
engine holds 256 deny patterns; restart the target"), and echoed in every answer, `alreadyInstalled` included, as
`catalogue.deny` (the held patterns sorted; an empty list when none is held). A zero-spec install delivers a deny and
installs nothing. `touched` lines gain `codeSource`; `hook.probe`'s answers carry `codeSources`; `hook.health` gains
`classes: [{class, loader, codeSource, bytes, methods, hooks, deniedNow?}]` (sorted by class and loader, at most
`hooks.maxClasses`) and `deny: {catalogue, from, option, modules}`, and a hook's health map `deniedNow: true` when one
of its classes is denied now (`HookHealth` is untouched).

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
  call) (Phase 8, built 2026-10-19, EW; P8 §5.1: a `CAPTURE` payload, and a Byteman `rule.fired` one, then pass the
  ring's mask pass a second time at publish, under `event:hook.<id>` and `event:rule.fired` with the dotted columns
  `args.0`, `bindings.0`, …, and the core's secret-key rule; harmless for an idempotent policy, a policy that is not
  checks `sourceId`; `CaptureTest`, `BindingEncoderTest`, `EventBusTest`), so a catalogue author must mark secret
  arguments and results: the catalogue reader warns on a `CAPTURE` hook
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

**As built (Phase 6, HK, 2026-10-09):** the encoding is as specified, with these readings (D182): a number
is encoded when its class is a JDK box (`Integer`, `Long`, `Short`, `Byte`, `Float`, `Double`), `BigDecimal` or
`BigInteger` exactly, so that no application subclass's method runs; a NaN or infinite number becomes its text (JSON
has none, and the event would be refused); `BigDecimal` is plain text unless its scale is extreme (then its
scientific text, so that `toPlainString` cannot allocate a million digits on the application's thread); a `BigInteger`
above 6,000 bits is cut (`null`, `truncated`), **and so is a `BigDecimal` whose unscaled value is (Fix wave (Phase 6
review, FA, 2026-10-09), the rule FB's probe found for both encoders: the build's guard read `precision()` and then
made the whole `toString()` of a huge unscaled value, about 2.7 s for 4 million bits on the application's thread,
against microseconds for `unscaledValue().bitLength()`; within 6,000 bits the precision is at most about 1,800
digits, so the precision test is gone)**; `Date` and `java.time` are encoded for `java.*` classes only. A collection
or map is iterated only when its class is one of an allow-list of exact JDK classes whose iteration takes no lock and
calls no method of an element: `ArrayList`, `LinkedList`, `ArrayDeque`, `PriorityQueue`, `HashSet`, `LinkedHashSet`, the
two `EnumSet` classes, `HashMap`, `LinkedHashMap`, `TreeMap`, `EnumMap`, `IdentityHashMap`, `Arrays$ArrayList`, the
empty and singleton classes of `Collections`, `ConcurrentHashMap`, `ConcurrentLinkedQueue`, `ConcurrentLinkedDeque`,
`CopyOnWriteArrayList`, `CopyOnWriteArraySet`, `ConcurrentSkipListMap` and every `ImmutableCollections$*` class;
everything else is a handle: an application's own collection class, a subclass, every view and wrapper (unmodifiable,
synchronized, checked, a sub-list, a sub-map, a key set), the collections whose iteration takes a lock (`Vector`,
`Stack`, `Hashtable`, `Properties`, every blocking queue), and `TreeSet` and `ConcurrentSkipListSet`, whose instances
may themselves be views over a bounded sub-map (`subSet`, `headSet`, `tailSet`). **(Fix wave (Phase 6 review, FA,
2026-10-09; L2-F2, L2-S1):** the build iterated every `java.*` class but a deny-list, which let `DelayQueue` through and
iterated every view: "a JDK wrapper such as an unmodifiable view is iterated" contradicted the rule's own reason, since
`unmodifiableList(vector)` and `unmodifiableMap(synchronizedMap(m))`, the documented idiom for iterating a synchronized
map, took the wrapped collection's lock on the application's thread, and `TreeMap.headMap` ran the application's
`compareTo`; D237.) A map key is the text of a JDK scalar, else the key object's handle. A mask policy that returns
something other than a string, a number, a boolean or null redacts the value. **The output bound equals the input
budget** (`hooks.capture.maxBytes` both ways, §4.3), so an event whose encoding used the whole budget carries JSON
beyond it and drops `args`, then `self`, then `returned` (D181): a capture keeps its values whole only well below the
budget. A policy that throws makes the handler throw one preallocated `MaskFailure` to `Spy` after the event is
published, which is how `Spy.counters()[2]` counts it.

**Fix wave (Phase 6 review, FA, 2026-10-09; L2-F11):** `thrown.message` calls the throwable's `getMessage()` on the
application's thread, which for an application's exception is application code: accepted, as the one exception to "no
`toString()` of an application object is ever called". The application's own messages are the useful ones; a check
for an overriding `getMessage` would resolve methods through the application's loader on that thread (it may load
classes and take the loader's locks), which is worse than the risk it removes; a throw is caught, the text is redacted
and cut at 500 characters, and A34 already leaves event payloads otherwise unredacted (D238).

**(Phase 6 security fix, pass 2, P2-L2-F7, built 2026-10-09, SE) Every captured string passes `redactText`, as a
thrown message does.** A captured `String` (an argument, `self`, the return value, an element of a list, a value or
the text of a key of a map) passes `ctx.redactText` after its cut (2000 or 256 code units) and before the
`MaskPolicy`s: the core's rule hides a secret `key=value` pair's value and the password of URL user-info under the
agent's keys (the protocol's rule, the `redact` option, every `RedactionKeys`), so that a connection string or an
option string carried by a hooked method's argument does not reach `hook.events` and `hook.health.last` in clear when
the catalogue author did not mark the index. The marks (`secretArgs`, `secretResult`) and the policies stay as above;
this is a floor under them, not a replacement. Why the floor is needed: the controller's run log masks by value only
(the credentials file's values: a captured `password=…` reached `run.jsonl` through an expectation's `actual`), and
the MCP server's masking has no user-info rule (a `jdbc:x://u:pw@h` reached an MCP client through `hook.events`,
which every policy offers). The cost on the application's thread is bounded by the cut and the input budget: the
rule is linear and lock-free (two `indexOf` scans for a text with neither `=` nor `@`), and it consults the same
`RedactionKeys` patterns `isSecretKey` already consults there; a failure redacts the whole string. Limit: a cut that
splits a secret (a user-info password before its `@`) may leave its first characters. The Byteman module's
`rule.fired` encoding applies the same rule to a bound string (§7.3). D125 and D182 are unchanged.

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

**As built (Phase 7, FX, 2026-10-11): a `hook.<id>` holds its state when `hook.install` answers** (D449). The core
probes a capability declared after the target is ready later, on an agent worker the declaration posts to, and until
that probe has run the entry reads `UNAVAILABLE("not probed yet: the target is not ready")` (P1 §5: the pending case;
since gap 92, 2026-10-11, a late declaration reads `UNAVAILABLE("not probed yet: declared after the target was ready, its
probe is queued")`, P7 §16 gap 92);
`hook.install` declares its `hook.<id>` after the target is ready. The engine therefore also **sets each `hook.<id>` at
once to its probe's first answer** (the health of the table above, computed on the install's thread under the engine's
lock, right after the declaration and only while no detach has begun); the declared probe stays for
`agent.capabilities {reprobe}`, and the worker's later run answers the same. `hook.install`'s answer (and the SPI's
`install`'s return) thus comes after every declared `hook.<id>` holds a state of this table, so the matrix read of §5.3
step 6, `ExecutorSession.open`'s, the MCP server's and `skillroy hooks install`'s never see the pending state. Before
this, a read that overtook the posted probes archived the installing run's matrix with some `hook.<id>` pending: a
hash of its own for the first run of a target, which failed the "one matrix per target" check of §13.2 item 4 (once
in Phase 6, once in the Phase 7 matrix; reproduced under CPU load, the pending ids the only difference), and a guard on
a hook in that first run would have read it not `OK`.

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

As built (Phase 6, CP): `HookCatalogues.read` throws `ScenarioException` (source and line); `ofPack` reads the resource
once per pack class and id and throws `IllegalStateException` (the resource and the line) on every call for a
malformed one, which `PlanCompiler` reports as a compile error and the installer as ENVIRONMENT. A class is "under
`packages`" when it is in one of them or below; `packages` may be absent, and every entry is then off unless written
`enabled: true` (with the warning). A `reflect` fallback is an error at its line. The secret-name warning is given for
disabled entries too (the sample's `sign.on.checked` has it: it captures `passwordMatches`' boolean result without
`secretResult`). The entry hash is the protocol's `HookSpec.hash()` of `HookEntry.toSpec()`; `emits` is kept as written
(an explicit default topic is a different spec from an absent one). `HookCatalogue.descriptor()` is the
`{id, version, hash, application?}` that `hook.install` takes; `HookCatalogues.overlayResource` and `resourceName` serve
an overlay's resource. (D184)

**(Phase 8, built 2026-10-19, EH; P8 §6.8.)** The catalogue gains `deny:` (at most 64 class patterns, distinct; an
**enabled** entry on a denied class is an error at its `class` line, a disabled one a warning: "it can never be
enabled") and a map form of a `packages` item, `{name, codeSource?}`, whose qualifier every entry under it inherits
(the longest name wins; an entry's own `target.codeSource` wins over it), baked into the entry's `HookTarget` by
`HookCatalogue.Builder.build()` so that the spec, the entry hash and the catalogue hash agree. `packages()` stays the
names; `packageRules()`, `deny()`, `denied(className)`, `MAX_DENY` and `Builder.packageRules`/`deny` are new, and
`HookEntry.withTarget`. The hash gains `deny: [sorted]` only when it is non-empty, so every catalogue that uses
neither key keeps its hash byte for byte; `descriptor()` carries `deny` when non-empty.

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
   `ENVIRONMENT` with the agent's error as the detail. **(Phase 6 security fix, pass 2, P2-L2-F3, built 2026-10-09,
   SE)** So is a `replace: true` install whose answer's `replaced.verified` is `FAILED` (the implicit remove left a
   reference to `Spy` in a restored class, §4.2): "hook.install failed: HOOK_VERIFY_FAILED: the remove of the installed
   version (replace: true) left a reference to Spy in a restored class; `skillroy hooks status`, then `skillroy hooks
   remove`", the detail `{primitive, error: {code, message}, restored}`, the record `ok: false` with `replaced: true`.
6. The matrix is read again (it now holds `hook.<id>`, each in its health state: §4.6's as-built note of Phase 7, FX),
   archived (§6.4), and `checkRequires` runs on it. The executor then records the agent's `lastSeq` when the first
   step begins, `stepsSinceSeq`, which the `hooks` fact root reads from (§6.3).

Hooks **stay installed** after the run (D129): the next run of any scenario of the same pack finds them
(`alreadyInstalled`), which is what makes a suite of thirty scenarios cost one retransform per class; they go when a
person runs `skillroy hooks remove`, when the agent is disabled (§3.5) or when the target exits. The JUnit glue
behaves as the CLI: `-Dskillroy.hooks=on|off|auto` (default `auto`) reaches `RunOptions.hooks` through
`ScenarioTests`'s overload without options.

As built (Phase 6, CP): a catalogue without an enabled entry counts as none (`skipped: "no catalogue"` under `AUTO`;
under `ON` PRECONDITION_FAILED "… has no enabled hook in its catalogue …"). Step 4's fallback is the first `seam` whose
primitive the target serves: its descriptor in `GET /schema` names no capability, or a capability the matrix shows
`OK` (a primitive id is not a capability id); the schema is read only when a hook is unavailable, and without it a
capability of the primitive's own id decides (D186). A refused install writes the `hooks` record with `ok: false`,
`error` and the detail before the run ends. A replaced catalogue adds `replaced: true` to the record. The installer is
`HookInstaller.install(pack, agent, caps, mode, refuseArmedStubs, clock)`, `refuseArmedStubs` true for `replay` only
(a session, the MCP server and `skillroy hooks install` record armed stubs). Another value of `skillroy.hooks` is the
glue's single test `environment`.

*Fix wave (Phase 6 review, FC, 2026-10-09):* steps 1 and 2 returned before step 3's `hook.health`, so a `replay` run
with `--hooks off` (or `-Dskillroy.hooks=off`), or of a pack without an enabled catalogue entry, ran against a stub
left armed by an earlier run, a person's `hook.stub` or a domain module's SPI (`by: "spi"`), and its record explained
nothing (finding L1-S2): against D129's "CI must never run against stubbed behaviour". The armed-stub check is
therefore taken out of step 3 and made **step 0**, before steps 1 and 2 can return a skip (fix-wave decision D231):
**whenever the matrix shows `hook.engine` `OK`**, whatever the mode and whether or not the pack has a catalogue, the
installer reads `hook.health {}` (its 10 s budget) first. Under `replay` an armed stub is `ERROR` / `ENVIRONMENT`
with step 3's message, and so is a `hook.health` that fails (the run cannot show that no stub is armed); under `live`
and `approve` the ids are recorded, in the `skipped` record too (`armed: [id…]`, written only when a stub is armed),
and a failed read is left out of a `skipped` record (the run proceeds, as it would have). Step 3 uses the answer of
step 0, so an install still costs one `hook.health`. Without `hook.engine` `OK` nothing is read: a module that is not
loaded holds no stub, and a stub is armed only through an engine that is `OK` (`hook.stub` and the SPI's `stub` name
`hook.engine`; §3.2's probe keeps an engine `OK` while the slot is held by its own handler). The cost is one bounded
read per run, and only when the hooks module is loaded with a usable engine.

**(Phase 8, built 2026-10-19, EH; P8 §6.9.)** A catalogue with a non-empty `deny` is something to install even
without an enabled entry: it is sent as a zero-spec `hook.install`, under `AUTO` and `ON` alike. When the pack's
catalogue has a deny, the install answer's `catalogue.deny` (and on the `alreadyInstalled` path step 0's
`hook.health.deny.catalogue`) must hold every pack pattern, else ERROR / ENVIRONMENT "the agent does not honour the
catalogue's deny list (<missing>); upgrade the agent" with the detail `{missing, answered}` (the echo rule, D565). An
`INVALID_PARAMS` on `specs[…]` while a spec carries `target.codeSource` gets "; the catalogue uses target.codeSource,
which this agent does not know: upgrade the agent" appended (D560). Every skip of a pack whose catalogue has a deny
records `denyNotDelivered: {patterns, agentOption}`, `agentOption` read from `rule.list.deny.option` when the matrix
shows a `rule.*` capability (else, or when the answer has no `deny`, `unknown`; D566). The record gains `deny` (the
pack's patterns) and `deniedNow` (the hooks step 0's health marks so), after its other keys.

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

As built (Phase 6, CP): a session has no `RunOptions`, so `SessionOptions` gains `hooks` as its eleventh component
(default `AUTO`, the ten-argument constructor kept, `withHooks`); `ExecutorSession.open` installs under it, before the
pre-flight and the web path, writes the `hooks` record into the journal's start record (`options.hooks`) and exposes it
as `hooks()`; the MCP server keeps the switch when it fills in its session's output directory. `--hooks` with another
value is a usage error (64) before the agent is contacted (D185). (Phase 6 security fix, pass 2, P2-L6-F1, P2-L6-F4,
built 2026-10-09, SC) The MCP server keeps the switch **and hands it to every `skillroy_scenario_run`**
(`RunOptions.withHooks(session.options().hooks())`): a client's run never installs, replaces or removes hooks against
the person's switch, and on a `--hooks off` server installs nothing (P5 §9.3). **`author` accepts `--hooks` too** and
hands it to the planner's server (`mcp … --hooks <mode>` in the planner's MCP configuration, `auto` when not given)
and to its own dry-run bind and `--run`; so the option is accepted by `run`, `session`, `mcp` and
`author`. Merged with TL: in both records `hooks` is the
eleventh component and `observer` the twelfth; the ten-argument constructors give `AUTO` and `RunObserver.NONE`, every
`with…` copy keeps both, and the MCP server's copy passes both.

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

As built (Phase 6, CP): `status` reads `otherAgents` from `hook.health`'s answer (D180), so it calls nothing but
`hook.health` (no `hook.probe`). A refused `install` is exit 1 for
PRECONDITION_FAILED and ENVIRONMENT alike (another catalogue on the target is refused by the installer before
`HOOK_CONFLICT` could be).

**(Phase 8, built 2026-10-19, EH; P8 §6.10.)** `status` prints, after "other agents", `deny (target): option <p, q |
none>; catalogue <from ids> <p | none>; modules <n>` (from `hook.health.deny`; `unknown (the agent reports no deny
list)` against an agent without it) and `deny (pack catalogue <id>): <p | none>`; after the hook table the table
`CLASS LOADER CODE SOURCE HOOKS` from `health.classes` ("no class touched" when empty; `DENIED: <reason>` in `HOOKS`
for `deniedNow`; nothing when the agent reports no classes); the pack table gains `CODE SOURCE` (`-` without a
qualifier). `--json`: `health` raw; `catalogue` gains `deny` and `packages: [{name, codeSource}]`. Exit codes
unchanged.

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

As built (Phase 6, CP): the run's provider is `BaseFacts.hooks(LongSupplier since)`, put into the run's facts as the
`web` root is (a pack root `hooks` hides it); `stepsSinceSeq` is the start mark's `seqAfter`; `lastFiredMs` is the
newest event's `ts`; `NOT_READY` also gives the empty list, and the same three codes from `hook.events` do too.
`BaseFacts.all()`'s own provider counts from 0, and an `ExecutorSession` counts from its own start mark. The
headless test of §13.1 item 7 drives a scripted hooks module in `HeadlessDesk` (`launch(env, hooks, swingTree)`):
the matrix declares `hook.engine` and `hook.limit.applied`, `hook.health` names the pack's catalogue as installed, and
one `hook.limit.applied` event is published for every return line the engine commits, before it asks for the next
input, with the signed-on code and the limit the desk's access rules give (D192). Scenario E's seed bindings are
approved by `ResolutionChecks` (label `approve`) and written under `-Dskillroy.bindings.update=true`.

*Fix wave (Phase 6 review, FC, 2026-10-09):* `hook.events` answers the oldest events first and at most 1000 (§4.2),
so one call with `limit: 1000` made `count` stop at 1000 and `last` the thousandth fire, not the newest, for a hook
that fired more often since the mark (a hot path, or a long `ExecutorSession`, whose window runs from its start mark):
a wrong verdict on `hooks[id=…].last` (finding L1-S3). The root now **pages** (fix-wave decision D232): while a page
is full it asks again with `since` = the page's last event's `seq`, at most **10 calls** per hook (10,000 events, the
default `eventRing`, which is all the ring holds by default); `count` is the sum and `last` the newest page's last
payload, exact whenever a page comes back short. When the tenth page is still full the entry gains
**`truncated: true`**, `count` is a lower bound, and `last` and `lastFiredMs` come from one more read from
`max(cursor, S − 1000)`, `S` being the agent's sequence after the tenth call (`RpcResult.seqAfter`): the newest of the
bus's last 1000 events is then the hook's newest fire at `S` or later; when the hook did not fire among them, `last`
and `lastFiredMs` are null, so that an expectation on `last` does not resolve rather than hold on an older payload.
Events the ring evicted before the read (`eventRing`, `eventRingChars`, P1 §2.4) are not counted either: a `count` above
the ring's retention is a lower bound, whether or not `truncated` says so. An event without a numeric `seq` ends the
paging with `truncated: true`.

*(Phase 8, built 2026-10-19, EX; P8 §8.6, D602.)* An eighth base root `events` stands beside `hooks`: every event of the
bus since the same start mark (`stepsSinceSeq`; a session's own mark), oldest first, through `evt.query` with the same
paging (1000 a page from each full page's last `seq`, at most ten pages). Its loss rule is stricter than this root's,
because a `{size: 0}` or a delta rests on the count: where `hooks` answers a lower bound with `truncated: true`, the
`events` root's read fails (ERROR / ENVIRONMENT) when the ring evicted any event since the mark (the first page's
`oldestSeq` past `since + 1`, a gap in the sequence) and when the tenth page is full.

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
| `waiver.evaluated` | `CAPTURE` | `WaiverEngine.evaluate(List, LocalDate) List` | `result: true` | `seam: lending.waiver.evaluate` | the waivers of a `DUE`; the result is a list of application objects, so the event carries handles (§4.3): the demonstration that no application state leaks (**fix wave, FA:** the desk returns an unmodifiable view, so the event carries one handle, D237) |
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

As built (Phase 6, CP): `PlanExecutor.CAPABILITIES_FILE`; the file is the masked pretty JSON of `toJson()`; the record
is written also without an output directory (`file: null`) and before the `requires` check, so that a run refused by
`requires` still archives what it saw. The MCP server reads the matrix again after its session's install and writes
`<out>/capabilities.json`.


*(Phase 7, built 2026-10-10, VC; P7 §8.5.)* The matrix archived is the merged one (the agent's with the contributed
`inference.*` ids, each `UNAVAILABLE (replay)` in `replay`); `capabilities.json` keeps the wire shape and carries no
source; the `capabilities` record gains `agentHash`, the hash of the agent's matrix after step 0b, which is what the
"one matrix per target" check compares (D130 kept on the agent's), and `contributed`; a session merges with
`SessionOptions.host` at open and writes `capabilities.json` once.

*(Phase 8, built 2026-10-19, EP; P8 §9.1.4.)* `capabilities.json` holds the running pack's own capabilities too (the
ids `DomainPack.capabilities()` declares, evaluated after the contributors' merge; wire shape, masked, no source), and
the `capabilities` record gains `pack`, the evaluated ids sorted `{id, state, reason, strategy, scope, cached,
error?}`, only when the pack declares any (a pack without them writes the record as before). `agentHash` stays the
agent's matrix after step 0b, so the "one matrix per target" check is unchanged.
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

As built (Phase 6, BW, 2026-10-09): BS14 ran on the Java 8 and Java 25 targets of the arm64 image and **passed with
conditions** (`../../spikes/BS14/RESULT.md`): items 1-6 on both targets, and the reuse of a parked `Retransformer` by a
second module instance. Its five conditions are folded into §7.3-§7.5 as "as built": (1) the option string has no
`prop:` (it prints `Setting …` on the host's stdout, and Byteman reads `org.jboss.byteman.verbose` by its presence, so
`=false` turns verbose output **on**), and `listener:false` must precede `manager:` (after it, it clears the manager);
(2) Byteman does not match a varargs helper method; (3) a trigger-time failure is printed on the host's stderr through
Byteman's `Helper` trace map unless the module puts its own streams there, and a transform-time failure is printed
nowhere (only `listScripts` shows it); (4) a sys-jar method with a Byteman-typed parameter must be branch-free, or its
`StackMapTable` puts a `CONSTANT_Class` of Byteman into the constant pool; (5) the byte check needs a recorder ahead
of Byteman's transformer and one behind it. Byteman's `manager:` needs no `listener:true`; `Main` loads the manager
through the system class loader and resolves `initialize(Retransformer, String, Integer)` by `getMethod` (static, public),
falling back to `initialize(Retransformer)`; it is called once, on the thread that called `premain`, with `hostname` and
`port` null. Byteman builds one helper adapter per firing through the helper's declared `(Rule)` constructor. No
listening socket and no thread before the load, after it, or after the unload; no JEP 451 line.

### 7.3 Primitives (module `byteman`; `rule.check`, `rule.submit` and `rule.list` name the capability `rule.engine`)

| Primitive | Params | Result | Flags | Timeout |
|---|---|---|---|---|
| `rule.check` | `{rule: <text>}` | `{ok, name, class, method, errors: [], warnings: [], typeChecked: bool}` | read-only: the lint (below), then Byteman's `RuleCheck` **only when Byteman is already loaded** (§7.4) **and the rule's class is already loaded and defined by the system loader** (found through the indexed `getAllLoadedClasses()`, never by loading); otherwise `typeChecked: false` with the warning "type check skipped: Byteman is not loaded (the first `rule.submit` loads it)" or "type check skipped: class not loaded"; `RuleCheck` runs with `setPrintStream` on a buffer whose text goes into `warnings`, so nothing reaches the host's console. **`rule.check` never loads Byteman and never loads a target class** | 30000 |
| `rule.submit` | `{rule: <text>, checkOnly?: false}` | `{name, installed: bool, classes: [{class, status}], messages: []}` | **mutating and destructive** (`_meta.confirm=true`; D133: every rule is a bytecode change and a `DO` clause can change behaviour in ways no lint can bound, so the person confirms every submit); loads Byteman on its first call (§7.4); `RULE_REJECTED` when the lint, the class bound or the type check refuses, with `data {errors}`; `checkOnly: true` is `rule.check` under this primitive's gates, kept because design §2.4 names the parameter | 30000 |
| `rule.unload` | `{name}` | `{removed: bool, restored: [{class, verified: OK\|DEGRADED\|FAILED, foreignEvents}]}`; `HOOK_VERIFY_FAILED` when a class reports `FAILED` | like `hook.remove`, **neither mutating nor destructive, never gated**: it restores (D119, D133); a rule can only have been installed by a confirmed `rule.submit`, which read-only mode refuses; no capability | 30000 |
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
Fix wave (Phase 6 review, FB, 2026-10-09): the bound runs **before Byteman is loaded** (§7.4), over the loaded classes
read once, so that a rule it refuses appends nothing to the system class path and runs no `premain` (both appends
are permanent); the type check, which needs Byteman, stays after the load (finding L2-F5).

**(Phase 6 security fix, pass 2, P2-L2-F1, built 2026-10-09, SE) The JDK's and the agent's classes are refused by
name, loaded or not.** The loader rule above judges the classes loaded at the submit only, while Byteman injects into
every class of the name its transformer sees later, in any loader. A confirmed rule on a JDK class outside the lint's
prefixes that the target had not loaded yet (`org.xml.sax.helpers.LocatorImpl`, `org.w3c.dom.*`, `org.ietf.jgss.*`:
the "trace the SAX locator" kind of rule §7.1 describes) matched nothing, passed, and at the class's first load got
Byteman's trigger code, which the bootstrap loader cannot link: every load threw `NoClassDefFoundError` into the
host, and on Java 25 the class could not be restored (`rule.unload` failed with `InternalError … invalid class`; on
Java 8 every call threw while the rule was installed). An `INTERFACE` rule escaped likewise through a JDK implementor
loaded later, even when an application implementor made the match non-empty. Now, **before the count and before the
load**, the rule's `CLASS` or `INTERFACE` name (the `^` stripped) is looked up as a class file through **the JDK's
loaders** (the platform loader on Java 9 and later, reached reflectively; the extension loader on Java 8; each
delegates to the bootstrap loader, and a resource lookup defines nothing) and through **the agent's own loaders** (the
module's chain, their own jars only, `URLClassLoader.findResource`): a name the first finds is
`RULE_REJECTED("<name>: class is the JDK's")`, one the second finds `RULE_REJECTED("<name>: class is the agent's
own")`. A JDK class can only extend or implement JDK types, so the `^` and `INTERFACE` paths close with the `CLASS`
path, and a rule on an application class the target has not loaded yet stays accepted (`rule.check`'s "type check
skipped: class not loaded" keeps its use). A prefix list (`org.w3c.`, `org.xml.`, …) was not added to the lint: it
would also refuse application libraries in those namespaces. **Residual, recorded:** a copy of an accepted application
name defined after the submit (another loader's) is not counted against `hooks.maxClassBytes` or `hooks.maxMethods`;
a load-time transform is not the stop-the-world pause the bound exists for. A name only another module's jar holds
(a sibling module loader, not the Byteman module's chain) is not looked up; its loaded copies meet the loader rule.
D132-D134 and D204 are unchanged; `boot:` stays unused (B8/D132).

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

Fix wave (Phase 6 review, FB, 2026-10-09): **what the lint promises, and what it does not** (finding L2-F8; D133). It
promises the structure above, that no Byteman built-in is named (the module's helper does not extend Byteman's
`Helper`, so they would not resolve anyway), and that none of the listed entry points is named as an identifier or a
qualified-name prefix. It does **not** promise that a rule cannot block, write, kill, create processes or reach
reflection: a `DO` clause may call any method of a bound object and any public static of a class the trigger class's
loader sees, the JDK's included (`$0.wait()`, `java.util.concurrent.locks.LockSupport.park()`, `new
java.util.concurrent.CountDownLatch(1).await()`, `java.beans.Beans.instantiate(…)`, a `java.util.logging.FileHandler`,
`ProcessHandle`), and no list of names closes that; the sentence above lists the reasons for the entries it names,
not a property of every accepted rule. The list is not widened name by name (a longer list would read as a promise
it cannot keep); **`confirm` on every `rule.submit` is the gate** (D133: "no lint can make a `DO` clause with calls on
bound objects safe"). `RuleLintTest.theLintIsATripwireNotASandbox` pins four such accepted rules, so that a wider
promise is a change of this paragraph first.

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

As built (Phase 6, BW, 2026-10-09):

- **The helper.** `fired(Object)` to `fired(Object, …, Object)` (one to eight bindings, fixed arity: BS14 condition 2;
  D195); the one constructor is the branch-free `(org.jboss.byteman.rule.Rule)`, which keeps the rule as `Object` in
  a non-final field; the name, trigger class and method come from the rule's `getName()`, `getTriggerClass()`,
  `getTriggerMethod()` by reflection (cached `Method`s). `count`, `countOf`, `flag`, `flagged`, `clear` are per rule
  (keyed by the rule's name), at most 1024 keys in the JVM; the package-private `forget(rule)` drops a rule's keys at
  its unload. `format` answers a string (cut at 256), an enum's name, a boolean, a character or a number of the JDK
  (`getClassLoader() == null`), else `<class name>`. The handler slot keeps counters `{fired, unhandled, failures}`.
  Fix wave (Phase 6 review, FB, 2026-10-09): `format`'s numbers are the JDK's exact types only (the six boxes,
  `BigDecimal`, `BigInteger`: an accumulator's `toString()` applies its function), a `BigInteger` or a `BigDecimal`'s
  unscaled value above 6,000 bits is `<class name>` before any text is made, and a `BigDecimal` of extreme scale is
  its scientific text, D182's rules (finding L2-F3).
- **`RuleManager`.** `initialize` is branch-free and delegates to a private `accept(Object)` (BS14 condition 4); it
  stores the **first** `Retransformer` of the JVM only and ignores every later call, so that no class can replace or
  clear what Byteman handed over through the public method (D200). The slot holds the `Retransformer` when parked, or
  the list of the names of rules a cut-short uninstall left behind (§7.5; D201); the probe reads the private slot by
  reflection without taking it (no third accessor). `SysConstantPoolTest` holds the jar's three class files to
  `CONSTANT_Class` entries under `java/` and `ai/skillroy/` and major 52.
- **The lint** as specified, and: `CLASS`/`INTERFACE` must be a fully qualified name (an unqualified one matches a class
  of that simple name in any package, a JDK class loaded later included); outside `RULE … ENDRULE` only blank lines
  and `#` comments (Byteman applies a `HELPER`, `IMPORT` or `COMPILE` line there as the default of the rules after it)
  (D204); the deny-list matches whole identifiers in every component of a dotted name and prefixes starting at any
  component, after white space around dots is closed up (`java.lang.System.exit`, `System . exit`). `RuleLintTest`
  holds the corpus: one refused rule per entry with "line 6: '<entry>' is not allowed (<reason>)", one accepted rule
  per entry with the word inside its name, and for the identifier-shaped entries inside its method.
- **`rule.check`** runs `RuleCheck` only while this module instance holds the `Retransformer` and the rule's class is
  defined by the system loader; `RuleCheck`'s errors, parse errors, type errors **and type warnings** are `errors`
  (a type warning means Byteman would not inject the rule, such as `$!` at `ENTRY`: D202), its warnings and the
  `WARNING` lines of its buffer are `warnings`.
- **`rule.submit`** answers `classes` from `listScripts` read right after `installScript`: `injected` (not triggered
  yet), `compiled`, `failed to compile: …` or `threw: …`. A `threw` (a transform-time failure: the rule was not
  injected, and Byteman printed nothing) removes the rule and answers `RULE_REJECTED` with the statuses; an exception
  out of `installScript` is `RULE_REJECTED` as well (D203). `messages` are `installScript`'s lines.
- **`rule.unload`** of a name the module does not hold answers `{removed: false, restored: []}`. A class another rule
  of the module still touches is not verified (it is not in `restored`); a class whose removal retransformation the
  behind recorder did not see is `DEGRADED("no restore observed")`; foreign events are counted on the ahead recorder
  since the rule's install. **(Phase 6 security fix, pass 2, P2-L2-F1, built 2026-10-09, SE)** A removal whose
  retransformation throws (Byteman catches a `VerifyError` there and nothing else: a `ClassFormatError` from another
  agent's transformer, or the `InternalError … invalid class` of a class that never linked) is no longer an
  `InvocationTargetException` out of the engine (`INTERNAL_ERROR`): it is `HOOK_VERIFY_FAILED`, "the rule <name> was
  removed, but the classes it touched cannot be restored: <cause>" with `data {classes: [{class, loader, verified:
  FAILED, reason: "cannot be restored: …"}], removed: true}` when Byteman no longer lists the rule (it drops the script
  before it retransforms, so the class keeps the injected code), and then the module drops its record too (no retry
  can repair anything; the log names the classes and says "restart the target"); while Byteman still lists it, "could
  not be removed", `removed: false`, and the record stays for a retry. A submit whose statuses fail and whose removal
  then throws is refused with both reasons.
- **The encoding** follows §4.3 with `mask("rule.<name>", "binding<i>", value)`; a container is read only when its class
  is the JDK's (iterating an application's own collection would run its code; otherwise it is a handle) (D205).
  `rule.fired.rule` is the name without the `skillroy:` prefix, and only the module's live rules publish.
  Fix wave (Phase 6 review, FB, 2026-10-09; findings L2-F1, L2-S1, L2-F3): the encoding applies §4.3's value rules in
  full, which "the JDK's" did not. **Scalars** are the JDK's exact types only (`String`, the six boxes, `Boolean`,
  `Character`, an enum by `name()`, `BigDecimal`, `BigInteger`; a `Date` and a `java.time` value of a `java.` class):
  an accumulator, an adder or an atomic is a handle, so its `longValue()` (which may apply the application's
  function) never runs. **Big numbers** (D182): a `BigInteger` of more than 6,000 bits, and a `BigDecimal` whose unscaled
  value has more, is `null` with `truncated` before any text is made (the unscaled bit length is the cheap test; D182's
  precision test would itself compute a power of ten of the value's size); a `BigDecimal` whose scale is outside -64
  to 2000 is its scientific text; every text is cut and counted on the input budget. **Containers**: an array is a
  list; a collection or map is read only when its exact class is on an allow-list of JDK classes whose iteration
  takes no lock and calls no method of an element (`ArrayList`, `LinkedList`, `ArrayDeque`, `PriorityQueue`,
  `HashSet`, `LinkedHashSet`, the two `EnumSet` classes, `HashMap`, `LinkedHashMap`, `TreeMap`, `EnumMap`,
  `IdentityHashMap`, `Arrays$ArrayList`, the empty and singleton collections of `Collections`, `ConcurrentHashMap`,
  `ConcurrentSkipListMap`, `ConcurrentLinkedQueue`/`Deque`, `CopyOnWriteArrayList`/`Set`, and the
  `ImmutableCollections` of Java 9 and later; not `TreeSet` or `ConcurrentSkipListSet`, whose `subSet`, `headSet`,
  `tailSet` and `descendingSet` answer an instance of the same class over a bounded sub-map that compares the keys on
  iteration); everything else is a handle, the JDK's locking collections (`Vector`,
  `Stack`, `Hashtable`, `Properties`, the synchronized wrappers, every blocking queue including `DelayQueue` and
  `LinkedTransferQueue`) and **every view** included (an unmodifiable or synchronized wrapper, a sub-map, a key set:
  a view's iterator is the wrapped collection's, which may lock or be the application's). This is D182's reason, "no
  application lock may be taken", applied by allow-list rather than by a deny-list of names, which missed the
  blocking queues whose names lack "Blocking" and every view. The list is the one the hooks module's `Capture` reads
  after its own fix (the hooks half of L2-S1 and L2-F2); one shared predicate in agent-api would keep the two from
  drifting. **(Phase 6 security fix, pass 2, P2-L2-F7, built 2026-10-09, SE)** A bound string (top-level, in a list,
  a map's value or key text) passes `ctx.redactText` after its cut and before the policies, as §4.3 now says for a
  captured string.

**(Phase 8, built 2026-10-19, EH; P8 §6.7.)** The class bound takes the class deny as a view the engine builds per call
from `hooks.deny` (first), the hooks module's held list (the registered `ClassDeny` that is a `HookEngine`) and every
other registered `ClassDeny`: the rule's `CLASS`/`INTERFACE` name the view refuses is an error **before the count and
before the load** (a confirmed `rule.submit` is `RULE_REJECTED("the rule was refused: <name>: denied by …")`, appends
nothing and runs no `premain`); a matched loaded class it refuses (a `^` or `INTERFACE` rule reaching a denied subtype)
is an error of its own; `rule.check` reports a denied rule class in `errors` and then skips the type check;
`rule.list` gains `deny: {option, modules, catalogue?}` (`catalogue` when the hooks module is loaded, through
`HookEngine.deny()`). **Residual** (gap 140): a denied subtype loaded after a `^` or `INTERFACE` submit receives
Byteman's trigger at its load; closing it needs a guard transformer behind Byteman's, a cost on every class load.

*(Phase 8, built 2026-10-19, ES; P8 §7.7, D576.)* `rule.check`'s `warnings: [string]` stays information in its value: the
warning channel of a call is the result's envelope (`RpcResult.warnings`, `CallWarning` objects), never a key of a
value, so `rule.check` neither raises one nor is read as one.

### 7.4 Loading Byteman

**Only under `rule.submit`**, after the dispatcher's read-only and confirm gates have passed, on an agent worker,
once per JVM (a read never loads an agent into the host; `rule.check` and `rule.list` answer `loaded: false` until
then). Fix wave (Phase 6 review, FB, 2026-10-09): and only after the lint and the class bound of §7.3 have accepted
the rule (finding L2-F5):

1. The jar: the agent option `byteman.jar` (§3.7) and nothing else, an absolute path to a readable jar whose manifest
   names `Premain-Class: org.jboss.byteman.agent.Main` (`UNAVAILABLE("not a Byteman jar: <path>")`), in a directory
   that no other user may write to and itself not writable by others (the agent's `SecureFiles` directory rule, S8,
   S19, H3; `UNAVAILABLE("byteman.jar is writable by others: <path>")`). No environment fallback: whoever installed a
   Byteman on the machine or set a variable in a shared profile must not decide which jar's `premain` runs inside the
   target (the contract's own stance against `OTEL_*`, D140); the operator names the jar.
   Fix wave (Phase 6 review, FB, 2026-10-09): **a symbolic link is resolved first** (`toRealPath`): the checks apply
   to the real jar and its real directory, and the real jar is the one appended, so that a link in a safe directory
   to a jar in a directory others may write to is refused, and a link swapped after the check changes nothing
   (finding L2-S2). On an ACL file system the jar and its directory must be **owned by the current user** (the owner of
   a file this process creates) **or by the system account, the administrators group or the installer service**,
   each resolved as a principal and never matched by a bare name, and no ALLOW entry may give anyone else a write
   right (finding L2-F6; the judgement `JarCheck.aclProblem` is tested on built ACL values, and the branch is not
   verified on a Windows host). The POSIX rule is unchanged (owned by the current user or root).
   (Phase 6 security fix, pass 2, P2-L1-F5, built 2026-10-09, SA.) The ownership and ACL judgement moved, unchanged,
   to the shared `JarTrust` of §3.6, which now also judges the `modules` paths and a named `hooks.spyJar`, and with
   it the per-volume refusal of P2-L7-F1 (a jar or directory on a FAT or exFAT volume is "writable by others");
   `JarCheck.writableByOthers` and `JarCheck.aclProblem` delegate to it, and `JarCheck`'s own steps (the manifest,
   the real path appended) are unchanged.
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

As built (Phase 6, BW, 2026-10-09):

- **Step 3's option string is `listener:false,manager:ai.skillroy.testjava.agent.byteman.sys.RuleManager`, in that order
  and nothing else** (BS14 condition 1; D194): `prop:org.jboss.byteman.verbose=false` is dropped. The sys jar is
  `<home>/optional/skillroy-agent-byteman-sys.jar` (the agent option `home`); there is no option for it.
- **The residue rule** of `ByteVerification` as HK changed it (D172: a residue is a reference under the prefix that
  the restored bytes hold and the reference bytes did not) suits the module's check: its reference is the ahead
  recorder's first bytes of a class before any rule of the module was on it, so a Byteman reference those bytes
  already carried (a constant-pool entry the JVM merged across an earlier redefinition) is no longer a false `FAILED`,
  while a trigger left behind still is.
- **Step 4 has two recorders** (BS14 condition 5; D196): the ahead recorder registered before `premain`, whose first
  bytes per class are the reference, and the behind recorder registered right after it, whose last bytes after a
  removal are what the JVM installed; §3.5's single recorder re-registered last would, for every later submit, see
  Byteman's output instead of the reference. They record only the classes a rule of the module names (by name, and the
  classes the class bound matched). **In the reuse case** both recorders are registered after Byteman's transformer,
  so before a submit the module retransforms the matched classes whose reference it has not seen yet, in the batches
  of §3.2 (no rule of the module is on them then, so Byteman returns nothing and the recorder sees the initial bytes):
  one extra pause per class, in the reuse case only.
- **Byteman's console output** (BS14 condition 3; D197): right after the load the module puts one `PrintStream` of its
  own into `org.jboss.byteman.rule.helper.Helper.traceMap` (a private static, by reflection) under `out`, `err`, `vrb`,
  `dbg` and `nzy`; it writes each line to the agent's log at WARN (`byteman: …`, at most 2,000 lines, each cut at 1,000
  characters), and the module removes it by identity at `uninstall`. A rule's type error at its first trigger (a rule
  whose class is not the system loader's escapes the type check) and a failing action's stack trace therefore reach
  the agent's log, not the host's console.
- A load that fails (a `premain` exception, no `Retransformer` handed over) removes the ahead recorder and answers
  `CAPABILITY_UNAVAILABLE` with the reason; Byteman's `loaded` property may then be set, and the next probe answers by
  §7.4 step 2.
- **(Phase 6 security fix, pass 2, P2-L2-F2, built 2026-10-09, SE) A load whose handler cannot arm is undone.** The
  build set the engine's `Retransformer` before it armed the handler, so when another handler held the helper's slot
  (A12: `RuleHelper.install` is a public static on the system class path, and the slot is free between two agent
  instances) the first `rule.submit` was refused and every later one installed rules whose firings reached the
  squatter and never `rule.fired`, while `rule.engine` stayed `OK` (on the fresh path the ahead recorder was gone as
  well). Now, with the `Retransformer` in hand (taken after `premain`, or taken from the manager on reuse), the module
  **arms the handler first**, then resolves the three methods, registers the behind recorder and redirects the trace;
  only then is the `Retransformer` the engine's. Anything that fails undoes all of it on both paths: the handler
  released, the behind and ahead recorders and the trace streams removed, and the `Retransformer` **parked back** in
  the manager (on the fresh path too, since `premain` never runs twice), so the engine is never half-loaded and the
  next `rule.submit` retries through the reuse path. The answer is `CAPABILITY_UNAVAILABLE("the helper's handler slot
  is held by another handler")`, and so is the probe's while a parked `Retransformer` sits beside a held slot (a parked
  `Retransformer` means the agent released its handler, so a held slot is someone else's; the probe reads
  `RuleHelper.held()` through the system loader, which can see the sys jar then). A `Retransformer` that lacks one of
  the three methods (another Byteman version) is undone the same way, "Byteman's rule base cannot be used: …". D200
  and D201 are unchanged.
- **(Phase 6 security fix, pass 2, P2-L2-F5, built 2026-10-09, SE) The recorders are bounded.** Each recorder keeps at
  most `hooks.maxClasses` records (one per name and loader) and copies no class file above `hooks.maxClassBytes`: a
  definition beyond the record bound is not recorded and its name is marked, one above the byte bound is counted (its
  events) and its record keeps no bytes, and the byte check then answers `DEGRADED("not recorded: beyond the
  recorder's bound")` for such a class. A rule's names are unwatched, and their records dropped, at `rule.unload`
  (and when Byteman dropped the rule, above) when no other rule of the module names them, so an unloaded rule's
  name is recorded no more. The build kept one record with up to two class-file copies for every loader that defined
  a watched name, without a bound and for the JVM's life in the left-behind state (a host that defines one name per
  redeploy or per plugin loader). With the defaults the two recorders hold at most 2 × 64 records of at most two
  256 KiB copies. D196 is unchanged.

### 7.5 Safety, lifecycle and the capability

- **Gates**: `rule.submit` under read-only mode is `READ_ONLY`, without `_meta.confirm` `CONFIRM_REQUIRED`, before
  readiness `NOT_READY`, all by the dispatcher (P1 §5); `rule.check`, `rule.list` and `rule.unload` are served in
  read-only mode; **loading Byteman happens only under `rule.submit`**, so a read-only agent never loads it.
- **Targets**: the lint's deny-list, the class bound and the loader rule (§7.3), and (Phase 6 security fix, pass 2,
  P2-L2-F1, built 2026-10-09, SE) the refusal by name of a JDK class and of the agent's own, loaded or not (§7.3);
  `boot:`, `sys:` beyond the two appends, `SETSYSPROPS` and `transform.all` are never used or exposed.
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
- As built (Phase 6, BW, 2026-10-09): `uninstall` disarms the handler, then removes every rule of the module in one
  `removeScripts` on a daemon thread `skillroy-uninstall-byteman` joined for `hooks.detachBudgetMs`. When it completes:
  the byte check per class is logged, the `Retransformer` parked, the helper's slot released, both recorders removed,
  the trace streams removed. When it does not: the WARN names the rules, the module **parks the list of their names**
  in `RuleManager`'s slot in place of the `Retransformer` (this is where the next instance's "<names from the log>"
  come from; D201), the ahead recorder stays registered holding the disarmed handler in its `keep` field, the behind
  recorder and the trace streams are removed (so a later failure of the leftover rule prints on the host's stderr
  again: a limit of the abnormal state). The test seam is the JVM system property
  `skillroy.agent.byteman.test.keepRulesAtUninstall=true`, read only by `uninstall`, which then leaves the rules as a
  cut-short budget would (D206).
- Fix wave (Phase 6 review, FB, 2026-10-09): **the detach budget bounds the whole `uninstall`** (findings L1-F5,
  L2-S4). As built above, the worker held the module's lock for all of `removeScripts` and `uninstall` took the lock
  again after the join, so a slow removal held it past the budget (and the snapshot at its start waited without bound
  behind a running `rule.*` call); a removal that ended after the cut then logged and verified from module code after
  the core had moved on. Now: (1) `uninstall` first marks the module **detaching** (every `rule.*` call that takes
  the lock afterwards answers `CAPABILITY_UNAVAILABLE("the byteman module is detaching")`, and no `rule.fired` is
  published) and disarms the handler; (2) it waits for the module's lock (a `ReentrantLock`) **at most the budget**
  and then keeps it to its end; (3) the worker `skillroy-uninstall-byteman` does **nothing but `removeScripts`** and
  records its outcome by compare-and-set; `uninstall` joins it for what is left of the budget, and the bookkeeping,
  the byte check, its log lines and the parking run on the uninstall's own thread, only when the removal completed in
  time; (4) a cut short (the lock not had within the budget, the removal not finished, or failed) leaves exactly the
  state of the bullet above, with the names of the module's rules (when the lock was not had: the names of the rules
  installed or being installed, read without it). A `rule.submit` that the cut-short `uninstall` stopped waiting for
  installs nothing more once it sees the module detaching, and when no rule of the module is installed gives the
  `Retransformer` back to the manager (parked, the helper's slot and both recorders released) as an uninstall with no
  rule does; when the lock was not had and the module had not loaded Byteman, `uninstall` parks nothing and logs a
  WARN that says so. **A removal that ends after the cut** runs
  no code of the module (no log line, nothing to the default uncaught-exception handler; its class needs nothing the
  closed module loader would have to load), and **the parked names then over-report**: Byteman removed the rules
  after all, but the next instance still refuses with their names ("restart the target"), the safe direction; a
  parked `Retransformer` still always means that no rule of the module is left. The seam of D206 is unchanged.
- **Capability `rule.engine`**: declared at `install` with a probe: `UNAVAILABLE("no Instrumentation in <mode>
  mode")`, `UNAVAILABLE("no byteman.jar configured")`, `UNAVAILABLE("not a Byteman jar: …")`,
  `UNAVAILABLE("byteman.jar is writable by others: …")`, `UNAVAILABLE("Byteman was loaded by the host; …")`,
  `UNAVAILABLE("Byteman rules of an earlier agent instance are still installed: …")`, `UNAVAILABLE("the helper's
  handler slot is held by another handler")` (a parked `Retransformer` beside a held slot; P2-L2-F2, SE), else `OK(strategy
  "in-process")` or `OK(strategy "in-process, reused")`; `agent.info` lists the module as every module. There is no
  per-rule capability: rules are ad hoc by definition, and a scenario that depends on one is wrong by design (design
  §3.2: expectations against primitives and hook events, through the catalogue).
- **Never on the wire**: the rule text appears in `rule.submit`'s params, and so in `run.jsonl`'s `call` record and
  the agent's log line for the call; a rule that names a secret value is the author's mistake, and `_meta.secretParams:
  ["rule"]` is honoured as for any call (A34).
- **(Phase 8, built 2026-10-19, EH; P8 §6.7.) Targets** gain the class deny: a class `hooks.deny`, the hooks module's
  held catalogue list or a domain module's `ClassDeny` refuses is never handed to Byteman (§7.3's bound, before the
  load); the later-loaded denied subtype is the residual recorded there.

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

As built (Phase 6, BW, 2026-10-09). Unit (`skillroy-agent-byteman-sys`): `RuleManagerTest`, `RuleHelperTest`,
`SysConstantPoolTest`. Unit (`skillroy-agent-byteman`, the test agent `ai.skillroy.testjava.agent.byteman.TestAgent`
in `target/<module>-test-agent.jar`, the toy `ai.skillroy.testjava.fixture.byteman.DepositDesk`): `RuleLintTest`,
`ClassBoundTest`, `BindingEncoderTest` in the default execution, and three classes in surefire executions of their
own, each a fresh JVM (`reuseForks=false` silently ran no test on the Java 8 test JVM; a command-line `-Dtest` merges
the executions into one JVM, and then the three skip with a message through `FreshJvm` instead of failing): `ProbeTest`
(the reasons of §7.5, with
Byteman's property set and the system loader replaced by an isolated one for "loaded by the host"),
`CheckLoadsNothingTest` (`rule.check` and `rule.list` on a fresh module define no `org.jboss.byteman` class, load
nothing of the sys jar and set no property) and `InProcessRouteTest` (the whole route in the test JVM: the first submit
loads Byteman from the test class path's jar, `rule.fired` with a handle and the limit, the `compiled` status,
`rule.check` type-checking once loaded, a type error, a `killJVM` rule and `$!` at `ENTRY` refused, the unload
`verified: OK`, the uninstall parking, a second instance reusing it with its reference primed, a cut-short third
instance refused by name while the rule's action still runs and no `rule.fired` arrives). `BytemanIT` plays the
re-attach without the attach API: the toy `LendingToyMain` runs with a second `-javaagent`
(`InstrumentationKeeper`, with the retransform and redefine rights of the bootstrap's manifest) and, on a stdin
command, calls the bootstrap's `agentmain` with that `Instrumentation`, which is what an attach calls; it also covers
the read-only refusal in a second child. Run: natively (macOS: the socket checks skip, no `/proc`), and in the arm64
image on the Java 8 and Java 25 targets, the module tests also on the Java 8 test JVM (`-Djvm`).

Fix wave (Phase 6 review, FB, 2026-10-09). A fourth class runs in an execution of its own, `DetachBudgetTest`
(`detach-budget`): with `hooks.detachBudgetMs=1000`, a call that holds the module's lock for 3 s and a removal that a
slow foreign transformer stretches to 3 s each give an `uninstall` that returns within the budget plus 1.5 s; the
late end of the removal adds no log line and nothing reaches the default uncaught-exception handler, the rule is
gone, and the next instance is still refused naming it (§7.5's over-report). `InProcessRouteTest`'s cut-short third
instance now comes from a call holding the module past a 1000 ms budget (the bound of the wait at the start of
`uninstall`); the seam of D206 stays covered by `BytemanIT`. `CheckLoadsNothingTest` adds a confirmed `rule.submit`
the class bound refuses (`hooks.maxClasses=1`, a `^` rule over the toy and its subclass): `RULE_REJECTED`, and still no
Byteman class, no sys-jar class, no append, no property. New in the default execution: `SettingsTest` (the five
ranges, `hooks.maxClasses` 1025 refused), `JarCheckTest` (a link to a jar in a directory others may write to refused
and the real jar appended; the ACL judgement on built values), `FreshJvmTest`; `BindingEncoderTest` gains the locking
collections, the views and the queues as handles, the allow-listed classes read, a sorted-set view over application
keys encoded with no `compareTo`, a `Vector` whose monitor another thread holds encoded at once, and the big-number and
exact-type rules; `RuleHelperTest` the same rules for `format`;
`RuleLintTest` the pinned limits of the lint (§7.3). **The dedicated JVMs**: `FreshJvm` skips a class whose JVM is not
fresh only when a command-line `-Dtest` merged the executions (surefire hands `test` to the fork), and fails it
otherwise, so that a dedicated execution that is not fresh can never pass as a skip (finding L3-S2). **No silent
empty run**: both Byteman modules set surefire's `failIfNoTests` from the property `skillroy.failIfNoTests`, `true` by
default, so an execution that runs no test fails the build (the Java 8 test JVM once ran zero tests and exited 0); a
profile activated by the property `test` sets it to `false`, so the documented `-Dtest=… -Dsurefire.failIfNoSpecifiedTests=false`
runs still pass in every module of the reactor (finding L3-F1, the Byteman half). The counts to read from the reports
on every leg: `skillroy-agent-byteman` 37 tests in ten classes, 0 skipped; `skillroy-agent-byteman-sys` 10 in three.
(Phase 6 security fix, pass 2, SE: `skillroy-agent-byteman` 43 tests in twelve classes, 0 skipped: `ClassBoundTest`
+1 (the refusal by name, P2-L2-F1), `CheckLoadsNothingTest`'s JDK-name submits (no new test), `BindingEncoderTest` +1
(P2-L2-F7), `ByteRecorderTest` 3 (new, P2-L2-F5), and `LoadUndoTest` 1 in an execution of its own, `load-undo` (a
squatter on the helper's slot before the first load: the fresh path and the reuse path undone, the probe
`UNAVAILABLE`, the rule working once the slot frees; a removal that a malformed class file makes throw answered
`HOOK_VERIFY_FAILED` with the record and its names dropped; P2-L2-F2, P2-L2-F1, P2-L2-F5).)

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

(Phase 6 security fix, pass 2, P2-L7-F2, built 2026-10-09, SD; not run on a Windows host) **No command of the `.bat`
or of `ci\run-on-windows.cmd` is taken from the current directory.** `cmd.exe` searches the current directory before
`PATH` for a command named without a path unless the variable `NoDefaultCurrentDirectoryInExePath` exists (Microsoft,
`NeedCurrentDirectoryForExePathW`), and `findstr` ran on every invocation, before any Java code, so a `findstr.exe`
(or `.com`, `.bat`, `.cmd`) planted by another user in a shared directory the operator ran `skillroy` from ran with
the operator's rights. So: the `.bat` sets `NoDefaultCurrentDirectoryInExePath=1` directly after `setlocal` (which
confines it to the script and the commands it starts) and names its one external command by its full path,
`%SystemRoot%\System32\findstr.exe`, in all five places of the table above (the version check, the digits test, the
two `MODULES=` tests and the `--list-modules` filter); `java.exe` stays the `PATH` lookup `%%~$PATH:i`, which never
searches the current directory, and is always called as `"%java%"`. `ci\run-on-windows.cmd` sets the same variable
after its `setlocal`, so that `call mvn`, which must stay a `PATH` lookup, never finds an `mvn.cmd` in the checkout
it changes into. `BatLauncherTest` splits every code line of both files into its commands (at `|`, `||`, `&&`, `&`
outside quotes, the command of an `if`, the set and the body of a `for`) and asserts that each command word is a
`cmd.exe` built-in, a label, `"%java%"` (the `.bat`), `call mvn` (the runner) or a full path under
`%SystemRoot%\System32\` ending `.exe`, and that the variable is set before the first such command. Host item (§9.5):
run `skillroy.bat` from a directory holding a decoy `findstr.cmd`, and the runner from a checkout holding a decoy
`mvn.cmd`.

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
an extensionless shell shim beside `claude.cmd` that Windows cannot start; D198), reads `PATH` and `PATHEXT`
without regard to the variable names' case (`Path`), compares extensions as given, lower- and upper-case, and on Windows
asks for a regular file (no executable bit); elsewhere `PATHEXT` is ignored and the file must be executable, as
before. `AuthorCommand.serverCommand(launcher, args)` writes the plain `cmd.exe /c` form and refuses, with exit code 64
and the remedy, a launcher path or argument holding `"`, `%`, `^`, `&`, `|`, `<`, `>`, a space, a tab or a control
character (the contract's list plus the two redirection characters and the other white space; D199).
`AuthorCommandWindowsTest` (five tests) proves the lookup, the form and the refusals on Linux.

(Phase 6 security fix, pass 2, P2-L7-F3, built 2026-10-09, SD; not run on a Windows host) **A batch file is never the
planner.** The `cmd.exe /c` form above is the planner's *client* starting `skillroy.bat`; the planner itself is
started by `Planner` through `ProcessBuilder`, and Windows starts a `.cmd` or `.bat` through an implicit `cmd.exe /c`
with Java's quoting, which pairs no quote the way `cmd.exe` does. So: the prompt goes to the planner on stdin and no
text of the brief or the model is an argument on any platform (P5 §10.2 item 4); `Planner` refuses a planner whose
name ends with `.cmd` or `.bat` (exit 64, the remedy the native `claude.exe` or `--claude-command`);
`findOnPath` keeps D198's order within a directory, but a directory whose candidate is a batch file is passed over for
a later directory with another candidate (npm's `claude.cmd` before a native `claude.exe` later on the `PATH`), and the
first batch file is returned only when no directory has anything else, so that the refusal names it; the schema
argument is pre-quoted by the C runtime's rules on Windows and the command line bounded to 32767 characters (P5 §10.2
item 4). `jdk.lang.Process.allowAmbiguousCommands=false` is **not** put on the launchers (D251:
with nothing of the model's on the command line it guards nothing more here, and it would change how every process
the CLI starts on Windows is quoted, unverified). Host items (§9.5): the planner through the native `claude.exe` with
the prompt on stdin, a decoy verb whose problem carries `" & … & "` and `%VAR%`, and the refusal of npm's
`claude.cmd`.

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

(Phase 6 security fix, pass 2, P2-L7-F1, built 2026-10-09, SD) "A file system with neither view" is now a **volume**
with neither: the branch is decided by the `FileStore` of the path (or of its nearest existing ancestor), a FAT or
exFAT volume (types `msdos`, `vfat`, `fat`, `fat12`, `fat16`, `fat32`, `exfat`, `umsdos`) is neither whatever it
reports, and what the POSIX branch creates must read back exactly `rwx------` or `rw-------`, else it is deleted and
refused (P1 §2.5 has the rule; the shared decision is `skillroy-protocol`'s `Volumes`). Before the fix the decision was
the provider's, the same for every path, so the zip file system of the tests was the only place the refusal could be
reached and every member accepted a FAT or exFAT volume. The `CredentialFiles` row above: a credentials file on a FAT
or exFAT volume is refused; the "neither: accepted with a warning" branch stays for any other volume without either
view (D138 unchanged). The host list of §9.5 keeps "a FAT or exFAT volume for the neither branch" (now the refusal on
Windows).

(Phase 6 security fix, pass 2, P2-L1-F3, built 2026-10-09, SA.) Two of the five members changed, their signatures
not: `writeOwnerOnly` creates the file and writes it through one channel (`CREATE_NEW`, `NOFOLLOW_LINKS`, the
owner-only attribute; on ACL file systems the ACL is read back before any byte is written), so the content cannot be
redirected through a link swapped in after the creation; and both `ensureOwnerOnlyDirectory` overloads also refuse a
directory with a directory above it that lets others replace entries (group- or other-writable without the sticky bit;
on ACL file systems a delete or take-over right that applies to it), naming that ancestor (P1 §2.5, "the directories
above it"). `createOwnerOnlyTempDirectory` and `untrustedDirectoryReason` are unchanged. Tests: `SecureFilesTest`
(`aDirectoryBelowAParentOthersMayWriteToWithoutTheStickyBitIsRefused`,
`anAncestorsAclMayLetOthersAddEntriesButNotReplaceThem`, `anOwnerOnlyFileIsNeverWrittenThroughALink`).
With P2-L7-F1 (the two fixes merged): on POSIX volumes `writeOwnerOnly` reads the new file's permissions back
(exactly `rw-------`, not following links) while that one channel is open and before a byte is written, as the ACL
branch reads its ACL back, and the ancestor rule takes each ancestor's kind from its volume as well, so an ancestor on a FAT or exFAT volume (above a
mount point) is refused: anybody with the volume may rename its entries (`PermissionlessVolumeTest`
`anAncestorOnAFatOrExfatVolumeIsRefused`).

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

(Phase 6 security fix, pass 2, P2-L8-F4, built 2026-10-09, SF) **The masked one-line messages carry no path.** The run's
messages name a file or a directory when the failure does: a connect failure named the discovery directory
(`${java.io.tmpdir}/skillroy-<user>`, whose name holds the user's name, or `<user.home>/.skillroy/run`), and the
executor's catch-all named the exception's text (a missing file's path). Now `skillroy.message` (the `environment`
event's included) and `skillroy.step.message` lose every absolute path before any other rule (`<path>` in its place:
a token starting with `~/`, a drive letter, or a separator followed by two segments; `TelemetryAttributes.withoutPaths`);
`run` gives the observer the connect failure with the discovery directory named by its role ("no live agent in the
discovery directory …"; stderr and the JUnit report, which stay on the host, keep the path); and an internal error's
message names the exception's class only (`internal error: <class>`, in a run, a step and a session step), its text
staying in the result's `detail.exception`, which no span carries. **Egress for a header is HTTPS only** (P2-L8-F2,
§10.3).

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

*As built (Phase 6, TL, 2026-10-09):*

- **The SPI's additions (decision D161 of the build).** A span must be open before its children, and the table of
  §10.4 parents a session step under its turn and a tool call's work under the client's trace, which after-the-fact
  methods cannot do. `RunObserver` therefore has, as `default` no-ops beside the methods above,
  `sessionStarted(SessionStart)` (`SessionStart(id, pack, packVersion, policy)`, the policy `read-only` or `acting`
  from `SessionOptions.readOnly`), `sessionStepStarted(StepStart)` (`StepStart` gains `origin`, null in a run),
  `sessionRecord(String type)` (the type of every journal record, the session span's events; never its content),
  `sessionEnded(String reason)`, `turnStarted(int index)`, `toolCallStarted(String tool, String traceparent)` and
  `traceId()` (the invocation's trace id, for `run-start`, `--json` and the JUnit report). `CallEnd` carries `rttMs`,
  the call's own round trip, in which the agent span is centred.
- **Where the observer travels.** `RunOptions.observer` is, on the TL branch, the **eleventh** component, since
  `hooks` (CP) did not exist there; the integration places `hooks` eleventh and `observer` twelfth as §6.1 says, and
  every caller uses `withObserver` and the older constructors only. The session's observer is
  **`SessionOptions.observer`** (an eleventh component with `withObserver` and the ten-argument constructor kept;
  decision D162): `ExecutorSession` wraps it, and `ExecutorSession.observer()` hands the same wrapped observer to
  `SessionService` (`turnStarted` at the start of `ask`, `sessionTurn` when the turn is recorded) and to the MCP
  server (`toolCallStarted` and `toolCall` around each call's dispatch, under the session lock, so that concurrent
  calls never interleave; `skillroy_scenario_run`'s runs carry it too). `ServerOptions` is unchanged.
- **The scenario records reach the observer through `RunLog` (decision D164).** `RunLog.observe(observer)` passes
  every record of the types `requires`, `launchProperties`, `preflight`, `hooks`, `capabilities`, `recover`,
  `cleanup` (the seven) and `expect`, `db`, `prompt`, `fidelity`, `capture` (the step span's events of §10.4, which
  the SPI had no method for) to `scenarioRecord`, as written (masked, JSON form, without `type` and `t`), whoever
  writes it: CP's `hooks` and `capabilities` records need no further call whichever package lands second. A log
  without a file still tells its observer (a session's). The bind records go through `bindEvent` as specified: the
  listener's records, and the executor's **final** `bind` record (success or failure, which ends the bind span); the
  intermediate `bind {written}` and `bind {writeError}` records are not passed.
- **Calls.** The `call` span covers the call, its sync, its capture and its prompt check (`callStarted` once the
  call's options are known, `callEnded` when `CallRunner.execute` returns or throws), so that its `await` spans are
  inside it; cleanup calls get the same span from `PlanExecutor` (their own `CleanupCall`). The trace parent is sent
  on the step and cleanup calls only, not on the executor's diagnostic reads (`swing.edtState`, `evt.query`, …).
- **`NONE` costs nothing new.** `RunObservers.safe(NONE)` is `NONE`, the executor and `CallRunner` build no record
  for it (`RunObservers.isNone`), `RunLog` does not copy a record for it, and `run-start` carries no `traceId`.
  `RunObservers.safe` logs a failing observer once (`System.Logger`, WARNING, the exception's class only) and
  replaces it by `NONE` for the rest of the run; its `close()` only flushes, since a run never ends the invocation's
  trace.
- **Watch out at integration:** a `SessionOptions` or `RunOptions` rebuilt through an older constructor drops the
  observer (the MCP server's `effectiveSession` was one such place and now re-adds it); CP's and FX's new
  constructors must carry it.

**The travelling `traceparent`.** `CallOptions` gains a fifth component `traceparent` (the four-argument constructor
stays, as A34 kept the three-argument one), `AgentClient.withMeta` writes it into `_meta.traceparent` (P1 §7's "the
controller sends … and nothing else" is amended: it sends the trace parent when one exists, §15.1), and
`CallRunner.options` fills it from `observer.currentTraceparent()`. The agent needs no change: it already reads
`_meta.traceparent` and stamps it on every event of the call. The MCP server accepts an inbound `_meta.traceparent`
on `tools/call` (`request.meta()`, control-channel §F16) parsed as W3C and dropped when malformed, so that a client's
trace continues into the run.

(Phase 7, built 2026-10-10, VM; P7 §10) No new observer method: a vision call is a ledger entry of purpose
`vision` (`SessionService.visionCall`, or the loop's own call while a tool set applies), so the `modelCall` record and
span of a session are built from it as today; a vision session's turns are `sessionTurn` records as any other.

### 10.3 Settings and the CLI

| Setting | Option (on `run`, `session`, `mcp`, `re-resolve`, `triage`, `author`) | System property (the JUnit glue) | `telemetry.yaml` | Default and rule |
|---|---|---|---|---|
| the switch | `--telemetry none\|otlp\|file` | `skillroy.telemetry` | `exporter:` | `none`: nothing is created, no SDK class loads, no thread starts (the CLI consults `ServiceLoader<RunObserverProvider>` only for another value, and the provider class references no OpenTelemetry type until `create`) |
| where | `--telemetry-endpoint <url>` | `skillroy.telemetry.endpoint` | `endpoint:` | `http://127.0.0.1:4318` (the OTLP/HTTP port); **`http://` is accepted only when the URL's host is a literal IP address for which `InetAddress.isLoopbackAddress()` holds (`127.0.0.0/8`, `::1`, `[::1]`) or the literal `localhost`**; anything else must be `https://`, verified against the JDK's default trust store; the module exposes **no** trust-store, client-certificate or "insecure" setting and never changes the `HttpClient`'s TLS defaults; a violation is a settings error |
| headers | – | – | `headers: { <Name>: env:<VARIABLE> }` | none; the value of each header is read from the named environment variable at `create` and **never** written, logged or printed; a variable that is not set is a settings error naming the variable, never a value; a header given as a literal (not `env:`) is refused ("a header value is read from an environment variable"); **a variable the inference settings read is refused** ("a telemetry header may not carry the model key"): `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_CUSTOM_HEADERS`, `ANTHROPIC_WORKSPACE_ID` and any variable `inference.yaml` names. **Phase 6 security fix, pass 2, P2-L8-F2 (built 2026-10-09, SF): a header needs an `https://` endpoint**; with an `http://` one, the default included, it is a settings error (below) |
| the file exporter's directory | `--out` (the CLI uses `<out>/telemetry/`) | `skillroy.telemetry.out` | `out:` | required for `file`; a path under the run's own output |
| service name and version | – | `skillroy.telemetry.serviceName` | `serviceName:` | `skillroy`; the version is `TelemetrySettings.serviceVersion`, which the CLI fills with its own version (`Main.version()`, private to the CLI, which the telemetry module cannot reference) and the JUnit glue with `skillroy-junit`'s `Implementation-Version`, `(development build)` when absent; `RunStart.cliVersion` carries the same value into `skillroy.cli.version` |
| attribute set | `--telemetry-attributes standard\|minimal` | `skillroy.telemetry.attributes` | `attributes:` | `standard` (§10.4); `minimal` treats **every attribute name of `TelemetryAttributes` by exactly one of four lists** (fix wave FC, §10.4): it **drops** `MINIMAL_DROPS`: `skillroy.message`, `skillroy.step.message`, the `environment` event's message, `skillroy.pack`, `skillroy.suite`, `skillroy.bindings.file`, `skillroy.slot`; it **replaces** `MINIMAL_REPLACED` by a class of the value: `skillroy.primitive` by its namespace (a pack primitive becomes `pack`, `swing.*` stays), `gen_ai.tool.name` by `verb`, `primitive` or `workflow`, `skillroy.step.verb` for a pack verb by `verb`, the `expect` event's path by its root, `skillroy.topic` by itself for a topic of `Topics`, by `hook` for a `hook.*` topic and by `pack` for any other; it **keeps** `MINIMAL_KEPT` as written: enumerations, counts, durations, sequence numbers, flags, hashes, the versions of the CLI, the target's Java and the agent, model names, request ids and costs, and the run's, the session's and the connections' own ids; and it **hashes** `MINIMAL_HASHED`, every other text, as the `sha256:` of the whole value (of each element of a list): `test.case.name`, `skillroy.failed_step`, `skillroy.correlation_id`, `skillroy.pack.version`, `skillroy.env.id`, `skillroy.agent.thread`, `skillroy.secret_params`, `skillroy.id`, `skillroy.ids`, `skillroy.armed`, `skillroy.names`, `skillroy.table`, `skillroy.source`, `skillroy.resolver`, `skillroy.verifiers`, `skillroy.fn`, `skillroy.oracle`, `skillroy.contributors`; a text attribute in none of the lists is hashed. The `resolve <slot>`, `oracle <name>` and `capture` event names become `resolve`, `oracle`, `capture`, and the span names become `scenario`, `step`, `call`, `chat`, `tools/call` with the id hashed into `skillroy.id.hash`. The §10.6 unit tests assert that the four lists partition every name of `TelemetryAttributes`, that every attribute of a `minimal` run is kept, replaced or hashed as its list says, and that no identifier of the run's scenarios, steps, pack, overlay, hooks, resolvers, verifiers, oracles, tables, sources, topics or threads is in clear |
| timeout | – | `skillroy.telemetry.timeoutMs` | `timeoutMs:` | `10000`: the exporter's per-batch timeout and the bound of `flush`/`close` |
| the file | `--telemetry-file <path>` | `skillroy.telemetry.file` | – | `telemetry.yaml` beside the credentials file (the `inference.yaml` lookup), else none; `version: 1`, strict, no secret. **Phase 6 security fix, pass 2, P2-L8-F1 (built 2026-10-09, SF): read only when nobody but the current user could have written it** (the owner rule below), else a settings error naming the file and the rule |

Precedence options > properties > file > defaults, as `InferenceSettings`. A settings error is a usage error (exit
64) found before the agent is contacted. `replay` runs may export (telemetry is not a model call; the zero-call
assertion is untouched). The variables a header names join the "never written" list (P2 §11.4, §15.1). Reporting:
with telemetry on, `run.jsonl`'s `run-start` gains `traceId`, `--json` gains `telemetry: {exporter, traceId}`, the
JUnit XML's `<system-out>` gains one line `traceId=<hex>` (a trace id is not a secret), and the CLI's summary one
line `telemetry: otlp <endpoint host:port>, trace <id>, <n> batches dropped` (the endpoint's host and port only, never
its headers; the dropped count from the module's own failure handler, §10.4).

*As built (Phase 6, TL, 2026-10-09):* the reader is **`TelemetrySettings.load`** in `skillroy-scenario`'s `observe`
package (decision D160 of the build), not in `skillroy-telemetry`: the JUnit glue, which does not depend on the
module, reads the file and the properties the same way, and the CLI decides `none` without loading anything; the
module's `create` checks the endpoint rule again and reads the header values. Rules beyond the table: an endpoint
with user information, a query or a fragment is refused; **every `ANTHROPIC_*` variable** is refused as a header's
(the four named and `ANTHROPIC_BASE_URL`, which the gateway reads, among them; `inference.yaml` names no variable
today); a header name or a key the grammar does not fix is never echoed in a message; the options map onto the
property names (`--telemetry` is `skillroy.telemetry`, …) and the messages name the property. The file exporter's
directory is `<out>/telemetry/` for `run` (its `--out`), `session` (its `--out` or `run-output/<session id>`; (Phase 7
security fix, S148, built 2026-10-11, SJ; P7 §9.3) without `--out` a new owner-only directory under the system's
temporary directory), `mcp`
(its `--out`) and `re-resolve` (its report directory); `triage` and `author` (whose `--out` is the scenarios
directory) need `out:` in `telemetry.yaml` or `skillroy.telemetry.out`, else the usage error names the setting.
`re-resolve` and `triage` export their root span only (their model calls are in their own ledgers and reports);
`author` exports its root span, its planner's ledger entries as `chat` spans and its bind and run as scenarios; the
planner's own `skillroy mcp` child is started without telemetry. The JUnit glue's service version is
`skillroy-junit`'s `Implementation-Version`, which its jar's manifest now carries; its observer is registered with one
JVM shutdown hook (`skillroy-telemetry-exit`) that closes every stream's observer, each bounded by its timeout. The
summary line goes to stdout with the human summary, to stderr with `--json` and for `mcp` (whose stdout is the
protocol); `--json`'s `telemetry` is null with telemetry off. `Main.version()` is package-private (it was private).

(Phase 6 security fix, pass 2, P2-L8-F1, P2-L8-F2, P2-L8-S2, built 2026-10-09, SF.) **The owner rule for the file.**
`telemetry.yaml`, named (`--telemetry-file`, `skillroy.telemetry.file`) or found beside the credentials file (or in
`<bindings>/<env>/` without one), is read only when nobody but the current user could have written it
(`SettingsFiles.read` in `skillroy-data`; the same rule for `inference.yaml`, P5 §4.4): looked at without following
links it is a regular file, or a symbolic link the current user (or root) owns, whose file is then judged; on POSIX
the file is owned by the current user or root, others may not write to it, and its group may not either unless the
group carries the owner's name (the user-private group a `umask 002` distribution gives every user; macOS's shared
`staff` is not one); on ACL file systems the owner is the current user (the owner of a file this process creates), the
system account or the administrators group and no ALLOW entry gives anybody else a write, append, delete or
ACL/owner/attribute right (others may read: the file holds no secret; not verified on a Windows host); a volume with
neither, or a FAT or exFAT volume, is refused. The file is judged before and after it is read and must be the same file
(its file key, where the file system has one). Any entry of that name beside the credentials is judged, a link or a
dangling link included, and a refusal is a settings error (exit 64) naming the file and the rule, never a silent skip,
so that a planted file stops the command and the operator learns of it. Until then the lookup asked
`Files.isRegularFile` alone, and a file alone could turn export on and name the environment variables whose values
became request headers: another OS user who could create a file in the credentials or bindings environment directory
received those values (any variable but `ANTHROPIC_*`) on a loopback port or an `https://` host of theirs. The rule is
the file's, not the directory's (decision D280 of the fix): a directory rule would also refuse the group-writable
checkout every user-private-group umask makes, and a planted or replaced file already fails the owner rule.
**Headers need `https://`** (D141 amended, D281): a header with an `http://` endpoint is a settings error ("a
telemetry header needs an https:// endpoint (the default endpoint http://127.0.0.1:4318 is not one; name your
collector's endpoint); over http:// it would reach whatever holds that port in clear (a loopback collector without
TLS takes no header)"), found by `TelemetrySettings.load` and again by the provider at `create`; the message names
neither the header nor its value. Over loopback `http://` a header reached whatever held the port in clear (another
user who bound it while the collector was down, or on a host without a collector), and since the default endpoint is
`http://`, an operator who gave a backend's token and forgot the endpoint sent it there. A loopback collector that
needs a token terminates TLS on loopback. **The planner's child exports nothing** (D168): `author` starts its
planner's `skillroy mcp` with `--telemetry none` beside `--inference none`; an option wins over the file, so a
`telemetry.yaml` beside the credentials no longer turns export on in the child.

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
| `scenario <id>` | run | `test.case.name`, `test.case.result.status` (`pass` for `PASS` and `DEGRADED`, `fail` for `FAIL` and `ERROR`), `skillroy.status`, `skillroy.failure.kind`, `skillroy.failed_step`, `skillroy.pack`, `skillroy.pack.version`, `skillroy.plan.version`, `skillroy.source_hash`, `skillroy.message` (the `RunResult`'s one-line message, masked), `skillroy.skipped_expectations`, `skillroy.degraded_fidelity`, `skillroy.model_calls`, `skillroy.model.cost_usd`, `skillroy.hooks.mode`, `skillroy.hooks.catalogue.hash`, `skillroy.capabilities.hash`; span status `ERROR` for `FAIL` and `ERROR` | `requires`, `launchProperties`, `preflight` (id, ok), `fingerprint` (hash only), `hooks` (mode, installed ids, the count of unavailable ones, armed ids), `recover`, `cleanup` (primitive, ok) |
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

*As built (Phase 6, TL, 2026-10-09):*

- **Threads (decision D163 of the build).** `BatchSpanProcessor` 1.66.0 offers no thread factory: its daemon worker
  is named by the SDK (`BatchSpanProcessor_WorkerThread-1`). The JDK sender's `HttpClient` runs on an executor of
  daemon threads named `skillroy-telemetry` (`setExecutorService`), which `close` shuts down; the JDK adds its own
  selector thread. The exporter runs **without retry** (`setRetryPolicy(null)`) and with the settings' timeout as the
  connect and call timeout, so that a refused endpoint costs one attempt per batch; the processor's and the
  exporter's meter provider is the no-op one (nothing asks `GlobalOpenTelemetry`). The `file` exporter uses a
  `SimpleSpanProcessor` (one line per span as it ends, on the reporting thread) and appends to `spans.jsonl`.
- **Failure reporting.** The exporter is wrapped: a failed batch is counted (`ExportStatus.droppedBatches`, the
  summary line) and the first one prints `skillroy: telemetry: the export to <host:port> failed; …` on stderr once.
  The `io.opentelemetry` JUL logger is set to `WARNING` with a handler of the module's own that keeps the SDK's records
  off the console (it prints nothing: the wrapper's one line is the report).
- **Names.** The root span is `skillroy <command>` (`run`, `session`, `mcp`, `re-resolve`, `triage`, `author`, and
  `junit` for a JUnit stream). `minimal` names `scenario`, `step`, `call`, `chat`, `tools/call` **and `agent`** without
  their ids (the agent span's name would otherwise repeat the primitive `minimal` replaces), each with
  `skillroy.id.hash`; `bind` and `await` have no id, and `session <id>` and `turn <n>` keep theirs (no confidential
  text). `TelemetryAttributes.MINIMAL_DROPS` is the six names dropped (`skillroy.message`, the `environment` event's
  included, `skillroy.step.message`, `skillroy.pack`, `skillroy.suite`, `skillroy.bindings.file`, `skillroy.slot`);
  `MINIMAL_REPLACED` the four replaced by a class (`skillroy.primitive`, `gen_ai.tool.name`, `skillroy.step.verb`,
  the `expect` event's path as `skillroy.expect.path`); `MINIMAL_HASHED` the two hashed (`test.case.name`,
  `skillroy.failed_step`). A tool's class is `workflow` (`skillroy_*`), `primitive` (a base namespace's tool) or `verb`
  (the rest, a pack primitive's tool included). The correlation id kept the scenario id in `minimal`, as the exact
  list did not name it (a question for the code review, answered by the fix wave below).
- **Fix wave (Phase 6 review, FC, 2026-10-09): `minimal` is an allow-list.** The code review found that the exact
  lists left in clear, on sibling attributes, the names `minimal` exists to hide (findings L1-F1, L2-F9, L1-S1): the
  correlation id `<scenarioId>/<stepId>/<n>` on every `call` span; `skillroy.pack.version` beside the dropped
  `skillroy.pack`, which for an overlay is `<base>+<overlayId>@<version>` (§11.1); the `requires` and `hooks` events'
  ids and armed ids; the `preflight` id and the launch property names; the `resolve` event's resolver and verifier
  ids; the oracle names and functions of the `oracle`, `guard` and `crosscheck` events; the inference contributors; a
  stored-row check's table and source; the topics of the `await` spans and of a call's events; the agent's thread;
  the secret parameters' names; and the environment id. Each name of `TelemetryAttributes` is now in exactly one of
  `MINIMAL_DROPS` (unchanged), `MINIMAL_REPLACED` (with `skillroy.topic`), `MINIMAL_KEPT` and `MINIMAL_HASHED` (§10.3's
  row as corrected), and a text attribute that is not dropped, replaced or kept is hashed, so that an attribute added
  later is hashed until someone lists it as kept (an exact list still, so D142 holds). The correlation id is hashed
  whole (fix-wave decision D230, the answer to D166's question): the hashes of its parts would join nothing the
  span tree does not join already, since the call's parent `step` and `scenario` spans carry their ids' hashes, and the
  whole value's hash is recomputed from `run.jsonl` as easily. `skillroy.pack.version` is hashed whole, the overlay's
  id and version with it (a version is the pack author's free text). `skillroy.source` names an agent event's
  publishing module and a stored-row check's data source alike; both are hashed. `session <id>`, `turn <n>`,
  `skillroy.session.id`, `mcp.session.id` and `skillroy.agent.session` keep their ids, as D166 decided.
- **Spans and attributes.** `skillroy.skipped_expectations`, `skillroy.degraded_fidelity`, `skillroy.bind.skipped`
  and `skillroy.step.skipped_expectations` are counts, not texts. The bind span opens at the first bind record and
  ends at the executor's final `bind` record (`ERROR` with the failure kind when `ok` is false); `fingerprint` is an
  event of the scenario span (hash and `durationMs`); `resolver` and `verifier` records are not exported (the
  `resolve` event names the resolver and the verifiers with their outcomes). A `chat` span's parent is the scenario's
  bind span (kept after it ended) for a `resolve` entry with a slot, else the scenario, else the innermost open span;
  its times are the details' `t` and `elapsedMs`. The agent span is centred in the call's own round trip and clamped
  inside the call span. A session step's span is the turn's child when a turn is open, else the open tool call's, else
  the session's. `tools/call` is a `SERVER` span, `call` a `CLIENT` span, `agent` a `SERVER` span. Event attributes are
  `skillroy.*` names of `TelemetryAttributes` (the record's identifiers only: never an expectation's actual or matcher
  value, a prompt's payload or a captured value).

(Phase 7, built 2026-10-10, VM; P7 §10, §11) No new attribute: a `modelCall` span of purpose `vision` carries the
purpose, the model, the tokens and the cost as every model call does, and never an image, a prompt, an intent or a
capture's hash.

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

*As built (Phase 6, TL, 2026-10-09):* `SpansFileExporter` writes the format as specified (`parentSpanId` null for a
root, attribute values as JSON strings, numbers, booleans or arrays; a double as text), appending, one line per span,
flushed per batch of one; the values are the allow-list's, masked before they became attributes.

(Phase 6 security fix, pass 2, P2-L8-F3, built 2026-10-09, SF.) `spans.jsonl` is appended to only when it is a regular
file of the current user, opened without following links; a symbolic link (a dangling one included) or another user's
file in its place is refused and no observer is created (a settings error naming the directory); an absent file is
created exclusively; its directory follows P2 §11.3's rule (the current user's, not writable by others without the
sticky bit). A file `out:` names is still where the settings file puts it, and the settings file is now the current
user's (§10.3). Until then the exporter appended with the JDK's defaults, through a link planted in its place.

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

*As built (Phase 6, TL, 2026-10-09):* `skillroy-scenario`: `RunObserverSeamTest` (every hook point beside its record,
masked, the trace parent and `traceId`, `NONE`'s cost, a throwing observer, the older constructors, a session's
reports, the JUnit report's line) and `TelemetrySettingsTest` (every rule of §10.3, 36 cases). `skillroy-telemetry`:
`TelemetryObserverTest` (the tree of a passing, a failing, a `SYNC_TIMEOUT` and a `REBIND_NEEDED` run on the fake
agent, statuses, the `gen_ai` span, the centred agent span, the trace parent present and absent, `minimal` against
`standard` with the exact lists, the session and a tool call continuing a client's trace, the secret and
binding-value scans, the file format; fix wave FC: the four `minimal` lists partition `TelemetryAttributes`, every
attribute of a `minimal` run is kept, replaced or hashed as its list says, and a `minimal` run of the fixture plus an
overlay pack's scenario and session reported with every record type holds none of its identifiers in clear), `OtlpTransportTest` (the loopback stub; the header's variable is given through
the settings' environment seam in process) and `ClassLoadingProbeTest` (a child JVM with `-Xlog:class+load`: finding
the providers and asking `none` load no `io.opentelemetry` class; creating an observer does). The executor's
fixtures are package-private, so the module's tests reach them through `ai.skillroy.testjava.scenario.TelemetryFixture`
in its own test sources (the same package, on the class path). `skillroy-cli`: `TelemetryCommandTest` (the file
exporter, `--json`, `run-start` and the JUnit line; the usage errors; a child JVM with `-Xlog:class+load` and no
`--telemetry`; a child JVM whose environment holds the header's variable, against a loopback stub, with every output
and file scanned for its value). `skillroy-mcp`: `McpTelemetryTest`; `skillroy-inference`: `SessionTelemetryTest`.
The scenarios module: `TelemetryStreamTest` (headless: the JUnit stream in a child JVM on the headless desk, scenario
C in `replay`, the root span written by the exit hook) and `TelemetryEndToEndIT` (§13.2 item 9, in the image).

(Phase 6 security fix, pass 2, P2-L8-F1 to F4, P2-L8-S1, P2-L8-S2, built 2026-10-09, SF.) A header now needs
`https://`, so the two tests that see a header arrive use a loopback **TLS** stub (`TlsStub`: a certificate for
`127.0.0.1` made by the JDK's `keytool` into the test's directory, nothing downloaded or committed): in process,
`OtlpTransportTest` trusts it through a package-private seam of the provider (`TelemetryObservers.otlp(…, pem)`,
`setTrustedCertificates`; no setting exposes it), and `TelemetryCommandTest`'s child JVM through the standard
`javax.net.ssl.trustStore` properties (the CLI keeps no trust-store option). Added:
`OtlpTransportTest.aHeaderIsNeverSentOverHttp`; `TelemetrySettingsTest.aHeaderNeedsAnHttpsEndpoint` and
`aSettingsFileOthersMayHaveWrittenIsRefusedNamingTheFile`;
`InferenceSettingsTest.aSettingsFileOthersMayWriteToIsRefusedNamingTheFile`; `SettingsFilesTest` (`skillroy-data`);
`TelemetryObserverTest.theRunsMessagesCarryNoPath` and `aLinkInPlaceOfTheSpansFileIsRefusedAndItsTargetLeftAlone`;
`OutputFilesTest` (`skillroy-controller`); three `PlanExecutorTest` cases (a planted link and a dangling `web-*` link
in a writable output directory; a link and a world-writable file in one whose entries the run cannot remove; a
directory others may write to); `InferenceOptionsTest.aSessionsLedgerFilesAreNeverWrittenThroughALink`;
`RunCommandTest`'s `--out` refusal; `TelemetryCommandTest`'s two new usage errors; and `AuthorCommandTest`'s
`--telemetry none`. Each that runs against the code before the fix failed there.

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
  path (`--pack base.jar --pack overlay.jar`, or two dependencies of a scenarios module); §17 Q4 (confirmed by the owner on
  2026-10-10): the private layers consume an overlay as this second jar; a layer that wraps the base pack in a class of its
  own moves that wrapper into `overlay(base)`, the same seam.
- **Scenarios stay untouched**: `pack: lending` selects the overlaid pack; `plan.json` records `packVersion` as
  `0.5.0+lending-branch@0.1.0`, which the P2 rule "a version that differs at run time is logged, not an error" (Q13)
  keeps harmless; a variant whose `db` SQL differs compiles **its own plans** from the same YAML (P2 §5's rule), §11.4.
- **Constants.** The pack's variant-sensitive literals are read from `settings()` with a default, through the
  contexts, so that an overlay changes one constant with `settings()` alone and replaces a resolver only when its
  logic differs. The keys are the pack's own (`<packId>.<name>`), documented in the pack.

As built (Phase 6, CP): `DomainPacks.overlaid(packs, overlays)` is the discovery's second half, public for tests; an
overlay that returns null or a pack with another id is refused like a missing base; `forScenario` and the CLI's
`Packs` report the refusal before anything runs (`ScenarioException`, exit 2). `DelegatingDomainPack` exposes
`base()`, `overlayId()` and `overlayVersion()` and validates the id (`[a-z][a-z0-9-]*`) and a non-blank version. The
contexts' `settings()` defaults collide in a class that implements two of them (a test fake implementing both
`ResolveContext` and `VerifyContext` must override it); the engine's contexts, the executor's, the session's, the CLI's
and the batch re-resolver's pre-flight contexts return the running pack's settings.

### 11.2 The hook catalogue of an overlay

An overlay's `hooks()` answers `HookCatalogues.overlay(base.hooks(), resource)`, where the resource
`META-INF/skillroy/hooks/<overlayId>.yaml` has the format of §5.1 with two differences: `catalogue:` is the
overlay's id, and its `hooks` entries **replace** the base's entries of the same id (every field of the entry) or
are added when the id is new; `disable: [id…]` turns base entries off. The result's hash is its own (§5.1), so a
target that carries the base catalogue is replaced under §5.3 step 3's "same id" rule only when the overlay keeps the
base's catalogue id; the sample's overlay uses its own id (`lending-branch`), so a desk that still holds the base's
catalogue makes the installer stop with `ENVIRONMENT` and the remedy, which is the right answer for a mixed-up
environment (and why §11.4's acceptance launches a fresh desk for each half).

**(Phase 8, built 2026-10-19, EH; P8 §6.8, D570.)** An overlay unions the package rules by name (an overlay's
qualifier for a name replaces the base's for the overlay's own entries; a plain name never drops a base qualifier) and
the deny lists (an overlay can never lift a base deny); the base's entries keep the qualifier baked when the base was
read, and the overlay's own and replaced entries inherit from the merged rules (the sample's `lending-branch.yaml`
replaces `limit.applied` on `ReturnCeilings` and so inherits `lending.yaml`'s qualifier). An enabled base entry the
overlay's deny matches is an error at the overlay's `deny` line unless the overlay disables it ("add it to disable").

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

As built (Phase 6, CP): `Edition {STANDARD, BRANCH}` carries the limit column and the setting name;
`LendingDb.create/createServable(name, seed, edition, today)`, `AccessControl.load(connection, edition)` and
`Engine(…, edition)` take it, the old signatures meaning `STANDARD`; `ReturnCeilings(access).applicable(code)`;
`LendingApp.edition()`. For §13.1 item 6's "which path calls it", `AccessControl` and `ReturnCeilings` carry
package-private call counters that only the application's own tests read (D191). The pack's public surface:
`LendingResolvers` public with public, non-final resolver classes, a public constructor taking the statement where a
resolver runs one (`SystemLimitResolver(sql)`, `StaffResolver(staffSql)`), the resolver ids as constants
(`STAFF_ID`, …) and the SQL texts; the verifier ids as `LendingPack` constants; `LendingAllowList.of(limitColumn,
maxReturnSetting)` (whose standard descriptions are byte for byte the 0.4.0 ones, so scenario D's recorded requests
still match); `LendingPack.MAX_RETURN_SETTING` and `DEFAULT_MAX_RETURN_SETTING`. The overlay's `BranchPack` builds its
resolution catalogue and its hook catalogue once, on first use. Gap 49 is not taken (D193). Pack 0.5.0 changes the
session's vocabulary text ("The pack: lending 0.5.0"), so the five scripted stand-in recordings of the interactive
session were regenerated by the fixture update path (`-Dskillroy.inference.fixtures.update=true`), the export's YAML,
plan and bindings with them (version and hashes only); scenario D's two live recordings are untouched (D190).

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

As built (Phase 6, CP): the overlay's jar is **not** on the scenarios module's test class path: it is a test dependency
(for the reactor order) excluded from the surefire and failsafe class paths (`classpathDependencyExcludes`) and handed
to the tests as `-Dskillroy.it.overlayJar` (`maven-dependency-plugin:properties`), which `BranchVariant` loads in a class
loader of its own over the test class path; on the class path itself, `ServiceLoader` would overlay the pack of every
standard-edition test, the JUnit glue's included (D189). The variant's bindings carry the label `approve` and are
written by `BranchEndToEndIT` under `-Dskillroy.bindings.update=true` (compared byte for byte otherwise). The negative
half runs E's standard plan with the variant's `branch-a` bindings (the same resolve hash and fingerprint) and a 10 s
step timeout.

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

Pass 1 ran on HEAD `5d617be` while this contract was written, on the four lenses whose code Phase 6 does not change:
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
the D-numbers of its four decisions, provisional until B26 was confirmed, which are D156-D159 here (§12.9); a fixer mints nothing.** FX builds
over the merged tree of the four other packages and lands before the Phase 6 code review, so that pass 2 reads the
fixes as code. Where a fix and a Phase 6 package touch one file, the FX fix lands last (the work-package list in the
session notes names them).

**Dispositions under §12.5.** The high and the six mediums are fixed in full. Of the lows, `dispositions.md` **defers
a part of five** with a release note each (S36 part (b), a deny-list of side-effect function names in `StatementGuard`;
S37 option (i), owner-only run output directories by default; a part of S40, pinning the CI example's image and actions;
S48, a minimal environment for the Playwright driver; a part of S60, relocating the `claude` CLI's configuration) and
fixes **S37 and S48 by documentation only**, which under §12.5 is an **acceptance, not a fix**, and so needs the
owner's word: the five were put to the owner as **§17 Q22**, named in B26 and repeated in B28, and accepted by the owner
on 2026-10-10, each with the phase
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

**As run (2026-10-09).** Group a (L1, L3-L7) ran on `a4b3d2e`, the tree before the code review's fix wave was integrated
into `main`; group b (L2, L8) ran on `6760bed`, after it. Eight reviewers and eight sceptics raised 39 findings (32 by the
reviewers, 7 by the sceptics from claims they broke): 38 were confirmed, **S63-S100: 1 high (S93, P2-L7-F3, model text
reaching Windows' implicit `cmd.exe` through the planner), 12 medium, 25 low; 18 counted, 16 operator-only, 4 token-holder
only**, and one was refuted (P2-L1-F2, no S-number). Six fix rounds (SA-SF, decisions D244-D286) fixed all 13 findings of
medium or above and 22 of the 25 lows; one low (S77) is fixed with a residual, and two lows (S84, S94) are left as they are;
the residual and the two are accepted by the owner in B28 (2026-10-10), each with a release note in `SECURITY.md`. The
passages the rounds wrote carry the review ids (`P2-L<n>-<F|S><m>`); `../TODO.md`, "Security review (repeat, pass 2,
2026-10-09)", maps them to S63-S100, and the record and the dispositions are `pass2/record.json` and `pass2/dispositions.md`
in the session notes.

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
the first public release is §17 Q12. **State, 2026-10-10:** both records exist with every finding dispositioned (S35-S62, S63-S100:
66 findings, 2 high, 18 medium, 46 low; everything of medium or above fixed); the "not verified" list is in `README.md`,
`SECURITY.md` and `threat-model.md` §8; `SECURITY.md`, `docs/release-checklist.md` and the threat model are in the tree; the
signature is **B28**, signed off by the owner on 2026-10-10, with the eight lows put to the owner in it accepted as proposed
(S84 beside S96 included); in `../TODO.md` the security-review item is ticked and the exit item's criterion "the repeated
security review is signed off" is marked met (the exit item stays open for the Windows criterion). No outside reader has read the record yet; under §17 Q12 that is not a gate.

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

- **`SECURITY.md`** (repository root, Markdown, no licence header): the disclosure channel (§17 Q13, confirmed 2026-10-10:
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
(`P6-SEC-1` to `P6-SEC-4`); each changes a confirmed decision and was put to the owner as §17 Q18-Q21, confirmed on 2026-10-10; each is built
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
  third-party adapter or a later primitive would be missed, map 5 §8 Q11). **As built (Phase 6, SR, 2026-10-09):**
  the descriptor writes `"script": true` only when the flag is set (a primitive without it serialises as before, so
  an older reader and the pinned wire test are unchanged; D216); `PrimitiveDescriptor` keeps its nine-argument
  constructor and gains a tenth `script` argument; the projection's `readOnly` uses the same fallback as its
  exclusions (`ToolProjection.isScript(d)`: the flag, or a name of `SCRIPT_PRIMITIVES`), so an older agent's unflagged
  `web.waitFlag` is not annotated `readOnlyHint: true` either (D217); "the policy excludes script primitives" is
  `Policy.excludesScripts()`, true when `excluded` holds every name of `SCRIPT_PRIMITIVES` (`readOnly(false)`,
  `mutating(false)`, `session(...)`), a derived method rather than a new record component (D218); the shared
  never-for-a-client rule refuses a flagged primitive under the same condition (`CallPolicy.scriptRefusal`, called by
  `CallPolicy.never`, so that `PlanPolicy` and, after FX-2's S62, the session service's verb checks both apply
  it); the dispatcher's refusal is a second check after the mutating one, with its own message ("… evaluates a
  caller's expression in the target and the agent is read-only").
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

**As built (Phase 6, SR, FX-2, 2026-10-09):** D157-D159 as stated, with these details (the amendment texts are in P5
§9.2-§9.4, §10.2, §12.2-§12.4 and §13.4, marked "Phase 6 security fix S<n>, A<m>"). D157: the meter's exhausted state
lives on `BudgetMeter` (`markExhausted`, `exhausted`), which `SessionService` now reads instead of a field of its own,
so that the service and `skillroy_scenario_run` share it; a run's refused reservation exhausts the session (D74), as a
refused resolve does; the estimate and the settlement are `…inference.budget.SessionMetering`'s, shared with
`SessionService.resolve`; a `skillroy_resolve` the server runs without an actor is metered on the same meter
(D211). D158: `ExecutorSession.compile(verb, args)` answers `Compiled(calls, cleanup, refusal)`, with an overload
`compile(verb, args, origin)` that strips the arguments as `execute` does for that origin (the session service checks
with it, D212); the never-for-a-client rule and the start-time probe arguments are `…inference.tools.CallPolicy`'s
(`never`, `arguments`, `refusals`, `probes`), to which `PlanPolicy` delegates; a verb the session service withholds is
listed by its `skillroy_catalogue` under `withheldVerbs` and not logged (the service has no log). D159: the text
resource is `skillroy://mcp/<n>.png.txt`; images are served when `ServerOptions.inference().images()` is true.

**As completed (Phase 6 security fix, pass 2, P2-L6-F2, P2-L6-F3, P2-L6-S1, built 2026-10-09, SC):** D159 is one rule
on every route, not on the two PNG resources only: a capture's bytes leave the controller to a client or a model only
under `images: true`, and then only as an image (the MCP server's blob behind a tool's resource link; a model request's
image part, Phase 7); on every text route (a tool's result at any depth, a resource served as text, a run's or a
session's file, a fact, an event, a call record, a triage request) they are elided to `<png: N bytes>` whatever
`images` says (`…scenario.Images`, applied by `RunLog`, `StepJournal`, the executor's evidence files, `PromptMask` and
the MCP server's `Masking`; P1 §8.5, P5 §6.2, §9.3, §9.4, §11.2). The decision is not changed: the text routes carried
the bytes only through the gaps pass 2 found, and an image's base64 as text is no image to any reader (D244).

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

**As built (Phase 6, HK, 2026-10-09):** items 1 to 4 and 13 as the HK package builds them:
- Item 1: `SpyTest` (6 tests; the class-file scan allows the package's own three classes, which a class always
  references, and checks major 52).
- Item 2: `ShadedContentIT` (2 tests).
- Item 3, in `skillroy-agent-hooks` (55 tests in 14 classes; the six `*FreshJvmTest` classes each in a JVM of its
  own, §2.3's note): `InstallFireRemoveTest`,
  `ProbeItemsTest` (§3.3 item by item; the unmodifiable class through an `Instrumentation` proxy that answers
  `isModifiableClass` false for it; the bounds through `hooks.maxClassBytes=1024` and `hooks.maxMethods=5`), `InstallTest`
  (two loaders, idempotency, `HOOK_CONFLICT`, `replace`, the ledger bound, a method hooked twice, the SPI, the
  restore of one removal in one batch),
  `ModesTest` (the three modes, `thrown` redacted, no retransformation on arm and clear, `hook.events`, the health
  states including `STALE` after the hooked class's loader is collected), `CaptureTest` (every scalar kind, the cut at
  100, a secret key, `secretArgs`, `secretResult`, policies on numbers and strings on the caller's thread, a throwing
  policy, a handle without `toString()`, the input budget measured on the encoder itself, re-entrancy through a
  policy that calls a hooked method), `StubTest`, `RestoreTest` (BC1, BC3, BC4, partial removal, the ungated
  primitives), `HooksModuleTest` (flags, options, the spy jar's content check, `Spy.VERSION`), and in fresh JVMs
  `FirstInstallFreshJvmTest` (nothing appended for a catalogue that fits nothing or a refused jar; one append),
  `StrictLoaderFreshJvmTest`, `DetachBudgetFreshJvmTest`, `PlantedReferenceFreshJvmTest`, `NexusMeasureFreshJvmTest`
  and `LoaderCollectableFreshJvmTest` (the last two with the module in a child-first loader of its own). In
  `skillroy-agent-core`: `HookSupportTest` (the contexts' `isSecretKey`/`redactText` with the `redact` option and a
  `RedactionKeys` pattern; `optionalModules`), `ModuleClassLoaderTest`; in `skillroy-agent-bootstrap`:
  `AgentClassLoaderTest`. The loader tests prove the parent's `Spy` wins over a copy in an agent or module jar.
- Item 4: `HookWireTypesTest` (9 tests), `ErrorCodesTest`, `ProtocolConstantsTest`; `ClassFileRefsTest`,
  `ByteVerificationTest`, `RetransformBatchesTest`, `DefaultMethodsTest` in `skillroy-agent-api`.
- Item 13: `DistributionContentIT`, `HomeConstantPoolIT`, `OptionalModulesIT`, `SharedLoadersIT` (§2.5's note).

*Fix wave (Phase 6 review, FC, 2026-10-09):* item 6's `sign.on.checked` sentence had only the display test
`HookChecks` order 67 (§13.2 item 1), and its check passed on the password index's absence: the committed entry
captures `args: [0]` only and the engine visits only the captured indexes, so `secretArgs: [1]` was never exercised
(finding L3-F3 as its sceptic restated it). Both tests now enable a variant of the pack's entry that also captures
index 1 (`HookChecks.signOnCheckedCapturingThePasswordIndex`; the committed catalogue is unchanged and its entry stays
off) and assert `<redacted>` at index 1 strictly. **Without a display**, `SignOnCheckedHeadlessIT` in the scenarios
module (failsafe, since the agent home is unpacked at `pre-integration-test`; no display, so it runs in the normal
`mvn verify`): the real agent with `optionalModules=skillroy-agent-hooks` in a headless child JVM on the test JVM's own
Java (`SignOnHeadlessMain`: the seed-A database and the real `AccessControl`, checking staff 100's password every
100 ms, the password read from a file, never the command line); the variant installed; the first event's `args.0` is
the code, `args.1` `<redacted>` and `returned` true, and the password is in no event, no `hook.health` answer and no
line of the child's output. Its agent home is a copy of the module's without the lending module, whose readiness
waits for a desk the host does not start. With `secretArgs` removed from the variant the test fails with the password
at index 1. In the image, order 67 does the same on the desk on both target Javas.

**Fix wave (Phase 6 review, FA, 2026-10-09):** item 3 gains `PartialInstallTest` (5 tests: a hook over two loaders whose
copy fails is taken out of the system class, which is restored `OK` before the call answers, the ledger empty, the
handler and the transformers gone, the slot free, and a `STUB` of the same id pinned to the copy stubs the copy only; a
refused hook taken out of a class that keeps another hook; a restore that fails keeps the entry and the slot, the hook
`ERROR("residue after a refused install")` and its id refused until `hook.remove {}` restores the class; an install
whose only class fails releases the slot at once, its reason through `redactText`; an `ERROR` reason redacted in
`hook.health` and the capability), `CaptureTest` +3 (the allow-list against every excluded shape; a view over a
synchronized map held by another thread encoded as a handle without waiting; a `BigDecimal` of 4 million unscaled bits
cut by its bit length in milliseconds), `ProbeItemsTest` +1 (a constructor is `UNAVAILABLE("constructors are not
hookable")` and the call installs the other spec), so 64 tests in 15 classes; the six fresh-JVM classes start with
`Harness.assumeFreshJvm()`, `PlantedReferenceFreshJvmTest` also has the residue's id refused by a second install, and
`LoaderCollectableFreshJvmTest` waits for its application thread at most 30 s (§2.3's note). Item 4: `HookWireTypesTest`
(`<init>` valid, `<clinit>` refused). Item 5: `HookCataloguesTest` (a constructor entry is read; the corpus's `<clinit>`
row). Item 13: `ChildJvm.pid` takes the leading digits of the `pid=` line, since on a Java 8 child run with
`-verbose:class` a `[Loaded …]` line can land between the digits and their newline (`ChildJvmIT`; found by the first
Linux matrix round's Java 8 test-JVM leg in `OptionalModulesIT`).

**(Phase 6 security fix, pass 2, SE, 2026-10-09):** item 3 gains `PartialInstallTest` +1 (a `hook.remove` of one of
two hooks whose retransformation without it throws: the removed hook a residue, its id refused by a replace pinned to
the copy, no stub can reach the system class; the replace's `replaced.verified` `FAILED`; P2-L2-S1, P2-L2-F3),
`RestoreTest` +1 (a batch that throws counts no engine event for the class the JVM never transformed, and a later
foreign retransformation is the restore's `DEGRADED("foreign redefinition observed: 1 events")`; P2-L2-F4),
`CaptureTest` +1 (captured strings through `redactText`; P2-L2-F7), `DetachLockFreshJvmTest` (P2-L2-F6) and
`ReplaceResidueFreshJvmTest` (P2-L2-F3), so 70 tests in 17 classes. In `skillroy-agent-bootstrap`:
`OptionsFileVolumeTest` (3: the private copy of the volume rule equal to the protocol's `Volumes`, this host's volume,
and an options file on real FAT and exFAT images refused where `hdiutil` exists). In `skillroy-scenario`: `HooksTest`
+1 (a replace whose remove verified `FAILED` is ERROR / ENVIRONMENT).

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
   carry a list of handles and no application field (**Fix wave, FA, 2026-10-09:** one handle: the desk returns an
   unmodifiable view, which §4.3's amended rule never iterates, D237); `hook.stub {id: line.amount, value:
   "1.00"}` without `confirm` is `CONFIRM_REQUIRED`, with it `hook.health` lists the stub, the next scanned line
   shows `amount 1.00` in `lending.loan`, `clear: true` restores the computation and empties `stubs`; the same install
   again answers `alreadyInstalled`; a second catalogue id is `HOOK_CONFLICT`; **after check 14** the `hook.remove {}`
   demonstration restores every class with `verified: OK` and `foreignEvents 0` and the matrix drops the `hook.<id>`
   ids; **in checks 16 and 17** the re-attach carries the same options and installs again without a second append (the
   target's log holds **one** CDS line on Java 25 and none on Java 8; `hook.probe.spy.onBootPath` true before the
   install); check 19 stays last. The target's log is scanned for any line from Byte Buddy, `sun.misc.Unsafe` or an
   exception with `skillroy` in its trace: none.

   As built (Phase 6, CP): `HookChecks` (a subclass of `LoadModeChecks`, orders 65, 66, 67, 165 and 205, and
   `afterReattach` called from checks 16 and 17); orders 166 to 169 are left for BW's item 7. The log scan at 205
   also reads the JEP 451 text: checks 16 and 17 re-attach through `ATTACH`, a dynamic load that a Java 25 target
   reports, so every "loaded dynamically" line must name `skillroy-agent-bootstrap.jar` (none another jar: the engine
   never loads an agent) and a Java 8 log holds none.
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
   As built (Phase 6, BW, 2026-10-09; moved at integration the same day): item 7 is three checks of `HookChecks`
   at the orders CP reserved, 166-168, after the `hook.remove` demonstration (165) and before check 15 (170). The hooks
   mode loads `optionalModules=skillroy-agent-hooks,skillroy-agent-byteman` with `byteman.jar` the scenarios module's
   test-scoped Byteman (`LendingTarget.HOOKS_LEG_MODULES`, `LendingTarget.bytemanJar()`), so the hooks leg's module
   lists (checks 1 and 15) gain `byteman` and its golden `javaagent-hooks.json` gains `rule.engine OK(in-process)`,
   which `CapabilityGoldenTest` requires of the hooks leg alone. The return line is driven through `lending.input`
   (staff 100, a return of one item, so `$!` is 20.00), not by running scenario A: a run's step 0b would install the
   pack's hooks again under `AUTO`, a retransformation of `AccessControl` the rule's byte check would count as foreign.
   166: `rule.engine OK(in-process)`, only the agent listens, `rule.check` ok with `typeChecked: false`, `CONFIRM_REQUIRED`
   without confirm, `killJVM` `RULE_REJECTED`, `rule.list` not loaded; 167: submit with confirm, `rule.fired` with a
   handle and 20.00, `rule.list` shows it; 168: unload `verified: OK` with 0 foreign events, the same listening sockets
   after, nothing of Byteman on the desk's output. Green in the arm64 image on the Java 8 and Java 25 desks
   (`LendingEndToEndIT$JavaAgentHooks`, 37 tests each); the other hooks-mode classes (`ResolutionJavaAgentHooksSeedA/B`,
   `InferenceEndToEndIT$JavaAgentHooksSeedA/B`, `BranchEndToEndIT`) stay green on Java 25 with the module loaded.
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

    *Fix wave (Phase 6 review, FC, 2026-10-09):* check 18 read every file under the mode's run root, but its
    self-check of the kinds it read was still Phase 2's list (finding L3-S3). It now also asserts `capabilities.json`
    in every mode (every run archives its matrix, §6.4) and, in the hooks leg, order 67's `sign-on-events.json`, the
    `CAPTURE` events of `sign.on.checked` with index 1 captured (§13.1's fix-wave note). The `hooks` records are lines
    of `run.jsonl`, which the check reads; no load mode's run root holds `spans.jsonl` (`TelemetryEndToEndIT` writes
    its own under `it-runs/telemetry/` and scans it with `SecretScan`), and a file of that name there would be read
    like any other.

### 13.3 Windows (waits for the Windows host; a written procedure, not run in this phase)

`ci/run-on-windows.cmd` and the list of §9.5 (the planner through a launcher path with a space among them), run twice
without a flake once the host exists, with the results in a Windows row of the matrix table and the "not verified on
Windows" marks removed from `README.md`, `SECURITY.md`, `threat-model.md` §8, P1 §2.5 and `../TODO.md`. Until then
the marks stay and §14 says so.

### 13.4 Live and opt-in (not required)

A recording of scenario D for the branch edition (`inference-record` profile, the owner's key) so that D could join
the variant's runs; `--telemetry otlp` against a real OpenTelemetry Collector on the operator's machine, following the
procedure in `README.md`'s telemetry paragraph. Neither is an exit criterion. *(The Collector run was made on 2026-10-11
in the arm64 image: one trace of 95 spans received, no password in the Collector's output; `../TODO.md`, Phase 6. The
variant's recording of D is not made.)*

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
| the repeated security review is signed off | §12.6: pass 1 fixed by the build (S35-S62), pass 2 after the build, the record, the dispositions, **the owner's signature (B28)** | **met** (2026-10-10): signed off by the owner, B28 |
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
| 32 | Hook catalogue: an `enabled` flag per hook | closed (built 2026-10-09, CP): §5.1, the sample's `sign.on.checked` |
| 33 | Third-party isolation when the host ships an older copy of the same library | closed (built 2026-10-09, HK): §2.1's relocation and BS3 RESULT §9 fact 10; `ShadedContentIT` and `HomeConstantPoolIT` keep it closed |
| 49 | `LendableItem` cannot say "an item that needs no induction" | not taken (D193): the build had no room and nothing depends on it; it waits for the next pack change (§6.3) |

(Phase 7, built 2026-10-10; P7 §16.2.) Phase 7 took gap 49 (closed: the `inductionRequired` argument of `LendableItem` came with
pack 0.6.0, D394), closed gap 36 (`web.pointer`) and gap 76 (the capture painted from the root pane), documented gap 60 in
A62's passage (the fix stays later) and left gap 31 where it was; it numbered 77-87, and its build 88-90 (`../TODO.md`).

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

The build added two, numbered **58-59**: **(58)** after another agent's `redefineClasses` HotSpot reconstitutes a class
with the constant pool it merged across redefinitions, so the hooks module verifies a restore relative to the first
bytes (D172); whether a Byteman restore meets the same on a real redefinition, and whether a Java 8 JVM behaves alike,
is not measured; **(59)** `re-resolve` and `triage` export their root span only and the planner's `skillroy mcp` child
exports nothing (D168): spans beyond the root, and a child started with telemetry, are a further egress decision.

---

## 16. Decisions taken in this contract, in its build and in the fixes of its review (D114-D286)

D114-D159 were taken by this contract (D149 and D150 as first written are superseded by D156 and D159 and kept as
struck rows, so that the numbering stays stable). **D160-D221 were taken by the build of 2026-10-09**: each package
recorded its decisions as `D-<tag>-<n>` in its own report, and the orchestrator numbered them at integration, in the
order the packages landed (TL D160-D168, HK D169-D183, CP D184-D193, BW D194-D207, FX-2 D208-D215, FX-1 D216-D221; each
package's ids ran from 1 without a gap or an extra member), replaced every `D-<tag>-<n>` of the "As built" notes and of
the other documents by its number, and wrote the rows below, each naming the section it touches and the package that
took it (B27, confirmed by the owner on 2026-10-10). **D222-D286 were taken by the code review's fix wave and by the fix rounds of the repeat
security review's pass 2, on 2026-10-09**, recorded the same way (`D-<tag>-<n>` in each fixer's report) and numbered by
the orchestrator in the order the fixers landed on `main` (FB D222-D229, FC D230-D233, FD D234-D235, FA D236-D243, SC D244-D249,
SD D250-D261, SA D262-D265, SB D266-D270, SE D271-D279, SF D280-D286: 65 decisions, each fixer's ids from 1 without a gap), with every
`D-<tag>-<n>` of the contracts replaced by its number. FA-FD are the fixers of the code review, SA-SF those of pass 2 (§12.3);
the finding each decision answers is named by its review id (`L1-F1`, `P2-L4-S1`, ...), and `../TODO.md` maps pass 2's ids to
S63-S100.

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
| D127 | Hooks are switched per run: `RunOptions.hooks` (`HooksMode AUTO \| ON \| OFF`), `--hooks`, `skillroy.hooks`; never a scenario key; `plan.json` stays version 5 (§6.1) | a scenario key `hooks: {mode}` with plan version 6; the module's presence in `modules/` as the switch; the catalogue's `enabled` flags as the switch | the exit criterion says the scenarios pass *unchanged* with hooks on and off, so the YAML cannot carry the switch; `requires:` and guards already express "the same scenario on a target without hooks" (B14, design §1.3); a plan version for a value nothing in the plan uses is churn |
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
| D160 | The telemetry settings reader (`telemetry.yaml`, the `skillroy.telemetry.*` properties, the loopback rule, the header rules) is `TelemetrySettings.load` in `skillroy-scenario`'s `observe` package; the module's `create` re-checks the endpoint and reads the header values (§10.3; TL) | the reader in `skillroy-telemetry` (the contract notes' first place) | the JUnit glue does not depend on the module and must read the file and the properties alike; the CLI must decide `none` without loading the module; the reader is plain JDK and StrictYaml |
| D161 | `RunObserver` gains the default no-op methods `sessionStarted`, `sessionStepStarted`, `sessionRecord`, `sessionEnded`, `turnStarted`, `toolCallStarted` and `traceId`; `StepStart` gains `origin` and `CallEnd` gains `rttMs` (§10.2; TL) | after-the-fact spans only; buffering a session's steps until the turn ends | a span must be open before its children: §10.4 parents a session step under its turn and a tool call's work under the client's trace; `traceId` is what `run-start`, `run --json` and the JUnit report need; `rttMs` is what centring the agent span needs |
| D162 | A session's observer is `SessionOptions.observer`; `ExecutorSession.observer()` hands it to the session service and the MCP server (§10.2; TL) | a setter on `ExecutorSession`; a component on `ServerOptions` | the session's own start is observed at `open`; one place for every session host; `ServerOptions` is unchanged |
| D163 | The SDK's `BatchSpanProcessor` worker keeps its own thread name; the JDK sender runs on daemon threads named `skillroy-telemetry`; no retry; no-op meter providers, and nothing asks `GlobalOpenTelemetry` (§10.4; TL) | a hand-written batch processor to name its thread; the SDK's default retry | 1.66.0 has no thread factory, and a processor of our own is more code to audit than a thread name; a refused endpoint must cost one attempt per batch within the timeout |
| D164 | The seven scenario records and the five step records (among them `expect`, `db`, `prompt`, `fidelity` and `capture`, for which the SPI had no method) reach `scenarioRecord` through a tap in `RunLog` (`RunLog.observe`), by record type, as written (§10.2; TL) | an observer call beside each write site (about twelve sites, some of them CP's) | the records and the spans cannot disagree; CP's `hooks` and `capabilities` writes are covered whichever package lands second; the step span's events (§10.4) had no hook point |
| D165 | A telemetry header may name no `ANTHROPIC_*` variable (a superset of the four names of D141, `ANTHROPIC_BASE_URL` included); an endpoint may carry no user information, query or fragment (§10.3; TL) | the four named variables only; any URL with an allowed scheme and host | a variable the gateway reads, or will read, is as much the model's as the key is; user information in a URL is a secret in an option |
| D166 | `minimal` names the `agent` span without its primitive (with `skillroy.id.hash`); `session <id>` and `turn <n>` keep theirs. The exact list does not name the correlation id, which keeps the scenario id: left as a question for the code review (§10.3, §10.4; TL). **The correlation-id clause is superseded by D230** (hashed whole) | the five names of §10.3 only | the agent span's name repeated the primitive that `minimal` replaces |
| D167 | The root span is `skillroy <command>` (`run`, `session`, `mcp`, `re-resolve`, `triage`, `author`), and `skillroy junit` for a JUnit stream (§10.4; TL) | `skillroy run` for every command | the trace says what produced it; a session or an MCP server is not a run |
| D168 | `re-resolve` and `triage` export their root span only; `author` exports its root, the planner's ledger entries and its bind and run; the planner's `skillroy mcp` child is started without telemetry (§10.3, §10.4; TL) | plumbing the observer through the batch job, triage and the planner's child | those paths have no executor run to observe and their model calls are in their own ledgers and reports; passing options to a child the planner starts is a further egress decision |
| D169 | `HookProbe` gains `loaderIds?: [string]` (`system`, or the loader's identity hash in hex) beside the count `loaders` (§4.1; HK) | reading `loaders` as the list; no way to learn a loader's id | §4.1 lets `target.loader` name the hexadecimal identity the agent reported for a loader in an earlier probe answer, and the shape had no field that reports one |
| D170 | `RetransformBatches.Sizes` and `of(List, Sizes, long, int)`; the three-argument form reads each class's resource size and counts an unknown size as the byte bound (§3.6; HK) | the three-argument form only | a batch bounded by bytes needs the classes' sizes, which a `List<Class<?>>` cannot give; the engine passes the bytes its recorder captured |
| D171 | The advice binds a site key `<id> <class> <method><descriptor>`, resolved by the handler's registry (§3.2, §4.3; HK) | the bare hook id with a second lookup by class and descriptor (impossible: `Spy` passes nothing else); several constants (`Spy`'s shape is fixed at three arguments) | §4.3's payload names the class, the method and the descriptor; one map read on the application's thread |
| D172 | A residue is a `Spy` reference that the restored bytes hold **and the first bytes did not**: `ByteVerification` changed after CP and BW had merged part 1 of HK; BW's two recorders are compatible with it (§3.5, §3.6, §7.4; HK) | the constant-pool scan of the restored bytes alone (the contract's wording) | HotSpot merges constant pools across redefinitions and, after another agent's `redefineClasses`, reconstitutes a class with them: a class hooked, restored, redefined and hooked again starts from bytes that already name `Spy` in unused entries (reproduced; `Spy`'s counters show the running code calls nothing); the old rule failed such a restore and held the slot for the JVM's life |
| D173 | The restore's recorder is a second instance, registered last for one restore and removed after it; the first recorder stays ahead of the hook transformer (§3.5; HK) | re-registering the one recorder last (the contract's wording) | re-registered last for good, the recorder would capture advised bytes as a later class's reference |
| D174 | The hook transformer stays registered until the ledger is empty, answering null for a class being restored (§3.5; HK) | `removeTransformer` as soon as no hook is left (the contract's other option) | the pre-filter is what keeps the handler strongly reachable while an advice exists (D117); one case instead of two |
| D175 | Each of the six `*FreshJvmTest` classes of the hooks module runs in a surefire execution, so a JVM, of its own; the other classes share the default execution's JVM and are made independent of each other (the tests' spec helper pins a fixture class to `loader: system`) (§2.3; HK). **Fix wave (FA, 2026-10-09):** an execution that runs no test fails the build, and a fresh-JVM class skips under a merging `-Dtest` and fails otherwise (§2.3's note; D240) | `reuseForks=false` for every class (the first build) | a bootstrap append and the slot last for the JVM's life, so those cases need fresh JVMs; but surefire 3.6.0 with `reuseForks=false` runs no test at all when `-Djvm` names a Java 8 JVM (the fork starts and is never handed its class; `reuseForks=true` runs it), which silently emptied the Java 8 leg of the module |
| D176 | The option ranges the contract left open: `hooks.maxClassBytes` 1024 to 16 MiB, `hooks.maxMethods` 1 to 65535, `hooks.maxClasses` 1 to 1024, `hooks.capture.maxBytes` 1024 to 1 MiB (§3.7; HK) | unbounded | a value out of range must fail the install naming the key (§3.2); the bounds keep a typo from disabling the size limit or the budget |
| D177 | A detach that leaves classes sets the system property `skillroy.hooks.adviceLeft` to their count; the next probe's reason reads it ("an unknown number of" without it) (§3.5; HK) | a constant text without the count | the contract's reason names `<n>`, and the earlier engine's loader is unreachable from the new one |
| D178 | Over several loaders a spec is installed on all its classes or on none (the first failing class decides) (§3.3; HK). **Fix wave (FA, 2026-10-09):** after step 6 as well: a hook refused there is taken out of the classes that took its advice before the call answers (§3.3's note; D236) | hooking the classes that pass | a partly installed hook would report OK while some copies fire nothing; simpler to reason about |
| D179 | A method already hooked is `UNAVAILABLE("method already hooked: …")`; `capture` on a spec that is not `CAPTURE`, and duplicate ids, are `INVALID_PARAMS` (§3.3; HK) | two advices on one method; leaving the catalogue reader as the only check | two advices give double events per call; the agent is reachable through the SPI and through `skillroy hooks install` as well |
| D180 | `hook.health` also answers `otherAgents` and `spy.onBootPath` (§4.2; HK; CP's `skillroy hooks status` reads them there) | `otherAgents` only in the probe and install answers | §6.2's `skillroy hooks status` prints `otherAgents` from `hook.health` |
| D181 | The output bound of a capture equals its input budget (`hooks.capture.maxBytes`): an event that used the whole budget drops `args`, then `self`, then `returned` (§4.3; HK) | a larger output bound | the contract states one limit for both; recorded so that a catalogue author knows that captures keep their values whole only well below the budget |
| D182 | Encoding readings: JDK number boxes, `BigDecimal` and `BigInteger` exactly; NaN and infinities as text; extreme `BigDecimal` scales as scientific text; a `BigInteger` above 6,000 bits cut; JDK collections iterated except those whose iteration takes a lock (synchronized wrappers, `Vector`, `Stack`, `Hashtable`, `Properties`, blocking queues), JDK wrappers such as unmodifiable views iterated (**Fix wave (FA, 2026-10-09):** replaced by an allow-list of exact JDK classes; every view and wrapper, `TreeSet` and `ConcurrentSkipListSet` are handles; §4.3's note, D237); map keys as JDK-scalar text or handles; a policy's non-scalar result redacted (§4.3; HK) | encoding any `Number` or `Collection` as such | no application method may run on the application's thread, no application lock may be taken, and the event must be JSON (NaN is refused) |
| D183 | A catalogue of which nothing installs is not recorded as installed (§3.2; HK) | recording it (then `alreadyInstalled` for nothing) | the next run probes again, so a class loaded later is hooked; nothing permanent happened |
| D184 | The catalogue reader: `HookCatalogues.read` throws `ScenarioException` with source and line; `ofPack` caches per pack class and id and rethrows a malformed resource as `IllegalStateException` on every call (a compile error or `ENVIRONMENT`); `packages` absent means every entry off unless `enabled: true`; a `reflect` fallback is an error at its line; the secret-name warning also covers disabled entries; `emits` is kept as written, so that an explicit default topic hashes differently from an absent one (§5.1; CP) | normalising `emits` | the catalogue hash must be the agent's |
| D185 | `SessionOptions.hooks` is the eleventh component (the ten-argument constructor is kept; `observer` is the twelfth after the merge with TL): a session has no `RunOptions` (§6.1; CP) | a parameter of `ExecutorSession.open` | the MCP server and the CLI already carry options |
| D186 | Step 4's fallback is the first `seam` whose primitive the target serves, read from `GET /schema` (a primitive's descriptor names its capability, or none) and only when a hook is unavailable; without a schema, a capability of the primitive's own id decides (§5.3; CP) | treating the seam id as a capability id | wrong for most seams |
| D187 | `hook.health`, `hook.events` and `rule.list` are offered by every MCP and session policy, the session service's read-only one included, although P5 §12.4's policy otherwise offers no primitive read (§4.2; CP) | offering them only under `--allow-base` | a person's evidence is what a client is meant to read |
| D188 | A catalogue with no enabled entry counts as "no catalogue": `AUTO` skips it, `ON` is `PRECONDITION_FAILED` (§5.3; CP) | installing it as a catalogue of nothing (recorded as installed, nothing armed) | nothing would be installed, and an installed catalogue that arms nothing would read as hooks on; the controller's side of the rule of D183 (nothing installed, nothing recorded) |
| D189 | The branch overlay's jar is off the scenarios module's test class path (`classpathDependencyExcludes`), handed in as `-Dskillroy.it.overlayJar` and loaded by `BranchVariant` in a class loader of its own (§11.4; CP) | a separate module for the variant's tests | on the class path `ServiceLoader` would overlay every standard-edition test's pack; a separate module is heavier, and the variant needs the scenarios module's harness |
| D190 | Pack 0.5.0 changes the session prompt's vocabulary line, so the five scripted session stand-in recordings were regenerated by the fixture update path, with the export's YAML, plan and bindings; scenario D's two live recordings are untouched (§11.3; CP; FX-2 regenerated the five again for D210) | re-recording scenario D's live requests | their requests still match: the standard allow-list descriptions are byte-identical |
| D191 | `AccessControl` and `ReturnCeilings` carry package-private call counters that only the application's own tests read (§11.3; CP) | no counters (the path inferred from the hook's events alone); public counters | §13.1 item 6 asks which path calls them, which the hook's events cannot say without a hook; package-private keeps them out of the application's surface |
| D192 | `HeadlessDesk.launch(env, hooks, swingTree)` scripts a hooks module (a matrix with `hook.engine` and `hook.limit.applied`, `hook.health` naming the pack's catalogue, one `hook.limit.applied` event per committed return line), so that scenario E's headless test runs without the hook engine (§11.4, §13.1; CP) | scenario E tested in the image only | E's headless test must run without HK's engine, which needs an `Instrumentation` no in-process headless test has |
| D193 | Gap 49 (an `inductionRequired` argument on `LendableItem`, the contract's optional item) is not taken in Phase 6 (§11.3, §15.2; CP) | taking it with pack 0.5.0, which regenerated every plan anyway | no room, and nothing depends on it |
| D194 | Byteman is started with the options `listener:false,manager:<the sys jar's RuleManager>`, in that order, and nothing else (§7.4; BW) | the contract's string with `prop:org.jboss.byteman.verbose=false`; `manager:` before `listener:false` | BS14: `prop:` prints on the host's stdout and, because Byteman reads the property by presence, `=false` turns verbose on; a later `listener:false` clears the manager |
| D195 | The helper's `fired` is a set of fixed-arity overloads of one to eight `Object`s (§7.3; BW) | `fired(Object...)` | BS14: Byteman's type checker does not match a varargs method |
| D196 | Two byte recorders, one ahead of Byteman (registered before `premain`) and one behind it (after `premain`); in the reuse case a priming retransformation of the matched classes not yet seen (§7.4; BW) | §3.5's single recorder re-registered last; no reference in the reuse case | BS14 condition 5: a recorder after Byteman sees Byteman's output, never the reference, for every later submit |
| D197 | Byteman's trace map is redirected into the agent's log (WARN, bounded) and removed at uninstall (§7.4, §7.5; BW) | leaving Byteman's stderr output and recording the limit; relying on the type check alone | BS14 condition 3: a rule on a class of a loader other than the system loader escapes `RuleCheck`, and its first-trigger failure would reach the host's console |
| D198 | On Windows `claude` plus each `PATHEXT` extension is tried before a bare `claude`; `PATH` and `PATHEXT` are read case-insensitively (§9.2; BW) | the bare name first; an unspecified order | npm installs an extensionless shell shim beside `claude.cmd`, which Windows cannot start |
| D199 | The refusal set for a launcher path or argument written into a `cmd.exe /c` configuration: the double quote, `%`, `^`, `&`, the pipe, `<`, `>`, white space and control characters (§9.2; BW) | the contract's list only | `<` and `>` are redirections; a tab splits like a space |
| D200 | `RuleManager.initialize` keeps the first `Retransformer` of the JVM and ignores later calls (§7.3; BW) | a plain store | the public method is callable by any class; set-once stops replacing or clearing what Byteman handed over |
| D201 | A cut-short uninstall parks the list of the leftover rule names in `RuleManager`'s slot (§7.5; BW) | a system property; reading the agent's log; a third accessor | the names must cross agent instances without a new public surface or host-visible state |
| D202 | `RuleCheck`'s type warnings are errors in `rule.check` and `rule.submit` (§7.3; BW) | treating them as warnings | BS14: `$!` at `ENTRY` is only a warning ("failed to find any matching trigger method") and would install a rule that never fires |
| D203 | A `threw` status after `installScript` removes the rule and answers `RULE_REJECTED` (§7.3; BW) | reporting `installed: true` with the status | the rule was not injected and Byteman printed nothing; leaving it would be a silent no-op |
| D204 | The lint requires a fully qualified `CLASS` or `INTERFACE` and nothing outside `RULE … ENDRULE` but blank lines and `#` comments (§7.3; BW) | unqualified names; script-level lines | an unqualified name matches any package's class, a later-loaded JDK class included; script-level `HELPER`, `IMPORT` and `COMPILE` lines set the defaults of the rules after them |
| D205 | The binding encoding reads a container only when its class is the JDK's (§7.3; BW) | iterating any `Collection` or `Map` | iterating an application's collection runs its code on the application's thread |
| D206 | The cut-short test seam is the JVM system property `skillroy.agent.byteman.test.keepRulesAtUninstall` (§7.5, §7.6; BW) | an agent option; a slow transformer | no option is added to the agent's surface; an IT can set the property in the child |
| D207 | `AuthorCommand` writes the planner's MCP configuration as one owner-only file `skillroy-mcp-<16 hex>.json` in the temporary directory, through `writeOwnerOnly` (§9.3; BW) | an owner-only temporary directory holding the file | one file to delete as before; the fix of S60 (FX-2) puts the planner's work under one owner-only directory anyway |
| D208 | The MCP `data_query` tool refuses a `$` reference in `sql` or `params` by its syntax (`References.containsReference`), whether or not a slot of that name exists; a literal such as `'a$$b'` or `'US$ 5'` runs byte for byte (`PlannedCall.literalArgs`) (§12.9; P5 §9.3, A54; S55; FX-2) | refusing only a reference to a slot the session holds | the same text would then be refused or run depending on what was resolved before; the disposition's fix and its test (c) disagreed (`'$notaslot'` was listed as running byte for byte), and the contract text (refuse) won |
| D209 | The overlay bridge never cuts before the mask: it keeps a request whole up to 100000 characters, answers a longer one without a model call, and asks through `SessionService.ask(text, 10000)` (mask the whole, then cut) (P5 §6.2, A53; S53; FX-2) | cutting at 100000 plus a margin; keeping up to `PromptMask.MAX_TEXT` | any margin fails for a secret longer than the margin, which is the finding itself; keeping up to 100000 would still cut a straddling secret at 100000 |
| D210 | The projected tools of a session and of an MCP server are masked after `strict(...)` by a wrapper (`ToolProjection.masked`), not inside `primitive(...)` and `verb(...)`; the key rule therefore rewrites the sample's `signOn` and `authorise` descriptions ("credential: {realm: staff, key: code}") in the tools as it already did in the planner's prompt, and the five committed session stand-in recordings were regenerated (P5 §6.1, §6.2, A50; S49; FX-2) | masking inside `primitive(...)` and `verb(...)` | masking only removes data keywords and masks texts, so the result equals masking before; the wrapper keeps FX-1's edits of `primitive` and `offered` conflict-free. A false positive of the key rule on pack prose, put to the owner (the sample's text could be reworded) and confirmed as built in B27 (2026-10-10) |
| D211 | The exhausted state of a scope lives on `BudgetMeter`; a run's refused reservation exhausts the session (D74); an actor-less `skillroy_resolve` that names a model is metered too (§12.9, D157; S56; FX-2) | a run refusal that leaves the session open for smaller resolves; leaving the actor-less path unmetered | the first gives two rules for one budget; a gateway that serves the binds while the actor cannot be made would be unbounded |
| D212 | `ExecutorSession.compile(verb, args, origin)` beside the specified two-argument form (§12.9, D158; S57, S62; FX-2) | the two-argument form only | the session service checks exactly the arguments `execute` will compile |
| D213 | A session verb withheld by the call-time gate is listed by the service's `skillroy_catalogue` and not logged (P5 §12.4, A60; S62; FX-2) | logging it (the disposition's word) | the service has no log of its own |
| D214 | The secret-looking property in S49's test is `password`, not `pin` (P5 §6.1, A50; S49; FX-2) | `pin`, the disposition's example | `pin` is not a secret word of `SecretKeys.looksSecret`, so the example would not exercise the rule |
| D215 | S58's test of a run's failure capture calls `registerRun` directly with a planted `failure/` directory (P5 §9.4, A57; S58; FX-2) | a desk run | a desk run does not write a PNG deterministically |
| D216 | `PrimitiveDescriptor.toJson` writes `"script": true` only when the flag is set (§12.9, D156; P1 §3; S42; FX-1) | always writing the key (the descriptor's other keys are always present) | a primitive without the flag serialises byte for byte as before: older readers, the pinned wire test and any catalogue golden |
| D217 | `ToolProjection`'s `readOnly` uses the same fallback as its exclusions (`isScript`: the flag, or a name of `SCRIPT_PRIMITIVES` for an older agent's descriptors) (§12.9, D156; S42; FX-1) | the flag alone (the disposition's step 5) | an older agent's unflagged `web.waitFlag` would still be annotated `readOnlyHint: true` |
| D218 | "The policy excludes script primitives" is the derived `Policy.excludesScripts()` (true when `excluded` holds every name of `SCRIPT_PRIMITIVES`), applied by the projection and, through `CallPolicy.scriptRefusal`, by the MCP server and the session service (§12.9, D156; S42; FX-1) | a new boolean record component | it keeps `Policy`'s canonical constructor and every caller (FX-2 and CP touch the same file); one rule for both hosts |
| D219 | The raw channel bound is 6 x `MAX_JSON_CHARS` + 64 KiB (`WebLib.MAX_CHANNEL_CHARS`), screenshots exempt; the receiving side's own 1 MiB rules apply to the parsed value (P4 §4.6, A45; P1 §2.4; S43; FX-1) | `MAX_JSON_CHARS` + 64 KiB (the disposition's figure) | a genuine 1 MiB value crosses the channel escaped twice, so a quote-heavy or non-ASCII value near 1 MiB is 2 to 6 MiB of raw text; the disposition's figure would refuse values the library produces and the adapter kit's 2 MiB-string item exercises |
| D220 | S38 goes through `Credentials.find`, `Credentials.Location` and `Credentials.loadFound`; `locate` refuses the same values with an unchecked `IllegalArgumentException` (P2 §4; S38; FX-1) | a checked `CredentialsException` from `locate` | no change to the callers in other packages' files (`AuthorCommand`, `BindArgs.credentialsDir`, `ScenarioTests.streamObserver`); one provenance for the CLI and the JUnit glue; the overload is `loadFound`, not `load`, because `load(null)` would become ambiguous |
| D221 | A subject over 1 Mi characters in a browser selection is `INVALID_PARAMS {reason: "text too long"}` on both paths (P4 §4.2, A48; P1 §8.4; S46; FX-1) | skipping that browser (no match) | "refused, never silently cut" (S24's rule); the same code on both paths |
| D222 | The Byteman uninstall is bounded by the detach budget: a `ReentrantLock` taken with `tryLock` for at most the budget and held to the end of the uninstall, a `detaching` state (while it holds, `rule.*` answer `CAPABILITY_UNAVAILABLE("the byteman module is detaching")` and `rule.fired` stops), a worker that only calls `removeScripts` and records its outcome by compare-and-set, while bookkeeping, verification, logging and parking run on the uninstall's own thread; a submit that gave up waiting for installs nothing more and hands the `Retransformer` back when no rule of the module is installed; the over-report of a late removal is documented (§7.5; code review fix wave, FB) | the reviewers' "abandoned flag plus an outer catch" (module code would run in the worker after the module's loader is closed); a self-healing re-park of the `Retransformer` | module code must not run in a worker after its loader is closed, and a re-park contradicts §7.5's cut-short state; the uninstall had taken 3016 ms against a 1000 ms budget (L1-F5, L2-S4) |
| D223 | No new test seam for the cut-short uninstall: it is driven by a held lock and a slow foreign transformer, and D206's system property stays the only seam (§7.5, §7.6; FB) | none recorded in FB's report | the cut-short is reached with a held lock and a slow foreign transformer, so D206's property stays the only seam |
| D224 | The Byteman binding encoder reads containers by an allow-list of exact JDK collection and map classes (identical to the hooks module's `Capture` list, D237), scalars by exact type, and cuts a number above 6,000 bits before any text is made, judged by the unscaled bit length; the list stays inside the Byteman module (§7.3; FB) | one predicate shared in `agent-api` (the two fixes landed in parallel and would have collided; a later refactor, gap 64) | an iteration of a locking collection or a view waits on a monitor or runs the application's code on its thread (L2-F1, L2-S1); the bit-length test costs microseconds where a `precision()` test cost seconds (FB's probe) |
| D225 | An oversize number formats as its class name in `RuleHelper.format` (§7.3; FB) | printing it in full (the old behaviour: a 4M-bit `BigInteger` was printed whole) | the helper is the rule's own printer and the same bit-length rule as the encoder's (D224) applies to it (L2-F3); the class name costs nothing |
| D226 | The rule lint stays a tripwire, and says so: the deny list is unchanged and `RuleLintTest.theLintIsATripwireNotASandbox` pins what it does not promise; the engine's gates (the class bound, the type check, `confirm`) are the controls (§7.3; FB) | adding the missing names one by one; refusing every qualified `java.` reference | the deny list does not meet its own reasons and no list of names can; the lint is documented as a tripwire and the class bound, the type check and `confirm` are the controls (L2-F8) |
| D227 | `JarCheck` judges the symbolic link's real path (`toRealPath`, the real jar appended to the search path), and on ACL file systems the owner must be the current user, SYSTEM, Administrators or TrustedInstaller, each resolved as a principal and never matched by name; the rule stays in the Byteman module (§7.4 step 1; FB) | reusing `SecureFiles`' owner rule | the jar's owner rule differs from `SecureFiles`' (a jar may belong to the system accounts); L2-F6, L2-S2. D264 later moved the rule into `JarTrust` |
| D228 | Both Byteman POMs take `failIfNoTests` from the property `skillroy.failIfNoTests` (default true), with the profile `merged-test-run` that turns it off when `-Dtest` is given (§7.6, §2.3; FB) | a literal `<failIfNoTests>true</failIfNoTests>` (breaks every `-Dtest` command downstream, the reason D240 gives for the hooks module); the old default, which let an emptied execution pass | an execution that runs no test must fail the build, and a developer's `-Dtest` must still work (L3-F1) |
| D229 | `FreshJvm` in the Byteman tests skips only under `-Dtest` and fails otherwise (§7.6; FB) | skipping always (a green skip in the matrix, L3-S2) | a dedicated JVM that is not fresh must never be a green skip |
| D230 | `minimal` is an allow-list with hash by default: every name of `TelemetryAttributes` is in exactly one of `MINIMAL_DROPS`, `MINIMAL_REPLACED` (plus `skillroy.topic`), `MINIMAL_KEPT` (86 names) and `MINIMAL_HASHED` (18), and a text attribute in none is hashed; the correlation id and the pack version are hashed whole; an agent event's topic becomes itself (a `Topics` constant), `hook` or `pack`; `skillroy.source` is hashed in both its uses; session and turn names, `skillroy.session.id`, `mcp.session.id` and `skillroy.agent.session` keep their ids. It answers D166's question, supersedes D166's correlation-id clause and takes the veto form `B27 D166 veto id`; D142 (an exact list) holds, the four lists being exact (§10.3, §10.4; code review fix wave, FC) | only adding the named attributes to the exact hashed list (the next attribute slips through again); hashing the correlation id's parts (the join already exists through the call's parent spans, and it needs the id's format parsed); cutting the pack version at `+` (a version is the pack author's free text); dropping instead of hashing | a hash keeps grouping and is recomputed from `run.jsonl`; the rule must prevent the next leak, not close three (L1-F1, L2-F9, L1-S1) |
| D231 | The armed-stub check is step 0 of `HookInstaller`: whenever the matrix shows `hook.engine` `OK`, `hook.health` is read before steps 1 and 2 may skip; in `replay` an armed stub or a failed read is `ERROR` / `ENVIRONMENT`; in `live` and `approve` `armed` is written into the `skipped` record only when a stub is armed, and a failed read leaves the skip alone; without `hook.engine` `OK` nothing is read (§5.3; FC) | reading whenever `hook.engine` is present in any state (a stub cannot be armed through such an engine, and the fake and real `EMBEDDED` targets answer `CAPABILITY_UNAVAILABLE` there); treating a failed read in a live skip as an error (`--hooks off` under `live` would fail for a read it never needed) | a replay must never run past an armed stub whatever ends the install (L1-S2); no existing record changes shape |
| D232 | The `hooks` fact root pages `hook.events`: at most 10 pages of 1000 (all the default `eventRing` holds), then `truncated: true`, `count` a lower bound, and `last` from one tail read over the bus's last 1000 events (null when the hook did not fire among them) (§6.3; FC) | reading the ring's size from `agent.info.options` and paging `eventRing / 1000` times (up to 1000 calls per attempt); `last` from `hook.health`'s cumulative `last` (two reads with no sequence number to order them); a `newest` parameter on `hook.events` (an agent and protocol change for a controller-only finding) | the oldest-first page's last entry was taken as the newest and `count` stopped at 1000 (L1-S3); an expectation on `last` must not hold on an older payload |
| D233 | The `sign.on.checked` tests use a variant of the pack's catalogue that captures `[0, 1]`; the committed `lending.yaml` is unchanged; `SignOnCheckedHeadlessIT` runs the pack's real entry through the real engine against the desk's real `AccessControl` (§13.1 item 6; FC) | changing `lending.yaml` to `args: [0, 1]` (moves the catalogue hash and the §6.3 table for a test's sake); a hooks-module composite with a hand-written spec (duplicates `CaptureTest`) | order 67 could not see `secretArgs` with a catalogue that captured index 0 only (L3-F3); no plan, golden or hash moves |
| D234 | The typed-keys display flake is fixed in the test, not in the application: the test waits for the entry field's focus-gained event (30 s, re-requesting the focus once a second as A39 does) before its Enter (`phase1-contracts.md` sample UI bullet; FD) | binding Enter in `EntryField` to submit the field itself (hides standard `JTextField` behaviour the sample exists to present and a keyboard driver must cope with); submitting with `postActionEvent()` (the test exists to drive the keyboard path); requesting the focus once and skipping the Enter check if it does not come | the cause was a race in the test: `JTextField`'s Enter acts on the text field that most recently had the focus, and the freshly shown frame had not yet given it to the entry field; 5 failures in 240 runs before, 0 in 380 after |
| D235 | The mechanism is pinned by a deterministic display test (`enterDoesNotSubmitAnEntryFieldThatNeverHadTheFocus`) and recorded in the Phase 1 sample UI bullet; no behaviour changes, so no other contract passage (FD) | none recorded in FD's report | the sample's own display test raced a documented JDK behaviour; the record keeps it from being rediscovered (and `swing.type` `DISPATCH` with `enter` has the same property, gap 60) |
| D236 | A hook refused at step 6 of an install is taken out class by class before the call answers: nothing for a class whose batch threw (the JVM kept the old bytes), a retransformation without it for a class that keeps other hooks, a §3.5 restore with verification for a class left with no site, a drop for an entry the call created that never took it; a class that may keep its advice keeps its ledger entry and the hook is `ERROR("residue after a refused install")`, and the id of a hook in either residue state is refused by later installs until a remove restores the class (§3.3, §3.5; code review fix wave, FA) | treating the refusal as a `hook.remove` of the hook (needs a retransformation that a persistent foreign failure turns into a false `FAILED` and a slot held for the JVM's life, and publishes `hooks.removed` for a hook never installed); keeping the entries and the advice (leaves live advice); putting the loader into the site key (changes D171) | `retransformClasses`' all-or-nothing contract makes "did this class take the advice" exact, so only those classes pay a retransformation; a site key has no loader (D171), so a new site of a residue's id on any copy would answer the advice left behind (L2-S3, the review's high finding). D271 applies the rule to the remove path |
| D237 | `Capture.iterable` is an allow-list of exact JDK classes whose iteration takes no lock and runs no application code, plus every `ImmutableCollections$*`; every view or wrapper, the locking collections, every blocking queue (`DelayQueue` included), `TreeSet` and `ConcurrentSkipListSet` are handles; the list is kept in the hooks module, identical to the Byteman encoder's (D224) (§4.3; FA) | a name deny-list plus `instanceof BlockingQueue` (L2-F2's minimum); the sceptic's list with the two sets; one predicate in `agent-api` shared with the Byteman encoder | a `TreeSet` view from `subSet` or `headSet` is the same class over a bounded sub-map whose iteration runs the application's `compareTo`, and its backing map cannot be read without reflection into `java.util` (L2-F2, L2-S1) |
| D238 | `getMessage()` of an application exception is accepted as the exception's reason, redacted and cut like any (`Capture.message`'s Javadoc says so) (§4.3; FA) | a per-class check that the message method is not overridden; capturing the messages of JDK exceptions only | the check would resolve methods through the application's loader on the application's thread, and the application's messages are the useful ones (L2-F11) |
| D239 | `<init>` is the per-hook state `UNAVAILABLE("constructors are not hookable")` and `<clinit>` stays a violation of the call with its own message ("a static initialiser is not hookable") (§3.3, §4.1; FA) | keeping `<init>` a violation and recording it (a compile error at the line serves an author better; the sceptic's preference) | the contract's item 1 names the state; the catalogue's fallback then applies and one constructor entry, even a disabled one, no longer fails every compile of the pack (L1-F3) |
| D240 | `failIfNoTests` of the hooks module comes from the property `skillroy.failIfNoTests` (default true) with a profile `single-test`, activated by the `test` property, that sets false; `Harness.assumeFreshJvm` skips only under `-Dtest` and fails otherwise (§2.3, §13.1; FA) | a literal `<failIfNoTests>true` (breaks every `-Dtest` command downstream); the Byteman module's skip-always guard (a green skip in the matrix, L3-S2) | an emptied execution fails the build by itself, and a dedicated JVM that is not fresh is never a green skip (L3-F1, L3-F2) |
| D241 | The corner of `Spy` reference bytes that already name `Spy` before a restore (L2-F10) is accepted under D172 and recorded; the open precise discriminator is a `Code`-attribute scan for instructions that reference a `Spy` member (§3.5; FA) | "differ and both name `Spy` means `FAILED`" (the reviewer's: brings back D172's false `FAILED`); the scan (about 150 lines of class-file decoding in `agent-api`, shared with Byteman's verification) | unreachable by any known engine path; the scan is left open (gap 66) |
| D242 | Redaction is applied where a failure's text leaves the engine: `wire(text)` is `ctx.redactText` plus a 500-character cut, at every exit of an install status, `hook.remove`'s `restored[].reason`, `hook.health`'s `ERROR` reason and the `hook.<id>` capability reason (§3.6; FA) | a `Redactor` seam inside `Transformers` (the reviewer's) | one place covers the three producing sites and any later one (L1-F2) |
| D243 | `ChildJvm.pid` takes the leading digits of the line (§13.1; FA) | the class-loading log in a separate file (`-Xlog:class+load:file=` on 9+, `-XX:+LogVMOutput -XX:-DisplayVMOutput` on 8: two mechanisms) | on a Java 8 test JVM an auto-flushing `PrintStream` writes `pid=3403` and its newline as two writes and the JVM's whole `[Loaded ...]` line lands between them; the JVM writes each of its lines in one call, so the digits are never split; one rule for both Java versions (the matrix's `OptionalModulesIT` failure) |
| D244 | On a text route a capture's bytes are elided whatever `images` says; under `images: true` they leave the controller only as an image (the one rule, `scenario.Images`, applied in `RunLog.write`, `StepJournal.append`, the executor's `failure/` files, `PromptMask.text` / `jsonLike` and the MCP server's `Masking`; every answer through `ToolCalls.reply`, at any depth) (P5 §9.3, §9.4, §11.2; P6 §12.9 under D159; security pass 2, SC) | eliding only when served (misses the sinks that write the value: the cleanup, sync and `trace.mark` records, the journal); eliding in `CallRunner`'s two `call` records (the same miss; the L6 sceptic's comparison) | one place covers every record type and the observer's copy, and no reader needs the bytes from `run.jsonl` (P2-L6-F2, F3, S1) |
| D245 | An image is defined by key and signature: a map's `base64` entry, a base64 run starting with the PNG signature, a JSON `"base64"` member, each becoming `<png: N bytes>` with `format`, `width` and `height` kept (P5 §9.4; SC) | none recorded in SC's report | one rule that finds an image in a map, in a flattened text and in a serialised JSON line, whatever record holds it |
| D246 | A capture is elided at the write and at the two mask boundaries (`RunLog.write`, `PromptMask`, `Masking`), not only where it is first produced (P5 §9.4; SC) | none recorded in SC's report | the bytes can leave through the readers (the mask boundaries) as well as through the writer |
| D247 | No `requestedModel` substitution for an unexpected model id; instead every string of a provider's message is scrubbed of the known secrets and bounded: ids and the model at 200 characters, other texts at 100 (P5 §4.1; SC) | replacing a model id that does not match `InferenceSettings.MODEL_ID` by `requestedModel` with a note (`MODEL_ID` is private and admits characters a key without an underscore also matches) | the scrub is what keeps the key out; a substitution is hygiene, not the control (P2-L5-F3) |
| D248 | `skillroy author` always writes `--hooks <mode>` into the planner's server configuration (default `auto`) and takes the switch for its own bind and run (P6 §6.1; P1 §8.6; SC) | none recorded in SC's report | the person's switch is then expressible for the one command that starts a model-driven server and is visible in the configuration (P2-L6-F4) |
| D249 | A client's `skillroy_scenario_run` takes the server's `--hooks` switch (`.withHooks(session().options().hooks())`), not always `OFF`; the residual is written into P5 §9.3: under `auto` or `on` a client's run re-applies the switch, so to change a target's hooks while such a server runs the server is restarted or started with `off` (P5 §9.3; P6 §6.1; SC) | a client's run that always passes `OFF` and relies on the matrix from the server's start (the run's `hooks` record would misreport `skipped: off`) | the person's switch is honoured exactly; the stricter variant was the owner's choice, not a fix (P2-L6-F1), and the owner confirmed D249 as built (B27, 2026-10-10) |
| D250 | The planner's prompt, first and repair, is its stdin: written to `prompt-<n>.txt` in the private work directory and given with `ProcessBuilder.redirectInput(file)`, never an argument on any platform (P5 §10.2 item 4; P6 §9.2; security pass 2, SD) | a pipe the planner's thread writes (can block on a CLI that does not read, and needs a thread against `Planner`'s "starts no thread"); keeping the argument and escaping it for `cmd.exe` (`%VAR%` cannot be made inert reliably inside or outside quotes) | nothing of the brief or the model on any command line, no thread, the deadline holds; `claude -p` 2.1.286 takes its prompt from a redirected stdin (verified offline against a local listener, a dummy key and `--bare`) (P2-L7-F3, the pass's high finding) |
| D251 | `-Djdk.lang.Process.allowAmbiguousCommands=false` is not put on the launchers (P6 §9.2, §9.5; SD) | pinning it as the reviewer's belt | with no model text on the command line, a batch file refused and the schema pre-quoted it guards nothing more here, it changes how every process the CLI starts on Windows is quoted (a `java` without `.exe` is treated as a shell file, a pre-quoted argument with inner quotes throws), and it is unverified without a host; a §9.5 host question |
| D252 | A batch file is never the planner, on every platform, by name (`.cmd`, `.bat`, any case); `findOnPath` keeps D198's order within a directory but passes over a batch file (and the extensionless `claude` beside it, npm's shim) for another candidate of the same or a later directory, and returns the first batch file only when nothing else is found, so that the refusal names it with the remedy (the native `claude.exe`, or `--claude-command`); it refines D198: npm's `claude.cmd` no longer wins (P6 §9.2; SD) | refusing only on Windows; dropping `.cmd` / `.bat` from the candidates ("claude not found on PATH" instead of naming npm's file); starting the `.cmd` through an explicit `cmd.exe /d /s /c` with every argument checked (the schema holds quotes, so no form survives) | a native `claude.exe` later on the `PATH` is used when there is one, and the refusal names the file when there is none |
| D253 | The session id `--resume` takes matches `[A-Za-z0-9_-]{1,128}`; a missing or other id ends the run (exit 1) before a repair starts (P5 §10.2; SD) | hex only (the reviewer's); starting a fresh session with only the repair prompt (the old behaviour for a missing id) | the CLI's ids are UUIDs, the class admits nothing a shell or `cmd.exe` reads specially, and the fixtures' `sess-41` stays valid |
| D254 | On Windows the `--json-schema` argument is handed over pre-quoted by the C runtime's rules (the JDK's default legacy handling passes an argument that starts and ends with a quote on verbatim), and the command line is bounded to 32767 characters with room for a `--resume` (exit 64 up front) (P6 §9.2; SD) | the belt of D251; a schema file (the CLI's help shows `--json-schema <schema>` as JSON text only) | unquoted, the schema would split into flags of the CLI (`PlannerCommandLineTest`'s control case); the schema is the pack's and the target's catalogue text, never the model's; unverified on a host |
| D255 | The per-volume decision lives in `skillroy-protocol` as `ai.skillroy.testjava.protocol.Volumes` (Java 8, JDK only), shared by both `SecureFiles`, `CredentialFiles` and `JarCheck` (P1 §2.5; P6 §9.3; SD) | four copies; a public member of the controller's `SecureFiles` (its public set is fixed at five by §9.3 and asserted by `SecureFilesTest`) | one rule on both sides of the wire; the bootstrap, which cannot use the protocol, got a private copy in D279 |
| D256 | The volume types without permissions are `msdos`, `vfat`, `fat`, `fat12`, `fat16`, `fat32`, `exfat` and `umsdos`, case-insensitive (Windows reports `FAT32`, `exFAT`); `fuseblk` is left to its views and the read-back; where the `FileStore` cannot be read, the provider's views decide as before (P1 §2.5; SD) | listing `fuseblk` (a FUSE exFAT or NTFS) | `fuseblk` may be a file system that keeps permissions; the read-back catches the rest |
| D257 | What the POSIX branch creates is read back exactly (`rwx------`, `rw-------`, not following links) on both sides, and deleted and refused otherwise; the agent's file creation no longer relies on `chmod` (which succeeds and changes nothing on `vfat`); an existing directory keeps the old rule; the agent reads back only a directory it created itself; messages keep "neither POSIX permissions nor ACLs" and add "(its volume is <type>, which keeps no permissions)" (P1 §2.5; P6 §9.3; SD) | relying on the provider's views alone (the finding) | a mask the file system ignores is caught by what it reads back, whatever the volume's type |
| D258 | A credentials file on a FAT or exFAT volume is refused before its mode is looked at; D138's "neither: accepted with a warning" stays for any other volume without either view (P6 §9.3; SD) | mapping such a volume to the warning branch (D138's letter), which would loosen Linux `vfat`'s `644` refusal | a credentials file that cannot be made private must not be read |
| D259 | The agent compares owners by the provider's view (`ownerKind`) as before, so the default-directory rule stays identical to the controller's `Discovery`; only what may be written is decided per volume (P1 §2.5; SD) | none recorded in SD's report | the agent's default-directory rule stays identical to the controller's `Discovery`; only what may be written is per volume |
| D260 | The SQL Server items are fixed structurally in `ReadOnlyJdbc`, for both channels: every statement runs in a transaction the read path rolls back itself (auto-commit off after `setReadOnly(true)`; then rollback, auto-commit on, read-only restored, in that order); a failed rollback is warned and auto-commit is still switched back on; `SQLFeatureNotSupportedException` on the switch leaves the connection as it was, any other failure aborts the call; the guard gains only `IMPLICIT_TRANSACTIONS` and `ANSI_DEFAULTS` (T-SQL only, no plausible identifier) (P1 §6; P2 §2.2, §7.2; SD) | the controller-only `setAutoCommit(false)` in `JdbcDataChannel.connect` (does not cover the agent's pooled connection, S76); the guard words `SERIALIZABLE`, `REPEATABLE`, `REPEATABLEREAD`, `DEADLOCK_PRIORITY`, `WHILE` (with the read path's own rollback a hint holds its locks for the statement only, and `REPEATABLE` would refuse PostgreSQL's `TABLESAMPLE ... REPEATABLE (seed)`) | the JDBC rule commits an open transaction on a mode change, so a leftover transaction must be rolled back first; leaving a pooled connection in manual commit is the worse outcome (P2-L3-F1, P2-L3-S1) |
| D261 | The real-volume tests (`hdiutil create` / `attach -nobrowse` into the test's temporary directory, detached with `-force` in `@AfterAll`, about 3 s per class) run in the normal build on macOS and are skipped where `hdiutil` is absent (P6 §13.1; SD) | opting in by a property | regression protection where the defect was observed, at a small cost |
| D262 | The body hash travels in the request header `X-Skillroy-Body-Sha256`, required on every request with a body; the request MAC covers it and is verified on the head alone, a body is read only for a request that holds the key, and its SHA-256 must equal the declared hash; the protocol version stays `0.2`; the unauthenticated body budget stays as defence in depth (P1 §3, §5, §7; security pass 2, SA) | the header optional with the old buffered path as a fall-back (a process without the key could still fill the budget); the sceptic's mitigations (client-side ping-and-resend, the first chunk outside the budget), which leave unauthenticated buffering in place; bumping the version to `0.3` (nothing was released on `0.2`, `/ping` compares majors only) | the contract's own remedy for the gap the H1 record called a "delay" (P1 §5) (P2-L1-F1); breaking for an older controller, nothing released |
| D263 | The agent checks the options file it reads: a regular file of the current user (by uid, else name; root accepted), with no group or other write bit, and no group or other bit at all when it holds a `token`, not a link, unchanged while read; neither view refused; the controller writes it through one channel (`CREATE_NEW`, `NOFOLLOW_LINKS`, the ACL read back before any byte) and `ensure` refuses a directory with an ancestor others may rename entries of (without the sticky bit; ACL: replace rights), the ancestors' owners not judged (P1 §2.4, §2.5, §7; P6 §9.3; SA) | refusing every group or other bit on the options file (breaks tokenless operator files); judging ancestors on the reading side too (the agent does not, so the two sides would disagree about where discovery files may live); judging the ancestors' owners (false refusals where root owns most ancestors) | closes code execution by another user behind an operator's choice of a shared parent directory (P2-L1-F3) |
| D264 | One public predicate `JarTrust` in `...agent.api.instrument` for every jar the agent loads from a path the operator names: a named `hooks.spyJar`, each `modules` path and, by delegation, `byteman.jar`; the home's own jars stay trusted; ancestors are not judged (P6 §3.2 step 3, §3.6, §3.7, §7.4; P1 §2.4; SA) | a copy in each of the three modules (three rules to keep equal, the finding's asymmetry again); applying it to the home's spy jar too (a home installed by another non-root user would lose its hooks, and the threat model trusts the home); a package-private class in the core (the hooks and Byteman modules cannot see core internals) | the two optional jars the agent loads into the host were judged by different rules (P2-L1-F5). It refines D227 |
| D265 | A supplied token has at least 16 characters of the token alphabet, on both sides (`SecureFiles.isValidToken` `{16,1024}`, `AgentRuntime.resolveOptions`, the CLI's `--token-file` reader); P1 says the floor bounds the length, not the randomness (P1 §2.4, §8; SA) | 32 hex characters (forbids tokens a password manager produces: 16 characters of a 66-character alphabet carry about 96 bits); a warning only (an operator does not read the agent log at start) | a short token falls to an online guesser with no squatting at all, and a refusal names the rule (P2-L1-F4) |
| D266 | A browser's DevTools events have a receive budget: console events are spent from a token bucket (burst 2 x `MAX_CHANNEL_CHARS`, 1 Mi characters a second); the event that empties it stops console capture once (`Runtime.disable` on JCEF's channel, unsubscribing on JxBrowser), a `web.console` warning is published, and capture resumes at the first main-frame navigation 10 s or more after the stop (P4 §4.3, §10.1 item 14; threat model §6; security pass 2, SB) | a fixed window counter (lets a page sit just under the window forever); "three oversized events in ten seconds" (misses a flood just under the channel bound); never resuming (loses console capture for the run after one burst); resuming on a timer alone; enabling `Runtime` only while a run waits on `web.console` (a redesign of the subscriptions, loses events between waits, and sub-frame contexts need `Runtime`) | a page chooses the size and the rate of its console output, and each event costs the host JVM about four copies (P2-L4-S1). Cost accepted and documented: `Runtime.disable` acts on the browser's one DevTools session, which an application client sharing JCEF's session loses meanwhile |
| D267 | The hand-off from the technology's thread to the adapter's executor is bounded to 2 x `MAX_CHANNEL_CHARS` of queued characters per browser, each event counting at least 256; an event past it is dropped, never waited for; the S43 check runs at the hand-off (P4 §4.3; SB) | blocking the technology's thread (forbidden: never block the host); a count bound only (does not bound characters) | the queue behind the executor was unbounded (P2-L4-S1) |
| D268 | The library guard answers a primitive string (texts prefixed `#skillroy-guard:`), not a marker object, bounded at `MAX_JSON_CHARS` on the `stringify` text so the first copy is at most `MAX_CHANNEL_CHARS`; `DRAIN_CHARS` drops from 900000 to 500000; applied to every library call, not `drain` only; the agent path's message for a thrown non-error value is `String(value)` (P4 §4.6, §5.2; SB) | the sceptic's marker object (a JxBrowser frame's string-only channel would pass it through the page's replaceable `L.stringify`); a bound of 2 x `MAX_JSON_CHARS` (the worst-case channel text is twice the bound); the guard on `drain` only (leaves S43's per-call first copy) | the page cannot alter a primitive string's `length`, `typeof` and `+`, and a string takes the adapter wrapper's `"s"` branch; a library value with many quotes is refused from about half a MiB of JSON (P2-L4-F2, F3) |
| D269 | The CDP attach dials the browser WebSocket the probe read, re-pointed to the endpoint's own authority whatever the endpoint named, refused with `PROTOCOL` when there is none; the handshake-redirect residual (Playwright follows a redirect of the WebSocket handshake) is disclosed and accepted by the owner (B28, 2026-10-10), not closed; `connect(endpoint, options, null)` keeps the driver's `http` form (P4 §5.1; threat model §6, §8; SB) | refusing a foreign URL (a browser behind a host-rewriting front would not attach); closing the residual by a deferred attach (D65 extended: a change to path selection and the `web.path` record) or a controller-side WebSocket relay (a WebSocket server the JDK does not have), both out of proportion for a low finding (§12.5) | S45 keeps the run's scripts from ever reaching a browser the application did not configure; the residual needs a squat on the debug port while the agent reports no target id (P2-L4-F1). It is on B28's list, accepted |
| D270 | Only an oversized drain ends a tap (the guard's `oversized`, or a receiving side's oversized refusal); a "not library text" answer does not (P4 §4.3; SB) | none recorded in SB's report | a "not library text" answer is small, so repeating it costs what a genuine drain costs |
| D271 | A removed hook whose class's partial retransformation threw becomes a residue (status `UNAVAILABLE`, `ERROR` in health, the id refused by D236): both for a batch that threw (`kept`: the advice is certainly there) and for a class the hook transformer failed on or did not advise (the advice is most likely gone, but the bytes were not verified); the second case also sets the entry's error so the hooks that stay report `ERROR`; cleared by the next `hook.remove {}`; no `HOOK_VERIFY_FAILED` on this path (no class was restored) (P6 §3.5; security pass 2, SE) | `kept` only (leaves an unverified class out of D236); throwing `HOOK_VERIFY_FAILED` (reports a restore that did not happen and hides the other statuses) | a stub armed for another copy would also stub the class the operator did not target (P2-L2-S1, P2-L2-F4) |
| D272 | The engine's own transform events are counted per thread: the `Recorder` increments an entry's `own` only for an event on the thread that called `retransformClasses` for the engine, and the per-batch `own++` is gone (P6 §3.5; SE) | decrementing for the classes after the failing one (the JVM does not say which class failed) | `foreignEvents()` is exact whatever a batch did (P2-L2-F4) |
| D273 | `hook.install { replace: true }` answers `replaced: {restored, verified}`, the new version stays installed, and `HookInstaller` maps `verified: FAILED` to `ERROR` / `ENVIRONMENT`; the SPI `HookEngine.install` is unchanged (P6 §4.2, §5.3 step 5; SE) | refusing the whole install with `HOOK_VERIFY_FAILED` (an "install nothing" rule, and a pack update blocked by another agent's residue) | the new catalogue stays installable and the residue is still reported (P2-L2-F3) |
| D274 | A detach that cannot take the engine's lock reports like the locked path: it logs every ledger class at WARN with the remedy and sets D177's count; the held call reports again at its end (clearing an over-reported count when it left nothing), `installLocked` refuses step 6 once the detach began (`CAPABILITY_UNAVAILABLE("the hooks module is uninstalling")`) and declares nothing after (P6 §3.5; SE) | the minimum only (the WARN and the count): a detached module would still gain classes and capabilities | the promised report of a class left behind (P2-L2-F6) |
| D275 | `redactText` applies to `String` values only (a map key's text included), after the cut and before the policies, in both encoders; not to `BigDecimal` or `java.time` text; a cut that splits a user-info password before its `@` may leave its first characters (P6 §4.3, §7.3; SE) | a window four times larger before the cut, as for thrown messages (it would read beyond the capture's input budget on the application's thread) | a `key=value` or URL user-info secret inside a captured argument reached `hook.events` and a run's output in clear (P2-L2-F7) |
| D276 | A Byteman rule's `CLASS` or `INTERFACE` name is refused by name, before anything loads, when the JDK's loaders (the platform loader reached reflectively on 9+, the extension loader on 8) or the agent's own module loaders find its class file as a resource ("class is the JDK's" / "the agent's own"); a removal that throws is `HOOK_VERIFY_FAILED` with `removed`, and the record is dropped once Byteman no longer lists the rule (P6 §7.3, §7.5; SE) | refusing every rule that matches no loaded class (forbids a rule on an application class that loads later, and misses the `INTERFACE` path through a JDK implementor); a lint prefix list (would refuse `org.w3c.tidy`-style application libraries); `boot:` for Byteman (B8, D132) | a JDK class can only extend or implement JDK types, so the name gate closes both paths and loads nothing (P2-L2-F1) |
| D277 | A Byteman load whose handler cannot arm is undone: arm first, then undo everything on both paths (the behind and ahead recorders, the trace streams, `retransformer = null`, the `Retransformer` parked back), with no "refused" state; the probe asks `RuleHelper.held()` through the system loader rather than loading `FiredHandler` (P6 §7.4, §7.5; SE) | a separate refused state for the fresh path | `premain` never runs twice, so the next submit retries from the reuse path (P2-L2-F2) |
| D278 | The Byteman recorders are bounded by the hooks module's own options (`hooks.maxClasses` records per recorder, `hooks.maxClassBytes` per copy); an oversize record keeps no bytes for its life; `rule.unload` drops the records of the names no other rule watches, so a later rule on the name primes its reference again (P6 §7.4; SE) | none recorded in SE's report | an oversize record's reference would be wrong, so it keeps no bytes; the one extra pause of a re-submit after the unwatch is the cost (P2-L2-F5) |
| D279 | The bootstrap's `OptionsFileCheck` decides POSIX or ACL per volume with a private nested copy of D256's type list (`OptionsFileCheck.Volume`) plus a test-scope dependency on `skillroy-protocol` whose test holds the two lists equal; an options file on such a volume is refused whether or not it holds a token (P1 §2.4, §2.5; SE) | moving `Volumes` into the bootstrap (the protocol and every user of it would depend on the bootstrap); a test that parses `Volumes.java`'s source (fragile) | the bootstrap is JDK-only and cannot use the protocol; anybody with the volume may have written the file (the gap found at the SA x SD merge) |
| D280 | The settings-file rule (`SettingsFiles` in `skillroy-data`, for `telemetry.yaml` and `inference.yaml`) is the file's, not the directory's: a regular file, or a link the current user owns whose target is judged; owned by the current user or root; no other-write bit, and no group-write bit unless the group is the owner's user-private group; ACL: no write right for anybody else; FAT, exFAT and neither-view volumes refused; the same file key before and after the read; a refusal is a settings error (exit 64) naming the file and the rule (P6 §10.3; P5 §4.4; threat model §3, §6; security pass 2, SF) | refusing every group bit and every link (the sceptic's letter: on Ubuntu or Fedora with `umask 002` every file an operator writes, a committed `inference.yaml` in a fresh checkout included, is `rw-rw-r--`, which P5 §4.4's "may be committed" needs); a directory rule (also refuses group-writable checkouts and adds nothing a planted or replaced file does not already fail) | a planted `telemetry.yaml` turned export on and named the environment variables sent as headers (P2-L8-F1); macOS's `staff` and any shared group are still refused |
| D281 | A telemetry header needs an `https://` endpoint (D141 amended): with `http://`, the default `http://127.0.0.1:4318` included, it is a settings error, checked again by the provider at `create`; the tests that see a header arrive use a loopback TLS stub (P6 §10.3, §10.1; SF) | refusing a header only with the default endpoint and accepting the named loopback case with a release note (consistent with S84); accepting the whole finding | the default endpoint is `http://`, so an operator who forgets the endpoint sends a token in clear to a port anyone can hold; a loopback collector that needs a token can terminate TLS; the tests could move to TLS without a product setting. The owner accepted S84 as it stands beside it (B28, 2026-10-10): the two rules disagree by choice (P2-L8-F2) |
| D282 | The output-directory rule is: owner the current user or root, and others may not write without the sticky bit, judged without following links (a link is followed when the current user or root owns it); group write allowed; only the directory a file goes into is judged, never its ancestors; root is accepted so that `--junit /tmp/report.xml` or `--out /tmp` stays possible, where every file is still created exclusively (P2 §11.3; P6 §10.5; SF) | `SecureFiles.untrustedDirectoryReason` (refuses group write, the user-private-group umask default); exclusive creation alone (leaves a directory another user owns, whose renames could swap a subdirectory under the run) | closes the writers that followed a planted link and the pre-created directory the `umask 077` advice did not cover (P2-L8-F3, P2-L8-S1) |
| D283 | A refused output directory, or a `run.jsonl` that cannot be created, ends the run `ERROR` / `ENVIRONMENT` before the agent is contacted (P2 §11.3; SF) | running without a log (the old best effort) | the operator must learn that something was planted; until then a log that could not be opened was silently skipped |
| D284 | `OutputFiles` lives in `skillroy-controller` (the lowest module every writer depends on; it reuses `SecureFiles`' "me", volume and owner logic) and `SettingsFiles` in `skillroy-data` beside `CredentialFiles` (it reuses its `Access`, "me" and volume kind) (P2 §11.3; P6 §10.3; SF) | one class in `skillroy-scenario` (not reachable from `skillroy-controller`'s own writers, and the settings reader needs `CredentialFiles`' package) | the dependency graph |
| D285 | Telemetry messages carry no path: the sources give the observer a role-worded environment message and class-only internal-error messages, and the observer replaces the whole absolute path of `skillroy.message` and `skillroy.step.message` by `<path>` (a token is a path when it starts with `~/`, a drive letter, or a separator followed by at least two segments, not inside a word) (P6 §10.1; SF) | the reviewer's observer rule alone, cutting to the last segment (it would keep `skillroy-<user>`) | the whole path, since the discovery directory's last segment holds the user name (P2-L8-F4) |
| D286 | The CLI's tests carry their own copy of `TlsStub` (P6 §10.6; SF) | depending on the scenario module's test-jar | its service files (the toy pack) would join the CLI's test class path and break every single-pack CLI test (17 failures seen) |

### 16.1 Where the §17 answers went

Every answer of §17 is written into the section its row names; the owner confirmed them on 2026-10-10 without a veto,
so the sections stand as written.

---

## 17. Questions for the owner: answered provisionally 2026-10-09, confirmed 2026-10-10 (B26)

Answered by the orchestrator on 2026-10-09 in the manner of P2 §17, P3 §14, P4 §15 and P5 §17, so that the build can
start; written into the sections named; **confirmed by the owner on 2026-10-10**, every row (`B26 ok`, no veto). The
column "A veto means" records the form a veto would have taken. Items the owner had to
decide for reasons outside this repository (the private layers, the Windows host, the backends, the disclosure
channel) are marked **(owner's knowledge)**: nine of the twenty-two.

| # | Question | Answer (provisional 2026-10-09, confirmed 2026-10-10) | Written into | A veto means |
|---|---|---|---|---|
| Q1 | **The carve-out of the hard rule, in words.** `CLAUDE.md` says "No third-party classes inside the target JVM"; `NOTICE` says the agent "contains no third-party code"; the design admits shaded Byte Buddy in the optional hooks module (B4). Proposed wording for `CLAUDE.md`'s rule: "No third-party classes inside the target JVM, with one carve-out (B4, Phase 6): Byte Buddy and the ASM it bundles, shaded and relocated under the project's prefix inside the optional hooks module `optional/skillroy-agent-hooks.jar`, which loads only when the agent option `optionalModules` names it; only `Spy`, project code, ever reaches the bootstrap search path. Nothing else, in no module." For `NOTICE`: "The agent that runs inside a target JVM contains no third-party code, except that its optional hooks module carries a relocated copy of Byte Buddy (Apache-2.0) and the ASM it bundles (BSD-3-Clause), whose notices travel inside that jar under META-INF/third-party/; the module is loaded only when named." `CONTRIBUTING.md`, `DistributionContentIT`'s comment and `../licence-audit.md` §1 get the same sentence. May the hooks jar ship in the default home under these words? | Yes (D114, D115) | §2.1, §2.5; `CLAUDE.md`, `NOTICE`, `CONTRIBUTING.md`, the audit (edited by the orchestrator with the owner's wording) | `B26 Q1 veto`: the hooks jar is a separate download (`skillroy-agent-hooks.zip`), the home stays third-party-free, `NOTICE` unchanged, the `optional/` mechanism stays for Byteman; `B26 Q1 veto wording`, with the text to use instead |
| Q2 | **Byteman in Phase 6.** Build the thin in-process module behind spike BS14 (CI's Byteman jar from the Maven cache as a test-scoped artifact, the sys jar compiled against it at `provided` scope, never in a distribution), or specify it only? **(owner's knowledge: do the private layers want AI-authored tracing, fault injection or synchronisation rules on their targets soon?)** | Build it behind BS14 (D132, D134); if the spike fails, specified only | §7 | `B26 Q2 veto`: §7 stays specified, the two modules are not created, `rule.*` stays reserved |
| Q3 | **A second project jar on the system class path.** B4 says only the bootstrap jar goes on the target's class path, with Byteman as the one exception (B8). The curated helper and the manager class need a project jar (`skillroy-agent-byteman-sys.jar`, Apache-2.0, two classes, compiled against Byteman at `provided` scope) on the **system** class path for the JVM's life, because Byteman loads a helper through the trigger class's loader. Acceptable inside the B8 exception? | Yes (D132): without it `rule.fired` events cannot reach the agent's bus and Byteman's default `Helper` with its `killJVM` and blocking built-ins would be the only helper | §7.3, §7.4 | `B26 Q3 veto`: no project jar on the system class path; the module keeps `rule.check/list/unload`, `rule.submit` accepts rules whose `DO` is empty or `traceln`-free assignments only, no `rule.fired` events, and Byteman's default helper is denied by the lint entirely |
| Q4 | **How the private layers consume an overlay.** As a second jar beside the base pack's jar on one class path (`--pack base.jar --pack overlay.jar`, or two dependencies), through `DomainPackOverlay`, or do they already wrap the base pack in a class of their own and register that instead? **(owner's knowledge)** | The second jar through `DomainPackOverlay` (D144); a layer that wraps today moves its wrapper into `overlay(base)` | §11.1 | `B26 Q4 veto`: `DomainPacks` prefers a `DomainPack` with the base's id that implements a marker interface `Overlays` over the base, and the `ServiceLoader<DomainPackOverlay>` SPI is dropped |
| Q5 | **An existing private catalogue shape.** Does a hook catalogue already exist in a private layer whose shape (ids, descriptors, fallbacks, versioning per application release) the base format must carry unchanged? **(owner's knowledge)** | The §5.1 format is the first catalogue anyone writes (D121) | §5.1 | `B26 Q5 veto`, naming the fields the format must add or rename (`… <field>`), or `… versions` for several catalogues per pack selected by application version (today a non-goal) |
| Q6 | **Third-party hook targets.** The catalogue defaults `enabled` to false for a class outside the pack's `packages` and accepts `enabled: true` there with a warning; the sample demonstrates `enabled: false` only. Does any tenant layer need a hook on a third-party class in Phase 6 **(owner's knowledge)**, and is a warning the right strength? | No third-party target in Phase 6; the warning stands (D121, D124). (Phase 8, built 2026-10-19, EH; P8 §6.8) The warning stands; a qualified `packages` rule (`target.codeSource` inherited by every entry under it) is the mechanism that makes "our own classes only" sayable for a package a third-party jar shares | §5.1 | `B26 Q6 veto`: the reader refuses `enabled: true` on a class outside `packages` (an error, not a warning) |
| Q7 | **The Windows host.** Which JDK 8 and JDK 25 builds will it carry (vendor, 64-bit only?), and may tests run in an interactive logon session there (the Windows items need real windows)? **(owner's knowledge)** | Assumed: Temurin 25 and Temurin 8, 64-bit only, an interactive session of the logged-on user; `ci/run-on-windows.cmd` is written for that (D137) | §9.5, §13.3 | `B26 Q7 veto`, naming the JDKs or the session constraint (`… no interactive session`: the display items are then not verifiable and the criterion is split again) |
| Q8 | **`ATTACH` on Windows.** In scope for Phase 6 (then the host procedure's `ATTACH` leg is part of the exit), or only `JAVAAGENT` plus the documented exclusion? | Best effort only (D139): the TODO item reads as the latter | §9.4, §9.5 | `B26 Q8 veto`: `ATTACH` on Windows joins the exit criterion when the host exists; the 5 s margin and the session rule are then measured and decided there |
| Q9 | **The `.bat` shipped unverified.** Carry `bin/skillroy.bat` in the distribution from the next commit, marked unverified in `README.md`, or only after the host has run it? | Ship it (D137): a static test proves the text, the host proves the run | §9.1, §2.6 | `B26 Q9 veto`: the assembly leaves it out until the host run; `BatLauncherTest` and the source file stay |
| Q10 | **OTel backends and what may leave the host.** Which backends will receive the spans (an OpenTelemetry Collector on the CI host, a hosted vendor, both)? If one needs gRPC, the sender flips to OkHttp. May a tenant's run export the `standard` set (scenario ids, pack names, primitive names, masked one-line messages) to a backend run by someone else, or must `minimal` be the default? **(owner's knowledge)** | OTLP/HTTP protobuf through the JDK sender, loopback-http or https only; `standard` by default (D141, D142) | §10.3, §10.4 | `B26 Q10 veto grpc`: the OkHttp sender and `grpc` as a protocol setting; `B26 Q10 veto minimal`: `minimal` the default; `B26 Q10 veto loopback`: `https://` endpoints refused, loopback only |
| Q11 | **The rule "no third-party classes inside the target JVM" for a relocated OpenTelemetry copy.** B11 left the door open for agent-side spans; the contract closes it (nothing in Phase 6 needs it). Closed for good? | Closed (D140) | §10.1 | `B26 Q11 veto`: the door stays open for a later phase's agent-side exporter under the same shading rules as Byte Buddy; nothing changes in Phase 6 |
| Q12 | **Who signs the repeat review, and an outside reader.** The sign-off is the owner's (B28). Should a human reviewer from outside the project read the record before the first public release (every review so far was run by model agents with model sceptics)? **(owner's knowledge)** | The owner signs; an outside reading is recommended and recorded in the release checklist as "done" or "waived by the owner", not a gate (D147) | §12.6, §12.8 | `B26 Q12 veto`: an outside reading is a gate of the first release |
| Q13 | **`SECURITY.md`'s disclosure channel and supported versions.** An address, or the repository host's private vulnerability reporting; fixes for the latest version only? **(owner's knowledge)** | The repository host's private reporting plus the address in `NOTICE`; the latest minor only (D148) | §12.8 | `B26 Q13 veto`, naming the channel (`… address <a>`) or the policy (`… last two minors`) |
| Q14 | **The CDS line on Java 25 in hooks mode.** Every start in hooks mode on a Java 25 target with class data sharing on prints `OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended` on the host's stderr once and ends sharing of non-boot classes (BS3 RESULT §9 fact 8). Acceptable, documented in README and `SECURITY.md`'s limitations, or should the engine offer a system-class-path placement of `Spy` (`hooks.spyPlacement=system`: no line, but invisible to loaders whose parent chain skips the system loader)? | Acceptable and documented (D116) | §2.5, §3.2 | `B26 Q14 veto`: the option `hooks.spyPlacement = bootstrap \| system` (default `bootstrap`) and a second visibility probe for the system placement |
| Q15 | **Releasing with Windows unverified.** May the first public release ship with the Windows items marked "not verified" (S7/S15, the owner-only files, the `.bat`, the rungs) in `README.md`, `SECURITY.md` and the threat model, or does the release wait for the host? **(owner's knowledge)** | It may ship so marked (§14) | §12.6, §12.8, §14 | `B26 Q15 veto`: the first release waits for the Windows host and §13.3 joins its gate |
| Q16 | **Accepting known risks.** The repeat review may accept or defer a **low** finding only, in writing, with a release note (deferred only to a named phase; a documentation-only change counts as an acceptance); nothing medium or above. Right rule? | Yes (D147) | §12.5 | `B26 Q16 veto none`: nothing accepted or deferred at all (2026-10-02's precedent); `B26 Q16 veto any`: the owner may accept any finding in writing |
| Q17 | **Read-only mode and observation.** May read-only mode install `OBSERVE` and `CAPTURE` hooks (a bytecode change without a behaviour change), with only `STUB` disabled, as design §2.2 step 7 reads ("read-only mode disables mutating primitives and `STUB` hooks")? | Yes (D119): `hook.install` is not mutating | §4.2 | `B26 Q17 veto`: `hook.install` is mutating too, so read-only mode installs nothing and `hooks.mode AUTO` skips the install on a read-only agent |
| Q18 | **The `script` flag refused in read-only mode (D156).** Pass 1's S42: `web.waitFlag` runs a caller's expression, so B21 Q5's answer (D51, "the six reads are not mutating") rested on a wrong premise. The agent's dispatcher and the CDP path refuse a `script` primitive in read-only mode; `web.waitFlag` stays "not mutating" for the step protocol; D98's offer rule (`--allow-evaluate`) is unchanged. Confirm the amendment of D51? | Yes (D156) | §12.9; P1 §2.4, §3, §5; P4 §4.2, D51 | `B26 Q18 veto`: the flag marks and projects only (the superseded D149); read-only mode keeps serving `web.waitFlag`, and S42 is recorded as accepted (low) with a release note |
| Q19 | **One session budget over `skillroy_scenario_run` (D157).** Pass 1's S56: a client's runs spend tier (b) calls outside the session budget. D101 (B24) is widened: the server's one `SESSION` meter bounds `skillroy_session_ask`, `skillroy_resolve` and a run's tier (b) slots, a run being refused `BUDGET` before any model call. Confirm? | Yes (D157) | §12.9; P5 §9.3, §12.4 | `B26 Q19 veto`: a run stays on the scenario budget only, and S56 is recorded as accepted (medium), which §12.5 forbids: the veto therefore also means the release waits on another fix the owner names |
| Q20 | **A client origin that never confirms, and the verb gate at call time (D158).** Pass 1's S57, S59, S62: the MCP server's origin was `person("mcp")`, so a verb's own `confirm` was honoured, and D96's verb gate ran on three probes at start, not on a call's real arguments. `Origin.Kind.CLIENT` never confirms; the gate runs at call time on the compiled calls, for the MCP server and the session service; the journal says `client <label>`; `author` passes `--client planner`. Confirm the amendment of D96 and of P5 §12.4's "the MCP client is the person's side"? | Yes (D158) | §12.9; P5 §9.2, §9.3, §12.3, §12.4 | `B26 Q20 veto client`: the server keeps a `PERSON` origin (then a verb's `confirm` is honoured for every client, which the owner accepts in writing); `B26 Q20 veto gate`: the probe-only gate stays and S57 is accepted (medium), which §12.5 forbids as in Q19 |
| Q21 | **Captures over MCP only under `images: true` (D159).** Pass 1's S58: captures reached the MCP client as unmasked, uncut blobs. A capture's bytes now leave the controller to a client only under the inference settings' `images: true` (D84, widened to MCP; D76's "captures as resource links" amended); otherwise a text resource names the written file; there is no separate flag. Confirm? | Yes (D159) | §12.9; P5 §4.1, §9.3, §9.4 | `B26 Q21 veto flag`: a separate `skillroy mcp --serve-captures` (the superseded D150); `B26 Q21 veto today`: served as today, S58 accepted (medium), which §12.5 forbids as in Q19 |
| Q22 | **Five low parts of pass 1 deferred or fixed by documentation only.** `dispositions.md` defers, each with a release note: S36 part (b) a deny-list of side-effect function names in `StatementGuard` (the read-only role is the boundary); S37 option (i) owner-only run output directories by default (it would break the CI example; documented: `umask 077` on a shared host); the pinning of the CI example's image and actions in S40 (a pinned example rots; the comment tells a copy to pin); S48 a minimal environment for the Playwright driver (it would break proxies and the `PLAYWRIGHT_*` variables; documented); the `CLAUDE_CONFIG_DIR` relocation of the planner's CLI configuration in S60 (unverified without a CLI run). Under §12.5 a documentation-only change and a deferral are acceptances that need the owner's word, each with the phase it goes to (7, or the phase that builds the seam: the data channel for S36 and S37, the CDP path for S48, the planner for S60, none for S40). Accept the five? | Yes: accepted as lows with their release notes, named in B26 and repeated in B28 (D147) | §12.2, §12.5, §12.8 | `B26 Q22 veto`, naming the finding to fix now (`… S37`, `… S48`, …): FX builds that part in the same wave |
