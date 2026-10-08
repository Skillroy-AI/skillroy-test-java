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

package ai.skillroy.testjava.samples.webhost.scenarios;

import ai.skillroy.testjava.samples.webhost.WebHostOptions;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestClassOrder;

/**
 * End-to-end acceptance of the web verbs (phase4-contracts.md 12.2, items 1 to 5, 7 and 8): the web host runs as a
 * child process on the Java named by {@code -Dskillroy.it.targetJava} (default: this JVM's), with the agent in
 * {@code JAVAAGENT} and {@code ATTACH} mode, in both rendering modes on a Java 9+ target and off screen only on a
 * Java 8 target (windowed JCEF on Java 8 is unverified, spike BS8), each twice: without a remote-debugging port (the
 * agent path) and with a free port this test picks (the CDP path). The checks are {@link WebHostChecks}; item 6 is
 * {@link CdpAttachSoakIT}; item 9 (the adapter contract kit against JCEF) runs in the web host module.
 *
 * <p>Needs a display and the profile {@code jcef}; run it with {@code ci/run-in-linux.sh mvn -o verify -Pjcef
 * -Dskillroy.headless=false -pl samples/skillroy-sample-web-host-scenarios -am} (with
 * {@code -Dskillroy.jcef.platform=linux-arm64} on an arm64 image), and add
 * {@code -Dskillroy.it.targetJava=/opt/jdk8/bin/java} for a Java 8 target. Target logs are in
 * {@code target/it-logs/}, scenario outputs in {@code target/it-runs/<launch>/}, timings in
 * {@code target/it-logs/timings.txt}.
 */
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class WebHostEndToEndIT {

    /** {@code -javaagent}, windowed, no remote-debugging port. */
    @Nested
    @Order(1)
    class JavaAgentWindowedWithoutPort extends WebHostChecks {
        @Override
        WebHostTarget.Mode mode() {
            return WebHostTarget.Mode.JAVAAGENT;
        }

        @Override
        WebHostOptions.Rendering rendering() {
            return WebHostOptions.Rendering.WINDOWED;
        }

        @Override
        boolean withPort() {
            return false;
        }
    }

    /** {@code -javaagent}, windowed, a remote-debugging port. */
    @Nested
    @Order(2)
    class JavaAgentWindowedWithPort extends WebHostChecks {
        @Override
        WebHostTarget.Mode mode() {
            return WebHostTarget.Mode.JAVAAGENT;
        }

        @Override
        WebHostOptions.Rendering rendering() {
            return WebHostOptions.Rendering.WINDOWED;
        }

        @Override
        boolean withPort() {
            return true;
        }
    }

    /** {@code -javaagent}, off screen, no remote-debugging port. */
    @Nested
    @Order(3)
    class JavaAgentOffScreenWithoutPort extends WebHostChecks {
        @Override
        WebHostTarget.Mode mode() {
            return WebHostTarget.Mode.JAVAAGENT;
        }

        @Override
        WebHostOptions.Rendering rendering() {
            return WebHostOptions.Rendering.OSR;
        }

        @Override
        boolean withPort() {
            return false;
        }
    }

    /** {@code -javaagent}, off screen, a remote-debugging port; also the assembled launcher (once per target Java). */
    @Nested
    @Order(4)
    class JavaAgentOffScreenWithPort extends WebHostChecks {
        @Override
        WebHostTarget.Mode mode() {
            return WebHostTarget.Mode.JAVAAGENT;
        }

        @Override
        WebHostOptions.Rendering rendering() {
            return WebHostOptions.Rendering.OSR;
        }

        @Override
        boolean withPort() {
            return true;
        }

        @Override
        boolean runsTheLauncherChecks() {
            return true;
        }
    }

    /** Attached, windowed, no remote-debugging port. */
    @Nested
    @Order(5)
    class AttachWindowedWithoutPort extends WebHostChecks {
        @Override
        WebHostTarget.Mode mode() {
            return WebHostTarget.Mode.ATTACH;
        }

        @Override
        WebHostOptions.Rendering rendering() {
            return WebHostOptions.Rendering.WINDOWED;
        }

        @Override
        boolean withPort() {
            return false;
        }
    }

    /** Attached, windowed, a remote-debugging port. */
    @Nested
    @Order(6)
    class AttachWindowedWithPort extends WebHostChecks {
        @Override
        WebHostTarget.Mode mode() {
            return WebHostTarget.Mode.ATTACH;
        }

        @Override
        WebHostOptions.Rendering rendering() {
            return WebHostOptions.Rendering.WINDOWED;
        }

        @Override
        boolean withPort() {
            return true;
        }
    }

    /** Attached, off screen, no remote-debugging port. */
    @Nested
    @Order(7)
    class AttachOffScreenWithoutPort extends WebHostChecks {
        @Override
        WebHostTarget.Mode mode() {
            return WebHostTarget.Mode.ATTACH;
        }

        @Override
        WebHostOptions.Rendering rendering() {
            return WebHostOptions.Rendering.OSR;
        }

        @Override
        boolean withPort() {
            return false;
        }
    }

    /** Attached, off screen, a remote-debugging port. */
    @Nested
    @Order(8)
    class AttachOffScreenWithPort extends WebHostChecks {
        @Override
        WebHostTarget.Mode mode() {
            return WebHostTarget.Mode.ATTACH;
        }

        @Override
        WebHostOptions.Rendering rendering() {
            return WebHostOptions.Rendering.OSR;
        }

        @Override
        boolean withPort() {
            return true;
        }
    }
}
