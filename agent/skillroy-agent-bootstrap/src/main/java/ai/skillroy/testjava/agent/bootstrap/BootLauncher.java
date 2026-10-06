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

package ai.skillroy.testjava.agent.bootstrap;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The shared start/stop logic behind {@link AgentBootstrap} and {@link EmbeddedAgent}: option resolution, the
 * main-class guard and enable rule, the agent class loader, and the reflective call into the core's
 * {@code AgentRuntime}. Holds the one running agent of this bootstrap copy so that a repeated load is idempotent.
 *
 * <p>All entry points are serialised on one lock and never throw: a failure is recorded in the system property
 * {@code skillroy.agent.error} and reported as one {@code [skillroy]} line on {@code System.err}.
 *
 * <p>No entry point waits without bound (contract amendment A6). A call that finds another thread's start or stop
 * in progress waits for it at most {@link #LOCK_WAIT_MS}. A call that cannot wait answers at once: one made on the
 * thread that is already starting or stopping the agent (host code that runs during the start), or on one of the
 * agent's own threads while a start or stop is in progress (a module's {@code install} runs on {@code skillroy-start}
 * while the host's start call waits for it). Such a start answers true (an agent is starting: nothing changes); such
 * a stop during a start is carried out as soon as the start has finished. A stop on the event-dispatch thread or on
 * a thread the application forbids never waits for the lock either (finding S33): when another start or stop is in
 * progress it is handed to a {@code skillroy-boot-stop} thread and carried out after that operation.
 *
 * <p>The running agent is held in one atomically swapped {@link Held} (finding S31): the runtime's close hook clears
 * it, compare-and-set, as soon as the agent has finished closing however it closed ({@code agent.disable}, a
 * production marker), so that a closed agent's loaders, modules, event ring and host context become collectable
 * without a further call into the bootstrap. Every reader takes one snapshot of the holder.
 */
final class BootLauncher {

    static final String RUNTIME_CLASS = "ai.skillroy.testjava.agent.core.AgentRuntime";
    static final String PROP_ERROR = BootOptions.PREFIX + "error";
    /**
     * Set by a running agent and cleared when it stops; unlike {@code port} it is never an option, so its presence
     * means an agent loaded through another copy of the bootstrap is serving this JVM.
     */
    static final String PROP_RUNNING = BootOptions.PREFIX + "loadMode";

    /** The core's {@code AgentRuntime.START_TIMEOUT_MS}: the bound of one start, module installs included. */
    static final long START_TIMEOUT_MS = 60000L;
    /** How long an entry point waits for another thread's start or stop: one start plus a margin for its cleanup. */
    static final long LOCK_WAIT_MS = START_TIMEOUT_MS + 5000L;

    /** Test seam: {@link #LOCK_WAIT_MS}, shortened by tests of the bound. */
    static volatile long lockWaitMs = LOCK_WAIT_MS;

    /** Name prefix of AWT event-dispatch threads (tested by name only: the bootstrap never loads AWT). */
    static final String EDT_PREFIX = "AWT-EventQueue";

    private static final ReentrantLock LOCK = new ReentrantLock();
    /** The running agent of this bootstrap copy and its loader; null when none. */
    private static final AtomicReference<Held> HELD = new AtomicReference<Held>();
    /** Counted up by every stop, so that an asynchronous start requested before a stop does not start after it. */
    private static final AtomicLong STOPS = new AtomicLong();
    /** True while the lock holder is starting an agent. */
    private static volatile boolean starting;
    /** True while the lock holder is stopping an agent. */
    private static volatile boolean stopping;
    /** Set by a stop that arrived re-entrantly during a start; the start honours it when it has finished. */
    private static volatile boolean stopRequested;

    /** Test seam: the process environment; null means {@link System#getenv()}. */
    static volatile Map<String, String> environment;

    private BootLauncher() {
    }

    /** One started agent: the runtime object and the loader it was loaded from. */
    static final class Held {
        final Object runtime;
        final URLClassLoader loader;
        /** True when the runtime releases {@link #loader} itself, at the very end of its shutdown. */
        volatile boolean loaderReleasedByRuntime;

        Held(Object runtime, URLClassLoader loader) {
            this.runtime = runtime;
            this.loader = loader;
        }
    }

    /** True while this bootstrap copy holds an agent (closed or not); for tests of finding S31. */
    static boolean holdsAgent() {
        return HELD.get() != null;
    }

    /**
     * Loads the agent in the given mode, or re-publishes discovery if this bootstrap already runs one.
     *
     * @param argString the agent argument string (JAVAAGENT, ATTACH), else null
     * @param embedded the host's options (EMBEDDED), else null
     * @return true if an agent is running afterwards (started now or before), false if it refused or failed
     */
    static boolean load(String mode, String argString, Properties embedded, Instrumentation inst,
                        ClassLoader hostLoader, Object hostContext) {
        boolean locked = false;
        try {
            if (cannotWait()) {
                // Re-entrant: the start or stop in progress is what this call would wait for.
                return runningOrStarting();
            }
            locked = LOCK.tryLock(lockWaitMs, TimeUnit.MILLISECONDS);
            if (!locked) {
                // Another start or stop overran its bound; report what is there rather than wait any longer.
                return runningOrStarting();
            }
            return loadLocked(mode, argString, embedded, inst, hostLoader, hostContext);
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();   // the host's interrupt, not ours to swallow
            }
            fail(mode + " start failed", t);
            return false;
        } finally {
            if (locked) {
                starting = false;
                LOCK.unlock();
            }
        }
    }

    /**
     * EMBEDDED start that returns at once (contract amendment A22): the start runs on a daemon {@code skillroy-start}
     * thread with the host loader as its context loader. Same enable and idempotence rules as {@link #load}.
     *
     * @return true when a start was initiated, one is in progress or an agent is already running; false when it is
     *     refused outright (not enabled) or the thread cannot be created
     */
    static boolean loadAsync(Properties embedded, final ClassLoader hostLoader, final Object hostContext) {
        try {
            final Properties options = copy(embedded);
            Map<String, String> args = BootOptions.fromProperties(options);
            Map<String, String> env = environment != null ? environment : System.getenv();
            if ("stop".equalsIgnoreCase(args.get("cmd"))
                    || (!BootOptions.enabledByEnvironment(System.getProperties(), env)
                    && !BootOptions.isTrue(args.get("enabled")))) {
                return false;
            }
            if (starting || isRunning() || System.getProperty(PROP_RUNNING) != null) {
                return true;
            }
            // Cleared now, so that a caller polling for the outcome never sees an earlier failure.
            System.clearProperty(PROP_ERROR);
            final long stopsBefore = STOPS.get();
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loadWaiting(options, hostLoader, hostContext, stopsBefore);
                }
            }, "skillroy-start");
            thread.setDaemon(true);
            if (hostLoader != null) {
                thread.setContextClassLoader(hostLoader);
            }
            thread.start();
            return true;
        } catch (Throwable t) {
            fail("EMBEDDED start failed", t);
            return false;
        }
    }

    /**
     * The body of an asynchronous start: waits for the lock like any host thread (deliberately not through
     * {@link #cannotWait()}, whose agent-thread rule would turn it into a no-op) and skips the start if a stop came
     * after the request.
     */
    private static void loadWaiting(Properties embedded, ClassLoader hostLoader, Object hostContext,
                                    long stopsBefore) {
        boolean locked = false;
        try {
            locked = LOCK.tryLock(lockWaitMs, TimeUnit.MILLISECONDS);
            if (!locked) {
                throw new IllegalStateException("another start or stop did not finish within " + lockWaitMs
                        + " ms");
            }
            if (STOPS.get() != stopsBefore) {
                return;   // EmbeddedAgent.stop() was called after startAsync: the start is withdrawn
            }
            loadLocked(BootOptions.EMBEDDED, null, embedded, null, hostLoader, hostContext);
        } catch (Throwable t) {
            fail("EMBEDDED start failed", t);
        } finally {
            if (locked) {
                starting = false;
                LOCK.unlock();
            }
        }
    }

    private static Properties copy(Properties source) {
        Properties out = new Properties();
        if (source != null) {
            for (String name : source.stringPropertyNames()) {
                out.setProperty(name, source.getProperty(name));
            }
        }
        return out;
    }

    /**
     * Stops the agent this bootstrap started, if any. Never throws. When another thread's start or stop holds the
     * lock, a caller that must not wait (the event-dispatch thread, a thread the application forbids, or any caller
     * while a start is in progress, whose forbidden threads are not known yet) returns at once and the stop is
     * carried out on a {@code skillroy-boot-stop} thread after that operation (finding S33).
     */
    static void stop() {
        STOPS.incrementAndGet();
        boolean locked = false;
        try {
            if (cannotWait()) {
                if (starting) {
                    stopRequested = true;
                }
                return;
            }
            locked = LOCK.tryLock();
            if (!locked && mustNotWait(Thread.currentThread())) {
                stopInBackground();
                return;
            }
            if (!locked) {
                locked = LOCK.tryLock(lockWaitMs, TimeUnit.MILLISECONDS);
            }
            if (!locked) {
                throw new IllegalStateException("another start or stop did not finish within " + lockWaitMs
                        + " ms");
            }
            stopLocked();
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            fail("stop failed", t);
        } finally {
            if (locked) {
                LOCK.unlock();
            }
        }
    }

    /**
     * True if the thread must not wait for another start or stop: the event-dispatch thread (by name), a thread the
     * running agent's {@code ThreadPolicyProvider} forbids, or any thread while a start is in progress (the agent is
     * not published yet, so its forbidden threads are unknown).
     */
    private static boolean mustNotWait(Thread thread) {
        if (thread.getName().startsWith(EDT_PREFIX) || starting) {
            return true;
        }
        Held held = HELD.get();
        if (held == null) {
            return false;
        }
        try {
            Object forbidden = held.runtime.getClass().getMethod("isForbiddenThread", Thread.class)
                    .invoke(held.runtime, thread);
            return Boolean.TRUE.equals(forbidden);
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    /** Hands the stop to a daemon thread that waits for the lock as an ordinary thread does. */
    private static void stopInBackground() {
        try {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    stopWaiting();
                }
            }, "skillroy-boot-stop");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            // Never fall back to waiting on a thread that must not wait.
            fail("stop failed", t);
        }
    }

    /** The waiting part of {@link #stop()}, without the {@link #cannotWait()} short cut (it runs on an own thread). */
    private static void stopWaiting() {
        boolean locked = false;
        try {
            locked = LOCK.tryLock(lockWaitMs, TimeUnit.MILLISECONDS);
            if (!locked) {
                throw new IllegalStateException("another start or stop did not finish within " + lockWaitMs
                        + " ms");
            }
            stopLocked();
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            fail("stop failed", t);
        } finally {
            if (locked) {
                LOCK.unlock();
            }
        }
    }

    /** True while this bootstrap copy holds a runtime that has not closed itself. */
    static boolean isRunning() {
        Held held = HELD.get();
        return held != null && !isClosed(held.runtime);
    }

    /** What a start that may not wait answers: an agent is starting, or one serves this JVM (any copy, any mode). */
    private static boolean runningOrStarting() {
        if (starting) {
            return true;
        }
        Held held = HELD.get();
        if (held != null) {
            return !isClosed(held.runtime);
        }
        return System.getProperty(PROP_RUNNING) != null;
    }

    /**
     * True if this thread must not wait for the lock: it already holds it (re-entry from host code running during a
     * start or stop), or it is an agent thread while a start or stop is in progress (that work may be waiting for
     * this very thread). An agent thread that finds the lock held for anything else (a quick "already running"
     * check) waits as any thread does.
     */
    private static boolean cannotWait() {
        if (LOCK.isHeldByCurrentThread()) {
            return true;
        }
        return (starting || stopping) && isAgentThread(Thread.currentThread());
    }

    /** Agent threads are named {@code skillroy-*} and live in the agent's thread group {@code skillroy}. */
    static boolean isAgentThread(Thread thread) {
        if (thread.getName().startsWith("skillroy-")) {
            return true;
        }
        ThreadGroup group = thread.getThreadGroup();
        return group != null && "skillroy".equals(group.getName());
    }

    private static boolean loadLocked(String mode, String argString, Properties embedded, Instrumentation inst,
                                      ClassLoader hostLoader, Object hostContext) throws Exception {
        boolean isEmbedded = BootOptions.EMBEDDED.equals(mode);
        Map<String, String> args = isEmbedded
                ? BootOptions.fromProperties(embedded) : BootOptions.parseArgs(argString);
        if (!isEmbedded && "stop".equalsIgnoreCase(args.get("cmd"))) {
            stopLocked();
            return false;
        }
        Held held = HELD.get();
        if (held != null) {
            if (!isClosed(held.runtime)) {
                if (!isEmbedded) {
                    // A second -javaagent or a re-attach: the controller may have lost the discovery file.
                    invoke(held.runtime, "republishDiscovery");
                }
                // EMBEDDED: the host's plugin may run more than once; an agent is running, so nothing changes.
                return true;
            }
            // It closed itself (agent.disable, a production marker): let that finish, then forget it.
            stopLocked();
        }
        if (System.getProperty(PROP_RUNNING) != null) {
            // An agent loaded through another copy of the bootstrap (another class loader, or JAVAAGENT next to
            // EMBEDDED) already serves this JVM: nothing changes.
            return true;
        }
        if (isEmbedded && "stop".equalsIgnoreCase(args.get("cmd"))) {
            return false;
        }
        Properties sys = System.getProperties();
        Map<String, String> env = environment != null ? environment : System.getenv();
        if (BootOptions.JAVAAGENT.equals(mode)) {
            String guard = BootOptions.firstNonEmpty(args.get("mainClassGuard"),
                    sys.getProperty(BootOptions.PREFIX + "mainClassGuard"));
            if (!guardAllows(guard) || !BootOptions.enabledByEnvironment(sys, env)) {
                return false;
            }
        } else if (BootOptions.EMBEDDED.equals(mode)) {
            if (!BootOptions.enabledByEnvironment(sys, env) && !BootOptions.isTrue(args.get("enabled"))) {
                return false;
            }
        }
        Map<String, String> options = BootOptions.resolve(mode, args, sys, env);
        if (!BootOptions.isTrue(options.get("enabled"))) {
            return false;
        }
        if (BootOptions.JAVAAGENT.equals(mode) && !guardAllows(options.get("mainClassGuard"))) {
            return false;
        }
        if (argString != null && argString.getBytes(Charset.forName("UTF-8")).length >= BootOptions.MAX_ARGS_BYTES) {
            options.put("argsWarning", "agent argument string is " + argString.length()
                    + " characters; JDK 8 attach truncates at 1024 bytes, use an options file (t=)");
        }
        // Cleared before, not after: a running agent may record a problem that does not stop it (an unsafe
        // discovery directory, contract amendment A3) and that must stay visible.
        System.clearProperty(PROP_ERROR);
        stopRequested = false;
        starting = true;
        startLocked(options, inst, hostLoader, mode, hostContext);
        starting = false;
        if (stopRequested) {
            stopRequested = false;
            stopLocked();
            return false;
        }
        return true;
    }

    /**
     * The main-class guard (finding S17): true when no guard is set, or when the launch target in
     * {@code sun.java.command} (the main class, or the jar path for {@code -jar}) equals the guard, that is the
     * command equals it or continues with white space after it. A prefix match on a token boundary rather than a
     * split at the first space, so that a jar path containing spaces still works as a guard. Never throws.
     */
    static boolean guardAllows(String guard) {
        if (guard == null || guard.trim().isEmpty()) {
            return true;
        }
        String g = guard.trim();
        String command = System.getProperty("sun.java.command", "").trim();
        if (!command.startsWith(g)) {
            return false;
        }
        return command.length() == g.length() || Character.isWhitespace(command.charAt(g.length()));
    }

    private static void startLocked(Map<String, String> options, Instrumentation inst, ClassLoader hostLoader,
                                    String mode, Object hostContext) throws Exception {
        File home = resolveHome(options.get("home"));
        options.put("home", home.getAbsolutePath());
        URL[] urls = libJars(home);
        ClassLoader parent = hostLoader != null ? hostLoader : ClassLoader.getSystemClassLoader();
        final AgentClassLoader loader = new AgentClassLoader(urls, parent);
        Method start;
        boolean withRelease;
        try {
            Class<?> runtimeClass = Class.forName(RUNTIME_CLASS, true, loader);
            try {
                start = runtimeClass.getMethod("start", Map.class, Instrumentation.class, ClassLoader.class,
                        String.class, Object.class, Runnable.class);
                withRelease = true;
            } catch (NoSuchMethodException older) {
                start = runtimeClass.getMethod("start", Map.class, Instrumentation.class, ClassLoader.class,
                        String.class, Object.class);
                withRelease = false;
            }
        } catch (Exception | LinkageError e) {
            // start was never invoked: the loader is still ours to close.
            closeQuietly(loader);
            throw e;
        }
        Runnable release = new Runnable() {
            @Override
            public void run() {
                release(loader);
            }
        };
        Object started;
        try {
            // With the release hook (finding S32) the runtime owns the loader from here on: it runs the hook once no
            // agent code runs any more, also when the start fails, so the loader is never closed under running code.
            started = withRelease ? start.invoke(null, options, inst, parent, mode, hostContext, release)
                    : start.invoke(null, options, inst, parent, mode, hostContext);
        } catch (InvocationTargetException e) {
            if (!withRelease) {
                closeQuietly(loader);
            }
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        } catch (Exception | LinkageError e) {
            if (!withRelease) {
                closeQuietly(loader);
            }
            throw e;
        }
        if (started == null) {
            if (!withRelease) {
                closeQuietly(loader);
            }
            throw new IllegalStateException("AgentRuntime.start returned null");
        }
        Held held = new Held(started, loader);
        HELD.set(held);
        // Registered (again) after HELD is set: a runtime that closed itself before that (a production marker on a
        // fast target) runs the hook at once, so the closed agent is forgotten all the same.
        held.loaderReleasedByRuntime = releaseWhenClosed(started, release) || withRelease;
    }

    /**
     * The runtime's close hook: releases the agent loader and forgets the agent if the bootstrap still holds this one
     * (compare-and-clear, never taking the lock: a later agent installed by a racing start is left alone; S31).
     */
    private static void release(URLClassLoader loader) {
        closeQuietly(loader);
        Held held = HELD.get();
        if (held != null && held.loader == loader) {
            HELD.compareAndSet(held, null);
        }
    }

    /**
     * Asks the runtime to run {@code release} when its shutdown has finished. The runtime may finish closing on a
     * thread of its own after {@code close()} has returned (a stop called on the event-dispatch thread or on a thread
     * the application forbids, or {@code agent.disable}); closing the loader before that would leave it unable to
     * load what the shutdown needs.
     *
     * @return false if the runtime has no such hook; the bootstrap then closes the loader when it stops the agent
     */
    private static boolean releaseWhenClosed(Object started, Runnable release) {
        try {
            started.getClass().getMethod("onClosed", Runnable.class).invoke(started, release);
            return true;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    private static void stopLocked() throws Exception {
        Held held = HELD.get();
        if (held == null) {
            return;
        }
        stopping = true;
        try {
            if (held.runtime instanceof AutoCloseable) {
                ((AutoCloseable) held.runtime).close();
            } else {
                invoke(held.runtime, "close");
            }
        } finally {
            stopping = false;
            HELD.compareAndSet(held, null);
            if (!held.loaderReleasedByRuntime) {
                closeQuietly(held.loader);
            }
        }
    }

    private static boolean isClosed(Object rt) {
        try {
            Object closed = rt.getClass().getMethod("isClosed").invoke(rt);
            return Boolean.TRUE.equals(closed);
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    private static Object invoke(Object target, String method) throws Exception {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    /** The {@code home} option, else the directory of the jar this class was loaded from. */
    static File resolveHome(String option) throws IOException {
        if (option != null && !option.trim().isEmpty()) {
            File home = new File(option.trim()).getAbsoluteFile();
            if (!home.isDirectory()) {
                throw new IOException("agent home is not a directory: " + home);
            }
            return home;
        }
        File jarDir = bootstrapJarDirectory();
        if (jarDir == null) {
            throw new IOException(
                    "agent home unknown: the bootstrap is not loaded from a jar and no home option is set");
        }
        return jarDir;
    }

    private static File bootstrapJarDirectory() {
        CodeSource source = BootLauncher.class.getProtectionDomain().getCodeSource();
        URL location = source == null ? null : source.getLocation();
        if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) {
            return null;
        }
        File file;
        try {
            file = new File(new URI(location.toExternalForm()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            file = new File(location.getPath());
        }
        return file.isFile() ? file.getAbsoluteFile().getParentFile() : null;
    }

    /** {@code <home>/lib/*.jar} sorted by file name. */
    static URL[] libJars(File home) throws IOException {
        File lib = new File(home, "lib");
        File[] jars = lib.listFiles(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isFile() && f.getName().endsWith(".jar");
            }
        });
        if (jars == null || jars.length == 0) {
            throw new IOException("agent home has no lib/*.jar: " + home);
        }
        Arrays.sort(jars, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) {
            urls[i] = jars[i].toURI().toURL();
        }
        return urls;
    }

    private static void closeQuietly(URLClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException | RuntimeException ignored) {
            // Releasing jar handles is best effort.
        }
    }

    /** Records a failure in {@code skillroy.agent.error} and prints the one {@code [skillroy]} line. Never throws. */
    static void fail(String what, Throwable t) {
        String message = what + ": " + describe(t);
        try {
            System.setProperty(PROP_ERROR, message);
        } catch (Throwable ignored) {
            // A security manager may refuse; nothing else to do.
        }
        try {
            System.err.println("[skillroy] " + message);
        } catch (Throwable ignored) {
            // Never let reporting fail the host.
        }
    }

    static String describe(Throwable t) {
        Throwable cause = t;
        while ((cause instanceof InvocationTargetException || cause instanceof ExecutionException
                || cause instanceof UndeclaredThrowableException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        StringBuilder sb = new StringBuilder(cause.getClass().getName());
        if (cause.getMessage() != null) {
            sb.append(": ").append(cause.getMessage());
        }
        Throwable root = cause.getCause();
        if (root != null && root != cause) {
            sb.append(" (caused by ").append(root.getClass().getName());
            if (root.getMessage() != null) {
                sb.append(": ").append(root.getMessage());
            }
            sb.append(')');
        }
        return sb.toString().replace('\n', ' ').replace('\r', ' ');
    }
}
