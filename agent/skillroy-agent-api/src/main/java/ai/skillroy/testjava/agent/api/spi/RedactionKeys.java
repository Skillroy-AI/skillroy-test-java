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

package ai.skillroy.testjava.agent.api.spi;

import java.util.regex.Pattern;

/**
 * Extra secret keys a module contributes to the agent's redaction (contract amendment A21). The core hides the value
 * of a system property, JVM argument or agent option whose key matches the shared default rule
 * ({@code SecretKeys}), the {@code redact} option, or the pattern of any registered {@code RedactionKeys}, wherever
 * it reports them ({@code jvm.info}, {@code jvm.properties}, {@code agent.info}). Lets a domain module name its
 * application's own secret settings (for example a property such as {@code lending.pin}) without every user having
 * to configure {@code redact}.
 *
 * <p>Consulted at call time, so a registration made after the start takes effect at once. Called on agent threads,
 * possibly concurrently: implementations must be thread-safe, cheap and must not block. A provider that throws
 * makes every key count as secret (fail closed).
 */
public interface RedactionKeys {

    /**
     * The keys this module considers secret: a key is secret when {@link java.util.regex.Matcher#find()} succeeds on
     * it. Case sensitivity is the pattern's own (use {@link Pattern#CASE_INSENSITIVE} to ignore case). Null means
     * none.
     */
    Pattern secretKeys();
}
