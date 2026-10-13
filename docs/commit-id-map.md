# Commit ids before 2026-10-13

On 2026-10-13, before the repository was first pushed, every commit's author, committer and `Signed-off-by` email was
rewritten to the owner's GitHub noreply address at the owner's request (`DECISIONS.md` B37). Every commit id changed;
every tree stayed byte-identical. The documents on `main` after the rewrite cite the new ids. The documents inside the
history, the tagged tree of `v0.1.0` among them, cite the old ones; this table maps them (old id, new id, date and
subject, oldest first).

```
efb48b793cba493251b62245f1b0db33a8b34886 37d93770dc5884e605aa5327310ff61c5e99887a 2026-10-06 Initial commit: skillroy-test-java base framework
00e04c3c0791e17d5808a8ed5b1c07d4f227fdf3 c6950edd322cf7aefcd5bc49613d1fed827fcb0a 2026-10-06 Record the second Linux matrix run of the Phase 3 tree (2026-10-06)
54aabb21003f409e8b1da9b8ad5b07fd6e20a325 a2312ce4e09d40a6ae9f06c37a0b591e4a95e606 2026-10-06 BS8: JCEF 152 in the Linux test image, passed with conditions
1185d8df3c7fbe639034cca46cef973093595415 4064d6a32dabac79b598881627497093d2d0832e 2026-10-06 Phase 4 contract: embedded web views, CDP and the in-process web SPI (specified, not built)
abc530825b21ded52853c686ad0d97467a320287 02bb9df05206257585d9be5250e299b41dcb7150 2026-10-07 Phase 4: embedded web views, CDP and the in-process web SPI (built, reviewed, verified)
1e2216547841e853043ae27de128cb1e27b47008 e4228d97a94f0ae0f02a27aa2a8a1e769174abdb 2026-10-07 Phase 5 contract: the AI layer (specified, not built); B23 provisional
0b4e9e2dd5e25b3fe814b20828d6fdda1ef661e7 f3986055930de7d6eb5bb69e8da97bf93d43b8d7 2026-10-07 Phase 4.x: amendments A35-A38 (waitFlag parity, one sequence read, SPI reload and bitmap source, re-probe pace)
04b735a9951a7dd107794e3e3c5029c9241c478a 61a0488ca82d635a557bd69372ada0be2a18e7b8 2026-10-07 Phase 5 marks into the Phase 1-4 contracts and the overview; contract corrections; B23 Q11 withdrawn
d46c5de80fe748a6df66acc6fe4652242b67504a ec36025bf539f9b6aa0df0addee3a9df06ff0082 2026-10-07 Spike BS13: the Claude API facts the Phase 5 contract relies on (offline part; the live part waits for a workspace id)
58d5f689ed51f855e1922c00e1e99b7efc5613f2 2ba5eee3e76e09336a7662537e59b79315598a5e 2026-10-07 Spike BS6 passed: SqlGuard across SQL dialects; the contract's guard rules corrected
3f7a91754c125baf4ac47147631035be7928fc15 b337e6d570786a1c1764f89b47e8de0437cab257 2026-10-08 A39: the COMPONENT rung re-issues its window activation; the display-test focus flake explained and mitigated
9cb4a27a2cdd5e28f15d3123e7682a4220aa67a1 5e8a3f3f5dc9b4bf1c5553ebe8f8be2c2bbc754c 2026-10-08 Phase 5 wave 1: the executor's session entry, the journal and its export, plan version 5, the contributor seam and the resolution-side changes (on the provisional B23)
d9757af763603f71dd3f07c9ba91e23f95204a35 417b00a77c69fc0249fe1343d2cc5a23598991aa 2026-10-08 Phase 5 wave 2a, the sample: the lending pack's SQL allow-list, argument schemas for every verb, scenario D, pack 0.4.0
321b5648d125f54e946232730449fbcc4c08351d f67639e4426f8f3ec174bf8c3ad6e0009c178394 2026-10-08 Phase 5 wave 2a: skillroy-inference, the inference core (gateway SPI, the Claude, recorded and scripted gateways, SqlGuard, the tier (b) resolver, budgets, the tool projection)
344f1e012df4943129f88c57010b51e76ac11e3f eb0b289aea48736814d55e8c93fecb577de2a3d0 2026-10-08 Phase 5 wave 2b: skillroy-mcp, the MCP server over stdio
2c6e2de807d88f11408e7c5151d99bc1074174aa 66e552c739bb5b8d12c729e7a0551a56e22d6cf5 2026-10-08 Phase 5 wave 2b: batch re-resolution, triage, the planner, the session service and the overlay bridge
ba6199dd0485084e3913768265e36098fa6e062c 77630d89a4b9c744a6671134299fe311508c3145 2026-10-08 Phase 5 wave 2c, the sample: value loaders, scenario D's bindings through stand-in recordings, the session export acceptance, the end-to-end class
7e18e0cc5c56ecfb68c87b67e6490ac9af1b14c8 50c896858e32e13bfde8844574b4adb5690677d6 2026-10-08 Phase 5 wave 2c: the CLI commands and options, the run writer, the distribution; B24
a6ed6e92079b0dc4904e5a0412b72587bdeb4908 dd57142bef946b89960fd2848f1e976355bcb3d9 2026-10-08 Phase 5: the first Linux matrix's three fixes; D95; licence audit §4 for the distribution
ef8ba96cd083c10ca5f7bf57880dd18503631c57 206dd08986b4165c421b10141d97bf75bc83c831 2026-10-08 Phase 5 as-built marks: the §16.1 passages of the earlier contracts and the design overview marked built
e7acd6196f3a757ca01995f65b47782e00ea1684 c65a6588bfceea6f6dbd912b550d17ffc9e1e7e8 2026-10-08 Phase 5 code review: the fix wave (four fixers and the reconciliation); D96-D104, D110
7a0b4c742f01b6ac8a9060eabc70862da57d6fa9 522bfed75d79013afb0eae6937901ad448a354cf 2026-10-08 skillroy-mcp: serialise a session's sends over the SDK's stdio transport
22092cdcecafc76010f4c9b930b72028187fb988 62c21660dd8648b173ce0709299ea4aeb725754f 2026-10-08 TODO: Phase 5 as verified; the review subsection; the matrix tables; the transport defect; next steps
ea6daf91023cec791b8807c1aaf101115f7bb913 34cb9b01d5f3708359e1d8015057459cbd12a311 2026-10-08 Docs: B23 and B24 confirmed by the owner; the model key works
166c85e7267f4539972fd7ea54d8c20e75930a64 94d6191947a80da6f13f996b6fdda7847bdcbfbd 2026-10-08 Spike BS13 live part: every API fact checked with the owner's key on SDK 2.70.0
7690322e1d43abb723ce61b15659c4ffea92a0d6 2314eae0ce719b15708547aa273e91953cbb3f76 2026-10-08 Phase 5 live: the profiles inference-live and inference-record, the live acceptance of 15.3, scenario D recorded live; D111-D113
22ae5745ec3744fa36afe3b2e9dbd211f939693b 8e556d31e8e09eb8b2bc2862d527190f5ea7cc53 2026-10-08 Sample scenario C (return-refunds-deposit): authored by the planner, reviewed, with its plan, bindings, fixtures and acceptance
972a3b9abc46f2365a4bede06dc56ba5c643e400 ecc37bfcad65191a8f99160ca75c193feb108aae 2026-10-08 Docs: the live session as built (TODO status, the live table, the matrix, walls and next steps; README; the overview)
e86e8e2ab6e58b6f770f7157e2078ea2a8acc25f 5d617be57d4896eb043f46d63d6151b2abdb0a64 2026-10-09 Docs: B25 confirmed by the owner (D111-D113)
c006bfe13276866d9a40d66607cd0ba2db5af2fb 75836c3dbb7b23f7ca0b5d69711f47fd542ad0ac 2026-10-09 Spike BS3: Byte Buddy retransformation, inline Advice, a bootstrap Spy, restore on detach
065746d2914ecbd0890d8702f17e45a713e50fa6 d8b326c8d7005e6e6108fa91ff14d756acd266c6 2026-10-09 Phase 6 contract: hooks, Byteman, JDK 25, Windows, OTel, the overlay and the repeat security review; B26 provisional
764012555d8eab8e47fccd675789d6cc6af7e6a8 dfd1ac294cc2275e5d510dc0cdebc289aff09c8e 2026-10-09 Spike BS14: Byteman in process, no listener (passed with conditions)
26d3c22f6ba4feeb86221617b58673b399aa31ef dba5b52ec55387fe61f8006189369abd89a107c5 2026-10-09 Phase 6 BW, Windows half: the .bat launcher, PATHEXT and cmd.exe /c, SecureFiles public
c42f0401e369cfae276cbc703299b0d2d11635ed 2c48a82266a952fdcacfceccc00adb4c208abffc 2026-10-09 Phase 6 HK, part 1: protocol hook types, agent-api SPI, api.instrument
f33488fd328fb4ce1906fc638aa194ae80f11484 902ae10d58da74060c4fcc4d2fd49bdf7e24e538 2026-10-09 Merge branch 'p6/hk' into p6/bw
3bf51e54ca0847392a5d0e64a057d7f6895ff387 bd7eb4f83901745a021046f4ab8f9493197f309a 2026-10-09 Phase 6 BW, Byteman: skillroy-agent-byteman-sys and skillroy-agent-byteman with their unit tests
8ba12c9593cd3ee2b40bf0c5c96bd3008f808e92 2354590a9f308b23892f6b12c36f5f2a09612482 2026-10-09 Phase 6 BW, Byteman: the optional/ set of the home, BytemanIT and its toy; the fresh-JVM tests in executions of their own
9b87ba4baeb3a1122430eeadc689b0e8bc3312d2 7e912b0e2aab56314ee20727504df0da1b7a1028 2026-10-09 Phase 6 BW, Byteman on the desk: the hooks leg's item 7 as BytemanDeskIT (to move into HookChecks)
cd38182c46d735461f025a1c5abfee5eb051f91e 1227f3f48faed3f7b6c08bb2a173a8b56457eb30 2026-10-09 Phase 6 BW, documents: the As-built notes of section 7 and BS14, P1 byteman.jar, the design's Byteman rows, the audit
91cd1a1682df02a69d4bc3f4ee037a400f282ab9 c4a90ce10fe40a7c9c9320c7337af2b70fd32110 2026-10-09 Phase 6 CP: hook catalogues and the installer, the overlay, the branch edition, scenario E, JDK 25 checks
8fc2384f4cb1a8013c8d6591fab103188e1f9b85 30bb838ad12c18f2dd8b630ae7d4eaa48d5489c5 2026-10-09 Phase 6 TL: OpenTelemetry export (RunObserver SPI, skillroy-telemetry, CLI and JUnit options)
bdfe0d4961f7af51a34c7edc5e132ad02908d912 43d1599d8365802377470d6116353bc617d4ad9f 2026-10-09 Merge main (TL) into p6/cp: hooks eleventh and observer twelfth in RunOptions and SessionOptions
9044c9ab88985218e91be214f7b21fc89c9eaba2 6857c3c0918829af516b855313c2de0f22210132 2026-10-09 Phase 6 HK, part 2: the spy, the shading module, the hook engine, the agent home
f76e96417444b0b1e651dbb6786c7c315107d719 3d822ff47e59334a403a474af2efa2b1401429a2 2026-10-09 Merge p6/hk (HK part 2) into p6/cp
720eb6bf6f8822544a83a9a4fb26e5f0c5425b33 81352aa1f5f44606ecebdc84c14c0c52b7fac1a0 2026-10-09 Merge Phase 6 HK: hooks and spy (skillroy-agent-spy, skillroy-agent-bytebuddy, skillroy-agent-hooks)
51f8eb85c5524da2658f054f5824171aa5c3058b 58c99847557db9b3b4878d7064223b140721f57d 2026-10-09 Merge main (81352aa: HK landed) into p6/cp
14bd710f15b2940ba6a821983031662404f4ce48 431285fa6ed98f1d77036466d8bcf571f3b14eb0 2026-10-09 Phase 6 CP: hooks status reads otherAgents from hook.health (D-HK-12); HookChecks' place for Byteman
cf79b520643f2516c0f5c04ac3766acb3b6d021b 41bb70f843c5ab8aae55e58957b7863bbf5e9311 2026-10-09 Phase 6 CP: the hooks leg's golden and the branch variant's bindings; the 205 log scan
6a342e7e9e23b2446a18a491845f67003f12aae3 c38372e63c25ec6940e1fa6b8d755e5733b86084 2026-10-09 Merge branch 'main' into p6/bw
5457ccbf54da48b220ffaeccf403f0ed5208919b b1ef29adcd0893e562e90824687c19bf05859e89 2026-10-09 Phase 6 BW, integration: item 7 moved into HookChecks (166-168), the Byteman options on the hooks mode
b22013e1efeade821c989e2278b84a001189185f 80a13a5e53d220e64575ceb1832a4e405d2be670 2026-10-09 Phase 6 BW, integration: the fresh-JVM Byteman tests skip under a merged -Dtest; item 7 and D-HK-4 as built
83336801c52e4773757747fcc82fdf80cce26db0 0c19ef09caf5718959bd3dc56896f514a035a146 2026-10-09 Phase 6 SR (FX-1): security fixes S35-S48 of pass 1, D156 in full
6d6ce08ab3c319b8b48d3f8d9c0fcc037fc18f25 665b1096244765160abc2f9e836545c52cd8a838 2026-10-09 Phase 6 FX-2 (SR): security fixes S55-S59, S61, S62 (part): literal data_query, session meter, client origin, verb gate at call time, captures under images
ee020adafd1a1e8d5e0bf26276230b8a1e9a440a a50d25b51f5a3050daacf16b988cb5f268b3258d 2026-10-09 Merge branch 'main' into p6/fx2
755844cf396e34349d6182ff20a70000abce7f2b b81384eb688d00fa98b93dc046738700630f212b 2026-10-09 Phase 6 FX-2 (SR): security fixes S49-S54, S60, S62 and the contract text of S49-S62
822dc58ec7a80b93f6d49728a3afea70c67dff64 8ad100dce6e6afb18800c771d6dd91b463b3fa0e 2026-10-09 Merge main (b81384e: FX-2 landed) into p6/fx1
f13657db7562864739c54e7f30a20ea817bd5396 edbe37dcc55f95a9cf3c045bd4e149a1378dc30d 2026-10-09 Docs: Phase 6 as built (decisions D160-D221, B27, the build log, BS14, the carve-out wording)
edbc325fa0119ba31765b8468728f4b183734dee 8d196077f679d3b15a8f4caf61467e3a2463d789 2026-10-09 SECURITY.md and docs/release-checklist.md (Phase 6 contract 12.8, D148)
f546363d46b27ff40a136dc42ba4db638f862ea4 a4b3d2e44c80d0625cdfd0eb230cf67afb408707 2026-10-09 Docs: the licence audit's module counts for Phase 6; alternatives for D188, D191, D192
5ed6780c0b6cbbf296614dba23a3bb96f0866c2c 95162361214cf74bda627d7309a02dce7a7d79ff 2026-10-09 Phase 6 review fix wave FB: the Byteman modules
f9c8033d355eb171864c380292c43af04aa790e0 4086dba35aff849a5d1c8ed8bf26d6ddd6bbc39d 2026-10-09 Merge Phase 6 fix wave FB: the Byteman modules (detach budget, encoder, JarCheck, settings, tests)
9cd91839c1578bbb4061b470888a5b066c016469 d9e0b412d8e5ee50fbbff9e57c444a0c52b23b60 2026-10-09 Phase 6 review fix wave FC: minimal as an allow-list, the armed-stub check before every skip, the hooks root's paging
f0e8477f5835cd3226199fa770e55fbbc4b94d6c 15933c8f7644fc183e11974bbb711d8ff2438565 2026-10-09 Merge Phase 6 fix wave FC: controller and telemetry (minimal as an allow-list, health before skipping, hooks root paging, the sign-on redaction test)
797b5ae73ce51bf89fcb61e6c8fb14b8e88097d5 b3b92b34249bb47384a9611a938f61fa7b2d76a0 2026-10-09 Phase 6 review fix wave FD: the sample's typed-keys display test waits for the entry field's focus before Enter
ffa83cdd110448ff9664a9b4c5796cea352bfa4f 722b3d15960dfa1a15ec5c6a9b70e0e9a048f83e 2026-10-09 Merge Phase 6 fix wave FD: the typed-keys display test waits for the entry field's focus before Enter
55fe53873daf3bbfb40d6625bc95d1ef1a82c7f7 fd4b69c9085b2d1d5fb47d4be14afdc0473aee0c 2026-10-09 Phase 6 review fix wave FA: the hooks module and the agent home's tests
ce1f41ff6cc45a99e5087cb92bbf1cdb9cbe7605 6760bed59ba33df5d044587b1d4629432f17b6c3 2026-10-09 Merge Phase 6 fix wave FA: the hooks module (a partly refused install taken out, capture allow-list, redacted reasons, constructors, fresh-JVM guards) and ChildJvm.pid on Java 8
dede6ec14002bca2885787e4b1f271b880a902ed d11ccdc7df5349ab217a24a7273ee2627a32b693 2026-10-09 Phase 6 security pass 2, fixer SC: the model boundary and the model as an actor (L5, L6)
2ab0f724c997f9fd7d57aad5f7c26e970f735cc2 6c70747821f21cc1d8c1f08761a16f2614414cfe 2026-10-09 Merge security pass 2 fixes SC: the model boundary and the model as an actor (one image rule on every route, the server's hooks switch for a client's run, PromptMask schema secrecy, provider texts scrubbed and bounded)
5f3eb6b8587650c3d993469c19d8adb2311a6e6b 06e1fb008aaa1892accc503211a36c75623ed8db 2026-10-09 Phase 6 security pass 2, fixer SD: L7 (Windows, file-system trust) and L3's SQL Server items
49becf3e1b1cb89fd7aeeec3bbf800431dd8e294 758f916fcd5199107b9144b912b925844ab869ee 2026-10-09 Merge security pass 2 fixes SD: Windows and SQL Server (the planner's prompt on stdin, no .cmd planner, System32 findstr, per-volume POSIX/ACL decision, statements in a rolled-back transaction)
96519aa7d544eb9d8b6e98b33f5d7336a78aeb23 d384dde54dca6f5e1f4faded5bc3f4190b757a9b 2026-10-09 Phase 6 security fix, pass 2 (SA, lens L1): P2-L1-F1, F3, F4, F5
3be43b9ff3349fcac38d07c13841a54bdee82668 da41033ae2ab26220d0b76a5266f2be5eeac5426 2026-10-09 Merge security pass 2 fixes SA: the control channel (body hash in the signed head, options-file and ancestor trust, token floor, one jar-trust rule)
25d51b610d7b8fcfb80686b4e9896903988fe15f 6c5dbdf602ebfaca034a1b490b3815ebab025a40 2026-10-09 Phase 6 security pass 2 (SB): embedded web views, P2-L4-S1, F2, F1, F3
84eeab6555c3dc1da2643ac40b70436312ab4c0c 91aa9c2bf63ae9f747feaee0c4bb0f9693fa92c2 2026-10-09 Merge security pass 2 fixes SB: the web views (console budget, the in-page answer guard, the attach at the probe's WebSocket, bare strings bounded)
b92a6e410c1b08df5a5ccc3b8450636bab65a585 2d61c6b799a24af9629fcc1b3948a6d60ea371d7 2026-10-09 Phase 6 security fix, pass 2 (SE, lens L2): P2-L2-S1, F1, F4, F3, F2, F5, F6, F7, and the bootstrap's volume rule
378b5e02cfbcf2275ec9a913607771f92700178c abec5c83043b2e816c44d35f9e8bb952ea264568 2026-10-09 Merge security pass 2 fixes SE: hooks and Byteman (a failed remove is a residue, rules on JDK and agent classes refused by name, per-thread own-event counting, replace reports its remove, bounded recorders, the bootstrap's options file judged per volume)
8e6606acfe4d002c66e9b5c23acb314a43a17ff3 5b11d98ecea86e4fe4df054c5dfe855a03711ef3 2026-10-09 Phase 6 security fix, pass 2 (SF, lens L8): P2-L8-F1, F2, F3, F4, S1, S2
4f9962f693b420c315295651d55e93bbf4d8c8b6 579af9c2277c3cfa8257758aa7c1cd860c7c0e2c 2026-10-09 Merge security pass 2 fixes SF: telemetry and run outputs (one OutputFiles rule for every writer, settings files under the credentials rule, no home paths in spans, the planner's MCP child exports nothing, headers only over https)
c10d14b14600dadebdaab5d36394b7383208cb7e 5233c712a1b56db2e8b0b43118286778273ac4e6 2026-10-09 Docs: Phase 6 reviewed, fixed and security-reviewed twice (D222-D286, S63-S100, B27 extended, B28)
58fdb65661b2798b6e773267e01958873fdf7ba1 ff9ce7e0b2b3636bbbc9d8604fc37b9a656d9a4b 2026-10-09 Docs: P4's drain bound and P6 10.4's hooks event row as built (gaps 72 and 75 closed)
af29e599c222eb610d8f3dfece29bb71ab109033 e3ceece7632a9a78ff0be57485f565185be8d256 2026-10-10 Docs: the Phase 6 final matrix (arm64 twice, amd64 once, every leg green on 579af9c)
11fce4e4b8241c37a90785539dc9ce759bee85ea 763e154f4deaa7732afd3d0560b7105d82565025 2026-10-10 Docs: B26 and B27 confirmed, B28 signed off by the owner (2026-10-10)
d97ca91c657ea81ab806fabfe4b15e0d51075ba6 850d16768434c9ec6ca887b47e5e62fea4466c94 2026-10-10 Spikes BS9-BS12 (Phase 7 gates) and the planner's stdin route checked live
2eebeed1cdd6acabf125bfd2ea2e8c7ebeb518dc abcfe6512e7e5af415cc311724e79a5c89ebb4ab 2026-10-10 Phase 7 contract: the vision bolt-on and interactive sessions (specified and reviewed 2026-10-10)
fecd468d9125e559b6516852e95a53b8e63d56f9 4ee685c693059f4cde5e6fa81dcb02cb427cfaa3 2026-10-10 Phase 7 step 1b: the session vocabulary's $reference sentence (D342, P7 9.2)
8d599002e2978ab9e79040699b314b1d030114cf 621da104a5f13b506331551773f6253c1d7f918d 2026-10-10 Phase 7 VC: the public API surface other packages call (early commit)
ce3386ecb4b455a5a3b132034aba0cb86413f2c5 924fe806aecc3d764c0c2e3f777117c4752bc095 2026-10-10 Merge commit '621da10' into p7/vm
dfee60e39f9ebbb59d1508465fd2e380c453085b f64056580d44c0f39189736446961010f5e305f6 2026-10-10 Phase 7 VM: the gateway changes (P7 7) and the session seam (P7 9.1)
dae63934e17a2416ba453dca827d3607682f403c d41ad6761da395aed980309e4b262da33deb34bb 2026-10-10 Phase 7 VM: the module skillroy-vision (P7 6) and the facade's reach
f517d98ef9b7630e7392f9a7afef10202bd0f8bf 800446b4da393e5ef4fed137a3efed7a5afc78cc 2026-10-10 Phase 7 VM: the hosts' vision and robot options (P7 9.3, 9.4)
a917e399aa08e53f085ca79deb45de27d8113d89 ae59d39122913558433ed619b44bf0707a723959 2026-10-10 Docs (VM): as-built notes in P7 and the Phase 7 marks in P5, P6 10, the design overview and the licence audit
607fd6a84295280c346953e5b38cf20f320e95b9 ffef348d11b0442688196676b67a4c43280d79f3 2026-10-10 Phase 7 VM: the vision session script, item 8's check and VisionLiveIT (P7 12.4, 13.2 item 8, 13.3)
fd4aab85307ebb0babde7148da621b40a5a6c135 6398518f276dc9ac600ce20ff13d965f88f3c50d 2026-10-10 Docs: B29 (the Phase 7 contract's 39 answers, provisional) and the Phase 7 status
96c347f4fdb7c5c138e2650e1bea3d706e063ac5 364bd13574c0e5ff5d3106c51819df5e477eea31 2026-10-10 Phase 7 VC: the scenario side, the merge, images, the opt-ins and the credential rule
80e2f9d7aa5324a072be7ff428d76c05ca4be930 59de9a61689a4e0eb6c34b793a83f0dbf96a85ff 2026-10-10 Phase 7 VC: the tests of P7 13.1 items 5 and 6
06091a456b1dfb2189b738b3e5c1d8ec0cc66647 19e906299b0c356dd8fdbd1feb1d49948c291426 2026-10-10 Phase 7 VC: the As-built notes, the earlier contracts' marks and the last fixes
71efa8e56eebe13a2b2455701c0aac4ba8633739 06a59ed15bdcebad7f836be362225d7c34438e76 2026-10-10 Merge Phase 7 package VC: the scenario side (plan v6, the four verbs, SKIPPED, the merged matrix, images and bindings v2, ExecutorSession's read/provenance/opt-ins/credential rule, call and run --allow-robot)
5fc2089ca86b7247135be61204122f8a38c856bb 58965f8c4f22f683ba35266d8294e2384c240475 2026-10-10 Merge branch 'main' (VC's full package, 06a59ed) into p7/vm
5d0e516f3228b2b8a44aebf4ca1adb86d2d67142 093b378ed3abe8826969df6c1336ebfa765e6db9 2026-10-10 Phase 7 VM: build on VC's real bodies (the player's baselines, the export's anchors, the tests that needed them)
d43d1c2b8083e7785b04e6d9e624591463205df5 b2360c45ee7c677ff3c79ca924d85849f898c633 2026-10-10 Phase 7 VM: the vision script names a control by its quoted name first
e5133b64f6d22f80dc724d3557641c32de8a4039 682a48800c1e3ef3326e5109866d9fbc23f9362c 2026-10-10 Phase 7 VS: the Swing SPIs RegionResolver, CaptureMask, SurfaceSource and LocatorResolver.describe (A64)
a236ccd4028d9b1bfc2f61593442bea962af8d68 48a2c566bc30a19d4809670c5dd75952aba81a1d 2026-10-10 Merge commit '4ee685c' into p7/vs
9fcfb52e62290cc3b5c0ded60d420da51103ad8e b3f52dbdc8b93ef04be179a719bd66847d883e2f 2026-10-10 Merge commit '621da10' into p7/vs
8b2bc36572e6227e3974c7b6d49801877cb0f4b0 627ebf7fd012f22732dfcea0a58fc0cf42c770ef 2026-10-10 Phase 7 VS: the agent's Swing side, the desk's number pad, the lending module 0.2.0 and pack 0.6.0
12493b4031ee84dd71bec6ab2313e7992da2b331 bbc729257e1032605d4ecdfe6300dcd1c51abc3a 2026-10-10 Phase 7 VS: the vision Swing checks, the Robot leg, and the P1, design and P7 marks
6819a95ea932e9ac1a869a32cc901adfc8b30d51 ae345fc6388121b417f93bf979065fc2d781f205 2026-10-10 Phase 7 VS: the overlaid branch pack reports 0.6.0+lending-branch@0.1.0
7f1319a168ace676d0d9a647980276980aebda2f 0327b3167de62de5a4c924091a7c52cbd49b93b0 2026-10-10 Merge branch 'main' into p7/vs
190a6d1c4b458e760bcc838eec611dd9f3559f77 d990713cff5edebd83043f8030c5457d22713946 2026-10-10 Phase 7 VS, step 2b: every plan's packVersion 0.6.0 and the bindings files rehashed
ec8fb84d6411a357b40ba16a03ec69c20569edc1 20c1bce30a578a65d415408f91f5d7e58b5b70a5 2026-10-10 Phase 7 VS, step 2b: the goldens, keypad-image approved on both Javas and seeds, Phase7Pending gone, RobotChecks' controller half
482288d2ae2a08dc38a1eebd35b955a6073a18ed 43c399ce358f421a465e54d7b35f461c8c6cc716 2026-10-10 Phase 7 VS, step 2b: keypad-frame masks the totals line and the printer's slips
3dceff3801298d96c2f953ad482600ffe668d9f7 d140ecc2256691aa80afbc301b63dd3648b2c411 2026-10-10 Merge branch 'main' (VS's package, 43c399c) into p7/vm
50d83bb84ae1378481cb2d81951a4239715a2d12 4bbf0dc9e0f1cbd644693bdf5a1fba2e80050a8e 2026-10-10 Phase 7 VM: the committed vision export session-vision-loan (P7 12.4, 13.2 item 8)
b37431757779012d3c646cfa03134e80f31f145b fab600732ce8006188bd5e1550fa9d781cb4f025 2026-10-10 Docs (VM): item 8 and VisionLiveIT as built (P7 13.2, 13.3), the P5 14.1 and 14.4 marks
ddcb1f672a0f1bb0fb2b585d7dec14b84999077b cdfbca7a6234074a8d1cb14c85e769bf4a2bd14d 2026-10-10 Phase 7 VW: the web side (web.hitTest, web.pointer, INPUT, the library's version 2, the CDP path, the SurfaceSource)
da1b667dfdb8a3de14c83a4fc828fa22775b66c0 2d1d10ff15f63af980787a740b9a1cbc06e66793 2026-10-10 Merge main (43c399c: VC and VS) into p7/vw
e7a4cb3a9b7ebd18aa36fdef18df46cec933c0c7 0d4a0cb8bfebfcf51e9d7fc877312a09f285fa51 2026-10-10 Phase 7 VW: item 10's Swing half verified on the head with VS (the As-built notes)
8fbfc42847394a0c1378fd1ffd847d581ddaf980 6938a3126c76716231ee4967cc1b9cc0fd4f8a6d 2026-10-10 Merge branch 'main' (VW's package, 0d4a0cb) into p7/vm
7af44eb00249e5e77b287ed36a9fb17746731de6 cf59b1f97003d48a0a0e8d724f1805902e05773e 2026-10-10 Phase 7 VM: CallPolicy's argument refusals and the web host's raw tools pinned (P7 9.1, 9.4; VW's two items)
e11ae083750e5063027a3691cdcbd6a1b099aaf3 5faacb4acc2dd97d2a64a745ec09bba6152eaac5 2026-10-10 Phase 7 VM: D-VM-6 closed: ExecutorSession.fixture, and the player half of P7 13.2 item 8
e96a20b39e3baf9569fcea5ba4b5e3b677556e74 fbef00034e5d8126b236989f4b5cf5a8eb9b1ca9 2026-10-10 Docs: Phase 7 built (D359-D430 numbered, B30), the build log, SECURITY.md, the marks
9f44718cd493beb0604e3afa636ad6024f7adc4a 03625bcf81a3fd823fa560b0ed62882f42193452 2026-10-10 Phase 7 fix wave FA: the agent's Swing side and its tests (review L2, L3)
73187c0ef459f21ef432ca894f589e0b6f832a9c c4094f3f49931dc437b02f8bbcd1883485e5f913 2026-10-10 Phase 7 fix wave FC: the vision module and the session (L1-F2..F6, L1-S1, L2-F2, L3-F10, LV-1)
791e0aedefa539dde4eefe397696cf0a93c1992c a54c2799fcef157d49de6f9b97b81f903fdea048 2026-10-10 Merge Phase 7 fix wave FC: the vision module and the session (review L1-F2, L2-F2, LV-1 and lows)
a897e17999c9729958b5cda68c3ea9353678fa7c 81ba7fdce57f47512686e58ae9a9b3b99ae9e937 2026-10-10 Phase 7 review fix wave FB: one Robot predicate for every controller gate; the focus rule, the stale guards and the secret scan tested
c3a65f18b3e8988f5d9fcc5df5eed41fed9c33bd c9f41e53f76149e8f9b6aaf36f5fb7b5b6c6f9e7 2026-10-10 Phase 7 review fix wave FB: seed B's keypad-frame baselines re-approved with the desk activated (L3-F9)
c7007729cd9dbcb8b576108a3682145f5acae6a9 589b8f5969af4067331cf2e91b5fbf55c5dc89a8 2026-10-10 Phase 7 review fix wave FB: via and mode take a literal value; a reference that resolves to ROBOT is refused at the call (D-FB-2)
c690befb1f9b98a9d49b4197d9ed9c0160a2f2e6 2633eec8ba9b1d2656eb85cfd8310b6e01bd25c9 2026-10-10 Merge Phase 7 fix wave FB: the scenario, controller and CLI side (review L1-F1/L2-F3, L3-F3 and lows; FB-N1)
a1341598176088c5503c232cdc0730e844a020d0 03c8da9379a7bf234ff15aac6a01dcbd17d35d34 2026-10-11 Fix FX: a hook.<id> holds its health state when hook.install answers (the capability-matrix hash flake, D-FX-1)
5d6fdd86b21db1a285d18ce6a8bcf2c81ac2baa3 703cc9b0b498cf1798eab5c6070d44019e217f3a 2026-10-11 Phase 7 security fix SG: the agent's Swing side (S104, S105, S106, S108 agent half, S121-S126, S129, S146 agent half)
506cf762953eda59747ea33105e60149f224109d 71abdfba85158bef3e99868c45ca074bd4dc66d3 2026-10-11 Phase 7 security fix SH: credentials, slots and the client's plan form (S101, S102, S107, S113, S127, S128, S139)
edba708d8bd92e0993f24878a41315b785eb3d9b ed397f0330f520427df80f992a1cdfb5c53335fc 2026-10-11 Merge Phase 7 security fix SH: credentials, slots and the client's plan form (S101, S102, S107, S113, S127, S128, S139)
9d4744553797bd012321fc0e90ad170f421aff98 61bb97791de9a32b0fe82ef6e01280ee7d8e972c 2026-10-11 Phase 7 security fix SJ: the image baselines, the bindings files and the run output (S103, S115-S120, S140, S141, S143, S145-S148)
73e406a62ee1bea33a21c87566e9946e0f26d1c5 dd2f1d61e1efdf2b4f6ca5697fdda617dee6afac 2026-10-11 Merge Phase 7 security fix SJ: image baselines, bindings files and run output (S103, S115-S120, S140, S141, S143, S145-S148)
8ff0ba39a0ef55cba34326647d7b7eff0d0a6ada 0adfdeee37df9f6e4e87a9e65356685ad761bf6a 2026-10-11 Phase 7 security fix SI: the vision module, the session service, the web reads and the CDP path (S108 controller half, S109-S112, S114, S124 recorder sentence, S130-S138, S143 Grounding/Png)
056a007e84a4b1843d190719c05053d87277c597 a1af6c82fe0fe00fc65f36aee8ab22d7ed855f88 2026-10-11 Merge Phase 7 security fix SI: the vision module, the session, the web reads and the CDP path (S108 controller half, S109-S112, S114, S130-S138, S143 halves)
c94aade0b60dad9e01079924cf98d7bdc64af4f5 223c5fb63f7083e386298c275bb9954058e85803 2026-10-11 Phase 7 security fix SG, S108 follow-up: a WEB surface carries the browser's whole bounds
ba44fe3f4c0c491627ea32bb572a406fd8ac5882 f7fe42cabeb7a6513be2f5b5f31438a38ac75a10 2026-10-11 Docs: Phase 7 reviewed, fixed and security-reviewed (D431-D486, S101-S148, B30 extended, B31)
9b27c9c07f15c452fc1a83c102edd18f8a7f2380 1c3b99c0e4a62b2f5464c74b477c63ec296072f4 2026-10-11 Docs: the Phase 7 final matrix (arm64 twice green on every leg, amd64 Java 25 green; amd64 Java 8 legs waiting)
dcc5050d0da22a8869bf20e868f8e608823ef2fb 70348efab6edcde3585e193e3189d22a223707dd 2026-10-11 Docs: B29 and B30 confirmed, B31 signed off by the owner (2026-10-11)
aadd5f43de0643a2133e3ac1a43cb8e5410036f2 96061e5e2f137771e8292ab0286947821676df20 2026-10-11 Test harness: a runtime that refused to start may delete its token file before AgentHarness reads it
89f9504e8d612671b09f6125bce055e3c77a4cfa 921307feaacf7d20957642dd0c3dce5413a85bab 2026-10-11 Gap 94: the recorder's credential check on the executor's tokenizer and rule, the sentence names the whole-slot rule
d6720ea2a5467ce9ae5d274f32a74a1e7e4a79b7 f681e5756d2a1bf3e706727b06ba5809344e8b0f 2026-10-11 Test: RobotPathDisplayTest's focus loss runs its thief cycle up to three times
bc1e25047bf158795233f893233f4071a07c698a 832fcb252424daf6c6ccf6d27091017967fa203f 2026-10-11 Gap 99: the bindings JSON reader follows the link rule of the writers and the PNG reader
c80236ebed7df3796a2d26c66055ac2be067338c d8bedbfe6938368a855b6217d9099fc33d867ef4 2026-10-11 Gap 92: a capability declared after the target is ready says so while its probe is queued
117da5919b06f6c47e253f2c357c8cc913cf307b 7cd4dd27ea582c384af75a0029257a388d4c5391 2026-10-11 Gap 63: hook.events refuses an id that is not a hook id and answers hook.* topics only
f761b762a1183e89b5f1ede1163be342622f1ec9 8dd4ef59baf6041e9abdfd38deff6c883559b641 2026-10-11 Docs: the amd64 Java 8 legs of Phase 7's final matrix green on 832fcb2; the matrix line and the Exit item ticked
3b8ea9c5701f9e044f4c2c97c6f31ee70cad0346 23ed86bb57cf669da4259bac0c8bab6d4db1ede1 2026-10-11 Docs: the arm64 matrix on the follow-ups' final code (7cd4dd2) green on every leg
f6b8208a5cf1b19f610f4ff60ee24ed2c6d480e3 beefc68f167e29ef49258eb1b732cdbcb35313b2 2026-10-11 Docs: B32 confirmed by the owner, the three unsigned Phase 6 merge commits accepted (2026-10-11)
934c04d8521d26a286bbbc32cd8274fe606529de 433e103b1d1f8d261e9b530085b2f4e78569d352 2026-10-11 Docs: the owner's answers of 2026-10-11 evening (B33): gaps 93 and 96 not refused, the domain confirmed, a JxBrowser licence
00238d308ba712cb65cab9d6f7730848d5ee13a4 5586d8b26a82a0874e1b6379f521cd8b3f05eaad 2026-10-11 Docs: the live, opt-in OTLP run against a real OpenTelemetry Collector (Phase 6 13.4), green
c4a12fc97623ea62eb651be877c4ba0dc9e89ef1 1645d6c2c444d2a763785d656b8e24698c629121 2026-10-11 Gaps 101 and 102; gap 65 analysed
d789571438eeeda86c494c6098b994f229cca3a6 6dd61577520ed6facc7c2810a3a345e86c76f337 2026-10-11 Docs: the licence audit re-checked for the release checklist's item 4 (2026-10-11)
a3b300f1cc9917ac750ee2842b6fe993cef8546b ab3b1e837596e15ef2d52f2d81ef16a5c098c4ca 2026-10-11 Contract: the jxbrowser adapter against JxBrowser 8.18.3 (the kit run, 2026-10-11)
b6dbc58b07ef40376ff0080e6645dad763cb6972 f03827dc1921f38d566777722106df5f56b0af1d 2026-10-11 jxbrowser: dispatch per event type, the wheel's sign, no input note on line 8 (D413)
5a1c097805a9a37a4e33c32c8d2ac2434eb79516 7bd53a70201f322d24e185d1ad4c2fce891e8f27 2026-10-11 jxbrowser: observers registered on the adapter's threads within 2 s (gap 47)
cda04aa824cf3bd8336e0732c394766993b97e15 b1800066fdcb6c05699f07e90de176dd5fbd1428 2026-10-11 Docs: the JxBrowser kit run against 8.18.3 closes gaps 41, 47 and 74; gap 103
041d79609d69a005e645965c370e4344324f0637 4d1deea65521b3fe4b97eb5046951ec09530fee0 2026-10-11 Merge the JxBrowser kit run against 8.18.3: input per event type, observers within a bound (gaps 41, 47, 74)
a8dd619236c8683d3de2a37076d39159f5034fcd b4743dccc47f1538cb75b26c5a93119fbfd9b3b9 2026-10-11 Docs: the JxBrowser kit run's decisions (D493, D494, B34) and the not-verified lists brought up to date
05ab3f379942907222d7d2cca0de465648a8ce22 b7cb5b26601fdfa0014d0e1072ded3e1fd3629b4 2026-10-11 Contract: gap 95's web host checks against real JCEF (P7 13.2 item 10, P4 9.1, 9.2)
11071fdfd26d32f2052a67183c173fbad6c78245 8e531c9cb1df17271f41249b04b17514303e8074 2026-10-11 Web host: a Notice button that shows a modal dialog over the page (gap 95)
43a974da15578bd84ef940cd52be6eaeff5aa0a6 211dd77e4024c6177c204d2404c724943b91186c 2026-10-11 Web host agent module: an optional CaptureMask on the front card (gap 95; module 0.2.0)
644642d4a461eb2d8aafe6f37ddff311c798ec4d a488ff577927e24112bed10cd68682ade6ab35a2 2026-10-11 WebHostChecks: gap 95's checks (a) to (e) against real JCEF
fd39aa4db3f682a74a485586008eee28842bc85a 9df1353c9c371a96edc8c19ee04f5c8bec4be8e3 2026-10-11 Docs: gap 95 closed, the web-host checks green against real JCEF on both target Javas
fc2393ee224e2e58900a8013db61ee54b91c55db 48a99c636e74a369d971ae19ee39723f89532829 2026-10-11 Merge gap 95: the web host's checks of a masked browser, a modal dialog over the page, the late click and the middle click against real JCEF
b3fc49c65a37209dea1eef865812d2aad8648a5b bbd572c25191333635b04edc6e6091be28fa7fef 2026-10-11 Docs: gap 95's decisions (D495-D499, B34) and gap 104 (a person's middle click on an off-screen JCEF page)
d8ff98eb9b5157825f087d9d9e8e5845b38bceee d7f88319e6374899f9d048fe3581010c0544a03e 2026-10-11 Docs: a draft release record for 0.1.0 (release checklist item 11; not released)
2de84cefcd0268f41263324b9af52ad64d7d1ca8 8f556497ecdc98023e45ed302d014ff902a3e1ed 2026-10-11 Docs: two stale not-verified lines (the planner's stdin route ran live on 2026-10-10; JxBrowser input verified on 8.18.3)
6feb06fc4d992e7639da8b8b3551690e4b76a3e4 75564eb09a025241aa13dbe1384cecd6e9b8101f 2026-10-11 Docs: the draft release record's item 6 after the two not-verified lines were reconciled
2e78f13c12935e646998583bd89162757e0e8f04 96d131333daa105e0e3f3a40b5436376ec62fc7e 2026-10-11 Add the log-linear latency histogram of the listener soak and its test
1fe198ac14c31ecd35768803fac18a95c1a62c21 fe8ef96c01d947d1d8a6ff258b0d0552a46f5eaa 2026-10-11 Add ListenerSoakIT: an opt-in soak of the agent's own control listener
364785c3e4b80ae7d24930fa603151ce9834c884 5b1e5d67341df1993277ac75630944da6cad7130 2026-10-11 ListenerSoakIT: bound the agent's own live objects, and say what the heap growth is
988976b2e536628568fcb48bffe672e8d812c487 2e4a9c45bb736914dd55b02feaab29afd1f4c8bd 2026-10-11 Docs: the one-hour soak of the agent's own listener, on a Java 25 and a Java 8 target (BS2), green
f9acb2cb0d7edca2ae2e0a998840d32e08e3265d 3ebecf7da84a9990aa33ed4e6db1d420198953cf 2026-10-11 Merge the one-hour soak of the agent's own listener (BS2): green on a Java 25 and a Java 8 target
b5cb4309a2774d88b91151b7a278f5cf0bf85ce5 0890c67ebd4c27a47e2059829f95c8f8a1406bdb 2026-10-11 Docs: the release-candidate matrix on 3ebecf7, green on all nine legs (release checklist item 1)
8167a49225b46cad58d1f846e458a01f73b55363 ea045eda5acd6e89506f02c73f31ea3c1d1ae867 2026-10-12 Docs: B34 confirmed by the owner (2026-10-12): D492-D499 stand as built
cd883257cdb39d63a41c1f373a6ce6b5360d1c19 4358989b108f36faa6d63b17fcb9e2876cfe1662 2026-10-12 Gap 104: the agent's middle click no longer pastes the X11 PRIMARY selection, which blocked an off-screen page
90e70267e12ad9ee1b8c4d3505e2b80db3f8b3ca 5aee2b1ac4ec30e0ea05aaeb490456ffe5d01ce1 2026-10-12 Docs: gap 104's fix in SECURITY.md, the threat model and the draft release record (a behaviour change)
2081471f76df5e2f03a0bfaa5404e7350f3875ec ed7fa042aa2a7f7d646fe1403e52b24c00a433d9 2026-10-12 Docs: B35 confirmed by the owner (2026-10-12): D500, gap 104's fix, stands
fc15e1acacb7f32e1d0f0a0703fa40ece48f9a71 9fb09dfc25d672688c02b8e5516840059ecc20a3 2026-10-12 Docs: keep the test JDKs out of /tmp (macOS's clean-up emptied the aarch64 JDK 8 on 2026-10-12)
4497c1df38f6b66185704b5a624a0f9b03aad4ac c16ae7bd62203254cc1c88597d042e8505ef326b 2026-10-12 Docs: an integration and overlay guide for a product layer on the base
2d6d3d007e24b4e1f2c6d2362a8c3d7046aff2d1 47cd446745aed35275500e9a2bc1b0dad252bbcc 2026-10-12 Release 0.1.0
1fe1c2cc1649fde72d4685e4a7961ba7c9b02b65 436b8228e02b7e2abf83d2517a374617973685db 2026-10-12 Docs: the 0.1.0 release matrix on 47cd446, green on all nine legs; v0.1.0 tagged
8c64a9d3ca357e463b5a9ab1dca1b8be78e4a9b3 a26f7c34f6c2911ad0e7f71bfe9845488b5e0f4f 2026-10-12 Start 0.2.0-SNAPSHOT after the 0.1.0 release
```
