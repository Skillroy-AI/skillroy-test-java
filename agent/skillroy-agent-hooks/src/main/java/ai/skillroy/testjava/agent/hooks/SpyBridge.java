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


package ai.skillroy.testjava.agent.hooks;

import ai.skillroy.testjava.agent.spy.Spy;

/**
 * Every static call of the engine into {@link Spy} (install, uninstall, the slot and the counters). It references
 * {@code Spy}, so the engine calls it only once {@code Spy} is on the bootstrap path; its signatures name no
 * {@code Spy} type (BS3 RESULT section 9 fact 9: a caller passing an engine object where a {@code Spy} type is expected
 * would make the verifier load that type when the caller is linked).
 */
final class SpyBridge {

    private SpyBridge() {
    }

    static boolean install(Object handler) {
        return Spy.install((Spy.Handler) handler);
    }

    static boolean uninstall(Object handler) {
        return Spy.uninstall((Spy.Handler) handler);
    }

    static boolean held() {
        return Spy.held();
    }

    static long[] counters() {
        return Spy.counters();
    }
}
