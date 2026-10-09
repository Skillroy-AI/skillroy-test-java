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

package ai.skillroy.testjava.spikes.bs3.engine;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.LinkedHashMap;
import java.util.Map;

import net.bytebuddy.agent.builder.AgentBuilder;

/**
 * BS3 item 6, second half: what a fresh engine (a new isolated loader over the same jar, as after a detach and a new
 * attach) finds and does. Loaded in its own loader, so every engine class here is a second copy; only {@code Spy} is
 * shared, through the bootstrap loader.
 */
public final class SecondInstall {

    private SecondInstall() {
    }

    public static Map<String, String> run(Instrumentation inst, Map<String, String> opts) {
        Map<String, String> res = new LinkedHashMap<String, String>();
        try {
            res.put("engineLoader", String.valueOf(SecondInstall.class.getClassLoader()));
            res.put("freshByteBuddy", String.valueOf(System.identityHashCode(AgentBuilder.class)
                    != Integer.parseInt(opts.get("firstAgentBuilder"))));
            res.put("spyOnBootPathAlready", String.valueOf(SpyBoot.bootSpy() != null));
            SpyBoot.Result sr = SpyBoot.ensure(inst, new File(opts.get("spyJar")), true);
            res.put("appendAgain", sr.appendedAgain + (sr.appendError == null ? "" : " error " + sr.appendError));
            res.put("appendAgainMs", Report.ms(sr.appendNanos));
            res.put("sameSpyClass", String.valueOf(System.identityHashCode(SpyAccess.spyClass())
                    == Integer.parseInt(opts.get("spyIdentity"))));
            res.put("spyVersion", SpyAccess.version());
            res.put("handlerAtStart", String.valueOf(SpyAccess.handler()));

            res.put("nexus", HookEngine.disableNexus(!"default".equals(opts.get("nexus"))));
            EventRing ring = new EventRing(256);
            HookRegistry reg = new HookRegistry("handler-second-engine", ring);
            SpyAccess.handler(reg);
            HostView host = new HostView();
            Class<?> ac = host.accessControlClass();
            HookEngine engine = new HookEngine(inst, reg, null);
            HookEngine.Installed in = engine.install(new HookEngine.Spec("limit.check", ac, "check", Probe.CHECK_DESC,
                    HookMode.OBSERVE));
            res.put("install", in.status + " " + in.errors);
            res.put("installMs", Report.ms(in.totalNanos));
            long seq = ring.last();
            String d = HostView.decision(host.check("100", "RETURN", "45.00"));
            int events = ring.since(seq).size();
            res.put("fired", d);
            res.put("events", String.valueOf(events));
            res.put("eventsArrived", String.valueOf(events == 2));
            HookEngine.Removal rm = engine.remove("limit.check", false);
            res.put("removeMs", Report.ms(rm.removeNanos));
            res.put("bytesIdentical", String.valueOf(rm.bytesIdentical));
            res.put("diff", String.valueOf(rm.diff));
            SpyAccess.handler(null);
        } catch (Throwable t) {
            res.put("error", t.toString());
            SpyAccess.handler(null);
        }
        return res;
    }
}
