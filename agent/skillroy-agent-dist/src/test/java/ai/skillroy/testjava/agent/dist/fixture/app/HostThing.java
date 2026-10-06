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

package ai.skillroy.testjava.agent.dist.fixture.app;

/** A host object handed to the agent in EMBEDDED mode; the test module reads it back reflectively. */
public final class HostThing {

    private final String name;

    public HostThing(String name) {
        this.name = name;
    }

    public String greeting() {
        return "hello from " + name;
    }

    @Override
    public String toString() {
        return "HostThing[" + name + "]";
    }
}
