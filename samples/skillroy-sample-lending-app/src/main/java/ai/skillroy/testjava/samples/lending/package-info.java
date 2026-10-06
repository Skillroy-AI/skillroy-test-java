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
 * The sample lending desk: the issue desk of a small community tool library, where staff lend tools to borrowers
 * against a deposit that is handed back when the tools come back. A deliberately awkward Swing application, with an
 * engine thread, custom widgets, modal prompts and an embedded database, that knows nothing about the framework.
 *
 * <h2>Structure</h2>
 * <ul>
 *   <li>{@link ai.skillroy.testjava.samples.lending.LendingApp}: {@code main}, wiring, the singleton accessor and
 *       plugin loading through {@link ai.skillroy.testjava.samples.lending.LendingPlugin}.</li>
 *   <li>{@link ai.skillroy.testjava.samples.lending.Engine}: the {@code LendingEngine} thread and its state machine
 *       ({@link ai.skillroy.testjava.samples.lending.LendingState}). It reads inputs from the
 *       {@link ai.skillroy.testjava.samples.lending.Inbox}, asks questions through the
 *       {@link ai.skillroy.testjava.samples.lending.PromptService}, posts loans to the
 *       {@link ai.skillroy.testjava.samples.lending.LendingDb} and prints loan slips through the
 *       {@link ai.skillroy.testjava.samples.lending.SlipPrinter}.</li>
 *   <li>Business services loaded once at start-up and safe to call from any thread:
 *       {@link ai.skillroy.testjava.samples.lending.AccessControl},
 *       {@link ai.skillroy.testjava.samples.lending.Catalogue},
 *       {@link ai.skillroy.testjava.samples.lending.WaiverEngine},
 *       {@link ai.skillroy.testjava.samples.lending.Deposits}.</li>
 *   <li>The immutable loan model: {@link ai.skillroy.testjava.samples.lending.Loan} and its parts.</li>
 *   <li>The Swing user interface, which only observes the engine and posts inputs to the inbox:
 *       {@link ai.skillroy.testjava.samples.lending.LendingFrame},
 *       {@link ai.skillroy.testjava.samples.lending.IconKey},
 *       {@link ai.skillroy.testjava.samples.lending.EntryField},
 *       {@link ai.skillroy.testjava.samples.lending.LoanTableModel},
 *       {@link ai.skillroy.testjava.samples.lending.PromptDialog},
 *       {@link ai.skillroy.testjava.samples.lending.PromptButton}
 *       and {@link ai.skillroy.testjava.samples.lending.SwingPromptView}. The engine and the database never touch
 *       Swing, so they run and are tested without a display.</li>
 *   <li>{@link ai.skillroy.testjava.samples.lending.LendingDbServer}: on request (system property
 *       {@code lending.dbPort}, with the account's password in {@code lending.dbPassword}) the database is also
 *       served on a loopback port to a read-only account, for tools outside the desk's JVM.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * The engine thread is the only thread that changes loan state and writes to the database. Other threads read
 * immutable snapshots ({@link ai.skillroy.testjava.samples.lending.Engine#status()},
 * {@link ai.skillroy.testjava.samples.lending.Engine#currentLoan()}) or use their own read-only connection
 * ({@link ai.skillroy.testjava.samples.lending.LendingDb#openReadOnlyConnection()}, or the read-only account through
 * the database listener). User interface controls post a
 * {@link ai.skillroy.testjava.samples.lending.LendingInput} to the inbox and never call the engine directly; the user
 * interface is updated on the event dispatch thread from engine callbacks.
 */
package ai.skillroy.testjava.samples.lending;
