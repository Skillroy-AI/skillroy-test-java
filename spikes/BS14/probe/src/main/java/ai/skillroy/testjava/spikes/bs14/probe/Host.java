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

package ai.skillroy.testjava.spikes.bs14.probe;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** Calls into the toy host's own thread through its two queues (reflection: the host is the system loader's). */
final class Host {

    private final BlockingQueue<String> codes;
    private final BlockingQueue<Object> results;

    @SuppressWarnings("unchecked")
    Host() throws Exception {
        Class<?> host = Class.forName("ai.skillroy.testjava.spikes.bs14.toy.ToyHost", false,
                ClassLoader.getSystemClassLoader());
        codes = (BlockingQueue<String>) host.getField("CODES").get(null);
        results = (BlockingQueue<Object>) host.getField("RESULTS").get(null);
    }

    Object call(String code) throws InterruptedException {
        codes.put(code);
        Object r = results.poll(20, TimeUnit.SECONDS);
        return r == null ? "<timeout>" : r;
    }
}
