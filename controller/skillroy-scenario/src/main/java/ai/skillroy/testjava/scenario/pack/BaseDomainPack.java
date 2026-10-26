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

package ai.skillroy.testjava.scenario.pack;

import ai.skillroy.testjava.scenario.ScenarioException;

import java.util.List;
import java.util.Set;

/**
 * The built-in zero-code {@link DomainPack} ({@code id = "base"}, {@code version = "1"}) used when a scenario names
 * {@code pack: base} or when no custom {@link DomainPack} is on the class path.
 *
 * <p>Provides all built-in {@code BaseSteps} ({@code ui.*}, {@code web.*}, {@code db.query}) and {@code env.*} facts
 * without requiring a custom Java module or {@code ServiceLoader} registration.
 */
public final class BaseDomainPack implements DomainPack {

    /** The pack id ({@value}). */
    public static final String ID = "base";

    /** The pack version ({@value}). */
    public static final String VERSION = "1";

    /** The singleton instance. */
    public static final BaseDomainPack INSTANCE = new BaseDomainPack();

    private static final StepLibrary STEPS = new StepLibrary() {
        @Override
        public Set<String> verbs() {
            return Set.of();
        }

        @Override
        public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) throws ScenarioException {
            throw new ScenarioException("unknown step '" + verb + "'");
        }
    };

    private static final SyncPolicy SYNC_POLICY = new SyncPolicy(null, List.of(), List.of(), null, null);

    private BaseDomainPack() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public StepLibrary steps() {
        return STEPS;
    }

    @Override
    public SyncPolicy syncPolicy() {
        return SYNC_POLICY;
    }

    @Override
    public List<FactProvider> facts() {
        return List.of();
    }
}
