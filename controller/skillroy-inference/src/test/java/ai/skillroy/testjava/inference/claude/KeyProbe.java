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

package ai.skillroy.testjava.inference.claude;

import ai.skillroy.testjava.inference.Gateways;
import ai.skillroy.testjava.inference.budget.InferenceLog;
import ai.skillroy.testjava.inference.budget.LedgerWriter;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.Strategy;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * The child JVM of {@link KeyFromEnvironmentTest}: opens the configured gateway as a run would ({@code Gateways.open}
 * with {@code gateway: claude}, {@code record: true}), the key and the base URL only from its environment, makes one
 * answered call and one refused call, records them with prompts logged, and prints what it got. Never prints the key
 * on purpose; the test checks that nothing does by accident.
 */
public final class KeyProbe {

    private KeyProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        InferenceSettings settings = new InferenceSettings("claude", out.resolve("recordings"), true, null, null, null,
                null, null, null, false, false, Map.of("callMs", 20_000L), true, null);
        CostLedger ledger = new CostLedger();
        LedgerWriter writer = new LedgerWriter(ledger, null, InferenceLog.toFile(out.resolve("inference.jsonl")),
                PromptMask.keysOnly(), true, Clock.systemUTC());
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("answer", Map.of("type", "string")),
                "required", List.of("answer"), "additionalProperties", false);
        ModelRequest request = ModelRequest.builder("resolve", ModelClass.SONNET_CLASS)
                .system(Content.Text.cached("the catalogue"))
                .message(Message.user("which item?")).outputSchema(schema).build();
        try (InferenceGateway gateway = Gateways.open(settings)) {
            System.out.println("gateway " + gateway);
            ModelResponse response = gateway.complete(request);
            writer.record(request, response, "probe", "item", Strategy.LLM_SQL);
            System.out.println("answered " + response.structured() + " " + response.usage());
            try {
                gateway.complete(request.withMessages(List.of(Message.user("again"))));
            } catch (InferenceException e) {
                writer.recordFailure(request, e, "probe", "item", Strategy.LLM_SQL);
                System.out.println("refused " + e.kind() + " " + e.getMessage());
                e.printStackTrace(System.out);
            }
        }
        // 4.2's second pass with the opener's mask: a gateway opened for record: true with a mask that knows a
        // credential writes its recording through that mask (a request builder that missed one is still caught).
        InferenceSettings masked = new InferenceSettings("claude", out.resolve("recordings-masked"), true, null, null,
                null, null, null, null, false, false, Map.of("callMs", 20_000L), false, null);
        PromptMask mask = PromptMask.of(ai.skillroy.testjava.data.SecretMask.of(List.of(CREDENTIAL)), List.of(),
                List.of());
        try (InferenceGateway gateway = Gateways.open(masked, mask)) {
            ModelResponse response = gateway.complete(ModelRequest.builder("resolve", ModelClass.SONNET_CLASS)
                    .system(Content.Text.cached("the catalogue"))
                    .message(Message.user("which item? the reader's note says " + CREDENTIAL + " and more")).build());
            System.out.println("masked recording " + response.stop());
        }
        System.out.println("ledger " + ledger.entries());
        System.out.println("done");
    }

    /** A credential the masked opener knows: it must reach no recording. */
    static final String CREDENTIAL = "Probe-Rec-Cred-8812";
}
