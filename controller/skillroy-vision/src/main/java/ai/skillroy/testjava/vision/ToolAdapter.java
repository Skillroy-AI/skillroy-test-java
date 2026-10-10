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

import java.util.List;

/**
 * A tool vocabulary of the vision loop (phase7-contracts.md 6.3): what the model is offered and how a call of it maps
 * to one neutral {@link Action}, which the session tools ground and the recorder turns into a step. Two vocabularies:
 * the provider-neutral custom tools ({@link CustomToolAdapter}, V1 always, V2 and V3 by the option) and the Claude
 * computer toolset ({@link ComputerToolsetAdapter}, V2 and V3 by default).
 */
public sealed interface ToolAdapter permits CustomToolAdapter, ComputerToolsetAdapter {

    /** The name of the visual question tool, a custom tool in both vocabularies. */
    String LOOK = "skillroy_look";

    /**
     * The custom tools offered.
     *
     * @param tier the session's tier
     * @param readsOnly true under a read-only policy or one that offers no verbs: the reads only
     */
    List<ToolSpec> tools(VisionTier tier, boolean readsOnly);

    /** The provider entries offered (the toolset), may be empty. */
    List<ProviderTool> providerTools(VisionTier tier, boolean readsOnly, boolean zoom);

    /** The acting tools left out under a read-only policy, for the instructions. */
    List<String> withheld(VisionTier tier);

    /** True for a name this vocabulary answers. */
    boolean handles(String name);

    /** The toolset name its calls and results carry ({@code computer}), or null for the custom tools. */
    String toolsetName();

    /**
     * The action of one call.
     *
     * @throws IllegalArgumentException for a call whose input does not fit its tool (answered to the model as text)
     */
    Action map(Content.ToolUse use, VisionTier tier);

    /** One neutral action of the loop. */
    sealed interface Action {
    }

    /** A fresh capture, returned as an image result (the custom {@code capture}, the toolset's {@code screenshot}). */
    record Capture() implements Action {
    }

    /** A click on a V1 mark. */
    record ClickMark(int mark, String button, int count) implements Action {
    }

    /** A click on a component by its ref from the text snapshot. */
    record ClickRef(String ref, String button, int count) implements Action {
    }

    /** A click at a pixel of the last capture; null coordinates mean the adapter's cursor. */
    record ClickAt(Integer x, Integer y, String button, int count, List<String> modifiers) implements Action {

        /** Copies the modifiers. */
        public ClickAt {
            modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        }
    }

    /** A drag between two pixels of the last capture. */
    record Drag(int x1, int y1, int x2, int y2) implements Action {
    }

    /** A wheel at a pixel (null: the cursor): {@code up} or {@code down}; left and right are refused. */
    record Scroll(Integer x, Integer y, String direction, int amount) implements Action {
    }

    /** Text typed at the focus owner. */
    record Type(String text, boolean enter) implements Action {
    }

    /** A key pressed at the focus owner, {@code repeat} times. */
    record Key(String key, List<String> modifiers, int repeat) implements Action {

        /** Copies the modifiers. */
        public Key {
            modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        }
    }

    /** A settle; the note says what was ignored (a toolset wait's duration). */
    record Wait(String note) implements Action {
    }

    /** A visual question on the last capture. */
    record Look(String question) implements Action {
    }

    /** The toolset's {@code zoom}: a rectangle of the last capture, in its pixels. */
    record Zoom(int x0, int y0, int x1, int y1) implements Action {
    }

    /** The toolset's {@code mouse_move}. */
    record MouseMove(int x, int y) implements Action {
    }

    /** The toolset's {@code left_mouse_down} or {@code left_mouse_up}, at the cursor (or a coordinate). */
    record MouseButton(boolean down, Integer x, Integer y) implements Action {
    }

    /** The toolset's {@code cursor_position}. */
    record CursorPosition() implements Action {
    }

    /** Not supported: answered to the model as text, nothing runs. */
    record Refused(String text) implements Action {
    }
}
