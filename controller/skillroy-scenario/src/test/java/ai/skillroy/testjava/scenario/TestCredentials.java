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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.data.CredentialsException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Made-up credentials for the toy pack's tests (phase2-contracts.md 13.4: the tests pass {@code BindOptions} with
 * {@code Credentials.of(…)} built in code; no credentials file is committed). Passwords are long enough (8 or more
 * characters) to be masked inside longer texts too.
 */
final class TestCredentials {

    static final String PW_100 = "pw-100-secret";
    static final String PW_1 = "pw-001-secret";
    static final String PW_200 = "pw-200-secret";
    static final String PW_300 = "pw-300-secret";

    private TestCredentials() {
    }

    /** Realm {@code staff} with entries 1, 100, 200 and 300, each with a {@code password}. */
    static Credentials staff() {
        Map<String, Object> staff = new LinkedHashMap<>();
        staff.put("1", Map.of("password", PW_1));
        staff.put("100", Map.of("password", PW_100));
        staff.put("200", Map.of("password", PW_200));
        staff.put("300", Map.of("password", PW_300));
        return of(Map.of("version", 1L, "credentials", Map.of("staff", staff)));
    }

    static Credentials of(Map<String, Object> document) {
        try {
            return Credentials.of(document, "test credentials");
        } catch (CredentialsException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Bind options with {@link #staff()}. */
    static BindOptions bind() {
        return BindOptions.defaults().withCredentials(staff());
    }
}
