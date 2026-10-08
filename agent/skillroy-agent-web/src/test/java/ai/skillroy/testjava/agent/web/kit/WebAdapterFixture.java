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

package ai.skillroy.testjava.agent.web.kit;

import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebEngineAdapter;
import ai.skillroy.testjava.agent.api.spi.web.WebRendering;
import ai.skillroy.testjava.protocol.CapabilityState;

import java.util.Collection;
import java.util.Set;

/**
 * What a technology's test module supplies to the adapter contract kit ({@link WebEngineAdapterContract},
 * phase4-contracts.md section 10.1): the adapter under test, live browsers of that technology and the means to drive
 * them the way the application would. The kit calls every method from the test thread; none is called on the
 * guarded thread. The browsers are the fixture's for the whole test class; the kit never closes the visible, hidden
 * or view-less one (only one from {@link #newDisposableBrowser()}).
 */
public interface WebAdapterFixture {

    /** The three kinds of browser the kit exercises. */
    enum Which {
        /** A browser shown in a window. */
        VISIBLE,
        /** A browser whose component exists but is not showing (a hidden card), created by the application. */
        HIDDEN,
        /** A browser without a view (reached through a seed only). */
        VIEWLESS
    }

    /** What the application's own listener received (item 10). */
    interface AppListener {
        /** How many events the application's listener received so far. */
        int received();
    }

    /** A new adapter for one test; the kit calls {@code detach()} on it when the test ends. */
    WebEngineAdapter newAdapter();

    /** The loader of the application's classes (what {@code AgentContext.hostClassLoader()} gives). */
    ClassLoader hostLoader();

    /**
     * The seeds through which the adapter finds the browser (Swing components of the tree, or the technology's own
     * objects, as a {@code BrowserRegistryAdapter} would hand them); null for {@link Which#VIEWLESS} when the
     * fixture has no view-less browser (the kit then skips that part).
     */
    Collection<?> seeds(Which which);

    /** The technology's own object of the browser ({@code vendorObject()} of its handle), or null as for seeds. */
    Object browserObject(Which which);

    /** Loads the HTML into the browser's main frame and returns once the main frame finished loading (bounded). */
    void load(WebBrowser browser, String html) throws Exception;

    /**
     * Creates a browser the kit may close (with a document loaded) and returns the technology's own object of it,
     * which the kit hands to the adapter as the only seed; null when the fixture cannot (items 5 and 10 then skip
     * their closure parts).
     */
    Object newDisposableBrowser() throws Exception;

    /** Closes the browser the way the application would and returns once the technology reports it closed. */
    void close(WebBrowser browser) throws Exception;

    /**
     * Installs the application's own listener on the browser through the technology's API (a console or load
     * listener) and returns what it receives; the kit checks that {@code detach()} leaves it working.
     */
    AppListener installAppListener(WebBrowser browser) throws Exception;

    /** Runs the task on the thread that must never be blocked (the EDT for JCEF off-screen and for the fakes). */
    void postToGuardedThread(Runnable task);

    /** The rendering the adapter must report for the browser. */
    WebRendering expectedRendering(Which which);

    /** The state {@code enumerationState()} must report after {@code browsers(all seeds)}. */
    CapabilityState expectedEnumerationState();

    /** The configured remote-debugging port, or null when none is (item 9 then skips). */
    Integer debugPort();

    /** Whether item 8 checks the page's colours in the bitmap (false for a fake without a renderer). */
    boolean checksBitmapColours();

    /**
     * Item 1: a loader that can reach the technology's classes but has loaded none of them (the probe must find the
     * technology absent and define nothing), or null when the fixture cannot make one.
     */
    RecordingClassLoader pristineLoader();

    /**
     * Item 1: a loader standing for an application that uses the technology: it has loaded the technology's gate
     * class, and initialised it as the application's own use does, and nothing else of the technology (the probe must
     * find the technology present and initialise no class of it, see {@link #initialisedClasses}); or null when the
     * fixture cannot make one (a technology with native libraries cannot be loaded twice in one JVM).
     */
    RecordingClassLoader recordingHostLoader();

    /**
     * Item 1: the names of the technology's classes whose static initialiser has run in the loader (the
     * {@link #recordingHostLoader()}), or null when the fixture cannot tell (the kit then skips that check). A fake
     * technology answers from static initialisers that record themselves ({@link ClassInitialisations}); a real one's
     * classes cannot report it.
     */
    default Set<String> initialisedClasses(ClassLoader loader) {
        return null;
    }

    /**
     * Item 1: the packages the technology's classes live in (exact package names, a subpackage named on its own), or
     * null when the fixture cannot tell (the kit then skips that check): every class the probe makes the
     * {@link #recordingHostLoader()} define must belong to one of them (the probe stays within the technology).
     */
    default Set<String> technologyPackages() {
        return null;
    }

    /**
     * Item 10: how many listeners or observers are registered with the technology itself on the browser right now
     * (the application's and any adapter's), or -1 when the fixture cannot count them (the kit then skips the check
     * that {@code detach()} removed the adapter's on the technology's side).
     */
    default int vendorListenerCount(WebBrowser browser) {
        return -1;
    }
}
