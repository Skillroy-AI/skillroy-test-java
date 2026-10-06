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
 * The {@code skillroy} command line front end ({@link ai.skillroy.testjava.cli.Main}): list JVMs, attach the agent,
 * inspect it (info, capabilities, schema, UI snapshot, events), call primitives, compile scenarios to plans and run
 * them. Built on {@code skillroy-controller} and {@code skillroy-scenario}; the argument parser is hand-written.
 * The assembled distribution is {@code target/skillroy-cli/} ({@code bin/skillroy}, {@code lib/*.jar}).
 */
package ai.skillroy.testjava.cli;
