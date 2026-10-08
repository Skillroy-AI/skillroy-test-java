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
 * {@link WebEvent}s.
 *
 * <p>A domain pack implements {@link BrowserRegistryAdapter} only: it hands the agent {@link BrowserSeed seeds} for
 * browsers the Swing-tree scan cannot see (cached, hidden or without a view) and needs to know no vendor type. A pack
 * that embeds a third technology also implements a {@link WebEngineAdapter} for it. Both are registered through
 * {@code AgentContext.spi()} ({@code SpiRegistry.register}) during {@code AgentModule.install}.
 *
 * <p>Java 8 and JDK types only ({@code java.awt.Component}, {@code java.awt.Dimension},
 * {@code java.awt.image.BufferedImage}, {@code java.util.concurrent.CompletableFuture}): no vendor type appears in any
 * signature, and no vendor artifact is a dependency in any scope.
 */
package ai.skillroy.testjava.agent.api.spi.web;
