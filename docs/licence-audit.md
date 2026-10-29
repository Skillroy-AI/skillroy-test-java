# Dependency licence audit

Intended licence of the project: **Apache-2.0**.

This is an engineering inventory built from primary sources available locally: the `<licenses>` element of each
artifact's POM (or parent POM) in the local Maven repository, licence and notice files inside jars, and package
copyright files inside the CI test container image. It is not legal advice. Where no local primary source gave an
SPDX identifier, the tables say "not determined locally".

## 1. Summary

**Result: everything the project distributes, bundles or depends on is compatible with distributing the project
under Apache-2.0.** No dependency carries a copyleft licence that reaches a distributed artifact.

- **The agent contains no third-party code, with one carve-out:** its optional hooks module
  (`optional/skillroy-agent-hooks.jar`, loaded only when the agent option `optionalModules` names it) carries a
  relocated copy of Byte Buddy (Apache-2.0) and the ASM it bundles (BSD-3-Clause), whose notices travel inside
  that jar under `META-INF/third-party/`. The agent home (`skillroy-agent-dist`) holds **eleven jars** (thirteen
  files including `LICENSE` and `NOTICE`): `skillroy-agent-bootstrap.jar`, `skillroy-agent-spy.jar`, three jars in
  `lib/`, three in `modules/`, and three in `optional/` (`skillroy-agent-hooks.jar`,
  `skillroy-agent-byteman.jar` and `skillroy-agent-byteman-sys.jar`). Every entry in ten of those jars is project
  code or resources; `skillroy-agent-web` reaches JCEF and JxBrowser purely by reflection, and the two Byteman
  jars contain no Byteman classes (Byteman is supplied by the user at runtime via `byteman.jar` and is never
  bundled). `DistributionContentIT`, `HomeConstantPoolIT` and `OptionalModulesIT` enforce these invariants on
  every build.
- **The CLI distribution is the only other place third-party code is redistributed:** `controller/skillroy-cli`
  assembles 9 project jars and **47 third-party runtime jars** (38 Apache-2.0, 8 MIT, 1 MIT-0): the Jackson YAML
  and datatype modules, SnakeYAML, the Anthropic Java SDK with its OkHttp/Kotlin/victools tree, JSqlParser, the
  MCP Java SDK with its Reactor/JSON-Schema tree, `slf4j-nop`, and the OpenTelemetry Java SDK/OTLP-HTTP set
  (§4). `THIRD-PARTY.txt` carries all required attributions and MIT/MIT-0 licence texts, and
  `DistributionNoticesIT` verifies `lib/` against `THIRD-PARTY.txt` on every build.
- **Playwright and JCEF are in no distribution.** Playwright is a compile dependency of the optional
  `skillroy-cdp` module alone. JCEF belongs only to the sample web host modules behind the `jcef` Maven profile.
- **JUnit 5 (EPL-2.0) is test-only** across the reactor, except in `skillroy-junit`, which declares
  `junit-jupiter-api` at compile scope for consumers without redistributing it.
- **HSQLDB (BSD-style licence) is used only by the sample lending desk and tests** and is not bundled in any
  distribution.
- **All 12 pinned Maven build plugins and Apache Maven itself are Apache-2.0** and are not redistributed (§6).
- **Dual-licensed artifacts:** `com.github.jsqlparser:jsqlparser` 5.4 (LGPL-2.1 or Apache-2.0) is taken under
  Apache-2.0, as stated in `THIRD-PARTY.txt`.
- **The Linux CI image (`ci/Dockerfile`) is used for testing only and is not redistributed** (§7).

### Ongoing compliance rules

- Every built jar, the agent home and the CLI distribution carry the root `LICENSE` and `NOTICE` (enforced by
  `DistributionContentIT` and `DistributionNoticesIT`).
- Never repack or strip `jackson-core`, so its embedded permissive component notices remain intact inside
  `META-INF/`.
- Keep JUnit, HSQLDB, Byteman, `skillroy-cdp`/Playwright, and JCEF out of the CLI and agent-home assemblies unless
  their third-party notices are audited and added to `THIRD-PARTY.txt` first.

