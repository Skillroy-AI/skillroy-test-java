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

package ai.skillroy.testjava.agent.web.reflect;

import java.util.concurrent.CompletableFuture;

/**
 * What the built-in adapters' browser handles offer beyond the {@code WebBrowser} SPI, for primitives the SPI does not
 * cover (a need recorded for the SPI's owner): {@code web.reload} reloads through the technology
 * ({@code CefBrowser.reload()} / {@code Navigation.reload()}, phase4-contracts.md section 4.2), which works where the
 * page cannot run a script, and {@code web.bitmap} reports where its image came from. A handle of a third-party
 * adapter without this interface is reloaded with {@code location.reload()} through {@code evaluate}, and its bitmap
 * source is reported as {@code VENDOR}.
 *
 * <p>Implementations follow the {@code WebBrowser} rules: thread-safe, called on agent workers, futures completed
 * without blocking, JDK exceptions only.
 */
public interface BrowserExtras {

    /** Reloads the main frame through the technology; completes once the reload was requested. */
    CompletableFuture<Void> reload(boolean ignoreCache);

    /** {@code PROTOCOL} (a DevTools-protocol screenshot) or {@code VENDOR} (the product's own bitmap). */
    String bitmapSource();
}
