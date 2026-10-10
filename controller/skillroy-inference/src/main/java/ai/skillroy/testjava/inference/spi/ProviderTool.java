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

import java.util.Map;
import java.util.regex.Pattern;

/**
 * A tool entry a provider defines and the gateway passes through unread (phase7-contracts.md 7.1, D318): the Claude
 * computer toolset ({@code provider "claude"}, {@code type "computer_toolset_20260801"}, {@code raw} holding its
 * {@code configs}). Another provider's gateway refuses it ({@code INVALID_REQUEST}). Mirrors {@link Content.Opaque}: a
 * caller names the provider and the type and hands the rest as JSON-like data. Travels in
 * {@link ModelRequest#providerTools()}, which enters the fingerprint only when it is non-empty (so no earlier recording
 * changes), and is declared by a gateway through {@link GatewayCapability#PROVIDER_TOOLS}.
 *
 * <p>Immutable ({@code raw} is a deep copy); safe to share between threads.
 *
 * @param provider the provider that defines the entry ({@code "claude"})
 * @param type the entry's type as the provider names it ({@code "computer_toolset_20260801"})
 * @param raw the entry's other fields, JSON-like (copied); null is empty
 */
public record ProviderTool(String provider, String type, Map<String, Object> raw) {

    /** The provider of the Claude computer toolset. */
    public static final String CLAUDE = "claude";
    /** The type of the Claude computer toolset of 2026-08-01. */
    public static final String COMPUTER_TOOLSET = "computer_toolset_20260801";
    /** The toolset's name, which every member call and every result names ({@code toolset_name}). */
    public static final String COMPUTER_TOOLSET_NAME = "computer";

    private static final Pattern TYPE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /** @throws IllegalArgumentException if the provider is blank or the type is not a short name */
    public ProviderTool {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("a provider tool names its provider");
        }
        if (type == null || !TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("a provider tool's type is a short name");
        }
        raw = JsonLike.freezeMap(raw);
    }

    /**
     * The Claude computer toolset with the zoom member enabled or not (phase7-contracts.md 6.3: off by default) and
     * every other member at the provider's default.
     */
    public static ProviderTool computerToolset(boolean zoom) {
        return new ProviderTool(CLAUDE, COMPUTER_TOOLSET, Map.of("configs", Map.of("zoom", Map.of("enabled", zoom))));
    }
}
