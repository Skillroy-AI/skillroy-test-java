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

package ai.skillroy.testjava.data;

import ai.skillroy.testjava.protocol.sql.Rows;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The controller-side JDBC channel against an HSQLDB database started in the test, and against fake drivers
 * (phase2-contracts.md 7.2, 14.1 item 2).
 */
class JdbcDataChannelTest {

    private static final String URL_PASSWORD = "Url-Info-Secret-31";
    private static TestDatabase db;

    @BeforeAll
    static void createDatabase() throws Exception {
        db = TestDatabase.create();
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        db.close();
    }

    @BeforeEach
    void resetFakes() {
        FakeDriver.CONNECTS.set(0);
        FakeDriver.last = null;
        FakeDriver.release = new CountDownLatch(1);
        FakeDriver.lateClosed = new CountDownLatch(1);
        FakeDriver.hanging = new CountDownLatch(1);
        DriverLog.LOADERS.clear();
    }

    /** A source served through the fake driver, found by the ServiceLoader, so that connects are counted. */
    private static Map<String, Object> counted(Object... extra) {
        return db.source(FakeDriver.PREFIX + db.url.substring("jdbc:".length()), extra);
    }

    private static JdbcDataChannel channel(Map<String, Object> source, RowMask... masks) throws Exception {
        return new JdbcDataChannel(TestDatabase.credentials("db", source), List.of(masks));
    }

    private static void assertSecretFree(String text) {
        for (String secret : List.of(TestDatabase.READER_PASSWORD, URL_PASSWORD, "Acct-Secret-1111")) {
            assertFalse(text.contains(secret), "a secret in: " + text);
        }
    }

    @Test
    void aGuardedStatementIsRefusedBeforeAConnectionIsUsed() throws Exception {
        try (JdbcDataChannel channel = channel(counted())) {
            DataException e = assertThrows(DataException.class, () -> channel.query("db", "DELETE FROM person",
                    List.of(), 10, null));
            assertEquals(DataException.Kind.GUARD, e.kind());
            assertTrue(e.getMessage().contains("refused by the statement guard"), e.getMessage());
            assertNull(e.rpcCode());
            assertEquals(0, FakeDriver.CONNECTS.get(), "no connection was opened");
            assertFalse(channel.connected("db"));
        }
    }

    @Test
    void theConnectionIsKeptAndReopenedOnceAfterItWasClosedUnderneath() throws Exception {
        try (JdbcDataChannel channel = channel(counted())) {
            assertEquals(3, channel.query("db", "SELECT code FROM person", List.of(), 10, null).rowCount());
            assertEquals(1, channel.query("db", "SELECT code FROM person WHERE code = ?", List.of("200"), 10,
                    Duration.ofSeconds(3)).rowCount());
            assertEquals(1, FakeDriver.CONNECTS.get(), "one connection for both statements");
            Connection kept = FakeDriver.last;
            assertTrue(kept.isReadOnly(), "the kept connection is read-only");
            kept.close();
            assertEquals(3, channel.query("db", "SELECT code FROM person", List.of(), 10, null).rowCount());
            assertEquals(2, FakeDriver.CONNECTS.get(), "reopened once");
            assertTrue(channel.connected("db"));
        }
        assertTrue(FakeDriver.last.isClosed(), "close() closes the kept connection");
    }

    @Test
    void aClosedChannelServesNothingMore() throws Exception {
        JdbcDataChannel channel = channel(counted());
        channel.query("db", "SELECT code FROM person", List.of(), 10, null);
        channel.close();
        channel.close();
        DataException e = assertThrows(DataException.class, () -> channel.query("db", "SELECT code FROM person",
                List.of(), 10, null));
        assertEquals(DataException.Kind.UNAVAILABLE, e.kind());
        assertTrue(channel.toString().endsWith("[closed]"), channel.toString());
    }

    @Test
    void rowMasksApplyToEveryValueAndAThrowingOneRevealsNothing() throws Exception {
        RowMask password = (source, column, value) -> "password".equalsIgnoreCase(column) ? "<redacted>" : value;
        RowMask upper = (source, column, value) -> "code".equalsIgnoreCase(column) && value != null
                ? new StringBuilder("C-").append(value) : value;
        try (JdbcDataChannel channel = channel(db.source(db.url), password, upper)) {
            Rows rows = channel.query("db", "SELECT code, password FROM account ORDER BY code", List.of(), 10, null);
            assertEquals(List.of(List.of("C-100", "<redacted>"), List.of("C-200", "<redacted>")), rows.rows(),
                    "masks run in order, and a value that is not normal is converted");
        }
        RowMask broken = (source, column, value) -> {
            throw new IllegalStateException("cannot mask " + value);
        };
        try (JdbcDataChannel channel = channel(db.source(db.url), broken)) {
            DataException e = assertThrows(DataException.class, () -> channel.query("db",
                    "SELECT password FROM account WHERE code = '100'", List.of(), 10, null));
            assertEquals(DataException.Kind.MASK, e.kind());
            assertEquals(IllegalStateException.class.getName(), e.exceptionClass());
            assertSecretFree(e.getMessage());
            assertSecretFree(e.toString());
            assertNull(e.getCause());
        }
    }

