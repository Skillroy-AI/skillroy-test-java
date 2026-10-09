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

package ai.skillroy.testjava.fixture.byteman;

import java.math.BigDecimal;

/**
 * The module tests' toy target (outside the protected prefixes, phase6-contracts.md 2.3): the return ceiling a staff
 * code may approve, and the deposit an item needs.
 */
public class DepositDesk implements Ceilings {

    @Override
    public BigDecimal ceiling(String code) {
        if (code == null || code.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return code.startsWith("SUP") ? new BigDecimal("500.00") : new BigDecimal("150.00");
    }

    public BigDecimal deposit(String itemCode) {
        return itemCode != null && itemCode.startsWith("DRL") ? new BigDecimal("40.00") : new BigDecimal("10.00");
    }

    public int waived(int loans) {
        return loans >= 2 ? 1 : 0;
    }
}
