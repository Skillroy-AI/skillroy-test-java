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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.SettingsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BindOptions}' Phase 5 components (phase5-contracts.md 5.5, 7.1, 8.1): the inference settings (default
 * {@code none()}), the reuse switch (default on), and the system properties that fill them, with
 * {@code inference.yaml} found where the credentials file is, or in the bindings root's environment directory.
 */
class BindOptionsTest {

    @TempDir
    Path dir;

    private final Map<String, String> properties = new HashMap<>();
    private final UnaryOperator<String> noKey = name -> null;

    private BindOptions fromProperties() throws Exception {
        return BindOptions.fromProperties(properties::get, noKey, null);
    }

    @Test
    void theDefaultsAreInferenceNoneAndReuseOn() {
        BindOptions defaults = BindOptions.defaults();
        assertSame(InferenceSettings.none(), defaults.inference());
        assertTrue(defaults.reuseBindings());
        BindOptions phase2 = new BindOptions(BindMode.LIVE, "seed-a", dir, null, null, true, "x", null, null);
        assertSame(InferenceSettings.none(), phase2.inference(), "the Phase 2 constructor");
        assertTrue(phase2.reuseBindings());
        assertSame(InferenceSettings.none(), defaults.withInference(null).inference());

        InferenceSettings recorded = new InferenceSettings("recorded", dir, false, null, null, null, null, null,
                null, true, false, null, false, null);
        BindOptions custom = defaults.withInference(recorded).withReuseBindings(false);
        assertSame(recorded, custom.inference());
        assertFalse(custom.reuseBindings());
        BindOptions copied = custom.withMode(BindMode.APPROVE).withEnvId("seed-b").withBindingsRoot(dir)
                .withCredentials(Credentials.empty()).withApprover(Approver.APPROVE_ALL).withWriteBindings(true)
                .withApprovedBy("label").withLedger(null).withClock(null);
        assertSame(recorded, copied.inference(), "every copy keeps them");
        assertFalse(copied.reuseBindings());
        assertTrue(copied.toString().contains("inference=recorded"), copied.toString());
        assertTrue(copied.toString().contains("reuseBindings=false"), copied.toString());
        assertTrue(BindOptions.defaults().toString().endsWith("inference=none]"), BindOptions.defaults().toString());
    }

    @Test
    void thePropertiesFillTheSettingsFromTheFileBesideTheCredentials() throws Exception {
        Path env = dir.resolve("bindings/seed-a");
        Files.createDirectories(env);
        Files.writeString(env.resolve(InferenceSettings.FILE_NAME), "version: 1\ngateway: recorded\nrecordings:"
                + " rec\n");
        properties.put(BindOptions.PROP_MODE, "live");
        properties.put(BindOptions.PROP_ENV, "seed-a");
        properties.put(BindOptions.PROP_BINDINGS, dir.resolve("bindings").toString());
        BindOptions noCredentials = fromProperties();
        assertEquals("recorded", noCredentials.inference().gateway(), "the bindings root's environment directory");
        assertEquals(env.resolve("rec").toAbsolutePath().normalize(), noCredentials.inference().recordings());
        assertTrue(noCredentials.reuseBindings());

        Path credentials = env.resolve(Credentials.FILE_NAME);
        Files.writeString(credentials, "version: 1\n");
        Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rw-------"));
        BindOptions beside = fromProperties();
        assertEquals(env.resolve(InferenceSettings.FILE_NAME), beside.inference().file().toAbsolutePath(),
                "beside the credentials file");

        properties.put(BindOptions.PROP_MODE, "replay");
        assertEquals("none", fromProperties().inference().gateway(), "replay forces none");
        properties.put(BindOptions.PROP_MODE, "live");
        properties.put(InferenceSettings.PROP_GATEWAY, "none");
        assertEquals("none", fromProperties().inference().gateway(), "a property over the file");

        properties.remove(InferenceSettings.PROP_GATEWAY);
        Files.writeString(env.resolve(InferenceSettings.FILE_NAME), "version: 1\ngateway: claude\n");
        IllegalArgumentException noKey = assertThrows(IllegalArgumentException.class, this::fromProperties);
        assertEquals("the model key is not set (ANTHROPIC_API_KEY)", noKey.getMessage(), "the JUnit glue's"
                + " environment test reports it");
        assertInstanceOf(SettingsException.class, noKey.getCause());
        BindOptions withKey = BindOptions.fromProperties(properties::get, name -> "set", null);
        assertEquals("claude", withKey.inference().gateway());
    }

    @Test
    void reResolveIsACommandNotAPropertyOfARun() {
        properties.put(BindOptions.PROP_MODE, "re-resolve");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, this::fromProperties);
        assertEquals("re-resolve is a command: skillroy re-resolve", e.getMessage());
        properties.put(BindOptions.PROP_MODE, "approve");
        properties.put(BindOptions.PROP_APPROVE, "all");
        try {
            BindOptions approve = fromProperties();
            assertEquals(BindMode.APPROVE, approve.mode());
            assertNull(approve.bindingsRoot());
            assertSame(Approver.APPROVE_ALL, approve.approver());
        } catch (Exception other) {
            throw new AssertionError(other);
        }
    }
}
