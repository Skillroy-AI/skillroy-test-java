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

import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.ProviderTool;
import ai.skillroy.testjava.inference.spi.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The provider-neutral custom tools (phase7-contracts.md 6.3; B7's neutrality): strict tool specs, names
 * {@code [a-z0-9_]}, the only form that can offer a click by mark or by ref. V1 offers {@code capture},
 * {@code click_mark}, {@code type}, {@code key}, {@code wait} and {@code skillroy_look}; V2 and V3 with this
 * vocabulary offer {@code capture}, {@code click_at}, {@code drag}, {@code scroll}, {@code click_ref}, {@code type},
 * {@code key}, {@code wait} and {@code skillroy_look} (as built: no {@code click_mark}, since V2 and V3 captures carry no
 * marks). Under a read-only policy, or one that offers no verbs, {@code capture}, {@code wait} and
 * {@code skillroy_look} only.
 */
public final class CustomToolAdapter implements ToolAdapter {

    private static final Set<String> NAMES = Set.of("capture", "click_mark", "click_ref", "click_at", "drag", "scroll",
            "type", "key", "wait", LOOK);
    private static final Set<String> READS = Set.of("capture", "wait", LOOK);

    @Override
    public List<ToolSpec> tools(VisionTier tier, boolean readsOnly) {
        List<ToolSpec> out = new ArrayList<>();
        for (String name : names(tier)) {
            if (!readsOnly || READS.contains(name)) {
                out.add(spec(name));
            }
        }
        return List.copyOf(out);
    }

    private static List<String> names(VisionTier tier) {
        return tier == VisionTier.V1 ? List.of("capture", "click_mark", "type", "key", "wait", LOOK)
                : List.of("capture", "click_at", "drag", "scroll", "click_ref", "type", "key", "wait", LOOK);
    }

    @Override
    public List<ProviderTool> providerTools(VisionTier tier, boolean readsOnly, boolean zoom) {
        return List.of();
    }

    @Override
    public List<String> withheld(VisionTier tier) {
        List<String> out = new ArrayList<>();
        for (String name : names(tier)) {
            if (!READS.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    @Override
    public boolean handles(String name) {
        return NAMES.contains(name);
    }

    @Override
    public String toolsetName() {
        return null;
    }

    @Override
    public Action map(Content.ToolUse use, VisionTier tier) {
        Map<String, Object> in = use.input();
        return switch (use.name()) {
            case "capture" -> new Capture();
            case "click_mark" -> new ClickMark(integer(in, "mark"), button(in), count(in));
            case "click_ref" -> new ClickRef(text(in, "ref"), button(in), count(in));
            case "click_at" -> new ClickAt(integer(in, "x"), integer(in, "y"), button(in), count(in), List.of());
            case "drag" -> new Drag(integer(in, "x1"), integer(in, "y1"), integer(in, "x2"), integer(in, "y2"));
            case "scroll" -> new Scroll(integer(in, "x"), integer(in, "y"), text(in, "direction")
                    .toLowerCase(Locale.ROOT), Math.max(1, Math.min(50, integer(in, "amount"))));
            case "type" -> new Type(text(in, "text"), Boolean.TRUE.equals(in.get("enter")));
            case "key" -> {
                List<String> modifiers = new ArrayList<>();
                if (in.get("modifiers") instanceof List<?> list) {
                    for (Object m : list) {
                        modifiers.add(String.valueOf(m).toUpperCase(Locale.ROOT));
                    }
                }
                yield new Key(text(in, "key").toUpperCase(Locale.ROOT), modifiers, 1);
            }
            case "wait" -> new Wait(null);
            case LOOK -> new Look(text(in, "question"));
            default -> new Refused("there is no tool " + use.name());
        };
    }

    // ------------------------------------------------------------------------------------------------ specs

    private static ToolSpec spec(String name) {
        return switch (name) {
            case "capture" -> new ToolSpec(name, "Takes a fresh capture of the application's windows (V1: with"
                    + " numbered marks).", object(), true, false);
            case "click_mark" -> new ToolSpec(name, "Clicks the component a mark of the last capture stands on.",
                    object("mark", Map.of("type", "integer", "description", "the mark's number"), "button", button(),
                            "count", count()), false, false);
            case "click_ref" -> new ToolSpec(name, "Clicks a component by its ref from the text snapshot.",
                    object("ref", Map.of("type", "string", "description", "the component's ref, such as h12"),
                            "button", button(), "count", count()), false, false);
            case "click_at" -> new ToolSpec(name, "Clicks a pixel of the last capture.", object("x", pixel(), "y",
                    pixel(), "button", button(), "count", count()), false, false);
            case "drag" -> new ToolSpec(name, "Drags from one pixel of the last capture to another.", object("x1",
                    pixel(), "y1", pixel(), "x2", pixel(), "y2", pixel()), false, false);
            case "scroll" -> new ToolSpec(name, "Turns the mouse wheel at a pixel of the last capture (up or down).",
                    object("x", pixel(), "y", pixel(), "direction", Map.of("type", "string", "enum", List.of("up",
                            "down", "left", "right")), "amount", Map.of("type", "integer", "description",
                            "notches")), false, false);
            case "type" -> new ToolSpec(name, "Types text into the component that has the focus (never a password:"
                    + " the person signs on).", object("text", Map.of("type", "string"), "enter", Map.of("type",
                    List.of("boolean", "null"), "description", "true to press Enter after the text")), false, false);
            case "key" -> new ToolSpec(name, "Presses a key (ENTER, ESCAPE, TAB, F2, A ...) at the component that has"
                    + " the focus.", object("key", Map.of("type", "string"), "modifiers", Map.of("type",
                    List.of("array", "null"), "items", Map.of("type", "string"), "description",
                    "any of SHIFT, CTRL, ALT, META")), false, false);
            case "wait" -> new ToolSpec(name, "Waits until the application is quiet.", object(), true, false);
            case LOOK -> new ToolSpec(name, "Asks one yes-or-no question about the last capture; the answer is"
                    + " advisory.", object("question", Map.of("type", "string")), true, false);
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static Map<String, Object> button() {
        return Map.of("type", List.of("string", "null"), "description", "left (default), middle or right");
    }

    private static Map<String, Object> count() {
        return Map.of("type", List.of("integer", "null"), "description", "clicks: 1 (default), 2 or 3");
    }

    private static Map<String, Object> pixel() {
        return Map.of("type", "integer", "description", "pixels of the last capture");
    }

    /** {@code {type: object, properties, required: all, additionalProperties: false}} of name/schema pairs. */
    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            props.put((String) pairs[i], pairs[i + 1]);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.copyOf(props.keySet()));
        schema.put("additionalProperties", false);
        return schema;
    }

    // ------------------------------------------------------------------------------------------------ inputs

    static String button(Map<String, Object> in) {
        Object b = in.get("button");
        String text = b == null ? "left" : String.valueOf(b).strip().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "left", "" -> "LEFT";
            case "middle" -> "MIDDLE";
            case "right" -> "RIGHT";
            default -> throw new IllegalArgumentException("a button is left, middle or right");
        };
    }

    static int count(Map<String, Object> in) {
        Object c = in.get("count");
        if (c == null) {
            return 1;
        }
        if (!(c instanceof Number n) || n.longValue() < 1 || n.longValue() > 3) {
            throw new IllegalArgumentException("a click count is 1, 2 or 3");
        }
        return n.intValue();
    }

    static int integer(Map<String, Object> in, String key) {
        if (!(in.get(key) instanceof Number n)) {
            throw new IllegalArgumentException("the input needs the whole number '" + key + "'");
        }
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, n.longValue()));
    }

    static String text(Map<String, Object> in, String key) {
        if (!(in.get(key) instanceof String s) || s.isEmpty()) {
            throw new IllegalArgumentException("the input needs the text '" + key + "'");
        }
        return s;
    }
}
