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

package ai.skillroy.testjava.agent.web.fake;

/**
 * The neutral fake's observer, shaped like the vendor's event observer (one method, {@code on(event)}); the adapter
 * implements it with a {@code java.lang.reflect.Proxy}.
 *
 * @param <E> the event type
 */
public interface FakeObserver<E> {

    void on(E event);
}
