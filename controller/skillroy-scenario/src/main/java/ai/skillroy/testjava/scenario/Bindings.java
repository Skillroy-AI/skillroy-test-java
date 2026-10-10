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

import ai.skillroy.testjava.controller.OutputFiles;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CredentialRef;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprint;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;

/**
 * The values of a run's slots, against which every {@code $slot} reference in the plan is resolved. Produced by
 * {@link Binder} at run start (fixtures, and from Phase 2 query slots, oracles and the credential join) or supplied by
 * the caller; extended with captured slots while steps run; written to {@code bindings.json}.
 *
 * <p>Reference rules ({@link #substitute}): {@code $slot} and {@code $slot.a.b}; a string that is exactly one
 * reference becomes the referenced value with its type, a reference embedded in a longer string becomes the value's
 * text; {@code $$} is a literal dollar; a numeric field, {@code first} or {@code last} indexes a list. An unknown slot
 * or field is a {@link ResolutionException} naming it. Phase 2 adds {@code $oracle.<name>} (an oracle's output) and
 * {@code $env.id} (the environment id), unless the plan has slots of those names (a Phase 1 plan may).
 *
 * <p>Secrets (phase2-contracts.md 8.4): fields joined from {@code credentials.yaml} with
 * {@link #withCredential} resolve in {@link #substitute} (what the arguments of step and cleanup calls use) and
 * nowhere else: the executor resolves expectations, {@code db} checks, await specs and matchers against the view
 * without them, where such a reference is a {@code REFERENCE} failure. {@link #secrets()} knows their values.
 *
 * <p>Immutable (values are deep unmodifiable copies); safe to share between threads.
 */
public final class Bindings {

    private static final Bindings EMPTY = new Bindings(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            null, null, null, Map.of(), Map.of(), Map.of(), SecretMask.NONE);

    /** Slot values with joined secret fields: what call arguments resolve against. */
    private final Map<String, Object> values;
    /** Slot values without joined secret fields: what everything else resolves against. */
    private final Map<String, Object> publicValues;
    private final Map<String, String> strategies;
    private final Map<String, Map<String, String>> joined;
    private final Map<String, CredentialRef> credentials;
    private final Map<String, Object> captured;
    private final String envId;
    private final BindMode mode;
    private final EnvFingerprint env;
    private final Map<String, Resolution> resolutions;
    private final Map<String, OracleValue> oracles;
    private final Map<String, CrossCheckOutcome> crossChecks;
    private final SecretMask secrets;

    private Bindings(Map<String, Object> values, Map<String, Object> publicValues, Map<String, String> strategies,
                     Map<String, Map<String, String>> joined, Map<String, CredentialRef> credentials,
                     Map<String, Object> captured, String envId, BindMode mode, EnvFingerprint env,
                     Map<String, Resolution> resolutions, Map<String, OracleValue> oracles,
                     Map<String, CrossCheckOutcome> crossChecks, SecretMask secrets) {
        this.values = values;
        this.publicValues = publicValues;
        this.strategies = strategies;
        this.joined = joined;
        this.credentials = credentials;
        this.captured = captured;
        this.envId = envId;
        this.mode = mode;
        this.env = env;
        this.resolutions = resolutions;
        this.oracles = oracles;
        this.crossChecks = crossChecks;
        this.secrets = secrets;
    }

    /** No slots. */
    public static Bindings empty() {
        return EMPTY;
    }

    /**
     * Bindings with the given slot values, each recorded with strategy {@code FIXTURE}.
     *
     * @param values slot name to value (JSON-like); copied
     */
    public static Bindings of(Map<String, ?> values) {
        Map<String, String> strategies = new LinkedHashMap<>();
        values.keySet().forEach(name -> strategies.put(name, "FIXTURE"));
        return of(values, strategies);
    }

