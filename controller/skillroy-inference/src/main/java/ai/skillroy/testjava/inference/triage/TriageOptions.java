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

package ai.skillroy.testjava.inference.triage;

import ai.skillroy.testjava.inference.spi.Effort;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * How a triage runs (phase5-contracts.md 11.2).
 *
 * @param labelsOnly true makes the label call only ({@code --labels-only}), no summary
 * @param clock what the triage's date and its records' times are read from; null means the system UTC clock
 * @param logPrompts true writes the masked prompt and response parts into the triage's {@code inference.jsonl}
 *     ({@code --log-prompts}, {@code logPrompts} of the settings)
 * @param effort the effort of both calls ({@code effort.triage} of the settings); null means low
 * @param models the model ids by class key ({@code models} of the settings), which the estimates are taken at; null
 *     means the defaults
 */
public record TriageOptions(boolean labelsOnly, Clock clock, boolean logPrompts, Effort effort,
                            Map<String, String> models) {

    /** Fills the clock, the effort and the model ids; copies the map. */
    public TriageOptions {
        clock = clock == null ? Clock.systemUTC() : clock;
        effort = effort == null ? Effort.LOW : effort;
        models = models == null ? InferenceSettings.DEFAULT_MODELS : Map.copyOf(models);
    }

    /** No prompt logging, effort low, the default model ids. */
    public TriageOptions(boolean labelsOnly, Clock clock) {
        this(labelsOnly, clock, false, null, null);
    }

    /** A label and a summary, the system clock. */
    public static TriageOptions defaults() {
        return new TriageOptions(false, null);
    }

    /**
     * The options of a triage under these settings: their {@code logPrompts}, {@code effort.triage} and model ids.
     *
     * @throws IllegalArgumentException when {@code effort.triage} is not low, medium or high
     */
    public static TriageOptions of(InferenceSettings settings, boolean labelsOnly, Clock clock) {
        Objects.requireNonNull(settings, "settings");
        String effort = settings.effort().get("triage");
        Effort parsed;
        try {
            parsed = effort == null ? Effort.LOW : Effort.parse(effort);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("effort.triage must be low, medium or high");
        }
        return new TriageOptions(labelsOnly, clock, settings.logPrompts(), parsed, settings.models());
    }

    /** A copy that logs the prompts or not. */
    public TriageOptions withLogPrompts(boolean on) {
        return new TriageOptions(labelsOnly, clock, on, effort, models);
    }

    /** The model id of a class: the settings', else the default. */
    public String model(ModelClass modelClass) {
        String id = models.get(modelClass.settingsKey());
        return id != null ? id : InferenceSettings.DEFAULT_MODELS.get(modelClass.settingsKey());
    }
}
