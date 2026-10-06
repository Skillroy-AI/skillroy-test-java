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

import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.data.CredentialsException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The credentials the sample's tests run with (phase2-contracts.md 13.4): an entry in realm {@code staff} for every
 * staff member of a seed, with the made-up passwords the seed data uses (test code, as the seed data is application
 * code), and optionally a JDBC source {@code lending} for the controller-side channel. No credentials file is
 * committed: {@link #write} generates {@code credentials.yaml}, owner-only, into a directory the test owns below
 * {@code java.io.tmpdir} (the build directory is bind-mounted by {@code ci/run-in-linux.sh}, where owner-only
 * permissions may not hold).
 *
 * <p>Stateless; safe from any thread.
 */
final class SampleCredentials {

    /** The two seeded environments and their ids (the bindings directories). */
    enum Env {
        SEED_A("seed-a", "A"),
        SEED_B("seed-b", "B");

        final String id;
        final String seed;

        Env(String id, String seed) {
            this.id = id;
            this.seed = seed;
        }
    }

    /** Seed A's staff codes and passwords (made-up sample data, as in the application's seed). */
    static final Map<String, String> SEED_A = ordered("100", "1111", "101", "2222", "102", "5555", "103", "6666",
            "104", "7777", "200", "3333", "300", "4444");
    /** Seed B's staff codes and passwords. */
    static final Map<String, String> SEED_B = ordered("4711", "9182", "4712", "7364", "4713", "1357", "4714", "2468",
            "4715", "8642", "4716", "1029", "4720", "5546", "4790", "3928");

    /**
     * A JDBC source {@code lending}: where the controller-side channel reads the desk's database. The password is a
     * secret (for a launched desk, generated per launch): {@link #toString()} leaves it out, so that no assertion
     * message or log line can carry it.
     */
    record JdbcSource(String url, String user, String password) {
        @Override
        public String toString() {
            return "JdbcSource[url=" + url + ", user=" + user + "]";
        }
    }

    private SampleCredentials() {
    }

    static Map<String, String> staff(Env env) {
        return env == Env.SEED_A ? SEED_A : SEED_B;
    }

    /** The text of a {@code credentials.yaml} for the environment. */
    static String yaml(Env env, JdbcSource jdbc) {
        StringBuilder text = new StringBuilder("version: 1\ncredentials:\n  staff:\n");
        staff(env).forEach((code, password) -> text.append("    \"").append(code).append("\": { password: \"")
                .append(password).append("\" }\n"));
        if (jdbc != null) {
            text.append("jdbc:\n  lending:\n    url: ").append(jdbc.url()).append("\n    user: ").append(jdbc.user())
                    .append("\n    password: \"").append(jdbc.password()).append("\"\n    statementTimeout: 10s\n");
        }
        return text.toString();
    }

    /**
     * Writes {@code <dir>/credentials.yaml}, owner-only where the file system has POSIX permissions.
     *
     * @return the file
     */
    static Path write(Path dir, Env env, JdbcSource jdbc) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(Credentials.FILE_NAME);
        Files.writeString(file, yaml(env, jdbc), StandardCharsets.UTF_8);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
        return file;
    }

    /** Writes and loads the file, through the same reader and permission rule a run uses. */
    static Credentials load(Path dir, Env env, JdbcSource jdbc) throws IOException, CredentialsException {
        return Credentials.load(write(dir, env, jdbc));
    }

    private static Map<String, String> ordered(String... codesAndPasswords) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < codesAndPasswords.length; i += 2) {
            map.put(codesAndPasswords[i], codesAndPasswords[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
