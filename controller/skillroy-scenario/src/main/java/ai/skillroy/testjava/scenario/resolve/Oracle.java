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

import java.lang.reflect.Type;

/**
 * Computes an expected outcome from bound values ({@code ExpectedAuthorisation}, {@code Money.negate}): committed,
 * reviewed, unit-tested Java in place of an expression language (decision D6). Its output becomes
 * {@code $oracle.<name>}; a scenario may guard it with {@code must_equal}.
 *
 * <p>{@link #compute} is PURE: no I/O, no agent, no clock, no randomness, no static state. Same input, same output, on
 * any JVM. An exception from it is a pack defect (ENVIRONMENT), never a scenario failure. A model never evaluates an
 * oracle. Shared by every run in the JVM: stateless.
 *
 * <p>Arguments: a map decodes as the input record; a list decodes positionally onto the record's components; when
 * the input type is itself a {@code List}, the list is the input; when it is neither a record nor a list, the list
 * must have exactly one element; a scalar decodes as the input itself. A null output is allowed (JSON null).
 *
 * @param <I> the input type (codec-supported)
 * @param <O> the output type (codec-supported)
 */
public interface Oracle<I, O> {

    /** The name scenarios write: {@code "ExpectedAuthorisation"}, {@code "Money.negate"}. */
    String fn();

    /** The input type; by default read from the class's {@code implements Oracle<I, O>} declaration. */
    default Type inputType() {
        return GenericTypes.argument(getClass(), Oracle.class, 0);
    }

    /** The output type; by default read from the class's {@code implements Oracle<I, O>} declaration. */
    default Type outputType() {
        return GenericTypes.argument(getClass(), Oracle.class, 1);
    }

    /** The expected outcome for this input. Pure. */
    O compute(I input);
}
