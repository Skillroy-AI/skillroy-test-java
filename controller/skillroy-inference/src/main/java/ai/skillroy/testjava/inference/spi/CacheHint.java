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
 * Whether and for how long the provider should cache the request's frozen prefix, up to the last system part marked
 * {@link Content.Text#cacheBreakpoint()} (phase5-contracts.md 3.2, 4.1). A hint, not a promise: a prefix under the
 * provider's minimum silently does not cache, which the usage shows as no cache reads.
 */
public enum CacheHint {
    /** No cache marker. */
    NONE,
    /** The provider's default time to live (five minutes for Claude). */
    SHORT,
    /** The longest the provider offers (one hour for Claude); what a batch round uses. */
    LONG,
    /**
     * The agent loop's hint (phase7-contracts.md 7.4, D323): the system prefix keeps its explicit breakpoint (at the
     * default time to live) and the request also carries the provider's automatic marker, which the provider advances
     * to the last cacheable block, so that a growing conversation reads its earlier turns from the cache. What the
     * vision loop uses; every other purpose keeps {@link #SHORT}.
     */
    AUTO
}
