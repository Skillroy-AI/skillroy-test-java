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
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One query kind: the name scenarios write ({@code query: SystemLimit}), the record that carries its arguments, the
 * type of its value, and the {@link Codec} that converts between those and JSON-like data. Registered in a pack's
 * {@link ResolutionCatalogue}.
 *
 * <p>Built through {@link #of}; the canonical constructor is not API (a later phase may add a component).
 * Immutable; safe to share between threads.
 *
 * @param kind the kind scenarios write, {@code [A-Za-z][A-Za-z0-9_.]*}
 * @param queryClass the record carrying the query's arguments
 * @param valueType the type of the answer ({@code T})
 * @param codec how arguments and values are decoded and encoded
 * @param <Q> the query type
 * @param <T> the value type
 */
public record QueryType<Q extends Query<T>, T>(String kind, Class<Q> queryClass, Type valueType, Codec codec) {

    private static final Pattern KIND = Pattern.compile("[A-Za-z][A-Za-z0-9_.]*");

    /**
     * @throws IllegalArgumentException if the kind is not a name or a component is missing
     */
    public QueryType {
        if (kind == null || !KIND.matcher(kind).matches()) {
            throw new IllegalArgumentException("a query kind must match [A-Za-z][A-Za-z0-9_.]*, found " + kind);
        }
        Objects.requireNonNull(queryClass, "queryClass");
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(codec, "codec");
    }

    /**
     * A query kind with {@link RecordCodec#DEFAULT}. The value type is read from the class's own
     * {@code implements Query<T>} declaration, so that a parameterised {@code T} ({@code Map<String,String>}) needs
     * no type-token helper.
     *
     * @throws IllegalArgumentException if the class does not declare a concrete {@code T}
     */
    public static <Q extends Query<T>, T> QueryType<Q, T> of(String kind, Class<Q> queryClass) {
        Objects.requireNonNull(queryClass, "queryClass");
        return new QueryType<>(kind, queryClass, GenericTypes.argument(queryClass, Query.class, 0),
                RecordCodec.DEFAULT);
    }

    /** This kind with another codec. */
    public QueryType<Q, T> withCodec(Codec newCodec) {
        return new QueryType<>(kind, queryClass, valueType, newCodec);
    }
}
