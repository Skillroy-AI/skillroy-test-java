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

import ai.skillroy.testjava.samples.webhost.pack.WebHostPack;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.ScenarioLoader;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.CaptureSpec;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.web.WebSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item 7 of phase4-contracts.md 12.1: the committed plans ({@code plan/<id>.plan.json}) are exactly what compiling the
 * committed scenarios W1 and W2 ({@code scenarios/*.yaml}) with the webhost pack gives now, byte for byte, and they
 * are version 4 plans that leave {@code web.path} at {@code auto}, so that the same plan runs over CDP and through
 * the agent. After changing a scenario or the pack, regenerate them with {@code -Dskillroy.plans.update=true}.
 */
class PlansUpToDateTest {

    static Path moduleDir() {
        return Path.of(System.getProperty("skillroy.it.moduleDir", ".")).toAbsolutePath().normalize();
    }

    static List<Path> committedScenarios() throws IOException {
        try (Stream<Path> files = Files.list(moduleDir().resolve("scenarios"))) {
            return files.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList();
        }
    }

    static Path planFor(Path yaml) throws Exception {
        String id = ScenarioLoader.load(yaml).id();
        return moduleDir().resolve("plan").resolve(id + ".plan.json");
    }

    @Test
    void everyCommittedPlanMatchesItsScenario() throws Exception {
        List<Path> scenarios = committedScenarios();
        assertEquals(List.of("borrow-in-page.yaml", "hidden-browser-reachable.yaml"), scenarios.stream()
                .map(p -> p.getFileName().toString()).toList());
        boolean update = Boolean.getBoolean("skillroy.plans.update");
        List<String> stale = new ArrayList<>();
        for (Path yaml : scenarios) {
            Path plan = planFor(yaml);
            if (update) {
                PlanCompiler.compileTo(yaml, new WebHostPack(), plan);
            }
            if (!PlanCompiler.isUpToDate(yaml, plan, new WebHostPack())) {
                stale.add(moduleDir().relativize(plan).toString());
            }
        }
        assertTrue(stale.isEmpty(), "out of date (regenerate with -Dskillroy.plans.update=true): " + stale);
        try (Stream<Path> plans = Files.list(moduleDir().resolve("plan"))) {
            assertEquals(scenarios.size(), plans.count(), "one plan per committed scenario, nothing else");
        }
    }

    /** Version 5, the pack and its version, the path left at auto, the default browser, the web capabilities. */
    @Test
    void theCommittedPlansAreVersion5AndLeaveThePathAtAuto() throws Exception {
        for (Path yaml : committedScenarios()) {
            Plan plan = Plan.read(planFor(yaml));
            assertEquals(6, plan.planVersion(), plan.scenarioId() + ": plan version 6 since Phase 7 (VC's one"
                    + " regeneration, phase7-contracts.md 8.2)");
            assertEquals(Plan.VERSION, plan.planVersion());
            assertEquals(WebHostPack.ID, plan.pack());
            assertEquals(WebHostPack.VERSION, plan.packVersion());
            assertEquals("none", plan.inference());
            assertEquals(WebSettings.Path.AUTO, plan.effectiveWeb().path(), plan.scenarioId());
            assertTrue(plan.requires().containsAll(List.of("web.browsers", "web.evaluate")), plan.scenarioId()
                    + " " + plan.requires());
            assertTrue(!plan.requires().contains("web.cdp"), "nothing asks for CDP: " + plan.requires());
            assertTrue(!plan.needsBindings(), "nothing to resolve");
        }
        Plan w1 = Plan.read(moduleDir().resolve("plan").resolve("borrow-in-page.plan.json"));
        assertEquals(Map.of("tag", "front"), w1.effectiveWeb().defaultBrowser());
        assertTrue(w1.requires().contains("web.events"), String.valueOf(w1.requires()));
        Plan w2 = Plan.read(moduleDir().resolve("plan").resolve("hidden-browser-reachable.plan.json"));
        assertEquals(Map.of("tag", "back"), w2.effectiveWeb().defaultBrowser());
        assertTrue(w2.requires().contains("web.resize"), String.valueOf(w2.requires()));
    }

    /**
     * W1 as section 9.3 shapes it: the tap first (its removal registered as a cleanup call), the two fills and the
     * click on the front page, the click's wait on the tapped {@code webhost.borrowed} with the captured detail, the
     * page event and the flag, every web call on {@code {tag: front}}.
     */
    @Test
    void theBorrowScenarioTapsFillsClicksAndAwaitsThePagesEvent() throws Exception {
        Plan plan = Plan.read(moduleDir().resolve("plan").resolve("borrow-in-page.plan.json"));
        List<String> primitives = plan.steps().stream().flatMap(st -> st.calls().stream()).map(Plan.Call::primitive)
                .toList();
        assertEquals(List.of("web.tap", "web.dom.fill", "web.dom.fill", "web.dom.click", "swing.settle",
                "web.dispatchEvent", "web.waitFlag"), primitives);
        Plan.Call tap = call(plan, "web.tap");
        assertEquals(Map.of("by", Map.of("tag", "front"), "patterns", List.of("webhost.*")), tap.args());
        assertEquals(Sync.NONE, tap.sync());
        Plan.Call click = call(plan, "web.dom.click");
        assertEquals(Map.of("by", Map.of("tag", "front"), "role", "button", "name", "Borrow"), click.args());
        assertEquals(Sync.EVENT, click.sync());
        assertEquals(new AwaitSpec("web.tap", Map.of("name", "webhost.borrowed"), 30_000L), click.await());
        assertEquals(CaptureSpec.of("borrowed", Map.of("item", "detail.item", "qty", "detail.qty")),
                click.capture());
        List<Plan.Call> fills = plan.steps().stream().flatMap(st -> st.calls().stream())
                .filter(c -> c.primitive().equals("web.dom.fill")).toList();
        assertEquals(List.of(Map.of("by", Map.of("tag", "front"), "selector", "#item", "text", "hammer"),
                Map.of("by", Map.of("tag", "front"), "selector", "#qty", "text", "2")),
                fills.stream().map(Plan.Call::args).toList());
        assertEquals(Map.of("by", Map.of("tag", "front"), "name", "webhost.setItem", "detail", Map.of("item", "saw")),
                call(plan, "web.dispatchEvent").args());
        assertEquals(Sync.NONE, call(plan, "web.waitFlag").sync());
        assertEquals(List.of("web.tap"), plan.cleanup().stream().map(Plan.Call::primitive).toList());
        assertEquals(Map.of("by", Map.of("tag", "front"), "patterns", List.of()), plan.cleanup().get(0).args());
    }

    /** W2 on {@code {tag: back}}: the resize, the flags, the page event; the expectations through the fact roots. */
    @Test
    void theHiddenBrowserScenarioActsOnTheBackPage() throws Exception {
        Plan plan = Plan.read(moduleDir().resolve("plan").resolve("hidden-browser-reachable.plan.json"));
        Plan.Call resize = call(plan, "web.resize");
        assertEquals(Map.of("by", Map.of("tag", "back"), "width", 1024L, "height", 768L), resize.args());
        assertEquals(Sync.SETTLE, resize.sync());
        for (Plan.Step step : plan.steps()) {
            for (Plan.Call c : step.calls()) {
                if (c.primitive().startsWith("web.")) {
                    assertEquals(Map.of("tag", "back"), c.args().get("by"), step.id() + " " + c);
                }
            }
        }
        assertTrue(plan.toJsonText().contains("\"web.browsers[tag=back].visibleInSwing\""), "the base root web");
        assertTrue(plan.toJsonText().contains("\"webhost.back.visibleInSwing\""), "the pack's root webhost");
    }

    /**
     * Every call waits or is waited on: a page action is followed by the UI settle (SETTLE) or awaits its event
     * (EVENT, then {@code swing.settle} with sync NONE, A32); {@code web.waitFlag} waits itself; {@code web.tap}
     * installs the tap before it answers and needs no wait.
     */
    @Test
    void thePlansUseEventDrivenSyncOnly() throws Exception {
        for (Path yaml : committedScenarios()) {
            Plan plan = Plan.read(planFor(yaml));
            for (Plan.Step step : plan.steps()) {
                List<Plan.Call> calls = step.calls();
                for (int i = 0; i < calls.size(); i++) {
                    Plan.Call call = calls.get(i);
                    boolean settlesAnAwaitedCall = call.primitive().equals("swing.settle") && i > 0
                            && calls.get(i - 1).sync() == Sync.EVENT;
                    boolean waits = call.sync() != Sync.NONE || settlesAnAwaitedCall
                            || List.of("web.waitFlag", "web.tap").contains(call.primitive());
                    assertTrue(waits, plan.scenarioId() + " " + step.id() + ": " + call);
                }
            }
        }
    }

    @Test
    void theNegativeScenariosCompile() throws Exception {
        Path dir = moduleDir().resolve("src/test/resources/negative");
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> yamls = files.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList();
            assertEquals(List.of("cdp-without-port.yaml", "fill-a-secret.yaml", "flag-does-not-compile.yaml",
                    "flag-never-ready.yaml", "no-such-button.yaml", "script-error.yaml", "two-inputs.yaml"),
                    yamls.stream().map(p -> p.getFileName().toString()).toList());
            for (Path yaml : yamls) {
                Plan plan = PlanCompiler.compile(yaml, new WebHostPack());
                assertEquals(yaml.getFileName().toString(), plan.scenarioId() + ".yaml");
            }
        }
        for (String flag : List.of("flag-never-ready.yaml", "flag-does-not-compile.yaml")) {
            Plan plan = PlanCompiler.compile(dir.resolve(flag), new WebHostPack());
            assertEquals(Map.of("by", Map.of("tag", "front"), "expression", flag.startsWith("flag-never")
                    ? "document.getElementById('due-date').value === '2026-10-31'" : "document.title ===",
                    "timeoutMs", flag.startsWith("flag-never") ? 1000L : 60000L), call(plan, "web.waitFlag").args(),
                    "A35: the base verb, the front page by default, its own time");
            assertEquals(Sync.NONE, call(plan, "web.waitFlag").sync());
        }
        Plan cdp = PlanCompiler.compile(dir.resolve("cdp-without-port.yaml"), new WebHostPack());
        assertEquals(WebSettings.Path.CDP, cdp.effectiveWeb().path());
        assertTrue(cdp.requires().contains("web.cdp"), "path cdp adds web.cdp: " + cdp.requires());
        Plan secret = PlanCompiler.compile(dir.resolve("fill-a-secret.yaml"), new WebHostPack());
        assertEquals(Map.of("by", Map.of("tag", "front"), "selector", "#item", "text", "$member.password"),
                call(secret, "web.dom.fill").args(), "the secret stays a reference in the plan");
        assertTrue(!secret.toJsonText().contains("password\":"), "no password value in the plan");
    }

    private static Plan.Call call(Plan plan, String primitive) {
        return plan.steps().stream().flatMap(st -> st.calls().stream()).filter(c -> c.primitive().equals(primitive))
                .findFirst().orElseThrow(() -> new AssertionError("no " + primitive + " call in "
                        + plan.scenarioId()));
    }
}
