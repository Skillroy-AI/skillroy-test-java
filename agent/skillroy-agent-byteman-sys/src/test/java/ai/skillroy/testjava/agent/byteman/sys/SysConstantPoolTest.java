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

package ai.skillroy.testjava.agent.byteman.sys;

import ai.skillroy.testjava.agent.api.instrument.ClassFileRefs;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sys jar's classes reference nothing of Byteman as a class (phase6-contracts.md 2.1, 7.3; BS14 condition 4):
 * every {@code CONSTANT_Class} of every class file under {@code target/classes} is under {@code java/} or
 * {@code ai/skillroy/}; Byteman's types appear in method descriptors only. Every class file is major 52.
 */
class SysConstantPoolTest {

    @Test
    void noClassReferenceOutsideJavaAndTheProject() throws IOException {
        final List<Path> classes = new ArrayList<Path>();
        Files.walkFileTree(Paths.get("target/classes"), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".class")) {
                    classes.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertEquals(3, classes.size(), "RuleManager, RuleHelper and RuleHelper$Handler: " + classes);
        List<String> outside = new ArrayList<String>();
        for (Path file : classes) {
            byte[] bytes = Files.readAllBytes(file);
            assertEquals(52, ClassFileRefs.majorVersion(bytes), file.toString());
            for (String ref : ClassFileRefs.classReferences(bytes)) {
                if (!ref.startsWith("java/") && !ref.startsWith("ai/skillroy/")) {
                    outside.add(file.getFileName() + " -> " + ref);
                }
            }
        }
        assertTrue(outside.isEmpty(), outside.toString());
    }
}
