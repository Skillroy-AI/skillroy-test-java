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

/** How a browser draws itself, as far as its adapter can tell; reported by {@link WebBrowser#rendering()}. */
public enum WebRendering {

    /** Into a native window of its own (JCEF's windowed browser; JxBrowser's hardware-accelerated mode). */
    WINDOWED,

    /** Off screen, into a lightweight Swing component (an application's own component; JxBrowser off screen). */
    OFFSCREEN_LIGHTWEIGHT,

    /** Off screen, into a heavyweight component (JCEF's off-screen browser: a JOGL canvas). */
    OFFSCREEN_HEAVYWEIGHT,

    /** The adapter cannot tell (JxBrowser 9, where the mode is per browser and not readable). */
    UNKNOWN
}
