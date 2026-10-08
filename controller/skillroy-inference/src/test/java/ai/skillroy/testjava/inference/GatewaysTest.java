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

package ai.skillroy.testjava.inference;

import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 4.4: the gateway the settings name, built once per distinct gateway settings and shared,
 * reference-counted, closed with its last lease; {@code none} answers every call {@code UNSUPPORTED} "no gateway".
 * (The {@code claude} path, the key from the environment and the recording wrapper are exercised by the child JVM of
 * {@code KeyFromEnvironmentTest}.)
 */
class GatewaysTest {

    @TempDir
    Path dir;

    private static InferenceSettings settings(String gateway, Path recordings, Map<String, String> budgets) {
        return new InferenceSettings(gateway, recordings, false, null, null, null, budgets, null, null, true, false,
                null, false, null);
    }

    private static ModelRequest request() {
        return ModelRequest.builder("resolve", ModelClass.SONNET_CLASS).message(Message.user("q")).build();
    }

    @Test
    void noneAnswersNoGateway() throws Exception {
        try (InferenceGateway g = Gateways.open(InferenceSettings.none())) {
            assertEquals("none", g.provider());
            assertTrue(g.capabilities().isEmpty());
            InferenceException e = assertThrows(InferenceException.class, () -> g.complete(request()));
            assertEquals(InferenceException.Kind.UNSUPPORTED, e.kind());
            assertEquals("no gateway", e.getMessage());
            assertEquals(InferenceException.Kind.UNSUPPORTED, assertThrows(InferenceException.class,
                    () -> g.submitBatch(List.of(request()))).kind());
        }
    }

    @Test
    void oneGatewayPerSettingsSharedAndClosedWithItsLastLease() throws Exception {
        int before = Gateways.openGateways();
        InferenceGateway a = Gateways.open(settings("recorded", dir, null));
        InferenceGateway b = Gateways.open(settings("recorded", dir, Map.of("scenario", "0.10")));
        assertEquals(before + 1, Gateways.openGateways(), "budgets do not make another gateway");
        InferenceGateway c = Gateways.open(settings("recorded", dir.resolve("other"), null));
        assertEquals(before + 2, Gateways.openGateways());
        assertEquals("recorded", a.provider());
        a.close();
        a.close();
        assertEquals(before + 2, Gateways.openGateways(), "a lease closes once, the gateway stays for b");
        assertEquals(InferenceException.Kind.NO_RECORDING, assertThrows(InferenceException.class,
                () -> b.complete(request())).kind(), "b still answers");
        assertEquals(InferenceException.Kind.UNSUPPORTED, assertThrows(InferenceException.class,
                () -> a.complete(request())).kind(), "a closed lease refuses");
        b.close();
        c.close();
        assertEquals(before, Gateways.openGateways());
    }

    @Test
    void recordedWithoutADirectoryIsRefused() {
        assertEquals(InferenceException.Kind.UNSUPPORTED, assertThrows(InferenceException.class,
                () -> Gateways.open(settings("recorded", null, null))).kind());
    }
}
