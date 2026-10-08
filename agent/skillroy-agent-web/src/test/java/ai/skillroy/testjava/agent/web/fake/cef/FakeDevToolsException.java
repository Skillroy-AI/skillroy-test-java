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

package ai.skillroy.testjava.agent.web.fake.cef;

import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;

/** The fake's own failure type for a DevTools method (as JCEF has its own); never seen beyond the adapter. */
public final class FakeDevToolsException extends RuntimeException {

    static {
        ClassInitialisations.record(FakeDevToolsException.class);
    }

    private static final long serialVersionUID = 1L;

    public FakeDevToolsException(String message) {
        super(message);
    }
}
