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

/** Classes the probe refuses (phase6-contracts.md section 3.3 items 4 and 5). */
public final class Refusals {

    private Refusals() {
    }

    /** A native method (never called). */
    public static class WithNative {
        public native int peek();

        public int plain() {
            return 1;
        }
    }

    /** An abstract method. */
    public abstract static class WithAbstract {
        public abstract int later();
    }

    /** Six methods and a constructor: above a method bound of 5. */
    public static class Wide {
        public int a() {
            return 1;
        }

        public int b() {
            return 2;
        }

        public int c() {
            return 3;
        }

        public int d() {
            return 4;
        }

        public int e() {
            return 5;
        }

        public int f() {
            return 6;
        }
    }

    /** Never loaded by any test (the "class not loaded" case names it by string only). */
    public static class NeverLoaded {
        public int x() {
            return 0;
        }
    }
}
