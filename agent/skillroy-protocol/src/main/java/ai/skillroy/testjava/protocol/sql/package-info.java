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

/**
 * The rules both data channels share (phase2-contracts.md 2.2): the statement guard, the column-value conversion, the
 * JDBC read path and the rows they produce. The agent's {@code data.query} uses them inside the target JVM, the
 * controller's JDBC channel on the controller, so that "over either channel" holds: a statement one accepts the other
 * accepts, parameters are bound with the same Java types, and a value read through one has the JSON form it has
 * through the other.
 *
 * <p>Phase 8 (phase8-contracts.md 4.3, 4.4): {@link ai.skillroy.testjava.protocol.sql.SelectLabels} reads the labels a
 * statement's top-level select list gives its columns (the agent names a positional source's columns with them), with
 * the guard's own lexical rules through the package-private tokenizer {@code SqlTokens} (moved out of
 * {@link ai.skillroy.testjava.protocol.sql.StatementGuard} unchanged); the read path's statement sink
 * ({@code ReadOnlyJdbc.executeLenient} with a {@code Consumer<Statement>}) lets the agent cancel a statement while it
 * is open.
 *
 * <p>This is the only package of {@code skillroy-protocol} that references {@code java.sql}; the wire types in
 * {@code ai.skillroy.testjava.protocol} load on a runtime without that module. Java 8, JDK only.
 */
package ai.skillroy.testjava.protocol.sql;
