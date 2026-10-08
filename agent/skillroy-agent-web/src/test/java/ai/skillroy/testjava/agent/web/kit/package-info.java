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
 * The adapter contract kit (phase4-contracts.md section 10.1), published as this module's test-jar: a technology's
 * test module extends {@link ai.skillroy.testjava.agent.web.kit.WebEngineAdapterContract} and implements
 * {@link ai.skillroy.testjava.agent.web.kit.WebAdapterFixture} over a real browser (or a fake of it); the kit's items
 * check the adapter rules of section 3 through the adapter alone. {@link ai.skillroy.testjava.agent.web.kit.KitPages}
 * and {@link ai.skillroy.testjava.agent.web.kit.KitScripts} are the pages and the expressions the kit uses,
 * {@link ai.skillroy.testjava.agent.web.kit.EdtWatchdog} fails a test whose adapter held the EDT, and
 * {@link ai.skillroy.testjava.agent.web.kit.RecordingClassLoader} records what a probe loads. Java 8.
 */
package ai.skillroy.testjava.agent.web.kit;
