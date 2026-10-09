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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the advice parameter that receives the hook's site key (phase6-contracts.md section 3.4): the engine's own
 * {@code Advice.OffsetMapping}, bound per hooked method with {@code Advice.withCustomMapping().bind(HookId.class,
 * key)}, so that the inlined code carries the key as a string constant ({@code ldc}) and one advice class serves every
 * hook. The key is the hook id followed by the hooked class, method and descriptor ({@link Site#key}), which the
 * handler's registry resolves to the hook and to what its event names.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@interface HookId {
}
