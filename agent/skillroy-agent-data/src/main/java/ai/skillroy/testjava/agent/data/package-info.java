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
 * Agent module {@code data}: guarded, read-only SQL over the target application's own data sources.
 *
 * <p>{@link ai.skillroy.testjava.agent.data.DataModule} registers {@code data.sources} and {@code data.query} over the
 * {@code DataSourceProvider} and {@code MaskPolicy} SPIs of {@code skillroy-agent-api}. The statement check, the value
 * conversion and the JDBC read path are the ones the controller's JDBC channel uses too, in
 * {@code ai.skillroy.testjava.protocol.sql} (phase2-contracts.md 2.2); this module keeps {@code data.query}, the
 * provider lookup, the {@code MaskPolicy} step and the conversion of a provider's {@code QueryResult}. It uses only
 * {@code java.sql} from the JDK and is loaded by its own module class loader, so nothing here is visible to other
 * modules.
 */
package ai.skillroy.testjava.agent.data;
