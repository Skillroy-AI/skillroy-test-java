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
 * Domain pack {@code webhost}: the controller half of the sample web host (phase4-contracts.md 9.3). It has no verbs
 * of its own (the base web verbs drive the host's pages), the sync policy of a target whose readiness is the
 * {@code web.ready} topic, the fact root {@code webhost} and a recovery that resets the front page.
 */
package ai.skillroy.testjava.samples.webhost.pack;
