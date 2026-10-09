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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.samples.lending.AccessControl;
import ai.skillroy.testjava.samples.lending.LendingDb;
import ai.skillroy.testjava.samples.lending.Seed;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/**
 * The headless host of {@link SignOnCheckedHeadlessIT} (phase6-contracts.md 13.1 item 6, fix wave of the Phase 6
 * review, FC): the lending desk's database (seed A) and its real {@link AccessControl}, no window and no engine. The
 * one argument names a file holding {@code <code> <password>} (the password never on the command line); the main
 * thread calls {@link AccessControl#passwordMatches} with them, the method the pack's {@code sign.on.checked} hooks,
 * every {@value #PERIOD_MS} ms (the launcher closes a child's standard input, so the test cannot drive it there), prints
 * {@code checked=<true|false>} once, and ends after {@value #LIFETIME_MS} ms if nobody stopped it first.
 */
public final class SignOnHeadlessMain {

    /** How often the password is checked. */
    static final long PERIOD_MS = 100L;
    /** The longest the host lives (the test closes it long before). */
    static final long LIFETIME_MS = 180_000L;

    private SignOnHeadlessMain() {
    }

    /** @param args the file holding {@code <code> <password>} */
    public static void main(String[] args) throws Exception {
        String line = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8).trim();
        int space = line.indexOf(' ');
        String code = line.substring(0, space);
        String password = line.substring(space + 1);
        try (LendingDb db = LendingDb.create("sign_on_headless", Seed.parse("A"), LocalDate.of(2026, 10, 8))) {
            AccessControl access = AccessControl.load(db.connection());
            System.out.println("ready");
            System.out.flush();
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LIFETIME_MS);
            boolean printed = false;
            while (System.nanoTime() < end) {
                boolean checked = access.passwordMatches(code, password);
                if (!printed) {
                    System.out.println("checked=" + checked);
                    System.out.flush();
                    printed = true;
                }
                Thread.sleep(PERIOD_MS);
            }
        }
        System.exit(0);
    }
}
