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

import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample's inference settings (phase5-contracts.md 4.4, 14.5): the committed {@code inference.example.yaml} is
 * read by the real, strict reader, names the recorded gateway and the module's recordings directory, holds no key and
 * is never read by accident (its name is not {@code inference.yaml}); {@code replay} still asks no model with it; the
 * recordings directory exists and says what goes into it.
 */
class InferenceExampleTest {

    private static Path example() {
        return ItEnv.moduleDir().resolve("inference.example.yaml");
    }

    private static Path recordings() {
        return ItEnv.moduleDir().resolve("src/test/resources/inference");
    }

    /** The settings of {@code mode} from the example file alone: no system property and no key in the environment. */
    private static InferenceSettings load(BindMode mode) throws Exception {
        return InferenceSettings.load(mode, null, Map.of(InferenceSettings.PROP_FILE, example().toString()),
                property -> null, variable -> null);
    }

    @Test
    void theExampleIsReadByTheRealReaderAndServesTheRecordedGateway() throws Exception {
        InferenceSettings settings = load(BindMode.APPROVE);
        assertEquals(InferenceSettings.GATEWAY_RECORDED, settings.gateway());
        assertEquals(recordings(), settings.recordings(), "relative to the file");
        assertFalse(settings.record());
        assertNull(settings.workspaceId());
        assertEquals(InferenceSettings.DEFAULT_MODELS, settings.models());
        assertEquals(InferenceSettings.DEFAULT_EFFORT, settings.effort());
        assertEquals(Map.of("scenario", "0.50", "session", "1.00"), settings.budgets(), "no job budget by default");
        assertEquals(InferenceSettings.DEFAULT_THRESHOLDS, settings.thresholds());
        assertNull(settings.prices(), "the built-in table of token rates");
        assertTrue(settings.fallbacks());
        assertFalse(settings.images());
        assertFalse(settings.logPrompts());
        assertEquals(example(), settings.file());
        assertEquals(InferenceSettings.GATEWAY_RECORDED, load(BindMode.LIVE).gateway(), "no key needed in live");
        assertEquals(InferenceSettings.GATEWAY_NONE, load(BindMode.REPLAY).gateway(), "replay never asks a model");
    }

    @Test
    void theExampleHoldsNoKeyAndIsNeverReadByAccident() throws Exception {
        assertNotEquals(InferenceSettings.FILE_NAME, example().getFileName().toString());
        String text = Files.readString(example());
        assertTrue(text.contains("ANTHROPIC_API_KEY"), "it says where the key comes from");
        assertFalse(text.contains("sk-"), "and holds none");
        assertTrue(text.contains("workspaceId: null"));
    }

    /**
     * The recordings directory exists with its README; no recording is committed yet, and scenario D's bindings, which
     * are written from its recordings, cannot be committed before them.
     */
    @Test
    void theRecordingsDirectorySaysWhatGoesIntoIt() throws Exception {
        assertTrue(Files.isDirectory(recordings()));
        String readme = Files.readString(recordings().resolve("README.md"));
        assertTrue(readme.contains(ScenarioD.ID), readme);
        List<Path> files;
        try (Stream<Path> list = Files.list(recordings())) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
        }
        if (files.isEmpty()) {
            assertTrue(readme.contains("Nothing is recorded yet"), "the README says so: " + readme);
        }
        assertTrue(!ScenarioD.HAS_BINDINGS || !files.isEmpty(), "scenario D's bindings come from its recordings");
    }
}
