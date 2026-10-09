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

package ai.skillroy.testjava.inference.tools;

import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase6-contracts.md 12.9, D156 (security fix S42): the shared "never for a client" rule refuses a primitive whose
 * descriptor carries the {@code script} flag wherever the host's policy excludes the named script primitives (the MCP
 * server without {@code --allow-evaluate}, every session), and lets it run where a person opted in.
 */
class CallPolicyScriptTest {

    private static final PrimitiveDescriptor PEEK = new PrimitiveDescriptor("lending.peek", "peek", null, null,
            "WORKER", 10000, null, false, false, true);

    @Test
    void aFlaggedPrimitiveIsRefusedWhereTheScriptPrimitivesAreExcluded() {
        String why = CallPolicy.never(PEEK, "lending.peek", ToolProjection.Policy.session(true, true).excluded());
        assertTrue(why != null && why.contains("lending.peek") && why.contains("--allow-evaluate"), why);
        assertTrue(CallPolicy.never(PEEK, "lending.peek", ToolProjection.Policy.readOnly(false).excluded()) != null);
        assertNull(CallPolicy.never(PEEK, "lending.peek", ToolProjection.Policy.mutating(true).excluded()),
                "a person opted in");
        assertNull(CallPolicy.never(PEEK, "lending.peek", Set.of()));
        assertNull(CallPolicy.never(new PrimitiveDescriptor("lending.read", "read", null, null, "WORKER", 10000,
                null, false, false), "lending.read", ToolProjection.SCRIPT_PRIMITIVES), "no flag, not named");
    }
}
