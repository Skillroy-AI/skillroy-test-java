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
package ai.skillroy.testjava.agent.byteman;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Byteman module's recorders are bounded (Phase 6 security fix, pass 2, P2-L2-F5; phase6-contracts.md 7.4 step
 * 4): at most {@code hooks.maxClasses} records and no copy above {@code hooks.maxClassBytes}; a definition beyond
 * either bound is marked so that the byte check answers DEGRADED; a name the module stops watching loses its records
 * and is not recorded again. Before the fix one watched name defined in 20,000 loaders kept 20,000 class-file copies.
 */
class ByteRecorderTest {

    private static final String NAME = "org.example.Plugin";
    private static final String INTERNAL = "org/example/Plugin";

    private static List<ClassLoader> loaders(int n) {
        List<ClassLoader> out = new ArrayList<ClassLoader>();
        for (int i = 0; i < n; i++) {
            out.add(new URLClassLoader(new URL[0], null));
        }
        return out;
    }

    @Test
    void theRecordsAreCappedAndADefinitionBeyondTheCapIsMarked() {
        ByteRecorder recorder = new ByteRecorder("ahead", new AtomicReference<Thread>(), 64, 1024);
        recorder.watch(NAME);
        List<ClassLoader> many = loaders(100);
        for (ClassLoader l : many) {
            recorder.transform(l, INTERNAL, null, null, new byte[] {1, 2, 3});
        }
        assertEquals(64, recorder.size(), "the 65th loader's definition is not recorded");
        assertNotNull(recorder.entry(NAME, many.get(0)).first);
        assertNull(recorder.entry(NAME, many.get(99)));
        assertTrue(recorder.beyondBound(NAME, many.get(99)), "the byte check is told it was not recorded");
    }

    @Test
    void aClassFileAboveTheByteBoundIsCountedNotCopied() {
        ByteRecorder recorder = new ByteRecorder("ahead", new AtomicReference<Thread>(), 64, 1024);
        recorder.watch(NAME);
        ClassLoader l = loaders(1).get(0);
        recorder.transform(l, INTERNAL, null, null, new byte[1025]);
        ByteRecorder.Entry e = recorder.entry(NAME, l);
        assertEquals(1, e.events);
        assertNull(e.first, "no copy above hooks.maxClassBytes");
        assertTrue(e.oversize);
        assertTrue(recorder.beyondBound(NAME, l));
        recorder.transform(l, INTERNAL, null, null, new byte[] {1});
        assertNull(e.first, "an entry once beyond the bound keeps no copy: its reference would be wrong");
        assertEquals(2, e.events);
    }

    @Test
    void anUnwatchedNameLosesItsRecordsAndIsNotRecordedAgain() {
        ByteRecorder recorder = new ByteRecorder("behind", new AtomicReference<Thread>(), 64, 1024);
        recorder.watch(NAME);
        recorder.watch("org.example.Other");
        List<ClassLoader> two = loaders(2);
        for (ClassLoader l : two) {
            recorder.transform(l, INTERNAL, null, null, new byte[] {1});
            recorder.transform(l, "org/example/Other", null, null, new byte[] {2});
        }
        assertEquals(4, recorder.size());
        recorder.unwatch(NAME);
        assertFalse(recorder.watches(NAME));
        assertEquals(2, recorder.size(), "the other name keeps its records");
        recorder.transform(two.get(0), INTERNAL, null, null, new byte[] {1});
        assertNull(recorder.entry(NAME, two.get(0)), "an unloaded rule's name is recorded no more");
        assertFalse(recorder.beyondBound(NAME, two.get(0)));
    }
}
