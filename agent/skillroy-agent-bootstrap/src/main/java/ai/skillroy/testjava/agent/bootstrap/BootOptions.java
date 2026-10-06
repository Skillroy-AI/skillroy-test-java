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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Parses and merges the agent options (phase1-contracts.md 2.4) and applies the {@code enabled} rule. Lives in the
 * bootstrap because the options decide whether anything is started at all, before any agent class is loaded.
 *
 * <p>Precedence, lowest to highest: system properties {@code skillroy.agent.<key>}; the options file named by
 * {@code t=} or {@code config=}; the agent argument string (or the {@code Properties} given to
 * {@code EmbeddedAgent.start}). Defaults are applied by the core. Stateless; safe from any thread.
 */
final class BootOptions {

    static final String PREFIX = "skillroy.agent.";
    static final String ENV_ENABLED = "SKILLROY_AGENT_ENABLED";
    static final String JAVAAGENT = "JAVAAGENT";
    static final String ATTACH = "ATTACH";
    static final String EMBEDDED = "EMBEDDED";

    /** JDK 8 attach truncates each argument at 1024 bytes; longer argument strings are suspect. */
    static final int MAX_ARGS_BYTES = 1024;

    /**
     * System properties under the prefix that the agent publishes or interprets itself; they are never read back as
     * options ({@code enabled} has its own rule). {@code session} is the session id of a running agent (hardening
     * item H2): it is generated at every start and never configurable.
     */
    private static final Set<String> NOT_OPTIONS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "enabled", "error", "protocol", "loadMode", "discoveryFile", "session")));

    private BootOptions() {
    }

    /**
     * Parses an agent argument string {@code k=v;k=v}. Keys and values are trimmed, empty segments are skipped and a
     * segment without {@code '='} is a flag with the value {@code "true"}. Later keys win.
     */
    static Map<String, String> parseArgs(String args) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (args == null) {
            return out;
        }
        for (String part : args.split(";", -1)) {
            String segment = part.trim();
            if (segment.isEmpty()) {
                continue;
            }
            int eq = segment.indexOf('=');
            String key = eq < 0 ? segment : segment.substring(0, eq).trim();
            String value = eq < 0 ? "true" : segment.substring(eq + 1).trim();
            if (!key.isEmpty()) {
                out.put(key, value);
            }
        }
        return out;
    }

    /** The {@code skillroy.agent.<key>} system properties as options, without the keys the agent publishes. */
    static Map<String, String> fromSystemProperties(Properties sys) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String name : new TreeSet<String>(sys.stringPropertyNames())) {
            if (name.startsWith(PREFIX) && name.length() > PREFIX.length()) {
                String key = name.substring(PREFIX.length());
                if (!NOT_OPTIONS.contains(key)) {
                    out.put(key, sys.getProperty(name));
                }
            }
        }
        return out;
    }

    /** The string entries of a {@code Properties} object (null gives an empty map), values trimmed. */
    static Map<String, String> fromProperties(Properties props) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (props == null) {
            return out;
        }
        for (String name : new TreeSet<String>(props.stringPropertyNames())) {
            String value = props.getProperty(name);
            if (value != null) {
                out.put(name.trim(), value.trim());
            }
        }
        return out;
    }

    /** Reads a Java properties file as UTF-8; escapes are honoured, so files written by Properties.store work. */
    static Map<String, String> readOptionsFile(File file) throws IOException {
        if (!file.isFile()) {
            throw new IOException("options file not found: " + file);
        }
        Properties props = new Properties();
        InputStream in = new FileInputStream(file);
        try {
            Reader reader = new InputStreamReader(in, Charset.forName("UTF-8"));
            props.load(reader);
        } finally {
            in.close();
        }
        return fromProperties(props);
    }

    /** The options file named by the highest-precedence {@code t} or {@code config} key, or null. */
    static String optionsFileName(Map<String, String> args, Map<String, String> sysOptions) {
        String name = firstNonEmpty(args.get("t"), args.get("config"), sysOptions.get("t"), sysOptions.get("config"));
        return name;
    }

    /** True if the system property {@code skillroy.agent.enabled} or the environment variable says {@code true}. */
    static boolean enabledByEnvironment(Properties sys, Map<String, String> env) {
        return isTrue(sys.getProperty(PREFIX + "enabled")) || (env != null && isTrue(env.get(ENV_ENABLED)));
    }

    /**
     * Merges all sources and sets {@code enabled} to {@code "true"} or {@code "false"} by the load-mode rule:
     * JAVAAGENT only from the system property or the environment (the argument string and the file it names may be
     * inherited through {@code JAVA_TOOL_OPTIONS}); EMBEDDED also from the host's own {@code Properties}; ATTACH also
     * from the attach arguments and the options file, because attaching is itself a deliberate act.
     *
     * @param args the parsed agent argument string, or the EMBEDDED {@code Properties}
     * @throws IOException if a named options file cannot be read
     */
    static Map<String, String> resolve(String loadMode, Map<String, String> args, Properties sys,
                                       Map<String, String> env) throws IOException {
        Map<String, String> sysOptions = fromSystemProperties(sys);
        String fileName = optionsFileName(args, sysOptions);
        Map<String, String> fileOptions = fileName == null
                ? new LinkedHashMap<String, String>() : readOptionsFile(new File(fileName));
        Map<String, String> merged = new LinkedHashMap<String, String>();
        merged.putAll(sysOptions);
        merged.putAll(fileOptions);
        merged.putAll(args);
        boolean enabled = enabledByEnvironment(sys, env);
        if (EMBEDDED.equals(loadMode)) {
            enabled = enabled || isTrue(args.get("enabled"));
        } else if (ATTACH.equals(loadMode)) {
            enabled = enabled || isTrue(args.get("enabled")) || isTrue(fileOptions.get("enabled"));
        }
        merged.put("enabled", String.valueOf(enabled));
        return merged;
    }

    /** {@code "true"} in any case, surrounding whitespace ignored. */
    static boolean isTrue(String value) {
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
