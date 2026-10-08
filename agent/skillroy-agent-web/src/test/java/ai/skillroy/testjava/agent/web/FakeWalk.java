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

package ai.skillroy.testjava.agent.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** A fake Swing walk: the components a test lists, showing as the test says; counts the reads. */
final class FakeWalk implements SeedWalk {

    final List<Object> components = new CopyOnWriteArrayList<Object>();
    final Set<Object> showing = Collections.synchronizedSet(Collections.newSetFromMap(
            new IdentityHashMap<Object, Boolean>()));
    final AtomicInteger componentReads = new AtomicInteger();
    final AtomicInteger showingReads = new AtomicInteger();
    volatile RuntimeException failure;

    @Override
    public List<Object> components(long budgetMs) {
        componentReads.incrementAndGet();
        if (failure != null) {
            throw failure;
        }
        return new ArrayList<Object>(components);
    }

    @Override
    public Map<Object, Boolean> showing(Collection<?> objects, long budgetMs) {
        showingReads.incrementAndGet();
        Map<Object, Boolean> out = new IdentityHashMap<Object, Boolean>();
        for (Object o : objects) {
            out.put(o, Boolean.valueOf(showing.contains(o)));
        }
        return out;
    }
}
