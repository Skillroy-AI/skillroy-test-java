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

package ai.skillroy.testjava.inference.resolve;

import ai.skillroy.testjava.scenario.Schemas.Doc;

import java.util.List;

/**
 * The structured answer of a tier (b) request (phase5-contracts.md 5.4 step 2): the statement, its parameters and why,
 * with the model's confidence and the rows it expects. Its schema ({@code Schemas.ofRecord}) is the request's output
 * schema; the answer is decoded with {@code Structured.decode}.
 *
 * @param sql the one SELECT, empty when {@code cannotAnswer}
 * @param params one per {@code ?}, in order
 * @param rationale why the statement answers the question
 * @param tablesUsed the tables the statement reads
 * @param orderBy the ORDER BY the model meant
 * @param confidence from 0 to 1
 * @param expectedRows the rows the model expects
 * @param cannotAnswer true when the catalogue cannot answer the question
 * @param cannotAnswerReason why, when {@code cannotAnswer}
 */
public record SqlProposal(
        @Doc("the one SELECT statement for the source's dialect; empty when cannotAnswer is true") String sql,
        @Doc("one entry per ? in the statement, in order") List<Param> params,
        @Doc("why this statement answers the question, in a few sentences; name no person") String rationale,
        @Doc("the tables the statement reads, by their names in the catalogue") List<String> tablesUsed,
        @Doc("the ORDER BY the statement uses") String orderBy,
        @Doc("how sure you are that the statement answers the question, from 0 to 1") double confidence,
        @Doc("how many rows you expect the statement to return") RowBounds expectedRows,
        @Doc("true when the question cannot be answered from the catalogue's tables") boolean cannotAnswer,
        @Doc("why the question cannot be answered; empty when cannotAnswer is false") String cannotAnswerReason) {

    /** Copies the lists; null is empty. */
    public SqlProposal {
        params = params == null ? List.of() : List.copyOf(params);
        tablesUsed = tablesUsed == null ? List.of() : List.copyOf(tablesUsed);
    }

    /**
     * One parameter.
     *
     * @param name the query argument it binds, a dotted path ({@code deposit.between.0})
     * @param value that argument's value as text
     * @param type {@code STRING}, {@code NUMBER}, {@code DATE}, {@code TIMESTAMP} or {@code BOOLEAN}
     */
    public record Param(
            @Doc("the query argument it binds, as a dotted path such as deposit.between.0") String name,
            @Doc("that argument's value as text, exactly as the query holds it") String value,
            @Doc("STRING, NUMBER, DATE, TIMESTAMP or BOOLEAN") String type) {
    }

    /**
     * The rows a model expects.
     *
     * @param min at least
     * @param max at most
     */
    public record RowBounds(@Doc("at least this many rows, 1 or more") int min,
                            @Doc("at most this many rows, 200 or fewer") int max) {
    }
}
