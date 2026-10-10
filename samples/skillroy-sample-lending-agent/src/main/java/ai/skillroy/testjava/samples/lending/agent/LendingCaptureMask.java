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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.agent.api.spi.CaptureMask;
import ai.skillroy.testjava.samples.lending.EntryField;
import ai.skillroy.testjava.samples.lending.NumberPad;

import java.awt.Component;

/**
 * Which of the desk's components show a secret (phase7-contracts.md sections 3.6, 12.2, D358): the
 * {@link EntryField} while {@code isMasked()} (the password state and the supervisor password sub-step, the two
 * places where the frame sets the flag; the field paints asterisks, but the rule is the policy's, not the paint's), and
 * the {@link NumberPad} while the entry field it feeds is masked, so that its keys draw grey, carry no mark and take
 * no model's click while a secret is expected. Nothing else.
 *
 * <p>Called on the event dispatch thread; fast, stateless, changes nothing.
 */
final class LendingCaptureMask implements CaptureMask {

    @Override
    public boolean masks(Component component) {
        if (component instanceof EntryField) {
            return ((EntryField) component).isMasked();
        }
        if (component instanceof NumberPad) {
            EntryField entry = ((NumberPad) component).entryField();
            return entry != null && entry.isMasked();
        }
        return false;
    }
}
