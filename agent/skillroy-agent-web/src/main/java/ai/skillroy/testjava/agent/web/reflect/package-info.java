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
 * The descriptor-driven reflection plumbing both built-in adapters use (phase4-contracts.md section 4.7, D54): a
 * {@link ai.skillroy.testjava.agent.web.reflect.Descriptor} names the vendor's classes and members by role,
 * {@link ai.skillroy.testjava.agent.web.reflect.ReflectiveApi} resolves it once per vendor class loader (cached
 * weakly) and implements vendor interfaces with {@code java.lang.reflect.Proxy}; a missing optional role is a missing
 * capability, never an error. {@link ai.skillroy.testjava.agent.web.reflect.LoadedClasses} tells whether the host has
 * loaded a class without loading it, and {@link ai.skillroy.testjava.agent.web.reflect.AdapterThreads} are the few
 * threads an adapter owns so that nothing runs on a technology's callback thread. Java 8; JDK types only.
 */
package ai.skillroy.testjava.agent.web.reflect;
