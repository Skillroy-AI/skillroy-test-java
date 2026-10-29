# Contributing

Thank you for considering a contribution. This project is licensed under the Apache License, Version 2.0
(`LICENSE`); contributions are accepted under the same licence.

## Developer Certificate of Origin

There is no contributor licence agreement. Instead every commit must be signed off under the
[Developer Certificate of Origin 1.1](https://developercertificate.org/): by adding a `Signed-off-by` line you
state that you wrote the change, or otherwise have the right to submit it under the project's licence.

```bash
git commit -s -m "Describe the change"
```

This adds a line such as `Signed-off-by: Your Name <you@example.org>` to the commit message. Use your real name.
Commits without a sign-off cannot be merged.

## Ground rules

- **Neutrality.** Nothing in this repository may name or describe a customer, a tenant or a proprietary
  application. Layers for particular applications live in their own repositories and depend on this one. A need
  from such a layer is expressed here as a generic SPI and exercised with the bundled sample application.
- **Java levels.** Everything under `agent/`, the sample application and the sample agent modules is Java 8
  language and API (`--release 8`), tests included, with no third-party classes inside the target JVM, except for
  the optional hooks module (`optional/skillroy-agent-hooks.jar`, loaded only when `optionalModules` names it),
  which carries Byte Buddy and its bundled ASM shaded and relocated under the project's prefix; only `Spy`,
  project code, ever reaches the bootstrap search path. A Byteman jar supplied to the optional Byteman module is
  the user's own and is never bundled. The controller side is Java 25 (`--release 25`).
- **The agent never harms its host.** Bounded waits everywhere; actions on the event dispatch thread are posted,
  reads are bounded; a thread the application forbids is never used.
- **No sleeps in the scenario executor.** Wait on events and on a settled UI.
- **Licence headers.** Every source file starts with the Apache-2.0 header (Java, JavaScript, XML, shell, batch
  and properties files, the Dockerfile, the CLI launcher and CI YAML; not documentation, scenario YAML or
  committed plans). `ci/license-headers.sh` lists files without one and exits 1 if there are any;
  `ci/license-headers.sh --apply` adds the header.
- **New dependencies** need an entry in `docs/licence-audit.md`. Nothing but the JDK may become visible to a
  target JVM, apart from the relocated hooks carve-out under "Java levels".

## Building and testing

See [README.md](README.md). In short: the build needs Maven 3.9 and JDK 25 or newer, and `mvn -q verify` runs
every test that needs no display. Tests that need a display run in the Linux test image through
`ci/run-in-linux.sh`; do not switch headless mode off on a desktop.

## Reporting security problems

Please do not open a public issue for a vulnerability. Report it privately as [SECURITY.md](SECURITY.md)
describes: through the repository host's private vulnerability reporting, or by mail to the address in
[NOTICE](NOTICE).