## 2. Scope and conventions

The reactor comprises 32 projects when built with `-Pjcef` (29 in the default build without `-Pjcef`):

- Root aggregator: `skillroy-test-java-parent`
- Agent side (`--release 8`): `skillroy-protocol`, `skillroy-agent-api`, `skillroy-agent-bootstrap`,
  `skillroy-agent-core`, `skillroy-agent-swing`, `skillroy-agent-data`, `skillroy-agent-web`,
  `skillroy-agent-spy`, `skillroy-agent-bytebuddy`, `skillroy-agent-hooks`, `skillroy-agent-byteman-sys`,
  `skillroy-agent-byteman`, `skillroy-agent-dist`
- Controller side (`--release 25`): `skillroy-controller`, `skillroy-data`, `skillroy-scenario`,
  `skillroy-inference`, `skillroy-mcp`, `skillroy-junit`, `skillroy-cli`, `skillroy-cdp`, `skillroy-telemetry`,
  `skillroy-vision`
- Lending desk sample: `skillroy-sample-lending-app` (`8`), `skillroy-sample-lending-agent` (`8`),
  `skillroy-sample-lending-pack` (`25`), `skillroy-sample-lending-pack-branch` (`25`),
  `skillroy-sample-lending-scenarios` (`25`)
- Web host sample (profile `jcef`): `skillroy-sample-web-host` (`8`), `skillroy-sample-web-host-agent` (`8`),
  `skillroy-sample-web-host-scenarios` (`25`)

Conventions:
- Project artifacts (`ai.skillroy:*`) are the project's own code and are not listed as third-party.
- Evidence cites **POM** (`<licenses>` in the artifact's or parent's POM), **JAR** (licence file or manifest entry
  inside the jar), **JDK**, or **IMAGE** (`/usr/share/doc/*/copyright` inside the test container).

### Third-party dependencies by module

- **JUnit set:** `junit-jupiter` 5.14.4 and transitive platform/assertion artifacts (§5).
- **Jackson set:** `jackson-annotations`, `jackson-core`, `jackson-databind`, `jackson-dataformat-yaml`,
  `snakeyaml` (§4).
- **Playwright set:** `playwright` 1.63.0, `driver`, `driver-bundle`, `gson` 2.14.0, `error_prone_annotations`
  2.48.0, `jspecify`, `opentest4j` (§5).
- **JCEF set:** `jcefmaven` 152.0.6, `jcef-api`, `jogl-all`, `gluegen-rt`, `commons-compress`, `commons-codec`,
  `commons-io`, `commons-lang3`, `gson` 2.11.0, `error_prone_annotations` 2.27.0 (§5).

