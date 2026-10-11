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
import java.util.Map;
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
 * @param images label to the masked PNG of each anchor the bindings file's {@code images} section names
 *     (phase7-contracts.md 6.6, 8.7): the caller places them under {@code <scenarioId>.images/} beside the bindings
 *     file; empty without anchors
 */
public record ExportResult(String yaml, BindingsFile bindings, Report report, Map<String, byte[]> images) {

    /** @throws NullPointerException if the YAML or the report is null */
    public ExportResult {
        Objects.requireNonNull(yaml, "yaml");
        Objects.requireNonNull(report, "report");
        Map<String, byte[]> copy = new java.util.TreeMap<>();
        if (images != null) {
            images.forEach((label, png) -> copy.put(label, png.clone()));
        }
        images = java.util.Collections.unmodifiableMap(copy);
    }

    /** An export without anchors (the Phase 5 shape). */
    public ExportResult(String yaml, BindingsFile bindings, Report report) {
        this(yaml, bindings, report, Map.of());
    }

    /** The anchors' PNGs, label to bytes; each call answers fresh copies. */
    @Override
    public Map<String, byte[]> images() {
        Map<String, byte[]> copy = new java.util.TreeMap<>();
        images.forEach((label, png) -> copy.put(label, png.clone()));
        return copy;
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
     * @param visionDerived one line per step a vision tool chose (phase7-contracts.md 6.6): the step, the capture's
     *     hash and file, the hit-test's ref, the mark, the point: review the locator and the rung
     * @param liveOnly the steps that cannot replay and are not exported, with the reason (Robot steps, raw pointer
     *     calls)
     */
    public record Report(String scenarioId, String sessionId, int steps, String inference, List<String> observed,
                         List<String> notHeld, List<String> excluded, List<String> notes, List<String> visionDerived,
                         List<String> liveOnly) {

        /** Copies the lists. */
        public Report {
            observed = List.copyOf(observed);
            notHeld = List.copyOf(notHeld);
            excluded = List.copyOf(excluded);
            notes = List.copyOf(notes);
            visionDerived = visionDerived == null ? List.of() : List.copyOf(visionDerived);
            liveOnly = liveOnly == null ? List.of() : List.copyOf(liveOnly);
        }

        /** A report without vision-derived or live-only steps (the Phase 5 shape). */
        public Report(String scenarioId, String sessionId, int steps, String inference, List<String> observed,
                      List<String> notHeld, List<String> excluded, List<String> notes) {
            this(scenarioId, sessionId, steps, inference, observed, notHeld, excluded, notes, List.of(), List.of());
        }

        /** The report as text, one section per list that is not empty. */
        public String text() {
            StringBuilder text = new StringBuilder();
            text.append("Exported session ").append(sessionId).append(" as ").append(scenarioId).append(": ")
                    .append(steps).append(steps == 1 ? " step" : " steps").append(", inference ").append(inference)
                    .append(".\n");
            section(text, "Observed expectations: derive or confirm", observed);
            section(text, "Vision-derived steps: review the locator and the rung", visionDerived);
            section(text, "Live-only", liveOnly);
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
