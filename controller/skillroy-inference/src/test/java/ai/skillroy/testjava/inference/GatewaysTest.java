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

import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 4.4: the gateway the settings name, built once per distinct gateway settings and shared,
 * reference-counted, closed with its last lease; {@code none} answers every call {@code UNSUPPORTED} "no gateway"; a
 * gateway the caller owns serves settings through {@code serve}.
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

    /**
     * 4.2's second pass: the recording wrapper of {@code record: true} writes through the opener's mask (here one that
     * knows a credential), and through the key rule and the bounds alone when the opener gave none.
     */
    @Test
    void theRecordingWrapperWritesThroughTheOpenersMask() throws Exception {
        String credential = "Wrapper-Cred-5531";
        InferenceSettings recording = new InferenceSettings("claude", dir.resolve("masked"), true, null, null, null,
                null, null, null, true, false, null, false, null);
        ModelRequest request = ModelRequest.builder("resolve", ModelClass.SONNET_CLASS)
                .message(Message.user("the note says " + credential + " and token=Wrapper-Tok-77")).build();
        ScriptedGateway live = ScriptedGateway.answering(r -> ScriptedGateway.text("ok"));
        try (InferenceGateway g = Gateways.recording(live, recording, ai.skillroy.testjava.inference.prompt.PromptMask
                .of(ai.skillroy.testjava.data.SecretMask.of(List.of(credential)), List.of(), List.of()))) {
            g.complete(request);
        }
        String masked = java.nio.file.Files.readString(dir.resolve("masked").resolve(request.fingerprint() + ".json"));
        assertFalse(masked.contains(credential), masked);
        assertFalse(masked.contains("Wrapper-Tok-77"), "the key rule too: " + masked);
        InferenceSettings plain = new InferenceSettings("claude", dir.resolve("plain"), true, null, null, null, null,
                null, null, true, false, null, false, null);
        try (InferenceGateway g = Gateways.recording(ScriptedGateway.answering(r -> ScriptedGateway.text("ok")), plain,
                null)) {
            g.complete(request);
        }
        String keysOnly = java.nio.file.Files.readString(dir.resolve("plain").resolve(request.fingerprint()
                + ".json"));
        assertFalse(keysOnly.contains("Wrapper-Tok-77"), "the key rule and the bounds without an opener's mask");
        assertTrue(keysOnly.contains(credential), "a value only the opener's mask knows");
        assertTrue(Gateways.recording(live, settings("claude", dir, null), null) == live, "record: false wraps nothing");
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

    /**
     * A gateway the caller owns serves settings until its registration ends: every lease answers through it, no lease
     * and no registration closes it, a second registration or a registration over an open gateway is refused, and
     * after the registration the settings build their own gateway again.
     */
    @Test
    void aServedGatewayAnswersItsSettingsUntilTheRegistrationEnds() throws Exception {
        int before = Gateways.openGateways();
        InferenceSettings scripted = settings("scripted", null, null);
        ScriptedGateway mine = ScriptedGateway.answering(r -> ScriptedGateway.text("served"));
        try (Gateways.Served served = Gateways.serve(scripted, mine)) {
            assertEquals(before + 1, Gateways.openGateways());
            assertThrows(IllegalStateException.class, () -> Gateways.serve(scripted, mine), "one at a time");
            InferenceGateway a = Gateways.open(scripted);
            InferenceGateway b = Gateways.open(settings("scripted", null, Map.of("scenario", "0.10")));
            assertEquals("served", a.complete(request()).text());
            assertEquals("served", b.complete(request()).text(), "budgets do not select another gateway");
            a.close();
            b.close();
            assertFalse(mine.closed(), "a lease never closes the caller's gateway");
            assertEquals(before + 1, Gateways.openGateways(), "the registration holds the entry without leases");
            InferenceGateway held = Gateways.open(scripted);
            served.close();
            served.close();
            assertEquals("served", held.complete(request()).text(), "a lease outlives the registration");
            assertEquals(before + 1, Gateways.openGateways());
            held.close();
            assertEquals(before, Gateways.openGateways());
            assertFalse(mine.closed(), "nor does the registration");
        }
        assertEquals(InferenceException.Kind.UNSUPPORTED, assertThrows(InferenceException.class,
                () -> Gateways.open(scripted)).kind(), "after it, scripted is an unknown gateway again");
        InferenceGateway open = Gateways.open(settings("recorded", dir, null));
        assertThrows(IllegalStateException.class, () -> Gateways.serve(settings("recorded", dir, null), mine),
                "not over an open gateway");
        open.close();
        assertEquals(before, Gateways.openGateways());
        assertEquals(3, mine.requests().size(), "every lease's request went to the caller's gateway");
    }

    @Test
    void recordedWithoutADirectoryIsRefused() {
        assertEquals(InferenceException.Kind.UNSUPPORTED, assertThrows(InferenceException.class,
                () -> Gateways.open(settings("recorded", null, null))).kind());
    }
}
