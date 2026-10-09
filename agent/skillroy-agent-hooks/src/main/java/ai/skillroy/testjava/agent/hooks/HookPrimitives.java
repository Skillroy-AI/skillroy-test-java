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


package ai.skillroy.testjava.agent.hooks;

import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.agent.api.Primitive;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.Primitives;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.protocol.hook.HookSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The six {@code hook.*} primitives (phase6-contracts.md section 4.2). {@code hook.probe}, {@code hook.install},
 * {@code hook.stub} and {@code hook.events} name the capability {@code hook.engine}; {@code hook.remove} and
 * {@code hook.health} name none, so that a restore and a diagnosis stay reachable whatever a reprobe says (without an
 * {@code Instrumentation} they answer {@code CAPABILITY_UNAVAILABLE} themselves). {@code hook.install} is not mutating
 * (D119: an OBSERVE or CAPTURE hook changes bytecode, not behaviour); {@code hook.stub} is mutating and destructive;
 * {@code hook.remove} is gated by nothing. None takes its arguments from an MCP client or a model: the controller's
 * projection never offers the four that change hooks.
 */
final class HookPrimitives {

    static final int EVENTS_DEFAULT_LIMIT = 100;
    static final int EVENTS_MAX_LIMIT = 1000;

    private HookPrimitives() {
    }

