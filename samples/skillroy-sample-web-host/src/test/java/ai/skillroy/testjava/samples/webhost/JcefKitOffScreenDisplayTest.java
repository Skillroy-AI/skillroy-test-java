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

package ai.skillroy.testjava.samples.webhost;

import ai.skillroy.testjava.agent.web.kit.WebAdapterFixture;
import ai.skillroy.testjava.agent.web.kit.WebEngineAdapterContract;
import ai.skillroy.testjava.samples.webhost.WebHostOptions.Rendering;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;

import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The adapter contract kit (phase4-contracts.md 10.3, 12.2 item 9) against the web host's real JCEF browsers,
 * rendered off screen: the front, the back and the view-less browser, item 9 with the remote-debugging port the
 * fixture picked ({@link JcefKitFixture}). Runs on a Java 25 and on a Java 8 test JVM. Needs a display: skipped when
 * headless, run under {@code ci/run-in-linux.sh ... -Pjcef -Dskillroy.headless=false}. CEF starts once per JVM, so
 * the class has a surefire execution of its own (the module's POM), which sets {@value #EXECUTION_PROPERTY}; run
 * anywhere else (a {@code -Dtest} pattern makes every execution run every class) it skips.
 */
@DisplayName("jcef adapter against the web host's JCEF, off screen (kit)")
class JcefKitOffScreenDisplayTest extends WebEngineAdapterContract {

    /** Set by this class's own surefire execution. */
    static final String EXECUTION_PROPERTY = "skillroy.kit.execution";

    @BeforeAll
    static void startTheHost() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        assumeTrue("off-screen".equals(System.getProperty(EXECUTION_PROPERTY)), "runs in its own surefire execution");
        JcefKitFixture.start(Rendering.OSR);
    }

    @AfterAll
    static void stopTheHost() {
        JcefKitFixture.stop();
    }

    @Override
    protected WebAdapterFixture fixture() {
        return JcefKitFixture.running();
    }
}
