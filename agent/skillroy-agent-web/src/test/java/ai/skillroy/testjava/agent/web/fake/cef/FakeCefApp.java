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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * The neutral fake of JCEF's application singleton, with the members the {@code jcef} adapter's descriptor names:
 * the private static {@code self}, the private {@code clients_} and {@code settings_}, the static {@code getState()},
 * {@code getVersion()}, and a {@code getInstance()} that fails the test when called (it would create the
 * technology; browser-spi F3). {@link #startup} and {@link #dispose} are the application's.
 */
public final class FakeCefApp {

    static {
        ClassInitialisations.record(FakeCefApp.class);
    }

    private static FakeCefApp self;
    private static volatile FakeCefAppState state = FakeCefAppState.NONE;
    private static final List<String> VIOLATIONS = Collections.synchronizedList(new ArrayList<String>());

    private final HashSet<FakeCefClient> clients_ = new HashSet<FakeCefClient>();
    private final FakeCefSettings settings_;
    final FakeRenderer renderer = new FakeRenderer();

    private FakeCefApp(FakeCefSettings settings) {
        this.settings_ = settings;
    }

    /** The application builds the singleton (as {@code CefAppBuilder.build()} does). */
    public static synchronized FakeCefApp startup(FakeCefSettings settings) {
        self = new FakeCefApp(settings);
        state = FakeCefAppState.INITIALIZED;
        return self;
    }

    /** The application's end of CEF. */
    public static synchronized void dispose() {
        if (self != null) {
            self.renderer.shutdown();
        }
        self = null;
        state = FakeCefAppState.TERMINATED;
    }

    /** Sets the state without an instance (tests of the state rule). */
    public static synchronized void forceState(FakeCefAppState s) {
        state = s;
    }

    public static FakeCefAppState getState() {
        return state;
    }

    /** Never to be called by an adapter: it would create CEF. */
    public static FakeCefApp getInstance() {
        VIOLATIONS.add("CefApp.getInstance() was called");
        throw new AssertionError("an adapter called getInstance()");
    }

    /** The violations seen (for tests). */
    public static List<String> violations() {
        synchronized (VIOLATIONS) {
            return new ArrayList<String>(VIOLATIONS);
        }
    }

    public FakeCefVersion getVersion() {
        return new FakeCefVersion();
    }

    public synchronized FakeCefClient createClient() {
        FakeCefClient c = new FakeCefClient(this);
        clients_.add(c);
        return c;
    }

    synchronized void removeClient(FakeCefClient c) {
        clients_.remove(c);
    }

    public FakeCefSettings settings() {
        return settings_;
    }

    /** The version, shaped like JCEF's nested version class. */
    public final class FakeCefVersion {
        public String getChromeVersion() {
            return "152.0.7977.83";
        }

        @Override
        public String toString() {
            return "JCEF Version = fake\nChromium Version = 152.0.7977.83";
        }
    }
}
