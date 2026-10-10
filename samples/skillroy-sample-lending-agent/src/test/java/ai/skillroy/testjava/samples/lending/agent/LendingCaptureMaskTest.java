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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.samples.lending.EntryField;
import ai.skillroy.testjava.samples.lending.NumberPad;
import org.junit.jupiter.api.Test;

import java.awt.EventQueue;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTextField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The desk's capture mask (phase7-contracts.md sections 3.6, 12.2, 13.1 item 9, D358): the entry field while it is
 * masked, and the number pad that feeds it, nothing else.
 */
class LendingCaptureMaskTest {

    private static <T> T onEdt(java.util.concurrent.Callable<T> task) throws Exception {
        final AtomicReference<T> out = new AtomicReference<T>();
        final AtomicReference<Exception> failure = new AtomicReference<Exception>();
        EventQueue.invokeAndWait(() -> {
            try {
                out.set(task.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return out.get();
    }

    @Test
    void theMaskNamesTheEntryFieldAndThePadWhileASecretIsExpected() throws Exception {
        LendingCaptureMask mask = new LendingCaptureMask();
        EntryField entry = onEdt(() -> new EntryField(10));
        NumberPad pad = onEdt(() -> new NumberPad(entry));
        assertFalse(mask.masks(entry));
        assertFalse(mask.masks(pad));
        onEdt(() -> {
            entry.setMasked(true);
            return null;
        });
        assertTrue(mask.masks(entry), "the password state");
        assertTrue(mask.masks(pad), "the pad feeds the masked field (D358)");
        assertFalse(mask.masks(new JTextField("other")), "nothing else");
        assertNull(mask.regions(entry), "the whole rectangle");
        onEdt(() -> {
            pad.press("4");
            pad.press("2");
            return null;
        });
        assertEquals("42", onEdt(entry::value), "the pad types through the field's document");
        assertEquals("**", onEdt(entry::getText), "and the field's mask applies");
    }
}
