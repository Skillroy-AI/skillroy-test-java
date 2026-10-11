# Release checklist

The ordered list a release of Skillroy Test (Java) runs through (decision D148, `design/phase6-contracts.md` §12.8).
Tick each item in the release's own record (the release note or its pull request), never here.

1. **The Linux matrix is green** on the release commit, with test counts read from the reports, not exit codes alone:
   the arm64 image twice and the amd64 image once (`ci/run-in-linux.sh`), each with the three legs: the whole reactor
   with Java 25 targets (`-Pjcef`, `-Dskillroy.headless=false`), the same with Java 8 targets
   (`-Dskillroy.it.targetJava=/opt/jdk8/bin/java`), and the agent-side modules on a Java 8 test JVM (`-Djvm=…`). The
   live profiles report themselves skipped.
2. **The security review.** The first public release: the repeat review's record (both passes) and the owner's
   sign-off, B28 in `DECISIONS.md`, given on 2026-10-10; and the Phase 7 delta review's record (S101-S148, run and fixed
   2026-10-10/11) with the owner's sign-off, B31 in `DECISIONS.md`, given on 2026-10-11. Every later
   release: a delta review of what changed since the last signed record.
   Every confirmed finding is fixed, or, if low, accepted in writing by the owner with a release note.
3. **Was the record read by someone outside the project?** Record "done" (by whom) or "waived by the owner"; recommended,
   not a gate (B26 Q12).
4. **The licence audit is re-run** (`docs/licence-audit.md` §9 lists its triggers): every new or changed dependency
   audited; the CLI distribution's `THIRD-PARTY.txt` compared with the built zip (`DistributionNoticesIT` green).
5. **The agent home's content**: `DistributionContentIT` and `HomeConstantPoolIT` green; `NOTICE`'s wording matches
   what the home carries (the optional hooks module's relocated Byte Buddy, B26 Q1).
6. **The "not verified" list is current** in `README.md`'s status paragraph, `SECURITY.md` and
   `design/threat-model.md` §8 (the Windows items, the `jxbrowser` adapter beyond the version and platform its kit ran on, the live-only
   paths, and the Phase 7 items of `design/phase7-contracts.md` §13.4: Robot input off the Linux image's display, any
   display scale other than 1, the vision loop beyond the spikes' short sessions and the live class's
   three short runs) and the delta review's fixes (a masked browser on real JCEF, the minimised-frame and off-screen refusals
   off the Linux image's display, the paste and the middle click on an X11 desktop, a second OS user, an APFS
   case-insensitive volume, the Windows ACL judgement of the run-output directory; `design/phase7-contracts.md` §13.4). The
   `.bat` launcher is marked unverified or verified accordingly.
7. **The committed artefacts are up to date**: plans, bindings, capability goldens, recordings and the scenario schema
   (`PlansUpToDateTest`, `BindingsUpToDateTest`, `CapabilityGoldenTest`, `ScenarioSchemaCommittedTest` and their
   siblings green).
8. **Versions**: the release version in every POM, in every `version()` of the agent modules and in `agentVersion`;
   no `-SNAPSHOT` left.
9. **Coordinates**: the domain behind the `ai.skillroy` groupId is under the project's control (B2; confirmed by the owner on
   2026-10-11, B33).
10. **The DCO trail**: every commit since the last release carries a `Signed-off-by` line. (First release: the three Phase 6
    merge commits without one, `ee020ad`, `6a342e7` and `f33488f`, were accepted by the owner on 2026-10-11.)
11. **The release note** names every accepted or deferred low finding with its release-note text (the eight of B28
    for the first release: the five of B26 Q22 and S77's residual, S84 and S94 of pass 2; and S142 and S144
    of the Phase 7 delta review, accepted by the owner in B31 on 2026-10-11, ten in all), the behaviour changes of the
    fixes that a user can see (`DECISIONS.md` B27 and B30), the "not verified" list, and the outcome of item 3.
12. **Neutrality**: the private deny-list check of the sibling repository passes on the release commit.
