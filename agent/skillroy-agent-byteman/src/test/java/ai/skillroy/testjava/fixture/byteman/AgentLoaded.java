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

package ai.skillroy.testjava.fixture.byteman;

/**
 * A fixture only {@code ClassBoundTest} loads, into a loader whose class is under the agent's prefix: a class of its
 * own, so that its copy (which stays loaded) matches no rule of another test running in the same JVM.
 */
public class AgentLoaded {

    public int ceiling(String code) {
        return code == null ? 0 : code.length();
    }
}
