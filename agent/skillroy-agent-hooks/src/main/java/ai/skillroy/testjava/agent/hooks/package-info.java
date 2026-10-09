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
 * The optional agent module {@code hooks} (phase6-contracts.md sections 3 and 4): the hook engine on the relocated
 * Byte Buddy of {@code skillroy-agent-bytebuddy} (imported under {@code ai.skillroy.testjava.agent.hooks.shaded}), the
 * one advice pair that calls nothing but {@code Spy}, the recorder, the ledger, the restore with its byte
 * verification, health, stubs and the six {@code hook.*} primitives. Java 8; loaded only when the agent option
 * {@code optionalModules} names {@code skillroy-agent-hooks}.
 */
package ai.skillroy.testjava.agent.hooks;