| Module | Compile (third-party) | Provided | Test |
|---|---|---|---|
| `skillroy-protocol` | none | none | JUnit set, HSQLDB |
| `skillroy-agent-api`, `-agent-bootstrap`, `-agent-core`, `-agent-swing`, `-agent-spy`, `-agent-dist` | none | none | JUnit set |
| `skillroy-agent-bytebuddy`, `skillroy-agent-hooks` | Byte Buddy 1.18.14 (shaded and relocated into `skillroy-agent-hooks`) | none | JUnit set |
| `skillroy-agent-data` | none | none | JUnit set, HSQLDB |
| `skillroy-agent-web` | none | none | JUnit set |
| `skillroy-agent-byteman-sys` | none | Byteman 4.0.27 (compiled against it; nothing bundled) | JUnit set |
| `skillroy-agent-byteman` | none | project modules only | JUnit set, Byteman 4.0.27 |
| `skillroy-controller` | none | none | JUnit set |
| `skillroy-data` | Jackson set | none | JUnit set, HSQLDB |
| `skillroy-scenario` | Jackson set (via `skillroy-data`) | none | JUnit set, HSQLDB |
| `skillroy-inference` | Anthropic Java SDK 2.70.0 set, JSqlParser 5.4, Jackson set | none | JUnit set, HSQLDB |
| `skillroy-mcp` | MCP Java SDK 2.0.1 (`mcp-core`, `mcp-json-jackson2`) + Reactor/JSON-Schema tree, inference set | none | JUnit set |
| `skillroy-junit` | Jackson set, `junit-jupiter-api` | none | JUnit set, `junit-platform-launcher` |
| `skillroy-telemetry` | OpenTelemetry 1.66.0 set (§4; OkHttp sender excluded, no autoconfigure), Jackson set | none | JUnit set, HSQLDB, `opentelemetry-sdk-testing` 1.66.0 |
| `skillroy-vision` | none of its own (uses JDK `java.awt.image` / `javax.imageio`, plus `skillroy-inference` and `skillroy-scenario`) | none | JUnit set |
| `skillroy-cli` | Jackson, inference, MCP, telemetry and vision sets, plus `slf4j-nop` 2.0.16 | none | JUnit set |
| `skillroy-cdp` | Playwright set, Jackson set | none | JUnit set |
| `skillroy-sample-lending-app` | HSQLDB | none | JUnit set |
| `skillroy-sample-lending-agent` | none | HSQLDB (via sample app) | JUnit set |
| `skillroy-sample-lending-pack`, `-pack-branch` | Jackson set | none | JUnit set |
| `skillroy-sample-lending-scenarios` | Jackson set, `junit-jupiter-api` (via `skillroy-junit`) | none | JUnit set, HSQLDB, OpenTelemetry set, Byteman 4.0.27 |
| `skillroy-sample-web-host` (`jcef`) | JCEF set | none | JUnit set, `jcef-natives-<platform>` |
| `skillroy-sample-web-host-agent` (`jcef`) | none | JCEF set (via web host) | JUnit set |
| `skillroy-sample-web-host-scenarios` (`jcef`) | Jackson set | none | JUnit set, Playwright set, JCEF set + natives |

## 3. What ships inside a target JVM

`skillroy-agent-dist` builds the agent home from `agent-home-layout.xml`:

| File in the agent home | Content | Third-party code |
|---|---|---|
| `LICENSE`, `NOTICE` | Root project licence and notice files | none |
| `skillroy-agent-bootstrap.jar` | `ai.skillroy.testjava.agent.bootstrap` classes and agent manifest | none |
| `skillroy-agent-spy.jar` | `Spy`, `Spy$Handler`, `Spy$Stub`, manifest, `META-INF/LICENSE` and `NOTICE` only (appended to bootstrap path only when hooks install) | none |
| `lib/skillroy-protocol.jar` | Shared wire DTOs, JSON codec, HMAC and SQL helpers | none |
| `lib/skillroy-agent-api.jar` | Agent SPIs (`ai.skillroy.testjava.agent.api.*`) | none |
| `lib/skillroy-agent-core.jar` | HTTP/1.1 control server, dispatcher, SSE ring, registries, safety guards | none |
| `modules/skillroy-agent-swing.jar` | Swing tree, input rungs, capture, hit-test, routed/Robot pointer | none |
| `modules/skillroy-agent-data.jar` | `data.query` over `DataSourceProvider` | none |
| `modules/skillroy-agent-web.jar` | Generic `web.*` primitives and reflective `jcef` / `jxbrowser` descriptors | none |
| `optional/skillroy-agent-hooks.jar` | Hook engine plus relocated `net.bytebuddy:byte-buddy:1.18.14` (and bundled ASM 9.10.1) under `ai.skillroy.testjava.agent.hooks.shaded.bytebuddy`; notices under `META-INF/third-party/` | **Yes: Byte Buddy (Apache-2.0) and ASM (BSD-3-Clause), shaded & relocated** |
| `optional/skillroy-agent-byteman.jar` | Optional in-process Byteman bridge (loads user-supplied `byteman.jar` by reflection) | none |
| `optional/skillroy-agent-byteman-sys.jar` | `RuleManager` and curated `RuleHelper` appended to system class path when Byteman loads | none |

`ShadedContentIT`, `DistributionContentIT`, `HomeConstantPoolIT`, `SysConstantPoolTest` and `OptionalModulesIT`
verify entry paths, constant-pool references and optional-module loading behavior automatically. A consumer who
wants zero third-party bytecode in the agent home can delete `optional/skillroy-agent-hooks.jar`.

