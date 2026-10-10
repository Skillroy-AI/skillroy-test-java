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

/** The neutral fake's mouse-pressed event and its builder (Phase 7, the INPUT roles). */
public final class FakeMousePressed {

    static {
        ClassInitialisations.record(FakeMousePressed.class);
    }

    final FakePoint location;
    final FakeMouseButton button;
    final int clickCount;

    private FakeMousePressed(Builder b) {
        this.location = b.location;
        this.button = b.button;
        this.clickCount = b.clickCount;
    }

    public static Builder newBuilder(FakePoint location) {
        return new Builder(location);
    }

    /** The builder ({@code button}, {@code clickCount}, {@code build}). */
    public static final class Builder {
        private final FakePoint location;
        private FakeMouseButton button = FakeMouseButton.PRIMARY;
        private int clickCount = 1;

        private Builder(FakePoint location) {
            this.location = location;
        }

        public Builder button(FakeMouseButton button) {
            this.button = button;
            return this;
        }

        public Builder clickCount(int clickCount) {
            this.clickCount = clickCount;
            return this;
        }

        public FakeMousePressed build() {
            return new FakeMousePressed(this);
        }
    }
}
