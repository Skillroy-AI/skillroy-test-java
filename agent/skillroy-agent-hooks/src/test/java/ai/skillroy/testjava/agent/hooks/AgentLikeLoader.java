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


package ai.skillroy.testjava.agent.hooks;

import ai.skillroy.testjava.fixture.hooks.ChildFirstLoader;

import java.net.URL;

/**
 * A loader whose own class is under {@code ai.skillroy.testjava.agent.}, as the agent loader and every module loader
 * are: the probe refuses its classes by loader, whatever their package (phase6-contracts.md section 3.3 item 2).
 */
final class AgentLikeLoader extends ChildFirstLoader {

    AgentLikeLoader(URL[] urls, ClassLoader parent, String... prefixes) {
        super(urls, parent, prefixes);
    }
}
