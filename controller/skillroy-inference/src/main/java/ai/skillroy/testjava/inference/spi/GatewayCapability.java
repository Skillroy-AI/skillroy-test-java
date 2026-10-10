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

package ai.skillroy.testjava.inference.spi;

/**
 * What a gateway can do beyond a plain completion (phase5-contracts.md 3.2): a second provider declares what it lacks
 * here instead of failing obscurely (B7).
 */
public enum GatewayCapability {
    /** An answer constrained by {@link ModelRequest#outputSchema()} and returned parsed in {@link ModelResponse#structured()}. */
    STRUCTURED_OUTPUT,
    /** Tools offered with tool choice {@code auto}; {@link Content.ToolUse} parts in the response. */
    TOOLS,
    /** A provider-side prompt cache honouring {@link CacheHint} and {@link Content.Text#cacheBreakpoint()}. */
    CACHING,
    /** {@link InferenceGateway#submitBatch} and the other batch methods. */
    BATCH,
    /** {@link Content.Image} parts may be sent. */
    IMAGES,
    /** {@link InferenceGateway#countTokens} answers the provider's count. */
    TOKEN_COUNT,
    /**
     * {@link ModelRequest#providerTools()} entries of this gateway's provider are passed through (phase7-contracts.md
     * 7.1, D318: the Claude computer toolset); a gateway without it is not sent a request that holds one.
     */
    PROVIDER_TOOLS
}
