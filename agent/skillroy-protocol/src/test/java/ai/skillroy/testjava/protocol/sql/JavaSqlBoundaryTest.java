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

package ai.skillroy.testjava.protocol.sql;

import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase2-contracts.md 2.2: no class of {@code skillroy-protocol} outside {@code …protocol.sql} references
 * {@code java.sql}, so that the wire types still load on a runtime without that module. Checked on the compiled class
 * files: a class that refers to a {@code java.sql} type carries its internal name in the constant pool.
 */
class JavaSqlBoundaryTest {

    @Test
    void onlyTheSqlPackageReferencesJavaSql() throws IOException, URISyntaxException {
        File root = new File(Json.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(root.isDirectory(), "the main classes are a directory in the build: " + root);
        File protocol = new File(root, "ai/skillroy/testjava/protocol");
        List<String> offenders = new ArrayList<String>();
        int checked = scan(protocol, offenders);
        assertTrue(checked > 10, "classes were found: " + checked);
        assertTrue(offenders.isEmpty(), "classes outside protocol.sql that reference java.sql: " + offenders);
        // The check itself can see a reference.
        byte[] guard = Files.readAllBytes(new File(protocol, "sql/ReadOnlyJdbc.class").toPath());
        assertTrue(new String(guard, StandardCharsets.ISO_8859_1).contains("java/sql/"));
    }

    private static int scan(File dir, List<String> offenders) throws IOException {
        int n = 0;
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().equals("sql")) {
                    n += scan(f, offenders);
                }
            } else if (f.getName().endsWith(".class")) {
                n++;
                String bytes = new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1);
                if (bytes.contains("java/sql/")) {
                    offenders.add(f.getName());
                }
            }
        }
        return n;
    }
}
