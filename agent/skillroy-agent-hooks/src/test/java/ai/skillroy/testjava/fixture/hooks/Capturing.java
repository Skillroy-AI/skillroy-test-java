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

/** Takes and returns anything, for the encoding of a captured value (phase6-contracts.md section 4.3). */
public class Capturing {

    /** An application object: captured as a handle, never through toString(). */
    public static final class Member {
        public final String code;
        public int toStringCalls;

        public Member(String code) {
            this.code = code;
        }

        @Override
        public String toString() {
            toStringCalls++;
            return "Member " + code;
        }
    }

    public Object take(Object first, Object second, Object third) {
        return first;
    }

    /** A sign-on check: the password argument is a secretArgs index in the tests. */
    public boolean signOn(String staffCode, String password) {
        return "100".equals(staffCode) && password != null;
    }

    /** Returns a credential: its result is marked secretResult in the tests. */
    public String issueToken(String staffCode) {
        return "token-for-" + staffCode;
    }

    /** A label whose call from a mask policy re-enters the handler. */
    public String label() {
        return "label";
    }

    /** No parameters and void: nothing to capture. */
    public void ping() {
    }
}
