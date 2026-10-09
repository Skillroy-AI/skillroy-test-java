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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuleHelper} (phase6-contracts.md 7.3, 7.6): the slot is set-once, weak and released by identity; a firing
 * without a handler is counted, a throwing handler is counted and swallowed; the rule's name, trigger class and method
 * are read by reflection from the object Byteman hands over; counters and flags are per rule; {@code format} never
 * runs an application object's {@code toString()}.
 */
class RuleHelperTest {

    private final List<Object[]> seen = new ArrayList<Object[]>();
    private RuleHelper.Handler installed;

    /** What Byteman's {@code Rule} offers the helper, by the same method names. */
    public static final class FakeRule {
        private final String name;

        FakeRule(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public String getTriggerClass() {
            return "ai.skillroy.testjava.fixture.byteman.LendingToyMain";
        }

        public String getTriggerMethod() {
            return "ceiling";
        }
    }

    /** An application object whose toString must never run. */
    static final class Loud {
        @Override
        public String toString() {
            throw new AssertionError("toString of an application object ran");
        }
    }

    /** An application subclass of Number: not a JDK number. */
    static final class AppNumber extends Number {
        @Override
        public int intValue() {
            return 1;
        }

        @Override
        public long longValue() {
            return 1;
        }

        @Override
        public float floatValue() {
            return 1;
        }

        @Override
        public double doubleValue() {
            return 1;
        }

        @Override
        public String toString() {
            throw new AssertionError("toString of an application number ran");
        }
    }

    static RuleHelper helper(String ruleName) throws Exception {
        RuleHelper helper = new RuleHelper(null);
        Field rule = RuleHelper.class.getDeclaredField("rule");
        rule.setAccessible(true);
        rule.set(helper, new FakeRule(ruleName));
        return helper;
    }

    @AfterEach
    void release() {
        if (installed != null) {
            RuleHelper.uninstall(installed);
        }
    }

    private RuleHelper.Handler recording() {
        return new RuleHelper.Handler() {
            @Override
            public void fired(String rule, String className, String method, Object[] bindings) {
                seen.add(new Object[] {rule, className, method, bindings});
            }
        };
    }

    @Test
    void theSlotIsSetOnceAndReleasedByIdentityOnly() {
        RuleHelper.Handler a = recording();
        RuleHelper.Handler b = recording();
        assertTrue(RuleHelper.install(a));
        installed = a;
        assertFalse(RuleHelper.install(b), "set-once while a live handler holds it");
        assertFalse(RuleHelper.uninstall(b), "only the installed handler releases it");
        assertTrue(RuleHelper.held());
        assertTrue(RuleHelper.uninstall(a));
        assertFalse(RuleHelper.held());
        assertTrue(RuleHelper.install(b));
        installed = b;
    }

    @Test
    void theWeakSlotFreesItselfWhenTheHandlerIsCollected() throws Exception {
        RuleHelper.Handler a = recording();
        assertTrue(RuleHelper.install(a));
        WeakReference<RuleHelper.Handler> ref = new WeakReference<RuleHelper.Handler>(a);
        a = null;
        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertFalse(RuleHelper.held(), "a handler nothing holds strongly frees the slot");
        RuleHelper.Handler b = recording();
        assertTrue(RuleHelper.install(b));
        installed = b;
    }

    @Test
    void firedHandsTheRawBindingsWithTheRulesNameClassAndMethod() throws Exception {
        RuleHelper.Handler h = recording();
        assertTrue(RuleHelper.install(h));
        installed = h;
        Object self = new Loud();
        BigDecimal limit = new BigDecimal("150.00");
        helper("skillroy:limit").fired(self, limit);
        helper("skillroy:eight").fired(1, 2, 3, 4, 5, 6, 7, 8);
        assertEquals(2, seen.size());
        assertEquals("skillroy:limit", seen.get(0)[0]);
        assertEquals("ai.skillroy.testjava.fixture.byteman.LendingToyMain", seen.get(0)[1]);
        assertEquals("ceiling", seen.get(0)[2]);
        assertArrayEquals(new Object[] {self, limit}, (Object[]) seen.get(0)[3]);
        assertEquals(8, ((Object[]) seen.get(1)[3]).length);
    }

    @Test
    void noHandlerAndAThrowingHandlerAreCountedNeverThrown() throws Exception {
        long[] before = RuleHelper.counters();
        helper("skillroy:none").fired("x");
        long[] afterNone = RuleHelper.counters();
        assertEquals(before[1] + 1, afterNone[1], "unhandled");
        RuleHelper.Handler throwing = new RuleHelper.Handler() {
            @Override
            public void fired(String rule, String className, String method, Object[] bindings) {
                throw new StackOverflowError("from the handler");
            }
        };
        assertTrue(RuleHelper.install(throwing));
        installed = throwing;
        helper("skillroy:throws").fired("x");
        assertEquals(afterNone[2] + 1, RuleHelper.counters()[2], "failures");
    }

    @Test
    void countersAndFlagsArePerRule() throws Exception {
        RuleHelper a = helper("skillroy:a");
        RuleHelper b = helper("skillroy:b");
        assertEquals(1, a.count("calls"));
        assertEquals(2, a.count("calls"));
        assertEquals(0, b.countOf("calls"));
        assertEquals(2, helper("skillroy:a").countOf("calls"), "another firing of the same rule sees it");
        assertTrue(a.flag("seen"));
        assertFalse(a.flag("seen"));
        assertTrue(a.flagged("seen"));
        assertFalse(b.flagged("seen"));
        assertTrue(a.clear("seen"));
        assertFalse(a.flagged("seen"));
        RuleHelper.forget("skillroy:a");
        assertEquals(0, a.countOf("calls"));
    }

    @Test
    void formatIsABoundedTextOfAScalarOnly() throws Exception {
        RuleHelper h = helper("skillroy:f");
        assertEquals("null", h.format(null));
        assertEquals("150.00", h.format(new BigDecimal("150.00")));
        assertEquals("true", h.format(Boolean.TRUE));
        assertEquals("x", h.format('x'));
        assertEquals("SECONDS", h.format(java.util.concurrent.TimeUnit.SECONDS));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longText.append('a');
        }
        assertEquals(RuleHelper.MAX_FORMAT, h.format(longText.toString()).length());
        assertEquals("<" + Loud.class.getName() + ">", h.format(new Loud()));
        assertEquals("<" + AppNumber.class.getName() + ">", h.format(new AppNumber()));
    }
}
