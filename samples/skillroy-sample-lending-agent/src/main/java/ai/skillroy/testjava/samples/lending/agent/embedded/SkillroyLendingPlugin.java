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

import ai.skillroy.testjava.agent.bootstrap.EmbeddedAgent;
import ai.skillroy.testjava.samples.lending.LendingApp;
import ai.skillroy.testjava.samples.lending.LendingPlugin;

import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * EMBEDDED load mode for the sample lending desk: a {@link LendingPlugin} (named in the application's
 * {@code lending.plugins} system property) that starts the agent through
 * {@link EmbeddedAgent#start(Properties, ClassLoader, Object)} with the running {@link LendingApp} as host context, so
 * that the {@code lending} agent module gets the very application object instead of looking for it through statics.
 *
 * <p>Needs the agent's bootstrap jar on the application's class path; the rest of the agent comes from the agent
 * home (the bootstrap jar's directory). The agent starts only if it is enabled ({@code -Dskillroy.agent.enabled=true}
 * or {@code SKILLROY_AGENT_ENABLED=true}); this plugin never enables it itself, so leaving it configured is harmless.
 * Agent options come from the {@code skillroy.agent.*} system properties as usual.
 *
 * <p>Host safety: nothing ever escapes into the application. Without the bootstrap jar the plugin logs and does
 * nothing. {@link #stop()} stops the agent only if this plugin started it.
 *
 * <p>Threading: the application calls {@link #start} on its launching thread once the desk is up;
 * {@code EmbeddedAgent.start} returns once the agent serves or has refused (bounded by its start timeout).
 */
public final class SkillroyLendingPlugin implements LendingPlugin {

    private static final Logger LOG = Logger.getLogger(SkillroyLendingPlugin.class.getName());
    private static final String LOAD_MODE_PROPERTY = "skillroy.agent.loadMode";

    private volatile boolean startedHere;

    /** Public no-argument constructor, as the application's plugin mechanism requires. */
    public SkillroyLendingPlugin() {
    }

    @Override
    public void start(LendingApp app) {
        try {
            boolean runningBefore = System.getProperty(LOAD_MODE_PROPERTY) != null;
            ClassLoader hostLoader = app == null ? SkillroyLendingPlugin.class.getClassLoader()
                    : app.getClass().getClassLoader();
            boolean running = EmbeddedAgent.start(new Properties(), hostLoader, app);
            startedHere = running && !runningBefore;
            if (running) {
                LOG.info("skillroy agent " + (runningBefore ? "already running" : "started") + " (EMBEDDED)");
            } else {
                LOG.info("skillroy agent not started (not enabled, or it refused; see skillroy.agent.error)");
            }
        } catch (LinkageError e) {
            LOG.log(Level.WARNING, "skillroy agent bootstrap jar is not on the class path; agent not started", e);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "skillroy agent could not be started", e);
        }
    }

    @Override
    public void stop() {
        if (!startedHere) {
            return;
        }
        startedHere = false;
        try {
            EmbeddedAgent.stop();
        } catch (LinkageError | RuntimeException e) {
            LOG.log(Level.WARNING, "skillroy agent could not be stopped", e);
        }
    }
}
