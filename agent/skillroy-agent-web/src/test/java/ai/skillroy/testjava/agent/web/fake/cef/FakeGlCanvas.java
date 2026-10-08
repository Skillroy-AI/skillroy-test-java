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

import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;

import java.awt.Canvas;
import java.awt.Graphics;

/**
 * A heavyweight canvas standing for JCEF's off-screen GL canvas; like the real one under {@code printAll} (BS8 F-E),
 * printing it fails. It reports itself showing once its browser was shown ({@link FakeCefBrowserBase#show}), as the
 * real one does inside a showing window (the fakes never show a window).
 */
public final class FakeGlCanvas extends Canvas {

    static {
        ClassInitialisations.record(FakeGlCanvas.class);
    }

    private static final long serialVersionUID = 1L;

    private volatile boolean shown;

    void shown(boolean on) {
        shown = on;
    }

    @Override
    public boolean isShowing() {
        return shown;
    }

    @Override
    public void print(Graphics g) {
        throw new IllegalStateException("setupPrint() not called");
    }
}
