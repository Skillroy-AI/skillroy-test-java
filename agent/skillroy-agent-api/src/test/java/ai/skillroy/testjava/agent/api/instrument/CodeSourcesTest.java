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

package ai.skillroy.testjava.agent.api.instrument;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodeSources} (phase8-contracts.md section 6.2, D556; section 13.1 row 23): every row of the naming table,
 * control characters after decoding, the sentinels, the 200 cut, and the field-getter rule proved with a location
 * whose stream handler throws on everything but the fields. Runs on the Java 8 leg too.
 */
class CodeSourcesTest {

    @Test
    void everyRowOfTheNamingTable() {
        assertEquals("lending-app-1.2.jar", CodeSources.name("file", "", "/opt/desk/lib/lending-app-1.2.jar"));
        assertEquals("desk.jar", CodeSources.name("file", "", "/C:/Users/x/desk/lib/desk.jar"));
        assertEquals("classes", CodeSources.name("file", "", "/build/my%20desk/classes/"));
        assertEquals("app.jar!/BOOT-INF/lib/lib.jar",
                CodeSources.name("jar", "", "file:/opt/app.jar!/BOOT-INF/lib/lib.jar!/"));
        assertEquals("app.jar!/BOOT-INF/lib/lib.jar",
                CodeSources.name("jar", "", "nested:/opt/app.jar/!BOOT-INF/lib/lib.jar!/"));
        assertEquals("java.base", CodeSources.name("jrt", "", "/java.base"));
        assertEquals("bundleresource:12.fwk", CodeSources.name("bundleresource", "12.fwk", "/"));
        assertEquals("(none)", CodeSources.of(String.class));
        assertEquals("(none)", CodeSources.NONE);
        assertEquals("(unreadable)", CodeSources.UNREADABLE);
    }

    @Test
    void realLocationsAreNamedByTheirFieldsAlone() throws MalformedURLException {
        URL plain = new URL("file:/opt/desk/lib/skillroy-sample-lending-app.jar");
        assertEquals("skillroy-sample-lending-app.jar", CodeSources.name(plain.getProtocol(), plain.getHost(),
                plain.getPath()));
        URL nested = new URL("jar:file:/opt/app.jar!/BOOT-INF/lib/lib.jar!/");
        assertEquals("app.jar!/BOOT-INF/lib/lib.jar", CodeSources.name(nested.getProtocol(), nested.getHost(),
                nested.getPath()));
        String ours = CodeSources.of(CodeSourcesTest.class);
        assertEquals("test-classes", ours, "the test classes come from a directory, named by its last segment only");
    }

    @Test
    void controlCharactersBecomeSpacesAfterDecoding() {
        assertEquals("a b.jar", CodeSources.name("file", "", "/x/a%0Ab.jar"));
        assertEquals("a b.jar", CodeSources.name("file", "", "/x/a%1Bb.jar"));
        assertEquals("a b.jar", CodeSources.name("file", "", "/x/a%7Fb.jar"));
        assertEquals("app.jar!/BOOT INF/lib.jar", CodeSources.name("jar", "", "file:/x/app.jar!/BOOT%0DINF/lib.jar"));
        assertEquals("desk+tools.jar", CodeSources.name("file", "", "/x/desk+tools.jar"), "'+' is kept");
        assertEquals("caf\u00e9.jar", CodeSources.name("file", "", "/x/caf%C3%A9.jar"), "UTF-8");
        assertEquals("bad%E2%82.jar", CodeSources.name("file", "", "/x/bad%E2%82.jar"),
                "an undecodable sequence is kept as written");
        assertEquals("100%.jar", CodeSources.name("file", "", "/x/100%.jar"), "a lone percent sign is kept");
        assertEquals("a%zzb.jar", CodeSources.name("file", "", "/x/a%zzb.jar"));
    }

    @Test
    void aLongNameIsCutAt200() {
        StringBuilder jar = new StringBuilder("/lib/");
        for (int i = 0; i < 300; i++) {
            jar.append('j');
        }
        jar.append(".jar");
        String name = CodeSources.name("file", "", jar.toString());
        assertEquals(CodeSources.MAX_LENGTH, name.length());
        assertTrue(name.startsWith("jjj"));
        assertEquals("file:", CodeSources.name("file", null, null), "no part and no host");
    }

    @Test
    void aClassDefinedWithoutADomainHasNone() throws Exception {
        TestLoader loader = new TestLoader();
        Class<?> plain = loader.define(Victim.class.getName(), bytesOf(Victim.class), null);
        assertEquals(CodeSources.NONE, CodeSources.of(plain));
        assertEquals(CodeSources.NONE, CodeSources.of(null));
    }

    @Test
    void onlyTheLocationsFieldGettersAreCalled() throws Exception {
        Hostile handler = new Hostile();
        URL location = new URL("vfs", "", -1, "/content/desk.ear/lib/desk-plugin-2.1.jar", handler);
        ProtectionDomain domain = new ProtectionDomain(new CodeSource(location, (Certificate[]) null), null);
        TestLoader loader = new TestLoader();
        Class<?> defined = loader.define(Victim.class.getName(), bytesOf(Victim.class), domain);
        handler.armed = true;
        assertEquals("desk-plugin-2.1.jar", CodeSources.of(defined));
        assertEquals(0, handler.calls, "no handler method ran");
        assertThrows(IllegalStateException.class, location::toString, "the handler is armed");
        assertThrows(IllegalStateException.class, location::hashCode, "the handler is armed");
        assertEquals(2, handler.calls);
    }

    /** A class whose bytes the tests define again in a loader of their own. */
    public static final class Victim {
        @Override
        public String toString() {
            return "victim";
        }
    }

    private static byte[] bytesOf(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Defines a class with or without a protection domain. */
    private static final class TestLoader extends ClassLoader {
        TestLoader() {
            super(CodeSourcesTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytes, ProtectionDomain domain) {
            return domain == null ? defineClass(name, bytes, 0, bytes.length)
                    : defineClass(name, bytes, 0, bytes.length, domain);
        }
    }

    /**
     * A custom protocol's handler, as an application server's virtual file system has: once armed, every method that
     * runs application code or resolves a host throws and counts.
     */
    private static final class Hostile extends URLStreamHandler {
        volatile boolean armed;
        volatile int calls;

        private void check() {
            if (armed) {
                calls++;
                throw new IllegalStateException("the code-source reader called the URL's handler");
            }
        }

        @Override
        protected URLConnection openConnection(URL u) throws IOException {
            check();
            throw new IOException("no connection");
        }

        @Override
        protected String toExternalForm(URL u) {
            check();
            return super.toExternalForm(u);
        }

        @Override
        protected int hashCode(URL u) {
            check();
            return super.hashCode(u);
        }

        @Override
        protected boolean equals(URL a, URL b) {
            check();
            return super.equals(a, b);
        }

        @Override
        protected boolean sameFile(URL a, URL b) {
            check();
            return super.sameFile(a, b);
        }

        @Override
        protected boolean hostsEqual(URL a, URL b) {
            check();
            return super.hostsEqual(a, b);
        }
    }
}
