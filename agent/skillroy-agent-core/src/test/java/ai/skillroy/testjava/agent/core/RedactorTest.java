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

package ai.skillroy.testjava.agent.core;

import ai.skillroy.testjava.agent.api.spi.RedactionKeys;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Secret redaction rules of contract amendment A5, module keys (A21) and URL userinfo (finding S21). */
class RedactorTest {

    private static final String R = Redactor.REDACTED;
    private final Redactor redactor = Redactor.withExtraKeys(null);

    @Test
    void theDefaultKeysAreSecretInAnyCaseAndAnywhereInTheKey() {
        String[] secret = {"password", "db.password", "DB_PASSWORD", "passwd", "user.pwd", "client.secret", "token",
            "auth.Token", "tokenFile", "credentials", "licence", "license.key", "LicenseFile", "apikey", "api-key",
            "api_key", "api.key", "x.API.KEY", "privatekey", "private-key", "private_key", "ssl.private.key"};
        for (String key : secret) {
            assertTrue(redactor.isSecret(key), key);
        }
        String[] plain = {"user", "url", "port", "java.home", "apiVersion", "private", "key", "pass", "nonce",
            "keyStore", "javax.net.ssl.trustStore"};
        for (String key : plain) {
            assertFalse(redactor.isSecret(key), key);
        }
        assertFalse(redactor.isSecret(null));
    }

    @Test
    void wholeArgumentsLoseEverythingAfterTheFirstEquals() {
        assertEquals("-Ddb.password=" + R, redactor.argument("-Ddb.password=hunter2"));
        assertEquals("-Ddb.password=" + R, redactor.argument("-Ddb.password=a,b;c d=e"));
        assertEquals("-Dapi.token=" + R, redactor.argument("-Dapi.token=abc"));
        assertEquals("--api-key=" + R, redactor.argument("--api-key=abc,def"));
        assertEquals("-secret=" + R, redactor.argument("-secret=x"));
        assertEquals("-Dplain=1", redactor.argument("-Dplain=1"));
        assertEquals("-Dpassword", redactor.argument("-Dpassword"));
        assertEquals("-Xmx1g", redactor.argument("-Xmx1g"));
        assertEquals("-XX:+UseG1GC", redactor.argument("-XX:+UseG1GC"));
        assertNull(redactor.argument(null));
    }

    @Test
    void pairsInsideOtherArgumentsAndValuesAreRedactedUpToTheNextSeparator() {
        assertEquals("-javaagent:/a/b.jar=t=/x;token=" + R + ";nonce=n1;port=0",
                redactor.argument("-javaagent:/a/b.jar=t=/x;token=abc;nonce=n1;port=0"));
        assertEquals("-agentlib:jdwp=transport=dt_socket,server=y,address=5005",
                redactor.argument("-agentlib:jdwp=transport=dt_socket,server=y,address=5005"));
        assertEquals("-Dconfig=user=x;password=" + R, redactor.argument("-Dconfig=user=x;password=y"));
        assertEquals("-Durl=jdbc:x://h/db?user=a&password=" + R + "&ssl=true",
                redactor.argument("-Durl=jdbc:x://h/db?user=a&password=b&ssl=true"));
        assertEquals("token=" + R + ";x=1", redactor.pairs("token=YWJj==;x=1"), "padding stays hidden");
        assertEquals("a=1 secret=" + R + " b=2", redactor.pairs("a=1 secret=s b=2"));
        assertEquals("=x", redactor.pairs("=x"), "an equals sign without a key");
        assertEquals("no pairs here", redactor.pairs("no pairs here"));
    }

    @Test
    void namedValuesAreRedactedWholeWhenTheNameIsSecret() {
        assertEquals(R, redactor.value("db.password", "hunter2"));
        assertEquals(R, redactor.value("db.password", ""), "present but empty is still present");
        assertNull(redactor.value("db.password", null), "absent stays absent");
        assertEquals("jdbc:x:?user=u&password=" + R, redactor.value("jdbc.url", "jdbc:x:?user=u&password=p"));
        assertEquals("/opt/app", redactor.value("app.home", "/opt/app"));
    }

