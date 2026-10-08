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

/**
 * The neutral fake's navigation event ({@code url()}, {@code frame()}, {@code isInMainFrame()},
 * {@code hasCommitted()}, {@code error()}).
 */
public final class FakeNavigationFinished {

    static {
        ClassInitialisations.record(FakeNavigationFinished.class);
    }

    private final String url;
    private final FakeFrame frame;
    private final boolean inMainFrame;
    private final boolean committed;
    private final String error;

    FakeNavigationFinished(String url, FakeFrame frame, boolean inMainFrame, boolean committed, String error) {
        this.url = url;
        this.frame = frame;
        this.inMainFrame = inMainFrame;
        this.committed = committed;
        this.error = error;
    }

    public String url() {
        return url;
    }

    public FakeFrame frame() {
        return frame;
    }

    public boolean isInMainFrame() {
        return inMainFrame;
    }

    public boolean hasCommitted() {
        return committed;
    }

    public String error() {
        return error;
    }
}
