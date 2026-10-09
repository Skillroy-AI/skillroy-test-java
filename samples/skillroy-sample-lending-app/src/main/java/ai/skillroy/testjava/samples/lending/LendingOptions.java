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

import java.awt.GraphicsEnvironment;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Start-up options of the desk. {@link #fromSystemProperties()} reads the documented system properties; the
 * other settings exist so that tests and embedders can run several desks in one JVM. A mutable builder: set it
 * up on one thread, then hand it to {@link LendingApp#launch}.
 *
 * <table border="1">
 *   <caption>System properties</caption>
 *   <tr><th>Property</th><th>Default</th><th>Meaning</th></tr>
 *   <tr><td>{@value #PROP_SEED}</td><td>A</td><td>which seeded environment to create, {@code A} or {@code B}</td></tr>
 *   <tr><td>{@value #PROP_EDITION}</td><td>standard</td><td>which edition of the desk to run, {@code standard} or
 *       {@code branch} ({@link Edition}; phase6-contracts.md 11.3)</td></tr>
 *   <tr><td>{@value #PROP_PLUGINS}</td><td>none</td><td>comma-separated {@link LendingPlugin} class names</td></tr>
 *   <tr><td>{@value #PROP_IDLE_LOCK}</td><td>0</td><td>idle seconds before the desk locks; 0 = never</td></tr>
 *   <tr><td>{@value #PROP_TITLE}</td><td>{@value LendingFrame#DEFAULT_TITLE}</td><td>main window title</td></tr>
 *   <tr><td>{@value #PROP_DB_PORT}</td><td>none</td><td>also serve the database on
 *       {@code 127.0.0.1:<port>} to the read-only account {@value LendingDb#READER_USER} (see
 *       {@link LendingDbServer}); 0 = any free port, which {@link LendingApp#dbServer()} reports. Needs
 *       {@value #PROP_DB_PASSWORD}</td></tr>
 *   <tr><td>{@value #PROP_DB_PASSWORD}</td><td>none</td><td>the password of the read-only account
 *       {@value LendingDb#READER_USER}: required when {@value #PROP_DB_PORT} is set (the desk refuses to start
 *       without it, naming this property), not used otherwise. A secret: the desk never logs it</td></tr>
 * </table>
 */
public final class LendingOptions {

    public static final String PROP_SEED = "lending.seed";
    /** The desk's edition, {@code standard} (the default) or {@code branch} ({@link Edition}). */
    public static final String PROP_EDITION = "lending.edition";
    public static final String PROP_PLUGINS = "lending.plugins";
    public static final String PROP_IDLE_LOCK = "lending.idleLockSeconds";
    public static final String PROP_TITLE = "lending.title";
    public static final String PROP_DB_PORT = "lending.dbPort";
    public static final String PROP_DB_PASSWORD = "lending.dbPassword";
    /** {@link #dbPort()} when the database is not served on a port (the default). */
    public static final int NO_DB_PORT = -1;

    private Seed seed = Seed.A;
    private Edition edition = Edition.STANDARD;
    private List<String> plugins = Collections.emptyList();
    private long idleLockMillis;
    private String title = LendingFrame.DEFAULT_TITLE;
    private String dbName = LendingDb.DEFAULT_NAME;
    private int dbPort = NO_DB_PORT;
    private String dbPassword;
    private Boolean ui;
    private Clock clock = Clock.systemDefaultZone();

    /** @return the defaults (seed A, no plugins, no idle lock, default title, database {@code lending}) */
    public static LendingOptions defaults() {
        return new LendingOptions();
    }

    /** @return options from {@link System#getProperties()} */
    public static LendingOptions fromSystemProperties() {
        return fromProperties(System.getProperties());
    }

    /**
     * @return options from the given properties ({@value #PROP_SEED}, {@value #PROP_EDITION}, {@value #PROP_PLUGINS},
     *     {@value #PROP_IDLE_LOCK}, {@value #PROP_TITLE}, {@value #PROP_DB_PORT}, {@value #PROP_DB_PASSWORD})
     * @throws IllegalArgumentException for an unknown seed or edition, a malformed idle-lock value or a port that is
     *     not a whole number between 0 and 65535 (a port without a password is refused by {@link LendingApp#launch})
     */
    public static LendingOptions fromProperties(Properties props) {
        LendingOptions o = new LendingOptions();
        o.seed(Seed.parse(props.getProperty(PROP_SEED)));
        o.edition(Edition.parse(props.getProperty(PROP_EDITION)));
        String plugins = props.getProperty(PROP_PLUGINS);
        if (plugins != null) {
            List<String> names = new ArrayList<String>();
            for (String name : plugins.split(",")) {
                if (!name.trim().isEmpty()) {
                    names.add(name.trim());
                }
            }
            o.plugins(names);
        }
        String idle = props.getProperty(PROP_IDLE_LOCK);
        if (idle != null && !idle.trim().isEmpty()) {
            long seconds;
            try {
                seconds = Long.parseLong(idle.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(PROP_IDLE_LOCK + " must be a whole number of seconds: " + idle);
            }
            o.idleLockSeconds(seconds);
        }
        String title = props.getProperty(PROP_TITLE);
        if (title != null && !title.trim().isEmpty()) {
            o.title(title);
        }
        String port = props.getProperty(PROP_DB_PORT);
        if (port != null && !port.trim().isEmpty()) {
            int value;
            try {
                value = Integer.parseInt(port.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(PROP_DB_PORT + " must be a port number: " + port);
            }
            if (value < 0) {
                throw new IllegalArgumentException(PROP_DB_PORT + " must be between 0 and 65535: " + port);
            }
            o.dbPort(value);
        }
        o.dbPassword(props.getProperty(PROP_DB_PASSWORD));
        return o;
    }

    public LendingOptions seed(Seed value) {
        this.seed = Objects.requireNonNull(value, "seed");
        return this;
    }

    /** @param value the desk's edition (phase6-contracts.md 11.3) */
    public LendingOptions edition(Edition value) {
        this.edition = Objects.requireNonNull(value, "edition");
        return this;
    }

    public LendingOptions plugins(List<String> classNames) {
        this.plugins = Collections.unmodifiableList(new ArrayList<String>(classNames));
        return this;
    }

    /** @param seconds idle seconds before the desk locks; 0 = never. A huge value saturates, it never wraps. */
    public LendingOptions idleLockSeconds(long seconds) {
        return idleLockMillis(TimeUnit.SECONDS.toMillis(seconds));
    }

    /** @param millis idle milliseconds before the desk locks; 0 = never */
    public LendingOptions idleLockMillis(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("idle lock must not be negative");
        }
        this.idleLockMillis = millis;
        return this;
    }

    public LendingOptions title(String value) {
        this.title = Objects.requireNonNull(value, "title");
        return this;
    }

    /** @param name in-memory database name; tests use distinct names to run several desks in one JVM */
    public LendingOptions dbName(String name) {
        this.dbName = Objects.requireNonNull(name, "dbName");
        return this;
    }

    /**
     * @param port the loopback port on which the desk also serves its database to the read-only account
     *     ({@link LendingDbServer}), 0 for any free port, or {@link #NO_DB_PORT} for no listener (the default); a
     *     listener needs {@link #dbPassword(String)}
     */
    public LendingOptions dbPort(int port) {
        if (port != NO_DB_PORT && (port < 0 || port > 65535)) {
            throw new IllegalArgumentException(PROP_DB_PORT + " must be between 0 and 65535: " + port);
        }
        this.dbPort = port;
        return this;
    }

    /**
     * @param password the password of the read-only account {@value LendingDb#READER_USER}, required when the desk
     *     serves its database ({@link #dbPort(int)}); taken as given, not trimmed. Null, empty or blank means none.
     */
    public LendingOptions dbPassword(String password) {
        this.dbPassword = password == null || password.trim().isEmpty() ? null : password;
        return this;
    }

    /** @param show true to show the Swing window, false for none; unset = show unless the JVM is headless */
    public LendingOptions ui(boolean show) {
        this.ui = show;
        return this;
    }

    public LendingOptions clock(Clock value) {
        this.clock = Objects.requireNonNull(value, "clock");
        return this;
    }

    public Seed seed() {
        return seed;
    }

    /** @return the desk's edition; {@link Edition#STANDARD} unless set */
    public Edition edition() {
        return edition;
    }

    public List<String> plugins() {
        return plugins;
    }

    public long idleLockMillis() {
        return idleLockMillis;
    }

    public String title() {
        return title;
    }

    public String dbName() {
        return dbName;
    }

    /** @return the database listener's port, 0 for any free port, or {@link #NO_DB_PORT} for none */
    public int dbPort() {
        return dbPort;
    }

    /** @return the read-only account's password, or null when none was given (never logged) */
    public String dbPassword() {
        return dbPassword;
    }

    /** @return whether the window will be shown: as set, else true unless the JVM is headless */
    public boolean ui() {
        return ui != null ? ui : !GraphicsEnvironment.isHeadless();
    }

    public Clock clock() {
        return clock;
    }
}
