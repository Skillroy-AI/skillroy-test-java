# Dependency licence audit

Audit date: 2026-10-02, updated 2026-10-03 for Phase 2, 2026-10-06 for the holder's name and the publication
metadata of the root POM, later on 2026-10-06 for the test image's Chromium run-time libraries (spike BS8, section 7)
and the artifacts spike BS8 used (section 8), and on 2026-10-07 for Phase 4 (sections 1 to 5, 8 and 9; later that
day the agent home's seventh jar was inspected and the web host scenarios module's scopes read from its POM). Version
audited: 0.1.0-SNAPSHOT, all 22 reactor modules (19 in a build without the Maven profile `jcef`; the 3 web host
samples are built only with `-Pjcef`); the spike project under `spikes/BS8/` is not a reactor module and distributes
nothing.
Intended licence of the project: Apache-2.0.

This is an engineering inventory built from primary sources that are available locally: the `<licenses>` element of
each artifact's POM (or of its parent POM) in the local Maven repository, licence files inside jars, and the
licence files inside the CI image. It is not legal advice. Where no local primary source gave an SPDX identifier,
the tables say "not determined locally".

## 1. Summary

**Result: everything the project distributes, bundles or depends on is compatible with distributing the project
under Apache-2.0.** No dependency carries a copyleft licence that reaches a distributed artifact.

- **The agent contains no third-party code.** The agent home holds seven jars. Every entry in all seven is one of the
  project's own classes or resources, and their classes refer to nothing outside the JDK and the project (section 3).
  The seventh, `skillroy-agent-web` (Phase 4, inspected 2026-10-07), reaches JCEF and JxBrowser only by reflection,
  through class names that its two descriptors hold as text. The statement in the root `NOTICE` that the agent
  contains no third-party code is correct.
- **The CLI distribution is the only place third-party code is redistributed:** since Phase 5 (2026-10-08) 36
  jars, 27 Apache-2.0, 8 MIT and 1 MIT-0 (until then five, all Apache-2.0: four Jackson jars and SnakeYAML). The
  additions are the Anthropic Java SDK with its tree, JSqlParser, the MCP Java SDK with its tree and the SLF4J
  no-operation provider. The jackson-core jar embeds small MIT, BSL-1.0 and BSD-2-Clause components and carries their
  notices inside the jar; the MIT and MIT-0 jars carry no licence file, so `THIRD-PARTY.txt` carries their copyright
  lines and licence texts; OkHttp's public-suffix data file is MPL-2.0 and ships unmodified with its notice (section
  4). `DistributionNoticesIT` holds the distribution to all of this.
- **Playwright and JCEF are in no distribution.** Playwright is the compile dependency of the optional
  `skillroy-cdp` alone (D49, `docs/design/phase4-contracts.md` section 14); no POM of the modules whose jars are in
  the CLI distribution refers to it. JCEF belongs to the sample web host modules behind the Maven profile `jcef`,
  with the natives at test scope. The POMs name Apache-2.0 for Playwright, jcefmaven and the small libraries around
  them; java-cef and CEF are BSD; JOGL and GlueGen carry several licences that their licence files assign to
  different components, not as alternatives; the Node.js binaries inside Playwright's driver bundle carry Node's own
  notices (section 5).
- **JUnit 5 is EPL-2.0 and is test-only.** It is in no distribution. One published module (`skillroy-junit`)
  declares it as a compile dependency, which makes it a dependency of consumers, not something this project ships.
- **HSQLDB (BSD-style licence) is used only by the sample lending desk and by tests.** It is not bundled in any jar
  or distribution.
