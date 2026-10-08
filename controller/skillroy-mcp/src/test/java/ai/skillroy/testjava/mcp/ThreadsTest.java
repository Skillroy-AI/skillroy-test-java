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

package ai.skillroy.testjava.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * No thread the server started outlives it: the SDK's transport threads (non-daemon), the SQL guard's parser thread
 * and the stdin reader end when the server is closed after the client closed its stream. Reactor's shared bounded
 * elastic workers are the SDK's and the JVM's, not the server's.
 */
class ThreadsTest {

    @TempDir
    Path dir;

    private static boolean ours(Thread t) {
        String name = t.getName();
        return !name.startsWith("boundedElastic") && !name.startsWith("parallel") && !name.startsWith("single")
                && (!t.isDaemon() || name.startsWith("sql-guard-parser") || name.startsWith("skillroy-mcp-stdin")
                || name.startsWith("test-mcp-client-reader"));
    }

    @Test
    void everyThreadTheServerStartedEndsWithIt() throws Exception {
        Set<Thread> before = Thread.getAllStackTraces().keySet();
        McpFixture fx = new McpFixture(dir).startReadOnly();
        Map<String, Object> args = new HashMap<>();
        args.put("source", "desk");
        args.put("sql", "SELECT code, title FROM item WHERE category = ?");
        args.put("params", List.of("tools"));
        assertFalse(fx.call("data_query", args).isError());
        assertEquals(1, fx.server.guardCount());
        fx.close();
        List<String> alive = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (!before.contains(t) && ours(t)) {
                t.join(10_000);
                if (t.isAlive()) {
                    alive.add(t.getName() + (t.isDaemon() ? " (daemon)" : ""));
                }
            }
        }
        assertEquals(List.of(), alive, "threads left running");
        assertEquals(0, fx.server.guardCount(), "the guards were closed");
    }
}
