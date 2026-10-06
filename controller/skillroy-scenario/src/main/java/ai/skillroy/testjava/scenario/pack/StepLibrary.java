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

import ai.skillroy.testjava.scenario.ScenarioException;

import java.util.List;
import java.util.Set;

/**
 * A pack's domain verbs ({@code signOn}, {@code scan}, ...) and how each compiles to primitive calls. Compiling
 * happens once, ahead of any run, so a verb must be a pure function of its arguments: no agent access, no clock, no
 * randomness. That is what makes {@code plan.json} reviewable and byte-identical across compilations.
 *
 * <p>The compiler looks verbs up in the pack's library first, then in {@code BaseSteps}; {@code expect} is always the
 * base verb. Implementations are called from the compiling thread only and need not be thread-safe.
 */
public interface StepLibrary {

    /** The verbs this library compiles; used for lookup and to list the available verbs in errors. */
    Set<String> verbs();

    /**
     * Compiles one step.
     *
     * <p>args is the YAML value of the verb (Map, List, String, Number, …) with $references left intact.
     *
     * @param verb one of {@link #verbs()}
     * @param args a private copy the library may keep or modify; YAML numbers are {@code Long} or {@code Double}
     * @param ctx the step's line, the pack, and where to register cleanup calls
     * @return the calls in execution order; may be empty
     * @throws ScenarioException if the arguments are wrong; the compiler adds the scenario id and line when absent
     */
    List<PlannedCall> compile(String verb, Object args, CompileContext ctx) throws ScenarioException;
}