    @Test
    void passwordAndUrlUserInfoAppearInNoExceptionOrText() throws Exception {
        // The fake driver's message holds the password and the URL; none of it may come through.
        Map<String, Object> source = db.source("jdbc:skillroy-fake://reader:" + URL_PASSWORD + "@db.test/sqlerror");
        try (JdbcDataChannel channel = channel(source)) {
            DataException e = assertThrows(DataException.class, () -> channel.query("db", "SELECT 1 FROM person",
                    List.of(), 1, null));
            assertEquals(DataException.Kind.SQL, e.kind());
            assertEquals("28000", e.sqlState());
            assertEquals(4711, e.errorCode());
            assertEquals(SQLException.class.getName(), e.exceptionClass());
            assertSecretFree(e.getMessage());
            assertSecretFree(e.toString());
            assertFalse(e.getMessage().contains("db.test"), "not even the host: " + e.getMessage());
            assertSecretFree(channel.toString());
            Credentials credentials = TestDatabase.credentials("db", source);
            assertSecretFree(credentials.toString());
            assertEquals("<redacted>", credentials.mask().apply(URL_PASSWORD));
            assertEquals("<redacted>", credentials.mask().apply(TestDatabase.READER_PASSWORD));
        }
    }

    @Test
    void theDriverIsFoundThroughTheServiceLoader() throws Exception {
        try (JdbcDataChannel channel = channel(counted())) {
            assertEquals(3, channel.query("db", "SELECT code FROM person", List.of(), 10, null).rowCount());
            assertEquals(1, FakeDriver.CONNECTS.get());
        }
    }

    @Test
    void theDriverIsFoundByClassNameInAChildLoaderThatDriverManagerCannotSee() throws Exception {
        String url = HiddenDriver.PREFIX + db.url.substring("jdbc:".length());
        assertThrows(SQLException.class, () -> DriverManager.getDriver(url), "not registered with DriverManager");
        ClassLoader child = new ChildFirstLoader(getClass().getClassLoader(), HiddenDriver.class.getName());
        Thread thread = Thread.currentThread();
        ClassLoader before = thread.getContextClassLoader();
        thread.setContextClassLoader(child);
        try (JdbcDataChannel channel = channel(db.source(url, "driver", HiddenDriver.class.getName()))) {
            assertEquals(3, channel.query("db", "SELECT code FROM person", List.of(), 10, null).rowCount());
        } finally {
            thread.setContextClassLoader(before);
        }
        assertEquals(1, DriverLog.LOADERS.size());
        assertSame(child, DriverLog.LOADERS.get(0), "the copy from the context class loader connected");
    }

    @Test
    void aConnectThatNeverReturnsIsATimeoutAndALateConnectionIsClosed() throws Exception {
        try (JdbcDataChannel channel = channel(db.source("jdbc:skillroy-fake://h/hang", "loginTimeout", "200ms"))) {
            long start = System.nanoTime();
            DataException e = assertThrows(DataException.class, () -> channel.query("db", "SELECT 1 FROM person",
                    List.of(), 1, null));
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(DataException.Kind.TIMEOUT, e.kind());
            assertTrue(e.getMessage().contains("200 ms"), e.getMessage());
            assertTrue(ms >= 150 && ms < 10000, "bounded by loginTimeout: " + ms + " ms");
            assertFalse(channel.connected("db"));
            FakeDriver.release.countDown();
            assertTrue(FakeDriver.lateClosed.await(30, TimeUnit.SECONDS), "the late connection was closed");
        }
    }

