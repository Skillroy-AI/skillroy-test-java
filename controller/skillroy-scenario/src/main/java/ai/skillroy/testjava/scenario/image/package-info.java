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
 * The deterministic image comparison and the one code path of image steps (phase7-contracts.md 6.7, decision D313):
 * {@link ai.skillroy.testjava.scenario.image.ImageCompare} compares decoded rasters exactly after masks,
 * {@link ai.skillroy.testjava.scenario.image.ImageSteps} runs an {@code expectImage} check and an anchored
 * {@code pointer} step's guard for {@code PlanExecutor} and {@code ExecutorSession} alike. Here, not in the vision
 * module, because {@code replay} runs both without it. JDK image API only ({@code java.awt.image},
 * {@code javax.imageio}).
 */
package ai.skillroy.testjava.scenario.image;
