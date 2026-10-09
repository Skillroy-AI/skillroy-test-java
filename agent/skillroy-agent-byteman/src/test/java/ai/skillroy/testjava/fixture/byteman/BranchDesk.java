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

/** A subclass of the toy, for the {@code ^} rules of the class bound. */
public class BranchDesk extends DepositDesk {

    @Override
    public BigDecimal ceiling(String code) {
        return super.ceiling(code).add(BigDecimal.TEN);
    }
}
