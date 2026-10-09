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

import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.agent.api.AgentModule;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.Set;

/**
 * Finds the agent modules and puts them in install order. Sources, in order (the first module with a given id wins):
 * {@code <home>/modules/*.jar} sorted by file name, then the jars under {@code <home>/optional/} that the
 * {@code optionalModules} option names by file stem (phase6-contracts.md section 2.1, D114: that directory is never
 * scanned, so a jar in it is not even opened unless named), then the paths of the {@code modules} option, each in its
 * own {@link ModuleClassLoader} whose parent is the agent loader; then the providers of the {@code AgentModule}
 * service files visible from the loader of the core itself (what {@code ServiceLoader} would find), so that
 * in-process tests and single-loader embeddings work; a provider class a module jar already named is not loaded
 * twice.
 *
 * <p>Nothing here is fatal for the agent: a {@code modules} path that does not exist, unreadable jars, jars without a
 * service entry, providers that cannot be created or linked, unknown requirements and cycles are logged and reported
 * as FAILED records (finding S14: every loading problem leaves a record, so that the runtime can tell that the module
 * carrying a {@code ProductionMarker} may be among them, and {@code agent.info} shows it). Such a record has no
 * module; its id is the path or provider that failed. Used only on the start thread.
 */
final class ModuleLoader {

    static final String SERVICE_FILE = "META-INF/services/" + AgentModule.class.getName();
    /** The agent option that names the optional module jars to load, by file stem (D114). */
    static final String OPTIONAL_MODULES = "optionalModules";
    /** The home's directory of optional module jars, never scanned (D114). */
    static final String OPTIONAL_DIR = "optional";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_SERVICE_ERRORS = 100;

    /**
     * One discovered module and its fate. The mutable fields are written by the start, readiness and closing threads
     * and read by control-channel threads; the runtime uses the record's monitor to keep {@code onTargetReady} and
     * {@code uninstall} of one module apart.
     */
    static final class Record {
        final String id;
        final String version;
        final AgentModule module;
        final String origin;
        volatile String state;
        volatile String message;
        volatile String readyMessage;
        volatile ModuleContext context;

        Record(String id, String version, AgentModule module, String origin) {
            this.id = id;
            this.version = version;
            this.module = module;
            this.origin = origin;
        }

        void fail(String why) {
            state = "FAILED";
            message = why;
        }

        boolean installed() {
            return "INSTALLED".equals(state);
        }

