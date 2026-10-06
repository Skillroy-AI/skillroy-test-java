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
import java.util.List;

/**
 * Converts between JSON-like data (scenario args, slot values, bindings files: maps, lists, strings, numbers,
 * booleans, null) and a pack's typed objects (query records, value types, oracle inputs and outputs).
 * {@link RecordCodec#DEFAULT} covers records and the common scalar types; a pack may supply its own.
 *
 * <p>Implementations are stateless or thread-safe: one codec serves every run in the JVM.
 */
public interface Codec {

    /**
     * Compile time: checks the shape of {@code template}, whose {@code $references} are still in place (a reference
     * may stand for any value). Never throws for a bad template.
     *
     * @return the problems found, empty when the template fits the type
     */
    List<String> check(Type type, Object template);

    /**
     * Bind time: the typed object for {@code jsonLike}, references resolved.
     *
     * @throws CodecException if the value does not fit the type; the message names the field and never quotes a
     *     secret
     */
    Object decode(Type type, Object jsonLike) throws CodecException;

    /** The JSON-like form of a typed object; total for the types the codec supports. */
    Object encode(Object typed);

    /**
     * Why this codec cannot handle {@code type} at all, or null when it can. Asked when a pack's catalogue is built,
     * so that an unsupported query, value or oracle type is a compile error rather than a bind-time surprise. The
     * default accepts every type.
     */
    default String unsupported(Type type) {
        return null;
    }
}
