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
 * The session service of the interactive profile (phase5-contracts.md 12.4, B15, D87): {@link
 * ai.skillroy.testjava.inference.session.SessionService} lets a model carry out a person's request through an {@code
 * ExecutorSession}, one journaled action at a time, within a {@link ai.skillroy.testjava.inference.session.SessionPolicy}
 * and the session budget; each request is a {@link ai.skillroy.testjava.inference.session.Turn}. The {@link
 * ai.skillroy.testjava.inference.session.OverlayBridge} serves an in-application overlay's requests through the
 * target's own event stream. Its hosts are the CLI's {@code session} command, the MCP server and the bridge.
 */
package ai.skillroy.testjava.inference.session;
