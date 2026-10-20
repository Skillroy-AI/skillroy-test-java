/*
 * Copyright 2026 Mike Mills.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Class-file helpers shared by the optional modules that change bytecode, the hooks module and the Byteman module
 * (phase6-contracts.md section 3.6, D153): the class references of a class file
 * ({@link ai.skillroy.testjava.agent.api.instrument.ClassFileRefs}), the byte comparison of a restore
 * ({@link ai.skillroy.testjava.agent.api.instrument.ByteVerification}) and the retransformation batches
 * ({@link ai.skillroy.testjava.agent.api.instrument.RetransformBatches}); and the provenance rule of every jar the
 * agent loads from an operator's path outside its home ({@link ai.skillroy.testjava.agent.api.instrument.JarTrust},
 * Phase 6 security fix, pass 2, P2-L1-F5: the {@code modules} paths, {@code hooks.spyJar}, {@code byteman.jar}); and
 * the code-source name of a loaded class ({@link ai.skillroy.testjava.agent.api.instrument.CodeSources},
 * phase8-contracts.md section 6.2: the jar's file name, never a directory, read through a location's field getters
 * only). API of the agent only in the sense that modules may use it; the controller never sees it. Java 8, JDK only.
 */
package ai.skillroy.testjava.agent.api.instrument;
