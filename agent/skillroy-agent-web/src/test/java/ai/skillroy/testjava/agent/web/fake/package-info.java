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
 * The neutral fake of JxBrowser (phase4-contracts.md section 10.2): this project's own classes with the member shapes
 * of the vendor's public API that the {@code jxbrowser} adapter uses (browser-spi F21), driven by the descriptor
 * {@code fake-jxbrowser.json} (the built-in descriptor with the fake's class names), so that the adapter's mechanics
 * are proven in the normal build. It renders nothing: pages are parsed, evaluations are table-driven
 * ({@link ai.skillroy.testjava.agent.web.fake.FakeScript} over a
 * {@link ai.skillroy.testjava.agent.web.fake.FakeDocument}) and results arrive on the EDT. It proves mechanics, not
 * compatibility with the product. Java 8.
 */
package ai.skillroy.testjava.agent.web.fake;
