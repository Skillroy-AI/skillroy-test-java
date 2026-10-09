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

import java.math.BigDecimal;
import java.math.BigInteger;

/** One method per return type, for the stub conversions of phase6-contracts.md section 3.4. */
public class Returns {

    /** A lending category. */
    public enum Kind {
        STANDARD, POWER_TOOL
    }

    public int count() {
        return 7;
    }

    public long total() {
        return 70L;
    }

    public short shelf() {
        return 3;
    }

    public byte bin() {
        return 2;
    }

    public char grade() {
        return 'B';
    }

    public boolean open() {
        return true;
    }

    public float weight() {
        return 1.5f;
    }

    public double rate() {
        return 0.25d;
    }

    public BigDecimal amount() {
        return new BigDecimal("12.50");
    }

    public BigInteger serial() {
        return BigInteger.valueOf(1234567L);
    }

    public String name() {
        return "drill";
    }

    public Kind kind() {
        return Kind.STANDARD;
    }

    public Integer boxed() {
        return Integer.valueOf(5);
    }

    public Object other() {
        return "other";
    }

    /** Counts its calls; the body a void STUB skips. */
    public int touches;

    public void touch() {
        touches++;
    }

    /** Throws: a STUB skips a throwing body too. */
    public BigDecimal broken() {
        throw new IllegalStateException("broken");
    }
}
