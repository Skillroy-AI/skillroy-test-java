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

package ai.skillroy.testjava.scenario.hooks;

import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;

import java.util.List;

/**
 * Minimal packs whose default {@code hooks()} reads a test resource: {@code hooky} has a catalogue
 * ({@code META-INF/skillroy/hooks/hooky.yaml}), {@code plain} none, {@code broken} a malformed one.
 */
final class ResourcePacks {

    private ResourcePacks() {
    }

    /** A pack with nothing but an id. */
    abstract static class Bare implements DomainPack {

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public StepLibrary steps() {
            return null;
        }

        @Override
        public SyncPolicy syncPolicy() {
            return null;
        }

        @Override
        public List<FactProvider> facts() {
            return List.of();
        }
    }

    /** With the catalogue {@code hooky}. */
    static final class Hooky extends Bare {
        @Override
        public String id() {
            return "hooky";
        }
    }

    /** Without a catalogue. */
    static final class Plain extends Bare {
        @Override
        public String id() {
            return "plain";
        }
    }

    /** With a malformed catalogue (its version is text, line 2). */
    static final class Broken extends Bare {
        @Override
        public String id() {
            return "broken";
        }
    }
}
