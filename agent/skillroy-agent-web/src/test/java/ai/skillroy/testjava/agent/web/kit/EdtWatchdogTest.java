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

import ai.skillroy.testjava.agent.web.fake.cef.FakeCefApp;
import ai.skillroy.testjava.agent.web.fake.cef.FakeCefBrowser;
import ai.skillroy.testjava.agent.web.fake.cef.FakeCefFixture;
import ai.skillroy.testjava.agent.web.fake.cef.FakeDevToolsClient;
import org.junit.jupiter.api.Test;

import java.awt.EventQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kit's watchdog of the guarded thread (phase4-contracts.md section 10.1 item 3, rule 4 of section 3): a block
 * outside the fixture's calls fails the check, one inside a fixture call is excused, one that started before a fixture
 * call is not forgiven by it, and kit item 5 fails an adapter whose event listeners block the guarded thread (events
 * the kit triggers by evaluation are judged). The guarded thread is the EDT; a block is a bounded wait on a latch
 * that nothing releases, standing for a careless listener.
 */
class EdtWatchdogTest {

    private static final long BLOCK_MS = 800;

    private static EdtWatchdog watchdog() {
        EdtWatchdog w = new EdtWatchdog(new EdtWatchdog.Poster() {
            @Override
            public void post(Runnable task) {
                EventQueue.invokeLater(task);
            }
        });
        w.start();
        return w;
    }

    /** Blocks the calling thread for {@code ms} (a wait nothing ends early). */
    private static void block(long ms) {
        try {
            new CountDownLatch(1).await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Posts a block of the EDT and returns a latch that opens when it ended. */
    private static CountDownLatch blockTheEdt() {
        final CountDownLatch ended = new CountDownLatch(1);
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                block(BLOCK_MS);
                ended.countDown();
            }
        });
        return ended;
    }

    @Test
    void aBlockOutsideTheFixturesCallsFailsTheCheck() throws Exception {
        EdtWatchdog w = watchdog();
        assertTrue(blockTheEdt().await(10, TimeUnit.SECONDS));
        AssertionError e = assertThrows(AssertionError.class, w::stopAndCheck);
        assertTrue(e.getMessage().contains("the guarded thread was blocked"), e.getMessage());
    }

    @Test
    void aBlockInsideAFixtureCallIsExcused() throws Exception {
        EdtWatchdog w = watchdog();
        w.excused(new EdtWatchdog.Call<Object>() {
            @Override
            public Object call() throws Exception {
                EventQueue.invokeAndWait(new Runnable() {
                    @Override
                    public void run() {
                        block(BLOCK_MS);
                    }
                });
                return null;
            }
        });
        w.stopAndCheck();
    }

    @Test
    void aBlockThatStartedBeforeAFixtureCallIsNotForgivenByIt() throws Exception {
        EdtWatchdog w = watchdog();
        final CountDownLatch ended = blockTheEdt();
        w.excused(new EdtWatchdog.Call<Object>() {
            @Override
            public Object call() throws Exception {
                return Boolean.valueOf(ended.await(10, TimeUnit.SECONDS)); // a fixture call outlasting the block
            }
        });
        AssertionError e = assertThrows(AssertionError.class, w::stopAndCheck);
        assertTrue(e.getMessage().contains("the guarded thread was blocked"), e.getMessage());
    }

    @Test
    void item5FailsAnAdapterWhoseListenersBlockTheGuardedThread() throws Exception {
        final FakeCefFixture f = new FakeCefFixture();
        try {
            // A listener on the browser's DevTools client that blocks the EDT for the console and load events, as a
            // careless adapter's listener would.
            FakeDevToolsClient c = ((FakeCefBrowser) f.browserObject(WebAdapterFixture.Which.VISIBLE))
                    .getDevToolsClient();
            c.addEventListener(new FakeDevToolsClient.EventListener() {
                @Override
                public void onEvent(String name, String json) {
                    if (EventQueue.isDispatchThread() && ("Runtime.consoleAPICalled".equals(name)
                            || "Page.loadEventFired".equals(name))) {
                        block(BLOCK_MS);
                    }
                }
            });
            WebEngineAdapterContract kit = new WebEngineAdapterContract() {
                @Override
                protected WebAdapterFixture fixture() {
                    return f;
                }
            };
            kit.setUpKit();
            try {
                kit.item05EventsLoadNavigationConsoleClosureAndNoneAfterCancel();
            } finally {
                AssertionError e = assertThrows(AssertionError.class, kit::tearDownKit);
                assertTrue(e.getMessage().contains("the guarded thread was blocked"), e.getMessage());
            }
        } finally {
            FakeCefApp.dispose();
        }
    }
}
