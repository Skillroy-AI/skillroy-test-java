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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestClassOrder;

/**
 * Phase 5 end-to-end acceptance (phase5-contracts.md 15.2): the sample lending desk runs as a child process on the
 * Java named by {@code -Dskillroy.it.targetJava} (default: this JVM's), once per load mode and seed, and the checks of
 * {@link InferenceChecks} run against it with the committed recordings and no model key: scenario D replays green with
 * zero model calls in every mode on both seeds (item 1); with the agent on the command line, on each seed, scenario D
 * is approved against the recordings to the committed bytes and its two negatives fail as they should (item 2), the
 * nightly job's dry run leaves every file unchanged (item 7) and nothing written holds a secret (item 8); on seed A the
 * interactive session exports the committed scenario, which replays (item 4), and the triage of two failed runs comes
 * back the same through its recordings (item 6). Item 3 (scenario C) waits for the planner's live run; item 5 and the
 * command form of item 4 wait for the CLI's new commands ({@code @Disabled("CLI wave")}).
 *
 * <p>Needs a display (each class's checks are skipped when headless); run it with
 * {@code ci/run-in-linux.sh mvn -o verify -pl samples/skillroy-sample-lending-scenarios -am -Dskillroy.headless=false
 * -Dit.test=InferenceEndToEndIT}, and add {@code -Dskillroy.it.targetJava=/opt/jdk8/bin/java} for a Java 8 target.
 * Scenario outputs are in {@code target/it-runs/inference/}.
 */
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class InferenceEndToEndIT {

    /** Every check of seed A, agent on the command line. */
    @Nested
    @Order(1)
    class JavaAgentSeedA extends InferenceChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVAAGENT;
        }

        @Override
        Env env() {
            return Env.SEED_A;
        }
    }

    /** Every check of seed B, agent on the command line. */
    @Nested
    @Order(2)
    class JavaAgentSeedB extends InferenceChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVAAGENT;
        }

        @Override
        Env env() {
            return Env.SEED_B;
        }
    }

    /** Item 1 on seed A, agent through JAVA_TOOL_OPTIONS. */
    @Nested
    @Order(3)
    class JavaToolOptionsSeedA extends InferenceChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVA_TOOL_OPTIONS;
        }

        @Override
        Env env() {
            return Env.SEED_A;
        }
    }

    /** Item 1 on seed B, agent through JAVA_TOOL_OPTIONS. */
    @Nested
    @Order(4)
    class JavaToolOptionsSeedB extends InferenceChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVA_TOOL_OPTIONS;
        }

        @Override
        Env env() {
            return Env.SEED_B;
        }
    }

    /** Item 1 on seed A, agent attached. */
    @Nested
    @Order(5)
    class AttachSeedA extends InferenceChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.ATTACH;
        }

        @Override
        Env env() {
            return Env.SEED_A;
        }
    }

    /** Item 1 on seed B, agent attached. */
    @Nested
    @Order(6)
    class AttachSeedB extends InferenceChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.ATTACH;
        }

        @Override
        Env env() {
            return Env.SEED_B;
        }
    }

    /** Item 1 on seed A, agent embedded by the application's plugin mechanism. */
    @Nested
    @Order(7)
    class EmbeddedSeedA extends InferenceChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.EMBEDDED;
        }

        @Override
        Env env() {
            return Env.SEED_A;
        }
    }

    /** Item 1 on seed B, agent embedded by the application's plugin mechanism. */
    @Nested
    @Order(8)
    class EmbeddedSeedB extends InferenceChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.EMBEDDED;
        }

        @Override
        Env env() {
            return Env.SEED_B;
        }
    }

    /** Item 1 on seed A with the hooks module loaded (phase6-contracts.md 13.2 items 4 and 5). */
    @Nested
    @Order(9)
    class JavaAgentHooksSeedA extends InferenceChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVAAGENT_HOOKS;
        }

        @Override
        Env env() {
            return Env.SEED_A;
        }
    }

    /** Item 1 on seed B with the hooks module loaded (phase6-contracts.md 13.2 items 4 and 5). */
    @Nested
    @Order(10)
    class JavaAgentHooksSeedB extends InferenceChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVAAGENT_HOOKS;
        }

        @Override
        Env env() {
            return Env.SEED_B;
        }
    }
}
