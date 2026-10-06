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

/**
 * A scenario file or plan cannot be loaded or compiled: malformed YAML, an unknown key or verb, bad verb arguments, a
 * bad expectation path or matcher. Checked, because a caller (CLI, JUnit glue) must report it against the scenario
 * rather than as a crash. It is never a test failure: nothing has run yet.
 *
 * <p>{@link #getMessage()} reads {@code "<scenario or source> line <n>: <reason>"} when the location is known.
 * Immutable; safe to share between threads.
 */
public final class ScenarioException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String scenarioId;
    private final int line;
    private final String reason;

    /** A failure whose location the caller fills in later (verbs throw this; the compiler adds id and line). */
    public ScenarioException(String reason) {
        this(null, 0, reason, null);
    }

    /** As {@link #ScenarioException(String)}, with a cause. */
    public ScenarioException(String reason, Throwable cause) {
        this(null, 0, reason, cause);
    }

    /**
     * @param scenarioId the scenario id, or the source name while the id is unknown; may be null
     * @param line 1-based line in the scenario file, 0 when unknown
     */
    public ScenarioException(String scenarioId, int line, String reason) {
        this(scenarioId, line, reason, null);
    }

    /** As {@link #ScenarioException(String, int, String)}, with a cause. */
    public ScenarioException(String scenarioId, int line, String reason, Throwable cause) {
        super(format(scenarioId, line, reason), cause);
        this.scenarioId = scenarioId;
        this.line = Math.max(0, line);
        this.reason = reason;
    }

    private static String format(String scenarioId, int line, String reason) {
        StringBuilder text = new StringBuilder();
        if (scenarioId != null) {
            text.append(scenarioId);
        }
        if (line > 0) {
            text.append(text.length() == 0 ? "line " : " line ").append(line);
        }
        if (text.length() > 0) {
            text.append(": ");
        }
        return text.append(reason).toString();
    }

    /** The scenario id (or source name), or null when unknown. */
    public String scenarioId() {
        return scenarioId;
    }

    /** 1-based line in the scenario file, 0 when unknown. */
    public int line() {
        return line;
    }

    /** The reason without the location prefix. */
    public String reason() {
        return reason;
    }

    /**
     * This exception with the scenario id and line filled in where they are missing; itself if nothing is missing.
     */
    public ScenarioException locate(String id, int atLine) {
        String newId = scenarioId != null ? scenarioId : id;
        int newLine = line > 0 ? line : atLine;
        if ((newId == null ? scenarioId == null : newId.equals(scenarioId)) && newLine == line) {
            return this;
        }
        ScenarioException located = new ScenarioException(newId, newLine, reason, getCause());
        located.setStackTrace(getStackTrace());
        return located;
    }
}
