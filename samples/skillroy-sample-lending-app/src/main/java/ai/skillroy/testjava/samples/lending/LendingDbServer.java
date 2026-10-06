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

package ai.skillroy.testjava.samples.lending;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.hsqldb.server.Server;
import org.hsqldb.server.ServerConstants;

/**
 * Serves the desk's in-memory database on a loopback port through HSQLDB's own network server
 * ({@link Server}), so that a reporting tool outside the desk's JVM can read it. Enabled by the system property
 * {@value LendingOptions#PROP_DB_PORT} ({@link LendingOptions#dbPort(int)}), together with the read-only account's
 * password {@value LendingOptions#PROP_DB_PASSWORD}; without the port the desk opens no port.
 *
 * <p>What a client gets: the URL {@link #url()} ({@code jdbc:hsqldb:hsql://127.0.0.1:<port>/<database name>},
 * normally {@code …/lending}), the account {@value LendingDb#READER_USER} with the password given to {@link #start}
 * (no password is built in), which may read every table and change nothing, and the same data the desk sees (MVCC:
 * committed rows only). Only a database made by {@link LendingDb#createServable} is served: its administrator has a
 * random name and password that never leave the desk's JVM, so the administrator cannot be used, or taken over,
 * through the port.
 *
 * <p>Why loopback only: the listener is a convenience of the sample for tests on the same host, not a service. It
 * binds {@value #ADDRESS} and nothing else, accepts at most {@value #MAX_CONNECTIONS} connections, runs on daemon
 * threads, writes no log and never exits the JVM.
 *
 * <p>Thread-safe. {@link #close()} stops the listener and leaves the database open; {@link LendingApp#shutdown()}
 * closes it before the database.
 */
public final class LendingDbServer implements AutoCloseable {

    /** The only address the listener binds. */
    public static final String ADDRESS = "127.0.0.1";
    /** Most client connections served at the same time. */
    public static final int MAX_CONNECTIONS = 16;
    /** JDBC URL prefix of the listener; the URL is this plus {@code <port>/<database name>}. */
    public static final String URL_PREFIX = "jdbc:hsqldb:hsql://" + ADDRESS + ":";

    private static final Logger LOG = Logger.getLogger(LendingDbServer.class.getName());
    /** How long {@link #close()} waits for the server thread to finish. */
    private static final long STOP_WAIT_MS = 5000L;

    private final Server server;
    private final String databaseName;
    private final int port;

    private LendingDbServer(Server server, String databaseName, int port) {
        this.server = server;
        this.databaseName = databaseName;
        this.port = port;
    }

    /**
     * Creates the read-only account and opens the listener on the calling thread (it waits until the listener is
     * open or has failed, typically a few hundred milliseconds).
     *
     * @param db the desk's database, made by {@link LendingDb#createServable}; it stays owned by the caller
     * @param port the port, or 0 for any free port ({@link #port()} then tells which)
     * @param readerPassword the password of the account {@value LendingDb#READER_USER}
     *     ({@link LendingDb#createReadOnlyUser(String)}); never logged
     * @throws IllegalArgumentException for a database made by {@link LendingDb#create}, whose administrator is
     *     {@code SA} without a password, a port outside 0-65535, or a null or blank password; nothing is changed then
     * @throws SQLException if the account cannot be created or the port cannot be opened (for example because it is
     *     in use); nothing is left listening then
     */
    public static LendingDbServer start(LendingDb db, int port, String readerPassword) throws SQLException {
        Objects.requireNonNull(db, "db");
        if (!db.isServable()) {
            throw new IllegalArgumentException("only a database made by LendingDb.createServable may be served on a"
                    + " port: the administrator of " + db.name() + " is SA without a password");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535: " + port);
        }
        if (readerPassword == null || readerPassword.trim().isEmpty()) {
            throw new IllegalArgumentException("the read-only account " + LendingDb.READER_USER + " needs a password");
        }
        db.createReadOnlyUser(readerPassword);
        Properties props = new Properties();
        props.setProperty("server.address", ADDRESS);
        props.setProperty("server.port", Integer.toString(port));
        props.setProperty("server.database.0", "mem:" + db.name());
        props.setProperty("server.dbname.0", db.name());
        props.setProperty("server.silent", "true");
        props.setProperty("server.trace", "false");
        props.setProperty("server.no_system_exit", "true");
        props.setProperty("server.restart_on_shutdown", "false");
        props.setProperty("server.remote_open", "false");
        props.setProperty("server.tls", "false");
        props.setProperty("server.daemon", "true");
        props.setProperty("server.maxconnections", Integer.toString(MAX_CONNECTIONS));
        Server server = new Server();
        server.setLogWriter(null);
        server.setErrWriter(null);
        try {
            server.setProperties(props);
        } catch (Exception e) {
            throw new SQLException("the database listener could not be configured: " + e, e);
        }
        server.start();
        if (server.getState() != ServerConstants.SERVER_STATE_ONLINE) {
            Throwable error = server.getServerError();
            stopQuietly(server);
            throw new SQLException("could not serve the lending database on " + ADDRESS + ":" + port + ": "
                    + (error != null ? error : server.getStateDescriptor()), error);
        }
        return new LendingDbServer(server, db.name(), server.getLocalPort());
    }

    /** @return the port the listener is bound to */
    public int port() {
        return port;
    }

    /** @return {@value #ADDRESS} */
    public String address() {
        return ADDRESS;
    }

    /** @return the JDBC URL a client connects to, for example {@code jdbc:hsqldb:hsql://127.0.0.1:9101/lending} */
    public String url() {
        return URL_PREFIX + port + "/" + databaseName;
    }

    /** @return true while the listener accepts connections */
    public boolean isRunning() {
        return server.getState() == ServerConstants.SERVER_STATE_ONLINE;
    }

    /**
     * Stops the listener and closes its client connections; the database stays open for the desk. Waits (at most a
     * few seconds) for the listener's thread to finish. Idempotent.
     */
    @Override
    public void close() {
        stopQuietly(server);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_WAIT_MS);
        try {
            while (server.getState() != ServerConstants.SERVER_STATE_SHUTDOWN && System.nanoTime() - deadline < 0) {
                Thread.sleep(10L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void stopQuietly(Server server) {
        try {
            if (server.getState() != ServerConstants.SERVER_STATE_SHUTDOWN) {
                server.stop();
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "stopping the database listener failed", e);
        }
    }

    @Override
    public String toString() {
        return "LendingDbServer{" + ADDRESS + ":" + port + "/" + databaseName + "}";
    }
}
