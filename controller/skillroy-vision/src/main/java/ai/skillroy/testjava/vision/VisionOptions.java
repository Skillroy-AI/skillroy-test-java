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

import ai.skillroy.testjava.inference.session.SessionService;
import ai.skillroy.testjava.inference.spi.ModelClass;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A vision session's options (phase7-contracts.md 6.1, D304), from the host's tool options {@code vision.tier},
 * {@code vision.model}, {@code vision.tools}, {@code vision.maxActions}, {@code vision.maxTurns}, {@code vision.zoom};
 * defaults {@code V1}, the Sonnet class, {@code TOOLSET}, 30, 20, false. Whether a capture carries marks follows the
 * tier (V1 only) and is no option. The Haiku class is allowed for V1 only (BS12: it wrote calls as text with a pixel
 * tool), whatever model id the settings map it to.
 *
 * @param tier the tier
 * @param model the model class of the loop's calls and visual questions
 * @param tools the tool vocabulary of V2 and V3
 * @param maxActions UI actions per session (the service enforces it)
 * @param maxTurns model turns per session (the service enforces it)
 * @param zoom the toolset's {@code zoom} member enabled
 */
public record VisionOptions(VisionTier tier, ModelClass model, VisionTools tools, int maxActions, int maxTurns,
                            boolean zoom) {

    /** The tool option of the tier ({@code v1 | v2 | v3 | off}). */
    public static final String TIER = "vision.tier";
    /** The tool option of the model class ({@code opus | sonnet | haiku}). */
    public static final String MODEL = "vision.model";
    /** The tool option of the vocabulary ({@code toolset | custom}). */
    public static final String TOOLS = "vision.tools";
    /** The tool option of the toolset's zoom member ({@code true | false}). */
    public static final String ZOOM = "vision.zoom";
    /** The tool option of the action limit, which the service reads. */
    public static final String MAX_ACTIONS = SessionService.OPTION_MAX_ACTIONS;
    /** The tool option of the turn limit, which the service reads. */
    public static final String MAX_TURNS = SessionService.OPTION_MAX_TURNS;

    /** The defaults: V1, Sonnet, the toolset, 30 actions, 20 turns, no zoom. */
    public static final VisionOptions DEFAULTS = new VisionOptions(VisionTier.V1, ModelClass.SONNET_CLASS,
            VisionTools.TOOLSET, SessionService.DEFAULT_MAX_ACTIONS, SessionService.DEFAULT_MAX_TURNS, false);

    /**
     * @throws IllegalArgumentException for the Haiku class with V2 or V3, or a limit outside 1 to 1000
     */
    public VisionOptions {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(tools, "tools");
        if (model == ModelClass.HAIKU_CLASS && tier != VisionTier.V1) {
            throw new IllegalArgumentException("the vision model haiku is allowed for v1 only (" + tier.option()
                    + " needs sonnet or opus)");
        }
        if (maxActions < 1 || maxActions > 1000 || maxTurns < 1 || maxTurns > 1000) {
            throw new IllegalArgumentException("vision.maxActions and vision.maxTurns are whole numbers from 1 to"
                    + " 1000");
        }
    }

    /** True when the options name the tier {@code off}. */
    public static boolean off(Map<String, String> options) {
        String tier = options == null ? null : options.get(TIER);
        return tier != null && "off".equalsIgnoreCase(tier.strip());
    }

    /**
     * The options of a host's tool options, each key absent its default; {@code vision.tier: off} is not a tier
     * ({@link #off} first).
     *
     * @throws IllegalArgumentException for a value that is not one of the key's, {@code off} as the tier, or as the
     *     record's constructor
     */
    public static VisionOptions from(Map<String, String> options) {
        Map<String, String> o = options == null ? Map.of() : options;
        VisionTier tier = o.get(TIER) == null ? DEFAULTS.tier() : VisionTier.parse(o.get(TIER));
        if (tier == null) {
            throw new IllegalArgumentException("the vision tier is off");
        }
        ModelClass model = o.get(MODEL) == null ? DEFAULTS.model() : modelClass(o.get(MODEL));
        VisionTools tools = o.get(TOOLS) == null ? DEFAULTS.tools() : VisionTools.parse(o.get(TOOLS));
        int maxActions = number(o, MAX_ACTIONS, DEFAULTS.maxActions());
        int maxTurns = number(o, MAX_TURNS, DEFAULTS.maxTurns());
        boolean zoom = o.get(ZOOM) != null && bool(o.get(ZOOM));
        return new VisionOptions(tier, model, tools, maxActions, maxTurns, zoom);
    }

    /** These options as a host's tool options. */
    public Map<String, String> toOptions() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(TIER, tier.option());
        out.put(MODEL, model.settingsKey());
        out.put(TOOLS, tools.name().toLowerCase(Locale.ROOT));
        out.put(MAX_ACTIONS, Integer.toString(maxActions));
        out.put(MAX_TURNS, Integer.toString(maxTurns));
        out.put(ZOOM, Boolean.toString(zoom));
        return out;
    }

    /**
     * These options narrowed by a scenario step's values (phase7-contracts.md 6.8: never widened): a step's tier is at
     * most the session's (V3 only in a session that runs V3, which needs {@code --allow-robot}); a step's model must be
     * a class the resulting tier allows; a step's {@code maxActions} is at most the session's.
     *
     * @param tier the step's tier, or null
     * @param model the step's model class name, or null
     * @param maxActions the step's limit, or null
     * @throws IllegalArgumentException when the step would widen the session's options
     */
    public VisionOptions narrow(String tier, String model, Long maxActions) {
        VisionTier t = this.tier;
        if (tier != null) {
            VisionTier asked = VisionTier.parse(tier);
            if (asked == null) {
                throw new IllegalArgumentException("a step's tier is v1, v2 or v3");
            }
            if (asked.ordinal() > this.tier.ordinal()) {
                throw new IllegalArgumentException("the step asks for " + asked.option() + " but the session runs "
                        + this.tier.option() + (asked == VisionTier.V3 ? " (v3 needs --allow-robot on the command)"
                        : ""));
            }
            t = asked;
        }
        ModelClass m = model == null ? this.model : modelClass(model);
        int actions = this.maxActions;
        if (maxActions != null) {
            if (maxActions < 1 || maxActions > this.maxActions) {
                throw new IllegalArgumentException("a step's maxActions is from 1 to the session's " + this.maxActions);
            }
            actions = maxActions.intValue();
        }
        return new VisionOptions(t, m, tools, actions, maxTurns, zoom);
    }

    /**
     * The model class of an option's name ({@code opus | sonnet | haiku}).
     *
     * @throws IllegalArgumentException for another name
     */
    public static ModelClass modelClass(String name) {
        String n = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        for (ModelClass c : ModelClass.values()) {
            if (c.settingsKey().equals(n)) {
                return c;
            }
        }
        throw new IllegalArgumentException("the vision model is opus, sonnet or haiku, found " + name);
    }

    private static int number(Map<String, String> o, String key, int fallback) {
        String text = o.get(key);
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number from 1 to 1000");
        }
    }

    private static boolean bool(String text) {
        String t = text.strip().toLowerCase(Locale.ROOT);
        if (t.equals("true")) {
            return true;
        }
        if (t.equals("false")) {
            return false;
        }
        throw new IllegalArgumentException(ZOOM + " is true or false, found " + text);
    }
}
