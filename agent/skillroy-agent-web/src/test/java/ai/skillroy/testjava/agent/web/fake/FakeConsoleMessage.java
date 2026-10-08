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

/** The neutral fake's console message ({@code level()}, {@code message()}, {@code source()}, {@code lineNumber()}). */
public final class FakeConsoleMessage {

    static {
        ClassInitialisations.record(FakeConsoleMessage.class);
    }

    private final FakeConsoleMessageLevel level;
    private final String message;
    private final String source;
    private final int lineNumber;

    FakeConsoleMessage(FakeConsoleMessageLevel level, String message, String source, int lineNumber) {
        this.level = level;
        this.message = message;
        this.source = source;
        this.lineNumber = lineNumber;
    }

    public FakeConsoleMessageLevel level() {
        return level;
    }

    public String message() {
        return message;
    }

    public String source() {
        return source;
    }

    public int lineNumber() {
        return lineNumber;
    }
}
