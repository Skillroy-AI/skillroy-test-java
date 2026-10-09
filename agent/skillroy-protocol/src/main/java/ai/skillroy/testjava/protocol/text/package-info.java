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
 * Bounds on text that the target, a page or a channel supplies, shared by the agent (Java 8) and the controller:
 * cutting and sizing values ({@link ai.skillroy.testjava.protocol.text.TextBounds}) and searching them with a regular
 * expression under a time budget ({@link ai.skillroy.testjava.protocol.text.BoundedRegex}). Security fixes S43 and
 * S46 of the repeat review (phase6-contracts.md 12.2).
 */
package ai.skillroy.testjava.protocol.text;
