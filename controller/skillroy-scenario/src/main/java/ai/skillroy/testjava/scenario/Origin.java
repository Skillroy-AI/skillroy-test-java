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

package ai.skillroy.testjava.scenario;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Who asked for an action of an interactive session (phase5-contracts.md 12.2, 12.3), as the journal records it: a
 * person (with a label of their choosing), a model (its id and the request id of the call that chose the action), an
 * MCP client (with the label its server was started with: {@code skillroy mcp --client}, phase6-contracts.md D158) or
 * the system. Only a {@link Kind#PERSON} may confirm a destructive call: the session strips {@code confirm} and
 * {@code _meta} from the arguments of every other origin's call, a {@link Kind#MODEL}'s, a {@link Kind#CLIENT}'s or the
 * {@link Kind#SYSTEM}'s (design 3.4; phase5-contracts.md 12.2).
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param kind who acted
 * @param by a person's label, a model id or {@code system}; may be null
 * @param intent why, in the actor's words (a model's text before its tool call), or null
 * @param requestId the model call that chose the action, or null
 * @param note anything else worth journaling, or null
 */
public record Origin(Kind kind, String by, String intent, String requestId, String note) {

    /** Who acted. */
    public enum Kind {
        /** A person at the session's terminal or overlay. */
        PERSON,
        /** A model through the session service. */
        MODEL,
        /** The session or its host itself. */
        SYSTEM,
        /**
         * An MCP client, often a model (phase6 security fix S57, D158): it acts through the session like a person but
         * confirms nothing; the journal and the export name it {@code client <label>}.
         */
        CLIENT
    }

    /** @throws NullPointerException if the kind is null */
    public Origin {
        Objects.requireNonNull(kind, "kind");
    }

    /** A person with this label (null: unnamed). */
    public static Origin person(String by) {
        return new Origin(Kind.PERSON, by, null, null, null);
    }

    /** A model with its id, its reason and the request id of the call that chose the action. */
    public static Origin model(String model, String intent, String requestId) {
        return new Origin(Kind.MODEL, model, intent, requestId, null);
    }

    /** An MCP client with the label its server was started with ({@code mcp} by default; {@code planner} for author). */
    public static Origin client(String by) {
        return new Origin(Kind.CLIENT, by, null, null, null);
    }

    /** The system, with a note. */
    public static Origin system(String note) {
        return new Origin(Kind.SYSTEM, "system", null, null, note);
    }

    /** {@code {kind, by?, intent?, requestId?, note?}}: the journal's shape; a new map on each call. */
    Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("kind", kind.name());
        if (by != null) {
            json.put("by", by);
        }
        if (intent != null) {
            json.put("intent", intent);
        }
        if (requestId != null) {
            json.put("requestId", requestId);
        }
        if (note != null) {
            json.put("note", note);
        }
        return json;
    }

    /**
     * Reads {@link #toJson()}'s shape (every {@link Kind}, {@code CLIENT} included).
     *
     * @throws IllegalArgumentException if the kind is missing or unknown
     */
    static Origin fromJson(Object json) {
        if (!(json instanceof Map<?, ?> map) || !(map.get("kind") instanceof String kind)) {
            throw new IllegalArgumentException("an origin needs a kind");
        }
        return new Origin(Kind.valueOf(kind), text(map.get("by")), text(map.get("intent")),
                text(map.get("requestId")), text(map.get("note")));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