## 4. Redistributed third-party artifacts (`skillroy-cli`)

`skillroy-cli` builds `skillroy-cli.zip` from `cli-layout.xml`, containing `bin/skillroy`, `bin/skillroy.bat`,
`LICENSE`, `NOTICE`, `THIRD-PARTY.txt`, 9 project jars (`cli`, `controller`, `data`, `inference`, `mcp`,
`protocol`, `scenario`, `telemetry`, `vision`), and the **47 third-party runtime jars** listed below.

### OpenTelemetry set (11 jars, all Apache-2.0)

| Artifact | Version | Licence (SPDX) | Evidence |
|---|---|---|---|
| `io.opentelemetry:opentelemetry-api` | 1.66.0 | Apache-2.0 | POM; attribution in `THIRD-PARTY.txt` |
| `io.opentelemetry:opentelemetry-common` | 1.66.0 | Apache-2.0 | POM; attribution in `THIRD-PARTY.txt` |
| `io.opentelemetry:opentelemetry-context` | 1.66.0 | Apache-2.0 | POM; attribution in `THIRD-PARTY.txt` |
| `io.opentelemetry:opentelemetry-exporter-common` | 1.66.0 | Apache-2.0 | POM; attribution in `THIRD-PARTY.txt` |
| `io.opentelemetry:opentelemetry-exporter-otlp` | 1.66.0 | Apache-2.0 | POM; attribution in `THIRD-PARTY.txt` |
| `io.opentelemetry:opentelemetry-exporter-otlp-common` | 1.66.0 | Apache-2.0 | POM; attribution in `THIRD-PARTY.txt` |
| `io.opentelemetry:opentelemetry-exporter-sender-jdk` | 1.66.0 | Apache-2.0 | POM; attribution in `THIRD-PARTY.txt` |
| `io.opentelemetry:opentelemetry-sdk-common` | 1.66.0 | Apache-2.0 | POM; attribution in `THIRD-PARTY.txt` |
| `io.opentelemetry:opentelemetry-sdk-logs` | 1.66.0 | Apache-2.0 | POM; attribution in `THIRD-PARTY.txt` |
| `io.opentelemetry:opentelemetry-sdk-metrics` | 1.66.0 | Apache-2.0 | POM; attribution in `THIRD-PARTY.txt` |
| `io.opentelemetry:opentelemetry-sdk-trace` | 1.66.0 | Apache-2.0 | POM; attribution in `THIRD-PARTY.txt` |

### Core, inference and MCP set (36 jars: 27 Apache-2.0, 8 MIT, 1 MIT-0)

