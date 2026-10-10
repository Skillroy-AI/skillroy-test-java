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

package ai.skillroy.testjava.vision;

import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.scenario.CapabilityContributor;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.util.Map;

/**
 * The contributor of {@code inference.vision} (phase7-contracts.md 8.5, decisions D312, D327): {@code OK} with the
 * tier as its strategy ({@code v1 | v2 | v3}, from the host's {@code vision-tier:} feature) when the host declares
 * {@code vision-session}, the settings name a gateway other than {@code none}, {@code images} is true and the agent's
 * {@code swing.capture.screen} is {@code OK} (the loop's capture is the {@code SCREEN} composite); else
 * {@code UNAVAILABLE} with the first failing reason: {@code replay}, {@code no vision session}, {@code gateway none},
 * {@code images: false}, {@code swing.capture.screen is <state>}. The vision module is its only contributor; absent
 * from the matrix when the module is not on the class path. A pure function of its context.
 */
public final class VisionCapabilities implements CapabilityContributor {

    /** The id it contributes. */
    public static final String ID = "inference.vision";
    /** The host feature of a vision session (phase7-contracts.md 8.5, D348). */
    public static final String HOST_SESSION = "vision-session";
    /** The prefix of the host feature that names the tier ({@code vision-tier:v1}). */
    public static final String HOST_TIER = "vision-tier:";
    /** The agent capability the loop's capture needs. */
    public static final String SCREEN = "swing.capture.screen";

    @Override
    public String id() {
        return "vision";
    }

    @Override
    public Map<String, Capability> capabilities(CapabilityContext ctx) {
        return Map.of(ID, capability(ctx));
    }

    private static Capability capability(CapabilityContext ctx) {
        if (ctx.mode() == BindMode.REPLAY) {
            return unavailable("replay");
        }
        if (!ctx.host().contains(HOST_SESSION)) {
            return unavailable("no vision session");
        }
        InferenceSettings settings = ctx.settings();
        if (!settings.enabled()) {
            return unavailable("gateway none");
        }
        if (!settings.images()) {
            return unavailable("images: false");
        }
        Capability screen = ctx.agent().get(SCREEN);
        if (screen == null || screen.state() != CapabilityState.OK) {
            return unavailable(SCREEN + " is " + (screen == null ? "missing" : screen.state().name()));
        }
        return new Capability(ID, CapabilityState.OK, null, tier(ctx));
    }

    private static String tier(CapabilityContext ctx) {
        for (String feature : ctx.host()) {
            if (feature.startsWith(HOST_TIER)) {
                return feature.substring(HOST_TIER.length());
            }
        }
        return VisionTier.V1.option();
    }

    private static Capability unavailable(String reason) {
        return new Capability(ID, CapabilityState.UNAVAILABLE, reason, null);
    }
}
