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
 * The CDP path of the web verbs (phase4-contracts.md section 5): {@link ai.skillroy.testjava.cdp.TargetProbe} checks
 * every page target of a remote-debugging endpoint with a bounded {@code Runtime.evaluate}, {@link
 * ai.skillroy.testjava.cdp.CdpAttach} runs Playwright's {@code connectOverCDP} under an external deadline and maps
 * pages by target id, and {@link ai.skillroy.testjava.cdp.CdpWebPath} serves the web primitives through Playwright
 * with the shared script library, found by the executor through {@code ServiceLoader<WebPath>} (D49).
 *
 * <p>The module is optional: nothing in the scenario module, the JUnit glue or the CLI depends on it, and nothing in
 * it starts the Playwright driver until a {@code CdpAttach} connects. A JUnit suite gets it with a Maven dependency on
 * {@code ai.skillroy:skillroy-cdp}; the CLI loads it with {@code --pack}, which needs every jar of its run-time class
 * path beyond the CLI's own: {@code skillroy-cdp-<version>.jar}, {@code playwright-1.63.0.jar}, {@code
 * driver-1.63.0.jar}, {@code driver-bundle-1.63.0.jar}, {@code gson-2.14.0.jar}, {@code jspecify-1.0.1.jar} and
 * {@code opentest4j-1.3.0.jar}.
 *
 * <p>Two event sequences: a web call served here returns the agent's event sequence ({@code evt.query}'s {@code
 * lastSeq} before and after the call) in {@code seqBefore} and {@code seqAfter}, while the events this path sees
 * itself ({@code web.tap}, {@code web.ready}, {@code web.load}) are numbered by the path's own sequence and read with
 * {@code eventsSince}.
 *
 * <p>The Playwright driver is extracted once per JVM into a {@code playwright-java-*} directory under the Java
 * temporary directory (about 140 MB on Linux x86-64), or under {@code PLAYWRIGHT_DRIVER_TMPDIR} when that variable is
 * set; a pre-extracted driver named by Playwright's {@code PLAYWRIGHT_DRIVER_DIR} skips the extraction.
 */
package ai.skillroy.testjava.cdp;
