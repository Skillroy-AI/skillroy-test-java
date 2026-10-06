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

package ai.skillroy.testjava.data.yaml;

/**
 * A YAML document that {@link StrictYaml} refused: malformed, or against one of its rules. Carries the line (1-based;
 * 0 when unknown) and the reason without a file name, so that each caller names the file in its own words (a scenario
 * loader wraps it in its own exception).
 *
 * <p>Some reasons quote the document's text (an ambiguous number, the parser's own message for malformed YAML);
 * {@link #quotesInput()} says so, and a reader of a file that holds secrets (credentials) must then not repeat the
 * reason. Immutable.
 */
public final class YamlException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int line;
    private final String reason;
    private final boolean quotesInput;

    /**
     * @param line 1-based line, 0 when unknown
     * @param reason what is wrong, one line
     */
    public YamlException(int line, String reason) {
        this(line, reason, false, null);
    }

    /**
     * @param quotesInput true when {@code reason} repeats text of the document
     * @param cause the parser's exception, or null
     */
    public YamlException(int line, String reason, boolean quotesInput, Throwable cause) {
        super((line > 0 ? "line " + line + ": " : "") + reason, cause);
        this.line = Math.max(0, line);
        this.reason = reason;
        this.quotesInput = quotesInput;
    }

    /** The 1-based line, or 0 when unknown. */
    public int line() {
        return line;
    }

    /** The reason, without the line. */
    public String reason() {
        return reason;
    }

    /** True when {@link #reason()} (and the cause's message) may repeat text of the document. */
    public boolean quotesInput() {
        return quotesInput;
    }
}
