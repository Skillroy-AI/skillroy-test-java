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

package ai.skillroy.testjava.inference.spi;

import ai.skillroy.testjava.scenario.resolve.CodecException;
import ai.skillroy.testjava.scenario.resolve.RecordCodec;

import java.lang.reflect.Type;

/**
 * Decodes a structured answer through {@link RecordCodec#DEFAULT} (phase5-contracts.md 3.3), so that a model's answer
 * and a bindings file obey one set of rules: records by component name (an unknown key is an error, a missing one is
 * null), strings, booleans, whole numbers, decimals, {@code double} and {@code float} components as JSON numbers (a
 * structured answer such as {@code SqlProposal.confidence}; text is not a number), enums by name, ISO dates, lists and
 * maps with string keys. Nothing of its own: the codec reads and writes every type a structured answer uses.
 *
 * <p>Stateless; safe from any thread.
 */
public final class Structured {

    private Structured() {
    }

    /**
     * The typed value of a structured answer.
     *
     * @throws CodecException if the answer does not fit the type; the message names the field, never the value
     */
    public static <T> T decode(Class<T> type, Object jsonLike) throws CodecException {
        @SuppressWarnings("unchecked")
        T value = (T) decode((Type) type, jsonLike);
        return value;
    }

    /**
     * The typed value of a structured answer.
     *
     * @throws CodecException if the answer does not fit the type
     */
    public static Object decode(Type type, Object jsonLike) throws CodecException {
        return RecordCodec.DEFAULT.decode(type, jsonLike);
    }

    /**
     * The JSON-like form of a typed value, as the codec writes it ({@code double} and {@code float} components as
     * numbers, keys sorted). What a scripted answer is built from.
     *
     * @throws IllegalArgumentException for a type the codec cannot encode
     */
    public static Object encode(Object typed) {
        return RecordCodec.DEFAULT.encode(typed);
    }
}
