# CLAUDE.md: skillroy-test-java (base framework)

## What this repo is

The tenant-neutral base of an AI-driven testing framework for Java applications (in-JVM Java 8 agent plus a
Java 25 controller). It is licensed under Apache-2.0 and headed for open source; nothing is published yet. Start
with `README.md`, then `docs/design/base-framework-design.md` and `docs/design/phase1-contracts.md`. Decisions are
in `docs/DECISIONS.md`; status and next steps are in `docs/TODO.md`.

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
  API only (`--release 8`), tests included. No third-party classes inside the target JVM.
- `controller/**`, `samples/skillroy-sample-lending-pack`, `samples/skillroy-sample-lending-scenarios`: Java 25
  (`--release 25`).
- Wire types and the JSON codec live in `skillroy-protocol` and are shared by both sides; never duplicate them.
- `docs/design/phase1-contracts.md` is the contract for Phase 1 and for the Phase 1.x amendments, each marked
  built or specified. Change the contract first, then the code.
- The agent must never throw into, block, or slow the host: bounded waits everywhere, actions on the EDT are
  posted (`invokeLater`), reads are bounded `invokeAndWait`, and a thread the application forbids is never used.
- No sleeps in the scenario executor: wait on events (`sync.await`) and `swing.settle`.
- Tests that need a display must skip when headless and are run with `ci/run-in-linux.sh`.
- Licence: every source file starts with the Apache-2.0 header; `ci/license-headers.sh` checks it (`--apply` adds
  a missing one). A new dependency needs an entry in `docs/licence-audit.md`; nothing but the JDK may become
  visible to a target JVM.
- The sample is a tool-library lending desk (domain id `lending`: items, loans, deposits, waiver rules, slips).
  No retail vocabulary anywhere (code, tests, fixtures, comments, docs); write new examples in the lending
  vocabulary or a plainly generic one.

## Working here

- Build: `mvn -q verify` (JDK 25+). Display and Java 8 target tests: see `README.md`.
- Do not commit or push unless asked; commits carry a DCO sign-off (`git commit -s`, see `CONTRIBUTING.md`). This
  repo has a public-facing remote; history is permanent once pushed.
