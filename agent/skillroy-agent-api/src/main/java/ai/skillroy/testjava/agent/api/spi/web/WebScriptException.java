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

package ai.skillroy.testjava.agent.api.spi.web;

/**
 * A script that {@link WebBrowser#evaluate} ran threw, or a promise it awaited was rejected; the primitives answer it
 * as {@code SCRIPT_ERROR} with the message and the stack.
 */
public final class WebScriptException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String stack;

    /**
     * @param message the error's message as the page saw it; may be null
     * @param stack the script's stack trace as text, or null when the technology reported none
     */
    public WebScriptException(String message, String stack) {
        super(message);
        this.stack = stack;
    }

    /** The script's stack trace as text, or null when the technology reported none. */
    public String stack() {
        return stack;
    }
}