| Artifact | Version | Licence (SPDX) | Evidence | Obligations when redistributed |
|---|---|---|---|---|
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | Apache-2.0 | POM; JAR `META-INF/LICENSE`, `NOTICE`; manifest | Ship licence and NOTICE (in jar) |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.3 | Apache-2.0 (+ embedded MIT, BSL-1.0, BSD-2-Clause parts below) | POM; JAR `META-INF/LICENSE`, `NOTICE`, embedded licence files | Keep jar unmodified with embedded licence files |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | Apache-2.0 | POM; JAR `META-INF/LICENSE`, `NOTICE`; manifest | Ship licence and NOTICE (in jar) |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | 2.22.3 | Apache-2.0 | POM; JAR `META-INF/LICENSE`, `NOTICE`; manifest | Ship licence and NOTICE (in jar) |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.3 | Apache-2.0 | POM; JAR `META-INF/LICENSE`, `NOTICE`; manifest | Ship licence and NOTICE (in jar) |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.3 | Apache-2.0 | POM; JAR `META-INF/LICENSE`, `NOTICE`; manifest | Ship licence and NOTICE (in jar) |
| `com.fasterxml.jackson.module:jackson-module-kotlin` | 2.22.3 | Apache-2.0 | POM; JAR `META-INF/LICENSE`, `NOTICE`; manifest | Ship licence and NOTICE (in jar) |
| `org.yaml:snakeyaml` | 2.5 | Apache-2.0 | POM; JAR manifest `Bundle-License` | Top-level `LICENSE` and `THIRD-PARTY.txt` attribution |
| `com.anthropic:anthropic-java` | 2.70.0 | MIT | POM | Copyright and MIT text in `THIRD-PARTY.txt` |
| `com.anthropic:anthropic-java-client-okhttp` | 2.70.0 | MIT | POM | Copyright and MIT text in `THIRD-PARTY.txt` |
| `com.anthropic:anthropic-java-core` | 2.70.0 | MIT | POM | Copyright and MIT text in `THIRD-PARTY.txt` |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | Apache-2.0 (`publicsuffixes.gz` data under MPL-2.0) | POM; JAR `okhttp3/internal/publicsuffix/NOTICE` | Top-level `LICENSE`; unmodified MPL-2.0 data file + notice in `THIRD-PARTY.txt` |
| `com.squareup.okio:okio` | 3.6.0 | Apache-2.0 | POM | Top-level `LICENSE` and `THIRD-PARTY.txt` |
| `com.squareup.okio:okio-jvm` | 3.6.0 | Apache-2.0 | POM | Top-level `LICENSE` and `THIRD-PARTY.txt` |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.1.21 | Apache-2.0 | POM | Top-level `LICENSE` and `THIRD-PARTY.txt` |
| `org.jetbrains.kotlin:kotlin-reflect` | 2.1.21 | Apache-2.0 | POM | Top-level `LICENSE` and `THIRD-PARTY.txt` |
| `org.jetbrains.kotlin:kotlin-stdlib-common` | 1.9.10 | Apache-2.0 | POM | Top-level `LICENSE` and `THIRD-PARTY.txt` |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk7` | 1.9.0 | Apache-2.0 | POM | Top-level `LICENSE` and `THIRD-PARTY.txt` |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk8` | 1.9.0 | Apache-2.0 | POM | Top-level `LICENSE` and `THIRD-PARTY.txt` |
| `org.jetbrains:annotations` | 13.0 | Apache-2.0 | POM | Top-level `LICENSE` and `THIRD-PARTY.txt` |
| `com.github.victools:jsonschema-generator` | 4.38.0 | Apache-2.0 | POM | Top-level `LICENSE` and `THIRD-PARTY.txt` |
| `com.github.victools:jsonschema-module-jackson` | 4.38.0 | Apache-2.0 | POM | Top-level `LICENSE` and `THIRD-PARTY.txt` |
| `com.github.victools:jsonschema-module-swagger-2` | 4.38.0 | Apache-2.0 | POM | Top-level `LICENSE` and `THIRD-PARTY.txt` |
| `com.fasterxml:classmate` | 1.7.0 | Apache-2.0 | POM; JAR `META-INF/LICENSE`, `NOTICE`; manifest | Ship licence and NOTICE (in jar) |
| `io.swagger.core.v3:swagger-annotations` | 2.2.50 | Apache-2.0 | POM; JAR `META-INF/LICENSE`, `NOTICE`; manifest | Ship licence and NOTICE (in jar) |
| `com.google.errorprone:error_prone_annotations` | 2.50.0 | Apache-2.0 | POM; manifest `Bundle-License` | Top-level `LICENSE` |
| `com.standardwebhooks:standardwebhooks` | 1.2.0 | MIT | POM | Copyright (Svix) and MIT text in `THIRD-PARTY.txt` |
| `org.slf4j:slf4j-api` | 2.0.16 | MIT | POM; JAR `META-INF/LICENSE.txt`; manifest | Licence in jar and `THIRD-PARTY.txt` |
| `org.slf4j:slf4j-nop` | 2.0.16 | MIT | POM; JAR `META-INF/LICENSE.txt`; manifest | Licence in jar and `THIRD-PARTY.txt` |
| `com.github.jsqlparser:jsqlparser` | 5.4 | Dual LGPL-2.1 / Apache-2.0 (**taken under Apache-2.0**) | POM; manifest `Bundle-License` | Choice stated in `THIRD-PARTY.txt`; top-level `LICENSE` |
| `io.modelcontextprotocol.sdk:mcp-core` | 2.0.1 | MIT | POM; manifest `Bundle-License` | Copyright and MIT text in `THIRD-PARTY.txt` |
| `io.modelcontextprotocol.sdk:mcp-json-jackson2` | 2.0.1 | MIT | POM; manifest `Bundle-License` | Copyright and MIT text in `THIRD-PARTY.txt` |
| `io.projectreactor:reactor-core` | 3.7.0 | Apache-2.0 | POM | Top-level `LICENSE` and `THIRD-PARTY.txt` |
| `org.reactivestreams:reactive-streams` | 1.0.4 | MIT-0 | POM | MIT-0 text in `THIRD-PARTY.txt` |
| `com.networknt:json-schema-validator` | 2.0.4 | Apache-2.0 | POM; manifest `Bundle-License` | Top-level `LICENSE` |
| `com.ethlo.time:itu` | 1.14.0 | Apache-2.0 | POM; manifest `Bundle-License` | Top-level `LICENSE` |

