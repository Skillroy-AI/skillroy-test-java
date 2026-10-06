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

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;

import java.util.List;
import java.util.Set;

/**
 * A second pack ({@code other}) that is deliberately not registered on the test class path: tests put a
 * {@code META-INF/services} entry for it into a jar and pass that jar with {@code --pack}.
 */
public final class OtherTestPack implements DomainPack {

    @Override
    public String id() {
        return "other";
    }

    @Override
    public String version() {
        return "2.0.0";
    }

    @Override
    public StepLibrary steps() {
        return new StepLibrary() {
            @Override
            public Set<String> verbs() {
                return Set.of();
            }

            @Override
            public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) {
                return List.of();
            }
        };
    }

    @Override
    public SyncPolicy syncPolicy() {
        return new SyncPolicy(null, List.of(), List.of(), null, null);
    }

    @Override
    public List<FactProvider> facts() {
        return List.of();
    }
}
