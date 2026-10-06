# Dependency licence audit

Audit date: 2026-10-02, updated 2026-10-03 for Phase 2 and 2026-10-06 for the holder's name and the publication
metadata of the root POM. Version audited: 0.1.0-SNAPSHOT, all 17 reactor modules.
Intended licence of the project: Apache-2.0.

This is an engineering inventory built from primary sources that are available locally: the `<licenses>` element of
each artifact's POM (or of its parent POM) in the local Maven repository, licence files inside jars, and the
licence files inside the CI image. It is not legal advice. Where no local primary source gave an SPDX identifier,
the tables say "not determined locally".

## 1. Summary

**Result: everything the project distributes, bundles or depends on is compatible with distributing the project
under Apache-2.0.** No dependency carries a copyleft licence that reaches a distributed artifact.

- **The agent contains no third-party code.** The agent home holds six jars; every entry in them is one of the
  project's own classes or resources, and the classes refer to nothing outside the JDK and the project (section 3).
  The statement in the root `NOTICE` that the agent contains no third-party code is correct.
- **The CLI distribution is the only place third-party code is redistributed:** five jars, all Apache-2.0
  (four Jackson jars and SnakeYAML). The jackson-core jar embeds small MIT, BSL-1.0 and BSD-2-Clause components
  and carries their notices inside the jar (section 4).
- **JUnit 5 is EPL-2.0 and is test-only.** It is in no distribution. One published module (`skillroy-junit`)
  declares it as a compile dependency, which makes it a dependency of consumers, not something this project ships.
- **HSQLDB (BSD-style licence) is used only by the sample lending desk and by tests.** It is not bundled in any jar
  or distribution.
- **All 11 pinned build plugins and Maven itself are Apache-2.0.** They are not redistributed.
- **No dual-licensed artifact is in the build today.** The first one planned is JNA; the licence to take is
  Apache-2.0 (section 8).
- The CI image is used for testing and is not redistributed (section 7).

### Needs attention before the first release

Items 1 to 4 were closed on 2026-10-02 (status column); the rest are open. How each was closed is described in the
status text and in sections 3 and 4.

