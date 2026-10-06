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

package ai.skillroy.testjava.scenario;

import java.util.Map;

/**
 * An element of {@code verify} and of a step's {@code expect}: a fact {@link Expectation} (Phase 1) or a stored-row
 * {@link RowCheck} (phase2-contracts.md 7.4). In {@code plan.json} the element's keys decide which: {@code path} for
 * an expectation, {@code db} for a row check.
 *
 * <p>Implementations are immutable; safe to share between threads.
 */
public sealed interface Check permits Expectation, RowCheck {

    /** The {@code plan.json} shape; a new map on each call. */
    Map<String, Object> toJson();

    /** A one-line description for messages and reports. */
    String describe();

    /** The guarding capability id (the check is skipped when it is not OK), or null. */
    String capability();
}
