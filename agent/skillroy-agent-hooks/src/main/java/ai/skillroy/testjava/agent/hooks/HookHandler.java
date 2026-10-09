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

import ai.skillroy.testjava.agent.spy.Spy;

/**
 * The engine's one {@link Spy.Handler} (phase6-contracts.md sections 3.1 and 3.2): a thin shim over
 * {@link HandlerCore}. It implements a {@code Spy} type, so it is <b>loaded only after the bootstrap append</b>, by
 * reflection (a class linked against a {@code Spy} type before the append keeps failing with
 * {@code NoClassDefFoundError} after it, BS3 RESULT section 9 fact 9), and nothing else of the engine names it.
 *
 * <p>Thread-safe as the core is.
 */
final class HookHandler implements Spy.Handler {

    private final HandlerCore core;

    HookHandler(HandlerCore core) {
        this.core = core;
    }

    @Override
    public Object enter(String hookId, Object self, Object[] args) {
        Object result = core.enter(hookId, self, args);
        return result == null ? null : Spy.stub(((HandlerCore.Armed) result).value);
    }

    @Override
    public void exit(String hookId, Object self, Object[] args, Object returned, Throwable thrown) {
        core.exit(hookId, self, args, returned, thrown);
    }
}
