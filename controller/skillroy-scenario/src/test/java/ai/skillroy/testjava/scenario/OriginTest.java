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

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Phase 6 security fix S57 (D158): the origin kinds read back from the journal's shape, {@code CLIENT} included. */
class OriginTest {

    @Test
    void everyKindRoundTripsThroughTheJournalsShape() {
        for (Origin origin : new Origin[] {Origin.person("desk"), Origin.model("a-model", "why", "req-1"),
                Origin.system("a host"), Origin.client("planner")}) {
            assertEquals(origin, Origin.fromJson(origin.toJson()), origin.toString());
        }
        Origin client = Origin.fromJson(Map.of("kind", "CLIENT", "by", "mcp"));
        assertEquals(Origin.Kind.CLIENT, client.kind());
        assertEquals("mcp", client.by());
        assertEquals(Map.of("kind", "CLIENT", "by", "mcp"), Origin.client("mcp").toJson());
    }
}
