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
 * {@link HookAdvice} without {@code suppress}: used by the probe only, on a sacrificial class, to show what a host
 * would see if the inlined code were not guarded (BS3 item 1). Never a shape for the real engine.
 */
public final class RawHookAdvice {

    private RawHookAdvice() {
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static Object enter(@HookId String hookId, @Advice.This(optional = true) Object self,
                        @Advice.AllArguments Object[] args) {
        return Spy.enter(hookId, self, args);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
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
