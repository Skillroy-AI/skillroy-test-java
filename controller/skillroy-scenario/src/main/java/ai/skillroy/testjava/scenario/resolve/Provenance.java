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

package ai.skillroy.testjava.scenario.resolve;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Everything needed to review how a slot's value was found: the resolver whose candidate was accepted, every
 * statement and read-only call that resolver made (in order), notes, how many candidates reached the verifiers, and
 * the candidates that were not accepted with the reason.
 *
 * <p>Built by the engine. Immutable (lists are deep unmodifiable copies); safe to share between threads.
 *
 * @param resolverId the accepted candidate's resolver; written as {@code provenance.resolver}
 * @param statements the statements of that resolver, in order
 * @param calls the read-only primitives that resolver called through {@code ctx.agent()}, in order
 * @param notes the resolver's {@code ctx.note} texts; not written to the bindings file
 * @param candidates how many candidates reached the verifiers, over all resolvers asked
 * @param candidatesTruncated true when more candidates existed than were handed to the verifiers, or a candidate
 *     statement was cut at 200 rows
 * @param rejected the candidates that were not accepted (at most 20), with resolver, label and reason
 * @param rationale why a model wrote the accepted statement (tier (b), phase5-contracts.md 5.4 step 5): the model's
 *     text, masked by the resolver, at most {@value #MAX_RATIONALE} characters (longer text is cut); null for tier (a)
 */
public record Provenance(String resolverId, List<Statement> statements, List<Call> calls, List<String> notes,
                         int candidates, boolean candidatesTruncated, List<Rejected> rejected, String rationale) {

    /** The longest rationale kept. */
    public static final int MAX_RATIONALE = 2000;

    /** Copies the lists (null is empty) and cuts the rationale at {@value #MAX_RATIONALE} characters. */
    public Provenance {
        statements = statements == null ? List.of() : List.copyOf(statements);
        calls = calls == null ? List.of() : List.copyOf(calls);
        notes = notes == null ? List.of() : List.copyOf(notes);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
        rationale = rationale == null || rationale.length() <= MAX_RATIONALE ? rationale
                : rationale.substring(0, MAX_RATIONALE);
    }

    /** The Phase 2 shape: no rationale (tier (a)). */
    public Provenance(String resolverId, List<Statement> statements, List<Call> calls, List<String> notes,
                      int candidates, boolean candidatesTruncated, List<Rejected> rejected) {
        this(resolverId, statements, calls, notes, candidates, candidatesTruncated, rejected, null);
    }

    /**
     * One statement a resolver ran.
     *
     * @param channel {@code "agent"} or {@code "jdbc"}
     * @param source the source id
     * @param sql the statement as the resolver passed it
     * @param params the parameters in the forms of phase2-contracts.md 7.1, masked
     * @param rowCount the rows returned
     * @param truncated true when the result was cut at the row cap
     * @param rowSample at most three rows, masked by the pack's row masks and the run's secrets; not written to
     *     the bindings file
     */
    public record Statement(String channel, String source, String sql, List<Object> params, int rowCount,
                            boolean truncated, List<List<Object>> rowSample) {

        /** Copies the lists (rows may hold nulls; a cell a row mask turned into a map keeps its keys sorted). */
        public Statement {
            Objects.requireNonNull(sql, "sql");
            params = params == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(params));
            List<List<Object>> sample = new ArrayList<>();
            if (rowSample != null) {
                for (List<Object> row : rowSample) {
                    List<Object> cells = new ArrayList<>(row.size());
                    for (Object cell : row) {
                        cells.add(Frozen.freezeSorted(cell));
                    }
                    sample.add(Collections.unmodifiableList(cells));
                }
            }
            rowSample = Collections.unmodifiableList(sample);
        }
    }

    /**
     * A read-only primitive a resolver called through {@code ctx.agent()}: the review trail of a non-SQL resolver.
     *
     * @param primitive the primitive's name
     * @param params its params, masked; kept with the keys of every map in sorted order, so that the bindings file,
     *     the run's {@code resolve} record and the approval request do not depend on the pack's map order
     */
    public record Call(String primitive, Object params) {

        /** Freezes the params, keys sorted at every depth. */
        public Call {
            Objects.requireNonNull(primitive, "primitive");
            params = Frozen.freezeSorted(params);
        }
    }

    /**
     * A candidate that was not accepted.
     *
     * @param resolverId the resolver that offered it; written as {@code resolver}
     * @param label the candidate's label ({@code "staff 100"})
     * @param reason why: the first failing verifier's reason, or {@code "no credential staff/100"}
     */
    public record Rejected(String resolverId, String label, String reason) {
    }
}