### Embedded components inside `jackson-core` 2.22.3

| Component | Licence (SPDX) | Copyright holder | File in jar |
|---|---|---|---|
| FastDoubleParser (`com.fasterxml.jackson.core.internal.shaded.fdp`) | MIT | Werner Randelshofer | `META-INF/FastDoubleParser-LICENSE` |
| Schubfach number writing code | MIT | Raffaello Giulietti | `META-INF/Schubfach-LICENSE` |
| Derived from Daniel Lemire's `fast_double_parser` | BSL-1.0 | Daniel Lemire | `META-INF/FastDoubleParser-ThirdParty-LICENSE` |
| `fast_float` | MIT | The `fast_float` authors | `META-INF/FastDoubleParser-ThirdParty-LICENSE` |
| `bigint` (2-clause portions) | BSD-2-Clause | Tim Buktu | `META-INF/FastDoubleParser-ThirdParty-LICENSE` |

## 5. Non-redistributed dependencies (test, provided, optional, sample-only)

None of the artifacts below is included in the agent home or the CLI distribution:

| Artifact | Version | Scope and modules | Licence (SPDX) | Evidence |
|---|---|---|---|---|
| `org.junit.jupiter:junit-jupiter` (`-api`, `-engine`, `-params`), `org.junit.platform:*`, `org.junit:junit-bom` | 5.14.4 / 1.14.4 | test across reactor; `junit-jupiter-api` compile in `skillroy-junit` and `skillroy-sample-lending-scenarios` | EPL-2.0 | POM; JAR `META-INF/LICENSE.md` |
| `org.opentest4j:opentest4j` | 1.3.0 | test; compile via `junit-jupiter-api` and `playwright` | Apache-2.0 | POM; JAR `META-INF/LICENSE` |
| `org.apiguardian:apiguardian-api` | 1.1.2 | test; compile via `junit-jupiter-api` | Apache-2.0 | POM; JAR `META-INF/LICENSE` |
| `io.opentelemetry:opentelemetry-sdk-testing`, `opentelemetry-sdk` | 1.66.0 | test in `skillroy-telemetry` | Apache-2.0 | POM |
| `org.jboss.byteman:byteman` | 4.0.27 | provided in `skillroy-agent-byteman-sys`; test in `skillroy-agent-byteman`, `skillroy-agent-dist`, `skillroy-sample-lending-scenarios` (user-supplied at runtime, never bundled) | LGPL-2.1-or-later | POM |
| `org.hsqldb:hsqldb` (`jdk8`) | 2.7.4 | compile in `skillroy-sample-lending-app`; provided in `skillroy-sample-lending-agent`; test in data/scenario/sample modules | BSD-style ("HSQLDB License") | POM |
| `com.microsoft.playwright:playwright`, `driver`, `driver-bundle` | 1.63.0 | compile in optional `skillroy-cdp`; test in `skillroy-sample-web-host-scenarios` | Apache-2.0 (bundled Node.js binaries and npm packages carry their own notices inside `driver` / `driver-bundle`) | POM; JAR `driver/*/LICENSE`, `ThirdPartyNotices.txt` |
| `com.google.code.gson:gson` | 2.14.0 / 2.11.0 | compile in `skillroy-cdp` (2.14.0) and web host sample (2.11.0) | Apache-2.0 | POM |
| `com.google.errorprone:error_prone_annotations` | 2.48.0 / 2.27.0 | compile via `gson` | Apache-2.0 | POM |
| `org.jspecify:jspecify` | 1.0.1 | compile via `playwright` | Apache-2.0 | POM |
| `me.friwi:jcefmaven` | 152.0.6 | compile in `skillroy-sample-web-host` (profile `jcef`) | Apache-2.0 | POM |
| `me.friwi:jcef-api`, `jcef-natives-linux-amd64`, `jcef-natives-linux-arm64` | `jcef-17e805a+cef-152.0.6+...` | compile/test in web host sample (profile `jcef`) | BSD-3-Clause (CEF/java-cef) + JogAmp component licences in natives | POM; JAR `LICENSE.txt`, `jogl.LICENSE.txt`, `gluegen.LICENSE.txt` |
| `me.friwi:jogl-all`, `gluegen-rt` | v2.4.0 | compile via `jcef-api` (profile `jcef`) | Multi-component permissive (BSD-2-Clause, BSD-3-Clause, BSD-4-Clause, SGI-B, Apache-2.0, Ubuntu Font 1.0) | POM; natives licence files |
| `org.apache.commons:commons-compress`, `commons-codec`, `commons-io`, `commons-lang3` | 1.28.0 / 1.19.0 / 2.20.0 / 3.18.0 | compile via `jcefmaven` (profile `jcef`) | Apache-2.0 | POM; JAR `META-INF/LICENSE.txt`, `NOTICE.txt` |

