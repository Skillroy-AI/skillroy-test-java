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

package ai.skillroy.testjava.spikes.bs3.engine;

import ai.skillroy.testjava.spikes.bs3.spy.Spy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * The one advice shape every hook gets, whatever its mode: the body only calls {@link Spy}, so the instrumented class
 * references nothing but {@code Spy} (and {@code java.*}). OBSERVE, CAPTURE and STUB are behaviours of the handler
 * behind {@code Spy}; switching between them is a handler change, never a retransform.
 *
 * <p>{@code suppress = Throwable.class} on both sides: anything the advice itself throws (including a
 * {@code NoClassDefFoundError} when the instrumented class's loader cannot see {@code Spy}) is caught in the inlined
 * code and never reaches the host. A skip returns the handler's value through the exit advice; a stub value whose
 * type does not fit the method's return type makes the cast in the exit advice fail, which is suppressed, so the method
 * then returns its type's default value (null, 0, false): the engine must check stub values at configuration time.
 */
public final class HookAdvice {

    private HookAdvice() {
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    static Object enter(@HookId String hookId, @Advice.This(optional = true) Object self,
                        @Advice.AllArguments Object[] args) {
        return Spy.enter(hookId, self, args);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(@HookId String hookId, @Advice.This(optional = true) Object self,
                     @Advice.AllArguments Object[] args, @Advice.Enter Object stub,
                     @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned,
                     @Advice.Thrown Throwable thrown) {
        if (stub != null) {
            returned = Spy.stubbed(stub);
        } else {
            Spy.exit(hookId, self, args, returned, thrown);
        }
    }
}
