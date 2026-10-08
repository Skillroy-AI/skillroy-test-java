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
 * Receives a browser's {@link WebEvent}s from {@link WebBrowser#subscribe}.
 *
 * <p>Called on whatever thread the technology delivers its events on (with JCEF's off-screen rendering, the EDT), and
 * possibly on more than one: an implementation is thread-safe, returns at once, never blocks and hands any further
 * work to an agent worker. The adapter catches anything it throws.
 */
public interface WebEventListener {

    /**
     * @param browser the handle the subscription was made on
     * @param event never null
     */
    void onEvent(WebBrowser browser, WebEvent event);
}
