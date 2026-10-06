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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprinter;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.StoredTable;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.pack.PromptPolicy;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny domain pack for tests ("toy"): an input primitive with ack and ready topics, a prompt that can be awaited and
 * answered (also quietly, without an ack, completed by an EVENT wait on the ready topics), an EVENT-completed emit, a
 * cleanup-registering sign-on and two facts. Fields let a test change its policy, pre-flight, recover and the calls
 * of the verb {@code custom}.
 */
public final class ToyPack implements DomainPack {

    PromptPolicy promptPolicy = PromptPolicy.FAIL;
    List<PreflightCheck> preflight = List.of();
    SyncPolicy syncPolicy = new SyncPolicy("input.acked", List.of("input.requested", "prompt.waiting"),
            List.of("prompt.waiting"), null, null);
    final AtomicInteger recovered = new AtomicInteger();
    RuntimeException recoverFailure;
    String version = "1.0.0";
    /** Replaces the two toy facts when not null. */
    List<FactProvider> facts;
    /** What the verb {@code custom} compiles to. */
    List<PlannedCall> custom = List.of();
    /** Called with the compile context when the verb {@code custom} compiles. */
    java.util.function.Consumer<CompileContext> onCustom = ctx -> { };
    /** The resolution catalogue (Phase 2); tests build one with {@link ToyResolution}. */
    ResolutionCatalogue resolution = ResolutionCatalogue.EMPTY;
    /** The fingerprinter, or null for the base components only. */
    EnvFingerprinter fingerprinter;
    /** Tables for db.<name> checks. */
    List<StoredTable> storedTables = List.of();
    /** Row masks applied on the controller. */
    List<RowMask> rowMasks = List.of();

