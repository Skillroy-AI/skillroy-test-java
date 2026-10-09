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

package ai.skillroy.testjava.agent.byteman.sys;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuleManager}'s slot (phase6-contracts.md 7.3, 7.6): the Retransformer taken once, parked, both package-private;
 * the one public method is {@code initialize}, which keeps the first value of the JVM only; no public method returns
 * anything that could be the Retransformer.
 */
class RuleManagerTest {

    /** {@code accept} is what {@code initialize} does with its argument (the Retransformer needs an Instrumentation). */
    private static void initializeWith(Object value) throws Exception {
        Method accept = RuleManager.class.getDeclaredMethod("accept", Object.class);
        accept.setAccessible(true);
        accept.invoke(null, value);
    }

    @Test
    void theFirstValueIsTakenOnceParkedAndTakenAgainAndNoLaterInitializeReplacesIt() throws Exception {
        Object first = new Object();
        initializeWith(first);
        initializeWith(new Object());
        RuleManager.initialize(null, null, null);
        assertSame(first, RuleManager.take(), "the first initialize wins");
        assertNull(RuleManager.take(), "taken once");
        initializeWith(new Object());
        assertNull(RuleManager.take(), "a later initialize after the take is ignored as well");
        RuleManager.park(first);
        assertSame(first, RuleManager.take(), "parked for a later module instance");
        List<String> leftover = Collections.singletonList("skillroy:limit");
        RuleManager.park(leftover);
        assertSame(leftover, RuleManager.take(), "or the names of the rules left behind");
        assertNull(RuleManager.take());
    }

    @Test
    void noPublicAccessorExists() throws Exception {
        assertTrue(Modifier.isFinal(RuleManager.class.getModifiers()));
        List<String> publicMethods = new ArrayList<String>();
        for (Method m : RuleManager.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) {
                publicMethods.add(m.getName() + Arrays.toString(m.getParameterTypes()));
                assertEquals(void.class, m.getReturnType(), "no public method returns anything: " + m);
            }
        }
        assertEquals(Collections.singletonList("initialize[class org.jboss.byteman.agent.Retransformer,"
                + " class java.lang.String, class java.lang.Integer]"), publicMethods);
        Method take = RuleManager.class.getDeclaredMethod("take");
        Method park = RuleManager.class.getDeclaredMethod("park", Object.class);
        for (Method m : new Method[] {take, park}) {
            assertFalse(Modifier.isPublic(m.getModifiers()) || Modifier.isProtected(m.getModifiers())
                    || Modifier.isPrivate(m.getModifiers()), m + " is package-private");
        }
        assertEquals(0, RuleManager.class.getConstructors().length, "no public constructor");
    }
}
