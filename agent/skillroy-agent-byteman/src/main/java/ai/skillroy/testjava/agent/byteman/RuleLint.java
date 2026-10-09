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

package ai.skillroy.testjava.agent.byteman;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lint every rule passes before anything reaches Byteman (phase6-contracts.md 7.3, D133). Errors name the line.
 *
 * <ul>
 *   <li>The text is at most {@value #MAX_BYTES} bytes (UTF-8) and holds exactly one {@code RULE … ENDRULE}; outside it
 *       only blank lines and {@code #} comments (a {@code HELPER}, {@code IMPORT} or {@code COMPILE} line there would
 *       set Byteman's default for the rule).</li>
 *   <li>The {@code RULE} line's name matches {@value #NAME}; the module submits the rule as {@code skillroy:<name>}.</li>
 *   <li>{@code CLASS} or {@code INTERFACE}, once, a fully qualified name (a leading {@code ^} allowed) not under a
 *       protected prefix ({@link #PROTECTED_CLASS_PREFIXES}); {@code METHOD}, once.</li>
 *   <li>A {@code HELPER} line is absent or exactly the module's ({@value #HELPER_LINE}); the lint removes it and the
 *       module puts its own after the {@code CLASS} or {@code INTERFACE} line. No {@code IMPORT}, {@code COMPILE},
 *       {@code NOCOMPILE}.</li>
 *   <li>The text scan: over every line but {@code RULE}, {@code CLASS}, {@code INTERFACE} and {@code HELPER}, after
 *       {@code #} comments and string and character literals are removed and white space around dots is closed up,
 *       the entries of {@link #DENY} are matched case-sensitively as whole Java identifiers, and its prefix entries as
 *       qualified-name prefixes starting at an identifier boundary (any component of a dotted name), so that a method
 *       {@code traceId} or {@code delayDays} is not {@code trace} or {@code delay}.</li>
 * </ul>
 *
 * <p>{@code RETURN}, {@code THROW}, assignments and calls on bound objects are allowed: a fault injection is the
 * point, and {@code confirm} on {@code rule.submit} is the gate. Stateless; safe from any thread.
 */
public final class RuleLint {

    /** The longest rule text, in UTF-8 bytes. */
    public static final int MAX_BYTES = 16 * 1024;
    /** A rule name as written (before the {@code skillroy:} prefix). */
    public static final String NAME = "[A-Za-z][A-Za-z0-9_. -]{0,79}";
    /** The prefix of every rule the module submits, so that it never removes a rule it did not install. */
    public static final String NAME_PREFIX = "skillroy:";
    /** The module's curated helper. */
    public static final String HELPER_CLASS = "ai.skillroy.testjava.agent.byteman.sys.RuleHelper";
    /** The only {@code HELPER} line a rule may carry. */
    public static final String HELPER_LINE = "HELPER " + HELPER_CLASS;

    /** Classes a rule may not name: the agent protects itself and the JDK. The sample's packages are allowed. */
    public static final List<String> PROTECTED_CLASS_PREFIXES = Collections.unmodifiableList(java.util.Arrays.asList(
            "java.", "javax.", "sun.", "com.sun.", "jdk.", "org.jboss.byteman.", "ai.skillroy.testjava.agent.",
            "ai.skillroy.testjava.protocol."));

    /**
     * The deny-list of the text scan, each entry with its reason, in one place for the security review. An entry that
     * ends with a dot, or {@code ClassLoader}, is a qualified-name prefix; the others are whole identifiers.
     */
    public static final Map<String, String> DENY;

    static {
        Map<String, String> d = new LinkedHashMap<String, String>();
        d.put("killJVM", "a Byteman built-in that ends the target JVM");
        d.put("killThread", "a Byteman built-in that ends an application thread");
        d.put("delay", "a Byteman built-in that blocks the application's thread");
        d.put("waitFor", "a Byteman built-in that blocks the application's thread");
        d.put("rendezvous", "a Byteman built-in that blocks the application's thread");
        d.put("joinWait", "a Byteman built-in that blocks the application's thread");
        d.put("signalWake", "a Byteman built-in that wakes or interrupts threads");
        d.put("signalThrow", "a Byteman built-in that throws into a waiting thread");
        d.put("traceOpen", "a Byteman built-in that writes files");
        d.put("traceClose", "a Byteman built-in that writes files");
        d.put("trace", "a Byteman built-in that writes to files or the host's console");
        d.put("traceln", "a Byteman built-in that writes to files or the host's console");
        d.put("traceStack", "a Byteman built-in that writes to files or the host's console");
        d.put("openTrace", "a Byteman built-in that writes files");
        d.put("ProcessBuilder", "creates processes");
        d.put("getRuntime", "reaches Runtime: processes, exit, halt");
        d.put("Unsafe", "reaches raw memory");
        d.put("MethodHandle", "reaches reflection");
        d.put("MethodHandles", "reaches reflection");
        d.put("setAccessible", "reaches private members");
        d.put("defineClass", "defines classes");
        d.put("getMethod", "reaches reflection");
        d.put("getDeclaredMethod", "reaches reflection");
        d.put("getField", "reaches reflection");
        d.put("getDeclaredField", "reaches reflection");
        d.put("getConstructor", "reaches reflection");
        d.put("getDeclaredConstructor", "reaches reflection");
        d.put("invoke", "reaches reflection");
        d.put("newInstance", "reaches reflection");
        d.put("System.", "exit, properties, the console, the environment");
        d.put("Runtime.", "processes, exit, halt");
        d.put("Thread.", "sleeps, interrupts and stops threads");
        d.put("Class.", "loads classes and reaches reflection");
        d.put("ClassLoader", "loads and defines classes");
        d.put("java.lang.reflect.", "reflection");
        d.put("java.io.", "files and streams");
        d.put("java.nio.", "files and channels");
        d.put("java.net.", "the network");
        d.put("java.sql.", "databases");
        d.put("javax.", "the JDK's extension packages");
        d.put("sun.", "JDK internals");
        d.put("jdk.", "JDK internals");
        d.put("org.jboss.byteman.", "Byteman's own internals");
        d.put("ai.skillroy.testjava.agent.", "the agent's own classes");
        d.put("ai.skillroy.testjava.protocol.", "the agent's own classes");
        DENY = Collections.unmodifiableMap(d);
    }

    private static final Pattern NAME_PATTERN = Pattern.compile(NAME);
    private static final Pattern CLASS_NAME = Pattern.compile(
            "\\^?[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Pattern METHOD_SPEC = Pattern.compile("\\S.*");
    /** A dotted name: identifiers joined by dots (white space around the dots is closed up first). */
    private static final Pattern DOTTED = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern SPACE_AROUND_DOT = Pattern.compile("\\s*\\.\\s*");

    private RuleLint() {
    }

    /** What the lint found. */
    public static final class Result {
        private final List<String> errors;
        private final String name;
        private final String className;
        private final boolean overriding;
        private final boolean isInterface;
        private final String method;
        private final String submitText;

        Result(List<String> errors, String name, String className, boolean overriding, boolean isInterface,
               String method, String submitText) {
            this.errors = Collections.unmodifiableList(new ArrayList<String>(errors));
            this.name = name;
            this.className = className;
            this.overriding = overriding;
            this.isInterface = isInterface;
            this.method = method;
            this.submitText = submitText;
        }

        public boolean ok() {
            return errors.isEmpty();
        }

        /** Each "line n: …" (or "the rule: …"). */
        public List<String> errors() {
            return errors;
        }

        /** The name as written on the RULE line (null when there is none). */
        public String name() {
            return name;
        }

        /** {@code skillroy:<name>}, as the module submits it (null when the name is missing). */
        public String storedName() {
            return name == null ? null : NAME_PREFIX + name;
        }

        /** The CLASS or INTERFACE name without a leading {@code ^} (null when missing). */
        public String className() {
            return className;
        }

        /** A leading {@code ^}: the rule applies to overriding methods of subclasses too. */
        public boolean overriding() {
            return overriding;
        }

        /** An INTERFACE rule. */
        public boolean isInterface() {
            return isInterface;
        }

        /** The METHOD line's text (null when missing). */
        public String method() {
            return method;
        }

        /**
         * The text the module submits when {@link #ok()}: the RULE line renamed {@code skillroy:<name>}, a user HELPER
         * line removed and the module's put after the CLASS or INTERFACE line; null otherwise.
         */
        public String submitText() {
            return submitText;
        }
    }

    /** Lints one rule text. Never throws; a null text is an error. */
    public static Result check(String text) {
        List<String> errors = new ArrayList<String>();
        if (text == null || text.trim().isEmpty()) {
            errors.add("the rule: no text");
            return new Result(errors, null, null, false, false, null, null);
        }
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            errors.add("the rule: " + bytes + " bytes, more than " + MAX_BYTES);
            return new Result(errors, null, null, false, false, null, null);
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int ruleLine = -1;
        int endLine = -1;
        int rules = 0;
        String name = null;
        String className = null;
        boolean overriding = false;
        boolean isInterface = false;
        int classLine = -1;
        String method = null;
        boolean inside = false;
        boolean bodyStarted = false;
        List<Integer> scanned = new ArrayList<Integer>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int n = i + 1;
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("RULE ") || line.equals("RULE")) {
                rules++;
                if (inside) {
                    errors.add("line " + n + ": a second RULE before ENDRULE");
                    continue;
                }
                inside = true;
                ruleLine = i;
                String given = line.substring(4).trim();
                if (rules > 1) {
                    errors.add("line " + n + ": exactly one RULE … ENDRULE is allowed");
                } else if (!NAME_PATTERN.matcher(given).matches()) {
                    errors.add("line " + n + ": the rule name must match " + NAME + ", found '" + clip(given) + "'");
                } else {
                    name = given;
                }
                continue;
            }
            if (!inside) {
                errors.add("line " + n + ": text outside RULE … ENDRULE (only blank lines and # comments may be there)");
                continue;
            }
            if (line.equals("ENDRULE")) {
                inside = false;
                endLine = i;
                continue;
            }
            if (line.startsWith("CLASS ") || line.startsWith("INTERFACE ")) {
                boolean iface = line.startsWith("INTERFACE ");
                if (classLine >= 0) {
                    errors.add("line " + n + ": a second CLASS or INTERFACE line");
                    continue;
                }
                if (bodyStarted) {
                    errors.add("line " + n + ": CLASS or INTERFACE after the rule's body started");
                }
                classLine = i;
                isInterface = iface;
                String given = line.substring(iface ? 10 : 6).trim();
                if (!CLASS_NAME.matcher(given).matches()) {
                    errors.add("line " + n + ": " + (iface ? "INTERFACE" : "CLASS") + " must be a fully qualified"
                            + " class name, found '" + clip(given) + "'");
                    continue;
                }
                overriding = given.startsWith("^");
                String bare = overriding ? given.substring(1) : given;
                for (String prefix : PROTECTED_CLASS_PREFIXES) {
                    if (bare.startsWith(prefix)) {
                        errors.add("line " + n + ": " + bare + " is in a protected package (" + prefix + "…): the"
                                + " agent protects itself and the JDK");
                    }
                }
                className = bare;
                continue;
            }
            if (line.startsWith("HELPER")) {
                if (!line.replaceAll("\\s+", " ").equals(HELPER_LINE)) {
                    errors.add("line " + n + ": a HELPER line may only name " + HELPER_CLASS + " (the module adds it)");
                }
                continue;
            }
            if (line.startsWith("IMPORT")) {
                errors.add("line " + n + ": IMPORT is not allowed");
                continue;
            }
            if (line.equals("COMPILE") || line.equals("NOCOMPILE")) {
                errors.add("line " + n + ": " + line + " is not allowed");
                continue;
            }
            if (line.startsWith("METHOD ")) {
                if (method != null) {
                    errors.add("line " + n + ": a second METHOD line");
                } else if (!METHOD_SPEC.matcher(line.substring(7).trim()).matches()) {
                    errors.add("line " + n + ": METHOD names no method");
                } else {
                    method = line.substring(7).trim();
                }
                scanned.add(i);
                continue;
            }
            bodyStarted = true;
            scanned.add(i);
        }
        if (rules == 0) {
            errors.add("the rule: no RULE line");
        }
        if (inside) {
            errors.add("the rule: no ENDRULE");
        }
        if (rules > 0 && classLine < 0) {
            errors.add("the rule: no CLASS or INTERFACE line");
        }
        if (rules > 0 && method == null) {
            errors.add("the rule: no METHOD line");
        }
        scan(lines, scanned, errors);
        String submit = null;
        if (errors.isEmpty()) {
            submit = submitText(lines, ruleLine, classLine, endLine, name);
        }
        return new Result(errors, name, className, overriding, isInterface, method, submit);
    }

    /** The deny-list scan over the given lines (comments and literals removed). */
    private static void scan(String[] lines, List<Integer> indexes, List<String> errors) {
        for (int i : indexes) {
            String code = withoutLiterals(lines[i]);
            code = SPACE_AROUND_DOT.matcher(code).replaceAll(".");
            Matcher m = DOTTED.matcher(code);
            while (m.find()) {
                if (m.start() > 0 && isIdentifierPart(code.charAt(m.start() - 1))) {
                    continue;
                }
                String dotted = m.group();
                String hit = denied(dotted);
                if (hit != null) {
                    errors.add("line " + (i + 1) + ": '" + hit + "' is not allowed (" + DENY.get(hit) + ")");
                }
            }
        }
    }

    /** The deny entry {@code dotted} (a dotted name, white space removed) hits, or null. */
    static String denied(String dotted) {
        String[] parts = dotted.split("\\.");
        for (String part : parts) {
            if (DENY.containsKey(part) && !isPrefixEntry(part)) {
                return part;
            }
        }
        for (int start = 0; start < parts.length; start++) {
            StringBuilder suffix = new StringBuilder(parts[start]);
            for (int j = start + 1; j < parts.length; j++) {
                suffix.append('.').append(parts[j]);
            }
            for (String entry : DENY.keySet()) {
                if (entry.endsWith(".") ? suffix.toString().startsWith(entry)
                        : isPrefixEntry(entry) && parts[start].startsWith(entry)) {
                    return entry;
                }
            }
        }
        return null;
    }

    private static boolean isPrefixEntry(String entry) {
        return entry.endsWith(".") || entry.equals("ClassLoader");
    }

    /** The line without its {@code #} comment and with string and character literals blanked. */
    static String withoutLiterals(String line) {
        StringBuilder out = new StringBuilder(line.length());
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                out.append(' ');
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                out.append(' ');
                continue;
            }
            if (c == '#') {
                break;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static String submitText(String[] lines, int ruleLine, int classLine, int endLine, String name) {
        StringBuilder out = new StringBuilder();
        for (int i = ruleLine; i <= endLine; i++) {
            String line = lines[i].trim();
            if (i == ruleLine) {
                out.append("RULE ").append(NAME_PREFIX).append(name).append('\n');
                continue;
            }
            if (line.startsWith("HELPER")) {
                continue;
            }
            out.append(lines[i]).append('\n');
            if (i == classLine) {
                out.append(HELPER_LINE).append('\n');
            }
        }
        return out.toString();
    }

    private static String clip(String s) {
        return s.length() > 100 ? s.substring(0, 100) + "…" : s;
    }
}
