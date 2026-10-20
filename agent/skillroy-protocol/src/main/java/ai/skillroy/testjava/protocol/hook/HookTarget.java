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

package ai.skillroy.testjava.protocol.hook;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Where a hook goes (phase6-contracts.md section 4.1): JSON {@code {class, method, descriptors[], loader?,
 * codeSource?}}. The engine hooks exactly the descriptors given, each a JVM method descriptor of a method the class
 * itself declares.
 *
 * <p>{@code loader} narrows the target to one loader: null (every loader that defines the class), {@code "system"},
 * {@code "host"} (the agent's host loader), or the hexadecimal identity the agent reported for a loader in an earlier
 * probe answer ({@link HookProbe#loaderIds()}).
 *
 * <p>{@code codeSource} (phase8-contracts.md section 6.3, gap 113) narrows it to the copies whose code-source name (the
 * jar's file name and any entries inside it, never a directory: {@code CodeSources} in the agent API) the Java regular
 * expression matches <em>as a whole</em>: {@code lending-app.*} does not match {@code lending-app-scheduler.jar}
 * unless written so. A filter like {@code loader}: a copy from another jar is left alone. {@link #toJson()} writes the
 * key only when it is set, so every spec without it keeps its hash; {@link #fromJson} stays strict on every other key,
 * so an agent that predates the key refuses a qualified spec instead of hooking every copy (D560).
 *
 * <p>Immutable; safe to share between threads.
 */
public final class HookTarget {

    /** The longest {@code codeSource} regular expression (phase8-contracts.md section 6.3). */
    public static final int MAX_CODE_SOURCE_LENGTH = 200;

    private static final String TYPE = "HookTarget";

    private final String className;
    private final String method;
    private final List<String> descriptors;
    private final String loader;
    private final String codeSource;

    /**
     * A target without a code-source qualifier.
     *
     * @param className the class's binary name ({@code a.b.C$D}), not null
     * @param method the method's name, not null
     * @param descriptors the JVM method descriptors to hook, not null and not empty
     * @param loader the loader filter, or null for every loader
     * @throws IllegalArgumentException if {@code descriptors} is empty or holds a null
     */
    public HookTarget(String className, String method, List<String> descriptors, String loader) {
        this(className, method, descriptors, loader, null);
    }

    /**
     * @param className the class's binary name ({@code a.b.C$D}), not null
     * @param method the method's name, not null
     * @param descriptors the JVM method descriptors to hook, not null and not empty
     * @param loader the loader filter, or null for every loader
     * @param codeSource a regular expression on the whole code-source name, or null for every code source; checked
     *     by {@link HookSpec#violation()}, not here
     * @throws IllegalArgumentException if {@code descriptors} is empty or holds a null
     */
    public HookTarget(String className, String method, List<String> descriptors, String loader, String codeSource) {
        this.className = Objects.requireNonNull(className, "className");
        this.method = Objects.requireNonNull(method, "method");
        Objects.requireNonNull(descriptors, "descriptors");
        if (descriptors.isEmpty()) {
            throw new IllegalArgumentException("a hook target needs at least one descriptor");
        }
        List<String> copy = new ArrayList<String>(descriptors);
        if (copy.contains(null)) {
            throw new IllegalArgumentException("a descriptor is null");
        }
        this.descriptors = Collections.unmodifiableList(copy);
        this.loader = loader;
        this.codeSource = codeSource;
    }

    /** The binary name of the class. */
    public String className() {
        return className;
    }

    public String method() {
        return method;
    }

    /** Unmodifiable, never empty. */
    public List<String> descriptors() {
        return descriptors;
    }

    /** The loader filter, or null. */
    public String loader() {
        return loader;
    }

    /** The code-source qualifier (a regular expression on the whole code-source name), or null. */
    public String codeSource() {
        return codeSource;
    }

    /** A copy of this target with the code-source qualifier given (null removes it). */
    public HookTarget withCodeSource(String regex) {
        return new HookTarget(className, method, descriptors, loader, regex);
    }

    /**
     * Null, or why {@code regex} is not a code-source qualifier: longer than {@link #MAX_CODE_SOURCE_LENGTH}
     * characters, or not a Java regular expression. The one rule {@link HookSpec#violation()} and the controller's
     * catalogue reader (a {@code packages} entry's qualifier) apply.
     */
    public static String codeSourceViolation(String regex) {
        if (regex == null) {
            return null;
        }
        if (regex.length() > MAX_CODE_SOURCE_LENGTH) {
            return "codeSource longer than " + MAX_CODE_SOURCE_LENGTH + " characters";
        }
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return "not a regular expression: " + e.getDescription();
        }
        return null;
    }

    /** {@code {"class","method","descriptors","loader"?,"codeSource"?}}; a new map on each call. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("class", className);
        json.put("method", method);
        json.put("descriptors", new ArrayList<Object>(descriptors));
        if (loader != null) {
            json.put("loader", loader);
        }
        if (codeSource != null) {
            json.put("codeSource", codeSource);
        }
        return json;
    }

    /**
     * Reads the shape written by {@link #toJson()}.
     *
     * @throws JsonException on an unknown field (anything but the five keys), a missing class, method or descriptors,
     *     an empty descriptor list or a field of the wrong type
     */
    public static HookTarget fromJson(Map<String, Object> json) {
        HookFields.requireObject(json, TYPE);
        HookFields.refuseUnknown(json, TYPE, "class", "method", "descriptors", "loader", "codeSource");
        String className = HookFields.requireString(json, "class", TYPE);
        String method = HookFields.requireString(json, "method", TYPE);
        List<String> descriptors = HookFields.optStrings(json, "descriptors", TYPE);
        if (descriptors == null) {
            throw HookFields.missing("descriptors", TYPE);
        }
        if (descriptors.isEmpty()) {
            throw new JsonException(TYPE + ": field \"descriptors\" is empty; a hook pins at least one descriptor");
        }
        return new HookTarget(className, method, descriptors, HookFields.optString(json, "loader", TYPE),
                HookFields.optString(json, "codeSource", TYPE));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HookTarget)) {
            return false;
        }
        HookTarget other = (HookTarget) o;
        return className.equals(other.className) && method.equals(other.method)
                && descriptors.equals(other.descriptors) && Objects.equals(loader, other.loader)
                && Objects.equals(codeSource, other.codeSource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, method, descriptors, loader, codeSource);
    }

    @Override
    public String toString() {
        return TYPE + Json.write(toJson());
    }
}
