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

package ai.skillroy.testjava.agent.web.reflect;

import ai.skillroy.testjava.agent.web.kit.RecordingClassLoader;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The descriptor plumbing of phase4-contracts.md section 4.7: the format and its errors, roles found by method,
 * field, class, name, arity and alternatives, missing optional roles as missing groups (never an error), private
 * members made accessible, calls and their failures, proxies for vendor interfaces in the vendor's loader, and the
 * resolution cached weakly per class loader.
 */
class ReflectiveApiTest {

    private static final String S = "ai.skillroy.testjava.agent.web.reflect.Sample";

    static Descriptor descriptor() {
        return Descriptor.parse("test", "{\"format\": 1, \"adapter\": \"sample\", \"gate\": \"" + S + "\", "
                + "\"roles\": {"
                + "\"sample\": {\"class\": \"" + S + "\", \"for\": \"core\"},"
                + "\"sample.create\": {\"class\": \"" + S + "\", \"method\": \"create\", \"static\": true, "
                + "\"for\": \"core\"},"
                + "\"sample.echo\": {\"class\": \"" + S + "\", \"method\": \"echo\", "
                + "\"params\": [\"java.lang.String\"], \"for\": \"core\"},"
                + "\"sample.add\": {\"class\": \"" + S + "\", \"method\": \"add\", \"params\": [\"int\", \"int\"], "
                + "\"for\": \"core\"},"
                + "\"sample.run\": {\"class\": \"" + S + "\", \"method\": \"run\", "
                + "\"params\": [\"java.lang.String\", \"java.util.function.Consumer\"], \"for\": \"core\"},"
                + "\"sample.put\": {\"class\": \"" + S + "\", \"method\": \"put\", \"arity\": 2, \"for\": \"core\"},"
                + "\"sample.fail\": {\"class\": \"" + S + "\", \"method\": \"fail\", \"for\": \"core\"},"
                + "\"sample.tell\": {\"class\": \"" + S + "\", \"method\": \"tell\", "
                + "\"params\": [\"" + S + "$Listener\"], \"for\": \"core\"},"
                + "\"sample.name\": {\"class\": \"" + S + "\", \"field\": \"name\", \"for\": \"core\"},"
                + "\"sample.secret\": {\"class\": \"" + S + "\", \"field\": \"secret\", \"static\": true, "
                + "\"access\": \"private\", \"for\": \"private\"},"
                + "\"sample.guarded\": {\"class\": \"" + S + "\", \"method\": \"guarded\", \"access\": \"private\", "
                + "\"for\": \"private\"},"
                + "\"listener\": {\"class\": \"" + S + "$Listener\", \"interface\": true, \"for\": \"core\"},"
                + "\"byName\": {\"class\": \"" + S + "\", \"match\": \"name\", \"for\": \"core\"},"
                + "\"renamed\": {\"class\": \"" + S + "\", \"method\": \"echoOfANewerLine\", "
                + "\"params\": [\"java.lang.String\"], \"for\": \"BITMAP\", \"alternatives\": ["
                + "{\"class\": \"" + S + "\", \"method\": \"echo\", \"params\": [\"java.lang.String\"]}]},"
                + "\"gone\": {\"class\": \"" + S + "\", \"method\": \"notThere\", \"for\": \"RESIZE\"},"
                + "\"goneClass\": {\"class\": \"com.example.NotThere\", \"for\": \"walk\"},"
                + "\"wrongStatic\": {\"class\": \"" + S + "\", \"method\": \"echo\", "
                + "\"params\": [\"java.lang.String\"], \"static\": true, \"for\": \"walk\"}"
                + "}}");
    }

