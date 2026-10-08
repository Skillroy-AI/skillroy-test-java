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
 * Agent module {@code webhost}: the sample web host's browser seeds and readiness (Phase 4 contract, section 9.2).
 * It depends on the agent API and on the host's registry class, nothing else; the generic web module turns the seeds
 * into browser handles.
 */
package ai.skillroy.testjava.samples.webhost.agent;
