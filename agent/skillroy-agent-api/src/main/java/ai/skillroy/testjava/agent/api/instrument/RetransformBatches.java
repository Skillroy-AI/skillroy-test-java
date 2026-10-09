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

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits the classes of one install or remove into retransformation batches (phase6-contracts.md sections 2.4 and
 * 3.2, D124, D153): every {@code Instrumentation.retransformClasses} call is one stop-the-world safepoint, so a batch
 * holds at most {@code maxClasses} classes whose class files sum to at most {@code maxBytes}. A single class larger
 * than {@code maxBytes} gets a batch of its own (the size limit that refuses such a class is the caller's check).
 * The order of the input is kept.
 *
 * <p>Stateless; safe from any thread.
 */
public final class RetransformBatches {

    /** The size of a class's class file in bytes, or a negative number when unknown. */
    public interface Sizes {
        /** May be called more than once per class; must not throw. */
        long bytes(Class<?> type);
    }

    /**
     * The size of the class file its defining loader serves as the resource {@code <name>.class} (the bootstrap's
     * through the system loader), or -1 when it cannot be read. Canonically but not byte-wise the size the JVM hands a
     * transformer (BS3 RESULT section 9 fact 11), which is close enough for a bound.
     */
    public static final Sizes CLASS_FILE_RESOURCE = new Sizes() {
        @Override
        public long bytes(Class<?> type) {
            try {
                String name = type.getName().replace('.', '/') + ".class";
                ClassLoader loader = type.getClassLoader();
                URL url = loader == null ? ClassLoader.getSystemResource(name) : loader.getResource(name);
                if (url == null) {
                    return -1;
                }
                URLConnection connection = url.openConnection();
                connection.setUseCaches(false);
                long length = connection.getContentLengthLong();
                try {
                    connection.getInputStream().close();
                } catch (IOException ignored) {
                    // only the length was wanted
                }
                return length;
            } catch (Throwable t) {
                return -1;
            }
        }
    };

    private RetransformBatches() {
    }

    /** {@link #of(List, Sizes, long, int)} with the sizes of {@link #CLASS_FILE_RESOURCE}. */
    public static List<List<Class<?>>> of(List<Class<?>> classes, long maxBytes, int maxClasses) {
        return of(classes, CLASS_FILE_RESOURCE, maxBytes, maxClasses);
    }

    /**
     * The batches, each an unmodifiable list, in input order. A class whose size is unknown counts as
     * {@code maxBytes} (a batch of its own unless the batch is empty).
     *
     * @throws IllegalArgumentException if {@code maxBytes} or {@code maxClasses} is not positive
     */
    public static List<List<Class<?>>> of(List<Class<?>> classes, Sizes sizes, long maxBytes, int maxClasses) {
        if (maxBytes <= 0 || maxClasses <= 0) {
            throw new IllegalArgumentException("maxBytes and maxClasses must be positive: " + maxBytes + ", "
                    + maxClasses);
        }
        List<List<Class<?>>> batches = new ArrayList<List<Class<?>>>();
        List<Class<?>> batch = new ArrayList<Class<?>>();
        long sum = 0;
        for (Class<?> type : classes) {
            long size = sizes.bytes(type);
            if (size < 0) {
                size = maxBytes;
            }
            if (!batch.isEmpty() && (batch.size() >= maxClasses || sum + size > maxBytes)) {
                batches.add(Collections.unmodifiableList(batch));
                batch = new ArrayList<Class<?>>();
                sum = 0;
            }
            batch.add(type);
            sum += size;
        }
        if (!batch.isEmpty()) {
            batches.add(Collections.unmodifiableList(batch));
        }
        return Collections.unmodifiableList(batches);
    }
}
