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
 * End-to-end acceptance: the sample lending desk runs as a child process on the Java named by
 * {@code -Dskillroy.it.targetJava} (default: this JVM's). Phase 1 (contract section 10): once per load mode, every
 * check of {@link LoadModeChecks} runs against it through the real controller, agent home, domain pack and executor.
 * Phase 7 (phase7-contracts.md 13.2 items 1 to 6): the load modes run {@link VisionSwingChecks} (the checks of
 * {@link LoadModeChecks} with the Swing items woven in), and {@link RobotChecks} is the Robot leg.
 * Phase 2 and Phase 3 (phase2-contracts.md 14.2, phase3-contracts.md 11.2): once per load mode and seed, the checks of
 * {@link ResolutionChecks} run scenario A and scenario B ({@code borrow-2-waive-1-slip}: the waiver, its oracle
 * cross-checked against the desk's dry run, the wrong oracle reported as ORACLE_SUSPECT) with the committed YAML,
 * plans and bindings, and replay scenario C ({@code return-refunds-deposit}, authored by the planner: a return that
 * hands the deposit back, phase5-contracts.md 14.3, 15.2 item 3); once per target Java, {@link PerTargetJavaChecks}
 * covers the controller-side JDBC channel against the desk's own database listener (scenarios A and B) and the
 * assembled launcher.
 *
 * <p>Needs a display (each mode's checks are skipped when headless); run it with
 * {@code ci/run-in-linux.sh mvn -o verify -pl samples/skillroy-sample-lending-scenarios -Dskillroy.headless=false}, and
 * add {@code -Dskillroy.it.targetJava=/opt/jdk8/bin/java} for a Java 8 target. Target logs are in
 * {@code target/it-logs/}, scenario outputs in {@code target/it-runs/<mode>/}, timings in
 * {@code target/it-logs/timings.txt}.
 */
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class LendingEndToEndIT {

    /** {@code -javaagent} on the command line. */
    @Nested
    @Order(1)
    class JavaAgent extends VisionSwingChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVAAGENT;
        }
    }

    /** {@code -javaagent} injected through {@code JAVA_TOOL_OPTIONS} with a main-class guard. */
    @Nested
    @Order(2)
    class JavaToolOptions extends VisionSwingChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVA_TOOL_OPTIONS;
        }
    }

    /** Attached to the running application through the attach API. */
    @Nested
    @Order(3)
    class Attach extends VisionSwingChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.ATTACH;
        }
    }

    /** Started by the application's own plugin mechanism ({@code lending.plugins}), with the application as context. */
    @Nested
    @Order(4)
    class Embedded extends VisionSwingChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.EMBEDDED;
        }
    }

    /** Scenario A on seed A, agent on the command line. */
    @Nested
    @Order(5)
    class ResolutionJavaAgentSeedA extends ResolutionChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVAAGENT;
        }

        @Override
        Env env() {
            return Env.SEED_A;
        }
    }

    /** Scenario A on seed B, agent on the command line. */
    @Nested
    @Order(6)
    class ResolutionJavaAgentSeedB extends ResolutionChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVAAGENT;
        }

        @Override
        Env env() {
            return Env.SEED_B;
        }
    }

    /** Scenario A on seed A, agent through JAVA_TOOL_OPTIONS. */
    @Nested
    @Order(7)
    class ResolutionJavaToolOptionsSeedA extends ResolutionChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVA_TOOL_OPTIONS;
        }

        @Override
        Env env() {
            return Env.SEED_A;
        }
    }

    /** Scenario A on seed B, agent through JAVA_TOOL_OPTIONS. */
    @Nested
    @Order(8)
    class ResolutionJavaToolOptionsSeedB extends ResolutionChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVA_TOOL_OPTIONS;
        }

        @Override
        Env env() {
            return Env.SEED_B;
        }
    }

    /** Scenario A on seed A, agent attached. */
    @Nested
    @Order(9)
    class ResolutionAttachSeedA extends ResolutionChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.ATTACH;
        }

        @Override
        Env env() {
            return Env.SEED_A;
        }
    }

    /** Scenario A on seed B, agent attached. */
    @Nested
    @Order(10)
    class ResolutionAttachSeedB extends ResolutionChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.ATTACH;
        }

        @Override
        Env env() {
            return Env.SEED_B;
        }
    }

    /** Scenario A on seed A, agent embedded by the application's plugin mechanism. */
    @Nested
    @Order(11)
    class ResolutionEmbeddedSeedA extends ResolutionChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.EMBEDDED;
        }

        @Override
        Env env() {
            return Env.SEED_A;
        }
    }

    /** Scenario A on seed B, agent embedded by the application's plugin mechanism. */
    @Nested
    @Order(12)
    class ResolutionEmbeddedSeedB extends ResolutionChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.EMBEDDED;
        }

        @Override
        Env env() {
            return Env.SEED_B;
        }
    }

    /** Once per target Java: the JDBC channel against the desk's listener, and the assembled launcher. */
    @Nested
    @Order(13)
    class PerTargetJava extends PerTargetJavaChecks {
    }

    /**
     * The hooks leg (phase6-contracts.md 13.2 items 1 to 3, 6): {@code -javaagent} with the optional hooks module,
     * every check of {@link LoadModeChecks} with {@link HookChecks} woven in.
     */
    @Nested
    @Order(14)
    class JavaAgentHooks extends HookChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVAAGENT_HOOKS;
        }
    }

    /** Scenarios A, B, C and E on seed A with the hooks module loaded (phase6-contracts.md 13.2 items 4, 5). */
    @Nested
    @Order(15)
    class ResolutionJavaAgentHooksSeedA extends ResolutionChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVAAGENT_HOOKS;
        }

        @Override
        Env env() {
            return Env.SEED_A;
        }
    }

    /**
     * The Robot leg (phase7-contracts.md 13.2 item 6): {@code -javaagent} with the agent option {@code robot=true},
     * once per target Java.
     */
    @Nested
    @Order(17)
    class JavaAgentRobot extends RobotChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVAAGENT_ROBOT;
        }
    }

    /** Scenarios A, B, C and E on seed B with the hooks module loaded (phase6-contracts.md 13.2 items 4, 5). */
    @Nested
    @Order(16)
    class ResolutionJavaAgentHooksSeedB extends ResolutionChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVAAGENT_HOOKS;
        }

        @Override
        Env env() {
            return Env.SEED_B;
        }
    }

    /**
     * The tools leg (phase8-contracts.md 13.2 items 1 to 10): the desk with its "Desk tools" window
     * ({@code lending.tools=true}) and the agent option {@code robot=true}, once per target Java: the input veto on
     * every route, the visible area, heavyweight routing and AWT controls end to end ({@link InputSafetyChecks}).
     */
    @Nested
    @Order(18)
    class JavaAgentTools extends InputSafetyChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVAAGENT_TOOLS;
        }
    }

    /**
     * The scanner leg (phase8-contracts.md 13.2 item 30, ES): {@code -javaagent} on a desk launched with its scanner
     * driver, once per target Java.
     */
    @Nested
    @Order(19)
    class JavaAgentScanner extends ScannerChecks {
        @Override
        LendingTarget.Mode mode() {
            return LendingTarget.Mode.JAVAAGENT_SCANNER;
        }
    }
}