    @Test
    void aCallsTimeoutBoundsTheConnectAndTheWaitForTheSourcesConnection() throws Exception {
        try (JdbcDataChannel channel = channel(db.source("jdbc:skillroy-fake://h/hang", "loginTimeout", "5s"))) {
            long start = System.nanoTime();
            DataException connect = assertThrows(DataException.class, () -> channel.query("db",
                    "SELECT 1 FROM person", List.of(), 1, Duration.ofMillis(300)));
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(DataException.Kind.TIMEOUT, connect.kind());
            assertTrue(connect.getMessage().contains("took longer than 300 ms"), connect.getMessage());
            assertTrue(ms >= 250 && ms < 4000, "bounded by the call's 300 ms, not the 5 s loginTimeout: " + ms);

            // A call without a timeout holds the source's connection slot for up to loginTimeout ...
            FakeDriver.hanging = new CountDownLatch(1);
            java.util.concurrent.CompletableFuture<Object> holder = java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return channel.query("db", "SELECT 1 FROM person", List.of(), 1, null);
                        } catch (Exception e) {
                            return e;
                        }
                    });
            assertTrue(FakeDriver.hanging.await(30, TimeUnit.SECONDS), "the first call is connecting");
            // ... and a call with a timeout waits for it at most that long.
            start = System.nanoTime();
            DataException waited = assertThrows(DataException.class, () -> channel.query("db",
                    "SELECT 1 FROM person", List.of(), 1, Duration.ofMillis(300)));
            ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(DataException.Kind.TIMEOUT, waited.kind());
            assertTrue(waited.getMessage().contains("another statement on JDBC source 'db' kept its connection for"
                    + " longer than 300 ms"), waited.getMessage());
            assertTrue(ms >= 250 && ms < 4000, "bounded by the call's 300 ms: " + ms);
            FakeDriver.release.countDown();
            assertTrue(holder.get(30, TimeUnit.SECONDS) instanceof Exception, "the first call ends too");
            assertTrue(FakeDriver.lateClosed.await(30, TimeUnit.SECONDS), "the late connections were closed");
        }
    }

    @Test
    void theValidationWaitFollowsTheCallsTimeout() {
        assertEquals(2, JdbcDataChannel.validationSeconds(null), "2 s without a timeout");
        assertEquals(1, JdbcDataChannel.validationSeconds(Duration.ofMillis(300)), "never 0, which means no limit");
        assertEquals(2, JdbcDataChannel.validationSeconds(Duration.ofMillis(1500)));
        assertEquals(2, JdbcDataChannel.validationSeconds(Duration.ofSeconds(60)));
    }

    @Test
    void nullFromConnectIsUnavailable() throws Exception {
        try (JdbcDataChannel channel = channel(db.source("jdbc:skillroy-fake://h/null"))) {
            DataException e = assertThrows(DataException.class, () -> channel.query("db", "SELECT 1 FROM person",
                    List.of(), 1, null));
            assertEquals(DataException.Kind.UNAVAILABLE, e.kind());
            assertTrue(e.getMessage().contains("Driver.connect returned null"), e.getMessage());
        }
    }

    @Test
    void aTimeoutWhileConnectingReportedByTheDriverIsATimeout() throws Exception {
        try (JdbcDataChannel channel = channel(db.source("jdbc:skillroy-fake://h/timeout"))) {
            DataException e = assertThrows(DataException.class, () -> channel.query("db", "SELECT 1 FROM person",
                    List.of(), 1, null));
            assertEquals(DataException.Kind.TIMEOUT, e.kind());
            assertEquals("HYT00", e.sqlState());
        }
    }

    @Test
    void noDriverIsUnavailable() throws Exception {
        for (Map<String, Object> source : List.of(db.source("jdbc:nobody-knows:x"),
                db.source(db.url, "driver", "org.example.NoSuchDriver"),
                db.source(db.url, "driver", String.class.getName()))) {
            try (JdbcDataChannel channel = channel(source)) {
                DataException e = assertThrows(DataException.class, () -> channel.query("db", "SELECT 1 FROM person",
                        List.of(), 1, null), source.toString());
                assertEquals(DataException.Kind.UNAVAILABLE, e.kind(), e.getMessage());
                assertSecretFree(e.getMessage());
            }
        }
    }

    @Test
    void aStatementTimeoutAndAnUnknownSource() throws Exception {
        try (JdbcDataChannel channel = channel(db.source(db.url, "statementTimeout", "1s"))) {
            assertTrue(channel.serves("db"));
            assertFalse(channel.serves("other"));
            assertFalse(channel.serves(null));
            assertEquals("jdbc", channel.kind("db"));
            DataException unknown = assertThrows(DataException.class, () -> channel.query("other", "SELECT 1",
                    List.of(), 1, null));
            assertEquals(DataException.Kind.UNKNOWN_SOURCE, unknown.kind());
            assertTrue(unknown.getMessage().contains("[db]"), unknown.getMessage());
            // Well-formed timeouts of any size are accepted (capped at 120 s); a zero one is a usage error.
            assertEquals(1, channel.query("db", "SELECT code FROM person WHERE code = ?", List.of("100"), 5,
                    Duration.ofMinutes(10)).rowCount());
            assertThrows(IllegalArgumentException.class, () -> channel.query("db", "SELECT 1 FROM person",
                    List.of(), 1, Duration.ZERO));
        }
    }

    @Test
    void theAccountIsTheBoundary() throws Exception {
        // A write that slipped past the guard would still be refused: the account may only select.
        try (Connection reader = db.reader()) {
            SQLException e = assertThrows(SQLException.class, () -> reader.createStatement()
                    .executeUpdate("DELETE FROM person"));
            assertEquals("42501", e.getSQLState());
        }
    }

    @Test
    void timeoutConversion() {
        assertEquals(1, Queries.seconds(Duration.ofMillis(1)));
        assertEquals(2, Queries.seconds(Duration.ofMillis(1500)));
        assertEquals(120, Queries.seconds(Duration.ofHours(1)));
        assertEquals(120000L, Queries.millis(Duration.ofHours(1)));
        assertEquals(1L, Queries.millis(Duration.ofNanos(10)));
    }
}
