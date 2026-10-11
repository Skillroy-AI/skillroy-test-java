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

package ai.skillroy.testjava.agent.swing;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * What the module asks of the OS input path (phase7-contracts.md section 3.4): the real pointer and a screen grab.
 * {@link RobotInput} implements it with {@code java.awt.Robot}; tests hand in a fake through {@link RobotFactory} that
 * delivers or drops. Nothing else in the module names {@code java.awt.Robot} in a signature, so that a JVM started
 * without the agent option {@code robot} never loads it (section 13.2 item 11). Called on an agent worker, never the
 * EDT.
 */
interface RobotPort {

    /** Moves the real pointer to a screen point. */
    void mouseMove(int x, int y);

    /** Presses the buttons of an {@code InputEvent} button mask. */
    void mousePress(int buttons);

    /** Releases the buttons of an {@code InputEvent} button mask. */
    void mouseRelease(int buttons);

    /** Turns the wheel by one notch per unit (positive towards the user). */
    void mouseWheel(int notches);

    /** The pixels of a screen rectangle as the screen shows them (the glass). */
    BufferedImage createScreenCapture(Rectangle screen);

    /** Where the real pointer is now, or null when it cannot be read. */
    Point pointer();
}