    @Test
    void rolesAreFoundAndMissingOnesAreMissingGroupsNotErrors() throws Exception {
        Descriptor d = descriptor();
        assertEquals("sample", d.adapter());
        assertEquals(S, d.gate());
        ResolvedApi api = ReflectiveApi.resolve(d, ReflectiveApiTest.class.getClassLoader());
        assertTrue(api.has("sample.echo"));
        assertTrue(api.has("renamed"), "found through its alternative");
        assertFalse(api.has("gone"));
        assertFalse(api.has("goneClass"));
        assertFalse(api.has("wrongStatic"), "a static mismatch is a missing role");
        assertTrue(api.missingReason("gone").contains("notThere"), api.missingReason("gone"));
        assertTrue(api.missingReason("goneClass").contains("com.example.NotThere"));
        assertEquals(new java.util.LinkedHashSet<String>(Arrays.asList("RESIZE", "walk")), api.missingGroups());
        assertTrue(api.complete("core"));
        assertTrue(api.complete("BITMAP"));
        assertFalse(api.complete("RESIZE"));
        assertTrue(api.firstMissing("RESIZE").startsWith("gone: "));
        assertNull(api.firstMissing("core"));
    }

    @Test
    void callsReadsAndFailures() throws Exception {
        ResolvedApi api = ReflectiveApi.resolve(descriptor(), ReflectiveApiTest.class.getClassLoader());
        Object sample = api.invoke("sample.create", null);
        assertTrue(api.isInstance("sample", sample));
        assertTrue(api.isInstance("byName", sample), "a role matched by name");
        assertFalse(api.isInstance("sample", "a string"));
        assertEquals("echo hi", api.invoke("sample.echo", sample, "hi"));
        assertEquals("echo alt", api.invoke("renamed", sample, "alt"));
        assertEquals(Integer.valueOf(5), api.invoke("sample.add", sample, Integer.valueOf(2), Integer.valueOf(3)));
        final Object[] got = new Object[1];
        api.invoke("sample.run", sample, "quiet", new Consumer<Object>() {
            @Override
            public void accept(Object o) {
                got[0] = o;
            }
        });
        assertEquals("QUIET", got[0]);
        api.invoke("sample.put", sample, String.class, "v");
        assertEquals("String=v", ((Sample) sample).log());
        assertEquals("sample", api.read("sample.name", sample));
        assertEquals("sample", api.get("sample.name", sample));
        assertEquals("hidden", api.read("sample.secret", null), "a private static field made accessible");
        assertEquals("guarded", api.invoke("sample.guarded", sample));
        ReflectiveException failure = assertThrows(ReflectiveException.class, () -> api.invoke("sample.fail",
                sample));
        assertEquals("sample.fail", failure.role());
        assertEquals(IllegalStateException.class.getName(), failure.vendorClass());
        assertEquals("vendor boom", failure.vendorMessage());
        assertTrue(failure.getMessage().contains("vendor boom"));
        assertThrows(IllegalStateException.class, () -> api.invoke("gone", sample), "a missing role is a defect");
        assertThrows(ReflectiveException.class, () -> api.invoke("sample.echo", sample, Integer.valueOf(1)));
    }

