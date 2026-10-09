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

package ai.skillroy.testjava.inference.job;

import ai.skillroy.testjava.scenario.FailureKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 security fix S50: the re-resolve report's Markdown (a pull request's body) renders the texts a model or the
 * binder wrote, a failure's message and the notes, as code, so that their markup is inert.
 */
class ReResolveReportTest {

    private static final String MARKUP = "![x](https://e.example/x) [click](https://e.example/y)\n# heading";

    @Test
    void aFailuresMessageAndTheNotesAreRenderedAsCode() {
        ReResolveReport report = new ReResolveReport("job-1", "seed-a", true, "a person", "scripted",
                Instant.parse("2026-10-09T02:00:00Z"), Duration.ofSeconds(3), List.of(new ReResolveReport.ScenarioReport(
                        "return-limit", ReResolveReport.Status.FAILED, "seed-a/return-limit.bindings.json", false, 0,
                        BigDecimal.ZERO, List.of(), FailureKind.RESOLUTION_FAILED, "NO_CANDIDATE", MARKUP)),
                BigDecimal.ZERO, new BigDecimal("1.00"), 1, 1, 1, null, List.of("claude-sonnet-5-5"),
                List.of("the model said: " + MARKUP), List.of(), List.of());
        String md = report.toMarkdown();
        assertTrue(md.contains("- `return-limit`: RESOLUTION_FAILED `NO_CANDIDATE`: `![x](https://e.example/x)"
                + " [click](https://e.example/y) # heading`"), md);
        assertTrue(md.contains("- `the model said: ![x](https://e.example/x)"), md);
        for (String line : md.lines().toList()) {
            assertFalse(line.startsWith("![x]") || line.startsWith("# heading"), line);
        }
    }
}
