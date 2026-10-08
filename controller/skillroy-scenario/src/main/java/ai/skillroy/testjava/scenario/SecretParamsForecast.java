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

import ai.skillroy.testjava.protocol.SecretKeys;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/**
 * Whether the step and cleanup calls of a plan may announce a secret parameter (amendment A34) when they run, decided
 * before the run binds (phase4-contracts.md 6.4): the executor opens its web path without tracing when they may,
 * because a trace records the pages a secret is typed into and the secret mask cannot read it.
 *
 * <p>The forecast follows the rule by which the executor names a call's secret parameters (a template reference to a
 * joined credential field, or to an A25 fixture secret by the key rule, {@link FixtureSecrets#reachesSecret}) and
 * never says no where that rule can later say yes; a false yes costs only the trace:
 * <ul>
 *   <li>with the caller's bindings, both halves are judged as the run will judge them: the joined fields of
 *       {@link Bindings#isSecretField}, and the key rule on the caller's values, where a slot that the plan captures
 *       during the run counts as a value only the run knows;</li>
 *   <li>otherwise a reference to a slot that declares a {@code credential} counts, unless it names a field that the
 *       slot's fixture value has itself (the bind refuses a credential field of that name); and the key rule is
 *       applied to the plan's fixture values as the bind will produce them, where a value that only the bind
 *       produces (from a query slot or an oracle) counts as secret wherever a reference reaches it.</li>
 * </ul>
 *
 * <p>Immutable; safe from any thread.
 */
final class SecretParamsForecast {

    /** Stands for a value that only the bind or a step produces: any structure or text. */
    private static final Object UNKNOWN = new Object();

    /** As deep as {@link FixtureSecrets} looks; deeper counts as unknown. */
    private static final int MAX_DEPTH = 200;

    private final Plan plan;
    private final Bindings provided;
    /** The values the key rule judges: the caller's slots, else the plan's fixture slots. */
    private final Map<String, Object> keyRuleValues;

    private SecretParamsForecast(Plan plan, Bindings provided, String envId) {
        this.plan = plan;
        this.provided = provided;
        this.keyRuleValues = provided != null ? callerValues(plan, provided) : fixtureValues(plan, envId);
    }

    /**
     * The forecast for one run of the plan.
     *
     * @param plan the plan
     * @param provided the caller's bindings, or null when the run binds the plan
     * @param envId the environment id the run binds with (for {@code $env.id} in a fixture), or null
     */
    static SecretParamsForecast of(Plan plan, Bindings provided, String envId) {
        return new SecretParamsForecast(Objects.requireNonNull(plan, "plan"), provided, envId);
    }

    /**
     * True when a step or cleanup call of the plan may announce a secret parameter when it runs.
     *
     * @param plan the plan
     * @param provided the caller's bindings, or null when the run binds the plan
     * @param envId the environment id the run binds with, or null
     */
    static boolean mayAnnounce(Plan plan, Bindings provided, String envId) {
        SecretParamsForecast forecast = of(plan, provided, envId);
        return Stream.concat(plan.steps().stream().flatMap(step -> step.calls().stream()), plan.cleanup().stream())
                .anyMatch(forecast::mayAnnounce);
    }

