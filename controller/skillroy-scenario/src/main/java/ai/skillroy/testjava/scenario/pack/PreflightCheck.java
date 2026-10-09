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

package ai.skillroy.testjava.scenario.pack;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.data.DataChannel;

/**
 * One environment check a pack runs before a scenario binds its slots (desk open, device usable, …). A failing check
 * is reported as ENVIRONMENT, not as a test failure, because the target application was never exercised.
 *
 * <p>Called on the executor's thread; must be read-only and bounded. The executor calls
 * {@link #check(PreflightContext)}, whose default calls {@link #check(AgentConnection)}: a check that reads the
 * database overrides the context form and reads through {@link PreflightContext#data()}, so that it also passes in
 * an environment that serves the source over JDBC (phase2-contracts.md 6.1).
 */
public interface PreflightCheck {

    /** A short id for reports, for example {@code "printer.online"}. */
    String id();

    /**
     * @return null = ok, else the reason
     * @throws Exception any failure, reported as ENVIRONMENT with the exception's message
     */
    String check(AgentConnection agent) throws Exception;

    /**
     * The Phase 2 entry; the executor calls this one. {@code ctx.data()} routes by source like every other read
     * (phase2-contracts.md 7.2).
     *
     * @return null = ok, else the reason
     * @throws Exception any failure, reported as ENVIRONMENT with the exception's message
     */
    default String check(PreflightContext ctx) throws Exception {
        return check(ctx.agent());
    }

    /** What a pre-flight check may use. Valid only during the call it was passed to. */
    interface PreflightContext {

        /** The scenario's connection. */
        AgentConnection agent();

        /** Guarded read-only SQL, routed by source id (JDBC for the sources credentials.yaml names). */
        DataChannel data();

        /** The environment id of the run. */
        String envId();

        /**
         * The running pack's settings (phase6-contracts.md 11.1); {@link PackSettings#EMPTY} by default, for a test's
         * fake.
         */
        default PackSettings settings() {
            return PackSettings.EMPTY;
        }
    }
}
