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

package ai.skillroy.testjava.spikes.bs14.toy;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The toy host. Loads and uses {@link LendingToy} on its own thread ("lending-toy"), then announces itself ready
 * through the system property {@code bs14.host.ready} and serves calls the probe queues: a staff code in
 * {@link #CODES}, the ceiling (or the throwable) in {@link #RESULTS}; {@code "STOP"} ends it. It has no listener of its
 * own, so the process's listening sockets are whatever the agent or Byteman opens.
 */
public final class ToyHost {

    public static final BlockingQueue<String> CODES = new LinkedBlockingQueue<String>();
    public static final BlockingQueue<Object> RESULTS = new LinkedBlockingQueue<Object>();

    private ToyHost() {
    }

    public static void main(String[] args) throws Exception {
        int waitSeconds = 120;
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--wait-seconds".equals(args[i])) {
                waitSeconds = Integer.parseInt(args[i + 1]);
            }
        }
        Thread.currentThread().setName("lending-toy");
        LendingToy toy = new LendingToy();
        System.out.println("toy: first ceiling " + toy.ceiling("STAFF-1") + ", deposit " + toy.deposit("DRL-1"));
        System.setProperty("bs14.host.ready", "true");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
        while (System.nanoTime() < deadline) {
            String code = CODES.poll(100, TimeUnit.MILLISECONDS);
            if (code == null) {
                continue;
            }
            if ("STOP".equals(code)) {
                System.out.println("toy: stop");
                return;
            }
            Object result;
            try {
                result = code.startsWith("DEP:") ? toy.deposit(code.substring(4)) : toy.ceiling(code);
            } catch (Throwable t) {
                result = t;
            }
            RESULTS.put(result == null ? "null" : result);
        }
        System.out.println("toy: wait expired");
    }
}
