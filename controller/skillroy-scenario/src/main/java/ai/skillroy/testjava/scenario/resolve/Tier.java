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

package ai.skillroy.testjava.scenario.resolve;

/**
 * The resolution tiers of the design: (a) committed SQL or Java, (b) model-written SQL, (c) model reasoning. Tiers
 * form a ladder gated on the verifier (decision D25); Phase 2 has tier (a) only.
 */
public enum Tier {
    /** Committed SQL or Java, and fixtures. */
    A,
    /** Model-written SQL (Phase 5). */
    B,
    /** Model reasoning or vision (Phase 5). */
    C
}
