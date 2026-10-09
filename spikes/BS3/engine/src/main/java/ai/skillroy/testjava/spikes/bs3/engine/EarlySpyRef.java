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

package ai.skillroy.testjava.spikes.bs3.engine;

import ai.skillroy.testjava.spikes.bs3.spy.Spy;

/**
 * A class that references {@link Spy} and is deliberately linked before the append, to show that a failed
 * resolution sticks to the referencing class (JVMS 5.4.3) even after {@code Spy} becomes resolvable.
 */
final class EarlySpyRef {

    private EarlySpyRef() {
    }

    static String touch() {
        return Spy.VERSION + "/" + Spy.counters().length;
    }
}
