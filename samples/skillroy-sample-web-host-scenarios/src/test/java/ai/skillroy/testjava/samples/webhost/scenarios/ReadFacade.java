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

package ai.skillroy.testjava.samples.webhost.scenarios;

import ai.skillroy.testjava.controller.AgentClient;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.inference.session.SessionActions;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.pack.PlannedCall;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The vision module's facade ({@link SessionActions}) over the check's own agent client, for the web host's gap-95
 * checks (phase7-contracts.md 13.2 item 10): {@link #read} passes a read to the agent as it is (the session's read
 * list and {@code CallPolicy} are not what these checks are about: they run the vision module's {@code Grounding} and
 * {@code Recorder} against real JCEF), {@link #eventsSince} reads {@code evt.query}, and nothing may act: both
 * {@code execute} methods record the attempt and fail, so a refusal the recorder should have given is never hidden by
 * an action that went through. No model is involved.
 */
final class ReadFacade implements SessionActions {

    private static final CallOptions READ = CallOptions.defaults().withTimeoutMs(30_000L);

    private final AgentClient agent;
    /** The primitives and verbs something tried to execute (none may be). */
    final List<String> executed = new CopyOnWriteArrayList<>();
    /** The primitives read, in order. */
    final List<String> reads = new CopyOnWriteArrayList<>();

    ReadFacade(AgentClient agent) {
        this.agent = agent;
    }

    @Override
    public StepOutcome execute(String verb, Object args, Map<String, Object> provenance) {
        executed.add(verb);
        throw new AssertionError("the check's facade acts on nothing: " + verb + " " + args);
    }

    @Override
    public StepOutcome execute(PlannedCall call, Map<String, Object> provenance) {
        executed.add(call.primitive());
        throw new AssertionError("the check's facade acts on nothing: " + call.primitive());
    }

    @Override
    public Object read(String primitive, Map<String, Object> args)
            throws AgentRpcException, AgentLostException {
        reads.add(primitive);
        return agent.call(primitive, args == null ? Map.of() : new LinkedHashMap<>(args), READ).value();
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        throw new AssertionError("no model call");
    }

    @Override
    public CapabilityMatrix capabilities() {
        try {
            return agent.capabilities();
        } catch (AgentLostException e) {
            throw new AssertionError("the agent is gone", e);
        }
    }

    @Override
    public String snapshotText(int maxChars) {
        throw new AssertionError("the checks read the snapshot text through ExecutorSession");
    }

    @Override
    public List<Event> eventsSince(long seq, String topics, int max) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("since", seq);
        if (topics != null) {
            params.put("topics", topics);
        }
        params.put("limit", (long) Math.max(1, max));
        List<Event> out = new ArrayList<>();
        try {
            Object value = agent.call("evt.query", params, READ).value();
            if (value instanceof Map<?, ?> m && m.get("events") instanceof List<?> events) {
                for (Object e : events) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> json = (Map<String, Object>) e;
                    out.add(Event.fromJson(json));
                }
            }
        } catch (AgentRpcException | AgentLostException e) {
            throw new AssertionError("evt.query failed: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public boolean secretsInFlight() {
        return false;
    }

    @Override
    public Path outputDir() {
        return null;
    }

    @Override
    public String screenLegend() {
        return null;
    }

    @Override
    public void note(String text) {
    }
}
