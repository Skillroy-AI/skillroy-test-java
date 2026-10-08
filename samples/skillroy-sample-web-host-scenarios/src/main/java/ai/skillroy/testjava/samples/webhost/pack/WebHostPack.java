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

package ai.skillroy.testjava.samples.webhost.pack;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;

import java.util.List;
import java.util.Set;

/**
 * Domain pack {@value #ID} (phase4-contracts.md 9.3): the controller half of the sample web host, next to the agent
 * module of the same id. Scenarios name it with {@code pack: webhost}; it is found with
 * {@code ServiceLoader<DomainPack>}.
 *
 * <ul>
 *   <li>Verbs: none. The base web verbs ({@code webTap}, {@code webFill}, {@code webClick}, {@code webDispatch},
 *       {@code webWaitFlag}, {@code webResize}) drive the host's pages.</li>
 *   <li>Sync policy: no ack topic (nothing in the host acknowledges an input), ready topic {@code web.ready} (the web
 *       module publishes it when a page loaded, a tapped event fired or a flag turned true), and no prompt topics:
 *       the host never asks a question, so the executor looks for no unexpected prompt.</li>
 *   <li>Facts: the root {@value WebHostFacts#ROOT} ({@link WebHostFacts}), the host's three browsers by name.</li>
 *   <li>Pre-flight: none.</li>
 *   <li>Recovery: dispatches {@code webhost.reset} to the front page (its form and status line are emptied) and
 *       lets the UI settle ({@link WebHostRecovery}).</li>
 * </ul>
 *
 * <p>Stateless and thread-safe; one instance may serve every scenario in the JVM.
 */
public final class WebHostPack implements DomainPack {

    /** Pack id, the {@code pack:} of web host scenarios. */
    public static final String ID = "webhost";
    /** Written into every plan; change it whenever something compiles differently. */
    public static final String VERSION = "0.1.0";
    /** Published by the web module: a page loaded, a tapped event fired, or a flag turned true. */
    public static final List<String> READY_TOPICS = List.of("web.ready");

    private static final SyncPolicy SYNC = new SyncPolicy(null, READY_TOPICS, List.of(), null, null);
    private static final List<FactProvider> FACTS = List.of(new WebHostFacts());
    private static final StepLibrary NO_VERBS = new StepLibrary() {
        @Override
        public Set<String> verbs() {
            return Set.of();
        }

        @Override
        public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) throws ScenarioException {
            throw new ScenarioException("the webhost pack has no verb '" + verb + "'; use the base web verbs");
        }
    };

    /** Public no-argument constructor for {@code ServiceLoader}. */
    public WebHostPack() {
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
        return NO_VERBS;
    }

    @Override
    public SyncPolicy syncPolicy() {
        return SYNC;
    }

    @Override
    public List<FactProvider> facts() {
        return FACTS;
    }

    @Override
    public void recover(AgentConnection agent) throws Exception {
        WebHostRecovery.recover(agent);
    }
}
