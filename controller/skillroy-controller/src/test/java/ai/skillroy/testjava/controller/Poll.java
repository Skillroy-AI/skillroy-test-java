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

package ai.skillroy.testjava.controller;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/** Test helper: waits for a condition with a deadline instead of sleeping a fixed time. */
final class Poll {

    private Poll() {
    }

    static void until(String what, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                fail("timed out after " + timeout.toMillis() + " ms waiting for " + what);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for " + what);
            }
        }
    }
}
