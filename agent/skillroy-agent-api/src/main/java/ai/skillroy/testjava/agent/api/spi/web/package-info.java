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

/**
 * The embedded-browser SPI (Phase 4 contract §3, decision B9): how the agent finds and operates the web views a
 * Swing application embeds, written once for every technology. One {@link WebEngineAdapter} per embedded-browser
 * technology (built in: {@code jcef} and {@code jxbrowser}, in the agent module {@code skillroy-agent-web}) turns seeds
 * into one {@link WebBrowser} handle per live browser. The generic {@code web.*} primitives are written against those
 * handles: optional operations sit behind {@link WebCapability} flags, frames are snapshot values ({@link WebFrame})
 * with adapter-minted keys, evaluation results cross as JSON text ({@link WebEval}), and events arrive as
 * {@link WebEvent}s. A reload and the bitmap's {@link BitmapSource source} are default methods of the handle
 * (amendment A37), which an adapter overrides where its technology does better than the page's own
 * {@code location.reload()}.
 *
 * <p>A domain pack implements two types of this package: {@link BrowserRegistryAdapter}, which hands the agent
 * {@link BrowserSeed seeds} for browsers the Swing-tree scan cannot see (cached, hidden or without a view) and needs to
 * know no vendor type, and (Phase 8, gap 110, amendment A79) {@link BrowserMask}, which names or releases a browser by
 * its seed tag or its vendor object: while any {@code CaptureMask} or {@code BrowserMask} is registered, a browser
 * without a view is masked unless a {@code BrowserMask} releases it. A pack that embeds a third technology also
 * implements a {@link WebEngineAdapter} for it. All are registered through {@code AgentContext.spi()}
 * ({@code SpiRegistry.register}) during {@code AgentModule.install}.
 *
 * <p>Java 8 and JDK types only ({@code java.awt.Component}, {@code java.awt.Dimension},
 * {@code java.awt.image.BufferedImage}, {@code java.util.concurrent.CompletableFuture}): no vendor type appears in any
 * signature, and no vendor artifact is a dependency in any scope.
 */
package ai.skillroy.testjava.agent.api.spi.web;