    /**
     * Bindings with the given slot values and the strategy that produced each.
     *
     * @param values slot name to value (JSON-like); copied
     * @param strategies slot name to strategy name; slots missing here are recorded as {@code FIXTURE}
     */
    public static Bindings of(Map<String, ?> values, Map<String, String> strategies) {
        Map<String, Object> copy = new LinkedHashMap<>();
        Map<String, String> strategyCopy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : values.entrySet()) {
            copy.put(Objects.requireNonNull(e.getKey(), "slot name"), Plan.freeze(e.getValue()));
            strategyCopy.put(e.getKey(), strategies.getOrDefault(e.getKey(), "FIXTURE"));
        }
        Map<String, Object> frozen = Collections.unmodifiableMap(copy);
        return new Bindings(frozen, frozen, Collections.unmodifiableMap(strategyCopy), Map.of(), Map.of(), Map.of(),
                null, null, null, Map.of(), Map.of(), Map.of(), SecretMask.NONE);
    }

    /** Slot name to value (joined credential fields included), in binding order; unmodifiable. */
    public Map<String, Object> values() {
        return values;
    }

    /** True if the slot is bound (possibly to null). */
    public boolean has(String slot) {
        return values.containsKey(slot);
    }

    /** The strategy that produced a slot's value ({@code FIXTURE}, {@code CANONICAL_SQL}, {@code REPLAY}, …). */
    public String strategy(String slot) {
        return strategies.get(slot);
    }

    /** The credentials entry a slot was joined to, or null. */
    public CredentialRef credential(String slot) {
        return credentials.get(slot);
    }

    /** The output of an oracle, or null when there is no such oracle (or its output is null). */
    public Object oracle(String name) {
        OracleValue value = oracles.get(name);
        return value == null ? null : value.value();
    }

    /** The oracles of the bind, by name, in evaluation order. */
    public Map<String, OracleValue> oracles() {
        return oracles;
    }

    /** The resolutions of the query slots, by slot, in evaluation order (empty for a plan without query slots). */
    public Map<String, Resolution> resolutions() {
        return resolutions;
    }

    /**
     * What became of each oracle's cross-check in the bind (phase3-contracts.md 6), by oracle name, in evaluation
     * order: agreements and skips (a disagreement fails the bind). Empty when no oracle has one, or the bindings were
     * not made by a full bind.
     */
    public Map<String, CrossCheckOutcome> crossChecks() {
        return crossChecks;
    }

    /** The slots captured from events so far, by name. */
    public Map<String, Object> captured() {
        return captured;
    }

    /** The environment id of the bind, or null when the bindings were not made by a Phase 2 bind. */
    public String envId() {
        return envId;
    }

    /** The joined credential field values, for a run's mask. */
    java.util.Set<String> secretValues() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Map<String, String> fields : joined.values()) {
            out.addAll(fields.values());
        }
        return out;
    }

    /** True when {@code $slot.field…} names a joined credential field (A34: such a call argument is a secret). */
    boolean isSecretField(String slot, List<String> fields) {
        Map<String, String> secret = joined.get(slot);
        return secret != null && (fields.isEmpty() || secret.containsKey(fields.get(0)));
    }

    /** A {@link SecretMask} of every joined credential field value. */
    public SecretMask secrets() {
        return secrets;
    }

    /**
     * Resolves one reference, written with or without the leading {@code $} ({@code "$item.code"} or
     * {@code "item.code"}), against the values with joined fields.
     *
     * @throws ResolutionException if the slot or a field is unknown
     */
    public Object lookup(String reference) throws ResolutionException {
        String text = reference.startsWith("$") ? reference : "$" + reference;
        Matcher m = References.REFERENCE.matcher(text);
        if (!m.matches()) {
            throw new ResolutionException(reference, "'" + reference + "' is not a reference ($slot or $slot.field)");
        }
        List<String> fields = m.group(2).isEmpty() ? List.of() : List.of(m.group(2).substring(1).split("\\."));
        return lookup(m.group(1), fields, text);
    }

    /**
     * A copy of {@code template} (any JSON-like value) with every reference substituted, joined credential fields
     * included: what the arguments of step and cleanup calls use.
     */
    public Object substitute(Object template) throws ResolutionException {
        return References.substitute(template, this::lookup);
    }

    /** {@link #substitute} without joined credential fields: for expectations, checks, awaits and matchers. */
    Object substitutePublic(Object template) throws ResolutionException {
        return References.substitute(template, this::lookupPublic);
    }

    /**
     * The full lookup (call arguments). A joined credential field is a text with no fields of its own: a reference
     * that goes past one is refused naming the slot and the field only, so that no message ever describes the
     * secret (navigating into a text would quote it).
     */
    Object lookup(String slot, List<String> fields, String text) throws ResolutionException {
        Map<String, String> secret = joined.get(slot);
        if (secret != null && fields.size() > 1 && secret.containsKey(fields.get(0))) {
            throw new ResolutionException(ResolutionException.REFERENCE, text, "cannot resolve " + text + ": '"
                    + fields.get(0) + "' is a credential field of slot '" + slot + "', a text without fields");
        }
        return lookup(values, slot, fields, text);
    }

    /** A lookup that refuses a joined credential field with a message that says why. */
    Object lookupPublic(String slot, List<String> fields, String text) throws ResolutionException {
        Map<String, String> secret = joined.get(slot);
        if (secret != null && !fields.isEmpty() && secret.containsKey(fields.get(0))) {
            throw new ResolutionException(ResolutionException.REFERENCE, text, "cannot resolve " + text
                    + " here: '" + fields.get(0) + "' is a credential field of slot '" + slot + "', usable only in"
                    + " the arguments of step and cleanup calls");
        }
        return lookup(publicValues, slot, fields, text);
    }

    private Object lookup(Map<String, Object> source, String slot, List<String> fields, String text)
            throws ResolutionException {
        if (!source.containsKey(slot)) {
            if ("oracle".equals(slot) && !oracles.isEmpty()) {
                if (fields.isEmpty() || !oracles.containsKey(fields.get(0))) {
                    throw new ResolutionException(text, "cannot resolve " + text + ": no oracle '"
                            + (fields.isEmpty() ? "" : fields.get(0)) + "'; oracles: " + oracles.keySet());
                }
                return References.navigate(oracles.get(fields.get(0)).value(), fields.subList(1, fields.size()),
                        text);
            }
            if ("env".equals(slot) && envId != null) {
                return References.navigate(Map.of("id", envId), fields, text);
            }
            throw new ResolutionException(text, "cannot resolve " + text + ": unknown slot '" + slot + "'"
                    + (source.isEmpty() ? " (no slots are bound)" : "; slots: " + source.keySet()));
        }
        return References.navigate(source.get(slot), fields, text);
    }

    /**
     * These bindings with a slot captured from an event (phase2-contracts.md 3.4). A captured slot is never written
     * to a bindings file; it appears under {@code captured} in the run's own {@code bindings.json}.
     */
    public Bindings withCaptured(String slot, Object value) {
        Objects.requireNonNull(slot, "slot");
        Map<String, Object> newValues = new LinkedHashMap<>(values);
        newValues.put(slot, Plan.freeze(value));
        Map<String, Object> newPublic = new LinkedHashMap<>(publicValues);
        newPublic.put(slot, Plan.freeze(value));
        Map<String, Object> newCaptured = new LinkedHashMap<>(captured);
        newCaptured.put(slot, Plan.freeze(value));
        return new Bindings(Collections.unmodifiableMap(newValues), Collections.unmodifiableMap(newPublic),
                strategies, joined, credentials, Collections.unmodifiableMap(newCaptured), envId, mode, env,
                resolutions, oracles, crossChecks, secrets);
    }

    /**
     * The credential join for a caller that builds bindings itself (phase2-contracts.md 6.6, 8.4): adds the secret
     * fields to the slot's value (for call arguments only) and their values to {@link #secrets()}.
     *
     * @throws IllegalArgumentException if the slot is not bound, its value is not a mapping, or a secret field's name
     *     is already a field of the value
     */
    public Bindings withCredential(String slot, Map<String, String> secretFields) {
        return withCredential(slot, secretFields, null);
    }

    /** {@link #withCredential(String, Map)}, recording which entry the fields came from. */
    Bindings withCredential(String slot, Map<String, String> secretFields, CredentialRef ref) {
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(secretFields, "secretFields");
        if (!values.containsKey(slot)) {
            throw new IllegalArgumentException("slot '" + slot + "' is not bound");
        }
        if (!(values.get(slot) instanceof Map<?, ?> value)) {
            throw new IllegalArgumentException("slot '" + slot + "' has no mapping value to join credential fields"
                    + " to");
        }
        Map<String, Object> joinedValue = new LinkedHashMap<>();
        value.forEach((k, v) -> joinedValue.put(String.valueOf(k), v));
        for (Map.Entry<String, String> field : secretFields.entrySet()) {
            if (joinedValue.containsKey(field.getKey())) {
                throw new IllegalArgumentException("slot '" + slot + "': the credential field '" + field.getKey()
                        + "' is already a field of the value");
            }
            joinedValue.put(field.getKey(), field.getValue());
        }
        Map<String, Object> newValues = new LinkedHashMap<>(values);
        newValues.put(slot, Plan.freeze(joinedValue));
        Map<String, Map<String, String>> newJoined = new LinkedHashMap<>(joined);
        Map<String, String> fields = new LinkedHashMap<>(newJoined.getOrDefault(slot, Map.of()));
        fields.putAll(secretFields);
        newJoined.put(slot, Collections.unmodifiableMap(fields));
        Map<String, CredentialRef> newCredentials = new LinkedHashMap<>(credentials);
        if (ref != null) {
            newCredentials.put(slot, ref);
        }
        return new Bindings(Collections.unmodifiableMap(newValues), publicValues, strategies,
                Collections.unmodifiableMap(newJoined), Collections.unmodifiableMap(newCredentials), captured, envId,
                mode, env, resolutions, oracles, crossChecks, secrets.plus(secretFields.values()));
    }

    /**
     * These bindings with what a full bind knows besides the values ({@code $oracle}, {@code $env}, the cross-check
     * outcomes, output).
     */
    Bindings withBind(String newEnvId, BindMode newMode, EnvFingerprint newEnv, Map<String, String> newStrategies,
                      Map<String, Resolution> newResolutions, Map<String, OracleValue> newOracles,
                      Map<String, CredentialRef> newCredentials, Map<String, CrossCheckOutcome> newCrossChecks) {
        Map<String, String> strategyCopy = new LinkedHashMap<>(strategies);
        strategyCopy.putAll(newStrategies);
        Map<String, CredentialRef> credentialCopy = new LinkedHashMap<>(credentials);
        credentialCopy.putAll(newCredentials);
        return new Bindings(values, publicValues, Collections.unmodifiableMap(strategyCopy), joined,
                Collections.unmodifiableMap(credentialCopy), captured, newEnvId, newMode, newEnv,
                Collections.unmodifiableMap(new LinkedHashMap<>(newResolutions)),
                Collections.unmodifiableMap(new LinkedHashMap<>(newOracles)),
                Collections.unmodifiableMap(new LinkedHashMap<>(newCrossChecks)), secrets);
    }

    /**
     * The run's {@code bindings.json} object (phase2-contracts.md 11.3): {@code {scenarioId, planHash, resolveHash,
     * mode, envId, env, resolutions: {slot: {strategy, value, …}}, oracles, captured}}; an oracle whose cross-check
     * ran or was skipped carries {@code crossCheck: {check, agreed}} or {@code {check, skipped: true}}
     * (phase3-contracts.md 8.3). Values are written without joined credential fields; the caller masks the result. A
     * new map on each call.
     */
    public Map<String, Object> toJson(Plan plan) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("scenarioId", plan.scenarioId());
        json.put("planHash", plan.hash());
        json.put("resolveHash", plan.resolveHash());
        json.put("mode", mode == null ? null : mode.label());
        json.put("envId", envId);
        if (env != null) {
            Map<String, Object> envJson = new LinkedHashMap<>();
            envJson.put("fingerprint", env.hash());
            envJson.put("components", new LinkedHashMap<>(env.components()));
            json.put("env", envJson);
        } else {
            json.put("env", null);
        }
        Map<String, Object> resolutionJson = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : publicValues.entrySet()) {
            String slot = e.getKey();
            if (captured.containsKey(slot)) {
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("strategy", strategies.getOrDefault(slot, "FIXTURE"));
            r.put("value", e.getValue());
            Resolution resolution = resolutions.get(slot);
            if (resolution != null) {
                if (!resolution.strategy().name().equals(r.get("strategy"))) {
                    r.put("recordedStrategy", resolution.strategy().name());
                }
                r.put("query", resolution.query());
                r.put("args", resolution.args());
                r.put("argsHash", resolution.argsHash());
                r.put("resolver", resolution.provenance() == null ? null : resolution.provenance().resolverId());
                List<Object> verified = new ArrayList<>();
                for (VerifierResult result : resolution.verified()) {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("id", result.verifierId());
                    v.put("passed", result.passed());
                    v.put("reason", result.reason());
                    verified.add(v);
                }
                r.put("verifier", verified);
            }
            CredentialRef ref = credentials.get(slot);
            if (ref != null) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("realm", ref.realm());
                c.put("id", ref.id());
                r.put("credential", c);
            }
            resolutionJson.put(slot, r);
        }
        json.put("resolutions", resolutionJson);
        Map<String, Object> oracleJson = new LinkedHashMap<>();
        for (OracleValue oracle : oracles.values()) {
            Map<String, Object> o = oracle.toJson();
            CrossCheckOutcome outcome = crossChecks.get(oracle.name());
            if (outcome != null) {
                o.put("crossCheck", outcome.bindingsJson());
            }
            oracleJson.put(oracle.name(), o);
        }
        json.put("oracles", oracleJson);
        json.put("captured", new LinkedHashMap<>(captured));
        return json;
    }

    /**
     * Writes {@link #toJson(Plan)} pretty-printed as UTF-8, creating parent directories (unmasked); created exclusively
     * in a directory of the current user, never through what is in its place (Phase 6 security fix, pass 2,
     * P2-L8-F3).
     */
    public void write(Path file, Plan plan) throws IOException {
        OutputFiles.writeString(file, Json.writePretty(toJson(plan)));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Bindings other && values.equals(other.values) && strategies.equals(other.strategies)
                && oracles.equals(other.oracles) && Objects.equals(envId, other.envId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values, strategies, oracles, envId);
    }

    /** The slot names only: never a value. */
    @Override
    public String toString() {
        return "Bindings" + values.keySet();
    }
}
