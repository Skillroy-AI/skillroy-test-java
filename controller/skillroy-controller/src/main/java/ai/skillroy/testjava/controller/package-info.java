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
 * The controller's connection layer: endpoints, the {@link ai.skillroy.testjava.controller.AgentConnection} seam the
 * scenario executor is written against, its exceptions and the capability matrix; the HTTP/SSE client
 * ({@link ai.skillroy.testjava.controller.AgentClient}, HMAC-authenticated through the shared
 * {@link ai.skillroy.testjava.protocol.Auth}: the token never travels), discovery, attach
 * ({@link ai.skillroy.testjava.controller.AttachSession}, the only user of {@code jdk.attach}), the agent home and
 * the target launcher. Java 25.
 */
package ai.skillroy.testjava.controller;
