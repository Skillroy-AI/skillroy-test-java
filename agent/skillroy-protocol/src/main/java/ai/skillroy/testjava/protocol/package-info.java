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
 * Wire types and the JSON codec of the control channel. Shared by the agent (inside the target JVM, Java 8) and the
 * controller (Java 25) so that neither side duplicates the wire format: the codec, the wire types, the constants of
 * {@link ai.skillroy.testjava.protocol.Protocol}, the request and reply authentication of
 * {@link ai.skillroy.testjava.protocol.Auth} and the secret-key rule of
 * {@link ai.skillroy.testjava.protocol.SecretKeys}. JDK only, no dependencies. The read rules both data channels share
 * are in {@link ai.skillroy.testjava.protocol.sql}, the only package that references {@code java.sql}.
 */
package ai.skillroy.testjava.protocol;
