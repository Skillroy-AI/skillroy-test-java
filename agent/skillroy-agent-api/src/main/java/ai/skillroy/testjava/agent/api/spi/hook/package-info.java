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
 * The hook engine's service provider interface (phase6-contracts.md section 3.6): implemented by the optional hooks
 * module and used by a domain module that wants hooks from Java. Its types are the protocol's
 * {@code ai.skillroy.testjava.protocol.hook} wire types.
 *
 * <p>Phase 8 (phase8-contracts.md sections 6.5 and 6.6): {@link ai.skillroy.testjava.agent.api.spi.hook.ClassDeny},
 * the class deny a domain module registers and both the hooks and the Byteman module ask before they change a class's
 * bytecode, and {@code HookEngine.deny()}, the catalogue deny-list the engine holds.
 */
package ai.skillroy.testjava.agent.api.spi.hook;
