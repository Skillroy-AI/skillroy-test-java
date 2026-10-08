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

package ai.skillroy.testjava.agent.web;

import ai.skillroy.testjava.protocol.Topics;

/**
 * The event topics the module publishes (phase4-contracts.md section 4.3), all with source {@value #SOURCE} and the
 * browser's ref in {@code browser}:
 * <ul>
 *   <li>{@value #LOAD} {@code {browser, url, frameKey?, kind: LOAD_FINISHED | LOAD_FAILED | NAVIGATED, error?}};</li>
 *   <li>{@value #READY} {@code {browser, url, signal: LOAD | TAP | FLAG, name?}} (D57);</li>
 *   <li>{@value #CONSOLE} {@code {browser, level, text, source?, line?, truncated?}}, the text at most
 *       {@value WebPrimitives#CONSOLE_TEXT_MAX} characters, secrets hidden;</li>
 *   <li>{@value #TAP} {@code {browser, name, detail?, truncated?}};</li>
 *   <li>{@value #CLOSED} {@code {browser}};</li>
 *   <li>{@value #BROWSERS_CHANGED} {@code {added: [ref], removed: [ref]}}.</li>
 * </ul>
 * Constants only; usable from any thread.
 */
final class WebTopics {

    /** The module id, the source of every event. */
    static final String SOURCE = "web";
    static final String LOAD = Topics.WEB_LOAD;
    static final String READY = Topics.WEB_READY;
    static final String CONSOLE = Topics.WEB_CONSOLE;
    static final String TAP = Topics.WEB_TAP;
    static final String CLOSED = Topics.WEB_CLOSED;
    static final String BROWSERS_CHANGED = Topics.WEB_BROWSERS_CHANGED;

    private WebTopics() {
    }
}