    @Test
    void aProxyImplementsTheVendorInterfaceInItsLoaderAndAnswersObjectMethodsItself() throws Exception {
        ResolvedApi api = ReflectiveApi.resolve(descriptor(), ReflectiveApiTest.class.getClassLoader());
        final int[] calls = new int[1];
        Object listener = api.implement("listener", new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                calls[0]++;
                return "heard " + args[0];
            }
        });
        assertSame(Sample.Listener.class.getClassLoader(), listener.getClass().getClassLoader());
        Object sample = api.invoke("sample.create", null);
        assertEquals("heard news", api.invoke("sample.tell", sample, listener));
        assertTrue(listener.equals(listener));
        assertFalse(listener.equals(new Object()));
        assertEquals(System.identityHashCode(listener), listener.hashCode());
        assertTrue(listener.toString().startsWith("skillroy:"));
        assertEquals(1, calls[0], "Object's methods never reach the handler");
        assertThrows(IllegalStateException.class, () -> api.implement("sample", null), "not an interface");
    }

    @Test
    void theResolutionIsCachedPerLoaderAndWeakly() throws Exception {
        Descriptor d = descriptor();
        ClassLoader here = ReflectiveApiTest.class.getClassLoader();
        ResolvedApi first = ReflectiveApi.resolve(d, here);
        assertSame(first, ReflectiveApi.resolve(d, here), "the same loader resolves once");
        RecordingClassLoader other = new RecordingClassLoader(here, here, S);
        ResolvedApi second = ReflectiveApi.resolve(d, other);
        assertNotSame(first, second, "another loader resolves on its own");
        assertNotSame(first.type("sample"), second.type("sample"), "the other loader's own class");
        assertEquals(2, ReflectiveApi.cachedLoaders(d));
        WeakReference<ResolvedApi> gone = new WeakReference<ResolvedApi>(second);
        second = null;
        other = null;
        for (int i = 0; i < 50 && gone.get() != null; i++) {
            System.gc();
            byte[] pressure = new byte[1 << 20];
            assertNotNull(pressure);
        }
        assertNull(gone.get(), "the cache does not keep a resolution alive");
        assertEquals(1, ReflectiveApi.cachedLoaders(d), "only the live one counts");
        ReflectiveApi.forget(d);
        assertEquals(0, ReflectiveApi.cachedLoaders(d));
        assertNotNull(first);
    }

    @Test
    void anInvalidDescriptorIsRefusedWithItsReason() {
        String[] bad = {
            "not json",
            "{\"format\": 2, \"adapter\": \"a\", \"gate\": \"a.B\", \"roles\": {}}",
            "{\"format\": 1, \"adapter\": \"a\", \"gate\": \"a.B\", \"roles\": {}, \"extra\": 1}",
            "{\"format\": 1, \"adapter\": \"a\", \"gate\": \"not a class\", \"roles\": {}}",
            "{\"format\": 1, \"adapter\": \"a\", \"gate\": \"a.B\", \"roles\": {\"r\": {\"class\": \"a.B\"}}}",
            "{\"format\": 1, \"adapter\": \"a\", \"gate\": \"a.B\", \"roles\": {\"r\": {\"class\": \"a.B\", "
                + "\"method\": \"m\", \"field\": \"f\", \"for\": \"core\"}}}",
            "{\"format\": 1, \"adapter\": \"a\", \"gate\": \"a.B\", \"roles\": {\"r\": {\"class\": \"a.B\", "
                + "\"field\": \"f\", \"params\": [], \"for\": \"core\"}}}",
            "{\"format\": 1, \"adapter\": \"a\", \"gate\": \"a.B\", \"roles\": {\"r\": {\"class\": \"a.B\", "
                + "\"method\": \"m\", \"arity\": 1, \"params\": [\"int\"], \"for\": \"core\"}}}",
            "{\"format\": 1, \"adapter\": \"a\", \"gate\": \"a.B\", \"roles\": {\"r\": {\"class\": \"a.B\", "
                + "\"match\": \"name\", \"method\": \"m\", \"for\": \"core\"}}}",
            "{\"format\": 1, \"adapter\": \"a\", \"gate\": \"a.B\", \"roles\": {\"r\": {\"class\": \"a.B\", "
                + "\"access\": \"protected\", \"for\": \"core\"}}}",
            "{\"format\": 1, \"adapter\": \"a\", \"gate\": \"a.B\", \"roles\": {\"r\": {\"class\": \"a.B\", "
                + "\"for\": \"core\", \"alternatives\": [{\"class\": \"a.C\", \"for\": \"x\"}]}}}",
        };
        for (String text : bad) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Descriptor.parse("t",
                    text), text);
            assertTrue(e.getMessage().startsWith("invalid descriptor t: "), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> Descriptor.load("ai/skillroy/none.json",
                ReflectiveApiTest.class.getClassLoader()));
    }

    @Test
    void theBuiltInDescriptorsParseAndNameNoMemberOffLimits() {
        for (String resource : new String[] {"ai/skillroy/testjava/agent/web/jcef.json",
                "ai/skillroy/testjava/agent/web/jxbrowser.json"}) {
            Descriptor d = Descriptor.load(resource, ReflectiveApiTest.class.getClassLoader());
            for (String role : d.roles()) {
                Descriptor.Role r = d.role(role);
                assertFalse("licenseKey".equals(r.method()), "no role reads a licence key");
                assertFalse("getInstance".equals(r.method()), "no role creates the technology");
                assertFalse("newInstance".equals(r.method()));
                assertFalse("dispatch".equals(r.method()), "input stays reserved");
                assertFalse("close".equals(r.method()), "nothing of the application is closed");
            }
        }
    }
}
