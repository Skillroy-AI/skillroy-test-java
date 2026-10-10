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

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.inference.session.SessionTools;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The vision options of {@code skillroy session} and {@code skillroy mcp} (phase7-contracts.md 9.1, 9.3, 9.4):
 * {@code --vision v1 | v2 | v3 | off}, {@code --vision-model}, {@code --vision-tools} (session only) and
 * {@code --allow-robot}, a person's flag; what they decide: the tier in effect and, when it is off, why; the host
 * features the session declares to the capability merge before it opens ({@code vision-session},
 * {@code vision-tier:<tier>}, D348); and the tool options handed to the service. {@code v3} without
 * {@code --allow-robot}, and the model {@code haiku} with {@code v2} or {@code v3}, are usage errors before anything
 * opens (never a silent downgrade). Stateless.
 */
final class VisionArgs {

    static final String VISION = "--vision";
    static final String VISION_MODEL = "--vision-model";
    static final String VISION_TOOLS = "--vision-tools";
    static final String ALLOW_ROBOT = "--allow-robot";

    /** The vision module's id among the session tool sets ({@code VisionSessionTools.id()}). */
    static final String MODULE = "vision";

    private static final List<String> TIERS = List.of("v1", "v2", "v3", "off");
    private static final List<String> MODELS = List.of("opus", "sonnet", "haiku");
    private static final List<String> TOOLS = List.of("toolset", "custom");

    private VisionArgs() {
    }

    /** {@code --vision} of {@code session} (the default as 9.1 says) and of {@code mcp} (off unless given). */
    static OptionSpec vision(boolean session) {
        return OptionSpec.value(VISION, "tier", session
                ? "v1 | v2 | v3 | off: the model sees redacted captures of the\ntarget's windows (default v1 when the"
                        + " vision module is\npresent, a gateway is named and images: true; else off)"
                : "v1 | v2 | v3: the vision tier of skillroy_session_ask's\nactor (default off); captures reach the"
                        + " client only\nunder images: true");
    }

    static final OptionSpec MODEL_OPTION = OptionSpec.value(VISION_MODEL, "class", "opus | sonnet | haiku: the vision"
            + " loop's model class\n(default sonnet; haiku with v1 only)");
    static final OptionSpec TOOLS_OPTION = OptionSpec.value(VISION_TOOLS, "set", "toolset | custom: V2 and V3's tools"
            + " (default the\ncomputer toolset)");

    /** {@code --allow-robot}: what it enables differs by command. */
    static OptionSpec robot(String what) {
        return OptionSpec.flag(ALLOW_ROBOT, what);
    }

    /** What the person asked for. */
    record Choice(String tier, String model, String tools, boolean robot) {
    }

    /**
     * The person's vision options.
     *
     * @throws UsageException for a value that is not one of the option's, {@code v3} without {@code --allow-robot}, or
     *     {@code haiku} with {@code v2} or {@code v3}
     */
    static Choice parse(ParsedArgs args, boolean session) throws UsageException {
        String tier = word(args, VISION, TIERS);
        if (!session && "off".equals(tier)) {
            tier = null;
        }
        String model = session ? word(args, VISION_MODEL, MODELS) : null;
        String tools = session ? word(args, VISION_TOOLS, TOOLS) : null;
        boolean robot = args.flag(ALLOW_ROBOT);
        if ("v3".equals(tier) && !robot) {
            throw new UsageException(VISION + " v3 needs " + ALLOW_ROBOT + " (Robot takes the real pointer: a person's"
                    + " opt-in, beside the agent option robot=true)");
        }
        if ("haiku".equals(model) && ("v2".equals(tier) || "v3".equals(tier))) {
            throw new UsageException(VISION_MODEL + " haiku is allowed with " + VISION + " v1 only");
        }
        return new Choice(tier, model, tools, robot);
    }

    private static String word(ParsedArgs args, String option, List<String> allowed) throws UsageException {
        String value = args.value(option);
        if (value == null) {
            return null;
        }
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (!allowed.contains(v)) {
            throw new UsageException(option + " takes " + String.join(" | ", allowed) + ", found '" + value + "'");
        }
        return v;
    }

    /** True when the vision module is among the loaded tool sets. */
    static boolean modulePresent(List<SessionTools> tools) {
        return tools.stream().anyMatch(t -> MODULE.equals(t.id()));
    }

    /**
     * What the options decide.
     *
     * @param tier {@code v1}, {@code v2}, {@code v3}, or {@code off}
     * @param reason why the tier is off, or null when it is on
     * @param host the host features for the session's capability merge (empty when off)
     * @param options the tool options for the service
     */
    record Decision(String tier, String reason, Set<String> host, Map<String, String> options) {

        /** True when a vision tier is in effect. */
        boolean on() {
            return reason == null;
        }

        /** The start-up line's words: {@code vision v1} or {@code vision off (<why>)}. */
        String line() {
            return on() ? "vision " + tier + " (the model sees redacted captures of the target's windows)"
                    : "vision off (" + reason + ")";
        }
    }

    /**
     * The tier in effect: the person's, or by default {@code v1} when the module is present, the settings name a gateway
     * and {@code images} is true (the session), {@code off} otherwise (the session) or unless given (the MCP server);
     * off whatever was asked when the module is absent, the gateway is {@code none} or {@code images} is false (with the
     * reason, which the start-up line says).
     */
    static Decision decide(Choice choice, boolean modulePresent, InferenceSettings inference, boolean defaultOn) {
        String asked = choice.tier();
        String reason = null;
        if ("off".equals(asked)) {
            reason = VISION + " off";
        } else if (!modulePresent) {
            reason = "the vision module is not on the class path";
        } else if (inference == null || !inference.enabled()) {
            reason = "no model gateway";
        } else if (!inference.images()) {
            reason = "images: false in the inference settings";
        } else if (asked == null && !defaultOn) {
            reason = "not asked for (" + VISION + " v1 | v2 | v3)";
        }
        String tier = reason != null ? "off" : asked != null ? asked : "v1";
        Map<String, String> options = new LinkedHashMap<>();
        options.put("vision.tier", tier);
        if (choice.model() != null) {
            options.put("vision.model", choice.model());
        }
        if (choice.tools() != null) {
            options.put("vision.tools", choice.tools());
        }
        Set<String> host = reason != null ? Set.of() : Set.of("vision-session", "vision-tier:" + tier);
        return new Decision(tier, reason, host, options);
    }
}
