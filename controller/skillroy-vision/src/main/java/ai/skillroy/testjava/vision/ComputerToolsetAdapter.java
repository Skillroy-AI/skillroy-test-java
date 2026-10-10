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
 * The Claude computer toolset (phase7-contracts.md 6.3; vision R1): one provider entry
 * {@code computer_toolset_20260801} with {@code zoom} off by default (BS12: never called in 828 calls, 411 tokens a
 * request), member calls arriving as tool calls named by the member with the toolset name {@code computer} (every
 * result echoes it), and the custom {@code skillroy_look} beside it. Under a read-only policy the acting members are
 * disabled in the entry's configs, leaving {@code screenshot}, {@code zoom} (when on), {@code wait} and
 * {@code cursor_position}. In V3 {@code left_mouse_down} and {@code left_mouse_up} are answered as text (D343: a Robot
 * press is never left held).
 */
public final class ComputerToolsetAdapter implements ToolAdapter {

    /** The members, as the provider names them. */
    static final List<String> MEMBERS = List.of("screenshot", "zoom", "left_click", "right_click", "middle_click",
            "double_click", "triple_click", "left_click_drag", "mouse_move", "left_mouse_down", "left_mouse_up",
            "scroll", "type", "key", "hold_key", "wait", "cursor_position");
    private static final Set<String> READ_MEMBERS = Set.of("screenshot", "zoom", "wait", "cursor_position");
    /** The most times a toolset key is repeated (6.3). */
    static final int MAX_REPEAT = 100;

    private final CustomToolAdapter custom = new CustomToolAdapter();

    @Override
    public List<ToolSpec> tools(VisionTier tier, boolean readsOnly) {
        List<ToolSpec> out = new ArrayList<>();
        for (ToolSpec t : custom.tools(tier, true)) {
            if (t.name().equals(LOOK)) {
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public List<ProviderTool> providerTools(VisionTier tier, boolean readsOnly, boolean zoom) {
        Map<String, Object> configs = new LinkedHashMap<>();
        configs.put("zoom", Map.of("enabled", zoom));
        if (readsOnly) {
            for (String member : MEMBERS) {
                if (!READ_MEMBERS.contains(member)) {
                    configs.put(member, Map.of("enabled", false));
                }
            }
        }
        return List.of(new ProviderTool(ProviderTool.CLAUDE, ProviderTool.COMPUTER_TOOLSET, Map.of("configs",
                configs)));
    }

    @Override
    public List<String> withheld(VisionTier tier) {
        List<String> out = new ArrayList<>();
        for (String member : MEMBERS) {
            if (!READ_MEMBERS.contains(member)) {
                out.add(member);
            }
        }
        return out;
    }

    @Override
    public boolean handles(String name) {
        return MEMBERS.contains(name) || LOOK.equals(name);
    }

    @Override
    public String toolsetName() {
        return ProviderTool.COMPUTER_TOOLSET_NAME;
    }

    @Override
    public Action map(Content.ToolUse use, VisionTier tier) {
        Map<String, Object> in = use.input();
        return switch (use.name()) {
            case "screenshot" -> new Capture();
            case "left_click" -> click(in, "LEFT", 1);
            case "right_click" -> click(in, "RIGHT", 1);
            case "middle_click" -> click(in, "MIDDLE", 1);
            case "double_click" -> click(in, "LEFT", 2);
            case "triple_click" -> click(in, "LEFT", 3);
            case "left_click_drag" -> {
                int[] from = coordinate(in, "start_coordinate");
                int[] to = coordinate(in, "coordinate");
                if (from == null || to == null) {
                    throw new IllegalArgumentException("left_click_drag needs start_coordinate and coordinate");
                }
                yield new Drag(from[0], from[1], to[0], to[1]);
            }
            case "mouse_move" -> {
                int[] at = coordinate(in, "coordinate");
                if (at == null) {
                    throw new IllegalArgumentException("mouse_move needs a coordinate");
                }
                yield new MouseMove(at[0], at[1]);
            }
            case "left_mouse_down", "left_mouse_up" -> {
                if (tier == VisionTier.V3) {
                    yield new Refused("not supported in V3; use left_click_drag");
                }
                int[] at = coordinate(in, "coordinate");
                yield new MouseButton(use.name().equals("left_mouse_down"), at == null ? null : at[0],
                        at == null ? null : at[1]);
            }
            case "scroll" -> {
                int[] at = coordinate(in, "coordinate");
                Object direction = in.get("scroll_direction");
                Object amount = in.get("scroll_amount");
                if (!(direction instanceof String d) || !(amount instanceof Number n)) {
                    throw new IllegalArgumentException("scroll needs scroll_direction and scroll_amount");
                }
                yield new Scroll(at == null ? null : at[0], at == null ? null : at[1], d.toLowerCase(Locale.ROOT),
                        (int) Math.max(1, Math.min(50, n.longValue())));
            }
            case "type" -> new Type(CustomToolAdapter.text(in, "text"), false);
            case "key" -> {
                KeyNames.Key key = KeyNames.of(CustomToolAdapter.text(in, "text"));
                int repeat = in.get("repeat") instanceof Number n ? (int) Math.max(1, Math.min(MAX_REPEAT,
                        n.longValue())) : 1;
                yield new Key(key.key(), key.modifiers(), repeat);
            }
            case "hold_key" -> new Refused("hold_key is not supported; use key");
            case "wait" -> new Wait("the duration is ignored: the application was waited for until it was quiet");
            case "zoom" -> {
                if (!(in.get("region") instanceof List<?> r) || r.size() != 4) {
                    throw new IllegalArgumentException("zoom needs a region [x0, y0, x1, y1]");
                }
                int[] v = new int[4];
                for (int i = 0; i < 4; i++) {
                    if (!(r.get(i) instanceof Number n)) {
                        throw new IllegalArgumentException("zoom's region holds whole numbers");
                    }
                    v[i] = n.intValue();
                }
                yield new Zoom(v[0], v[1], v[2], v[3]);
            }
            case "cursor_position" -> new CursorPosition();
            case LOOK -> new Look(CustomToolAdapter.text(in, "question"));
            default -> new Refused("there is no tool " + use.name());
        };
    }

    private static Action click(Map<String, Object> in, String button, int count) {
        int[] at = coordinate(in, "coordinate");
        return new ClickAt(at == null ? null : at[0], at == null ? null : at[1], button, count, modifiers(in));
    }

    /** The modifier keys a click's {@code text} names ({@code shift}, {@code ctrl+shift}). */
    private static List<String> modifiers(Map<String, Object> in) {
        if (!(in.get("text") instanceof String text) || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : text.split("\\+")) {
            String m = part.strip().toLowerCase(Locale.ROOT);
            String name = switch (m) {
                case "ctrl", "control" -> "CTRL";
                case "shift" -> "SHIFT";
                case "alt" -> "ALT";
                case "super", "meta", "cmd" -> "META";
                default -> throw new IllegalArgumentException("unknown modifier '" + part.strip() + "'");
            };
            if (!out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    private static int[] coordinate(Map<String, Object> in, String key) {
        if (!(in.get(key) instanceof List<?> c)) {
            return null;
        }
        if (c.size() != 2 || !(c.get(0) instanceof Number x) || !(c.get(1) instanceof Number y)) {
            throw new IllegalArgumentException(key + " is [x, y]");
        }
        return new int[] {x.intValue(), y.intValue()};
    }
}
