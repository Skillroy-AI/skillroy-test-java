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
 * Where a browser's {@link WebBrowser#bitmap()} comes from, as {@link WebBrowser#bitmapSource()} reports it and
 * {@code web.bitmap} names it in its {@code source} (Phase 4 contract §3, amendment A37). Informational: nothing
 * depends on it but the report.
 */
public enum BitmapSource {

    /**
     * A screenshot through the browser's DevTools protocol ({@code Page.captureScreenshot}: the built-in jcef
     * adapter).
     */
    PROTOCOL,

    /**
     * The technology's own bitmap call (the built-in jxbrowser adapter's {@code Browser.bitmap()}), or anything else:
     * the default of {@link WebBrowser#bitmapSource()}.
     */
    VENDOR
}
