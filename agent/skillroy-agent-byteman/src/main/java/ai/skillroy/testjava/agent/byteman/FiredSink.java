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

package ai.skillroy.testjava.agent.byteman;

/**
 * Where the module's handler delivers a firing; implemented by the engine. A module type, so that the handler class,
 * which also implements the sys jar's {@code RuleHelper.Handler}, can be constructed by reflection after the append
 * without anything else of the module naming a sys type.
 */
public interface FiredSink {

    /** A firing of a rule (Byteman's name, with the {@code skillroy:} prefix), on the application's thread. */
    void fired(String rule, String className, String method, Object[] bindings);
}
