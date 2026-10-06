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

package ai.skillroy.testjava.controller;

import ai.skillroy.testjava.protocol.Protocol;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Starts a target JVM as a child process, optionally with the agent loaded at launch (JAVAAGENT mode, directly on the
 * command line or injected through {@code JAVA_TOOL_OPTIONS}). Used by tests and CI wrappers that own the target's
 * life cycle; {@link LaunchedTarget#close()} ends it.
 *
 * <p>Command line: {@code <java> <jvmArgs…> [agent flags] [-cp <classPath>] <mainClass> <args…>}. Agent flags are
 * {@code -Dskillroy.agent.discoveryDir=<dir>} (when an agent is configured or {@link Builder#discoveryDir} is set),
 * and with {@link Builder#javaAgent} also {@code -Dskillroy.agent.enabled=true} and
 * {@code -javaagent:<bootstrap jar>=t=<options file>}. The discovery directory defaults to
 * {@link Discovery#defaultDir()} (with the fall-back of findings S8/S19), always passed to the agent as an absolute
 * path. The options file is owner-only, lives in the discovery directory, holds a generated {@code token} and
 * {@code nonce} plus the given options, and is deleted once {@link LaunchedTarget#awaitAgent} has verified the agent,
 * at the latest by {@link LaunchedTarget#close()} (finding S5). No option of the launcher concerns the agent's socket
 * options. With {@link Builder#viaJavaToolOptions viaJavaToolOptions(true)} the agent flags go
 * into the {@code JAVA_TOOL_OPTIONS} environment variable instead (appended to any existing value, quoted where they
 * contain spaces) and the agent argument gains {@code ;mainClassGuard=<mainClass>}, so that other JVMs started in that
 * environment ignore it.
 *
 * <p>EMBEDDED targets: pass the system properties the host's plugin mechanism needs with {@link Builder#jvmArg}, set
 * {@link Builder#discoveryDir}, and use {@link LaunchedTarget#awaitAgent}.
 *
 * <p>Output: discarded unless {@link Builder#inheritOutputTo} names a log file (stdout and stderr together). The
 * child's stdin is closed at start.
 *
 * <p>A builder is not thread-safe; use it from one thread.
 */
public final class TargetLauncher {

    private TargetLauncher() {
    }

    /**
     * Starts building a launch with the given {@code java} executable (for a Java 8 target, its
     * {@code bin/java}).
     */
    public static Builder java(Path javaExecutable) {
        return new Builder(Objects.requireNonNull(javaExecutable, "javaExecutable"));
    }

    /** Collects the launch settings; {@link #start()} launches. */
    public static final class Builder {

        private final Path java;
        private final List<Path> classPath = new ArrayList<>();
        private final List<String> jvmArgs = new ArrayList<>();
        private final List<String> args = new ArrayList<>();
        private final Map<String, String> env = new LinkedHashMap<>();
        private String mainClass;
        private AgentHome agentHome;
        private Map<String, String> agentOptions = Map.of();
        private boolean viaJavaToolOptions;
        private Path logFile;
        private Path discoveryDir;

        private Builder(Path java) {
            this.java = java;
        }

        /** Replaces the class path ({@code -cp}); an empty list means no {@code -cp} option. */
        public Builder classPath(List<Path> entries) {
            classPath.clear();
            for (Path entry : Objects.requireNonNull(entries, "entries")) {
                classPath.add(Objects.requireNonNull(entry, "class path entry"));
            }
            return this;
        }

        /** The main class (required). */
        public Builder mainClass(String name) {
            this.mainClass = Objects.requireNonNull(name, "name");
            return this;
        }

        /** Adds one JVM option, before the agent flags and the class path. */
        public Builder jvmArg(String arg) {
            jvmArgs.add(Objects.requireNonNull(arg, "arg"));
            return this;
        }

        /** Adds one program argument, after the main class. */
        public Builder arg(String arg) {
            args.add(Objects.requireNonNull(arg, "arg"));
            return this;
        }

        /** Sets an environment variable of the child; a null value removes an inherited one. */
        public Builder env(String k, String v) {
            env.put(Objects.requireNonNull(k, "k"), v);
            return this;
        }

        /**
         * JAVAAGENT mode: load the agent from {@code home} at launch. A {@code token} and a {@code nonce} are
         * generated unless {@code options} contains them; {@code options} (contract §2.4) go into the options file.
         * A {@code discoveryDir} option also becomes the directory {@link LaunchedTarget#awaitAgent} watches (it
         * is passed on as an absolute path).
         */
        public Builder javaAgent(AgentHome home, Map<String, String> options) {
            this.agentHome = Objects.requireNonNull(home, "home");
            Map<String, String> copy = new LinkedHashMap<>();
            if (options != null) {
                options.forEach((k, v) -> {
                    if (k != null && v != null) {
                        copy.put(k, v);
                    }
                });
            }
            this.agentOptions = copy;
            return this;
        }

        /** Injects the agent flags through {@code JAVA_TOOL_OPTIONS} with a main-class guard; needs javaAgent. */
        public Builder viaJavaToolOptions(boolean on) {
            this.viaJavaToolOptions = on;
            return this;
        }

        /** Sends the child's stdout and stderr to {@code logFile} (truncated; parent directories are created). */
        public Builder inheritOutputTo(Path logFile) {
            this.logFile = logFile;
            return this;
        }

        /**
         * The discovery directory, passed to the target as {@code -Dskillroy.agent.discoveryDir} and watched by
         * {@link LaunchedTarget#awaitAgent}. Default: {@link Discovery#defaultDir()} (the per-user
         * {@code ${java.io.tmpdir}/skillroy-<user>}, or {@code <user.home>/.skillroy/run} when another user occupies
         * that, findings S8/S19), passed only when an agent is configured, and then always as the absolute directory
         * resolved here. With {@link #javaAgent} the launcher creates the directory owner-only if it is missing and
         * refuses an existing one that fails the trust check of contract §2.5 (see {@link Discovery}).
         */
        public Builder discoveryDir(Path dir) {
            this.discoveryDir = dir;
            return this;
        }

        /**
         * Launches the target.
         *
         * @throws IOException if the options file cannot be written (including a discovery directory that belongs
         *     to another user, is writable by group or others, or is a symbolic link), a path cannot be passed to the
         *     JVM (an agent home containing {@code '='}, an options file path containing {@code ';'}), or the process
         *     cannot be started
         * @throws IllegalStateException if no main class is set, or viaJavaToolOptions is on without javaAgent
         * @throws IllegalArgumentException if a {@code token} option is not a usable token, or a path
         *     holds both quote characters and cannot be quoted for {@code JAVA_TOOL_OPTIONS}
         */
        public LaunchedTarget start() throws IOException {
            if (mainClass == null || mainClass.isBlank()) {
                throw new IllegalStateException("mainClass is required");
            }
            if (viaJavaToolOptions && agentHome == null) {
                throw new IllegalStateException("viaJavaToolOptions(true) needs javaAgent(...)");
            }
            if (agentOptions.containsKey("token") && !SecureFiles.isValidToken(agentOptions.get("token"))) {
                throw new IllegalArgumentException("the token option is not a usable token (allowed: letters, "
                        + "digits and ._~+/=-)");
            }
            Path named = agentOptions.containsKey("discoveryDir") ? Path.of(agentOptions.get("discoveryDir"))
                    : discoveryDir;
            Path dir;
            if (named != null) {
                dir = named.toAbsolutePath().normalize();
            } else if (agentHome != null) {
                dir = Discovery.ensureDefaultDir();
            } else {
                dir = Discovery.defaultDir().toAbsolutePath().normalize();
            }

            Path optionsFile = null;
            String nonce = null;
            String token = null;
            if (agentHome != null) {
                Map<String, String> options = new LinkedHashMap<>(agentOptions);
                String absoluteDir = dir.toString();
                options.computeIfPresent("discoveryDir", (k, v) -> absoluteDir);
                token = options.computeIfAbsent("token", k -> SecureFiles.newToken());
                nonce = options.computeIfAbsent("nonce", k -> SecureFiles.newNonce());
                optionsFile = SecureFiles.writeOptionsFile(dir, options);
            }
            try {
                return launch(dir, optionsFile, nonce, token);
            } catch (IOException | RuntimeException e) {
                SecureFiles.deleteQuietly(optionsFile);
                throw e;
            }
        }

        /** Builds the command line and environment and starts the process; the caller owns {@code optionsFile}. */
        private LaunchedTarget launch(Path dir, Path optionsFile, String nonce, String token) throws IOException {
            List<String> agentFlags = new ArrayList<>();
            if (agentHome != null || discoveryDir != null) {
                agentFlags.add("-D" + Protocol.PROP_PREFIX + "discoveryDir=" + dir);
            }
            if (optionsFile != null) {
                String jar = agentHome.bootstrapJar().toString();
                String problem = AttachSession.agentArgumentProblem(jar, "t=" + optionsFile);
                if (problem != null) {
                    throw new IOException(problem);
                }
                String argument = "t=" + optionsFile + (viaJavaToolOptions ? ";mainClassGuard=" + mainClass : "");
                agentFlags.add("-D" + Protocol.PROP_PREFIX + "enabled=true");
                agentFlags.add("-javaagent:" + jar + "=" + argument);
            }

            List<String> command = new ArrayList<>();
            command.add(java.toString());
            command.addAll(jvmArgs);
            Map<String, String> environment = new LinkedHashMap<>(env);
            if (viaJavaToolOptions) {
                String existing = environment.containsKey(JAVA_TOOL_OPTIONS) ? environment.get(JAVA_TOOL_OPTIONS)
                        : System.getenv(JAVA_TOOL_OPTIONS);
                StringBuilder value = new StringBuilder(existing == null ? "" : existing.strip());
                for (String flag : agentFlags) {
                    if (!value.isEmpty()) {
                        value.append(' ');
                    }
                    value.append(quoteForToolOptions(flag));
                }
                environment.put(JAVA_TOOL_OPTIONS, value.toString());
            } else {
                command.addAll(agentFlags);
            }
            if (!classPath.isEmpty()) {
                List<String> entries = new ArrayList<>();
                classPath.forEach(p -> entries.add(p.toString()));
                command.add("-cp");
                command.add(String.join(File.pathSeparator, entries));
            }
            command.add(mainClass);
            command.addAll(args);

            ProcessBuilder builder = new ProcessBuilder(command);
            environment.forEach((k, v) -> {
                if (v == null) {
                    builder.environment().remove(k);
                } else {
                    builder.environment().put(k, v);
                }
            });
            builder.redirectInput(Redirect.PIPE);
            if (logFile != null) {
                Path parent = logFile.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                builder.redirectErrorStream(true);
                builder.redirectOutput(Redirect.to(logFile.toFile()));
            } else {
                builder.redirectOutput(Redirect.DISCARD);
                builder.redirectError(Redirect.DISCARD);
            }
            Process process = builder.start();
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // The child simply keeps an open stdin.
            }
            return new LaunchedTarget(process, Collections.unmodifiableList(command),
                    Collections.unmodifiableMap(environment), optionsFile, nonce, token, dir, logFile);
        }
    }

    static final String JAVA_TOOL_OPTIONS = "JAVA_TOOL_OPTIONS";

    /**
     * Quotes one option for {@code JAVA_TOOL_OPTIONS}, which the JVM splits at white space but lets single or double
     * quotes group (Java 8 included).
     *
     * @throws IllegalArgumentException if the option contains both quote characters and needs quoting
     */
    static String quoteForToolOptions(String option) {
        boolean needsQuotes = option.isEmpty() || option.chars().anyMatch(c -> Character.isWhitespace(c)
                || c == '"' || c == '\'');
        if (!needsQuotes) {
            return option;
        }
        if (option.indexOf('"') < 0) {
            return '"' + option + '"';
        }
        if (option.indexOf('\'') < 0) {
            return "'" + option + "'";
        }
        throw new IllegalArgumentException("cannot quote for JAVA_TOOL_OPTIONS: " + option);
    }
}
