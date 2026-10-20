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

package ai.skillroy.testjava.fixture.hooks;

/**
 * A class defined from two code sources (phase8-contracts.md section 6.4, gap 113): the test-classes copy of the
 * system loader and a copy a child-first loader defines from a temporary jar. Used by no other test, so that no copy a
 * child loader of another test class left in the JVM widens the set.
 */
public class TwoJars {

    private int lends;

    /** Counts one loan. */
    public int lend() {
        return ++lends;
    }
}
