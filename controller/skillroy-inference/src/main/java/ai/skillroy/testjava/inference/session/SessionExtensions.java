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

package ai.skillroy.testjava.inference.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a host adds to a session service (phase7-contracts.md 9.1): the tool sets it loaded
 * ({@link SessionTools#load}) and the tool options from its flags ({@code vision.tier}, {@code vision.model},
 * {@code vision.tools}, {@code vision.zoom}, {@code vision.maxActions}, {@code vision.maxTurns}). The service owns the
 * tool sets once constructed with them and closes them.
 *
 * @param tools the tool sets, in load order; null is none
 * @param options the tool options; null is none
 */
public record SessionExtensions(List<SessionTools> tools, Map<String, String> options) {

    /** Copies both. */
    public SessionExtensions {
        tools = tools == null ? List.of() : List.copyOf(tools);
        options = options == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    /** No tool set and no option: the Phase 5 service. */
    public static SessionExtensions none() {
        return new SessionExtensions(List.of(), Map.of());
    }
}
