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
 * Controller-side data access (phase2-contracts.md 2.1, 4, 7), below {@code skillroy-scenario}: the
 * {@link ai.skillroy.testjava.data.DataChannel} abstraction with its two implementations (through the agent's
 * {@code data.query}, and over JDBC from the controller) and the router
 * {@link ai.skillroy.testjava.data.DataChannels}; {@code credentials.yaml}
 * ({@link ai.skillroy.testjava.data.Credentials}); the value mask every run output passes
 * ({@link ai.skillroy.testjava.data.SecretMask}); the controller's row masks
 * ({@link ai.skillroy.testjava.data.RowMask}); and, in {@code …data.yaml}, the strict YAML reader. It keeps
 * {@code java.sql} code and its test database out of the scenario module. The statement guard, value conversion and
 * JDBC read path are shared with the agent through {@code ai.skillroy.testjava.protocol.sql}.
 *
 * <p>Java 25. Everything here is thread-safe unless a class says otherwise.
 */
package ai.skillroy.testjava.data;
