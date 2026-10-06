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

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * A test {@code java.sql.Driver}, registered through {@code META-INF/services/java.sql.Driver} of the test class path,
 * for URLs {@code jdbc:skillroy-fake:...}. {@code jdbc:skillroy-fake:hsqldb:<rest>} connects to
 * {@code jdbc:hsqldb:<rest>} (and counts); a URL ending in {@code /null} makes {@code connect} return null, one ending
 * in {@code /hang} counts {@link #hanging} down, blocks until {@link #release} is counted down and then returns a
 * connection that records its closing, {@code /sqlerror} throws an {@code SQLException} whose message holds the
 * password and the URL, and {@code /timeout} throws an {@code SQLTimeoutException}.
 */
public final class FakeDriver implements Driver {

    static final String PREFIX = "jdbc:skillroy-fake:";
    static final AtomicInteger CONNECTS = new AtomicInteger();
    static volatile Connection last;
    static volatile CountDownLatch release = new CountDownLatch(1);
    static volatile CountDownLatch lateClosed = new CountDownLatch(1);
    /** Counted down when a connect starts to hang. */
    static volatile CountDownLatch hanging = new CountDownLatch(1);

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        String rest = url.substring(PREFIX.length());
        if (rest.startsWith("hsqldb:")) {
            CONNECTS.incrementAndGet();
            Connection connection = new org.hsqldb.jdbc.JDBCDriver().connect("jdbc:" + rest, info);
            last = connection;
            return connection;
        }
        if (rest.endsWith("/null")) {
            return null;
        }
        if (rest.endsWith("/sqlerror")) {
            throw new SQLException("login failed for " + info.getProperty("user") + " with password "
                    + info.getProperty("password") + " at " + url, "28000", 4711);
        }
        if (rest.endsWith("/timeout")) {
            throw new SQLTimeoutException("login timed out at " + url, "HYT00", 1);
        }
        if (rest.endsWith("/hang")) {
            hanging.countDown();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            CountDownLatch closed = lateClosed;
            return (Connection) Proxy.newProxyInstance(FakeDriver.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("close")) {
                            closed.countDown();
                        }
                        return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                    });
        }
        throw new SQLException("unknown fake mode", "08001", 1);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
