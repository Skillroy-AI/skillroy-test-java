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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.agent.api.spi.hook.ClassDeny;
import ai.skillroy.testjava.protocol.hook.ClassPattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The lending module's class deny (phase8-contracts.md 6.5 source 3, 12.2, D573): a domain module's own
 * {@link ClassDeny}, registered only when the agent option {@value #OPTION} names patterns (comma-separated
 * {@link ClassPattern}s), answering {@code the lending module denies <pattern>}. No launch sets the option: it is the
 * sample's demonstration of the third source of the deny, behind an option as every sample addition is. Names only:
 * nothing of the application is touched.
 *
 * <p>Immutable; safe from any agent thread.
 */
final class LendingClassDeny implements ClassDeny {

    /** The agent option naming the patterns. */
    static final String OPTION = "lending.hooks.deny";

    private final List<ClassPattern> patterns;

    private LendingClassDeny(List<ClassPattern> patterns) {
        this.patterns = Collections.unmodifiableList(patterns);
    }

    /**
     * The deny of the option's value, or null when it names no pattern.
     *
     * @throws IllegalArgumentException {@code option lending.hooks.deny: pattern <i> <violation>} (the module's
     *     FAILED record names the key), or more than 64 patterns
     */
    static LendingClassDeny fromOption(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        List<ClassPattern> out = new ArrayList<ClassPattern>();
        int i = 0;
        for (String item : text.split(",", -1)) {
            String p = item.trim();
            if (p.isEmpty()) {
                continue;
            }
            String violation = ClassPattern.violation(p);
            if (violation != null) {
                throw new IllegalArgumentException("option " + OPTION + ": pattern " + i + " " + violation);
            }
            out.add(ClassPattern.compile(p));
            i++;
        }
        if (out.size() > ClassPattern.MAX_PER_SOURCE) {
            throw new IllegalArgumentException("option " + OPTION + ": more than " + ClassPattern.MAX_PER_SOURCE
                    + " patterns (" + out.size() + ")");
        }
        return out.isEmpty() ? null : new LendingClassDeny(out);
    }

    /** The patterns, as given. */
    List<String> patterns() {
        List<String> out = new ArrayList<String>();
        for (ClassPattern p : patterns) {
            out.add(p.pattern());
        }
        return out;
    }

    @Override
    public String refuse(String className) {
        for (ClassPattern p : patterns) {
            if (p.matches(className)) {
                return "the lending module denies " + p.pattern();
            }
        }
        return null;
    }
}
