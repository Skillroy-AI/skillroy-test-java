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

package ai.skillroy.testjava.samples.lending.pack;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.scenario.pack.FactProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The lending desk's fact roots for expectation paths, each read through the lending module's primitives:
 *
 * <ul>
 *   <li>{@code loan}: {@code lending.loan}, the open loan or else the last recorded one ({@code loan.total},
 *       {@code loan.lines[code=YRD-001].qty}, {@code loan.deposits[0].amount}, {@code loan.authorisations});</li>
 *   <li>{@code state}: {@code lending.state} ({@code state.name}, {@code state.staff.code}, {@code state.validInputs});
 *   </li>
 *   <li>{@code slip}: the most recent slips from {@code lending.slip}, oldest first, so that
 *       {@code slip.last.text} is the newest slip;</li>
 *   <li>{@code prompt}: {@code lending.prompt}, the open prompt or null ({@code prompt.text}, {@code prompt.id}).</li>
 * </ul>
 *
 * <p>Stateless; every fetch is one bounded agent call.
 */
final class LendingFacts {

    /** How many slips the {@code slip} root holds. */
    static final long SLIPS = 10;
    private static final CallOptions OPTIONS = CallOptions.defaults().withTimeoutMs(10_000L);

    private LendingFacts() {
    }

    static List<FactProvider> all() {
        return List.of(
                fact("loan", agent -> agent.call("lending.loan", Map.of(), OPTIONS).value()),
                fact("state", agent -> agent.call("lending.state", Map.of(), OPTIONS).value()),
                fact("slip", LendingFacts::slips),
                fact("prompt", agent -> agent.call("lending.prompt", Map.of(), OPTIONS).value()));
    }

    /** {@code lending.slip} returns the newest first; the fact lists them oldest first. */
    static Object slips(AgentConnection agent) throws Exception {
        Object value = agent.call("lending.slip", Map.of("last", SLIPS), OPTIONS).value();
        List<Object> slips = new ArrayList<>();
        if (value instanceof Map<?, ?> m && m.get("slips") instanceof List<?> list) {
            slips.addAll(list);
        }
        Collections.reverse(slips);
        return slips;
    }

    /** One fetch of a fact. */
    @FunctionalInterface
    interface Fetch {
        Object fetch(AgentConnection agent) throws Exception;
    }

    static FactProvider fact(String root, Fetch fetch) {
        return new FactProvider() {
            @Override
            public String root() {
                return root;
            }

            @Override
            public Object fetch(AgentConnection agent) throws Exception {
                return fetch.fetch(agent);
            }

            @Override
            public String toString() {
                return "lending fact " + root;
            }
        };
    }
}
