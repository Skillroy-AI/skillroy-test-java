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

package ai.skillroy.testjava.agent.swing;

import ai.skillroy.testjava.protocol.JsonView;

import java.awt.Component;
import java.util.Map;

/**
 * The target of an input primitive: {@code target} (a ref, or a locator object) or {@code by} (a locator), never both.
 * Parsed on the calling thread; resolved on the EDT.
 *
 * <p>Immutable; safe to share between threads.
 */
final class TargetSpec {

    private final String ref;
    private final Locator locator;

    private TargetSpec(String ref, Locator locator) {
        this.ref = ref;
        this.locator = locator;
    }

    /**
     * Reads {@code target} / {@code by} from the arguments.
     *
     * @return null when neither is given and {@code required} is false
     * @throws ai.skillroy.testjava.protocol.RpcException INVALID_PARAMS when both or (if required) neither are given,
     *     or the locator is malformed
     */
    static TargetSpec parse(JsonView args, boolean required) {
        Object target = args.get("target");
        Object by = args.get("by");
        if (target != null && by != null) {
            throw Errors.invalid("give either 'target' or 'by', not both", Errors.data("param", "target"));
        }
        if (target instanceof Map) {
            return new TargetSpec(null, Locator.parse(target, "target"));
        }
        if (target != null) {
            return new TargetSpec(Locator.scalar(target, "target"), null);
        }
        if (by != null) {
            return new TargetSpec(null, Locator.parse(by, "by"));
        }
        if (required) {
            throw Errors.invalid("missing required parameter 'target' (a ref) or 'by' (a locator)",
                    Errors.data("param", "target"));
        }
        return null;
    }

    /** The component, resolved on the EDT: STALE_HANDLE, NOT_FOUND or AMBIGUOUS if there is not exactly one. */
    Component resolve(ComponentView view) {
        if (ref != null) {
            return view.context().handles().resolve(ref, Component.class);
        }
        return new LocatorEngine(view).one(locator, null, false);
    }

    @Override
    public String toString() {
        return ref != null ? "ref " + ref : String.valueOf(locator);
    }
}
