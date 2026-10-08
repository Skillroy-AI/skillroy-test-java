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

package ai.skillroy.testjava.agent.web.jcef;

import ai.skillroy.testjava.agent.web.reflect.ReflectiveException;
import ai.skillroy.testjava.agent.web.reflect.ResolvedApi;

import java.awt.Component;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The component route of the {@code jcef} enumeration (phase4-contracts.md section 4.5: a seed that is "a component
 * equal to some browser's {@code getUIComponent()} or a descendant of it"). JCEF has no component-to-browser
 * accessor, so a component can only name a browser the adapter already knows; a seeds-only enumeration uses it to keep
 * such a browser listed while its component is in the Swing tree. A class of its own because it names AWT types: the
 * adapter is constructed at install, where no AWT class may be loaded, and this class is loaded only when the route
 * runs.
 */
final class JcefComponents {

    /** How far up a component's parents the route looks. */
    private static final int MAX_DEPTH = 256;

    private JcefComponents() {
    }

    /**
     * The browsers among {@code known} whose {@code getUIComponent()} is one of the component seeds or contains one,
     * in seed order, each once. Parents are read off the EDT: a stale answer only keeps a browser one enumeration
     * longer.
     */
    static List<Object> byComponent(ResolvedApi r, List<Object> known, List<Object> seeds) {
        Map<Object, Object> byUi = new IdentityHashMap<Object, Object>();
        for (Object b : known) {
            try {
                Object ui = r.invoke("browser.getUIComponent", b);
                if (ui != null) {
                    byUi.put(ui, b);
                }
            } catch (ReflectiveException e) {
                // no component known for this browser
            }
        }
        if (byUi.isEmpty()) {
            return Collections.emptyList();
        }
        List<Object> out = new ArrayList<Object>();
        Map<Object, Boolean> taken = new IdentityHashMap<Object, Boolean>();
        for (Object seed : seeds) {
            if (!(seed instanceof Component)) {
                continue;
            }
            int depth = 0;
            for (Component p = (Component) seed; p != null && depth < MAX_DEPTH; p = p.getParent(), depth++) {
                Object b = byUi.get(p);
                if (b != null) {
                    if (taken.put(b, Boolean.TRUE) == null) {
                        out.add(b);
                    }
                    break;
                }
            }
        }
        return out;
    }
}
