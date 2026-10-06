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


package ai.skillroy.testjava.agent.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bounds of the agent log (security finding S2): no caller can retain a large string through it. */
class RingLogTest {

    private static String repeat(char c, int n) {
        return ControlServerTest.repeat(c, n);
    }

    @Test
    void aMegabyteMessageYieldsALineOfAtMostMaxLine() {
        RingLog log = new RingLog();
        log.warn(repeat('m', 1024 * 1024));
        String line = log.tail(1).get(0);
        assertTrue(line.length() <= RingLog.MAX_LINE, "length " + line.length());
        assertTrue(line.contains("...[+" + (1024 * 1024 - RingLog.MAX_MESSAGE) + " chars]"), line.substring(
                line.length() - 40));
    }

    @Test
    void exceptionMessagesAndCausesAreCutAndTheWholeLineIsBounded() {
        RingLog log = new RingLog();
        RuntimeException cause = new RuntimeException(repeat('c', 100000));
        IllegalStateException t = new IllegalStateException(repeat('e', 100000), cause);
        log.error(repeat('m', 100000), t);
        String line = log.tail(1).get(0);
        assertTrue(line.length() <= RingLog.MAX_LINE, "length " + line.length());
        assertTrue(line.contains("IllegalStateException"), line);
        assertFalse(line.contains(repeat('e', RingLog.MAX_EXCEPTION_MESSAGE + 1)), "exception message cut");
        StringBuilder sb = new StringBuilder();
        RingLog.appendClean(sb, repeat('x', 5000), 300);
        assertEquals(300 + "...[+4700 chars]".length(), sb.length());
    }

    @Test
    void aLongThreadNameIsCut() throws Exception {
        final RingLog log = new RingLog();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                log.info("from a thread with a long name");
            }
        }, repeat('t', 10000));
        t.start();
        t.join(10000);
        String line = log.tail(1).get(0);
        assertTrue(line.length() < 400, line);
    }

    @Test
    void controlCharactersCannotForgeLines() {
        RingLog log = new RingLog();
        log.warn("first\nINFO [forged] second\r\u0000\u001b[31m\u0085end", new RuntimeException("a\nb"));
        String line = log.tail(1).get(0);
        assertFalse(line.contains("\r") || line.contains("\u0000") || line.contains("\u001b")
                || line.contains("\u0085"), line);
        String[] parts = line.split("\n");
        assertTrue(parts[0].contains("first INFO [forged] second"), parts[0]);
        assertTrue(parts[0].contains("a b"), parts[0]);
        for (int i = 1; i < parts.length; i++) {
            assertTrue(parts[i].startsWith("    at ") || parts[i].startsWith("  caused by "), parts[i]);
        }
    }

    @Test
    void theRingIsBoundedInEntriesAndCharacters() {
        RingLog log = new RingLog();
        for (int i = 0; i < RingLog.CAPACITY + 50; i++) {
            log.info(i + " " + repeat('z', 5000));
        }
        List<String> lines = log.tail(Integer.MAX_VALUE);
        assertEquals(RingLog.CAPACITY, lines.size());
        long chars = 0;
        for (String line : lines) {
            chars += line.length();
        }
        assertTrue(chars <= (long) RingLog.CAPACITY * RingLog.MAX_LINE, "characters held: " + chars);
        assertTrue(lines.get(0).contains("] 50 "), lines.get(0).substring(0, 60));
    }
}
