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

package ai.skillroy.testjava.samples.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** Blocking ask, answers from other threads, and abandonment. */
class PromptServiceTest {

    private static Prompt awaitOpen(PromptService service) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Recorder.TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Prompt p = service.current();
            if (p != null) {
                return p;
            }
            Thread.sleep(10);
        }
        fail("no prompt opened");
        return null;
    }

    @Test
    void askBlocksUntilAnswered() throws Exception {
        PromptService service = new PromptService();
        RecordingPromptView view = new RecordingPromptView();
        service.setView(view);
        AtomicReference<String> answer = new AtomicReference<String>();
        Thread asker = new Thread(() -> {
            try {
                answer.set(service.ask("q", "Continue?", Arrays.asList("Yes", "No")));
            } catch (InterruptedException e) {
                answer.set("interrupted");
            }
        }, "asker");
        assertFalse(service.answer("Yes"), "nothing to answer yet");
        asker.start();
        Prompt open = awaitOpen(service);
        assertEquals("q", open.id());
        assertEquals("Continue?", open.text());
        assertTrue(asker.isAlive());
        assertThrows(IllegalArgumentException.class, () -> service.answer("Maybe"));
        assertFalse(service.answer("other-id", "Yes"), "a different id is not answered");
        assertTrue(service.answer("q", "no"));
        assertFalse(service.answer("Yes"), "already answered");
        asker.join(Recorder.TIMEOUT_MS);
        assertEquals("No", answer.get());
        assertNull(service.current());
        assertEquals(1, view.shown().size());
        assertEquals(1, view.hidden().size());
    }

    @Test
    void interruptAbandonsThePrompt() throws Exception {
        PromptService service = new PromptService();
        AtomicReference<String> outcome = new AtomicReference<String>();
        Thread asker = new Thread(() -> {
            try {
                service.ask("q", "Wait", Collections.singletonList("OK"));
                outcome.set("answered");
            } catch (InterruptedException e) {
                outcome.set("interrupted");
            }
        }, "asker");
        asker.start();
        awaitOpen(service);
        assertThrows(IllegalStateException.class, () -> service.ask("other", "x", Collections.singletonList("OK")));
        asker.interrupt();
        asker.join(Recorder.TIMEOUT_MS);
        assertEquals("interrupted", outcome.get());
        assertNull(service.current());
    }

    @Test
    void ofManyConcurrentAnswersExactlyOneWins() throws Exception {
        PromptService service = new PromptService();
        for (int round = 0; round < 20; round++) {
            AtomicReference<String> answer = new AtomicReference<String>();
            Thread asker = new Thread(() -> {
                try {
                    answer.set(service.ask("q", "Pick", Arrays.asList("A", "B")));
                } catch (InterruptedException e) {
                    answer.set("interrupted");
                }
            }, "asker");
            asker.start();
            awaitOpen(service);
            int answerers = 8;
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger wins = new AtomicInteger();
            AtomicReference<String> winner = new AtomicReference<String>();
            List<Thread> threads = new ArrayList<Thread>();
            for (int i = 0; i < answerers; i++) {
                String choice = i % 2 == 0 ? "A" : "B";
                Thread t = new Thread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (service.answer(choice)) {
                        wins.incrementAndGet();
                        winner.set(choice);
                    }
                }, "answerer-" + i);
                t.start();
                threads.add(t);
            }
            go.countDown();
            for (Thread t : threads) {
                t.join(Recorder.TIMEOUT_MS);
            }
            asker.join(Recorder.TIMEOUT_MS);
            assertFalse(asker.isAlive());
            assertEquals(1, wins.get(), "exactly one answer is taken");
            assertEquals(winner.get(), answer.get(), "ask returns the answer that won");
            assertNull(service.current());
        }
    }

    @Test
    void aFailingViewOrListenerDoesNotBreakThePrompt() throws Exception {
        PromptService service = new PromptService();
        List<String> seen = Collections.synchronizedList(new ArrayList<String>());
        service.setView(new PromptView() {
            @Override
            public void show(Prompt prompt, PromptService s) {
                throw new IllegalStateException("view failure for the test");
            }

            @Override
            public void hide(Prompt prompt) {
                throw new IllegalStateException("view failure for the test");
            }
        });
        service.addListener(new PromptService.Listener() {
            @Override
            public void shown(Prompt prompt) {
                throw new IllegalStateException("listener failure for the test");
            }
        });
        service.addListener(new PromptService.Listener() {
            @Override
            public void shown(Prompt prompt) {
                seen.add("shown " + prompt.id());
            }

            @Override
            public void answered(Prompt prompt, String choice) {
                seen.add("answered " + choice);
            }
        });
        AtomicReference<String> answer = new AtomicReference<String>();
        Thread asker = new Thread(() -> {
            try {
                answer.set(service.ask("q", "Continue?", Collections.singletonList("OK")));
            } catch (InterruptedException | RuntimeException e) {
                answer.set(e.toString());
            }
        }, "asker");
        asker.start();
        awaitOpen(service);
        assertTrue(service.answer("ok"));
        asker.join(Recorder.TIMEOUT_MS);
        assertEquals("OK", answer.get());
        assertEquals(Arrays.asList("shown q", "answered OK"), seen);
        assertNull(service.current());
    }

    @Test
    void promptValidatesButtons() {
        assertThrows(IllegalArgumentException.class, () -> new Prompt("x", "t", Collections.<String>emptyList()));
        assertThrows(IllegalArgumentException.class, () -> new Prompt("x", "t", Arrays.asList("OK", "OK")));
        assertEquals("Cancel", new Prompt("x", "t", Arrays.asList("OK", "Cancel")).matchButton("CANCEL"));
        assertNull(new Prompt("x", "t", Arrays.asList("OK")).matchButton("No"));
    }
}
