# Release checklist

Run through these checks before tagging a release of Skillroy Test (Java):

1. **Linux test matrix is green** on the release commit (`ci/run-in-linux.sh`), checking report counts rather than
   exit codes alone across the three legs:
   - the full reactor with Java 25 targets (`-Pjcef -Dskillroy.headless=false`),
   - the full reactor with Java 8 targets (`-Dskillroy.it.targetJava=/opt/jdk8/bin/java`),
   - the agent-side modules on a Java 8 test JVM (`-Djvm=/opt/jdk8/bin/java`).
2. **Licence headers and dependency audit are current**:
   - `ci/license-headers.sh` passes,
   - any new or updated dependency is recorded in [`docs/licence-audit.md`](licence-audit.md),
   - `DistributionNoticesIT` passes against the assembled CLI zip and `THIRD-PARTY.txt`.
3. **Agent home content and isolation**: `DistributionContentIT` and `HomeConstantPoolIT` pass, and [`NOTICE`](../NOTICE)
   accurately reflects what the agent home carries.
4. **Security documentation is current**: [`SECURITY.md`](../SECURITY.md) and
   [`docs/design/threat-model.md`](design/threat-model.md) reflect any new trust boundaries, capabilities or
   unverified platform paths.
5. **Committed sample artefacts are up to date**: plans, bindings, capability goldens, recordings and scenario
   schemas pass their verification tests (`PlansUpToDateTest`, `BindingsUpToDateTest`, `CapabilityGoldenTest`,
   `ScenarioSchemaCommittedTest`).
6. **Versions**: the release version is set across all POMs and agent module metadata, with no `-SNAPSHOT`
   remaining.
7. **Coordinates**: control of the `ai.skillroy` namespace and repository URLs is verified.
8. **DCO sign-off**: commits since the previous release carry a `Signed-off-by` line.
9. **Neutrality**: no proprietary, customer or tenant names or paths appear anywhere in the tree.