    @Override
    public String id() {
        return "toy";
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public StepLibrary steps() {
        return new Steps(this);
    }

    @Override
    public SyncPolicy syncPolicy() {
        return syncPolicy;
    }

    @Override
    public List<FactProvider> facts() {
        return facts != null ? facts : List.of(fact("state", "toy.state"), fact("loan", "toy.loan"));
    }

    @Override
    public PromptPolicy promptPolicy() {
        return promptPolicy;
    }

    @Override
    public List<PreflightCheck> preflight() {
        return preflight;
    }

    @Override
    public ResolutionCatalogue resolution() {
        return resolution;
    }

    @Override
    public EnvFingerprinter fingerprinter() {
        return fingerprinter;
    }

    @Override
    public List<StoredTable> storedTables() {
        return storedTables;
    }

    @Override
    public List<RowMask> rowMasks() {
        return rowMasks;
    }

    @Override
    public void recover(AgentConnection agent) {
        recovered.incrementAndGet();
        if (recoverFailure != null) {
            throw recoverFailure;
        }
    }

    private static FactProvider fact(String root, String primitive) {
        return new FactProvider() {
            @Override
            public String root() {
                return root;
            }

            @Override
            public Object fetch(AgentConnection agent) throws Exception {
                return agent.call(primitive, Map.of()).value();
            }
        };
    }

    /** The toy verbs. */
    static final class Steps implements StepLibrary {

        private final ToyPack pack;

        Steps(ToyPack pack) {
            this.pack = pack;
        }

        @Override
        public Set<String> verbs() {
            return Set.of("enter", "key", "ackOnly", "poke", "signOn", "answer", "expectPrompt", "lock", "mapOf",
                    "invalid", "boom", "nothing", "scanAndAnswer", "answerQuietly", "emit", "lockUntil", "custom",
                    "badEvent");
        }

        @Override
        public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) throws ScenarioException {
            Map<?, ?> a = args instanceof Map<?, ?> m ? m : Map.of();
            switch (verb) {
                case "enter":
                    return List.of(input("Entry", a.get("value"), Sync.READY));
                case "key":
                    return List.of(input("Key." + a.get("action"), null, Sync.READY));
                case "ackOnly":
                    return List.of(input("Entry", a.get("value"), Sync.ACK));
                case "poke":
                    return List.of(new PlannedCall("toy.poke", Map.of(), Sync.SETTLE, 3000));
                case "signOn": {
                    Object staff = a.get("staff");
                    if (!(staff instanceof String ref) || !ref.startsWith("$")) {
                        throw new ScenarioException("signOn: 'staff' must be a $slot reference");
                    }
                    // Phase 2 (6.1): the password comes from credentials.yaml, so the slot must declare a credential.
                    CompileContext.SlotInfo info = ctx.slot(ref.substring(1));
                    if (info == null || info.credential() == null) {
                        throw new ScenarioException("signOn: slot " + ref + " has no credential; add credential:"
                                + " {realm: staff, key: code}");
                    }
                    ctx.addCleanup(new PlannedCall("toy.input", Map.of("source", "Key.SIGN_OFF"), Sync.NONE, 5000));
                    return List.of(input("Entry", ref + ".code", Sync.READY), input("Entry", ref + ".password",
                            Sync.READY));
                }
                case "answer": {
                    PlannedCall call = new PlannedCall("toy.prompt.answer", Map.of("choice",
                            String.valueOf(a.get("choice"))), Sync.READY, 0);
                    ctx.markHandlesPrompt(call);
                    return List.of(call);
                }
                case "scanAndAnswer": {
                    PlannedCall answer = new PlannedCall("toy.prompt.answer", Map.of("choice", "OK"), Sync.READY, 0);
                    ctx.markHandlesPrompt(answer);
                    return List.of(input("Entry", a.get("value"), Sync.READY), answer);
                }
                case "expectPrompt": {
                    long within = a.containsKey("within") ? Durations.toMillis(a.get("within"), "expectPrompt.within")
                            : ctx.stepTimeoutMs();
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("topics", "prompt.waiting");
                    params.put("timeoutMs", within);
                    return List.of(new PlannedCall("sync.await", params, Sync.NONE, within + 1000));
                }
                case "lock":
                    ctx.addCleanup(new PlannedCall("toy.unlock", Map.of("which", a.get("name")), Sync.NONE, 0));
                    return List.of(new PlannedCall("toy.lock", Map.of("which", a.get("name")), Sync.NONE, 0));
                case "mapOf":
                    return List.of(new PlannedCall("toy.many", Map.of("zeta", 1, "alpha", 2, "mid", Map.of("y", 1,
                            "b", 2), "omega", List.of(Map.of("k2", 1, "k1", 2))), Sync.NONE, 0));
                case "invalid":
                    throw new ScenarioException("invalid: always rejected");
                case "boom":
                    throw new IllegalStateException("pack bug");
                case "nothing":
                    return new ArrayList<>();
                case "answerQuietly": {
                    // The quiet answer publishes no ack: EVENT on the pack's ready topics instead of READY.
                    PlannedCall call = PlannedCall.event("toy.prompt.quietAnswer", Map.of("choice",
                            String.valueOf(a.get("choice"))), AwaitSpec.anyOf(ctx.pack().syncPolicy().readyTopics()));
                    ctx.markHandlesPrompt(call);
                    return List.of(call);
                }
                case "emit": {
                    // Map.of iterates in a per-JVM order: the compiler must sort the match keys.
                    Map<String, Object> match = Map.of("id", a.get("id"), "kind", "toy", "zone", "A", "batch", 1);
                    return List.of(new PlannedCall("toy.emit", Map.of("id", a.get("id")), Sync.EVENT, 0,
                            new AwaitSpec("toy.done", match, a.containsKey("within") ? Durations.toMillis(
                                    a.get("within"), "emit.within") : 0)));
                }
                case "lockUntil":
                    ctx.addCleanup(new PlannedCall("toy.unlock", Map.of("which", a.get("name")), Sync.EVENT, 2000,
                            new AwaitSpec("toy.unlocked", Map.of("which", a.get("name")), 1500)));
                    return List.of(new PlannedCall("toy.lock", Map.of("which", a.get("name")), Sync.NONE, 0));
                case "custom":
                    pack.onCustom.accept(ctx);
                    return pack.custom;
                case "badEvent":
                    // A pack bug: EVENT through the four-argument constructor, which has no await spec.
                    return List.of(new PlannedCall("toy.emit", Map.of(), Sync.EVENT, 0));
                default:
                    throw new ScenarioException("unknown toy verb " + verb);
            }
        }

        private static PlannedCall input(String source, Object value, Sync sync) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("source", source);
            if (value != null) {
                args.put("value", value);
            }
            return new PlannedCall("toy.input", args, sync, 0);
        }
    }
}
