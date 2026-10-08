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

/** The neutral fake's console event ({@code consoleMessage()}). */
public final class FakeConsoleMessageReceived {

    static {
        ClassInitialisations.record(FakeConsoleMessageReceived.class);
    }

    private final FakeConsoleMessage message;

    FakeConsoleMessageReceived(FakeConsoleMessage message) {
        this.message = message;
    }

    public FakeConsoleMessage consoleMessage() {
        return message;
    }
}
