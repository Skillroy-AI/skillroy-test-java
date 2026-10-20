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

import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The desk application: {@code main}, the wiring of database, services, engine and window, and the plugin
 * mechanism ({@link LendingPlugin}).
 *
 * <p>Start-up order ({@link #launch}): refuse a listener without the read-only account's password
 * ({@link LendingOptions#dbPassword()}) before anything is created; create and seed the database (servable, see
 * {@link LendingDb#createServable}, when a listener is wanted), load staff, catalogue and waiver rules, create the
 * printer, prompt service and engine, open the database listener when {@link LendingOptions#dbPort()} asks for one
 * ({@link LendingDbServer}), create and show the window on the event dispatch thread (unless headless) and, with
 * {@link LendingOptions#tools()}, the "Desk tools" window ({@link DeskTools}) after it, start the
 * engine thread, publish {@link #instance()}, then start the plugins. Without a window the desk still runs; prompts
 * are then answered only through {@link PromptService#answer}.
 *
 * <p>Accessors are safe from any thread. {@link #shutdown()} stops plugins, engine, window, database listener and
 * database.
 */
public final class LendingApp {

    private static final Logger LOG = Logger.getLogger(LendingApp.class.getName());
    private static volatile LendingApp instance;

    private final LendingOptions options;
    private final LendingDb db;
    private final AccessControl access;
    private final Catalogue catalogue;
    private final WaiverEngine waiverRules;
    private final SlipPrinter printer;
    private final PromptService prompts;
    private final Engine engine;
    private volatile LendingFrame frame;
    private volatile DeskTools tools;
    private volatile LendingDbServer dbServer;
    private final List<LendingPlugin> plugins = new ArrayList<LendingPlugin>();
    private final AtomicBoolean pluginsStopped = new AtomicBoolean();
    private final AtomicBoolean shutDown = new AtomicBoolean();
    private final Thread shutdownHook;

    private LendingApp(LendingOptions options, LendingDb db, AccessControl access, Catalogue catalogue,
                       WaiverEngine waiverRules, SlipPrinter printer, PromptService prompts, Engine engine) {
        this.options = options;
        this.db = db;
        this.access = access;
        this.catalogue = catalogue;
        this.waiverRules = waiverRules;
        this.printer = printer;
        this.prompts = prompts;
        this.engine = engine;
        this.shutdownHook = new Thread(this::stopPlugins, "LendingShutdown");
    }

    /**
     * Starts the desk with options from the system properties ({@link LendingOptions}).
     */
    public static void main(String[] args) throws Exception {
        launch(LendingOptions.fromSystemProperties());
    }

    /**
     * Starts a desk. The most recently launched desk becomes {@link #instance()}.
     *
     * @return the running application
     * @throws IllegalArgumentException when {@link LendingOptions#dbPort()} asks for a listener and no
     *     {@link LendingOptions#dbPassword()} is given; nothing is created then (no database, no listener, no window)
     */
    public static LendingApp launch(LendingOptions options) throws Exception {
        boolean serve = options.dbPort() != LendingOptions.NO_DB_PORT;
        if (serve && options.dbPassword() == null) {
            throw new IllegalArgumentException(LendingOptions.PROP_DB_PASSWORD + " is required when "
                    + LendingOptions.PROP_DB_PORT + " is set: it is the password of the read-only account "
                    + LendingDb.READER_USER);
        }
        LocalDate today = LocalDate.now(options.clock());
        LendingDb db = serve ? LendingDb.createServable(options.dbName(), options.seed(), options.edition(), today)
                : LendingDb.create(options.dbName(), options.seed(), options.edition(), today);
        LendingApp app;
        try {
            AccessControl access = AccessControl.load(db.connection(), options.edition());
            Catalogue catalogue = Catalogue.load(db.connection());
            WaiverEngine waiverRules = WaiverEngine.load(db.connection());
            db.connection().commit();
            Map<String, String> settings = db.settings();
            SlipPrinter printer = new SlipPrinter(settings.get("slip_header"));
            PromptService prompts = new PromptService();
            Engine engine = new Engine(db, catalogue, access, waiverRules, prompts, printer, options.clock(),
                options.idleLockMillis(), options.edition());
            // phase8-contracts.md 12.1 (D535): the idle callback's tick, lending.idleTickMillis.
            engine.idleTickMillis(options.idleTickMillis());
            app = new LendingApp(options, db, access, catalogue, waiverRules, printer, prompts, engine);
        } catch (Exception e) {
            db.close();
            throw e;
        }
        try {
            if (serve) {
                app.dbServer = LendingDbServer.start(db, options.dbPort(), options.dbPassword());
                LOG.info("serving the database read-only on " + app.dbServer.url() + " (user "
                        + LendingDb.READER_USER + ")");
            }
            if (options.ui()) {
                app.createWindow();
                if (options.tools()) {
                    app.createTools(); // Phase 8 (phase8-contracts.md 12.1): the "Desk tools" window, after the frame
                }
            }
            app.engine.start();
        } catch (Exception e) {
            app.shutdown();
            throw e;
        }
        instance = app;
        Runtime.getRuntime().addShutdownHook(app.shutdownHook);
        app.startPlugins();
        return app;
    }

    private void createWindow() throws InterruptedException, InvocationTargetException {
        Runnable create = () -> {
            LendingFrame f = new LendingFrame(options.title(), engine, printer);
            prompts.setView(new SwingPromptView(f));
            f.setVisible(true);
            frame = f;
        };
        if (EventQueue.isDispatchThread()) {
            create.run();
        } else {
            EventQueue.invokeAndWait(create);
        }
    }

    /** Opens the "Desk tools" window on the event dispatch thread; it never takes the focus itself. */
    private void createTools() throws InterruptedException, InvocationTargetException {
        Runnable create = () -> {
            DeskTools t = new DeskTools();
            t.setVisible(true);
            tools = t;
        };
        if (EventQueue.isDispatchThread()) {
            create.run();
        } else {
            EventQueue.invokeAndWait(create);
        }
    }

    private void startPlugins() {
        ClassLoader loader = LendingApp.class.getClassLoader();
        for (String name : options.plugins()) {
            try {
                Class<?> type = Class.forName(name, true, loader);
                LendingPlugin plugin = (LendingPlugin) type.getDeclaredConstructor().newInstance();
                plugin.start(this);
                synchronized (plugins) {
                    plugins.add(plugin);
                }
                LOG.info("started plugin " + name);
            } catch (Exception | LinkageError e) {
                LOG.log(Level.WARNING, "plugin " + name + " failed to start", e);
            }
        }
    }

    private void stopPlugins() {
        if (!pluginsStopped.compareAndSet(false, true)) {
            return;
        }
        List<LendingPlugin> list;
        synchronized (plugins) {
            list = new ArrayList<LendingPlugin>(plugins);
        }
        Collections.reverse(list);
        for (LendingPlugin plugin : list) {
            try {
                plugin.stop();
            } catch (RuntimeException | LinkageError e) {
                LOG.log(Level.WARNING, "plugin " + plugin.getClass().getName() + " failed to stop", e);
            }
        }
    }

    /**
     * Stops the plugins (reverse order), the engine, the window, the database listener and the database.
     * Idempotent. Call it from any thread except the engine thread.
     */
    public void shutdown() {
        if (!shutDown.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException | IllegalArgumentException e) {
            LOG.log(Level.FINE, "shutdown hook not removed", e);
        }
        stopPlugins();
        prompts.setView(null);
        engine.stop();
        final DeskTools t = tools;
        if (t != null) {
            Runnable dispose = t::dispose;
            if (EventQueue.isDispatchThread()) {
                dispose.run();
            } else {
                try {
                    EventQueue.invokeAndWait(dispose);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (InvocationTargetException e) {
                    LOG.log(Level.WARNING, "closing the desk tools failed", e.getCause());
                }
            }
            tools = null;
        }
        final LendingFrame f = frame;
        if (f != null) {
            Runnable close = () -> {
                f.detach();
                f.dispose();
            };
            if (EventQueue.isDispatchThread()) {
                close.run();
            } else {
                try {
                    EventQueue.invokeAndWait(close);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (InvocationTargetException e) {
                    LOG.log(Level.WARNING, "closing the window failed", e.getCause());
                }
            }
            frame = null;
        }
        LendingDbServer server = dbServer;
        if (server != null) {
            server.close();
        }
        db.close();
        if (instance == this) {
            instance = null;
        }
    }

    /** @return the running desk, or null before start-up completed (and after its shutdown) */
    public static LendingApp instance() {
        return instance;
    }

    public LendingOptions options() {
        return options;
    }

    public Seed seed() {
        return options.seed();
    }

    /** @return the desk's edition (phase6-contracts.md 11.3) */
    public Edition edition() {
        return options.edition();
    }

    public String title() {
        return options.title();
    }

    public LendingDb db() {
        return db;
    }

    /** @return the database listener, or null when {@link LendingOptions#dbPort()} asked for none */
    public LendingDbServer dbServer() {
        return dbServer;
    }

    public AccessControl access() {
        return access;
    }

    public Catalogue catalogue() {
        return catalogue;
    }

    public WaiverEngine waiverRules() {
        return waiverRules;
    }

    public SlipPrinter printer() {
        return printer;
    }

    public PromptService prompts() {
        return prompts;
    }

    public Engine engine() {
        return engine;
    }

    /** @return the engine's inbox, where every input source posts */
    public Inbox inbox() {
        return engine.inbox();
    }

    /** @return the main window, or null when running without one */
    public LendingFrame frame() {
        return frame;
    }

    /**
     * @return the "Desk tools" window ({@link LendingOptions#PROP_TOOLS}), or null when it was not asked for or the
     *     desk runs without a window
     */
    public DeskTools tools() {
        return tools;
    }

    /** @return the started plugins, in start order */
    public List<LendingPlugin> plugins() {
        synchronized (plugins) {
            return Collections.unmodifiableList(new ArrayList<LendingPlugin>(plugins));
        }
    }
}
