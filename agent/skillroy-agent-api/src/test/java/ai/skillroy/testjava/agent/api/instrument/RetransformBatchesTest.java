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

package ai.skillroy.testjava.agent.api.instrument;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RetransformBatches}: at most N classes and M bytes per batch, order kept, oversize and unknown alone. */
class RetransformBatchesTest {

    private static final List<Class<?>> NINE = Arrays.<Class<?>>asList(String.class, Integer.class, Long.class,
            Short.class, Byte.class, Double.class, Float.class, Character.class, Boolean.class);

    private static RetransformBatches.Sizes sizes(final Map<Class<?>, Long> sizes) {
        return new RetransformBatches.Sizes() {
            @Override
            public long bytes(Class<?> type) {
                Long size = sizes.get(type);
                return size == null ? 1 : size.longValue();
            }
        };
    }

    private static Map<Class<?>, Long> sized(Object... classAndSize) {
        Map<Class<?>, Long> out = new HashMap<Class<?>, Long>();
        for (int i = 0; i < classAndSize.length; i += 2) {
            out.put((Class<?>) classAndSize[i], Long.valueOf(((Number) classAndSize[i + 1]).longValue()));
        }
        return out;
    }

    @Test
    void neverMoreThanEightClassesInABatch() {
        List<List<Class<?>>> batches = RetransformBatches.of(NINE, sizes(sized()), 262144, 8);
        assertEquals(2, batches.size());
        assertEquals(NINE.subList(0, 8), batches.get(0));
        assertEquals(NINE.subList(8, 9), batches.get(1));
    }

    @Test
    void theBytesOfABatchStayWithinTheLimit() {
        List<Class<?>> three = NINE.subList(0, 3);
        List<List<Class<?>>> batches = RetransformBatches.of(three,
                sizes(sized(String.class, 100, Integer.class, 100, Long.class, 100)), 250, 8);
        assertEquals(Arrays.asList(NINE.subList(0, 2), NINE.subList(2, 3)), batches);
    }

    @Test
    void anOversizeOrUnknownClassGetsABatchOfItsOwn() {
        List<Class<?>> three = NINE.subList(0, 3);
        assertEquals(3, RetransformBatches.of(three,
                sizes(sized(String.class, 10, Integer.class, 1000, Long.class, 10)), 100, 8).size());
        assertEquals(3, RetransformBatches.of(three,
                sizes(sized(String.class, 10, Integer.class, -1, Long.class, 10)), 100, 8).size());
        assertEquals(Collections.singletonList(NINE.subList(1, 2)), RetransformBatches.of(NINE.subList(1, 2),
                sizes(sized(Integer.class, 5000)), 100, 8), "one class alone, whatever its size");
    }

    @Test
    void emptyInputAndBadBounds() {
        assertTrue(RetransformBatches.of(new ArrayList<Class<?>>(), 100, 8).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RetransformBatches.of(NINE, 0, 8));
        assertThrows(IllegalArgumentException.class, () -> RetransformBatches.of(NINE, 100, 0));
    }

    @Test
    void theResourceSizeIsTheClassFilesLength() throws IOException {
        assertEquals(ClassFileRefsTest.bytesOf(RetransformBatchesTest.class).length,
                RetransformBatches.CLASS_FILE_RESOURCE.bytes(RetransformBatchesTest.class));
        assertTrue(RetransformBatches.CLASS_FILE_RESOURCE.bytes(String.class) > 1000,
                "a bootstrap class through the system loader's resources");
        Runnable lambda = () -> { };
        assertEquals(-1, RetransformBatches.CLASS_FILE_RESOURCE.bytes(lambda.getClass()),
                "a generated class has no class file resource");
    }
}
