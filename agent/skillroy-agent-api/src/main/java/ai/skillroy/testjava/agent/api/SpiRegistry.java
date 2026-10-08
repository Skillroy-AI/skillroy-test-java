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

import java.util.List;

/**
 * Typed registry of SPI implementations (the interfaces in {@code ai.skillroy.testjava.agent.api.spi} and its
 * sub-package {@code spi.web}). Lets a domain module plug into generic modules without either depending on the other.
 *
 * <p>Implementations are thread-safe; lookups see a consistent snapshot.
 */
public interface SpiRegistry {

    /** Registers an implementation; closing the returned handle unregisters it. */
    <T> AutoCloseable register(Class<T> type, T implementation);

    /** Registration order. */
    <T> List<T> all(Class<T> type);

    /** Null if none. */
    <T> T first(Class<T> type);
}
