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

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.scenario.DomainPacks;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.pack.DomainPack;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;

/**
 * The domain packs available to {@code compile} and {@code run}: those on the CLI's class path plus those in
 * {@code --pack} jars (or class directories), all found with {@code ServiceLoader<DomainPack>}. The jars are loaded
 * by one {@link URLClassLoader} whose parent is the CLI's loader, so a pack sees the scenario API the CLI itself
 * uses. The loader stays open while scenarios run (a pack loads classes lazily) and is closed with this object. The
 * executor looks for a controller-side web path ({@code ServiceLoader<WebPath>}, phase4-contracts.md 6.1) through
 * the same loader, so the CDP path arrives as {@code --pack} jars too (decision D49).
 *
 * <p>Not thread-safe; one command uses one instance.
 */
final class Packs implements AutoCloseable {

    /** The {@code --pack} option, shared by {@code compile} and {@code run}. */
    static final String OPTION = "--pack";

    static final OptionSpec OPTION_SPEC = OptionSpec.repeatable(OPTION, "jar",
            "a jar (or class directory) with more domain packs, found\n"
                    + "with ServiceLoader, or any other jar the run needs\n"
                    + "(a JDBC driver, the CDP web path with Playwright's\n"
                    + "jars); packs on the class path are always found");

    private final URLClassLoader jarLoader;
    private final ClassLoader loader;
    private final List<DomainPack> packs;

    private Packs(URLClassLoader jarLoader, ClassLoader loader, List<DomainPack> packs) {
        this.jarLoader = jarLoader;
        this.loader = loader;
        this.packs = packs;
    }

    /**
     * Discovers the packs on {@code parent} and in {@code jars}.
     *
     * @throws UsageException if a jar does not exist
     * @throws CliException ({@link Exit#SCENARIO}) if a pack cannot be loaded or two packs share an id
     */
    static Packs load(List<String> jars, ClassLoader parent) throws UsageException, CliException {
        List<URL> urls = new ArrayList<>();
        for (String jar : jars) {
            Path path = ParsedArgs.toPath(OPTION, jar);
            if (!Files.exists(path)) {
                throw new UsageException(OPTION + " " + jar + ": no such file or directory");
            }
            try {
                urls.add(path.toAbsolutePath().normalize().toUri().toURL());
            } catch (MalformedURLException e) {
                throw new UsageException(OPTION + " " + jar + ": " + e.getMessage());
            }
        }
        URLClassLoader jarLoader = urls.isEmpty() ? null
                : new URLClassLoader("skillroy-packs", urls.toArray(URL[]::new), parent);
        ClassLoader loader = jarLoader != null ? jarLoader : parent;
        try {
            List<DomainPack> found = DomainPacks.discover(loader);
            Map<String, DomainPack> byId = new LinkedHashMap<>();
            for (DomainPack pack : found) {
                DomainPack previous = byId.putIfAbsent(pack.id(), pack);
                if (previous != null) {
                    throw new CliException(Exit.SCENARIO, "two domain packs have the id '" + pack.id() + "': "
                            + previous.getClass().getName() + " and " + pack.getClass().getName());
                }
            }
            return new Packs(jarLoader, loader, List.copyOf(found));
        } catch (ServiceConfigurationError | LinkageError e) {
            closeQuietly(jarLoader);
            throw new CliException(Exit.SCENARIO, "a domain pack cannot be loaded: " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            // phase6-contracts.md 11.1: an overlay whose base pack is absent is refused before anything runs.
            closeQuietly(jarLoader);
            throw new CliException(Exit.SCENARIO, e.getMessage(), e);
        } catch (CliException | RuntimeException e) {
            closeQuietly(jarLoader);
            throw e;
        }
    }

    /** Every pack found, sorted by id. */
    List<DomainPack> all() {
        return packs;
    }

    /**
     * The pack {@code packId} names, or the only pack when it is null.
     *
     * @throws ScenarioException if no pack fits
     */
    DomainPack select(String packId) throws ScenarioException {
        return DomainPacks.select(packId, packs);
    }

    /** The loader the packs came from; set as the context class loader while a pack's code runs. */
    ClassLoader loader() {
        return loader;
    }

    @Override
    public void close() {
        closeQuietly(jarLoader);
    }

    private static void closeQuietly(URLClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException ignored) {
            // Only open jar files are released here; nothing else depends on it.
        }
    }
}
