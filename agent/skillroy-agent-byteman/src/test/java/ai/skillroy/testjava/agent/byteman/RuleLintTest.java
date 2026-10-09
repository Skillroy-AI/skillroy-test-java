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

package ai.skillroy.testjava.agent.byteman;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lint's corpus (phase6-contracts.md 7.3, 7.6): for every deny-list entry one refused rule, with the line reported,
 * and one accepted rule whose name or target method contains the entry as a substring; the sample desk's class with
 * the module's HELPER line accepted; an agent class refused; the structural rules.
 */
class RuleLintTest {

    static final String TOY = "ai.skillroy.testjava.fixture.byteman.DepositDesk";

    /** A rule on the toy whose DO line (line 6) is {@code action}. */
    static String rule(String name, String method, String action) {
        return "RULE " + name + "\n"
                + "CLASS " + TOY + "\n"
                + "METHOD " + method + "\n"
                + "AT ENTRY\n"
                + "IF true\n"
                + "DO " + action + "\n"
                + "ENDRULE\n";
    }

    /** Per deny entry, an action that uses it (and nothing earlier in the list). */
    static Map<String, String> refusals() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("killJVM", "killJVM()");
        m.put("killThread", "killThread()");
        m.put("delay", "delay(1000)");
        m.put("waitFor", "waitFor($0)");
        m.put("rendezvous", "rendezvous(\"r\")");
        m.put("joinWait", "joinWait($0, 1)");
        m.put("signalWake", "signalWake($0)");
        m.put("signalThrow", "signalThrow($0)");
        m.put("traceOpen", "traceOpen(\"k\", \"/tmp/x\")");
        m.put("traceClose", "traceClose(\"k\")");
        m.put("trace", "trace(\"x\")");
        m.put("traceln", "traceln(\"x\")");
        m.put("traceStack", "traceStack()");
        m.put("openTrace", "openTrace(\"k\", \"/tmp/x\")");
        m.put("ProcessBuilder", "new ProcessBuilder(\"sh\")");
        m.put("getRuntime", "fired(getRuntime())");
        m.put("Unsafe", "fired(Unsafe)");
        m.put("MethodHandle", "fired(MethodHandle)");
        m.put("MethodHandles", "fired(MethodHandles)");
        m.put("setAccessible", "$0.field.setAccessible(true)");
        m.put("defineClass", "$0.loader.defineClass(\"x\")");
        m.put("getMethod", "$0.getClass().getMethod(\"x\")");
        m.put("getDeclaredMethod", "$0.getClass().getDeclaredMethod(\"x\")");
        m.put("getField", "$0.getClass().getField(\"x\")");
        m.put("getDeclaredField", "$0.getClass().getDeclaredField(\"x\")");
        m.put("getConstructor", "$0.getClass().getConstructor()");
        m.put("getDeclaredConstructor", "$0.getClass().getDeclaredConstructor()");
        m.put("invoke", "$1.invoke($0)");
        m.put("newInstance", "$1.newInstance()");
        m.put("System.", "System.exit(0)");
        m.put("Runtime.", "Runtime.version()");
        m.put("Thread.", "Thread.sleep(10)");
        m.put("Class.", "Class.forName(\"x\")");
        m.put("ClassLoader", "fired(ClassLoader.getSystemClassLoader())");
        m.put("java.lang.reflect.", "fired(java.lang.reflect.Array.getLength($1))");
        m.put("java.io.", "fired(new java.io.File(\"x\"))");
        m.put("java.nio.", "fired(java.nio.file.Paths.get(\"x\"))");
        m.put("java.net.", "fired(new java.net.Socket(\"h\", 1))");
        m.put("java.sql.", "fired(java.sql.DriverManager.getDrivers())");
        m.put("javax.", "fired(javax.swing.UIManager.getLookAndFeel())");
        m.put("sun.", "fired(sun.misc.Signal.raise($1))");
        m.put("jdk.", "fired(jdk.internal.misc.VM.isBooted())");
        m.put("org.jboss.byteman.", "fired(org.jboss.byteman.rule.Rule.disableTriggers())");
        m.put("ai.skillroy.testjava.agent.", "fired(ai.skillroy.testjava.agent.byteman.sys.RuleManager.LOCK)");
        m.put("ai.skillroy.testjava.protocol.", "fired(ai.skillroy.testjava.protocol.Topics.RULE_FIRED)");
        return m;
    }

    @Test
    void theCorpusCoversEveryDenyEntry() {
        assertEquals(RuleLint.DENY.keySet(), refusals().keySet(), "one refused rule per deny entry");
    }

    @Test
    void everyDenyEntryIsRefusedWithItsLine() {
        for (Map.Entry<String, String> e : refusals().entrySet()) {
            RuleLint.Result r = RuleLint.check(rule("audit", "ceiling", e.getValue()));
            assertFalse(r.ok(), e.getKey() + " must be refused: " + e.getValue());
            assertTrue(r.errors().contains("line 6: '" + e.getKey() + "' is not allowed (" + RuleLint.DENY.get(e.getKey())
                    + ")"), e.getKey() + ": " + r.errors());
            assertNull(r.submitText());
        }
    }

    @Test
    void aWordInsideANameOrAMethodIsNotTheWord() {
        for (String entry : RuleLint.DENY.keySet()) {
            String bare = entry.endsWith(".") ? entry.substring(0, entry.length() - 1) : entry;
            String inName = "audit " + entry + " calls";
            RuleLint.Result byName = RuleLint.check(rule(inName, "ceiling", "fired($0, $1)"));
            assertTrue(byName.ok(), inName + ": " + byName.errors());
            if (!entry.contains(".") || entry.endsWith(".") && bare.indexOf('.') < 0) {
                String method = "check" + bare.substring(0, 1).toUpperCase() + bare.substring(1) + "Days";
                RuleLint.Result byMethod = RuleLint.check(rule("audit", method, "fired($0)"));
                assertTrue(byMethod.ok(), method + ": " + byMethod.errors());
            }
        }
        assertTrue(RuleLint.check(rule("audit", "ceiling", "fired($0.traceId, $0.delayDays)")).ok());
        assertTrue(RuleLint.check(rule("audit", "ceiling", "fired($0.systemCode, $0.invoked)")).ok());
        assertTrue(RuleLint.check(rule("audit", "ceiling", "fired(\"killJVM System.exit\") # trace(x)")).ok(),
                "string literals and comments are not scanned");
        assertTrue(RuleLint.check(rule("audit", "ceiling", "RETURN null")).ok(), "RETURN is a fault injection");
        assertTrue(RuleLint.check(rule("audit", "ceiling", "THROW new IllegalStateException(\"x\")")).ok());
    }

    /**
     * Fix wave (L2-F8, D133): what the lint does not promise. Its list stops Byteman's built-ins and the entry points it
     * names; a {@code DO} clause can still block, write, create processes or reach reflection through other JDK names
     * or through calls on bound objects, and the lint accepts those: {@code confirm} on every submit is the gate
     * (phase6-contracts.md 7.3, the fix-wave note). Pinned, so that a wider promise is a change of the contract first.
     */
    @Test
    void theLintIsATripwireNotASandbox() {
        for (String action : Arrays.asList("$0.wait()", "java.util.concurrent.locks.LockSupport.park()",
                "new java.util.concurrent.CountDownLatch(1).await()", "java.beans.Beans.instantiate(null, \"x.Y\")")) {
            RuleLint.Result r = RuleLint.check(rule("limit", "ceiling", action));
            assertTrue(r.ok(), action + ": " + r.errors());
        }
    }

    @Test
    void whiteSpaceAroundADotDoesNotHideAnEntry() {
        RuleLint.Result r = RuleLint.check(rule("audit", "ceiling", "fired($1 . invoke ($0), System . exit(0))"));
        assertTrue(r.errors().contains("line 6: 'invoke' is not allowed (reaches reflection)"), r.errors().toString());
        assertTrue(r.errors().contains("line 6: 'System.' is not allowed (exit, properties, the console, the"
                + " environment)"), r.errors().toString());
    }

    @Test
    void theSampleDesksClassWithTheModulesHelperIsAcceptedAndRewritten() {
        String text = "# the assistant's return limit\n"
                + "RULE return limit read\n"
                + "CLASS ai.skillroy.testjava.samples.lending.AccessControl\n"
                + "METHOD returnLimit\n"
                + "HELPER ai.skillroy.testjava.agent.byteman.sys.RuleHelper\n"
                + "AT EXIT\n"
                + "IF true\n"
                + "DO fired($0, $!)\n"
                + "ENDRULE\n";
        RuleLint.Result r = RuleLint.check(text);
        assertTrue(r.ok(), r.errors().toString());
        assertEquals("return limit read", r.name());
        assertEquals("skillroy:return limit read", r.storedName());
        assertEquals("ai.skillroy.testjava.samples.lending.AccessControl", r.className());
        assertEquals("returnLimit", r.method());
        assertEquals("RULE skillroy:return limit read\n"
                + "CLASS ai.skillroy.testjava.samples.lending.AccessControl\n"
                + "HELPER ai.skillroy.testjava.agent.byteman.sys.RuleHelper\n"
                + "METHOD returnLimit\n"
                + "AT EXIT\n"
                + "IF true\n"
                + "DO fired($0, $!)\n"
                + "ENDRULE\n", r.submitText());
    }

    @Test
    void protectedClassesAreRefusedAndASubclassRuleIsAllowed() {
        RuleLint.Result agent = RuleLint.check(rule("x", "m", "fired($0)").replace(TOY,
                "ai.skillroy.testjava.agent.core.Dispatcher"));
        assertFalse(agent.ok());
        assertTrue(agent.errors().get(0).startsWith("line 2: ai.skillroy.testjava.agent.core.Dispatcher is in a"
                + " protected package"), agent.errors().toString());
        for (String jdk : new String[] {"java.lang.Thread", "^java.util.AbstractList", "javax.swing.JButton",
                "sun.misc.Unsafe", "com.sun.net.httpserver.HttpServer", "jdk.internal.misc.VM",
                "org.jboss.byteman.agent.Main", "ai.skillroy.testjava.protocol.Json"}) {
            assertFalse(RuleLint.check(rule("x", "m", "fired($0)").replace(TOY, jdk)).ok(), jdk);
        }
        RuleLint.Result sub = RuleLint.check(rule("x", "m", "fired($0)").replace("CLASS " + TOY,
                "CLASS ^" + TOY));
        assertTrue(sub.ok(), sub.errors().toString());
        assertTrue(sub.overriding());
        assertEquals(TOY, sub.className());
        RuleLint.Result iface = RuleLint.check(rule("x", "m", "fired($0)").replace("CLASS " + TOY,
                "INTERFACE ai.skillroy.testjava.fixture.byteman.Ceilings"));
        assertTrue(iface.ok(), iface.errors().toString());
        assertTrue(iface.isInterface());
    }

    @Test
    void theStructureIsChecked() {
        String good = rule("audit", "ceiling", "fired($0)");
        assertTrue(RuleLint.check(good).ok());
        assertError(good + good, "exactly one RULE");
        assertError(good.replace("ENDRULE\n", ""), "no ENDRULE");
        assertError(good.replace("CLASS " + TOY + "\n", ""), "no CLASS or INTERFACE line");
        assertError(good.replace("METHOD ceiling\n", ""), "no METHOD line");
        assertError(good.replace("CLASS " + TOY, "CLASS DepositDesk"), "line 2: CLASS must be a fully qualified");
        assertError("HELPER org.jboss.byteman.rule.helper.Helper\n" + good, "line 1: text outside RULE");
        assertError(good.replace("AT ENTRY", "HELPER org.jboss.byteman.rule.helper.Helper\nAT ENTRY"),
                "line 4: a HELPER line may only name");
        assertError(good.replace("AT ENTRY", "IMPORT java.base\nAT ENTRY"), "line 4: IMPORT is not allowed");
        assertError(good.replace("AT ENTRY", "COMPILE\nAT ENTRY"), "line 4: COMPILE is not allowed");
        assertError(good.replace("AT ENTRY", "NOCOMPILE\nAT ENTRY"), "line 4: NOCOMPILE is not allowed");
        assertError(good.replace("RULE audit", "RULE skillroy:audit"), "line 1: the rule name must match");
        assertError(good.replace("RULE audit", "RULE 9lives"), "line 1: the rule name must match");
        assertError("", "the rule: no text");
        StringBuilder big = new StringBuilder(good);
        while (big.length() <= RuleLint.MAX_BYTES) {
            big.append("# padding padding padding padding padding padding padding padding\n");
        }
        assertError(big.toString(), "more than " + RuleLint.MAX_BYTES);
    }

    private static void assertError(String text, String fragment) {
        RuleLint.Result r = RuleLint.check(text);
        assertFalse(r.ok(), "refused: " + text);
        boolean found = false;
        for (String e : r.errors()) {
            found |= e.contains(fragment);
        }
        assertTrue(found, fragment + " in " + r.errors());
    }
}
