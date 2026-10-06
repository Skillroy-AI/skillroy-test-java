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

package ai.skillroy.testjava.scenario.pack;

import ai.skillroy.testjava.controller.AgentConnection;

/**
 * A root name usable in expectation paths ("loan", "app", "slip", "ui", …) and how to fetch its current value.
 * Expectations are evaluated against facts read from the application, never against screenshots.
 *
 * <p>{@link #fetch} is called from the executor's thread, once per root per evaluation attempt (expectations are
 * retried every 100 ms until they hold or the step times out), so it must be cheap, side-effect free and bounded.
 */
public interface FactProvider {

    /** The first segment of the paths this provider answers; a pack root hides a base root of the same name. */
    String root();

    /**
     * The current value: JSON-like data (Map, List, String, Number, Boolean or null), normally the {@code value()}
     * of an RpcResult.
     *
     * @throws Exception any failure; the attempt does not hold and is retried until the step timeout
     */
    Object fetch(AgentConnection agent) throws Exception;
}
