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

package ai.skillroy.testjava.samples.lending;

import java.math.BigDecimal;

/**
 * The engine's own state at an idle point, read by {@link Engine#idleView()} on the engine thread inside an
 * {@link EngineListener#idle} callback (phase8-contracts.md 12.1). {@link #step()} and {@link #pendingQty()} are in no
 * published snapshot: only a read on the engine thread at that point sees them. Immutable.
 */
public final class IdleView {

    private final LendingState state;
    private final String step;
    private final BigDecimal pendingQty;
    private final boolean returnMode;
    private final LendingState lockedFrom;
    private final int lineCount;
    private final Long idleLockRemainingMs;

    IdleView(LendingState state, String step, BigDecimal pendingQty, boolean returnMode, LendingState lockedFrom,
             int lineCount, Long idleLockRemainingMs) {
        this.state = state;
        this.step = step;
        this.pendingQty = pendingQty;
        this.returnMode = returnMode;
        this.lockedFrom = lockedFrom;
        this.lineCount = lineCount;
        this.idleLockRemainingMs = idleLockRemainingMs;
    }

    /** @return the state */
    public LendingState state() {
        return state;
    }

    /**
     * @return the pending sub-step: {@code NONE}, {@code QUANTITY}, {@code SUPERVISOR_CODE} or
     *     {@code SUPERVISOR_PASSWORD}
     */
    public String step() {
        return step;
    }

    /** @return the quantity entered for the next item, or null */
    public BigDecimal pendingQty() {
        return pendingQty;
    }

    /** @return true in return mode */
    public boolean returnMode() {
        return returnMode;
    }

    /** @return the state the desk locked from, or null when it is not locked */
    public LendingState lockedFrom() {
        return lockedFrom;
    }

    /** @return the lines of the open loan (0 when none is open) */
    public int lineCount() {
        return lineCount;
    }

    /** @return the time left until the idle lock fires, in ms, or null when no idle lock applies */
    public Long idleLockRemainingMs() {
        return idleLockRemainingMs;
    }

    @Override
    public String toString() {
        return "IdleView[" + state + ", step " + step + ", pendingQty " + pendingQty + ", returnMode " + returnMode
            + ", lockedFrom " + lockedFrom + ", lines " + lineCount + ", lock in " + idleLockRemainingMs + "]";
    }
}
