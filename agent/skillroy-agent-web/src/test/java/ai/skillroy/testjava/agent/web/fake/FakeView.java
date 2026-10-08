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

import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;

import javax.swing.JComponent;

/** The neutral fake's Swing view of a browser ({@code getBrowser()}), a lightweight {@code JComponent}. */
public final class FakeView extends JComponent {

    static {
        ClassInitialisations.record(FakeView.class);
    }

    private static final long serialVersionUID = 1L;

    private final transient FakeBrowser browser;

    public FakeView(FakeBrowser browser) {
        this.browser = browser;
    }

    public FakeBrowser getBrowser() {
        return browser;
    }
}
