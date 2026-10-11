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

import ai.skillroy.testjava.agent.api.spi.RegionResolver;
import ai.skillroy.testjava.samples.lending.EntryField;
import ai.skillroy.testjava.samples.lending.NumberPad;
import org.junit.jupiter.api.Test;

import java.awt.EventQueue;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The number pad's regions on an unshown pad (phase7-contracts.md sections 12.2, 13.1 item 9): regions need no
 * display.
 */
class LendingRegionsTest {

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
    void theNumberPadHasTwelveKeyRegions() throws Exception {
        LendingRegions regions = new LendingRegions();
        NumberPad pad = onEdt(() -> new NumberPad(new EntryField(10)));
        assertTrue(regions.supports(pad));
        assertFalse(regions.supports(new JButton("x")));
        List<String> ids = new ArrayList<String>();
        for (RegionResolver.Region r : onEdt(() -> regions.regions(pad))) {
            ids.add(r.id);
        }
        assertEquals(Arrays.asList("key:7", "key:8", "key:9", "key:4", "key:5", "key:6", "key:1", "key:2", "key:3",
                "key:.", "key:0", "key:clear"), ids);
        assertEquals(new RegionResolver.Region("key:7", new Rectangle(0, 0, 48, 40)),
                regions.regionAt(pad, new Point(10, 10)));
        assertEquals(new RegionResolver.Region("key:clear", new Rectangle(96, 120, 48, 40)),
                regions.regionAt(pad, new Point(100, 150)));
        assertNull(regions.regionAt(pad, new Point(500, 10)));
        assertEquals(new Rectangle(48, 120, 48, 40), regions.rectOf(pad, "key:0"));
        assertNull(regions.rectOf(pad, "key:42"));
        assertNull(regions.rectOf(new JButton(), "key:0"));
        assertTrue(regions.regions(new JButton()).isEmpty());
    }
}
