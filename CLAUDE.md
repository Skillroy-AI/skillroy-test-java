# CLAUDE.md: skillroy-test-java (base framework)

## What this repo is

The tenant-neutral base of an AI-driven testing framework for Java applications (in-JVM Java 8 agent plus a
Java 25 controller), licensed under Apache-2.0. Start with `README.md`, then
`docs/design/base-framework-design.md` and `docs/guides/integration-guide.md`.

## Neutrality rule (hard)

Nothing in this repository may name or describe any customer, tenant, or proprietary application: no product
names, class or package names, file paths, config keys, table or column names, and no citations into non-public
source trees. Write about a *target application*, a *target JVM*, a *target host*, *embedded web views*, and
*domain packs*. Third-party libraries and tools may be named.

Application-specific layers live in separate private sibling repositories that depend on this one. If a need
from such a layer shows up here, express it as a generic SPI and exercise it with the sample lending desk.

A private sibling repository carries a script that greps this repo against a deny-list; it must stay clean.
Do not copy that deny-list into this repository.

## Engineering rules

- `agent/**`, `samples/skillroy-sample-lending-app`, `samples/skillroy-sample-lending-agent`: Java 8 language and
  API only (`--release 8`), tests included. No third-party classes inside the target JVM, with one carve-out:
  Byte Buddy and the ASM it bundles, shaded and relocated under the project's prefix inside the optional hooks
  module `optional/skillroy-agent-hooks.jar`, which loads only when the agent option `optionalModules` names it;
  only `Spy`, project code, ever reaches the bootstrap search path. Nothing else, in no module. (A Byteman jar
  supplied to the optional Byteman module is the user's own and is never bundled.)
- `controller/**`, `samples/skillroy-sample-lending-pack`, `samples/skillroy-sample-lending-scenarios`: Java 25
  (`--release 25`).
- Wire types and the JSON codec live in `skillroy-protocol` and are shared by both sides; never duplicate them.
- **PoC-first iteration:** Prioritize low-friction target onboarding, fast edit-and-run loops, and clear
  diagnostics over ceremony. Update code and tests directly.
- **Trusted test-harness boundary:** Treat the local controller, CLI, MCP client, and configured model session as
  part of the trusted test-authoring harness. Keep basic credential redaction in logs and outputs, without adding
  oracle-prevention complexity that withholds SQL/UI diagnostics or blocks legitimate test queries.
- The agent must never throw into, block, or slow the host: bounded waits everywhere, actions on the EDT are
  posted (`invokeLater`), reads are bounded `invokeAndWait`, and a thread the application forbids is never used.
- No sleeps in the scenario executor: wait on events (`sync.await`) and `swing.settle`.
- Tests that need a display must skip when headless and are run with `ci/run-in-linux.sh`.
- Licence: every source file starts with the Apache-2.0 header; `ci/license-headers.sh` checks it (`--apply` adds
  a missing one). A new dependency needs an entry in `docs/licence-audit.md`; nothing but the JDK may become
  visible to a target JVM, apart from the relocated hooks carve-out above.
- The sample is a tool-library lending desk (domain id `lending`: items, loans, deposits, waiver rules, slips).
  No retail vocabulary anywhere (code, tests, fixtures, comments, docs); write new examples in the lending
  vocabulary or a plainly generic one.

## Working here

- Build: `mvn -q verify` on JDK 25 (`agent/skillroy-agent-hooks` depends on the shaded jar built during `package`,
  so run `verify` rather than bare `test` or `compile`). Display and Java 8 target tests: see `README.md`.
- Commits carry a DCO sign-off (`git commit -s`, see `CONTRIBUTING.md`).
