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

/** The neutral fake's mouse-wheel event and its builder, with {@code float} deltas (Phase 7, the INPUT roles). */
public final class FakeMouseWheel {

    static {
        ClassInitialisations.record(FakeMouseWheel.class);
    }

    final FakePoint location;
    final float deltaX;
    final float deltaY;

    private FakeMouseWheel(Builder b) {
        this.location = b.location;
        this.deltaX = b.deltaX;
        this.deltaY = b.deltaY;
    }

    public static Builder newBuilder(FakePoint location) {
        return new Builder(location);
    }

    /** The builder ({@code deltaX}, {@code deltaY}, {@code build}). */
    public static final class Builder {
        private final FakePoint location;
        private float deltaX;
        private float deltaY;

        private Builder(FakePoint location) {
            this.location = location;
        }

        public Builder deltaX(float deltaX) {
            this.deltaX = deltaX;
            return this;
        }

        public Builder deltaY(float deltaY) {
            this.deltaY = deltaY;
            return this;
        }

        public FakeMouseWheel build() {
            return new FakeMouseWheel(this);
        }
    }
}
