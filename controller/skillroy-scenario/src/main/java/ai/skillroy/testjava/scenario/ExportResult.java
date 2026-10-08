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

import java.util.List;
import java.util.Objects;

/**
 * A session exported as a scenario (phase5-contracts.md 12.3): the YAML, the bindings file as {@code approve} would
 * write it, and the report for the reviewer. Nothing is written by the export itself: its callers ({@code skillroy
 * session export}, the MCP server) write {@code <id>.yaml}, {@code plan/<id>.plan.json} and
 * {@code bindings/<env>/<id>.bindings.json}.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param yaml the scenario's text, which the scenario loader reads and the compiler compiles
 * @param bindings the bindings file of its query slots and oracles, or null when it has none
 * @param report what the reviewer should look at
 */
public record ExportResult(String yaml, BindingsFile bindings, Report report) {

    /** @throws NullPointerException if the YAML or the report is null */
    public ExportResult {
        Objects.requireNonNull(yaml, "yaml");
        Objects.requireNonNull(report, "report");
    }

    /**
     * The reviewer's report of an export.
     *
     * @param scenarioId the exported scenario
     * @param sessionId the session it came from
     * @param steps how many steps the scenario has
     * @param inference the compiled plan's {@code inference}: {@code bind} when a tier (b) slot was exported
     * @param observed the expectations written from an observed value, each "{@code path: value (step n)}": derive or
     *     confirm them (D86)
     * @param notHeld the checks that did not hold, which are not exported
     * @param excluded the steps left out (they did not pass, or came after the first failure)
     * @param notes anything else the reviewer should know, one line each
     */
    public record Report(String scenarioId, String sessionId, int steps, String inference, List<String> observed,
                         List<String> notHeld, List<String> excluded, List<String> notes) {

        /** Copies the lists. */
        public Report {
            observed = List.copyOf(observed);
            notHeld = List.copyOf(notHeld);
            excluded = List.copyOf(excluded);
            notes = List.copyOf(notes);
        }

        /** The report as text, one section per list that is not empty. */
        public String text() {
            StringBuilder text = new StringBuilder();
            text.append("Exported session ").append(sessionId).append(" as ").append(scenarioId).append(": ")
                    .append(steps).append(steps == 1 ? " step" : " steps").append(", inference ").append(inference)
                    .append(".\n");
            section(text, "Observed expectations: derive or confirm", observed);
            section(text, "Checks that did not hold (not exported)", notHeld);
            section(text, "Steps not exported", excluded);
            section(text, "Notes", notes);
            return text.toString();
        }

        private static void section(StringBuilder text, String title, List<String> lines) {
            if (lines.isEmpty()) {
                return;
            }
            text.append(title).append('\n');
            for (String line : lines) {
                text.append("  - ").append(Values.printableLine(line)).append('\n');
            }
        }
    }
}
