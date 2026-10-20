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

package ai.skillroy.testjava.scenario.pack;

import ai.skillroy.testjava.protocol.CallWarning;
import ai.skillroy.testjava.protocol.RungResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One rung of a call's ladder (phase8-contracts.md 7.6, gap 22): a way to perform the call's action, from the best
 * ({@link RungResult#PHYSICAL}: through the device's own path) down to a shortcut ({@link RungResult#SEMANTIC}). The
 * primitive chooses the rung when it acts and reports it ({@code RungResult}'s keys); the executor checks the report
 * against the ladder, records a lower rung as {@code DEGRADED} and, before anything runs, that the step's
 * {@code minRung} can be reached through a capability that is {@code OK}.
 *
 * @param name the rung's name, matching {@link RungResult#NAME}
 * @param capability the capability id that says whether the rung is available (an agent id or a pack capability),
 *     or null for a rung that is always there
 */
public record Rung(String name, String capability) {

    /** The longest capability id. */
    static final int MAX_CAPABILITY = 128;

    /**
     * @throws IllegalArgumentException if {@code name} is not a rung name or {@code capability} is blank, longer than
     *     128 characters or holds white space
     */
    public Rung {
        if (!RungResult.isName(name)) {
            throw new IllegalArgumentException("rung name must match " + RungResult.NAME.pattern() + ": " + name);
        }
        if (capability != null && (capability.isBlank() || capability.length() > MAX_CAPABILITY
                || capability.chars().anyMatch(Character::isWhitespace))) {
            throw new IllegalArgumentException("rung " + name + ": capability must be a capability id: " + capability);
        }
    }

    /** A rung that is always available. */
    public static Rung of(String name) {
        return new Rung(name, null);
    }

    /**
     * The rules a call's {@code accept}, ladder and {@code minRung} follow wherever a call is built (a
     * {@link PlannedCall}, a plan's call): every code a warning code, 2 to 8 rungs with distinct names when there is a
     * ladder, and {@code minRung} one of them.
     *
     * @param where the call, for the message ({@code "call lending.input"})
     * @throws IllegalArgumentException naming the first broken rule
     */
    public static void check(String where, Set<String> accept, List<Rung> rungs, String minRung) {
        for (String code : accept) {
            if (!CallWarning.isValidCode(code)) {
                throw new IllegalArgumentException(where + ": accept holds a code that is not a warning code: " + code);
            }
        }
        if (!rungs.isEmpty()) {
            if (rungs.size() < PlannedCall.MIN_RUNGS || rungs.size() > PlannedCall.MAX_RUNGS) {
                throw new IllegalArgumentException(where + ": a rung ladder has " + PlannedCall.MIN_RUNGS + " to "
                        + PlannedCall.MAX_RUNGS + " rungs, found " + rungs.size());
            }
            Set<String> names = new HashSet<>();
            for (Rung rung : rungs) {
                if (rung == null) {
                    throw new IllegalArgumentException(where + ": a rung ladder holds null");
                }
                if (!names.add(rung.name())) {
                    throw new IllegalArgumentException(where + ": rung " + rung.name() + " is named twice");
                }
            }
        }
        if (minRung != null && rungs.stream().noneMatch(r -> r.name().equals(minRung))) {
            throw new IllegalArgumentException(where + ": minRung " + minRung + " is not a rung of "
                    + (rungs.isEmpty() ? "a call without a ladder" : "its ladder " + names(rungs)));
        }
    }

    /** {@code [PHYSICAL, SEMANTIC]}. */
    public static String names(List<Rung> rungs) {
        return rungs.stream().map(Rung::name).toList().toString();
    }
}