    /** True when the call may name a secret parameter when it runs: an argument that references one may. */
    boolean mayAnnounce(Plan.Call call) {
        for (Object template : call.args().values()) {
            for (References.Ref ref : References.collect(template)) {
                if (joinedField(ref) || fixtureSecret(ref)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A34's first half: a joined credential field, or the whole value of a slot that has them. */
    private boolean joinedField(References.Ref ref) {
        if (provided != null) {
            return provided.isSecretField(ref.slot(), ref.fields());
        }
        Plan.Slot slot = plan.slot(ref.slot());
        if (slot == null || slot.credential() == null) {
            return false;
        }
        // A field of the fixture value itself is never joined: the bind refuses a credential field of that name.
        return ref.fields().isEmpty() || !(slot.value() instanceof Map<?, ?> value
                && value.containsKey(ref.fields().get(0)));
    }

    /** A34's second half: an A25 fixture secret by the key rule, in a slot the run's mask covers by that rule. */
    private boolean fixtureSecret(References.Ref ref) {
        if (!keyRuleValues.containsKey(ref.slot())) {
            return false;
        }
        Object reached;
        try {
            reached = navigate(keyRuleValues.get(ref.slot()), ref.fields(), ref.text());
        } catch (ResolutionException e) {
            // The run cannot resolve it either; judged by the path alone, as the executor judges it.
            return SecretKeys.looksSecret(ref.slot()) || ref.fields().stream().anyMatch(SecretKeys::looksSecret);
        }
        return holdsUnknown(reached, 0) || FixtureSecrets.reachesSecret(ref.slot(), ref.fields(), reached);
    }

    /** The caller's values; a slot the plan captures while it runs holds a value only the run knows. */
    private static Map<String, Object> callerValues(Plan plan, Bindings provided) {
        Map<String, Object> values = new LinkedHashMap<>(provided.values());
        for (Plan.Slot slot : plan.slots()) {
            if ("capture".equals(slot.kind()) && values.containsKey(slot.name())) {
                values.put(slot.name(), UNKNOWN);
            }
        }
        return values;
    }

    /**
     * The plan's fixture values as the bind will produce them: a reference to another fixture or to {@code $env.id}
     * is substituted as {@link Binder} substitutes it; {@link #UNKNOWN} stands for what only the bind produces (a query
     * slot, an oracle) and for what it refuses (a cycle, a reference that does not resolve).
     */
    private static Map<String, Object> fixtureValues(Plan plan, String envId) {
        Map<String, Plan.Slot> fixtures = new LinkedHashMap<>();
        for (Plan.Slot slot : plan.slots()) {
            if ("fixture".equals(slot.kind())) {
                fixtures.put(slot.name(), slot);
            }
        }
        Map<String, Object> bound = new LinkedHashMap<>();
        Set<String> inProgress = new HashSet<>();
        for (String name : fixtures.keySet()) {
            bind(name, plan, fixtures, envId, bound, inProgress);
        }
        return bound;
    }

    private static Object bind(String name, Plan plan, Map<String, Plan.Slot> fixtures, String envId,
                               Map<String, Object> bound, Set<String> inProgress) {
        if (bound.containsKey(name)) {
            return bound.get(name);
        }
        if (!inProgress.add(name)) {
            return UNKNOWN;
        }
        Object value = substitute(fixtures.get(name).value(), (slot, fields, text) -> {
            Object referenced;
            if (fixtures.containsKey(slot)) {
                referenced = bind(slot, plan, fixtures, envId, bound, inProgress);
            } else if ("env".equals(slot) && envId != null && plan.slot(slot) == null) {
                referenced = Map.of("id", envId);
            } else {
                return UNKNOWN;
            }
            try {
                return navigate(referenced, fields, text);
            } catch (ResolutionException e) {
                return UNKNOWN;
            }
        }, 0);
        inProgress.remove(name);
        bound.put(name, value);
        return value;
    }

    /** Looks up one whole reference of a fixture value; never fails ({@link #UNKNOWN} instead). */
    @FunctionalInterface
    private interface Lookup {
        Object lookup(String slot, List<String> fields, String text);
    }

    /**
     * The template with each string that is exactly one reference replaced by what {@code lookup} finds, as
     * {@link References#substitute} replaces it. A string that holds references inside a longer text stays as
     * written: the bind makes a text of it, and the key rule asks of a text only whether it is empty, which the
     * template is not.
     */
    private static Object substitute(Object template, Lookup lookup, int depth) {
        if (depth > MAX_DEPTH) {
            return UNKNOWN;
        }
        if (template instanceof String text) {
            Matcher whole = References.REFERENCE.matcher(text);
            if (!whole.matches()) {
                return text;
            }
            String dotted = whole.group(2);
            List<String> fields = dotted.isEmpty() ? List.of() : List.of(dotted.substring(1).split("\\."));
            return lookup.lookup(whole.group(1), fields, text);
        }
        if (template instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), substitute(e.getValue(), lookup, depth + 1));
            }
            return out;
        }
        if (template instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(substitute(item, lookup, depth + 1));
            }
            return out;
        }
        return template;
    }

    /** {@link References#navigate}, where a step into {@link #UNKNOWN} reaches {@link #UNKNOWN}. */
    private static Object navigate(Object value, List<String> fields, String text) throws ResolutionException {
        Object current = value;
        for (String field : fields) {
            if (current == UNKNOWN) {
                return UNKNOWN;
            }
            current = References.navigate(current, List.of(field), text);
        }
        return current;
    }

    /** True when the value is, or holds at any depth, a value only the bind or a step produces. */
    private static boolean holdsUnknown(Object value, int depth) {
        if (value == UNKNOWN || depth > MAX_DEPTH) {
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                if (holdsUnknown(item, depth + 1)) {
                    return true;
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                if (holdsUnknown(item, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }
}
