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

package ai.skillroy.testjava.samples.lending.agent.embedded;

import ai.skillroy.testjava.samples.lending.LendingApp;
import ai.skillroy.testjava.samples.lending.LendingOptions;
import ai.skillroy.testjava.samples.lending.Seed;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin's safety rules without a real agent: it never enables the agent itself, and nothing reaches the host.
 * Starting the agent for real (EMBEDDED mode) is covered end to end by the scenarios module's
 * {@code LendingEndToEndIT}.
 */
class SkillroyLendingPluginTest {

    @Test
    void anAgentThatIsNotEnabledIsNotStartedAndTheDeskIsUnaffected() throws Exception {
        assertNull(System.getProperty("skillroy.agent.enabled"), "test precondition");
        LendingApp app = LendingApp.launch(LendingOptions.defaults().seed(Seed.A).ui(false)
                .dbName("lending_plugin_test")
                .plugins(Collections.singletonList(SkillroyLendingPlugin.class.getName())));
        try {
            assertEquals(1, app.plugins().size(), "the application started the plugin");
            assertTrue(app.plugins().get(0) instanceof SkillroyLendingPlugin);
            assertNull(System.getProperty("skillroy.agent.loadMode"), "no agent runs");
            assertNull(System.getProperty("skillroy.agent.error"), "a refusal is silent");
            assertTrue(app.engine().isRunning());
            assertEquals(0, app.engine().listenerCount());
        } finally {
            app.shutdown();
        }
        assertNull(System.getProperty("skillroy.agent.loadMode"));
    }

    @Test
    void stopWithoutAStartedAgentDoesNothing() {
        SkillroyLendingPlugin plugin = new SkillroyLendingPlugin();
        plugin.stop();
        plugin.start(null);
        plugin.stop();
        assertNull(System.getProperty("skillroy.agent.loadMode"));
    }
}
