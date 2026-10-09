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

package ai.skillroy.testjava.spikes.bs3.toy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongBiFunction;

/**
 * The toy target of BS3: a tool library's deposit rule. It references nothing but {@code java.*}, so a loader that
 * delegates only {@code java.*} can define it. {@link #applyAsLong} lets the host call {@link #depositFor} directly
 * through a JDK interface, without reflection, whichever loader defined this class.
 */
public class DepositRule implements ToLongBiFunction<String, Integer> {

    /** @return the deposit in cents for lending the item for so many days: 150 a day plus the item code's length */
    public long depositFor(String itemCode, int days) {
        if (days < 0) {
            throw new IllegalArgumentException("days must not be negative: " + days);
        }
        return 150L * days + (itemCode == null ? 0 : itemCode.length());
    }

    /** @return true when a borrower with this many items out gets the borrow-2-waive-1 waiver */
    public boolean waives(int itemsOnLoan) {
        return itemsOnLoan >= 2;
    }

    /** @return the name of the rate in force; its constant is what another agent's transformer rewrites in item 3 */
    public String rateName() {
        return "rate:standard";
    }

    /**
     * A return that waits at the desk: signals {@code entered}, then waits for {@code release} (bounded). Used to
     * hold a thread inside the method while the class is restored (BS3 item 5).
     */
    public String holdReturn(CountDownLatch entered, CountDownLatch release, long timeoutMs)
            throws InterruptedException {
        entered.countDown();
        boolean released = release.await(timeoutMs, TimeUnit.MILLISECONDS);
        return released ? "returned" : "timed out";
    }

    @Override
    public long applyAsLong(String itemCode, Integer days) {
        return depositFor(itemCode, days);
    }
}
