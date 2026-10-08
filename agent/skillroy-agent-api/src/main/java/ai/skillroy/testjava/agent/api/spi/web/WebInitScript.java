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
 * A script registered with {@link WebBrowser#addInitScript}: it runs in every new document of the browser until
 * {@link #cancel() cancelled}, at the {@link Timing} the technology allowed.
 */
public interface WebInitScript extends WebSubscription {

    /** When an init script runs in a new document. */
    enum Timing {
        /** Before the page's own scripts ({@link WebCapability#INIT_SCRIPT_DOCUMENT_START}). */
        DOCUMENT_START,
        /** As early as the technology allows: after the document loaded. */
        DOCUMENT_LOADED
    }

    /** When this script runs; never null. */
    Timing timing();
}