        /** The {@code agent.info} entry: {@code {id, version, state, message?}}. */
        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<String, Object>();
            json.put("id", id);
            json.put("version", version);
            json.put("state", state);
            if (message != null) {
                json.put("message", message);
            }
            if (readyMessage != null) {
                json.put("readyMessage", readyMessage);
            }
            return json;
        }
    }

    private final AgentLog log;
    private final List<ModuleClassLoader> loaders = new ArrayList<ModuleClassLoader>();
    /** Provider class names named by the module jars, so that a class-path copy of one is not loaded again. */
    private final Set<String> fromJars = new LinkedHashSet<String>();

    ModuleLoader(AgentLog log) {
        this.log = log;
    }

    /** The loaders created for module jars; closed by the runtime at shutdown. */
    List<ModuleClassLoader> loaders() {
        return loaders;
    }

    /** Discovers modules in source order, dropping later duplicates of an id (with a warning). */
    List<Record> discover(Map<String, String> options, ClassLoader agentLoader) {
        Map<String, Record> byId = new LinkedHashMap<String, Record>();
        String home = options.get("home");
        if (home != null && !home.trim().isEmpty()) {
            File dir = new File(home.trim(), "modules");
            File[] jars = dir.listFiles(new FileFilter() {
                @Override
                public boolean accept(File f) {
                    return f.isFile() && f.getName().endsWith(".jar");
                }
            });
            if (jars != null) {
                Arrays.sort(jars, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return a.getName().compareTo(b.getName());
                    }
                });
                for (File jar : jars) {
                    fromJar(jar, agentLoader, byId);
                }
            }
        }
        optional(home, options.get(OPTIONAL_MODULES), agentLoader, byId);
        String extra = options.get("modules");
        if (extra != null) {
            for (String path : extra.split(",")) {
                String trimmed = path.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                File jar = new File(trimmed);
                if (!jar.exists()) {
                    log.warn("module path does not exist: " + jar);
                    failedRecord(jar.getPath(), "module path does not exist: " + jar, byId);
                    continue;
                }
                fromJar(jar, agentLoader, byId);
            }
        }
        fromClassPath(agentLoader, byId);
        return new ArrayList<Record>(byId.values());
    }

    /**
     * The {@code optionalModules} source (D114): each comma-separated file stem names {@code <home>/optional/<stem>.jar},
     * loaded as a module jar. A stem that is not a plain file name, names no jar, or is given without a home is a
     * FAILED record (S14: never fatal, visible in {@code agent.info}); a jar without a service file is one as well.
     */
    private void optional(String home, String stems, ClassLoader agentLoader, Map<String, Record> byId) {
        if (stems == null || stems.trim().isEmpty()) {
            return;
        }
        for (String part : stems.split(",")) {
            String stem = part.trim();
            if (stem.isEmpty()) {
                continue;
            }
            if (stem.indexOf('/') >= 0 || stem.indexOf('\\') >= 0 || stem.startsWith(".")) {
                log.warn("optionalModules: '" + stem + "' is not a file stem");
                failedRecord(OPTIONAL_DIR + "/" + stem, "optionalModules: '" + stem
                        + "' is not a file stem of a jar under <home>/" + OPTIONAL_DIR, byId);
                continue;
            }
            if (home == null || home.trim().isEmpty()) {
                log.warn("optionalModules names " + stem + ", but the agent has no home");
                failedRecord(OPTIONAL_DIR + "/" + stem + ".jar", "optionalModules names " + stem
                        + ", but the agent has no home", byId);
                continue;
            }
            File jar = new File(new File(home.trim(), OPTIONAL_DIR), stem + ".jar");
            if (!jar.isFile()) {
                log.warn("optionalModules names " + stem + ", but there is no " + jar);
                failedRecord(jar.getPath(), "optional module jar does not exist: " + jar, byId);
                continue;
            }
            fromJar(jar, agentLoader, byId);
        }
    }

    private void fromJar(File jar, ClassLoader agentLoader, Map<String, Record> byId) {
        ModuleClassLoader loader;
        List<String> providers;
        try {
            loader = new ModuleClassLoader(new URL[] {jar.getAbsoluteFile().toURI().toURL()}, agentLoader);
            providers = providerNames(loader.findResources(SERVICE_FILE));
        } catch (IOException | RuntimeException e) {
            log.warn("cannot read module jar " + jar, e);
            failedRecord(jar.getPath(), "cannot read module jar: " + describe(e), byId);
            return;
        }
        if (providers.isEmpty()) {
            log.warn("module jar " + jar + " declares no " + AgentModule.class.getName() + " service");
            closeQuietly(loader);
            failedRecord(jar.getPath(), "module jar declares no " + AgentModule.class.getName() + " service", byId);
            return;
        }
        loaders.add(loader);
        for (String className : providers) {
            fromJars.add(className);
            String origin = jar.getPath();
            try {
                Class<?> type = Class.forName(className, false, loader);
                if (!AgentModule.class.isAssignableFrom(type)) {
                    throw new IllegalStateException(className + " does not implement " + AgentModule.class.getName());
                }
                AgentModule module = (AgentModule) type.getDeclaredConstructor().newInstance();
                add(module, origin, byId);
            } catch (Throwable t) {
                log.warn("cannot create module " + className + " from " + jar, t);
                Record failed = new Record(className, null, null, origin);
                failed.fail("cannot create " + className + ": " + describe(t));
                putFirst(failed, byId);
            }
        }
    }

    /**
     * The class-path source: every provider named by a service file visible from {@code agentLoader} (its parents
     * included), in resource order, created like {@code ServiceLoader} would. A provider class already named by a
     * module jar of this agent is skipped: an embedding host typically ships the module jar on its own class path
     * as well (for its plugin class), where the provider cannot link the agent API; that copy is the module already
     * loaded, not a module that failed. Any other provider that cannot be loaded leaves a FAILED record (S14).
     */
    private void fromClassPath(ClassLoader agentLoader, Map<String, Record> byId) {
        List<String> providers;
        try {
            providers = providerNames(agentLoader.getResources(SERVICE_FILE));
        } catch (IOException | RuntimeException e) {
            log.warn("cannot read the module service files on the class path: " + describe(e));
            failedRecord("class path", "cannot read the module service files on the class path: " + describe(e),
                    byId);
            return;
        }
        int errors = 0;
        for (String className : providers) {
            if (fromJars.contains(className)) {
                log.info("module provider " + className + " on the class path is the one already loaded from a "
                        + "module jar; the class-path copy is not used");
                continue;
            }
            try {
                Class<?> type = Class.forName(className, false, agentLoader);
                if (!AgentModule.class.isAssignableFrom(type)) {
                    throw new IllegalStateException(className + " does not implement " + AgentModule.class.getName());
                }
                add((AgentModule) type.getDeclaredConstructor().newInstance(), "class path", byId);
            } catch (Throwable t) {
                if (++errors > MAX_SERVICE_ERRORS) {
                    break;
                }
                log.warn("skipping a module provider on the class path: " + className + ": " + describe(t));
                Record failed = new Record(className, null, null, "class path");
                failed.fail("cannot load the module provider " + className + " on the class path: " + describe(t));
                putFirst(failed, byId);
            }
        }
    }

    private void add(AgentModule module, String origin, Map<String, Record> byId) {
        String id;
        String version;
        try {
            id = module.id();
            version = module.version();
        } catch (Throwable t) {
            log.warn("module " + module.getClass().getName() + " from " + origin + " has no usable id", t);
            Record failed = new Record(module.getClass().getName(), null, null, origin);
            failed.fail("id() failed: " + describe(t));
            putFirst(failed, byId);
            return;
        }
        if (id == null || id.trim().isEmpty()) {
            Record failed = new Record(module.getClass().getName(), version, null, origin);
            failed.fail("module id is empty");
            putFirst(failed, byId);
            return;
        }
        putFirst(new Record(id, version, module, origin), byId);
    }

    /** A FAILED record without a module, for a loading problem (finding S14). */
    private void failedRecord(String id, String message, Map<String, Record> byId) {
        Record failed = new Record(id, null, null, id);
        failed.fail(message);
        putFirst(failed, byId);
    }

    private void putFirst(Record record, Map<String, Record> byId) {
        Record existing = byId.get(record.id);
        if (existing != null) {
            log.warn("duplicate module id '" + record.id + "' from " + record.origin + " ignored; the one from "
                    + existing.origin + " is used");
            return;
        }
        byId.put(record.id, record);
    }

    /** Installs in dependency order; discovery order breaks ties. */
    void installAll(List<Record> records, Installer installer) {
        List<Record> pending = new ArrayList<Record>();
        Set<String> known = new LinkedHashSet<String>();
        for (Record r : records) {
            known.add(r.id);
        }
        for (Record r : records) {
            if (r.state != null) {
                continue;
            }
            Set<String> requires;
            try {
                requires = r.module.requires();
            } catch (Throwable t) {
                r.fail("requires() failed: " + describe(t));
                continue;
            }
            for (String req : requires == null ? Collections.<String>emptySet() : requires) {
                if (!known.contains(req)) {
                    r.fail("requires unknown module '" + req + "'");
                    break;
                }
            }
            if (r.state == null) {
                pending.add(r);
            }
        }
        Map<String, Record> byId = new LinkedHashMap<String, Record>();
        for (Record r : records) {
            byId.put(r.id, r);
        }
        boolean progress = true;
        while (progress && !pending.isEmpty()) {
            progress = false;
            for (Iterator<Record> it = pending.iterator(); it.hasNext(); ) {
                Record r = it.next();
                String blocking = null;
                String failedReq = null;
                for (String req : safeRequires(r)) {
                    Record dep = byId.get(req);
                    if (dep == null || "FAILED".equals(dep.state)) {
                        failedReq = req;
                        break;
                    }
                    if (!dep.installed()) {
                        blocking = req;
                    }
                }
                if (failedReq != null) {
                    r.fail("required module '" + failedReq + "' is not installed");
                    it.remove();
                    progress = true;
                    break;
                }
                if (blocking == null) {
                    it.remove();
                    installer.install(r);
                    progress = true;
                    break;
                }
            }
        }
        for (Record r : pending) {
            r.fail("dependency cycle: " + safeRequires(r) + " never installed");
        }
        for (Record r : records) {
            if ("FAILED".equals(r.state)) {
                log.warn("module " + r.id + " (" + r.origin + ") FAILED: " + r.message);
            }
        }
    }

    private static Set<String> safeRequires(Record r) {
        try {
            Set<String> requires = r.module.requires();
            return requires == null ? Collections.<String>emptySet() : requires;
        } catch (Throwable t) {
            return Collections.emptySet();
        }
    }

    /** Installs one module; sets the record's state. */
    interface Installer {
        void install(Record record);
    }

    static List<String> providerNames(Enumeration<URL> files) throws IOException {
        Set<String> names = new LinkedHashSet<String>();
        while (files.hasMoreElements()) {
            URL url = files.nextElement();
            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            InputStream in = connection.getInputStream();
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF8));
                String line;
                while ((line = reader.readLine()) != null) {
                    int hash = line.indexOf('#');
                    String name = (hash >= 0 ? line.substring(0, hash) : line).trim();
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            } finally {
                in.close();
            }
        }
        return new ArrayList<String>(names);
    }

    static String describe(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause
                && (cause instanceof java.lang.reflect.InvocationTargetException
                || cause instanceof ServiceConfigurationError || cause instanceof ExceptionInInitializerError)) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getClass().getName() + ": " + cause.getMessage()
                : cause.getClass().getName();
    }

    static void closeQuietly(ModuleClassLoader loader) {
        try {
            loader.close();
        } catch (IOException | RuntimeException ignored) {
            // Releasing jar handles is best effort.
        }
    }
}