- **All 11 pinned build plugins and Maven itself are Apache-2.0.** They are not redistributed.
- **One dual-licensed artifact is in the build since Phase 5:** JSqlParser 5.4 (LGPL-2.1 or Apache-2.0); the
  project takes Apache-2.0, `THIRD-PARTY.txt` says so (sections 4 and 8). JNA, the next one planned, would be taken
  under Apache-2.0 as well.
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
| 7 | Not determined locally | HSQLDB SPDX identifier; SPDX identifiers of the free-form copyright files of several CI image packages; the SGI Free Software License B and Ubuntu Font Licence entries in the JOGL POM; whether the JCEF natives deliver Chromium's third-party notices (section 5); every planned piece in section 8 | Resolve when the piece is adopted, when the image is published or when a JCEF artifact would enter a distribution | Open |
| 8 | Observation, not a licence issue | No class in the controller-side jars refers to `jackson-databind` or `jackson-annotations` types; only `jackson-core` and `jackson-dataformat-yaml` are referenced | Check whether data binding is needed; dropping it would remove two redistributed jars | **Moot since Phase 5 (2026-10-08):** the Anthropic Java SDK and the MCP Java SDK bind their types with `jackson-databind`; the CLI distribution needs all three Jackson core jars |
| 9 | Notice-heavy Phase 4 pieces: Playwright's driver bundle (Node.js binaries for five platforms) and the JCEF natives with JOGL and GlueGen | In no distribution today (D49; sample and test scope), but each carries notices that would have to travel with it: Node's `driver/<platform>/LICENSE` files (an MIT-style grant, then the notices of Node's own components) and the driver package's `LICENSE`, `NOTICE` and `ThirdPartyNotices.txt`; the natives' `LICENSE.txt`, `jogl.LICENSE.txt` and `gluegen.LICENSE.txt`. No file with Chromium's third-party notices was found in the natives, and the JOGL and GlueGen POMs list licences beyond BSD-2-Clause and BSD-3-Clause (section 5) | Keep `skillroy-cdp`, the Playwright set and the JCEF set out of every assembly: `DistributionNoticesIT` allows only Apache-2.0 jars in the CLI `lib/` and fails on one that `THIRD-PARTY.txt` does not list, and `DistributionContentIT` fixes the agent home's file list. If one of them ever enters a distribution, audit it as section 4 does, ship the notices above and extend `THIRD-PARTY.txt` first | Open |

## 2. Scope and conventions

Modules audited (24 since Phase 5, 2026-10-08; 22 before): the root POM `skillroy-test-java-parent`; the agent side `skillroy-protocol`,
`skillroy-agent-api`, `skillroy-agent-bootstrap`, `skillroy-agent-core`, `skillroy-agent-swing`,
`skillroy-agent-data`, `skillroy-agent-web` (new in Phase 4), `skillroy-agent-dist`; the controller side
`skillroy-controller`, `skillroy-data` (new in Phase 2), `skillroy-scenario`, `skillroy-inference` and `skillroy-mcp` (new in
Phase 5), `skillroy-junit`, `skillroy-cli`, `skillroy-cdp` (new in Phase 4); the lending samples `skillroy-sample-lending-app`, `skillroy-sample-lending-agent`,
`skillroy-sample-lending-pack`, `skillroy-sample-lending-scenarios`; and the web host samples
`skillroy-sample-web-host`, `skillroy-sample-web-host-agent` and `skillroy-sample-web-host-scenarios` (all three new
in Phase 4). The root POM lists 20 modules in `<modules>` (all but itself and the web host samples; 18 before Phase 5);
the three web host samples are listed only in its profile `jcef` and are built only with `-Pjcef`, so a build without
the profile has 21 reactor projects and one with it 24. The agent-side modules (`skillroy-agent-web` included), the sample app and sample
agent, and the web host and its agent module build with `--release 8`; the controller-side modules (`skillroy-cdp`
included), the sample pack and the scenarios of both samples build with `--release 25`.

- "Project" artifacts (`ai.skillroy:*`) are the project's own and are not listed as third-party.
- Scopes are Maven scopes as reported by `dependency:list`. The reactor declares no `runtime`-scoped dependency.
- "Evidence" names the primary source: **POM** (`<licenses>` in the artifact's POM or a parent POM), **JAR** (a
  licence file or manifest entry inside the jar), **JDK** and **IMAGE** (files inside the CI image).
- SPDX identifiers are given as stated by the evidence; none was guessed.

### Third-party dependencies by module

"JUnit set" is `junit-jupiter` 5.14.4 with its transitive artifacts (section 5). "Jackson set" is
`jackson-annotations`, `jackson-core`, `jackson-databind`, `jackson-dataformat-yaml` and `snakeyaml` (section 4).
"Playwright set" is `playwright` 1.63.0 with `driver`, `driver-bundle`, `gson` 2.14.0, `error_prone_annotations`
2.48.0, `jspecify` and `opentest4j` (section 5). "JCEF set" is `jcefmaven` 152.0.6 with `jcef-api`, `jogl-all`,
`gluegen-rt`, `commons-compress`, `commons-codec`, `commons-io`, `commons-lang3`, `gson` 2.11.0 and
`error_prone_annotations` 2.27.0 (section 5); the platform natives are named apart.

| Module | Compile (third-party) | Provided | Test |
|---|---|---|---|
| `skillroy-protocol` | none | none | JUnit set, HSQLDB (since Phase 2, for the moved `…protocol.sql` tests) |
| `skillroy-agent-api`, `-agent-bootstrap`, `-agent-core`, `-agent-swing`, `-agent-dist` | none | none | JUnit set |
| `skillroy-agent-data` | none | none | JUnit set, HSQLDB |
| `skillroy-agent-web` (Phase 4) | none | none | JUnit set |
| `skillroy-controller` | none | none | JUnit set |
| `skillroy-data` (Phase 2) | Jackson set (it declares `jackson-databind` and `jackson-dataformat-yaml`) | none | JUnit set, HSQLDB |
| `skillroy-scenario` | Jackson set, through `skillroy-data` (since Phase 2 its own POM declares no Jackson artifact) | none | JUnit set, HSQLDB |
| `skillroy-inference` (Phase 5, 2026-10-08) | the Anthropic Java SDK 2.70.0 set (OkHttp, Okio, Kotlin, Jackson's jdk8/jsr310/Kotlin modules, victools, swagger-annotations, errorprone annotations, standardwebhooks, slf4j-api), JSqlParser 5.4; the Jackson set through `skillroy-data` | none | JUnit set, HSQLDB |
| `skillroy-mcp` (Phase 5, 2026-10-08) | the MCP Java SDK 2.0.1 (`mcp-core`, `mcp-json-jackson2`) with `reactor-core`, `reactive-streams`, `slf4j-api`, `json-schema-validator` and `itu`; the inference set through `skillroy-inference` | none | JUnit set |
| `skillroy-junit` | Jackson set; `junit-jupiter-api` and its transitive artifacts | none | JUnit set, `junit-platform-launcher` |
| `skillroy-cli` | Jackson set, through `skillroy-data`, which it also declares itself since Phase 2; since Phase 5 (2026-10-08) the inference set through `skillroy-inference` and the MCP set through `skillroy-mcp`, plus `slf4j-nop` 2.0.16, which it declares itself (D88: the only SLF4J binding, so the libraries' logging is silent) | none | JUnit set |
| `skillroy-cdp` (Phase 4) | Playwright set; Jackson set, through `skillroy-scenario` and `skillroy-data` | none | JUnit set |
| `skillroy-sample-lending-app` | HSQLDB | none | JUnit set |
| `skillroy-sample-lending-agent` | none | HSQLDB (through the sample app) | JUnit set |
| `skillroy-sample-lending-pack` | Jackson set | none | JUnit set |
| `skillroy-sample-lending-scenarios` | Jackson set; `junit-jupiter-api` set (through `skillroy-junit`) | none | JUnit set, HSQLDB (through the sample app) |
| `skillroy-sample-web-host` (Phase 4, profile `jcef`) | JCEF set | none | JUnit set; the natives `jcef-natives-<platform>` of the platform in `skillroy.jcef.platform` (default `linux-amd64`) |
| `skillroy-sample-web-host-agent` (Phase 4, profile `jcef`) | none | JCEF set (through the host module, which it declares `provided`; it declares no third-party artifact itself) | JUnit set |
| `skillroy-sample-web-host-scenarios` (Phase 4, profile `jcef`) | Jackson set, through `skillroy-scenario` (and `skillroy-data`); it declares no third-party artifact at compile scope | none | JUnit set (`junit-jupiter`); the Playwright set, through `skillroy-cdp`; the JCEF set and the natives `jcef-natives-<platform>`, through the web host, which it declares with the natives; besides, test-scope project artifacts whose transitive dependencies it excludes (the agent home's zip, the host's agent module, the CLI). Read from its POM on 2026-10-07; it does not depend on `skillroy-junit` |

## 3. What ships inside a target JVM

The agent home is built by `skillroy-agent-dist` from `agent-home-layout.xml`, which lists exactly seven project
artifacts by coordinates (`ai.skillroy:skillroy-agent-bootstrap`, `-protocol`, `-agent-api`, `-agent-core`,
`-agent-swing`, `-agent-data`, `-agent-web`). Third-party artifacts cannot enter it: the module has no third-party
compile dependency, and the descriptor uses explicit includes. The built zip was inspected when the audit was written,
when it held six jars; the seventh, added by Phase 4, was inspected on 2026-10-07 as the module's own jar
(`agent/skillroy-agent-web/target/skillroy-agent-web-0.1.0-SNAPSHOT.jar` of the final tree), which the home holds as
`modules/skillroy-agent-web.jar`.

| File in the agent home | Content | Third-party code |
|---|---|---|
| `LICENSE`, `NOTICE` | Copies of the root files (added 2026-10-02) | none |
| `skillroy-agent-bootstrap.jar` | 9 classes (`ai.skillroy.testjava.agent.bootstrap`), manifest | none |
| `lib/skillroy-protocol.jar` | 20 classes (`ai.skillroy.testjava.protocol`) | none |
| `lib/skillroy-agent-api.jar` | 61 class files on 2026-10-08 (`ai.skillroy.testjava.agent.api` and its sub-packages; the count of 36 recorded in Phase 1 predates the web SPI of Phase 4 and the Phase 4.x defaults `BitmapSource`, `WebBrowser$1`, `WebBrowser$2`) | none |
| `lib/skillroy-agent-core.jar` | 75 classes, `agent.properties` | none |
| `modules/skillroy-agent-swing.jar` | 73 classes, one properties file, one service file | none |
| `modules/skillroy-agent-data.jar` | 8 classes, one service file | none |
| `modules/skillroy-agent-web.jar` | 156 classes since the Phase 4.x amendments of 2026-10-08 (155 in Phase 4: `BrowserExtras` removed, `WebModule$Pace` and `WebModule$Pace$1` added) (`ai.skillroy.testjava.agent.web` and its sub-packages `jcef`, `jxbrowser` and `reflect`): the agent's `web.*` primitives, the browser index and events, and two reflective adapters; the descriptors `jcef.json` and `jxbrowser.json`, `web-module.properties`, one service file (Phase 4) | none |

Checks made on the six jars inspected (221 class files in all):

- Every entry is under `ai/skillroy/` or `META-INF/`. The only `META-INF` entries besides Maven metadata and the
  manifest are two `META-INF/services` files naming the project's own module interface and, since 2026-10-02,
  `META-INF/LICENSE` and `META-INF/NOTICE` (the project's own files). `DistributionContentIT` checks both the
  exact list of files in the agent home and that no jar holds an entry outside `ai/skillroy/` and `META-INF/`.
- The constant pools of all 221 classes refer only to `java.*`, `javax.*` (`accessibility`, `imageio`, `swing`),
  `com.sun.net.httpserver` (the JDK's `jdk.httpserver` module) and `ai.skillroy.*`. No third-party package is
  referenced.
- When this audit was written no LICENSE or NOTICE file was in any of the jars; that gap is closed (item 1 in
  section 1). The six jars and the home itself now carry the project's own licence files and nothing else; the
  Phase 4 jars carry them too (section 4).
- The seventh jar, `modules/skillroy-agent-web.jar`, inspected on 2026-10-07 with `unzip -l` and `javap -v`: 155
  class files (62 in `…agent.web`, 41 in `…agent.web.jcef`, 31 in `…agent.web.jxbrowser`, 21 in `…agent.web.reflect`)
  and, besides them, only the manifest, `META-INF/maven`, one `META-INF/services` file naming the project's module
  interface, the project's own `META-INF/LICENSE` and `META-INF/NOTICE`, and three resources of the module under
  `ai/skillroy/` (`jcef.json`, `jxbrowser.json`, `web-module.properties`). Every entry is under `ai/skillroy/` or
  `META-INF/`, and no entry is a vendor class (nothing under `org/cef/` or `com/teamdev/`). The constant pools refer
  only to `java.*`, `javax.imageio` and `ai.skillroy.*`, and no class file contains the text `org/cef`, `org.cef`,
  `com/teamdev` or `com.teamdev`. The two descriptors do name JCEF's and JxBrowser's classes and members, as strings:
  that is by design (decision D54 of `docs/design/phase4-contracts.md`), because the adapters resolve those names by
  reflection through the application's own class loader, so the agent carries no vendor class and no reference a JVM
  would have to link. `DistributionContentIT` holds the jar in the exact file list of the agent home and checks its
  entries as it does the others'.

Modules that are loaded into a target JVM only in the sample set-up are not part of the agent home. The sample
agent module `skillroy-sample-lending-agent` declares everything it compiles against as `provided` and its jar
holds only project classes. The sample application jar `skillroy-sample-lending-app` also holds only project
classes; HSQLDB must be put on the class path next to it and is not bundled.

The web host samples (Phase 4, profile `jcef`) follow the same pattern. The agent module
`skillroy-sample-web-host-agent` declares the agent API and the host module as `provided`; its jar (5 classes, built
2026-10-07) holds only project classes, and its constant pools name no JCEF type. The host jar
`skillroy-sample-web-host` (21 classes) holds its own classes and its own three pages `webhost/*.html`, which have no
external references; JCEF and the natives are separate dependencies and are not bundled.

## 4. Redistributed third-party artifacts

The CLI distribution is built by `skillroy-cli` from `cli-layout.xml`: `bin/skillroy` (a POSIX shell launcher, the
project's own), `lib/`, which receives the module's jar and its `runtime`-scope dependencies, and, since
2026-10-02, `LICENSE`, `NOTICE` and `THIRD-PARTY.txt` at the top level. It is produced as a directory and as
`skillroy-cli.zip`. Until Phase 5 the zip held 17 entries (three directories, the three top-level files, the
launcher and ten jars: five project jars and the four Jackson jars and SnakeYAML). **Since Phase 5 (2026-10-08)**
`lib/` holds 43 jars (the zip: 50 entries): seven project jars (`skillroy-cli`, `skillroy-controller`,
`skillroy-data`, `skillroy-inference`, `skillroy-mcp`, `skillroy-protocol`, `skillroy-scenario`, version
0.1.0-SNAPSHOT; `DistributionNoticesIT` expects exactly these seven) and the 36 third-party jars below: 27
Apache-2.0, 8 MIT, 1 MIT-0. They are the four Jackson jars and SnakeYAML as before, the Anthropic Java SDK 2.70.0
with its tree (OkHttp and Okio, the Kotlin standard library and reflection with the JetBrains annotations, Jackson's
`jdk8`, `jsr310` and Kotlin modules, the victools JSON schema generator with classmate and swagger-annotations, Error
Prone annotations, the Standard Webhooks library, `slf4j-api`), JSqlParser 5.4, the MCP Java SDK 2.0.1 with its tree
(`reactor-core`, `reactive-streams`, the networknt JSON schema validator, `itu`) and the SLF4J no-operation provider
`slf4j-nop` (D88: the CLI silences the libraries' logging; nothing else binds SLF4J). JUnit, HSQLDB and the agent
jars are not in it, and neither are `skillroy-cdp` and Playwright (D49): no POM of the modules whose jars are in
`lib/` refers to them. `DistributionNoticesIT` checks the set of jars against `THIRD-PARTY.txt` line by line
(coordinates, SPDX identifier, file name), requires each licence to be one of Apache-2.0, MIT and MIT-0, and
requires the evidence of each licence to be inside the jar (a licence file, the manifest's `Bundle-License` or the
embedded POM) or, failing that, in `THIRD-PARTY.txt`'s attributions with the licence text distributed.

The 36 jars, their licences and what the evidence is (the sizes are those of the jars in the local Maven cache on
2026-10-08):

| Artifact | Version | Licence (SPDX) | Evidence | Obligations when redistributed |
|---|---|---|---|---|
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | Apache-2.0 | POM; JAR `META-INF/LICENSE`, `NOTICE`; manifest `Bundle-License` | Ship licence and NOTICE (they are in the jar) |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | Apache-2.0, with embedded MIT, BSL-1.0, BSD-2-Clause parts (below) | POM; JAR `META-INF/LICENSE`, `NOTICE`, three more licence files; manifest | As above; keep the embedded licence files with the jar |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | Apache-2.0 | POM; JAR `META-INF/LICENSE`, `NOTICE`; manifest | Ship licence and NOTICE (in the jar) |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | 2.22.3 | Apache-2.0 | POM (parent `jackson-dataformats-text`); JAR `META-INF/LICENSE`, `NOTICE`; manifest | Ship licence and NOTICE (in the jar) |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.3 | Apache-2.0 | POM (parent `jackson-modules-java8`); JAR `META-INF/LICENSE`, `NOTICE`; manifest | Ship licence and NOTICE (in the jar) |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.3 | Apache-2.0 | POM (parent `jackson-modules-java8`); JAR `META-INF/LICENSE`, `NOTICE`; manifest | Ship licence and NOTICE (in the jar) |
| `com.fasterxml.jackson.module:jackson-module-kotlin` | 2.22.3 | Apache-2.0 | POM; JAR `META-INF/LICENSE`, `NOTICE`; manifest | Ship licence and NOTICE (in the jar) |
| `org.yaml:snakeyaml` | 2.5 | Apache-2.0 | POM; JAR manifest `Bundle-License` | No LICENSE or NOTICE file in the jar: the Apache-2.0 text (`LICENSE`) and the attribution (`THIRD-PARTY.txt`) are at the top level of the distribution |
| `com.anthropic:anthropic-java` | 2.70.0 | MIT | POM `<name>MIT</name>`; no licence file, no `Bundle-License` | The copyright line and the MIT text travel in `THIRD-PARTY.txt` ("Licence texts") |
| `com.anthropic:anthropic-java-client-okhttp` | 2.70.0 | MIT | POM; no licence file | As above |
| `com.anthropic:anthropic-java-core` | 2.70.0 (37,180,630 bytes) | MIT | POM; no licence file | As above |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | Apache-2.0; its data file `okhttp3/internal/publicsuffix/publicsuffixes.gz` is compiled from the Public Suffix List under MPL-2.0 | POM; the jar has no licence file; `okhttp3/internal/publicsuffix/NOTICE` names the MPL-2.0 file | Apache-2.0 text at the top level; the MPL-2.0 data file is shipped unmodified inside the jar and its source is named in `THIRD-PARTY.txt` (MPL-2.0 §3.2 is met by the notice and the unmodified file) |
| `com.squareup.okio:okio` | 3.6.0 | Apache-2.0 | POM; no licence file | Apache-2.0 text at the top level; attribution in `THIRD-PARTY.txt` |
| `com.squareup.okio:okio-jvm` | 3.6.0 | Apache-2.0 | POM; no licence file | As above |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.1.21 | Apache-2.0 | POM ("The Apache License, Version 2.0"); no licence file | As above |
| `org.jetbrains.kotlin:kotlin-reflect` | 2.1.21 (3,081,305 bytes) | Apache-2.0 | POM; no licence file | As above |
| `org.jetbrains.kotlin:kotlin-stdlib-common` | 1.9.10 (no classes; a transitive leftover the SDK's POM still names) | Apache-2.0 | POM; no licence file | As above |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk7` | 1.9.0 (`module-info` only) | Apache-2.0 | POM; no licence file | As above |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk8` | 1.9.0 (`module-info` only) | Apache-2.0 | POM; no licence file | As above |
| `org.jetbrains:annotations` | 13.0 | Apache-2.0 | POM; no licence file | As above |
| `com.github.victools:jsonschema-generator` | 4.38.0 | Apache-2.0 | POM (parent `jsonschema-generator-parent`); no licence file | As above |
| `com.github.victools:jsonschema-module-jackson` | 4.38.0 | Apache-2.0 | POM (parent); no licence file | As above |
| `com.github.victools:jsonschema-module-swagger-2` | 4.38.0 | Apache-2.0 | POM (parent); no licence file | As above |
| `com.fasterxml:classmate` | 1.7.0 | Apache-2.0 | POM; JAR `META-INF/LICENSE`, `NOTICE`; manifest `Bundle-License` | Ship licence and NOTICE (in the jar) |
| `io.swagger.core.v3:swagger-annotations` | 2.2.50 | Apache-2.0 | POM (parent `swagger-project`); JAR `META-INF/LICENSE`, `NOTICE`; manifest `Bundle-License` | Ship licence and NOTICE (in the jar) |
| `com.google.errorprone:error_prone_annotations` | 2.50.0 | Apache-2.0 ("Apache 2.0") | POM; manifest `Bundle-License`; no licence file | Apache-2.0 text at the top level |
| `com.standardwebhooks:standardwebhooks` | 1.2.0 | MIT ("The MIT License") | POM; no licence file, no `Bundle-License` | The copyright line (Svix) and the MIT text travel in `THIRD-PARTY.txt` |
| `org.slf4j:slf4j-api` | 2.0.16 | MIT | POM (parent `slf4j-parent`); JAR `META-INF/LICENSE.txt`; manifest `Bundle-License` | Ship the licence file (in the jar); the text is also in `THIRD-PARTY.txt` |
| `org.slf4j:slf4j-nop` | 2.0.16 | MIT | POM (parent `slf4j-parent`); JAR `META-INF/LICENSE.txt`; manifest `Bundle-License` | As above |
| `com.github.jsqlparser:jsqlparser` | 5.4 (2,008,413 bytes) | Dual LGPL-2.1 / Apache-2.0; **taken under Apache-2.0** (section 8) | POM (two `<license>` entries); manifest `Bundle-License` names both; no licence file | `THIRD-PARTY.txt` states the choice; the Apache-2.0 text is at the top level |
| `io.modelcontextprotocol.sdk:mcp-core` | 2.0.1 | MIT ("MIT License") | POM; manifest `Bundle-License`; no licence file | The copyright line and the MIT text travel in `THIRD-PARTY.txt` |
| `io.modelcontextprotocol.sdk:mcp-json-jackson2` | 2.0.1 | MIT | POM; manifest `Bundle-License`; no licence file | As above |
| `io.projectreactor:reactor-core` | 3.7.0 (1,876,501 bytes) | Apache-2.0 | POM; no licence file | Apache-2.0 text at the top level; attribution in `THIRD-PARTY.txt` |
| `org.reactivestreams:reactive-streams` | 1.0.4 | MIT-0 | POM (`MIT-0`); no licence file | No attribution required by the licence; the text is in `THIRD-PARTY.txt` all the same |
| `com.networknt:json-schema-validator` | 2.0.4 | Apache-2.0 ("Apache License Version 2.0") | POM; manifest `Bundle-License`; no licence file | Apache-2.0 text at the top level |
| `com.ethlo.time:itu` | 1.14.0 | Apache-2.0 | POM; manifest `Bundle-License`; no licence file | Apache-2.0 text at the top level |

The three licence files that are neither `META-INF/LICENSE` nor `META-INF/NOTICE` (SLF4J's `META-INF/LICENSE.txt`
twice, OkHttp's `okhttp3/internal/publicsuffix/NOTICE`) are listed in `THIRD-PARTY.txt` ("Other licence and notice
files inside the jars"), and the test fails if one of them disappears from its jar.

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

Not inspected inside the Phase 5 jars: whether `anthropic-java-core` (37 MB, generated model types), `kotlin-reflect`
or `reactor-core` embed or shade third-party code beyond what their POMs declare. Their POMs declare no shading and
their manifests no embedded component; a release audit should list the packages inside each (as section 3 does for
the project's own jars) before the first release.

Other places third-party code could be redistributed, and the result:

| Place | Third-party code |
|---|---|
| Agent home (directory and zip) | none (section 3) |
| Project jars published to a Maven repository (the 15 reactor jars audited before Phase 4, `skillroy-data` included) | none: no jar contains an entry outside `ai/skillroy/`, `META-INF/maven`, the manifest, `META-INF/services` and, since 2026-10-02, the project's own `META-INF/LICENSE` and `META-INF/NOTICE`; nothing is shaded |
| The Phase 4 jars (2026-10-07: `skillroy-agent-web` and its `test-jar`, `skillroy-cdp`, `skillroy-sample-web-host`, `skillroy-sample-web-host-agent`, `skillroy-sample-web-host-scenarios`) | none: 155 and 213, 37, 21, 5 and 5 classes, nothing shaded. Besides `ai/skillroy/` they hold only the manifest, `META-INF/maven`, `META-INF/services` files, the project's own `META-INF/LICENSE` and `META-INF/NOTICE` and, in the host jar, its three own pages `webhost/*.html`; the agent module's descriptors and properties file (section 3) and the `test-jar`'s fake descriptors and kit pages are under `ai/skillroy/`. The `test-jar` (the adapter contract kit) holds no vendor class either: its fakes live under the project's own package. `skillroy-cdp` refers to Playwright types and one gson type but does not contain them |
| The Phase 5 jars (2026-10-08: `skillroy-inference`, `skillroy-mcp`) | none: 142 and 22 classes, nothing shaded; besides `ai/skillroy/` they hold only the manifest, `META-INF/maven`, `META-INF/services` (the `ResolverContributor` registration of the inference module) and the project's own `META-INF/LICENSE` and `META-INF/NOTICE`. They refer to the Anthropic SDK, JSqlParser and the MCP SDK types but do not contain them |
| Published POMs | They name third-party dependencies but do not contain them |
| Sample application, sample agent module | HSQLDB is not bundled; it is a separate dependency |
| Test fixtures and resources in the source tree | No vendored third-party files, fonts, images, native libraries or jars were found in the tree (the seven stand-in model recordings and the spike corpora under `spikes/BS6` are the project's own text) |

## 5. All other dependencies: test, provided, sample-only

None of these is in the agent home or the CLI distribution. The Playwright set (compile scope of the optional
`skillroy-cdp`) and the JCEF set (the sample web host modules) are in no distribution at all.

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
| `org.opentest4j:opentest4j` | 1.3.0 | test; compile where `junit-jupiter-api` is compile, and in `skillroy-cdp` (through `playwright`) | Apache-2.0 | POM; JAR `META-INF/LICENSE` |
| `org.apiguardian:apiguardian-api` | 1.1.2 | test; compile where `junit-jupiter-api` is compile | Apache-2.0 | POM; JAR `META-INF/LICENSE` |
| `org.hsqldb:hsqldb` (classifier `jdk8`) | 2.7.4 | compile in `skillroy-sample-lending-app`; provided in `skillroy-sample-lending-agent`; test in `skillroy-protocol`, `skillroy-agent-data`, `skillroy-data`, `skillroy-scenario` (these three since Phase 2) and `skillroy-sample-lending-scenarios` | BSD-style ("HSQLDB License, a BSD open source license"); SPDX not determined locally | POM only; the jar has no licence file |
| `com.microsoft.playwright:playwright` | 1.63.0 | compile, `skillroy-cdp`; test, through it, in `skillroy-sample-web-host-scenarios`; in no distribution (D49) | Apache-2.0 | POM (inherited from the parent `com.microsoft.playwright:parent-pom:1.63.0`); the jar has no licence file |
| `com.microsoft.playwright:driver` | 1.63.0 | compile, through `playwright` (`skillroy-cdp`) | Apache-2.0; it carries the `playwright-core` package and its bundled npm packages, whose texts are not enumerated here | POM (parent `parent-pom`); JAR `driver/package/LICENSE`, `NOTICE`, `ThirdPartyNotices.txt` and the sidecars `driver/package/lib/utilsBundle.js.LICENSE`, `serverRegistry.js.LICENSE`, `webp_codec.LICENSE` |
| `com.microsoft.playwright:driver-bundle` | 1.63.0 | compile, through `playwright` (`skillroy-cdp`); 203,821,698 bytes | Apache-2.0 for the artifact (parent POM); the five Node.js binaries inside carry Node's own notices (note below) | POM (parent `parent-pom`); JAR `driver/mac-arm64/LICENSE`, `driver/mac/LICENSE`, `driver/linux-arm64/LICENSE`, `driver/linux/LICENSE`, `driver/win32_x64/LICENSE` |
| `com.anthropic:anthropic-java` (Phase 5, 2026-10-08) | 2.70.0 | compile, `skillroy-inference`; the CLI distribution once the CLI depends on it (wave 2b); 37,180,630 bytes for `anthropic-java-core`, 46,327,621 bytes for the whole new set of 24 jars | MIT (the three artifacts `anthropic-java`, `anthropic-java-client-okhttp`, `anthropic-java-core`) | POM `<name>MIT</name>` of each; no licence file in the jars, so `THIRD-PARTY.txt` must carry the MIT text and copyright line |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8`, `jackson-datatype-jsr310`; `com.fasterxml.jackson.module:jackson-module-kotlin` | 2.22.3 (aligned by the `jackson-bom` import of the root POM) | runtime, through the Anthropic SDK | Apache-2.0 | POM chain `jackson-modules-java8` > `jackson-base`; JAR `LICENSE`, `NOTICE` |
| `org.jetbrains.kotlin:kotlin-stdlib`, `kotlin-reflect` | 2.1.21 (managed to one version in the root POM; `kotlin-stdlib-jdk7`/`-jdk8` 1.9.0 hold `module-info` only, `kotlin-stdlib-common` 1.9.10 no classes) | compile / runtime, through the Anthropic SDK and Jackson's Kotlin module | Apache-2.0 | POM |
| `org.jetbrains:annotations` | 13.0 | compile, through Kotlin | Apache-2.0 | POM |
| `com.google.errorprone:error_prone_annotations` | 2.50.0 | compile, through the Anthropic SDK | Apache-2.0 ("Apache 2.0") | POM; manifest `Bundle-License` |
| `com.standardwebhooks:standardwebhooks` | 1.2.0 | compile, through the Anthropic SDK | MIT ("The MIT License") | POM |
| `io.swagger.core.v3:swagger-annotations` | 2.2.50 | compile, through the Anthropic SDK | Apache-2.0 | POM parent `swagger-project`; JAR `LICENSE`, `NOTICE`; manifest `Bundle-License` |
| `com.github.victools:jsonschema-generator`, `jsonschema-module-jackson`, `jsonschema-module-swagger-2` | 4.38.0 | runtime, through the Anthropic SDK | Apache-2.0 | POM parent `jsonschema-generator-parent` |
| `com.fasterxml:classmate` | 1.7.0 | runtime, through victools | Apache-2.0 | POM; JAR `LICENSE`, `NOTICE` |
| `org.slf4j:slf4j-api` | 2.0.16 | runtime, through victools (and compile through the MCP SDK in wave 2b) | MIT | POM chain `slf4j-parent` > `slf4j-bom`; JAR `META-INF/LICENSE.txt` |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | runtime, through the Anthropic SDK | Apache-2.0; its `publicsuffixes.gz` is compiled from the Public Suffix List under MPL-2.0 | POM; JAR `okhttp3/internal/publicsuffix/NOTICE` |
| `com.squareup.okio:okio`, `okio-jvm` | 3.6.0 | runtime, through OkHttp | Apache-2.0 | POM |
| `com.github.jsqlparser:jsqlparser` (Phase 5, 2026-10-08) | 5.4 | compile, `skillroy-inference`; the CLI distribution (wave 2b); no dependencies; 2,008,413 bytes | dual LGPL-2.1 / Apache-2.0 (two `<license>` entries in its POM; every source header says "Dual licensed under GNU LGPL 2.1 or Apache License 2.0"); the project takes Apache-2.0 | POM; manifest `Bundle-License` names both; no licence file in the jar |
| `io.modelcontextprotocol.sdk:mcp-core`, `mcp-json-jackson2` (Phase 5, 2026-10-08) | 2.0.1 | compile, `skillroy-mcp`; the CLI distribution (wave 2b) | MIT (both; no licence file in the jars, so `THIRD-PARTY.txt` must carry the MIT text) | POM; manifest `Bundle-License` |
| `io.projectreactor:reactor-core` | 3.7.0 | compile, through `mcp-core` | Apache-2.0 | POM; no licence file in the jar |
| `org.reactivestreams:reactive-streams` | 1.0.4 | compile, through `reactor-core` | MIT-0 | POM; no licence file in the jar |
| `com.networknt:json-schema-validator` | 2.0.4 | compile, through `mcp-json-jackson2` | Apache-2.0 | POM; manifest `Bundle-License` |
| `com.ethlo.time:itu` | 1.14.0 | compile, through `json-schema-validator` | Apache-2.0 | POM; manifest `Bundle-License` |
| `com.google.code.gson:gson` | 2.14.0 (Playwright set); 2.11.0 (JCEF set) | compile: 2.14.0 through `playwright` in `skillroy-cdp`; 2.11.0 through `jcefmaven` in the web host modules | Apache-2.0 | POM |
| `com.google.errorprone:error_prone_annotations` | 2.48.0 (through gson 2.14.0); 2.27.0 (through gson 2.11.0) | compile, in the same modules as the `gson` version it comes with | Apache-2.0 (the POMs say "Apache 2.0") | POM |
| `org.jspecify:jspecify` | 1.0.1 | compile, through `playwright` (`skillroy-cdp`) | Apache-2.0 | POM |
| `me.friwi:jcefmaven` | 152.0.6 | compile, `skillroy-sample-web-host` (profile `jcef`); provided in `skillroy-sample-web-host-agent` and test in `skillroy-sample-web-host-scenarios`, through the host module; in no distribution | Apache-2.0 | POM ("Apache-2.0 License"); the jar has no licence file |
| `me.friwi:jcef-api` | `jcef-17e805a+cef-152.0.6+g708dc14+chromium-152.0.7977.83` (the JCEF tag) | compile, through `jcefmaven` (same modules and scopes) | BSD-3-Clause (the java-cef and CEF text; the POM says "BSD License") | POM; JAR `LICENSE.txt` (the three-clause text, copyright Marshall A. Greenblatt and Google Inc.) |
| `me.friwi:jcef-natives-linux-amd64` | the JCEF tag | test, `skillroy-sample-web-host` and `skillroy-sample-web-host-scenarios`, when `skillroy.jcef.platform` is `linux-amd64` (the default); 152,141,475 bytes | BSD-3-Clause for java-cef and CEF; JogAmp's licences for the JOGL and GlueGen natives (below); Chromium's own third-party notices: none found in the bundle, not verified here | POM ("BSD License"); notices inside the tarball the jar carries: `LICENSE.txt`, `jogl.LICENSE.txt`, `gluegen.LICENSE.txt` |
| `me.friwi:jcef-natives-linux-arm64` | the JCEF tag | test, `skillroy-sample-web-host` and `skillroy-sample-web-host-scenarios`, when `skillroy.jcef.platform` is `linux-arm64`; 167,953,639 bytes | as the amd64 artifact | as the amd64 artifact (its three notice files are byte-identical to those of the amd64 artifact) |
| `me.friwi:jogl-all` | v2.4.0 | compile, through `jcef-api` | BSD-2-Clause, BSD-3-Clause, SGI Free Software License B, Apache-2.0, Apache-1.1 and Ubuntu Font Licence 1.0, as the POM lists them, for different components; SPDX not determined locally for the SGI and Ubuntu entries | POM; the component list is `jogl.LICENSE.txt` inside the natives; the jar has no licence file |
| `me.friwi:gluegen-rt` | v2.4.0 | compile, through `jcef-api` | BSD-2-Clause, BSD-3-Clause and BSD-4-Clause, as the POM lists them, for different components | POM; the component list is `gluegen.LICENSE.txt` inside the natives; the jar has no licence file |
| `org.apache.commons:commons-compress` | 1.28.0 | compile, through `jcefmaven` | Apache-2.0 | POM chain `commons-parent` > `apache`; JAR `META-INF/LICENSE.txt`, `NOTICE.txt` |
| `commons-codec:commons-codec` | 1.19.0 | compile, through `commons-compress` | Apache-2.0 | same |
| `commons-io:commons-io` | 2.20.0 | compile, through `commons-compress` | Apache-2.0 | same |
| `org.apache.commons:commons-lang3` | 3.18.0 | compile, through `commons-compress` | Apache-2.0 | same |

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
- Playwright enters the build only as the compile dependency of the optional `skillroy-cdp` (D49,
  `docs/design/phase4-contracts.md` section 14). Its `playwright` POM also names Java-WebSocket, slf4j-simple and two
  JUnit artifacts, all at test scope in the parent POM, so Maven does not pass them on. The driver bundle holds the
  Node.js binary of five platforms (`mac-arm64`, `mac`, `linux-arm64`, `linux`, `win32_x64`), each beside a `LICENSE`
  that opens with Node's MIT-style grant and goes on with the notices of Node's own components (2,946 lines in the one
  file the four Unix-like platforms share; `win32_x64` has another). Those notices include GPL texts with the Autoconf
  exception for two build-time files of ICU4C (`aclocal.m4`, `config.guess`); the licence families in them were not
  enumerated further. The `driver` jar carries the `playwright-core` package with an Apache-2.0 `LICENSE`, a `NOTICE`
  (code derived from Puppeteer, Apache-2.0) and a `ThirdPartyNotices.txt` that points to the sidecar `.LICENSE` files
  of its inlined npm packages (the `utilsBundle.js` sidecar lists 79; texts seen: MIT, ISC, BSD 3-Clause and the Blue
  Oak Model License) and of a WebP codec; they were not enumerated either.
- Two artifacts of the Playwright and JCEF sets appear in two versions: `gson` 2.14.0 and 2.11.0, and
  `error_prone_annotations` 2.48.0 and 2.27.0. They sit in different modules (`skillroy-cdp`; the web host modules),
  so each module has one version. The scenarios module of the web host sample has both sets on its test class path;
  its POM declares `skillroy-cdp` before the web host, so that Maven's mediation (equal depth, the first declaration
  wins) gives it `gson` 2.14.0 and, through it, `error_prone_annotations` 2.48.0. That is read from the POM, not from a
  `dependency:list` run.
- The JCEF set belongs to the sample web host (profile `jcef`). `skillroy.jcef.platform` selects one natives artifact
  (default `linux-amd64`); only the two Linux artifacts were inspected, the natives of other platforms were not. In
  each natives jar the notices are not jar entries but sit inside the nested tarball
  `jcef-natives-<platform>-<JCEF tag>.tar.gz`: `LICENSE.txt` (CEF's three-clause BSD text, byte-identical to
  `LICENSE.txt` in the `jcef-api` jar), `jogl.LICENSE.txt` and `gluegen.LICENSE.txt` (JogAmp's component lists), a
  `README.txt` and a `build_meta.json`. No file with Chromium's third-party notices is among them; whether the bundle
  delivers them some other way is not verified here.
- JOGL and GlueGen: each POM lists several licences, and the licence file for each in the natives assigns them to
  components (JogAmp's own code under BSD-2-Clause; imported parts under BSD-3-Clause, the SGI licence, Apache-2.0 and
  Apache-1.1; bundled fonts under the Ubuntu Font Licence 1.0; for GlueGen one BSD-4-Clause part). The JOGL file also
  names Khronos and NVIDIA header files and an optional part from Oculus VR under that company's own licence, which
  the POM does not list; their terms were not assessed here. The licences are not alternatives, so neither artifact is
  dual-licensed in the sense of section 1.

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
one `apt-get install --no-install-recommends` of ten Debian packages (since 2026-10-06 twenty-five: the fifteen
run-time libraries of an embedded Chromium, second table below), then `ENV LANG=C.UTF-8`. The image is used
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

Added on 2026-10-06 for spike BS8 and Phase 4 (the libraries `ldd` reported missing for `libcef.so`, `libjcef.so` and
`jcef_helper` of jcefmaven 152.0.6; versions as installed in the image built that day; licences as the Ubuntu archive
states them for these well-known libraries, each to be confirmed from its `copyright` file before the image is ever
published):

| Package | Version inspected | Licence as commonly stated | SPDX |
|---|---|---|---|
| `libnss3` | 2:3.120-1ubuntu2.1 | MPL-2.0 | not determined locally |
| `libnspr4` | 2:4.38.2-1ubuntu1 | MPL-2.0 | not determined locally |
| `libatk1.0-0t64` | 2.60.4-0ubuntu0.1 | LGPL-2.0+ | not determined locally |
| `libatk-bridge2.0-0t64` | 2.60.4-0ubuntu0.1 | LGPL-2.0+ | not determined locally |
| `libatspi2.0-0t64` | 2.60.4-0ubuntu0.1 | LGPL-2.0+ | not determined locally |
| `libcups2t64` | 2.4.16-1ubuntu1.3 | Apache-2.0 (with the GPL/LGPL exception notices of CUPS) | not determined locally |
| `libdbus-1-3` | 1.16.2-2ubuntu4 | AFL-2.1 or GPL-2.0+ | not determined locally |
| `libglib2.0-0t64` | 2.88.0-1ubuntu0.1 | LGPL-2.1+ | not determined locally |
| `libcairo2` | 1.18.4-3 | LGPL-2.1 or MPL-1.1 | not determined locally |
| `libpango-1.0-0` | 1.57.0-1 | LGPL-2.0+ | not determined locally |
| `libasound2t64` | 1.2.15.3-1ubuntu1.1 | LGPL-2.1+ | not determined locally |
| `libxcomposite1` | 1:0.4.6-1build1 | permissive X.Org style | not determined locally |
| `libxdamage1` | 1:1.1.7-1 | permissive X.Org style | not determined locally |
| `libxfixes3` | 1:6.0.0-2build2 | permissive X.Org style | not determined locally |
| `libxkbcommon0` | 1.13.1-1 | MIT/X11 | not determined locally |

With them the image holds 212 packages (dpkg). They are test infrastructure: nothing of them is linked into a project
artifact, and the agent never loads them (the target application's own embedded browser does).

Four of the original ten (`libfreetype6`, `fontconfig`, `fonts-dejavu-core`, `procps`) were already present in the base image.
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
| JSqlParser 5.4 | `SqlGuard` of Phase 5 | declared 2026-10-08 (wave 2a), dual LGPL-2.1 / Apache-2.0, the project takes Apache-2.0: moved to §5 | done |
| MCP Java SDK | `skillroy-mcp` of Phase 5 | declared 2026-10-08 (wave 2b) as `mcp-core` and `mcp-json-jackson2` 2.0.1, MIT: moved to §5 with their tree | done |
| Anthropic Java SDK | the Claude gateway of Phase 5 | declared 2026-10-08 (wave 2a) at 2.70.0, MIT: moved to §5 with its tree | done |
| JxBrowser | Reflective adapter inside `skillroy-agent-web` (B9; Phase 4) | Commercial licence needed at run time (B9); the base ships no vendor code | the adapter ships without vendor code; JxBrowser itself is in no build and not audited; never bundled |
| snakeyaml-engine, victools jsonschema-generator | Controller schema and YAML (design table) | not audited | not audited |
| Jetty 12 | Servlet transport for a remote MCP controller | not audited | not audited |
| OpenTelemetry Java | Export in Phase 6 (B11) | not audited | not audited |

Moved to section 5 on 2026-10-07 (both are built in Phase 4): Playwright Java and JCEF (jcefmaven 152.0.6 and its
transitive artifacts).

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
   `mvn -o -B verify -DskipTests dependency:tree dependency:list` (run in `<copy>`; add `-Pjcef` for the web host
   samples, which needs the natives of the chosen platform in the local Maven repository). Drop `-o` only if an
   artifact is missing from the local Maven repository.
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

The Phase 4 entries were added on 2026-10-07 from the module POMs and `dependency:list`, which was run offline on
scratch POMs that repeat each new module's third-party declarations, because the reactor siblings were not installed
in the local Maven repository. Licences were read with the snippet in step 6, the notices of the Playwright jars with
`unzip -p`, those of the JCEF natives from the tarball inside each jar (`unzip -p <jar> <tarball> | tar -tz`), and the
Phase 4 project jars that existed that day were inspected as in steps 4 and 5. Later that day the agent home's seventh
jar, as the final tree's native build produced it, was inspected as in step 5 (its constant pools with `javap -v`, and
its class files searched for the vendors' package names), the other Phase 4 jars of the final tree by their entry
lists, and the web host scenarios module's scopes were read from its POM.

To repeat: run steps 1 to 7 on a clean copy and compare the tables here with the output. The dependency versions
are managed in the root POM (`jackson.version`, `hsqldb.version`, `junit.version`, and the plugin version
properties; since Phase 4 also `jcefmaven.version`, `jcef.tag` and `skillroy.jcef.platform` in the profile `jcef`,
while `playwright.version` is in the `skillroy-cdp` POM), so a version change there is the usual trigger for a
re-audit, as is any new module that declares a dependency, a change to either assembly descriptor, or a change to
`ci/Dockerfile`.
