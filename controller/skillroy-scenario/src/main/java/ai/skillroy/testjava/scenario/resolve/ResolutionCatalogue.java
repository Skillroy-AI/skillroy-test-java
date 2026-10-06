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

package ai.skillroy.testjava.scenario.resolve;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A pack's query kinds, resolvers, verifiers, oracles and oracle cross-checks ({@code DomainPack.resolution()}), read
 * once per compile and once per run. Order matters: resolvers are tried in registration order, and a slot without
 * {@code verify:} gets the kind's verifiers in registration order. Cross-checks are found by id, one per id
 * (phase3-contracts.md 5.2).
 *
 * <p>A variant pack (an overlay for one installation) starts from the base pack's catalogue with
 * {@link #builder(ResolutionCatalogue)} and {@link Builder#replace replaces} objects by id: plans name roles (kinds,
 * verifier ids, oracle functions), never resolver ids (decision D24), so a variant answers them with other code
 * without recompiling the base's scenarios.
 *
 * <p>{@link Builder#build()} validates: two query types sharing a kind, or one query class under two kinds; a
 * resolver or verifier for an undeclared query class; two resolvers with one id, two oracles with one function name,
 * or two verifiers with one id <b>and</b> one query class; a resolver whose strategy is not {@code CANONICAL_SQL} or
 * {@code CANONICAL_JAVA}; an id that is {@code inference} or starts with {@code inference.} (reserved for what the
 * controller contributes from Phase 5 on); a query, value or oracle type its codec does not support; and, for
 * cross-checks (phase3-contracts.md 5.2), two with one id, one whose {@code oracleFn()} names no registered oracle,
 * one whose input or output type does not equal its oracle's (compared as {@code java.lang.reflect.Type}s after
 * {@code GenericTypes} resolution) or is not supported by the codec. Each is an {@link IllegalStateException},
 * reported as a compile error naming the pack.
 *
 * <p>Immutable once built; safe to share between threads (the registered objects must be too).
 */
public final class ResolutionCatalogue {

    /** A catalogue with nothing in it: the default of {@code DomainPack.resolution()}. */
    public static final ResolutionCatalogue EMPTY = new ResolutionCatalogue(List.of(), List.of(), List.of(),
            List.of(), List.of());

    private final List<QueryType<?, ?>> queries;
    private final List<Resolver<?, ?>> resolvers;
    private final List<Verifier<?, ?>> verifiers;
    private final List<Oracle<?, ?>> oracles;
    private final List<CrossCheck<?, ?>> crossChecks;
    private final Map<String, QueryType<?, ?>> byKind = new LinkedHashMap<>();
    private final Map<String, Oracle<?, ?>> byFn = new LinkedHashMap<>();
    private final Map<String, CrossCheck<?, ?>> byCheckId = new LinkedHashMap<>();

    private ResolutionCatalogue(List<QueryType<?, ?>> queries, List<Resolver<?, ?>> resolvers,
                                List<Verifier<?, ?>> verifiers, List<Oracle<?, ?>> oracles,
                                List<CrossCheck<?, ?>> crossChecks) {
        this.queries = List.copyOf(queries);
        this.resolvers = List.copyOf(resolvers);
        this.verifiers = List.copyOf(verifiers);
        this.oracles = List.copyOf(oracles);
        this.crossChecks = List.copyOf(crossChecks);
        for (QueryType<?, ?> type : this.queries) {
            byKind.put(type.kind(), type);
        }
        for (Oracle<?, ?> oracle : this.oracles) {
            byFn.put(oracle.fn(), oracle);
        }
        for (CrossCheck<?, ?> check : this.crossChecks) {
            byCheckId.put(check.id(), check);
        }
    }

    /** An empty builder. */
    public static Builder builder() {
        return new Builder(EMPTY);
    }

    /** A builder that starts with everything {@code base} holds, in its order: how a variant pack overlays a base. */
    public static Builder builder(ResolutionCatalogue base) {
        return new Builder(Objects.requireNonNull(base, "base"));
    }

    /** The query type of a kind, or null. */
    public QueryType<?, ?> query(String kind) {
        return byKind.get(kind);
    }

