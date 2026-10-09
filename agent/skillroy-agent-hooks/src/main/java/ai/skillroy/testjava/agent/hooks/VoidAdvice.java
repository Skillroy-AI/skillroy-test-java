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

import ai.skillroy.testjava.agent.hooks.shaded.bytebuddy.asm.Advice;
import ai.skillroy.testjava.agent.spy.Spy;

/**
 * The advice inlined into a hooked method returning {@code void} (phase6-contracts.md section 3.4): as
 * {@link ValueAdvice} without a return value. A {@code STUB} hook on a void method skips the body only.
 *
 * <p>Never loaded as code by the engine: Byte Buddy reads its class file and copies the bodies.
 */
final class VoidAdvice {

    private VoidAdvice() {
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    static Object enter(@HookId String id, @Advice.This(optional = true) Object self,
                        @Advice.AllArguments Object[] args) {
        return Spy.enter(id, self, args);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(@HookId String id, @Advice.This(optional = true) Object self,
                     @Advice.AllArguments Object[] args, @Advice.Enter Object stub, @Advice.Thrown Throwable thrown) {
        if (stub == null) {
            Spy.exit(id, self, args, null, thrown);
        }
    }
}
