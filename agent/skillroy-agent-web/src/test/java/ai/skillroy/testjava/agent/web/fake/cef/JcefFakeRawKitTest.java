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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The adapter contract kit against the fake JCEF shapes through the raw channel (the descriptor with the
 * {@code raw} roles, as {@code jcef.json} has them), where every second answer reaches the observers before its
 * message id: the kit passes, the adapter sends nothing through the shared client, and the fake saw no violation
 * (checked right after the kit detached the adapter, so that a violation made by {@code detach()} is charged to the
 * test that made it).
 */
@DisplayName("jcef adapter against the fake JCEF shapes, raw channel (kit)")
class JcefFakeRawKitTest extends WebEngineAdapterContract {

    private static FakeCefFixture shared;
    private int clientCallsBefore;

    @Override
    protected WebAdapterFixture fixture() throws Exception {
        if (shared == null) {
            shared = new FakeCefFixture(FakeCefFixture.RAW_DESCRIPTOR);
        }
        return shared;
    }

    @BeforeEach
    void countClientCalls() {
        clientCallsBefore = FakeDevToolsClient.executed();
    }

    @Override
    protected void afterDetach() {
        assertEquals(Collections.emptyList(), FakeCefApp.violations());
        assertEquals(Collections.emptyList(), FakeCefBrowserBase.violations());
        assertEquals(clientCallsBefore, FakeDevToolsClient.executed(), "every call went through the raw channel");
    }
}
