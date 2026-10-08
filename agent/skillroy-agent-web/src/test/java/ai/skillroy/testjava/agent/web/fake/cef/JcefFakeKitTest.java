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

package ai.skillroy.testjava.agent.web.fake.cef;

import ai.skillroy.testjava.agent.web.kit.WebAdapterFixture;
import ai.skillroy.testjava.agent.web.kit.WebEngineAdapterContract;
import org.junit.jupiter.api.DisplayName;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The adapter contract kit against the fake JCEF shapes, so that the {@code jcef} adapter's protocol handling is
 * proven here too (the real JCEF run is the web host sample's, in the Linux image). After every test, right after the
 * kit detached the adapter (so that a violation made by {@code detach()} is charged to the test that made it): the
 * fake saw no {@code CefApp.getInstance()} and no {@code CefDevToolsClient.close()} by the adapter.
 */
@DisplayName("jcef adapter against the fake JCEF shapes (kit)")
class JcefFakeKitTest extends WebEngineAdapterContract {

    private static FakeCefFixture shared;

    @Override
    protected WebAdapterFixture fixture() throws Exception {
        if (shared == null) {
            shared = new FakeCefFixture();
        }
        return shared;
    }

    @Override
    protected void afterDetach() {
        assertEquals(Collections.emptyList(), FakeCefApp.violations());
        assertEquals(Collections.emptyList(), FakeCefBrowserBase.violations());
    }
}