    @Test
    void theRedactOptionAddsAKeyRegex() {
        Redactor extra = Redactor.withExtraKeys("session|cookie");
        assertEquals("-Dapp.sessionKey=" + R, extra.argument("-Dapp.sessionKey=k1"));
        assertEquals(R, extra.value("http.Cookie", "c"));
        assertEquals("-Ddb.password=" + R, extra.argument("-Ddb.password=x"), "the defaults still apply");
        assertEquals("-Dapp.sessionKey=k1", redactor.argument("-Dapp.sessionKey=k1"), "only with the option");
        assertEquals("-Dx=1", Redactor.withExtraKeys("  ").argument("-Dx=1"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Redactor.withExtraKeys("(unclosed"));
        assertTrue(e.getMessage().contains("redact"), e.getMessage());
    }

    @Test
    void thePasswordOfUrlUserinfoIsRedactedInAnyValue() {
        assertEquals("jdbc:mysql://u:" + R + "@h/db", redactor.value("db.url", "jdbc:mysql://u:p@h/db"));
        assertEquals("-Ddb.url=mongodb://u:" + R + "@h1:27017,h2/db",
                redactor.argument("-Ddb.url=mongodb://u:p@h1:27017,h2/db"));
        assertEquals("redis://:" + R + "@h:6379", redactor.value("cache", "redis://:pw@h:6379"));
        assertEquals("jdbc:mysql://u:" + R + "@h/db", redactor.value("db.url", "jdbc:mysql://u:p@ss@h/db"),
                "the last @ of the authority ends the password");
        assertEquals("jdbc:oracle:thin:scott/" + R + "@//h:1521/svc",
                redactor.value("db.url", "jdbc:oracle:thin:scott/tiger@//h:1521/svc"));
        assertEquals("http://u:" + R + "@proxy:8080 x", redactor.argument("http://u:secret@proxy:8080 x"));
        assertEquals("jdbc:x://u:" + R + "@h/db?user=a&password=" + R,
                redactor.value("db.url", "jdbc:x://u:p@h/db?user=a&password=b"), "both rules together");
    }

    @Test
    void valuesWithoutAUserinfoPasswordAreUnchanged() {
        String[] unchanged = {"http://host:8080/path@x", "http://[::1]:8080/a", "ssh://git@github.com/x.git",
            "jdbc:oracle:thin:@//h:1521/svc", "jdbc:hsqldb:mem:lending", "user@example.com", "a:b@c"};
        for (String value : unchanged) {
            assertEquals(value, redactor.value("some.value", value), value);
            assertEquals("-Dsome.value=" + value, redactor.argument("-Dsome.value=" + value), value);
        }
    }

    @Test
    void registeredRedactionKeysAreConsultedAtCallTime() {
        CoreSpiRegistry spi = new CoreSpiRegistry();
        Redactor withModules = Redactor.withExtraKeys(null, spi, new RingLog());
        assertEquals("4711", withModules.value("lending.pin", "4711"), "nothing registered yet");
        AutoCloseable handle = spi.register(RedactionKeys.class, new RedactionKeys() {
            @Override
            public Pattern secretKeys() {
                return Pattern.compile("\\.pin$");
            }
        });
        assertEquals(R, withModules.value("lending.pin", "4711"), "a registration takes effect at once");
        assertEquals("-Dlending.pin=" + R, withModules.argument("-Dlending.pin=4711"));
        assertEquals(R, withModules.value("db.password", "x"), "the defaults still apply");
        assertEquals("4711", withModules.value("lending.PIN", "4711"), "the pattern's own case sensitivity");
        spi.register(RedactionKeys.class, new RedactionKeys() {
            @Override
            public Pattern secretKeys() {
                return null;
            }
        });
        assertEquals("1", withModules.value("plain", "1"), "a null pattern means no keys");
        try {
            handle.close();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals("4711", withModules.value("lending.pin", "4711"), "an unregistered module's keys stop counting");
    }

    @Test
    void aFailingRedactionKeysProviderMakesEveryKeySecret() {
        CoreSpiRegistry spi = new CoreSpiRegistry();
        RingLog log = new RingLog();
        spi.register(RedactionKeys.class, new RedactionKeys() {
            @Override
            public Pattern secretKeys() {
                throw new IllegalStateException("broken");
            }
        });
        Redactor failing = Redactor.withExtraKeys(null, spi, log);
        assertEquals(R, failing.value("java.version", "1.8"));
        assertEquals(R, failing.value("user.home", "/h"));
        int warnings = 0;
        for (String line : log.tail(100)) {
            if (line.contains("RedactionKeys")) {
                warnings++;
            }
        }
        assertEquals(1, warnings, "logged once, not per key");
    }
}
