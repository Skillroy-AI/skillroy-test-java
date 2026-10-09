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


package ai.skillroy.testjava.fixture.hooks;

/**
 * The desk's late-fee rule, for the install whose classes retransform only partly (a hook over two loaders, one copy
 * failing). Only {@code PartialInstallTest} loads it, and in a child loader of its own, so a copy left in the JVM
 * widens no other test's spec.
 */
public class LateFeeRule {

    private final int perDay;

    public LateFeeRule() {
        this(2);
    }

    public LateFeeRule(int perDay) {
        this.perDay = perDay;
    }

    /** The fee for a loan returned this many days late. */
    public int feeFor(int days) {
        return days <= 0 ? 0 : days * perDay;
    }

    /** The band a late return falls in. */
    public String band(int days) {
        if (days > 14) {
            return "overdue";
        }
        if (days > 7) {
            return "late";
        }
        return days > 0 ? "grace" : "due";
    }

    /** The text of the slip line for a late return. */
    public String slipLine(String item, int days) {
        return item + ": " + band(days) + ", fee " + feeFor(days);
    }
}
