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


package ai.skillroy.testjava.telemetry;

import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.observe.RunObserverProvider;
import ai.skillroy.testjava.scenario.observe.TelemetrySettings;
import ai.skillroy.testjava.scenario.resolve.SettingsException;

/**
 * The {@code file} exporter (phase6-contracts.md 10.5, decision D143): {@code spans.jsonl} in the settings'
 * {@code out} directory, in the project's {@code skillroy-spans/1} format. Found through {@code ServiceLoader}; this
 * class names no OpenTelemetry type, so that finding it loads nothing of the SDK until {@link #create}.
 */
public final class FileObserverProvider implements RunObserverProvider {

    /** For {@code ServiceLoader}. */
    public FileObserverProvider() {
    }

    @Override
    public String exporter() {
        return TelemetrySettings.EXPORTER_FILE;
    }

    @Override
    public RunObserver create(TelemetrySettings settings) throws SettingsException {
        if (!TelemetrySettings.EXPORTER_FILE.equals(settings.exporter())) {
            throw new SettingsException("the file exporter was asked for exporter " + settings.exporter());
        }
        return TelemetryObservers.file(settings, System.err);
    }
}
