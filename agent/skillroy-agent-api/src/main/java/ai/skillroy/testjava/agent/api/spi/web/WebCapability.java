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
 * The optional operations of a {@link WebBrowser} handle, as {@link WebBrowser#capabilities()} reports them. A missing
 * flag is a missing capability, not an error: the primitive that needs it answers {@code CAPABILITY_UNAVAILABLE} with
 * the reason. {@link #RAW_PROTOCOL} is reserved: no method uses it and no adapter reports it. The order of the
 * constants is frozen (the adapter contract kit and the SPI shape test rely on it).
 */
public enum WebCapability {

    /**
     * {@link WebBrowser#evaluate}: an expression's value as JSON text. {@link WebBrowser#addInitScript} needs it
     * too.
     */
    EVALUATE,

    /** {@link WebEval#awaitPromise()}: the settled value of a promise as the result. */
    AWAIT_PROMISE,

    /** {@link WebBrowser#frames()}, and frame keys in {@link WebEval}. */
    FRAMES,

    /**
     * {@link WebEvent.Kind#LOAD_FINISHED}, {@link WebEvent.Kind#LOAD_FAILED} and {@link WebEvent.Kind#NAVIGATED}
     * through {@link WebBrowser#subscribe}.
     */
    EVENTS_LOAD,

    /** {@link WebEvent.Kind#CONSOLE} through {@link WebBrowser#subscribe}. */
    EVENTS_CONSOLE,

    /**
     * {@link WebBrowser#addInitScript} runs scripts before the page's own
     * ({@link WebInitScript.Timing#DOCUMENT_START}).
     */
    INIT_SCRIPT_DOCUMENT_START,

    /** {@link WebBrowser#resize} and {@link WebBrowser#clearResize()}: a viewport override that leaves Swing alone. */
    RESIZE,

    /** {@link WebBrowser#bitmap()}: the page as rendered, whether its component is showing or not. */
    BITMAP,

    /** {@link WebBrowser#cdpTargetId()}: the browser's CDP target id. */
    CDP_TARGET_ID,

    /**
     * Synthetic pointer input into a page: {@link WebBrowser#dispatchPointer} ({@code web.pointer}; phase7-contracts.md
     * section 4.1, amendment A66). Key input ({@code Input.dispatchKeyEvent}, {@code Input.insertText}) is a later use
     * of the same flag; the DOM primitives still click with {@code el.click()}.
     */
    INPUT,

    /**
     * Reserved for a raw protocol passthrough, an adapter-specific extension that is never part of the common
     * contract. No method uses it and no adapter reports it.
     */
    RAW_PROTOCOL
}