## 6. Build plugins

All Maven plugins are pinned in `pluginManagement` in the root `pom.xml` and inherit `Apache-2.0` from the Apache
parent POM chain (`maven-enforcer-plugin` 3.6.3, `maven-resources-plugin` 3.5.0, `maven-compiler-plugin` 3.16.0,
`maven-surefire-plugin` 3.6.0, `maven-failsafe-plugin` 3.6.0, `maven-jar-plugin` 3.5.1, `maven-assembly-plugin`
3.8.0, `maven-dependency-plugin` 3.11.0, `maven-clean-plugin` 3.5.0, `maven-install-plugin` 3.2.0,
`maven-deploy-plugin` 3.1.4, and `maven-shade-plugin` 3.6.2). None is redistributed.

## 7. Linux test container (`ci/Dockerfile`)

`ci/Dockerfile` builds a local test container from `eclipse-temurin:25-jdk` (OpenJDK under
`GPL-2.0-only WITH Classpath-exception-2.0`) plus Ubuntu Xvfb, fontconfig/DejaVu, and Chromium/JCEF shared-library
packages (`xvfb`, `xauth`, `libxtst6`, `libxi6`, `libxrender1`, `libxext6`, `libfreetype6`, `fontconfig`,
`fonts-dejavu-core`, `procps`, `libnss3`, `libnspr4`, `libatk1.0-0t64`, `libatk-bridge2.0-0t64`, `libatspi2.0-0t64`,
`libcups2t64`, `libdbus-1-3`, `libglib2.0-0t64`, `libcairo2`, `libpango-1.0-0`, `libasound2t64`, `libxcomposite1`,
`libxdamage1`, `libxfixes3`, `libxkbcommon0`). The image is used solely to run headless display and cross-JDK tests
and is not published or redistributed.

## 8. Re-running this audit

Re-run this check whenever a dependency version changes in `pom.xml` or `skillroy-cdp/pom.xml`, a new module adds a
dependency, or an assembly descriptor (`agent-home-layout.xml`, `cli-layout.xml`) changes:

1. Build and inspect dependency lists:
   `mvn -B verify -DskipTests dependency:tree dependency:list` (and with `-Pjcef`).
2. Inspect assembled distributions:
   `unzip -l agent/skillroy-agent-dist/target/skillroy-agent.zip` and
   `unzip -l controller/skillroy-cli/target/skillroy-cli.zip`.
3. Run the automated verification suites: `DistributionContentIT`, `HomeConstantPoolIT`, `ShadedContentIT`,
   `OptionalModulesIT`, and `DistributionNoticesIT`.
