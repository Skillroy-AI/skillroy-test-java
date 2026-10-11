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

import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;

/** The neutral fake's point ({@code Point.of(int, int)}), in the vendor's pixels (Phase 7, the INPUT roles). */
public final class FakePoint {

    static {
        ClassInitialisations.record(FakePoint.class);
    }

    final int x;
    final int y;

    private FakePoint(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public static FakePoint of(int x, int y) {
        return new FakePoint(x, y);
    }
}
