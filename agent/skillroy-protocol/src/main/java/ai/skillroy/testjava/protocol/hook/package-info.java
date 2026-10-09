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
 * The wire types of the {@code hook.*} primitives (phase6-contracts.md section 4.1), shared by the agent's optional
 * hooks module and the controller's hook catalogue so that neither side duplicates them: what one hook is
 * ({@link ai.skillroy.testjava.protocol.hook.HookSpec} with its {@link ai.skillroy.testjava.protocol.hook.HookTarget}
 * and {@link ai.skillroy.testjava.protocol.hook.HookCapture}), what a probe found
 * ({@link ai.skillroy.testjava.protocol.hook.HookProbe}), what an install or a remove did per hook
 * ({@link ai.skillroy.testjava.protocol.hook.HookStatus}) and how an installed hook is doing
 * ({@link ai.skillroy.testjava.protocol.hook.HookHealth}). JDK only; every type is immutable and reads and writes the
 * project's JSON normal form ({@code toJson} and {@code fromJson}).
 */
package ai.skillroy.testjava.protocol.hook;
