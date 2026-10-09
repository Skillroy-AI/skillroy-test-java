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

import ai.skillroy.testjava.spikes.bs14.sys.RuleHelper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The module's handler behind RuleHelper's slot. It names RuleHelper's type, so it is loaded by name only after the
 * sys jar is on the system class path (BS3 RESULT fact 9: a class linked against a type before the append keeps
 * failing). Each event is kept as one line: rule|class|method|thread|binding types and scalar values.
 */
public final class FiredHandler implements RuleHelper.Handler {

    private static final ConcurrentLinkedQueue<String> EVENTS = new ConcurrentLinkedQueue<String>();

    private FiredHandler() {
    }

    /** Installs a new handler in RuleHelper's slot; returns it (as Object) when the slot took it, else null. */
    public static Object arm() {
        FiredHandler h = new FiredHandler();
        return RuleHelper.install(h) ? h : null;
    }

    public static boolean disarm(Object handler) {
        return handler instanceof FiredHandler && RuleHelper.uninstall((FiredHandler) handler);
    }

    public static boolean held() {
        return RuleHelper.held();
    }

    public static long[] counters() {
        return RuleHelper.counters();
    }

    public static List<String> drain() {
        List<String> out = new ArrayList<String>();
        String e;
        while ((e = EVENTS.poll()) != null) {
            out.add(e);
        }
        return out;
    }

    @Override
    public void fired(String rule, String className, String method, Object[] bindings) {
        StringBuilder sb = new StringBuilder();
        sb.append(rule).append('|').append(className).append('|').append(method).append('|')
                .append(Thread.currentThread().getName()).append('|');
        for (int i = 0; bindings != null && i < bindings.length; i++) {
            Object b = bindings[i];
            if (i > 0) {
                sb.append(',');
            }
            if (b == null) {
                sb.append("null");
            } else if (b instanceof String || b instanceof Number || b instanceof Boolean || b instanceof BigDecimal) {
                sb.append(b.getClass().getSimpleName()).append(':').append(b);
            } else {
                sb.append("handle:").append(b.getClass().getName());
            }
        }
        sb.append("|n=").append(bindings == null ? -1 : bindings.length);
        EVENTS.add(sb.toString());
    }
}
