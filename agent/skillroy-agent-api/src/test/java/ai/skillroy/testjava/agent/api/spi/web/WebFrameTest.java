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

package ai.skillroy.testjava.agent.api.spi.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link WebFrame}: a snapshot value, equal when every field is equal (Phase 4 contract §3, §12.1 item 1). */
class WebFrameTest {

    @Test
    void theAccessorsReturnTheFields() {
        WebFrame main = new WebFrame("F-1", null, "", "data:text/html,desk", true);
        assertEquals("F-1", main.key());
        assertNull(main.parentKey());
        assertEquals("", main.name());
        assertEquals("data:text/html,desk", main.url());
        assertTrue(main.main());

        WebFrame child = new WebFrame("F-2", "F-1", "loans", "data:text/html,loans", false);
        assertEquals("F-2", child.key());
        assertEquals("F-1", child.parentKey());
        assertEquals("loans", child.name());
        assertEquals("data:text/html,loans", child.url());
        assertFalse(child.main());
    }

    @Test
    void aNullNameOrUrlBecomesEmpty() {
        WebFrame frame = new WebFrame("F-2", "F-1", null, null, false);
        assertEquals("", frame.name());
        assertEquals("", frame.url());
        assertEquals(new WebFrame("F-2", "F-1", "", "", false), frame);
    }

    @Test
    void aSubFrameMayHaveAnUnknownParent() {
        assertNull(new WebFrame("F-2", null, "", "", false).parentKey());
    }

    @Test
    void framesAreEqualWhenEveryFieldIsEqual() {
        WebFrame frame = new WebFrame("F-2", "F-1", "loans", "data:,a", false);
        WebFrame same = new WebFrame("F-2", "F-1", "loans", "data:,a", false);
        assertEquals(frame, same);
        assertEquals(frame.hashCode(), same.hashCode());
        assertNotEquals(frame, new WebFrame("F-3", "F-1", "loans", "data:,a", false), "key");
        assertNotEquals(frame, new WebFrame("F-2", "F-9", "loans", "data:,a", false), "parentKey");
        assertNotEquals(frame, new WebFrame("F-2", null, "loans", "data:,a", false), "parentKey null");
        assertNotEquals(frame, new WebFrame("F-2", "F-1", "items", "data:,a", false), "name");
        assertNotEquals(frame, new WebFrame("F-2", "F-1", "loans", "data:,b", false), "url");
        WebFrame main = new WebFrame("F-1", null, "", "data:,a", true);
        assertNotEquals(main, new WebFrame("F-1", null, "", "data:,a", false), "main");
        assertFalse(frame.equals(null));
        assertFalse(frame.equals("F-2"));
    }

    @Test
    void theKeyIsRequired() {
        NullPointerException missing = assertThrows(NullPointerException.class,
                () -> new WebFrame(null, null, "", "", true));
        assertEquals("key must not be null", missing.getMessage());
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> new WebFrame("", null, "", "", true));
        assertEquals("key must not be empty", empty.getMessage());
    }

    @Test
    void theMainFrameHasNoParent() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new WebFrame("F-1", "F-0", "", "", true));
        assertEquals("the main frame has no parent: parentKey must be null, was F-0", e.getMessage());
    }

    @Test
    void toStringNamesEveryField() {
        assertEquals("WebFrame{key=F-2, parentKey=F-1, name=loans, url=data:,a, main=false}",
                new WebFrame("F-2", "F-1", "loans", "data:,a", false).toString());
        assertEquals("WebFrame{key=F-1, parentKey=null, name=, url=, main=true}",
                new WebFrame("F-1", null, null, null, true).toString());
    }
}
