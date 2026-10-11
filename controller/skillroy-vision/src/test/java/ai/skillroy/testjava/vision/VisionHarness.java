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

package ai.skillroy.testjava.vision;

import ai.skillroy.testjava.inference.budget.BudgetMeter;
import ai.skillroy.testjava.inference.budget.InferenceLog;
import ai.skillroy.testjava.inference.budget.LedgerWriter;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.inference.session.SessionExtensions;
import ai.skillroy.testjava.inference.session.SessionPolicy;
import ai.skillroy.testjava.inference.session.SessionService;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.StepJournal;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A vision session for the module's tests (phase7-contracts.md 13.1 item 8): the fake agent on the desk fixture, an
 * {@code ExecutorSession} opened with a scripted gateway's settings ({@code images: true}) and the vision host features,
 * a {@code SessionService} with this module's {@link VisionSessionTools} and the scripted model's answers by position.
 */
class VisionHarness implements AutoCloseable {

    final FakeVisionAgent agent;
    final ExecutorSession session;
    final VisionSessionTools tools = new VisionSessionTools();
    final ScriptedGateway gateway;
    final CostLedger ledger = new CostLedger();
    final SessionService service;
    final Path dir;

    /** The settings of a vision session: a scripted gateway, images on. */
    static InferenceSettings settings(boolean images) {
        return new InferenceSettings("scripted", null, false, null, null, null, null, null, null, true, images, null,
                false, null);
    }

    VisionHarness(Path dir, Map<String, String> options, List<ModelResponse> answers) throws Exception {
        this(dir, options, answers, SessionPolicy.acting().withMaxActionsPerTurn(50), false, true);
    }

    VisionHarness(Path dir, Map<String, String> options, List<ModelResponse> answers, SessionPolicy policy,
                  boolean robot, boolean images) throws Exception {
        this.dir = dir;
        this.agent = new FakeVisionAgent(dir);
        configure(agent);
        SessionOptions base = agent.bridge.options();
        String tier = options.getOrDefault(VisionOptions.TIER, "v1");
        this.session = ExecutorSession.open(agent.bridge.pack(), agent.bridge.agent(), base
                .withBind(base.bind().withInference(settings(images)).withLedger(ledger))
                .withRobot(robot).withHost(Set.of("vision-session", "vision-tier:" + tier)));
        this.gateway = ScriptedGateway.sequence(answers);
        BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, "1.00", ledger);
        LedgerWriter writer = new LedgerWriter(ledger, meter, InferenceLog.inMemory(), PromptMask.keysOnly(), false,
                agent.bridge.clock());
        Map<String, String> all = new LinkedHashMap<>(options);
        all.putIfAbsent(VisionOptions.TIER, tier);
        this.service = new SessionService(session, gateway, policy, meter, writer, PriceTable.builtIn(),
                agent.bridge.clock(), new SessionExtensions(List.of(tools), all));
    }

    /** A hook for a test to program the fake before the session opens. */
    void configure(FakeVisionAgent fake) {
    }

    List<StepJournal.StepEntry> steps() {
        return session.journal().entries().stream().filter(e -> e instanceof StepJournal.StepEntry)
                .map(e -> (StepJournal.StepEntry) e).toList();
    }

    static Usage usage(String requestId) {
        return new Usage("scripted", "claude-sonnet-5-5", "claude-sonnet-5-5", requestId, 1300, 40, 0, 0, 0, false,
                "0.0030", Duration.ZERO, null);
    }

    static ModelResponse tools(String requestId, Content... parts) {
        return new ModelResponse(List.of(parts), ModelResponse.StopReason.TOOL_USE, usage(requestId), null);
    }

    static Content.ToolUse use(String id, String name, Map<String, Object> input) {
        return new Content.ToolUse(id, name, input);
    }

    static Content.ToolUse member(String id, String name, Map<String, Object> input) {
        return new Content.ToolUse(id, name, input, "computer");
    }

    static ModelResponse done(String requestId) {
        return tools(requestId, use("done-" + requestId, "done", Map.of("answer", "finished")));
    }

    @Override
    public void close() {
        service.close();
        session.close();
    }
}
