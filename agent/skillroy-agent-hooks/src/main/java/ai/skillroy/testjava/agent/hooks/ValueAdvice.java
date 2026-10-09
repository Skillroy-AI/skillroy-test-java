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
import ai.skillroy.testjava.agent.hooks.shaded.bytebuddy.implementation.bytecode.assign.Assigner;
import ai.skillroy.testjava.agent.spy.Spy;

/**
 * The advice inlined into a hooked method with a return value (phase6-contracts.md section 3.4): two lines that call
 * nothing but {@link Spy}, whatever the hook's mode, so that the instrumented class references nothing but {@code Spy}
 * and {@code java.*}. A non-null enter result (a {@code Spy.Stub}) skips the body and the exit advice returns its
 * value; otherwise the exit advice hands the result or the throwable to {@code Spy.exit}. Both methods suppress every
 * throwable of the advice itself (a {@code NoClassDefFoundError} included), so nothing of the advice reaches the host.
 *
 * <p>Never loaded as code by the engine: Byte Buddy reads its class file and copies the bodies.
 */
final class ValueAdvice {

    private ValueAdvice() {
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    static Object enter(@HookId String id, @Advice.This(optional = true) Object self,
                        @Advice.AllArguments Object[] args) {
        return Spy.enter(id, self, args);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(@HookId String id, @Advice.This(optional = true) Object self,
                     @Advice.AllArguments Object[] args, @Advice.Enter Object stub,
                     @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned,
                     @Advice.Thrown Throwable thrown) {
        if (stub != null) {
            returned = ((Spy.Stub) stub).value();
        } else {
            Spy.exit(id, self, args, returned, thrown);
        }
    }
}
