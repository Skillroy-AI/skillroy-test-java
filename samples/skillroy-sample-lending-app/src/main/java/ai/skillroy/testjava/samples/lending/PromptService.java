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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The desk's modal questions. {@link #ask} blocks the asking thread (the engine thread) until the question is
 * answered, by a button in the {@link PromptView} or by {@link #answer} from any other thread. One prompt is open
 * at a time.
 *
 * <p>Thread-safe. {@link #ask} must not be called on the event dispatch thread (it would block the user interface
 * that is supposed to answer it).
 */
public final class PromptService {

    /**
     * Observer of prompts, called on the asking thread: {@code shown} after the prompt became {@link #current()},
     * {@code answered} after it was answered and hidden. Must not block.
     */
    public interface Listener {
        /** The prompt is open. */
        default void shown(Prompt prompt) {
        }

        /** The prompt was answered with {@code choice}. */
        default void answered(Prompt prompt, String choice) {
        }
    }

    private static final Logger LOG = Logger.getLogger(PromptService.class.getName());

    private final Object lock = new Object();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private volatile PromptView view;
    private Prompt current;
    private String choice;

    /** @param view the view that presents prompts, or null for none. Takes effect for the next prompt. */
    public void setView(PromptView view) {
        this.view = view;
    }

    /** @return the current view, or null */
    public PromptView view() {
        return view;
    }

    /** Adds a listener. Thread-safe. */
    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Removes a listener. Thread-safe. */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Opens a prompt and waits until it is answered. There is no timeout: like any modal question, it stays open
     * until somebody answers it or the asking thread is interrupted.
     *
     * @param id stable prompt id
     * @param text text shown to the user
     * @param buttons button labels in display order
     * @return the chosen button label, exactly as declared
     * @throws InterruptedException if the asking thread was interrupted; the prompt is then closed unanswered
     * @throws IllegalStateException if another prompt is open
     */
    public String ask(String id, String text, List<String> buttons) throws InterruptedException {
        Prompt prompt = new Prompt(id, text, buttons);
        synchronized (lock) {
            if (current != null) {
                throw new IllegalStateException("a prompt is already open: " + current.id());
            }
            current = prompt;
            choice = null;
        }
        for (Listener listener : listeners) {
            try {
                listener.shown(prompt);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "prompt listener failed", e);
            }
        }
        PromptView v = view;
        if (v != null) {
            try {
                v.show(prompt, this);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "prompt view failed to show " + prompt.id(), e);
            }
        }
        String answer;
        try {
            synchronized (lock) {
                while (choice == null) {
                    lock.wait();
                }
                answer = choice;
            }
        } finally {
            synchronized (lock) {
                current = null;
                choice = null;
            }
            if (v != null) {
                try {
                    v.hide(prompt);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "prompt view failed to hide " + prompt.id(), e);
                }
            }
        }
        for (Listener listener : listeners) {
            try {
                listener.answered(prompt, answer);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "prompt listener failed", e);
            }
        }
        return answer;
    }

    /**
     * Answers the open prompt, whatever its id. Thread-safe; returns at once.
     *
     * @param choice a button label (exact, else case-insensitive)
     * @return false if no prompt is open (or it was already answered)
     * @throws IllegalArgumentException if {@code choice} is not one of the open prompt's buttons
     */
    public boolean answer(String choice) {
        return answer(null, choice);
    }

    /**
     * Answers the open prompt only if it has the given id, so that a late answer cannot hit a newer prompt.
     *
     * @param promptId the id the open prompt must have, or null for any
     * @param choice a button label (exact, else case-insensitive)
     * @return false if no prompt is open, it was already answered, or its id differs
     * @throws IllegalArgumentException if {@code choice} is not one of the open prompt's buttons
     */
    public boolean answer(String promptId, String choice) {
        synchronized (lock) {
            if (current == null || this.choice != null) {
                return false;
            }
            if (promptId != null && !promptId.equals(current.id())) {
                return false;
            }
            String button = current.matchButton(choice);
            if (button == null) {
                throw new IllegalArgumentException("'" + choice + "' is not a button of prompt " + current.id()
                    + " " + current.buttons());
            }
            this.choice = button;
            lock.notifyAll();
            return true;
        }
    }

    /** @return the open, not yet answered prompt, or null. Thread-safe. */
    public Prompt current() {
        synchronized (lock) {
            return choice == null ? current : null;
        }
    }
}
