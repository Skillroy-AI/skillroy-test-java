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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A second module instance in the same JVM (a fresh loader, as after agent.disable and a re-attach): it takes the
 * Retransformer the first instance parked, installs its own handler in the freed slot, submits, fires, unloads and
 * parks again; no append, no premain (phase6-contracts.md 7.4 step 2).
 */
public final class Second {

    private Second() {
    }

    public static Map<String, String> run() throws Exception {
        Map<String, String> facts = new LinkedHashMap<String, String>();
        ClassLoader sys = ClassLoader.getSystemClassLoader();
        Class<?> manager = Class.forName(Probe.MANAGER, true, sys);
        Method take = manager.getDeclaredMethod("take");
        take.setAccessible(true);
        Method park = manager.getDeclaredMethod("park", Object.class);
        park.setAccessible(true);
        Object retransformer = take.invoke(null);
        facts.put("check.reuse.tookParked", retransformer != null ? "PASS" : "FAIL");
        if (retransformer == null) {
            return facts;
        }
        Class<?> handlerClass = Class.forName(Probe.HANDLER, true, Second.class.getClassLoader());
        facts.put("reuse.handlerLoader", String.valueOf(handlerClass.getClassLoader()));
        Object handler = handlerClass.getMethod("arm").invoke(null);
        facts.put("check.reuse.slotFreeForSecondHandler", handler != null ? "PASS" : "FAIL");
        Method install = retransformer.getClass().getMethod("installScript", List.class, List.class,
                PrintWriter.class);
        Method remove = retransformer.getClass().getMethod("removeScripts", List.class, PrintWriter.class);
        String text = Probe.rule("skillroy:reuse.exit", "ceiling", "EXIT", "observe($1, $!)");
        StringWriter out = new StringWriter();
        install.invoke(retransformer, Collections.singletonList(text), Collections.singletonList("bs14-reuse"),
                new PrintWriter(out, true));
        facts.put("reuse.installOutput", out.toString().trim());
        Object result = new Host().call("SUP-3");
        facts.put("reuse.callResult", String.valueOf(result));
        List<String> events = Probe.drain(handlerClass);
        facts.put("reuse.events", String.valueOf(events));
        facts.put("check.reuse.firedThroughSecondHandler",
                String.valueOf(events).contains("skillroy:reuse.exit|" + Probe.TOY + "|ceiling|lending-toy|"
                        + "String:SUP-3,BigDecimal:500.00|n=2") ? "PASS" : "FAIL");
        StringWriter rem = new StringWriter();
        remove.invoke(retransformer, Collections.singletonList(text), new PrintWriter(rem, true));
        facts.put("reuse.removeOutput", rem.toString().trim());
        park.invoke(null, retransformer);
        facts.put("check.reuse.handlerReleased",
                Boolean.TRUE.equals(handlerClass.getMethod("disarm", Object.class).invoke(null, handler))
                        ? "PASS" : "FAIL");
        return facts;
    }
}
