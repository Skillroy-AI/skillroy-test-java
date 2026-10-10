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

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.inference.budget.BudgetException;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.session.SessionService;
import ai.skillroy.testjava.inference.session.Turn;
import ai.skillroy.testjava.inference.spi.Effort;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.scenario.BindException;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.Origin;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.Scenario;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.ScenarioLoader;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs a committed scenario that has {@code aiAct} or {@code aiExpect} steps over one {@link ExecutorSession} in
 * {@code live} mode and one {@link SessionService} with the vision tools, both opened by its host
 * ({@code skillroy session --play}, or the sample's end-to-end check) with {@code SessionOptions.host =
 * {"vision-session", "vision-tier:<tier>"}} and the person's robot flag (phase7-contracts.md 6.8, decision D311; the
 * module constructs neither). It binds the scenario's slots through the session, then runs every step in order: a
 * deterministic step through {@code execute(verb, args, Origin.person(by))} (an {@code expect} step evaluates its
 * expectations there); an {@code expectImage} or an anchored {@code pointer} through the session's image route; an
 * {@code aiAct} as one vision turn (passes on {@code done}; {@code ask_person} is asked at the terminal, or fails
 * {@code ASSERTION} in a non-interactive run; a limit, the budget, a tool's {@code endTurn} or a lost agent fail
 * {@code ENVIRONMENT}); an {@code aiExpect} as one visual question through {@link SessionService#visionCall} whose
 * {@code YES}/{@code NO} against {@code expect} holds or fails {@code ASSERTION} and whose {@code UNSURE} skips it (the
 * run at best {@code DEGRADED}, D317), behind the guard {@code inference.vision}. A step's {@code tier}, {@code model}
 * and {@code maxActions} narrow the session's options and never widen them; {@code tier: V3} without the person's flag
 * is a usage error before anything runs. Never from {@code PlanExecutor}, never over MCP.
 *
 * <p>Single-threaded: the host's thread.
 */
public final class ScenarioPlayer {

    /** Binds a fixture slot into the session (the host's; see {@link #play}). */
    @FunctionalInterface
    public interface FixtureBinder {
        void bind(ExecutorSession session, Scenario.Slot slot, Origin person) throws BindException;
    }

    /** Answers a model's question at the terminal; null when the run is not interactive. */
    @FunctionalInterface
    public interface Person {
        /** The person's answer to the model's question, or null to stop. */
        String answer(String question);
    }

    /** One step's outcome. */
    public record StepResult(int index, String verb, Status status, FailureKind failureKind, String message) {
    }

    /** The run's outcome: the status (PASS, DEGRADED, FAIL or ERROR), the steps, the message of the first failure. */
    public record Result(String scenarioId, Status status, List<StepResult> steps, String message) {

        /** Copies the steps. */
        public Result {
            steps = List.copyOf(steps);
        }
    }

    private final ExecutorSession session;
    private final SessionService service;
    private final VisionOptions options;
    private final boolean robotAllowed;
    private final VisionSessionTools tools;
    private final PromptMask mask;
    private final Origin person;
    private final Person asker;
    private final FixtureBinder fixtures;

    /**
     * @param session the host's open session ({@code live}, with the vision host features)
     * @param service the host's service on it, with the vision tools
     * @param tools the vision tool set the host gave the service (so that a step can narrow the model and the
     *     tier between V2 and V3), or null
     * @param options the session's vision options
     * @param robotAllowed the person's {@code --allow-robot} on the command
     * @param mask the session's mask
     * @param person who runs the deterministic steps ({@code Origin.person})
     * @param asker the terminal's answer to a model's question, or null for a non-interactive run
     * @param fixtures how a fixture slot is bound into the session, or null (then a scenario with a fixture slot is
     *     refused before anything runs; see the contract's as-built note)
     */
    public ScenarioPlayer(ExecutorSession session, SessionService service, VisionSessionTools tools,
                          VisionOptions options, boolean robotAllowed, PromptMask mask, Origin person, Person asker,
                          FixtureBinder fixtures) {
        this.session = Objects.requireNonNull(session, "session");
        this.service = Objects.requireNonNull(service, "service");
        this.tools = tools;
        this.options = Objects.requireNonNull(options, "options");
        this.robotAllowed = robotAllowed;
        this.mask = Objects.requireNonNull(mask, "mask");
        this.person = Objects.requireNonNull(person, "person");
        this.asker = asker;
        this.fixtures = fixtures;
    }

    /**
     * Plays a scenario.
     *
     * @param yaml the scenario's bytes
     * @param sourceName its name, for messages
     * @throws ScenarioException when it does not load or compile, or a step would widen the session's options (a
     *     usage error before anything runs)
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public Result play(byte[] yaml, String sourceName) throws ScenarioException, AgentLostException,
            InterruptedException {
        Scenario scenario = ScenarioLoader.load(yaml, sourceName);
        Plan plan = PlanCompiler.compile(scenario, session.pack());
        Map<Integer, Plan.Step> byLine = new LinkedHashMap<>();
        for (Plan.Step step : plan.steps()) {
            byLine.put(step.line(), step);
        }
        for (Scenario.Step step : scenario.steps()) {
            if ("aiAct".equals(step.verb())) {
                narrowed(step);
            }
        }
        for (Scenario.Slot slot : scenario.slots()) {
            if ("fixture".equals(slot.kind()) && fixtures == null) {
                throw new ScenarioException(sourceName + ": slot '" + slot.name() + "' is a fixture, which this host"
                        + " cannot bind into a session");
            }
        }
        List<StepResult> results = new ArrayList<>();
        try {
            for (Scenario.Slot slot : scenario.slots()) {
                bind(slot);
            }
        } catch (BindException e) {
            return new Result(scenario.id(), Status.ERROR, results, "slot '" + e.slot() + "': " + e.getMessage());
        }
        boolean degraded = false;
        int index = 0;
        for (Scenario.Step step : scenario.steps()) {
            index++;
            StepResult r = switch (step.verb()) {
                case "aiAct" -> aiAct(index, step);
                case "aiExpect" -> aiExpect(index, step);
                case "expectImage" -> image(index, step, byLine.get(step.line()));
                case "pointer" -> guarded(byLine.get(step.line())) ? image(index, step, byLine.get(step.line()))
                        : deterministic(index, step);
                default -> deterministic(index, step);
            };
            results.add(r);
            if (r.status() == Status.DEGRADED) {
                degraded = true;
            } else if (r.status() != Status.PASS) {
                return new Result(scenario.id(), r.status(), results, "step " + index + " (" + step.verb() + "): "
                        + r.message());
            }
        }
        return new Result(scenario.id(), degraded ? Status.DEGRADED : Status.PASS, results, null);
    }

    /** The session's options narrowed by an {@code aiAct} step (a usage error when the step would widen them). */
    private VisionOptions narrowed(Scenario.Step step) throws ScenarioException {
        Map<?, ?> a = step.args() instanceof Map<?, ?> m ? m : Map.of();
        String tier = a.get("tier") instanceof String t ? t : null;
        if (tier != null && "V3".equalsIgnoreCase(tier.strip()) && !robotAllowed) {
            throw new ScenarioException("line " + step.line() + ": aiAct tier V3 needs --allow-robot on the command");
        }
        try {
            VisionOptions narrowed = options.narrow(tier, a.get("model") instanceof String s ? s : null,
                    a.get("maxActions") instanceof Number n ? n.longValue() : null);
            if (narrowed.tier() != options.tier() && (narrowed.tier() == VisionTier.V1
                    || options.tier() == VisionTier.V1)) {
                throw new IllegalArgumentException("a step's tier " + narrowed.tier().option() + " needs a session"
                        + " of that tier (the session runs " + options.tier().option() + ")");
            }
            return narrowed;
        } catch (IllegalArgumentException e) {
            throw new ScenarioException("line " + step.line() + ": aiAct: " + e.getMessage());
        }
    }

    private void bind(Scenario.Slot slot) throws BindException, AgentLostException, InterruptedException {
        if ("query".equals(slot.kind())) {
            CredentialSpec credential = slot.credential();
            session.resolve(slot.name(), slot.query(), slot.args(), slot.strategy(), slot.verify(), credential,
                    person);
        } else if ("fixture".equals(slot.kind())) {
            fixtures.bind(session, slot, person);
        }
    }

    private StepResult deterministic(int index, Scenario.Step step) throws AgentLostException,
            InterruptedException {
        StepOutcome o = session.execute(step.verb(), step.args(), person);
        return new StepResult(index, step.verb(), o.status(), o.failureKind(), o.message());
    }

    private static boolean guarded(Plan.Step step) {
        if (step == null) {
            return false;
        }
        for (Plan.Call call : step.calls()) {
            if (call.guard() != null) {
                return true;
            }
        }
        return false;
    }

    private StepResult image(int index, Scenario.Step step, Plan.Step compiled) throws AgentLostException,
            InterruptedException {
        if (compiled == null) {
            return new StepResult(index, step.verb(), Status.ERROR, FailureKind.ENVIRONMENT, "the step has no compiled"
                    + " form");
        }
        StepOutcome o = session.expectImage(compiled, person);
        return new StepResult(index, step.verb(), o.status(), o.failureKind(), o.message());
    }

    private StepResult aiAct(int index, Scenario.Step step) throws AgentLostException, InterruptedException {
        Map<?, ?> a = step.args() instanceof Map<?, ?> m ? m : Map.of();
        String intent = a.get("intent") instanceof String s ? s : "";
        VisionOptions narrowed;
        try {
            narrowed = narrowed(step);
        } catch (ScenarioException e) {
            return new StepResult(index, "aiAct", Status.ERROR, FailureKind.ENVIRONMENT, e.getMessage());
        }
        if (tools != null) {
            tools.narrow(narrowed);
        }
        try {
            Turn turn = service.ask(intent);
            while (turn.outcome() == Turn.Outcome.QUESTION && asker != null) {
                String answer = asker.answer(turn.answer());
                if (answer == null) {
                    break;
                }
                turn = service.ask(answer);
            }
            return switch (turn.outcome()) {
                case DONE -> new StepResult(index, "aiAct", Status.PASS, null, turn.answer());
                case QUESTION -> new StepResult(index, "aiAct", Status.FAIL, FailureKind.ASSERTION,
                        "the model asked a question: " + turn.answer());
                default -> new StepResult(index, "aiAct", Status.FAIL, FailureKind.ENVIRONMENT, turn.outcome() + ": "
                        + turn.answer());
            };
        } finally {
            if (tools != null) {
                tools.narrow(null);
            }
        }
    }

    private StepResult aiExpect(int index, Scenario.Step step) throws AgentLostException, InterruptedException {
        Map<?, ?> a = step.args() instanceof Map<?, ?> m ? m : Map.of();
        String question = a.get("question") instanceof String s ? s : "";
        String expect = a.get("expect") instanceof String e ? e.strip().toUpperCase(java.util.Locale.ROOT) : "YES";
        Capability guard = session.capabilities().get(VisionCapabilities.ID);
        if (guard == null || guard.state() != CapabilityState.OK) {
            return new StepResult(index, "aiExpect", Status.DEGRADED, null, "skipped: capability "
                    + VisionCapabilities.ID + " is " + (guard == null ? "missing" : guard.state() + " ("
                    + guard.reason() + ")"));
        }
        Capture capture;
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("scope", "SCREEN");
            params.put("mode", "PAINT");
            params.put("redact", true);
            params.put("annotateRefs", options.tier() == VisionTier.V1);
            capture = Capture.of(session.read("swing.capture", params));
        } catch (AgentRpcException | IllegalArgumentException e) {
            return new StepResult(index, "aiExpect", Status.ERROR, FailureKind.ENVIRONMENT, "no capture: "
                    + e.getMessage());
        }
        byte[] image = Grounding.encodeMarked(capture, options.tier() == VisionTier.V1);
        VisualQuestion.Verdict verdict;
        try {
            verdict = VisualQuestion.ask(service::visionCall, image, question, VisualQuestion.Asker.PERSON,
                    legend(), mask, options.model(), effort());
        } catch (BudgetException | InferenceException e) {
            return new StepResult(index, "aiExpect", Status.ERROR, FailureKind.ENVIRONMENT, "the question could not"
                    + " be asked: " + e.getMessage());
        }
        session.note("aiExpect " + verdict.verdict() + " (expected " + expect + ") on capture " + capture.hash() + ": "
                + verdict.explanation(), person);
        if (verdict.unsure()) {
            return new StepResult(index, "aiExpect", Status.DEGRADED, null, "skipped: the model is unsure ("
                    + verdict.explanation() + ")");
        }
        return verdict.verdict().equals(expect) ? new StepResult(index, "aiExpect", Status.PASS, null,
                verdict.explanation()) : new StepResult(index, "aiExpect", Status.FAIL, FailureKind.ASSERTION,
                "the model answered " + verdict.verdict() + " where " + expect + " was expected: "
                        + verdict.explanation());
    }

    private String legend() {
        try {
            String legend = session.pack().screenLegend();
            return legend == null || legend.isBlank() ? null : mask.text(legend.strip());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Effort effort() {
        String text = session.options().bind() == null || session.options().bind().inference() == null ? null
                : session.options().bind().inference().effort().get("vision");
        try {
            return text == null ? Effort.LOW : Effort.parse(text);
        } catch (IllegalArgumentException e) {
            return Effort.LOW;
        }
    }
}
