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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.scenario.resolve.Provenance;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What differs between two bindings files (phase2-contracts.md 9): the scenario and environment ids, the pack
 * version, the hashes, each fingerprint component, and per slot the value, args, strategy, resolver, statements,
 * calls, verifier outcome and credential, and per oracle the function, the value and the cross-check
 * ({@code oracles.<name>.crossCheck}, phase3-contracts.md 8.3). For reviewing a re-resolution and for comparing two
 * environments ({@code diff-bindings}); also shown at approval against an existing file.
 *
 * <p>Changes are ordered: ids, pack version, hashes, fingerprint components (sorted), then slots and oracles in the
 * order of the first file (slots only the second has come last). Immutable; safe to share between threads.
 *
 * @param changes the changes, in that order
 */
public record BindingsDiff(List<Change> changes) {

    /**
     * One difference.
     *
     * @param where the field: {@code "resolutions.assistant.value"}, {@code "env.components.lending.seed"}
     * @param before the value in the first file (null when absent)
     * @param after the value in the second file (null when absent)
     */
    public record Change(String where, Object before, Object after) {
    }

    /** Copies the list. */
    public BindingsDiff {
        changes = List.copyOf(changes);
    }

    /** True when the files do not differ. */
    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /** The slots (and {@code oracle.<name>}) that differ, in change order. */
    public Set<String> slots() {
        Set<String> out = new LinkedHashSet<>();
        for (Change change : changes) {
            if (change.where().startsWith("resolutions.")) {
                String rest = change.where().substring("resolutions.".length());
                out.add(rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest);
            } else if (change.where().startsWith("oracles.")) {
                String rest = change.where().substring("oracles.".length());
                out.add("oracle." + (rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest));
            }
        }
        return out;
    }

    static BindingsDiff between(BindingsFile a, BindingsFile b) {
        List<Change> out = new ArrayList<>();
        add(out, "scenarioId", a.scenarioId(), b.scenarioId());
        add(out, "envId", a.envId(), b.envId());
        add(out, "packVersion", a.packVersion(), b.packVersion());
        add(out, "planHash", a.planHash(), b.planHash());
        add(out, "resolveHash", a.resolveHash(), b.resolveHash());
        add(out, "env.fingerprint", a.env().hash(), b.env().hash());
        Set<String> components = new TreeSet<>(a.env().components().keySet());
        components.addAll(b.env().components().keySet());
        for (String name : components) {
            add(out, "env.components." + name, a.env().components().get(name), b.env().components().get(name));
        }
        Set<String> slots = new LinkedHashSet<>(a.resolutions().keySet());
        slots.addAll(b.resolutions().keySet());
        for (String slot : slots) {
            Resolution ra = a.resolutions().get(slot);
            Resolution rb = b.resolutions().get(slot);
            String where = "resolutions." + slot;
            if (ra == null || rb == null) {
                add(out, where, ra == null ? null : BindingsFile.resolutionJson(ra),
                        rb == null ? null : BindingsFile.resolutionJson(rb));
                continue;
            }
            add(out, where + ".value", ra.value(), rb.value());
            add(out, where + ".args", ra.args(), rb.args());
            add(out, where + ".strategy", ra.strategy().name(), rb.strategy().name());
            add(out, where + ".resolver", resolver(ra), resolver(rb));
            Map<String, Object> pa = ra.provenance() == null ? Map.of() : BindingsFile.provenanceJson(ra.provenance());
            Map<String, Object> pb = rb.provenance() == null ? Map.of() : BindingsFile.provenanceJson(rb.provenance());
            add(out, where + ".statements", pa.get("statements"), pb.get("statements"));
            add(out, where + ".calls", pa.get("calls"), pb.get("calls"));
            add(out, where + ".verifier", verifiers(ra), verifiers(rb));
            add(out, where + ".credential", ra.credential() == null ? null : ra.credential().toString(),
                    rb.credential() == null ? null : rb.credential().toString());
        }
        Set<String> oracles = new LinkedHashSet<>(a.oracles().keySet());
        oracles.addAll(b.oracles().keySet());
        for (String name : oracles) {
            OracleValue oa = a.oracles().get(name);
            OracleValue ob = b.oracles().get(name);
            String where = "oracles." + name;
            if (oa == null || ob == null) {
                add(out, where, oa == null ? null : oa.toJson(), ob == null ? null : ob.toJson());
                continue;
            }
            add(out, where + ".fn", oa.fn(), ob.fn());
            add(out, where + ".value", oa.value(), ob.value());
            add(out, where + ".crossCheck", oa.crossCheck() == null ? null : oa.crossCheck().toJson(),
                    ob.crossCheck() == null ? null : ob.crossCheck().toJson());
        }
        return new BindingsDiff(out);
    }

    private static String resolver(Resolution r) {
        Provenance p = r.provenance();
        return p == null ? null : p.resolverId();
    }

    private static List<Object> verifiers(Resolution r) {
        List<Object> out = new ArrayList<>();
        for (VerifierResult v : r.verified()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", v.verifierId());
            json.put("passed", v.passed());
            out.add(json);
        }
        return out;
    }

    private static void add(List<Change> out, String where, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            out.add(new Change(where, before, after));
        }
    }
}
