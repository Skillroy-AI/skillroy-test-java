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

import java.util.HashMap;

/** The neutral fake of JCEF's client: the protected {@code getAllBrowser()} over a private map, as JCEF keeps it. */
public final class FakeCefClient {

    static {
        ClassInitialisations.record(FakeCefClient.class);
    }

    private final FakeCefApp app;
    private final HashMap<Integer, FakeCefBrowser> browser_ = new HashMap<Integer, FakeCefBrowser>();
    private int nextKey;

    FakeCefClient(FakeCefApp app) {
        this.app = app;
    }

    protected Object[] getAllBrowser() {
        synchronized (browser_) {
            return browser_.values().toArray();
        }
    }

    /** A new browser; off-screen ({@code osr}) or windowed. Its native browser exists once it is shown or created. */
    public FakeCefBrowser createBrowser(String url, boolean osr) {
        FakeCefBrowserBase b = osr ? new FakeCefBrowserOsr(app, this) : new FakeCefBrowserWr(app, this);
        synchronized (browser_) {
            browser_.put(Integer.valueOf(++nextKey), b);
        }
        return b;
    }

    void removed(FakeCefBrowser b) {
        synchronized (browser_) {
            browser_.values().remove(b);
        }
    }

    public FakeCefApp app() {
        return app;
    }
}
