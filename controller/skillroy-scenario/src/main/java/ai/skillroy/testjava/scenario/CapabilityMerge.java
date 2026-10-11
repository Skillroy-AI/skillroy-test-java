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

import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.scenario.resolve.BindMode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The merge of controller-side capabilities into a host's matrix (phase7-contracts.md 8.5, decisions D326, D327, D348,
 * D354), once per host: {@code PlanExecutor} after step 0b, {@code ExecutorSession.open} with the session's host
 * features, the MCP server's start.
 *
 * <p>Rules: an agent id that starts with {@code inference.} is refused (the prefix is the controller's, P2 3.3); every
 * contributed id must start with {@code inference.} and be keyed by its own id; an id the agent also declares, or that
 * two contributors answer, is a conflict naming both; a contributor that throws is refused naming it (it is a pure
 * function of its context); in {@code replay} every contributed id is forced to {@code UNAVAILABLE(reason="replay")}.
 * A refusal is ERROR / ENVIRONMENT for the host. The merged matrix remembers each contributed entry's source
 * ({@code controller:<contributor id>}, {@link CapabilityMatrix#source}); its wire shape carries none.
 *
 * <p>Stateless; thread-safe.
 */
public final class CapabilityMerge {

    /** The prefix every contributed id carries and no agent id may (phase2-contracts.md 3.3). */
    public static final String PREFIX = "inference.";

    /** The reason of every contributed id in {@code replay}. */
    public static final String REPLAY_REASON = "replay";

    private static final int MAX_PROVIDERS = 100;

    private CapabilityMerge() {
    }

    /**
     * The contributors found on a class loader, and the first loading error.
     *
     * @param contributors the contributors that loaded, in {@code ServiceLoader} order
     * @param loadError the first provider that could not be loaded, or null
     */
    public record Loaded(List<CapabilityContributor> contributors, String loadError) {

        /** Copies the list. */
        public Loaded {
            contributors = contributors == null ? List.of() : List.copyOf(contributors);
        }
    }

    /**
     * What a merge gives.
     *
     * @param merged the agent's matrix with the contributed entries
     * @param agent the agent's matrix as given
     * @param contributed the contributed ids, sorted
     */
    public record Result(CapabilityMatrix merged, CapabilityMatrix agent, List<String> contributed) {

        /** Copies the list. */
        public Result {
            contributed = contributed == null ? List.of() : List.copyOf(contributed);
        }
    }

    /** A merge that cannot be made: ERROR / ENVIRONMENT for the host, with this message. */
    public static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        /** @param message names the contributor and the id */
        public Refused(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Finds the contributors through {@code ServiceLoader} on {@code loader} (null: the thread's context class
     * loader); a provider that cannot be loaded is skipped and its first error kept.
     */
    public static Loaded load(ClassLoader loader) {
        ClassLoader effective = loader != null ? loader : Thread.currentThread().getContextClassLoader();
        if (effective == null) {
            effective = CapabilityContributor.class.getClassLoader();
        }
        List<CapabilityContributor> found = new ArrayList<>();
        String error = null;
        try {
            Iterator<CapabilityContributor> providers = ServiceLoader.load(CapabilityContributor.class, effective)
                    .iterator();
            for (int i = 0; i < MAX_PROVIDERS; i++) {
                try {
                    if (!providers.hasNext()) {
                        break;
                    }
                    found.add(providers.next());
                } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
                    if (error == null) {
                        error = message(e);
                    }
                }
            }
        } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
            error = message(e);
        }
        return new Loaded(found, error);
    }

    /**
     * Merges the contributors' capabilities into the agent's matrix under the rules of the class comment.
     *
     * @param agent the agent's matrix (after step 0b or the session's hook install)
     * @param contributors the contributors, in order
     * @param ctx what they may know; its {@code agent} should be {@code agent}
     * @throws Refused when an agent id carries the prefix, a contributor throws, answers an id without the prefix or
     *     keyed by another id, or two parties answer one id
     */
    public static Result merge(CapabilityMatrix agent, List<CapabilityContributor> contributors,
                               CapabilityContributor.CapabilityContext ctx) throws Refused {
        CapabilityMatrix base = agent == null ? new CapabilityMatrix(Map.of()) : agent;
        for (String id : base.all().keySet()) {
            if (id.startsWith(PREFIX)) {
                throw new Refused("the agent declares the capability '" + id + "': the prefix '" + PREFIX
                        + "' is reserved for what the controller contributes");
            }
        }
        Map<String, String> owner = new TreeMap<>();
        Map<String, Map<String, Capability>> bySource = new LinkedHashMap<>();
        for (CapabilityContributor contributor : contributors == null ? List.<CapabilityContributor>of()
                : contributors) {
            String name = contributorId(contributor);
            Map<String, Capability> answered;
            try {
                answered = contributor.capabilities(ctx);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                throw new Refused("capability contributor " + name + " failed: " + message(e));
            }
            Map<String, Capability> kept = new TreeMap<>();
            for (Map.Entry<String, Capability> e : (answered == null ? Map.<String, Capability>of() : answered)
                    .entrySet()) {
                String id = e.getKey();
                Capability capability = e.getValue();
                if (id == null || capability == null || !id.equals(capability.id())) {
                    throw new Refused("capability contributor " + name + " answered " + id + " with "
                            + (capability == null ? "nothing" : "the capability '" + capability.id() + "'"));
                }
                if (!id.startsWith(PREFIX)) {
                    throw new Refused("capability contributor " + name + " answered '" + id + "': every contributed id"
                            + " starts with '" + PREFIX + "'");
                }
                if (base.get(id) != null) {
                    throw new Refused("capability '" + id + "' is declared by the agent and contributed by " + name);
                }
                if (owner.containsKey(id)) {
                    throw new Refused("capability '" + id + "' is contributed by " + owner.get(id) + " and by "
                            + name + "; one contributor answers an id");
                }
                owner.put(id, name);
                kept.put(id, ctx.mode() == BindMode.REPLAY
                        ? new Capability(id, CapabilityState.UNAVAILABLE, REPLAY_REASON, null) : capability);
            }
            bySource.merge(CapabilityMatrix.SOURCE_CONTROLLER_PREFIX + name, kept, (a, b) -> {
                Map<String, Capability> both = new TreeMap<>(a);
                both.putAll(b);
                return both;
            });
        }
        CapabilityMatrix merged = base;
        for (Map.Entry<String, Map<String, Capability>> e : bySource.entrySet()) {
            if (!e.getValue().isEmpty()) {
                merged = merged.with(e.getValue(), e.getKey());
            }
        }
        return new Result(merged, base, new ArrayList<>(new TreeSet<>(owner.keySet())));
    }

    private static String contributorId(CapabilityContributor contributor) {
        try {
            String id = contributor.id();
            return id == null || id.isBlank() ? contributor.getClass().getName() : id;
        } catch (RuntimeException e) {
            return contributor.getClass().getName();
        }
    }

    private static String message(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
    }
}