| # | Item | Why | Suggested action | Status |
|---|---|---|---|---|
| 1 | LICENSE and NOTICE are not packaged | Neither assembly descriptor nor the jar configuration copies them; no jar or zip contains them | Add both to the META-INF of every jar and to the top level of the agent home and CLI zips | **Closed 2026-10-02.** The root POM binds a `copy-resources` execution (`copy-legal-files`) that puts the root `LICENSE` and `NOTICE` into `META-INF/` of every module's classes, so every one of the 14 jars (15 since Phase 2 added `skillroy-data`) carries them; both assembly layouts copy them to the top level of the agent home and of the CLI distribution (directory and zip). `DistributionContentIT` (agent home) and `DistributionNoticesIT` (CLI) check the exact file lists and that every copy equals the root file |
| 2 | Apache-2.0 notices for the CLI zip | Section 4(a) and 4(d) of the licence: recipients get a copy of the licence and the attribution notices of redistributed works. Jackson jars carry `META-INF/LICENSE` and `META-INF/NOTICE`; the SnakeYAML jar carries no licence file | Keep the jars unmodified; add a NOTICE to the CLI zip that reproduces the Jackson and SnakeYAML attributions and a copy of Apache-2.0 | **Closed 2026-10-02.** The CLI distribution has the Apache-2.0 text (`LICENSE`), the project `NOTICE` and a `THIRD-PARTY.txt` at its top level. `THIRD-PARTY.txt` lists the five redistributed artifacts with version, SPDX licence and file, and carries the Jackson and SnakeYAML attributions; the jars are copied unmodified. `DistributionNoticesIT` compares the file with the jars actually in `lib/` (coordinates read from each jar's own `pom.properties`), allows only listed licences and checks that each is evidenced inside its jar, so the file cannot go stale |
| 3 | Embedded permissive components in jackson-core | MIT, BSL-1.0 and BSD-2-Clause code is shaded into the jar; its notices are in `META-INF/` of that jar | Do not strip or repack that jar (no shading, no minimising) | **Closed 2026-10-02.** `THIRD-PARTY.txt` lists the embedded components with licence and the file inside the jar that holds each licence text; `DistributionNoticesIT` fails if one of those files is gone from `lib/jackson-core-*.jar`, or if a jar gains a licence or notice file that the list does not name. The rule itself stays: never repack that jar |
| 4 | The root POM has no `<licenses>` element | Published POMs would declare no licence | Declare Apache-2.0 in the root POM | **Closed 2026-10-02.** The root POM declares `<licenses>` (Apache License, Version 2.0, `https://www.apache.org/licenses/LICENSE-2.0.txt`, distribution `repo`) and `<inceptionYear>2026</inceptionYear>`; every module inherits it. On 2026-10-06 `<url>`, `<developers>` (the holder) and `<scm>` (the private remote) were added for publication |
| 5 | EPL-2.0 (JUnit) | Test-only; EPL-2.0 is weak copyleft and is not combined into anything distributed | Keep JUnit out of every assembly; keep `skillroy-junit` the only module that declares JUnit at compile scope | Open |
| 6 | HSQLDB, BSD-style | Sample and test use only | Keep it out of the assemblies; if a runnable sample bundle is ever shipped, include HSQLDB's licence text with it | Open |
| 7 | Not determined locally | HSQLDB SPDX identifier; SPDX identifiers of the free-form copyright files of several CI image packages; every planned piece in section 8 | Resolve when the piece is adopted or when the image is published | Open |
| 8 | Observation, not a licence issue | No class in the controller-side jars refers to `jackson-databind` or `jackson-annotations` types; only `jackson-core` and `jackson-dataformat-yaml` are referenced | Check whether data binding is needed; dropping it would remove two redistributed jars | Open |

## 2. Scope and conventions

Modules audited (17): the root POM `skillroy-test-java-parent`; the agent side `skillroy-protocol`,
`skillroy-agent-api`, `skillroy-agent-bootstrap`, `skillroy-agent-core`, `skillroy-agent-swing`,
`skillroy-agent-data`, `skillroy-agent-dist`; the controller side `skillroy-controller`, `skillroy-data` (new in
Phase 2), `skillroy-scenario`, `skillroy-junit`, `skillroy-cli`; and the samples `skillroy-sample-lending-app`,
`skillroy-sample-lending-agent`, `skillroy-sample-lending-pack`, `skillroy-sample-lending-scenarios`. The agent-side
modules and the sample app and sample agent build with `--release 8`; the controller-side modules and the sample pack
and scenarios build with `--release 25`.

- "Project" artifacts (`ai.skillroy:*`) are the project's own and are not listed as third-party.
- Scopes are Maven scopes as reported by `dependency:list`. The reactor declares no `runtime`-scoped dependency.
- "Evidence" names the primary source: **POM** (`<licenses>` in the artifact's POM or a parent POM), **JAR** (a
  licence file or manifest entry inside the jar), **JDK** and **IMAGE** (files inside the CI image).
- SPDX identifiers are given as stated by the evidence; none was guessed.

### Third-party dependencies by module

"JUnit set" is `junit-jupiter` 5.14.4 with its transitive artifacts (section 5). "Jackson set" is
`jackson-annotations`, `jackson-core`, `jackson-databind`, `jackson-dataformat-yaml` and `snakeyaml` (section 4).

| Module | Compile (third-party) | Provided | Test |
|---|---|---|---|
| `skillroy-protocol` | none | none | JUnit set, HSQLDB (since Phase 2, for the moved `…protocol.sql` tests) |
| `skillroy-agent-api`, `-agent-bootstrap`, `-agent-core`, `-agent-swing`, `-agent-dist` | none | none | JUnit set |
| `skillroy-agent-data` | none | none | JUnit set, HSQLDB |
| `skillroy-controller` | none | none | JUnit set |
| `skillroy-data` (Phase 2) | Jackson set (it declares `jackson-databind` and `jackson-dataformat-yaml`) | none | JUnit set, HSQLDB |
| `skillroy-scenario` | Jackson set, through `skillroy-data` (since Phase 2 its own POM declares no Jackson artifact) | none | JUnit set, HSQLDB |
| `skillroy-junit` | Jackson set; `junit-jupiter-api` and its transitive artifacts | none | JUnit set, `junit-platform-launcher` |
| `skillroy-cli` | Jackson set, through `skillroy-data`, which it also declares itself since Phase 2 | none | JUnit set |
| `skillroy-sample-lending-app` | HSQLDB | none | JUnit set |
| `skillroy-sample-lending-agent` | none | HSQLDB (through the sample app) | JUnit set |
| `skillroy-sample-lending-pack` | Jackson set | none | JUnit set |
| `skillroy-sample-lending-scenarios` | Jackson set; `junit-jupiter-api` set (through `skillroy-junit`) | none | JUnit set, HSQLDB (through the sample app) |

## 3. What ships inside a target JVM

The agent home is built by `skillroy-agent-dist` from `agent-home-layout.xml`, which lists exactly six project
artifacts by coordinates (`ai.skillroy:skillroy-agent-bootstrap`, `-protocol`, `-agent-api`, `-agent-core`,
`-agent-swing`, `-agent-data`). Third-party artifacts cannot enter it: the module has no third-party compile
dependency, and the descriptor uses explicit includes. The built zip was inspected.

| File in the agent home | Content | Third-party code |
|---|---|---|
| `LICENSE`, `NOTICE` | Copies of the root files (added 2026-10-02) | none |
| `skillroy-agent-bootstrap.jar` | 9 classes (`ai.skillroy.testjava.agent.bootstrap`), manifest | none |
| `lib/skillroy-protocol.jar` | 20 classes (`ai.skillroy.testjava.protocol`) | none |
| `lib/skillroy-agent-api.jar` | 36 classes (`ai.skillroy.testjava.agent.api`) | none |
| `lib/skillroy-agent-core.jar` | 75 classes, `agent.properties` | none |
| `modules/skillroy-agent-swing.jar` | 73 classes, one properties file, one service file | none |
| `modules/skillroy-agent-data.jar` | 8 classes, one service file | none |

Checks made on the six jars (221 class files in all):

- Every entry is under `ai/skillroy/` or `META-INF/`. The only `META-INF` entries besides Maven metadata and the
  manifest are two `META-INF/services` files naming the project's own module interface and, since 2026-10-02,
  `META-INF/LICENSE` and `META-INF/NOTICE` (the project's own files). `DistributionContentIT` checks both the
  exact list of files in the agent home and that no jar holds an entry outside `ai/skillroy/` and `META-INF/`.
- The constant pools of all 221 classes refer only to `java.*`, `javax.*` (`accessibility`, `imageio`, `swing`),
  `com.sun.net.httpserver` (the JDK's `jdk.httpserver` module) and `ai.skillroy.*`. No third-party package is
  referenced.
- When this audit was written no LICENSE or NOTICE file was in any of the jars; that gap is closed (item 1 in
  section 1). The six jars and the home itself now carry the project's own licence files and nothing else.

Modules that are loaded into a target JVM only in the sample set-up are not part of the agent home. The sample
agent module `skillroy-sample-lending-agent` declares everything it compiles against as `provided` and its jar
holds only project classes. The sample application jar `skillroy-sample-lending-app` also holds only project
classes; HSQLDB must be put on the class path next to it and is not bundled.

## 4. Redistributed third-party artifacts

The CLI distribution is built by `skillroy-cli` from `cli-layout.xml`: `bin/skillroy` (a POSIX shell launcher, the
project's own), `lib/`, which receives the module's jar and its `runtime`-scope dependencies, and, since
2026-10-02, `LICENSE`, `NOTICE` and `THIRD-PARTY.txt` at the top level. It is produced as a directory and as
`skillroy-cli.zip`. The built zip holds 17 entries (three directories, the three top-level files, the launcher and
ten jars). The ten jars in `lib/` are five project jars (`skillroy-cli`, `skillroy-controller`, `skillroy-data`,
`skillroy-scenario`, `skillroy-protocol`, version 0.1.0-SNAPSHOT; `skillroy-data` since Phase 2, and
`DistributionNoticesIT` expects exactly these five) and the five third-party jars below. JUnit, HSQLDB and the agent
jars are not in it.

| Artifact | Version | Licence (SPDX) | Evidence | Obligations when redistributed |
|---|---|---|---|---|
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | Apache-2.0 | POM; JAR `META-INF/LICENSE`, `NOTICE` | Ship licence and NOTICE (they are in the jar) |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | Apache-2.0, with embedded MIT, BSL-1.0, BSD-2-Clause parts (below) | POM; JAR `META-INF/LICENSE`, `NOTICE`, three more licence files | As above; keep the embedded licence files with the jar |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | Apache-2.0 | POM; JAR `META-INF/LICENSE`, `NOTICE` | Ship licence and NOTICE (in the jar) |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | 2.22.3 | Apache-2.0 | POM (parent `jackson-dataformats-text`); JAR `META-INF/LICENSE`, `NOTICE` | Ship licence and NOTICE (in the jar) |
| `org.yaml:snakeyaml` | 2.5 | Apache-2.0 | POM; JAR manifest `Bundle-License` | The jar has no LICENSE or NOTICE file: the Apache-2.0 text (`LICENSE`) and the attribution (`THIRD-PARTY.txt`) are at the top level of the CLI distribution |

Components embedded in `jackson-core` 2.22.3 (stated in its `META-INF/NOTICE` and the licence files beside it):

| Component | Licence (SPDX) | Copyright holder named | File in the jar |
|---|---|---|---|
| FastDoubleParser, shaded under `com.fasterxml.jackson.core.internal.shaded.fdp` | MIT | Werner Randelshofer | `META-INF/FastDoubleParser-LICENSE` |
| Schubfach number writing code | MIT | Raffaello Giulietti | `META-INF/Schubfach-LICENSE` |
| Code derived from Daniel Lemire's fast_double_parser | BSL-1.0 | Daniel Lemire | `META-INF/FastDoubleParser-ThirdParty-LICENSE` |
| fast_float | MIT | The fast_float authors | `META-INF/FastDoubleParser-ThirdParty-LICENSE` |
| bigint (portions under the 2-clause licence) | BSD-2-Clause | Tim Buktu | `META-INF/FastDoubleParser-ThirdParty-LICENSE` |

All of these are permissive and compatible with Apache-2.0. The MIT and BSD-2-Clause terms require the copyright
notice to travel with the code; it does, as long as the jar is shipped unmodified.

SnakeYAML 2.5 also contains the package `org.yaml.snakeyaml.external.com.google.gdata.util.common.base`. The jar
carries no separate licence statement for it; it is not determined locally.

Other places third-party code could be redistributed, and the result:

| Place | Third-party code |
|---|---|
| Agent home (directory and zip) | none (section 3) |
| Project jars published to a Maven repository (all 15 reactor jars, `skillroy-data` included) | none: no jar contains an entry outside `ai/skillroy/`, `META-INF/maven`, the manifest, `META-INF/services` and, since 2026-10-02, the project's own `META-INF/LICENSE` and `META-INF/NOTICE`; nothing is shaded |
| Published POMs | They name third-party dependencies but do not contain them |
| Sample application, sample agent module | HSQLDB is not bundled; it is a separate dependency |
| Test fixtures and resources in the source tree | No vendored third-party files, fonts, images, native libraries or jars were found in the tree |

## 5. All other dependencies: test, provided, sample-only

None of these is in the agent home or the CLI distribution.

| Artifact | Version | Scope and modules | Licence (SPDX) | Evidence |
|---|---|---|---|---|
| `org.junit.jupiter:junit-jupiter` | 5.14.4 | test, every module that has tests | EPL-2.0 | POM |
| `org.junit.jupiter:junit-jupiter-api` | 5.14.4 | test; compile in `skillroy-junit` and `skillroy-sample-lending-scenarios` | EPL-2.0 | POM; JAR `META-INF/LICENSE.md` |
| `org.junit.jupiter:junit-jupiter-engine` | 5.14.4 | test | EPL-2.0 | POM |
| `org.junit.jupiter:junit-jupiter-params` | 5.14.4 | test | EPL-2.0 | POM |
| `org.junit.platform:junit-platform-commons` | 1.14.4 | test; compile where `junit-jupiter-api` is compile | EPL-2.0 | POM |
| `org.junit.platform:junit-platform-engine` | 1.14.4 | test | EPL-2.0 | POM |
| `org.junit.platform:junit-platform-launcher` | 1.14.4 | test, `skillroy-junit` only | EPL-2.0 | POM; JAR `META-INF/LICENSE.md` |
| `org.junit:junit-bom` | 5.14.4 | import scope (dependency management only) | EPL-2.0 | POM |
| `org.opentest4j:opentest4j` | 1.3.0 | test; compile where `junit-jupiter-api` is compile | Apache-2.0 | POM; JAR `META-INF/LICENSE` |
| `org.apiguardian:apiguardian-api` | 1.1.2 | test; compile where `junit-jupiter-api` is compile | Apache-2.0 | POM; JAR `META-INF/LICENSE` |
| `org.hsqldb:hsqldb` (classifier `jdk8`) | 2.7.4 | compile in `skillroy-sample-lending-app`; provided in `skillroy-sample-lending-agent`; test in `skillroy-protocol`, `skillroy-agent-data`, `skillroy-data`, `skillroy-scenario` (these three since Phase 2) and `skillroy-sample-lending-scenarios` | BSD-style ("HSQLDB License, a BSD open source license"); SPDX not determined locally | POM only; the jar has no licence file |

Notes:

- JUnit 5 is EPL-2.0. It is used to run tests and is never combined into a distributed artifact. Surefire and
  Failsafe also load the JUnit Platform launcher at test time; that is a plugin dependency (section 6).
- `skillroy-junit` is a published module that declares `junit-jupiter-api` at compile scope, so JUnit reaches the
  class path of anyone who uses that module. The project does not redistribute it.
- HSQLDB's POM names "HSQLDB License, a BSD open source license" and links to the HSQLDB site; the licence text
  itself was not available locally, so no SPDX identifier is claimed. `docs/DECISIONS.md` (B1) also records it as
  BSD-style. It is copied into build output directories by the sample's integration-test set-up, which are not
  distributed.
- The project's own artifacts (`ai.skillroy:*`) appear at `compile`, `provided` and `test` scope between modules.
  `skillroy-agent-dist` (type `zip`) is a test-scope dependency of `skillroy-sample-lending-scenarios` and is never
  put on a class path.

## 6. Build plugins

All plugin versions are pinned in the root POM (`pluginManagement`) and are the ones used by `mvn verify`, plus
three that `verify` does not run. Each plugin's licence was read from its POM; none states a licence itself, and
each inherits `Apache-2.0` from the Apache parent POM through its Maven parent chain. Plugin transitive
dependencies (Plexus, the Maven resolver, the Surefire providers and so on) were not enumerated; they are used at
build time only and are not redistributed.

| Plugin (`org.apache.maven.plugins`) | Version | Use in this build | Licence (SPDX) | Evidence |
|---|---|---|---|---|
| `maven-enforcer-plugin` | 3.6.3 | Requires Maven 3.9 or later and JDK 25 or later | Apache-2.0 | POM chain `enforcer` > `maven-parent` > `apache` |
| `maven-resources-plugin` | 3.5.0 | Copies and filters resources | Apache-2.0 | POM chain `maven-plugins` > `maven-parent` > `apache` |
| `maven-compiler-plugin` | 3.16.0 | Compiles main and test code at release 8 or 25 | Apache-2.0 | same |
| `maven-surefire-plugin` | 3.6.0 | Unit tests | Apache-2.0 | POM chain `surefire` > `maven-parent` > `apache` |
| `maven-failsafe-plugin` | 3.6.0 | Integration tests in `skillroy-agent-dist`, `skillroy-cli`, `skillroy-sample-lending-scenarios` | Apache-2.0 | same |
| `maven-jar-plugin` | 3.5.1 | Jars; manifest entries for the bootstrap, CLI and sample app | Apache-2.0 | POM chain `maven-plugins` |
| `maven-assembly-plugin` | 3.8.0 | The agent home and CLI directories and zips | Apache-2.0 | same |
| `maven-dependency-plugin` | 3.11.0 | `unpack` and `copy` for integration tests; `tree` and `list` for this audit | Apache-2.0 | same |
| `maven-clean-plugin` | 3.5.0 | `mvn clean` (pinned, not run by `verify`) | Apache-2.0 | same |
| `maven-install-plugin` | 3.2.0 | `mvn install` (pinned, not run by `verify`) | Apache-2.0 | same |
| `maven-deploy-plugin` | 3.1.4 | `mvn deploy` (pinned, not run by `verify`) | Apache-2.0 | same |

Apache Maven itself (3.9 or later is required by the enforcer rule) is Apache-2.0, as stated in the `LICENSE` file
inside its distribution. It is the build tool and is not redistributed. The Maven libraries it bundles were not
audited.

## 7. Test image

`ci/Dockerfile` builds the image used for display tests and cross-JDK tests: `FROM eclipse-temurin:25-jdk`, then
one `apt-get install --no-install-recommends` of ten Debian packages, then `ENV LANG=C.UTF-8`. The image is used
for testing and is not redistributed. The facts below come from an image built from that Dockerfile and inspected on
2026-10-02 (Temurin 25.0.4.1; the base archive was Ubuntu 26.04, so the `.deb` packages come from the Ubuntu
archive). The tag `25-jdk` moves, so versions will differ on later builds.

| Component | Version inspected | Licence | Evidence |
|---|---|---|---|
| Eclipse Temurin JDK (`/opt/java/openjdk`) | 25.0.4.1 | GPL-2.0-only WITH Classpath-exception-2.0 | JDK: `legal/java.base/LICENSE` (GPLv2) and `ASSEMBLY_EXCEPTION` (OpenJDK assembly exception). Bundled third-party parts have their own notices under `legal/` (for example FreeType, HarfBuzz, libpng, giflib, lcms) |
| Ubuntu base system | 26.04 | Many packages, each under its own licence (GPL, LGPL, MIT, others) | Not enumerated |

Packages named in the Dockerfile (licences as stated in each package's `copyright` file under `/usr/share/doc`):

| Package | Version inspected | Licence as stated | SPDX |
|---|---|---|---|
| `xvfb` | 2:21.1.22-1ubuntu1.2 | Free-form file; X.Org MIT-style licence variants and other permissive notices | not determined locally |
| `xauth` | 1:1.1.2-1.1build1 | Free-form file; permissive X.Org style | not determined locally |
| `libxtst6` | 2:1.2.5-1build1 | Free-form file; permissive X.Org style | not determined locally |
| `libxi6` | 2:1.8.2-2 | Free-form file; permissive X.Org style | not determined locally |
| `libxrender1` | 1:0.9.12-1build1 | Free-form file; permissive notices (Keith Packard, SuSE) | not determined locally |
| `libxext6` | 2:1.3.4-1build3 | Free-form file; permissive X.Org style | not determined locally |
| `libfreetype6` | 2.14.2+dfsg-1ubuntu0.1 | Structured (DEP-5) file lists FTL, GPL-2+, GPL-3+, Expat, BSD-3-Clause, BSL-1.0, Zlib and others | per file, see left |
| `fontconfig` | 2.17.1-3ubuntu1 | Free-form file; permissive notice (Keith Packard) | not determined locally |
| `fonts-dejavu-core` | 2.37-8build1 | `bitstream-vera` and `GPL-2+` as listed | per file, see left |
| `procps` | 2:4.0.4-9ubuntu1 | GPL-2.0+, LGPL-2.0+, LGPL-2.1+ as listed | per file, see left |

Four of these (`libfreetype6`, `fontconfig`, `fonts-dejavu-core`, `procps`) were already present in the base image.
The install command added 58 packages in all: the six named packages that were missing plus 52 dependencies
installed automatically (X libraries, Mesa, LLVM, nettle, libelf, libsensors and others). Their copyright files
list mostly MIT/Expat and X.Org permissive licences, with BSD, ISC and Apache-2.0 (including the LLVM exception)
parts and some GPL and LGPL components. They are test infrastructure and are not linked into any project artifact.

Other things the CI script uses at run time, none of them part of the image:

| Item | Licence | Note |
|---|---|---|
| Apache Maven from the host, mounted read-only by `ci/run-in-linux.sh` | Apache-2.0 | Not in the image |
| An optional Linux JDK 8 mounted at `/opt/jdk8` for legacy-target tests | not determined locally | Supplied by the person running the tests; depends on the vendor |

If the image were ever published, the GPL and LGPL packages in it would bring source-offer and notice obligations
of their own. That is not the case today.

## 8. Planned, not yet audited

The design names the following pieces. None is in the build today, so none was checked against a POM or jar, and no
licence is claimed here beyond what `docs/DECISIONS.md` already records (cited in the third column).

| Piece | Named for | Licence recorded in the repository | Status |
|---|---|---|---|
| Byte Buddy | Bytecode hooks module (shaded and relocated) | Apache-2.0 (B1) | not audited; verify at adoption, including the shaded copy |
| Byteman 4.0.27 | Optional `rule.*` module | LGPL-2.1-or-later (B8) | optional; the jar is never bundled; the user supplies it |
| JNA | Optional emulated-attach path | Dual Apache-2.0 / LGPL-2.1-or-later (B1, B8); take Apache-2.0 | optional controller dependency; not audited |
| jattach | Attach as root or across users | Apache-2.0 (B1) | not redistributed; found on `PATH` or at a configured path (B8) |
| Playwright Java | Controller, embedded web views | not audited | not audited |
| JSqlParser | SQL guard allow-list | not audited | not audited |
| MCP Java SDK | MCP server in the controller | not audited | not audited |
| Anthropic Java SDK | Authoring planner and resolvers | not audited | not audited |
| JCEF | Embedded-browser SPI (research note) | not audited | not audited |
| JxBrowser | Optional reflective module | Commercial licence needed at run time (B9); the base ships no vendor code | not audited; never bundled |
| snakeyaml-engine, victools jsonschema-generator | Controller schema and YAML (design table) | not audited | not audited |
| Jetty 12 | Servlet transport for a remote MCP controller | not audited | not audited |
| OpenTelemetry Java | Export in Phase 6 (B11) | not audited | not audited |

Each of these needs a licence check, and a decision on bundling, before it enters a distribution. The pieces under
LGPL (Byteman, and JNA if its LGPL option were ever taken) need particular care, because they must not be bundled
into an Apache-2.0 distribution without meeting LGPL's terms; B8 already keeps them optional and unbundled.

## 9. How this audit was produced

The audit was run on a working copy of the source tree (without `target/` and `.git/`), not on the tree under
edit. In that copy the sample modules carry the `skillroy-sample-lending-*` names used in this document and the
controller side builds with `--release 25`. The build ran offline, so no artifact was fetched from the network.
Tools: Apache Maven 3.9 and a JDK 25 or later (the root enforcer rule requires both).

1. Copy the tree: `rsync -a --exclude target --exclude .git <tree>/ <copy>/`.
2. Build the reactor, assemble both distributions and print every dependency tree and list, offline:
   `mvn -o -B verify -DskipTests dependency:tree dependency:list` (run in `<copy>`). Drop `-o` only if an artifact
   is missing from the local Maven repository.
3. Read the plugin versions and the dependency versions from the root POM, and the executed plugin goals from the
   build log (`--- plugin:version:goal` lines).
4. List the two distributions:
   `unzip -l agent/skillroy-agent-dist/target/skillroy-agent.zip` and
   `unzip -l controller/skillroy-cli/target/skillroy-cli.zip`; read `agent-home-layout.xml` and `cli-layout.xml`.
5. Check that no agent or project jar contains foreign entries:
   `unzip -Z1 <jar> | grep -v '^ai/skillroy/' | grep -v '^META-INF/'`, and list the packages the classes refer to
   from their constant pools (`javap -v -p -cp <dir> <classes> | grep '= Class '`), ignoring `java/` and
   `ai/skillroy/`.
6. Read each artifact's licence from the local Maven repository, following `<parent>` until a `<licenses>` element
   is found (this snippet is the whole tool; set `M2_REPO` to the local Maven repository):

   ```python
   import os, sys, xml.etree.ElementTree as ET
   def lic(g, a, v):
       p = os.path.join(os.environ["M2_REPO"], g.replace(".", "/"), a, v, f"{a}-{v}.pom")
       if not os.path.exists(p): return None
       r = ET.parse(p).getroot(); tag = lambda e: e.tag.split("}")[-1]
       kids = {tag(c): c for c in r}
       if "licenses" in kids and len(kids["licenses"]):
           return [{tag(x): (x.text or "").strip() for x in l} for l in kids["licenses"]]
       par = kids.get("parent")
       if par is None: return []
       f = {tag(x): (x.text or "").strip() for x in par}
       return lic(f["groupId"], f["artifactId"], f["version"])
   for coord in sys.argv[1:]: print(coord, lic(*coord.split(":")))
   ```

   Licence files inside jars: `unzip -Z1 <jar> | grep -i -E 'licen|notice'`, then `unzip -p <jar> <entry>`.
7. For the test image: `docker build -t skillroy-test-java-ci:local ci`, then run the image with a shell entry
   point to read `/opt/java/openjdk/release`, `/opt/java/openjdk/legal/java.base/LICENSE`, the `Install:` lines of
   `/var/log/apt/history.log`, and `/usr/share/doc/<package>/copyright` for each package.

To repeat: run steps 1 to 7 on a clean copy and compare the tables here with the output. The dependency versions
are managed in the root POM (`jackson.version`, `hsqldb.version`, `junit.version`, and the plugin version
properties), so a version change there is the usual trigger for a re-audit, as is any new module that declares a
dependency, a change to either assembly descriptor, or a change to `ci/Dockerfile`.
