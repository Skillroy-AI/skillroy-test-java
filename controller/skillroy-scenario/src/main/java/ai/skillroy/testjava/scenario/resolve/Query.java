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
 * A typed question about an environment ("the cheapest lendable item whose deposit is in this range"), asked by a
 * scenario's {@code resolve} section instead of naming test data. Implemented by records of a pack; {@code T} is the
 * type of the answer. The kind a scenario writes is declared by a {@link QueryType}, not by the query itself.
 *
 * <p>Implementations are immutable value objects (records); safe to share between threads.
 *
 * @param <T> the type of the answer, read from the class's own {@code implements Query<T>} declaration
 */
public interface Query<T> {
}
