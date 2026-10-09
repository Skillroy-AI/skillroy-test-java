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

package ai.skillroy.testjava.scenario.hooks;

import ai.skillroy.testjava.protocol.hook.HookCapture;
import ai.skillroy.testjava.protocol.hook.HookMode;
import ai.skillroy.testjava.protocol.hook.HookSpec;
import ai.skillroy.testjava.protocol.hook.HookTarget;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One entry of a hook catalogue (phase6-contracts.md 5.1): the {@link HookSpec} the agent receives, plus what only the
 * controller reads, whether the entry is {@code enabled} and its {@code fallbacks}. Built by
 * {@link HookCatalogues#read} from YAML, or in Java by a pack or an overlay.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param id the hook id ({@link HookSpec#ID_REGEX}); the capability is {@code hook.<id>}
 * @param mode {@code OBSERVE}, {@code CAPTURE} or {@code STUB}
 * @param enabled whether the controller sends the entry to the agent
 * @param target the class, method and descriptors hooked, and the loader the hook is limited to (null: every loader)
 * @param capture what a {@code CAPTURE} hook copies, or null (and always null for another mode)
 * @param secretArgs argument indexes whose values are never captured; never null
 * @param secretResult true: the return value is never captured
 * @param emits the topic as written, or null for {@code hook.<id>}
 * @param fallbacks the public seams to fall back on when the hook is not installed, at least one, the first a seam
 * @param note free text carried to the agent (at most 200 characters), or null
 */
public record HookEntry(String id, HookMode mode, boolean enabled, HookTarget target, HookCapture capture,
                        List<Integer> secretArgs, boolean secretResult, String emits, List<Fallback> fallbacks,
                        String note) {

    /** What a scenario falls back on when a hook is not installed (phase6-contracts.md 5.1, design 2.6). */
    public sealed interface Fallback permits Fallback.Seam {

        /**
         * A public seam: a primitive id the capability matrix must show {@code OK} for the fallback to apply.
         *
         * @param primitive the primitive id, in {@code PrimitiveSpec}'s name pattern
         */
        record Seam(String primitive) implements Fallback {

            /** @throws NullPointerException if {@code primitive} is null */
            public Seam {
                Objects.requireNonNull(primitive, "primitive");
            }

            @Override
            public String toString() {
                return "seam:" + primitive;
            }
        }
    }

    /** Method-name fragments that suggest a secret argument or result (phase6-contracts.md 4.3), lower case. */
    static final List<String> SECRET_WORDS = List.of("password", "pin", "secret", "token", "credential");

    /**
     * Copies the lists.
     *
     * @throws NullPointerException if the id, mode, target or fallbacks are null
     */
    public HookEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(target, "target");
        secretArgs = secretArgs == null ? List.of() : List.copyOf(secretArgs);
        fallbacks = List.copyOf(Objects.requireNonNull(fallbacks, "fallbacks"));
    }

    /** The spec the agent receives: every field but {@code enabled} and {@code fallbacks}. */
    public HookSpec toSpec() {
        return new HookSpec(id, mode, target, emits, capture, secretArgs, secretResult, note);
    }

    /** The capability the hook is declared as once installed: {@code hook.<id>}. */
    public String capabilityId() {
        return HookSpec.TOPIC_PREFIX + id;
    }

    /** This entry with {@code enabled} set as given. */
    public HookEntry withEnabled(boolean on) {
        return new HookEntry(id, mode, on, target, capture, secretArgs, secretResult, emits, fallbacks, note);
    }

    /**
     * The warning of phase6-contracts.md 4.3, or null: a {@code CAPTURE} hook whose method name suggests a secret
     * ({@link #SECRET_WORDS}, case-insensitive) with {@code secretArgs} empty, or capturing the result without
     * {@code secretResult}.
     */
    String secretWarning() {
        if (mode != HookMode.CAPTURE) {
            return null;
        }
        String method = target.method().toLowerCase(Locale.ROOT);
        if (SECRET_WORDS.stream().noneMatch(method::contains)) {
            return null;
        }
        if (secretArgs.isEmpty()) {
            return "hook " + id + " captures " + target.method() + ", whose name suggests a secret, with no secretArgs";
        }
        if (capture != null && capture.result() && !secretResult) {
            return "hook " + id + " captures the result of " + target.method() + ", whose name suggests a secret,"
                    + " without secretResult";
        }
        return null;
    }
}
