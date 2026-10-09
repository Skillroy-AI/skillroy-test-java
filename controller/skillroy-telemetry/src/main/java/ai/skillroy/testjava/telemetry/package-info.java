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
 * OpenTelemetry export of runs, sessions and MCP tool calls (phase6-contracts.md 10, decisions D140-D143): the
 * {@code otlp} and {@code file} providers of the {@code RunObserver} SPI, the span builder, the attribute allow-list and
 * the {@code skillroy-spans/1} writer. Controller only: nothing of it enters a target JVM.
 */
package ai.skillroy.testjava.telemetry;
