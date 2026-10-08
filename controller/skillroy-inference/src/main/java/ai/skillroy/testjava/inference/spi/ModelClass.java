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

package ai.skillroy.testjava.inference.spi;

/**
 * Which model, by role, so that callers never name a vendor's model id (B7, phase5-contracts.md 3.2): the settings map
 * each class to an id ({@code inference.yaml} {@code models:}, keys {@code opus}, {@code sonnet}, {@code haiku}).
 */
public enum ModelClass {
    /** The most capable class (the planner). */
    OPUS_CLASS("opus"),
    /** The default class (resolvers, sessions, summaries). */
    SONNET_CLASS("sonnet"),
    /** The fastest, cheapest class (triage labels). */
    HAIKU_CLASS("haiku");

    private final String settingsKey;

    ModelClass(String settingsKey) {
        this.settingsKey = settingsKey;
    }

    /** The key of this class in {@code InferenceSettings.models()}. */
    public String settingsKey() {
        return settingsKey;
    }
}
