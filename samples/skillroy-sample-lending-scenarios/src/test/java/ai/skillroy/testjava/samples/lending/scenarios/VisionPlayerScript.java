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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.inference.budget.BudgetMeter;
import ai.skillroy.testjava.inference.budget.InferenceLog;
import ai.skillroy.testjava.inference.budget.LedgerWriter;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.session.SessionExtensions;
import ai.skillroy.testjava.inference.session.SessionPolicy;
import ai.skillroy.testjava.inference.session.SessionService;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.vision.ScenarioPlayer;
import ai.skillroy.testjava.vision.VisionOptions;
import ai.skillroy.testjava.vision.VisionSessionTools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A committed vision scenario played by the scenario player on the real desk (phase7-contracts.md 6.8, 13.2 item 8):
 * one session on seed A with the settings of {@link VisionSessionScript#settings()} (images on), the host features
 * {@code vision-session} and {@code vision-tier:v1}, the person's origin {@link VisionSessionScript#PERSON}, the
 * session's own fixture binding (D-VM-6), and a session service with the vision tools (V1) over the gateway given.
 * The session recovers the desk when it closes, as the CLI's does.
 *
 * <p>Single-threaded test code.
 */
final class VisionPlayerScript {

    private VisionPlayerScript() {
    }

    /** What a play left: the player's result, the ledger, and the journal's file. */
    record Played(ScenarioPlayer.Result result, CostLedger ledger, Path journal) {
    }

    /** Plays {@code scenarios/<file>} on {@code desk} with {@code gateway} as the model; outputs below {@code dir}. */
    static Played play(AgentConnection desk, Credentials credentials, InferenceGateway gateway, Path dir, String file)
            throws Exception {
        return play(desk, credentials, gateway, VisionSessionScript.settings(), dir, file,
                Files.readAllBytes(ItEnv.scenario(file)));
    }

    /**
     * Plays {@code yaml} (named {@code file}) on {@code desk} with {@code gateway} as the model and {@code settings} as
     * the session's inference settings; outputs below {@code dir}.
     */
    static Played play(AgentConnection desk, Credentials credentials, InferenceGateway gateway,
                       InferenceSettings settings, Path dir, String file, byte[] yaml) throws Exception {
        CostLedger ledger = new CostLedger();
        Path out = dir.resolve("session");
        SessionOptions options = SessionOptions.defaults(out).withClock(Clock.systemUTC())
                .withId("played-" + file.replace(".yaml", "")).withRecoverOnClose(true)
                .withBind(BindOptions.defaults().withEnvId(Env.SEED_A.id).withCredentials(credentials)
                        .withLedger(ledger).withInference(settings))
                .withHost(Set.of("vision-session", "vision-tier:v1"));
        Map<String, String> vision = Map.of(VisionOptions.TIER, "v1");
        try (ExecutorSession session = ExecutorSession.open(Runs.PACK, desk, options)) {
            PromptMask mask = PromptMask.of(credentials.mask(), Runs.PACK.sqlAllowLists(), Runs.PACK.rowMasks())
                    .withImages(true);
            BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, "1.00", ledger);
            LedgerWriter writer = new LedgerWriter(ledger, meter, InferenceLog.toFile(out.resolve("inference.jsonl")),
                    mask, false, Clock.systemUTC());
            VisionSessionTools tools = new VisionSessionTools();
            ScenarioPlayer.Result result;
            try (SessionService service = new SessionService(session, gateway, SessionPolicy.acting(), meter, writer,
                    PriceTable.builtIn(), Clock.systemUTC(), new SessionExtensions(List.of(tools), vision))) {
                ScenarioPlayer player = new ScenarioPlayer(session, service, tools, VisionOptions.from(vision), false,
                        mask, VisionSessionScript.PERSON, null, null)
                        .withBaselines(ItEnv.moduleDir().resolve("bindings"), Env.SEED_A.id);
                result = player.play(yaml, file);
            }
            return new Played(result, ledger, session.journal().file());
        }
    }
}
