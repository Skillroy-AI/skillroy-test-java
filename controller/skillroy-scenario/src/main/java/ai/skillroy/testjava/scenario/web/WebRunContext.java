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

package ai.skillroy.testjava.scenario.web;

import java.nio.file.Path;
import java.util.Map;

/**
 * What a {@link WebPath} may know about the run it is opened for (the {@code RunContext} of phase4-contracts.md
 * section 6.1): where artifacts go and how to append a record to {@code run.jsonl}. Implemented by the executor.
 */
public interface WebRunContext {

    /**
     * The run's artifacts directory (its output directory), or {@code null} when the run writes none. A path writes
     * there only what the executor asks for (the trace file of {@link WebPath#saveTrace}); nothing that records the
     * pages as the run goes, which would keep what a page showed, a typed secret included (phase4-contracts.md 6.4).
     */
    Path artifactsDir();

    /** Appends one record to {@code run.jsonl} as given (the executor adds the line, nothing else). */
    void record(Map<String, Object> record);
}
