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
 * The vision bolt-on of interactive sessions (phase7-contracts.md 6, decisions D302-D358): {@link
 * ai.skillroy.testjava.vision.VisionSessionTools} plugs into the session service's {@code SessionTools} seam and acts
 * only through its facade; {@link ai.skillroy.testjava.vision.Grounding} captures and hit-tests, {@link
 * ai.skillroy.testjava.vision.Recorder} turns a model's action into the step that will be replayed, {@link
 * ai.skillroy.testjava.vision.VisualQuestion} asks one question on one capture, {@link
 * ai.skillroy.testjava.vision.ScenarioPlayer} runs a scenario with {@code aiAct} and {@code aiExpect} steps over a
 * session its host opened, {@link ai.skillroy.testjava.vision.Captures} keeps the acted-on captures, and {@link
 * ai.skillroy.testjava.vision.VisionCapabilities} contributes {@code inference.vision}. Optional at run time: found
 * through {@code ServiceLoader}; nothing below this module names a vision type. Controller only.
 */
package ai.skillroy.testjava.vision;
