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


package ai.skillroy.testjava.scenario.observe;

import ai.skillroy.testjava.scenario.resolve.SettingsException;

/**
 * Makes the {@link RunObserver} of an exporter (phase6-contracts.md 10.2): a {@code ServiceLoader} SPI, found through
 * the thread's context class loader (the {@code WebPath} and {@code ResolverContributor} pattern, P4 D49, P5 D70) only
 * when the settings name an exporter other than {@code none}. An implementation's class references no type of its
 * exporter's library until {@link #create} runs, so that finding it loads nothing of that library.
 */
public interface RunObserverProvider {

    /** The exporter this provider makes: {@code otlp} or {@code file}. */
    String exporter();

    /**
     * Makes the observer of one CLI invocation or JUnit stream.
     *
     * @param settings validated settings ({@link TelemetrySettings#load}) naming this provider's exporter
     * @throws SettingsException when the settings cannot be used (a header's variable that is not set, a directory that
     *     cannot be written); the message names the setting, never a value
     */
    RunObserver create(TelemetrySettings settings) throws SettingsException;
}
