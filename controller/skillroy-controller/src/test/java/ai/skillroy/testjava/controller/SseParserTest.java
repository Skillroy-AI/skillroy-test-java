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

package ai.skillroy.testjava.controller;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseParserTest {

    private static List<SseParser.Frame> feed(String... lines) {
        SseParser parser = new SseParser();
        List<SseParser.Frame> frames = new ArrayList<>();
        for (String line : lines) {
            SseParser.Frame frame = parser.line(line);
            if (frame != null) {
                frames.add(frame);
            }
        }
        return frames;
    }

    @Test
    void parsesIdEventAndData() {
        assertEquals(List.of(new SseParser.Frame("12", "state.changed", "{\"seq\":12}")),
                feed("id: 12", "event: state.changed", "data: {\"seq\":12}", ""));
    }

    @Test
    void joinsDataLinesAndKeepsOnlyOneLeadingSpaceOff() {
        assertEquals(List.of(new SseParser.Frame(null, null, "a\n  b\nc")), feed("data:a", "data:   b", "data: c", ""));
    }

    @Test
    void commentsAndHeartbeatsProduceNothing() {
        assertEquals(List.of(), feed(": hb", "", ": connected", ""));
        assertEquals(List.of(new SseParser.Frame("1", null, "x")), feed(": hb", "id: 1", ": hb", "data: x", ""));
    }

    @Test
    void blocksWithoutDataAreDroppedAndFieldsDoNotLeakIntoTheNextBlock() {
        assertEquals(List.of(new SseParser.Frame(null, null, "y")),
                feed("id: 5", "event: gap", "", "data: y", ""));
    }

    @Test
    void retryUnknownFieldsAndFieldsWithoutColonAreIgnored() {
        assertEquals(List.of(new SseParser.Frame(null, "e", "")),
                feed("retry: 1000", "foo: bar", "event: e", "data", ""));
    }

    @Test
    void anIdWithNulIsIgnored() {
        assertEquals(List.of(new SseParser.Frame(null, null, "z")), feed("id: 1\0", "data: z", ""));
    }

    @Test
    void anUnterminatedBlockIsNotDispatched() {
        SseParser parser = new SseParser();
        assertNull(parser.line("data: partial"));
    }

    /** Finding S25: the joined data of one event is capped; past the cap the parser stays overflowed. */
    @Test
    void theDataOfOneEventIsCapped() {
        SseParser parser = new SseParser(10);
        assertNull(parser.line("data: 12345"));
        assertNull(parser.line("data: 6789"), "5 + 1 + 4 = 10 characters fit");
        assertEquals(new SseParser.Frame(null, null, "12345\n6789"), parser.line(""));
        assertFalse(parser.overflowed());
        assertNull(parser.line("data: 12345"));
        assertNull(parser.line("data: 67890"));
        assertTrue(parser.overflowed(), "11 characters do not");
        assertNull(parser.line(""), "nothing is dispatched after an overflow");
        assertNull(parser.line("data: x"));
        assertNull(parser.line(""));
        assertTrue(parser.overflowed());
        assertThrows(IllegalArgumentException.class, () -> new SseParser(0));
    }
}