    /** The declared kinds, in registration order. */
    public Set<String> kinds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(byKind.keySet()));
    }

    /** The resolvers of a kind, in the order they are tried; empty for an unknown kind. */
    public List<Resolver<?, ?>> resolvers(String kind) {
        QueryType<?, ?> type = byKind.get(kind);
        List<Resolver<?, ?>> out = new ArrayList<>();
        if (type != null) {
            for (Resolver<?, ?> resolver : resolvers) {
                if (resolver.queryClass() == type.queryClass()) {
                    out.add(resolver);
                }
            }
        }
        return List.copyOf(out);
    }

    /** The verifiers of a kind, in registration order (the default for a slot without {@code verify:}). */
    public List<Verifier<?, ?>> verifiers(String kind) {
        QueryType<?, ?> type = byKind.get(kind);
        List<Verifier<?, ?>> out = new ArrayList<>();
        if (type != null) {
            for (Verifier<?, ?> verifier : verifiers) {
                if (verifier.queryClass() == type.queryClass()) {
                    out.add(verifier);
                }
            }
        }
        return List.copyOf(out);
    }

    /** The verifier with this id for this kind, or null when the kind has none of that id. */
    public Verifier<?, ?> verifier(String kind, String id) {
        for (Verifier<?, ?> verifier : verifiers(kind)) {
            if (verifier.id().equals(id)) {
                return verifier;
            }
        }
        return null;
    }

    /** The oracle with this function name, or null. */
    public Oracle<?, ?> oracle(String fn) {
        return byFn.get(fn);
    }

    /** The oracle function names, in registration order. */
    public Set<String> oracleFns() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(byFn.keySet()));
    }

    /** The cross-check with this id, or null when there is none. */
    public CrossCheck<?, ?> crossCheck(String id) {
        return byCheckId.get(id);
    }

    /** Every cross-check declared for the oracle function {@code oracleFn}, sorted by id; empty when none. */
    public List<CrossCheck<?, ?>> crossChecks(String oracleFn) {
        List<CrossCheck<?, ?>> out = new ArrayList<>();
        for (CrossCheck<?, ?> check : crossChecks) {
            if (Objects.equals(check.oracleFn(), oracleFn)) {
                out.add(check);
            }
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return List.copyOf(out);
    }

    /** True when nothing is registered. */
    public boolean isEmpty() {
        return queries.isEmpty() && resolvers.isEmpty() && verifiers.isEmpty() && oracles.isEmpty()
                && crossChecks.isEmpty();
    }

    /** The counts only. */
    @Override
    public String toString() {
        return "ResolutionCatalogue[" + queries.size() + " kinds, " + resolvers.size() + " resolvers, "
                + verifiers.size() + " verifiers, " + oracles.size() + " oracles, " + crossChecks.size()
                + " cross-checks]";
    }

    /** Collects the catalogue; used by one thread while a pack builds it. */
    public static final class Builder {

        private final List<QueryType<?, ?>> queries;
        private final List<Resolver<?, ?>> resolvers;
        private final List<Verifier<?, ?>> verifiers;
        private final List<Oracle<?, ?>> oracles;
        private final List<CrossCheck<?, ?>> crossChecks;

        private Builder(ResolutionCatalogue base) {
            queries = new ArrayList<>(base.queries);
            resolvers = new ArrayList<>(base.resolvers);
            verifiers = new ArrayList<>(base.verifiers);
            oracles = new ArrayList<>(base.oracles);
            crossChecks = new ArrayList<>(base.crossChecks);
        }

        /** Declares a query kind. */
        public <Q extends Query<T>, T> Builder query(QueryType<Q, T> type) {
            queries.add(Objects.requireNonNull(type, "type"));
            return this;
        }

        /** Adds a resolver; the order of calls is the order resolvers are tried. */
        public Builder resolver(Resolver<?, ?> resolver) {
            resolvers.add(Objects.requireNonNull(resolver, "resolver"));
            return this;
        }

        /** Adds a verifier; the order of calls is the default order for a slot without {@code verify:}. */
        public Builder verifier(Verifier<?, ?> verifier) {
            verifiers.add(Objects.requireNonNull(verifier, "verifier"));
            return this;
        }

        /** Adds an oracle. */
        public Builder oracle(Oracle<?, ?> oracle) {
            oracles.add(Objects.requireNonNull(oracle, "oracle"));
            return this;
        }

        /** Adds a cross-check; registration order is irrelevant (one per id, found by id and by oracle function). */
        public Builder crossCheck(CrossCheck<?, ?> check) {
            crossChecks.add(Objects.requireNonNull(check, "check"));
            return this;
        }

        /**
         * Replaces the registered resolver with the same id, keeping its position.
         *
         * @throws IllegalStateException when there is nothing to replace
         */
        public Builder replace(Resolver<?, ?> resolver) {
            Objects.requireNonNull(resolver, "resolver");
            for (int i = 0; i < resolvers.size(); i++) {
                if (Objects.equals(resolvers.get(i).id(), resolver.id())) {
                    resolvers.set(i, resolver);
                    return this;
                }
            }
            throw new IllegalStateException("no resolver " + resolver.id() + " to replace");
        }

        /**
         * Replaces the registered verifier with the same id and the same query class, keeping its position.
         *
         * @throws IllegalStateException when there is nothing to replace
         */
        public Builder replace(Verifier<?, ?> verifier) {
            Objects.requireNonNull(verifier, "verifier");
            for (int i = 0; i < verifiers.size(); i++) {
                Verifier<?, ?> old = verifiers.get(i);
                if (Objects.equals(old.id(), verifier.id()) && old.queryClass() == verifier.queryClass()) {
                    verifiers.set(i, verifier);
                    return this;
                }
            }
            throw new IllegalStateException("no verifier " + verifier.id() + " for "
                    + (verifier.queryClass() == null ? "null" : verifier.queryClass().getSimpleName())
                    + " to replace");
        }

        /**
         * Replaces the registered oracle with the same function name, keeping its position.
         *
         * @throws IllegalStateException when there is nothing to replace
         */
        public Builder replace(Oracle<?, ?> oracle) {
            Objects.requireNonNull(oracle, "oracle");
            for (int i = 0; i < oracles.size(); i++) {
                if (Objects.equals(oracles.get(i).fn(), oracle.fn())) {
                    oracles.set(i, oracle);
                    return this;
                }
            }
            throw new IllegalStateException("no oracle " + oracle.fn() + " to replace");
        }

        /**
         * Replaces the registered cross-check with the same id, keeping its position: how a variant pack overlays a
         * base pack's check (decision D24).
         *
         * @throws IllegalStateException when there is nothing to replace
         */
        public Builder replace(CrossCheck<?, ?> check) {
            Objects.requireNonNull(check, "check");
            for (int i = 0; i < crossChecks.size(); i++) {
                if (Objects.equals(crossChecks.get(i).id(), check.id())) {
                    crossChecks.set(i, check);
                    return this;
                }
            }
            throw new IllegalStateException("no cross-check " + check.id() + " to replace");
        }

        /**
         * The catalogue, validated as the class comment describes.
         *
         * @throws IllegalStateException naming the first problem
         */
        public ResolutionCatalogue build() {
            Map<String, QueryType<?, ?>> kinds = new HashMap<>();
            Map<Class<?>, String> classes = new HashMap<>();
            for (QueryType<?, ?> type : queries) {
                if (kinds.put(type.kind(), type) != null) {
                    throw new IllegalStateException("two query types share the kind " + type.kind());
                }
                String other = classes.put(type.queryClass(), type.kind());
                if (other != null) {
                    throw new IllegalStateException("query class " + type.queryClass().getSimpleName()
                            + " is registered under two kinds: " + other + " and " + type.kind());
                }
                unsupported(type.codec(), type.queryClass(), "query class of kind " + type.kind());
                unsupported(type.codec(), type.valueType(), "value type of kind " + type.kind());
            }
            Set<String> resolverIds = new LinkedHashSet<>();
            for (Resolver<?, ?> resolver : resolvers) {
                String id = id(resolver.id(), "resolver");
                if (!resolverIds.add(id)) {
                    throw new IllegalStateException("two resolvers share the id " + id);
                }
                declared(classes, resolver.queryClass(), "resolver " + id);
                Strategy strategy = resolver.strategy();
                if (strategy != Strategy.CANONICAL_SQL && strategy != Strategy.CANONICAL_JAVA) {
                    throw new IllegalStateException("resolver " + id + " has strategy " + strategy + "; a pack's"
                            + " resolver must be CANONICAL_SQL or CANONICAL_JAVA (model strategies need the inference"
                            + " module, which is not part of this version)");
                }
            }
            Set<String> verifierKeys = new LinkedHashSet<>();
            for (Verifier<?, ?> verifier : verifiers) {
                String id = id(verifier.id(), "verifier");
                declared(classes, verifier.queryClass(), "verifier " + id);
                if (!verifierKeys.add(id + "\u0000" + verifier.queryClass().getName())) {
                    throw new IllegalStateException("two verifiers share the id " + id + " and the query class "
                            + verifier.queryClass().getSimpleName());
                }
                List<String> requires = verifier.requires();
                if (requires == null) {
                    throw new IllegalStateException("verifier " + id + " returned null from requires()");
                }
            }
            Map<String, Type[]> oracleTypes = new HashMap<>();
            for (Oracle<?, ?> oracle : oracles) {
                String fn = id(oracle.fn(), "oracle");
                if (oracleTypes.containsKey(fn)) {
                    throw new IllegalStateException("two oracles share the function name " + fn);
                }
                Type input;
                Type output;
                try {
                    input = oracle.inputType();
                    output = oracle.outputType();
                } catch (RuntimeException e) {
                    throw new IllegalStateException("oracle " + fn + ": " + e.getMessage(), e);
                }
                unsupported(RecordCodec.DEFAULT, input, "input type of oracle " + fn);
                unsupported(RecordCodec.DEFAULT, output, "output type of oracle " + fn);
                oracleTypes.put(fn, new Type[] {input, output});
            }
            Set<String> checkIds = new LinkedHashSet<>();
            for (CrossCheck<?, ?> check : crossChecks) {
                String id = id(check.id(), "cross-check");
                if (!checkIds.add(id)) {
                    throw new IllegalStateException("two cross-checks share the id " + id);
                }
                String fn = check.oracleFn();
                Type[] oracle = fn == null ? null : oracleTypes.get(fn);
                if (oracle == null) {
                    throw new IllegalStateException("cross-check " + id + " names the oracle function " + fn
                            + ", which no oracle declares");
                }
                Type input;
                Type output;
                List<String> requires;
                try {
                    input = check.inputType();
                    output = check.outputType();
                    requires = check.requires();
                } catch (RuntimeException e) {
                    throw new IllegalStateException("cross-check " + id + ": " + e.getMessage(), e);
                }
                if (requires == null) {
                    throw new IllegalStateException("cross-check " + id + " returned null from requires()");
                }
                unsupported(RecordCodec.DEFAULT, input, "input type of cross-check " + id);
                unsupported(RecordCodec.DEFAULT, output, "output type of cross-check " + id);
                if (!oracle[0].equals(input) || !oracle[1].equals(output)) {
                    throw new IllegalStateException("cross-check " + id + " has input " + input.getTypeName()
                            + " and output " + output.getTypeName() + ", but oracle " + fn + " has input "
                            + oracle[0].getTypeName() + " and output " + oracle[1].getTypeName() + "; a check's"
                            + " types must equal its oracle's");
                }
            }
            return new ResolutionCatalogue(queries, resolvers, verifiers, oracles, crossChecks);
        }

        private static String id(String id, String what) {
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("a " + what + " has no id");
            }
            if (id.equals("inference") || id.startsWith("inference.")) {
                throw new IllegalStateException(what + " id " + id + ": the prefix 'inference' is reserved for"
                        + " what the controller contributes");
            }
            return id;
        }

        private static void declared(Map<Class<?>, String> classes, Class<?> queryClass, String what) {
            if (queryClass == null || !classes.containsKey(queryClass)) {
                throw new IllegalStateException(what + " names the query class "
                        + (queryClass == null ? "null" : queryClass.getSimpleName())
                        + ", which no query type declares");
            }
        }

        private static void unsupported(Codec codec, Type type, String what) {
            String problem = codec.unsupported(type);
            if (problem != null) {
                throw new IllegalStateException(what + " is not supported by its codec: " + problem);
            }
        }
    }
}
