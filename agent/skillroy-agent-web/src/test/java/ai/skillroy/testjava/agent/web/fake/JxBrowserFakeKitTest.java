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

package ai.skillroy.testjava.agent.web.fake;

import ai.skillroy.testjava.agent.web.kit.WebAdapterFixture;
import ai.skillroy.testjava.agent.web.kit.WebEngineAdapterContract;
import org.junit.jupiter.api.DisplayName;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The adapter contract kit against the neutral fake of JxBrowser, without the {@code InjectJsCallback} opt-in (init
 * scripts after the document loaded): every item but item 8's colour check and item 9, as phase4-contracts.md
 * section 10.2 says. After every test, right after the kit detached the adapter (so that a violation made by
 * {@code detach()} is charged to the test that made it): no rule violation seen by the fake (no blocking call on the
 * EDT, no licence key read, no input dispatched) and no {@code set(...)} callback taken.
 */
@DisplayName("jxbrowser adapter against the neutral fake (kit)")
class JxBrowserFakeKitTest extends WebEngineAdapterContract {

    private static FakeJxFixture shared;

    @Override
    protected WebAdapterFixture fixture() throws Exception {
        if (shared == null) {
            shared = new FakeJxFixture(false);
        }
        return shared;
    }

    @Override
    protected void afterDetach() {
        assertEquals(Collections.emptyList(), shared.engine.violations());
        for (FakeBrowser b : new FakeBrowser[] {shared.visible, shared.hidden, shared.viewless}) {
            assertEquals(Collections.emptyList(), b.setCalls(), "no set(...) callback without the opt-in");
        }
    }
}
