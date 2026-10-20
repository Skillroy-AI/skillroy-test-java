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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.agent.api.Primitive;
import ai.skillroy.testjava.agent.api.Probe;
import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.spi.ActionFirer;
import ai.skillroy.testjava.agent.api.spi.AppStateProvider;
import ai.skillroy.testjava.agent.api.spi.DataSourceProvider;
import ai.skillroy.testjava.agent.api.spi.DeviceAdapter;
import ai.skillroy.testjava.agent.api.spi.DialogTextExtractor;
import ai.skillroy.testjava.agent.api.spi.LocatorResolver;
import ai.skillroy.testjava.agent.api.spi.CaptureMask;
import ai.skillroy.testjava.agent.api.spi.MaskPolicy;
import ai.skillroy.testjava.agent.api.spi.RegionResolver;
import ai.skillroy.testjava.agent.api.spi.NameEnricher;
import ai.skillroy.testjava.agent.api.spi.ReadinessSource;
import ai.skillroy.testjava.agent.api.spi.RedactionKeys;
import ai.skillroy.testjava.agent.api.spi.ThreadPolicyProvider;
import ai.skillroy.testjava.agent.api.spi.hook.ClassDeny;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.samples.lending.Engine;
import ai.skillroy.testjava.samples.lending.EngineStatus;
import ai.skillroy.testjava.samples.lending.LendingApp;
import ai.skillroy.testjava.samples.lending.LendingDbServer;
import ai.skillroy.testjava.samples.lending.Prompt;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent module {@value #ID}: what the agent needs to know about the sample lending desk that no generic module can
 * (contract section 9.2). It registers
 *
 * <ul>
 *   <li>a {@link ReadinessSource}: ready once the desk runs and its engine waits for input, which is also the
 *       moment the module's engine listener is attached (see {@link ListenerSlot}); a desk found asking a
 *       question is ready too, without the listener and so without {@code lending.input}, until the question is
 *       answered;</li>
 *   <li>a {@link ThreadPolicyProvider}: domain calls run on the daemon thread {@code skillroy-lending-worker}; the
 *       engine thread {@code LendingEngine} is forbidden;</li>
 *   <li>an {@link AppStateProvider} {@code lending} for {@code app.state};</li>
 *   <li>{@link NameEnricher}, {@link LocatorResolver} ({@code {action:"DUE"}}, with {@code describe}),
 *       {@link ActionFirer} and {@link DialogTextExtractor} for the desk's custom widgets ({@link LendingWidgets});</li>
 *   <li>a {@link RegionResolver} for the number pad's twelve keys ({@link LendingRegions}) and a
 *       {@link CaptureMask} for the entry field and the number pad while a secret is expected
 *       ({@link LendingCaptureMask}) (Phase 7);</li>
 *   <li>a {@link DataSourceProvider} {@code lending} and a {@link DeviceAdapter} {@code printer};</li>
 *   <li>a {@link MaskPolicy} that hides the {@code password} column of source {@code lending}
 *       ({@link PasswordMask});</li>
 *   <li>a {@link RedactionKeys} for the desk's own secret keys ({@code lending.pin}, see {@link LendingSecrets});</li>
 *   <li>the primitives {@code lending.input}, {@code lending.state}, {@code lending.loan}, {@code lending.prompt},
 *       {@code lending.prompt.answer}, {@code lending.slip}, {@code lending.access.check}, {@code lending.deposit},
 *       {@code lending.limits} and {@code lending.waiver.evaluate}, each with a capability of the same name, plus
 *       the capabilities {@code lending.sql} and {@code lending.loan.authorisations}, all probed against the running
 *       desk.</li>
 * </ul>
 *
 * <p>How the desk is found: in EMBEDDED mode the {@code LendingApp} the host passed to {@code EmbeddedAgent.start}
 * ({@code AgentContext.hostContext()}); in the other modes the application's {@code LendingApp.instance()}, looked at
 * only once the engine thread exists. {@link #install} touches nothing of the application and nothing of AWT.
 *
 * <p>The core calls the lifecycle methods one at a time. {@link #uninstall} makes the engine listener inert at once
 * and removes it as soon as the application allows, stops the worker and unregisters everything, its capabilities
 * included (it closes the handles their declarations returned, contract amendment A18; the core would withdraw them
 * after {@code uninstall} anyway, but the module does not rely on that).
 */
public final class LendingModule implements AgentModule {

    /** Module id; also the id of the domain pack and the primitive namespace. */
    public static final String ID = "lending";
    /**
     * Module version: change it whenever the module's primitives or SPIs change (the pack's bump rule).
     * {@code 0.2.0} (Phase 7): {@link LendingRegions}, {@link LendingCaptureMask} and {@code LendingWidgets.describe};
     * the surface also grew since Phase 1 without a bump (the hooks catalogue's primitives and the waiver reads).
     */
    public static final String VERSION = "0.2.0";

    /** Capability: SQL through {@code data.query} on source {@code lending}. */
    static final String CAP_SQL = "lending.sql";
    /** Capability: the loan's supervisor authorisations are reported by {@code lending.loan}. */
    static final String CAP_AUTHORISATIONS = "lending.loan.authorisations";
    /** How long {@link #uninstall} waits for a moment when the engine listener may be removed. */
    static final long DETACH_WAIT_MS = 3000L;

    private final List<AutoCloseable> registrations = new ArrayList<AutoCloseable>();
    private final List<String> primitiveNames = new ArrayList<String>();
    private volatile AgentContext ctx;
    private volatile LendingAccess access;
    private volatile ListenerSlot slot;
    private volatile LendingWorker worker;

    /** Public no-argument constructor for {@code ServiceLoader}. */
    public LendingModule() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public void install(AgentContext context) {
        ctx = context;
        // Phase 8 (phase8-contracts.md 6.5, 12.2, D573): the module's own class deny, only behind its agent option.
        LendingClassDeny classDeny = LendingClassDeny.fromOption(context.option(LendingClassDeny.OPTION));
        if (classDeny != null) {
            register(ClassDeny.class, classDeny);
        }
        access = new LendingAccess(context.hostContext());
        slot = new ListenerSlot(context);
        worker = new LendingWorker(access);
        LendingWidgets widgets = new LendingWidgets();
        register(ReadinessSource.class, new Readiness());
        register(ThreadPolicyProvider.class, worker);
        register(AppStateProvider.class, new AppState());
        register(NameEnricher.class, widgets);
        register(LocatorResolver.class, widgets);
        register(ActionFirer.class, widgets);
        register(DialogTextExtractor.class, widgets);
        register(DataSourceProvider.class, new LendingDataSource(access));
        register(MaskPolicy.class, new PasswordMask());
        register(DeviceAdapter.class, new PrinterDevice(access));
        register(RedactionKeys.class, new LendingSecrets());
        register(RegionResolver.class, new LendingRegions());
        register(CaptureMask.class, new LendingCaptureMask());
        for (Primitive p : new LendingPrimitives(context, access, slot).all()) {
            context.primitives().register(p);
            primitiveNames.add(p.spec().name());
        }
        for (String name : LendingPrimitives.NAMES) {
            declare(name, LendingPrimitives.INPUT.equals(name) ? new InputProbe() : new RunningProbe("snapshot"));
        }
        declare(CAP_AUTHORISATIONS, new RunningProbe("loan snapshot"));
        declare(CAP_SQL, new SqlProbe());
    }

    @Override
    public void onTargetReady(AgentContext context) {
        // The readiness source attached the engine listener at the first moment the engine allowed it, or found the
        // desk asking a question (the listener then follows the answer); nothing is left to do here but say so.
        EngineEvents events = slot == null ? null : slot.events();
        context.log().info("lending: target ready; engine listener " + (events != null ? "attached"
                : "not attached yet (a prompt is open)"));
    }

    @Override
    public void uninstall() {
        AgentContext context = ctx;
        try {
            ListenerSlot s = slot;
            if (s != null) {
                s.detach(DETACH_WAIT_MS);
            }
            LendingWorker w = worker;
            if (w != null) {
                w.shutdown();
            }
            synchronized (registrations) {
                for (AutoCloseable r : registrations) {
                    try {
                        r.close();
                    } catch (Exception e) {
                        if (context != null) {
                            context.log().warn("lending: unregistering an SPI or a capability failed: " + e);
                        }
                    }
                }
                registrations.clear();
            }
            if (context != null) {
                for (String name : primitiveNames) {
                    try {
                        context.primitives().unregister(name);
                    } catch (RuntimeException e) {
                        context.log().warn("lending: unregistering " + name + " failed: " + e);
                    }
                }
            }
            primitiveNames.clear();
        } catch (Throwable t) {
            if (context != null) {
                try {
                    context.log().warn("lending: uninstall failed: " + t, t);
                } catch (Throwable ignored) {
                    // uninstall never throws.
                }
            }
        }
    }

    private <T> void register(Class<T> type, T implementation) {
        keep(ctx.spi().register(type, implementation));
    }

    /** Declares a capability; {@link #uninstall} withdraws it through the returned handle. */
    private void declare(String id, Probe probe) {
        keep(ctx.capabilities().declare(id, probe));
    }

    private void keep(AutoCloseable handle) {
        if (handle == null) {
            return;
        }
        synchronized (registrations) {
            registrations.add(handle);
        }
    }

    /** For tests: the module's listener slot. */
    ListenerSlot slot() {
        return slot;
    }

    /** For tests: the module's desk lookup. */
    LendingAccess access() {
        return access;
    }

    // ------------------------------------------------------------------ SPIs

    /**
     * Ready when the desk runs and the engine listener could be attached (the engine waits for input), or when
     * the desk is asking a question: a prompt can stay open for ever, and only a ready agent may answer it
     * (answering is a mutating call). The listener is then attached as soon as the desk waits for input again.
     */
    private final class Readiness implements ReadinessSource {
        @Override
        public String id() {
            return "lending.engine";
        }

        @Override
        public boolean isReady() {
            LendingApp app = access.app();
            return app != null && (slot.tryAttach(app) || slot.promptBlocksAttach(app));
        }

        @Override
        public String describe() {
            LendingApp app = access.app();
            if (app == null) {
                return "waiting for the lending application to start its engine";
            }
            return app.engine().isRunning() ? "waiting for the desk engine to wait for input"
                    : "the desk engine is not running";
        }
    }

    /**
     * The {@code lending} section of {@code app.state}: the engine status, the open prompt, whether a loan is open,
     * the engine's listener count (which shows whether an agent left its listener behind), the seed, the port the
     * desk's database listener is bound to ({@code dbPort}, null without a listener: how a test learns the port the
     * desk picked for {@code lending.dbPort=0}; it changes from launch to launch, so it is no fingerprint component),
     * and the desk's business date ({@code today}, ISO-8601, from the engine's clock: the date {@code DUE} evaluates
     * the waiver rules on, which a resolver whose query depends on the date reads here; it changes every day, so it
     * is no fingerprint component either).
     */
    private final class AppState implements AppStateProvider {
        @Override
        public String id() {
            return ID;
        }

        @Override
        public Map<String, Object> appState() {
            LendingApp app = access.app();
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            if (app == null) {
                m.put("running", Boolean.FALSE);
                return m;
            }
            slot.tryAttach(app);
            Engine engine = app.engine();
            EngineStatus status = engine.status();
            m.put("running", Boolean.valueOf(engine.isRunning()));
            m.putAll(LendingJson.state(status));
            m.put("modalPrompt", LendingJson.prompt(app.prompts().current()));
            m.put("loanOpen", Boolean.valueOf(engine.currentLoan() != null));
            m.put("lastLoanId", engine.lastLoan() == null ? null : engine.lastLoan().id());
            m.put("slips", Long.valueOf(app.printer().count()));
            m.put("engineListeners", Long.valueOf(engine.listenerCount()));
            m.put("seed", app.seed() == null ? null : app.seed().name());
            // phase6-contracts.md 11.3: the desk's edition, the one key Phase 6 adds to the section.
            m.put("edition", app.edition() == null ? null : app.edition().label());
            LendingDbServer server = app.dbServer();
            m.put("dbPort", server == null ? null : Long.valueOf(server.port()));
            m.put("today", LendingWaivers.businessDate(app).toString());
            return m;
        }
    }

    /** OK while the desk runs. */
    private final class RunningProbe implements Probe {
        private final String strategy;

        RunningProbe(String strategy) {
            this.strategy = strategy;
        }

        @Override
        public ProbeResult run(AgentContext context) {
            LendingApp app = access.app();
            if (app == null) {
                return ProbeResult.unavailable("the lending application is not running");
            }
            if (!app.engine().isRunning()) {
                return ProbeResult.unavailable("the desk engine is not running");
            }
            return ProbeResult.ok(strategy);
        }
    }

    /** {@code lending.input} also needs the engine listener, which reports the acknowledgements. */
    private final class InputProbe implements Probe {
        @Override
        public ProbeResult run(AgentContext context) {
            ProbeResult running = new RunningProbe("inbox").run(context);
            if (running.state() != CapabilityState.OK) {
                return running;
            }
            LendingApp app = access.app();
            if (slot.tryAttach(app)) {
                return running;
            }
            Prompt open = app == null ? null : app.prompts().current();
            return ProbeResult.unavailable(open == null ? "the engine listener is not attached yet"
                    : "the desk has a prompt open (" + open.id() + ") and takes no input; the engine listener "
                    + "is attached once the prompt is answered (lending.prompt.answer)");
        }
    }

    /** Opens a read-only connection and runs a trivial query. */
    private final class SqlProbe implements Probe {
        @Override
        public ProbeResult run(AgentContext context) throws Exception {
            LendingApp app = access.app();
            if (app == null) {
                return ProbeResult.unavailable("the lending application is not running");
            }
            Connection c = app.db().openReadOnlyConnection();
            try {
                Statement st = c.createStatement();
                try {
                    ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM desk_setting");
                    rs.next();
                } finally {
                    st.close();
                }
            } finally {
                c.close();
            }
            return ProbeResult.ok("read-only JDBC");
        }
    }

    /** Unmodifiable view, for tests. */
    List<String> primitiveNames() {
        return Collections.unmodifiableList(primitiveNames);
    }
}
