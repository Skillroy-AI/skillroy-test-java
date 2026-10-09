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

package ai.skillroy.testjava.scenario.hooks;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.hook.HookSpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A domain pack's hook catalogue (phase6-contracts.md 5.1, decision D121): the hooks the controller may install on a
 * target, read from the pack's YAML resource by {@link HookCatalogues}, carried to the agent as the
 * {@link HookSpec}s of its enabled entries, and installed once per target, idempotent by {@link #hash()}.
 *
 * <p>The hash is {@code sha256:} over the canonical JSON of {@code {id, version, hooks: [{hash, enabled}…]}}, one
 * element per entry with the entry's {@link HookSpec#hash()} and its {@code enabled} flag, sorted by {@code hash}: a
 * change of any entry, of the version or of an {@code enabled} flag changes it. Nothing of a catalogue enters
 * {@code plan.json} or the environment fingerprint (decisions D127, D128).
 *
 * <p>Immutable; safe to share between threads.
 */
public final class HookCatalogue {

    /** A catalogue id: {@code [a-z][a-z0-9-]*}, by convention the pack's (or the overlay's) id. */
    public static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]*");

    /** The most hooks a catalogue holds: the ceiling of the agent's {@code hooks.maxClasses}. */
    public static final int MAX_HOOKS = 64;

    /** No hook: what a pack without a catalogue answers. Its id is empty and its version 0. */
    public static final HookCatalogue EMPTY = new HookCatalogue("", 0, null, List.of(), List.of());

    private final String id;
    private final int version;
    private final String application;
    private final List<HookEntry> hooks;
    private final List<String> packages;
    private final List<String> warnings;
    private final String hash;

    private HookCatalogue(String id, int version, String application, List<HookEntry> hooks, List<String> packages) {
        this.id = id;
        this.version = version;
        this.application = application;
        this.hooks = List.copyOf(hooks);
        this.packages = List.copyOf(packages);
        this.warnings = List.copyOf(warnings(this.hooks, this.packages));
        this.hash = hash(id, version, this.hooks);
    }

    /** The catalogue id (empty for {@link #EMPTY}). */
    public String id() {
        return id;
    }

    /** The catalogue version, a positive integer (0 for {@link #EMPTY}); part of the hash. */
    public int version() {
        return version;
    }

    /** Free text for the record (the application and version the catalogue was written for), or null. */
    public String application() {
        return application;
    }

    /** Every entry, enabled or not, in the catalogue's order. */
    public List<HookEntry> hooks() {
        return hooks;
    }

    /** The application's own packages, which decide the default of {@code enabled}. */
    public List<String> packages() {
        return packages;
    }

    /** The entries with {@code enabled: true}, in order. */
    public List<HookEntry> enabled() {
        return hooks.stream().filter(HookEntry::enabled).toList();
    }

    /** The {@link HookSpec}s of the enabled entries, in order: what {@code hook.install} sends. */
    public List<HookSpec> toSpecs() {
        return enabled().stream().map(HookEntry::toSpec).toList();
    }

    /** The entry with this id, or null. */
    public HookEntry entry(String hookId) {
        for (HookEntry entry : hooks) {
            if (entry.id().equals(hookId)) {
                return entry;
            }
        }
        return null;
    }

    /** True when the catalogue has no entry at all. */
    public boolean isEmpty() {
        return hooks.isEmpty();
    }

    /**
     * What the reader accepted but a reviewer should see (phase6-contracts.md 4.3, 5.1), one sentence each, printed by
     * {@code skillroy hooks status}: an entry enabled on a class outside {@link #packages()}, and a {@code CAPTURE} hook
     * whose method name suggests a secret without {@code secretArgs} or {@code secretResult}.
     */
    public List<String> warnings() {
        return warnings;
    }

    /** {@code sha256:} over {@code {id, version, hooks: [{hash, enabled}…]}} sorted by hash (see the class comment). */
    public String hash() {
        return hash;
    }

    /** {@code {id, version, hash, application?}}: what {@code hook.install}'s {@code catalogue} parameter carries. */
    public Map<String, Object> descriptor() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("version", (long) version);
        json.put("hash", hash);
        if (application != null) {
            json.put("application", application);
        }
        return json;
    }

    /** A builder that starts from {@code base}'s id, version, application, packages and entries. */
    public static Builder builder(HookCatalogue base) {
        return new Builder(Objects.requireNonNull(base, "base"));
    }

    /** A builder of an empty catalogue with this id and version. */
    public static Builder builder(String id, int version) {
        return new Builder(EMPTY).id(id).version(version);
    }

    /** True when {@code className} is in one of {@code packages} or a package below it. */
    static boolean inPackages(String className, List<String> packages) {
        for (String pkg : packages) {
            if (className.startsWith(pkg + ".")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> warnings(List<HookEntry> hooks, List<String> packages) {
        List<String> out = new ArrayList<>();
        for (HookEntry entry : hooks) {
            if (entry.enabled() && !inPackages(entry.target().className(), packages)) {
                out.add("hook " + entry.id() + " is enabled on " + entry.target().className() + ", a class outside"
                        + " the catalogue's packages " + packages + " (a third-party target: the author's licence"
                        + " question)");
            }
            String secret = entry.secretWarning();
            if (secret != null) {
                out.add(secret);
            }
        }
        return out;
    }

    private static String hash(String id, int version, List<HookEntry> hooks) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (HookEntry entry : hooks) {
            Map<String, Object> element = new LinkedHashMap<>();
            element.put("hash", entry.toSpec().hash());
            element.put("enabled", entry.enabled());
            entries.add(element);
        }
        entries.sort(Comparator.comparing((Map<String, Object> e) -> (String) e.get("hash"))
                .thenComparing(e -> (Boolean) e.get("enabled")));
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("version", (long) version);
        json.put("hooks", entries);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Json.writeCanonical(json)
                    .getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HookCatalogue other && id.equals(other.id) && version == other.version
                && Objects.equals(application, other.application) && hooks.equals(other.hooks)
                && packages.equals(other.packages);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version, application, hooks, packages);
    }

    @Override
    public String toString() {
        return "HookCatalogue[" + id + " v" + version + ", " + hooks.size() + " hooks, " + hash + "]";
    }

    /**
     * Builds a catalogue from another (phase6-contracts.md 5.1, 11.2): an overlay replaces entries by id, adds new
     * ones and turns entries off or on. Not thread-safe; the catalogue it builds is.
     */
    public static final class Builder {

        private String id;
        private int version;
        private String application;
        private final List<HookEntry> hooks;
        private final List<String> packages;

        private Builder(HookCatalogue base) {
            this.id = base.id;
            this.version = base.version;
            this.application = base.application;
            this.hooks = new ArrayList<>(base.hooks);
            this.packages = new ArrayList<>(base.packages);
        }

        /** Another catalogue id. */
        public Builder id(String newId) {
            this.id = Objects.requireNonNull(newId, "id");
            return this;
        }

        /** Another version. */
        public Builder version(int newVersion) {
            this.version = newVersion;
            return this;
        }

        /** Another application text (null: none). */
        public Builder application(String text) {
            this.application = text;
            return this;
        }

        /** Adds packages to those that decide the default of {@code enabled} (duplicates ignored). */
        public Builder packages(List<String> more) {
            for (String pkg : more) {
                if (!packages.contains(pkg)) {
                    packages.add(Objects.requireNonNull(pkg, "package"));
                }
            }
            return this;
        }

        /**
         * Replaces the entry with the same id, in its place.
         *
         * @throws IllegalStateException when there is no entry with that id (the {@code ResolutionCatalogue} precedent)
         */
        public Builder replace(HookEntry entry) {
            int i = indexOf(Objects.requireNonNull(entry, "entry").id());
            if (i < 0) {
                throw new IllegalStateException("no hook " + entry.id() + " to replace in catalogue " + id);
            }
            hooks.set(i, entry);
            return this;
        }

        /**
         * Adds an entry at the end.
         *
         * @throws IllegalStateException when an entry with that id exists already
         */
        public Builder add(HookEntry entry) {
            if (indexOf(Objects.requireNonNull(entry, "entry").id()) >= 0) {
                throw new IllegalStateException("hook " + entry.id() + " is already in catalogue " + id);
            }
            hooks.add(entry);
            return this;
        }

        /**
         * Turns the entry with this id off.
         *
         * @throws IllegalStateException when there is no entry with that id
         */
        public Builder disable(String hookId) {
            return setEnabled(hookId, false);
        }

        /**
         * Turns the entry with this id on.
         *
         * @throws IllegalStateException when there is no entry with that id
         */
        public Builder enable(String hookId) {
            return setEnabled(hookId, true);
        }

        private Builder setEnabled(String hookId, boolean on) {
            int i = indexOf(hookId);
            if (i < 0) {
                throw new IllegalStateException("no hook " + hookId + " in catalogue " + id);
            }
            hooks.set(i, hooks.get(i).withEnabled(on));
            return this;
        }

        private int indexOf(String hookId) {
            for (int i = 0; i < hooks.size(); i++) {
                if (hooks.get(i).id().equals(hookId)) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * The catalogue.
         *
         * @throws IllegalStateException when the id is malformed, the version is not positive, there are more than
         *     {@value HookCatalogue#MAX_HOOKS} entries, or an entry breaks a name rule of its spec
         */
        public HookCatalogue build() {
            if (hooks.isEmpty() && id.isEmpty()) {
                return EMPTY;
            }
            if (!ID.matcher(id).matches()) {
                throw new IllegalStateException("a catalogue id must match " + ID.pattern() + ", found \"" + id + "\"");
            }
            if (version <= 0) {
                throw new IllegalStateException("catalogue " + id + ": the version must be a positive integer");
            }
            if (hooks.size() > MAX_HOOKS) {
                throw new IllegalStateException("catalogue " + id + " has " + hooks.size() + " hooks, at most "
                        + MAX_HOOKS + " are allowed");
            }
            for (HookEntry entry : hooks) {
                HookSpec.Violation violation = entry.toSpec().violation();
                if (violation != null) {
                    throw new IllegalStateException("catalogue " + id + ", hook " + entry.id() + ": " + violation);
                }
            }
            return new HookCatalogue(id, version, application, hooks, packages);
        }
    }
}
