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
 * The web host sample (Phase 4 contract, section 9.1): a deliberately small Swing application that embeds JCEF
 * through jcefmaven the way a desktop application embeds web views, as the second sample target beside the lending
 * desk. It knows nothing about the framework.
 *
 * <ul>
 *   <li>{@link ai.skillroy.testjava.samples.webhost.WebHostApp}: {@code main}, start-up, shutdown; the class
 *       description lists the JVM arguments the host needs and its exact log lines.</li>
 *   <li>{@link ai.skillroy.testjava.samples.webhost.WebHostOptions}: the {@code -Dwebhost.*} system properties.</li>
 *   <li>{@link ai.skillroy.testjava.samples.webhost.WebHostBrowsers}: the static registry through which code in the
 *       same JVM (the sample's agent module, tests) reaches the browsers.</li>
 *   <li>{@link ai.skillroy.testjava.samples.webhost.WebHostPages}: the three pages, their markup and script, as
 *       {@code data:} URLs.</li>
 *   <li>{@link ai.skillroy.testjava.samples.webhost.FirstRunSentinels}: the files that keep Chromium's first-run
 *       terms dialog from blocking start-up.</li>
 * </ul>
 *
 * <h2>Build</h2>
 * The module is built only with the Maven profile {@code jcef}. jcefmaven is a compile dependency; the natives of the
 * platform named by {@code skillroy.jcef.platform} (default {@code linux-amd64}) are a test dependency, because only a
 * running browser needs them: the module's display tests and the end-to-end acceptance, which puts them on the host's
 * class path. Tests that start the host need a display and are skipped in a headless build; each test class that
 * starts the host runs in a surefire execution, and so a JVM, of its own, because CEF can be initialised once per
 * JVM.
 *
 * <h2>Threading</h2>
 * {@code WebHostApp.launch} runs on the caller's thread and builds the window on the event dispatch thread; CEF calls
 * the host's load handler on its UI thread (the event dispatch thread with off-screen rendering). The registry and the
 * accessors are safe from any thread.
 */
package ai.skillroy.testjava.samples.webhost;
