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
 * The agent module {@code web} (phase4-contracts.md section 4): {@link ai.skillroy.testjava.agent.web.WebModule}, the
 * thirteen {@code web.*} primitives written once against the embedded-browser SPI
 * ({@code ai.skillroy.testjava.agent.api.spi.web}), the browser index, the {@code web.*} events, the page-event tap
 * and the capability cycle. The built-in adapters live in the sub-packages {@code jcef} and {@code jxbrowser}, the
 * descriptor-driven reflection plumbing in {@code reflect}. Java 8; no vendor artifact in any scope; no AWT class is
 * touched at install.
 */
package ai.skillroy.testjava.agent.web;
