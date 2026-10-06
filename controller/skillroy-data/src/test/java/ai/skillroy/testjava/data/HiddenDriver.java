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

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * A driver that is registered nowhere: no service entry, never handed to {@code DriverManager}. The test loads its own
 * copy through a child class loader (as a {@code --pack} jar would), names it with {@code driver:} and checks that the
 * channel used that copy. {@code jdbc:skillroy-hidden:<rest>} connects to {@code jdbc:hsqldb:<rest>}.
 */
public final class HiddenDriver implements Driver {

    static final String PREFIX = "jdbc:skillroy-hidden:";

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        DriverLog.LOADERS.add(getClass().getClassLoader());
        return new org.hsqldb.jdbc.JDBCDriver().connect("jdbc:" + url.substring(PREFIX.length()), info);
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