    static List<Primitive> all(final HookEngineImpl engine) {
        List<Primitive> out = new ArrayList<Primitive>();
        out.add(Primitives.of(PrimitiveSpec.builder("hook.probe")
                .description("Probes hook specs (phase6-contracts.md 3.3): whether each could be installed, and why "
                        + "not; never installs. Answers {hooks: [HookProbe], spy, otherAgents}.")
                .param("specs", "array", true, "HookSpec objects, at most 64")
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).capability(HooksModule.ENGINE).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return engine.probeCall(specs(inv));
                    }
                }));
        out.add(Primitives.of(PrimitiveSpec.builder("hook.install")
                .description("Installs a catalogue's hook specs (phase6-contracts.md 3.2, 4.2), idempotent by "
                        + "catalogue hash; HOOK_CONFLICT for another catalogue unless replace.")
                .param("specs", "array", true, "HookSpec objects, at most 64")
                .param("catalogue", "object", true, "{id, version?, hash, application?}")
                .param("replace", "boolean", false, "remove an installed catalogue first (default false)")
                .thread(ThreadPolicy.WORKER).timeoutMs(60000).capability(HooksModule.ENGINE).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        Map<String, Object> catalogue = inv.args().obj("catalogue").raw();
                        return engine.installCall(specs(inv), new LinkedHashMap<String, Object>(catalogue),
                                inv.args().bool("replace", false));
                    }
                }));
        out.add(Primitives.of(PrimitiveSpec.builder("hook.stub")
                .description("Arms a STUB hook with a value of its method's return type, or clears it "
                        + "(phase6-contracts.md 3.4, 4.2). Changes the target's behaviour for every user of the JVM.")
                .param("id", "string", true, "the hook id")
                .param("value", "string|number|boolean|null", false, "the value the method returns while armed")
                .param("clear", "boolean", false, "clear the stub (default false)")
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).capability(HooksModule.ENGINE).destructive().build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        Object value = inv.args().get("value");
                        if (value instanceof Map || value instanceof List) {
                            throw new RpcException(ErrorCodes.INVALID_PARAMS, "a stub value is a JSON scalar or null",
                                    HookEngineImpl.data("param", "value"));
                        }
                        return engine.stubCall(inv.args().requireStr("id"), value, inv.args().bool("clear", false),
                                "call");
                    }
                }));
        out.add(Primitives.of(PrimitiveSpec.builder("hook.remove")
                .description("Removes hooks (all when ids is absent or empty) with the restore and the byte "
                        + "verification of phase6-contracts.md 3.5; HOOK_VERIFY_FAILED when a class kept a reference "
                        + "to Spy.")
                .param("ids", "array", false, "hook ids")
                .thread(ThreadPolicy.WORKER).timeoutMs(30000).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return engine.removeCall(ids(inv));
                    }
                }));
        out.add(Primitives.of(PrimitiveSpec.builder("hook.health")
                .description("The health of the installed hooks (phase6-contracts.md 3.6), the armed stubs, the "
                        + "spy's counters, the installed catalogue and the other agents.")
                .param("ids", "array", false, "hook ids (absent or empty: every hook)")
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return engine.healthCall(ids(inv));
                    }
                }));
        out.add(Primitives.of(PrimitiveSpec.builder("hook.events")
                .description("The bus's hook events after a sequence number, oldest first (phase6-contracts.md "
                        + "4.2): those of one hook, or of every hook.* topic.")
                .param("since", "integer", true, "the sequence number to read after")
                .param("id", "string", false, "a hook id")
                .param("limit", "integer", false, "at most this many (default 100, at most 1000)")
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).capability(HooksModule.ENGINE).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        long since = inv.args().requireNum("since");
                        long limit = inv.args().num("limit", EVENTS_DEFAULT_LIMIT);
                        if (limit < 1 || limit > EVENTS_MAX_LIMIT) {
                            throw new RpcException(ErrorCodes.INVALID_PARAMS, "limit must be 1 to "
                                    + EVENTS_MAX_LIMIT, HookEngineImpl.data("param", "limit"));
                        }
                        String id = inv.args().str("id");
                        TopicFilter filter = TopicFilter.parse(id == null ? HookSpec.TOPIC_PREFIX + "*"
                                : engine.topicOf(id));
                        List<Object> events = new ArrayList<Object>();
                        for (Event e : inv.context().events().since(since, filter, (int) limit)) {
                            events.add(e.toJson());
                        }
                        Map<String, Object> out = new LinkedHashMap<String, Object>();
                        out.put("events", events);
                        return out;
                    }
                }));
        return out;
    }

    /** The names of the six, for the tests and the documentation. */
    static final List<String> NAMES = Arrays.asList("hook.probe", "hook.install", "hook.stub", "hook.remove",
            "hook.health", "hook.events");

    @SuppressWarnings("unchecked")
    static List<HookSpec> specs(Invocation inv) {
        List<Object> raw = inv.args().list("specs");
        if (!inv.args().has("specs")) {
            throw new RpcException(ErrorCodes.INVALID_PARAMS, "missing required parameter 'specs'",
                    HookEngineImpl.data("param", "specs"));
        }
        List<HookSpec> specs = new ArrayList<HookSpec>();
        for (int i = 0; i < raw.size(); i++) {
            Object item = raw.get(i);
            if (!(item instanceof Map)) {
                throw new RpcException(ErrorCodes.INVALID_PARAMS, "a spec is a JSON object",
                        HookEngineImpl.data("param", "specs[" + i + "]"));
            }
            try {
                specs.add(HookSpec.fromJson((Map<String, Object>) item));
            } catch (JsonException e) {
                throw new RpcException(ErrorCodes.INVALID_PARAMS, e.getMessage(),
                        HookEngineImpl.data("param", "specs[" + i + "]"));
            } catch (IllegalArgumentException e) {
                throw new RpcException(ErrorCodes.INVALID_PARAMS, e.getMessage(),
                        HookEngineImpl.data("param", "specs[" + i + "]"));
            }
        }
        return specs;
    }

    static List<String> ids(Invocation inv) {
        List<String> ids = new ArrayList<String>();
        List<Object> raw = inv.args().list("ids");
        for (int i = 0; i < raw.size(); i++) {
            Object item = raw.get(i);
            if (!(item instanceof String)) {
                throw new RpcException(ErrorCodes.INVALID_PARAMS, "a hook id is a string",
                        HookEngineImpl.data("param", "ids[" + i + "]"));
            }
            ids.add((String) item);
        }
        return ids;
    }
}
