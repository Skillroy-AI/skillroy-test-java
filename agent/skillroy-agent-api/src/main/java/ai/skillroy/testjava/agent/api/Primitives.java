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

package ai.skillroy.testjava.agent.api;

import java.util.Objects;

/**
 * Builds a {@link Primitive} from a spec and a lambda, so that small primitives need no class of their own:
 * {@code Primitives.of(spec, inv -> value)}.
 *
 * <p>Stateless; safe from any thread. The returned primitive is as thread-safe as the body.
 */
public final class Primitives {

    /** The body of a primitive built with {@link #of}; same contract as {@link Primitive#invoke}. */
    public interface Body {
        /** See {@link Primitive#invoke}. */
        Object invoke(Invocation invocation) throws Exception;
    }

    private Primitives() {
    }

    /** A primitive whose {@code spec()} is {@code spec} and whose {@code invoke} delegates to {@code body}. */
    public static Primitive of(PrimitiveSpec spec, Body body) {
        final PrimitiveSpec s = Objects.requireNonNull(spec, "spec");
        final Body b = Objects.requireNonNull(body, "body");
        return new Primitive() {
            @Override
            public PrimitiveSpec spec() {
                return s;
            }

            @Override
            public Object invoke(Invocation invocation) throws Exception {
                return b.invoke(invocation);
            }

            @Override
            public String toString() {
                return "Primitive[" + s.name() + "]";
            }
        };
    }
}
