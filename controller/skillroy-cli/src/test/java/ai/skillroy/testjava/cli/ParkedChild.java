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

package ai.skillroy.testjava.cli;

import java.io.IOException;

/**
 * A child JVM for the {@code jvms} tests: prints {@code ready} once it runs, then waits until its standard input is
 * closed, so the test controls its lifetime without signals or sleeps.
 */
public final class ParkedChild {

    private ParkedChild() {
    }

    public static void main(String[] args) throws IOException {
        System.out.println("ready");
        System.out.flush();
        while (System.in.read() >= 0) {
            // Park until the parent closes our standard input.
        }
    }
}
